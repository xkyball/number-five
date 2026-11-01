#include <math.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>

#include "bsp/board_api.h"
#include "hardware/adc.h"
#include "hardware/dma.h"
#include "hardware/gpio.h"
#include "pico/stdlib.h"
#include "pico/unique_id.h"
#include "tusb.h"

#define NO5_MAGIC0 0x4e
#define NO5_MAGIC1 0x35
#define NO5_PROTOCOL_VERSION 1
#define NO5_MAX_SAMPLES 16384
#define NO5_RX_FRAME_MAX 512
#define NO5_TX_RAW_MAX (16 + 36 + NO5_MAX_SAMPLES * 2 + 4)
#define NO5_TX_ENC_MAX (NO5_TX_RAW_MAX + NO5_TX_RAW_MAX / 254 + 8)
#define ADC_GPIO 26
#define ADC_INPUT 0
#define EXTERNAL_TRIGGER_GPIO 15

enum msg_type {
    MSG_HELLO = 0x01, MSG_HELLO_ACK = 0x02,
    MSG_CONFIG_SET = 0x10, MSG_CONFIG_ACK = 0x11,
    MSG_START = 0x12, MSG_STOP = 0x13,
    MSG_PING_FRAME = 0x20, MSG_STATUS = 0x21,
    MSG_TIME_SYNC_REQ = 0x30, MSG_TIME_SYNC_RESP = 0x31,
    MSG_ERROR = 0x7f
};

enum { TRIGGER_EXTERNAL_GPIO = 1, TRIGGER_ANALOG_THRESHOLD = 2 };
enum { SIGNAL_ENVELOPE = 1, SIGNAL_CARRIER = 2 };
enum { PING_TRIGGER_VALID = 1u << 0, PING_ADC_CLIPPED = 1u << 1, PING_ADC_OVERRUN = 1u << 2,
       PING_PRETRIGGER_VALID = 1u << 3, PING_CAPTURE_TIMEOUT = 1u << 4, PING_TRIGGER_AMBIGUOUS = 1u << 5 };

#pragma pack(push, 1)
typedef struct { uint8_t magic[2], version, type; uint32_t seq, payload_len, flags; } packet_header_t;
typedef struct { uint8_t fw_major, fw_minor, fw_patch, reserved0; uint32_t capabilities, max_sample_rate_hz, max_samples; uint16_t adc_bits, adc_fullscale_mv; char device_serial[16]; } hello_t;
typedef struct { uint32_t revision, sample_period_ns, sample_count; uint16_t pretrigger_samples; uint8_t trigger_mode, signal_mode; uint16_t trigger_threshold, trigger_hysteresis; uint32_t capture_timeout_us, reserved0; } config_set_t;
typedef struct { uint32_t revision, status, actual_sample_period_ns, actual_sample_count; } config_ack_t;
typedef struct { uint64_t ping_id, trigger_device_time_us; uint32_t sample_period_ns, sample_count; int32_t tx_zero_sample_index; uint16_t adc_min, adc_max; uint32_t ping_flags; } ping_prefix_t;
typedef struct { uint64_t uptime_us, pings_total; uint32_t dropped_frames, trigger_misses, adc_overruns, usb_errors, last_error; int32_t pico_temperature_mc; } status_t;
typedef struct { uint32_t token; } time_sync_req_t;
typedef struct { uint32_t token; uint64_t pico_rx_us, pico_tx_us; } time_sync_resp_t;
#pragma pack(pop)

static uint8_t rx_encoded[NO5_RX_FRAME_MAX];
static size_t rx_encoded_len = 0;
static uint8_t rx_decoded[NO5_RX_FRAME_MAX];
static uint8_t tx_raw[NO5_TX_RAW_MAX];
static uint8_t tx_encoded[NO5_TX_ENC_MAX];
static uint16_t sample_buf[NO5_MAX_SAMPLES];
static uint32_t tx_seq = 0;
static uint64_t ping_id = 0;
static bool configured = false;
static bool acquiring = false;
static uint dma_chan;

static config_set_t cfg = {
    .revision = 0, .sample_period_ns = 4000, .sample_count = 10000, .pretrigger_samples = 0,
    .trigger_mode = TRIGGER_ANALOG_THRESHOLD, .signal_mode = SIGNAL_ENVELOPE,
    .trigger_threshold = 2500, .trigger_hysteresis = 100, .capture_timeout_us = 100000, .reserved0 = 0
};

