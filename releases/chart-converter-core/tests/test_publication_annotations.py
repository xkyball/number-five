"""Invented publication sources only; no chart/runtime fixtures."""
import copy
import unittest

from no_chart_converter.navionics.display_control_wire import DisplayControlRow, decode_display_control
from no_chart_converter.navionics.regional_source.publication_annotations import plan_publication_annotations


def feature(source_id='invented:one', attributes=None):
    return dict(sourceId=source_id, classAcronym='M_NPUB', classCode=305,
        geometry=dict(kind='area', rings=[dict(id='outer', role='exterior',
            coordinates=[[0,0], [0,1], [1,1], [0,0]])],
            polygons=[dict(exteriorRingId='outer', interiorRingIds=[])]),
        attributes=attributes if attributes is not None else [dict(acronym='PUBREF', type='utf8', value='TEST PUBLICATION')])


def plan(features, anchors=None, **settings):
    args = dict(enabled=True, min_level=2, max_level=3,
                display_rows=(DisplayControlRow(0, 0x8000, 0, 0),) * 2)
    args.update(settings)
    return plan_publication_annotations(features,
        anchors if anchors is not None else {f['sourceId']: [1, 2] for f in features}, **args)


class PublicationAnnotationTests(unittest.TestCase):
    def test_default_disabled_is_noop(self):
        result = plan_publication_annotations(object())
        self.assertFalse(result.enabled)
        self.assertEqual(result.annotations, ())
        self.assertEqual(result.retained_sources, ())

    def test_enabled_is_explicit_bool(self):
        for value in (1, 0, None, 'yes'):
            with self.assertRaises(ValueError):
                plan_publication_annotations([], enabled=value)

    def test_no_implicit_feature_range(self):
        with self.assertRaises(ValueError):
            plan_publication_annotations([feature()], {'invented:one': [1,2]}, enabled=True)

    def test_regular_annotation_and_lossless_D(self):
        result = plan([feature()])
        row = result.annotations[0]
        self.assertEqual((row.native_class, row.native_kind, row.physical_layer), (201,40,16))
        self.assertEqual((row.min_level, row.max_level), (2,3))
        self.assertEqual(decode_display_control(row.display_control, 2,3)[0].style_word, 0x8000)
        self.assertEqual(row.name, 'Publication: TEST PUBLICATION (derived)')

    def test_information_not_relabelled_reference(self):
        f = feature(attributes=[dict(acronym='INFORM', type='utf8', value='TEST NOTICE')])
        row = plan([f]).annotations[0]
        self.assertIn('Information (INFORM): TEST NOTICE', row.information)
        self.assertNotIn('Publication reference (PUBREF):', row.information)
        self.assertEqual(row.name, 'Nautical publication information (derived)')

    def test_multiple_source_text_fields_preserve_order(self):
        attrs = [dict(acronym=k, type='utf8', value=v) for k,v in
                 [('INFORM','FIRST'), ('PUBREF','SECOND'), ('PUBREF','THIRD')]]
        text = plan([feature(attributes=attrs)]).annotations[0].information
        self.assertLess(text.index('FIRST'), text.index('SECOND'))
        self.assertLess(text.index('SECOND'), text.index('THIRD'))

    def test_empty_owner_retained_without_invented_text(self):
        f = feature(attributes=[dict(acronym='catgeo',type='uint32',value=3)])
        result = plan([f], anchors={})
        self.assertEqual(result.annotations, ())
        self.assertEqual(result.omitted_no_text, ('invented:one',))
        self.assertEqual(len(result.retained_sources), 1)

    def test_blank_content_is_not_publication_text(self):
        f = feature(attributes=[dict(acronym='PUBREF',type='utf8',value=' \t\n')])
        self.assertEqual(plan([f], anchors={}).omitted_no_text, ('invented:one',))

    def test_original_source_not_mutated(self):
        f = feature()
        before = copy.deepcopy(f)
        plan([f])
        self.assertEqual(f, before)

    def test_retention_hashes_cover_full_area_and_attributes(self):
        f = feature()
        first = plan([f]).retained_sources[0]
        f['geometry']['rings'][0]['coordinates'][1][1] = 2
        second = plan([f]).retained_sources[0]
        self.assertNotEqual(first.geometry_sha256, second.geometry_sha256)
        self.assertNotEqual(first.feature_sha256, second.feature_sha256)
        self.assertEqual(first.ordered_attributes_sha256, second.ordered_attributes_sha256)
        f['attributes'].append(dict(acronym='catgeo',type='uint32',value=3))
        third = plan([f]).retained_sources[0]
        self.assertNotEqual(second.ordered_attributes_sha256, third.ordered_attributes_sha256)

    def test_same_reference_does_not_merge_distinct_owners(self):
        result = plan([feature('invented:one'), feature('invented:two')])
        self.assertEqual(len(result.annotations), 2)
        self.assertEqual(len(result.retained_sources), 2)

    def test_duplicate_owner_rejected(self):
        with self.assertRaises(ValueError):
            plan([feature(),feature()])

    def test_untyped_text_never_stringified(self):
        for attr in [dict(acronym='PUBREF',type='uint32',value=7),
                     dict(acronym='INFORM',type='utf8',value=7)]:
            with self.assertRaises(ValueError):
                plan([feature(attributes=[attr])])

    def test_missing_or_noninteger_world_anchor_rejected(self):
        for anchors in ({}, {'invented:one':[True,2]}, {'invented:one':[1.0,2]},
                        {'invented:one':[1<<31,2]}, {'invented:one':[1]}):
            with self.assertRaises(ValueError):
                plan([feature()],anchors=anchors)

    def test_extra_anchor_cannot_hide_bad_owner_join(self):
        with self.assertRaises(ValueError):
            plan([feature()],anchors={'invented:one':[1,2], 'invented:unknown':[1,2]})

    def test_other_class_or_nonarea_rejected(self):
        for key, value in [('classAcronym','OTHER'), ('geometry',dict(kind='line'))]:
            f=feature();f[key]=value
            with self.assertRaises(ValueError):
                plan([f])


if __name__ == '__main__':
    unittest.main()
