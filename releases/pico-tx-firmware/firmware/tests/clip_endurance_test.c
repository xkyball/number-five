#include <assert.h>
#include <stdio.h>
#include "clip_diagnostic.h"
static unsigned starts,links,completed; static uint64_t previous,shortest;
static bool quiet_schedule,dense_schedule,seven_schedule;
static void start(void *p,const tx_config_t *c) {
    clip_state_t *s=p; assert(c->cycles==4 && c->high_ns==1000);
    if(previous) { uint64_t dt=s->tx.last_start-previous; assert(dt>=(seven_schedule?27000u:dense_schedule?33000u:quiet_schedule?40000u:80000u)); if(dt<shortest) shortest=dt; }
    if(quiet_schedule) {
        uint64_t phase=(s->tx.last_start-1645600u)%386400u;
        assert(phase>=(dense_schedule?174000u:180000u) && phase<=341000);
        assert(phase+16384+20000<380000); // full RX window and original guard
    }
    previous=s->tx.last_start; starts++;
}
static void idle(void *p) { (void)p; }
static void event(void *p,const tx_event_t *e) { (void)p; links+=e->event==TX_PING_LINKED; completed+=e->event==CLIP_DONE; }
static void setup(clip_state_t *s,unsigned duration) {
    clip_init(s); s->tx.context=s; s->tx.start=start; s->tx.idle=idle; s->tx.event=event;
    starts=links=completed=0; previous=0; shortest=1000000;
    for(unsigned k=0;k<4;k++) clip_poll(s,100000+k*386400,true,false,100000+k*386400);
    clip_config_t c={{1,197000,1000,4,seven_schedule?27000u:dense_schedule?33000u:quiet_schedule?40000u:80000u,1000},duration};
    assert(clip_configure(s,&c)==TX_OK); s->tx.ping_mode=true;
    tx_arm_t arm={2,CLIP_INTERLEAVED,2000000};
    assert(clip_arm(s,&arm,1260000,true)==TX_OK);
    assert(clip_fire(s,1000,1260000,true)==TX_OK);
    assert(clip_fire(s,1000,1260000,true)==TX_DUPLICATE);
}
static void simulate(clip_state_t *s,bool renew,bool missing_ping,bool lost_reference) {
    uint64_t native=1645600, marker=1259200, renewal=1600000;
    for(uint64_t now=1260000;now<605000000;now+=100) {
        if(now==native && (!lost_reference || starts<8)) { marker=now; native+=386400; }
        if(now==native-386400+600) marker=now; // secondary edge in original burst
        if(s->tx.active && !s->tx.marked) marker=s->tx.last_start+3;
        if(s->tx.active && s->tx.marked && !missing_ping && now-s->tx.last_start>=18000)
            tx_link_ping(&s->tx,starts,s->tx.marker_us+1,3);
        if(renew && now>=renewal) {
            tx_arm_t a={3,CLIP_INTERLEAVED,2000000};
            assert(clip_arm(s,&a,now,true)==TX_OK); renewal+=500000;
        }
        bool ready=!quiet_schedule || !s->tx.last_start || now-s->tx.last_start>=(seven_schedule?25000u:30000u);
        clip_poll(s,now,ready,s->tx.active && now-s->tx.last_start>=50,marker);
        if(!s->tx.armed) break;
    }
}
int main(void) {
    clip_state_t s; setup(&s,600); simulate(&s,true,false,false);
    assert(completed==1 && starts>=6000 && starts<=6400 && links==starts);
    assert(s.spent && !s.tx.armed && shortest>=86000 && shortest<=87000);
    assert(s.deadline-s.tx.last_start<=150000);
    tx_arm_t a={4,CLIP_INTERLEAVED,2000000};
    assert(clip_arm(&s,&a,s.deadline+1,true)==TX_UNARMED);
    setup(&s,2); simulate(&s,true,false,false); assert(completed==1 && starts>=20 && links==starts);
    setup(&s,600); simulate(&s,false,false,false); assert(s.tx.error==TX_LEASE_EXPIRED && !completed);
    setup(&s,600); simulate(&s,true,true,false); assert(s.tx.error==TX_NO_PING && starts==1);
    setup(&s,600); simulate(&s,true,false,true); assert(s.tx.error==CLIP_REFERENCE_LOST && !completed);
    quiet_schedule=true;
    setup(&s,600); simulate(&s,true,false,false);
    assert(completed==1 && starts>=7700 && starts<8000 && links==starts && shortest==40000);
    setup(&s,2); simulate(&s,true,false,false); assert(completed==1 && starts>=25 && links==starts);
    setup(&s,600); simulate(&s,false,false,false); assert(s.tx.error==TX_LEASE_EXPIRED && !completed);
    setup(&s,600); simulate(&s,true,true,false); assert(s.tx.error==TX_NO_PING && starts==1);
    setup(&s,600); simulate(&s,true,false,true); assert(s.tx.error==CLIP_REFERENCE_LOST && !completed);
    dense_schedule=true;
    setup(&s,600); simulate(&s,true,false,false);
    assert(completed==1 && starts>=9300 && starts<10000 && links==starts && shortest==33000);
    setup(&s,2); simulate(&s,true,false,false); assert(completed==1 && starts>=30 && links==starts);
    setup(&s,600); simulate(&s,false,false,false); assert(s.tx.error==TX_LEASE_EXPIRED && !completed);
    setup(&s,600); simulate(&s,true,true,false); assert(s.tx.error==TX_NO_PING && starts==1);
    setup(&s,600); simulate(&s,true,false,true); assert(s.tx.error==CLIP_REFERENCE_LOST && !completed);
    seven_schedule=true;
    setup(&s,600); simulate(&s,true,false,false);
    assert(completed==1 && starts>=10800 && starts<12000 && links==starts && shortest==27000);
    setup(&s,2); simulate(&s,true,false,false); assert(completed==1 && starts>=35 && links==starts);
    setup(&s,600); simulate(&s,false,false,false); assert(s.tx.error==TX_LEASE_EXPIRED && !completed);
    setup(&s,600); simulate(&s,true,true,false); assert(s.tx.error==TX_NO_PING && starts==1);
    setup(&s,600); simulate(&s,true,false,true); assert(s.tx.error==CLIP_REFERENCE_LOST && !completed);
    puts("600 seconds, >=6000 unique physical/PING links, native guards, hard limit, expired lease and missing PING verified");
}
