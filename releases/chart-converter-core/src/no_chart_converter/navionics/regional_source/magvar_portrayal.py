"""Pure regional MAGVAR portrayal adapter for the public compiler.

Area91's original style fills the domain magenta. The normal-native-qualified
Point40/91/68 is the pick symbol. The Original domain/typed data are retained in
full. Real Line32/91/69 retains all vector data and uses the qualified year/INFORM
route for readable Original values. Optional generic Info205/Area90 remains held;
this module never aliases an information domain to an address or other class.
"""
from copy import deepcopy
import hashlib,json,re
from math import isqrt
from shapely.geometry import MultiPolygon,Point,box
from no_chart_converter.navionics.regional_source import semantic_authoring as source

POINT_RETAINED_POLICY='magvar_point91_with_retained_full_original_domain_v1'
POINT_INFO205_POLICY='magvar_point91_and_qualified_generic_info205_domain_v1'

def digest(v):return hashlib.sha256(json.dumps(v,sort_keys=True,separators=(',',':'),allow_nan=False).encode()).hexdigest()
def need(v,k):
 if not v:raise ValueError(k)
def require_policy(policy):
 need(policy in(POINT_RETAINED_POLICY,POINT_INFO205_POLICY),'EXPLICIT_MAGVAR_PORTRAYAL_POLICY');return policy

def require_info205_qualification(value):
 need(type(value)is dict and value.get('format')=='no5-native-generic-info205-area90-qualification/1'and value.get('status')=='PASS_NORMAL_AREA_DOMAIN_QUERY_NO_FILL','REAL_INFO205_NATIVE_QUALIFICATION_REQUIRED')
 need(value.get('nativeClassKindLayer')==[205,24,90]and all(value.get(k)is True for k in('normalInteriorDomainQuery','normalFactoryAndInformationText','sourceHoleExcluded','noOpaqueFill','defaultPublicLayerEnabled')),'ALL_INFO205_DOMAIN_AND_PORTRAYAL_GATES_REQUIRED')
 p=value.get('actualRootReceipt');need(type(p)is dict and type(p.get('bytes'))is int and p['bytes']>0 and type(p.get('sha256'))is str and re.fullmatch('[0-9a-f]{64}',p['sha256'])and type(p.get('path'))is str,'ACTUAL_INFO205_ROOT_RECEIPT_PIN_REQUIRED')
 return deepcopy(value)

def _full_shape(feature):
 g=feature['geometry'];need(g.get('kind')=='area','ORIGINAL_MAGVAR_AREA_REQUIRED');parts=source._parts(feature)
 projected=[[source.geo.forward_model(p)['formulaF64XY']for p in part]for part in parts]
 rings={r['id']:p for r,p in zip(g['rings'],projected,strict=True)}
 shape=MultiPolygon([(rings[p['exteriorRingId']],[rings[r]for r in p['interiorRingIds']])for p in g['polygons']])
 need(shape.is_valid and not shape.is_empty and shape.area>0,'COMPLETE_VALID_ORIGINAL_MAGVAR_DOMAIN_NO_REPAIR');return shape

def _source_information(feature):
 # Public information text is for the chart user; complete ordered typed
 # Source records (including their provenance) stay in fullOriginalDomains.
 labels={'RYRMGV':'Reference year (RYRMGV)',
         'VALACM':'Annual change (VALACM)',
         'VALMAG':'Magnetic variation (VALMAG)'}
 is_line=feature.get('geometry',{}).get('kind')=='line'
 lines=['Magnetic variation — line information'if is_line else'Magnetic variation']
 for attr in feature['attributes']:
  acronym=attr.get('acronym','')
  if acronym=='catgeo':continue
  value=attr.get('value')
  need(type(value)in(str,int,float),'MAGVAR_INFORMATION_SIMPLE_SOURCE_VALUE_REQUIRED')
  # The retained Source field does not specify a unit; do not invent one.
  lines.append(labels.get(acronym,acronym or 'Source attribute')+': '+str(value))
 if not is_line:lines.extend(['','This symbol is an information pick site for the associated chart area.'])
 return '\n'.join(lines)

