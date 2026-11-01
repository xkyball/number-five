#include <assert.h>
#include <stdio.h>
#include "transmit.h"
static int starts,idles,markers,links,failures;
static void start(void *p,const tx_config_t *c) { (void)p; assert(c->cycles<=128); starts++; }
static void idle(void *p) { (void)p; idles++; }
static void event(void *p,const tx_event_t *e) { (void)p; markers+=e->event==TX_MARKED; links+=e->event==TX_PING_LINKED; failures+=e->event==TX_FAILED; }
static void init(tx_state_t *s) { tx_init(s); s->start=start; s->idle=idle; s->event=event; }
int main(void) {
    tx_state_t s; init(&s);
    tx_config_t c={1,197000,1000,8,200000,5000}; tx_arm_t a={2,TX_MANUAL,2000000};
    assert(tx_fire(&s,1,1000,true,true)==TX_UNARMED && starts==0);
    assert(tx_configure(&s,&c)==TX_OK); assert(tx_arm(&s,&a,100000,true)==TX_OK);
    assert(tx_fire(&s,7,101000,true,true)==TX_OK); assert(starts==1);
    assert(tx_fire(&s,7,101001,true,true)==TX_DUPLICATE);
    tx_poll(&s,101030,true,true,101001); assert(s.marked && s.finished && s.active);
    tx_link_ping(&s,42,101001,3); assert(!s.active && markers==1 && links==1);
    assert(tx_fire(&s,8,102000,true,true)==TX_BUSY);
    assert(tx_fire(&s,8,302000,true,true)==TX_OK);
    tx_poll(&s,307001,true,true,101001); assert(!s.armed && s.error==TX_NO_MARKER);
    assert(tx_arm(&s,&a,310000,true)==TX_UNARMED); // renewals cannot revive a fault
    assert(tx_configure(&s,&c)==TX_OK); a.mode=TX_PERIODIC;
    assert(tx_arm(&s,&a,600000,true)==TX_OK); s.ping_mode=true;
    tx_poll(&s,610000,true,false,0); assert(s.active && s.last_id==9 && s.need_ping);
    tx_poll(&s,610050,true,true,610001); tx_link_ping(&s,43,610001,3);
    tx_poll(&s,810000,true,false,610001); assert(s.active && s.need_ping && s.last_id==10);
    tx_abort(&s,TX_STOPPED); assert(!s.active && !s.armed && s.mode==TX_OFF);
    assert(tx_configure(&s,&c)==TX_OK); a.mode=TX_MANUAL;
    assert(tx_arm(&s,&a,700000,true)==TX_OK); tx_poll(&s,2700000,true,true,0);
    assert(!s.armed && s.error==TX_LEASE_EXPIRED);
    assert(tx_configure(&s,&c)==TX_OK); assert(tx_arm(&s,&a,3000000,true)==TX_OK);
    assert(tx_fire(&s,11,3000010,true,true)==TX_OK); tx_poll(&s,3000050,true,true,3000011);
    tx_poll(&s,3100011,true,true,3000011); assert(s.error==TX_NO_PING && !s.armed);
    c.cycles=0; assert(tx_configure(&s,&c)==TX_INVALID); c.cycles=129; assert(tx_configure(&s,&c)==TX_INVALID);
    c.cycles=8; c.high_ns=4000; assert(tx_configure(&s,&c)==TX_INVALID);
    init(&s); assert(!s.armed && !s.active && !s.configured); // reset starts disarmed
    printf("{\"starts\":%d,\"idle_calls\":%d,\"markers\":%d,\"linked_pings\":%d,\"faults\":%d}\n",starts,idles,markers,links,failures);
}
