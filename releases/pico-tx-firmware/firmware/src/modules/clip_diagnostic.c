#include <string.h>
#include "clip_diagnostic.h"

#define NATIVE_MIN_US 350000u
#define NATIVE_MAX_US 430000u
#define NATIVE_JITTER_US 2000u
#define OFFSET_US 30000u
#define GUARD_US 20000u
#define LATE_US 1000u
#define DENSE_LATE_US 4000u

static uint32_t slots(const clip_state_t *s) { return s->depth_auto?s->depth_pending.max_own_per_native:s->seven_slots?7u:s->dense_window?6u:s->quiet_window?5u:4u; }
static uint32_t spacing(const clip_state_t *s) { return s->seven_slots?27000u:33000u; }
static void reference_diagnostic(clip_state_t *,uint32_t,uint64_t);
static void skip_dense_slot(clip_state_t *s,uint64_t now) {
    reference_diagnostic(s,10,now);
    s->slot++;
    if(s->depth_auto) s->next_due+=s->last_interval;
    else s->next_due=s->native_last+174000u+(uint64_t)s->slot*spacing(s);
}

uint32_t clip_depth_configure(clip_state_t *s,const clip_depth_config_t *c,uint64_t now) {
    if(!s->configured || !s->interleaved || !s->seven_slots) return TX_INVALID;
    if(!c->revision || c->revision<=s->depth_pending.revision || c->revision<=s->depth.revision) return TX_DUPLICATE;
    if(s->continuous && (c->floor_us<24300u || c->max_own_per_native>8u)) return TX_INVALID;
    if(s->tx.armed && s->depth_auto && (c->floor_us!=s->depth_pending.floor_us ||
        c->max_own_per_native!=s->depth_pending.max_own_per_native)) return TX_BUSY;
    if(c->count<2048 || c->count>16384 || c->count%256 || c->floor_us<6000 || c->floor_us>27000 ||
       c->spacing_us<c->floor_us || c->spacing_us<c->count*2u+3000u || c->spacing_us>100000 ||
       c->valid_us<100000 || c->valid_us>1000000 || !c->max_own_per_native || c->max_own_per_native>64) return TX_INVALID;
    s->depth_pending=*c; s->depth_until=now+c->valid_us; s->depth_auto=true;
    return TX_OK;
}
static void depth_geometry(clip_state_t *s,uint64_t now) {
    if(!s->tx.active && s->depth_pending.revision>s->depth.revision) s->depth=s->depth_pending;
    bool search=now>=s->depth_until || (!(s->cycle&1u) && s->slot==0);
    s->capture_count=search?16384u:s->depth.count;
    s->plan_revision=s->depth.revision;
    s->last_interval=s->capture_count*2u+3000u;
    uint32_t floor=now>=s->depth_until?27000u:s->depth.floor_us;
    if(s->last_interval<floor) s->last_interval=floor;
    if(!search && s->last_interval<s->depth.spacing_us) s->last_interval=s->depth.spacing_us;
}

