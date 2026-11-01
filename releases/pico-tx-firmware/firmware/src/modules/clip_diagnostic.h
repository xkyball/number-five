#pragma once
#include "transmit.h"

// Deliberately separate from the normal five-Hz TX policy. One FIRE consumes
// a configured run; continuous mode still requires a renewable lease and FIRE.
enum { CLIP_MODE=3, CLIP_INTERLEAVED=4, CLIP_DONE=7, CLIP_REFERENCE_LOST=11,
       CLIP_COLLISION=12, CLIP_LATE=13 };
#pragma pack(push,1)
typedef struct { tx_config_t burst; uint32_t burst_count; } clip_config_t;
// Additive command 0x47. Geometry changes only at a safe capture boundary.
typedef struct { uint32_t request, revision, count, spacing_us, floor_us, valid_us, max_own_per_native; } clip_depth_config_t;
#pragma pack(pop)
_Static_assert(sizeof(clip_config_t)==28,"Clip CONFIG wire layout");
typedef struct {
    tx_state_t tx;
    uint32_t count, remaining, native_stable;
    uint64_t seen_marker, native_last, native_period, reference_after;
    uint32_t native_intervals[3], native_interval_count, native_interval_next;
    uint64_t requested_id, next_due;
    uint64_t deadline;
    uint32_t duration_s, slot;
    uint64_t starts;
    bool continuous;
    bool configured, pending, waiting_reference, spent, interleaved, quiet_window;
    bool reference_suspect, dense_window, seven_slots;
    bool depth_auto;
    clip_depth_config_t depth_pending, depth;
    uint64_t depth_until;
    uint32_t cycle, capture_count, last_interval, plan_revision;
} clip_state_t;
void clip_init(clip_state_t *);
uint32_t clip_configure(clip_state_t *,const clip_config_t *);
uint32_t clip_arm(clip_state_t *,const tx_arm_t *,uint64_t,bool);
uint32_t clip_fire(clip_state_t *,uint64_t,uint64_t,bool);
void clip_poll(clip_state_t *,uint64_t,bool,bool,uint64_t);
void clip_abort(clip_state_t *,uint32_t);
uint32_t clip_depth_configure(clip_state_t *,const clip_depth_config_t *,uint64_t);
