"""Default-off query-exact AC cull for an explicit finite source/view domain.

No source tree, query, geometry, AO, UID or attribute mutation. A caller must
materialize the proposal only inside the explicitly bound physical routes.
"""
from fractions import Fraction as F
import hashlib,re,struct
POLICY='FINITE_SOURCE_DESTINATION_ROOT1_PURE_AC_COMPLETE_RING_BBOX_CULL/1'
TRANSFORM='ROOT1_F64_TILE256_F32_QROUND_IDENTITY/1'
Q=2147483649
AC=re.compile(rb'AC\([A-Z0-9_]+\);')
# Same established all-closed Root1 membership condition as the public
# area_polygon_groups helper: each closed part closes its own polygon. No LC
# false-list concatenation/connector contract is borrowed for area fills.
GROUPING_MODEL='SOURCE_ORDER_ALL_PARTS_CLOSED_ROOT1_NO_MASK_OR_HOLE_FILTER/1'
GROUPING_EVIDENCE=dict(areaReadPolygonCodeSHA256='18909b23166525833895cc54fa2a4a83de5f145bb403770142c31e1108e1611d',areaLoadDataCodeSHA256='60851af1767886198be5b5fd7224c6800d872ac5b96c48148bceef1a60adc3b8')
def need(v,s):
 if not v:raise ValueError(s)
def sha(v):return hashlib.sha256(v).hexdigest()
def pins(record,index,coordinates,render):return{k:dict(bytes=len(v),sha256=sha(v))for k,v in zip(('FI','I','CO','RO'),(record,index,coordinates,render))}
def tile(t):
 need(type(t)in(list,tuple)and len(t)==3 and all(type(v)is int for v in t),'EXPLICIT_INTEGER_TILE')
 z,x,y=t;need(0<=z<=29 and 0<=x<1<<z and 0<=y<1<<z,'HOST_TILE_WIRE_DOMAIN_NOT_NATIVE_ADMISSION')
 return z,x,y
