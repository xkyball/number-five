"""Depth-driven capture geometry. No display setting controls physical TX.

The profile is the authority for normal output. A local trial opt-in may only
exercise bounded candidate runs; it never promotes a hardware limit.
"""
from __future__ import annotations

import asyncio
from bisect import bisect_left, bisect_right
from collections import deque
from dataclasses import dataclass
import hashlib
import json
import math
from pathlib import Path
import struct
import time

from .clip_endurance import ClipProfile, ClipEnduranceController
from .transmit import EVENT, TransmitController
from .interval_signature import PATTERN, PATTERN_NAME, interval_symbol

MIN_COUNT, MAX_COUNT = 2048, 16384
PIPELINE_RESERVE_US = 3000


def capture_geometry(depth_m, sound_speed_mps=1500.0):
    if depth_m is None:
        return MAX_COUNT, False
    if not math.isfinite(depth_m) or depth_m <= 0:
        raise ValueError('depth must be finite and positive')
    if not math.isfinite(sound_speed_mps) or sound_speed_mps <= 0:
        raise ValueError('sound speed must be finite and positive')
    wanted = max(depth_m * 1.5, depth_m + 1.0)
    count = math.ceil((2 * wanted / sound_speed_mps) / 2e-6 / 256) * 256
    return max(MIN_COUNT, min(MAX_COUNT, count)), count > MAX_COUNT


class DepthPolicy:
    def __init__(self, *, sound_speed_mps=1500.0, clock=time.monotonic):
        self.clock, self.sound_speed_mps = clock, sound_speed_mps
        self.count = MAX_COUNT
        self.depth = self.last_at = self.stable_since = None
        self.hits = 0
        self.reason = 'searching'
        self.range_limited = False

    def observe(self, depth, *, valid, age_s=0.0):
        now = self.clock()
        if not valid or depth is None or not math.isfinite(depth) or depth <= 0 or not 0 <= age_s < 1:
            self.hits = 0
            self.stable_since = None
            fresh = self.last_at is not None and now-self.last_at < .9
            conflict = (depth is not None and math.isfinite(depth) and depth > 0
                        and self.depth is not None
                        and abs(depth-self.depth)>max(.15,.05*self.depth))
            # An absent candidate is not a new depth measurement. Keep an
            # already applied window only while the LAST real own-ping depth
            # is still fresh. Do not refresh its timestamp, shrink a window,
            # count this ping as usable, or hide a contradictory measurement.
            missing_or_plausible = depth is None or (math.isfinite(depth) and depth > 0)
            if fresh and missing_or_plausible and not conflict and 0 <= age_s < 1:
                self.reason = 'depth_held_fresh'
            else:
                self.count = MAX_COUNT
                self.reason = 'unconfirmed_depth'
            return
        if self.last_at is not None and now-self.last_at >= .9:
            # A real stale interval requires a new stable acquisition before
            # shrinking again, even if snapshot() already returned full RX.
            self.count = MAX_COUNT
            self.stable_since = None
            self.hits = 0
        wanted, limited = capture_geometry(depth, self.sound_speed_mps)
        stable = self.stable_since is not None and self.depth is not None and abs(depth-self.depth) <= max(.15, .05*self.depth)
        if self.depth is not None and not stable:
            self.count = MAX_COUNT
        self.hits = self.hits+1 if stable else 1
        if not stable:
            self.stable_since = now
        self.depth, self.last_at = depth, now-age_s
        self.range_limited = limited
        if wanted > self.count:
            self.count = wanted
        elif self.hits >= 5 and self.stable_since is not None and now-self.stable_since >= 1:
            self.count = wanted
        self.reason = 'range_reserve_limited' if limited else 'depth_locked' if self.count < MAX_COUNT else 'stabilizing'

    def snapshot(self):
        age = None if self.last_at is None else max(0., self.clock()-self.last_at)
        stale = age is None or age >= .9
        # .1s command/transport reserve guarantees a stale estimate cannot speed
        # up output. The device independently expires every received plan.
        return {'depth_m': self.depth, 'depth_age_s': age,
                'sample_count': MAX_COUNT if stale else self.count,
                'range_reserve_limited': self.range_limited,
                'reason': 'depth_stale' if stale else self.reason,
                'valid_us': 1000000 if stale else max(100000, int((1-age)*1e6))}


