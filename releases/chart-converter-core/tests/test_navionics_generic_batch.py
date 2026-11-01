"""Invented generic attribute/resource/layout tests, no runtime or private IO."""
from copy import deepcopy
from dataclasses import replace
import io
import struct
import pytest

from no_chart_converter.navionics import generic_batch as g, atlas as a
from no_chart_converter.navionics.batch import Point40, Line32, Area24, NativeLevelRange, plan_sha256
from no_chart_converter.navionics.layered_batch import DepthPoint44, PlainLayer, LayeredBatchSpec
from no_chart_converter.navionics.writer import RawMapHeader


def header(): return RawMapHeader(0x1234,(100000,100000,101000,101000),(1024,1024),(8,20),0)


def point(name='light',text='Own café'):
    return g.GenericAttributedFeature(Point40(name,71,(100001,100002),NativeLevelRange(0,15)),[
        g.GenericAttribute(109,text),g.GenericAttribute(127,'(2)'),
        g.GenericAttribute(72,[3,4,3]),g.GenericAttribute(101,13),
        g.GenericAttribute(90,60,2),g.GenericAttribute(80,b'AB')])


def spec(features=None):
    features=[point()] if features is None else features
    return g.GenericAttributedBatchSpec(header(),g.group_generic_features(features))


def emit(s,atlas=False,budget=4096,sink=None):
    planner=a.plan_atlas if atlas else g.plan_generic_batch
    writer=a.write_atlas if atlas else g.write_generic_batch
    p=planner(s,max_output_bytes=budget); sink=io.BytesIO() if sink is None else sink
    r=writer(s,sink,expected_plan_sha256=plan_sha256(p),max_output_bytes=budget)
    return p,sink.getvalue(),r


def record(raw,offset):
    tag=struct.unpack_from('<H',raw,offset)[0]
    width={0:1,1:2,2:4}[(tag>>6)&3]
    n=int.from_bytes(raw[offset+2:offset+2+width],'little')
    return tag&63,width,offset+2+width,n


def assert_pointer_walk(raw,p):
    for row in p['layers']:
        kind,w,at,n=record(raw,row['recordOffset']);assert(kind,n)==(18,19+9*len(row.get('maskRows',[])))
        assert raw[at]==row['layerId']
        assert struct.unpack_from('<I',raw,at+3)[0]==row['firstFeatureOffset']
        for i,mask in enumerate(row.get('maskRows',[])):
            assert struct.unpack_from('<BII',raw,at+19+i*9)==(mask['classCode'],mask['mask0'],mask['mask1'])
        assert raw[row['featureEndOffset']:row['featureEndOffset']+3]==b'\x00\x80\x04'
    assert raw[p['layerEndOffset']:p['layerEndOffset']+3]==b'\x00\x00\x04'
    for row in p['features']:
        kind,w,at,n=record(raw,row['recordOffset']);assert(kind,w,n)==(row['kind'],row['lengthWidth'],row['payloadBytes'])
        attr_at=at+(10 if kind in (24,32)else 2)+w
        assert int.from_bytes(raw[attr_at-w:attr_at],'little')==row.get('attributeBytes',0)
        after=attr_at+row.get('attributeBytes',0)
        if kind in (24,32):
            assert int.from_bytes(raw[after:after+w],'little')==row['partCount']
            refs=struct.unpack_from('<'+'I'*row['partCount'],raw,after+w)
            assert refs==tuple(part['streamOffset']for part in row['parts'])
        elif kind==40: assert struct.unpack_from('<ii',raw,after)==tuple(row['rawPointXY'])
        else: assert struct.unpack_from('<Hii',raw,after)==(row['depthWord'],*row['rawPointXY'])


def test_point40_has_attributes_then_xy_without_invented_depth():
    p,raw,r=emit(spec());f=p['features'][0];_,w,at,n=record(raw,f['recordOffset'])
    expected=b'\x03\x03\x04\x03'+b'\x02AB'+struct.pack('<H',60)+b'\x0d'+struct.pack('<HH',9,10)
    # Class order: COLOUR, DATEND, HEIGHT, LITCHR, OBJNAM, SIGGRP.
    assert raw[at:at+2]==bytes((71,15))
    assert raw[at+2:at+2+w]==len(expected).to_bytes(w,'little')
    assert raw[at+2+w:at+2+w+len(expected)]==expected
    assert n==10+w+len(expected)
    assert_pointer_walk(raw,p);assert r['nativeReaderExecuted']is False


