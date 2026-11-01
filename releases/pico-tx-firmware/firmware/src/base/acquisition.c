#include "internal.h"
#include "hardware/adc.h"
#include "hardware/dma.h"
#include "hardware/irq.h"
#include "hardware/sync.h"
#include "hardware/gpio.h"
#include "pico/stdlib.h"

#define TX_MARKER_GPIO 16u
#if NO5_CLIP_DIAGNOSTIC
#define TX_MARKER_REFRACTORY_US 5000u
#else
#define TX_MARKER_REFRACTORY_US 500u
#endif

static no5_base_t *irq_owner;
static void __isr irq_entry(void) { base_dma_complete(irq_owner); }
static void __isr marker_irq(uint gpio, uint32_t events) {
    no5_base_t *b=irq_owner;
    if(!b || gpio!=TX_MARKER_GPIO || !(events&GPIO_IRQ_EDGE_RISE) || !b->acq.marker_enabled) return;
    uint64_t now=time_us_64();
    // Keep the first physical edge until the active capture consumes it; ringing
    // cannot move TX forward. Clip reference observed a secondary edge at
    // +512us. Its 5ms electrical packet holdoff is far below the 80ms minimum
    // emission spacing and never blanks or modifies ADC echo samples.
    if((b->acq.marker_us && now-b->acq.marker_us<TX_MARKER_REFRACTORY_US) ||
       b->acq.marker_pending) {
        b->acq.marker_rejected++;
        return;
    }
    b->acq.marker_us=now; b->acq.marker_observed_us=now; b->acq.marker_pending=true;
}
void base_trip(no5_base_t *b,uint32_t error) { if(b->safety_abort) b->safety_abort(b->safety_context); adc_run(false); b->acq.running=false; if(!b->acq.fault) b->acq.fault=error; }

void base_dma_complete(no5_base_t *b) {
    uint32_t pending=dma_hw->ints0 & ((1u<<b->acq.channels[0])|(1u<<b->acq.channels[1]));
    dma_hw->ints0=pending;
    if(!b->acq.running) return;
    // Both completions imply software missed a whole block deadline. Stop honestly.
    if(pending==((1u<<b->acq.channels[0])|(1u<<b->acq.channels[1]))) { base_trip(b,ERR_DMA); return; }
    for(uint k=0;k<2;k++) if(pending&(1u<<b->acq.channels[k])) {
        if(b->acq.channel_block[k]!=b->acq.produced) { base_trip(b,ERR_DMA); return; }
        if(adc_hw->fcs & (ADC_FCS_OVER_BITS|ADC_FCS_UNDER_BITS)) { b->acq.overruns++; base_trip(b,ERR_FIFO); return; }
        __dmb(); b->acq.produced++;
        uint32_t next=b->acq.channel_block[k]+2;
        if(next-b->acq.consumed>=b->acq.ring_blocks) { base_trip(b,ERR_RING); return; }
        b->acq.channel_block[k]=next;
        dma_channel_set_write_addr(b->acq.channels[k],b->acq.samples+
            (next%b->acq.ring_blocks)*b->acq.block_samples,false);
        dma_channel_set_trans_count(b->acq.channels[k],b->acq.block_samples,false);
    }
}

void base_stop_adc(no5_base_t *b) {
    adc_run(false); b->acq.running=false;
    // RP2040 E13: suppress abort-generated completion interrupts.
    for(uint k=0;k<2;k++) dma_channel_set_irq0_enabled(b->acq.channels[k],false);
    // Disable chaining before aborting either channel.
    for(uint k=0;k<2;k++) hw_clear_bits(&dma_hw->ch[b->acq.channels[k]].ctrl_trig,DMA_CH0_CTRL_TRIG_EN_BITS);
    for(uint k=0;k<2;k++) dma_channel_abort(b->acq.channels[k]);
    dma_hw->ints0=(1u<<b->acq.channels[0])|(1u<<b->acq.channels[1]);
    adc_fifo_drain();
}

