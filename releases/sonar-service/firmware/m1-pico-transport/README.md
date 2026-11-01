# No 5 Pico M1 transport firmware

This is the RP2040 side of `NO5-PICO/1`.

It implements:

- TinyUSB CDC device transport;
- COBS framing and CRC-32/IEEE;
- HELLO / CONFIG / START / STOP / STATUS;
- TIME_SYNC request/response using `time_us_64()`;
- PING_FRAME transfer;
- first ADC0 (GPIO26) DMA capture path;
- external GPIO trigger (GPIO15) or analog-threshold trigger;
- optional synthetic 20 Hz bench source.

The RP2040 ADC is rated up to 500 kS/s, and the Pico SDK documents that a
conversion takes 96 cycles of the 48 MHz ADC clock. The code derives the ADC
clock divider from the requested sample period and rejects periods below 2 us.

## Build

Install the Raspberry Pi Pico SDK and export `PICO_SDK_PATH`, then:

```bash
mkdir build && cd build
cmake ..
cmake --build . -j
```

For USB-only bench validation before the Venterior signal is connected:

```bash
mkdir build-test && cd build-test
cmake -DNO5_SYNTHETIC_TEST=ON ..
cmake --build . -j
```

Flash `no5_pico_m1.uf2` to the Pico.

## Important P1 hardware boundary

The firmware's ADC and trigger paths are real, but **do not connect the Venterior
transducer/high-voltage line to GPIO26**. M1 expects the future safe, low-voltage
receive/envelope tap described by P1. GPIO inputs must remain within RP2040
limits and the exact divider/clamp interface must be finalized after the
Venterior tap point is identified.

M1 analog-threshold capture currently sets `tx_zero_sample_index = 0` and does
not yet implement the protocol's circular pretrigger feature. That is deliberate:
pretrigger is a firmware refinement after the actual Venterior trigger point is
known.

The supplied USB VID/PID (`CAFE:4010`) is for development/testing and should be
replaced with an appropriately assigned production identity before any product
release.
