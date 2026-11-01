#include <assert.h>
#include <stdio.h>
#include "offline_sdk.h"
#include "../src/base/internal.h"
#include "../src/base/api.h"
static adc_hw_t adc_registers; static dma_hw_t dma_registers;
adc_hw_t *adc_hw=&adc_registers; dma_hw_t *dma_hw=&dma_registers;
uint32_t save_and_disable_interrupts(void) { return 0; } void restore_interrupts(uint32_t x) { (void)x; }
void adc_init(void){} void adc_gpio_init(uint x){(void)x;} void adc_select_input(uint x){(void)x;} void adc_run(bool x){(void)x;} void adc_fifo_drain(void){} void adc_fifo_setup(bool a,bool b,uint c,bool d,bool e){(void)a;(void)b;(void)c;(void)d;(void)e;} void adc_set_clkdiv(float x){(void)x;}
uint dma_claim_unused_channel(bool x){(void)x;return 0;} dma_channel_config dma_channel_get_default_config(uint x){(void)x;return (dma_channel_config){0};} void channel_config_set_transfer_data_size(dma_channel_config*a,uint b){(void)a;(void)b;} void channel_config_set_read_increment(dma_channel_config*a,bool b){(void)a;(void)b;} void channel_config_set_write_increment(dma_channel_config*a,bool b){(void)a;(void)b;} void channel_config_set_dreq(dma_channel_config*a,uint b){(void)a;(void)b;} void channel_config_set_chain_to(dma_channel_config*a,uint b){(void)a;(void)b;} void channel_config_set_high_priority(dma_channel_config*a,bool b){(void)a;(void)b;} void dma_channel_configure(uint a,const dma_channel_config*b,void*c,const volatile void*d,uint e,bool f){(void)a;(void)b;(void)c;(void)d;(void)e;(void)f;} void dma_channel_set_irq0_enabled(uint a,bool b){(void)a;(void)b;} void dma_channel_set_write_addr(uint a,void*b,bool c){(void)a;(void)b;(void)c;} void dma_channel_set_trans_count(uint a,uint b,bool c){(void)a;(void)b;(void)c;} void dma_channel_abort(uint a){(void)a;} void dma_start_channel_mask(uint32_t a){(void)a;} void hw_clear_bits(volatile uint32_t*a,uint32_t b){*a&=~b;} void irq_set_exclusive_handler(uint a,void(*b)(void)){(void)a;(void)b;} void irq_set_enabled(uint a,bool b){(void)a;(void)b;} void __dmb(void){} uint64_t time_us_64(void){return 0;}
void gpio_init(uint a){(void)a;} void gpio_set_dir(uint a,bool b){(void)a;(void)b;} void gpio_disable_pulls(uint a){(void)a;} void gpio_set_irq_enabled_with_callback(uint a,uint32_t b,bool c,void(*d)(uint,uint32_t)){(void)a;(void)b;(void)c;(void)d;}
#include "../src/base/acquisition.c"
int main(void) {
 no5_base_t b={0}; uint32_t i=0; b.acq.marker_enabled=true;
 b.acq.marker_pending=true; b.acq.marker_us=1000000; assert(base_take_tx_marker(&b,999000,4096,2000,&i)&&i==500&&!b.acq.marker_pending);
 b.acq.marker_pending=true; b.acq.marker_us=998999; assert(!base_take_tx_marker(&b,999000,4096,2000,&i)&&b.acq.marker_rejected==1);
 b.acq.marker_pending=true; b.acq.marker_us=1007184; assert(base_take_tx_marker(&b,999000,4096,2000,&i)&&i==4092);
 b.acq.marker_pending=true; b.acq.marker_us=1007192; assert(!base_take_tx_marker(&b,999000,4096,2000,&i));
 assert(b.acq.marker_pending && b.acq.marker_rejected==1);
 assert(base_take_tx_marker(&b,1007192,4096,2000,&i)&&i==0&&!b.acq.marker_pending);
 for(uint32_t period=2000;period<=8000;period*=2) {
  b.acq.marker_pending=true; b.acq.marker_us=2000000+4096*period/1000-1;
  assert(base_take_tx_marker(&b,2000000,4096,period,&i)&&i==4095&&!b.acq.marker_pending);
  assert(!base_take_tx_marker(&b,2000000+4096*period/1000,4096,period,&i));
 }
 puts("marker index mapping passed"); return 0;
}
