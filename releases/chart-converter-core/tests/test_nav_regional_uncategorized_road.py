"""Invented IR only: explicit road policy, unchanged defaults, shared tiles.

No licensed input, OEM code, device, decoder or native execution is used.
"""
from copy import deepcopy
import hashlib
import json
import unittest

from no_chart_converter.navionics.regional_source import compile_region
from no_chart_converter.navionics.regional_source import adapter
from no_chart_converter.navionics.regional_source.road_integration import append_source_atoms, mixed_complete_tile_spec
from no_chart_converter.navionics.regional_source import semantic_authoring
from no_chart_converter.navionics.road_authoring_registry import ROAD_UNCATEGORIZED_POLICY
from no_chart_converter.navionics.road_uncategorized import prepare_uncategorized_road
from test_nav_regional_retained_ir import own_cell, request


def road_cell(*, metadata=False):
    cell = own_cell()
    line = deepcopy(cell['features'][1])
    line.update(sourceId='own:road', classCode=116, classAcronym='ROADWY', sourcePrimitive=1,
        sourceRecord=dict(offset=120, length=11),
        attributes=[dict(code=50000, acronym='catgeo', type='uint32', value=2),
                    dict(code=133, acronym='SCAMIN', type='uint32', value=89999),
                    dict(code=102, acronym='OBJNAM', type='string', value='Own invented road')],
        displayScale=dict(SCAMIN=89999))
    cell['features'].append(line)
    if metadata:
        area = deepcopy(cell['features'][0])
        area.update(sourceId='own:metadata', classCode=310, classAcronym='M_SREL', sourcePrimitive=2,
            attributes=[dict(code=150, acronym='SURATH', type='string', value='Own invented authority')])
        cell['features'].append(area)
    return cell


def opted_request():
    value = request()
    value.update(road_authoring_policy=ROAD_UNCATEGORIZED_POLICY,
                 unsupported_owner_policy='retain_diagnostic_gaps')
    return value


def compile_own(cell=None, value=None):
    return compile_region(road_cell() if cell is None else cell,
        opted_request() if value is None else value, maximum_plain_bytes=1 << 20)


def road_occurrences(result):
    return result['roadAuthoring']['emittedOccurrences']


