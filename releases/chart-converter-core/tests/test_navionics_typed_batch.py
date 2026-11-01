"""Invented own grammar controls; never Original reader/portrayal acceptance."""
from decimal import Decimal, localcontext
import hashlib
import io
import struct

import pytest
from no_chart_converter.navionics import typed_batch as t
from no_chart_converter.navionics.batch import Line32,Area24,plan_sha256
from no_chart_converter.navionics.writer import RawMapHeader

HEADER=RawMapHeader(0x1234,(0,0,100,100),(4,4),(8,20),0)
LINE=(((0,0),(1,2),(3,1)),)
AREA=(((0,0),(3,0),(1,2),(0,0)),)


def f(label,attrs=(), *, area=False, depcnt=False, parts=None):
    cls=Area24 if area else Line32
    return t.AttributedFeature(cls(label,42 if depcnt else 131,parts or(AREA if area else LINE)),attrs)


def spec(features):
    return t.TypedBatchSpec(HEADER,t.group_typed_features(features))


def emit(s, budget=4096):
    p=t.plan_typed_batch(s,max_output_bytes=budget);out=io.BytesIO()
    r=t.write_typed_batch(s,out,expected_plan_sha256=plan_sha256(p),max_output_bytes=budget)
    b=out.getvalue()
    assert r['bytesWritten']==len(b)==p['totalBytes'] and r['emittedStreamSHA256']==hashlib.sha256(b).hexdigest()
    assert not r['nativeReaderExecuted']
    return p,b


def record(raw,offset):
    # Independent envelope parser from tag bits and declared payload extent.
    tag=int.from_bytes(raw[offset:offset+2],'little');width={0:1,1:2,2:4}[(tag>>6)&3]
    size=int.from_bytes(raw[offset+2:offset+2+width],'little')
    start=offset+2+width;end=start+size
    assert end<=len(raw)
    return tag&63,width,raw[start:end],end,bool(tag&0x8000)


def test_repeated_layer_ids_and_presence_runs_preserve_global_order():
    fs=[f('a',[t.EnumE(151,2)]),f('b',[t.EnumE(151,4),t.EnumE(62,6)]),
        f('c',[t.EnumE(151,3)]),f('d',[]),f('e',[t.EnumE(62,1)])]
    s=spec(fs);p,b=emit(s)
    assert [v.layer_id for v in s.layers]==[137]*5
    assert [r['sourceId']for r in p['features']]==['a','b','c','d','e']
    masks=[(0x200,0),(0x40000200,0),(0x200,0),(0,0),(0x40000000,0)]
    attrs=[b'\x02',b'\x06\x04',b'\x03',b'',b'\x01']
    cursor=p['firstLayerOffset']
    for group,row,mask in zip(s.layers,p['layers'],masks):
        assert cursor==row['recordOffset']
        kind,width,body,cursor,active=record(b,cursor)
        assert(kind,width,active)==(18,1,True)
        assert struct.unpack_from('<BHIIii',body)==(137,0,row['firstFeatureOffset'],0,0,100000)
        if mask!=(0,0):assert struct.unpack_from('<BII',body,19)==(131,*mask)
        else:assert len(body)==19
    assert cursor==p['layerEndOffset'];assert record(b,cursor)[0:1]==(0,)and record(b,cursor)[4]is False
    for row,value,mask in zip(p['features'],attrs,masks):
        kind,width,body,end,active=record(b,row['recordOffset'])
        assert(kind,active)==(32,True)and body[8:10]==bytes((131,0))
        attr_len=int.from_bytes(body[10:10+width],'little')
        assert attr_len==len(value)==row['attributeBytes']
        assert body[10+width:10+width+attr_len]==value
        part_count=int.from_bytes(body[10+width+attr_len:10+2*width+attr_len],'little')
        assert part_count==1 and row['attributeMask']==list(mask)
        for part in row['parts']:
            assert b[part['streamOffset']:part['streamOffset']+part['streamBytes']]==b'\0\0\0\0\x88\x01\0\x02\0\x88\x03\0\x01\0\0'
        assert record(b,p['layers'][row['layerOrdinal']]['featureEndOffset'])[0:5:4]==(0,True)