static uint32_t crc32_ieee(const uint8_t *data, size_t len) {
    uint32_t crc = 0xffffffffu;
    for (size_t i = 0; i < len; i++) {
        crc ^= data[i];
        for (int bit = 0; bit < 8; bit++) crc = (crc >> 1) ^ (0xedb88320u & (-(int32_t)(crc & 1u)));
    }
    return crc ^ 0xffffffffu;
}

static size_t cobs_encode(const uint8_t *in, size_t len, uint8_t *out, size_t cap) {
    if (cap < 1) return 0;
    size_t ri = 0, wi = 1, code_index = 0;
    uint8_t code = 1;
    while (ri < len) {
        if (in[ri] == 0) {
            if (code_index >= cap) return 0;
            out[code_index] = code;
            code_index = wi++;
            code = 1;
            ri++;
        } else {
            if (wi >= cap) return 0;
            out[wi++] = in[ri++];
            code++;
            if (code == 0xff) {
                out[code_index] = code;
                code_index = wi++;
                code = 1;
                if (wi > cap) return 0;
            }
        }
    }
    out[code_index] = code;
    return wi;
}

static size_t cobs_decode(const uint8_t *in, size_t len, uint8_t *out, size_t cap) {
    size_t ri = 0, wi = 0;
    while (ri < len) {
        uint8_t code = in[ri++];
        if (code == 0) return 0;
        size_t count = (size_t)code - 1;
        if (ri + count > len || wi + count > cap) return 0;
        memcpy(out + wi, in + ri, count);
        wi += count; ri += count;
        if (code != 0xff && ri < len) {
            if (wi >= cap) return 0;
            out[wi++] = 0;
        }
    }
    return wi;
}

static bool cdc_write_all(const uint8_t *data, size_t len) {
    absolute_time_t deadline = make_timeout_time_ms(1000);
    size_t off = 0;
    while (off < len) {
        tud_task();
        if (!tud_cdc_connected()) return false;
        uint32_t avail = tud_cdc_write_available();
        if (avail) {
            uint32_t n = (uint32_t)(len - off);
            if (n > avail) n = avail;
            off += tud_cdc_write(data + off, n);
            tud_cdc_write_flush();
        }
        if (absolute_time_diff_us(get_absolute_time(), deadline) < 0) return false;
    }
    tud_cdc_write_flush();
    return true;
}

static bool send_packet(uint8_t type, const void *payload, uint32_t payload_len) {
    if (sizeof(packet_header_t) + payload_len + 4 > sizeof(tx_raw)) return false;
    packet_header_t h = { .magic = {NO5_MAGIC0, NO5_MAGIC1}, .version = NO5_PROTOCOL_VERSION,
                          .type = type, .seq = tx_seq++, .payload_len = payload_len, .flags = 0 };
    memcpy(tx_raw, &h, sizeof(h));
    if (payload_len) memcpy(tx_raw + sizeof(h), payload, payload_len);
    uint32_t crc = crc32_ieee(tx_raw, sizeof(h) + payload_len);
    memcpy(tx_raw + sizeof(h) + payload_len, &crc, 4);
    size_t enc = cobs_encode(tx_raw, sizeof(h) + payload_len + 4, tx_encoded, sizeof(tx_encoded) - 1);
    if (!enc) return false;
    tx_encoded[enc++] = 0;
    return cdc_write_all(tx_encoded, enc);
}

static void send_hello(void) {
    hello_t h = {0};
    h.fw_major = 0; h.fw_minor = 2; h.fw_patch = 0;
    h.capabilities = (1u<<0) | (1u<<1) | (1u<<3) | (1u<<5);
    h.max_sample_rate_hz = 500000;
    h.max_samples = NO5_MAX_SAMPLES;
    h.adc_bits = 12; h.adc_fullscale_mv = 3300;
    char uid[2 * PICO_UNIQUE_BOARD_ID_SIZE_BYTES + 1];
    pico_get_unique_board_id_string(uid, sizeof(uid));
    memcpy(h.device_serial, uid, sizeof(h.device_serial));
    send_packet(MSG_HELLO, &h, sizeof(h));
}

static void configure_adc_rate(uint32_t sample_period_ns) {
    float cycles = (float)sample_period_ns * 48.0f / 1000.0f; // 48 MHz => 0.048 cycles/ns
    float div = cycles > 96.0f ? cycles - 1.0f : 0.0f;
    adc_set_clkdiv(div);
}

