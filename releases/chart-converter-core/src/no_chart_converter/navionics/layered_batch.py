"""Own typed, attribute-free Plain layers and source-bound DepthPoint44.

The native dictionary/word contract is distinct from native acceptance of an
authored file. No geographic conversion, datum, portrayal or loader admission
is inferred. With no explicit native range, legacy plans/bytes remain unchanged.
"""
from dataclasses import dataclass
import hashlib
import struct

from .batch import (
    Point40, Line32, Area24, _integer, _pair, _envelope,
    plan_payload_width, plan_sha256, NativeLevelRange, _validate_range,
    _packed_range, _range_plan, _range_from_json,
)
from .metadata import encode_plain_metadata_record
from .raw_grid_bounds import validate_point40_xy, validate_raw_grid_bounds, validate_u16_xy
from .writer import RawMapHeader, encode_map_header


@dataclass(frozen=True)
class DepthPoint44:
    """SOUNDG class136 in layer140, with the proven meter-unit tag bits zero.

    Mantissa is signed14; scales0/1/2/3 mean /10, *1, *10, *100. No vertical
    datum, invalid-value convention, native execution or drawn text is claimed.
    Values are deliberately explicit codec inputs rather than a rounded float.
    """
    source_id: str
    xy: tuple[int, int]
    mantissa: int
    decimal_scale: int = 0
    native_level_range: NativeLevelRange | None = None

    def __post_init__(self):
        if type(self.source_id) is not str or not 1 <= len(self.source_id) <= 128:
            raise ValueError('source_id: nonempty string of at most 128 characters')
        object.__setattr__(self, 'xy', _pair(self.xy, -(1 << 31), (1 << 31) - 1, 'depth point xy'))
        _integer(self.mantissa, -8192, 8191, 'signed14 depth mantissa')
        _integer(self.decimal_scale, 0, 3, 'depth decimal scale')
        _validate_range(self.native_level_range)

    @property
    def class_code(self):
        return 136

    @property
    def depth_word(self):
        return (self.decimal_scale << 14) | (self.mantissa & 0x3fff)

    @property
    def expected_depth_f32(self):
        value = self.mantissa / 10 if self.decimal_scale == 0 else (
            self.mantissa * (1, 10, 100)[self.decimal_scale - 1])
        return struct.unpack('<f', struct.pack('<f', value))[0]


@dataclass(frozen=True)
class PlainLayer:
    layer_id: int
    features: tuple[Point40 | DepthPoint44 | Line32 | Area24, ...]
    layer_range: tuple[int, int] = (0, 100000)

    def __post_init__(self):
        _integer(self.layer_id, 0, 255, 'layer_id')
        object.__setattr__(self, 'layer_range', _pair(
            self.layer_range, -(1 << 31), (1 << 31) - 1, 'layer_range'))
        if not isinstance(self.features, (tuple, list)) or not self.features:
            raise ValueError('nonempty finite layer features required')
        for feature in self.features:
            if type(feature) not in (Point40, DepthPoint44, Line32, Area24):
                raise ValueError('typed attribute-free feature required')
            role = 0 if type(feature) in (Point40, DepthPoint44) else (
                1 if type(feature) is Line32 else 2)
            if self.layer_id & 3 != role:
                raise ValueError('layer low2 type must match point/line/area feature')
            if type(feature) is DepthPoint44 and self.layer_id != 140:
                raise ValueError('DepthPoint44 requires source-bound SOUNDG layer140')
        object.__setattr__(self, 'features', tuple(self.features))


@dataclass(frozen=True)
class LayeredBatchSpec:
    header: RawMapHeader
    layers: tuple[PlainLayer, ...]
    date_text_1: str = '20261017'
    date_text_2: str = '20261017'

    def __post_init__(self):
        if type(self.header) is not RawMapHeader or self.header.trailing_value != 0:
            raise ValueError('header requires a zero cursor placeholder; writer computes it')
        if not isinstance(self.layers, (tuple, list)) or not self.layers or any(
                type(layer) is not PlainLayer for layer in self.layers):
            raise ValueError('nonempty finite PlainLayer sequence required')
        object.__setattr__(self, 'layers', tuple(self.layers))
        if len({layer.layer_id for layer in self.layers}) != len(self.layers):
            raise ValueError('unique layer IDs are an own input rule')
        features = tuple(f for layer in self.layers for f in layer.features)
        if len({f.source_id for f in features}) != len(features):
            raise ValueError('source_id must be unique across all layers')
        validate_raw_grid_bounds(self.header.raw_bounds, self.header.raw_grid)
        for feature in features:
            if type(feature) in (Point40, DepthPoint44):
                validate_point40_xy(feature.xy, self.header.raw_bounds)
            else:
                for part in feature.parts:
                    validate_u16_xy(part, self.header.raw_grid)
        encode_plain_metadata_record(declared_file_bytes=258, raw_bounds=self.header.raw_bounds,
            date_text_1=self.date_text_1, date_text_2=self.date_text_2)


