"""Explicit generic attribute maps with shared class presence and BMP resources.

This API consumes caller-selected raw integers/bytes and qualified enum values.
It never infers source units, rounding, missing values, depth or completeness.
Text tables are ordinary Plain records after the contiguous map-header group.
"""
from dataclasses import dataclass
import hashlib
import struct

from .batch import (Point40, Line32, Area24, _integer, _pair, _envelope,
                    _packed_range, _range_plan, _range_from_json,
                    plan_payload_width, plan_sha256)
from .layered_batch import DepthPoint44
from .generic_registry import ATTRIBUTES, CLASSES, PAIRED_ENUMS, EXPLICIT_ENUM_ALIASES
from .generic_registry import ATTRIBUTE_CONTRACT_SHA256, CLASS_CONTRACT_SHA256, ENUM_ALIAS_CONTRACT_SHA256
from .generic_registry import (
    CLASS_EXPANSION_CONTRACT_SHA256, CLASS_EXPANSION_NEW_CODES, CLASS_EXPANSION_ROUTES,
    ENUM_EXPANSION_CONTRACT_SHA256, ADDITIONAL_ENUM_VALUES, HKO_STORAGE_CONTRACT_SHA256,
)
from .generic_attribute_wire import encode_native_value, presence_payload, plan_bmp_text_pool
from .road_authoring_registry import (ROAD_AUTHORING_POLICY, ROAD_UNCATEGORIZED_POLICY,
    ROAD_UNCATEGORIZED_CONSUMER_SHA256, ROAD_CONSUMER_CONTRACT_SHA256,
    road_class_row, road_enum_values)
from .resare_enum_pairing import (CATREA28_PAIRING_POLICY, CATREA28_PAIRING_CONTRACT_SHA256,
    paired_catrea_values)
from .metadata import encode_plain_metadata_record
from .raw_grid_bounds import validate_point40_xy, validate_raw_grid_bounds, validate_u16_xy
from .writer import RawMapHeader, encode_map_header

MAX_GENERIC_BYTES = 64 * 1024 * 1024
MAX_TEXT_POOL_BYTES = 1024 * 1024  # Own bounded resource allocation, not format.
POINT_ATTRIBUTE_CONTRACT_SHA256 = 'b7d7af0fd8a8c7d3782c5e059dfdf4ca3cb72aade7fc7f06da22d058e3d03334'


def qualified_enum_values(native_code_u16, *, road_authoring_policy=None, source_pairing_policy=None):
    """Finite paired domain plus explicitly source-bound abbreviation aliases."""
    if source_pairing_policy is not None:
        if road_authoring_policy is not None:
            raise ValueError('source enum pairing and road policies are separate')
        return paired_catrea_values(native_code_u16, source_pairing_policy)
    if road_authoring_policy is not None:
        return road_enum_values(native_code_u16, road_authoring_policy)
    _integer(native_code_u16, 1, 264, 'native enum attribute code')
    if ATTRIBUTES[native_code_u16]['storage'] not in ('E', 'L'):
        raise ValueError('native attribute is not enum/list storage')
    return tuple(sorted(set(PAIRED_ENUMS.get(native_code_u16, ())) |
                        set(EXPLICIT_ENUM_ALIASES.get(native_code_u16, ())) |
                        set(ADDITIONAL_ENUM_VALUES.get(native_code_u16, ()))))


