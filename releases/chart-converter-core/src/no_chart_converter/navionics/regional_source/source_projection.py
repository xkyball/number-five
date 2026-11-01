"""Pure own retained-IR authoring closure; extracted functions remain source-exact.
No decoder, filesystem, CLI, network, Native or Original execution entry point.
"""
from fractions import Fraction
import hashlib
import json
import math
import struct
from no_chart_converter.navionics.layered_batch import layered_batch_from_spec, plan_layered_batch
from no_chart_converter.navionics.batch import plan_sha256
from no_chart_converter.navionics.raw_grid_bounds import validate_raw_grid_bounds, validate_point40_xy
from no_chart_converter.navionics.writer import RawMapHeader

INPUT_FORMAT = 'no5-own-native-degree-coast-soundings/1'

A, K, D, H = 6378388.0, 1.0067642927, 57.29577951308, 1.570796327

def _f32(value):
    return struct.unpack('<f', struct.pack('<f', value))[0]

def _number(value, label):
    if type(value) not in (int, float):
        raise ValueError(label + ': finite number required, no bool')
    try:
        number = float(value)
    except OverflowError as error:
        raise ValueError(label + ': finite number required') from error
    if not math.isfinite(number):
        raise ValueError(label + ': finite number required')
    return number

def forward_model(latitude_longitude):
    """Source-bound F64 formula -> F32 -> I32 truncation; host libm only."""
    if type(latitude_longitude) is not list or len(latitude_longitude) != 2:
        raise ValueError('latitude_longitude: two-element JSON list required')
    lat, lon = (_number(v, 'degree input') for v in latitude_longitude)
    if abs(lat) > 83 or abs(lon) > 180:
        raise ValueError('degree input outside selected native forward domain')
    x = lon * A / D
    y = math.log(math.tan((math.atan(math.tan(lat / D) / K) + H) * .5)) * A
    floats = [_f32(x), _f32(y)]
    integers = [math.trunc(v) for v in floats]
    return dict(inputLatitudeLongitude=[lat, lon], formulaF64XY=[x, y],
        sourceF32XY=floats, sourceF32LEHex=[struct.pack('<f', v).hex() for v in floats],
        sourceI32XY=integers, f32MinusFormulaF64=[floats[0]-x, floats[1]-y],
        i32MinusF32=[integers[i]-floats[i] for i in range(2)])

def _exact_keys(value, keys, label):
    if type(value) is not dict or set(value) != set(keys):
        raise ValueError(label + ': exact fields required')

def _json_bytes(value):
    return (json.dumps(value, indent=2, sort_keys=True, allow_nan=False) + '\n').encode()

def _nearest_u16(source, bounds, grid):
    exact = [Fraction((source[i]-bounds[i])*(grid[i]-1),
                      bounds[i+2]-bounds[i]) for i in range(2)]
    return [math.floor(v+Fraction(1, 2)) for v in exact]