def plan_layered_batch(spec: LayeredBatchSpec, *, max_output_bytes=4096):
    """Two-pass contiguous offset plan; all new profiles remain unqualified."""
    if type(spec) is not LayeredBatchSpec:
        raise TypeError('spec must be LayeredBatchSpec')
    _integer(max_output_bytes, 1, 64 * 1024 * 1024, 'own output budget')
    rows, bank_payload = [], 24
    for ordinal, layer in enumerate(spec.layers):
        for feature in layer.features:
            point = type(feature) in (Point40, DepthPoint44)
            kind = 44 if type(feature) is DepthPoint44 else (
                40 if point else 32 if type(feature) is Line32 else 24)
            parts, bbox = [], None
            if not point:
                for i, part in enumerate(feature.parts):
                    digest = hashlib.sha256()
                    for xy in part:
                        digest.update(struct.pack('<HH', *xy))
                    parts.append(dict(ordinal=i, streamBytes=5 * len(part), pointCount=len(part),
                                      rawU16PointSHA256=digest.hexdigest()))
                first = feature.parts[0][0]
                bbox = [first[0], first[1], first[0], first[1]]
                for part in feature.parts:
                    for x, y in part:
                        bbox = [min(bbox[0], x), min(bbox[1], y),
                                max(bbox[2], x), max(bbox[3], y)]
                bank_payload += sum(part['streamBytes'] for part in parts)
            count = len(parts)
            fixed = (12 if kind == 44 else 10) if point else 10 + 4 * count + (kind == 24)
            size = plan_payload_width(fixed, sized_fields=(0,) if point else (0, count))
            row = dict(sourceId=feature.source_id, layerOrdinal=ordinal, layerId=layer.layer_id,
                kind=kind, classCode=feature.class_code, attributeBytes=0,
                partCount=count, bboxU16=bbox, parts=parts,
                rawPointXY=list(feature.xy) if point else None, **size)
            row['recordBytes'] = 2 + row['lengthWidth'] + row['payloadBytes']
            if kind == 44:
                row.update(depthMantissa=feature.mantissa, depthDecimalScale=feature.decimal_scale,
                    depthWord=feature.depth_word,
                    sourcePredictedDepthF32LEHex=struct.pack('<f', feature.expected_depth_f32).hex(),
                    nativePrivateDepthUnitSourcePrediction=2)
            native_range = _range_plan(feature)
            if native_range is not None:
                row['nativeLevelRange'] = native_range
            rows.append(row)
    has_bank = any(row['parts'] for row in rows)
    bank_width = plan_payload_width(bank_payload)['lengthWidth'] if has_bank else None
    bank_at = 320 if has_bank else None
    layer_at = 320 + (2 + bank_width + bank_payload if has_bank else 0)
    layer_end = layer_at + 22 * len(spec.layers)
    cursor = layer_end + 7
    stream_cursor = bank_at + 2 + bank_width + 24 if has_bank else None
    layers, feature_index = [], 0
    for ordinal, layer in enumerate(spec.layers):
        layer_row = dict(ordinal=ordinal, layerId=layer.layer_id,
            recordOffset=layer_at + 22 * ordinal, layerRange=list(layer.layer_range),
            firstFeatureOffset=cursor, featureCount=len(layer.features),
            depthUnitTagBits=0, preservedOpaqueWord=0)
        for _ in layer.features:
            row = rows[feature_index]
            row['layerOffset'] = layer_row['recordOffset']
            row['recordOffset'] = cursor
            cursor += row['recordBytes']
            for part in row['parts']:
                _integer(stream_cursor, 0, (1 << 30) - 1, 'direct stream offset low30')
                part['streamOffset'] = stream_cursor
                stream_cursor += part['streamBytes']
            feature_index += 1
        layer_row['featureEndOffset'] = cursor
        cursor += 7
        layers.append(layer_row)
    for value in (265, layer_at, layer_end, cursor, *(r['recordOffset'] for r in rows)):
        _integer(value, 0, 0xffffffff, 'planned offset/extent U32')
    if cursor > max_output_bytes:
        raise ValueError('OUTPUT_BUDGET_EXCEEDED: layered extent exceeds explicit own budget')
    validation = validate_raw_grid_bounds(spec.header.raw_bounds, spec.header.raw_grid,
        bank_bounds=spec.header.raw_bounds if has_bank else None,
        bank_grid=spec.header.raw_grid if has_bank else None)
    plan = dict(format='no5-own-plain-layered-feature-batch-plan/1',
        totalBytes=cursor, outputBudgetBytes=max_output_bytes, headerOffset=265,
        bankOffset=bank_at, bankLengthWidth=bank_width,
        bankPayloadBytes=bank_payload if has_bank else 0,
        firstLayerOffset=layer_at, layerEndOffset=layer_end, layers=layers, features=rows,
        rawHeader=dict(flags=spec.header.flags, rawBounds=list(spec.header.raw_bounds),
                       rawGrid=list(spec.header.raw_grid), rawLevels=list(spec.header.raw_levels)),
        dateText1=spec.date_text_1, dateText2=spec.date_text_2, rawInputValidation=validation,
        nativeAcceptance='UNTESTED_LAYERED_AND_DEPTH44_GENERALIZATION',
        physicalPacking='SOURCE_BOUND_CONTIGUOUS_PLAIN_GENERIC_NATIVE_UNQUALIFIED',
        semanticAttributes='UNSUPPORTED_EMPTY_ONLY',
        depthQualification='SOURCE_BOUND_LAYER140_ZERO_TAG_UNITS_NOT_NATIVE_EXECUTED',
        geographicDatumPortrayal='UNQUALIFIED', sourceIds='CALLER_LEDGER_LABELS_NOT_NATIVE_UIDS')
    if max_output_bytes > 8192:
        plan['scaledSoftwareProfile'] = dict(maxFileBytes=64 * 1024 * 1024,
            physicalLayout='SOURCE_BOUND_CONTIGUOUS_ABSOLUTE_OFFSETS',
            nativeQualification='PENDING_SCALED_NATIVE_READBACK',
            budgetIsFormatLimit=False)
    return plan