@dataclass(frozen=True)
class GenericAttribute:
    native_code_u16: int
    value: object
    declared_native_units: int | None = None
    road_authoring_policy: str | None = None
    source_pairing_policy: str | None = None

    def __post_init__(self):
        _integer(self.native_code_u16, 1, 264, 'native attribute code')
        if self.road_authoring_policy is not None:
            road_enum_values(self.native_code_u16, self.road_authoring_policy)
        if self.source_pairing_policy is not None:
            qualified_enum_values(self.native_code_u16, road_authoring_policy=self.road_authoring_policy,
                                  source_pairing_policy=self.source_pairing_policy)
        row = ATTRIBUTES[self.native_code_u16]
        storage = row['storage']
        if storage not in ('E', 'L', 'M', 'J', 'H', 'K', 'O', 'A', 'S', 'B', 'T'):
            raise ValueError('dictionary storage has no admitted generic recipe')
        if storage in ('M', 'J', 'H', 'K'):
            _integer(self.declared_native_units, 0, 255, 'explicit native numeric-unit premise')
        elif self.declared_native_units is not None:
            raise ValueError('native units apply only to float numeric M/J/H/K input')
        if storage in ('B', 'T'):
            # Validate BMP, length and normal-consumer limits without file IO.
            plan_bmp_text_pool([self.value], 0)
        else:
            encode_native_value(storage, self.value,
                allowed_enums=qualified_enum_values(self.native_code_u16, road_authoring_policy=self.road_authoring_policy, source_pairing_policy=self.source_pairing_policy) if storage in ('E', 'L') else ())
        if storage == 'L':
            object.__setattr__(self, 'value', tuple(self.value))

    @property
    def storage(self):
        return ATTRIBUTES[self.native_code_u16]['storage']

    def wire_bytes(self, text_ids):
        value = text_ids[self.value] if self.storage in ('B', 'T') else self.value
        return encode_native_value(self.storage, value,
            allowed_enums=qualified_enum_values(self.native_code_u16, road_authoring_policy=self.road_authoring_policy, source_pairing_policy=self.source_pairing_policy) if self.storage in ('E', 'L') else ())

    def expected(self, text_ids):
        row = dict(native_code_u16=self.native_code_u16, storage=self.storage)
        if self.storage == 'E': row['enum_u8'] = self.value
        elif self.storage == 'L': row['enum_u8s'] = list(self.value)
        elif self.storage in ('M', 'J', 'H', 'K'):
            row['wire_i32' if self.storage == 'M' else 'wire_u32' if self.storage == 'K' else 'wire_u16'] = self.value
            row['declared_native_units'] = self.declared_native_units
            dictionary = ATTRIBUTES[self.native_code_u16]
            row['dictionaryFactor'] = dictionary['factorOrEnumStart']
            row['dictionaryDefaultUnits'] = dictionary['defaultUnitsRaw']
            row['dictionaryUnitSource'] = dictionary['unitSourceRaw']
            row['sourceQuantization'] = 'EXPLICIT_CALLER_PREMISE_NOT_INFERRED'
        elif self.storage == 'O':
            row['wire_u16'] = self.value
            row['dictionaryFactor'] = ATTRIBUTES[self.native_code_u16]['factorOrEnumStart']
            row['nativeIntegerDivision'] = 'UNSIGNED_U32_AFTER_U16_READ'
        elif self.storage in ('A', 'S'): row['raw_hex'] = self.value.hex()
        else:
            row['text_id'] = text_ids[self.value]
            row['text'] = self.value
            row['utf8_bytes'] = len(self.value.encode('utf-8'))
        return row


