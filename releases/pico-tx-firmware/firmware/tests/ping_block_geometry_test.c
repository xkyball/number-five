#include <assert.h>
#include <stdio.h>
#include "../src/app.h"
#include "../src/modules/modes.h"
#include "../src/modules/transmit_hw.h"
static no5_app_t app;
static uint16_t block[COUNT];
static uint8_t packet[PAYLOAD_MAX];
static unsigned packets, expected_count;
static uint16_t sample(uint64_t i) { return (uint16_t)((i*13)%3500+100); }
uint32_t base_block_samples(const no5_base_t *b) { return b->acq.block_samples; }
const config_t *base_config(const no5_base_t *b) { return &b->session.config; }
uint64_t base_time_us(void) { return 1000000; }
uint64_t base_epoch_us(const no5_base_t *b) { return b->acq.epoch_us; }
void base_background_service(no5_base_t *b) { (void)b; }
void base_release_block(no5_base_t *b,uint32_t i) { b->acq.consumed=i+1; }
void base_trip(no5_base_t *b,uint32_t error) { b->acq.fault=error;assert(!error); }
void base_count_drop(no5_base_t *b) { b->wire.dropped++; }
uint64_t base_next_ping(no5_base_t *b) { return ++b->session.pings_total; }
uint8_t *base_payload(no5_base_t *b) { return b->wire.payload; }
bool base_send_packet(no5_base_t *b,uint8_t type,const void *data,uint32_t n) {
    (void)b;assert(type==PING && n==sizeof(ping_t)+expected_count*2);memcpy(packet,data,n);packets++;return true;
}
void transmit_hw_poll(no5_app_t *a) { (void)a; }
bool base_take_tx_marker(no5_base_t *b,uint64_t t,uint32_t n,uint32_t period,uint32_t *i) {
    if(!b->acq.marker_pending || b->acq.marker_us<t || b->acq.marker_us>=t+n*period/1000) return false;
    *i=(uint32_t)((b->acq.marker_us-t)*1000/period);b->acq.marker_pending=false;return true;
}
static void run(unsigned size,unsigned offset,unsigned count) {
    memset(&app,0,sizeof(app));packets=0;expected_count=count;no5_base_t *b=&app.base;
    b->acq.block_samples=size;b->acq.epoch_us=1000000;b->acq.marker_enabled=true;
    b->session.config=(config_t){.period=2000,.count=8192,.threshold=150,.hysteresis=8,.trigger=2,.signal=1,.timeout=1000000};
    ping_begin_run(&app.ping,&b->session.config);
    app.ping.capture.count=count;
    uint64_t first=(uint64_t)size*3+offset;
    b->acq.marker_pending=true;b->acq.marker_us=b->acq.epoch_us+first*2;
    for(unsigned k=0;k<20 && !packets;k++) {
        for(unsigned j=0;j<size;j++) block[j]=sample((uint64_t)k*size+j);
        no5_block_view_t view={block,k,2000,b->acq.epoch_us,0x8000};
        ping_on_block(b,&app.ping,&view);
        if(app.ping.capture.ready) ping_try_emit(b,&app.ping);
    }
    assert(packets==1 && !b->acq.fault && !b->wire.dropped);
    ping_t header;memcpy(&header,packet,sizeof(header));
    assert(header.count==count && header.period==2000 && header.zero==0 && header.flags==1);
    assert(header.time_us==b->acq.epoch_us+first*2);
    for(unsigned j=0;j<count;j++) {
        uint16_t value;memcpy(&value,packet+sizeof(header)+j*2,2);
        assert(value==sample(first+j));
    }
}
int main(void) {
    const unsigned sizes[]={1024,4096};
    const unsigned counts[]={2048,8192,16384};
    for(unsigned n=0;n<3;n++) {
    for(unsigned k=0;k<2;k++) {
        run(sizes[k],0,counts[n]);run(sizes[k],1,counts[n]);run(sizes[k],sizes[k]/2,counts[n]);run(sizes[k],sizes[k]-1,counts[n]);
    }
    }
    puts("variable identical ADC samples and physical zero across small/Scope DMA blocks and boundary offsets");
}