class PingRates:
    def __init__(self):
        self.rows = {k: deque(maxlen=60000) for k in ('own', 'physical', 'raw', 'usable')}
        self.device_s = self.host_at = None
        self.started_s = None

    def add(self, kind, device_us):
        t = device_us/1e6
        if self.device_s is None or t >= self.device_s:
            self.device_s, self.host_at = t, time.monotonic()
        self.started_s = t if self.started_s is None else min(self.started_s, t)
        self.rows[kind].append(t)

    def snapshot(self):
        if self.device_s is None:
            return {'window_s': 5, 'time_basis': 'device_marker_us', 'complete': False, 'rates': {}}
        now = self.device_s+time.monotonic()-self.host_at
        begin = max(self.started_s, now-5)
        span = now-begin
        rates = {}
        for kind, q in self.rows.items():
            while q and q[0] < now-7:
                q.popleft()
            a = sorted(q)
            count = bisect_right(a, now)-bisect_right(a, begin)
            starts = {begin, now-1}
            starts.update(t for t in a if begin <= t <= now-1)
            starts.update(t-1 for t in a if begin <= t-1 <= now-1)
            minimum = None if span < 1 else min(
                bisect_right(a, t+1)-bisect_right(a, t) for t in starts if begin <= t <= now-1)
            points = [begin]+[t for t in a if begin < t < now]+[now]
            gap = max((b-a for a, b in zip(points, points[1:])), default=span)
            rates[kind] = {'count': count, 'hz': count/span if span else None,
                           'rolling_1s_min': minimum, 'max_gap_s': gap}
        return {'window_s': 5, 'start_device_us': round(begin*1e6), 'end_device_us': round(now*1e6),
                'observed_s': span, 'time_basis': 'device_marker_us_host_extrapolated_now',
                'complete': span >= 5-1e-6, 'rates': rates}


@dataclass(frozen=True)
class DepthRateProfile(ClipProfile):
    qualified_min_spacing_us: int = 27000
    qualified_max_own_per_native: int = 7
    continuous_run_supported: bool = False

    @classmethod
    def load(cls, path):
        path = Path(path).resolve()
        raw = path.read_bytes()
        row = json.loads(raw)
        if row.get('schema') != 'no5.clip-depth-rate-profile/1' or row.get('firmware') not in ([0, 7, 11],[0,7,12],[0,7,13]):
            raise ValueError('incompatible depth-rate profile')
        continuous=row.get('firmware') in ([0,7,12],[0,7,13])
        if continuous and (row.get('continuous_run_supported') is not True or row.get('timed_max_duration_s')!=600):
            raise ValueError('continuous firmware requires an explicit lease-bound profile')
        ref = row['baseline']
        base_path = path.parent/ref['path']
        if hashlib.sha256(base_path.read_bytes()).hexdigest() != ref['sha256']:
            raise ValueError('depth-rate baseline hash mismatch')
        base = ClipProfile.load(base_path)
        floor = row.get('qualified_min_spacing_us')
        quota = row.get('qualified_max_own_per_native',7)
        if type(quota) is not int or not 1<=quota<=64 or (floor==27000 and quota>7):
            raise ValueError('unqualified native-cycle output quota')
        if type(floor) is not int or not 6000 <= floor <= 27000:
            raise ValueError('invalid qualified spacing')
        if floor < 27000:
            ref = row.get('qualification', {})
            proof_path = path.parent/ref.get('path', '')
            if not proof_path.is_file() or hashlib.sha256(proof_path.read_bytes()).hexdigest() != ref.get('sha256'):
                raise ValueError('missing physical rate qualification')
            proof = json.loads(proof_path.read_text())
            if (proof.get('schema') != 'no5.depth-rate-qualification/1' or proof.get('passed') is not True
                    or proof.get('minimum_spacing_us') != floor or proof.get('duration_s') != 600
                    or proof.get('max_own_per_native') != quota
                    or not all(proof.get(k) is True for k in ('all_physical_links', 'raw_samples_unchanged',
                        'r17_recovery_passed', 'echo_aba_passed', 'no_acquisition_errors'))):
                raise ValueError('rate has no complete physical acceptance')
        return cls(base.serial, hashlib.sha256(raw).hexdigest(), base.evidence,
                   firmware=tuple(row['firmware']), quiet_window=True, qualified_min_spacing_us=floor,
                   qualified_max_own_per_native=quota,continuous_run_supported=continuous)


