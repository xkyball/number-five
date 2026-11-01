#include <string.h>
#include "modes.h"
#include "../app.h"
#include "transmit_hw.h"
#define MIN(a,b) ((a)<(b)?(a):(b))
#define MAX(a,b) ((a)>(b)?(a):(b))
bool ping_validate_config(const config_t *c) { return c->trigger==2 && c->signal==1 && c->count>=32 && c->count<=MAX_PING_SAMPLES &&
        c->pre<=MAX_PRE_SAMPLES && c->pre<c->count && c->threshold>0 && c->threshold<=4095 &&
        c->hysteresis>0 && c->hysteresis<=c->threshold && c->timeout>=1000; }
void ping_begin_run(no5_ping_state_t *state, const config_t *config) {
    memset(&state->capture,0,sizeof(state->capture));
    if(config->trigger==2) capture_configure(&state->capture,config->count,config->pre,config->threshold,config->hysteresis);
    state->timeout_misses=0; state->last_candidate_us=base_time_us();
}
void ping_on_block(no5_base_t *b, no5_ping_state_t *state, const no5_block_view_t *view) {
    uint32_t block=view->block; const uint16_t *s=view->samples;
    const config_t *config=base_config(b);
    const uint32_t block_samples=base_block_samples(b);
    const uint64_t first_index=(uint64_t)block*block_samples;
    const uint64_t first_us=view->epoch_us+first_index*config->period/1000;
    uint32_t marker_index=block_samples;
    // Marker mode deliberately starts a capture on GP16.  The previous analog
    // threshold is retained for normal (unmarked) captures only.
    bool marker_start=b->acq.marker_enabled && !state->capture.collecting && !state->capture.ready
        && base_take_tx_marker(b,first_us,block_samples,config->period,&marker_index);
    for(uint32_t i=0;i<block_samples;i++) {
        const uint64_t index=first_index+i; const uint16_t code=s[i]&4095;
        bool valid=!(s[i]&view->adc_error_mask);
        if(marker_start && i==marker_index) valid=valid && capture_begin_marker(&state->capture,code,index);
        else if(!b->acq.marker_enabled || state->capture.collecting) valid=valid && capture_sample(&state->capture,code,index);
        if(!valid) { state->capture.ready=state->capture.collecting=false; base_trip(b,ERR_ADC); break; }
        if((i&127)==127) base_background_service(b);
    }
    base_release_block(b,block);
    uint64_t now=view->epoch_us+(uint64_t)(block+1)*block_samples*config->period/1000;
    if(state->capture.collecting || state->capture.ready) state->last_candidate_us=now;
    else if(now-state->last_candidate_us>=config->timeout) { state->timeout_misses++; state->last_candidate_us=now; }
}
void ping_try_emit(no5_base_t *b, no5_ping_state_t *state) {
    const config_t *config=base_config(b);
    uint8_t *payload=base_payload(b);
    uint64_t epoch_us=base_epoch_us(b);

    // Default remains the legacy trigger-relative pretrigger index.  A valid GP16
    // edge replaces it with the physical-TX index inside this returned capture.
    const bool marker_triggered=state->capture.marker_triggered;
    const uint32_t count=state->capture.count;
    ping_t p={ base_next_ping(b),epoch_us+state->capture.trigger_index*config->period/1000,
        config->period,count,marker_triggered?0:(int32_t)config->pre,4095,0,
        marker_triggered?1u:(32u|(config->pre?8u:0u)) };
    if(!marker_triggered) {
        uint64_t first_index=state->capture.trigger_index-config->pre;
        uint64_t first_us=epoch_us+first_index*config->period/1000;
        uint32_t marker_index;
        if(base_take_tx_marker(b,first_us,count,config->period,&marker_index)) {
            p.zero=(int32_t)marker_index;
            p.flags|=1u;
        }
    }
    for(uint32_t i=0;i<count;i++) {
        uint16_t value=state->capture.output[i]; p.min=MIN(p.min,value); p.max=MAX(p.max,value);
        memcpy(payload+sizeof(p)+i*2,&value,2);
        if((i&127)==127) base_background_service(b);
    }
    if(p.min==0 || p.max==4095) p.flags|=2;
    memcpy(payload,&p,sizeof(p));
    if(!base_send_packet(b,PING,payload,sizeof(p)+count*2)) { base_count_drop(b); base_trip(b,ERR_USB); }
    no5_app_t *app=(no5_app_t *)b;
    transmit_hw_poll(app);
    if(p.flags&1u) {
#if NO5_CLIP_DIAGNOSTIC
        tx_link_ping(&app->clip.tx,p.id,p.time_us,config->period/1000+1);
#else
        tx_link_ping(&app->tx,p.id,p.time_us,config->period/1000+1);
#endif
    }
    state->capture.ready=false;
    state->capture.count=config->count;
}
void ping_end_run(no5_ping_state_t *state, no5_end_reason_t reason) {
    if(reason==NO5_END_STOP) state->capture.ready=state->capture.collecting=false;
}
uint32_t ping_status_misses(const no5_ping_state_t *state) {
    return state->timeout_misses+(uint32_t)state->capture.missed_candidates;
}
