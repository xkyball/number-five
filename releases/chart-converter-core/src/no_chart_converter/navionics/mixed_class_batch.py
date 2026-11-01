"""Additive opt-in candidate: one physical layer ID, exact per-class masks.

Normal GenericAttribute/feature/header/geometry/text validation is reused.
No old API/type/default is changed. Native mixed-class semantics is pending.
"""
from copy import deepcopy
from dataclasses import dataclass
import hashlib
from no_chart_converter.navionics import generic_batch as generic
from no_chart_converter.navionics.batch import _integer, _pair, plan_payload_width, plan_sha256

POLICY = 'EXPLICIT_SINGLE_PHYSICAL_LAYER_PER_ID_CLASS_MASKS_V1'
FORMAT = 'no5-own-plain-class-mask-batch/1'


def _need(v, why):
    if not v:
        raise ValueError(why)


@dataclass(frozen=True)
class ClassMaskLayer:
    layer_id: int
    features: tuple[generic.GenericAttributedFeature, ...]
    layer_range: tuple[int, int] = (0, 100000)

    def __post_init__(self):
        _integer(self.layer_id, 0, 255, 'physical layer ID')
        object.__setattr__(self, 'layer_range', _pair(self.layer_range, -(1 << 31), (1 << 31)-1, 'complete physical layer range'))
        _need(type(self.features) in (tuple, list) and self.features and
              all(type(f) is generic.GenericAttributedFeature for f in self.features),
              'NONEMPTY_EXACT_GENERIC_FEATURES_REQUIRED')
        presence = {}
        for f in self.features:
            _need(f.layer_id == self.layer_id, 'EVERY_FEATURE_GENUINE_REGISTRY_LAYER_MUST_MATCH')
            key = (f.road_authoring_policy, f.presence)
            _need(f.class_code not in presence or presence[f.class_code] == key,
                  'SAME_CLASS_PRESENCE_OR_AUTHORING_POLICY_CONFLICT_REQUIRES_SEPARATE_MAP')
            presence[f.class_code] = key
        object.__setattr__(self, 'features', tuple(self.features))


@dataclass(frozen=True)
class ClassMaskBatchSpec:
    header: object
    layers: tuple[ClassMaskLayer, ...]
    date_text_1: str = '20261018'
    date_text_2: str = '20261018'
    physical_layer_policy: str | None = None

    def __post_init__(self):
        _need(self.physical_layer_policy == POLICY, 'EXPLICIT_OPTIN_PHYSICAL_LAYER_POLICY_REQUIRED')
        _need(type(self.layers) in (tuple, list) and self.layers and
              all(type(l) is ClassMaskLayer for l in self.layers), 'EXACT_CLASS_MASK_LAYERS_REQUIRED')
        _need(len({l.layer_id for l in self.layers}) == len(self.layers),
              'DUPLICATE_PHYSICAL_LAYER_ID_REQUIRES_SEPARATE_MAP')
        object.__setattr__(self, 'layers', tuple(self.layers))
        # Existing normal dataclass performs the complete header, bounds, U16,
        # class/kind, source-ID and metadata validation without writing bytes.
        _legacy_validation_spec(self)


def _legacy_validation_spec(spec):
    runs = []
    for layer in spec.layers:
        runs.extend(generic.group_generic_features(layer.features, layer_range=layer.layer_range))
    return generic.GenericAttributedBatchSpec(spec.header, runs, spec.date_text_1, spec.date_text_2)


def _class_mask_rows(layer, text_ids):
    seen = {}
    for f in layer.features:
        mask0, mask1, _ = f.encoded(text_ids)
        pair = (mask0, mask1)
        _need(f.class_code not in seen or seen[f.class_code] == pair,
              'SAME_CLASS_ENCODED_PRESENCE_CONFLICT')
        seen[f.class_code] = pair
    # First class occurrence order, one exact row per nonzero class mask.
    # No default attribute values and no mask union are introduced.
    return [dict(classCode=c, mask0=m[0], mask1=m[1])
            for c, m in seen.items() if m != (0, 0)]


