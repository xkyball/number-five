"""Own normal-path RawPlan -> native header scalar HOST model.

No binary bodies, native libraries, files or coordinate fixtures are included.
Host libm is a prediction, not a universal bit-exact ARM libm replacement.
The two externally compared saved cases are qualified separately.
"""
import math
import struct

from no_chart_converter.navionics.raw_grid_bounds import validate_raw_grid_bounds

LN2 = 0.69314718
EPSILON = 2.220446049250313e-16
CALCFIELD_Y_LIMIT = 17779935
HEADER_DIVISOR = 3704.54
RADIUS_LITERAL = 6378388.0
PI_OVER_TWO_LITERAL = 1.570796327
LATITUDE_FACTOR_LITERAL = 1.0067642927
DEGREES_LITERAL = 57.29577951308
FIELD_METRES_LITERAL = 111136.2


def _uint(v, bits, name):
    if type(v) is not int or not 0 <= v < 1 << bits:
        raise ValueError(name+': unsigned integer field required')
    return v


def _f32(v):
    try:
        out = struct.unpack('<f', struct.pack('<f', v))[0]
    except (OverflowError, struct.error) as exc:
        raise ValueError('finite F32 output required') from exc
    if not math.isfinite(out):
        raise ValueError('finite F32 output required')
    return out


def _hex32(v):
    return struct.pack('<f', v).hex()


def _latitude(y):
    # Preserve source operation order and literals, without a CRS/datum claim.
    value = math.exp(float(y) / RADIUS_LITERAL)
    value = math.atan(value)
    value = value + value
    value = value - PI_OVER_TWO_LITERAL
    value = math.tan(value)
    value = value * LATITUDE_FACTOR_LITERAL
    return math.atan(value)


def _calc_field(y0, y1, header_max_zoom, fourth_arg):
    if not (-CALCFIELD_Y_LIMIT <= y0 <= CALCFIELD_Y_LIMIT and
            -CALCFIELD_Y_LIMIT <= y1 <= CALCFIELD_Y_LIMIT and y0 < y1):
        raise ValueError('CALCFIELD_NORMAL_SUCCESS_Y_DOMAIN_REQUIRED')
    a = _latitude(y0) * DEGREES_LITERAL
    b = _latitude(y1) * DEGREES_LITERAL
    value = abs(a - b)
    value = value * FIELD_METRES_LITERAL
    value = value / math.pow(2.0, float(header_max_zoom - fourth_arg))
    out = _f32(value)
    if out <= 0:
        raise ValueError('CALCFIELD_POSITIVE_NORMAL_F32_REQUIRED')
    return out


def _round_range(value):
    if not math.isfinite(value) or value <= 0:
        raise ValueError('ROUND_RANGE_POSITIVE_FINITE_REQUIRED')
    exponent = math.ceil(math.log(value) / LN2)
    return _f32(math.exp(float(exponent) * LN2))


def _level_from_field(value):
    v = float(value) / HEADER_DIVISOR
    rounded = _round_range(v + 0.0625)
    half = _f32(rounded * _f32(0.5))
    selected = rounded if v - 0.0625 >= float(half) else half
    quotient = math.log(float(selected)) / LN2
    # VCVT.S32.F64 truncates toward zero. For a negative log, floor is WRONG.
    signed = math.trunc(quotient) + 3
    return signed & 0xffff, dict(fieldOver3704_54F64=v,
        roundRangeF32LEHex=_hex32(rounded), selectedF32LEHex=_hex32(selected),
        logRatioF64=quotient, signedBeforeU16Store=signed,
        nativeU16ModuloStoreVisible=not 0 <= signed <= 65535)