@dataclass(frozen=True)
class GenericAttributedFeature:
    geometry: Point40 | Line32 | Area24 | DepthPoint44
    attributes: tuple[GenericAttribute, ...]
    road_authoring_policy: str | None = None

    def __post_init__(self):
        if type(self.geometry) not in (Point40, Line32, Area24, DepthPoint44):
            raise ValueError('explicit Point40/Line32/Area24/DepthPoint44 geometry required')
        registry = road_class_row(self.class_code, self.road_authoring_policy) if self.road_authoring_policy is not None else CLASSES.get(self.class_code)
        if registry is None:
            raise ValueError('native class is not in the source-bound registry')
        # SOUNDG keeps its genuine Kind44 and explicit stored Z; Point40 never
        # substitutes a fabricated depth. Other routes use the bound geometry.
        kinds = registry['allowedKinds']
        if self.kind not in kinds and not (self.class_code == 136 and self.kind == 44):
            raise ValueError('geometry route not source-qualified for native class')
        if type(self.attributes) not in (tuple, list) or any(type(a) is not GenericAttribute for a in self.attributes):
            raise ValueError('explicit generic attribute sequence required')
        if any(a.road_authoring_policy != self.road_authoring_policy for a in self.attributes):
            raise ValueError('feature and attribute authoring policies must match')
        if self.road_authoring_policy == ROAD_UNCATEGORIZED_POLICY:
            if self.attributes:
                raise ValueError('uncategorized road scope requires no native attributes')
        elif self.road_authoring_policy is not None and (not self.attributes or any(
                a.native_code_u16 != 158 or a.road_authoring_policy != self.road_authoring_policy
                for a in self.attributes)):
            raise ValueError('road authoring scope requires explicit E158 and matching policy')
        if any(a.source_pairing_policy is not None for a in self.attributes):
            if self.class_code != 120 or self.kind != 24 or self.road_authoring_policy is not None:
                raise ValueError('CATREA28 source pairing scope is only RESARE120 Area24')
        order = registry['nativeAttributeOrder']
        codes = [a.native_code_u16 for a in self.attributes]
        if len(codes) != len(set(codes)) or any(code not in order for code in codes):
            raise ValueError('duplicate attribute or attribute outside actual class dictionary')
        object.__setattr__(self, 'attributes', tuple(sorted(self.attributes,
            key=lambda a: order.index(a.native_code_u16))))

    @property
    def source_id(self): return self.geometry.source_id
    @property
    def class_code(self): return self.geometry.class_code
    @property
    def kind(self):
        return {Point40: 40, Line32: 32, Area24: 24, DepthPoint44: 44}[type(self.geometry)]
    @property
    def layer_id(self):
        registry = road_class_row(self.class_code, self.road_authoring_policy) if self.road_authoring_policy is not None else CLASSES[self.class_code]
        return registry['layerBase'] + {40: 0, 44: 0, 32: 1, 24: 2}[self.kind]
    @property
    def presence(self): return tuple(a.native_code_u16 for a in self.attributes)

    def encoded(self, text_ids):
        registry = road_class_row(self.class_code, self.road_authoring_policy) if self.road_authoring_policy is not None else CLASSES[self.class_code]
        return presence_payload(registry['nativeAttributeOrder'],
            {a.native_code_u16: a.wire_bytes(text_ids) for a in self.attributes})


@dataclass(frozen=True)
class GenericPresenceLayer:
    layer_id: int
    features: tuple[GenericAttributedFeature, ...]
    layer_range: tuple[int, int] = (0, 100000)

    def __post_init__(self):
        _integer(self.layer_id, 0, 255, 'generic typed layer ID')
        object.__setattr__(self, 'layer_range', _pair(self.layer_range, -(1 << 31), (1 << 31)-1, 'layer range'))
        if type(self.features) not in (tuple, list) or not self.features or any(type(f) is not GenericAttributedFeature for f in self.features):
            raise ValueError('nonempty generic feature sequence required')
        first = self.features[0]
        if any(f.layer_id != self.layer_id or (f.class_code, f.presence) != (first.class_code, first.presence) for f in self.features):
            raise ValueError('physical layer requires one class/complete attribute presence set')
        object.__setattr__(self, 'features', tuple(self.features))


def group_generic_features(features, *, layer_range=(0, 100000)):
    """Keep stable contiguous class/geometry/presence runs, including A/B/A."""
    if type(features) not in (tuple, list) or not features or any(type(f) is not GenericAttributedFeature for f in features):
        raise ValueError('nonempty explicit generic feature sequence required')
    layers, run, prior = [], [], None
    for feature in features:
        key = (feature.layer_id, feature.class_code, feature.presence)
        if run and key != prior:
            layers.append(GenericPresenceLayer(run[0].layer_id, tuple(run), layer_range))
            run = []
        run.append(feature); prior = key
    layers.append(GenericPresenceLayer(run[0].layer_id, tuple(run), layer_range))
    return tuple(layers)


@dataclass(frozen=True)
class GenericAttributedBatchSpec:
    header: RawMapHeader
    layers: tuple[GenericPresenceLayer, ...]
    date_text_1: str = '20261018'
    date_text_2: str = '20261018'

    def __post_init__(self):
        if type(self.header) is not RawMapHeader or self.header.trailing_value != 0:
            raise ValueError('header requires zero cursor placeholder')
        if type(self.layers) not in (tuple, list) or not self.layers or any(type(v) is not GenericPresenceLayer for v in self.layers):
            raise ValueError('nonempty explicit generic layer sequence required')
        object.__setattr__(self, 'layers', tuple(self.layers))
        features = [f for layer in self.layers for f in layer.features]
        if len({f.source_id for f in features}) != len(features):
            raise ValueError('source_id must be unique across all groups')
        validate_raw_grid_bounds(self.header.raw_bounds, self.header.raw_grid)
        for f in features:
            if f.kind in (40, 44): validate_point40_xy(f.geometry.xy, self.header.raw_bounds)
            else:
                for part in f.geometry.parts: validate_u16_xy(part, self.header.raw_grid)
        encode_plain_metadata_record(declared_file_bytes=258, raw_bounds=self.header.raw_bounds,
            date_text_1=self.date_text_1, date_text_2=self.date_text_2)


