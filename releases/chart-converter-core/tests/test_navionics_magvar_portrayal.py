"""Portable invented Source fixtures; no chart files or Original dependencies."""
from copy import deepcopy
import pytest
from no_chart_converter.navionics.regional_source.magvar_portrayal import (
 POINT_RETAINED_POLICY,POINT_INFO205_POLICY,rewrite_region_magvar,digest,_full_shape,Point,
)

def fixture():
 outer=[[0.,0.],[.02,0.],[.02,.02],[0.,.02],[0.,0.]]
 hole=[[.003,.003],[.006,.003],[.006,.006],[.003,.006],[.003,.003]]
 original=dict(sourceId='OWN_MAGVAR_AREA',classAcronym='MAGVAR',sourcePrimitive=2,attributes=[dict(acronym='catgeo',type='uint32',value=3),dict(acronym='RYRMGV',type='utf8',value='2018'),dict(acronym='VALMAG',type='float64',value=-25.),dict(acronym='VALACM',type='float64',value=-4.)],geometry=dict(kind='area',reconstruction=dict(status='exact_topology',issues=[]),rings=[dict(id='outer',role='exterior',coordinates=outer),dict(id='hole',role='interior',coordinates=hole)],polygons=[dict(exteriorRingId='outer',interiorRingIds=['hole'])]))
 area=dict(kind=24,class_code=91,source_id='OWN_MAGVAR_AREA',parts=[[[0,0],[32768,0],[32768,32768],[0,32768],[0,0]],[[10000,10000],[20000,10000],[20000,20000],[10000,20000],[10000,10000]]],attributes=[],native_level_range=dict(min_level=0,max_level=15))
 other=dict(kind=40,class_code=76,source_id='OWN_OTHER_POINT',xy=[100,100],attributes=[],native_level_range=dict(min_level=0,max_level=15))
 document=dict(format='no5-own-plain-atlas/1',maps=[dict(format='no5-own-plain-generic-attribute-batch/1',header=dict(flags=4660,raw_bounds=[0,0,1024,1024],raw_grid=[32769,32769],raw_levels=[8,20],trailing_value=0),layers=[dict(layer_id=70,layer_range=[0,100000],features=[area]),dict(layer_id=64,layer_range=[0,100000],features=[other])],date_text_1='20000101',date_text_2='20000101')])
 ledger=dict(featureLedger=[dict(mapOrdinal=0,layerOrdinal=0,featureOrdinal=0,originalOwnerSourceId='OWN_MAGVAR_AREA',sourceAtomId='OWN_MAGVAR_AREA',kind=24,emittedId='OWN_MAGVAR_AREA'),dict(mapOrdinal=0,layerOrdinal=1,featureOrdinal=0,originalOwnerSourceId='OWN_OTHER_POINT',sourceAtomId='OWN_OTHER_POINT',kind=40,emittedId='OWN_OTHER_POINT')])
 return dict(document=document,geometryLedger=ledger),dict(features=[original]),original

def test_rewrites_native_fill_and_retains_full_hole_domain():
 result,cell,original=fixture();old=deepcopy((result,cell));q=rewrite_region_magvar(result,cell,policy=POINT_RETAINED_POLICY)
 assert (result,cell)==old
 assert q['magvarPortrayal']['fullOriginalDomains'][0]['completeOriginalFeature']==original
 assert len(q['magvarPortrayal']['fullOriginalDomains'][0]['completeOriginalFeature']['geometry']['rings'])==2
 assert not any(f.get('class_code')==91 and f['kind']==24 for m in q['document']['maps']for l in m['layers']for f in l['features'])
 marker=q['magvarPortrayal']['markers'][0];assert _full_shape(original).contains(Point(marker['absoluteXY']))
 assert q['document']['maps'][0]['layers'][0]['features'][0]==result['document']['maps'][0]['layers'][1]['features'][0]
 assert q['magvarPortrayal']['nativeInfo205DomainEmitted']is False

def test_no_implicit_policy_or_fake_source_attribute_parity():
 result,cell,original=fixture()
 with pytest.raises(ValueError):rewrite_region_magvar(result,cell,policy='implicit')
 with pytest.raises(ValueError):rewrite_region_magvar(result,cell,policy=POINT_INFO205_POLICY)
 q=rewrite_region_magvar(result,cell,policy=POINT_RETAINED_POLICY)
 marker=next(f for m in q['document']['maps']for l in m['layers']for f in l['features']if f['source_id'].endswith('#magvar-pick-site'))
 assert [a['native_code_u16']for a in marker['attributes']]==[121,98]
 assert 'Magnetic variation (VALMAG): -25.0'in marker['attributes'][1]['text']and 'Annual change (VALACM): -4.0'in marker['attributes'][1]['text']
 assert 'Reference year (RYRMGV): 2018'in marker['attributes'][1]['text']
 assert all(token not in marker['attributes'][1]['text']for token in('sourceOwner','derivedPointIsPickSiteOnly','sourcePrimitive','{'))
 assert q['magvarPortrayal']['native137140NumericParity']is False

def test_info205_requires_all_normal_native_domain_gates():
 result,cell,_=fixture()
 partial=dict(format='no5-native-generic-info205-area90-qualification/1',status='PASS_NORMAL_AREA_DOMAIN_QUERY_NO_FILL',nativeClassKindLayer=[205,24,90])
 with pytest.raises(ValueError):rewrite_region_magvar(result,cell,policy=POINT_INFO205_POLICY,info205_qualification=partial)


def test_source_derived_inset_and_old_seam_failure():
 result,cell,_=fixture();q=rewrite_region_magvar(result,cell,policy=POINT_RETAINED_POLICY)
 marker=q['magvarPortrayal']['markers'][0];rule=marker['selection']['placementReserve']
 assert rule['sourceInset']==64 and rule['requiredSourceUnits']==62
 assert rule['originalGlyphRadiusPixels']==30 and rule['maxNativePixelQuantumSourceUnits']==2
 assert min(marker['selection']['distanceToMapEdgesSourceUnits'])>=64
 # Exact power-of-two source model of the actual Reader/RelativePixels path.
 # At q20 an old +1m pick maps to pixel0 at the tile seam, not pixel1.
 assert ((1<<19)>>20)==0
 for distance in marker['selection']['distanceToMapEdgesSourceUnits']:
  for qlevel in range(21):
   native_pixels=(distance<<19)>>qlevel
   assert native_pixels>=31

def test_placement_profile_refused_without_modifying_domain():
 result,cell,_=fixture();before=deepcopy(cell)
 result['document']['maps'][0]['header']['raw_grid']=[1024,1024]
 with pytest.raises(ValueError,match='SOURCE_QUALIFIED_MAGVAR'):
  rewrite_region_magvar(result,cell,policy=POINT_RETAINED_POLICY)
 assert cell==before
