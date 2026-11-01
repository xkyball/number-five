import asyncio
import json
from pathlib import Path
import time
from types import SimpleNamespace
import uuid

import pytest
from fastapi.testclient import TestClient

from no5_sonard_openecho.__main__ import create_host_app
from no5_sonard_openecho.depth_rate import DepthRateProfile
from no5_sonard_openecho.recovery import RecoveryController
from no5_sonard_openecho.transmit import TxCommandTimeout
from test_depth_rate import fixture


def setup(root):
    service, tx, wire = fixture(root)
    tx.profile = DepthRateProfile.load(Path(__file__).resolve().parents[1]/'deployment/clip-depth-qualified-0713.json')
    tx.client.hello.fw_patch = 13
    tx.client.hello.capabilities = 0xC0003FEE
    tx.client.clock = SimpleNamespace(ready=True)
    now = [0.]
    recovery = RecoveryController(service, clock=lambda: now[0])
    service.recovery = recovery
    calls = []
    pilot_pass = [True]

    async def update(policy):
        calls.append(('update', dict(policy)))
        tx.policy = dict(policy)
        tx.mode = policy['mode']
        tx.run = {'state': 'ARMED', 'duration_s': policy['duration_s'], 'verified': False}
        return {}

    async def fire(payload):
        key = str(uuid.UUID(payload['request_id']))
        if key in tx._requests: return dict(tx._requests[key])
        calls.append(('fire', key, tx.policy['duration_s']))
        tx._requests[key] = result = {'request_id': key, 'command_accepted': True}
        if tx.policy['duration_s'] == 2:
            tx.run.update(state='COMPLETE' if pilot_pass[0] else 'FAILED', verified=pilot_pass[0],
                          own_starts=22, physical_markers=22, raw_ping_links=22, usable_pings=19)
            tx.short_verified = pilot_pass[0]
            tx.mode = 'off'
        else: tx.run['state'] = 'RUNNING'
        return result

    async def abort():
        calls.append(('abort',))
        tx.mode = 'off'
        if tx.run and tx.run['state'] in {'ARMED', 'RUNNING'}: tx.run['state'] = 'ABORTED'
        return {}

    # Unit-test double for finite qualification. Actual physical matching,
    # geometry and usable-echo criteria remain covered by DepthRate tests.
    tx.update, tx.fire, tx.abort = update, fire, abort
    tx.snapshot = lambda: {'run': dict(tx.run or {})}
    return service, tx, recovery, now, calls, pilot_pass


async def authorize(recovery, tx):
    await recovery.set_policy({'mode': 'auto'})
    await recovery.operator_update({'mode': 'periodic', 'duration_s': 0,
                                   'rate_policy': 'depth_auto', 'interval_pattern': 'off'})
    await recovery.operator_fire({'request_id': str(uuid.uuid4())})
    assert recovery.desired is not None
    tx.run.update(state='FAILED', reason='native_reference_lost')
    tx.mode = 'off'
    await recovery.tick()


def healthy(service, recovery, now, *, start=0., count=12, same=False):
    for n in range(count):
        now[0] = start+n*.4
        service._last_raw_host_at = now[0]
        recovery.observe_reference({'meaning': 'qualified', 'observation_kind': 'marker_edge',
            'native_stable': 100+n, 'native_period_us': 386000,
            'native_last_device_us': 1000000+(0 if same else n*386000)+int(start*1e6),
            'observed_utc_ns': time.time_ns()})
        # Tests health without starting an attempt until the series ends.
        recovery._health()