def _marker_attributes(feature):
 year=[a for a in feature['attributes']if a.get('acronym')=='RYRMGV'];need(len(year)<=1,'NO_DUPLICATE_ORIGINAL_MAGVAR_YEAR');attrs=[]
 if year:
  need(year[0].get('type')=='utf8'and type(year[0].get('value'))is str,'ORIGINAL_MAGVAR_YEAR_UTF8');attrs.append(dict(native_code_u16=121,raw_hex=year[0]['value'].encode('utf-8').hex()))
 attrs.append(dict(native_code_u16=98,text=_source_information(feature)))
 return attrs

def _line_attributes(feature):
 need(feature.get('classAcronym')=='MAGVAR'and feature.get('geometry',{}).get('kind')=='line','ORIGINAL_MAGVAR_LINE_REQUIRED')
 return _marker_attributes(feature)

def _marker_reserve(header):
 # This intentionally bounded profile has a genuine normalized V222 header:
 # rawGrid32769 -> normalized2^29, maxZoom20; scale=1024/2^29=2^-19.
 # ch2_RelativePixels floors after a shift by min(requestedZoom,maxZoom).
 # At maxZoom one pixel is 2 Source metres. Original MAGVAR bitmap29x33,
 # pivot(5,16), rotation-safe corner radius ceil(sqrt(24^2+17^2))=30px.
 # One more pixel bounds the projection floor. Round62m upward to64m.
 # No native field is changed and no fitted single-pixel nudge is used.
 bounds=header['raw_bounds'];width=bounds[2]-bounds[0];height=bounds[3]-bounds[1]
 need(width==height==1024 and header['raw_grid']==[32769,32769],
      'SOURCE_QUALIFIED_MAGVAR_1024_GRID32769_PLACEMENT_PROFILE_REQUIRED')
 radius2=max(5,29-5)**2+max(16,33-16)**2
 radius=isqrt(radius2)+(isqrt(radius2)**2<radius2)
 quantum=width//512;required=(radius+1)*quantum
 reserve=1<<(required-1).bit_length()
 return dict(sourceInset=reserve,maxNativeZoom=20,normalizedGrid=1<<29,
     maxNativePixelQuantumSourceUnits=quantum,originalGlyphRadiusPixels=radius,
     projectionFloorAllowancePixels=1,requiredSourceUnits=required,
     qualification='SOURCE_READER_PROJECTION_AND_GENUINE_HEADER_PROFILE_NOT_NEW_NATIVE_ACCEPTANCE')

def _interior_marker(feature,coverage,maps):
 full=_full_shape(feature);clipped=full.intersection(box(*coverage));need(not clipped.is_empty and clipped.area>0,'MAGVAR_REGION_AREA_INTERSECTION_REQUIRED')
 # The domain is not eroded or rewritten. Only the derived pick site's
 # candidate set is intersected with a Source-proven safe tile interior.
 rep=clipped.representative_point();candidates=[]
 for mi,m in enumerate(maps):
  placement=_marker_reserve(m['header']);r=placement['sourceInset'];b=m['header']['raw_bounds']
  safe=[b[0]+r,b[1]+r,b[2]-r,b[3]-r]
  feasible=clipped.intersection(box(*safe))
  if feasible.is_empty or feasible.area<=0:continue
  inside=feasible.representative_point()
  seeds=[(min(max(rep.x,safe[0]),safe[2]),min(max(rep.y,safe[1]),safe[3])),(inside.x,inside.y)]
  for sx,sy in seeds:
   base=[round(sx),round(sy)]
   for dx,dy in((0,0),(1,1),(-1,-1),(1,-1),(-1,1),(1,0),(0,1),(-1,0),(0,-1)):
    xy=[base[0]+dx,base[1]+dy];point=Point(xy)
    distances=[xy[0]-b[0],xy[1]-b[1],b[2]-xy[0],b[3]-xy[1]]
    if min(distances)<r or not full.contains(point)or not clipped.contains(point):continue
    score=((xy[0]-rep.x)**2+(xy[1]-rep.y)**2,-xy[0],-xy[1])
    selection=dict(selection='nearest bounded I32 candidate in Original region domain and Source-derived tile inset',sourceRepresentativeF64=[rep.x,rep.y],sourceRoundedI32=[round(rep.x),round(rep.y)],seamAvoidanceDeltaI32=[xy[0]-round(rep.x),xy[1]-round(rep.y)],distanceToMapEdgesSourceUnits=distances,placementReserve=placement,distanceToOriginalBoundarySourceUnits=full.boundary.distance(point),distanceToRegionIntersectionBoundarySourceUnits=clipped.boundary.distance(point),derivedPointNotOriginalPhysicalSourcePoint=True)
    candidates.append((score,xy,mi,selection))
 need(candidates,'NO_MAGVAR_MARKER_WITH_SOURCE_PROVEN_TILE_INTERIOR_RESERVE')
 _,xy,mi,selection=min(candidates,key=lambda x:x[0]);return xy,mi,selection

