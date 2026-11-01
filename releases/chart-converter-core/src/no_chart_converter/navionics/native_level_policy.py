"""Explicit native-view-band authoring model; no SCAMIN conversion or I/O.

Independently authored arithmetic from the pinned public normal view chain.
Host libm is a prediction, not a bit-exact native execution oracle. The caller
provides a native normalized-header48 (captured or separately source-qualified
prediction with external provenance), not the wire Kind17 body.
"""

import math
import struct

LN2_LITERAL = 0.69314718
HEADER_DIVISOR = 3704.54
REQUEST_FORMAT = "no5-native-view-band-policy/1"


def _i32(value, name):
    if type(value) is not int or not -(1 << 31) <= value < (1 << 31):
        raise ValueError(name + ": signed I32 required")
    return value


def _f32(value):
    try:
        return struct.unpack("<f", struct.pack("<f", value))[0]
    except (OverflowError, struct.error) as exc:
        raise ValueError("finite F32 required") from exc


def _positive_f32_hex(text, name):
    if type(text) is not str or len(text) != 8:
        raise ValueError(name + ": exact four-byte LE hex required")
    try:
        raw = bytes.fromhex(text)
    except ValueError as exc:
        raise ValueError(name + ": hex required") from exc
    if len(raw) != 4 or raw.hex() != text:
        raise ValueError(name + ": canonical lowercase hex required")
    value = struct.unpack("<f", raw)[0]
    if not math.isfinite(value) or value <= 0:
        raise ValueError(name + ": positive finite F32 required")
    return value


def _header(text):
    if type(text) is not str or len(text) != 96:
        raise ValueError("exact native header48 LE hex required")
    try:
        raw = bytes.fromhex(text)
    except ValueError as exc:
        raise ValueError("native header hex required") from exc
    if len(raw) != 48 or raw.hex() != text:
        raise ValueError("canonical native header48 hex required")
    max_zoom = struct.unpack_from("<h", raw, 4)[0]
    grids = struct.unpack_from("<ii", raw, 24)
    header_maximum = struct.unpack_from("<H", raw, 34)[0]
    base = struct.unpack_from("<f", raw, 44)[0]
    if max_zoom < 0 or min(grids) <= 0 or not math.isfinite(base) or base <= 0:
        raise ValueError("positive normalized header/grid and nonnegative zoom required")
    return base, max_zoom, header_maximum


def _environment(env):
    if type(env) is not dict or set(env) != {"gEnvLim0", "gEnvLim1"}:
        raise ValueError("explicit exact environment pair required")
    a = _i32(env["gEnvLim0"], "gEnvLim0")
    b = _i32(env["gEnvLim1"], "gEnvLim1")
    total = _i32(a + b, "environment sum")
    return total, b


def _state_at_zoom(base, zoom, header_maximum, threshold):
    # Source: CalcMapZoom pow(2,q)->F32; CalcMapView2 computes a8 in
    # F64 before one F32 conversion; CalcChartView applies log/floor.
    multiplier = _f32(math.ldexp(1.0, zoom))
    a8 = _f32((float(base) / HEADER_DIVISOR) * float(multiplier))
    if not math.isfinite(a8) or a8 <= 0:
        raise ValueError("modeled positive finite view scale required")
    step = math.floor(math.log(float(a8)) / LN2_LITERAL + 0.5) + 3
    return {
        "mapZoomI32": zoom,
        "chartStepI32": step,
        "zoomMultiplierF32LEHex": struct.pack("<f", multiplier).hex(),
        "chartViewScaleF32LEHex": struct.pack("<f", a8).hex(),
        "rangeBranch": "ORDINARY" if zoom >= threshold else "UNDERZOOM",
        "headerMaximumU16": header_maximum,
    }


def predict_native_state(native_header48_le_hex, environment, native_view_range_f32_le_hex):
    """Model one positive public GetNavionicsRange value, not a denominator.

    The accepted normal CalcScale path is required. Failed/overflowing zooms
    never become an authoring range by clamping. No native object is modified.
    """
    base, header_limit, header_maximum = _header(native_header48_le_hex)
    env_limit, threshold = _environment(environment)
    view_range = _positive_f32_hex(native_view_range_f32_le_hex, "native view range")
    zoom = math.floor(math.log(float(view_range) / (float(base) / HEADER_DIVISOR)) /
                      LN2_LITERAL + 0.5)
    if zoom < 0:
        raise ValueError("CALC_SCALE_BELOW_ZERO: original status 0x10008102")
    if zoom > min(header_limit, env_limit):
        raise ValueError("CALC_SCALE_ABOVE_LIMIT: original status 0x10008101")
    if zoom > 127:
        raise ValueError("finite host F32 multiplier model required")
    return _state_at_zoom(base, zoom, header_maximum, threshold)