def plan_class_mask_batch(spec, *, max_output_bytes=4096):
    if type(spec) is not ClassMaskBatchSpec:
        raise TypeError('spec must be explicit ClassMaskBatchSpec')
    _integer(max_output_bytes, 1, generic.MAX_GENERIC_BYTES, 'own class-mask output budget')
    legacy = _legacy_validation_spec(spec)
    parent = generic.plan_generic_batch(legacy, max_output_bytes=generic.MAX_GENERIC_BYTES)
    plan = deepcopy(parent)
    cursor = parent['firstLayerOffset']
    layers = []
    for ordinal, source in enumerate(spec.layers):
        masks = _class_mask_rows(source, parent['textResources']['textIds'])
        payload = 19 + 9 * len(masks)
        width = plan_payload_width(payload)['lengthWidth']
        layer = dict(ordinal=ordinal, layerId=source.layer_id, recordOffset=cursor,
                     layerRange=list(source.layer_range), featureCount=len(source.features),
                     maskRows=masks, payloadBytes=payload, lengthWidth=width,
                     recordBytes=2+width+payload, depthUnitTagBits=0, preservedOpaqueWord=0)
        layers.append(layer)
        cursor += layer['recordBytes']
    layer_end = cursor
    cursor += 7  # Normal physical directory END, unchanged envelope.
    index = 0
    for ordinal, (source, row) in enumerate(zip(spec.layers, layers)):
        row['firstFeatureOffset'] = cursor
        for f in source.features:
            feature = plan['features'][index]
            _need(feature['sourceId'] == f.source_id and feature['classCode'] == f.class_code,
                  'EXACT_STABLE_FULL_FEATURE_ORDER')
            feature.update(layerOrdinal=ordinal, layerOffset=row['recordOffset'], recordOffset=cursor)
            cursor += feature['recordBytes']
            index += 1
        row['featureEndOffset'] = cursor
        cursor += 7  # One complete feature END per resulting physical layer.
    _need(index == len(plan['features']), 'ALL_FEATURE_ROWS_EXACTLY_ONCE')
    _integer(cursor, 0, 0xffffffff, 'class-mask U32 extent')
    _need(cursor <= max_output_bytes, 'OUTPUT_BUDGET_EXCEEDED')
    plan.update(format='no5-own-plain-class-mask-batch-plan/1', totalBytes=cursor,
                outputBudgetBytes=max_output_bytes, layers=layers, layerEndOffset=layer_end,
                physicalLayerPolicy=POLICY, legacyValidationPlanSHA256=plan_sha256(parent),
                nativeAcceptance='MIXED_CLASS_MASK_NATIVE_BUCKET_READER_PAINT_PENDING',
                legacyGenericPresenceLayerChanged=False)
    # The vector bank precedes the changed directory; every existing absolute
    # stream pointer/vertex stream and every full attribute payload stays exact.
    return plan


def write_class_mask_batch(spec, sink, *, expected_plan_sha256, max_output_bytes=4096):
    plan = plan_class_mask_batch(spec, max_output_bytes=max_output_bytes)
    digest = plan_sha256(plan)
    _need(type(expected_plan_sha256) is str and expected_plan_sha256 == digest,
          'EXACT_EXPECTED_PLAN_SHA256_REQUIRED')
    if not callable(getattr(sink, 'write', None)):
        raise TypeError('sink must provide write(bytes)->int')
    sha = hashlib.sha256()
    written = 0
    # The unchanged normal serializer already writes a variable class/mask row
    # list and each feature's genuine class, range, attribute length and values.
    for chunk in generic._chunks(spec, plan):
        at = 0
        while at < len(chunk):
            n = sink.write(chunk[at:])
            if type(n) is not int or not 0 < n <= len(chunk)-at:
                raise OSError('SINK_WRITE_DID_NOT_MAKE_VALID_PROGRESS')
            sha.update(chunk[at:at+n]); written += n; at += n
    _need(written == plan['totalBytes'], 'EMISSION_EXTENT_DIFFERS_FROM_PLAN')
    return dict(format='no5-own-plain-class-mask-batch-emission/1', bytesWritten=written,
                emittedStreamSHA256=sha.hexdigest(), planSHA256=digest, plan=plan,
                nativeReaderExecuted=False, persistedFileReadback=False)


def class_mask_batch_from_spec(value, *, policy=None):
    _need(type(value) is dict and value.get('format') == FORMAT and policy == POLICY and
          value.get('physical_layer_policy') == POLICY, 'EXPLICIT_CLASS_MASK_SPEC_AND_POLICY_REQUIRED')
    _need(set(value)-{'road_authoring_policy', 'physical_layer_policy'} ==
          {'format', 'header', 'layers', 'date_text_1', 'date_text_2'}, 'EXACT_CLASS_MASK_BATCH_FIELDS')
    _need(type(value['layers']) is list and value['layers'], 'NONEMPTY_PHYSICAL_LAYER_LIST')
    singleton = deepcopy(value)
    singleton['format'] = 'no5-own-plain-generic-attribute-batch/1'
    singleton.pop('physical_layer_policy')
    singleton['layers'] = []
    counts = []
    for layer in value['layers']:
        _need(type(layer) is dict and set(layer) == {'layer_id', 'layer_range', 'features'} and
              type(layer['features']) is list and layer['features'], 'EXACT_COMPLETE_PHYSICAL_LAYER_FIELDS')
        counts.append(len(layer['features']))
        for f in layer['features']:
            singleton['layers'].append(dict(layer_id=layer['layer_id'], layer_range=layer['layer_range'], features=[deepcopy(f)]))
    parsed = generic.generic_batch_from_spec(singleton)
    layers = []
    at = 0
    for layer, count in zip(value['layers'], counts):
        features = tuple(l.features[0] for l in parsed.layers[at:at+count])
        layers.append(ClassMaskLayer(layer['layer_id'], features, layer['layer_range']))
        at += count
    return ClassMaskBatchSpec(parsed.header, layers, parsed.date_text_1, parsed.date_text_2, policy)


