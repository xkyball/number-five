#include <assert.h>
#include <stdio.h>
#include "clip_diagnostic.h"

static unsigned starts,links,quarantines,recoveries,completed,skipped;
static unsigned period=40000;
static void start(void *context,const tx_config_t *c) {
    clip_state_t *s=context;
    assert(!s->reference_suspect && c->cycles==4 && c->high_ns==1000);
    starts++;
}
static void idle(void *context) { (void)context; }
static void event(void *context,const tx_event_t *e) {
    (void)context;
    links+=e->event==TX_PING_LINKED;
    completed+=e->event==CLIP_DONE;
    quarantines+=e->event==8 && e->reason==5;
    recoveries+=e->event==8 && e->reason==6;
    skipped+=e->event==8 && e->reason==10;
}
static void setup(clip_state_t *s) {
    starts=links=quarantines=recoveries=completed=skipped=0;
    clip_init(s);s->tx.context=s;s->tx.start=start;s->tx.idle=idle;s->tx.event=event;
    for(unsigned k=0;k<4;k++) {
        uint64_t t=100000+k*386208;
        clip_poll(s,t,true,false,t);
    }
    clip_config_t c={{1,197000,1000,4,period,1000},2};
    assert(clip_configure(s,&c)==TX_OK);s->tx.ping_mode=true;
    tx_arm_t a={2,CLIP_INTERLEAVED,2000000};
    assert(clip_arm(s,&a,1260000,true)==TX_OK);
    assert(clip_fire(s,1000,1260000,true)==TX_OK);
}
static void simulate(clip_state_t *s,unsigned offset,bool missing_native,bool stop) {
    uint64_t first=1644832,next=first,marker=1258624,renewal=1600000;
    bool injected=false,stopped=false;
    for(uint64_t now=1260000;now<5000000;now+=100) {
        if(now>=next && (!missing_native || next==first)) {
            marker=next;next+=next==first?386080:386208;
        }
        if(s->tx.active && !s->tx.marked) marker=s->tx.last_start+4;
        if(s->tx.active && s->tx.marked && now-s->tx.last_start>=(period==27000?25000u:30000u))
            tx_link_ping(&s->tx,starts,s->tx.marker_us+1,3);
        if(!injected && now>=first+offset) {
            // Exact extra edge from the physical failure, including the case
            // where the last burst's valid raw PING has not been linked yet.
            marker=first+offset;injected=true;
        }
        if(injected && stop && !stopped) {
            clip_abort(s,TX_DISCONNECTED);stopped=true;
        }
        if(now>=renewal && s->tx.armed) {
            tx_arm_t a={3,CLIP_INTERLEAVED,2000000};
            assert(clip_arm(s,&a,now,true)==TX_OK);renewal+=500000;
        }
        clip_poll(s,now,true,s->tx.active && now-s->tx.last_start>=50,marker);
        if(!s->tx.armed) break;
    }
}
int main(void) {
    clip_state_t s;
    // Actual 2026-10-17 failure: an early extra edge, followed by two genuine
    // native intervals with opposite timestamp jitter. Preserve the 2ms
    // envelope and quarantine, without aborting on a 2229us adjacent delta.
    period=27000; setup(&s); s.continuous=true; s.depth_auto=true;
    s.depth_pending.max_own_per_native=8;
    uint64_t edge=s.native_last;
    const uint32_t measured[]={385986,386009,386127,385970,386179,386074,386043,384839,387068,386300};
    for(unsigned i=0;i<sizeof(measured)/sizeof(measured[0]);i++) {
        if(i==7) {
            clip_poll(&s,edge+21620,true,false,edge+21620);
            assert(s.reference_suspect && !starts);
        }
        edge+=measured[i];
        tx_arm_t renewal={3,CLIP_INTERLEAVED,2000000};
        assert(clip_arm(&s,&renewal,edge-1000,true)==TX_OK);
        clip_poll(&s,edge,true,false,edge);
        assert(s.pending && s.tx.armed && !s.reference_suspect);
        assert(s.native_last==edge && !starts);
    }
    assert(quarantines==1 && recoveries==1);
    clip_poll(&s,edge+390000,true,false,edge+390000);
    assert(!s.pending && !s.tx.armed && s.tx.error==CLIP_REFERENCE_LOST);
    // A later plausible clock cannot automatically revive a safety fault.
    clip_poll(&s,edge+776000,true,false,edge+776000);
    assert(!s.pending && !s.tx.armed && !starts);
    for(unsigned profile=0;profile<3;profile++) {
    period=profile==2?27000u:profile==1?33000u:40000u;
    setup(&s);simulate(&s,360190,false,false);
    assert(completed==1 && quarantines==1 && recoveries==1);
    assert(starts>=25 && links==starts && !s.reference_suspect);
    // An early extra edge suppresses every remaining slot of that cycle.
    setup(&s);simulate(&s,150000,false,false);
    assert(completed==1 && quarantines==1 && recoveries==1 && links==starts);
    // Never synthesize the missing real clock, nor continue on an early edge.
    setup(&s);simulate(&s,150000,true,false);
    assert(!starts && !completed && s.tx.error==CLIP_REFERENCE_LOST);
    assert(!s.pending && !s.tx.armed && s.spent);
    setup(&s);simulate(&s,150000,false,true);
    assert(!starts && !completed && s.tx.error==TX_DISCONNECTED);
    clip_poll(&s,3000000,true,true,3000000);
    assert(!starts && !s.pending && !s.tx.armed);
    }
    period=33000;setup(&s);
    clip_poll(&s,1644832,true,false,1644832);
    uint64_t due=s.next_due;
    clip_poll(&s,due+4100,false,false,1644832);
    assert(skipped==1 && starts==0 && s.next_due==due+33000);
    clip_poll(&s,s.next_due,true,false,1644832);
    assert(starts==1 && s.tx.last_start==due+33000);
    // Packing/poll jitter within 4ms may use the already planned slot.
    // The same 27ms spacing and full receive/native guards still apply.
    period=27000;setup(&s);
    clip_poll(&s,1644832,true,false,1644832);
    due=s.next_due;
    clip_poll(&s,due+3500,true,false,1644832);
    assert(starts==1 && skipped==0 && s.tx.last_start==due+3500);
    setup(&s);clip_poll(&s,1644832,true,false,1644832);due=s.next_due;
    clip_poll(&s,due+4100,true,false,1644832);
    assert(starts==0 && skipped==1 && s.next_due==due+27000);
    puts("recorded extra marker quarantined; actual reference recovers; missing clock and USB loss stop output");
}
