Q=2147483649
LOW24=0xffffff
AUTHORIZED={}
AUTHORIZED_CALLS={}
def configure_authorizations(cases, calls):
    global AUTHORIZED, AUTHORIZED_CALLS
    rows=cases['rows']; call_rows=calls['calls']
    AUTHORIZED={(r['owner']['cellId'],r['owner']['uid'],r['owner']['sourceOwnerOrdinal'],r['epoch'],r['referenceFloatWkbSHA256'],r['integerOutputWkbSHA256']):r for r in rows}
    AUTHORIZED_CALLS={(r['owner']['cellId'],r['owner']['uid'],r['owner']['sourceOwnerOrdinal'],r['epoch']):r for r in call_rows}
    if len(AUTHORIZED)!=len(rows) or len(AUTHORIZED_CALLS)!=len(call_rows):
        raise ValueError('UNIQUE_PRIVATE_PIXEL_MODEL_AUTHORIZATIONS')

"""Our Root-Q integer display encoder; exact source/query payloads stay external.

GEOS grid=1 valid_output is the primary conversion, not an inward cycle search.
The primary authored policy measures .1 boundary pixels. The separately
authorized exact97 display calls instead require whole filled-pixel model
equality; their actual boundary errors and omitted floating fill stay explicit.
Empty positive components require complete lattice and qRound-zero evidence.
"""

from pathlib import Path

import hashlib, sys, math, struct, json

from fractions import Fraction

from shapely import set_precision,constrained_delaunay_triangles,get_coordinates

from shapely.geometry import Polygon,LineString,MultiPoint

from shapely.ops import unary_union

from shapely.affinity import affine_transform,translate

from shapely import __version__ as SHAPELY_VERSION,geos_version_string

Q=2147483649

LOW24=0xffffff

def need(ok,label):
    if not ok:raise ValueError(label)

def sha(g):return hashlib.sha256(g.wkb).hexdigest()

def parts(g,kind):
    if g.is_empty:return []
    if g.geom_type==kind:return[g]
    return[p for h in getattr(g,'geoms',())for p in parts(h,kind)]

def projected_model_equal(p,g,clipper):
    """Own GEOS filled pixel model only; never native QRegion/AA observation."""
    # AreaSimple consumes filled polygon regions. Points/lines produced only
    # by intersection closure carry no fill; boundary strokes are separate.
    p=unary_union(parts(p,'Polygon'));g=unary_union(parts(g,'Polygon'))
    rows=[];ox,oy=clipper.origin
    for level in range(clipper.level,clipper.epoch_last_level+1):
        scale=clipper.tile_size*(1<<level)/Q
        matrix=[scale,0,0,scale,ox*scale,oy*scale]
        a=set_precision(set_precision(affine_transform(p,matrix),1,mode='valid_output'),0)
        b=set_precision(set_precision(affine_transform(g,matrix),1,mode='valid_output'),0)
        need(a.equals(b),'AUTHORING_WHOLE_FILLED_PIXEL_MODEL_DIFFERENCE')
        rows.append(dict(level=level,affineMatrix=matrix,referencePixelWkbSHA256=sha(a),outputPixelWkbSHA256=sha(b),
            referencePixelFillArea=a.area,outputPixelFillArea=b.area,filledPixelRegionsEqual=True,
            symmetricDifferencePixelArea=a.symmetric_difference(b).area))
    return dict(disposition='PIXEL_GRID_MODEL_EQUAL_AT_ALL_AUTHORED_LEVELS',
        referenceFloatWkbSHA256=sha(p),integerOutputWkbSHA256=sha(g),rootOrigin=list(clipper.origin),
        rootQ=Q,tilePixels=clipper.tile_size,authoredLevels=list(range(clipper.level,clipper.epoch_last_level+1)),
        projection='translate local Root by origin, scale by tilePixels*2^level/Q',
        model='GEOS set_precision(grid=1, valid_output), reset model0 before diagnostic overlay',
        shapelyVersion=SHAPELY_VERSION,geosVersion=geos_version_string,controls=rows,
        globalBoundaryHausdorffPoint1PassedClaim=False,nativeQRegionAAOrA97EqualityClaim=False,
        LS_LC_BoundaryStrokeEqualityNotProvenByFillModel=True,strokeImpactPhysicalTestQuestion=True)