def bound(t,halo):
 z,x,y=t;d=256*(1<<z);values=[F((256*x-halo)*Q,d),F((256*y-halo)*Q,d),F((256*(x+1)+halo)*Q,d),F((256*(y+1)+halo)*Q,d)]
 return[values[0].numerator//values[0].denominator,values[1].numerator//values[1].denominator,-((-values[2].numerator)//values[2].denominator),-((-values[3].numerator)//values[3].denominator)]
def prepare(record,index,coordinates,render,*,policy=None,domain=None,expected_input_pins=None,max_parts=2048,max_points=65536):
 original=dict(record=record,indices=index,coordinates=coordinates,render=render)
 def keep(reason):return dict(action='KEEP',reason=reason,original=original,proposal=None,applied=False)
 if policy!=POLICY:return keep('DEFAULT_OFF_EXPLICIT_POLICY_REQUIRED')
 try:
  need(all(type(v)is bytes for v in(record,index,coordinates,render))and len(record)==32 and sum(map(len,(record,index,coordinates,render)))<=8<<20,'BOUNDED_IMMUTABLE_FOUR_BUFFERS')
  need(expected_input_pins==pins(record,index,coordinates,render),'EXACT_LITERAL_INPUT_PINS')
  need(type(max_parts)is type(max_points)is int and 0<max_parts<=2048 and 0<max_points<=65536,'EXPLICIT_FINITE_WORK_CAPS')
  need(type(domain)is dict and set(domain)=={'sourceTile','destinationTiles','haloPixels','transformContract','routingBindingSHA256'},'EXPLICIT_FINITE_ACTUAL_ROUTING_DOMAIN')
  source=tile(domain['sourceTile']);dest=domain['destinationTiles'];halo=domain['haloPixels'];binding=domain['routingBindingSHA256']
  need(type(dest)is list and 1<=len(dest)<=16 and type(halo)is int and halo==128,'FINITE_DESTINATIONS_AND_FULL128_PROTECTION')
  need(domain['transformContract']==TRANSFORM and type(binding)is str and re.fullmatch('[0-9a-f]{64}',binding)is not None,'EXPLICIT_TRANSFORM_AND_ROUTING_BINDING')
  views=[tile(t)for t in dest];need(len(set(views))==len(views),'UNIQUE_EXPLICIT_DESTINATIONS')
  for z,x,y in views:need(z>=source[0]and x>>(z-source[0])==source[1]and y>>(z-source[0])==source[2],'DESTINATION_INSIDE_BOUND_SOURCE')
  f=struct.unpack('<Q5I4B',record);need(f[6]==3,'KIND3_AREA_ONLY')
  at=f[2];need(at<=len(render)-6,'RO_FRAME_HEADER');count,query=struct.unpack_from('<HI',render,at);pos=at+6;contexts=[]
  for _ in range(count):
   need(pos+6<=len(render),'RO_CONTEXT_HEADER');key,n=struct.unpack_from('<IH',render,pos);pos+=6;need(pos+n<=len(render),'RO_CONTEXT_BODY');contexts.append((key,render[pos:pos+n]));pos+=n
  need(count==2 and {k for k,v in contexts}=={196608,196609}and all(AC.fullmatch(v)for k,v in contexts),'PURE_AC_BOTH_CONTEXTS_NO_ANCHOR_TEXT_OUTLINE_OR_UNKNOWN_CONTEXT')
  at=f[1];need(at<=len(index)-4,'I_FRAME_HEADER');n=struct.unpack_from('<I',index,at)[0];need(0<n<=max_parts and at+4+4*n<=len(index),'WHOLE_I_FRAME_AND_WORK_CAP')
  bounds=[bound(t,halo)for t in views];rows=[];point_count=0;maxabs=F(0);gaps=[None]*len(views);outer=False
  for i in range(n):
   word=struct.unpack_from('<I',index,at+4+4*i)[0];off=word&0xffffff;usage=word>>24
   need(usage in(24,25,20,21)and off+4<=len(coordinates),'ONLY_COMPLETE_CLOSED_AREA_ROLES')
   h=struct.unpack_from('<I',coordinates,off)[0];points=h&0xffffff
   need(h>>24==1 and points>=4 and off+4+8*points<=len(coordinates),'ROOT1_COMPLETE_RING')
   point_count+=points;need(point_count<=max_points,'OWN_POINT_WORK_CAP')
   raw=coordinates[off:off+4+8*points];xy=list(struct.iter_unpack('<II',raw[4:]));need(xy[0]==xy[-1]and all(v<=Q for p in xy for v in p),'CLOSED_ROOT_INTEGER_RING')
   bb=[min(x for x,y in xy),min(y for x,y in xy),max(x for x,y in xy),max(y for x,y in xy)];outer|=usage in(24,25);perview=[]
   for j,(view,b)in enumerate(zip(views,bounds)):
    gap=max(b[0]-bb[2],b[1]-bb[3],bb[0]-b[2],bb[1]-b[3]);need(gap>0,'TOUCHING_OR_INTERSECTING_COMPLETE_RING_KEEP')
    gaps[j]=gap if gaps[j]is None else min(gaps[j],gap);perview.append(gap)
    z,x,y=view
    for a,b in xy:maxabs=max(maxabs,abs(F(a*(1<<(z+8)),Q)-256*x),abs(F(b*(1<<(z+8)),Q)-256*y))
   rows.append(dict(part=i,indexWord=word,usage=usage,root=1,points=points,bbox=bb,rawSHA256=sha(raw),separationRootUnitsByDestination=perview))
  need(outer and maxabs<1<<23,'EXTERIOR_PRESENT_AND_NORMAL_F32_PROJECTION_MAGNITUDE')
  frame=struct.pack('<HI',2,query)+b''.join(struct.pack('<IH',key,0)for key,_ in contexts)
  receipt=dict(format='no5-finite-domain-query-exact-ac-cull/1',policy=POLICY,domain=domain,inputPins=expected_input_pins,UID=f[0],kind=3,dictionaryIndex=f[3],nativeClassWord=f[5],queryOffset=query,
   sourceParts=n,sourcePointOccurrences=point_count,sourceHoles=sum(r['usage']in(20,21)for r in rows),completeRingProof=rows,protectedOutwardRootRectangles=bounds,
   groupingModel=GROUPING_MODEL,groupingOriginalSourceEvidence=GROUPING_EVIDENCE,eachClosedRoot1PartClosesOnePolygon=True,interpartLCConnectorRuleUsed=False,
   minimumSeparationRootUnitsByDestination=gaps,maximumAbsoluteProjectedCoordinate=[maxabs.numerator,maxabs.denominator],pureFillProtectionPixels=halo,
   projectionQualification='Root1 normal F64 tile conversion, bounded F32 identity projection, no pen/glyph/pattern recurrence; caller binds actual transform/routing, not a self-authenticated contract',
   originalFrameSHA256=sha(render[f[2]:pos]),replacementFrameSHA256=sha(frame),oldProgrammes=[v.hex()for k,v in contexts],newProgrammes=['',''],
   geometryAndQueryArraysUnchanged=True,queryAOOffsetUnchanged=True,UIDClassAndAllOtherFIFieldsUnchanged=True,defaultOff=True,applied=False,nativePixelsQualified=False,allZoomQualified=False)
  return dict(action='PROPOSE_EMPTY_DRAW_CONTEXTS',reason='ALL_COMPLETE_RING_BBOXES_DISJOINT_EVERY_EXPLICIT_PROTECTED_DESTINATION',original=original,proposal=dict(renderFrame=frame),receipt=receipt,applied=False)
 except(ValueError,struct.error)as e:return keep(str(e))

def prepare_source(features,index,coordinates,render,*,policy=None,domain=None,owner_uids=None,expected_input_pins=None):
 """Normal complete-source bank seam, before the caller's offset planning.

 Explicit UID selection or every owner in this actual source; default policy
 leaves all banks byte-identical. Actual query geometry/AO is never rewritten.
 A caller still has to enforce the finite domain through source routing.
 """
 original=dict(featureBytes=features,indexBytes=index,coordinateBytes=coordinates,renderBytes=render)
 if policy!=POLICY:return dict(**original,receipt=dict(status='DEFAULT_OFF',changedOwners=0,appliedToFile=False))
 need(all(type(v)is bytes for v in(features,index,coordinates,render))and len(features)%32==0 and 0<len(features)<=2<<20 and sum(map(len,(features,index,coordinates,render)))<=8<<20,'COMPLETE_BOUNDED_SOURCE_BANKS')
 need(expected_input_pins=={k:dict(bytes=len(v),sha256=sha(v))for k,v in zip(('FI','I','CO','RO'),(features,index,coordinates,render))},'COMPLETE_SOURCE_PINS')
 uid_rows={}
 for ordinal in range(len(features)//32):uid_rows.setdefault(struct.unpack_from('<Q',features,32*ordinal)[0],[]).append(ordinal)
 selected=list(uid_rows)if owner_uids is None else owner_uids
 need(type(selected)is list and all(type(v)is int and 0<=v<1<<64 for v in selected)and len(set(selected))==len(selected)and all(len(uid_rows.get(v,[]))==1 for v in selected),'EXACT_UNIQUE_SOURCE_UID_SELECTION')
 out_f=bytearray(features);out_ro=bytearray(render);frames={};changed=[];kept=[]
 for uid in selected:
  ordinal=uid_rows[uid][0];record=features[32*ordinal:32*(ordinal+1)]
  result=prepare(record,index,coordinates,render,policy=policy,domain=domain,expected_input_pins=pins(record,index,coordinates,render))
  if result['action']=='KEEP':kept.append(dict(UID=uid,sourceFI=ordinal,reason=result['reason']));continue
  frame=result['proposal']['renderFrame']
  if frame not in frames:frames[frame]=len(out_ro);out_ro.extend(frame)
  struct.pack_into('<I',out_f,32*ordinal+12,frames[frame]);changed.append(dict(UID=uid,sourceFI=ordinal,newROOffset=frames[frame],proof=result['receipt']))
 inverse=bytearray(out_f)
 for row in changed:
  at=32*row['sourceFI']+12;inverse[at:at+4]=features[at:at+4]
 need(inverse==features and bytes(out_ro[:len(render)])==render and len(out_ro)<=2<<20,'ONLY_FI_RO_WORDS_AND_FINITE_APPENDED_RO_FRAMES')
 return dict(featureBytes=bytes(out_f),indexBytes=index,coordinateBytes=coordinates,renderBytes=bytes(out_ro),receipt=dict(format='no5-query-exact-pure-ac-source-bank-cull/1',policy=policy,domain=domain,
  inputPins=expected_input_pins,sourceOwners=len(features)//32,selectedUIDs=selected,changedOwners=len(changed),changed=changed,kept=kept,appendedRenderBytes=len(out_ro)-len(render),
  allGeometryAndQueryArraysExact=True,originalRenderPrefixExact=True,AOAndAttributesNotMutated=True,allOtherFIFieldsExact=True,defaultOff=True,appliedToFile=False,nativePixelsQualified=False,allZoomQualified=False))
