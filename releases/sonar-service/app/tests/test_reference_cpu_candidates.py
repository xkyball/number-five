"""Bounded differential tests: same profile bytes, physical evidence and state."""
import asyncio
import ast
import copy
import importlib
from pathlib import Path
import struct
import sys
import zipfile
from types import SimpleNamespace

import numpy as np
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'tools'))
from reference_cpu_candidates import load_pair, original_profile, REBUILD
from no5_sonard.pico_protocol import PingFrame, Packet, MessageType


@pytest.fixture(scope='module')
def pair():
    return load_pair()


def packed(values):
    return b''.join(struct.pack('<d', v) for v in values)


@pytest.mark.parametrize('length,stop', [
    (1, 0), (1, 1), (2, 1), (511, 511), (512, 512), (513, 513),
    (2048, 0), (2048, 17), (2048, 2048), (8192, 2000), (16384, 16000)])
def test_identical_bins_bytes_and_untouched_tail(pair, length, stop):
    values = np.random.default_rng(length + stop).uniform(0, 4095, length)
    if 0 < stop < length:
        values[stop:] = 1e12  # The last reduceat must not include this tail.
    before = values.tobytes()
    assert packed(pair[1].clip.reference_profile(values, stop)) == packed(original_profile(values, stop))
    assert values.tobytes() == before


@pytest.mark.parametrize('kind', ['float32', 'integer', 'negative', 'signed_zero',
                                  'nan', 'inf', 'negative_inf', 'strided', 'ties', 'big_endian'])
def test_exceptional_values_preserve_original_float_and_error_semantics(pair, kind):
    values = np.linspace(0., 4095., 2051)
    if kind == 'float32': values = values.astype('float32')
    elif kind == 'integer': values = values.astype('uint16')
    elif kind == 'negative': values -= 2000
    elif kind == 'signed_zero': values[10:20] = -0.
    elif kind == 'nan': values[15] = np.nan
    elif kind == 'inf': values[15] = np.inf
    elif kind == 'negative_inf': values[15] = -np.inf
    elif kind == 'strided': values = values[::-2]
    elif kind == 'ties': values[:] = np.resize([1.005, 1.015, 2.675, 2.685], len(values))
    elif kind == 'big_endian': values = values.astype('>f8')
    before = values.tobytes()
    stop = len(values) - 1
    assert packed(pair[1].clip.reference_profile(values, stop)) == packed(original_profile(values, stop))
    assert values.tobytes() == before


@pytest.mark.parametrize('values,stop', [(np.array([]), 0), (np.ones(10), 20),
                                       (np.ones(10), -1)])
def test_invalid_bounds_keep_original_failure(pair, values, stop):
    with pytest.raises(ValueError) as old:
        original_profile(values, stop)
    with pytest.raises(type(old.value)) as new:
        pair[1].clip.reference_profile(values, stop)
    assert str(new.value) == str(old.value)


def test_only_reviewed_controller_methods_change_and_reference_variant_is_marker_identical():
    changed = {'clip_endurance.py': {'ClipEnduranceController.on_ping'},
               'depth_rate.py': {'DepthRateController.on_ping'},
               'service.py': {'HostService._process_ping'}}
    def methods(source):
        tree = ast.parse(source)
        return {f'{c.name}.{f.name}': ast.dump(f, include_attributes=False)
                for c in tree.body if isinstance(c, ast.ClassDef)
                for f in c.body if isinstance(f, (ast.FunctionDef, ast.AsyncFunctionDef))}
    with zipfile.ZipFile(REBUILD / 'vendor/sonard-tracking-0431/no5_sonard_openecho-0.4.31-py3-none-any.whl') as wheel:
        for filename, allowed in changed.items():
            before = methods(wheel.read('no5_sonard_openecho/' + filename))
            after = methods((REBUILD / 'templates/sonar-reference-cpu-candidate' / filename).read_text())
            for name, source in before.items():
                if name not in allowed: assert after[name] == source, name
        before = methods(wheel.read('no5_sonard_openecho/clip_endurance.py'))
        after = methods((REBUILD / 'templates/sonar-reference-cpu-candidate/reference-only/clip_endurance.py').read_text())
        assert before.keys() == after.keys()
        assert all(before[n] == after[n] for n in before if n != 'ClipEnduranceController.on_ping')


def controller(module, root, *, depth=False, diagnostic='ECHO_AD'):
    service = SimpleNamespace(config=SimpleNamespace(storage_root=root, sound_speed_mps=1500.),
                              control_root=root, status=SimpleNamespace(segment_id=None),
                              _session_path=None)
    tx = (module.depth.DepthRateController if depth else module.clip.ClipEnduranceController)(service, None)
    tx.policy['rate_policy'] = 'fixed_qualified'
    if depth:
        tx.diagnostic_source = diagnostic
    events = []
    tx._archive = lambda event, **fields: events.append((event, copy.deepcopy(fields)))
    return tx, events


