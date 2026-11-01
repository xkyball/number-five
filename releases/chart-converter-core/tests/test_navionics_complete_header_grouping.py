"""Invented own public maps only; no IO, Source data or native execution."""
from copy import deepcopy
import io,unittest
from no_chart_converter.navionics import complete_header_grouping as g
from no_chart_converter.navionics import fragment_grouping as legacy
from no_chart_converter.navionics.atlas import atlas_from_spec,plan_atlas,write_atlas
from no_chart_converter.navionics.batch import plan_sha256

def mapdoc(sid,cls=41,value=300,ran=(0,15),presence=True):
 attrs=([dict(native_code_u16=82,raw_integer=0,declared_native_units=2),dict(native_code_u16=83,raw_integer=value,declared_native_units=2)]if cls==41 else[dict(native_code_u16=98,text='West −3.5°; original sign')])if presence else[]
 f=dict(kind=24,source_id=sid,class_code=cls,parts=[[[0,0],[20,0],[20,20],[0,20],[0,0]],[[2,2],[2,4],[4,4],[4,2],[2,2]]],attributes=attrs,native_level_range=dict(min_level=ran[0],max_level=ran[1]))
 return dict(format='no5-own-plain-generic-attribute-batch/1',header=dict(flags=4660,raw_bounds=[0,0,65534,65534],raw_grid=[65535,65535],raw_levels=[8,20],trailing_value=0),date_text_1='20261020',date_text_2='20261020',layers=[dict(layer_id=38 if cls==41 else 118,layer_range=[-5,10000],features=[f])])

def args(maps):
 b=[dict(inputOrdinal=i,cellId='invented',mapValueSHA256=g._complete_digest(m),sourceReference=dict(unitOrdinal=i,localAtlasPlanSHA256='1'*64,standaloneBodyPlanSHA256='2'*64))for i,m in enumerate(maps)]
 s=dict(originalBinding='invented trusted source pins')
 return b,s,dict(policy=g.COMPLETE_MAP_METADATA_POLICY,expected_maps_sha256=g._complete_digest(maps),expected_bindings_sha256=g._complete_digest(dict(orderedBindings=b,sourceBinding=s)))

