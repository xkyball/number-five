"""Own raw Plain FeatureBatch planning and sequential emission.

Only the first logical block is qualified by this software interface. This is
a wire generalization of finite own Point40/Line32/Area24 inputs, with no new
native acceptance, attribute semantics, geographic or rendering guarantee.
An explicit larger budget is source-bound; finite native history is separate.
"""
from dataclasses import dataclass
import hashlib
import json
import struct

from .metadata import encode_plain_metadata_record
from .raw_grid_bounds import validate_point40_xy, validate_raw_grid_bounds, validate_u16_xy
from .writer import RawMapHeader, encode_map_header


MAX_SINGLE_BLOCK_BYTES = 4096  # Compatible default, NOT a format size limit.
MAX_CROSSPAGE_PROOF_BYTES = 8192  # Own bounded source-only proof budget.


def _integer(value, low, high, name):
    if type(value) is not int or not low <= value <= high:
        raise ValueError(f'{name}: integer required in {low}..{high}')
    return value


def _pair(value, low, high, name):
    if not isinstance(value, (tuple, list)) or len(value) != 2:
        raise ValueError(f'{name}: two coordinates required')
    return tuple(_integer(v, low, high, name) for v in value)


def _identity(obj):
    if type(obj.source_id) is not str or not 1 <= len(obj.source_id) <= 128:
        raise ValueError('source_id: nonempty string of at most 128 characters')
    _integer(obj.class_code, 0, 255, 'class_code')
    _validate_range(obj.native_level_range)


@dataclass(frozen=True)
class NativeLevelRange:
    """Explicit native level nibbles, unrelated to source scale denominators.

    Source-bound flags7 filtering uses these nibbles; flags4 readbacks bypass
    the filter. Covering0..15 is not a portrayal or all-zoom qualification.
    """
    min_level: int
    max_level: int

    def __post_init__(self):
        _integer(self.min_level, 0, 15, 'minimum native level')
        _integer(self.max_level, 0, 15, 'maximum native level')
        if self.min_level > self.max_level:
            raise ValueError('minimum native level exceeds maximum')

    @property
    def packed_byte(self):
        return (self.min_level << 4) | self.max_level


def _validate_range(value):
    if value is not None:
        if type(value) is not NativeLevelRange:
            raise ValueError('native_level_range requires NativeLevelRange or None')
        value.__post_init__()


def _packed_range(feature):
    value = feature.native_level_range
    _validate_range(value)
    return 0 if value is None else value.packed_byte


def _range_plan(feature):
    value = feature.native_level_range
    _validate_range(value)
    if value is None:
        return None
    return dict(minLevel=value.min_level, maxLevel=value.max_level,
                packedByte=value.packed_byte)


def _range_from_json(value):
    if value is None:
        return None
    if type(value) is not dict or set(value) != {'min_level', 'max_level'}:
        raise ValueError('exact native level range fields required')
    return NativeLevelRange(value['min_level'], value['max_level'])


@dataclass(frozen=True)
class Point40:
    source_id: str
    class_code: int
    xy: tuple[int, int]
    native_level_range: NativeLevelRange | None = None

    def __post_init__(self):
        _identity(self)
        object.__setattr__(self, 'xy', _pair(self.xy, -(1 << 31), (1 << 31) - 1, 'point xy'))


def _parts(value, area):
    if not isinstance(value, (tuple, list)) or not value:
        raise ValueError('at least one vector part is required')
    result = []
    for part in value:
        if not isinstance(part, (tuple, list)) or len(part) < (4 if area else 2):
            raise ValueError('insufficient stored points in part')
        points = tuple(_pair(p, 0, 65535, 'raw U16 xy') for p in part)
        if area and (points[0] != points[-1] or len(set(points[:-1])) < 3):
            raise ValueError('Area24 requires a closed part with three distinct vertices')
        result.append(points)
    return tuple(result)


@dataclass(frozen=True)
class Line32:
    source_id: str
    class_code: int
    parts: tuple[tuple[tuple[int, int], ...], ...]
    native_level_range: NativeLevelRange | None = None

    def __post_init__(self):
        _identity(self)
        object.__setattr__(self, 'parts', _parts(self.parts, False))


@dataclass(frozen=True)
class Area24:
    source_id: str
    class_code: int
    parts: tuple[tuple[tuple[int, int], ...], ...]
    native_level_range: NativeLevelRange | None = None

    def __post_init__(self):
        _identity(self)
        # Closure is an own input rule, not an inferred hole/orientation role.
        object.__setattr__(self, 'parts', _parts(self.parts, True))