def test_grouping_only_contiguous_equal_presence_not_global_merge():
    fs=[f('a',[t.EnumE(151,2)]),f('b',[t.EnumE(151,3)]),f('c',[t.EnumE(62,6),t.EnumE(151,4)]),f('d',[t.EnumE(151,2)])]
    groups=t.group_typed_features(fs)
    assert [[v.source_id for v in g.features]for g in groups]==[['a','b'],['c'],['d']]
    with pytest.raises(ValueError,match='presence'):
        t.PresenceLayer(137,[fs[0],fs[2]])


@pytest.mark.parametrize('value,raw',[(0,0),(5,50),(10,100),(15,150),(20,200),(30,300),(50,500),
                                     (Decimal('-0.1'),-1),(Decimal('0.1'),1)])
def test_M_signed_tenth_payload_and_original_float_operation_prediction(value,raw):
    a=t.ScaledM(138,value,2);p,b=emit(spec([f('depth',[a],depcnt=True)]))
    row=p['features'][0];kind,w,body,_,_=record(b,row['recordOffset'])
    assert row['classCode']==42 and row['layerId']==37 and row['attributeMask']==[0x40000000,0]
    assert int.from_bytes(body[10:10+w],'little')==4
    assert struct.unpack_from('<i',body,10+w)[0]==raw
    native_i32_f32=struct.unpack('<f',struct.pack('<f',raw))[0]
    assert a.predicted_native_f32_le_hex==struct.pack('<f',native_i32_f32/10).hex()
    assert row['attributes']==[dict(native_code_u16=138,storage='M',wire_i32=raw,declared_native_units=2)]


def test_M_encoding_is_independent_of_Decimal_context_precision():
    with localcontext()as context:
        context.prec=2
        a=t.ScaledM(138,Decimal('123456.7'),2)
        assert a.wire_i32==1234567


@pytest.mark.parametrize('code,value',[(62,1),(62,3),(62,4),(62,6),(62,8),(62,13),(151,2),(151,3),(151,4)])
def test_only_paired_E_meanings_have_one_byte_payload(code,value):
    a=t.EnumE(code,value)
    assert a.wire_bytes==bytes((value,))and a.expected==dict(native_code_u16=code,storage='E',enum_u8=value)


@pytest.mark.parametrize('code,value',[(62,0),(62,2),(62,16),(151,1),(151,6),(151,7),(151,255),(138,2),(62,True),(True,1)])
def test_unpaired_future_enum_or_bool_refused(code,value):
    with pytest.raises(ValueError):t.EnumE(code,value)


@pytest.mark.parametrize('value,unit',[(0,None),(0,True),(0,0),(0,1),(0,3),(0.1,2),(True,2),
  (Decimal('0.01'),2),(Decimal('NaN'),2),(Decimal('Infinity'),2),(Decimal('214748364.8'),2),(Decimal('-214748364.9'),2)])
def test_missing_units_float_rounding_nonfinite_fraction_or_i32_overflow_refused(value,unit):
    with pytest.raises(ValueError):t.ScaledM(138,value,unit)


def test_native_i32_extremes_are_explicit_and_no_unit_inference():
    assert t.ScaledM(138,Decimal('214748364.7'),2).wire_i32==2147483647
    assert t.ScaledM(138,Decimal('-214748364.8'),2).wire_i32==-2147483648
    with pytest.raises(TypeError):t.ScaledM(138,5)


@pytest.mark.parametrize('count,width,payload',[(59,1,252),(60,2,258),(16379,2,65534),(16380,4,65542)])
def test_attribute_len_part_count_offsets_and_envelope_width_move_together(count,width,payload):
    feature=f('wide',[t.ScaledM(138,5,2)],depcnt=True,parts=tuple(((0,0),(1,1))for _ in range(count)))
    p,b=emit(spec([feature]),budget=2*1024*1024)
    row=p['features'][0]
    assert(row['lengthWidth'],row['payloadBytes'])==(width,payload)
    kind,w,body,_,_=record(b,row['recordOffset'])
    assert int.from_bytes(body[10:10+w],'little')==4 and body[10+w:14+w]==b'2\0\0\0'
    assert int.from_bytes(body[14+w:14+2*w],'little')==count
    for part in(row['parts'][0],row['parts'][-1]):
        assert int.from_bytes(body[14+2*w+4*part['ordinal']:18+2*w+4*part['ordinal']],'little')==part['streamOffset']
        assert b[part['streamOffset']:part['streamOffset']+part['streamBytes']]==b'\0\0\0\0\x88\x01\0\x01\0\0'