static bool capture_adc(uint32_t n) {
    adc_fifo_drain();
    adc_fifo_setup(true, true, 1, false, false);
    dma_channel_config dc = dma_channel_get_default_config(dma_chan);
    channel_config_set_transfer_data_size(&dc, DMA_SIZE_16);
    channel_config_set_read_increment(&dc, false);
    channel_config_set_write_increment(&dc, true);
    channel_config_set_dreq(&dc, DREQ_ADC);
    dma_channel_configure(dma_chan, &dc, sample_buf, &adc_hw->fifo, n, false);
    dma_start_channel_mask(1u << dma_chan);
    adc_run(true);
    dma_channel_wait_for_finish_blocking(dma_chan);
    adc_run(false);
    adc_fifo_drain();
    return true;
}

static void service_rx(void);

static bool wait_for_trigger(uint64_t *trigger_us) {
#ifdef NO5_SYNTHETIC_TEST
    static uint64_t next_us = 0;
    uint64_t now = time_us_64();
    if (!next_us) next_us = now;
    while (acquiring && now < next_us) {
        tud_task(); service_rx(); sleep_us(200); now = time_us_64();
    }
    next_us = now + 50000; // 20 Hz bench stream
    *trigger_us = now;
    return acquiring;
#else
    if (cfg.trigger_mode == TRIGGER_EXTERNAL_GPIO) {
        bool last = gpio_get(EXTERNAL_TRIGGER_GPIO);
        absolute_time_t deadline = make_timeout_time_us(cfg.capture_timeout_us);
        while (acquiring) {
            tud_task(); service_rx();
            bool cur = gpio_get(EXTERNAL_TRIGGER_GPIO);
            if (!last && cur) { *trigger_us = time_us_64(); return true; }
            last = cur;
            if (absolute_time_diff_us(get_absolute_time(), deadline) < 0) return false;
        }
        return false;
    }
    adc_fifo_setup(false, false, 0, false, false);
    absolute_time_t deadline = make_timeout_time_us(cfg.capture_timeout_us);
    while (acquiring) {
        tud_task(); service_rx();
        uint16_t v = adc_read();
        if (v >= cfg.trigger_threshold) { *trigger_us = time_us_64(); return true; }
        if (absolute_time_diff_us(get_absolute_time(), deadline) < 0) return false;
    }
    return false;
#endif
}

static void generate_synthetic(void) {
#ifdef NO5_SYNTHETIC_TEST
    float meters_per_sample = (float)cfg.sample_period_ns * 1e-9f * 1500.0f / 2.0f;
    float bottom = 11.5f + 1.5f * sinf((float)time_us_64() / 7000000.0f);
    for (uint32_t i = 0; i < cfg.sample_count; i++) {
        float d = i * meters_per_sample;
        float b = 450.0f + 42000.0f * expf(-0.5f * powf((d-bottom)/0.22f, 2.0f));
        float fish = 9000.0f * expf(-0.5f * powf((d-6.2f)/0.10f, 2.0f));
        uint32_t v = (uint32_t)(b + fish + (i * 37u + (uint32_t)ping_id * 17u) % 180u);
        if (v > 4095) v = 4095;
        sample_buf[i] = (uint16_t)v;
    }
#endif
}

static void capture_and_send_ping(void) {
    uint64_t trigger_us = 0;
    bool got_trigger = wait_for_trigger(&trigger_us);
    if (!acquiring) return;
    if (!got_trigger) return;
#ifdef NO5_SYNTHETIC_TEST
    generate_synthetic();
#else
    capture_adc(cfg.sample_count);
#endif
    uint16_t amin = 0xffff, amax = 0;
    for (uint32_t i=0; i<cfg.sample_count; i++) { if (sample_buf[i] < amin) amin=sample_buf[i]; if (sample_buf[i] > amax) amax=sample_buf[i]; }
    ping_prefix_t p = { .ping_id = ++ping_id, .trigger_device_time_us = trigger_us,
        .sample_period_ns = cfg.sample_period_ns, .sample_count = cfg.sample_count,
        .tx_zero_sample_index = 0, .adc_min = amin, .adc_max = amax,
        .ping_flags = PING_TRIGGER_VALID | (amax >= 4095 ? PING_ADC_CLIPPED : 0) };
    uint32_t payload_len = sizeof(p) + cfg.sample_count * 2;
    memcpy(tx_raw + sizeof(packet_header_t), &p, sizeof(p));
    memcpy(tx_raw + sizeof(packet_header_t) + sizeof(p), sample_buf, cfg.sample_count * 2);
    // send_packet cannot use tx_raw payload in-place because it prepends the header into tx_raw.
    static uint8_t ping_payload[sizeof(ping_prefix_t) + NO5_MAX_SAMPLES * 2];
    memcpy(ping_payload, &p, sizeof(p));
    memcpy(ping_payload + sizeof(p), sample_buf, cfg.sample_count * 2);
    send_packet(MSG_PING_FRAME, ping_payload, payload_len);
}