@dataclass(frozen=True)
class BatchSpec:
    header: RawMapHeader
    features: tuple[Point40 | Line32 | Area24, ...]
    layer_id: int = 60
    layer_range: tuple[int, int] = (0, 100000)
    date_text_1: str = '20261017'
    date_text_2: str = '20261017'

    def __post_init__(self):
        if type(self.header) is not RawMapHeader or self.header.trailing_value != 0:
            raise ValueError('header requires a zero cursor placeholder; writer computes it')
        if not isinstance(self.features, (tuple, list)) or not self.features:
            raise ValueError('nonempty finite features required')
        if any(type(f) not in (Point40, Line32, Area24) for f in self.features):
            raise ValueError('only typed Point40/Line32/Area24, without attributes or flags')
        object.__setattr__(self, 'features', tuple(self.features))
        if len({f.source_id for f in self.features}) != len(self.features):
            raise ValueError('source_id must be unique within the batch ledger')
        _integer(self.layer_id, 0, 255, 'layer_id')
        object.__setattr__(self, 'layer_range', _pair(
            self.layer_range, -(1 << 31), (1 << 31) - 1, 'layer_range'))
        validate_raw_grid_bounds(self.header.raw_bounds, self.header.raw_grid)
        for f in self.features:
            if type(f) is Point40:
                validate_point40_xy(f.xy, self.header.raw_bounds)
            else:
                for part in f.parts:
                    validate_u16_xy(part, self.header.raw_grid)
        # Calendar validation uses the existing own encoder, before any output.
        encode_plain_metadata_record(declared_file_bytes=258, raw_bounds=self.header.raw_bounds,
            date_text_1=self.date_text_1, date_text_2=self.date_text_2)


def plan_payload_width(base_bytes: int, *, sized_fields=()) -> dict:
    """Choose a stable shared envelope/field width, with at most three trials.

    ``base_bytes`` includes all fixed-size fields and payload data. Each value
    in ``sized_fields`` adds one W-byte field. Every complete L(W) and field
    value must fit W. This scalar arithmetic helper does not qualify a large
    physical file or authorize nonempty semantic attributes.
    """
    _integer(base_bytes, 0, 0xffffffff, 'base_bytes')
    if not isinstance(sized_fields, (tuple, list)):
        raise ValueError('sized_fields requires a finite tuple/list')
    for value in sized_fields:
        _integer(value, 0, 0xffffffff, 'sized field')
    for width in (1, 2, 4):
        payload = base_bytes + len(sized_fields) * width
        limit = 1 << (8 * width)
        if payload < limit and all(value < limit for value in sized_fields):
            return {'lengthWidth': width, 'payloadBytes': payload}
    raise ValueError('complete payload does not fit the U32 envelope')


def _envelope(kind, payload_bytes, width, selected=True):
    _integer(kind, 0, 63, 'kind')
    _integer(payload_bytes, 0, 0xffffffff, 'payload bytes')
    if width not in (1, 2, 4) or payload_bytes >= 1 << (width * 8):
        raise ValueError('envelope width mismatch')
    tag = kind | {1: 0, 2: 0x40, 4: 0x80}[width] | (0x8000 if selected else 0)
    return struct.pack('<H', tag) + payload_bytes.to_bytes(width, 'little')