def _text_values(spec):
    return [a.value for layer in spec.layers for f in layer.features for a in f.attributes
            if a.storage in ('B', 'T')]


def _text_resources(strings, offset):
    if not strings: return (), {}, []
    directory, pool, ids = plan_bmp_text_pool(strings, offset, max_pool_bytes=MAX_TEXT_POOL_BYTES)
    lookup = dict(zip(strings, ids))
    rows = [dict(text=value, textId=number, utf16Bytes=len(value.encode('utf-16le')),
                 utf8Bytes=len(value.encode('utf-8'))) for value, number in lookup.items()]
    return (directory, pool), lookup, rows


def _plan_generic(spec, max_output_bytes, *, shared_text_ids=None):
    if type(spec) is not GenericAttributedBatchSpec:
        raise TypeError('spec must be GenericAttributedBatchSpec')
    _integer(max_output_bytes, 1, MAX_GENERIC_BYTES, 'own generic output budget')
    if shared_text_ids is None:
        resources, text_ids, texts = _text_resources(_text_values(spec), 320)
    else:
        resources, text_ids, texts = (), shared_text_ids, []
        if any(value not in text_ids for value in _text_values(spec)):
            raise ValueError('every generic text must be bound by shared Atlas resources')
    resource_bytes = sum(map(len, resources))
    rows, bank_payload, vectors = [], 24, False
    for ordinal, layer in enumerate(spec.layers):
        for f in layer.features:
            mask0, mask1, attr = f.encoded(text_ids)
            parts, bbox = [], None
            if f.kind in (24, 32):
                vectors = True
                for i, part in enumerate(f.geometry.parts):
                    digest = hashlib.sha256()
                    for xy in part:
                        digest.update(struct.pack('<HH', *xy))
                        bbox = [xy[0],xy[1],xy[0],xy[1]] if bbox is None else [min(bbox[0],xy[0]),min(bbox[1],xy[1]),max(bbox[2],xy[0]),max(bbox[3],xy[1])]
                    parts.append(dict(ordinal=i, streamBytes=5*len(part), pointCount=len(part), rawU16PointSHA256=digest.hexdigest()))
            count = len(parts)
            base = 10 + len(attr) + (4*count + (f.kind == 24) if count else (2 if f.kind == 44 else 0))
            size = plan_payload_width(base, sized_fields=(len(attr), count) if count else (len(attr),))
            row = dict(sourceId=f.source_id, layerOrdinal=ordinal, layerId=layer.layer_id,
                kind=f.kind, classCode=f.class_code, attributeBytes=len(attr),
                attributes=[a.expected(text_ids) for a in f.attributes], attributeMask=[mask0,mask1],
                attributePayloadSHA256=hashlib.sha256(attr).hexdigest(), partCount=count,
                bboxU16=bbox, parts=parts, rawPointXY=list(f.geometry.xy) if not count else None,
                recordBytes=2+size['lengthWidth']+size['payloadBytes'], **size)
            if f.kind == 44: row['depthWord'] = f.geometry.depth_word
            native_range = _range_plan(f.geometry)
            if native_range is not None: row['nativeLevelRange'] = native_range
            rows.append(row); bank_payload += sum(p['streamBytes'] for p in parts)
    bank_width = plan_payload_width(bank_payload)['lengthWidth'] if vectors else None
    bank_at = 320+resource_bytes if vectors else None
    layer_at = 320+resource_bytes+(2+bank_width+bank_payload if vectors else 0)
    cursor, layers = layer_at, []
    for ordinal, layer in enumerate(spec.layers):
        first = layer.features[0]; m0,m1,_ = first.encoded(text_ids)
        masks = [dict(classCode=first.class_code,mask0=m0,mask1=m1)] if first.attributes else []
        payload=19+9*len(masks); width=plan_payload_width(payload)['lengthWidth']
        layers.append(dict(ordinal=ordinal,layerId=layer.layer_id,recordOffset=cursor,
            layerRange=list(layer.layer_range),featureCount=len(layer.features),maskRows=masks,
            payloadBytes=payload,lengthWidth=width,recordBytes=2+width+payload,
            depthUnitTagBits=0,preservedOpaqueWord=0))
        cursor += 2+width+payload
    layer_end=cursor; cursor+=7
    stream_cursor=bank_at+2+bank_width+24 if vectors else None
    index=0
    for source, layer in zip(spec.layers,layers):
        layer['firstFeatureOffset']=cursor
        for _ in source.features:
            row=rows[index]; row['layerOffset']=layer['recordOffset']; row['recordOffset']=cursor
            cursor+=row['recordBytes']
            for part in row['parts']:
                _integer(stream_cursor,0,(1<<30)-1,'generic direct stream low30')
                part['streamOffset']=stream_cursor; stream_cursor+=part['streamBytes']
            index+=1
        layer['featureEndOffset']=cursor; cursor+=7
    _integer(cursor,0,0xffffffff,'generic U32 extent')
    if cursor>max_output_bytes: raise ValueError('OUTPUT_BUDGET_EXCEEDED: generic extent exceeds own budget')
    plan = dict(format='no5-own-plain-generic-attribute-batch-plan/1',totalBytes=cursor,
        outputBudgetBytes=max_output_bytes,headerOffset=265,bankOffset=bank_at,
        bankLengthWidth=bank_width,bankPayloadBytes=bank_payload if vectors else 0,
        firstLayerOffset=layer_at,layerEndOffset=layer_end,layers=layers,features=rows,
        rawHeader=dict(flags=spec.header.flags,rawBounds=list(spec.header.raw_bounds),rawGrid=list(spec.header.raw_grid),rawLevels=list(spec.header.raw_levels)),
        dateText1=spec.date_text_1,dateText2=spec.date_text_2,
        textResources=dict(offset=320 if resources else None,bytes=resource_bytes,entries=texts,
            textIds=dict(text_ids),ownership='LOCAL' if shared_text_ids is None else 'ATLAS_SHARED',
            resourceSHA256=hashlib.sha256(b''.join(resources)).hexdigest()),
        rawInputValidation=validate_raw_grid_bounds(spec.header.raw_bounds,spec.header.raw_grid),
        attributeContractSHA256=ATTRIBUTE_CONTRACT_SHA256,classContractSHA256=CLASS_CONTRACT_SHA256,
        pointAttributeContractSHA256=POINT_ATTRIBUTE_CONTRACT_SHA256,
        enumAliasContractSHA256=ENUM_ALIAS_CONTRACT_SHA256,
        nativeAcceptance='GENERIC_ATTRIBUTE_AND_RESOURCE_NATIVE_READBACK_PENDING',
        inputCompleteness='EXTERNAL_SOURCE_AND_LOSS_LEDGER_REQUIRED',
        geographicDatumPortrayal='UNQUALIFIED')
    features = [f for layer in spec.layers for f in layer.features]
    if any(f.class_code in CLASS_EXPANSION_NEW_CODES or
           f.kind in CLASS_EXPANSION_ROUTES.get(f.class_code, ()) for f in features):
        plan['classExpansionContractSHA256'] = CLASS_EXPANSION_CONTRACT_SHA256
    if any(attr.storage in ('H', 'K', 'O') for f in features for attr in f.attributes):
        plan['numericStorageContractSHA256'] = HKO_STORAGE_CONTRACT_SHA256
    # Older values retain their complete plan digests. A newly permitted value
    # binds the additional finite semantic evidence, never a loose label match.
    expanded_enum = False
    for f in features:
        for attr in f.attributes:
            if attr.storage not in ('E', 'L'): continue
            if attr.road_authoring_policy is not None or attr.source_pairing_policy is not None:
                continue
            prior = set(PAIRED_ENUMS.get(attr.native_code_u16, ())) | set(EXPLICIT_ENUM_ALIASES.get(attr.native_code_u16, ()))
            values = (attr.value,) if attr.storage == 'E' else attr.value
            expanded_enum |= any(value not in prior for value in values)
    if expanded_enum:
        plan['enumExpansionContractSHA256'] = ENUM_EXPANSION_CONTRACT_SHA256
    if any(attr.source_pairing_policy is not None for f in features for attr in f.attributes):
        plan['sourceEnumPairingPolicy'] = CATREA28_PAIRING_POLICY
        plan['sourceEnumPairingContractSHA256'] = CATREA28_PAIRING_CONTRACT_SHA256
    if any(f.road_authoring_policy is not None for f in features):
        policies = {f.road_authoring_policy for f in features if f.road_authoring_policy is not None}
        if len(policies) == 1:
            plan['roadAuthoringPolicy'] = next(iter(policies))
        else:
            plan['roadAuthoringPolicies'] = sorted(policies)
        if ROAD_UNCATEGORIZED_POLICY in policies:
            plan['roadUncategorizedConsumerSHA256'] = ROAD_UNCATEGORIZED_CONSUMER_SHA256
        plan['roadConsumerContractSHA256'] = ROAD_CONSUMER_CONTRACT_SHA256
        plan['sourceRoadIdentity'] = 'EXPLICIT_AUTHORING_POLICY_EXTERNAL_SOURCE_LEDGER_REQUIRED'
    return plan


