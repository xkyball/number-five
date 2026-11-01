"""Invented portable fixtures; no chart files or Original dependencies."""
from copy import deepcopy
import pytest
from no_chart_converter.navionics.regional_source.magvar_portrayal import POINT_RETAINED_POLICY,rewrite_region_magvar,_line_attributes

def fixture(value=-8.,name='VALACM'):
 original=dict(sourceId='OWN_LINE',classCode=81,classAcronym='MAGVAR',sourcePrimitive=1,attributes=[dict(acronym='catgeo',type='uint32',value=2),dict(acronym='RYRMGV',type='utf8',value='2018'),dict(acronym=name,type='float64',value=value)],geometry=dict(kind='line',parts=[dict(coordinates=[[0.,0.],[.001,.001],[.002,.003]])]))
 line=dict(kind=32,class_code=91,source_id='OWN_LINE#part',parts=[[[5,7],[100,200],[300,600]]],attributes=[],native_level_range=dict(min_level=3,max_level=9))
 other=dict(kind=40,class_code=76,source_id='OWN_OTHER',xy=[100,100],attributes=[],native_level_range=dict(min_level=0,max_level=15))
 document=dict(format='no5-own-plain-atlas/1',maps=[dict(format='no5-own-plain-generic-attribute-batch/1',header=dict(flags=4660,raw_bounds=[0,0,1024,1024],raw_grid=[32769,32769],raw_levels=[8,20],trailing_value=0),layers=[dict(layer_id=69,layer_range=[0,100000],features=[line]),dict(layer_id=64,layer_range=[0,100000],features=[other])],date_text_1='20000101',date_text_2='20000101')])
 rows=[dict(mapOrdinal=0,layerOrdinal=i,featureOrdinal=0,originalOwnerSourceId=owner,sourceAtomId=owner,kind=kind,emittedId=sid)for i,owner,kind,sid in[(0,'OWN_LINE',32,'OWN_LINE#part'),(1,'OWN_OTHER',40,'OWN_OTHER')]]
 return dict(document=document,geometryLedger=dict(featureLedger=rows)),dict(features=[original]),original

def test_line_geometry_ids_ranges_headers_and_input_exact():
 result,cell,original=fixture();old=deepcopy((result,cell));out=rewrite_region_magvar(result,cell,policy=POINT_RETAINED_POLICY);assert(result,cell)==old
 before=result['document']['maps'][0];after=out['document']['maps'][0];assert before['header']==after['header']and len(out['document']['maps'])==1
 b=before['layers'][0]['features'][0];a=after['layers'][0]['features'][0];assert {k:v for k,v in a.items()if k!='attributes'}=={k:v for k,v in b.items()if k!='attributes'}
 assert after['layers'][1]==before['layers'][1]
 assert out['magvarPortrayal']['fullOriginalLines'][0]['completeOriginalFeature']==original
 assert out['magvarPortrayal']['lineSourceOwners']==1 and out['magvarPortrayal']['areaSourceOwners']==0
 assert not out['magvarPortrayal']['markers']and not out['magvarPortrayal']['fullOriginalDomains']
 assert len(out['geometryLedger']['featureLedger'])==2

def test_line_labels_keep_negative_annual_value_and_no_point_footer():
 result,cell,_=fixture();out=rewrite_region_magvar(result,cell,policy=POINT_RETAINED_POLICY);attrs=out['document']['maps'][0]['layers'][0]['features'][0]['attributes'];assert[a['native_code_u16']for a in attrs]==[121,98]
 assert attrs[0]['raw_hex']=='32303138';text=attrs[1]['text'];assert 'Annual change (VALACM): -8.0'in text and 'Reference year (RYRMGV): 2018'in text
 assert 'Magnetic variation (VALMAG)'not in text and 'pick site'not in text and 'associated chart area'not in text
 assert 'degrees'not in text and 'minutes'not in text

def test_variation_family_remains_distinct_from_annual_change():
 _,_,feature=fixture(value=-26.,name='VALMAG');attrs=_line_attributes(feature);assert 'Magnetic variation (VALMAG): -26.0'in attrs[1]['text'];assert 'Annual change (VALACM)'not in attrs[1]['text']

def test_incorrect_native_layer_rejected():
 result,cell,_=fixture();result['document']['maps'][0]['layers'][0]['layer_id']=70
 with pytest.raises(ValueError,match='REAL_MAGVAR_LINE_OWNER'):rewrite_region_magvar(result,cell,policy=POINT_RETAINED_POLICY)

def test_original_area_not_aliased_to_native_line():
 result,cell,_=fixture();cell['features'][0]['geometry']['kind']='area'
 with pytest.raises(ValueError,match='REAL_MAGVAR_LINE_OWNER'):rewrite_region_magvar(result,cell,policy=POINT_RETAINED_POLICY)

def test_duplicate_year_is_not_silently_selected():
 _,_,feature=fixture();feature['attributes'].append(deepcopy(feature['attributes'][1]))
 with pytest.raises(ValueError,match='NO_DUPLICATE_ORIGINAL_MAGVAR_YEAR'):_line_attributes(feature)

def test_absent_year_is_not_invented():
 _,_,feature=fixture();feature['attributes']=[a for a in feature['attributes']if a['acronym']!='RYRMGV'];attrs=_line_attributes(feature);assert[a['native_code_u16']for a in attrs]==[98];assert '2018'not in attrs[0]['text']