def plan_plain_batch(spec: BatchSpec, *, max_output_bytes: int = MAX_SINGLE_BLOCK_BYTES) -> dict:
    """Plan lengths and absolute contiguous offsets without materializing streams.

    Default 4096 retains the 0.2.21 plan, including its digest. Nondefault
    budgets are explicit plan fields. Budgets above 4096 permit only a bounded
    source-bound crosspage proof, without general native qualification.
    """
    if type(spec) is not BatchSpec:
        raise TypeError('spec must be BatchSpec')
    _integer(max_output_bytes, 1, MAX_CROSSPAGE_PROOF_BYTES, 'own output budget')
    rows, bank_payload = [], 24
    for f in spec.features:
        point = type(f) is Point40
        kind = 40 if point else 32 if type(f) is Line32 else 24
        parts = []
        if not point:
            for i, part in enumerate(f.parts):
                sha = hashlib.sha256()
                for xy in part:
                    sha.update(struct.pack('<HH', *xy))
                parts.append(dict(ordinal=i, streamBytes=5 * len(part), pointCount=len(part),
                                  rawU16PointSHA256=sha.hexdigest()))
        count = len(parts)
        size = plan_payload_width(10 if point else 10 + 4 * count + (kind == 24),
                                  sized_fields=(0,) if point else (0, count))
        width, payload = size['lengthWidth'], size['payloadBytes']
        bbox = None
        if not point:
            first = f.parts[0][0]
            bbox = [first[0], first[1], first[0], first[1]]
            for part in f.parts:
                for x, y in part:
                    bbox = [min(bbox[0], x), min(bbox[1], y),
                            max(bbox[2], x), max(bbox[3], y)]
            bank_payload += sum(part['streamBytes'] for part in parts)
        rows.append(dict(sourceId=f.source_id, kind=kind, classCode=f.class_code,
            lengthWidth=width, payloadBytes=payload, recordBytes=2 + width + payload,
            attributeBytes=0, partCount=count, bboxU16=bbox, parts=parts,
            rawPointXY=list(f.xy) if point else None))
        native_range = _range_plan(f)
        if native_range is not None:
            rows[-1]['nativeLevelRange'] = native_range
    has_bank = any(row['parts'] for row in rows)
    bank_width = plan_payload_width(bank_payload)['lengthWidth'] if has_bank else None
    header_at = 258 + 7
    bank_at = header_at + 55 if has_bank else None
    layer_at = header_at + 55 + (2 + bank_width + bank_payload if has_bank else 0)
    first_at = layer_at + 22 + 7
    cursor = first_at
    stream_cursor = bank_at + 2 + bank_width + 24 if has_bank else None
    for row in rows:
        row['recordOffset'] = cursor
        _integer(cursor, 0, 0xffffffff, 'feature offset U32')
        cursor += row['recordBytes']
        for part in row['parts']:
            _integer(stream_cursor, 0, (1 << 30) - 1, 'direct part stream offset low30')
            part['streamOffset'] = stream_cursor
            stream_cursor += part['streamBytes']
    total = cursor + 7
    for value in (header_at, layer_at, first_at, cursor, total):
        _integer(value, 0, 0xffffffff, 'planned offset/extent U32')
    if total > max_output_bytes:
        if max_output_bytes == MAX_SINGLE_BLOCK_BYTES:
            raise ValueError('UNQUALIFIED_PHYSICAL_PACKING: total extent exceeds first 4096-byte logical block')
        raise ValueError('OUTPUT_BUDGET_EXCEEDED: total extent exceeds explicit own proof budget')
    arithmetic = validate_raw_grid_bounds(spec.header.raw_bounds, spec.header.raw_grid,
        bank_bounds=spec.header.raw_bounds if has_bank else None,
        bank_grid=spec.header.raw_grid if has_bank else None)
    plan = dict(format='no5-own-plain-feature-batch-plan/1', totalBytes=total,
        headerOffset=header_at, bankOffset=bank_at, bankLengthWidth=bank_width,
        bankPayloadBytes=bank_payload if has_bank else 0, layerOffset=layer_at,
        layerEndOffset=layer_at + 22, firstFeatureOffset=first_at, featureEndOffset=cursor,
        rawHeader=dict(flags=spec.header.flags, rawBounds=list(spec.header.raw_bounds),
            rawGrid=list(spec.header.raw_grid), rawLevels=list(spec.header.raw_levels)),
        layerId=spec.layer_id, layerRange=list(spec.layer_range),
        rawInputValidation=arithmetic,
        dateText1=spec.date_text_1, dateText2=spec.date_text_2, features=rows,
        nativeAcceptance='UNTESTED_GENERALIZATION', physicalPacking='FIRST_LOGICAL_BLOCK_ONLY',
        semanticAttributes='UNSUPPORTED_EMPTY_ONLY', worldScaleCRSPortrayal='UNQUALIFIED',
        sourceIds='CALLER_LEDGER_LABELS_NOT_NATIVE_UIDS')
    if max_output_bytes != MAX_SINGLE_BLOCK_BYTES:
        plan['outputBudgetBytes'] = max_output_bytes
        if max_output_bytes > MAX_SINGLE_BLOCK_BYTES:
            plan['physicalPacking'] = 'SOURCE_BOUND_CONTIGUOUS_PLAIN_GENERIC_NATIVE_UNQUALIFIED'
    return plan


def plan_sha256(plan: dict) -> str:
    """Bind the complete JSON plan; the writer independently recomputes it."""
    return hashlib.sha256(json.dumps(plan, sort_keys=True, separators=(',', ':'),
                                    ensure_ascii=True, allow_nan=False).encode('ascii')).hexdigest()


