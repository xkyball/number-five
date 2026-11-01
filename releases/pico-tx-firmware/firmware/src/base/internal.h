#pragma once
#include "state.h"
#include "api.h"
void base_crc_init(no5_base_t *);
uint32_t base_crc32(no5_base_t *, const uint8_t *, size_t);
size_t base_cobs_encode(no5_base_t *, const uint8_t *, size_t, uint8_t *);
size_t base_cobs_decode(no5_base_t *, const uint8_t *, size_t, uint8_t *);
void base_prepare_packet(no5_base_t *, uint8_t, const void *, uint32_t, uint32_t);
bool base_tx_pending(no5_base_t *);
void base_pump_tx(no5_base_t *);
void base_dma_complete(no5_base_t *);
void base_stop_adc(no5_base_t *);
void base_acquisition_init(no5_base_t *);
void base_acquisition_prepare(no5_base_t *);
void base_acquisition_launch(no5_base_t *);
no5_block_view_t base_block_view(no5_base_t *);