class Controls(unittest.TestCase):
 def test_aba_complete_layer_blocks_ranges_attrs_and_holes(self):
  maps=[mapdoc('A',value=300,ran=(1,4)),mapdoc('B',120,ran=(3,8)),mapdoc('A-again',value=900,ran=(0,15))];maps[1]['layers'][0]['layer_range']=[1,12];maps[2]['layers'][0]['layer_range']=[-100,300]
  before=deepcopy(maps);b,s,k=args(maps);r=g.group_complete_map_metadata(maps,b,s,**k);out=r['document']['maps'][0]
  self.assertEqual(out['layers'],[m['layers'][0]for m in maps]);self.assertEqual([l['layer_id']for l in out['layers']],[38,118,38]);self.assertEqual(g.restore_input_maps(r),maps);self.assertEqual(g.verify_complete_map_metadata_group(r,maps,b,s,**k)['status'],'PASS_EXACT_LAYER_BLOCK_CONCATENATION');self.assertEqual(maps,before);self.assertFalse(r['nativeEquivalent'])
 def test_normal_public_plan_and_bytes_keep_every_physical_layer(self):
  maps=[mapdoc('A'),mapdoc('B',presence=False),mapdoc('A2',value=600)];b,s,k=args(maps);r=g.group_complete_map_metadata(maps,b,s,**k);spec=atlas_from_spec(r['document']);plan=plan_atlas(spec,max_output_bytes=65536);body=plan['maps'][0]['bodyPlan'];self.assertEqual(len(body['layers']),3);self.assertEqual([f['sourceId']for f in body['features']],['A','B','A2']);self.assertEqual([f['attributeBytes']for f in body['features']],[8,0,8]);sink=io.BytesIO();em=write_atlas(spec,sink,expected_plan_sha256=plan_sha256(plan),max_output_bytes=65536);self.assertEqual(len(sink.getvalue()),em['bytesWritten'])
 def test_65_features_no_kind_or63_page_confusion(self):
  maps=[mapdoc('f'+str(i),value=i)for i in range(65)];b,s,k=args(maps);r=g.group_complete_map_metadata(maps,b,s,**k);self.assertEqual(len(r['document']['maps']),1);self.assertEqual(len(r['document']['maps'][0]['layers']),65);self.assertEqual(r['sourceLedger']['features'],65);self.assertEqual(g.restore_input_maps(r),maps)
 def test_complete_multi_layer_input_blocks_keep_offsets(self):
  a=mapdoc('A');a['layers']+=mapdoc('B',120)['layers'];maps=[a,mapdoc('C')];b,s,k=args(maps);r=g.group_complete_map_metadata(maps,b,s,**k);self.assertEqual([x['firstOutputLayerOrdinal']for x in r['sourceLedger']['origins']],[0,2]);self.assertEqual(g.restore_input_maps(r),maps)
 def test_different_header_dates_and_other_map_metadata_refuse(self):
  for mutate in(lambda m:m['header'].update(flags=7),lambda m:m['header'].update(raw_grid=[65534,65534]),lambda m:m.update(date_text_1='20261021')):
   maps=[mapdoc('A'),mapdoc('B')];mutate(maps[1]);b,s,k=args(maps)
   with self.assertRaisesRegex(ValueError,'MAP_METADATA_MUST_BE_IDENTICAL'):g.group_complete_map_metadata(maps,b,s,**k)
 def test_different_cell_and_duplicate_feature_ids_refuse(self):
  maps=[mapdoc('A'),mapdoc('B')];b,s,k=args(maps);b[1]['cellId']='other';k['expected_bindings_sha256']=g._complete_digest(dict(orderedBindings=b,sourceBinding=s))
  with self.assertRaisesRegex(ValueError,'ONE_ORIGINAL_SOURCE_CELL'):g.group_complete_map_metadata(maps,b,s,**k)
  maps=[mapdoc('A'),mapdoc('A')];b,s,k=args(maps)
  with self.assertRaisesRegex(ValueError,'FEATURE_ID_RENAME_OR_ALIAS'):g.group_complete_map_metadata(maps,b,s,**k)
 def test_input_binding_scope_and_bool_ordinal_refuse(self):
  maps=[mapdoc('A')];b,s,k=args(maps);b[0]['inputOrdinal']=False;k['expected_bindings_sha256']=g._complete_digest(dict(orderedBindings=b,sourceBinding=s))
  with self.assertRaisesRegex(ValueError,'ORDERED_MAP_BINDING'):g.group_complete_map_metadata(maps,b,s,**k)
  b,s,k=args(maps)
  with self.assertRaisesRegex(ValueError,'INPUT_MAP_WORK_CAP'):g.group_complete_map_metadata(maps,b,s,max_input_maps=True,**k)
 def test_rehashed_layer_order_range_attribute_tamper_fails_source_inverse(self):
  maps=[mapdoc('A'),mapdoc('B',120),mapdoc('C',value=600)];b,s,k=args(maps);r=g.group_complete_map_metadata(maps,b,s,**k)
  for mutate in(lambda v:v['document']['maps'][0]['layers'].reverse(),lambda v:v['document']['maps'][0]['layers'][0].update(layer_range=[0,1]),lambda v:v['document']['maps'][0]['layers'][0]['features'][0]['attributes'][1].update(raw_integer=999),lambda v:v.update(nativeEquivalent=True)):
   v=deepcopy(r);mutate(v);v.pop('resultSHA256');v['resultSHA256']=g._complete_digest(v)
   with self.assertRaises(ValueError):g.verify_complete_map_metadata_group(v,maps,b,s,**k)
 def test_corrupt_seal_and_omitted_layer_refuse(self):
  maps=[mapdoc('A')];b,s,k=args(maps);r=g.group_complete_map_metadata(maps,b,s,**k);r['resultSHA256']='0'*64
  with self.assertRaisesRegex(ValueError,'SEAL'):g.restore_input_maps(r)
 def test_legacy_exact_style_api_stays_distinct(self):
  self.assertEqual(legacy.POLICY,'EXACT_LOCAL_HEADER_DATES_LAYER_STYLE_AND_RANGE_GROUPING/1');self.assertNotEqual(legacy.POLICY,g.COMPLETE_MAP_METADATA_POLICY);self.assertTrue(callable(legacy.group_exact_maps_2048))

if __name__=='__main__':unittest.main()