def test_area_attributes_precede_part_count_and_retained_tail():
    p,b=emit(spec([f('area',[t.EnumE(62,4),t.EnumE(151,2)],area=True)]));row=p['features'][0]
    kind,w,body,_,_=record(b,row['recordOffset'])
    assert kind==24 and row['layerId']==138 and body[10:14]==b'\x02\x04\x02\x01'
    assert body[-1]==0


@pytest.mark.parametrize('features',[
  [f('a',[t.EnumE(151,2)]),f('a',[t.EnumE(151,3)])],
])
def test_duplicate_source_labels_refuse(features):
    with pytest.raises(ValueError,match='unique'):spec(features)


def test_invalid_class_geometry_duplicate_attr_and_wrong_storage_refuse():
    with pytest.raises(ValueError):t.AttributedFeature(Line32('bad',43,LINE),[])
    with pytest.raises(ValueError):t.AttributedFeature(Area24('bad',42,AREA),[])
    with pytest.raises(ValueError):f('bad',[t.EnumE(62,1),t.EnumE(62,3)])
    with pytest.raises(ValueError):f('bad',[t.ScaledM(138,5,2)])
    with pytest.raises(ValueError):f('bad',[t.EnumE(151,2)],depcnt=True)
    with pytest.raises(ValueError):t.PresenceLayer(138,[f('bad',[t.EnumE(151,2)])])


def test_budget_and_plan_refusals_happen_before_first_sink_write():
    s=spec([f('a',[t.EnumE(151,2)])]);out=io.BytesIO()
    with pytest.raises(ValueError,match='BUDGET'):t.write_typed_batch(s,out,expected_plan_sha256='bad',max_output_bytes=1)
    assert out.getvalue()==b''
    with pytest.raises(ValueError,match='PLAN_SHA'):t.write_typed_batch(s,out,expected_plan_sha256='bad')
    assert out.getvalue()==b''


def test_positive_short_writes_and_invalid_sink_return():
    s=spec([f('a',[t.EnumE(151,2)])]);p,expected=emit(s)
    class Short:
        def __init__(self):self.raw=bytearray()
        def write(self,b):self.raw.extend(b[:2]);return min(2,len(b))
    out=Short();t.write_typed_batch(s,out,expected_plan_sha256=plan_sha256(p));assert out.raw==expected
    class Bad:
        def write(self,b):return True
    with pytest.raises(OSError):t.write_typed_batch(s,Bad(),expected_plan_sha256=plan_sha256(p))


def document():
    return dict(format='no5-own-plain-typed-attribute-batch/1',header=dict(flags=0x1234,raw_bounds=[0,0,100,100],
      raw_grid=[4,4],raw_levels=[8,20],trailing_value=0),layers=[dict(layer_id=37,layer_range=[0,100000],features=[
        dict(kind=32,source_id='depth',class_code=42,parts=LINE,attributes=[dict(storage='M',native_code_u16=138,value_decimal='5',declared_native_units=2)])])],
      date_text_1='20261017',date_text_2='20261017')


def test_exact_json_schema_matches_typed_API_and_refuses_extra_source_fields():
    value=document();s=t.typed_batch_from_spec(value)
    assert emit(s)[1]==emit(spec([f('depth',[t.ScaledM(138,5,2)],depcnt=True)]))[1]
    value['layers'][0]['features'][0]['SCAMIN']=5000
    with pytest.raises(ValueError,match='nothing silently dropped'):t.typed_batch_from_spec(value)


@pytest.mark.parametrize('mutation',[
  lambda a:a.update(unknown=1),lambda a:a.update(storage='X'),
  lambda a:a.update(value_decimal=5),lambda a:a.update(value_decimal='0.01'),
  lambda a:a.pop('declared_native_units')])
def test_json_unknown_storage_extra_fields_and_implicit_units_refused(mutation):
    value=document();mutation(value['layers'][0]['features'][0]['attributes'][0])
    with pytest.raises(ValueError):t.typed_batch_from_spec(value)
