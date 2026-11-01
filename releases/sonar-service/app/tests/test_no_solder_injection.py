"""Meaningful limits and counterexamples for the proposed clip-in route."""
import importlib.util
from pathlib import Path

import numpy as np
import pytest

ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location(
    'no_solder_injection', ROOT/'hardware/venterior-model/no_solder_injection.py')
clip = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(clip)


def test_one_second_train_has_ten_distinct_starts_and_native_margin():
    result = clip.schedule()
    own = np.array(result['own_starts_s'])
    assert len(own) == 10
    assert np.allclose(np.diff(own), .1)
    assert own[-1] < 1
    assert result['minimum_separation_s_assuming_native_period'] == pytest.approx(.03)
    # Slightly different native timing can collide with the same own train.
    # A calculated margin may never replace real marker phase qualification.
    collided = clip.schedule(native_period=.43)
    assert collided['minimum_separation_s_assuming_native_period'] < 1e-12
    assert not collided['hardware_clock_or_collision_qualification']


@pytest.mark.parametrize('kwargs', [
    {'count': 11}, {'count': 0}, {'count': 1.5}, {'count': True},
    {'own_interval': .099}, {'native_period': 0}, {'offset': -.01},
    {'native_period': float('nan')}, {'own_interval': float('inf')},
])
def test_unbounded_or_invalid_train_rejected(kwargs):
    with pytest.raises(ValueError):
        clip.schedule(**kwargs)


def test_current_bounds_include_resistor_tolerance_and_negative_transient():
    bound = clip.resistor_current_bounds()
    assert bound['gpio_470ohm_1percent_at_P_minus_0p5_v_bound_a_each'] < .012
    assert bound['three_branches_at_P_minus_0p5_v_bound_a_sum'] < .05
    assert bound['gpio_470ohm_1percent_at_P_minus_0p5_v_bound_a_each'] > 3.63/470
    assert not bound['drive_strength_is_current_limiter']
    assert bound['other_GPIO_and_QSPI_current_must_fit_remaining_budget']


def test_probe_cases_do_not_confuse_a_clamp_with_a_usable_input():
    assert clip.probe_interpretation(.274).startswith('consistent_with')
    assert 'strong_clamp' in clip.probe_interpretation(.014)
    assert 'requires_review' in clip.probe_interpretation(.144)
    for voltage in [.41, 3.3, -.1, float('nan')]:
        assert clip.probe_interpretation(voltage).startswith('disconnect')
    assert 'not_TX_qualified' in clip.probe_interpretation(.274)


def test_public_spice_receipt_contains_actual_counterexamples_and_no_hardware_claim():
    import json
    receipt = json.loads((ROOT/'hardware/venterior-model/no-solder-injection-20261015.json').read_text())
    weak = {case['name']: case for case in receipt['weak_probe']}
    assert .18 < weak['NPN_idle']['voltage_P_v'] < .38
    assert weak['active_clamp_5ohm']['voltage_P_v'] < .05
    assert .05 < weak['active_clamp_100ohm']['voltage_P_v'] < .18
    assert len(receipt['burst_hypotheses']) == 8
    for case in [*receipt['weak_probe'], *receipt['burst_hypotheses']]:
        assert case['engine_errors'] == []
        assert len(case['deck_sha256']) == 64
    assert not receipt['gates']['ten_hz_hardware_qualified']
    assert receipt['gates']['finite_multi_GPIO_firmware_pending']


def test_two_output_model_covers_added_load_and_stays_within_current_budget():
    import json
    receipt = json.loads((ROOT/'hardware/venterior-model/two-output-390-20261015.json').read_text())
    bound = receipt['resistor_current_bounds']
    assert bound['worst_each_at_IOVDD_3p63_and_P_minus_0p5_and_1percent_tolerance_a'] < .012
    assert bound['worst_sum_at_same_assumptions_a'] < .05
    assert receipt['gpio_profile']['gpio'] == [18, 22]
    cases = receipt['burst_hypotheses']
    assert len(cases) == 8
    assert {c['additional_shunt_hypothesis_ohm'] for c in cases} == {None, 300}
    assert all(c['pins'] == 2 and c['series_resistance_ohm_each'] == 390 for c in cases)
    assert all(not c['hardware_qualified'] and not c['engine_errors'] for c in cases)
