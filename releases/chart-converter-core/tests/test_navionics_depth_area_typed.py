"""Invented depth-area wire controls, no native meaning/portrayal acceptance."""
from decimal import Decimal
import io
import hashlib
import json
from pathlib import Path
import struct
import pytest
from no_chart_converter.navionics import typed_batch as new
from no_chart_converter.navionics.batch import Area24,Line32,plan_sha256
from no_chart_converter.navionics.writer import RawMapHeader

AREA=(((0,0),(3,0),(1,2),(0,0)),)
HEADER=RawMapHeader(1,(0,0,100,100),(4,4),(8,20),0)

def feature(label,cls,codes):
    return new.AttributedFeature(Area24(label,cls,AREA),[new.ScaledM(code,Decimal('1.2')if code==82 else Decimal('5.6'),2)for code in codes])

def emit(spec):
    p=new.plan_typed_batch(spec);out=io.BytesIO();new.write_typed_batch(spec,out,expected_plan_sha256=plan_sha256(p));return p,out.getvalue()


@pytest.mark.parametrize('cls',[41,46])
@pytest.mark.parametrize('codes,mask,payload',[
    ([],0,b''),([82],0x40000000,b'\x0c\0\0\0'),
    ([83],0x20000000,b'8\0\0\0'),([83,82],0x60000000,b'\x0c\0\0\x008\0\0\0')])
def test_each_presence_and_dictionary_order(cls,codes,mask,payload):
    f=feature('own',cls,codes);p,b=emit(new.TypedBatchSpec(HEADER,new.group_typed_features([f])))
    r=p['features'][0];w=r['lengthWidth'];start=r['recordOffset']+2+w
    assert r['layerId']==38 and r['classCode']==cls and r['attributeMask']==[mask,0]
    assert b[start+8:start+10]==bytes((cls,0))
    assert int.from_bytes(b[start+10:start+10+w],'little')==len(payload)
    assert b[start+10+w:start+10+w+len(payload)]==payload
    assert p['depthAreaAttributeContractSHA256']==new.DEPTH_AREA_CONTRACT_SHA256
    assert [a['native_code_u16']for a in r['attributes']]==sorted(codes)
    assert all(a['declared_native_units']==2 for a in r['attributes'])


def test_same_layer_id_different_class_and_presence_keep_physical_order():
    fs=[feature('a',41,[82,83]),feature('b',46,[82,83]),feature('c',41,[82]),feature('d',41,[82,83])]
    s=new.TypedBatchSpec(HEADER,new.group_typed_features(fs));p,b=emit(s)
    assert [r['layerId']for r in p['layers']]==[38]*4
    assert [r['sourceId']for r in p['features']]==['a','b','c','d']
    assert [r['maskRows'][0]['classCode']for r in p['layers']]==[41,46,41,41]
    for r in p['layers']:
        start=r['recordOffset']+2+r['lengthWidth']
        assert struct.unpack_from('<I',b,start+3)[0]==r['firstFeatureOffset']
        assert b[r['featureEndOffset']:r['featureEndOffset']+3]==b'\0\x80\x04'


@pytest.mark.parametrize('cls,kind,code',[(41,Line32,82),(46,Line32,83),(41,Area24,138),(46,Area24,138),(42,Line32,82),(131,Area24,83)])
def test_no_unqualified_geometry_or_cross_class_attribute(cls,kind,code):
    parts=AREA if kind is Area24 else(((0,0),(1,1)),)
    with pytest.raises(ValueError):new.AttributedFeature(kind('bad',cls,parts),[new.ScaledM(code,5,2)])


@pytest.mark.parametrize('code',[82,83])
def test_scaler_requires_explicit_units_exact_tenths_and_i32_domain(code):
    assert new.ScaledM(code,Decimal('-0.1'),2).wire_i32==-1
    with pytest.raises(ValueError):new.ScaledM(code,Decimal('0.01'),2)
    with pytest.raises(ValueError):new.ScaledM(code,5,0)
    with pytest.raises(ValueError):new.ScaledM(code,True,2)
    with pytest.raises(ValueError):new.EnumE(code,1)


def test_prior_typed_smoke_plan_and_all730_bytes_stay_exact():
    # Previously frozen entirely invented profile; no external chart fixture.
    doc=json.loads((Path(__file__).parent/'fixtures/navionics_typed_own_smoke.json').read_bytes())
    spec=new.typed_batch_from_spec(doc)
    plan,raw=emit(spec)
    assert 'depthAreaAttributeContractSHA256' not in plan
    assert plan_sha256(plan)=='65825d8e62d030d753e3b132fe486365166e22c35087c181c62f692e2b8c65d0'
    assert len(raw)==730
    assert hashlib.sha256(raw).hexdigest()=='079bfe006886e16ff73b6aeded671267c863e5704b3da93497b014fd1132971c'


def test_new_JSON_fields_use_existing_strict_M_schema():
    doc=dict(format='no5-own-plain-typed-attribute-batch/1',header=dict(flags=1,raw_bounds=[0,0,100,100],raw_grid=[4,4],raw_levels=[8,20],trailing_value=0),
      layers=[dict(layer_id=38,layer_range=[0,100000],features=[dict(kind=24,source_id='d',class_code=41,parts=AREA,
        attributes=[dict(storage='M',native_code_u16=82,value_decimal='1.2',declared_native_units=2),dict(storage='M',native_code_u16=83,value_decimal='5.6',declared_native_units=2)])])],date_text_1='20261017',date_text_2='20261017')
    s=new.typed_batch_from_spec(doc);assert emit(s)[0]['features'][0]['attributeBytes']==8
    doc['layers'][0]['features'][0]['attributes'][0]['unit_guess']='metres'
    with pytest.raises(ValueError):new.typed_batch_from_spec(doc)
