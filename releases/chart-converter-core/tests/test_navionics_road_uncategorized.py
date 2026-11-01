"""Invented opt-in/wire boundaries and exact pre-existing plan compatibility."""
from copy import deepcopy
import importlib.util
import io
from pathlib import Path
import sys
import unittest

from no_chart_converter.navionics import generic_batch as old
from no_chart_converter.navionics import road_authoring_registry as old_registry

HERE=Path(__file__).resolve().parent
PACKAGE=HERE/'package/src/no_chart_converter/navionics'
if not PACKAGE.is_dir():
    PACKAGE=Path(old.__file__).resolve().parent
def load(name,path):
    spec=importlib.util.spec_from_file_location(name,path);m=importlib.util.module_from_spec(spec)
    sys.modules[name]=m;spec.loader.exec_module(m);return m
registry=load('no_chart_converter.navionics.road_authoring_registry',PACKAGE/'road_authoring_registry.py')
candidate=load('no_chart_converter.navionics.road_uncategorized_candidate_generic',PACKAGE/'generic_batch.py')
helper=load('no_chart_converter.navionics.road_uncategorized',PACKAGE/'road_uncategorized.py')

def doc(policy=None,code=208,attributes=None):
    d=dict(format='no5-own-plain-generic-attribute-batch/1',
        header=dict(flags=0,raw_bounds=[0,0,1024,1024],raw_grid=[1024,1024],raw_levels=[8,20],trailing_value=0),
        layers=[dict(layer_id=121 if code==208 else 37,layer_range=[0,100000],features=[dict(kind=32,source_id='OWN:ROAD',class_code=code,
            parts=[[[1,2],[200,300]]],attributes=[] if attributes is None else attributes)])],date_text_1='20261019',date_text_2='20261019')
    if policy is not None:d['road_authoring_policy']=policy
    return d

def emit(module,d):
    spec=module.generic_batch_from_spec(d);plan=module.plan_generic_batch(spec,max_output_bytes=65536)
    f=io.BytesIO();module.write_generic_batch(spec,f,max_output_bytes=65536,expected_plan_sha256=module.plan_sha256(plan))
    return plan,f.getvalue()

def source():
    return dict(sourceId='OWN:RAW',classCode=116,classAcronym='ROADWY',sourcePrimitive=1,
        sourceRecord=dict(offset=10,length=11),geometry=dict(kind='line',parts=[dict(coordinates=[[1,2],[3,4]])]),
        displayScale=dict(SCAMIN=90000),attributes=[dict(code=50000,acronym='catgeo',type='uint32',value=2),
            dict(code=133,acronym='SCAMIN',type='uint32',value=90000),dict(code=112,acronym='NATCON',type='string',value='1,2')])

class Controls(unittest.TestCase):
    def test_explicit_zero_attr_wire_is_ordinary_and_bound(self):
        plan,wire=emit(candidate,doc(registry.ROAD_UNCATEGORIZED_POLICY));f=plan['features'][0]
        self.assertEqual(f['attributeBytes'],0);self.assertEqual(f['attributeMask'],[0,0]);self.assertEqual(f['attributes'],[])
        self.assertEqual(f['classCode'],208);self.assertEqual(f['kind'],32)
        self.assertEqual(plan['roadAuthoringPolicy'],registry.ROAD_UNCATEGORIZED_POLICY)
        self.assertEqual(plan['roadUncategorizedConsumerSHA256'],registry.ROAD_UNCATEGORIZED_CONSUMER_SHA256)
        payload=f['recordOffset']+2+f['lengthWidth']
        self.assertEqual(wire[payload+8:payload+10],bytes([208,0]))
        self.assertEqual(int.from_bytes(wire[payload+10:payload+10+f['lengthWidth']],'little'),0)

    def test_default_and_old_major_policy_are_not_broadened(self):
        for m in (old,candidate):
            with self.assertRaises(ValueError):emit(m,doc())
            with self.assertRaises(ValueError):emit(m,doc(old_registry.ROAD_AUTHORING_POLICY))
        with self.assertRaises(ValueError):emit(candidate,doc('unknown'))
        with self.assertRaises(ValueError):emit(candidate,doc(registry.ROAD_UNCATEGORIZED_POLICY,attributes=[dict(native_code_u16=158,enum_u8=2)]))

    def test_three_preexisting_complete_plans_and_streams_are_exact(self):
        docs=[doc(code=42),doc(old_registry.ROAD_AUTHORING_POLICY,attributes=[dict(native_code_u16=158,enum_u8=1)]),
              doc(old_registry.ROAD_AUTHORING_POLICY,attributes=[dict(native_code_u16=158,enum_u8=2)])]
        goldens=[('3e7932c17226d2f33bab4041c14c3c85a9a3eef3749b3cba711320a09f579e93','2115af2ea17875e96195335c75496783c7ad5fb270bd24038c032fdc55f4d75e'),
            ('12a6b05e3e185cee6bb9137eb234e20d47d857dfc3ad5f189fc751786049a389','3ac83991c8f7e729ce75123af6212ab8cc695c32b8036efc6747cf39e3d43fbc'),
            ('390564704273169e96e26f583586d202ef1bf74573108ea64e128ac8c966c7cd','35ccc6eb5f12b1de368c731c1a4b34207b66ef5a4d75e146a23f8c77a375ba92')]
        for d,golden in zip(docs,goldens):
            with self.subTest(d=d.get('road_authoring_policy')):
                self.assertEqual(emit(old,d),emit(candidate,d))
                plan,wire=emit(candidate,d)
                self.assertEqual((candidate.plan_sha256(plan),helper.hashlib.sha256(wire).hexdigest()),golden)

    def test_full_source_ledger_survives_without_geometry_or_category_fabrication(self):
        f=source();before=deepcopy(f);p=helper.prepare_uncategorized_road(f,policy=registry.ROAD_UNCATEGORIZED_POLICY)
        self.assertEqual(f,before);self.assertEqual(p['attributes'],[]);self.assertEqual(p['externalSourceOccurrences'],2)
        self.assertEqual([r['sourceAttribute']for r in p['sourceAttributeLedger']],f['attributes'])
        self.assertFalse(p['geometryIncluded']);self.assertEqual(p['nativeCategory'],'ABSENT_NOT_MAJOR2')

    def test_present_category_unknown_area_and_silent_optin_refuse(self):
        for value in (1,2,3,4,6,None,'2'):
            f=source();f['attributes'].append(dict(code=57,acronym='CATROD',type='uint32',value=value))
            with self.subTest(value=value),self.assertRaises(ValueError):helper.prepare_uncategorized_road(f,policy=registry.ROAD_UNCATEGORIZED_POLICY)
        with self.assertRaises(ValueError):helper.prepare_uncategorized_road(source())
        f=source();f['sourcePrimitive']=2;f['geometry']['kind']='area'
        with self.assertRaises(ValueError):helper.prepare_uncategorized_road(f,policy=registry.ROAD_UNCATEGORIZED_POLICY)

if __name__=='__main__':unittest.main()
