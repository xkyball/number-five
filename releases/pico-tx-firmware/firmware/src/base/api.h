#pragma once
#include <stddef.h>
#include <stdbool.h>
#include "../wire_types.h"
typedef struct no5_base no5_base_t;
typedef struct {
    const uint16_t *samples;
    uint32_t block, period;
    uint64_t epoch_us;
    uint16_t adc_error_mask;
} no5_block_view_t;
void base_trip(no5_base_t *, uint32_t);
bool base_send_packet(no5_base_t *, uint8_t, const void *, uint32_t);
uint8_t *base_payload(no5_base_t *);
const config_t *base_config(const no5_base_t *);
void base_release_block(no5_base_t *, uint32_t);
void base_usb_service(void);
void base_background_service(no5_base_t *);
uint64_t base_time_us(void);
void base_count_stream(no5_base_t *);
uint64_t base_next_ping(no5_base_t *);
void base_count_drop(no5_base_t *);
uint64_t base_epoch_us(const no5_base_t *);
uint32_t base_block_samples(const no5_base_t *);
// Returns a GP16 edge as an index in [start_us, start_us + count * period_ns).
// The edge is consumed exactly once; stale edges are counted and discarded.
bool base_take_tx_marker(no5_base_t *, uint64_t start_us, uint32_t count,
                         uint32_t period_ns, uint32_t *index);
