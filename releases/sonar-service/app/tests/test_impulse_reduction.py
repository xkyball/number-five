import hashlib
from types import SimpleNamespace

import numpy as np
import pytest
from fastapi.testclient import TestClient

from no5_sonard_openecho.noise_reduction import NoiseReduction
from no5_sonard_openecho.impulse_reduction import separate_impulses
from no5_sonard_openecho.__main__ import create_host_app
from test_display_selection import make_service


def fixture(n=2048, *, target=900, count=None):
    x = np.arange(n)
    clean = 66 + 140*np.exp(-.5*((x-target)/22)**2)
    clean += 12*np.exp(-.5*((x-580)/8)**2)  # undetected weak target
    clean += 70*np.exp(-.5*((x-1200)/40)**2)  # wide stationary structure
    noise = np.zeros(n)
    positions = list(range(350, n-30, 22))
    if count is not None:
        positions = positions[:count]
    for i in positions:
        noise[i:i+20] += 180*.78**np.arange(20)
    return np.rint(clean+noise).astype(np.uint16), np.rint(clean).astype(np.uint16)


def run(raw, *, impulse='auto', blocks=(), **kw):
    return NoiseReduction(impulse_mode=impulse).process(raw, ping_id=7,
        sample_period_ns=2000, tx_zero_sample_index=0, acquisition_source='own', blocks=blocks, **kw)


def test_repeated_impulses_removed_only_from_derived_display_and_echo_blocks_exact():
    raw, _ = fixture()
    raw.setflags(write=False)
    digest = hashlib.sha256(raw.tobytes()).hexdigest()
    block = SimpleNamespace(start_sample=840, end_sample=960)
    old, _ = run(raw, impulse='off', blocks=[block])
    new, status = run(raw, blocks=[block])
    assert status['impulse_reduction']['active']
    assert status['impulse_reduction']['events'] >= 6
    assert np.mean(new[350:470]) < .3*np.mean(old[350:470])
    np.testing.assert_array_equal(new[815:986], old[815:986])
    assert np.all(new <= raw)
    assert new[570:590].max() >= 10
    assert not np.shares_memory(new, raw)
    assert hashlib.sha256(raw.tobytes()).hexdigest() == digest


@pytest.mark.parametrize('target', [700, 900, 1100])
def test_moving_broad_target_and_new_weak_target_survive_train(target):
    raw, clean = fixture(target=target)
    block = SimpleNamespace(start_sample=target-60, end_sample=target+60)
    a, _ = run(raw, impulse='off', blocks=[block])
    b, _ = run(raw, blocks=[block])
    np.testing.assert_array_equal(a[target-85:target+86], b[target-85:target+86])
    assert abs(int(b[target-40:target+40].argmax())-int(a[target-40:target+40].argmax())) == 0
    assert b[570:590].max() >= 10


@pytest.mark.parametrize('count', [0, 1, 5])
def test_single_new_sharp_return_and_short_train_never_suppressed(count):
    raw, _ = fixture(count=count)
    a, _ = run(raw, impulse='off')
    b, status = run(raw)
    np.testing.assert_array_equal(a, b)
    assert not status['impulse_reduction']['active']


def test_echo_lookalike_outside_repeated_spacing_is_kept():
    raw, _ = fixture(n=4096)
    # A separately detected new narrow echo is protected even when it has
    # exactly the nuisance's morphology and occurs on the train's phase.
    raw[878:898] += np.rint(50*.78**np.arange(20)).astype(np.uint16)
    block = SimpleNamespace(start_sample=878, end_sample=898)
    a, _ = run(raw, impulse='off', blocks=[block])
    b, _ = run(raw, blocks=[block])
    np.testing.assert_array_equal(a[853:924], b[853:924])


def test_invalid_reference_and_unknown_acquisition_are_passthrough():
    raw, _ = fixture()
    a, _ = run(raw, valid_tx=False)
    np.testing.assert_array_equal(a, raw)
    for period, source in [(4000,'own'), (2000,'native')]:
        a, _ = separate_impulses(raw, raw, np.zeros(len(raw),bool), sample_period_ns=period, source=source)
        np.testing.assert_array_equal(a, raw)


def test_off_auto_api_persistence_idempotence_and_validation(tmp_path):
    service = make_service(tmp_path)
    api = TestClient(create_host_app(service))
    url = '/api/v1/sonar/noise-reduction'
    for bad in [None, True, [], 'magic']:
        assert api.put(url,json={'impulse_mode':bad}).status_code == 422
    off = api.put(url,json={'impulse_mode':'off'}).json()
    assert off['policy']['algorithm'] == 'local_adc_background_v2'
    assert off['clear_client_history']
    assert not api.put(url,json={'impulse_mode':'off'}).json()['clear_client_history']
    assert make_service(tmp_path).noise_reduction.impulse_mode == 'off'
    assert api.put(url,json={'impulse_mode':'auto'}).json()['policy']['algorithm'] == 'local_adc_background_v3'


def test_historical_recorded_algorithms_reproduce_exact_metadata():
    raw, _ = fixture()
    old = NoiseReduction(impulse_mode='off', previous_metadata=True)
    clean, decision = run(raw, impulse='off')
    decision.pop('impulse_mode'); decision.pop('impulse_reduction')
    replay, status = NoiseReduction.from_recorded(decision).process(raw, ping_id=7,
        sample_period_ns=2000, tx_zero_sample_index=0, acquisition_source='own')
    assert status == decision
    np.testing.assert_array_equal(replay, clean)
    new, current = run(raw)
    replay, status = NoiseReduction.from_recorded(current).process(raw, ping_id=7,
        sample_period_ns=2000, tx_zero_sample_index=0, acquisition_source='own')
    assert status == current
    np.testing.assert_array_equal(new, replay)


def test_public_read_vectors_match_actual_adc_m1_and_atomic_contract():
    import base64
    import json
    from pathlib import Path
    from no5_sonard.protocol import unpack_echogram_frame
    from no5_sonard.processing_m1 import max_pool_resample
    from no5_sonard_openecho.echogram_context import unpack_context
    fixture = json.loads((Path(__file__).parent/'fixtures/impulse-interface-0417.json').read_text())
    assert len(fixture['cases']) == 9
    outputs = {}
    for case in fixture['cases']:
        raw = np.frombuffer(base64.b64decode(case['raw_adc_base64']),dtype='<u2')
        recorded = case['noise_reduction']['latest']
        clean,status = NoiseReduction.from_recorded(recorded).process(raw,
            ping_id=case['published_ping']['ping_id'],sample_period_ns=case['sample_period_ns'],
            tx_zero_sample_index=0,acquisition_source=case['published_ping']['actual_source'],
            blocks=[SimpleNamespace(**b) for b in case['blocks']])
        assert status == recorded
        assert clean.tobytes() == base64.b64decode(case['derived_adc_base64'])
        metadata,frame = unpack_context(base64.b64decode(case['context_base64']))
        assert metadata == case['context_metadata'] and frame == base64.b64decode(case['frame_base64'])
        header,amplitudes = unpack_echogram_frame(frame)
        np.testing.assert_array_equal(amplitudes,max_pool_resample(clean,start_index=0,bins=1024))
        assert header.displayed_max_depth_m == case['published_ping']['display_window']['display_range_m']
        assert case['published_ping']['noise_active']
        outputs[case['name']] = clean
    assert not np.array_equal(outputs['impulse_auto'], outputs['impulse_off'])
    np.testing.assert_array_equal(outputs['impulse_auto'][815:986], outputs['impulse_off'][815:986])
