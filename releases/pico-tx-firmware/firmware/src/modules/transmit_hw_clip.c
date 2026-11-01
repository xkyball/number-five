#include <string.h>
#include "../app.h"
#include "transmit_hw.h"
#include "clip_burst.pio.h"
#include "hardware/pio.h"
#include "hardware/clocks.h"
#include "hardware/sync.h"
#include "pico/stdlib.h"

// Physical pads 24 and 29 need one additional hook beyond existing GP22.
// The intervening GPIOs remain untouched inputs/SIO, not additional outputs.
#define CLIP_BASE_GPIO 18u
#define CLIP_GPIO_MASK ((1u<<18)|(1u<<22))
static const uint pins[]={18u,22u};
static PIO engine=pio1;
static uint sm,offset;
static volatile uint32_t emergency;
static void idle(void *ctx) {
    (void)ctx; pio_sm_set_enabled(engine,sm,false);
    for(uint i=0;i<2;i++) {
        gpio_put(pins[i],0); gpio_set_dir(pins[i],true);
        gpio_set_function(pins[i],GPIO_FUNC_SIO);
    }
}
static void trip(void *ctx) { idle(ctx); emergency=TX_ACQUISITION_FAULT; }
static void event(void *ctx,const tx_event_t *e) {
    no5_app_t *a=ctx; base_send_packet(&a->base,TX_EVENT,e,sizeof(*e));
}
static void start(void *ctx,const tx_config_t *c) {
    no5_app_t *a=ctx;
    if(a->clip.depth_auto) a->ping.capture.count=a->clip.capture_count;
    uint32_t clock=clock_get_hz(clk_sys);
    uint32_t ticks=(clock+c->frequency_hz/2)/c->frequency_hz;
    uint32_t high=(uint32_t)(((uint64_t)clock*c->high_ns+500000000)/1000000000);
    pio_sm_set_enabled(engine,sm,false); pio_sm_clear_fifos(engine,sm);
    pio_interrupt_clear(engine,0); pio_sm_restart(engine,sm);
    pio_sm_config pc=clip_finite_burst_program_get_default_config(offset);
    sm_config_set_set_pins(&pc,CLIP_BASE_GPIO,5); sm_config_set_clkdiv(&pc,1.0f);
    pio_sm_init(engine,sm,offset,&pc);
    // Clear a disabled PIO's old HIGH latch before it regains the pads.
    pio_sm_set_pins_with_mask(engine,sm,0,CLIP_GPIO_MASK);
    pio_sm_set_pindirs_with_mask(engine,sm,CLIP_GPIO_MASK,CLIP_GPIO_MASK);
    for(uint i=0;i<2;i++) pio_gpio_init(engine,pins[i]);
    pio_sm_put_blocking(engine,sm,c->cycles-1);
    pio_sm_put_blocking(engine,sm,high-3);
    pio_sm_put_blocking(engine,sm,ticks-high-4);
    uint32_t irq=save_and_disable_interrupts();
    a->clip.tx.last_start=time_us_64(); // Just before PIO enable, not USB arrival.
    pio_sm_set_enabled(engine,sm,true);
    restore_interrupts(irq);
}
static bool ready(no5_app_t *a) {
    no5_base_t *b=&a->base;
    return b->acq.running && b->acq.marker_enabled && !b->acq.fault &&
        b->session.config.period==2000 && b->wire.control_count<3 &&
        ((b->session.config.trigger==0 && b->session.config.count==4096) ||
         (b->session.config.trigger==2 && b->session.config.count==8192 &&
          !a->ping.capture.collecting && !a->ping.capture.ready));
}
void transmit_hw_init(no5_app_t *a) {
    tx_init(&a->tx); clip_init(&a->clip);
    sm=pio_claim_unused_sm(engine,true); offset=pio_add_program(engine,&clip_finite_burst_program);
    for(uint i=0;i<2;i++) {
        gpio_init(pins[i]); gpio_put(pins[i],0); gpio_set_dir(pins[i],true);
        gpio_pull_down(pins[i]); gpio_set_drive_strength(pins[i],GPIO_DRIVE_STRENGTH_12MA);
    }
    idle(a);
    a->clip.tx.context=a; a->clip.tx.start=start; a->clip.tx.idle=idle; a->clip.tx.event=event;
    a->base.safety_abort=trip; a->base.safety_context=a;
}
void transmit_hw_stop(no5_app_t *a,uint32_t why) { clip_abort(&a->clip,why); }
void transmit_hw_poll(no5_app_t *a) {
    if(emergency) { emergency=0; clip_abort(&a->clip,TX_ACQUISITION_FAULT); }
    if(a->clip.tx.armed && !a->base.acq.running) { clip_abort(&a->clip,TX_ACQUISITION_FAULT); return; }
    uint32_t irq=save_and_disable_interrupts();
    uint64_t marker=a->base.acq.marker_observed_us;
    restore_interrupts(irq);
    a->clip.tx.ping_mode=a->base.session.config.trigger==2;
    if(a->base.acq.running && marker>=a->base.acq.epoch_us)
        clip_poll(&a->clip,time_us_64(),ready(a),pio_interrupt_get(engine,0),marker);
}
void transmit_hw_command(no5_app_t *a,uint8_t type,const uint8_t *p,uint32_t length) {
    uint32_t request=0,result=TX_INVALID;
    if(length>=4) memcpy(&request,p,4);
    if(type==TX_CLIP_CONFIG && length==sizeof(clip_config_t)) {
        clip_config_t c; memcpy(&c,p,sizeof(c)); result=clip_configure(&a->clip,&c);
    } else if(type==TX_DEPTH_CONFIG && length==sizeof(clip_depth_config_t)) {
        clip_depth_config_t c; memcpy(&c,p,sizeof(c));
        result=clip_depth_configure(&a->clip,&c,time_us_64());
    } else if(type==TX_ARM && length==sizeof(tx_arm_t)) {
        tx_arm_t c; memcpy(&c,p,sizeof(c));
        bool acquisition=a->base.acq.running && a->base.acq.marker_enabled && !a->base.acq.fault;
        result=clip_arm(&a->clip,&c,time_us_64(),acquisition);
    } else if(type==TX_FIRE && length==sizeof(tx_fire_t)) {
        tx_fire_t c; memcpy(&c,p,sizeof(c)); result=clip_fire(&a->clip,c.id,time_us_64(),ready(a));
    } else if(type==TX_ABORT && length==4) { clip_abort(&a->clip,TX_STOPPED); result=TX_OK; }
    tx_state_t *s=&a->clip.tx;
    tx_ack_t ack={request,result,s->active?2u:(s->armed?1u:0u),s->armed?(a->clip.interleaved?CLIP_INTERLEAVED:CLIP_MODE):TX_OFF,s->lease_until};
    base_send_packet(&a->base,TX_ACK,&ack,sizeof(ack));
}
