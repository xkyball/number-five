from . import source_clip as core

"""Own extension for genuine closed Kind3 Usage24/20 fill-ring owners.

No Kind8, guessed ring grouping, invalid repair or triangulation declaration.
The frozen triangle kernel provides coverage masks and whole-polygon inward
integer quantization. This adapter emits actual closed exterior/interior rings.
"""

from pathlib import Path

import hashlib, json, struct, sys

from shapely.geometry import Polygon, box

from shapely.ops import unary_union

need=core.need

def decode_ring_owner(coordinates,indices,record,*,source_tile,coordinate_format=2):
    need(type(coordinates)is bytes and type(indices)is bytes and len(record)==32,'COMPLETE_OWNER_BYTES')
    need(record[28]==3 and coordinate_format==2,'GENUINE_KIND3_ENUM2_ONLY')
    need(len(indices)>=4,'INDEX_HEADER')
    n=struct.unpack_from('<I',indices)[0];need(n>0 and len(indices)==4+4*n,'COMPLETE_INDEX_OCCURRENCES')
    rings=[]
    for word, in struct.iter_unpack('<I',indices[4:]):
        at=word&core.LOW24;role=word>>24
        need(role in (24,25,20,21),'ONLY_CLOSED_EXTERIOR24_INTERIOR20_WITH_REVERSE')
        need(at+4<=len(coordinates),'ELEMENT_BOUND');h=struct.unpack_from('<I',coordinates,at)[0]
        count=h&core.LOW24;flags=h>>24
        need(flags in (0,1) and (flags==1 or tuple(source_tile)==(0,0,0)),'ROOT_OR_GENUINE_L0_ONLY')
        need(count>=4 and at+4+8*count<=len(coordinates),'COMPLETE_U32_RING')
        pts=list(struct.iter_unpack('<II',coordinates[at+4:at+4+8*count]))
        need(all(0<=v<=core.Q for p in pts for v in p),'ROOT_INTEGER_DOMAIN')
        if role&3==1:pts.reverse()
        need(pts[0]==pts[-1] and len(set(pts[:-1]))>=3 and core.signed2(pts)!=0,'CLOSED_NONZERO_RING')
        need(Polygon(pts).is_valid,'INVALID_ORIGINAL_OWNER_RING_NO_REPAIR')
        rings.append(dict(points=pts,role=role,rootFlags=flags,originalOffset=at))
    need(any(r['role']&12==8 for r in rings),'OWNER_EXTERIOR_REQUIRED')
    need(len({r['rootFlags']for r in rings})==1,'CONSISTENT_OWNER_ROOT_BASIS')
    return rings

def whole_ring_geometry(rings,clipper):
    # Native readPolygon assigns Usage0/8 Exterior(1), Usage4/12 Other(2).
    # The simple-fill consumer unions all exterior polygons and subtracts the
    # interior polygons. Do not pair holes by centroid or discard a lone ring.
    outer=unary_union([Polygon(clipper.local(r['points']))for r in rings if r['role']&12==8])
    inner=unary_union([Polygon(clipper.local(r['points']))for r in rings if r['role']&12==4])
    need(inner.difference(outer).area==0,'COMPLETE_INTERIOR_INSIDE_EXTERIOR_UNION')
    whole=outer.difference(inner)
    need(whole.is_valid and whole.area>0,'VALID_WHOLE_OWNER_FILL')
    return whole

def ring_bbox(rings):
    pts=[p for r in rings for p in r['points']]
    return min(x for x,y in pts),min(y for x,y in pts),max(x for x,y in pts),max(y for x,y in pts)

def owner_scoped_winner(sources,denominator,*,level,owner_bbox):
    """Ignore ONLY complete excluded rings with exact disjoint AABB.

    This can accept a globally invalid exclusion if it is mathematically
    irrelevant to THIS owner. Its original bytes remain pinned by the caller;
    no repair, altered source metadata or global validity claim follows.
    Every resulting mask is restricted to the explicit integer owner bbox.
    Intersecting invalid coverage still fails in the frozen core.
    """
    need(len(owner_bbox)==4 and all(type(v)is int and 0<=v<=core.Q for v in owner_bbox)
         and owner_bbox[0]<owner_bbox[2] and owner_bbox[1]<owner_bbox[3],'EXACT_INTEGER_OWNER_SCOPE_BBOX')
    scoped=[];omitted=[]
    for s in sources:
        excluded=[]
        for ring in s.excluded:
            bounds=(min(x for x,y in ring),min(y for x,y in ring),max(x for x,y in ring),max(y for x,y in ring))
            disjoint=bounds[2]<owner_bbox[0] or bounds[0]>owner_bbox[2] or bounds[3]<owner_bbox[1] or bounds[1]>owner_bbox[3]
            if disjoint:
                payload=b''.join(struct.pack('<II',*p)for p in ring)
                omitted.append(dict(cellId=s.cell_id,ringBBox=list(bounds),ownerBBox=list(owner_bbox),
                    points=len(ring),originalPointSHA256=core.sha(payload),globallyValid=Polygon(ring).is_valid,
                    reason='EXACT_DISJOINT_AABB_NO_EFFECT_WITHIN_OWNER_SCOPE'))
            else:excluded.append(ring)
        scoped.append(type(s)(s.cell_id,s.native_scale,s.covered,tuple(excluded)))
    winner=core.GeometryWinner(scoped,denominator,level=level)
    local=winner.local([(owner_bbox[0],owner_bbox[1]),(owner_bbox[2],owner_bbox[3])])
    region=box(*local[0],*local[1])
    winner.masks={k:g.intersection(region)for k,g in winner.masks.items()}
    winner.originalSources=tuple(sources);winner.ownerScopeBBox=tuple(owner_bbox);winner.omittedExcludedRings=omitted
    return winner

