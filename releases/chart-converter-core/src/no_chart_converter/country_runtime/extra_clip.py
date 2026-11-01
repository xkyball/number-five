from . import source_clip as core

"""Own additive simple-line Reverse and mixed-winding exterior-triangle clip.

Frozen common coverage/quantizer code is reused byteexact. Original query data
must remain in the parent footer. No native/device/country actions here.
"""

from pathlib import Path

import hashlib, struct, sys

from shapely.geometry import Polygon,LineString

from shapely.ops import unary_union

need=core.need

def decode_extra_owner(coordinates,indices,record,*,source_tile,coordinate_format=2):
    need(type(coordinates)is bytes and type(indices)is bytes and len(record)==32,'COMPLETE_OWNER_BYTES')
    need(coordinate_format==2 and record[28]in(2,3),'ONLY_ENUM2_LINE_OR_FILL_TRIANGLE')
    need(len(indices)>=4,'INDEX_HEADER');n=struct.unpack_from('<I',indices)[0]
    need(n>0 and len(indices)==4+4*n,'COMPLETE_INDEX_ARRAY');elements=[]
    for word, in struct.iter_unpack('<I',indices[4:]):
        at=word&core.LOW24;role=word>>24;need(at+4<=len(coordinates),'ELEMENT_START')
        h=struct.unpack_from('<I',coordinates,at)[0];count=h&core.LOW24;flags=h>>24
        need(flags in(0,1) and(flags==1 or tuple(source_tile)==(0,0,0)),'ROOT_OR_GENUINE_L0_ONLY')
        need(count>=2 and at+4+8*count<=len(coordinates),'COMPLETE_U32_XY')
        raw=coordinates[at:at+4+8*count];stored=list(struct.iter_unpack('<II',raw[4:]))
        need(all(0<=v<=core.Q for p in stored for v in p),'ROOT_WORLD_INTEGER_DOMAIN')
        if record[28]==2:need(role in(16,17),'ONLY_SIMPLE_LINE16_OR_REVERSE17')
        else:
            need(role==8 and count==4 and stored[0]==stored[-1] and len(set(stored[:-1]))==3,'ONLY_CLOSED4_ROLE8_TRIANGLES')
            need(core.signed2(stored)!=0,'DEGENERATE_TRIANGLE_REQUIRES_SEPARATE_NATIVE_EMPTY_CONSUMER_PROOF')
        directed=list(reversed(stored))if role&3==1 else stored
        elements.append(dict(role=role,stored=stored,directed=directed,raw=raw,originalOffset=at))
    return record[28],elements

def _encode(pieces,record):
    co=bytearray();refs=[]
    for directed,role in pieces:
        need(len(co)<=core.LOW24 and 1<len(directed)<=core.LOW24,'LOW24_ELEMENT_START_COUNT')
        refs.append((role<<24)|len(co));stored=list(reversed(directed))if role&3==1 else directed
        co+=struct.pack('<I',len(stored)|(1<<24))+b''.join(struct.pack('<II',*p)for p in stored)
    need(len(co)<1<<24,'COMPLETE_COORDINATE_STREAM_LOW24')
    ix=struct.pack('<I',len(refs))+struct.pack('<'+'I'*len(refs),*refs)
    rec=bytearray(record)if refs else None
    if rec is not None:struct.pack_into('<I',rec,8,0)
    return bytes(co),ix,bytes(rec)if rec is not None else None

def clip_extra_owner(coordinates,indices,record,*,cell_id,clipper,source_tile,coordinate_format=2):
    need(cell_id in clipper.masks,'BOUND_SOURCE_ID')
    kind,elements=decode_extra_owner(coordinates,indices,record,source_tile=source_tile,coordinate_format=coordinate_format)
    pieces=[];details=[];kept=0;changed=0
    if kind==2:
        for e in elements:
            g=LineString(clipper.local(e['directed']))
            if clipper.masks[cell_id].covers(g):
                pieces.append((e['directed'],e['role']));kept+=1
            else:
                changed+=1;out,d=clipper.clip_line(g,cell_id)
                pieces.extend((clipper.global_points(p.coords),e['role'])for p in out)
                details.append(dict(originalRole=e['role'],originalElementOffset=e['originalOffset'],**d))
    else:
        whole=unary_union([Polygon(clipper.local(e['directed']))for e in elements])
        need(whole.is_valid and whole.area>0,'VALID_COMPLETE_EXTERIOR_TRIANGLE_UNION')
        if clipper.masks[cell_id].covers(whole):
            # ALL original signs stay byteexact in contained primitives.
            pieces=[(e['directed'],8)for e in elements];kept=len(elements)
        else:
            changed=len(elements);geometry,d=clipper.clip_polygon(whole,cell_id);details.append(d)
            for polygon in core.parts(geometry,'Polygon'):
                triangles=list(core.constrained_delaunay_triangles(polygon).geoms)
                need(triangles and unary_union(triangles).equals(polygon),'WHOLE_OWNER_HOLE_SAFE_TESSELLATION')
                for t in triangles:
                    pts=clipper.global_points(t.exterior.coords)
                    need(len(pts)==4 and pts[0]==pts[-1] and core.signed2(pts)!=0,'NONCOLLAPSED_NEW_TRIANGLE')
                    # OUR newly tessellated exterior triangles use negative
                    # directed winding. Native role8 makes each Exterior1;
                    # OddEvenFill and per-polygon union do not use source sign.
                    if core.signed2(pts)>0:pts=list(reversed(pts))
                    pieces.append((pts,8))
    co,ix,rec=_encode(pieces,record)
    signed={'+':sum(core.signed2(e['stored'])>0 for e in elements),'-':sum(core.signed2(e['stored'])<0 for e in elements)}if kind==3 else None
    receipt=dict(policy='own-simple-reverse-and-mixed-exterior-triangle-clip/1',sourceCell=cell_id,requestedViewLevel=clipper.level,
        kind=kind,inputIndexOccurrences=len(elements),outputIndexOccurrences=len(pieces),unchangedContainedPrimitives=kept,
        clippedOrRemovedPrimitives=changed,inputRoleCounts={str(r):sum(e['role']==r for e in elements)for r in sorted({e['role']for e in elements})},
        originalTriangleSignCounts=signed,containedPointBytesAndSignsPreserved=True,
        originalLineRolesPreserved=True,reverseApplication='stored -> directed exactly once; encode directed -> stored iff original Role17',
        newTriangleWinding='OWN_NEGATIVE_EXTERIOR_WINDING_ONLY_WHEN_WHOLE_OWNER_RETESSSELLATED',
        newTrianglesDoNotClaimOriginalPerTriangleSignIdentity=True,originalQueryFooter='CALLER_MUST_RETAIN_FULL_ORIGINAL',
        UIDClassWord20RenderAndRecordComplementExactExceptIndexOffset0=True,allOutputRoot1=True,coordinateFormat=2,
        coordinateSHA256=core.sha(co),indexSHA256=core.sha(ix),clipDetails=details,
        degenerateOriginalTrianglesEncountered=0,degenerateOriginalNativeEmptyAdmission=False,
        nativeCalls=0,countryBuilds=0,nativePaintOrGPUClaim=False)
    return co,ix,rec,receipt