class ExplicitRegionalRoad(unittest.TestCase):
    def test_legacy_default_bytes_and_complete_result_are_exact(self):
        # Captured before the additive bridge from the existing invented fixture.
        value = compile_own(own_cell(), request())
        self.assertNotIn('roadAuthoring', value)
        self.assertEqual(value['plainSHA256'],
            '4852c6a9ec7f37397199961ef4a0cd51c253c6619e27b5bde284573fe52fe987')
        value.pop('plain')
        raw = json.dumps(value, sort_keys=True, separators=(',', ':'),
                         ensure_ascii=False, allow_nan=False).encode()
        self.assertEqual(hashlib.sha256(raw).hexdigest(),
            '9c74f129aa9f93df90e7fbd0d44212824f8f21ed6bc897fd770208c7232d0de4')

    def test_default_does_not_author_road_or_infer_category(self):
        value = request()
        value['unsupported_owner_policy'] = 'retain_diagnostic_gaps'
        result = compile_own(value=value)
        self.assertNotIn('roadAuthoring', result)
        self.assertFalse(result['regionalGaps'][0]['nativeEmission'])
        self.assertNotIn('own:road', {r['originalOwnerSourceId']
            for r in result['geometryLedger']['featureLedger']})

    def test_optin_mixed_tile_preserves_owners_attributes_and_open_gaps(self):
        cell, value = road_cell(metadata=True), opted_request()
        before_cell, before_value = deepcopy(cell), deepcopy(value)
        result = compile_own(cell, value)
        self.assertEqual(cell, before_cell)
        self.assertEqual(value, before_value)
        self.assertEqual(len(result['document']['maps']), 1)
        self.assertEqual({r['kind'] for r in result['geometryLedger']['featureLedger']}, {24, 32, 40, 44})
        record = result['roadAuthoring']['owners'][0]
        self.assertEqual([a['sourceAttribute'] for a in record['recipe']['sourceAttributeLedger']],
                         cell['features'][-2]['attributes'])
        self.assertEqual(record['recipe']['sourceDisplayScale'], dict(SCAMIN=89999))
        self.assertEqual(record['nativeCategory'], 'ABSENT_NOT_MAJOR2')
        self.assertFalse(record['sourceSCAMINParity'])
        self.assertFalse(record['sourceOwnerPromotion'])
        self.assertEqual(record['originalSourceVertexCount'], 2)
        self.assertTrue(record['originalUnsupported'])
        outcomes = {r['sourceId']: r for r in result['inventory']['allOriginalOwnerOutcomes']}
        self.assertEqual(outcomes['own:road']['unsupported'], record['originalUnsupported'])
        gaps = {r['sourceId']: r for r in result['regionalGaps']}
        self.assertEqual(set(gaps), {'own:road', 'own:metadata'})
        self.assertTrue(gaps['own:road']['nativeEmission'])
        self.assertFalse(gaps['own:road']['nativeExecution'])
        self.assertFalse(gaps['own:metadata']['nativeEmission'])
        self.assertFalse(result['nativeProductAcceptance'])
        self.assertFalse(result['sourceSCAMINParity'])
        self.assertEqual(result['inventory']['sourceMappedOriginalOwners'], 4)
        self.assertEqual(result['inventory']['sourcePolicyAuthoredOriginalOwners'], 1)
        scale = next(r for r in result['sourceDisplayScaleLedger'] if r['sourceId'] == 'own:road')
        self.assertEqual(scale['sourceSCAMINAttributes'][0]['value'], 89999)
        self.assertFalse(scale['nativeConversionQualified'])

    def test_strict_gaps_still_refuse_unqualified_semantics(self):
        value = opted_request()
        value['unsupported_owner_policy'] = 'refuse_region_gaps'
        with self.assertRaisesRegex(ValueError, 'SOURCE_REGION_HAS_UNREPRESENTED_OWNERS:own:road'):
            compile_own(value=value)

    def test_single_road_recipe_still_rejects_present_catrod(self):
        for category in (0, 1, 2, 3, 4, 6, 255, None, '2'):
            cell = road_cell()
            cell['features'][-1]['attributes'].append(
                dict(code=57, acronym='CATROD', type='uint32', value=category))
            with self.subTest(category=category), self.assertRaisesRegex(ValueError, 'CATROD_MUST_BE_ABSENT'):
                prepare_uncategorized_road(cell['features'][-1], policy=ROAD_UNCATEGORIZED_POLICY)

    def test_categorized_other_road_remains_gap_without_blocking_candidate(self):
        cell = road_cell()
        other = deepcopy(cell['features'][-1])
        other['sourceId'] = 'own:categorized-road'
        other['attributes'].append(dict(code=57, acronym='CATROD', type='uint32', value=2))
        cell['features'].append(other)
        before = deepcopy(cell)
        result = compile_own(cell)
        self.assertEqual(cell, before)
        self.assertEqual([r['sourceId'] for r in result['roadAuthoring']['owners']], ['own:road'])
        self.assertTrue(all(r['originalOwnerSourceId'] == 'own:road' for r in road_occurrences(result)))
        gaps = {r['sourceId']: r for r in result['regionalGaps']}
        self.assertEqual(set(gaps), {'own:road', 'own:categorized-road'})
        self.assertTrue(gaps['own:road']['nativeEmission'])
        self.assertFalse(gaps['own:categorized-road']['nativeEmission'])
        self.assertNotIn('explicitRoadAuthoringPolicy', gaps['own:categorized-road'])
        rows = [r for r in result['inventory']['allOriginalAttributeRows']
                if r['sourceId'] == other['sourceId']]
        self.assertEqual([r['sourceAttribute'] for r in rows], other['attributes'])
        strict = opted_request()
        strict['unsupported_owner_policy'] = 'refuse_region_gaps'
        with self.assertRaisesRegex(ValueError, 'SOURCE_REGION_HAS_UNREPRESENTED_OWNERS:own:road,own:categorized-road'):
            compile_own(cell, strict)

    def test_categorized_road_outside_region_cannot_abort_missing_category_candidate(self):
        cell = road_cell()
        other = deepcopy(cell['features'][-1])
        other['sourceId'] = 'own:categorized-outside'
        other['attributes'].append(dict(code=57, acronym='CATROD', type='uint32', value=3))
        other['geometry']['parts'][0]['coordinates'] = [[1, 2], [3, 4]]
        cell['features'].append(other)
        result = compile_own(cell)
        self.assertEqual([r['sourceId'] for r in result['roadAuthoring']['owners']], ['own:road'])
        self.assertEqual([r['sourceId'] for r in result['regionalGaps']], ['own:road'])
        outcome = next(r for r in result['inventory']['allOriginalOwnerOutcomes']
                       if r['sourceId'] == other['sourceId'])
        self.assertTrue(outcome['unsupported'])

    def test_existing_authored_owner_is_not_appended_or_relabelled(self):
        cell = road_cell()
        outcomes, *_ = semantic_authoring.base._inventory(cell)
        atoms, first = append_source_atoms(cell, outcomes, [], semantic=semantic_authoring,
                                          policy=ROAD_UNCATEGORIZED_POLICY)
        before = deepcopy(outcomes)
        again, second = append_source_atoms(cell, outcomes, atoms, semantic=semantic_authoring,
                                             policy=ROAD_UNCATEGORIZED_POLICY)
        self.assertEqual(len(first['owners']), 1)
        self.assertEqual(again, atoms)
        self.assertIs(again[0], atoms[0])
        self.assertEqual(second['owners'], [])
        self.assertEqual(outcomes, before)

    def test_wrong_or_implicit_policy_is_rejected(self):
        for policy in (None, '', True, 'major_road', 2):
            value = opted_request()
            value['road_authoring_policy'] = policy
            with self.subTest(policy=policy), self.assertRaisesRegex(ValueError, 'EXPLICIT_UNCATEGORIZED_ROAD_POLICY'):
                compile_own(value=value)
        value = opted_request()
        value['unrelated_loose_key'] = True
        with self.assertRaisesRegex(ValueError, 'EXACT_REGION_REQUEST'):
            compile_own(value=value)

    def test_wrong_catgeo_or_primitive_is_not_relabelled(self):
        for kind in ('catgeo', 'primitive'):
            cell = road_cell()
            if kind == 'catgeo':
                cell['features'][-1]['attributes'][0]['value'] = 3
            else:
                cell['features'][-1]['sourcePrimitive'] = 2
            with self.subTest(kind=kind), self.assertRaises(ValueError):
                compile_own(cell)

    def test_road_area_remains_unsupported(self):
        cell = own_cell()
        area = deepcopy(cell['features'][0])
        area.update(sourceId='own:road-area', classCode=116, classAcronym='ROADWY', sourcePrimitive=2)
        cell['features'].append(area)
        result = compile_own(cell)
        self.assertEqual(result['roadAuthoring']['owners'], [])
        self.assertEqual(road_occurrences(result), [])
        self.assertEqual(result['regionalGaps'][0]['sourceId'], 'own:road-area')
        self.assertFalse(result['regionalGaps'][0]['nativeEmission'])

    def test_same_complete_header_and_public_empty_attribute_road_plan(self):
        result = compile_own()
        baseline = compile_own(own_cell(), request())
        self.assertEqual(result['document']['maps'][0]['header'], baseline['document']['maps'][0]['header'])
        rows = [f for m in result['plan']['maps'] for f in m['bodyPlan']['features']
                if f['classCode'] == 208]
        self.assertEqual(len(rows), len(road_occurrences(result)))
        self.assertTrue(rows)
        for row in rows:
            self.assertEqual((row['kind'], row['attributeBytes'], row['attributeMask'], row['attributes']),
                             (32, 0, [0, 0], []))
        self.assertFalse(result['roadAuthoring']['headerUnion'])
        self.assertFalse(result['roadAuthoring']['sourceOwnerDeduplication'])

    def test_original_line_crosses_shared_tiles_with_every_owner_fragment(self):
        cell, value = road_cell(), opted_request()
        cell['features'][-1]['geometry']['parts'][0]['coordinates'] = [[.0007, .0012], [.004, .0012]]
        value.update(region_bounds=[0, 0, 512, 256], maximum_maps=2)
        result = compile_own(cell, value)
        occurrences = road_occurrences(result)
        self.assertEqual({x['mapOrdinal'] for x in occurrences}, {0, 1})
        self.assertEqual(len({x['emittedId'] for x in occurrences}), len(occurrences))
        self.assertTrue(all(x['sourceAtomId'] == x['originalOwnerSourceId'] == 'own:road' for x in occurrences))
        proof = next(p for p in result['geometryLedger']['partitionCoverage']
                     if p['originalOwnerSourceId'] == 'own:road')
        self.assertTrue(proof['exactRationalSegmentIntervalPartitionEqual'])
        self.assertEqual(proof['fragmentCount'], len(occurrences))

    def test_approved_emitted_ids_and_no_native_attrs_are_enforced(self):
        result = compile_own()
        value = result['document']['maps'][0]
        ids = [r['emittedId'] for r in road_occurrences(result)]
        before = deepcopy(value)
        spec = mixed_complete_tile_spec(value, approved_road_emitted_ids=ids,
                                        policy=ROAD_UNCATEGORIZED_POLICY)
        self.assertEqual(len(spec.layers), len(value['layers']))
        self.assertEqual(value, before)
        for approved in ([], ids + ['unbound'], ids * 2):
            with self.subTest(approved=approved), self.assertRaises(ValueError):
                mixed_complete_tile_spec(value, approved_road_emitted_ids=approved,
                                         policy=ROAD_UNCATEGORIZED_POLICY)
        altered = deepcopy(value)
        feature = next(f for layer in altered['layers'] for f in layer['features'] if f.get('class_code') == 208)
        feature['attributes'] = [dict(native_code_u16=158, enum_u8=2)]
        with self.assertRaisesRegex(ValueError, 'ONLY_APPROVED_ABSENT_CATEGORY_ROAD_LINE'):
            mixed_complete_tile_spec(altered, approved_road_emitted_ids=ids,
                                     policy=ROAD_UNCATEGORIZED_POLICY)

    def test_work_profile_handles_tiles_without_roads_without_relaxing_budget(self):
        cell, value = road_cell(), opted_request()
        cell['features'][0]['geometry']['rings'] = cell['features'][0]['geometry']['rings'][:1]
        cell['features'][0]['geometry']['polygons'][0]['interiorRingIds'] = []
        value.update(region_bounds=[64, 64, 192, 192], tile_side_source_units=16, maximum_maps=64)
        result = compile_own(cell, value)
        self.assertEqual(len(result['document']['maps']), 64)
        self.assertEqual(result['document']['work_profile'], 'own_atlas256_host_work_profile_v1')
        self.assertLess(len({r['mapOrdinal'] for r in road_occurrences(result)}), 64)
        value['maximum_maps'] = 63
        with self.assertRaisesRegex(ValueError, 'EXPLICIT_COMPLETE_TILE_WORK_BUDGET'):
            compile_own(cell, value)


if __name__ == '__main__':
    unittest.main()
