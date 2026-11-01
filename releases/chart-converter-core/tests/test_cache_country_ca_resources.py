"""Synthetic numeric recipes only; no chart, OEM payload or saved owner input."""
import json
import struct
import pytest
from no_chart_converter.cache_country.ca_resources import compile_ca_resource, Refusal
from no_chart_converter.cache_country.light_sectors import LightSectorRefusal

PALETTE={'LITRD':[241,19,0],'LITGN':[0,200,0]}
NEW='CA(OUTLW, 4,LITRD, 2,190.0, 207.0,  10.0,  25.0)'

def compile_(token,ident=91050):
    return compile_ca_resource(token,palette=PALETTE,resource_id=ident)


def test_new_sector_has_own_definition_and_bounded_arcs():
    item=compile_(NEW);resource=item['resource']
    assert resource['name']=='N5F058CB'
    assert item['genericSourceCARecipe']['sourceFeatureIdentityUsed'] is False
    assert item['genericSourceCARecipe']['canonicalNumericToken']=='CA(OUTLW,4,LITRD,2,190,207,10,25)'
    assert 'SY('+resource['name']+')'==item['programTransform']['targetProgrammeASCII']
    assert len(resource['SYMD'])==39
    for line in resource['common41FragmentASCII'].splitlines():
        tail=line[6:];i=0
        while tail[i].isdigit():i+=1
        assert len(tail[i:].encode('ascii'))==int(tail[:i])
    assert item['centerlineProofs'][0]['combinedCenterlineHausdorffBoundPx']<=0.05
    assert item['centerlineProofs'][0]['angleSweepRadians']>0
    assert len(item['legs'])==2
    assert all(leg['dashLengthPx']>=4 and leg['gapLengthPx']>=4 for leg in item['legs'])


def test_equal_numeric_lexemes_share_definition_without_owner_key():
    a=compile_(NEW);b=compile_('CA(OUTLW,4,LITRD,2,1.9e2,207,10,25)')
    assert a['resource']==b['resource']
    assert a['genericSourceCARecipe']['originalToken']!=b['genericSourceCARecipe']['originalToken']
    assert 'uid' not in a['sourceScope']


def test_nearby_sector_is_not_a_substitute():
    a=compile_(NEW);b=compile_('CA(OUTLW,4,LITRD,2,190,210,10,25)')
    assert a['resource']['name']!=b['resource']['name']
    assert a['resource']['SVCT']!=b['resource']['SVCT']
    assert a['evaluatedSourceGLSL']['endDegreesF32']==207
    assert b['evaluatedSourceGLSL']['endDegreesF32']==210


def test_float64_angle_identity_is_retained_before_f32_portrayal():
    a=compile_('CA(OUTLW,4,LITRD,2,190.00000000000003,207,10,25)')
    b=compile_(NEW)
    assert a['genericSourceCARecipe']['parameters'][4]!=b['genericSourceCARecipe']['parameters'][4]
    assert a['resource']['name']!=b['resource']['name']


def test_full_circle_uses_native_circle_without_invented_sector_legs():
    item=compile_('CA(OUTLW,4,LITGN,2,0,360,10,0)')
    assert item['legs']==[]
    assert item['centerlineProofs'][0]['kind']=='native_CI_addEllipse'
    assert item['resource']['SVCT'].count(';CI')==2
    assert item['genericSourceCARecipe']['atlasPublicationRequired'] is True


@pytest.mark.parametrize('token',[
    'CA(OUTLW,4,LITRD,2,190,207,10.5,25)',
    'CA(OUTLW,4,LITRD,2,NaN,207,10,25)',
    'CA(OUTLW,4,LITRD,0,190,207,10,25)',
    'CA(OUTLW,4,LITRD,2,190,207,10,25);SY(OTHER001)',
])
def test_invalid_recipes_are_refused(token):
    with pytest.raises((Refusal,LightSectorRefusal)):
        compile_(token)