def predict_numeric_header(raw_map_plan):
    """Predict scale/bounds/header fields without inventing a runtime chart ID.

    Accepts an existing map bodyPlan or its rawHeader+firstLayerOffset subset.
    This deliberately reuses the existing conservative OWN grid2..65536 policy;
    it is not a native file-format maximum or a native acceptance statement.
    """
    if type(raw_map_plan) is not dict or type(raw_map_plan.get('rawHeader')) is not dict:
        raise ValueError('raw map bodyPlan required')
    rh = raw_map_plan['rawHeader']
    if set(rh) != {'flags','rawBounds','rawGrid','rawLevels'}:
        raise ValueError('exact rawHeader fields required')
    flags = _uint(rh['flags'],16,'flags')
    levels = rh['rawLevels']
    if type(levels) not in (list,tuple) or len(levels) != 2:
        raise ValueError('two raw level words required')
    for v in levels:
        _uint(v,32,'raw level')
    first = _uint(raw_map_plan.get('firstLayerOffset'),32,'firstLayerOffset')
    if first == 0:
        raise ValueError('positive relocated firstLayerOffset required')
    arithmetic = validate_raw_grid_bounds(rh['rawBounds'],rh['rawGrid'])
    bounds = tuple(rh['rawBounds'])
    sx, sy = arithmetic['shiftedSignedGrid']
    # ReadMapHeader converts the full signed shifted Y as fixedpoint with9
    # fractional bits, before log/floor. This is not the rawLevels pair.
    qfloat = math.log(float(sy)/512.0)/LN2 + EPSILON
    max_zoom = math.trunc(math.floor(qfloat))
    if not 0 <= max_zoom <= 32767:
        raise ValueError('normal signed16 maxZoom model domain required')
    ratio = float(sy)/float(sx)
    dy = float(bounds[3])-float(bounds[1])
    extent = math.trunc(dy/ratio)
    adjusted_y1 = bounds[1]+extent
    coarse = _calc_field(bounds[1],adjusted_y1,max_zoom,max_zoom)
    fine = _calc_field(bounds[1],adjusted_y1,max_zoom,0)
    # Genuine normal CalcField status0x80000000 takes the second CalcField
    # branch, writing +44; it must not leave the initial -14 literal in place.
    fine_scaled = _f32(fine * _f32(16384.0))
    maximum, max_trace = _level_from_field(fine_scaled)
    minimum, min_trace = _level_from_field(coarse)
    return dict(format='no5-raw-map-header-normalization-host-prediction/1',
        qualification='HOST_SOURCE_MODEL_NOT_UNIVERSAL_ARM_LIBM_ORACLE',
        flagsU16=flags,rawBounds=list(bounds),shiftedGridBeforeNormalization=[sx,sy],
        normalizedGridI32=[(sx & ~16383)-16384,(sy & ~16383)-16384],
        maxZoomI16=max_zoom,minimumU16=minimum,maximumU16=maximum,
        firstLayerOffsetU32=first,coarseFieldF32LEHex=_hex32(coarse),mapBaseF32LEHex=_hex32(fine),
        adjustedCalcFieldYBoundsI32=[bounds[1],adjusted_y1],gridLogQuotientF64=qfloat,
        levelNormalization=dict(minimum=min_trace,maximum=max_trace),
        rawLevelsUsedForFinalNativeLevels=False,sourceSCAMINConverted=False,
        runtimeHeaderPrefixUnresolved=True,OriginalCalls=0,
        normalApiHeaderReadRequiredForUnqualifiedInputs=True)


def predict_header48(raw_map_plan, *, actual_chart_id_u16, map_ordinal_u16):
    """Assemble a predicted POD only with explicit normal caller ID words.

    ReadMapHeader+0 is cursorR0->word0, +2 is its R1 map ordinal. These are
    not stored inside the raw Kind17 payload and must never be guessed from
    source IDs or grid/SCAMIN values. MapBaseF32 needs neither ID word.
    """
    cid = _uint(actual_chart_id_u16,16,'actual chart ID')
    mid = _uint(map_ordinal_u16,16,'map ordinal')
    out = predict_numeric_header(raw_map_plan)
    raw = struct.pack('<4H6i2HI',cid,mid,out['maxZoomI16'],out['flagsU16'],
        *out['rawBounds'],*out['normalizedGridI32'],out['minimumU16'],out['maximumU16'],out['firstLayerOffsetU32'])
    raw += bytes.fromhex(out['coarseFieldF32LEHex'])+bytes.fromhex(out['mapBaseF32LEHex'])
    assert len(raw) == 48
    return dict(out, nativeHeader48LEHex=raw.hex(),actualChartIdU16=cid,mapOrdinalU16=mid,
                runtimeHeaderPrefixUnresolved=False,normalCallerIDWordsExplicit=True)
