"""Invented raw headers only; no saved native data is packaged here."""
import copy
import math
import struct
import pytest
from no_chart_converter.navionics import raw_header_normalization as m


def plan():
    return dict(rawHeader=dict(flags=12,rawBounds=[1000,-2000,5000,2000],
        rawGrid=[65536,65536],rawLevels=[2,13]),firstLayerOffset=1000)


def test_normalized_grid_and_full_pod_layout_with_explicit_caller_ids():
    p=plan();r=m.predict_header48(p,actual_chart_id_u16=11,map_ordinal_u16=5)
    b=bytes.fromhex(r['nativeHeader48LEHex'])
    assert len(b)==48 and struct.unpack_from('<4H',b)==(11,5,21,12)
    assert struct.unpack_from('<4i',b,8)==tuple(p['rawHeader']['rawBounds'])
    assert struct.unpack_from('<2i',b,24)==(1073725440,1073725440)
    assert struct.unpack_from('<I',b,36)[0]==1000
    assert b[40:44].hex()==r['coarseFieldF32LEHex'] and b[44:].hex()==r['mapBaseF32LEHex']


def test_raw_level_words_are_transient_not_final_lod_or_scamin():
    a=plan();b=copy.deepcopy(a);b['rawHeader']['rawLevels']=[0xffffffff,0]
    x=m.predict_header48(a,actual_chart_id_u16=1,map_ordinal_u16=0)
    y=m.predict_header48(b,actual_chart_id_u16=1,map_ordinal_u16=0)
    assert x['nativeHeader48LEHex']==y['nativeHeader48LEHex']
    assert not x['rawLevelsUsedForFinalNativeLevels'] and not x['sourceSCAMINConverted']


def test_grid_halving_changes_maxzoom_and_base_not_coarse_field():
    a=plan();b=copy.deepcopy(a);b['rawHeader']['rawGrid']=[32768,32768]
    x=m.predict_numeric_header(a);y=m.predict_numeric_header(b)
    assert (x['maxZoomI16'],y['maxZoomI16'])==(21,20)
    assert x['coarseFieldF32LEHex']==y['coarseFieldF32LEHex']
    xf=struct.unpack('<f',bytes.fromhex(x['mapBaseF32LEHex']))[0]
    assert m._hex32(m._f32(xf*2))==y['mapBaseF32LEHex']


def test_rectangular_grid_derives_calcfield_y_extent_before_normalization():
    p=plan();p['rawHeader']['rawGrid']=[32768,65536]
    r=m.predict_numeric_header(p)
    assert r['adjustedCalcFieldYBoundsI32']==[-2000,0]
    assert r['rawBounds']==[1000,-2000,5000,2000] # Stored bounds do not become adjusted Y.
    assert r['normalizedGridI32']==[536854528,1073725440]


def test_vcvt_negative_log_truncates_toward_zero_not_floor():
    level,t=m._level_from_field(0.1*m.HEADER_DIVISOR)
    assert t['logRatioF64'] < -3
    assert math.floor(t['logRatioF64'])+3==-1
    assert level==0 and t['signedBeforeU16Store']==0


def test_native_u16_store_wrap_is_visible_not_clamped_to_authoring_nibbles():
    level,t=m._level_from_field(1.0)
    assert level==65535 and t['signedBeforeU16Store']==-1
    assert t['nativeU16ModuloStoreVisible']


def test_numeric_prediction_does_not_invent_a_runtime_chart_id():
    r=m.predict_numeric_header(plan())
    assert 'nativeHeader48LEHex'not in r and r['runtimeHeaderPrefixUnresolved']
    assert r['normalApiHeaderReadRequiredForUnqualifiedInputs']and r['OriginalCalls']==0
    with pytest.raises(TypeError):m.predict_header48(plan(),map_ordinal_u16=0)


@pytest.mark.parametrize('change',[
    lambda p:p['rawHeader'].update(rawGrid=[1,2]),
    lambda p:p['rawHeader'].update(rawGrid=[65537,65536]),
    lambda p:p['rawHeader'].update(rawGrid=[2.0,2]),
    lambda p:p['rawHeader'].update(rawBounds=[0,0,1,0]),
    lambda p:p['rawHeader'].update(rawBounds=[0,-17779936,100,-17779935]),
    lambda p:p['rawHeader'].update(flags=True),
    lambda p:p['rawHeader'].update(rawLevels=[False,1]),
    lambda p:p.update(firstLayerOffset=0),
    lambda p:p.update(firstLayerOffset=1<<32),
])
def test_unsupported_or_nonnormal_inputs_refuse_instead_of_guessing_header(change):
    p=plan();change(p)
    with pytest.raises(ValueError):m.predict_numeric_header(p)


def test_prefix_unsigned_width_and_map_ordinal_do_not_change_numeric_fields():
    x=m.predict_header48(plan(),actual_chart_id_u16=0,map_ordinal_u16=0)
    y=m.predict_header48(plan(),actual_chart_id_u16=65535,map_ordinal_u16=65535)
    assert bytes.fromhex(x['nativeHeader48LEHex'])[4:]==bytes.fromhex(y['nativeHeader48LEHex'])[4:]
    with pytest.raises(ValueError):m.predict_header48(plan(),actual_chart_id_u16=-1,map_ordinal_u16=0)


def test_no_mutation_and_no_global_native_acceptance_claim():
    p=plan();before=copy.deepcopy(p);r=m.predict_numeric_header(p)
    assert p==before
    assert r['qualification']=='HOST_SOURCE_MODEL_NOT_UNIVERSAL_ARM_LIBM_ORACLE'
    assert r['normalApiHeaderReadRequiredForUnqualifiedInputs']
