"""Opt-in finite native E/M attributes with physical presence-group layers.

No legacy emitter is changed. Native class/layer/attribute IDs are explicit
source-bound translations, not S57 arithmetic. A value's absence is a distinct
presence group; unknown fields or values refuse rather than becoming sentinels.
Only owned derived constants and invented tests belong in this module.
"""
from dataclasses import dataclass
from decimal import Decimal, InvalidOperation
import hashlib
import struct

from no_chart_converter.navionics.batch import (
    Line32, Area24, _integer, _pair, _envelope, plan_payload_width, plan_sha256,
    _packed_range, _range_plan, _range_from_json,
)
from no_chart_converter.navionics.metadata import encode_plain_metadata_record
from no_chart_converter.navionics.raw_grid_bounds import validate_raw_grid_bounds, validate_u16_xy
from no_chart_converter.navionics.writer import RawMapHeader, encode_map_header

ATTRIBUTE_CONTRACT_SHA256 = '5b605cad0518bfef87b64f24a39d6c9bec11d64db2545b61884b02fa5a1e43b0'
PRESENCE_CONTRACT_SHA256 = '22575447c0ece593e534e5d0a24bf9d86c902448523f35aae3f3fc2d8c0fa1bb'
DEPTH_AREA_CONTRACT_SHA256 = '6ad9decdf3f67b897d9a5652c9deffdb332236ef9c63c1df2960e0bcede4c55c'
M_CODES = frozenset((82, 83, 138))
# Paired source/native enum meanings, deliberately narrower than the wire U8.
ENUM_VALUES = {62: frozenset((1, 3, 4, 6, 8, 13)), 151: frozenset((2, 3, 4))}
POSITIONS = {42: {138: 1}, 131: {62: 1, 151: 22}, 41: {82: 1, 83: 2}, 46: {82: 1, 83: 2}}
TYPED_LAYERS = {(42, 32): 37, (131, 32): 137, (131, 24): 138, (41, 24): 38, (46, 24): 38}
MAX_TOOL_OUTPUT_BYTES = 64 * 1024 * 1024


def _f32(value):
    return struct.unpack('<f', struct.pack('<f', value))[0]


@dataclass(frozen=True)
class EnumE:
    native_code_u16: int
    enum_u8: int

    def __post_init__(self):
        _integer(self.native_code_u16, 0, 65535, 'native attribute code')
        if self.native_code_u16 not in ENUM_VALUES:
            raise ValueError('only source-bound CATSLC62/WATLEV151 E attributes')
        _integer(self.enum_u8, 0, 255, 'enum value')
        if self.enum_u8 not in ENUM_VALUES[self.native_code_u16]:
            raise ValueError('unpaired enum meaning; no sentinel or next-attribute lookup')

    @property
    def wire_bytes(self):
        return bytes((self.enum_u8,))

    @property
    def expected(self):
        return dict(native_code_u16=self.native_code_u16, storage='E', enum_u8=self.enum_u8)


@dataclass(frozen=True)
class ScaledM:
    native_code_u16: int
    value: Decimal | int
    declared_native_units: int

    def __post_init__(self):
        if type(self.native_code_u16) is not int or self.native_code_u16 not in M_CODES:
            raise ValueError('only source-bound VALDCO138/DRVAL1-82/DRVAL2-83 M attributes')
        if type(self.declared_native_units) is not int or self.declared_native_units != 2:
            raise ValueError('explicit declared native-unit2 premise required; no inferred datum/unit')
        if type(self.value) is int:
            value = Decimal(self.value)
        elif type(self.value) is Decimal:
            value = self.value
        else:
            raise ValueError('M value requires Decimal or integer; no float rounding')
        if not value.is_finite() or len(value.as_tuple().digits) > 64 or not -64 <= value.as_tuple().exponent <= 64:
            raise ValueError('finite bounded Decimal required by own scalar tool budget')
        numerator, denominator = value.as_integer_ratio()
        raw, residue = divmod(numerator * 10, denominator)
        if residue:
            raise ValueError('M value must be an exact native tenth; no rounding')
        _integer(raw, -(1 << 31), (1 << 31)-1, 'signed I32 M payload')
        object.__setattr__(self, 'value', value)

    @property
    def wire_i32(self):
        n, d = self.value.as_integer_ratio()
        return n * 10 // d

    @property
    def wire_bytes(self):
        return struct.pack('<i', self.wire_i32)

    @property
    def expected(self):
        return dict(native_code_u16=self.native_code_u16, storage='M', wire_i32=self.wire_i32, declared_native_units=2)

    @property
    def predicted_native_f32_le_hex(self):
        return struct.pack('<f', _f32(self.wire_i32) / 10).hex()


