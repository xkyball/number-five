#pragma once
#include <stdbool.h>
#include <stdint.h>
#include <string.h>

#define MAX_PING_SAMPLES 16384u
#define MAX_PRE_SAMPLES 8192u

// A threshold crossing is a candidate reference, never a verified sonar TX.
typedef struct {
    uint16_t pre_samples[MAX_PRE_SAMPLES], output[MAX_PING_SAMPLES];
    uint32_t count, pre, threshold, hysteresis, head, filled, captured;
    uint64_t next_index, trigger_index, missed_candidates;
    uint16_t previous;
    bool previous_valid, armed, collecting, ready, marker_triggered;
} capture_t;

bool capture_configure(capture_t *, uint32_t, uint32_t, uint32_t, uint32_t);
bool capture_sample(capture_t *, uint16_t, uint64_t);
bool capture_begin_marker(capture_t *, uint16_t, uint64_t);