def layered_batch_from_spec(value):
    """Strict additive JSON schema, separate from the unchanged legacy schema."""
    if type(value) is not dict or set(value) != {
            'format', 'header', 'layers', 'date_text_1', 'date_text_2'} or value['format'] != (
            'no5-own-plain-layered-feature-batch/1'):
        raise ValueError('expected exact own Plain layered FeatureBatch spec')
    header = value['header']
    if type(header) is not dict or set(header) != set(RawMapHeader.__dataclass_fields__):
        raise ValueError('exact raw header fields required')
    if type(value['layers']) is not list:
        raise ValueError('layers must be a finite JSON list')
    layers = []
    for layer in value['layers']:
        if type(layer) is not dict or set(layer) != {'layer_id', 'layer_range', 'features'}:
            raise ValueError('exact layer fields required; no units/opaque-word options')
        if type(layer['features']) is not list:
            raise ValueError('features must be a finite JSON list')
        features = []
        for row in layer['features']:
            if type(row) is not dict or type(row.get('kind')) is not int:
                raise ValueError('typed feature row required')
            if row['kind'] == 44:
                if set(row) - {'native_level_range'} != {'kind', 'source_id', 'xy', 'mantissa', 'decimal_scale'}:
                    raise ValueError('exact DepthPoint44 fields required; class136 is fixed')
                features.append(DepthPoint44(row['source_id'], row['xy'], row['mantissa'], row['decimal_scale'],
                                              _range_from_json(row.get('native_level_range'))))
            else:
                cls = {40: Point40, 32: Line32, 24: Area24}.get(row['kind'])
                key = 'xy' if row['kind'] == 40 else 'parts'
                if cls is None or set(row) - {'native_level_range'} != {'kind', 'source_id', 'class_code', key}:
                    raise ValueError('unsupported feature fields/attributes/flags')
                features.append(cls(row['source_id'], row['class_code'], row[key],
                                    _range_from_json(row.get('native_level_range'))))
        layers.append(PlainLayer(layer['layer_id'], features, layer['layer_range']))
    return LayeredBatchSpec(RawMapHeader(**header), layers, value['date_text_1'], value['date_text_2'])