def coalesce_legacy_spec(spec, *, policy=None):
    """Explicit stable compatibility join; refusal never pages/reorders maps.

    Returns a candidate typed spec and an external inverse layer-origin ledger.
    Equal layer IDs must be contiguous and have exactly equal layer ranges.
    """
    _need(policy == POLICY and type(spec) is generic.GenericAttributedBatchSpec,
          'EXACT_LEGACY_GENERIC_SPEC_AND_OPTIN_REQUIRED')
    layers, origins, seen = [], [], set()
    for ordinal, source in enumerate(spec.layers):
        if not layers or layers[-1].layer_id != source.layer_id:
            _need(source.layer_id not in seen,
                  'NONCONTIGUOUS_DUPLICATE_LAYER_ID_REQUIRES_SEPARATE_MAP')
            seen.add(source.layer_id)
            layers.append(ClassMaskLayer(source.layer_id, source.features, source.layer_range))
            start = 0
        else:
            before = layers[-1]
            _need(before.layer_range == source.layer_range,
                  'COMPLETE_LAYER_RANGE_CONFLICT_REQUIRES_SEPARATE_MAP')
            start = len(before.features)
            layers[-1] = ClassMaskLayer(before.layer_id, before.features+source.features, before.layer_range)
        origins.append(dict(inputLayerOrdinal=ordinal, outputLayerOrdinal=len(layers)-1,
                            firstFeatureOrdinal=start, featureCount=len(source.features),
                            layerId=source.layer_id, layerRange=list(source.layer_range)))
    candidate = ClassMaskBatchSpec(spec.header, layers, spec.date_text_1, spec.date_text_2, policy)
    ledger = dict(format='no5-class-mask-layer-coalescing-origins/1', policy=POLICY,
                  originalLegacyPlanSHA256=plan_sha256(generic.plan_generic_batch(spec, max_output_bytes=generic.MAX_GENERIC_BYTES)),
                  orderedSourceIdsSHA256=plan_sha256([f.source_id for l in spec.layers for f in l.features]),
                  layers=origins, originalPhysicalLayers=len(spec.layers), candidatePhysicalLayers=len(layers),
                  NativeBucketAndPaintQualified=False)
    _need(restore_legacy_spec(candidate, ledger) == spec, 'FULL_TYPED_LAYER_ATTRIBUTE_GEOMETRY_RANGE_INVERSE')
    return candidate, ledger


def restore_legacy_spec(spec, ledger):
    _need(type(spec) is ClassMaskBatchSpec and type(ledger) is dict and
          ledger.get('format') == 'no5-class-mask-layer-coalescing-origins/1' and
          ledger.get('policy') == POLICY, 'EXACT_INVERSE_ORIGIN_SCHEMA')
    _need(type(ledger.get('layers')) is list and
          type(ledger.get('originalPhysicalLayers')) is int and ledger['originalPhysicalLayers'] == len(ledger['layers']) and
          type(ledger.get('candidatePhysicalLayers')) is int and ledger['candidatePhysicalLayers'] == len(spec.layers) and
          ledger.get('NativeBucketAndPaintQualified') is False, 'EXACT_INVERSE_SCOPE_AND_NATIVE_PENDING')
    layers = []
    for ordinal, row in enumerate(ledger['layers']):
        _need(type(row['inputLayerOrdinal']) is int and row['inputLayerOrdinal'] == ordinal and
              type(row['outputLayerOrdinal']) is int and 0 <= row['outputLayerOrdinal'] < len(spec.layers) and
              type(row['firstFeatureOrdinal']) is int and row['firstFeatureOrdinal'] >= 0 and
              type(row['featureCount']) is int and row['featureCount'] > 0, 'STRICT_ORDERED_TYPED_LAYER_ORIGINS')
        parent = spec.layers[row['outputLayerOrdinal']]
        start, count = row['firstFeatureOrdinal'], row['featureCount']
        _need(start+count <= len(parent.features) and type(row['layerId']) is int and row['layerId'] == parent.layer_id and
              type(row['layerRange']) is list and len(row['layerRange']) == 2 and all(type(v) is int for v in row['layerRange']) and
              tuple(row['layerRange']) == parent.layer_range, 'EXACT_ORIGINAL_LAYER_SCOPE')
        layers.append(generic.GenericPresenceLayer(parent.layer_id, parent.features[start:start+count], parent.layer_range))
    restored = generic.GenericAttributedBatchSpec(spec.header, layers, spec.date_text_1, spec.date_text_2)
    _need(plan_sha256(generic.plan_generic_batch(restored, max_output_bytes=generic.MAX_GENERIC_BYTES)) ==
          ledger['originalLegacyPlanSHA256'], 'EXACT_ORIGINAL_LEGACY_PLAN_INVERSE')
    _need(plan_sha256([f.source_id for l in restored.layers for f in l.features]) == ledger['orderedSourceIdsSHA256'],
          'EXACT_ORDERED_SOURCE_IDENTITIES_INVERSE')
    return restored
