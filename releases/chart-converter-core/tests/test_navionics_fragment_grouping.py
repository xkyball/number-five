"""Invented maps only; no real owner planning, emission or native process."""
from copy import deepcopy
import unittest
from no_chart_converter.navionics import fragment_grouping as api
from no_chart_converter.navionics.atlas import plan_atlas


def fixture(header_groups=(0, 0, 1), ranges=None, values=None):
    maps, bindings = [], []
    for i, group in enumerate(header_groups):
        feature = dict(source_id='INVENTED:fragment' + str(i), class_code=41, kind=24,
            parts=[[[0,0],[100,0],[100,100],[0,100],[0,0]],
                   [[25,25],[25,75],[75,75],[75,25],[25,25]]],
            attributes=[dict(native_code_u16=82, raw_integer=0, declared_native_units=2),
                        dict(native_code_u16=83, raw_integer=300 if values is None else values[i], declared_native_units=2)])
        m = dict(format='no5-own-plain-generic-attribute-batch/1', date_text_1='20261018', date_text_2='20261018',
            header=dict(flags=0x1234, raw_bounds=[group*20,0,group*20+20,20], raw_grid=[101,101],
                        raw_levels=[0,15], trailing_value=0),
            layers=[dict(layer_id=38, layer_range=[0,100000], features=[feature])])
        original = deepcopy(m)
        feature['native_level_range'] = dict(min_level=0, max_level=15 if ranges is None else ranges[i])
        bindings.append(dict(unitOrdinal=i, mapOrdinal=i, fragmentSourceId=feature['source_id'],
            savedDocumentSHA256=api.digest(dict(format='no5-own-plain-atlas/1',maps=[original])),
            savedFinalAtlasPlanSHA256='1'*64, geometrySHA256=api.digest(feature['parts']),
            attributesSHA256=api.digest(feature['attributes']), headerSHA256=api.digest(m['header']),
            partVertexCounts=[5,5], historicalNativeRange=None,
            authoredNativeRange=deepcopy(feature['native_level_range'])))
        maps.append(m)
    return dict(format='no5-own-plain-atlas/1', maps=maps), bindings


def group(document, rows, **kwargs):
    source = dict(originalAttributesSHA256='2'*64)
    return api.group_exact_maps(document, rows, source,
        policy=api.POLICY, expected_document_sha256=api.digest(document),
        expected_bindings_sha256=api.digest(dict(orderedUnits=rows, sourceBinding=source)), **kwargs)


class Controls(unittest.TestCase):
    def test_same_header_group_multiple_features_ids_holes_attrs_exact(self):
        document, rows = fixture(); before = deepcopy((document, rows))
        result = group(document, rows)
        self.assertEqual((document, rows), before)
        self.assertEqual(len(result['document']['maps']), 2)
        self.assertEqual([len(m['layers'][0]['features']) for m in result['document']['maps']], [2,1])
        original = {f['source_id']: f for m in document['maps'] for f in m['layers'][0]['features']}
        output = {f['source_id']: f for m in result['document']['maps'] for f in m['layers'][0]['features']}
        self.assertEqual(output, original)
        self.assertEqual([(r['newMapOrdinal'],r['newFeatureOrdinal']) for r in result['ledger']['unitLocations']], [(0,0),(0,1),(1,0)])
        self.assertEqual(result['ledger']['partCount'], 6)
        self.assertEqual(result['ledger']['rawPointOccurrences'], 30)
        self.assertEqual(result['ledger']['nativeAttributeCopies'], 6)
        self.assertEqual(result['ledger']['inputUnitBindings'], rows)

    def test_range_or_style_difference_never_groups(self):
        document, rows = fixture((0,0,0), ranges=(15,0,15), values=(300,300,301))
        result = group(document, rows)
        self.assertEqual(len(result['document']['maps']), 3)

    def test_missing_duplicate_tampered_units_refuse(self):
        document, rows = fixture()
        with self.assertRaisesRegex(ValueError, 'UNIT_LIST'): group(document, rows[:-1])
        bad = deepcopy(rows); bad[1]['fragmentSourceId'] = bad[0]['fragmentSourceId']
        with self.assertRaisesRegex(ValueError, 'FRAGMENT_ID'): group(document, bad)
        bad_doc = deepcopy(document); bad_doc['maps'][1]['layers'][0]['features'][0]['parts'][1][1][0] += 1
        with self.assertRaisesRegex(ValueError, 'RAW_HEADER'): group(bad_doc, rows)

    def test_explicit_work_caps_refuse_instead_of_splitting_competing_maps(self):
        document, rows = fixture()
        with self.assertRaisesRegex(ValueError, 'FEATURE_WORK_CAP'): group(document, rows, max_features_per_group=1)
        with self.assertRaisesRegex(ValueError, 'WORK_CAPS'): group(document, rows, max_maps=True)
        document, rows = fixture(tuple(range(17)))
        with self.assertRaises(ValueError): group(document, rows)
        document.update(format='no5-own-plain-atlas-work-profile/1', work_profile=api.PROFILE256)
        with self.assertRaisesRegex(ValueError, 'MAP_WORK_CAP'): group(document, rows)
        self.assertEqual(group(document, rows, max_maps=256)['ledger']['groupedMaps'], 17)
        # The input itself is ordinary17-map JSON, so opt in explicitly before
        # invoking the public parser; no legacy16 parser mutation is permitted.

    def test_invented194_to166_ordinary_multifeature_writer_plan(self):
        # Invented repetition only: 21 duplicate groups, 28 excess maps, max4.
        headers = tuple(range(166)) + (0,)*3 + tuple(range(1,6))*2 + tuple(range(6,21))
        document, rows = fixture(headers)
        document.update(format='no5-own-plain-atlas-work-profile/1', work_profile=api.PROFILE256)
        result = group(document, rows, max_maps=256)
        ledger = result['ledger']
        self.assertEqual((ledger['inputMaps'], ledger['groupedMaps'], ledger['features'],
                          ledger['maxFeaturesInMap']), (194,166,194,4))
        plan = plan_atlas(api.public_spec(result['document']), max_output_bytes=1024*1024)
        self.assertEqual(plan['mapCount'], 166)
        planned = [f for m in plan['maps'] for f in m['bodyPlan']['features']]
        original = {f['source_id']: f for m in document['maps'] for f in m['layers'][0]['features']}
        self.assertEqual({f['sourceId'] for f in planned}, set(original))
        self.assertEqual(len(planned), 194)
        self.assertEqual(len({f['recordOffset'] for f in planned}), 194)
        for f in planned:
            self.assertEqual(f['partCount'], 2)
            self.assertEqual([p['pointCount'] for p in f['parts']], [5,5])
            self.assertEqual([(a['native_code_u16'],a['wire_i32'],a['declared_native_units'])
                              for a in f['attributes']], [(82,0,2),(83,300,2)])


if __name__ == '__main__':
    unittest.main()
