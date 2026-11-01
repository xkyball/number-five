#include <assert.h>
#include <stdio.h>
#include <string.h>
#include "offline_sdk.h"
#include "../src/base/internal.h"
#include "../src/modules/modes.h"
#include "../src/modules/clip_diagnostic.h"
#define MIN(a,b) ((a)<(b)?(a):(b))
#include "../src/base/wire.c"
#include "../src/modules/scope_stream.c"

static no5_base_t base;
static clip_state_t clip;
static uint64_t now, marker, next_native, last_start, disconnect_at;
static unsigned starts, marked, finished, done, failed, streams, packets, during_copy, diagnostics;
static uint32_t sequence;
static bool connected, in_copy;
static uint16_t samples[COUNT];
static uint8_t received[ENCODED_MAX];
static size_t received_length;

uint64_t time_us_64(void) { return now; }
void tud_task(void) {
    now+=60; // Bounded checkpoint cost; one whole block consumes several ms.
    if(now>=next_native) { marker=next_native; next_native+=386000; }
    if(last_start && now>=last_start+4 && marker<last_start) marker=last_start+4;
    if(disconnect_at && now>=disconnect_at) connected=false;
}
void base_trip(no5_base_t *b,uint32_t error) {
    b->acq.running=false; b->acq.fault=error; clip_abort(&clip,TX_ACQUISITION_FAULT);
}
uint8_t *base_payload(no5_base_t *b) { return b->wire.payload; }
void base_release_block(no5_base_t *b,uint32_t block) { b->acq.consumed=block+1; }
void base_count_stream(no5_base_t *b) { b->session.blocks_total++; }
void base_count_drop(no5_base_t *b) { b->wire.dropped++; }
bool base_take_tx_marker(no5_base_t *b,uint64_t t,uint32_t c,uint32_t p,uint32_t *i) {
    (void)b;(void)t;(void)c;(void)p;(void)i;return false; // Mapping has its own real-C test.
}
static uint32_t independent_crc(const uint8_t *p,size_t n) {
    uint32_t c=~0u;
    for(size_t i=0;i<n;i++) { c^=p[i]; for(unsigned k=0;k<8;k++) c=(c>>1)^((c&1)?0xedb88320u:0); }
    return ~c;
}
static void inspect_frame(void) {
    uint8_t raw[RAW_MAX]; size_t r=0,w=0;
    while(r<received_length) {
        uint8_t c=received[r++]; assert(c && r+c-1<=received_length);
        for(unsigned j=1;j<c;j++) raw[w++]=received[r++];
        if(c!=255 && r<received_length) raw[w++]=0;
    }
    header_t h; memcpy(&h,raw,sizeof(h)); uint32_t crc; memcpy(&crc,raw+w-4,4);
    assert(w==h.length+20 && h.seq==sequence++ && h.magic[0]=='N' && h.magic[1]=='5');
    assert(crc==independent_crc(raw,w-4)); packets++;
    if(h.type==STREAM) {
        stream_t s; memcpy(&s,raw+16,sizeof(s)); assert(s.count==COUNT && s.period==2000);
        for(unsigned i=0;i<COUNT;i+=2) {
            const uint8_t *p=raw+16+40+i*3/2;
            assert((uint16_t)(p[0]|((p[1]&15)<<8))==samples[i]);
            assert((uint16_t)((p[1]>>4)|(p[2]<<4))==samples[i+1]);
        }
        streams++;
    } else { assert(h.type==TX_EVENT && h.length==sizeof(tx_event_t)); }
}
uint32_t tud_cdc_write_available(void) { return ENCODED_MAX; }
uint32_t tud_cdc_write(const void *p,uint32_t n) {
    const uint8_t *bytes=p;
    for(unsigned i=0;i<n;i++) {
        if(bytes[i]) { assert(received_length<sizeof(received)); received[received_length++]=bytes[i]; }
        else { inspect_frame(); received_length=0; }
    }
    return n;
}
void tud_cdc_write_flush(void) {}
static void start(void *p,const tx_config_t *c) {
    (void)p; assert(c->cycles==2 && c->high_ns==1000); starts++;
    if(last_start) assert(now-last_start>=100000 && now-last_start<=101000);
    last_start=now;
}
static void idle(void *p) { (void)p; }
static void event(void *p,const tx_event_t *e) {
    (void)p; marked+=e->event==TX_MARKED; finished+=e->event==TX_FINISHED;
    done+=e->event==CLIP_DONE; failed+=e->event==TX_FAILED;
    diagnostics+=e->event==8;
    during_copy+=in_copy;
    assert(base_send_packet(&base,TX_EVENT,e,sizeof(*e)));
}
static void poll(void *p) {
    (void)p;
    if(!connected) clip_abort(&clip,TX_DISCONNECTED);
    else clip_poll(&clip,now,base.acq.running && base.wire.control_count<3,
                   clip.tx.active && now>=last_start+11,marker);
}
static void run(bool checkpoints,bool unplug) {
    memset(&base,0,sizeof(base)); clip_init(&clip); base_crc_init(&base);
    starts=marked=finished=done=failed=streams=packets=during_copy=sequence=diagnostics=0;
    received_length=0; last_start=0; disconnect_at=0; connected=true; in_copy=false;
    base.acq.running=true; base.background_service=checkpoints?poll:NULL;
    clip.tx.start=start; clip.tx.idle=idle; clip.tx.event=event;
    for(unsigned k=0;k<4;k++) clip_poll(&clip,100000+k*386000,true,false,100000+k*386000);
    now=1639000; marker=1258000; next_native=1644000;
    clip_config_t c={{1,197000,1000,2,100000,1000},10};
    tx_arm_t a={2,CLIP_MODE,2000000};
    assert(clip_configure(&clip,&c)==TX_OK && clip_arm(&clip,&a,now,true)==TX_OK);
    assert(clip_fire(&clip,100,now,true)==TX_OK);
    const uint64_t epoch=1258038; // First target at block phase 6362 us, like real capture.
    uint32_t block=(uint32_t)((now-epoch)/8192);
    uint64_t available=epoch+(uint64_t)(block+1)*8192;
    for(unsigned i=0;i<COUNT;i++) samples[i]=(uint16_t)((i*13)%3500+100);
    while(now<2750000) {
        tud_task(); poll(NULL); base_pump_tx(&base);
        if(unplug && last_start && !disconnect_at) disconnect_at=last_start+99500;
        if(now>=available && !base_tx_pending(&base) && !base.wire.control_count) {
            no5_block_view_t view={samples,block,2000,epoch,0x8000};
            in_copy=true; scope_on_block(&base,&view); in_copy=false;
            block++; available+=8192;
        }
        poll(NULL); base_pump_tx(&base);
    }
    while(base_tx_pending(&base)||base.wire.control_count) base_pump_tx(&base);
    assert(!base.acq.fault && !base.wire.dropped && !base.wire.usb_errors && streams>100);
    assert(!clip.tx.armed && packets==streams+starts+marked+finished+done+failed+diagnostics);
    if(unplug) { assert(starts==1 && failed==1 && clip.tx.error==TX_DISCONNECTED); }
    else if(checkpoints) { assert(starts==10 && marked==10 && finished==10 && done==1 && !failed && during_copy); }
    else { assert(starts<10 && failed==1 && clip.tx.error==CLIP_LATE); }
}
int main(void) {
    run(false,false); // Old whole-block scheduling reproduces the deadline failure.
    run(true,false);  // Ten marked bursts without losing/altering raw samples or events.
    run(true,true);   // Disconnection during block work prevents subsequent starts.
    puts("wire/Scope checkpoints: finite cadence, raw bytes/CRC/sequence, events and disconnect passed");
}
