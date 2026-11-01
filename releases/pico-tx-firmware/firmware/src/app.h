#pragma once
#include "base/internal.h"
#include "modules/modes.h"
#include "modules/transmit.h"
#if NO5_CLIP_DIAGNOSTIC
#include "modules/clip_diagnostic.h"
#endif
typedef struct no5_app {
    no5_base_t base;
    no5_ping_state_t ping;
    tx_state_t tx;
#if NO5_CLIP_DIAGNOSTIC
    clip_state_t clip;
#endif
    uint64_t last_status, last_hello;
    bool fault_reported;
} no5_app_t;
void no5_app_init(no5_app_t *);
void no5_app_step(no5_app_t *);
void no5_start_run(no5_app_t *);
void no5_service_rx(no5_app_t *);
void no5_send_status(no5_app_t *);
void no5_send_hello(no5_app_t *);
