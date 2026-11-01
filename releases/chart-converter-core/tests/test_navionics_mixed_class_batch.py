from copy import deepcopy
from dataclasses import replace
from pathlib import Path
import hashlib,io,json,struct,sys,unittest
import importlib.util
if importlib.util.find_spec('no_chart_converter.navionics.mixed_class_batch') is not None:
 from no_chart_converter.navionics import mixed_class_batch as m
else:
 candidate=Path(__file__).with_name('mixed_class_batch.py')
 spec=importlib.util.spec_from_file_location('own_class_mask_candidate',candidate);m=importlib.util.module_from_spec(spec);sys.modules[spec.name]=m;spec.loader.exec_module(m)
from no_chart_converter.navionics import generic_batch as g
from no_chart_converter.navionics.batch import plan_sha256,Point40
from no_chart_converter.navionics.writer import RawMapHeader

def document():
 layers=[]
 for sid,cls,x,low,high,end in [('own-aba-A0',41,80,0,50,15),('own-aba-B',46,400,20,70,14),('own-aba-A1',41,720,100,150,13)]:
  f=dict(source_id=sid,class_code=cls,kind=24,parts=[[[x,80],[x+160,80],[x+160,240],[x,240],[x,80]]],native_level_range=dict(min_level=0,max_level=end),attributes=[dict(native_code_u16=82,raw_integer=low,declared_native_units=2),dict(native_code_u16=83,raw_integer=high,declared_native_units=2)])
  layers.append(dict(layer_id=38,layer_range=[0,100000],features=[f]))
 return dict(format='no5-own-plain-generic-attribute-batch/1',header=dict(flags=4660,raw_bounds=[2000000,-4000000,2001023,-3998977],raw_grid=[1024,1024],raw_levels=[8,20],trailing_value=0),layers=layers,date_text_1='20261020',date_text_2='20261020')
def mixed_doc():
 d=document();d['format']=m.FORMAT;d['physical_layer_policy']=m.POLICY;d['layers']=[dict(layer_id=38,layer_range=[0,100000],features=[l['features'][0]for l in d['layers']])];return d
def emit(spec,sink=None):
 p=m.plan_class_mask_batch(spec);sink=io.BytesIO()if sink is None else sink;r=m.write_class_mask_batch(spec,sink,expected_plan_sha256=plan_sha256(p));return p,sink.getvalue(),r
def record(raw,offset):
 word=struct.unpack_from('<H',raw,offset)[0];width={0:1,1:2,2:4}[(word>>6)&3];size=int.from_bytes(raw[offset+2:offset+2+width],'little');return word&63,width,offset+2+width,size
