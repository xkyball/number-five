#include <assert.h>
#include <stdio.h>
#include "clip_diagnostic.h"
static unsigned starts, probes, links;
static uint64_t native_origin, native_period, last_start;
static unsigned last_count, last_floor;
static void idle(void *ctx) { (void)ctx; }
static void event(void *ctx,const tx_event_t *e) {
    clip_state_t *s=ctx;
    if(e->event==9) assert(e->id==s->capture_count && e->reason==s->depth.revision);
    links+=e->event==TX_PING_LINKED;
}
static void start(void *ctx,const tx_config_t *c) {
    clip_state_t *s=ctx;
    uint64_t phase=(s->tx.last_start-native_origin)%native_period;
    assert(c->frequency_hz==197000 && c->high_ns==1000 && c->cycles==4);
    assert(phase>=174000 && phase+s->capture_count*2+23000<=native_period);
    if(last_start) {
        assert(s->tx.last_start-last_start>=last_count*2+3000);
        assert(s->tx.last_start-last_start>=last_floor);
    }
    last_start=s->tx.last_start;last_count=s->capture_count;last_floor=s->depth.floor_us;
    starts++;probes+=s->capture_count==16384;
}
static void run(unsigned period,bool expire,bool lose_native,bool renew) {
    clip_state_t s;clip_init(&s);s.tx.context=&s;s.tx.start=start;s.tx.idle=idle;s.tx.event=event;
    starts=probes=links=last_count=last_floor=0;last_start=0;native_period=period;
    for(unsigned k=0;k<4;k++)clip_poll(&s,100000+k*period,true,false,100000+k*period);
    clip_config_t c={{1,197000,1000,4,27000,1000},2};
    assert(clip_configure(&s,&c)==TX_OK);
    uint64_t begin=100000+3*period+1000;
    clip_depth_config_t d={2,1,2048,7096,6000,1000000,64};
    assert(clip_depth_configure(&s,&d,begin)==TX_OK);
    assert(clip_depth_configure(&s,&d,begin)==TX_DUPLICATE);
    tx_arm_t a={3,CLIP_INTERLEAVED,2000000};
    assert(clip_arm(&s,&a,begin,true)==TX_OK);
    d.revision++;d.floor_us=7000;
    assert(clip_depth_configure(&s,&d,begin)==TX_BUSY);d.floor_us=6000;
    assert(clip_fire(&s,1000,begin,true)==TX_OK);s.tx.ping_mode=true;
    uint64_t marker=100000+3*period,next_native=marker+period,next_renew=begin+250000;
    native_origin=next_native;
    for(uint64_t now=begin;now<begin+5000000;now+=100) {
        if(now>=next_native && (!lose_native || starts<5)) {marker=next_native;next_native+=period;}
        if(s.tx.active && !s.tx.marked)marker=s.tx.last_start+3;
        if(s.tx.active && s.tx.marked && now-s.tx.last_start>=s.capture_count*2+1000)
            tx_link_ping(&s.tx,starts,s.tx.marker_us,3);
        if(now>=next_renew && renew) {
            assert(clip_arm(&s,&a,now,true)==TX_OK);next_renew+=250000;
            if(!expire) {d.revision++;assert(clip_depth_configure(&s,&d,now)==TX_OK);}
        }
        clip_poll(&s,now,true,s.tx.active && now-s.tx.last_start>=50,marker);
        if(!s.tx.armed)break;
    }
    assert(links==starts);
    if(lose_native)assert(s.tx.error==CLIP_REFERENCE_LOST);
    else if(!renew)assert(s.tx.error==TX_LEASE_EXPIRED);
    else {assert(s.spent && starts>20 && probes>=2);if(expire)assert(s.capture_count==16384);}
}
static void interval_code(unsigned period) {
    const unsigned bits[]={1,1,1,0,0,1,0};
    clip_state_t s;clip_init(&s);s.tx.context=&s;s.tx.start=start;s.tx.idle=idle;s.tx.event=event;
    starts=probes=links=last_count=last_floor=0;last_start=0;native_period=period;
    for(unsigned k=0;k<4;k++)clip_poll(&s,100000+k*period,true,false,100000+k*period);
    clip_config_t c={{1,197000,1000,4,27000,1000},30};
    assert(clip_configure(&s,&c)==TX_OK);
    uint64_t begin=100000+3*period+1000,marker=100000+3*period,next_native=marker+period;
    clip_depth_config_t d={2,1,2048,27000,24300,1000000,8};
    assert(clip_depth_configure(&s,&d,begin)==TX_OK);
    tx_arm_t a={3,CLIP_INTERLEAVED,2000000};assert(clip_arm(&s,&a,begin,true)==TX_OK);
    assert(clip_fire(&s,1000,begin,true)==TX_OK);s.tx.ping_mode=true;native_origin=next_native;
    unsigned cycles=0,short_plans=0,long_plans=0;
    for(uint64_t now=begin;now<begin+5000000;now+=100) {
        if(now>=next_native) {
            marker=next_native;next_native+=period;
            d.revision++;d.spacing_us=bits[cycles++%7]?27000:24300;
            short_plans+=d.spacing_us==24300;long_plans+=d.spacing_us==27000;
            assert(clip_depth_configure(&s,&d,now)==TX_OK);
            assert(clip_arm(&s,&a,now,true)==TX_OK);
        }
        if(s.tx.active && !s.tx.marked)marker=s.tx.last_start+3;
        if(s.tx.active && s.tx.marked && now-s.tx.last_start>=s.capture_count*2+1000)
            tx_link_ping(&s.tx,starts,s.tx.marker_us,3);
        clip_poll(&s,now,true,s.tx.active && now-s.tx.last_start>=50,marker);
        assert(s.tx.armed);
    }
    // Reconcile an acquisition that straddles the observation boundary;
    // polling the core alone cannot schedule another clip output.
    if(s.tx.active) {
        tx_poll(&s.tx,s.tx.last_start+s.capture_count*2+1000,true,true,s.tx.marker_us);
        tx_link_ping(&s.tx,starts,s.tx.marker_us,3);
    }
    assert(starts>60 && links==starts && probes>=4 && short_plans && long_plans);
    clip_abort(&s,TX_STOPPED);assert(!s.pending && !s.tx.armed && !s.tx.active);
}
int main(void) {
    run(380000,false,false,true);run(395000,false,false,true);
    run(386400,true,false,true);run(386400,false,true,true);run(386400,false,false,false);
    interval_code(380000);interval_code(395000);
    clip_state_t s;clip_init(&s);clip_config_t c={{1,197000,1000,4,27000,1000},2};
    assert(clip_configure(&s,&c)==TX_OK);
    clip_depth_config_t d={2,1,2048,6000,6000,1000000,64};
    assert(clip_depth_configure(&s,&d,0)==TX_INVALID);
    d.spacing_us=7096;d.count=16385;assert(clip_depth_configure(&s,&d,0)==TX_INVALID);
    puts("adaptive RX geometry, probes, expiry, minimum real gaps, native guards and lease verified");
}