def exact_lattice(p,max_rows=200000):
    """Complete lattice endpoints of an exact constrained triangle partition."""
    need(not p.interiors and len(p.exterior.coords)<=65,'FINITE_HOLELESS_LATTICE_SCOPE')
    tris=list(constrained_delaunay_triangles(p).geoms)
    need(unary_union(tris).equals(p),'EXACT_CONSTRAINED_COMPONENT_PARTITION')
    hulls=[];proofs=[];scan=0
    for tri in tris:
        rr=list(tri.exterior.coords)[:-1];swap=tri.bounds[2]-tri.bounds[0]<tri.bounds[3]-tri.bounds[1]
        rr=[(Fraction(y),Fraction(x))if swap else(Fraction(x),Fraction(y))for x,y in rr]
        lo,hi=math.ceil(min(y for x,y in rr)),math.floor(max(y for x,y in rr));scan+=max(0,hi-lo+1)
        need(scan<=max_rows,'FINITE_COMPLETE_LATTICE_ROW_BUDGET')
        endpoints=[];count=0
        for y in range(lo,hi+1):
            cross=[]
            for(ax,ay),(bx,by)in zip(rr,rr[1:]+rr[:1]):
                if ay==by:
                    if Fraction(y)==ay:cross.extend((ax,bx))
                elif min(ay,by)<=y<=max(ay,by):cross.append(ax+(Fraction(y)-ay)*(bx-ax)/(by-ay))
            if cross:
                a,b=math.ceil(min(cross)),math.floor(max(cross))
                if a<=b:endpoints.extend(((y,a),(y,b))if swap else((a,y),(b,y)));count+=b-a+1
        h=MultiPoint(endpoints).convex_hull if endpoints else Polygon()
        if h.geom_type=='Polygon':need(h.difference(tri).area==0,'LATTICE_HULL_CONSERVATIVE')
        hulls.append(h);proofs.append(dict(triangleWkbSHA256=sha(tri),scanlines=max(0,hi-lo+1),integerPoints=count,
            shorterAxisSwapped=swap,hullWkbHex=h.wkb.hex(),hullType=h.geom_type,hullAreaRootUnits2=h.area))
    return unary_union([h for h in hulls if h.geom_type=='Polygon']),hulls,proofs

