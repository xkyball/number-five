from fractions import Fraction as R
import math
import random
import struct
import pytest
from no_chart_converter.rx5 import arm_qround_interval as m

def f32(v):return struct.unpack('<f',struct.pack('<f',v))[0]
def own_scalar(v):
    # Own nearest-even model is one enclosed mode, never Original execution.
    v=f32(v);t=m.vcvt_s32(R(f32(v-1)));j=m.vcvt_s32(R(f32(f32(v-f32(t))+.5)))
    return m.add_s32(t,j)

@pytest.mark.parametrize('r',[R(1,10),R(-1,10),R(1,1<<149),R(-1,1<<149),R(0),R(1<<40),R(-(1<<40))])
def test_enclosure_contains_exact_value(r):
    lo,hi=m.f32_enclosure(r,r)
    assert lo<=r<=hi and lo<=R(f32(float(r)))<=hi

@pytest.mark.parametrize('r,expected',[(R(-5,2),-2),(R(5,2),2),(R(-(1<<80)),m.INT_MIN),(R(1<<80),m.INT_MAX)])
def test_fixed_vcvt_truncation_saturation(r,expected):assert m.vcvt_s32(r)==expected


def test_negative_saturation_final_add_can_wrap_positive():
    v=R(-2500000000);p=m.saturated_negative_qround_interval(v,v)
    assert p['saturatedT']==m.INT_MIN and p['outputS32Interval'][0]>0
    assert p['outputS32Interval'][0]<=own_scalar(v)<=p['outputS32Interval'][1]
    assert own_scalar(v)!=m.INT_MIN


def test_random_own_scalar_controls_are_enclosed_without_discontinuity():
    rng=random.Random(173)
    for _ in range(200):
        a=rng.randrange(-4000000000,-2200000000);b=a+rng.randrange(0,500000)
        p=m.saturated_negative_qround_interval(R(a),R(b));assert p
        lo,hi=p['outputS32Interval']
        assert all(lo<=own_scalar(v)<=hi for v in(a,(a+b)//2,b))

@pytest.mark.parametrize('lo,hi',[(m.INT_MIN-100000,m.INT_MIN+100000),(-100,-1),(-1,1)])
def test_variable_branch_or_signed_add_discontinuity_refuses(lo,hi):assert m.saturated_negative_qround_interval(lo,hi)is None

@pytest.mark.parametrize('v',[True,1.5,float('nan'),float('inf'),'0'])
def test_ambiguous_nonexact_inputs_refuse(v):
    with pytest.raises(ValueError):m.f32_enclosure(v,v)


def test_exact_representable_endpoints_do_not_get_unnecessary_ulp_padding():
    assert m.f32_enclosure(R(-(1<<31)),R(-(1<<31)))==(R(-(1<<31)),R(-(1<<31)))
    assert m.add_s32(m.INT_MIN,m.INT_MIN)==0
    assert m.add_s32(m.INT_MAX,1)==m.INT_MIN


def test_lower_saturation_plateau_crossing_is_continuous_at_zero():
    p=m.saturated_negative_qround_interval(-(1<<32)-100000,-(1<<32)+100000)
    assert p and p["outputS32Interval"][0]==0 and p["outputS32Interval"][1]>0
