#pragma once
#include <stddef.h>
#include <stdbool.h>
#include "../wire_types.h"

typedef struct { uint8_t type, length; uint32_t flags; uint8_t payload[40]; } control_t;
typedef struct {
    uint8_t payload[PAYLOAD_MAX], raw[RAW_MAX], encoded[ENCODED_MAX];
    control_t control_queue[8];
    unsigned control_head, control_tail, control_count;
    size_t tx_length, tx_offset;
    uint64_t tx_started;
    bool tx_timeout_reported;
    uint8_t rx[512], decoded[512];
    uint32_t crc_table[256], sequence, usb_errors, dropped;
    size_t rx_len;
    bool rx_discard;
} no5_wire_state_t;
typedef struct {
    uint16_t samples[RING*COUNT];
    uint32_t block_samples, ring_blocks;
    volatile bool running;
    volatile uint32_t produced, consumed, fault, overruns;
    uint32_t channel_block[2];
    unsigned channels[2];
    uint64_t epoch_us;
    // One most-recent GP16 rising edge, consumed only by the active mode.
    volatile bool marker_enabled, marker_pending;
    volatile uint64_t marker_us;
    volatile uint64_t marker_observed_us;
    volatile uint32_t marker_rejected;
} no5_acquisition_state_t;
typedef struct {
    config_t config;
    bool configured;
    uint64_t blocks_total, pings_total;
} no5_session_state_t;
typedef struct no5_base {
    no5_wire_state_t wire;
    no5_acquisition_state_t acq;
    no5_session_state_t session;
    void (*safety_abort)(void *);
    void *safety_context;
    // Main-thread checkpoints only. This callback may enqueue small control
    // events, but must never encode/pump a packet or reuse the raw buffers.
    void (*background_service)(void *);
} no5_base_t;
