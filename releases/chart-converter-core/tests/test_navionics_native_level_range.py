"""Invented optional native nibbles; no scale-denominator or portrayal claim."""
from copy import deepcopy
import hashlib
import io

import pytest

from no_chart_converter.navionics import batch as b, layered_batch as l
from no_chart_converter.navionics import typed_batch as t, atlas as a


# Whole-plan and whole-wire hashes from the published pre-opt-in implementation,
# using only the invented documents below. No runtime data or chart fixtures.
BASELINE = {'raw': ('8ab604e2c6e0a56b14baefeb09935e596062700915289d3611dd5e07673de8d0', 'b57382178ea9e76f741a7932c6e18fead1bfecafa340e9b5c968865b94bd8ccc'), 'layered': ('eac72ee003658bdd707426361ce9e4620c6bf5083870d253c618b0b244f1d6ee', '6364003e9a30fa0f5ef9383037b3b3bef0e1177098b89b0bd1f540ae2f4660a5'), 'typed': ('1956af9a9eafb4e0554740636c626cc3de993f43f2550c46127647e1458e4ace', '7a80937614666be40e41e925eb4d218134db3d94f61c23a2131049f76dc4543d'), 'atlas': ('4b00cb9946ee7df40360e84dc61943c0d79814a0b6db69cf14cc18e284c431b7', '76c2ad29d0a5ec7ab121611f1c5be9ff7f89fa0afe83a554a941a1070c379058')}


def header():
    return dict(flags=0x1234, raw_bounds=[100000, 100000, 101000, 101000],
                raw_grid=[1024, 1024], raw_levels=[8, 20], trailing_value=0)


def raw_doc(prefix=''):
    return dict(format='no5-own-plain-feature-batch/1', header=header(),
        features=[dict(kind=40, source_id=prefix+'p', class_code=126, xy=[100001,100002]),
                  dict(kind=32, source_id=prefix+'l', class_code=29,
                       parts=[[[0,0],[3,2]]]),
                  dict(kind=24, source_id=prefix+'a', class_code=74,
                       parts=[[[0,0],[3,0],[1,2],[0,0]]])],
        layer_id=60, layer_range=[0,100000],
        date_text_1='20261018', date_text_2='20261018')


def layered_doc(prefix=''):
    value=raw_doc(prefix)
    features=value.pop('features')
    value.pop('layer_id'); value.pop('layer_range')
    value['format']='no5-own-plain-layered-feature-batch/1'
    value['layers']=[dict(layer_id=code, layer_range=[0,100000], features=[f])
                     for code,f in zip([60,61,62],features)]
    value['layers'].append(dict(layer_id=140,layer_range=[0,100000],features=[
        dict(kind=44,source_id=prefix+'d',xy=[100003,100004],mantissa=50,decimal_scale=0)]))
    return value


def typed_doc(prefix=''):
    line=[[0,0],[3,2]]
    area=[[0,0],[3,0],[1,2],[0,0]]
    features=[
        dict(kind=32,source_id=prefix+'contour',class_code=42,parts=[line]*61,
             attributes=[dict(storage='M',native_code_u16=138,value_decimal='5.0',declared_native_units=2)]),
        dict(kind=24,source_id=prefix+'depth-area',class_code=41,parts=[area],
             attributes=[dict(storage='M',native_code_u16=82,value_decimal='0.0',declared_native_units=2),
                         dict(storage='M',native_code_u16=83,value_decimal='5.0',declared_native_units=2)]),
        dict(kind=24,source_id=prefix+'shore-area',class_code=131,parts=[area],
             attributes=[dict(storage='E',native_code_u16=62,enum_u8=13),
                         dict(storage='E',native_code_u16=151,enum_u8=3)])]
    return dict(format='no5-own-plain-typed-attribute-batch/1',header=header(),
        layers=[dict(layer_id=code,layer_range=[0,100000],features=[f])
                for code,f in zip([37,38,138],features)],
        date_text_1='20261018',date_text_2='20261018')


def document(mode):
    if mode=='raw': return raw_doc()
    if mode=='layered': return layered_doc()
    if mode=='typed': return typed_doc()
    return dict(format='no5-own-plain-atlas/1',maps=[layered_doc('one-'),typed_doc('two-'),layered_doc('three-')])


def feature_rows(doc):
    if doc['format']=='no5-own-plain-atlas/1':
        return [f for m in doc['maps'] for f in feature_rows(m)]
    if 'features' in doc: return doc['features']
    return [f for layer in doc['layers'] for f in layer['features']]


def api(mode):
    return {'raw':(b.batch_from_spec,b.plan_plain_batch,b.write_plain_batch),
            'layered':(l.layered_batch_from_spec,l.plan_layered_batch,l.write_layered_batch),
            'typed':(t.typed_batch_from_spec,t.plan_typed_batch,t.write_typed_batch),
            'atlas':(a.atlas_from_spec,a.plan_atlas,a.write_atlas)}[mode]


