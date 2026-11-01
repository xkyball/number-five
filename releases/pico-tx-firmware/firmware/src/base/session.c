#include <string.h>
#include "../app.h"
#include "bsp/board_api.h"
#include "../modules/transmit_hw.h"
#include "pico/stdlib.h"
#include "pico/bootrom.h"
#include "pico/unique_id.h"
#include "tusb.h"

uint8_t *base_payload(no5_base_t *b) { return b->wire.payload; }
const config_t *base_config(const no5_base_t *b) { return &b->session.config; }
uint64_t base_epoch_us(const no5_base_t *b) { return b->acq.epoch_us; }
uint64_t base_time_us(void) { return time_us_64(); }
void base_usb_service(void) { tud_task(); }
#if NO5_CLIP_DIAGNOSTIC
static void clip_background_service(void *context) {
    no5_app_t *app=context;
    // No RX dispatch or packet pumping here: CRC/COBS may own those buffers.
    if(!tud_cdc_connected()) transmit_hw_stop(app,TX_DISCONNECTED);
    else transmit_hw_poll(app);
}
#endif
void base_count_stream(no5_base_t *b) { b->session.blocks_total++; }
uint64_t base_next_ping(no5_base_t *b) { return ++b->session.pings_total; }
void base_count_drop(no5_base_t *b) { b->wire.dropped++; }
void no5_start_run(no5_app_t *app) {
    base_acquisition_prepare(&app->base);
    // All modes reset the old PING metrics, preserving the current STATUS semantics.
    ping_begin_run(&app->ping,&app->base.session.config);
    base_acquisition_launch(&app->base);
}
void no5_send_hello(no5_app_t *app) {
    no5_base_t *b=&app->base;
    hello_t h={ .minor=7,.caps=(1u<<6)|(1u<<31)|(1u<<30)|(1u<<1)|(1u<<2)|(1u<<3)|(1u<<5),.rate=500000,.count=MAX_PING_SAMPLES,.bits=12,.mv=3300 };
#if NO5_CLIP_DIAGNOSTIC
    h.caps|=(1u<<7)|(1u<<8)|(1u<<9)|(1u<<10)|(1u<<11)|(1u<<12)|(1u<<13); h.patch=13;
#endif
    char uid[17]; pico_get_unique_board_id_string(uid,sizeof(uid)); memcpy(h.serial,uid,16);
    base_send_packet(b,HELLO,&h,sizeof(h));
}

void no5_send_status(no5_app_t *app) {
    no5_base_t *b=&app->base;
    status_t s={ time_us_64(),b->session.config.trigger==2?b->session.pings_total:b->session.blocks_total,b->wire.dropped,ping_status_misses(&app->ping),b->acq.overruns,b->wire.usb_errors,b->acq.fault,0 };
    base_send_packet(b,STATUS,&s,sizeof(s));
}