def test_late_stop_ack_keeps_supervisor_alive_and_requires_stop_then_fresh_pilot(tmp_path):
    s,tx,r,now,calls,_=setup(tmp_path)
    async def run():
        await authorize(r,tx)
        original_abort=tx.abort;stops=[0]
        async def flaky_abort():
            stops[0]+=1
            if stops[0]<=2:raise TxCommandTimeout(0x43,stops[0],1.5)
            return await original_abort()
        tx.abort=flaky_abort;r._was_running=True
        calls.clear()
        await r.tick()
        assert r.state=='WAIT_STOP' and r.snapshot()['pending_stop_confirmation']
        assert not calls and stops[0]==1
        await r.tick()  # bounded backoff, not a busy retry
        assert stops[0]==1
        healthy(s,r,now,start=10);await r.tick()
        assert r.state=='WAIT_STOP' and stops[0]==2
        healthy(s,r,now,start=30);await r.tick()
        assert r.state=='WAIT_HEALTH' and not r.snapshot()['pending_stop_confirmation']
        assert not any(c[0]=='fire' for c in calls)
        await r.tick()
        assert r._attempt_task is None  # old stability cannot cross STOP confirmation
        healthy(s,r,now,start=40);await r.tick();await r._attempt_task
        assert [c[2] for c in calls if c[0]=='fire']==[2,0]
        assert r.state=='RUNNING' and r.recoveries==1
        await r.operator_abort()
    asyncio.run(run())


def test_operator_stop_revokes_pending_stop_retry_and_never_rearms(tmp_path):
    s,tx,r,now,calls,_=setup(tmp_path)
    async def run():
        await authorize(r,tx);original_abort=tx.abort
        async def missing():raise TxCommandTimeout(0x43,1,1.5)
        tx.abort=missing;r._was_running=True
        await r.tick();assert r._stop_pending
        tx.abort=original_abort;await r.operator_abort();calls.clear()
        healthy(s,r,now,start=20);await r.tick()
        assert r.desired is None and not r._stop_pending and not calls
    asyncio.run(run())


def test_storage_failure_still_holds_without_any_automatic_retry(tmp_path):
    _,tx,r,_,calls,_=setup(tmp_path)
    async def run():
        await authorize(r,tx)
        async def broken():raise OSError('archive is not writable')
        tx.abort=broken;r._was_running=True;calls.clear()
        r.start();await asyncio.wait_for(r._task,1)
        assert r.state=='HOLD' and 'archive' in r.reason
        assert not any(c[0]=='fire' for c in calls)
    asyncio.run(run())


def test_recovery_needs_advancing_physical_clock_raw_and_pilot(tmp_path):
    s, tx, r, now, calls, _ = setup(tmp_path)
    async def run():
        await authorize(r, tx)
        original = r.desired['authorized_fire']
        calls.clear()
        healthy(s, r, now, same=True)
        await r.tick()
        assert not any(c[0] == 'fire' for c in calls)
        healthy(s, r, now, start=10)
        assert r._health() is None
        await r.tick(); await r._attempt_task
        fires = [c for c in calls if c[0] == 'fire']
        assert [c[2] for c in fires] == [2, 0]
        assert len({original, fires[0][1], fires[1][1]}) == 3
        assert r.recoveries == 1 and r.state == 'RUNNING'
        assert r.last_result['pilot']['physical_markers'] == 22
        assert r.desired['authorized_fire'] == original
    asyncio.run(run())


@pytest.mark.parametrize('bad', ['raw_stale', 'clock', 'identity', 'profile', 'geometry', 'no_usb', 'quarantine'])
def test_bad_health_never_starts_a_pilot(tmp_path, bad):
    s, tx, r, now, calls, _ = setup(tmp_path)
    async def run():
        await authorize(r, tx)
        healthy(s, r, now)
        if bad == 'raw_stale': now[0] += 1.1
        elif bad == 'clock': tx.client.clock.ready = False
        elif bad == 'identity': tx.client.hello.device_serial = 'wrong'
        elif bad == 'profile': r.desired['binding']['profile_sha256'] = 'wrong'
        elif bad == 'geometry': tx.geometry_errors = 1
        elif bad == 'no_usb': tx.client = None
        elif bad == 'quarantine': r.observe_reference({'meaning': 'quarantine'})
        calls.clear(); await r.tick()
        assert r.state == 'WAIT_HEALTH' and not any(c[0] == 'fire' for c in calls)
    asyncio.run(run())