def range_accepts(min_level, max_level, *, chart_step, map_zoom,
                  environment_threshold, header_maximum, range_filter_enabled=True):
    """Independent integer predicate for the source-qualified range callback.

    The caller still owns geometry/detail/visibility filtering. This returns
    only the range predicate. With request mask0x2 absent, it is bypassed.
    """
    if any(type(v) is not int or not 0 <= v <= 15 for v in (min_level, max_level)):
        raise ValueError("native nibbles must be explicit integers0..15")
    if min_level > max_level:
        raise ValueError("ordered native range required")
    for value, name in ((chart_step, "chart step"), (map_zoom, "map zoom"),
                        (environment_threshold, "environment threshold")):
        _i32(value, name)
    if type(header_maximum) is not int or not 0 <= header_maximum <= 65535:
        raise ValueError("header maximum U16 required")
    if type(range_filter_enabled) is not bool:
        raise ValueError("explicit filter Boolean required")
    if not range_filter_enabled:
        return True
    lower = chart_step if map_zoom >= environment_threshold else header_maximum
    return min_level <= lower and chart_step <= max_level


def derive_native_band(request):
    """Return an explicit writer range for a caller-declared native view band.

    This bounded policy requires the normal ordinary range branch throughout.
    It is not a format refusal of native underzoom, whose different predicate
    remains available through range_accepts. The interval is inclusive and
    endpoints must be exact positive F32 bit strings. No absent/default band.
    """
    keys = {"format", "mapId", "nativeHeader48LEHex", "environment",
            "nativeViewRangeF32LEHex"}
    if type(request) is not dict or set(request) != keys or request["format"] != REQUEST_FORMAT:
        raise ValueError("exact explicit native view policy request required")
    if type(request["mapId"]) is not str or not request["mapId"]:
        raise ValueError("nonempty caller map ID required")
    interval = request["nativeViewRangeF32LEHex"]
    if type(interval) is not dict or set(interval) != {"minimumInclusive", "maximumInclusive"}:
        raise ValueError("exact inclusive native view interval required")
    low = _positive_f32_hex(interval["minimumInclusive"], "minimum view range")
    high = _positive_f32_hex(interval["maximumInclusive"], "maximum view range")
    if low > high:
        raise ValueError("ordered native view interval required")
    base, _, header_maximum = _header(request["nativeHeader48LEHex"])
    _, threshold = _environment(request["environment"])
    endpoints = [predict_native_state(request["nativeHeader48LEHex"], request["environment"],
                                     interval[name]) for name in ("minimumInclusive", "maximumInclusive")]
    # Positive log is monotone. Enumerating all integer q between endpoints
    # avoids assuming that the two chartStep endpoints describe every state.
    states = [_state_at_zoom(base, q, header_maximum, threshold)
              for q in range(endpoints[0]["mapZoomI32"], endpoints[1]["mapZoomI32"] + 1)]
    if any(row["rangeBranch"] != "ORDINARY" for row in states):
        raise ValueError("OWN_POLICY_SCOPE_UNDERZOOM: separate explicit policy required")
    levels = [row["chartStepI32"] for row in states]
    if not levels or min(levels) < 0 or max(levels) > 15:
        raise ValueError("derived ordinary chart steps do not fit native nibbles0..15")
    lo, hi = min(levels), max(levels)
    assert all(range_accepts(lo, hi, chart_step=row["chartStepI32"],
                            map_zoom=row["mapZoomI32"], environment_threshold=threshold,
                            header_maximum=header_maximum) for row in states)
    return {
        "format": "no5-derived-native-view-band/1", "mapId": request["mapId"],
        "native_level_range": {"min_level": lo, "max_level": hi},
        "packedByte": (lo << 4) | hi, "modeledStates": states,
        "nativeExecuted": False, "sourceSCAMINEncoded": False,
        "qualification": "HOST_SOURCE_MODEL_DECLARED_NATIVE_VIEW_BAND_ONLY",
        "rangeFilterPremise": "request mask0x2 enabled; other filters remain independent",
        "intervalExclusiveVisibilityClaimed": False,
    }