def plan_generic_batch(spec, *, max_output_bytes=4096):
    return _plan_generic(spec,max_output_bytes)


def _chunks(spec, plan):
    h=spec.header; text_ids=plan['textResources']['textIds']
    yield encode_plain_metadata_record(declared_file_bytes=plan['totalBytes'],raw_bounds=h.raw_bounds,
        date_text_1=spec.date_text_1,date_text_2=spec.date_text_2)
    yield _envelope(4,4,1)+struct.pack('<I',265)
    yield encode_map_header(RawMapHeader(h.flags,h.raw_bounds,h.raw_grid,h.raw_levels,plan['firstLayerOffset']))
    if plan['textResources']['bytes']:
        resources,_,_=_text_resources(_text_values(spec),plan['textResources']['offset'])
        yield from resources
    if plan['bankOffset'] is not None:
        yield _envelope(52,plan['bankPayloadBytes'],plan['bankLengthWidth'])
        yield struct.pack('<IIiiii',*h.raw_grid,*h.raw_bounds)
        for layer in spec.layers:
            for f in layer.features:
                if f.kind in (24,32):
                    for part in f.geometry.parts:
                        yield struct.pack('<HH',*part[0])
                        for xy in part[1:]: yield b'\x88'+struct.pack('<HH',*xy)
                        yield b'\0'
    for source,row in zip(spec.layers,plan['layers']):
        yield _envelope(18,row['payloadBytes'],row['lengthWidth'])+struct.pack('<BHIIii',
            source.layer_id,0,row['firstFeatureOffset'],0,*source.layer_range)
        for mask in row['maskRows']: yield struct.pack('<BII',mask['classCode'],mask['mask0'],mask['mask1'])
    yield _envelope(0,4,1,False)+b'\xff'*4
    index=0
    for layer in spec.layers:
        for f in layer.features:
            row=plan['features'][index]; w=row['lengthWidth']; attr=f.encoded(text_ids)[2]
            yield _envelope(f.kind,row['payloadBytes'],w)
            if f.kind in (24,32): yield struct.pack('<4H',*row['bboxU16'])
            yield bytes((f.class_code,_packed_range(f.geometry)))+len(attr).to_bytes(w,'little')+attr
            if f.kind==44: yield struct.pack('<Hii',f.geometry.depth_word,*f.geometry.xy)
            elif f.kind==40: yield struct.pack('<ii',*f.geometry.xy)
            else:
                yield row['partCount'].to_bytes(w,'little')
                for part in row['parts']: yield struct.pack('<I',part['streamOffset'])
                if f.kind==24: yield b'\0'
            index+=1
        yield _envelope(0,4,1)+b'\xff'*4


