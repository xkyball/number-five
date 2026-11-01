"""Explicit No5-equivalent conservative source-SCAMIN authoring proposal.

Pure host arithmetic: no I/O, writer, native call or implicit range default.
The native cell's declared view policy remains a separate mandatory input.
This is a portrayal policy with bounded overdisplay, not exact S57 parity.
"""
import math
import struct

from no_chart_converter.navionics.native_level_policy import derive_native_band

POLICY = 'NO5_HORIZONTAL_PPMM3_NO_PREMATURE_SCAMIN/1'
LN2 = 0.69314718
CQ_REFERENCE = 42327.265625


def _need(value, reason):
    if not value:
        raise ValueError(reason)


def _positive(value, reason):
    _need(type(value) in (int, float) and math.isfinite(value) and value > 0, reason)
    return float(value)


def _f32(value):
    try:
        result = struct.unpack('<f', struct.pack('<f', value))[0]
    except (OverflowError, struct.error) as error:
        raise ValueError('finite F32 required') from error
    _need(math.isfinite(result), 'FINITE_F32')
    return result


def _bits(value):
    return struct.unpack('<I', struct.pack('<f', value))[0]


def _from_bits(bits):
    return struct.unpack('<f', struct.pack('<I', bits))[0]


def _hex(value):
    return struct.pack('<f', value).hex()


def no5_horizontal_calibration():
    """Named existing No5 PPMM and projection convention, not a97 screen DPI.

    The ratio matches horizontal angular scale in the source formulas. F64
    host operations do not claim arbitrary native projection bit parity.
    """
    source_angular_scale = (6378137.0 * .9996) * (math.pi / 180.0)
    native_angular_scale = 6378388.0 / 57.29577951308
    return dict(policy=POLICY, pixelsPerMillimetre=3.0,
                nativeProjectedToSourceViewportScale=source_angular_scale/native_angular_scale,
                convention='HORIZONTAL_ANGULAR_SCALE_MATCH',
                physicalA97DPIClaimed=False)


def source_denominator(native_units_per_pixel, calibration):
    _need(calibration == no5_horizontal_calibration(), 'EXPLICIT_NAMED_NO5_CALIBRATION')
    mpp = _positive(native_units_per_pixel, 'POSITIVE_NATIVE_PROJECTED_UNITS_PER_PIXEL')
    return mpp * calibration['nativeProjectedToSourceViewportScale'] * 1000.0 * calibration['pixelsPerMillimetre']


def _native_range(mpp, zoom_steps):
    """Selected genuine uncorrected CQ formula; host libm prediction only."""
    q = _f32(CQ_REFERENCE/_f32(mpp))
    logarithm = math.log(float(q))/LN2
    step = math.floor(logarithm)-zoom_steps
    return _f32(math.ldexp(1.0, 12-max(0, step))), logarithm


