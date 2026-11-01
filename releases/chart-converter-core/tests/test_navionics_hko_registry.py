"""Invented H/K/O raw-field controls, no Original execution or source-unit guess."""
from copy import deepcopy
import io
import struct
import pytest

from no_chart_converter.navionics import generic_batch as g, atlas as a
from no_chart_converter.navionics.batch import Point40,Area24,plan_sha256
from no_chart_converter.navionics.generic_attribute_wire import encode_native_value
from no_chart_converter.navionics.writer import RawMapHeader


@pytest.mark.parametrize('storage,values,spec',[
    ('H',[0,1,32765,32768,65534,65535],'<H'),
    ('K',[0,1,0x7fffffff,0x80000000,0xfffffffd],'<I'),
    ('O',[0,1,32767,65533],'<H')])
def test_exact_unsigned_raw_bytes(storage,values,spec):
    for value in values:assert encode_native_value(storage,value)==struct.pack(spec,value)


@pytest.mark.parametrize('storage,value',[
    ('H',-1),('H',65536),('H',32766),('H',32767),('H',True),('H',1.0),
    ('K',-1),('K',0x100000000),('K',0xfffffffe),('K',0xffffffff),('K',False),
    ('O',-1),('O',65534),('O',65535),('O',65536),('O',1.0)])
def test_status_and_type_refusals(storage,value):
    with pytest.raises(ValueError):encode_native_value(storage,value)


def doc():
    return dict(format='no5-own-plain-generic-attribute-batch/1',
        header=dict(flags=0x1234,raw_bounds=[100000,100000,101000,101000],raw_grid=[1024,1024],raw_levels=[8,20],trailing_value=0),
        layers=[
            dict(layer_id=112,layer_range=[0,100000],features=[dict(kind=40,source_id='elevation',class_code=75,
                xy=[100001,100002],attributes=[dict(native_code_u16=85,raw_integer=123,declared_native_units=2)])]),
            dict(layer_id=88,layer_range=[0,100000],features=[dict(kind=40,source_id='multiple-lights',class_code=71,
                xy=[100003,100004],attributes=[dict(native_code_u16=104,raw_integer=3)])]),
            dict(layer_id=70,layer_range=[0,100000],features=[dict(kind=24,source_id='magnetic-area',class_code=91,
                parts=[[[0,0],[3,0],[1,2],[0,0]]],attributes=[dict(native_code_u16=137,raw_integer=14,declared_native_units=1),
                    dict(native_code_u16=140,raw_integer=65535,declared_native_units=5)])])],
        date_text_1='20261018',date_text_2='20261018')


def emitted(value):
    s=g.generic_batch_from_spec(value);p=g.plan_generic_batch(s);f=io.BytesIO()
    g.write_generic_batch(s,f,expected_plan_sha256=plan_sha256(p));return p,f.getvalue()


def attr_payload(raw,row):
    at=row['recordOffset']+2+row['lengthWidth']+(10 if row['kind']in(24,32)else 2)
    assert int.from_bytes(raw[at:at+row['lengthWidth']],'little')==row['attributeBytes']
    at+=row['lengthWidth'];return raw[at:at+row['attributeBytes']]


def test_three_float_and_integer_fields_real_dictionary_class_positions():
    p,b=emitted(doc());assert [f['classCode']for f in p['features']]==[75,71,91]
    assert [attr_payload(b,r)for r in p['features']]==[struct.pack('<I',123),struct.pack('<H',3),struct.pack('<HH',14,65535)]
    assert [x['storage']for r in p['features']for x in r['attributes']]==['K','O','H','H']
    assert p['features'][1]['attributes'][0]['nativeIntegerDivision']=='UNSIGNED_U32_AFTER_U16_READ'


@pytest.mark.parametrize('code,value,units',[(85,100,None),(137,100,None),(140,-1,5),(104,3,0)])
def test_numeric_unit_premise_and_no_signed_h_reinterpretation(code,value,units):
    with pytest.raises(ValueError):g.GenericAttribute(code,value,units)


def test_stale_hko_plan_and_bad_units_refuse_before_write():
    value=doc();p,_=emitted(value);value['layers'][0]['features'][0]['attributes'][0]['raw_integer']=124
    s=g.generic_batch_from_spec(value);f=io.BytesIO()
    with pytest.raises(ValueError):g.write_generic_batch(s,f,expected_plan_sha256=plan_sha256(p))
    assert not f.getvalue()
    value=doc();value['layers'][1]['features'][0]['attributes'][0]['declared_native_units']=0
    with pytest.raises(ValueError):g.generic_batch_from_spec(value)