def test_bmp_directory_resource_ids_and_exact_text_lengths():
    p,raw,_=emit(spec());res=p['textResources'];assert res['offset']==320
    kind,w,at,n=record(raw,320);assert(kind,w,n)==(5,1,7)
    assert raw[at:at+3]==b'EN\x01';target=struct.unpack_from('<I',raw,at+3)[0]
    assert target==330
    kind,w,at,n=record(raw,target);assert kind==48
    assert struct.unpack_from('<HH',raw,at)==(9,10)
    cursor=at+4
    for id_,text in [(9,'Own café'),(10,'(2)')]:
        tid,extent=struct.unpack_from('<HH',raw,cursor);cursor+=4
        assert tid==id_ and raw[cursor:cursor+extent].decode('utf-16le')==text
        cursor+=extent
    assert cursor==at+n and p['bankOffset']is None


def test_soundg_preserves_depth_and_quality_attributes():
    f=g.GenericAttributedFeature(DepthPoint44('sounding',(100003,100004),101),[
        g.GenericAttribute(117,[1,3]),g.GenericAttribute(130,4,2)])
    p,raw,_=emit(spec([f]));row=p['features'][0];_,w,at,n=record(raw,row['recordOffset'])
    attrs=b'\x02\x01\x03'+struct.pack('<H',4)
    assert raw[at:at+2]==bytes((136,0)) and n==12+w+len(attrs)
    assert raw[at+2+w:at+2+w+len(attrs)]==attrs
    assert raw[at+2+w+len(attrs):at+n]==struct.pack('<Hii',101,100003,100004)
    assert_pointer_walk(raw,p)


def test_presence_runs_never_reorder_missing_or_empty_values():
    f=point();empty=g.GenericAttributedFeature(Point40('empty',71,(100002,100003)),[g.GenericAttribute(72,[])])
    again=replace(f,geometry=replace(f.geometry,source_id='again'))
    s=spec([f,empty,again]);p,raw,_=emit(s)
    assert [x.layer_id for x in s.layers]==[88,88,88]
    assert [x['sourceId']for x in p['features']]==['light','empty','again']
    assert p['features'][1]['attributeBytes']==1
    assert_pointer_walk(raw,p)


def test_mixed_atlas_one_global_text_pool_and_all_rebased_pointers():
    rawmap=LayeredBatchSpec(header(),[PlainLayer(60,[Point40('raw',126,(100001,100002))])],
                           '20261018','20261018')
    first=spec();second=spec([point('second'),point('third','Another')])
    atlas=a.AtlasSpec([rawmap,first,second]);p,raw,_=emit(atlas,True,budget=8192)
    res=p['textResources'];assert res['offset']==p['headerGroup']['lastOffset']+1
    assert [v['textId']for v in res['entries']]==[9,10,11]
    assert [v['text']for v in res['entries']]==['Own café','(2)','Another']
    assert p['maps'][0]['bodyOffset']==res['offset']+res['bytes']
    assert p['genericMapOrdinals']==[1,2]
    for row in p['maps']:
        assert_pointer_walk(raw,row['bodyPlan'])
        if row['ordinal']:
            assert row['bodyPlan']['textResources']['bytes']==0
            assert row['bodyPlan']['textResources']['ownership']=='ATLAS_SHARED'


@pytest.mark.parametrize('parts,width',[(60,1),(61,2),(16380,2),(16381,4)])
def test_coupled_vector_widths_rebuilt_with_generic_attributes(parts,width):
    # SLCONS WATLEV: one-byte attribute adds one to the fixed-width candidates.
    f=g.GenericAttributedFeature(Line32('line',131,(((0,0),(3,2)),)*parts),[g.GenericAttribute(151,2)])
    p,raw,_=emit(spec([f]),budget=1<<20)
    row=p['features'][0];assert row['lengthWidth']==width
    assert_pointer_walk(raw,p)


@pytest.mark.parametrize('n,width',[(243,1),(244,2)])
def test_point_attribute_length_and_record_width_are_coupled(n,width):
    f=g.GenericAttributedFeature(Point40('buoy',11,(100001,100002)),[g.GenericAttribute(80,b'x'*n)])
    p,raw,_=emit(spec([f]));assert p['features'][0]['lengthWidth']==width
    assert_pointer_walk(raw,p)


