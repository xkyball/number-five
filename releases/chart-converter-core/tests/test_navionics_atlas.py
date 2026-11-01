"""Invented host fixtures only; no native libraries, files, provider or keys."""
import hashlib
import io
import json
import struct
import pytest
from no_chart_converter.navionics import layered_batch as l, atlas as a
from no_chart_converter.navionics.batch import Point40, Line32, Area24, plan_sha256
from no_chart_converter.navionics.writer import RawMapHeader

def model(n=0, *, parts=2, points=3, origin=100000, vectors=True):
 h=RawMapHeader(0x1234,(origin,origin,origin+1000,origin+1000),(1024,1024),(8,20),0)
 layers=[l.PlainLayer(60,[Point40(f'p{n}',126,(origin+25,origin+50))]),
         l.PlainLayer(140,[l.DepthPoint44(f'd{n}',(origin+30,origin+60),101)])]
 if vectors:
  part=tuple((i%1024,(i*3)%1024) for i in range(points))
  layers.extend([l.PlainLayer(61,[Line32(f'l{n}',29,tuple(part for _ in range(parts)))]),
   l.PlainLayer(62,[Area24(f'a{n}',74,(((0,0),(8,0),(3,7),(0,0)),))])])
 return l.LayeredBatchSpec(h,layers)

def specjson(s):
 layers=[]
 for ly in s.layers:
  fs=[]
  for f in ly.features:
   if type(f) is l.DepthPoint44:r=dict(kind=44,source_id=f.source_id,xy=list(f.xy),mantissa=f.mantissa,decimal_scale=f.decimal_scale)
   else:
    r=dict(kind=40 if type(f)is Point40 else 32 if type(f)is Line32 else 24,source_id=f.source_id,class_code=f.class_code)
    r['xy' if type(f)is Point40 else 'parts']=f.xy if type(f)is Point40 else f.parts
   fs.append(r)
  layers.append(dict(layer_id=ly.layer_id,layer_range=ly.layer_range,features=fs))
 return dict(format='no5-own-plain-layered-feature-batch/1',header=s.header.__dict__,layers=layers,date_text_1=s.date_text_1,date_text_2=s.date_text_2)

def emit(spec,budget=8192,sink=None):
 p=a.plan_atlas(spec,max_output_bytes=budget);sink=io.BytesIO() if sink is None else sink
 r=a.write_atlas(spec,sink,expected_plan_sha256=plan_sha256(p),max_output_bytes=budget)
 return p,sink,r

def record(b,at):
 tag=struct.unpack_from('<H',b,at)[0];width=(1,2,4,4)[(tag>>6)&3]
 size=int.from_bytes(b[at+2:at+2+width],'little');return tag&63,at+2+width,size,at+2+width+size

# Frozen from the own invented fixture against the unchanged .23 planner.
BASELINE = {(4096, False): ('ca4f28ae7570cc2c9afc21ee2073dfa369094858e10c114f6212d23c7f4565e7', '45706221f2f26fa4389552366b455366f745e9bafbf2f5a2ed0985c536d5ae72'), (4096, True): ('3fff5d1b12bed5dc60b9457495951fb610f24e4d3a4441bad629f26d28226b8f', '4ac0aad80aed922d2e31e568e1848c943d6bd2756a4641d29d9b3b29e34f767b'), (8192, False): ('431bbcd838545a8dc34414857ad3d63b7f84926f55fcea55325e05bf56528afd', '45706221f2f26fa4389552366b455366f745e9bafbf2f5a2ed0985c536d5ae72'), (8192, True): ('4b84fa499b681074cc789635009b8662aefcd6e7bc7427cb18cf602bafed26bf', '4ac0aad80aed922d2e31e568e1848c943d6bd2756a4641d29d9b3b29e34f767b')}
@pytest.mark.parametrize('budget',[4096,8192])
@pytest.mark.parametrize('vectors',[False,True])
def test_legacy_exact_plans_and_bytes(budget,vectors):
 s=model(vectors=vectors);plan=l.plan_layered_batch(s,max_output_bytes=budget)
 expected_plan,expected_bytes=BASELINE[(budget,vectors)]
 assert plan_sha256(plan)==expected_plan
 f=io.BytesIO();l.write_layered_batch(s,f,expected_plan_sha256=expected_plan,max_output_bytes=budget)
 assert hashlib.sha256(f.getvalue()).hexdigest()==expected_bytes
 assert emit(a.AtlasSpec([s]),budget)[1].getvalue()==f.getvalue()