static uint64_t distance(uint64_t a,uint64_t b) { return a>b?a-b:b-a; }
static void accept_interval(clip_state_t *s,uint32_t dt) {
    // Adjacent intervals share a timestamp error with opposite signs. Comparing
    // each against its immediate predecessor doubled the measured jitter and
    // falsely tripped on 384839 -> 387068us. Keep the same 2ms envelope around
    // the median of three measured intervals. The clock origin is still the
    // actual edge; no synthetic marker, wider guard or renewed lease is used.
    s->native_intervals[s->native_interval_next]=dt;
    s->native_interval_next=(s->native_interval_next+1u)%3u;
    if(s->native_interval_count<3u) s->native_interval_count++;
    uint32_t a=s->native_intervals[0],b=s->native_intervals[1],c=s->native_intervals[2];
    if(s->native_interval_count<3u) { s->native_period=dt; return; }
    s->native_period=a>b?(b>c?b:(a>c?c:a)):(a>c?a:(b>c?c:b));
}
static void reference_diagnostic(clip_state_t *s,uint32_t detail,uint64_t observed) {
    if((s->pending || detail==11) && s->tx.event) {
        tx_event_t e={8,detail,observed,s->native_last,s->native_period,
            ((uint64_t)s->native_stable<<32)|s->slot};
        s->tx.event(s->tx.context,&e);
    }
}
static void reference_fault(clip_state_t *s,uint32_t detail,uint64_t observed) {
    reference_diagnostic(s,detail,observed);
    clip_abort(s,CLIP_REFERENCE_LOST);
}
static bool fresh(const clip_state_t *s,uint64_t now) {
    return s->native_stable>=3 && s->native_period && now>=s->native_last &&
        now-s->native_last<=NATIVE_MAX_US;
}
static bool margin(const clip_state_t *s,uint64_t t) {
    if(!s->native_period || t<s->native_last) return false;
    uint64_t phase=(t-s->native_last)%s->native_period;
    return phase>=GUARD_US && s->native_period-phase>=GUARD_US;
}
void clip_init(clip_state_t *s) { memset(s,0,sizeof(*s)); tx_init(&s->tx); }
void clip_abort(clip_state_t *s,uint32_t why) {
    bool active=s->pending;
    s->pending=s->waiting_reference=false;
    if(active) s->spent=true;
    tx_abort(&s->tx,why);
}
uint32_t clip_configure(clip_state_t *s,const clip_config_t *c) {
    if(s->pending || s->tx.armed || s->tx.active) return TX_BUSY;
    const tx_config_t *b=&c->burst;
    // Zero explicitly selects lease-bound continuous operation on the qualified
    // four-cycle 27ms profile. Other finite diagnostic profiles retain bounds.
    // period=40000 selects the additive, measured quiet-window profile.
    // The old 80000 profile and finite mode 3 keep their original schedules.
    bool continuous=c->burst_count==0 && b->period_us==27000 && b->cycles==4;
    bool interleaved=(b->period_us==80000 || b->period_us==40000 || b->period_us==33000 || b->period_us==27000) && b->cycles==4 &&
        ((c->burst_count>=2 && c->burst_count<=600) || continuous);
    if(b->frequency_hz!=197000 || b->high_ns!=1000 ||
       (b->cycles!=1 && b->cycles!=2 && b->cycles!=4 && b->cycles!=8) ||
       (!interleaved && c->burst_count!=1 && c->burst_count!=10) || b->marker_timeout_us!=1000 ||
       (!interleaved && b->period_us!=(c->burst_count==1?1000000u:100000u))) return TX_INVALID;
    // Validate electrical burst geometry through the existing core; only this
    // finite controller may then use the shorter requested train interval.
    tx_config_t ordinary=*b; ordinary.period_us=200000;
    uint32_t result=tx_configure(&s->tx,&ordinary);
    if(result!=TX_OK) return result;
    s->tx.config=*b;
    s->count=s->remaining=c->burst_count;
    s->configured=true; s->spent=false; s->requested_id=0;
    s->interleaved=interleaved; s->duration_s=interleaved?c->burst_count:0;
    s->continuous=continuous;
    s->seven_slots=interleaved && b->period_us==27000;
    s->dense_window=interleaved && (b->period_us==33000 || s->seven_slots);
    s->quiet_window=interleaved && (b->period_us==40000 || s->dense_window);
    s->deadline=0; s->starts=0; s->slot=0;
    s->depth_auto=false; s->cycle=0; s->capture_count=8192; s->last_interval=27000;
    memset(&s->depth,0,sizeof(s->depth)); memset(&s->depth_pending,0,sizeof(s->depth_pending));
    return TX_OK;
}
uint32_t clip_arm(clip_state_t *s,const tx_arm_t *a,uint64_t now,bool ready) {
    if(!s->configured || s->spent) return TX_UNARMED;
    if(!s->tx.armed && s->reference_suspect) return TX_INVALID;
    if(s->interleaved) {
        if(a->mode!=CLIP_INTERLEAVED || a->lease_us!=2000000 || !ready || !fresh(s,now) ||
           s->native_period<380000 || s->native_period>395000) return TX_INVALID;
        if(s->tx.armed) {
            // A renewal cannot revive an expired lease or extend the hard run.
            if(now>=s->tx.lease_until || (s->deadline && now>=s->deadline)) return TX_UNARMED;
            s->tx.lease_until=now+a->lease_us; return TX_OK;
        }
        tx_arm_t ordinary=*a; ordinary.mode=TX_MANUAL;
        return tx_arm(&s->tx,&ordinary,now,true);
    }
    if(s->tx.armed || s->pending) return TX_BUSY; // Renewals cannot extend a train.
    if(a->mode!=CLIP_MODE || a->lease_us!=2000000 || !ready || !fresh(s,now)) return TX_INVALID;
    tx_arm_t ordinary=*a; ordinary.mode=TX_MANUAL;
    return tx_arm(&s->tx,&ordinary,now,true);
}
uint32_t clip_fire(clip_state_t *s,uint64_t id,uint64_t now,bool ready) {
    if(id<=s->tx.last_id || (s->requested_id && id<=s->requested_id)) return TX_DUPLICATE;
    if(!s->tx.armed || now>=s->tx.lease_until || s->spent) return TX_UNARMED;
    if(s->pending || s->tx.active || !ready) return TX_BUSY;
    if(!fresh(s,now) || s->reference_suspect) return TX_INVALID;
    s->requested_id=id; s->pending=s->waiting_reference=true;
    s->reference_after=s->native_last;
    return TX_OK;
}
static void observe(clip_state_t *s,uint64_t marker) {
    if(!marker || marker<=s->seen_marker) return;
    s->seen_marker=marker;
    if(s->tx.last_start && marker>=s->tx.last_start &&
       marker-s->tx.last_start<=5000) return;
    if(s->native_last && marker-s->native_last<=5000) return;
    // Once locked, an early off-phase edge is not a new native clock. The
    // recorded failure had an extra edge at +360190us, then a real native
    // marker at +386080us. Preserve the qualified clock, but quarantine ALL
    // remaining outputs until an actual in-window native marker arrives.
    // No extrapolated marker may start a cycle; genuine loss still expires.
    if(s->native_stable>=3 && s->native_period &&
       marker+NATIVE_JITTER_US<s->native_last+s->native_period) {
        if(!s->reference_suspect) reference_diagnostic(s,5,marker);
        s->reference_suspect=true;
        return;
    }
    bool recovered=s->reference_suspect;
    if(s->native_last) {
        uint64_t dt=marker-s->native_last;
        bool valid=dt>=NATIVE_MIN_US && dt<=NATIVE_MAX_US &&
            (!s->native_period || distance(dt,s->native_period)<=NATIVE_JITTER_US);
        // A median must not conceal an individual period outside the qualified
        // electrical profile, even when it is close to the previous estimate.
        if(s->pending && s->interleaved && (dt<380000u || dt>395000u)) {
            reference_fault(s,2,marker); valid=false;
        }
        if(valid) { s->native_stable++; accept_interval(s,(uint32_t)dt); }
        else {
            if(s->pending) reference_fault(s,1,marker);
            s->native_stable=0; s->native_period=0;
            s->native_interval_count=s->native_interval_next=0;
        }
    }
    s->native_last=marker;
    s->reference_suspect=false;
    if(s->native_stable>=3) reference_diagnostic(s,11,marker);
    if(recovered && s->native_stable>=3) reference_diagnostic(s,6,marker);
    if(s->pending && s->interleaved && marker>s->reference_after) {
        if(s->native_period<380000 || s->native_period>395000) {
            reference_fault(s,2,marker); return;
        }
        if(!s->depth_auto && !s->waiting_reference && s->slot<slots(s) && !recovered) {
            reference_fault(s,3,marker); return;
        }
        s->slot=0; s->next_due=marker+(s->dense_window?174000u:s->quiet_window?180000u:40000u); s->waiting_reference=false;
        s->cycle++;
        return;
    }
    if(s->pending && s->waiting_reference && marker>s->reference_after) {
        s->next_due=marker+OFFSET_US;
        for(uint32_t k=0;k<s->remaining;k++) {
            // Include the bounded cumulative main-loop delay and native jitter.
            uint64_t t=s->next_due+(uint64_t)k*s->tx.config.period_us;
            if(!margin(s,t) || !margin(s,t+(uint64_t)k*LATE_US)) {
                clip_abort(s,CLIP_COLLISION); return;
            }
        }
        s->waiting_reference=false;
    }
}
void clip_poll(clip_state_t *s,uint64_t now,bool ready,bool done,uint64_t marker) {
    observe(s,marker);
    bool was_armed=s->tx.armed;
    tx_poll(&s->tx,now,ready,done,marker);
    if(was_armed && !s->tx.armed) { s->pending=s->waiting_reference=false; s->spent=true; }
    if(!s->pending || !s->tx.armed) return;
    if(s->reference_suspect &&
       now>s->native_last+s->native_period+NATIVE_JITTER_US) {
        reference_fault(s,4,now); return;
    }
    if(!fresh(s,now)) { reference_fault(s,4,now); return; }
    if(s->tx.active) return;
    if((!s->interleaved && !s->remaining) || (s->interleaved && s->deadline && now>=s->deadline)) {
        s->pending=false; s->spent=true;
        if(s->tx.idle) s->tx.idle(s->tx.context);
        s->tx.armed=false; s->tx.mode=TX_OFF;
        tx_event_t e={CLIP_DONE,TX_OK,s->tx.last_id,s->tx.last_start,s->tx.marker_us,0};
        if(s->tx.event) s->tx.event(s->tx.context,&e);
        return;
    }
    if(s->interleaved && s->slot>=slots(s)) return;
    if(s->reference_suspect) return;
    if(s->waiting_reference || now<s->next_due) return;
    if(s->depth_auto) depth_geometry(s,now);
    // The dense grid allows bounded USB/packing jitter. Missed slots are
    // omitted, never squeezed together or replayed, and never shift the
    // native clock. Normal and five-slot policies retain their old limits.
    if(s->dense_window) {
        uint32_t window=s->depth_auto?s->capture_count*2u:16384u;
        if(now-s->next_due>DENSE_LATE_US || !margin(s,now) ||
           (s->depth_auto && now+window+NATIVE_JITTER_US+1000u+GUARD_US>s->native_last+s->native_period) ||
           !margin(s,now+window+NATIVE_JITTER_US+(s->depth_auto?1000u:0u))) { skip_dense_slot(s,now); return; }
        uint32_t gap=s->depth_auto?s->tx.config.period_us:spacing(s);
        if(!ready || (s->tx.last_start && now-s->tx.last_start<gap)) return;
    }
    else {
    if(now-s->next_due>LATE_US) { reference_diagnostic(s,7,now); clip_abort(s,CLIP_LATE); return; }
    if(!ready) { clip_abort(s,TX_BUSY); return; }
    if(!margin(s,now)) { clip_abort(s,CLIP_COLLISION); return; }
    // Quiet PINGs keep the full 8192*2us receive window, native jitter and
    // the existing collision guard clear; a late slot aborts, never squeezes.
    if(s->quiet_window && !margin(s,now+16384u+NATIVE_JITTER_US)) {
        clip_abort(s,CLIP_COLLISION); return;
    }
    }
    uint64_t id=!s->starts?s->requested_id:s->tx.last_id+1;
    if(s->depth_auto) {
        tx_event_t e={9,s->plan_revision,s->capture_count,now,s->last_interval,s->cycle};
        if(s->tx.event) s->tx.event(s->tx.context,&e);
    }
    uint32_t result=tx_fire(&s->tx,id,now,true,s->tx.ping_mode);
    if(result!=TX_OK) { clip_abort(s,result); return; }
    s->starts++;
    if(s->interleaved) {
        if(!s->continuous && !s->deadline) s->deadline=s->tx.last_start+(uint64_t)s->duration_s*1000000;
        s->slot++;
        if(s->depth_auto) {
            s->next_due+=s->last_interval;
            s->tx.config.period_us=s->last_interval;
        } else s->next_due=s->dense_window?s->native_last+174000u+(uint64_t)s->slot*spacing(s):
            s->tx.last_start+(s->quiet_window?40000u:100000u);
        if(!s->continuous && s->starts>(s->depth_auto?60000u:s->seven_slots?12000u:s->dense_window?10000u:8000u)) clip_abort(s,CLIP_REFERENCE_LOST);
    } else { s->remaining--; s->next_due=s->tx.last_start+s->tx.config.period_us; }
}