def adapt_geographic_input(value, *, max_output_bytes=4096):
    """Return (existing raw JSON spec, model report); no binary or native IO.

    Square bounds and equal grids are an explicit conservative adapter rule.
    The error budget bounds only predicted vector metric-I32 minus forward-I32
    per axis. It is not geographic accuracy or native libm equivalence.
    """
    _exact_keys(value, ('format', 'header', 'coastlines', 'soundings',
                       'date_text_1', 'date_text_2', 'max_metric_error_source_units'), 'input')
    if value['format'] != INPUT_FORMAT:
        raise ValueError('own native-degree input format required')
    _exact_keys(value['header'], RawMapHeader.__dataclass_fields__, 'header')
    header = RawMapHeader(**value['header'])
    validate_raw_grid_bounds(header.raw_bounds, header.raw_grid)
    x0, y0, x1, y1 = header.raw_bounds
    if x1-x0 != y1-y0 or header.raw_grid[0] != header.raw_grid[1]:
        raise ValueError('adapter requires square bounds and equal grids')
    budget = _number(value['max_metric_error_source_units'], 'metric error budget')
    if not 0 <= budget <= 0x7fffffff:
        raise ValueError('nonnegative bounded metric error budget required')
    if type(value['coastlines']) is not list or type(value['soundings']) is not list:
        raise ValueError('finite coastline/sounding JSON lists required')
    bounds, grid = header.raw_bounds, header.raw_grid
    scales = [_f32((bounds[i+2]-bounds[i]) / ((grid[i]-1) << 14)) for i in range(2)]
    lines, points, ledger = [], [], []
    for feature in value['coastlines']:
        _exact_keys(feature, ('source_id', 'parts'), 'coastline')
        if type(feature['parts']) is not list:
            raise ValueError('coastline parts must be JSON list')
        raw_parts = []
        for part_ordinal, part in enumerate(feature['parts']):
            if type(part) is not list:
                raise ValueError('part must be JSON list')
            raw_part = []
            for ordinal, degrees in enumerate(part):
                row = forward_model(degrees)
                source = validate_point40_xy(row['sourceI32XY'], bounds)
                raw = _nearest_u16(source, bounds, grid)
                # Match selected source order: F32 scale -> F64, product,
                # origin add, positive-local-pixel +.5, I32 truncation.
                metric = [math.trunc(bounds[i] + (raw[i] << 14)*scales[i] + .5)
                          for i in range(2)]
                delta = [metric[i]-source[i] for i in range(2)]
                if any(abs(v) > budget for v in delta):
                    raise ValueError('PREDICTED_METRIC_ERROR_BUDGET_EXCEEDED')
                if raw_part and raw == raw_part[-1]:
                    raise ValueError('ADJACENT_LINE_VERTICES_COLLAPSE_ON_LOCAL_GRID')
                raw_part.append(raw)
                row.update(sourceId=feature['source_id'], partOrdinal=part_ordinal,
                    vertexOrdinal=ordinal, rawU16XY=raw, predictedMetricI32XY=metric,
                    predictedMetricMinusSourceI32=delta)
                ledger.append(row)
            raw_parts.append(raw_part)
        lines.append(dict(kind=32, class_code=29, source_id=feature['source_id'], parts=raw_parts))
    for feature in value['soundings']:
        _exact_keys(feature, ('source_id', 'latitude_longitude', 'mantissa', 'decimal_scale'), 'sounding')
        row = forward_model(feature['latitude_longitude'])
        validate_point40_xy(row['sourceI32XY'], bounds)
        points.append(dict(kind=44, source_id=feature['source_id'], xy=row['sourceI32XY'],
                           mantissa=feature['mantissa'], decimal_scale=feature['decimal_scale']))
        row.update(sourceId=feature['source_id'], role='absolute-depth-point')
        ledger.append(row)
    layers = [dict(layer_id=code, layer_range=[0, 100000], features=features)
              for code, features in ((61, lines), (140, points)) if features]
    raw_spec = dict(format='no5-own-plain-layered-feature-batch/1', header=value['header'],
        layers=layers, date_text_1=value['date_text_1'], date_text_2=value['date_text_2'])
    # All existing ID, depth-word, geometry, bounds and byte-budget validation
    # stays in the public writer. No second serializer or native format path.
    plan = plan_layered_batch(layered_batch_from_spec(raw_spec), max_output_bytes=max_output_bytes)
    report = dict(format='no5-own-geographic-adapter-report/1',
        rawSpecSHA256=hashlib.sha256(_json_bytes(raw_spec)).hexdigest(),
        writerPlanSHA256=plan_sha256(plan), plannedBinaryBytes=plan['totalBytes'],
        outputBudgetBytes=max_output_bytes, metricErrorBudgetSourceUnits=budget,
        scaleF32LEHex=[struct.pack('<f', v).hex() for v in scales], vertices=ledger,
        depthEncoding='EXPLICIT_SIGNED14_MANTISSA_AND_SCALE_NO_FLOAT_DEPTH_ROUNDING',
        rounding='F32_FORWARD_THEN_I32_TRUNC; LOCAL_EXACT_FRACTION_NEAREST_HALF_UP; METRIC_F64_POSITIVE_PIXEL_BIAS',
        sourceQualification='HOST_LIBM_SOURCE_MODEL_SOFTWARE_ONLY',
        nativeReaderExecuted=False, nativeForwardExecuted=False,
        normalCollectionAdmission='UNPROVEN_NOT_SOLVED_BY_WRITER_FIELDS',
        datumAccuracyRendering='UNQUALIFIED')
    return raw_spec, report