def emit(mode,doc,sink=None):
    parser,planner,writer=api(mode)
    spec=parser(doc); plan=planner(spec)
    sink=io.BytesIO() if sink is None else sink
    receipt=writer(spec,sink,expected_plan_sha256=b.plan_sha256(plan))
    return plan,sink.getvalue(),receipt


def rows(plan):
    if 'maps' in plan:
        return [f for m in plan['maps'] for f in m['bodyPlan']['features']]
    return plan['features']


def flag_offset(row):
    return row['recordOffset']+2+row['lengthWidth']+(9 if row['kind'] in (24,32) else 1)


@pytest.mark.parametrize('mode',['raw','layered','typed','atlas'])
def test_legacy_absent_and_none_keep_whole_plan_and_wire_hashes(mode):
    doc=document(mode); plan,wire,_=emit(mode,doc)
    assert (b.plan_sha256(plan),hashlib.sha256(wire).hexdigest())==BASELINE[mode]
    assert all('nativeLevelRange' not in r for r in rows(plan))
    for f in feature_rows(doc): f['native_level_range']=None
    other,raw,_=emit(mode,doc)
    assert other==plan and raw==wire


@pytest.mark.parametrize('mode',['raw','layered','typed','atlas'])
def test_opt_in_changes_only_flags_preserving_pointers_attributes_and_widths(mode):
    doc=document(mode); before,old,_=emit(mode,doc)
    for f in feature_rows(doc): f['native_level_range']=dict(min_level=0,max_level=15)
    plan,wire,receipt=emit(mode,doc)
    offsets=[flag_offset(r) for r in rows(plan)]
    assert [i for i,(x,y) in enumerate(zip(old,wire)) if x!=y]==offsets
    assert len(old)==len(wire)
    assert all(wire[i]==15 and old[i]==0 for i in offsets)
    assert all(r['nativeLevelRange']==dict(minLevel=0,maxLevel=15,packedByte=15) for r in rows(plan))
    assert [(r['recordOffset'],r['lengthWidth'],r['parts']) for r in rows(plan)]==[
        (r['recordOffset'],r['lengthWidth'],r['parts']) for r in rows(before)]
    assert receipt['nativeReaderExecuted'] is False
    if mode in ('typed','atlas'):
        assert any(r['lengthWidth']==2 for r in rows(plan))


@pytest.mark.parametrize('mode',['raw','layered','typed','atlas'])
def test_explicit_zero_same_wire_but_binds_caller_intent_and_prewrite_plan(mode):
    doc=document(mode); before,old,_=emit(mode,doc)
    feature_rows(doc)[0]['native_level_range']=dict(min_level=0,max_level=0)
    plan,wire,_=emit(mode,doc)
    assert wire==old and b.plan_sha256(plan)!=b.plan_sha256(before)
    parser,_,writer=api(mode); sink=io.BytesIO()
    with pytest.raises(ValueError):
        writer(parser(doc),sink,expected_plan_sha256=b.plan_sha256(before))
    assert sink.getvalue()==b''


@pytest.mark.parametrize('minimum,maximum',[(0,0),(0,15),(7,7),(4,12),(15,15)])
def test_exact_nibble_encoding(minimum,maximum):
    value=b.NativeLevelRange(minimum,maximum)
    assert value.packed_byte==(minimum<<4)|maximum


@pytest.mark.parametrize('value',[{},dict(min_level=0),dict(min_level=-1,max_level=15),
    dict(min_level=0,max_level=16),dict(min_level=3,max_level=2),
    dict(min_level=True,max_level=15),dict(min_level=0,max_level=False),
    dict(min_level=0.0,max_level=15),dict(min_level=0,max_level='15'),
    dict(min_level=0,max_level=15,SCAMIN=1),[0,15],False])
@pytest.mark.parametrize('mode',['raw','layered','typed','atlas'])
def test_strict_json_refuses_invalid_ranges_or_scale_denominator_fields(mode,value):
    doc=document(mode); feature_rows(doc)[0]['native_level_range']=value
    with pytest.raises(ValueError): api(mode)[0](doc)


def test_forged_mutated_range_rejected_before_write():
    spec=b.batch_from_spec(raw_doc()); plan=b.plan_plain_batch(spec)
    bad=b.NativeLevelRange(0,15)
    object.__setattr__(bad,'max_level',16)
    object.__setattr__(spec.features[0],'native_level_range',bad)
    sink=io.BytesIO()
    with pytest.raises(ValueError): b.write_plain_batch(spec,sink,expected_plan_sha256=b.plan_sha256(plan))
    assert not sink.getvalue()


def test_positive_short_writes_preserve_mixed_range_wire():
    class Short(io.BytesIO):
        def write(self,data): return super().write(data[:7])
    doc=document('atlas')
    for index,f in enumerate(feature_rows(doc)):
        f['native_level_range']=dict(min_level=index%8,max_level=15-index%8)
    expected=emit('atlas',doc)
    actual=emit('atlas',deepcopy(doc),Short())
    assert actual==expected