def state(tx):
    names = ('_ledger', '_pings', 'linked_pings', '_total', '_native', '_echoes',
             'profiles', 'mode', 'reason', '_fault_generation', 'physical_markers', 'output_starts')
    result = {k: copy.deepcopy(getattr(tx, k)) for k in names}
    for k in ('_latest_geometry', 'geometry_errors'):
        if hasattr(tx, k): result[k] = copy.deepcopy(getattr(tx, k))
    return result


@pytest.mark.parametrize('depth,diagnostic', [(False, 'ECHO_AD'), (True, 'ECHO_AD'), (True, 'SUPPLY')])
def test_first_last_precedence_three_us_boundary_and_ledger_changes(pair, tmp_path, depth, diagnostic):
    a, ea = controller(pair[0], tmp_path / 'a', depth=depth, diagnostic=diagnostic)
    b, eb = controller(pair[1], tmp_path / 'b', depth=depth, diagnostic=diagnostic)
    samples = np.arange(2048, dtype='uint16') % 4096
    before = samples.tobytes()
    for index, shift in enumerate([0, -3, 3, -4, 4, 0]):
        stamp = 1_000_000 + 10_000 * index
        rows = {
            11: {'tx_id': 11, 'physical_tx_confirmed': True, 'marker_device_us': stamp - 3,
                 'ping_id': index, 'capture_window': {'first': True}},
            12: {'tx_id': 12, 'physical_tx_confirmed': False, 'marker_device_us': stamp},
            13: {'tx_id': 13, 'physical_tx_confirmed': True, 'marker_device_us': stamp + 3,
                 'ping_id': None, 'capture_window': {'first': False}}}
        for tx in (a, b): tx._ledger = copy.deepcopy(rows)
        ping = PingFrame(index, stamp + shift, 2000, 0, 0, 2047, 1, samples)
        a.on_ping(ping)
        result = b.on_ping(ping)
        assert result.own == a.is_own_ping(ping) == b.is_own_ping(ping)
        assert state(a) == state(b)
        assert ea == eb
        if result.own and depth:
            assert b._latest_geometry['window'] == result.first['capture_window']
        if result.own and diagnostic == 'ECHO_AD':
            assert b.profiles[-1]['tx_id'] == result.last['tx_id']
        # No retained result substitutes for a newly received physical event.
        for tx in (a, b): tx._ledger.clear()
        assert not b.is_own_ping(ping)
    assert samples.tobytes() == before
    assert not any(isinstance(value, pair[1].clip.PingAssociation) for value in vars(b).values())


def test_late_marker_terminal_event_duplicate_ping_and_eviction_bounds(pair, tmp_path):
    a, ea = controller(pair[0], tmp_path / 'a')
    b, eb = controller(pair[1], tmp_path / 'b')
    samples = np.full(2048, 200, dtype='uint16')
    for index in range(515):
        stamp = 1_000_000 + index * 10000
        ping = PingFrame(index + 1, stamp + 3, 2000, 0, 0, 200, 1, samples)
        # Raw first for some pings; terminal event before raw for others.
        if index % 3 == 0:
            a.on_ping(ping); b.on_ping(ping)
        for event in (1, 2, 6, 7):
            packet = Packet(MessageType.TX_EVENT, index, 0,
                            pair[0].transmit.EVENT.pack(event, 0, index + 10, stamp, stamp + 3,
                                                       ping.ping_id if event == 6 else 0))
            a.on_packet(packet); b.on_packet(packet)
        a.on_ping(ping); result = b.on_ping(ping)
        assert result.own == a.is_own_ping(ping)
        if index < 3 or index in (127, 128, 511, 512, 514):
            assert state(a) == state(b)
        assert ea == eb
        assert len(b._ledger) <= 512 and len(b._pings) <= 512 and len(b.profiles) <= 128
        ea.clear(); eb.clear()
    assert b.linked_pings == a.linked_pings == 515


