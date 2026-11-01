"""Own RAW batch-input guard; no writer, native call, or CRS conversion.

A successful result proves only the named input/normalization arithmetic.
It is deliberately not a generic Original-Header/LOD/Scale qualification.
"""
import math
from collections.abc import Sequence

I32_MIN = -(1 << 31)
I32_MAX = (1 << 31) - 1
SHIFT = 14
HEADER_X_ABS_LIMIT = 20_038_300
HEADER_Y_ABS_LIMIT = 17_779_936
MAX_RAW_GRID_U16_DOMAIN = 65_536


def _integers(values, length, label):
    if not isinstance(values, Sequence) or isinstance(values, (str, bytes)):
        raise ValueError(label + "_SEQUENCE_REQUIRED")
    if len(values) != length or any(type(v) is not int for v in values):
        raise ValueError(label + "_EXACT_INTEGER_FIELDS_REQUIRED")
    return tuple(values)


def validate_raw_grid_bounds(raw_bounds, raw_grid, *, bank_bounds=None,
                             bank_grid=None):
    """Validate a nonwrapping RAW Header/Bank proposal, without writing it.

    Optional bank fields must be supplied together and equal the header.
    The X/Y limits are the freshly read ReadMapHeader bounds checks, not
    a declaration of a CRS/datum. They are inclusive in that function.
    Point-to-degrees has a separate stricter Y threshold and is not called.

    Bounds/grid rectangles may differ in aspect; the actual binary64
    intermediate passed to CalcField must stay positive, signed-I32-safe,
    and inside this deliberately conservative Y envelope. CalcField's
    remaining range/LOD normalization is explicitly still UNQUALIFIED.
    """
    bounds = _integers(raw_bounds, 4, "BOUNDS")
    grid = _integers(raw_grid, 2, "GRID")
    if any(not I32_MIN <= v <= I32_MAX for v in bounds):
        raise ValueError("BOUNDS_I32_RANGE")
    x0, y0, x1, y1 = bounds
    if not (x0 < x1 and y0 < y1):
        raise ValueError("NONWRAPPING_STRICT_BOUNDS_REQUIRED")
    if max(abs(x0), abs(x1)) > HEADER_X_ABS_LIMIT:
        raise ValueError("HEADER_X_ENVELOPE")
    if max(abs(y0), abs(y1)) > HEADER_Y_ABS_LIMIT:
        raise ValueError("HEADER_Y_ENVELOPE")
    if any(not 2 <= n <= MAX_RAW_GRID_U16_DOMAIN for n in grid):
        raise ValueError("GRID_U16_DOMAIN_REQUIRES_2_TO_65536")
    shifted = tuple(n << SHIFT for n in grid)
    normalized = tuple(v - (1 << SHIFT) for v in shifted)
    if any(not 0 < v <= I32_MAX for v in shifted + normalized):
        raise ValueError("GRID_SIGNED_FIXEDPOINT_RANGE")
    # This mirrors only ReadMapHeader 499078..4990d8. The ratio is of the
    # two signed integer values after their <<14, converted to binary64.
    ratio = float(shifted[1]) / float(shifted[0])
    adjusted_extent = float(y1 - y0) / ratio
    if not math.isfinite(adjusted_extent) or not 0 < adjusted_extent < 1 << 31:
        raise ValueError("HEADER_ASPECT_VCVT_I32_RANGE")
    extent_i32 = math.trunc(adjusted_extent)
    adjusted_end = y0 + extent_i32
    if extent_i32 <= 0 or not I32_MIN <= adjusted_end <= I32_MAX:
        raise ValueError("HEADER_ASPECT_ADD_I32_RANGE")
    if not -HEADER_Y_ABS_LIMIT <= adjusted_end <= HEADER_Y_ABS_LIMIT:
        raise ValueError("HEADER_ASPECT_CALCFIELD_ENVELOPE")
    if (bank_bounds is None) != (bank_grid is None):
        raise ValueError("BANK_BOUNDS_AND_GRID_REQUIRED_TOGETHER")
    bank_checked = bank_bounds is not None
    if bank_checked:
        bb = _integers(bank_bounds, 4, "BANK_BOUNDS")
        bg = _integers(bank_grid, 2, "BANK_GRID")
        if bb != bounds or bg != grid:
            raise ValueError("HEADER_BANK_BOUNDS_GRID_MISMATCH")
    return {
        "status": "PASS_RAW_INPUT_AND_SELECTED_NORMALIZATION_ARITHMETIC",
        "rawBounds": list(bounds),
        "rawGrid": list(grid),
        "shift": SHIFT,
        "shiftedSignedGrid": list(shifted),
        "normalizedGridSourcePrediction": list(normalized),
        "u16LocalCoordinateMaximum": [n - 1 for n in grid],
        "headerAspectRatioF64": ratio,
        "headerAspectExtentF64": adjusted_extent,
        "headerCalcFieldEndI32SourcePrediction": adjusted_end,
        "bankCheckedEqual": bank_checked,
        "nativeHeaderLODScaleQualification": "UNQUALIFIED",
        "originalExecutionPerformed": False,
        "worldToRawRoundingPerformed": False,
        "CRSOrDatumIdentified": False,
        "nativeN65536AcceptanceClaim": False,
    }


def validate_u16_xy(points, raw_grid):
    """Check supplied raw vector XY; do not invent ring/part semantics."""
    grid = _integers(raw_grid, 2, "GRID")
    if any(not 2 <= n <= MAX_RAW_GRID_U16_DOMAIN for n in grid):
        raise ValueError("GRID_U16_DOMAIN_REQUIRES_2_TO_65536")
    if not isinstance(points, Sequence) or isinstance(points, (str, bytes)):
        raise ValueError("POINTS_SEQUENCE_REQUIRED")
    result = []
    for ordinal, point in enumerate(points):
        x, y = _integers(point, 2, "XY_" + str(ordinal))
        if not (0 <= x < grid[0] and 0 <= y < grid[1]):
            raise ValueError("XY_OUTSIDE_LOCAL_U16_GRID")
        result.append((x, y))
    return tuple(result)


def validate_point40_xy(xy, raw_bounds):
    """Check a supplied signed source-unit Point40 inside its local header."""
    x, y = _integers(xy, 2, "POINT40_XY")
    bounds = _integers(raw_bounds, 4, "BOUNDS")
    if any(not I32_MIN <= v <= I32_MAX for v in (x, y) + bounds):
        raise ValueError("POINT40_OR_BOUNDS_I32_RANGE")
    x0, y0, x1, y1 = bounds
    if not (x0 < x1 and y0 < y1 and x0 <= x <= x1 and y0 <= y <= y1):
        raise ValueError("POINT40_OUTSIDE_STRICT_LOCAL_BOUNDS")
    return (x, y)