def write_generic_batch(spec,sink,*,expected_plan_sha256,max_output_bytes=4096):
    plan=plan_generic_batch(spec,max_output_bytes=max_output_bytes); digest=plan_sha256(plan)
    if type(expected_plan_sha256)is not str or expected_plan_sha256!=digest:
        raise ValueError('EXACT_EXPECTED_PLAN_SHA256_REQUIRED')
    if not callable(getattr(sink,'write',None)): raise TypeError('sink must provide write(bytes)->int')
    sha=hashlib.sha256(); written=0
    for chunk in _chunks(spec,plan):
        at=0
        while at<len(chunk):
            count=sink.write(chunk[at:])
            if type(count)is not int or not 0<count<=len(chunk)-at: raise OSError('SINK_WRITE_DID_NOT_MAKE_VALID_PROGRESS')
            sha.update(chunk[at:at+count]); written+=count; at+=count
    if written!=plan['totalBytes']: raise RuntimeError('generic emission extent differs from plan')
    return dict(format='no5-own-plain-generic-attribute-batch-emission/1',bytesWritten=written,
        emittedStreamSHA256=sha.hexdigest(),planSHA256=digest,plan=plan,
        nativeReaderExecuted=False,persistedFileReadback=False)


def generic_batch_from_spec(value):
    if type(value)is not dict or set(value)-{'road_authoring_policy'}!={'format','header','layers','date_text_1','date_text_2'} or value['format']!='no5-own-plain-generic-attribute-batch/1':
        raise ValueError('exact generic batch schema required')
    if type(value['header'])is not dict or set(value['header'])!=set(RawMapHeader.__dataclass_fields__): raise ValueError('exact header fields required')
    if type(value['layers'])is not list: raise ValueError('finite JSON layer list required')
    policy = value.get('road_authoring_policy')
    if 'road_authoring_policy' in value and policy not in (ROAD_AUTHORING_POLICY, ROAD_UNCATEGORIZED_POLICY):
        raise ValueError('explicit exact road authoring policy required')
    layers=[]
    for layer in value['layers']:
        if type(layer)is not dict or set(layer)!={'layer_id','layer_range','features'} or type(layer['features'])is not list: raise ValueError('exact generic presence layer fields required')
        features=[]
        for row in layer['features']:
            if type(row)is not dict or type(row.get('kind'))is not int: raise ValueError('explicit generic feature kind required')
            kind=row['kind']; geo='xy' if kind in (40,44) else 'parts'
            required={'kind','source_id',geo,'attributes'}|({'mantissa','decimal_scale'}if kind==44 else {'class_code'})
            if set(row)-{'native_level_range'}!=required or type(row['attributes'])is not list: raise ValueError('exact generic feature fields required')
            ran=_range_from_json(row.get('native_level_range'))
            if kind==44: geometry=DepthPoint44(row['source_id'],row['xy'],row['mantissa'],row['decimal_scale'],ran)
            else:
                cls={40:Point40,32:Line32,24:Area24}.get(kind)
                if cls is None: raise ValueError('unqualified generic geometry kind')
                geometry=cls(row['source_id'],row['class_code'],row[geo],ran)
            attrs=[]
            for attr in row['attributes']:
                if type(attr)is not dict or type(attr.get('native_code_u16'))is not int: raise ValueError('explicit native attribute code required')
                code=attr['native_code_u16']; storage=ATTRIBUTES.get(code,{}).get('storage')
                key='raw_hex' if storage in ('A','S') else 'text' if storage in ('B','T') else 'enum_u8s' if storage=='L' else 'enum_u8' if storage=='E' else 'raw_integer'
                required={'native_code_u16',key}|({'declared_native_units'}if storage in ('M','J','H','K')else set())
                if set(attr)-{'source_pairing_policy'}!=required: raise ValueError('dictionary-specific exact generic attribute fields required')
                pairing = attr.get('source_pairing_policy')
                if 'source_pairing_policy' in attr and pairing != CATREA28_PAIRING_POLICY:
                    raise ValueError('explicit exact CATREA28 source pairing policy required')
                val=attr[key]
                if key=='raw_hex':
                    if type(val)is not str or len(val)%2 or len(val)>506: raise ValueError('bounded explicit raw hex required')
                    try: data=bytes.fromhex(val)
                    except ValueError as exc: raise ValueError('raw hex required') from exc
                    if data.hex()!=val.lower(): raise ValueError('hex must contain only complete byte pairs without whitespace')
                    val=data
                attrs.append(GenericAttribute(code,val,attr.get('declared_native_units'),policy,pairing))
            features.append(GenericAttributedFeature(geometry,attrs,policy))
        layers.append(GenericPresenceLayer(layer['layer_id'],features,layer['layer_range']))
    return GenericAttributedBatchSpec(RawMapHeader(**value['header']),layers,value['date_text_1'],value['date_text_2'])
