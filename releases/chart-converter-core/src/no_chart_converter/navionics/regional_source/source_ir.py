"""Pure own retained-IR authoring closure; extracted functions remain source-exact.
No decoder, filesystem, CLI, network, Native or Original execution entry point.
"""
from fractions import Fraction
import math
import struct
from . import source_projection as geo
from no_chart_converter.navionics.layered_batch import DepthPoint44

MAX_FEATURES = 100000

MAX_SAMPLES = 100000

LINE_TARGETS = {'COALNE': (30, 29, 61), 'PONTON': (95, 105, 105)}

class Unsupported(ValueError):
    """A counted whole feature or sounding sample; never an invented feature."""

def quantize_depth_metres(value, *, max_error_metres):
    """Nearest representable signed14/scale word, with explicit F32 error cap.

    Compare both neighboring mantissas for every scale using predicted F32.
    Error ties prefer the finer scale, then the mantissa away from zero.
    Source numeric values are treated as exact binary floats, not decimal text.
    This does not establish source depth units, vertical datum or accuracy.
    """
    number = geo._number(value, 'depth')
    limit = geo._number(max_error_metres, 'depth error budget')
    if not 0 <= limit <= 1:
        raise ValueError('DEPTH_ERROR_BUDGET_MUST_BE_IN_0_TO_1_METRE')
    if not -819200 <= number <= 819100:
        raise Unsupported('DEPTH_OUTSIDE_SIGNED14_SCALE_WORD_DOMAIN')
    source = Fraction(number)
    candidates = []
    for scale, step in enumerate((Fraction(1, 10), Fraction(1), Fraction(10), Fraction(100))):
        ratio = source / step
        for mantissa in {math.floor(ratio), math.ceil(ratio)}:
            if -8192 <= mantissa <= 8191:
                codec = DepthPoint44('validation', [0, 0], mantissa, scale)
                predicted = codec.expected_depth_f32
                error = abs(Fraction(predicted)-source)
                candidates.append((error, scale, -abs(mantissa), mantissa, predicted, codec.depth_word))
    if not candidates:
        raise Unsupported('DEPTH_OUTSIDE_SIGNED14_SCALE_WORD_DOMAIN')
    error, scale, _, mantissa, predicted, word = min(candidates)
    if error > Fraction(limit):
        raise Unsupported('DEPTH_F32_QUANTIZATION_ERROR_BUDGET_EXCEEDED')
    return dict(mantissa=mantissa, decimal_scale=scale, depthWord=word,
        sourceValue=number, sourcePredictedDepthF32=predicted,
        sourcePredictedDepthF32LEHex=struct.pack('<f', predicted).hex(),
        absoluteErrorMetres=float(error),
        negativeZeroSignNotPreserved=number == 0 and math.copysign(1, number) < 0)

def _degrees(position):
    if type(position) is not list or len(position) != 2:
        raise Unsupported('IR_LONGITUDE_LATITUDE_PAIR_REQUIRED')
    # The regular decoder IR is lon/lat; the target model takes lat/lon.
    return [position[1], position[0]]

def _coordinates_count(feature):
    g = feature.get('geometry')
    if type(g) is not dict:
        raise ValueError('IR_FEATURE_GEOMETRY_REQUIRED')
    if g.get('kind') == 'line':
        parts = g.get('parts', [])
        if type(parts) is not list:
            raise ValueError('IR_LINE_PARTS_LIST_REQUIRED')
        return sum(len(p.get('coordinates', [])) if type(p) is dict and
                   type(p.get('coordinates')) is list else 0 for p in parts)
    if g.get('kind') == 'multipoint':
        points = g.get('points', [])
        if type(points) is not list:
            raise ValueError('IR_MULTIPOINT_LIST_REQUIRED')
        return len(points)
    return 0