def rewrite_region_magvar(result,cell,*,policy,info205_qualification=None):
 """Return a new public document + exact retained data, without filesystem IO.

 Unaffected public features and all map headers are exact values. Existing
 clipped MAGVAR vector coordinates are reused only by the qualified Info205
 carrier. A diagnostic point-only result retains those Native-domain candidates
 and the complete unmodified Original owner, but never claims a Native domain.
 """
 require_policy(policy);before=digest(cell);out=deepcopy(result);doc=out['document'];maps=doc['maps'];need(maps,'PUBLIC_REGION_DOCUMENT_REQUIRED')
 qualification=require_info205_qualification(info205_qualification)if policy==POINT_INFO205_POLICY else None
 if policy==POINT_RETAINED_POLICY:need(info205_qualification is None,'NO_UNUSED_INFO205_QUALIFICATION')
 ledger=result['geometryLedger']['featureLedger'];lookup={(x['mapOrdinal'],x['layerOrdinal'],x['featureOrdinal']):x for x in ledger};features={f['sourceId']:f for f in cell['features']}
 coverage=[min(m['header']['raw_bounds'][0]for m in maps),min(m['header']['raw_bounds'][1]for m in maps),max(m['header']['raw_bounds'][2]for m in maps),max(m['header']['raw_bounds'][3]for m in maps)]
 owners={};line_owners={};line_emissions=[];newledger=[];removed=[];unaffected_before=[];unaffected_after=[]
 for mi,m in enumerate(maps):
  layers=[]
  for li,layer in enumerate(m['layers']):
   kept=[];neutral=[]
   for fi,f in enumerate(layer['features']):
    old=lookup[mi,li,fi]
    if(f.get('kind'),f.get('class_code'))==(32,91):
     owner=old['originalOwnerSourceId'];original=features[owner]
     need(original['classAcronym']=='MAGVAR'and original['geometry']['kind']=='line'and layer['layer_id']==69,'REAL_MAGVAR_LINE_OWNER_WIRE_AND_LAYER_JOIN')
     value=deepcopy(f);value['attributes']=_line_attributes(original)
     need(all(value[k]==f[k]for k in set(f)-{'attributes'}),'WHOLE_NATIVE_LINE_GEOMETRY_ID_AND_RANGE_UNCHANGED')
     line_owners[owner]=original
     info=dict(old,nativeMAGVARLineInformationAuthored=True,OriginalLineGeometryUnchanged=True,allOriginalOrderedTypedAttributesRetained=True,native137140NumericParity=False)
     line_emissions.append(dict(info,oldNativeAttributes=deepcopy(f.get('attributes',[])),newNativeAttributes=deepcopy(value['attributes'])))
     kept.append((value,info));continue
    if(f.get('kind'),f.get('class_code'))!=(24,91):
     unchanged=deepcopy(f);kept.append((unchanged,old));unaffected_before.append(digest(f));continue
    owner=old['originalOwnerSourceId'];original=features[owner];need(original['classAcronym']=='MAGVAR'and original['geometry']['kind']=='area'and layer['layer_id']==70,'REAL_MAGVAR_OWNER_WIRE_AND_LAYER_JOIN')
    owners[owner]=original;removed.append(dict(old,sourceFeature=deepcopy(f),originalNativeArea91PaintSuppressed=True))
    if qualification is not None:
     info=deepcopy(f);info.update(class_code=205,source_id=f['source_id']+'#info205',attributes=[dict(native_code_u16=109,text='Magnetic variation — Original MAGVAR information'),dict(native_code_u16=98,text=_source_information(original))]);need(len(info['source_id'])<=128,'BOUNDED_INFO_DOMAIN_ID');neutral.append((info,dict(old,nativeClassCode=205,derivedGenericInformationDomain=True,originalClassIdentityNativeParity=False,OriginalDomainCoordinatePartsUnchanged=True)))
   if kept:
    ni=len(layers);nl=deepcopy(layer);nl['features']=[]
    for f,l in kept:
     nf=len(nl['features']);nl['features'].append(f)
     if not l.get('nativeMAGVARLineInformationAuthored'):unaffected_after.append(digest(f))
     newledger.append(dict(l,mapOrdinal=mi,layerOrdinal=ni,featureOrdinal=nf,featureValueSHA256=digest(f)))
    layers.append(nl)
   if neutral:
    ni=len(layers);nl=dict(layer_id=90,layer_range=deepcopy(layer['layer_range']),features=[])
    for f,l in neutral:
     nf=len(nl['features']);nl['features'].append(f);newledger.append(dict(l,mapOrdinal=mi,layerOrdinal=ni,featureOrdinal=nf,emittedId=f['source_id'],featureValueSHA256=digest(f)))
    layers.append(nl)
  m['layers']=layers
 domains=[];markers=[]
 for owner,original in sorted(owners.items()):
  xy,mi,selection=_interior_marker(original,coverage,maps);source_id=owner+'#magvar-pick-site';need(len(source_id)<=128,'BOUNDED_MAGVAR_MARKER_ID')
  feature=dict(kind=40,class_code=91,source_id=source_id,xy=xy,attributes=_marker_attributes(original),native_level_range=dict(min_level=0,max_level=15));li=len(maps[mi]['layers']);maps[mi]['layers'].append(dict(layer_id=68,layer_range=[0,100000],features=[feature]))
  row=dict(sourceAtomId=source_id,originalOwnerSourceId=owner,kind=40,mapOrdinal=mi,layerOrdinal=li,featureOrdinal=0,emittedId=source_id,derivedMAGVARPickSymbol=True,featureValueSHA256=digest(feature),selection=selection);newledger.append(row);markers.append(dict(row,absoluteXY=xy,nativeClass=91,physicalLayer=68))
  domains.append(dict(sourceId=owner,completeOriginalFeature=deepcopy(original),completeOriginalGeometrySHA256=digest(original['geometry']),completeOriginalTypedAttributesSHA256=digest(original['attributes']),clippedRegionVectorOccurrences=[deepcopy(x)for x in removed if x['originalOwnerSourceId']==owner],nativeInfo205DomainEmitted=qualification is not None,native137140NumericParity=False))
 need(all(a['header']==b['header']for a,b in zip(maps,result['document']['maps'],strict=True)),'ALL_MAP_HEADERS_UNCHANGED');need(unaffected_before==unaffected_after,'ALL_UNAFFECTED_FEATURE_VALUES_AND_ORDER_EXACT');need(all(m['layers']for m in maps),'EMPTY_MAP_AFTER_PORTRAYAL_REQUIRES_NO_DATA_POLICY');need(before==digest(cell),'ORIGINAL_CELL_UNCHANGED');need(not any(f.get('kind')==24 and f.get('class_code')==91 for m in maps for l in m['layers']for f in l['features']),'NO_AREA91_FILL_IN_PORTRAYAL_OUTPUT')
 out['prePortrayalGeometryLedger']=deepcopy(result['geometryLedger']);out['geometryLedger']=dict(result['geometryLedger'],featureLedger=newledger,emittedFeatures=len(newledger),portrayalRewritten=True,originalMAGVARArea91NativePaintRemoved=True,OriginalDomainTopologyProofsBelongToPrePortrayalLedger=True)
 out['magvarPortrayal']=dict(format='no5-region-magvar-portrayal-rewrite/1',policy=policy,sourceOriginalCellUnchanged=True,sourceOwners=len(owners)+len(line_owners),areaSourceOwners=len(owners),lineSourceOwners=len(line_owners),markers=markers,fullOriginalDomains=domains,fullOriginalLines=[dict(sourceId=sid,completeOriginalFeature=deepcopy(original),completeOriginalGeometrySHA256=digest(original['geometry']),completeOriginalTypedAttributesSHA256=digest(original['attributes']),native137140NumericParity=False)for sid,original in sorted(line_owners.items())],lineNativeOccurrences=line_emissions,allMAGVARLineGeometryIDsHeadersAndRangesUnchanged=True,unaffectedFeatureCount=len(unaffected_before),unaffectedFeatureValuesAndOrderExact=True,allMapHeadersUnchanged=True,nativeInfo205Qualification=qualification,nativeInfo205DomainEmitted=qualification is not None,diagnosticRetainedDomainIsNotNativeDomainQuery=qualification is None,SourceSCAMINParity=False,native137140NumericParity=False,nativeProductQualified=False)
 return out
