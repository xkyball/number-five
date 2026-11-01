from pathlib import Path
import sys
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'tools'))
from analyze_depth_echo_motion import motion_verdict


def test_different_return_position_uses_independent_lcd_change():
    result=motion_verdict([1.134,.8325,1.25175],[1.,.8,1.1])
    assert result['echo_motion_passed'] and not result['same_position_return']
    assert abs(result['lcd_adjusted_closure_error_m']-.01775)<1e-9


def test_stationary_return_or_wrong_direction_does_not_pass():
    assert not motion_verdict([1.134,1.134,1.134],[1.,.8,1.1])['echo_motion_passed']
    assert not motion_verdict([1.134,1.5,1.25],[1.,.8,1.1])['echo_motion_passed']