class Mixed(unittest.TestCase):
 def test_invented_A_B_A_writes_one_layer_two_ordered_class_masks_and_full_feature_records(self):
  spec=m.class_mask_batch_from_spec(mixed_doc(),policy=m.POLICY);p,raw,r=emit(spec)
  self.assertEqual((len(p['layers']),len(p['features']),len(raw)),(1,3,560));l=p['layers'][0];k,w,at,size=record(raw,l['recordOffset']);self.assertEqual((k,size),(18,37));self.assertEqual(raw[at],38)
  masks=[struct.unpack_from('<BII',raw,at+19+i*9)for i in range(2)];self.assertEqual([x[0]for x in masks],[41,46]);self.assertEqual(masks,[(x['classCode'],x['mask0'],x['mask1'])for x in l['maskRows']]);self.assertEqual([x['classCode']for x in p['features']],[41,46,41])
  self.assertEqual(struct.unpack_from('<I',raw,at+3)[0],l['firstFeatureOffset']);self.assertEqual(raw[p['layerEndOffset']:p['layerEndOffset']+3],b'\0\0\x04');self.assertEqual(raw[l['featureEndOffset']:l['featureEndOffset']+3],b'\0\x80\x04');self.assertFalse(r['nativeReaderExecuted'])
  # The old ungrouped physical directory and each complete original feature
  # wire record are walked independently, not regenerated from expected masks.
  old=g.generic_batch_from_spec(document());op=g.plan_generic_batch(old);out=io.BytesIO();g.write_generic_batch(old,out,expected_plan_sha256=plan_sha256(op));prior=out.getvalue();self.assertEqual(hashlib.sha256(prior).hexdigest(),'a32c6aff33223ef44d6ce0e4d6a039f8ae5090bb8add1ef2d2bad59888eaa1c1')
  self.assertEqual(prior[p['bankOffset']:p['firstLayerOffset']],raw[p['bankOffset']:p['firstLayerOffset']])
  for before,after in zip(op['features'],p['features']):
   self.assertEqual(prior[before['recordOffset']:before['recordOffset']+before['recordBytes']],raw[after['recordOffset']:after['recordOffset']+after['recordBytes']]);self.assertEqual(before['attributes'],after['attributes']);self.assertEqual(before['nativeLevelRange'],after['nativeLevelRange']);self.assertEqual(before['parts'],after['parts'])
 def test_different_class_distinct_presence_masks_keep_missing_attribute_absent(self):
  d=mixed_doc();d['layers'][0]['features'][1]['attributes'].pop();p,raw,_=emit(m.class_mask_batch_from_spec(d,policy=m.POLICY))
  self.assertNotEqual(p['layers'][0]['maskRows'][0]['mask0'],p['layers'][0]['maskRows'][1]['mask0']);self.assertEqual([len(f['attributes'])for f in p['features']],[2,1,2]);self.assertEqual([f['attributeBytes']for f in p['features']],[8,4,8])
 def test_existing_layer_origin_inverse_and_tamper_refusal(self):
  old=g.generic_batch_from_spec(document());new,ledger=m.coalesce_legacy_spec(old,policy=m.POLICY);self.assertEqual(m.restore_legacy_spec(new,ledger),old);self.assertEqual([r['firstFeatureOrdinal']for r in ledger['layers']],[0,1,2]);self.assertEqual([r['outputLayerOrdinal']for r in ledger['layers']],[0,0,0])
  changed=deepcopy(ledger);changed['layers'][2]['firstFeatureOrdinal']=1
  with self.assertRaises(ValueError):m.restore_legacy_spec(new,changed)
  for change in [dict(NativeBucketAndPaintQualified=True),dict(candidatePhysicalLayers=True)]:
   broken=dict(ledger,**change)
   with self.assertRaisesRegex(ValueError,'SCOPE'):m.restore_legacy_spec(new,broken)
 def test_same_class_presence_conflict_refuses_without_mask_union_or_defaults(self):
  d=mixed_doc();d['layers'][0]['features'][2]['attributes'].pop()
  with self.assertRaisesRegex(ValueError,'PRESENCE'):m.class_mask_batch_from_spec(d,policy=m.POLICY)
 def test_duplicate_physical_ID_and_default_off_are_explicit(self):
  d=mixed_doc();d['layers'].append(deepcopy(d['layers'][0]));
  for i,f in enumerate(d['layers'][1]['features']):f['source_id']='new-feature-'+str(i)
  # Layer-ID conflict refuses before any map partition or feature reordering.
  with self.assertRaisesRegex(ValueError,'DUPLICATE_PHYSICAL_LAYER'):m.class_mask_batch_from_spec(d,policy=m.POLICY)
  with self.assertRaisesRegex(ValueError,'POLICY'):m.class_mask_batch_from_spec(mixed_doc())
 def test_legacy_API_still_rejects_mixed_presence_layer(self):
  s=m.class_mask_batch_from_spec(mixed_doc(),policy=m.POLICY)
  with self.assertRaisesRegex(ValueError,'one class'):g.GenericPresenceLayer(38,s.layers[0].features)
  with self.assertRaises(TypeError):g.plan_generic_batch(s)
 def test_existing_single_class_text_resources_and_normal_bytes_are_exact(self):
  fs=tuple(g.GenericAttributedFeature(Point40('own-text-%d'%i,71,(100001+i,100002)),[g.GenericAttribute(109,t)])for i,t in enumerate(['Own café','Second','Own café']))
  h=RawMapHeader(0x1234,(100000,100000,101000,101000),(1024,1024),(8,20),0);old=g.GenericAttributedBatchSpec(h,g.group_generic_features(fs));op=g.plan_generic_batch(old);a=io.BytesIO();g.write_generic_batch(old,a,expected_plan_sha256=plan_sha256(op))
  new=m.ClassMaskBatchSpec(h,[m.ClassMaskLayer(88,fs)],physical_layer_policy=m.POLICY);p,raw,_=emit(new);self.assertEqual(raw,a.getvalue());self.assertEqual(p['textResources'],op['textResources'])
 def test_plan_binding_and_short_writes_are_checked(self):
  s=m.class_mask_batch_from_spec(mixed_doc(),policy=m.POLICY);out=io.BytesIO()
  with self.assertRaisesRegex(ValueError,'EXACT_EXPECTED'):m.write_class_mask_batch(s,out,expected_plan_sha256='0'*64)
  self.assertEqual(out.getvalue(),b'')
  class Short(io.BytesIO):
   def write(self,raw):return super().write(raw[:3])
  p,raw,_=emit(s);sp,sraw,_=emit(s,Short());self.assertEqual(sraw,raw)
  class Bad:
   def write(self,raw):return True
  with self.assertRaisesRegex(OSError,'PROGRESS'):emit(s,Bad())
if __name__=='__main__':unittest.main()
