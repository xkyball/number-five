#pragma once
#include "transmit.h"
struct no5_app;
void transmit_hw_init(struct no5_app *);
void transmit_hw_stop(struct no5_app *, uint32_t);
void transmit_hw_poll(struct no5_app *);
void transmit_hw_command(struct no5_app *, uint8_t, const uint8_t *, uint32_t);
