"""Pure exact-header/style/range grouping, without geometry union or emission."""
from copy import deepcopy
import hashlib
import json

from no_chart_converter.navionics.atlas import atlas_from_spec, atlas_work_profile_from_spec
from no_chart_converter.navionics.atlas import atlas_2048_work_profile_from_spec

POLICY = 'EXACT_LOCAL_HEADER_DATES_LAYER_STYLE_AND_RANGE_GROUPING/1'
PROFILE256 = 'own_atlas256_host_work_profile_v1'
PROFILE2048 = 'own_atlas2048_host_work_profile_v1'


def need(ok, why):
    if not ok:
        raise ValueError(why)


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(',', ':'),
                      ensure_ascii=False, allow_nan=False).encode()


def digest(value):
    return hashlib.sha256(canonical(value)).hexdigest()


def public_spec(document):
    return (atlas_work_profile_from_spec(document) if 'work_profile' in document
            else atlas_from_spec(document))


def public_spec_2048(document):
    return (atlas_2048_work_profile_from_spec(document)
            if document.get('work_profile') == PROFILE2048 else public_spec(document))


def group_exact_maps(document, ordered_units, source_binding, *, policy,
                     expected_document_sha256, expected_bindings_sha256,
                     max_maps=16, max_features_per_group=63, work_profile=None):
    """Group complete one-feature maps only when every nongeometry role agrees.

    Whole parts, their order/winding, per-feature attributes and fragment IDs
    are copied exactly. No CO/U16 transform, interpolation or hole repair occurs.
    The feature work cap refuses an oversized same-header group; it never splits
    that group back into competing maps. Caps are caller work limits only.
    """
    need(policy == POLICY and type(policy) is str, 'EXPLICIT_GROUPING_POLICY')
    need(work_profile is None or (type(work_profile) is str and work_profile == PROFILE2048),
         'EXPLICIT_GROUPING_2048_WORK_PROFILE')
    input_cap = 2048 if work_profile == PROFILE2048 else 256
    parser = public_spec_2048 if work_profile == PROFILE2048 else public_spec
    need(type(max_maps) is int and 1 <= max_maps <= input_cap and
         type(max_features_per_group) is int and 1 <= max_features_per_group <= 63,
         'EXPLICIT_GROUPING_WORK_CAPS')
    need(type(expected_document_sha256) is str and digest(document) == expected_document_sha256,
         'BOUND_DIAGNOSTIC_DOCUMENT')
    need(type(source_binding) is dict and bool(source_binding), 'BOUND_ORIGINAL_SOURCE_LEDGER_REQUIRED')
    need(type(expected_bindings_sha256) is str and digest(dict(orderedUnits=ordered_units,
        sourceBinding=source_binding)) == expected_bindings_sha256, 'PINNED_COMPLETE_ORIGINAL_AND_UNIT_BINDINGS')
    # The public schema parser validates every existing raw/attribute/header
    # field, but no plan_atlas/write_atlas or geometry kernel is called.
    parser(document)
    maps = document['maps']
    need(type(ordered_units) is list and len(maps) == len(ordered_units) and
         1 <= len(maps) <= input_cap, 'COMPLETE_ORDERED_UNIT_LIST')
    groups, group_at, locations, ids = [], {}, [], set()
    keys = {'unitOrdinal', 'mapOrdinal', 'fragmentSourceId', 'savedDocumentSHA256',
        'savedFinalAtlasPlanSHA256', 'geometrySHA256', 'attributesSHA256', 'headerSHA256',
        'partVertexCounts', 'historicalNativeRange', 'authoredNativeRange'}
    for i, (m, binding) in enumerate(zip(maps, ordered_units)):
        need(type(binding) is dict and set(binding) == keys and
             type(binding['unitOrdinal']) is int and type(binding['mapOrdinal']) is int and
             binding['unitOrdinal'] == binding['mapOrdinal'] == i, 'EXACT_ORDERED_UNIT_BINDINGS')
        need(all(type(binding[k]) is str and len(binding[k]) == 64 and
                 all(c in '0123456789abcdef' for c in binding[k]) for k in keys if k.endswith('SHA256')),
             'EXACT_UNIT_HASH_DOMAINS')
        need(len(m['layers']) == 1 and len(m['layers'][0]['features']) == 1,
             'ONE_WHOLE_AREA_FEATURE_PER_INPUT_MAP')
        layer = m['layers'][0]; feature = layer['features'][0]
        sid = feature['source_id']
        need(type(sid) is str and sid == binding['fragmentSourceId'] and sid not in ids and
             feature['kind'] == 24, 'UNIQUE_WHOLE_AREA_FRAGMENT_ID')
        ids.add(sid)
        need(digest(m['header']) == binding['headerSHA256'] and
             digest(feature['parts']) == binding['geometrySHA256'] and
             digest(feature['attributes']) == binding['attributesSHA256'] and
             [len(r) for r in feature['parts']] == binding['partVertexCounts'] and
             feature.get('native_level_range') == binding['authoredNativeRange'],
             'ALL_UNIT_RAW_HEADER_ATTRIBUTES_AND_RANGE_EXACT')
        historical = deepcopy(m)
        if binding['historicalNativeRange'] is None:
            if work_profile == PROFILE2048:
                historical['layers'][0]['features'][0].pop('native_level_range', None)
            else:
                historical['layers'][0]['features'][0].pop('native_level_range')
        else:
            historical['layers'][0]['features'][0]['native_level_range'] = deepcopy(binding['historicalNativeRange'])
        need(digest(dict(format='no5-own-plain-atlas/1', maps=[historical])) == binding['savedDocumentSHA256'],
             'WHOLE_SAVED_DOCUMENT_COMPLEMENT_EXACT')
        # Every nongeometry feature field participates, including full attrs
        # and authored native range. Any future unsupported style is separate.
        key = deepcopy(m)
        key['layers'][0]['features'] = [{k: deepcopy(v) for k, v in feature.items()
                                       if k not in ('source_id', 'parts')}]
        key = canonical(key)
        if key not in group_at:
            group_at[key] = len(groups)
            new = deepcopy(m); new['layers'][0]['features'] = []
            groups.append(new)
        group = group_at[key]; features = groups[group]['layers'][0]['features']
        need(len(features) < max_features_per_group, 'SAME_HEADER_GROUP_EXCEEDS_FEATURE_WORK_CAP')
        ordinal = len(features); features.append(deepcopy(feature))
        locations.append(dict(unitOrdinal=i, fragmentSourceId=sid, oldMapOrdinal=i,
            newMapOrdinal=group, newLayerOrdinal=0, newFeatureOrdinal=ordinal,
            rawGeometrySHA256=binding['geometrySHA256'], attributesSHA256=binding['attributesSHA256'],
            headerSHA256=binding['headerSHA256'], savedFinalAtlasPlanSHA256=binding['savedFinalAtlasPlanSHA256']))
    need(len(groups) <= max_maps, 'EXPLICIT_GROUPED_MAP_WORK_CAP')
    out = dict(format='no5-own-plain-atlas/1', maps=groups)
    if work_profile == PROFILE2048:
        out.update(format='no5-own-plain-atlas2048-work-profile/1', work_profile=PROFILE2048)
    elif max_maps > 16:
        out.update(format='no5-own-plain-atlas-work-profile/1', work_profile=PROFILE256)
    parser(out)
    all_features = [f for m in out['maps'] for l in m['layers'] for f in l['features']]
    need(len(all_features) == len(ids) == len(maps) and
         {f['source_id'] for f in all_features} == ids, 'ALL_FEATURES_AND_IDS_RETAINED_BIJECTIVELY')
    return dict(document=out, ledger=dict(format='no5-exact-fragment-header-grouping-ledger/1',
        policy=policy, inputDocumentSHA256=expected_document_sha256, outputDocumentSHA256=digest(out),
        sourceBinding=deepcopy(source_binding), inputUnitBindings=deepcopy(ordered_units),
        inputBindingsSHA256=expected_bindings_sha256,
        unitLocations=locations, inputMaps=len(maps), groupedMaps=len(groups), features=len(ids),
        maxFeaturesInMap=max(len(m['layers'][0]['features']) for m in groups),
        partCount=sum(len(f['parts']) for f in all_features),
        rawPointOccurrences=sum(len(r) for f in all_features for r in f['parts']),
        nativeAttributeCopies=sum(len(f['attributes']) for f in all_features),
        geometryUnionsOrReencodes=0, explicitMapWorkCap=max_maps,
        explicitFeatureWorkCap=max_features_per_group, defaultWriterChanged=False,
        futureGlobalPlanAndExactEmissionRequired=True, NativeSelectionOrPortrayalQualified=False))


def group_exact_maps_2048(document, ordered_units, source_binding, *, policy,
                          expected_document_sha256, expected_bindings_sha256,
                          max_maps=2048, max_features_per_group=63):
    """Explicit larger host work cap; identical exact-map grouping algorithm."""
    return group_exact_maps(document, ordered_units, source_binding, policy=policy,
        expected_document_sha256=expected_document_sha256,
        expected_bindings_sha256=expected_bindings_sha256, max_maps=max_maps,
        max_features_per_group=max_features_per_group, work_profile=PROFILE2048)