def test_raw_m_signed_and_area_ring_parts_preserved():
    rings=(((0,0),(10,0),(10,10),(0,0)),((2,2),(3,2),(2,3),(2,2)))
    f=g.GenericAttributedFeature(Area24('depth-area',41,rings),[g.GenericAttribute(83,50,2),g.GenericAttribute(82,-10,2)])
    p,raw,_=emit(spec([f]));row=p['features'][0]
    assert [v['native_code_u16']for v in row['attributes']]==[82,83]
    _,w,at,_=record(raw,row['recordOffset'])
    assert raw[at+10+w:at+18+w]==struct.pack('<ii',-10,50)
    assert row['partCount']==2;assert_pointer_walk(raw,p)


@pytest.mark.parametrize('code,value,units',[(101,6,None),(72,[254],None),(72,[1]*254,None),
    (90,65534,2),(90,True,2),(90,60,None),(82,0x7fffffff,2),
    (80,b'x'*254,None),(109,'\x00',None),(109,'\U0001f600',None),
    (109,'x'*1023,None),(109,9,None),(2,1,2),(0,1,None)])
def test_unqualified_values_statuses_or_text_refuse(code,value,units):
    with pytest.raises(ValueError):g.GenericAttribute(code,value,units)


def test_dictionary_membership_geometry_and_presence_layer_strict():
    with pytest.raises(ValueError):g.GenericAttributedFeature(Point40('bad',11,(100001,100002)),[g.GenericAttribute(90,10,2)])
    with pytest.raises(ValueError):g.GenericAttributedFeature(Line32('bad',71,(((0,0),(1,2)),)),[])
    with pytest.raises(ValueError):g.GenericAttributedFeature(Point40('bad',71,(100001,100002)),[g.GenericAttribute(72,[3]),g.GenericAttribute(72,[4])])
    with pytest.raises(ValueError):g.GenericPresenceLayer(89,[point()])


def json_doc():
    return dict(format='no5-own-plain-generic-attribute-batch/1',header=header().__dict__,
        layers=[dict(layer_id=88,layer_range=[0,100000],features=[dict(kind=40,source_id='json',class_code=71,
            xy=[100001,100002],native_level_range=dict(min_level=0,max_level=15),attributes=[
                dict(native_code_u16=72,enum_u8s=[3,4]),dict(native_code_u16=90,raw_integer=60,declared_native_units=2),
                dict(native_code_u16=109,text='Own café')])])],date_text_1='20261018',date_text_2='20261018')


def test_exact_json_standalone_and_nested_generic_dispatch():
    doc=json_doc();s=g.generic_batch_from_spec(doc);p,raw,_=emit(s)
    atlas=a.atlas_from_spec(dict(format='no5-own-plain-atlas/1',maps=[doc]))
    q,other,_=emit(atlas,True)
    assert raw==other
    assert q['maps'][0]['bodyPlan']['features'][0]['attributes']==p['features'][0]['attributes']


@pytest.mark.parametrize('field,value',[('Z',0),('attributes',None),('native_level_range',{'min_level':0,'max_level':16})])
def test_unknown_feature_fields_no_fabricated_depth(field,value):
    doc=json_doc();doc['layers'][0]['features'][0][field]=value
    with pytest.raises(ValueError):g.generic_batch_from_spec(doc)


def test_typed_attribute_json_cannot_alias_storage_or_units():
    for bad in [dict(native_code_u16=109,raw_hex='4142'),dict(native_code_u16=90,raw_integer=60),
                dict(native_code_u16=72,enum_u8=3),dict(native_code_u16=80,raw_hex='41 42')]:
        doc=json_doc();doc['layers'][0]['features'][0]['attributes']=[bad]
        with pytest.raises(ValueError):g.generic_batch_from_spec(doc)


def test_stale_text_plan_and_budget_refuse_before_sink_write():
    before=spec();plan=g.plan_generic_batch(before)
    after=spec([point(text='Own different')]);sink=io.BytesIO()
    with pytest.raises(ValueError):g.write_generic_batch(after,sink,expected_plan_sha256=plan_sha256(plan))
    assert not sink.getvalue()
    with pytest.raises(ValueError):g.write_generic_batch(before,sink,expected_plan_sha256=plan_sha256(plan),max_output_bytes=plan['totalBytes']-1)
    assert not sink.getvalue()