@dataclass(frozen=True)
class AttributedFeature:
    geometry: Line32 | Area24
    attributes: tuple[EnumE | ScaledM, ...]

    def __post_init__(self):
        if type(self.geometry) not in (Line32, Area24):
            raise ValueError('finite typed attribute extension supports Line32/Area24 only')
        key = (self.geometry.class_code, 32 if type(self.geometry) is Line32 else 24)
        if key not in TYPED_LAYERS:
            raise ValueError('only native DEPCNT42 line / SLCONS131 line or area / DEPARE41 or DRGARE46 area')
        if type(self.attributes) not in (tuple, list) or any(type(a) not in (EnumE, ScaledM) for a in self.attributes):
            raise ValueError('explicit finite typed attribute sequence required')
        codes = [a.native_code_u16 for a in self.attributes]
        if len(set(codes)) != len(codes) or any(code not in POSITIONS[self.geometry.class_code] for code in codes):
            raise ValueError('duplicate or unsupported attribute for native class')
        if any((type(a) is ScaledM) != (a.native_code_u16 in M_CODES) for a in self.attributes):
            raise ValueError('native dictionary storage type mismatch')
        attrs = tuple(sorted(self.attributes, key=lambda a: POSITIONS[self.geometry.class_code][a.native_code_u16]))
        object.__setattr__(self, 'attributes', attrs)

    @property
    def source_id(self): return self.geometry.source_id
    @property
    def class_code(self): return self.geometry.class_code
    @property
    def kind(self): return 32 if type(self.geometry) is Line32 else 24
    @property
    def layer_id(self): return TYPED_LAYERS[(self.class_code, self.kind)]
    @property
    def presence(self): return tuple(a.native_code_u16 for a in self.attributes)
    @property
    def wire_attributes(self): return b''.join(a.wire_bytes for a in self.attributes)
    @property
    def mask_pair(self):
        words = [0, 0]
        for code in self.presence:
            position = POSITIONS[self.class_code][code]
            words[position // 32] |= 1 << (31 - position % 32)
        return tuple(words)


@dataclass(frozen=True)
class PresenceLayer:
    layer_id: int
    features: tuple[AttributedFeature, ...]
    layer_range: tuple[int, int] = (0, 100000)

    def __post_init__(self):
        _integer(self.layer_id, 0, 255, 'typed layer ID')
        object.__setattr__(self, 'layer_range', _pair(self.layer_range, -(1 << 31), (1 << 31)-1, 'layer range'))
        if type(self.features) not in (tuple, list) or not self.features or any(type(f) is not AttributedFeature for f in self.features):
            raise ValueError('nonempty explicit typed feature group required')
        features = tuple(self.features)
        first = features[0]
        if any(f.layer_id != self.layer_id or (f.class_code, f.presence) != (first.class_code, first.presence) for f in features):
            raise ValueError('one geometry/class/complete attribute presence per physical layer')
        object.__setattr__(self, 'features', features)


def group_typed_features(features, *, layer_range=(0, 100000)):
    """Stable contiguous presence runs; never globally reorder A/B/A into A/A/B."""
    if type(features) not in (tuple, list) or not features or any(type(f) is not AttributedFeature for f in features):
        raise ValueError('nonempty explicit typed feature sequence required')
    groups, run, previous = [], [], None
    for feature in features:
        key = (feature.layer_id, feature.class_code, feature.presence)
        if run and key != previous:
            groups.append(PresenceLayer(run[0].layer_id, tuple(run), layer_range))
            run = []
        run.append(feature)
        previous = key
    groups.append(PresenceLayer(run[0].layer_id, tuple(run), layer_range))
    return tuple(groups)


@dataclass(frozen=True)
class TypedBatchSpec:
    header: RawMapHeader
    layers: tuple[PresenceLayer, ...]
    date_text_1: str = '20261017'
    date_text_2: str = '20261017'

    def __post_init__(self):
        if type(self.header) is not RawMapHeader or self.header.trailing_value != 0:
            raise ValueError('header requires zero cursor placeholder; writer computes it')
        if type(self.layers) not in (tuple, list) or not self.layers or any(type(v) is not PresenceLayer for v in self.layers):
            raise ValueError('explicit finite PresenceLayer sequence required')
        object.__setattr__(self, 'layers', tuple(self.layers))
        features = [f for layer in self.layers for f in layer.features]
        if len({f.source_id for f in features}) != len(features):
            raise ValueError('source_id must be unique across all presence groups')
        validate_raw_grid_bounds(self.header.raw_bounds, self.header.raw_grid)
        for feature in features:
            for part in feature.geometry.parts:
                validate_u16_xy(part, self.header.raw_grid)
        encode_plain_metadata_record(declared_file_bytes=258, raw_bounds=self.header.raw_bounds,
            date_text_1=self.date_text_1, date_text_2=self.date_text_2)


def plan_typed_batch(spec, *, max_output_bytes=4096):
    if type(spec) is not TypedBatchSpec:
        raise TypeError('spec must be TypedBatchSpec')
    _integer(max_output_bytes, 1, MAX_TOOL_OUTPUT_BYTES, 'own output byte budget')
    rows, bank_payload = [], 24
    for ordinal, layer in enumerate(spec.layers):
        for feature in layer.features:
            parts, bbox = [], None
            for i, part in enumerate(feature.geometry.parts):
                digest = hashlib.sha256()
                for xy in part:
                    digest.update(struct.pack('<HH', *xy))
                    bbox = [xy[0], xy[1], xy[0], xy[1]] if bbox is None else [min(bbox[0],xy[0]),min(bbox[1],xy[1]),max(bbox[2],xy[0]),max(bbox[3],xy[1])]
                parts.append(dict(ordinal=i, streamBytes=5*len(part), pointCount=len(part), rawU16PointSHA256=digest.hexdigest()))
            count, attr = len(parts), feature.wire_attributes
            _integer(len(attr), 0, 65535, 'native U16 AttrLen')
            size = plan_payload_width(10+4*count+(feature.kind==24)+len(attr), sized_fields=(len(attr),count))
            rows.append(dict(sourceId=feature.source_id, layerOrdinal=ordinal, layerId=layer.layer_id,
                kind=feature.kind, classCode=feature.class_code, attributeBytes=len(attr),
                attributes=[a.expected for a in feature.attributes], attributeMask=list(feature.mask_pair),
                attributePayloadSHA256=hashlib.sha256(attr).hexdigest(),
                partCount=count,bboxU16=bbox,parts=parts,rawPointXY=None,
                recordBytes=2+size['lengthWidth']+size['payloadBytes'], **size))
            native_range = _range_plan(feature.geometry)
            if native_range is not None:
                rows[-1]['nativeLevelRange'] = native_range
            bank_payload += sum(p['streamBytes'] for p in parts)
    bank_width = plan_payload_width(bank_payload)['lengthWidth']
    layer_at = 320+2+bank_width+bank_payload
    cursor = layer_at
    layers = []
    for ordinal, layer in enumerate(spec.layers):
        first = layer.features[0]
        masks = [] if not first.attributes else [dict(classCode=first.class_code,mask0=first.mask_pair[0],mask1=first.mask_pair[1])]
        payload = 19+9*len(masks)
        width = plan_payload_width(payload)['lengthWidth']
        layers.append(dict(ordinal=ordinal, layerId=layer.layer_id, recordOffset=cursor,
            layerRange=list(layer.layer_range),featureCount=len(layer.features),maskRows=masks,
            payloadBytes=payload,lengthWidth=width,recordBytes=2+width+payload,
            depthUnitTagBits=0,preservedOpaqueWord=0))
        cursor += 2+width+payload
    layer_end = cursor
    cursor += 7
    stream_cursor = 320+2+bank_width+24
    index = 0
    for layer,row in zip(spec.layers,layers):
        row['firstFeatureOffset'] = cursor
        for _ in layer.features:
            feature = rows[index]
            feature['layerOffset'] = row['recordOffset']
            feature['recordOffset'] = cursor
            cursor += feature['recordBytes']
            for part in feature['parts']:
                _integer(stream_cursor, 0, (1<<30)-1, 'direct stream pointer low30')
                part['streamOffset'] = stream_cursor
                stream_cursor += part['streamBytes']
            index += 1
        row['featureEndOffset'] = cursor
        cursor += 7
    for value in (265,layer_at,layer_end,cursor,*(r['recordOffset']for r in rows)):
        _integer(value,0,0xffffffff,'planned offset/extent U32')
    if cursor > max_output_bytes:
        raise ValueError('OUTPUT_BUDGET_EXCEEDED: typed extent exceeds explicit own tool budget')
    validation = validate_raw_grid_bounds(spec.header.raw_bounds,spec.header.raw_grid,
        bank_bounds=spec.header.raw_bounds,bank_grid=spec.header.raw_grid)
    plan = dict(format='no5-own-plain-typed-attribute-batch-plan/1', totalBytes=cursor,
        outputBudgetBytes=max_output_bytes,headerOffset=265,bankOffset=320,
        bankLengthWidth=bank_width,bankPayloadBytes=bank_payload,firstLayerOffset=layer_at,
        layerEndOffset=layer_end,layers=layers,features=rows,
        rawHeader=dict(flags=spec.header.flags,rawBounds=list(spec.header.raw_bounds),rawGrid=list(spec.header.raw_grid),rawLevels=list(spec.header.raw_levels)),
        dateText1=spec.date_text_1,dateText2=spec.date_text_2,rawInputValidation=validation,
        nativeAcceptance='SOURCE_BOUND_E_M_AND_DUPLICATE_LAYER_ITERATION_NATIVE_READBACK_PENDING',
        physicalPacking='SOURCE_BOUND_CONTIGUOUS_PLAIN_GENERIC_NATIVE_UNQUALIFIED',
        semanticAttributes='EXPLICIT_NATIVE_E_M_SUBSET_ONLY',
        otherSourceAttributes='CALLER_MUST_ACCOUNT_NO_FULL_SOURCE_COMPLETENESS_CLAIM',
        geographicDatumPortrayal='UNQUALIFIED',sourceIds='CALLER_LEDGER_LABELS_NOT_NATIVE_UIDS',
        attributeContractSHA256=ATTRIBUTE_CONTRACT_SHA256,presenceContractSHA256=PRESENCE_CONTRACT_SHA256)
    if any(row['classCode'] in (41, 46) for row in rows):
        plan['depthAreaAttributeContractSHA256'] = DEPTH_AREA_CONTRACT_SHA256
    return plan


def _chunks(spec, plan):
    h=spec.header
    yield encode_plain_metadata_record(declared_file_bytes=plan['totalBytes'],raw_bounds=h.raw_bounds,
        date_text_1=spec.date_text_1,date_text_2=spec.date_text_2)
    yield _envelope(4,4,1)+struct.pack('<I',265)
    yield encode_map_header(RawMapHeader(h.flags,h.raw_bounds,h.raw_grid,h.raw_levels,plan['firstLayerOffset']))
    yield _envelope(52,plan['bankPayloadBytes'],plan['bankLengthWidth'])
    yield struct.pack('<IIiiii',*h.raw_grid,*h.raw_bounds)
    for layer in spec.layers:
        for feature in layer.features:
            for part in feature.geometry.parts:
                yield struct.pack('<HH',*part[0])
                for xy in part[1:]:yield b'\x88'+struct.pack('<HH',*xy)
                yield b'\0'
    for layer,row in zip(spec.layers,plan['layers']):
        yield _envelope(18,row['payloadBytes'],row['lengthWidth'])+struct.pack('<BHIIii',
            layer.layer_id,0,row['firstFeatureOffset'],0,*layer.layer_range)
        for mask in row['maskRows']:yield struct.pack('<BII',mask['classCode'],mask['mask0'],mask['mask1'])
    yield _envelope(0,4,1,False)+b'\xff'*4
    index=0
    for layer in spec.layers:
        for feature in layer.features:
            row=plan['features'][index];width=row['lengthWidth'];attr=feature.wire_attributes
            yield _envelope(row['kind'],row['payloadBytes'],width)+struct.pack('<4H',*row['bboxU16'])
            yield bytes((feature.class_code,_packed_range(feature.geometry)))+len(attr).to_bytes(width,'little')+attr
            yield row['partCount'].to_bytes(width,'little')
            for part in row['parts']:yield struct.pack('<I',part['streamOffset'])
            if feature.kind==24:yield b'\0'
            index+=1
        yield _envelope(0,4,1)+b'\xff'*4


def write_typed_batch(spec, sink, *, expected_plan_sha256, max_output_bytes=4096):
    """Rebind full plan before any write; positive short writes retry only IO."""
    plan=plan_typed_batch(spec,max_output_bytes=max_output_bytes)
    digest=plan_sha256(plan)
    if type(expected_plan_sha256)is not str or expected_plan_sha256!=digest:
        raise ValueError('EXACT_EXPECTED_PLAN_SHA256_REQUIRED')
    if not callable(getattr(sink,'write',None)):
        raise TypeError('sink must provide write(bytes)->int')
    sha=hashlib.sha256();written=0
    for chunk in _chunks(spec,plan):
        at=0
        while at<len(chunk):
            count=sink.write(chunk[at:])
            if type(count)is not int or not 0<count<=len(chunk)-at:
                raise OSError('SINK_WRITE_DID_NOT_MAKE_VALID_PROGRESS')
            sha.update(chunk[at:at+count]);at+=count;written+=count
    if written!=plan['totalBytes']:raise RuntimeError('emission extent differs from plan')
    return dict(format='no5-own-plain-typed-attribute-batch-emission/1',bytesWritten=written,
        emittedStreamSHA256=sha.hexdigest(),planSHA256=digest,plan=plan,
        nativeReaderExecuted=False,persistedFileReadback=False)


def typed_batch_from_spec(value):
    """Exact opt-in JSON schema; unknown fields/storage/attributes refuse."""
    if type(value) is not dict or set(value) != {
            'format','header','layers','date_text_1','date_text_2'} or value['format'] != (
            'no5-own-plain-typed-attribute-batch/1'):
        raise ValueError('exact own typed attribute batch document required')
    header = value['header']
    if type(header) is not dict or set(header) != set(RawMapHeader.__dataclass_fields__):
        raise ValueError('exact raw header fields required')
    if type(value['layers']) is not list:
        raise ValueError('layers must be a finite JSON list')
    layers = []
    for layer in value['layers']:
        if type(layer) is not dict or set(layer) != {'layer_id','layer_range','features'}:
            raise ValueError('exact presence layer fields required')
        if type(layer['features']) is not list:
            raise ValueError('features must be a finite JSON list')
        features = []
        for row in layer['features']:
            if type(row) is not dict or set(row) not in (
                    {'kind','source_id','class_code','parts','attributes'},
                    {'kind','source_id','class_code','parts','attributes','native_level_range'}):
                raise ValueError('exact typed feature fields required; nothing silently dropped')
            if type(row['kind']) is not int or row['kind'] not in (24,32):
                raise ValueError('finite typed geometry kind24/32 required')
            if type(row['attributes']) is not list:
                raise ValueError('attributes must be an explicit finite JSON list')
            attrs = []
            for attr in row['attributes']:
                if type(attr) is not dict:
                    raise ValueError('typed attribute row required')
                if attr.get('storage') == 'E' and set(attr) == {'storage','native_code_u16','enum_u8'}:
                    attrs.append(EnumE(attr['native_code_u16'],attr['enum_u8']))
                elif attr.get('storage') == 'M' and set(attr) == {
                        'storage','native_code_u16','value_decimal','declared_native_units'}:
                    text = attr['value_decimal']
                    if type(text) is not str or not 1 <= len(text) <= 96:
                        raise ValueError('M value_decimal requires an explicit bounded decimal string')
                    try:
                        decimal = Decimal(text)
                    except InvalidOperation as exc:
                        raise ValueError('M value_decimal is not a valid Decimal') from exc
                    attrs.append(ScaledM(attr['native_code_u16'],decimal,attr['declared_native_units']))
                else:
                    raise ValueError('unknown or extra attribute fields/storage; no silent drops')
            cls = Line32 if row['kind'] == 32 else Area24
            features.append(AttributedFeature(cls(row['source_id'],row['class_code'],row['parts'],
                _range_from_json(row.get('native_level_range'))),attrs))
        layers.append(PresenceLayer(layer['layer_id'],features,layer['layer_range']))
    return TypedBatchSpec(RawMapHeader(**header),layers,value['date_text_1'],value['date_text_2'])