class RobustClipper:
    def __init__(self,base,*,epoch_last_level=None,tile_size=256,owner_binding=None):
        self.base=base;self.epoch_last_level=base.level if epoch_last_level is None else epoch_last_level
        self.tile_size=tile_size;self.owner_binding=owner_binding;self.events=[];self.reference=None;self.emitted=None
        self.pixel_model_fallback_used=False
    def __getattr__(self,key):return getattr(self.base,key)
    @property
    def scale(self):return self.tile_size*(1<<self.epoch_last_level)/Q
    def no_area(self,p):
        g,hulls,lattice=exact_lattice(p)
        if not g.is_empty:return None,g,lattice
        # Collinear finite lattice positions cannot form a positive integer area.
        allh=unary_union(hulls).convex_hull
        need(allh.is_empty or allh.geom_type in('Point','LineString'),'NO_POSITIVE_INTEGER_COMPONENT')
        controls=[];ox,oy=self.origin
        for level in range(self.level,self.epoch_last_level+1):
            scale=self.tile_size*(1<<level)/Q
            points=[(math.floor((x+ox)*scale+.5),math.floor((y+oy)*scale+.5))for x,y in p.exterior.coords]
            rounded=Polygon(points)
            # Shoelace-zero alone is insufficient for a self-crossing walk.
            # A collinear hull is an exact zero OddEven-area certificate.
            need(MultiPoint(points).convex_hull.area==0,'UNREPRESENTABLE_COMPONENT_QROUND_POINTS_NOT_COLLINEAR')
            controls.append(dict(viewLevel=level,qRoundPoints=points,fillAreaPixels2=rounded.area))
        return dict(disposition='UNREPRESENTABLE_SUBGRID_FILL_NO_NATIVE_AREA',originalFloatWkbHex=p.wkb.hex(),
            originalFloatWkbSHA256=sha(p),ownerBinding=self.owner_binding,areaRootUnits2=p.area,
            bboxDiameterRootUnits=math.dist(p.bounds[:2],p.bounds[2:]),
            diameterPixelBoundAtAuthoredLastLevel=math.dist(p.bounds[:2],p.bounds[2:])*self.scale,
            noPoint1DiameterOrEmptyHausdorffClaim=True,integerLatticeProof=lattice,
            qRoundControls=controls,hostMethodBoundNotNativePixelObservation=True,
            originalQueryFooter='CALLER_RETAINS_FULL_UNCHANGED',noGeneralSmallAreaDrop=True),g,lattice
    def quantize_polygon(self,p):
        self.last_quantization_input=p
        need(p.is_valid and p.area>0,'VALID_FLOAT_REFERENCE_COMPONENT')
        # Reset the precision MODEL after quantization, leaving integer XY
        # unchanged. Subsequent diagnostics must not snap the Float reference.
        g=set_precision(set_precision(p,1,mode='valid_output'),0);path='GEOS_ONE_ROOT_UNIT_VALID_OUTPUT'
        noarea=None;lattice=None
        if g.is_empty:
            noarea,g,lattice=self.no_area(p)
            if noarea:self.events.append(noarea);return Polygon(),noarea
            path='EXACT_CONSERVATIVE_LATTICE_HULL_OF_REPRESENTABLE_COLLAPSED_GRID_COMPONENT'
        # Never silently erase an original hole. If grid output changes hole
        # count, expose a real representability case instead of filling it.
        before=len(p.interiors);after=sum(len(q.interiors)for q in parts(g,'Polygon'))
        distance=p.boundary.hausdorff_distance(g.boundary)
        authorized=None;pixel_receipt=None
        if self.owner_binding:
            o=self.owner_binding;key=(o['cellId'],o['uid'],o['sourceOwnerOrdinal'],self.level,sha(p),sha(g))
            authorized=AUTHORIZED.get(key)
        if authorized:
            need(g.is_valid and g.area>0 and self.epoch_last_level==authorized['authoredLastLevel'],'EXACT_AUTHORIZED_PIXEL_MODEL_SCOPE')
            pixel_receipt=projected_model_equal(p,g,self);self.pixel_model_fallback_used=True
            path='PIXEL_GRID_MODEL_EQUAL_AT_ALL_AUTHORED_LEVELS'
        if not authorized and(not g.is_valid or after!=before or distance*self.scale>.1)and not p.interiors and len(p.exterior.coords)<=65:
            h,lower,lattice=exact_lattice(p)
            if not h.is_empty and h.is_valid and sum(len(q.interiors)for q in parts(h,'Polygon'))==before:
                hd=p.boundary.hausdorff_distance(h.boundary)
                if hd*self.scale<=.1:g=h;distance=hd;after=before;path='EXACT_CONSERVATIVE_LATTICE_HULL_AFTER_GRID_BOUNDARY_LOSS'
        if not authorized and not(g.is_valid and g.area>0 and after==before and distance*self.scale<=.1):
            error=ValueError('ROBUST_GRID_HOLE_OR_POINT1_AUTHORED_PIXEL_CONTINUATION_REQUIRED')
            error.receipt=dict(ownerBinding=self.owner_binding,inputWkbHex=p.wkb.hex(),gridWkbHex=g.wkb.hex(),
                holesBefore=before,holesAfter=after,hausdorffRootUnits=distance,authoredPixels=distance*self.scale,
                authoredLastLevel=self.epoch_last_level,latticeProof=lattice)
            raise error
        added=g.difference(p);lost=p.difference(g)
        event=dict(policy='own-root-integer-display-roundtrip/1',path=path,gridSizeRootUnits=1,
            shapelyPrecisionMode='valid_output',ownerBinding=self.owner_binding,
            originalFloatWkbHex=p.wkb.hex(),originalFloatWkbSHA256=sha(p),outputWkbSHA256=sha(g),
            inputAreaRootUnits2=p.area,outputAreaRootUnits2=g.area,signedAddedAreaRootUnits2=added.area,
            signedLostAreaRootUnits2=lost.area,discreteBoundaryHausdorffRootUnits=distance,authoredPixelDeviation=distance*self.scale,
            continuousHausdorffGuaranteeClaim=False,
            pixelModelReceipt=pixel_receipt,exact97AuthorizationSHA256=AUTHORIZATION_SHA if authorized else None,
            geometricPoint1BoundPassed=distance*self.scale<=.1,
            ownAuthoringPixelBudget=.1,authoredEpoch=self.level,authoredLastLevel=self.epoch_last_level,
            inputComponents=1,outputComponents=len(parts(g,'Polygon')),holesBefore=before,holesAfter=after,
            latticeProof=lattice,noZeroInventFillClaim=added.area>0,originalQueriesUnchangedByCaller=True,
            originalPixelRasterOrUnlimitedOverzoomEqualityClaim=False)
        self.events.append(event);return g,event
    def clip_polygon(self,p,cell_id):
        reference=unary_union(parts(p.intersection(self.masks[cell_id]),'Polygon'));out=[];details=[]
        if getattr(self,'authorized_original_call',False):
            # Convert the ACTUAL WHOLE reference once. Do not mix old lattice
            # candidates or assume component snap/union is distributive.
            result=set_precision(set_precision(reference,1,mode='valid_output'),0)
            need(result.is_valid,'VALID_STANDARD_INTEGER_WHOLE_REGION')
            try:proof=projected_model_equal(reference,result,self)
            except ValueError as error:
                if str(error)!='AUTHORING_WHOLE_FILLED_PIXEL_MODEL_DIFFERENCE':raise
                # Four finite global floor/ceil phase candidates. These are
                # derived-output quantization choices, never source changes.
                proof=None
                for dx,dy in ((-.5,-.5),(-.5,.5),(.5,-.5),(.5,.5)):
                    candidate=set_precision(set_precision(translate(reference,dx,dy),1,mode='valid_output'),0)
                    if not candidate.is_valid:continue
                    try:trial=projected_model_equal(reference,candidate,self)
                    except ValueError:continue
                    result=candidate;proof=trial;proof['rootQuantizationPhaseChoice']=[dx,dy];break
                if proof is None:
                    error.receipt=dict(referenceFloatWkbHex=reference.wkb.hex(),standardIntegerWkbHex=result.wkb.hex(),ownerBinding=self.owner_binding,authoredLastLevel=self.epoch_last_level)
                    raise error
            hd=reference.boundary.hausdorff_distance(result.boundary)if not result.is_empty else None
            event=dict(policy='own-exact97-whole-standard-grid-display/1',ownerBinding=self.owner_binding,
                referenceFloatWkbHex=reference.wkb.hex(),referenceFloatWkbSHA256=sha(reference),
                actualValidIntegerOutputWkbHex=result.wkb.hex(),actualIntegerOutputWkbSHA256=sha(result),
                historicalLatticeCandidatesNotUsed=True,discreteBoundaryHausdorffRootUnits=hd,
                actualBoundaryAuthoringPixels=hd*self.scale if hd is not None else None,
                globalBoundaryPoint1Claim=False,signedAddedAreaRootUnits2=result.difference(reference).area,
                signedLostAreaRootUnits2=reference.difference(result).area,
                holesBefore=sum(len(q.interiors)for q in parts(reference,'Polygon')),
                holesAfter=sum(len(q.interiors)for q in parts(result,'Polygon')),
                disposition='PIXEL_GRID_MODEL_EMPTY_DISPLAY'if result.is_empty else proof['disposition'],
                positiveFloatingClipWasOmitted=reference.area>0 and result.is_empty,
                noU32RepresentabilityOrNoPositiveComponentRemovedClaim=True,
                pixelModelReceipt=proof,authorizedOriginalCallInputSHA256=CALL_INPUTS_SHA,
                originalQueryFooter='CALLER_RETAINS_FULL_UNCHANGED',
                additionalLS_LC_BoundaryStrokeImpactNotProven=True)
            self.events.append(event);self.reference=reference;self.emitted=result;self.pixel_model_fallback_used=True
            return result,event
        for part in parts(reference,'Polygon'):
            g,r=self.quantize_polygon(part);details.append(r)
            if not g.is_empty:out.append(g)
        result=unary_union(out);need(result.is_valid,'ROBUST_WHOLE_VALID')
        self.reference=reference;self.emitted=result
        return result,dict(referenceFloatWkbSHA256=sha(reference),quantization=details,
            signedAddedAreaRootUnits2=result.difference(reference).area,
            signedLostAreaRootUnits2=reference.difference(result).area,primaryBoundaryPoint1Policy=True,
            pixelModelFallbackUsed=False)