class DepthRateController(ClipEnduranceController):
    def __init__(self, service, profile):
        super().__init__(service, profile)
        self.policy['rate_policy'] = 'depth_auto'
        self.policy['interval_pattern'] = 'off'
        self.depth_policy = DepthPolicy(sound_speed_mps=service.config.sound_speed_mps)
        self.rates = PingRates()
        self.revision = 0
        self.applied_window = None
        self.geometry_errors = 0
        self.trial_spacing_us = None
        self.trial_endurance_evidence = None
        self.diagnostic_source = 'ECHO_AD'
        self._latest_geometry = None
        self.reference_observation = None
        self.reference_fault_latched = None
        self._reference_received_at = None
        self._plan_lock = asyncio.Lock()
        self._interval_task = None
        self._plan_labels = {}
        self._last_interval_label = None
        self._depth_refresh_task = None
        self._depth_refresh_error = None
        self._archive_wait_count = None

    def bind(self, client):
        super().bind(client)
        self.depth_policy = DepthPolicy(sound_speed_mps=self.service.config.sound_speed_mps)
        self.rates = PingRates()
        self.applied_window = self._latest_geometry = None
        self.revision = self.geometry_errors = 0
        self.reference_observation = self.reference_fault_latched = None
        self._reference_received_at = None
        self._archive_wait_count = None
        self._plan_labels.clear()
        self._last_interval_label = None

    @property
    def floor_us(self):
        return self.trial_spacing_us or self.profile.qualified_min_spacing_us

    @property
    def cycle_quota(self):
        return 64 if self.trial_spacing_us is not None else self.profile.qualified_max_own_per_native

    def snapshot(self):
        x = super().snapshot()
        d = self.depth_policy.snapshot()
        ref_age=None if self._reference_received_at is None else time.monotonic()-self._reference_received_at
        n = d['sample_count']
        if self.policy['rate_policy']=='depth_auto' and self._archive_wait_count is not None:
            n=MAX_COUNT
        if self.diagnostic_source != 'ECHO_AD':
            n = 2048
            d.update(depth_m=None,depth_age_s=None,reason='diagnostic_fixed_window')
        interval = max(self.floor_us, n*2+PIPELINE_RESERVE_US)
        if self.policy['interval_pattern'] != 'off' and self._last_interval_label:
            interval = max(interval, self._last_interval_label['requested_spacing_us'])
        fixed = self.policy['rate_policy']=='fixed_qualified'
        if fixed:
            n, interval = 8192, 27000
        x.update(schema='no5.depth-rate-status/1', rate_control={
            'schema': 'no5.depth-rate-control/1', **d,
            'sample_count': n,
            'rate_policy': self.policy['rate_policy'], 'minimum_spacing_us': interval,
            'qualified_min_spacing_us': self.profile.qualified_min_spacing_us,
            'max_own_per_native': 7 if fixed else self.cycle_quota,
            'experimental_trial': self.trial_spacing_us is not None,
            'trial_spacing_us': self.trial_spacing_us,
            'capture_input_source': self.diagnostic_source,
            'plan_refresh_error': self._depth_refresh_error,
            'archive_geometry_preparing': self._archive_wait_count,
            'receive_range_m': n*2e-6*self.service.config.sound_speed_mps/2,
            'sample_period_ns': 2000, 'pipeline_reserve_us': PIPELINE_RESERVE_US,
            'periodic_full_search': None if fixed else 'first own ping of every second native cycle',
            'applied_window': self.applied_window, 'geometry_errors': self.geometry_errors,
            'limiting_reason': 'fixed_qualified' if fixed else 'archive_geometry_preparing' if self._archive_wait_count is not None else d['reason'] if n == MAX_COUNT else 'qualified_spacing' if interval == self.floor_us else 'receive_window',
            'planned_hz': 18.12 if fixed else self._planned_hz(n, interval, self.cycle_quota),
        }, ping_rates=self.rates.snapshot(), original_reference={
            'schema':'no5.original-reference-observation/1',
            'latest':self.reference_observation, 'latched_fault':self.reference_fault_latched,
            'arrival_age_s':ref_age,'observation_fresh':ref_age is not None and ref_age<=.43,
            'latched_until':'USB rebind; historical fault, not a rearm instruction',
            'recovery_rule':'off-phase quarantine resumes only on real in-window native marker with live lease; reference faults require explicit new arm/fire',
            'tx_mode':self.mode})
        x['interval_signature'] = {
            'schema': 'no5.interval-signature-status/1', 'mode': self.policy['interval_pattern'],
            'experimental': True, 'pattern': list(PATTERN), 'unit': 'native_cycle',
            'short_spacing_us': self.floor_us, 'long_spacing_us': max(27000, self.floor_us),
            'last_requested': self._last_interval_label,
            'acoustic_code_confirmed': False, 'multipath_rejection_qualified': False,
            'burst_parameters_changed': False, 'raw_samples_modified': False}
        x['schedule'].update(kind='depth_auto_native_quiet_window' if self.policy['rate_policy']=='depth_auto' else 'native_quiet_window',
                             own_slots_us=None if self.policy['rate_policy']=='depth_auto' else [174000,201000,228000,255000,282000,309000,336000],
                             minimum_own_spacing_us=interval if self.policy['rate_policy']=='depth_auto' else 27000,
                             nominal_own_mean_hz=self._planned_hz(n,interval,self.cycle_quota) if self.policy['rate_policy']=='depth_auto' else 18.12,
                             maximum_slot_lateness_us=4000)
        if self.trial_spacing_us is not None:
            x['qualified'] = False
        return x

    @staticmethod
    def _planned_hz(count, interval, quota=64):
        # Conservative 380ms cycle; one full probe every second cycle.
        def capacity(probe):
            due, total = 174000, 0
            while True:
                n = MAX_COUNT if probe and total == 0 else count
                if total>=quota or due+n*2+23000 > 380000:
                    return total
                total += 1
                due += max(interval, n*2+PIPELINE_RESERVE_US)
        return (capacity(False)+capacity(True))/(2*.380)

    async def update(self, payload):
        if not isinstance(payload, dict) or set(payload)-{'mode', 'duration_s', 'rate_policy', 'interval_pattern'}:
            raise ValueError('unknown depth-rate policy fields')
        pattern = payload.get('interval_pattern', self.policy['interval_pattern'])
        if not isinstance(pattern, str) or pattern not in {'off', PATTERN_NAME}:
            raise ValueError('interval_pattern must be off or native_cycle_code_v1')
        if self.mode != 'off' and pattern != self.policy['interval_pattern']:
            raise RuntimeError('interval pattern changes require unarmed TX')
        if self.mode != 'off' and (payload.get('mode') != 'off' or
                payload.get('rate_policy',self.policy['rate_policy']) != self.policy['rate_policy']):
            raise RuntimeError('rate policy changes require unarmed TX')
        rate = payload.get('rate_policy', self.policy['rate_policy'])
        if rate not in {'fixed_qualified', 'depth_auto'}:
            raise ValueError('rate_policy must be fixed_qualified or depth_auto')
        duration = payload.get('duration_s', self.policy['duration_s'])
        if pattern != 'off' and (rate != 'depth_auto' or self.floor_us >= 27000
                or self.trial_spacing_us is not None or self.diagnostic_source != 'ECHO_AD'
                or duration not in {2, 30}):
            raise ValueError('interval experiment requires qualified fast depth_auto, ECHO_AD and a 2s/30s bounded run')
        if self.diagnostic_source != 'ECHO_AD' and duration != 2:
            raise ValueError('R17 recovery diagnostic is limited to one 2s run')
        durations={0,2,30,600} if self.profile.continuous_run_supported else {2,30,600}
        if payload.get('mode',self.policy['mode']) not in {'off','periodic'} or type(duration) is not int or duration not in durations:
            raise ValueError('mode off/periodic; duration_s 2/30/600; 0 requires continuous capability')
        if duration==0 and (self.trial_spacing_us is not None or self.diagnostic_source!='ECHO_AD'):
            raise ValueError('continuous operation requires the qualified ECHO_AD profile, never a trial')
        if payload.get('mode') != 'off' and duration in {0,30,600} and not self.short_verified:
            raise RuntimeError('short physical qualification required in this USB session')
        if self.trial_spacing_us and duration == 600 and not self.trial_endurance_evidence:
            raise RuntimeError('trial endurance requires passed short/30s/R17/A-B-A receipts')
        previous = self.policy['rate_policy']
        previous_pattern = self.policy['interval_pattern']
        self.policy['rate_policy'] = rate
        self.policy['interval_pattern'] = pattern
        try:
            return await super().update({k:v for k,v in payload.items() if k not in {'rate_policy', 'interval_pattern'}})
        except BaseException:
            self.policy['rate_policy'] = previous
            self.policy['interval_pattern'] = previous_pattern
            raise

    async def _command(self, kind, body=b''):
        if kind == 0x46:
            self.applied_window = self._latest_geometry = None
        adaptive = kind == 0x41 and self.policy['rate_policy'] == 'depth_auto'
        if adaptive and self._depth_refresh_task is not None and self._depth_refresh_task.done():
            self._depth_refresh_task.result()  # storage/protocol failures remain fatal
        if adaptive and self.mode == 'off':
            # Initial arming needs a configured window, with output still idle.
            await self._push_depth_plan()
        result = await super()._command(kind, body)
        if adaptive and self.mode != 'off' and (self._depth_refresh_task is None or self._depth_refresh_task.done()):
            # A slow plan ACK must never stand in front of the next finite lease.
            self._depth_refresh_task = asyncio.create_task(self._refresh_depth_plan(), name='depth-plan-refresh')
        return result

    async def _refresh_depth_plan(self):
        from .transmit import TxCommandTimeout
        try:
            await self._push_depth_plan()
            self._depth_refresh_error = None
        except TxCommandTimeout as exc:
            # The independently timed device plan expires into full search.
            # Actual WINDOW events/raw geometry remain authoritative. No FIRE
            # retry and no shortened RX window to compensate for this timeout.
            self._depth_refresh_error = str(exc)
            self._archive('depth_plan_unconfirmed', error=str(exc),
                          fallback='device_plan_expiry_full_search')

    async def _push_depth_plan(self):
        async with self._plan_lock:
            d = self.depth_policy.snapshot()
            self.revision += 1
            count = d['sample_count']
            if self.diagnostic_source != 'ECHO_AD':
                count = 2048
            ensure=getattr(getattr(self.service,'_writer',None),'ensure_geometry',None)
            self._archive_wait_count=None
            if ensure is not None and not ensure(count,2000):
                self._archive_wait_count=count
                count=MAX_COUNT  # Full RX reserve; never discard range to meet a rate.
            interval = max(self.floor_us, count*2+PIPELINE_RESERVE_US)
            label = None
            if self.policy['interval_pattern'] != 'off':
                reference = self.reference_observation or {}
                label = interval_symbol(reference.get('native_stable', 0), self.floor_us)
                label.update(native_marker_device_us=reference.get('native_last_device_us'),
                             native_stable=reference.get('native_stable'), revision=self.revision)
                interval = max(interval, label['requested_spacing_us'])
                label['commanded_spacing_us'] = interval
                label['distinguishable'] = count*2+PIPELINE_RESERVE_US < 27000
            # Register before awaiting ACK: a fast device may already emit the
            # same revision's window event before this coroutine resumes.
            self._plan_labels[self.revision] = label
            if len(self._plan_labels) > 256:
                self._plan_labels.pop(next(iter(self._plan_labels)))
            self._last_interval_label = label
            await super()._command(0x47, struct.pack('<IIIIII', self.revision, count, interval, self.floor_us, d['valid_us'], self.cycle_quota))
            self._archive('depth_plan_requested', revision=self.revision, **d,
                          minimum_spacing_us=interval, floor_us=self.floor_us,max_own_per_native=self.cycle_quota,
                          interval_signature=label)

    async def _push_interval_cycle(self):
        try:
            if self.mode != 'off' and self.policy['interval_pattern'] != 'off':
                await self._push_depth_plan()
        except asyncio.CancelledError:
            raise
        except Exception as exc:
            self._archive('interval_signature_failure', error=str(exc))
            # Abort without trying to await/cancel this task from itself.
            self._interval_task = None
            await self.abort()

    async def _cancel_renewal(self):
        refresh, self._depth_refresh_task = self._depth_refresh_task, None
        if refresh is not None and refresh is not asyncio.current_task():
            refresh.cancel()
            try: await refresh
            except asyncio.CancelledError: pass
            except Exception: pass  # retrieved by the lease loop; retirement must continue
        task, self._interval_task = self._interval_task, None
        if task is not None and task is not asyncio.current_task():
            task.cancel()
            try:
                await task
            except asyncio.CancelledError:
                pass
        await super()._cancel_renewal()

    def on_packet(self, packet):
        if int(packet.type) == 0x45:
            event, revision, count, start, interval, cycle = EVENT.unpack(packet.payload)
            if event == 8 and revision in {1,2,3,4,5,6,11}:
                names={1:'native_interval_invalid',2:'native_period_outside_profile',
                       3:'previous_native_cycle_unfinished',4:'native_reference_deadline_expired',
                       5:'off_phase_marker_quarantine',6:'real_native_marker_recovery',11:'qualified_native_marker'}
                clock=getattr(self.client,'clock',None)
                utc=None if clock is None or not clock.ready else int(clock.pico_us_to_utc_ns(count))
                observed={'observation_reason':names[revision], 'firmware_event':8,'firmware_detail':revision,
                    'observed_device_us':count,'observed_utc_ns':utc,
                    'utc_basis':None if utc is None else 'accepted_usb_time_sync',
                    'observation_kind':'timeout_check' if revision==4 else 'marker_edge',
                    'native_last_device_us':start,'native_period_us':interval,
                    'native_stable':cycle>>32,'slot':cycle&0xffffffff,
                    'meaning':'fault' if revision in {1,2,3,4} else 'quarantine' if revision==5 else 'qualified',
                    'received_host_utc_ns':time.time_ns()}
                self.reference_observation=observed
                self._reference_received_at=time.monotonic()
                recovery = getattr(self.service, 'recovery', None)
                if recovery is not None:
                    recovery.observe_reference(observed)
                if revision in {1,2,3,4}:
                    self.reference_fault_latched=dict(observed)
                self._archive('original_reference_observation',**observed)
                if (revision == 11 and self.mode != 'off' and self.policy['interval_pattern'] != 'off'
                        and (self._interval_task is None or self._interval_task.done())):
                    self._interval_task = asyncio.create_task(self._push_interval_cycle(), name='interval-cycle-plan')
            if event == 9:
                self.applied_window = {'revision': revision, 'sample_count': count,
                    'next_min_spacing_us': interval, 'device_us': start, 'native_cycle': cycle}
                label = self._plan_labels.get(revision)
                if label is not None:
                    self.applied_window['interval_signature'] = dict(label)
                self._archive('depth_window_applied', **self.applied_window)
                return
            if event in {1, 2, 6}:
                self.rates.add({1:'own', 2:'physical', 6:'raw'}[event], start if event != 2 else interval)
            super().on_packet(packet)
            if event == 1 and count in self._ledger and self.applied_window is not None:
                self._ledger[count]['capture_window'] = dict(self.applied_window)
                self._archive('depth_tx_window', tx_id=count, **self.applied_window)
            return
        super().on_packet(packet)

    def on_ping(self, ping):
        if self.diagnostic_source == 'ECHO_AD':
            super().on_ping(ping)
        else:
            # A supply-recovery trace must not enter the bath-echo heuristic.
            TransmitController.on_ping(self,ping)
            self._total+=1
            self._native+=not self.is_own_ping(ping)
        if not self.is_own_ping(ping):
            return
        item = next(v for v in self._ledger.values() if v.get('physical_tx_confirmed') and
                    abs(v.get('marker_device_us', 0)-ping.trigger_device_time_us) <= 3)
        window = item.get('capture_window')
        if self.policy['rate_policy'] == 'depth_auto' and (not window or len(ping.samples) != window['sample_count']):
            self.geometry_errors += 1
            asyncio.create_task(self.abort())
        self._latest_geometry = {'ping_id': ping.ping_id, 'device_us': ping.trigger_device_time_us,
                                 'sample_count': len(ping.samples), 'window': window}
        self._archive('depth_ping_geometry', **self._latest_geometry)

    def on_detection(self, result, presentation):
        super().on_detection(result, presentation)
        if result.acquisition_source != 'own' or not self._latest_geometry or self._latest_geometry['ping_id'] != result.ping_id:
            return
        bottom = presentation.get('bottom')
        valid = result.valid_tx and presentation['quality']=='LOCKED' and bottom is not None and bottom.get('confidence', 0) >= .75
        age = 2. if result.time_utc_ns is None else max(0., (time.time_ns()-result.time_utc_ns)/1e9)
        self.depth_policy.observe(None if bottom is None else bottom['depth_m'], valid=valid, age_s=age)
        if valid:
            self.rates.add('usable', self._latest_geometry['device_us'])

    async def _finish(self):
        if self.policy['rate_policy'] == 'fixed_qualified':
            return await super()._finish()
        await self._cancel_renewal()
        await asyncio.sleep(.15)
        # Storage can finish after the final physical ping. Wait for its real
        # raw association, never turn a fixed sleep into fabricated evidence.
        drain_until = time.monotonic()+3
        while (self.run and not self.snapshot()['run']['all_associated']
               and time.monotonic()<drain_until):
            await asyncio.sleep(.02)
        snap = self.snapshot()['run']
        valid = (snap['duration_s']>0 and snap['all_associated'] and snap['own_starts'] >= 5 and not self.geometry_errors and
                 self._errors()==snap['errors_at_start'] and snap['elapsed_s'] >= snap['duration_s']-.25)
        diagnostic = self.diagnostic_source != 'ECHO_AD'
        if snap['duration_s'] == 2:
            valid = valid and (diagnostic or snap['usable_pings'] >= 5)
            self.short_verified = valid and not diagnostic
        self.run.update(snap, counters_final=True, state='COMPLETE' if valid else 'FAILED', verified=valid,
                        echo_qualification=not diagnostic,
                        reason='adaptive run complete; physical/raw geometry reconciled' if valid else 'unexpected completion of continuous run' if not snap['duration_s'] else 'adaptive reconciliation failed',
                        finished_utc_ns=time.time_ns(), errors_at_end=self._errors())
        self.reason = self.run['reason']
        self._archive('endurance_result', run=self.snapshot()['run'])