def clip_ring_owner(coordinates,indices,record,*,cell_id,clipper,source_tile,coordinate_format=2):
    need(cell_id in clipper.masks,'BOUND_SOURCE_ID')
    rings=decode_ring_owner(coordinates,indices,record,source_tile=source_tile,coordinate_format=coordinate_format)
    if hasattr(clipper,'ownerScopeBBox'):
        b=ring_bbox(rings);s=clipper.ownerScopeBBox
        need(s[0]<=b[0]<=b[2]<=s[2] and s[1]<=b[1]<=b[3]<=s[3],'SCOPED_WINNER_CANNOT_SERVE_OUTSIDE_OWNER_BBOX')
    whole=whole_ring_geometry(rings,clipper);mask=clipper.masks[cell_id]
    same=mask.covers(whole)
    if same:
        output=[(r['points'],r['role'])for r in rings];geometry=whole
        detail=dict(wholeOwnerContained=True,allOriginalDirectedPointBytesPreserved=True,
            positiveComponents=len(core.parts(whole,'Polygon')),quantization=[])
    else:
        geometry,detail=clipper.clip_polygon(whole,cell_id);output=[]
        meta={}
        for usage in (8,4):
            samples=[r for r in rings if r['role']&12==usage]
            if not samples:continue
            # A changed topology cannot keep per-ring identity. Require one
            # original role/reverse/sign contract for each semantic role.
            contracts={(r['role'],core.signed2(r['points'])>0)for r in samples}
            need(len(contracts)==1,'UNIFORM_CHANGED_RING_ROLE_REVERSE_AND_SIGN')
            meta[usage]=next(iter(contracts))
        # A new winner exclusion can create a hole even if the source owner had
        # none. Use the genuine interior role20, with opposite directed winding
        # to the authentic exterior contract; receipt identifies NEW mask hole.
        if 4 not in meta:meta[4]=(20,not meta[8][1])
        for p in core.parts(geometry,'Polygon'):
            for usage,ring in [(8,p.exterior)]+[(4,r)for r in p.interiors]:
                pts=clipper.global_points(ring.coords);role,positive=meta[usage]
                if (core.signed2(pts)>0)!=positive:pts=list(reversed(pts))
                output.append((pts,role))
    co=bytearray();words=[]
    for pts,role in output:
        need(4<=len(pts)<=core.LOW24 and len(co)<=core.LOW24,'NATIVE_LOW24_RING_START_COUNT')
        need(pts[0]==pts[-1] and core.signed2(pts)!=0,'OUTPUT_COMPLETE_NONCOLLAPSED_RING')
        words.append((role<<24)|len(co));stored=list(reversed(pts))if role&3==1 else pts
        co+=struct.pack('<I',len(pts)|(1<<24))+b''.join(struct.pack('<II',*p)for p in stored)
    need(len(co)<1<<24,'TOTAL_COORDINATE_STREAM_LOW24')
    ix=struct.pack('<I',len(words))+struct.pack('<'+'I'*len(words),*words)
    rec=bytearray(record)if words else None
    if rec is not None:struct.pack_into('<I',rec,8,0)
    if rec is not None:
        actual=decode_ring_owner(bytes(co),ix,bytes(rec),source_tile=source_tile,coordinate_format=2)
        inverse=whole_ring_geometry(actual,clipper)
        need(inverse.equals(geometry),'NATIVE_RING_ROLE_INVERSE_EXACT_GEOMETRY')
        need(inverse.difference(mask).area==0 and inverse.difference(whole).area==0,'NO_WINNER_OR_OWNER_FILL_LEAK')
    receipt=dict(policy='own-source-parity-closed-kind3-ring-clip/1',kind=3,inputRingOccurrences=len(rings),
        outputRingOccurrences=len(words),inputExteriorRings=sum(r['role']&12==8 for r in rings),
        inputInteriorRings=sum(r['role']&12==4 for r in rings),outputExteriorRings=sum(r&12==8 for p,r in output),
        outputInteriorRings=sum(r&12==4 for p,r in output),changed=not same,
        sourceCell=cell_id,requestedViewLevel=clipper.level,sourceTile=list(source_tile),rootOutput=1,coordinateFormat=2,
        UIDClassWord20RenderAndRecordComplementUnchanged=True,originalQueryFooter='CALLER_MUST_RETAIN',
        noFillOutsideOriginalOwnerOrWinner=True,closedPolygonUsageNotTriangle=True,clipDetail=detail,
        scopeBBox=list(clipper.ownerScopeBBox)if hasattr(clipper,'ownerScopeBBox')else None,
        excludedRingsProvenDisjoint=getattr(clipper,'omittedExcludedRings',[]),
        noGlobalCoverageValidityClaim=True,coordinateSHA256=core.sha(co),indexSHA256=core.sha(ix),
        kind8Admission=False,nativeCalls=0,countryBuilds=0,nativePixelProof=False)
    return bytes(co),ix,bytes(rec)if rec is not None else None,receipt
