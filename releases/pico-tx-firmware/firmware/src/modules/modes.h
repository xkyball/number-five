#pragma once
#include "../base/api.h"
typedef struct {
    capture_t capture;
    uint32_t timeout_misses;
    uint64_t last_candidate_us;
} no5_ping_state_t;
typedef enum { NO5_END_STOP, NO5_END_FAULT, NO5_END_DISCONNECT } no5_end_reason_t;
bool scope_validate_config(const config_t *);
void scope_on_block(no5_base_t *, const no5_block_view_t *);
bool ping_validate_config(const config_t *);
void ping_begin_run(no5_ping_state_t *, const config_t *);
void ping_on_block(no5_base_t *, no5_ping_state_t *, const no5_block_view_t *);
void ping_try_emit(no5_base_t *, no5_ping_state_t *);
void ping_end_run(no5_ping_state_t *, no5_end_reason_t);
uint32_t ping_status_misses(const no5_ping_state_t *);