void no5_service_rx(no5_app_t *app) {
    no5_base_t *b=&app->base;
    while(tud_cdc_available()) {
        uint8_t rx_byte; if(tud_cdc_read(&rx_byte,1)!=1) break;
        if(rx_byte) { if(b->wire.rx_len<sizeof(b->wire.rx) && !b->wire.rx_discard) b->wire.rx[b->wire.rx_len++]=rx_byte; else b->wire.rx_discard=true; continue; }
        size_t n=b->wire.rx_discard?0:base_cobs_decode(b,b->wire.rx,b->wire.rx_len,b->wire.decoded); b->wire.rx_len=0; b->wire.rx_discard=false;
        if(n<20) continue;
        uint64_t rx_us=time_us_64();
        header_t h; memcpy(&h,b->wire.decoded,16); uint32_t crc; memcpy(&crc,b->wire.decoded+n-4,4);
        if(h.magic[0]!='N'||h.magic[1]!='5'||h.version!=1||h.length!=n-20||crc!=base_crc32(b,b->wire.decoded,n-4)) continue;
        if(h.type==HELLO_ACK) no5_send_hello(app);
        else if(h.type==STOP) { transmit_hw_stop(app,TX_STOPPED); base_stop_adc(b); ping_end_run(&app->ping,NO5_END_STOP); b->acq.consumed=b->acq.produced; no5_send_status(app); }
        else if((h.type>=TX_CONFIG && h.type<=TX_ABORT) || h.type==TX_CLIP_CONFIG || h.type==TX_DEPTH_CONFIG) transmit_hw_command(app,h.type,b->wire.decoded+16,h.length);
        else if(h.type==TIME_REQ && h.length==4) {
            sync_t reply; memcpy(&reply.token,b->wire.decoded+16,4); reply.rx_us=rx_us; reply.tx_us=time_us_64();
            base_send_packet(b,TIME_RESP,&reply,sizeof(reply));
        }
        else if(h.type==CONFIG && h.length==sizeof(config_t)) {
            config_t c; memcpy(&c,b->wire.decoded+16,sizeof(c));
            bool scope_mode=scope_validate_config(&c);
            bool ping_mode=ping_validate_config(&c);
            bool marker=(c.reserved&1u)!=0;
            bool valid=!b->acq.running && (scope_mode||ping_mode) && (c.period==2000||c.period==4000||c.period==8000) && !(c.reserved&~1u);
            if(valid) { b->acq.marker_enabled=marker; b->acq.marker_pending=false; }
            if(valid) { b->session.config=c; b->session.configured=true; }
            ack_t a={c.revision,valid?0:1,b->session.config.period,b->session.config.count}; base_send_packet(b,CONFIG_ACK,&a,sizeof(a));
        }
        else if(h.type==START && b->session.configured && !b->acq.running && h.length==0) { no5_start_run(app); no5_send_status(app); }
        else if(h.type==BOOTSEL && !b->acq.running && h.length==0) { sleep_ms(20); reset_usb_boot(0,0); }
    }
}

void no5_app_init(no5_app_t *app) {
    no5_base_t *b=&app->base;
    b->session.config=(config_t){ .period=4000,.count=COUNT };
    board_init(); base_crc_init(b); base_acquisition_init(b);
    transmit_hw_init(app);
#if NO5_CLIP_DIAGNOSTIC
    b->background_service=clip_background_service;
#endif

    tud_init(0); app->last_status=0; app->last_hello=0; app->fault_reported=false;
}
void no5_app_step(no5_app_t *app) {
    no5_base_t *b=&app->base;

        tud_task();
        if(!tud_cdc_connected()) { transmit_hw_stop(app,TX_DISCONNECTED); if(b->acq.running||b->session.configured) base_stop_adc(b); b->session.configured=false; b->acq.consumed=b->acq.produced; b->wire.rx_len=0; b->wire.rx_discard=false; b->wire.tx_length=b->wire.tx_offset=0; b->wire.control_count=b->wire.control_head=b->wire.control_tail=0; sleep_ms(1); return; }
        base_pump_tx(b);
        no5_service_rx(app);
        transmit_hw_poll(app);
        uint64_t now=time_us_64();
        if(!b->session.configured && now-app->last_hello>=1000000) { no5_send_hello(app); app->last_hello=now; }
        if(b->acq.produced>b->acq.consumed) {
            no5_block_view_t view=base_block_view(b);
            if(b->session.config.trigger==2) ping_on_block(b,&app->ping,&view);
            else if(!base_tx_pending(b) && !b->wire.control_count) scope_on_block(b,&view);
        }
        if(b->acq.running && b->session.config.trigger==2 && app->ping.capture.ready && !base_tx_pending(b) && !b->wire.control_count) ping_try_emit(b,&app->ping);
        transmit_hw_poll(app);
        if(b->acq.fault && !app->fault_reported) { base_stop_adc(b); no5_send_status(app); app->fault_reported=true; }
        if(b->acq.running) app->fault_reported=false;
        if(now-app->last_status>=1000000) { no5_send_status(app); app->last_status=now; }
        base_pump_tx(b);
        if(!b->acq.running && b->acq.produced==b->acq.consumed) sleep_ms(1);
    }
