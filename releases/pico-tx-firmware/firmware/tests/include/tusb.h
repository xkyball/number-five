#pragma once
#include <stdint.h>
#include <stdbool.h>
void tud_task(void);
uint32_t tud_cdc_write_available(void);
uint32_t tud_cdc_write(const void *, uint32_t);
void tud_cdc_write_flush(void);