def batch_from_spec(value: dict) -> BatchSpec:
    """Parse exact own JSON fields; unknown flags/attributes/options refuse."""
    if type(value) is not dict or set(value) != {
            'format', 'header', 'features', 'layer_id', 'layer_range',
            'date_text_1', 'date_text_2'} or value['format'] != 'no5-own-plain-feature-batch/1':
        raise ValueError('expected exact own Plain FeatureBatch spec')
    header = value['header']
    if type(header) is not dict or set(header) != set(RawMapHeader.__dataclass_fields__):
        raise ValueError('exact raw header fields required')
    if type(value['features']) is not list:
        raise ValueError('features must be a finite JSON list')
    features = []
    for row in value['features']:
        if type(row) is not dict or type(row.get('kind')) is not int:
            raise ValueError('typed feature row required')
        kind = row['kind']
        cls = {40: Point40, 32: Line32, 24: Area24}.get(kind)
        geometry_key = 'xy' if kind == 40 else 'parts'
        if cls is None or set(row) - {'native_level_range'} != {'kind', 'source_id', 'class_code', geometry_key}:
            raise ValueError('unsupported feature fields, flags, attributes or kind')
        features.append(cls(row['source_id'], row['class_code'], row[geometry_key],
                            _range_from_json(row.get('native_level_range'))))
    return BatchSpec(RawMapHeader(**header), tuple(features), value['layer_id'],
        value['layer_range'], value['date_text_1'], value['date_text_2'])


def _chunks(spec, plan):
    h = spec.header
    yield encode_plain_metadata_record(declared_file_bytes=plan['totalBytes'],
        raw_bounds=h.raw_bounds, date_text_1=spec.date_text_1, date_text_2=spec.date_text_2)
    yield _envelope(4, 4, 1) + struct.pack('<I', plan['headerOffset'])
    yield encode_map_header(RawMapHeader(h.flags, h.raw_bounds, h.raw_grid,
                                       h.raw_levels, plan['layerOffset']))
    if plan['bankOffset'] is not None:
        yield _envelope(52, plan['bankPayloadBytes'], plan['bankLengthWidth'])
        yield struct.pack('<IIiiii', *h.raw_grid, *h.raw_bounds)
        for f in spec.features:
            if type(f) is Point40:
                continue
            for part in f.parts:
                yield struct.pack('<HH', *part[0])
                for xy in part[1:]:
                    yield b'\x88' + struct.pack('<HH', *xy)
                yield b'\0'
    yield _envelope(18, 19, 1) + struct.pack('<BHIIii', spec.layer_id, 0,
        plan['firstFeatureOffset'], 0, *spec.layer_range)
    yield _envelope(0, 4, 1, False) + b'\xff' * 4
    for f, row in zip(spec.features, plan['features']):
        w = row['lengthWidth']
        yield _envelope(row['kind'], row['payloadBytes'], w)
        if type(f) is not Point40:
            yield struct.pack('<4H', *row['bboxU16'])
        yield bytes((f.class_code, _packed_range(f))) + bytes(w)  # Optional native range; AttrLen0.
        if type(f) is Point40:
            yield struct.pack('<ii', *f.xy)
        else:
            yield row['partCount'].to_bytes(w, 'little')
            for part in row['parts']:
                yield struct.pack('<I', part['streamOffset'])
            if type(f) is Area24:
                yield b'\0'  # Retained own Area24 tail, no universal semantics asserted.
    yield _envelope(0, 4, 1) + b'\xff' * 4


def write_plain_batch(spec: BatchSpec, sink, *, expected_plan_sha256: str,
                      max_output_bytes: int = MAX_SINGLE_BLOCK_BYTES) -> dict:
    """Pass two: emit sequentially to a caller-owned fresh sink at byte zero.

    Only ``write(bytes)->int`` is used. Positive short writes are retried; zero,
    None, bool and invalid counts fail. Exceptions propagate and can leave a
    partial sink; no success report is returned and no rollback is claimed.
    There is no seek, flush, close, native call or whole-file concatenation.
    The digest binds emitted bytes, not a later persisted file readback.
    """
    plan = plan_plain_batch(spec, max_output_bytes=max_output_bytes)
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
            written += count
            at += count
    if written != plan['totalBytes']:
        raise RuntimeError('emission extent differs from planned extent')
    return dict(format='no5-own-plain-feature-batch-emission/1', bytesWritten=written,
        emittedStreamSHA256=sha.hexdigest(), planSHA256=digest, plan=plan,
        nativeReaderExecuted=False, persistedFileReadback=False)
