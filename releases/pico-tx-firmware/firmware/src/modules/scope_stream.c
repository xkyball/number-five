#include <string.h>
#include "modes.h"
#define MIN(a,b) ((a)<(b)?(a):(b))
#define MAX(a,b) ((a)>(b)?(a):(b))

bool scope_validate_config(const config_t *c) { return c->count==COUNT && !c->pre && !c->trigger && !c->signal; }
void scope_on_block(no5_base_t *b, const no5_block_view_t *view) {

    uint32_t block=view->block; const uint16_t *s=view->samples;
    uint8_t *payload=base_payload(b);
    stream_t prefix={ (uint64_t)block+1,(uint64_t)block*COUNT,view->epoch_us+(uint64_t)block*COUNT*view->period/1000,view->period,COUNT,0,4095,0 };
    bool adc_error=false;
    for(uint32_t i=0;i<COUNT;i+=2) {
        uint16_t a=s[i],z=s[i+1]; if((a|z)&view->adc_error_mask) adc_error=true;
        a&=4095; z&=4095;
        prefix.min=MIN(prefix.min,MIN(a,z)); prefix.max=MAX(prefix.max,MAX(a,z));
        uint32_t j=40+i*3/2; payload[j]=(uint8_t)a; payload[j+1]=(uint8_t)((a>>8)|((z&15)<<4)); payload[j+2]=(uint8_t)(z>>4);
        if((i&127)==126) base_background_service(b);
    }
    base_release_block(b,block); // Release only after the complete sample copy.
    uint32_t marker_index;
    if(base_take_tx_marker(b,prefix.time_us,COUNT,prefix.period,&marker_index))
        prefix.flags|=(1u<<8)|(marker_index<<16);
    if(adc_error) { base_trip(b,ERR_ADC); prefix.flags|=64; }
    if(prefix.min==0 || prefix.max==4095) prefix.flags|=2;
    memcpy(payload,&prefix,40); base_count_stream(b);
    if(!base_send_packet(b,STREAM,payload,40+COUNT*3/2)) { base_count_drop(b); base_trip(b,ERR_USB); }
}
