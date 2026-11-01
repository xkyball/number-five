#include <string.h>
#include "internal.h"
#include "pico/stdlib.h"
#include "tusb.h"

void base_background_service(no5_base_t *b) {
    tud_task();
    if(b->background_service) b->background_service(b->safety_context);
}

void base_crc_init(no5_base_t *b) {
    for (uint32_t i=0;i<256;i++) { uint32_t c=i; for(int b=0;b<8;b++) c=(c>>1)^((c&1)?0xedb88320u:0); b->wire.crc_table[i]=c; }
}

uint32_t base_crc32(no5_base_t *b,const uint8_t *p, size_t n) {
    uint32_t c=0xffffffffu;
    for(size_t i=0;i<n;i++) { c=b->wire.crc_table[(c^p[i])&255]^(c>>8); if((i&255)==255) base_background_service(b); }
    return c^0xffffffffu;
}

size_t base_cobs_encode(no5_base_t *b,const uint8_t *p,size_t n,uint8_t *out) {
    size_t w=1, mark=0; uint8_t code=1;
    for(size_t i=0;i<n;i++) {
        if(!p[i]) { out[mark]=code; mark=w++; code=1; }
        else { out[w++]=p[i]; if(++code==255) { out[mark]=code; mark=w++; code=1; } }
        if((i&255)==255) base_background_service(b);
    }
    out[mark]=code; return w;
}

size_t base_cobs_decode(no5_base_t *b,const uint8_t *p,size_t n,uint8_t *out) {
    size_t r=0,w=0;
    while(r<n) {
        uint8_t c=p[r++]; if(!c || r+c-1>n || w+c>sizeof(b->wire.decoded)) return 0;
        for(uint8_t j=1;j<c;j++) out[w++]=p[r++];
        if(c!=255 && r<n) out[w++]=0;
    }
    return w;
}

void base_prepare_packet(no5_base_t *b,uint8_t type,const void *p,uint32_t n,uint32_t flags) {
    header_t h={ {'N','5'},1,type,b->wire.sequence++,n,flags };
    memcpy(b->wire.raw,&h,16); if(n) memcpy(b->wire.raw+16,p,n);
    uint32_t crc=base_crc32(b,b->wire.raw,16+n); memcpy(b->wire.raw+16+n,&crc,4);
    b->wire.tx_length=base_cobs_encode(b,b->wire.raw,20+n,b->wire.encoded); b->wire.encoded[b->wire.tx_length++]=0;
    b->wire.tx_offset=0; b->wire.tx_started=time_us_64(); b->wire.tx_timeout_reported=false;
}

bool base_tx_pending(no5_base_t *b) { return b->wire.tx_offset<b->wire.tx_length; }

bool base_send_packet(no5_base_t *b,uint8_t type,const void *p,uint32_t n) {
    if(type==STREAM || type==PING) {
        // A checkpoint while packing STREAM can enqueue TX events. Complete
        // this already copied raw block before pumping those events; rejecting
        // it here would fabricate a USB fault and lose immutable ADC samples.
        bool copied_stream=(type==STREAM || type==PING) && b->background_service;
        if(base_tx_pending(b) || (b->wire.control_count && !copied_stream)) return false;
        base_prepare_packet(b,type,p,n,b->acq.running?1u:0u); return true;
    }
    if(n>40 || b->wire.control_count==8) { b->wire.usb_errors++; base_trip(b,ERR_USB); return false; }
    control_t *c=&b->wire.control_queue[b->wire.control_tail]; c->type=type; c->length=(uint8_t)n; c->flags=b->acq.running?1u:0u;
    if(n) memcpy(c->payload,p,n);
    b->wire.control_tail=(b->wire.control_tail+1)%8; b->wire.control_count++; return true;
}

void base_pump_tx(no5_base_t *b) {
    if(!base_tx_pending(b) && b->wire.control_count) {
        control_t *c=&b->wire.control_queue[b->wire.control_head]; base_prepare_packet(b,c->type,c->payload,c->length,c->flags);
        b->wire.control_head=(b->wire.control_head+1)%8; b->wire.control_count--;
    }
    if(!base_tx_pending(b)) return;
    uint32_t available=tud_cdc_write_available(), left=(uint32_t)(b->wire.tx_length-b->wire.tx_offset);
    if(available) { b->wire.tx_offset+=tud_cdc_write(b->wire.encoded+b->wire.tx_offset,MIN(available,left)); tud_cdc_write_flush(); }
    if(base_tx_pending(b) && time_us_64()-b->wire.tx_started>200000 && !b->wire.tx_timeout_reported) {
        b->wire.tx_timeout_reported=true; b->wire.usb_errors++; base_trip(b,ERR_USB);
        // Keep the outstanding packet intact; it can finish if the host resumes.
    }
}