@pytest.mark.parametrize('diagnostic', ['ECHO_AD', 'SUPPLY'])
def test_one_synchronous_marker_association_replaces_repeated_full_scans(pair, tmp_path, diagnostic):
    class Ledger(dict):
        visits = 0
        lookups = 0
        def values(self):
            self.lookups += 1
            owner = self
            class Values:
                def __iter__(self):
                    for row in dict.values(owner):
                        owner.visits += 1
                        yield row
                def __reversed__(self):
                    for row in reversed(dict.values(owner)):
                        owner.visits += 1
                        yield row
            return Values()
    a, _ = controller(pair[0], tmp_path / 'a', depth=True, diagnostic=diagnostic)
    b, _ = controller(pair[1], tmp_path / 'b', depth=True, diagnostic=diagnostic)
    for tx in (a, b):
        tx._ledger = Ledger({i: {'tx_id': i, 'physical_tx_confirmed': True,
                                'marker_device_us': 1000000 + i * 10000,
                                'capture_window': {'sample_count': 2048}}
                             for i in range(512)})
    ping = PingFrame(99, 1000000 + 511 * 10000, 2000, 0, 0, 200, 1,
                     np.full(2048, 200, dtype='uint16'))
    a.on_ping(ping)
    old_own = a.is_own_ping(ping)  # Original HostService classification.
    association = b.on_ping(ping)
    assert association.own == old_own
    assert a._ledger.lookups == 5
    assert b._ledger.lookups == 2  # Original reconciliation + one association.
    assert b._ledger.visits == 1024
    assert a._ledger.visits == (2049 if diagnostic == 'ECHO_AD' else 2560)


@pytest.mark.parametrize('first_count', [2048, 8192])
def test_depth_auto_keeps_first_match_geometry_and_same_abort_decision(pair, tmp_path, first_count):
    async def run():
        a, ea = controller(pair[0], tmp_path / 'a', depth=True)
        b, eb = controller(pair[1], tmp_path / 'b', depth=True)
        aborted = [0, 0]
        for index, tx in enumerate((a, b)):
            tx.policy['rate_policy'] = 'depth_auto'
            tx._ledger = {11: {'tx_id': 11, 'physical_tx_confirmed': True, 'marker_device_us': 1000000,
                              'capture_window': {'sample_count': first_count}},
                          12: {'tx_id': 12, 'physical_tx_confirmed': True, 'marker_device_us': 1000001,
                               'capture_window': {'sample_count': 2048}}}
            async def abort(i=index): aborted[i] += 1
            tx.abort = abort
        ping = PingFrame(1, 1000000, 2000, 0, 0, 200, 1, np.full(2048, 200, dtype='uint16'))
        a.on_ping(ping); result = b.on_ping(ping)
        await asyncio.sleep(0)
        assert result.own and result.first['tx_id'] == 11 and result.last['tx_id'] == 12
        assert state(a) == state(b) and ea == eb
        assert aborted == [int(first_count != 2048)] * 2
    asyncio.run(run())


@pytest.mark.parametrize('origin', ['own', 'native', 'legacy_fallback'])
def test_service_consumes_only_same_step_association_without_second_marker_scan(pair, tmp_path, monkeypatch, origin):
    module = importlib.import_module(pair[1].name + '.service')
    io = importlib.import_module(pair[1].name + '.archive_io')
    from no5_sonard.config import ServiceConfig
    from no5_sonard.service import SonarService
    host = module.HostService(ServiceConfig(device='/no-device', storage_root=tmp_path), archive_enabled=False)
    calls = []
    association = (None if origin == 'legacy_fallback' else
                   pair[1].clip.PingAssociation({'tx_id': 1} if origin == 'own' else None, None))
    def receive(ping): calls.append('ping'); return association
    def own(ping):
        calls.append('fallback')
        if origin != 'legacy_fallback': raise AssertionError('duplicate marker lookup')
        return True
    host.transmit = SimpleNamespace(on_ping=receive, is_own_ping=own)
    class Finished(Exception): pass
    async def base(self, ping, client): self.status.last_ping_utc_ns = 1700000000000000000
    async def process(function, **kwargs):
        assert kwargs['acquisition_source'] == ('native' if origin == 'native' else 'own')
        raise Finished
    monkeypatch.setattr(SonarService, '_handle_ping', base)
    monkeypatch.setattr(io, 'complete_in_worker', process)
    ping = PingFrame(1, 1000003, 2000, 0, 0, 200, 1, np.full(2048, 200, dtype='uint16'))
    with pytest.raises(Finished):
        asyncio.run(host._process_ping(ping, SimpleNamespace(clock=SimpleNamespace(ready=True))))
    assert calls == (['ping', 'fallback'] if origin == 'legacy_fallback' else ['ping'])


def matrix_cases():
    return [dict(variant=variant, archive=archive, repeat=repeat,
                 CPU=cpu, p95_ms=latency, integrity_passed=True, processed_pings=1118)
            for archive in (False, True) for repeat in (1, 2, 3)
            for variant, cpu, latency in [('baseline', 60., 130.), ('reference', 56., 125.), ('candidate', 52., 120.)]]


