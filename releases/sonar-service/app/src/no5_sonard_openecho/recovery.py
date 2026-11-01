"""Explicit continuous-run intent, recovered through fresh physical qualification.

This is a host supervisor, not an exemption from any Pico stop condition. Every
recovery uses a new finite pilot and new durable FIRE UUIDs. No raw/DSP inputs
are changed and an operator STOP revokes intent before waiting for USB.
"""
from __future__ import annotations

import asyncio
from collections import deque
import json
import os
from pathlib import Path
import tempfile
import time
import uuid
from no5_sonard.transport.serial_posix import SerialDisconnected
from .transmit import TxCommandTimeout


class RecoveryController:
    CHECK_S = .25
    STABLE_S = 3.
    MIN_MARKERS = 8
    MAX_AGE_S = 1.
    PILOT_TIMEOUT_S = 8.

    def __init__(self, service, *, clock=time.monotonic):
        self.service = service
        self.tx = service.transmit
        self.clock = clock
        self.path = Path(getattr(service, 'control_root', service.config.storage_root)) / 'transmit-recovery.json'
        self.stop_path = (self.path.parent/'operator-stop.json'
                          if self.path.parent!=Path(service.config.storage_root)else None)
        saved = json.loads(self.path.read_text()) if self.path.exists() else {}
        if saved and (saved.get('schema') != 'no5.transmit-recovery-policy/1'
                      or saved.get('mode') not in {'off', 'auto'}):
            raise ValueError('invalid recovery policy; TX remains unarmed')
        self.mode = saved.get('mode', 'off')
        self.last_start_request = saved.get('last_start_request')
        self.desired = saved.get('desired') if self.mode == 'auto' else None
        if self.stop_path is not None and self.stop_path.exists():self.desired=None
        self.state = 'WAIT_HEALTH' if self.desired else 'IDLE'
        self.reason = 'restored intent requires fresh physical qualification' if self.desired else 'no continuous-run intent'
        self.generation = 0
        self.attempts = self.recoveries = 0
        self.last_result = None
        self._markers = deque(maxlen=64)
        self._last_marker = None
        self._health_key = None
        self._stable_since = None
        self._healthy_counters = None
        self._failures = 0
        self._next_attempt_at = 0.
        self._control = asyncio.Lock()
        self._task = self._attempt_task = None
        self._closed = False
        self._was_running = False
        self._stop_pending = bool(self.desired)
        self._stop_failures = 0
        self._next_stop_at = 0.

    def _binding(self):
        p = self.tx.profile
        return None if p is None else {'serial': p.serial, 'profile_sha256': p.sha256,
                                      'firmware': list(getattr(p, 'firmware', ()))}

    def _supported(self):
        from .depth_rate import DepthRateController
        return (isinstance(self.tx, DepthRateController)
                and self.tx.profile.continuous_run_supported
                and self.tx.profile.firmware == (0, 7, 13)
                and self.tx.trial_spacing_us is None
                and self.tx.diagnostic_source == 'ECHO_AD')

    def _persist(self):
        self.path.parent.mkdir(parents=True, exist_ok=True)
        fd, name = tempfile.mkstemp(dir=self.path.parent, prefix='.recovery-')
        try:
            with os.fdopen(fd, 'w') as out:
                json.dump({'schema': 'no5.transmit-recovery-policy/1', 'mode': self.mode,
                           'desired': self.desired, 'last_start_request':self.last_start_request}, out, sort_keys=True)
                out.flush(); os.fsync(out.fileno())
            os.replace(name, self.path)
            directory = os.open(self.path.parent, os.O_RDONLY)
            try: os.fsync(directory)
            finally: os.close(directory)
        finally:
            Path(name).unlink(missing_ok=True)

    def _event(self, event, **fields):
        self.tx._archive('recovery_' + event, generation=self.generation, **fields)

    def snapshot(self):
        return {'schema': 'no5.transmit-recovery-status/1', 'mode': self.mode,
                'state': self.state, 'reason': self.reason,
                'desired': self.desired, 'attempts': self.attempts,
                'recoveries': self.recoveries, 'last_result': self.last_result,
                'pending_stop_confirmation': self._stop_pending,
                'stable_markers': len(self._markers),
                'stable_for_s': 0 if self._stable_since is None else max(0, self.clock()-self._stable_since),
                'retry_in_s': max(0, self._next_attempt_at-self.clock()),
                'requirements': {'stable_s': self.STABLE_S, 'unique_native_markers': self.MIN_MARKERS,
                                 'max_age_s': self.MAX_AGE_S, 'pilot_s': 2,
                                 'backoff_s': [5, 10, 20, 40, 60]},
                'raw_samples_modified': False,
                'firmware_stop_conditions_unchanged': True}

    def start(self):
        if self._task and not self._task.done(): return
        self._closed = False
        self._task = asyncio.create_task(self._loop(), name='tx-recovery-health')

    def _reset_health(self):
        self._markers.clear()
        self._stable_since = self._healthy_counters = None

    def observe_reference(self, row):
        """Only newly received physical edges count, never polls of a stale row."""
        marker = row.get('native_last_device_us')
        stamp = row.get('observed_utc_ns')
        valid = (row.get('meaning') == 'qualified' and row.get('observation_kind') == 'marker_edge'
                 and row.get('native_stable', 0) >= 3
                 and 380000 <= row.get('native_period_us', 0) <= 395000
                 and type(marker) is int and type(stamp) is int
                 and -.1 <= (time.time_ns()-stamp)/1e9 <= self.MAX_AGE_S)
        if not valid:
            self._reset_health()
            return
        if self._last_marker is not None and marker <= self._last_marker:
            if marker < self._last_marker: self._reset_health()
            return
        self._last_marker = marker
        self._markers.append((marker, self.clock()))

    def _health(self):
        c = self.tx.client
        key = self.tx.connection_id
        if key != self._health_key:
            self._health_key = key
            self._last_marker = None
            self._reset_health()
        reason = None
        if not self._supported(): reason = 'unsupported or experimental capture profile'
        elif not self.desired or self.desired.get('binding') != self._binding(): reason = 'saved intent/profile mismatch'
        elif self.service.status.state != 'RUNNING' or not c: reason = 'waiting for USB acquisition'
        elif (not c.hello or c.hello.device_serial != self.tx.profile.serial
              or (c.hello.fw_major, c.hello.fw_minor, c.hello.fw_patch) != self.tx.profile.firmware
              or c.hello.capabilities != 0xC0003FEE): reason = 'identity/capability mismatch'
        elif not c.clock.ready: reason = 'waiting for accepted USB clock'
        elif (self.service._last_raw_host_at is None
              or self.clock()-self.service._last_raw_host_at > self.MAX_AGE_S): reason = 'waiting for fresh raw acquisition'
        elif self.tx.geometry_errors: reason = 'capture geometry error requires operator review'
        elif not self._markers or self.clock()-self._markers[-1][1] > self.MAX_AGE_S: reason = 'waiting for fresh original markers'
        if reason:
            self._reset_health()
            return reason
        counters = dict(self.tx._errors())
        status = getattr(c.transport, 'last_device_status', None) or {}
        counters.update({key: status.get(key) for key in ('adc_overruns', 'usb_errors', 'dropped_frames')})
        if self._healthy_counters != counters:
            self._healthy_counters = counters
            self._stable_since = self.clock()
            self._markers.clear()
            return 'waiting for stable acquisition counters'
        if self._stable_since is None: self._stable_since = self.clock()
        if (len(self._markers) < self.MIN_MARKERS or self.clock()-self._stable_since < self.STABLE_S
                or self._markers[-1][1]-self._markers[0][1] < self.STABLE_S):
            return 'qualifying stable original clock'
        return None

    @property
    def reconnect_enabled(self):
        return self.mode == 'auto' and self.desired is not None and not self._closed

    async def set_policy(self, payload):
        if not isinstance(payload, dict) or set(payload) != {'mode'} or payload['mode'] not in {'off', 'auto'}:
            raise ValueError('recovery policy accepts only mode: off or auto')
        if payload['mode'] == 'auto' and not self._supported():
            raise RuntimeError('automatic recovery requires the qualified 0.7.13 ECHO_AD profile')
        if payload['mode'] == 'off':
            # Disabling recovery also stops output; no hidden continuing intent.
            self.mode = 'off'
            await self.operator_abort()
        else:
            self.mode = 'auto'
            self._persist()
            self._event('policy', mode=self.mode)
        return self.snapshot()

    async def _cancel_attempt(self):
        task, self._attempt_task = self._attempt_task, None
        if task:
            task.cancel()
            try: await task
            except asyncio.CancelledError: pass
            except Exception as exc:
                self.last_result = {'failed': True, 'reason': f'{type(exc).__name__}: {exc}'}

    def _revoke(self, reason):
        self.generation += 1
        self.desired = None
        self._stop_pending = False
        self.state, self.reason = 'IDLE', reason
        self._reset_health()
        self._persist()
        self._event('intent_revoked', reason=reason)

    async def operator_abort(self):
        try:
            if self.stop_path is not None:
                self.stop_path.parent.mkdir(parents=True,exist_ok=True)
                with self.stop_path.open('w')as out:
                    json.dump({'operator_stop':True,'utc_ns':time.time_ns()},out)
                    out.flush();os.fsync(out.fileno())
            self._revoke('operator STOP; automatic restart cancelled')
        finally:
            await self._cancel_attempt()
            async with self._control:
                result = await self.tx.abort()
        return result

    async def operator_update(self, payload):
        # A new operator policy replaces any old recovery intent, including
        # when it is changed during a pilot. Only a NEW successful FIRE adopts it.
        self._revoke('operator policy transition; waiting for fresh FIRE')
        await self._cancel_attempt()
        async with self._control:
            return await self.tx.update(payload)

    async def operator_fire(self, payload):
        async with self._control:
            generation = self.generation
            try: old = str(uuid.UUID(payload['request_id']))
            except (ValueError, TypeError, AttributeError, KeyError): old = None
            duplicate = old in self.tx._requests
            result = await self.tx.fire(payload)
            if (generation == self.generation and not self._closed
                    and self.mode == 'auto' and not duplicate and result.get('command_accepted')
                    and self._supported() and self.tx.policy.get('duration_s') == 0
                    and self.tx.mode == 'periodic'
                    and self.tx.policy.get('interval_pattern') == 'off'):
                self.generation += 1
                self.desired = {'intent_id': str(uuid.uuid4()), 'binding': self._binding(),
                                'policy': dict(self.tx.policy), 'authorized_fire': result['request_id'],
                                'created_utc_ns': time.time_ns()}
                try: self._persist()
                except Exception:
                    self.desired = None
                    await self.tx.abort()
                    raise
                self._was_running = True
                self._stop_pending = False
                self.state, self.reason = 'RUNNING', 'monitoring authorized continuous run'
                self._event('intent_started', intent=self.desired)
            return result

    async def close(self, *, operator_stop=True):
        self._closed = True
        if self._task:
            self._task.cancel()
            try: await self._task
            except asyncio.CancelledError: pass
            self._task = None
        if operator_stop:
            await self.operator_abort()
        else:
            await self._cancel_attempt()
            async with self._control:
                await self.tx.abort()
            self.state, self.reason = 'WAIT_HEALTH', 'service stopped; authorized intent retained'

    async def start_continuous(self, payload=None):
        """Explicit new start request; the ordinary recovery path qualifies first."""
        payload = payload or {'request_id':str(uuid.uuid4())}
        if not isinstance(payload,dict) or set(payload)!={'request_id'}:
            raise ValueError('start requires one request_id')
        request = str(uuid.UUID(payload['request_id']))
        if request == self.last_start_request:return self.snapshot()
        if not self._supported():
            raise RuntimeError('continuous start requires the qualified 0.7.13 ECHO_AD profile')
        if getattr(self.service, '_storage_fault', None):
            raise RuntimeError('archive HOLD requires explicit recover before start')
        async with self._control:
            if self.stop_path is not None:self.stop_path.unlink(missing_ok=True)
            self.last_start_request=request
            if self.desired is None:
                self.generation += 1
                self.mode = 'auto'
                self.desired = {'intent_id': str(uuid.uuid4()), 'binding': self._binding(),
                    'policy': {'mode':'periodic', 'duration_s':0, 'rate_policy':'depth_auto',
                               'interval_pattern':'off'}, 'authorized_fire':None,
                    'created_utc_ns':time.time_ns()}
                self._persist()
                self._stop_pending = True
                self._reset_health()
                self._event('continuous_start_requested', intent=self.desired)
            else:self._persist()
        self.start()
        return self.snapshot()

    async def _loop(self):
        while not self._closed:
            try:
                await self.tick()
            except asyncio.CancelledError: raise
            except Exception as exc:
                # Archive/storage/control errors must not turn into blind retries.
                self.state, self.reason = 'HOLD', f'{type(exc).__name__}: {exc}'
                recorder = getattr(self.service, '_record_storage_fault', None)
                if isinstance(exc, OSError) and recorder:recorder(exc)
                await self._cancel_attempt()
                try: await self.tx.abort()
                except Exception: pass
                return
            await asyncio.sleep(self.CHECK_S)

    async def tick(self):
        if self.stop_path is not None and self.stop_path.exists()and self.desired:
            await self.operator_abort()
            return
        if self.mode != 'auto' or not self.desired: return
        check = getattr(self.service, 'check_archive', None)
        if check: check()
        if self._attempt_task and not self._attempt_task.done(): return
        if self._attempt_task:
            task, self._attempt_task = self._attempt_task, None
            if not task.cancelled() and task.exception() is not None:
                raise task.exception()
        run = self.tx.run or {}
        if run.get('state') in {'RUNNING', 'ARMED'} and self.tx.mode != 'off':
            self._was_running = True
            self.state, self.reason = 'RUNNING', 'monitoring authorized continuous run'
            return
        if self._was_running:
            self._was_running = False
            self._reset_health()
            self.tx.short_verified = False
            self._event('interrupted', previous_run=dict(run), reason=self.tx.reason)
            self._stop_pending = True
            self._next_stop_at = 0.
        if self._stop_pending:
            # A late STOP ACK is not evidence of a storage/protocol failure, and
            # must not kill the health supervisor. No pilot/FIRE is allowed until
            # a newly correlated STOP reply succeeds, followed by fresh health.
            if not await self._confirm_stop(): return
            self._reset_health()
            return
        reason = self._health()
        if reason:
            self.state, self.reason = 'WAIT_HEALTH', reason
            return
        if self.clock() < self._next_attempt_at:
            self.state, self.reason = 'BACKOFF', 'waiting before another finite qualification attempt'
            return
        self._attempt_task = asyncio.create_task(self._attempt(self.generation), name='tx-recovery-pilot')

    async def _confirm_stop(self):
        if self.clock() < self._next_stop_at:
            self.state, self.reason = 'WAIT_STOP', 'waiting before another STOP confirmation'
            return False
        generation = self.generation
        async with self._control:
            if generation != self.generation or not self.desired or self.mode != 'auto': return False
            if (not self.tx.client or not self.tx.client.hello
                    or self.tx.client.hello.device_serial != self.desired['binding']['serial']):
                self.state, self.reason = 'WAIT_STOP', 'waiting for the bound Pico to confirm STOP'
                return False
            try:
                await self.tx.abort()
            except (TxCommandTimeout, ConnectionError, SerialDisconnected) as exc:
                if generation != self.generation: return False
                self._stop_failures += 1
                self._next_stop_at = self.clock()+min(60, 5*2**min(4, self._stop_failures-1))
                self._reset_health()
                self.state, self.reason = 'WAIT_STOP', f'{type(exc).__name__}: {exc}'
                self._event('stop_unconfirmed', reason=self.reason,
                            retry_in_s=self._next_stop_at-self.clock())
                return False
            if generation != self.generation: return False
            self._stop_pending = False
            self._stop_failures = 0
            self.state, self.reason = 'WAIT_HEALTH', 'STOP confirmed; qualifying fresh original markers'
            self._event('stop_confirmed')
            return True

    def _current(self, generation):
        if generation != self.generation or self.mode != 'auto' or self.desired is None or self._closed:
            raise asyncio.CancelledError()

    async def _attempt(self, generation):
        success = False
        try:
            async with self._control:
                self._current(generation)
                if self._health() is not None: return
                connection = self.tx.connection_id
                desired = dict(self.desired['policy'])
                self.attempts += 1
                self.state, self.reason = 'PILOT', 'finite 2s physical marker/raw/echo qualification'
                self._event('attempt', attempt=self.attempts, intent_id=self.desired['intent_id'])
                self.tx.short_verified = False
                await self.tx.update({**desired, 'duration_s': 2})
                self._current(generation)
                await self.tx.fire({'request_id': str(uuid.uuid4())})
                deadline = self.clock()+self.PILOT_TIMEOUT_S
                while (self.tx.run or {}).get('state') not in {'COMPLETE', 'FAILED', 'ABORTED'}:
                    self._current(generation)
                    if self.clock() >= deadline: raise TimeoutError('recovery pilot did not complete')
                    await asyncio.sleep(.05)
                pilot = self.tx.snapshot()['run']
                if not (pilot['state'] == 'COMPLETE' and pilot.get('verified') and self.tx.short_verified):
                    raise RuntimeError('recovery pilot lacks physical/raw/usable-echo qualification')
                self._current(generation)
                if self.tx.connection_id != connection or self._health() is not None:
                    raise RuntimeError('health or USB connection changed during pilot')
                self.last_result = {'pilot': pilot, 'connection_id': connection}
                self._event('pilot_passed', **self.last_result)
                await self.tx.update(desired)
                self._current(generation)
                result = await self.tx.fire({'request_id': str(uuid.uuid4())})
                self._current(generation)
                if not result.get('command_accepted'): raise RuntimeError('continuous FIRE not accepted')
                self.last_result['continuous_request'] = result
                self._event('resumed', **self.last_result)
                self.state, self.reason = 'RUNNING', 'fresh pilot passed; continuous run resumed'
                self.recoveries += 1
                self._failures = 0
                self._was_running = success = True
        except asyncio.CancelledError: raise
        except Exception as exc:
            self._failures += 1
            self._next_attempt_at = self.clock()+min(60, 5*2**min(4, self._failures-1))
            self.state, self.reason = 'BACKOFF', f'{type(exc).__name__}: {exc}'
            self.last_result = {'failed': True, 'reason': self.reason, 'at_utc_ns': time.time_ns()}
            self._event('attempt_failed', **self.last_result)
        finally:
            if not success:
                try: await self.tx.abort()
                except (TxCommandTimeout, ConnectionError, SerialDisconnected):
                    if generation == self.generation: self._stop_pending = True
                self._reset_health()