def test_counter_change_requires_new_stability_and_failed_pilot_backs_off(tmp_path):
    s, tx, r, now, calls, passed = setup(tmp_path)
    async def run():
        await authorize(r, tx)
        healthy(s, r, now)
        tx.client.packet_seq_gaps += 1
        assert r._health() == 'waiting for stable acquisition counters'
        healthy(s, r, now, start=10)
        passed[0] = False
        await r.tick(); await r._attempt_task
        assert r.state == 'BACKOFF' and r.recoveries == 0 and tx.mode == 'off'
        assert r.snapshot()['retry_in_s'] == pytest.approx(5)
        assert [c[2] for c in calls if c[0] == 'fire'] == [0, 2]
        calls.clear(); await r.tick()
        assert not calls
    asyncio.run(run())


@pytest.mark.parametrize('stage', ['update', 'pilot_fire', 'pilot_wait', 'continuous_fire'])
def test_stop_cancels_inflight_pilot_and_late_continuous_ack(tmp_path, stage):
    s, tx, r, now, calls, _ = setup(tmp_path)
    async def run():
        await authorize(r, tx); healthy(s, r, now)
        entered = asyncio.Event()
        update, fire = tx.update, tx.fire
        async def delayed_update(policy):
            result = await update(policy)
            if stage == 'update': entered.set(); await asyncio.Event().wait()
            return result
        async def delayed_fire(payload):
            duration = tx.policy['duration_s']
            if (stage == 'pilot_fire' and duration == 2) or (stage == 'continuous_fire' and duration == 0):
                entered.set(); await asyncio.Event().wait()
            result = await fire(payload)
            if stage == 'pilot_wait' and duration == 2:
                tx.run['state'] = 'RUNNING'; entered.set()
            return result
        tx.update, tx.fire = delayed_update, delayed_fire
        await r.tick(); await asyncio.wait_for(entered.wait(), 1)
        await r.operator_abort()
        assert r.desired is None and tx.mode == 'off'
        assert json.loads(r.path.read_text())['desired'] is None
        healthy(s, r, now, start=20)
        before = len(calls); await r.tick()
        assert len(calls) == before and r.recoveries == 0
    asyncio.run(run())


def test_persisted_intent_requalifies_but_duplicate_fire_after_stop_does_not_restore_it(tmp_path):
    s, tx, r, now, calls, _ = setup(tmp_path)
    async def run():
        await authorize(r, tx)
        request = r.desired['authorized_fire']
        restored = RecoveryController(s, clock=lambda: now[0])
        assert restored.desired == r.desired and restored.state == 'WAIT_HEALTH'
        # A process restart restores intent, never an armed device or an old FIRE.
        assert restored._health() is not None
        await r.operator_abort()
        await r.operator_fire({'request_id': request.upper()})
        assert r.desired is None
        assert RecoveryController(s).desired is None
    asyncio.run(run())


def test_api_default_validation_and_enabling_alone_never_arms(tmp_path):
    s, tx, _ = fixture(tmp_path)
    api = TestClient(create_host_app(s))
    assert api.get('/api/v1/transmit/recovery').json()['mode'] == 'off'
    assert api.put('/api/v1/transmit/recovery', json={'mode':'auto'}).status_code == 409
    assert api.put('/api/v1/transmit/recovery', json={'mode':'auto', 'skip_pilot':True}).status_code == 422
    tx.profile = DepthRateProfile.load(Path(__file__).resolve().parents[1]/'deployment/clip-depth-qualified-0713.json')
    assert api.put('/api/v1/transmit/recovery', json={'mode':'auto'}).status_code == 200
    assert tx.mode == 'off' and s.recovery.desired is None


