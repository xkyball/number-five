"""Numerical checks of analog recovery extraction without claiming a TX clock."""
import importlib.util
from pathlib import Path
import sys

import numpy as np
import pytest

tools=Path(__file__).resolve().parents[1]/'tools'
sys.path.insert(0,str(tools))
spec=importlib.util.spec_from_file_location('supply_scope_analysis',tools/'analyze_supply_scope.py')
analysis=importlib.util.module_from_spec(spec)
spec.loader.exec_module(analysis)


def test_periodic_recovery_retained_despite_late_contact_loss_and_mains_component():
    rng=np.random.default_rng(61015)
    t=(np.arange(30000)+.5)*.001
    phase=(t-.12)%.3862
    y=.672-.012*np.exp(-phase/.06)+.003*np.sin(2*np.pi*50*t)
    y+=rng.normal(0,.0001,len(y))
    y[(t>20)&(t<24)]=.03
    y[(t>24)&(t<27)]=1.7
    result,_,_=analysis.analog_cycles(y)
    assert result['fitted_interval_ms']==pytest.approx(386.2,abs=.08)
    assert result['max_abs_trough_fit_residual_ms']<2
    assert result['smoothed_drop_gp26_v_p10_p50_p90'][1]>.007
    assert result['remaining_drop_fraction_100ms_after_smoothed_trough_p10_p50_p90'][1]==pytest.approx(np.exp(-.1/.06),abs=.06)
    assert not result['GP16_time_origin']


def test_mains_only_must_not_become_a_native_ping_cycle():
    t=np.arange(30000)*.001
    with pytest.raises(ValueError,match='Insufficient'):
        analysis.analog_cycles(.445+.003*np.sin(2*np.pi*50*t))


def test_boxcar_has_no_false_zero_padded_edge_droop():
    assert np.allclose(analysis.centered_average(np.full(500,.445),20),.445)
    with pytest.raises(ValueError):
        analysis.centered_average([1,2],20)
