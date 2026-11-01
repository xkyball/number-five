"""Explicit missing-category ROADWY bridge into the existing regional writer.

This authoring premise is not native ROADWY identity, portrayal, query or
SCAMIN qualification. Original unsupported outcomes and attributes stay visible.
"""
from copy import deepcopy

from no_chart_converter.navionics.road_authoring_registry import ROAD_UNCATEGORIZED_POLICY
from no_chart_converter.navionics.road_uncategorized import prepare_uncategorized_road
from no_chart_converter.navionics.generic_batch import (
    GenericAttributedBatchSpec, generic_batch_from_spec,
)


def need(ok, label):
    if not ok:
        raise ValueError(label)


def require_policy(policy):
    need(type(policy) is str and policy == ROAD_UNCATEGORIZED_POLICY,
         'EXPLICIT_UNCATEGORIZED_ROAD_POLICY_REQUIRED')


def append_source_atoms(cell, outcomes, atoms, *, semantic, policy):
    """Append complete original F64 lines before the SAME shared-grid pass.

    No existing geometry, category, native attribute or unsupported reason is
    replaced. Only absent-CATROD, not-yet-authored line owners are candidates;
    categorized roads retain their existing outcomes. The normal source recipe
    still validates exact ROADWY116/Line/catgeo2. ROADWY areas stay unsupported.
    """
    require_policy(policy)
    need(len(cell['features']) == len(outcomes), 'COMPLETE_ORIGINAL_OWNER_INVENTORY')
    existing = {a['source_id'] for a in atoms}
    added, owners = [], []
    for feature, outcome in zip(cell['features'], outcomes, strict=True):
        if feature.get('classAcronym') != 'ROADWY' or feature.get('geometry', {}).get('kind') != 'line':
            continue
        sid = feature['sourceId']
        need(outcome['sourceId'] == sid, 'SAME_ORDERED_SOURCE_OWNER')
        if sid in existing:
            continue
        attrs = feature.get('attributes')
        if type(attrs) is list and any(type(a) is dict and
                (a.get('code') == 57 or a.get('acronym') == 'CATROD') for a in attrs):
            continue
        recipe = prepare_uncategorized_road(feature, policy=policy)
        parts = semantic._parts(feature)
        projected = [semantic.geo.forward_model(p)['formulaF64XY']
                     for part in parts for p in part]
        added.append(dict(parent=outcome, source_id=sid, native_class=208,
            layer=121, kind=32, name='ROADWY', mode='generic', attributes=[],
            omitted=[], structuralAttributes=[], depth=None, sample=None,
            parts=parts, projected=projected,
            part_metadata=[{} for _ in parts], polygon_roles=[]))
        existing.add(sid)
        owners.append(dict(sourceId=sid, recipe=recipe,
            originalUnsupported=deepcopy(outcome['unsupported']),
            originalSourceAttributeCount=len(feature['attributes']),
            originalSourcePartCount=len(parts),
            originalSourceVertexCount=sum(map(len, parts)),
            sourceProjection='ORIGINAL_IR_F64_TO_EXISTING_FORMULA_F64_SHARED_GRID',
            nativeClass=208, nativeLayerId=121, nativeKind=32,
            nativeAttributes=[], nativeCategory='ABSENT_NOT_MAJOR2',
            nativeClassIdentityQualified=False, sourceAttributeNativeParity=False,
            sourceSCAMINParity=False, sourceOwnerPromotion=False))
    return atoms + added, dict(policy=policy, owners=owners,
        originalUnsupportedOutcomesRetained=True,
        originalTypedAttributesRetained=True, sharedGridBeforeClipping=True,
        headerUnion=False, sourceOwnerDeduplication=False,
        sourceSCAMINParity=False, nativeQualification=False, Native=0, Original=0)


def mixed_complete_tile_spec(value, *, approved_road_emitted_ids, policy):
    """Qualify only approved208 layers using the EXISTING public road parser.

    Its top-level road policy would otherwise also apply to ordinary classes.
    Every physical layer is parsed under its proper existing policy and then
    assembled as typed layers under the one unchanged complete-tile header.
    No old byte/header/map union or second geometry engine is involved.
    """
    require_policy(policy)
    need(type(approved_road_emitted_ids) in (list, tuple) and
         all(type(s) is str and s for s in approved_road_emitted_ids) and
         len(set(approved_road_emitted_ids)) == len(approved_road_emitted_ids),
         'EXACT_EMITTED_ROAD_OWNER_LEDGER_IDS')
    need(type(value) is dict and set(value) ==
         {'format', 'header', 'layers', 'date_text_1', 'date_text_2'} and
         value['format'] == 'no5-own-plain-generic-attribute-batch/1' and
         type(value['layers']) is list and value['layers'],
         'UNCHANGED_COMPLETE_GENERIC_TILE_SCHEMA')
    approved, seen, layers, first = set(approved_road_emitted_ids), [], [], None
    for layer in value['layers']:
        need(type(layer) is dict and type(layer.get('features')) is list and
             layer['features'], 'EXPLICIT_NONEMPTY_PHYSICAL_LAYER')
        road = [f for f in layer['features'] if f.get('class_code') == 208]
        one = dict(value, layers=[deepcopy(layer)])
        if road:
            need(len(road) == len(layer['features']) and layer.get('layer_id') == 121,
                 'CLASS208_ONLY_IN_OWN_LINE121_LAYER')
            for feature in road:
                need(feature.get('kind') == 32 and feature.get('attributes') == [] and
                     feature.get('source_id') in approved,
                     'ONLY_APPROVED_ABSENT_CATEGORY_ROAD_LINE')
                seen.append(feature['source_id'])
            one['road_authoring_policy'] = policy
        parsed = generic_batch_from_spec(one)
        if first is None:
            first = parsed
        need(parsed.header == first.header and parsed.date_text_1 == first.date_text_1 and
             parsed.date_text_2 == first.date_text_2,
             'ONE_SHARED_UNCHANGED_TILE_HEADER_AND_DATES')
        layers.extend(parsed.layers)
    need(len(seen) == len(set(seen)) and set(seen) == approved,
         'EVERY_APPROVED_ROAD_EMISSION_EXACTLY_ONCE')
    return GenericAttributedBatchSpec(first.header, tuple(layers),
                                      first.date_text_1, first.date_text_2)
