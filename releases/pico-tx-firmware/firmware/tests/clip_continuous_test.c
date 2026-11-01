#include <assert.h>
#include <stdio.h>
#include "clip_diagnostic.h"
static unsigned starts,links,completed;
static uint64_t previous;
static unsigned previous_count;
static void idle(void *ctx) { (void)ctx; }
static void start(void *ctx,const tx_config_t *c) {
    clip_state_t *s=ctx;
    assert(c->frequency_hz==197000 && c->high_ns==1000 && c->cycles==4);
    if(previous) {
        assert(s->tx.last_start-previous>=(s->depth_auto?24300u:27000u));
        assert(s->tx.last_start-previous>=previous_count*2u+3000u);
    }
    previous=s->tx.last_start;previous_count=s->capture_count;starts++;
}
static void event(void *ctx,const tx_event_t *e) {
    (void)ctx;links+=e->event==TX_PING_LINKED;completed+=e->event==CLIP_DONE;
}
static void setup(clip_state_t *s) {
    clip_init(s);starts=links=completed=0;previous=0;previous_count=0;
    s->tx.context=s;s->tx.start=start;s->tx.idle=idle;s->tx.event=event;
    for(unsigned k=0;k<4;k++)clip_poll(s,100000+k*386400,true,false,100000+k*386400);
    clip_config_t c={{1,197000,1000,4,27000,1000},0};
    assert(clip_configure(s,&c)==TX_OK && s->continuous && !s->deadline);
    assert(!s->tx.armed && !starts); // CONFIG never fires.
    tx_arm_t a={2,CLIP_INTERLEAVED,2000000};
    assert(clip_arm(s,&a,1260000,true)==TX_OK && !starts);
    assert(clip_fire(s,1000,1260000,true)==TX_OK);
    assert(clip_fire(s,1000,1260000,true)==TX_DUPLICATE);
    s->tx.ping_mode=true;
}
static uint64_t simulate(clip_state_t *s,unsigned seconds,bool renew,bool lose_native,bool lose_ping) {
    uint64_t marker=1259200,native=1645600,renewal=1600000,end=1260000+(uint64_t)seconds*1000000;
    for(uint64_t now=1260000;now<end;now+=100) {
        if(now==native && (!lose_native || starts<8)) {marker=now;native+=386400;}
        if(s->tx.active && !s->tx.marked)marker=s->tx.last_start+3;
        if(s->tx.active && s->tx.marked && !lose_ping && now-s->tx.last_start>=(s->depth_auto?s->capture_count*2u+1000u:18000u))
            tx_link_ping(&s->tx,starts,s->tx.marker_us,3);
        if(renew && now>=renewal) {
            tx_arm_t a={3,CLIP_INTERLEAVED,2000000};
            assert(clip_arm(s,&a,now,true)==TX_OK);renewal+=500000;
        }
        clip_poll(s,now,true,s->tx.active && now-s->tx.last_start>=50,marker);
        if(!s->tx.armed)return now;
    }
    return end;
}
int main(void) {
    clip_state_t s;setup(&s);
    simulate(&s,700,true,false,false);
    assert(s.tx.armed && s.pending && !s.deadline && !completed);
    assert(starts>12000 && links>=starts-1); // Cross former duration AND start quota.
    clip_abort(&s,TX_STOPPED);assert(!s.tx.armed && !s.pending && s.spent);
    tx_arm_t a={4,CLIP_INTERLEAVED,2000000};
    assert(clip_arm(&s,&a,800000000,true)==TX_UNARMED);
    setup(&s);simulate(&s,5,false,false,false);assert(s.tx.error==TX_LEASE_EXPIRED);
    setup(&s);simulate(&s,5,true,true,false);assert(s.tx.error==CLIP_REFERENCE_LOST);
    setup(&s);simulate(&s,5,true,false,true);assert(s.tx.error==TX_NO_PING);
    setup(&s);
    clip_depth_config_t d={3,1,2048,24300,21900,1000000,8};
    assert(clip_depth_configure(&s,&d,1260001)==TX_INVALID);
    d.floor_us=24300;d.max_own_per_native=9;
    assert(clip_depth_configure(&s,&d,1260001)==TX_INVALID);
    d.max_own_per_native=8;assert(clip_depth_configure(&s,&d,1260001)==TX_OK);
    simulate(&s,700,true,false,false);assert(s.tx.armed && s.pending && !completed && !s.deadline);
    setup(&s);s.starts=(1ull<<32)-2;
    simulate(&s,3,true,false,false);assert(s.starts>(1ull<<32));
    clip_abort(&s,TX_DISCONNECTED);assert(!s.tx.armed && !s.pending);
    clip_init(&s);assert(!s.configured && !s.continuous && !s.tx.armed);
    clip_config_t c={{1,197000,1000,4,40000,1000},0};assert(clip_configure(&s,&c)==TX_INVALID);
    c.burst.period_us=27000;c.burst_count=601;assert(clip_configure(&s,&c)==TX_INVALID);
    c.burst_count=600;assert(clip_configure(&s,&c)==TX_OK && !s.continuous);
    puts("continuous beyond 600s and former quota; lease, marker, PING, STOP, USB and reset remain fail-closed");
}