def test_shortwrites_exact_and_partial_io_reported_honestly():
    class Short(io.BytesIO):
        def write(self,data):return super().write(data[:3])
    s=spec();assert emit(s)==emit(s,sink=Short())
    class Broken(io.BytesIO):
        def write(self,data):
            if self.tell()>100:raise OSError('invented failure')
            return super().write(data[:23])
    sink=Broken()
    with pytest.raises(OSError):emit(s,sink=sink)
    assert 0<len(sink.getvalue())<g.plan_generic_batch(s)['totalBytes']


def test_empty_and_duplicate_text_preserved_without_silent_missing():
    s=spec([point('a',''),point('b','')]);p,raw,_=emit(s)
    assert [e['text']for e in p['textResources']['entries']]==['','(2)']
    assert [f['attributes'][-2]['text_id']for f in p['features']]==[9,9]
    assert_pointer_walk(raw,p)


def test_storage_s_raw_recipe_does_not_invent_unqualified_class_membership():
    value=g.GenericAttribute(167,b'\0\x80')
    assert value.storage=='S' and value.wire_bytes({})==b'\x02\0\x80'
    with pytest.raises(ValueError):g.GenericAttributedFeature(Point40('no-slot',71,(100001,100002)),[value])


@pytest.mark.parametrize('text,count,width',[('x',1,1),('x'*100,2,2),('x'*1000,34,4)])
def test_text_pool_record_width_and_absolute_reference(text,count,width):
    # Distinct strings retain IDs; the U32 record-size case is software-only.
    features=[point('p'+str(i),text+str(i)) for i in range(count)]
    p,raw,_=emit(spec(features),budget=1<<20)
    _,_,at,_=record(raw,320);target=struct.unpack_from('<I',raw,at+3)[0]
    assert record(raw,target)[1]==width
    assert_pointer_walk(raw,p)


def test_explicit_text_pool_budget_refuses_before_any_sink_write(monkeypatch):
    monkeypatch.setattr(g,'MAX_TEXT_POOL_BYTES',20)
    sink=io.BytesIO()
    with pytest.raises(ValueError):g.write_generic_batch(spec(),sink,expected_plan_sha256='0'*64)
    assert not sink.getvalue()


def test_shared_resources_rebase_vector_bank_following_mixed_map_families():
    from no_chart_converter.navionics import typed_batch as t
    line=Line32('generic-line',131,(((0,0),(3,2)),)*61)
    vec=g.GenericAttributedFeature(line,[g.GenericAttribute(151,2)])
    generic=spec([point(),vec])
    typed=t.TypedBatchSpec(header(),t.group_typed_features([
        t.AttributedFeature(Line32('typed',42,(((0,0),(3,2)),)),[t.ScaledM(138,5,2)])]),
        '20261018','20261018')
    rawmap=LayeredBatchSpec(header(),[PlainLayer(60,[Point40('raw',126,(100001,100002))])],
                           '20261018','20261018')
    p,raw,_=emit(a.AtlasSpec([typed,generic,rawmap]),True,budget=8192)
    assert p['typedMapOrdinals']==[0] and p['genericMapOrdinals']==[1]
    row=p['maps'][1]['bodyPlan'];assert row['bankOffset']==p['maps'][1]['bodyOffset']
    assert row['features'][1]['lengthWidth']==2
    for m in p['maps']:
        assert_pointer_walk(raw,m['bodyPlan'])
        kind,_,at,n=record(raw,m['headerOffset']);assert(kind,n)==(17,52)
        assert struct.unpack_from('<I',raw,at+n-4)[0]==m['bodyPlan']['firstLayerOffset']


@pytest.mark.parametrize('value',[1,2,8])
def test_original_litchr_aliases_and_finite_source_expansion_only(value):
    assert g.GenericAttribute(101,value).wire_bytes({})==bytes((value,))
    assert set(g.EXPLICIT_ENUM_ALIASES[101])=={1,2,8}
    assert set(g.qualified_enum_values(101))-set(g.PAIRED_ENUMS[101])=={1,2,8,3,4,5,7,12,28}
    assert g.qualified_enum_values(72)==g.PAIRED_ENUMS[72]
