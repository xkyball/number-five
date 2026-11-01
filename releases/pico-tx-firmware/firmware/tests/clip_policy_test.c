#include <assert.h>
#include <stdio.h>
#include "clip_diagnostic.h"
static unsigned starts,done_events,idles;
static uint64_t last_start;
static void start(void *p,const tx_config_t *c) {
    clip_state_t *s=p; assert(c->cycles<=8 && c->high_ns==1000);
    starts++; last_start=s->tx.last_start;
}
static void idle(void *p) { (void)p; idles++; }
static void event(void *p,const tx_event_t *e) { (void)p; done_events+=e->event==CLIP_DONE; }
static void init(clip_state_t *s,uint64_t period) {
    clip_init(s); s->tx.context=s; s->tx.start=start; s->tx.idle=idle; s->tx.event=event;
    starts=done_events=idles=0; last_start=0;
    for(unsigned k=0;k<4;k++) {
        uint64_t t=100000+k*period; clip_poll(s,t,true,false,t);
    }
    assert(s->native_stable==3);
}
static void configure(clip_state_t *s,unsigned count) {
    clip_config_t c={{1,197000,1000,1,count==1?1000000u:100000u,1000},count};
    assert(clip_configure(s,&c)==TX_OK);
}
static void arm_fire(clip_state_t *s,uint64_t now) {
    tx_arm_t a={2,CLIP_MODE,2000000};
    assert(clip_arm(s,&a,now,true)==TX_OK);
    assert(starts==0);
    assert(clip_arm(s,&a,now+1,true)==TX_BUSY);
    assert(clip_fire(s,100,now,true)==TX_OK);
    assert(clip_fire(s,100,now,true)==TX_DUPLICATE);
    assert(clip_fire(s,101,now,true)==TX_BUSY);
}
int main(void) {
    clip_state_t s;
    init(&s,386000); configure(&s,10); arm_fire(&s,1259000);
    uint64_t marker=1258000,native=1644000,prior=0;
    for(uint64_t now=1259000;now<2700000;now+=100) {
        if(now==native) { marker=now; native+=386000; }
        if(s.tx.active && !s.tx.marked && now>=last_start+2) marker=last_start+2;
        clip_poll(&s,now,true,s.tx.active && now>=last_start+45,marker);
        if(last_start!=prior) {
            if(prior) assert(last_start-prior==100000);
            prior=last_start;
        }
    }
    assert(starts==10 && done_events==1 && !s.tx.armed && s.spent);
    tx_arm_t a={2,CLIP_MODE,2000000};
    assert(clip_arm(&s,&a,2700001,true)==TX_UNARMED);
    assert(clip_fire(&s,100,2700001,true)==TX_DUPLICATE);
    clip_poll(&s,3000000,true,true,2802000); assert(starts==10);

    init(&s,386000); configure(&s,1); arm_fire(&s,1259000);
    clip_poll(&s,1644000,true,false,1644000);
    clip_poll(&s,1674000,true,false,1644000); assert(starts==1);
    clip_poll(&s,1674050,true,true,1674002);
    assert(done_events==1 && !s.pending && !s.tx.armed);

    init(&s,386000); configure(&s,10); arm_fire(&s,1259000);
    clip_poll(&s,1644000,true,false,1644000);
    clip_poll(&s,1674000,true,false,1644000);
    clip_poll(&s,1675001,true,true,1644000);
    assert(starts==1 && s.tx.error==TX_NO_MARKER && !s.tx.armed && s.spent);

    init(&s,386000); configure(&s,10); arm_fire(&s,1259000);
    clip_poll(&s,1644000,true,false,1644000);
    clip_poll(&s,1675001,true,false,1644000);
    assert(starts==0 && s.tx.error==CLIP_LATE && !s.tx.armed);

    init(&s,430000); configure(&s,10); arm_fire(&s,1391000);
    clip_poll(&s,1820000,true,false,1820000);
    assert(starts==0 && s.tx.error==CLIP_COLLISION && !s.tx.armed);

    init(&s,386000); configure(&s,10); arm_fire(&s,1259000);
    clip_poll(&s,1650000,true,false,1650000); // Native changes by 6 ms.
    assert(starts==0 && s.tx.error==CLIP_REFERENCE_LOST && !s.tx.armed);

    init(&s,386000); configure(&s,10); arm_fire(&s,1259000);
    clip_poll(&s,3259000,true,false,1258000);
    assert(starts==0 && s.tx.error==TX_LEASE_EXPIRED && !s.tx.armed);

    const unsigned faults[]={TX_STOPPED,TX_DISCONNECTED,TX_ACQUISITION_FAULT};
    for(unsigned k=0;k<3;k++) {
        init(&s,386000); configure(&s,10); arm_fire(&s,1259000);
        clip_abort(&s,faults[k]);
        assert(starts==0 && idles && !s.pending && !s.tx.armed && s.spent);
        clip_poll(&s,1644000,true,true,1644000); assert(starts==0);
    }
    clip_init(&s); assert(!s.configured && !s.pending && !s.tx.armed);
    clip_config_t bad={{1,197000,1000,1,100000,1000},11};
    assert(clip_configure(&s,&bad)==TX_INVALID);
    bad.burst_count=10; bad.burst.cycles=128; assert(clip_configure(&s,&bad)==TX_INVALID);
    bad.burst.cycles=1; bad.burst.high_ns=2000; assert(clip_configure(&s,&bad)==TX_INVALID);
    bad.burst.high_ns=1000; bad.burst.period_us=99999; assert(clip_configure(&s,&bad)==TX_INVALID);
    tx_state_t normal; tx_init(&normal);
    tx_config_t normal_config={1,197000,1000,1,100000,1000};
    assert(tx_configure(&normal,&normal_config)==TX_INVALID); // No normal-policy relaxation.
    puts("finite quotas, phase, cadence, duplicate, STOP/USB/ADC, lease and marker failure checks passed");
}