def wrap_clipper(base,*,epoch_last_level=None,tile_size=256,owner_binding=None):
    return RobustClipper(base,epoch_last_level=epoch_last_level,tile_size=tile_size,owner_binding=owner_binding)

def decode(co,ix,rec,clipper):
    need(len(rec)==32 and rec[28]in(2,3),'BOUND_KIND2_3_OWNER')
    n=struct.unpack_from('<I',ix)[0];need(len(ix)==4+4*n and struct.unpack_from('<I',rec,8)[0]==0,'STANDALONE_COMPLETE_INDEX')
    outer=[];inner=[];lines=[];elements=[]
    for w, in struct.iter_unpack('<I',ix[4:]):
        role=w>>24;at=w&LOW24;need(at+4<=len(co),'ELEMENT_HEADER_RANGE');h=struct.unpack_from('<I',co,at)[0]
        count=h&LOW24;need(h>>24==1 and count>1 and at+4+8*count<=len(co),'GENUINE_ROOT1_U32_XY')
        pts=list(struct.iter_unpack('<II',co[at+4:at+4+8*count]));need(all(0<=v<=Q for p in pts for v in p),'ROOT_WORLD_U32_DOMAIN')
        need(role&3 in(0,1),'BOUND_REVERSE_BIT');directed=list(reversed(pts))if role&3==1 else pts
        if rec[28]==2:
            need(role in(16,17),'BOUND_SIMPLE_COALNE_LINE');g=LineString(clipper.local(directed));lines.append(g)
        else:
            need(role in(8,9,24,25,20,21)and directed[0]==directed[-1],'BOUND_CLOSED_FILL_USAGE')
            g=Polygon(clipper.local(directed));need(g.is_valid and g.area>0,'VALID_ORIGINAL_FILL_RING')
            (outer if role&12==8 else inner).append(g)
        elements.append((g,role))
    whole=unary_union(lines)if rec[28]==2 else unary_union(outer).difference(unary_union(inner))
    need(whole.is_valid,'VALID_NATIVE_ROLE_WHOLE_OWNER');return whole,elements