def scamin_native_cap(scamin, calibration, native_view_profile):
    _need(type(scamin) is int and 0 < scamin < 1 << 31, 'EXACT_POSITIVE_SOURCE_SCAMIN')
    _need(type(native_view_profile) is dict and
          set(native_view_profile) == {'latitudeAdjustment', 'zoomSteps'} and
          native_view_profile['latitudeAdjustment'] == 'OFF' and
          type(native_view_profile['zoomSteps']) is int and
          -4 <= native_view_profile['zoomSteps'] <= 4,
          'EXPLICIT_SOURCE_QUALIFIED_NATIVE_VIEW_PROFILE')
    factor = source_denominator(1.0, calibration)
    threshold = scamin/factor
    mpp = _f32(threshold)
    if source_denominator(mpp, calibration) > scamin:
        mpp = _from_bits(_bits(mpp)-1)
    _need(mpp >= .01, 'SOURCE_THRESHOLD_WITHIN_SELECTED_CQ_DOMAIN')
    cap, logq = _native_range(mpp, native_view_profile['zoomSteps'])
    floor_distance = min(logq-math.floor(logq), math.ceil(logq)-logq)
    # A source-model boundary uncertainty widens visibility instead of hiding.
    guard_widened = floor_distance < 1e-6
    if guard_widened:
        cap = min(4096.0, cap*2.0)
    if cap == 4096.0:
        # CQ clamps the logarithmic step at0. The coarsest range therefore
        # continues indefinitely in this scalar model; a finite search endpoint
        # cannot create a real upper bucket edge. The declared cell/view band
        # still bounds the eventual feature authoring operation.
        return dict(sourceSCAMIN=scamin, policy=POLICY,
                    thresholdNativeProjectedUnitsPerPixel=threshold,
                    lastSourceVisibleMppF32LEHex=_hex(mpp),
                    maximumNativeRangeF32LEHex=_hex(cap), maximumNativeRange=cap,
                    thresholdLogQ=logq, sourceBoundaryGuardWidened=guard_widened,
                    modeledBucketLastMppF32=None, modeledBucketNextMppF32=None,
                    modeledBucketUpperNo5Denominator=None,
                    modeledMaximumOverdisplayDenominator=None,
                    modeledBucketUpperBoundFinite=False,
                    modeledBucketBoundary='SATURATED_COARSEST_RANGE_NO_FINITE_UPPER_EDGE',
                    nativeExecuted=False, exactSCAMINParity=False,
                    guarantee='NO_PREMATURE_HIDE_WITHIN_DECLARED_SOURCE_MODEL_PROFILE',
                    qualification='SOURCE_HOST_MODEL_NOT_NATIVE_LIBM_BIT_ORACLE')
    # Finite binary32 search, not a view/geometry enumeration. Report the host
    # model's last accepted MPP and its first rejected neighbour separately.
    lo, hi = _bits(_f32(.01)), _bits(_f32(1e6))
    while lo < hi:
        mid = (lo+hi+1)//2
        if _native_range(_from_bits(mid), native_view_profile['zoomSteps'])[0] <= cap:
            lo = mid
        else:
            hi = mid-1
    last = _from_bits(lo); following = _from_bits(lo+1)
    _need(_native_range(last, native_view_profile['zoomSteps'])[0] <= cap and
          _native_range(following, native_view_profile['zoomSteps'])[0] > cap,
          'FINITE_SELECTED_CQ_BUCKET_EDGE')
    return dict(sourceSCAMIN=scamin, policy=POLICY,
                thresholdNativeProjectedUnitsPerPixel=threshold,
                lastSourceVisibleMppF32LEHex=_hex(mpp),
                maximumNativeRangeF32LEHex=_hex(cap),
                maximumNativeRange=cap,
                thresholdLogQ=logq, sourceBoundaryGuardWidened=guard_widened,
                modeledBucketLastMppF32=last, modeledBucketNextMppF32=following,
                modeledBucketUpperNo5Denominator=source_denominator(last, calibration),
                modeledMaximumOverdisplayDenominator=source_denominator(last, calibration)-scamin,
                nativeExecuted=False, exactSCAMINParity=False,
                guarantee='NO_PREMATURE_HIDE_WITHIN_DECLARED_SOURCE_MODEL_PROFILE',
                qualification='SOURCE_HOST_MODEL_NOT_NATIVE_LIBM_BIT_ORACLE')


def derive_scamin_band(*, source_scamin, native_cell_band, calibration,
                       native_view_profile):
    """Compose a real threshold with an independently authored cell view band.

    Missing SCAMIN gets the unchanged cell policy, never a made-up denominator.
    Queries/attributes/geometry are outside this pure authoring operation.
    """
    cell = derive_native_band(native_cell_band)
    if source_scamin is None:
        return dict(policy=POLICY, sourceSCAMIN=None,
                    disposition='SOURCE_SCAMIN_ABSENT_EXPLICIT_CELL_POLICY_ONLY',
                    native_level_range=cell['native_level_range'], cellPolicy=cell,
                    sourceDenominatorInvented=False, exactSCAMINParity=False)
    cap = scamin_native_cap(source_scamin, calibration, native_view_profile)
    interval = native_cell_band['nativeViewRangeF32LEHex']
    low = struct.unpack('<f', bytes.fromhex(interval['minimumInclusive']))[0]
    high = struct.unpack('<f', bytes.fromhex(interval['maximumInclusive']))[0]
    selected_high = min(high, cap['maximumNativeRange'])
    if selected_high < low:
        # Retain the owner's query/attributes through its explicit cell policy;
        # do not invent an empty range or silently drop the source owner.
        band = cell
        disposition = 'NO_SOURCE_VISIBLE_CELL_VIEW_CONSERVATIVE_CELL_KEEP'
    else:
        request = dict(native_cell_band, nativeViewRangeF32LEHex=dict(
            minimumInclusive=interval['minimumInclusive'], maximumInclusive=_hex(selected_high)))
        band = derive_native_band(request)
        disposition = 'CONSERVATIVE_CQ_BUCKET_FEATURE_BAND'
    return dict(policy=POLICY, sourceSCAMIN=source_scamin, disposition=disposition,
                native_level_range=band['native_level_range'], featurePolicy=band,
                cellPolicy=cell, SCAMINBucket=cap, sourceDenominatorInvented=False,
                exactSCAMINParity=False, queryGeometryAndAttributesChanged=False)
