"""Internal arithmetic proof support, never a chart or NoInk admission API.

Each F32 arithmetic result is enclosed by adjacent representable values around
exact rational endpoints. This includes normal IEEE rounding modes without
assuming host nearest-even equals the target FPSCR. VCVT.S32.F32 uses fixed
truncation and saturation; the final ARM ADD wraps modulo2**32. Nonfinite,
branch-ambiguous or ADD-discontinuous intervals refuse rather than widening
into an alleged outside/empty certificate. No Original instruction is executed.
"""
from fractions import Fraction
import math
import struct

INT_MIN = -(1 << 31)
INT_MAX = (1 << 31)-1
QT_ROUNDING_CONTRACT_SHA256 = '5fde31db68cd2555f1ea52d12c2f14236f443845774281b690a8e1f77a8ba600'


def _r(value):
    if type(value) not in (int,Fraction):
        raise ValueError('exact integer/Fraction input required, no bool/float')
    value=Fraction(value)
    if abs(value) >= 1 << 100:
        raise ValueError('own finite arithmetic proof budget exceeded')
    return value


def _bits(value): return struct.unpack('<I',struct.pack('<f',value))[0]
def _value(bits): return struct.unpack('<f',struct.pack('<I',bits))[0]


def _adjacent(value, upward):
    if value == 0:
        return _value(1 if upward else 0x80000001)
    return _value(_bits(value)+(1 if (value>0)==upward else -1))


def f32_enclosure(lower, upper):
    """Closed rational interval enclosing every normal-mode F32 rounding."""
    lower,upper=_r(lower),_r(upper)
    if lower>upper:
        raise ValueError('ordered exact interval required')
    a,b=_value(_bits(float(lower))),_value(_bits(float(upper)))
    if Fraction(a)>lower:a=_adjacent(a,False)
    if Fraction(b)<upper:b=_adjacent(b,True)
    return Fraction(a),Fraction(b)


def vcvt_s32(value):
    """Finite ARM VCVT.S32.F32: fixed truncate toward zero, saturate S32."""
    return max(INT_MIN,min(INT_MAX,math.trunc(_r(value))))


def add_s32(a,b):
    if type(a)is not int or type(b)is not int or not INT_MIN<=a<=INT_MAX or not INT_MIN<=b<=INT_MAX:
        raise ValueError('two actual signed32 operands required')
    return(a+b+(1<<31))%(1<<32)-(1<<31)


def saturated_negative_qround_interval(lower,upper):
    """Enclose one proven negative T==INT_MIN branch; return None if unclear.

    Input interval must enclose the actual finite QPolygonF coordinate before
    Qt's first subtraction. This function does not derive projection bounds,
    certify QRegion interpolation, or authorize removing any draw work.
    """
    lo,hi=f32_enclosure(lower,upper)
    if hi>=0:return None
    a,b=f32_enclosure(lo-1,hi-1)
    if(vcvt_s32(a),vcvt_s32(b))!=(INT_MIN,INT_MIN):return None
    # INT_MIN itself is exactly representable as binary32 in every FP mode.
    a,b=f32_enclosure(lo-INT_MIN,hi-INT_MIN)
    a,b=f32_enclosure(a+Fraction(1,2),b+Fraction(1,2))
    j0,j1=vcvt_s32(a),vcvt_s32(b)
    total0,total1=INT_MIN+j0,INT_MIN+j1
    if(total0+(1<<31))//(1<<32)!=(total1+(1<<31))//(1<<32):return None
    return dict(inputF32Interval=[lo,hi],saturatedT=INT_MIN,
        remainderIntegerInterval=[j0,j1],outputS32Interval=[add_s32(INT_MIN,j0),add_s32(INT_MIN,j1)],
        normalFPRoundingModesEnclosed=True,integerAddDiscontinuityCrossed=False)