def clip_owner(co,ix,rec,*,cell_id,clipper,source_tile=(0,0,0),coordinate_format=2,epoch_last_level=None,owner_binding=None):
    if not isinstance(clipper,RobustClipper):
        clipper=wrap_clipper(clipper,epoch_last_level=epoch_last_level,owner_binding=owner_binding)
    if epoch_last_level is not None:need(clipper.epoch_last_level==epoch_last_level,'AUTHORED_LAST_LEVEL_EXACT')
    if owner_binding is not None:need(clipper.owner_binding==owner_binding,'ACTUAL_OWNER_BINDING_EXACT')
    need(source_tile==(0,0,0)and coordinate_format==2,'ONLY_PREPARED_ROOT1_FORMAT2')
    whole,elements=decode(co,ix,rec,clipper);mask=clipper.masks[cell_id]
    clipper.authorized_original_call=False
    if clipper.owner_binding:
        o=clipper.owner_binding;admission=AUTHORIZED_CALLS.get((o['cellId'],o['uid'],o['sourceOwnerOrdinal'],clipper.level))
        if admission:
            need(hashlib.sha256(co).hexdigest()==admission['preparedCoordinatesSHA256']and hashlib.sha256(ix).hexdigest()==admission['preparedIndicesSHA256'],'EXACT_ORIGINAL_97_CALL_GEOMETRY_BYTES')
            need(clipper.epoch_last_level==admission['authoredLastLevel'],'EXACT_ORIGINAL_97_CALL_AUTHORED_LEVELS')
            clipper.authorized_original_call=True
    if mask.covers(whole):
        clipper.reference=whole;clipper.emitted=whole
        return co,ix,rec,dict(wholeOwnerContained=True,allCoordinateIndexRecordBytesExact=True)
    output=[];details=[]
    if rec[28]==3:
        g,detail=clipper.clip_polygon(whole,cell_id);details.append(detail)
        # Explicit native Exterior24 / Interior20 preserves complete hole sets;
        # it avoids triangle-by-triangle duplicate boundary rounding.
        for p in parts(g,'Polygon'):
            output.append((list(p.exterior.coords),24));output.extend((list(r.coords),20)for r in p.interiors)
    else:
        refs=[];emitted=[]
        for original,role in elements:
            for p in parts(original.intersection(mask),'LineString'):
                g=set_precision(set_precision(p,1,mode='keep_collapsed'),0)
                if not g.is_valid or g.length==0:
                    # Keep the positive source fragment as a genuine two-point
                    # U32 line. Candidate distance is checked against the whole
                    # reference, never promoted to an invented area/point.
                    a=p.coords[0];b=p.coords[-1];choices=[]
                    for ax in range(math.floor(a[0])-1,math.ceil(a[0])+2):
                        for ay in range(math.floor(a[1])-1,math.ceil(a[1])+2):
                            for bx in range(math.floor(b[0])-1,math.ceil(b[0])+2):
                                for by in range(math.floor(b[1])-1,math.ceil(b[1])+2):
                                    if (ax,ay)==(bx,by):continue
                                    h=LineString([(ax,ay),(bx,by)])
                                    hd=h.hausdorff_distance(p)
                                    if hd*clipper.scale<=.1:choices.append((hd,math.dist(a,(ax,ay))+math.dist(b,(bx,by)),h))
                    if not choices:
                        error=ValueError('ROBUST_POSITIVE_LINE_FRAGMENT_POINT1_CONTINUATION_REQUIRED')
                        error.receipt=dict(ownerBinding=clipper.owner_binding,referenceWkbHex=p.wkb.hex(),authoredLastLevel=clipper.epoch_last_level)
                        raise error
                    g=min(choices,key=lambda r:(r[0],r[1],r[2].wkb))[2]
                distance=g.hausdorff_distance(p);need(distance*clipper.scale<=.1,'ROBUST_LINE_POINT1_PIXEL_BOUND')
                points=list(g.coords)
                if role&3==1:points.reverse()
                output.append((points,role));refs.append(p);emitted.append(g)
                details.append(dict(referenceWkbSHA256=sha(p),hausdorffRootUnits=distance,authoredPixelDeviation=distance*clipper.scale))
        clipper.reference=unary_union(refs);clipper.emitted=unary_union(emitted)
    coords=bytearray();words=[]
    for local,role in output:
        need(len(coords)<1<<24 and 1<len(local)<1<<24,'NATIVE_LOW24_COUNTS_AND_OFFSETS')
        points=[(int(x+clipper.origin[0]),int(y+clipper.origin[1]))for x,y in local]
        need(all(0<=v<=Q for p in points for v in p),'FINAL_WORLD_U32_RANGE')
        words.append((role<<24)|len(coords));coords.extend(struct.pack('<I',(1<<24)|len(points)))
        for point in points:coords.extend(struct.pack('<II',*point))
    idx=struct.pack('<I',len(words))+b''.join(struct.pack('<I',w)for w in words)
    record=bytearray(rec)if words else None
    if record is not None:struct.pack_into('<I',record,8,0)
    result=bytes(record)if record is not None else None
    return bytes(coords),idx,result,dict(policy='own-native-role-integer-display-encoder/1',kind=rec[28],
        details=details,originalQueryFooter='CALLER_RETAINS_FULL_UNCHANGED',reverseAppliedOnce=True,
        recordComplementUnchanged=result is None or result[:8]==rec[:8]and result[12:]==rec[12:],
        outputElements=len(words),physicalNativePixelProof=False)

