"""Focused invented mixed-map controls; no Original or private input."""
from decimal import Decimal
import hashlib
import importlib.util
import io
from pathlib import Path
import struct
import sys
import pytest
from no_chart_converter.navionics import layered_batch as l, typed_batch as t, atlas as a
from no_chart_converter.navionics.batch import Line32,Area24,Point40,plan_sha256
from no_chart_converter.navionics.writer import RawMapHeader
BASELINE = {1: ('cee33c212a0ea59714feb542b4bf3ef9313541476dcefac399b037dba7020eef', '3db481a219889c4881fb31fa5b78eaf665d5610a7a2fae0d8d38229d02af09e4'), 2: ('aab477bda8c791e57128d0c8c076e05ff78db7b5dc74c060f91d6795b9c43b74', 'fe7cf393fd15e96d0f61753067f2d55fb61e0584cd72186758199f6e297112a3'), 8: ('ab6c91399679dc3cf2d333514a9c4ee0dd5a389d40a3e4933ed5a268d9ffccce', '2dec877f498eddff1a87f5f888ed2c38bb97983184ac0c302f24d7fa5fd5c718'), 16: ('48edf34949e22f3bc0a148e5285ee9f442a1120e8aee2be041fcd2a75eeb5504', '93c88c17fec59336a0015d6223b0284961985f4aa1c819517bc0cb82c41b6d29')}

def head():return RawMapHeader(0x1234,(100000,100000,101000,101000),(1024,1024),(8,20),0)
def raw(i):return l.LayeredBatchSpec(head(),[l.PlainLayer(60,[Point40(f'p{i}',126,(100001,100002))]),l.PlainLayer(140,[l.DepthPoint44(f'd{i}',(100003,100004),101)])])
def typed(i,area=False,parts=2):
 cls=Area24 if area else Line32;points=((0,0),(3,0),(1,2),(0,0))if area else ((0,0),(1,2),(3,1))
 fs=[t.AttributedFeature(cls(f'e{i}',131,tuple(points for _ in range(parts))),[t.EnumE(151,2)]),
     t.AttributedFeature(cls(f'em{i}',131,(points,)),[t.EnumE(62,3),t.EnumE(151,4)])]
 return t.TypedBatchSpec(head(),t.group_typed_features(fs))
def emit(s):
 p=a.plan_atlas(s,max_output_bytes=1<<20);f=io.BytesIO();r=a.write_atlas(s,f,expected_plan_sha256=plan_sha256(p),max_output_bytes=1<<20);return p,f.getvalue(),r

def rec(b,at):
 tag=struct.unpack_from('<H',b,at)[0];w={0:1,1:2,2:4}[(tag>>6)&3];n=int.from_bytes(b[at+2:at+2+w],'little');return tag&63,at+2+w,n

@pytest.mark.parametrize('n',[1,2,8,16])
def test_raw_only_complete_plan_and_bytes_unchanged(n):
 maps=[raw(i)for i in range(n)];pn,b,_=emit(a.AtlasSpec(maps))
 assert (plan_sha256(pn),hashlib.sha256(b).hexdigest())==BASELINE[n]
 assert 'typedMapOrdinals'not in pn

@pytest.mark.parametrize('area',[False,True])
def test_single_typed_map_bytes_equal_typed_emitter(area):
 s=typed(1,area);p,b,_=emit(a.AtlasSpec([s]));q=t.plan_typed_batch(s,max_output_bytes=1<<20);f=io.BytesIO();t.write_typed_batch(s,f,expected_plan_sha256=plan_sha256(q),max_output_bytes=1<<20)
 assert b==f.getvalue() and p['typedMapOrdinals']==[0]

@pytest.mark.parametrize('parts,width',[(60,1),(61,2)])
def test_mixed_offsets_presence_attributes_and_width(parts,width):
 maps=[raw(0),typed(1,parts=parts),typed(2,True),raw(3)];p,b,r=emit(a.AtlasSpec(maps));assert p['typedMapOrdinals']==[1,2]
 assert hashlib.sha256(b).hexdigest()==r['emittedStreamSHA256']
 for ordinal in (1,2):
  m=p['maps'][ordinal]['bodyPlan'];assert m['layers'][0]['layerId']==m['layers'][1]['layerId']
  for layer in m['layers']:
   kind,at,n=rec(b,layer['recordOffset']);assert(kind,n)==(18,28)
   mask=layer['maskRows'][0];assert struct.unpack_from('<BII',b,at+19)==(mask['classCode'],mask['mask0'],mask['mask1'])
   assert struct.unpack_from('<I',b,at+3)[0]==layer['firstFeatureOffset']
  for actual,source in zip(m['features'],maps[ordinal].layers):
   f=source.features[0];kind,at,n=rec(b,actual['recordOffset']);w=actual['lengthWidth'];attr_at=at+10+w
   assert int.from_bytes(b[at+10:attr_at],'little')==len(f.wire_attributes)
   assert b[attr_at:attr_at+len(f.wire_attributes)]==f.wire_attributes
   count_at=attr_at+len(f.wire_attributes);assert int.from_bytes(b[count_at:count_at+w],'little')==len(f.geometry.parts)
   assert struct.unpack_from('<'+'I'*len(f.geometry.parts),b,count_at+w)==tuple(x['streamOffset']for x in actual['parts'])
 assert p['maps'][1]['bodyPlan']['features'][0]['lengthWidth']==width


def test_exact_json_dispatch_and_missing_fields_refuse():
 value=dict(format='no5-own-plain-atlas/1',maps=[dict(format='no5-own-plain-typed-attribute-batch/1',header=head().__dict__,date_text_1='20261017',date_text_2='20261017',layers=[dict(layer_id=37,layer_range=[0,100000],features=[dict(kind=32,source_id='real-label',class_code=42,parts=[[[0,0],[1,2]]],attributes=[dict(storage='M',native_code_u16=138,value_decimal='10.1',declared_native_units=2)])])])])
 parsed=a.atlas_from_spec(value);assert type(parsed.maps[0])is t.TypedBatchSpec
 p,b,_=emit(parsed);assert p['maps'][0]['bodyPlan']['features'][0]['attributeBytes']==4
 value['maps'][0]['format']='unknown'
 with pytest.raises(ValueError):a.atlas_from_spec(value)


def test_attribute_value_presence_bind_plan_before_write():
 x=typed(1);y=t.TypedBatchSpec(x.header,[t.PresenceLayer(137,[t.AttributedFeature(x.layers[0].features[0].geometry,[t.EnumE(151,3)])]),x.layers[1]])
 px=a.plan_atlas(a.AtlasSpec([x]));py=a.plan_atlas(a.AtlasSpec([y]));assert plan_sha256(px)!=plan_sha256(py)
 f=io.BytesIO()
 with pytest.raises(ValueError):a.write_atlas(a.AtlasSpec([y]),f,expected_plan_sha256=plan_sha256(px))
 assert not f.getvalue()