void base_acquisition_init(no5_base_t *b) {
    irq_owner=b;
adc_init(); adc_gpio_init(26); adc_select_input(0);
    b->acq.channels[0]=dma_claim_unused_channel(true); b->acq.channels[1]=dma_claim_unused_channel(true);
    irq_set_exclusive_handler(DMA_IRQ_0,irq_entry); irq_set_enabled(DMA_IRQ_0,true);
    gpio_init(TX_MARKER_GPIO); gpio_set_dir(TX_MARKER_GPIO,false); gpio_disable_pulls(TX_MARKER_GPIO);
    gpio_set_irq_enabled_with_callback(TX_MARKER_GPIO,GPIO_IRQ_EDGE_RISE,true,marker_irq);
}
void base_acquisition_prepare(no5_base_t *b) {

    base_stop_adc(b); b->acq.produced=b->acq.consumed=0; b->acq.marker_pending=false; b->acq.marker_rejected=0; b->acq.fault=0; b->acq.channel_block[0]=0; b->acq.channel_block[1]=1;
    // PING capture completion used to wait for an 8.192ms DMA boundary.
    // Use 2.048ms internal blocks in the clip PING mode, retaining the SAME
    // 32768-sample ring, sample rate, 8192-sample packets and Scope contract.
    b->acq.block_samples=COUNT;
#if NO5_CLIP_DIAGNOSTIC
    if(b->session.config.trigger==2) b->acq.block_samples=1024u;
#endif
    b->acq.ring_blocks=RING*COUNT/b->acq.block_samples;
    adc_fifo_setup(true,true,1,true,false);
    adc_hw->fcs |= ADC_FCS_OVER_BITS|ADC_FCS_UNDER_BITS;
    adc_set_clkdiv(b->session.config.period==2000?0.0f:(float)(b->session.config.period*48/1000-1));
    for(uint k=0;k<2;k++) {
        dma_channel_config dc=dma_channel_get_default_config(b->acq.channels[k]);
        channel_config_set_transfer_data_size(&dc,DMA_SIZE_16);
        channel_config_set_read_increment(&dc,false); channel_config_set_write_increment(&dc,true);
        channel_config_set_dreq(&dc,DREQ_ADC); channel_config_set_chain_to(&dc,b->acq.channels[1-k]);
        channel_config_set_high_priority(&dc,true);
        dma_channel_configure(b->acq.channels[k],&dc,b->acq.samples+k*b->acq.block_samples,
            &adc_hw->fifo,b->acq.block_samples,false);
        dma_channel_set_irq0_enabled(b->acq.channels[k],true);
    }
}
void base_acquisition_launch(no5_base_t *b) {
    b->acq.epoch_us=time_us_64(); b->acq.running=true;
    dma_start_channel_mask(1u<<b->acq.channels[0]); adc_run(true);
}
no5_block_view_t base_block_view(no5_base_t *b) {
    return (no5_block_view_t){ b->acq.samples+
        (b->acq.consumed%b->acq.ring_blocks)*b->acq.block_samples,
        b->acq.consumed,b->session.config.period,b->acq.epoch_us,ADC_FIFO_ERR_BITS };
}
uint32_t base_block_samples(const no5_base_t *b) { return b->acq.block_samples?b->acq.block_samples:COUNT; }
void base_release_block(no5_base_t *b, uint32_t block) { __dmb(); b->acq.consumed=block+1; }


bool base_take_tx_marker(no5_base_t *b, uint64_t start_us, uint32_t count,
                         uint32_t period_ns, uint32_t *index) {
    uint32_t irq=save_and_disable_interrupts();
    bool pending=b->acq.marker_enabled && b->acq.marker_pending;
    uint64_t marker=pending ? b->acq.marker_us : 0;
    uint64_t span_us=((uint64_t)count*period_ns+999u)/1000u;
    // A queued older DMA block must not discard the edge of a later block.
    if(pending && marker>=start_us && marker-start_us>=span_us) {
        restore_interrupts(irq);
        return false;
    }
    if(pending) b->acq.marker_pending=false;
    restore_interrupts(irq);
    if(!pending) return false;
    if(marker<start_us || marker-start_us>=span_us) {
        b->acq.marker_rejected++;
        return false;
    }
    uint64_t relative_ns=(marker-start_us)*1000u;
    uint32_t value=(uint32_t)((relative_ns+period_ns/2u)/period_ns);
    // A marker in the final half-sample is still INSIDE this block. Rounding
    // it to count used to consume and discard it. Use the nearest in-block
    // sample (<=1us error at the clip profile's 500 kS/s), never lose the edge.
    if(value>=count) value=count-1;
    *index=value;
    return true;
}
