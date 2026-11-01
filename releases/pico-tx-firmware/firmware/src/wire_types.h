#pragma once
#include <stdint.h>
#include "modules/capture.h"

#define COUNT 4096u
#define RING 8u
#define PAYLOAD_MAX (36 + MAX_PING_SAMPLES * 2)
#define RAW_MAX (16 + PAYLOAD_MAX + 4)
#define ENCODED_MAX (RAW_MAX + RAW_MAX / 254 + 3)
enum { HELLO=1, HELLO_ACK=2, CONFIG=0x10, CONFIG_ACK=0x11,
       START=0x12, STOP=0x13, BOOTSEL=0x14, PING=0x20, STATUS=0x21, STREAM=0x22, TIME_REQ=0x30, TIME_RESP=0x31,
       TX_CONFIG=0x40, TX_ARM=0x41, TX_FIRE=0x42, TX_ABORT=0x43, TX_ACK=0x44, TX_EVENT=0x45,
       TX_CLIP_CONFIG=0x46, TX_DEPTH_CONFIG=0x47 };
enum { ERR_USB=1, ERR_RING=2, ERR_FIFO=3, ERR_ADC=4, ERR_DMA=5 };
#pragma pack(push, 1)
typedef struct { uint8_t magic[2], version, type; uint32_t seq, length, flags; } header_t;
typedef struct { uint8_t major, minor, patch, reserved; uint32_t caps, rate, count; uint16_t bits, mv; char serial[16]; } hello_t;
typedef struct { uint32_t revision, period, count; uint16_t pre; uint8_t trigger, signal; uint16_t threshold, hysteresis; uint32_t timeout, reserved; } config_t;
typedef struct { uint32_t revision, status, period, count; } ack_t;
typedef struct { uint64_t block, first_index, time_us; uint32_t period, count, flags; uint16_t min, max; } stream_t;
typedef struct { uint64_t id, time_us; uint32_t period, count; int32_t zero; uint16_t min, max; uint32_t flags; } ping_t;
typedef struct { uint32_t token; uint64_t rx_us, tx_us; } sync_t;
typedef struct { uint64_t uptime, blocks; uint32_t dropped, misses, overruns, usb_errors, error; int32_t temperature; } status_t;
#pragma pack(pop)
_Static_assert(sizeof(header_t)==16 && sizeof(stream_t)==40 && sizeof(config_t)==28, "wire layout");
_Static_assert(sizeof(ping_t)==36 && sizeof(sync_t)==20, "M1 wire layout");

