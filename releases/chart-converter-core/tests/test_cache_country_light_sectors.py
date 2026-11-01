"""Invented recipe/atlas; structural adapter tests, no native pixel claim."""
import copy
import struct
import zlib

import pytest

from no_chart_converter.cache_country import light_sectors as light


def sample():
    name = 'N5TEST01'
    fragment = b'SYMB  10SY00001NIL\nSYMD  39N5TEST01V00000000000010000100-0050-0050\nSCRF  6ACHBLK\nSVCT  15SPA;PU0,0;CI5;\n'
    def chunk(kind, body):
        return struct.pack('>I', len(body)) + kind + body + struct.pack('>I', zlib.crc32(kind+body))
    png = b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', 8,8,8,6,0,0,0))
    png += chunk(b'IDAT', zlib.compress(bytes(8*(1+8*4)))) + chunk(b'IEND', b'')
    row = b'N5TEST01,1,0,0,8,8,-4,-4'
    resources = {64:b'CHBLK;0;0;0;0;0;0;0\n',65:fragment,68:png,69:row+b'\n'}
    token = 'CA(OUTLW,4,LITRD,2,359,1,15,25)'
    mapping = dict(format=light.FORMAT, sizingPolicy=light.SIZING_POLICY,
                   resourceSHA256={str(k):light.sha(v)for k,v in resources.items()},
                   bindings=[dict(sourceCA=token,symbolName=name,definitionSHA256=light.sha(fragment),atlasRowSHA256=light.sha(row))])
    feature = dict(classAcronym='LIGHTS', geometry={'kind':'point','position':[9.,54.]}, sourceFeatureId=50193,
                   attributes=[{'acronym':'SECTR1','type':'float64','value':179.},
                               {'acronym':'SECTR2','type':'float64','value':181.},
                               {'acronym':'COLOUR','type':'uint32','value':3},
                               {'acronym':'VALNMR','type':'float64','value':20.}])
    return feature,resources,{'lightSectors':mapping},token


def test_fresh_feature_reuses_exact_recipe_assets_and_preserves_suffix():
    feature,resources,profile,token=sample()
    source=copy.deepcopy(feature)
    suffix=";TX('Text;CA(inert)',1,2);SY(LIGHTS01);"
    original=' '+token+' '+suffix
    result,receipt=light.translate_light_sectors(original,feature,resources,profile)
    assert result==' SY(N5TEST01) '+suffix
    assert feature==source
    assert receipt['sourceFeatureSHA256']
    assert receipt['originalProgrammeSHA256']==light.sha(original.encode())
    assert receipt['newResourceRasterized'] is False
    assert receipt['nativeOrA97Execution'] is False
    # Same attribute inputs with a fresh arbitrary sourceID/anchor can reuse;
    # neither UID nor cell names participate in recipe selection.
    feature['sourceFeatureId']=371
    feature['geometry']['position']=[-60.,12.]
    assert light.translate_light_sectors(original,feature,resources,profile)[0]==result


def test_integral_decimal_lexemes_and_absent_original_closing_are_supported():
    feature,resources,profile,token=sample()
    original=token.replace(',4,',', 4.0,').replace(',2,',', 2.0,').replace(',15,25)',',15.0,25.0')
    result,receipt=light.translate_light_sectors(original,feature,resources,profile)
    assert result=='SY(N5TEST01)'
    assert receipt['replacements'][0]['parameters']['closingPresent'] is False
    assert receipt['replacements'][0]['originalToken']==original


def test_unmapped_angles_never_borrow_a_neighbouring_recipe():
    feature,resources,profile,token=sample()
    with pytest.raises(light.LightSectorRefusal,match='UNKNOWN_SECTOR'):
        light.translate_light_sectors(token.replace('359','358.9'),feature,resources,profile)


def test_190_to_207_is_not_the_existing_190_to_210_sector():
    feature,resources,profile,_=sample()
    profile['lightSectors']['bindings'][0]['sourceCA']='CA(OUTLW,4,LITRD,2,190,210,10,25)'
    missing='CA(OUTLW, 4,LITRD, 2,190.0, 207.0,  10.0,  25.0)'
    with pytest.raises(light.LightSectorRefusal,match='UNKNOWN_SECTOR'):
        light.translate_light_sectors(missing,feature,resources,profile)
    assert light.parse_ca(missing)['parameters'] != light.parse_ca(profile['lightSectors']['bindings'][0]['sourceCA'])['parameters']


@pytest.mark.parametrize('bad', ['CA(OUTLW,4,LITRD,2,0,90,15,25,9)',
                                'CA(OUTLW,4,LITRD,2,nan,90,15,25)',
                                'CA(OUTLW,4,LITRD,2,0,90,15.5,25)',
                                'CA(OUTLW,4,LITRD,2,0,90,15,25);SY(X)'])
def test_unsupported_native_or_private_syntax_refused(bad):
    with pytest.raises(light.LightSectorRefusal):
        light.parse_ca(bad)


def test_same_generation_asset_and_owner_shortcut_guards():
    feature,resources,profile,token=sample()
    resources[68]+=b'changed'
    with pytest.raises(light.LightSectorRefusal,match='SAME_GENERATION'):
        light.translate_light_sectors(token,feature,resources,profile)
    feature,resources,profile,token=sample()
    profile['lightSectors']['bindings'][0]['uid']=99
    with pytest.raises(light.LightSectorRefusal,match='NO_OWNER_PINS'):
        light.translate_light_sectors(token,feature,resources,profile)
    feature,resources,profile,token=sample()
    profile['lightSectors']['bindings'][0]['symbolName']='FAKE0001'
    with pytest.raises(light.LightSectorRefusal,match='SYMBOL_DEFINITION'):
        light.translate_light_sectors(token,feature,resources,profile)


def test_inert_text_and_nonca_programmes_need_no_profile():
    original="TX('Text CA(foo); continued',1,2);SY(LIGHTS01);"
    assert light.translate_light_sectors(original,{}, {}, {})[0]==original
    assert light.translate_light_sectors('LS(SOLD,1,CHBLK);',{}, {}, {})[1]['changed'] is False
