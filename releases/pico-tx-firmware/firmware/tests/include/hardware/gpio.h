#pragma once
#include "offline_sdk.h"
#define GPIO_IRQ_EDGE_RISE 8u
void gpio_init(uint); void gpio_set_dir(uint,bool); void gpio_disable_pulls(uint); void gpio_set_irq_enabled_with_callback(uint,uint32_t,bool,void (*)(uint,uint32_t));