static void handle_packet(const uint8_t *raw, size_t len, uint64_t rx_us) {
    if (len < sizeof(packet_header_t) + 4) return;
    packet_header_t h; memcpy(&h, raw, sizeof(h));
    if (h.magic[0] != NO5_MAGIC0 || h.magic[1] != NO5_MAGIC1 || h.version != NO5_PROTOCOL_VERSION) return;
    if (len != sizeof(h) + h.payload_len + 4) return;
    uint32_t got_crc; memcpy(&got_crc, raw + len - 4, 4);
    if (crc32_ieee(raw, len - 4) != got_crc) return;
    const uint8_t *payload = raw + sizeof(h);
    if (h.type == MSG_CONFIG_SET && h.payload_len == sizeof(config_set_t)) {
        config_set_t incoming; memcpy(&incoming, payload, sizeof(incoming));
        uint32_t status = 0;
        if (incoming.sample_count == 0 || incoming.sample_count > NO5_MAX_SAMPLES || incoming.sample_period_ns < 2000) status = 1;
        if (!status) {
            cfg = incoming;
            configure_adc_rate(cfg.sample_period_ns);
            configured = true;
        }
        config_ack_t ack = {incoming.revision, status, cfg.sample_period_ns, cfg.sample_count};
        send_packet(MSG_CONFIG_ACK, &ack, sizeof(ack));
    } else if (h.type == MSG_TIME_SYNC_REQ && h.payload_len == sizeof(time_sync_req_t)) {
        time_sync_req_t req; memcpy(&req, payload, sizeof(req));
        time_sync_resp_t resp = {.token=req.token, .pico_rx_us=rx_us, .pico_tx_us=time_us_64()};
        send_packet(MSG_TIME_SYNC_RESP, &resp, sizeof(resp));
    } else if (h.type == MSG_START) {
        if (configured) acquiring = true;
    } else if (h.type == MSG_STOP) {
        acquiring = false;
    }
}

static void service_rx(void) {
    while (tud_cdc_available()) {
        uint8_t buf[64];
        uint32_t n = tud_cdc_read(buf, sizeof(buf));
        for (uint32_t i=0; i<n; i++) {
            uint8_t b = buf[i];
            if (b == 0) {
                if (rx_encoded_len) {
                    uint64_t rx_us = time_us_64();
                    size_t decoded = cobs_decode(rx_encoded, rx_encoded_len, rx_decoded, sizeof(rx_decoded));
                    if (decoded) handle_packet(rx_decoded, decoded, rx_us);
                }
                rx_encoded_len = 0;
            } else if (rx_encoded_len < sizeof(rx_encoded)) {
                rx_encoded[rx_encoded_len++] = b;
            } else {
                rx_encoded_len = 0;
            }
        }
    }
}

int main(void) {
    board_init();
    tusb_init();
    adc_init();
    adc_gpio_init(ADC_GPIO);
    adc_select_input(ADC_INPUT);
    gpio_init(EXTERNAL_TRIGGER_GPIO);
    gpio_set_dir(EXTERNAL_TRIGGER_GPIO, GPIO_IN);
    gpio_pull_down(EXTERNAL_TRIGGER_GPIO);
    dma_chan = dma_claim_unused_channel(true);
    configure_adc_rate(cfg.sample_period_ns);

    uint64_t last_hello = 0, last_status = 0;
    while (true) {
        tud_task();
        service_rx();
        uint64_t now = time_us_64();
        if (!configured && tud_cdc_connected() && now - last_hello >= 1000000) { send_hello(); last_hello = now; }
        if (tud_cdc_connected() && now - last_status >= 1000000) {
            status_t st = {.uptime_us=now, .pings_total=ping_id, .dropped_frames=0, .trigger_misses=0,
                           .adc_overruns=0, .usb_errors=0, .last_error=0, .pico_temperature_mc=0};
            send_packet(MSG_STATUS, &st, sizeof(st)); last_status = now;
        }
        if (acquiring) capture_and_send_ping();
        else sleep_ms(1);
    }
}