def test_usb_reconnect_is_only_for_retained_explicit_intent(tmp_path, monkeypatch):
    from no5_sonard.transport.serial_posix import SerialDisconnected
    s, tx, r, now, calls, _ = setup(tmp_path)
    async def run():
        await authorize(r, tx)
        attempts = []
        async def connection():
            attempts.append(True)
            if len(attempts) == 1: raise SerialDisconnected('test USB loss')
            s._running = False
        monkeypatch.setattr(s, '_run_pico_connection', connection)
        s._running = True; s._client = s._writer = None
        await s._supervisor()
        assert len(attempts) == 2 and s.status.reconnects == 1
        await r.operator_abort()
        attempts.clear(); s._running = True
        await s._supervisor()
        assert len(attempts) == 1 and s.status.state == 'ERROR'
    asyncio.run(run())


def test_old_or_future_reference_packet_does_not_count_as_healthy(tmp_path):
    s, tx, r, now, calls, _ = setup(tmp_path)
    async def run():
        await authorize(r, tx)
        for age in [2., -2.]:
            r.observe_reference({'meaning':'qualified', 'observation_kind':'marker_edge',
                'native_stable':100, 'native_period_us':386000,
                'native_last_device_us':1000000, 'observed_utc_ns':time.time_ns()-int(age*1e9)})
            assert not r._markers
    asyncio.run(run())


def test_stop_still_aborts_when_persist_fails_and_no_intent_survives_in_memory(tmp_path, monkeypatch):
    s, tx, r, now, calls, _ = setup(tmp_path)
    async def run():
        await authorize(r, tx)
        def broken(): raise OSError('disk failure')
        monkeypatch.setattr(r, '_persist', broken)
        with pytest.raises(OSError): await r.operator_abort()
        assert tx.mode == 'off' and r.desired is None and calls[-1] == ('abort',)
    asyncio.run(run())


def test_identity_failure_during_rebind_holds_without_start_retry(tmp_path, monkeypatch):
    s, tx, r, now, calls, _ = setup(tmp_path)
    async def run():
        await authorize(r, tx)
        count = []
        async def wrong_peer():
            count.append(1)
            raise RuntimeError('HELLO identity/capabilities differ from the selected device')
        monkeypatch.setattr(s, '_run_pico_connection', wrong_peer)
        s._running = True; s._client = s._writer = None
        await s._supervisor()
        assert len(count) == 1 and s.status.state == 'ERROR'
    asyncio.run(run())


def test_stop_while_operator_fire_ack_is_pending_cannot_recreate_intent(tmp_path):
    s, tx, r, now, calls, _ = setup(tmp_path)
    async def run():
        await r.set_policy({'mode':'auto'})
        await r.operator_update({'mode':'periodic','duration_s':0,'rate_policy':'depth_auto','interval_pattern':'off'})
        original = tx.fire; entered = asyncio.Event(); release = asyncio.Event()
        async def delayed(payload):
            entered.set(); await release.wait()
            return await original(payload)
        tx.fire = delayed
        firing = asyncio.create_task(r.operator_fire({'request_id':str(uuid.uuid4())}))
        await entered.wait()
        stopping = asyncio.create_task(r.operator_abort())
        await asyncio.sleep(0)  # Intent is revoked before STOP waits for FIRE's lock.
        release.set(); await firing; await stopping
        assert r.desired is None and tx.mode == 'off'
        assert json.loads(r.path.read_text())['desired'] is None
    asyncio.run(run())


def test_start_is_idempotent_and_clean_shutdown_revokes_saved_intent(tmp_path):
    s, tx, r, now, calls, _ = setup(tmp_path)
    async def run():
        await authorize(r, tx)
        r.start(); task = r._task
        r.start(); assert r._task is task
        await r.close()
        assert task.done() and r.desired is None and tx.mode == 'off'
        assert RecoveryController(s).desired is None
    asyncio.run(run())
