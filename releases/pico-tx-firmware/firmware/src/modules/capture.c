#include "capture.h"

bool capture_configure(capture_t *s, uint32_t count, uint32_t pre,
                                     uint32_t threshold, uint32_t hysteresis) {
    if (count < 32 || count > MAX_PING_SAMPLES || pre > MAX_PRE_SAMPLES ||
        pre >= count || threshold > 4095 || threshold == 0 ||
        hysteresis == 0 || hysteresis > threshold) return false;
    memset(s, 0, sizeof(*s));
    s->count=count; s->pre=pre; s->threshold=threshold; s->hysteresis=hysteresis;
    return true;
}

bool capture_sample(capture_t *s, uint16_t code, uint64_t index) {
    if (code > 4095 || index != s->next_index) return false;
    s->next_index++;
    if (s->collecting) {
        s->output[s->captured++]=code;
        if (s->captured == s->count) { s->collecting=false; s->ready=true; }
    } else {
        if (code <= s->threshold-s->hysteresis) s->armed=true;
        bool crossing=s->armed && s->previous_valid && s->previous<s->threshold && code>=s->threshold;
        if (crossing) {
            s->armed=false;
            if (s->ready) s->missed_candidates++;
            else if (s->filled>=s->pre) {
                uint32_t first=(s->head+MAX_PRE_SAMPLES-s->pre)%MAX_PRE_SAMPLES;
                for (uint32_t i=0;i<s->pre;i++) s->output[i]=s->pre_samples[(first+i)%MAX_PRE_SAMPLES];
                s->output[s->pre]=code; s->captured=s->pre+1;
                s->trigger_index=index;
                s->collecting=s->captured<s->count; s->ready=!s->collecting;
            }
        }
    }
    s->pre_samples[s->head]=code; s->head=(s->head+1)%MAX_PRE_SAMPLES;
    if (s->filled<MAX_PRE_SAMPLES) s->filled++;
    s->previous=code; s->previous_valid=true;
    return true;
}

// GP16 is the physical transmit reference.  In marker mode an acquisition
// starts on that edge and contains only post-transmit samples.
bool capture_begin_marker(capture_t *s, uint16_t code, uint64_t index) {
    if (code > 4095 || s->collecting || s->ready || !s->count) return false;
    s->trigger_index=index; s->next_index=index+1; s->output[0]=code;
    s->captured=1; s->marker_triggered=true; s->armed=false;
    s->collecting=s->count>1; s->ready=!s->collecting;
    return true;
}
