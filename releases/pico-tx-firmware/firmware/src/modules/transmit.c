#include <string.h>
#include "transmit.h"

static void emit(tx_state_t *s,uint32_t event,uint32_t reason,uint64_t ping) {
    tx_event_t e={event,reason,s->last_id,s->last_start,s->marker_us,ping};
    if(s->event) s->event(s->context,&e);
}
void tx_init(tx_state_t *s) { memset(s,0,sizeof(*s)); }
uint32_t tx_configure(tx_state_t *s,const tx_config_t *c) {
    // Electrical qualification is additionally required in the host profile.
    if(s->armed || s->active) return TX_BUSY;
    if(c->frequency_hz<180000 || c->frequency_hz>220000 || !c->cycles || c->cycles>128 ||
       c->high_ns<500 || c->high_ns>2500 || c->period_us<200000 || c->period_us>10000000 ||
       c->marker_timeout_us<100 || c->marker_timeout_us>10000 ||
       1000000000u/c->frequency_hz-c->high_ns<2000) return TX_INVALID;
    s->config=*c; s->configured=true; s->error=TX_OK; return TX_OK;
}
uint32_t tx_arm(tx_state_t *s,const tx_arm_t *a,uint64_t now,bool ready) {
    if(!s->configured || !ready || (a->mode!=TX_MANUAL && a->mode!=TX_PERIODIC) ||
       a->lease_us<100000 || a->lease_us>2000000) return TX_INVALID;
    if(s->armed && s->mode!=a->mode) return TX_BUSY;
    if(!s->armed && s->error!=TX_OK) return TX_UNARMED;
    if(!s->armed) {
        s->next_due=now+10000;
        if(s->last_start && s->next_due<s->last_start+s->config.period_us)
            s->next_due=s->last_start+s->config.period_us;
    }
    s->armed=true; s->mode=a->mode; s->lease_until=now+a->lease_us;
    return TX_OK;
}
uint32_t tx_fire(tx_state_t *s,uint64_t id,uint64_t now,bool ready,bool ping) {
    if(!s->armed || now>=s->lease_until) return TX_UNARMED;
    if(id<=s->last_id) return TX_DUPLICATE;
    if(!ready || s->active || (s->last_start && now-s->last_start<s->config.period_us)) return TX_BUSY;
    s->last_id=id; s->last_start=now; s->marker_us=0; s->marked=s->finished=false;
    s->active=true; s->need_ping=s->ping_mode=ping; s->error=TX_OK;
    s->ping_deadline=now+100000;
    s->next_due=now+s->config.period_us;
    s->start(s->context,&s->config); emit(s,TX_STARTED,TX_OK,0); return TX_OK;
}
void tx_abort(tx_state_t *s,uint32_t reason) {
    bool had_work=s->armed || s->active;
    if(s->idle) s->idle(s->context);
    s->armed=s->active=false; s->mode=TX_OFF; s->error=reason;
    if(had_work) emit(s,reason==TX_STOPPED?TX_ABORTED:TX_FAILED,reason,0);
}
void tx_poll(tx_state_t *s,uint64_t now,bool ready,bool done,uint64_t marker) {
    if(!s->armed) return;
    if(now>=s->lease_until) { tx_abort(s,TX_LEASE_EXPIRED); return; }
    if(s->active) {
        if(!s->marked && marker>=s->last_start && marker-s->last_start<=s->config.marker_timeout_us) {
            s->marked=true; s->marker_us=marker; emit(s,TX_MARKED,TX_OK,0);
        }
        if(!s->marked && now-s->last_start>s->config.marker_timeout_us) { tx_abort(s,TX_NO_MARKER); return; }
        if(done && !s->finished) { s->finished=true; emit(s,TX_FINISHED,TX_OK,0); }
        if(s->finished && s->marked && !s->need_ping) s->active=false;
        if(s->active && s->need_ping && now>s->ping_deadline) { tx_abort(s,TX_NO_PING); return; }
    }
    if(s->mode==TX_PERIODIC && !s->active && now>=s->next_due) {
        if(!ready) { tx_abort(s,TX_BUSY); return; }
        (void)tx_fire(s,s->last_id+1,now,ready,s->ping_mode);
    }
}
void tx_link_ping(tx_state_t *s,uint64_t id,uint64_t marker,uint32_t tolerance_us) {
    uint64_t delta=marker>s->marker_us?marker-s->marker_us:s->marker_us-marker;
    if(s->active && s->marked && s->need_ping && delta<=tolerance_us) {
        emit(s,TX_PING_LINKED,TX_OK,id); s->need_ping=false;
        if(s->finished) s->active=false;
    }
}
