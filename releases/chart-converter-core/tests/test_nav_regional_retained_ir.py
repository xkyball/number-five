"""Synthetic own retained-IR coverage; no private charts, Native or decoder."""
from copy import deepcopy
import unittest
from no_chart_converter.navionics.regional_source import compile_region
from no_chart_converter.navionics.regional_source import adapter


def own_cell():
    outer=[[.0005,.0005],[.002,.0005],[.002,.002],[.0005,.002],[.0005,.0005]]
    hole=[[.001,.001],[.001,.0015],[.0015,.0015],[.0015,.001],[.001,.001]]
    def feature(sid,name,code,geometry):return dict(sourceId=sid,classAcronym=name,classCode=code,geometry=geometry,attributes=[],displayScale={})
    topology=dict(status='exact_topology',issues=[])
    return dict(format='no5-chart-ir/1',kind='cell',id='OWN_INVENTED_IR',features=[
        feature('own:area','DEPARE',42,dict(kind='area',reconstruction=topology,
            rings=[dict(id='r0',role='exterior',coordinates=outer),dict(id='r1',role='interior',coordinates=hole)],
            polygons=[dict(exteriorRingId='r0',interiorRingIds=['r1'])])),
        feature('own:line','COALNE',30,dict(kind='line',reconstruction=topology,parts=[dict(coordinates=[[.0007,.0008],[.0017,.0018]])])),
        feature('own:point','LNDMRK',74,dict(kind='point',position=[.0018,.0018])),
        feature('own:sound','SOUNDG',129,dict(kind='multipoint',points=[dict(ordinal=0,position=[.0011,.0013],
            depth=dict(meaning='depth',value=12.3,unit=dict(value='m',state='verified')))]))])


def request():return dict(format=adapter.REQUEST_FORMAT,region_bounds=[0,0,256,256],tile_side_source_units=256,maximum_maps=1,
    maximum_vector_error_source_units=2,date_text='20261021',range_policy=adapter.POLICY,
    acknowledge_attribute_premises=True,unsupported_owner_policy='refuse_region_gaps')


class PortableAuthoring(unittest.TestCase):
    def test_all_four_kinds_and_polygon_hole_through_real_public_writer(self):
        cell=own_cell();before=deepcopy(cell);q=compile_region(cell,request(),maximum_plain_bytes=1<<20)
        self.assertEqual(cell,before);self.assertEqual(q['regionalGapCount'],0)
        self.assertEqual({r['kind']for r in q['geometryLedger']['featureLedger']},{24,32,40,44})
        self.assertEqual(len(q['document']['maps'][0]['layers'][0]['features'][0]['parts']),2)
        self.assertGreater(len(q['plain']),0);self.assertFalse(q['nativeProductAcceptance'])
        self.assertFalse(q['sourceSCAMINParity']);self.assertEqual(q['sourceDecoderCalls'],0)

    def test_unmapped_regional_owner_is_refused_without_default_category(self):
        cell=own_cell();other=deepcopy(cell['features'][1]);other.update(sourceId='own:unmapped',classAcronym='OWN_UNSUPPORTED',classCode=9999);cell['features'].append(other)
        with self.assertRaisesRegex(ValueError,'SOURCE_REGION_HAS_UNREPRESENTED_OWNERS:own:unmapped'):
            compile_region(cell,request(),maximum_plain_bytes=1<<20)

    def test_diagnostic_mode_retains_unknown_class_as_explicit_gap(self):
        cell=own_cell();other=deepcopy(cell['features'][1]);other.update(sourceId='own:unmapped',classAcronym='OWN_UNSUPPORTED',classCode=9999);cell['features'].append(other)
        r=request();r['unsupported_owner_policy']='retain_diagnostic_gaps';q=compile_region(cell,r,maximum_plain_bytes=1<<20)
        self.assertEqual(q['regionalGapCount'],1);self.assertEqual(q['regionalGaps'][0]['sourceId'],'own:unmapped')
        self.assertFalse(q['regionalGaps'][0]['nativeEmission'])
        self.assertEqual(q['inventory']['originalFeatureCount'],5)

    def test_implicit_scale_conversion_and_output_budget_are_refused(self):
        r=request();r['range_policy']='assume_scamin'
        with self.assertRaisesRegex(ValueError,'EXPLICIT_DIAGNOSTIC_ONLY_RANGE_POLICY'):compile_region(own_cell(),r,maximum_plain_bytes=1<<20)
        with self.assertRaisesRegex(ValueError,'EXPLICIT_FINITE_PLAIN_BYTE_WORK_BUDGET'):compile_region(own_cell(),request(),maximum_plain_bytes=0)


if __name__=='__main__':unittest.main()
