#pragma once
#include <stdbool.h>
#include <stdint.h>

enum { TX_OFF, TX_MANUAL, TX_PERIODIC };
enum { TX_OK, TX_INVALID, TX_UNARMED, TX_BUSY, TX_DUPLICATE, TX_NO_MARKER,
       TX_LEASE_EXPIRED, TX_STOPPED, TX_DISCONNECTED, TX_ACQUISITION_FAULT,
       TX_NO_PING };
enum { TX_STARTED=1, TX_MARKED, TX_FINISHED, TX_FAILED, TX_ABORTED, TX_PING_LINKED };
#pragma pack(push, 1)
typedef struct { uint32_t request, frequency_hz, high_ns, cycles, period_us, marker_timeout_us; } tx_config_t;
typedef struct { uint32_t request, mode, lease_us; } tx_arm_t;
typedef struct { uint32_t request; uint64_t id; } tx_fire_t;
typedef struct { uint32_t request, result, state, mode; uint64_t lease_until_us; } tx_ack_t;
typedef struct { uint32_t event, reason; uint64_t id, output_start_us, marker_us, ping_id; } tx_event_t;
#pragma pack(pop)
_Static_assert(sizeof(tx_config_t)==24 && sizeof(tx_event_t)==40 && sizeof(tx_ack_t)==24, "TX wire layout");

typedef struct {
    tx_config_t config;
    bool configured, armed, active, marked, finished, need_ping, ping_mode;
    uint32_t mode, error;
    uint64_t lease_until, next_due, last_start, last_id, marker_us, ping_deadline;
    void *context;
    void (*start)(void *, const tx_config_t *);
    void (*idle)(void *);
    void (*event)(void *, const tx_event_t *);
} tx_state_t;

void tx_init(tx_state_t *);
uint32_t tx_configure(tx_state_t *, const tx_config_t *);
uint32_t tx_arm(tx_state_t *, const tx_arm_t *, uint64_t, bool);
uint32_t tx_fire(tx_state_t *, uint64_t, uint64_t, bool, bool);
void tx_abort(tx_state_t *, uint32_t);
void tx_poll(tx_state_t *, uint64_t, bool, bool, uint64_t);
void tx_link_ping(tx_state_t *, uint64_t, uint64_t, uint32_t);