def test_single_generic_hko_atlas_matches_standalone():
    value=doc();p,expected=emitted(value);s=a.atlas_from_spec(dict(format='no5-own-plain-atlas/1',maps=[value]))
    plan=a.plan_atlas(s);f=io.BytesIO();a.write_atlas(s,f,expected_plan_sha256=plan_sha256(plan))
    assert f.getvalue()==expected


def geometry(kind,code,name):
    if kind==40:return Point40(name,code,(100001,100002))
    if kind==32:
        from no_chart_converter.navionics.batch import Line32
        return Line32(name,code,(((0,0),(3,2)),))
    return Area24(name,code,(((0,0),(3,0),(1,2),(0,0)),))


@pytest.mark.parametrize('code,kind,layer',[(4,24,34),(4,40,32),(144,40,144),
    (1,40,12),(10,24,106),(10,32,105),(91,32,69),(131,40,136)])
def test_positive_new_exact_classes_and_routes(code,kind,layer):
    f=g.GenericAttributedFeature(geometry(kind,code,'route'),[])
    assert f.layer_id==layer
    s=g.GenericAttributedBatchSpec(RawMapHeader(0x1234,(100000,100000,101000,101000),(1024,1024),(8,20),0),g.group_generic_features([f]))
    p=g.plan_generic_batch(s);assert p['layers'][0]['layerId']==layer
    assert 'classExpansionContractSHA256'in p


@pytest.mark.parametrize('code,source',[(7,8),(9,9),(13,11),(30,30),(34,36),
    (62,60),(60,61),(61,62),(103,109),(120,131),(151,187)])
def test_standard_case_sensitive_source_code_metadata_fixes(code,source):
    assert g.ATTRIBUTES[code]['sourceCode']==source


def test_generic_retains_previously_typed_source_slcons_set():
    for value in (1,3,4,6,8,13):
        assert g.GenericAttribute(62,value).wire_bytes({})==bytes((value,))
    assert set(g.qualified_enum_values(151))>={2,3,4,5}


def test_status_global_domain_does_not_create_missing_rtpbcn_class_slot():
    assert g.GenericAttribute(131,[4,5]).wire_bytes({})==b'\x02\x04\x05'
    with pytest.raises(ValueError):
        g.GenericAttributedFeature(Point40('no-member',124,(100001,100002)),[g.GenericAttribute(131,[4])])
    assert 131 not in g.CLASSES[124]['nativeAttributeOrder']


def test_missing_exact_newobj_and_unqualified_nature_values_not_invented():
    assert all(row['acronym']not in('NEWOBJ','M_NPUB','M_SREL','ROADWY')for row in g.CLASSES.values())
    with pytest.raises(ValueError):g.GenericAttribute(108,['7.'])
    with pytest.raises(ValueError):g.GenericAttribute(107,[1,None])


EXTRA_VALUES = {101:[3,4,5,7,12,28],103:[10],117:[11],120:[3,5],131:[4,5],
    135:[4],151:[5],30:[5],33:[2],38:[2],56:[1],60:[1,3],61:[12],
    62:[1,2,3,4,6,8,10,13],64:[39],7:[8],73:[6],77:[1,2],88:[2,3],9:[1,3,5,10]}


@pytest.mark.parametrize('code,value',[(code,value)for code,values in EXTRA_VALUES.items()for value in values])
def test_every_finite_additional_source_native_enum_pair(code,value):
    storage=g.ATTRIBUTES[code]['storage'];v=value if storage=='E'else[value]
    attr=g.GenericAttribute(code,v)
    assert attr.wire_bytes({})==(bytes((value,))if storage=='E'else bytes((1,value)))


@pytest.mark.parametrize('code,value',[(7,10),(30,12),(36,21),(48,10),(52,28),
    (62,12),(64,55),(120,17),(120,26),(151,7)])
def test_explicitly_unpaired_enum_outcomes_remain_refused(code,value):
    v=value if g.ATTRIBUTES[code]['storage']=='E'else[value]
    with pytest.raises(ValueError):g.GenericAttribute(code,v)
