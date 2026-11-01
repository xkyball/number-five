#include <string.h>
#include "../app.h"
#include "transmit_hw.h"
#include "burst.pio.h"
#include "hardware/pio.h"
#include "hardware/clocks.h"
#include "hardware/sync.h"
#include "pico/stdlib.h"

#define TX_GPIO 22u
static PIO engine=pio1;
static uint sm, offset;
static volatile uint32_t emergency;
static void idle(void *ctx) {
    (void)ctx; pio_sm_set_enabled(engine,sm,false);
    gpio_set_function(TX_GPIO,GPIO_FUNC_SIO); gpio_put(TX_GPIO,0); gpio_set_dir(TX_GPIO,true);
}
static void trip(void *ctx) { idle(ctx); emergency=TX_ACQUISITION_FAULT; }
static void event(void *ctx,const tx_event_t *e) { no5_app_t *app=ctx; base_send_packet(&app->base,TX_EVENT,e,sizeof(*e)); }
static void start(void *ctx,const tx_config_t *c) {
    (void)ctx;
    uint32_t clock=clock_get_hz(clk_sys);
    uint32_t ticks=(clock+c->frequency_hz/2)/c->frequency_hz;
    uint32_t high=(uint32_t)(((uint64_t)clock*c->high_ns+500000000)/1000000000);
    pio_sm_set_enabled(engine,sm,false); pio_sm_clear_fifos(engine,sm); pio_interrupt_clear(engine,0);
    pio_sm_restart(engine,sm);
    pio_sm_config pc=finite_burst_program_get_default_config(offset);
    sm_config_set_set_pins(&pc,TX_GPIO,1); sm_config_set_clkdiv(&pc,1.0f);
    pio_sm_init(engine,sm,offset,&pc);
    pio_sm_set_pins_with_mask(engine,sm,0,1u<<TX_GPIO);
    pio_sm_set_consecutive_pindirs(engine,sm,TX_GPIO,1,true); pio_gpio_init(engine,TX_GPIO);
    pio_sm_put_blocking(engine,sm,c->cycles-1);
    pio_sm_put_blocking(engine,sm,high-3);
    pio_sm_put_blocking(engine,sm,ticks-high-4);
    pio_sm_set_enabled(engine,sm,true);
}
static bool ready(no5_app_t *a) {
    no5_base_t *b=&a->base;
    return b->acq.running && b->acq.marker_enabled && !b->acq.fault &&
        b->wire.control_count<3 &&
        (b->session.config.trigger!=2 || (!a->ping.capture.collecting && !a->ping.capture.ready));
}
void transmit_hw_init(no5_app_t *a) {
    tx_init(&a->tx); sm=pio_claim_unused_sm(engine,true); offset=pio_add_program(engine,&finite_burst_program);
    gpio_init(TX_GPIO); idle(a); gpio_pull_down(TX_GPIO);
    gpio_set_drive_strength(TX_GPIO,GPIO_DRIVE_STRENGTH_12MA);
    a->tx.context=a; a->tx.start=start; a->tx.idle=idle; a->tx.event=event;
    a->base.safety_abort=trip; a->base.safety_context=a;
}
void transmit_hw_stop(no5_app_t *a,uint32_t reason) { tx_abort(&a->tx,reason); }
void transmit_hw_poll(no5_app_t *a) {
    if(emergency) { emergency=0; tx_abort(&a->tx,TX_ACQUISITION_FAULT); }
    if(a->tx.armed && !a->base.acq.running) { tx_abort(&a->tx,TX_ACQUISITION_FAULT); return; }
    a->tx.ping_mode=a->base.session.config.trigger==2;
    uint32_t interrupts=save_and_disable_interrupts();
    uint64_t marker=a->base.acq.marker_observed_us;
    restore_interrupts(interrupts);
    uint64_t previous=a->tx.last_id;
    tx_poll(&a->tx,time_us_64(),ready(a),pio_interrupt_get(engine,0),marker);
    if(a->tx.last_id!=previous && a->tx.need_ping)
        a->tx.ping_deadline=a->tx.last_start+(uint64_t)a->base.session.config.count*a->base.session.config.period/1000+50000;
}
void transmit_hw_command(no5_app_t *a,uint8_t type,const uint8_t *payload,uint32_t length) {
    uint32_t request=0,result=TX_INVALID; if(length>=4) memcpy(&request,payload,4);
    if(type==TX_CONFIG && length==sizeof(tx_config_t)) { tx_config_t c; memcpy(&c,payload,sizeof(c)); result=tx_configure(&a->tx,&c); }
    else if(type==TX_ARM && length==sizeof(tx_arm_t)) {
        tx_arm_t c; memcpy(&c,payload,sizeof(c));
        // A lease renewal need not coincide with the tiny idle gap between packets.
        bool acquisition=a->base.acq.running && a->base.acq.marker_enabled && !a->base.acq.fault;
        result=tx_arm(&a->tx,&c,time_us_64(),acquisition);
    }
    else if(type==TX_FIRE && length==sizeof(tx_fire_t)) {
        tx_fire_t c; memcpy(&c,payload,sizeof(c));
        result=a->tx.mode==TX_MANUAL?tx_fire(&a->tx,c.id,time_us_64(),ready(a),a->base.session.config.trigger==2):TX_UNARMED;
        if(result==TX_OK && a->tx.need_ping)
            a->tx.ping_deadline=a->tx.last_start+(uint64_t)a->base.session.config.count*a->base.session.config.period/1000+50000;
    }
    else if(type==TX_ABORT && length==4) { tx_abort(&a->tx,TX_STOPPED); result=TX_OK; }
    tx_ack_t ack={request,result,a->tx.active?2u:(a->tx.armed?1u:0u),a->tx.mode,a->tx.lease_until};
    base_send_packet(&a->base,TX_ACK,&ack,sizeof(ack));
}