def validate_final_owner(clipper,co,ix,rec,*,cell_id):
    need(clipper.ownerAuthenticSemantics['own']==cell_id,'FINAL_BOUND_SOURCE_ID')
    ref=clipper.reference;expected=clipper.emitted;need(ref is not None and expected is not None,'ACTUAL_CLIP_REFERENCE_AVAILABLE')
    if rec is None:
        need(co==b''and ix==struct.pack('<I',0)and expected.is_empty,'FINAL_EMPTY_SERIALIZATION')
        model=projected_model_equal(ref,expected,clipper)if clipper.pixel_model_fallback_used else None
        return dict(passed=True,disposition='PIXEL_GRID_MODEL_EMPTY_DISPLAY'if model else'NO_NATIVE_AREA_WITH_EXPLICIT_COMPONENT_PROOFS',
            pixelModelWholeSerializedOwnerReceipt=model,positiveFloatingClipWasOmitted=ref.area>0,
            originalQueryFooter='CALLER_RETAINS_FULL_UNCHANGED',events=clipper.events)
    g,_=decode(co,ix,rec,clipper);need(g.equals(expected),'SERIALIZED_NATIVE_ROLE_INVERSE_EXACT')
    try:whole_pixel=projected_model_equal(ref,g,clipper)if clipper.pixel_model_fallback_used else None
    except ValueError as error:
        error.receipt=dict(referenceFloatWkbHex=ref.wkb.hex(),expectedWkbHex=expected.wkb.hex(),
            actualSerializedRolesWkbHex=g.wkb.hex(),coordinatesHex=co.hex(),indicesHex=ix.hex(),record32Hex=rec.hex(),
            rootOrigin=list(clipper.origin),ownerBinding=clipper.owner_binding,authoredEpoch=clipper.level,authoredLastLevel=clipper.epoch_last_level)
        raise
    # Each component was independently bound before serialization; empty no-area
    # artifacts carry their separate lattice/qRound proof, not a Hausdorff claim.
    semantics=clipper.ownerAuthenticSemantics
    return dict(passed=True,policy='own-final-serialized-role-point1-authoring-roundtrip/1',
        finalIntegerGeometryWkbSHA256=sha(g),originalFloatClipWkbSHA256=sha(ref),
        signedAddedAreaRootUnits2=g.difference(ref).area if rec[28]==3 else None,
        signedLostAreaRootUnits2=ref.difference(g).area if rec[28]==3 else None,
        outsideAuthenticOwnAreaRootUnits2=g.difference(semantics['ownCoverage']).area if rec[28]==3 else None,
        higherAuthenticActiveFillOverlapAreaRootUnits2=g.intersection(semantics['higher']).area if rec[28]==3 else None,
        authoredLastLevel=clipper.epoch_last_level,ownAuthoringPixelBudget=.1,
        pixelModelWholeSerializedOwnerReceipt=whole_pixel,
        exactZeroOutsideAuthenticDomainClaim=False,unlimitedRuntimeZoomOrNativePaintProof=False,
        originalQueryFooter='CALLER_RETAINS_FULL_UNCHANGED',componentEvents=clipper.events)
