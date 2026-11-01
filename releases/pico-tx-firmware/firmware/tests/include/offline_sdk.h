#pragma once
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
typedef unsigned uint;
#define __isr
#define ADC_FIFO_ERR_BITS 0x8000u
#define ADC_FCS_OVER_BITS 0x0800u
#define ADC_FCS_UNDER_BITS 0x0400u
#define DMA_CH0_CTRL_TRIG_EN_BITS 1u
#define DMA_SIZE_16 1u
#define DREQ_ADC 36u
#define DMA_IRQ_0 0u
typedef struct { volatile uint32_t fcs, fifo; } adc_hw_t;
typedef struct { volatile uint32_t ctrl_trig; } dma_channel_hw_t;
typedef struct { volatile uint32_t ints0; dma_channel_hw_t ch[2]; } dma_hw_t;
extern adc_hw_t *adc_hw; extern dma_hw_t *dma_hw;
typedef struct { uint32_t size, read_increment, write_increment, dreq, chain, priority; } dma_channel_config;
void adc_init(void); void adc_gpio_init(uint); void adc_select_input(uint); void adc_run(bool); void adc_fifo_drain(void); void adc_fifo_setup(bool,bool,uint,bool,bool); void adc_set_clkdiv(float);
uint dma_claim_unused_channel(bool); dma_channel_config dma_channel_get_default_config(uint); void channel_config_set_transfer_data_size(dma_channel_config *,uint); void channel_config_set_read_increment(dma_channel_config *,bool); void channel_config_set_write_increment(dma_channel_config *,bool); void channel_config_set_dreq(dma_channel_config *,uint); void channel_config_set_chain_to(dma_channel_config *,uint); void channel_config_set_high_priority(dma_channel_config *,bool); void dma_channel_configure(uint,const dma_channel_config *,void *,const volatile void *,uint,bool); void dma_channel_set_irq0_enabled(uint,bool); void dma_channel_set_write_addr(uint,void *,bool); void dma_channel_set_trans_count(uint,uint,bool); void dma_channel_abort(uint); void dma_start_channel_mask(uint32_t); void hw_clear_bits(volatile uint32_t *,uint32_t); void irq_set_exclusive_handler(uint,void (*)(void)); void irq_set_enabled(uint,bool); void __dmb(void);
uint64_t time_us_64(void); uint32_t save_and_disable_interrupts(void); void restore_interrupts(uint32_t);