def test_matrix_selection_requires_each_increment_and_all_three_repeats():
    from run_reference_cpu_matrix import acceptance
    cases = matrix_cases()
    assert acceptance(cases)['selected_variant'] == 'candidate'
    one = next(v for v in cases if v['variant'] == 'candidate' and v['repeat'] == 2)
    one['CPU'] = 57.  # Median gain alone cannot hide a losing paired run.
    assert acceptance(cases)['selected_variant'] == 'reference'
    with pytest.raises(ValueError): acceptance(cases[:-1])


def test_matrix_rejects_latency_or_live_regression_but_keeps_recorder_quality_separate():
    from run_reference_cpu_matrix import acceptance
    cases = matrix_cases()
    for v in cases:
        v['archive_integrity_passed'] = False  # Separate quality, not Live failure.
        if v['variant'] == 'candidate': v['p95_ms'] = 150.
    assert acceptance(cases)['selected_variant'] == 'reference'
    next(v for v in cases if v['variant'] == 'reference')['processed_pings'] = 1117
    assert acceptance(cases)['selected_variant'] == 'baseline'


def test_matrix_cannot_start_from_a_short_or_unclean_qualification(tmp_path):
    import hashlib
    import json
    import time
    from run_reference_cpu_matrix import completed_qualification
    result = dict(passed=True, duration_observed_s=600., duration_target_s=600, error_count=0,
                  stream=dict(gateway_received_bytes=80*1024**2, gateway_path='android_guest_relay',
                              gateway_observation_transport='emulator_console_loopback'))
    path = tmp_path / 'qualified-live-result.json'
    path.write_text(json.dumps(result))
    guard = dict(final_actual_pins_passed=True, no_active_qualification=True,
                 own_reader_and_forward_closed=True, result_sha256=hashlib.sha256(path.read_bytes()).hexdigest(),
                 prepared_epoch_s=time.time())
    (tmp_path / 'post-qualification-ready.json').write_text(json.dumps(guard))
    with pytest.raises(RuntimeError): completed_qualification(tmp_path)
    result.update(duration_observed_s=21600.01, duration_target_s=21600)
    path.write_text(json.dumps(result));guard['result_sha256'] = hashlib.sha256(path.read_bytes()).hexdigest()
    (tmp_path / 'post-qualification-ready.json').write_text(json.dumps(guard))
    assert completed_qualification(tmp_path) == guard
    guard['own_reader_and_forward_closed'] = False
    (tmp_path / 'post-qualification-ready.json').write_text(json.dumps(guard))
    with pytest.raises(RuntimeError): completed_qualification(tmp_path)


def test_parallel_pi_measurement_requires_the_new_explicit_request_and_preserves_live_run(tmp_path):
    import json
    import time
    from run_reference_cpu_matrix import concurrent_measurement_authorization
    path = tmp_path / 'parallel-pi-measurement-authorized.json'
    guard = dict(prepared_epoch_s=time.time(), direct_user_request='Dann miss die neuen Optimierungen',
                 scope='isolated_pi_pty_cpu_only_preserve_mac_qualification',
                 mac_live_process_identity_verified=True, mac_live_progress_error_count=0,
                 mac_live_progress_age_s=2., source_and_HELM_unchanged=True,
                 no_qualification_control_commands=True)
    path.write_text(json.dumps(guard))
    assert concurrent_measurement_authorization(tmp_path) == guard
    for field, value in [('direct_user_request', 'old request'), ('mac_live_progress_error_count', 1),
                         ('mac_live_process_identity_verified', False), ('mac_live_progress_age_s', 16.),
                         ('source_and_HELM_unchanged', False), ('no_qualification_control_commands', False),
                         ('prepared_epoch_s', time.time() - 61)]:
        path.write_text(json.dumps({**guard, field: value}))
        with pytest.raises(RuntimeError): concurrent_measurement_authorization(tmp_path)


def test_each_case_permission_is_fresh_identity_bound_and_consumed_once(tmp_path):
    import json,time
    from run_reference_cpu_matrix import consume_case_permission
    identity = {'pid': 100, 'start_ticks': 200}
    assert not consume_case_permission(tmp_path, 0, identity)
    permit = {'case_index': 0, **identity, 'prepared_epoch_s': time.time()}
    path = tmp_path/'case-permit-0.json';path.write_text(json.dumps(permit))
    assert consume_case_permission(tmp_path, 0, identity)
    assert not consume_case_permission(tmp_path, 0, identity)
    path.write_text(json.dumps({**permit,'start_ticks':201}))
    with pytest.raises(RuntimeError):consume_case_permission(tmp_path, 0, identity)