@pytest.mark.parametrize('count',[1,2,8,16])
def test_all_global_addresses(count):
 p,sink,r=emit(a.AtlasSpec([model(i,origin=100000+2000*i) for i in range(count)]),64*1024*1024)
 b=sink.getvalue();assert len(b)==p['totalBytes']==r['bytesWritten']
 assert int.from_bytes(b[11:15],'little')==len(b)
 kind,body,size,end=record(b,258);assert (kind,size)==(4,4*count)
 pointers=struct.unpack_from('<'+'I'*count,b,body)
 assert list(pointers)==[row['headerOffset'] for row in p['maps']]
 assert list(pointers)==[end+55*i for i in range(count)]
 assert p['headerGroup']['firstOffset']==end and p['headerGroup']['lastOffset']==end+55*count-1
 cursor=end+55*count
 for row in p['maps']:
  m=row['bodyPlan'];assert row['bodyOffset']==cursor
  k,h,z,he=record(b,row['headerOffset']);assert(k,z,he-row['headerOffset'])==(17,52,55)
  assert struct.unpack_from('<I',b,h+48)[0]==m['firstLayerOffset']
  if m['bankOffset']is not None:
   assert m['bankOffset']==cursor and record(b,cursor)[0]==52
  for ly in m['layers']:
   k,h,z,_=record(b,ly['recordOffset']);assert(k,z)==(18,19)
   assert b[h]==ly['layerId'] and struct.unpack_from('<I',b,h+3)[0]==ly['firstFeatureOffset']
   k,h,z,_=record(b,ly['featureEndOffset']);assert(k,z)==(0,4) and b[h:h+4]==b'\xff'*4
   assert struct.unpack_from('<H',b,ly['featureEndOffset'])[0]==0x8000
  assert struct.unpack_from('<H',b,m['layerEndOffset'])[0]==0
  for f in m['features']:
   k,h,z,_=record(b,f['recordOffset']);assert(k,z)==(f['kind'],f['payloadBytes'])
   if f['kind'] in (24,32):
    ptr_at=h+10+2*f['lengthWidth']
    assert struct.unpack_from('<'+'I'*f['partCount'],b,ptr_at)==tuple(t['streamOffset'] for t in f['parts'])
    for part in f['parts']:
     q=part['streamOffset'];assert 0<=q<1<<30
     assert b[q+part['streamBytes']-1]==0
  cursor+=row['bodyBytes']
 assert cursor==len(b)
 assert hashlib.sha256(b).hexdigest()==r['emittedStreamSHA256']

@pytest.mark.parametrize('parts,width',[(60,1),(61,2),(16380,2),(16381,4)])
def test_fixedpoint_and_bank_width4(parts,width):
 s=model(parts=parts,points=2);p=a.plan_atlas(a.AtlasSpec([s]),max_output_bytes=4*1024*1024)
 body=p['maps'][0]['bodyPlan'];line=next(f for f in body['features'] if f['kind']==32)
 assert line['lengthWidth']==width
 if parts>=16380:assert body['bankLengthWidth']==4
 _,sink,_=emit(a.AtlasSpec([s]),4*1024*1024);b=sink.getvalue()
 k,at,z,_=record(b,line['recordOffset']);assert k==32
 assert int.from_bytes(b[at+10+width:at+10+2*width],'little')==parts

class Short:
 def __init__(self,maximum=11):self.maximum=maximum;self.b=bytearray();self.max_seen=0;self.calls=0
 def write(self,data):
  self.max_seen=max(self.max_seen,len(data));self.calls+=1;n=min(self.maximum,len(data));self.b.extend(data[:n]);return n

def test_streaming_large_output_bounded_sink():
 s=a.AtlasSpec([model(i,parts=4,points=6000) for i in range(3)])
 sink=Short(7000);p,_,r=emit(s,1024*1024,sink)
 assert p['totalBytes']>350000 and sink.max_seen==65536
 assert r['emittedStreamSHA256']==hashlib.sha256(sink.b).hexdigest()
 assert p['nativeQualification']=='PENDING_MULTI_MAP_AND_SCALED_NATIVE_READBACK'
 assert all(m['bodyPlan']['scaledSoftwareProfile']['budgetIsFormatLimit']is False for m in p['maps'])

@pytest.mark.parametrize('bad',[None,True,0,-1,1.5,64*1024*1024+1])
def test_bad_budget_before_sink(bad):
 sink=Short()
 with pytest.raises(ValueError):a.write_atlas(a.AtlasSpec([model()]),sink,expected_plan_sha256='x',max_output_bytes=bad)
 assert sink.calls==0

def test_budget_and_plan_refusal_before_write():
 s=a.AtlasSpec([model()]);p=a.plan_atlas(s,max_output_bytes=8192);sink=Short()
 for digest,budget in [(plan_sha256(p),4096),('bad',8192),(plan_sha256(p),1)]:
  with pytest.raises(ValueError):a.write_atlas(s,sink,expected_plan_sha256=digest,max_output_bytes=budget)
 assert sink.calls==0
 p2=a.plan_atlas(a.AtlasSpec([model(points=4)]),max_output_bytes=8192)
 assert plan_sha256(p)!=plan_sha256(p2)

@pytest.mark.parametrize('bad',[None,True,0,-1,1.5,1000000])
def test_bad_shortwrite(bad):
 class Bad:
  def write(self,data):return bad
 with pytest.raises(OSError):emit(a.AtlasSpec([model()]),sink=Bad())

def test_strict_schema_ids_dates_and_scope():
 s=model();value=dict(format='no5-own-plain-atlas/1',maps=[specjson(s)])
 assert a.atlas_from_spec(json.loads(json.dumps(value)))==a.AtlasSpec([s])
 with pytest.raises(ValueError):a.atlas_from_spec(dict(value,attributes=[]))
 for maps in ([],[s]*17,[s,s]):
  with pytest.raises(ValueError):a.AtlasSpec(maps)
 other=model(1);other=l.LayeredBatchSpec(other.header,other.layers,'20261018','20261018')
 with pytest.raises(ValueError):a.AtlasSpec([s,other])