def _chunks(spec, plan):
    header = spec.header
    yield encode_plain_metadata_record(declared_file_bytes=plan['totalBytes'],
        raw_bounds=header.raw_bounds, date_text_1=spec.date_text_1, date_text_2=spec.date_text_2)
    yield _envelope(4, 4, 1) + struct.pack('<I', 265)
    yield encode_map_header(RawMapHeader(header.flags, header.raw_bounds, header.raw_grid,
                                        header.raw_levels, plan['firstLayerOffset']))
    if plan['bankOffset'] is not None:
        yield _envelope(52, plan['bankPayloadBytes'], plan['bankLengthWidth'])
        yield struct.pack('<IIiiii', *header.raw_grid, *header.raw_bounds)
        for layer in spec.layers:
            for feature in layer.features:
                if type(feature) in (Point40, DepthPoint44):
                    continue
                for part in feature.parts:
                    yield struct.pack('<HH', *part[0])
                    for xy in part[1:]:
                        yield b'\x88' + struct.pack('<HH', *xy)
                    yield b'\0'
    for layer, row in zip(spec.layers, plan['layers']):
        # Tag bits8..10 stay zero. Body+7 word stays0 without reinterpreting it.
        yield _envelope(18, 19, 1) + struct.pack('<BHIIii', layer.layer_id, 0,
            row['firstFeatureOffset'], 0, *layer.layer_range)
    yield _envelope(0, 4, 1, False) + b'\xff' * 4
    index = 0
    for layer in spec.layers:
        for feature in layer.features:
            row = plan['features'][index]
            width = row['lengthWidth']
            yield _envelope(row['kind'], row['payloadBytes'], width)
            if type(feature) not in (Point40, DepthPoint44):
                yield struct.pack('<4H', *row['bboxU16'])
            yield bytes((feature.class_code, _packed_range(feature))) + bytes(width)
            if type(feature) is DepthPoint44:
                yield struct.pack('<Hii', feature.depth_word, *feature.xy)
            elif type(feature) is Point40:
                yield struct.pack('<ii', *feature.xy)
            else:
                yield row['partCount'].to_bytes(width, 'little')
                for part in row['parts']:
                    yield struct.pack('<I', part['streamOffset'])
                if type(feature) is Area24:
                    yield b'\0'
            index += 1
        yield _envelope(0, 4, 1) + b'\xff' * 4


def write_layered_batch(spec: LayeredBatchSpec, sink, *, expected_plan_sha256,
                        max_output_bytes=4096):
    """Fresh sink at byte0; positive short writes retry, errors leave partial IO.

    Plan and budget are rebound before the first write. No seek/flush/close,
    original execution, authentication or rollback is performed by this API.
    """
    plan = plan_layered_batch(spec, max_output_bytes=max_output_bytes)
    digest = plan_sha256(plan)
    if type(expected_plan_sha256) is not str or expected_plan_sha256 != digest:
        raise ValueError('EXACT_EXPECTED_PLAN_SHA256_REQUIRED')
    if not callable(getattr(sink, 'write', None)):
        raise TypeError('sink must provide write(bytes)->int')
    sha, written = hashlib.sha256(), 0
    for chunk in _chunks(spec, plan):
        at = 0
        while at < len(chunk):
            count = sink.write(chunk[at:])
            if type(count) is not int or not 0 < count <= len(chunk) - at:
                raise OSError('SINK_WRITE_DID_NOT_MAKE_VALID_PROGRESS')
            sha.update(chunk[at:at + count])
            at += count
            written += count
    if written != plan['totalBytes']:
        raise RuntimeError('emission extent differs from plan')
    return dict(format='no5-own-plain-layered-feature-batch-emission/1', bytesWritten=written,
        emittedStreamSHA256=sha.hexdigest(), planSHA256=digest, plan=plan,
        nativeReaderExecuted=False, persistedFileReadback=False)
