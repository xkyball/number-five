"""Own conservative integer coverage clip, not vendor code or a country writer.

Scope: complete UInt32 Root/L0 closed-four triangle fill owners (Kind3,
Usage8), and simple polyline owners (Kind2). Other area/ring forms, Kind8,
Z, complex-line pattern phases and source-wide query reconstruction are
deliberately refused. Caller retains original Render/Additional/query owner.
"""

from fractions import Fraction

from itertools import product

import hashlib, math, struct

from shapely import constrained_delaunay_triangles, geos_version_string

from shapely.geometry import Polygon, MultiPolygon, LineString, Point

from shapely.ops import unary_union

Q = 2147483649

LOW24 = (1 << 24)-1

MAX_VERTEX_ERROR = math.sqrt(2)

POLICY = 'own-source-parity-conservative-geometry-clip/1'

def need(ok, label):
    if not ok: raise ValueError(label)

def sha(b): return hashlib.sha256(b).hexdigest()

def signed2(ring):
    return sum(ax*by-bx*ay for (ax,ay),(bx,by) in zip(ring,ring[1:]))

def parts(g, kind):
    if g.is_empty: return []
    if g.geom_type == kind: return [g]
    if hasattr(g,'geoms'): return [p for h in g.geoms for p in parts(h,kind)]
    return []

class GeometryWinner:
    """Strict valid-source masks using the caller's approved denominator.

    Covered and excluded rings are actual coverage, never a fill surface.
    The rank matches source_winner.SourceWinner at this level. Polygon mask
    closures share zero-area boundaries; open endpoint ownership is not a
    native line-raster proof. Invalid source coverage fails without repair.
    """
    def __init__(self, sources, display_denominator, *, level):
        self.sources = tuple(sources); self.level = level
        need(type(level) is int and 0 <= level <= 29,'LEVEL')
        need(isinstance(display_denominator,Fraction) and display_denominator>0,'EXPLICIT_OWN_DENOMINATOR')
        need(self.sources and len({s.cell_id for s in self.sources})==len(self.sources),'UNIQUE_SOURCES')
        self.denominator = display_denominator
        allpoints = [p for s in self.sources for r in s.covered+s.excluded for p in r]
        need(all(type(v)is int and 0<=v<=Q for p in allpoints for v in p),'AUTHENTIC_ROOT_U32_DOMAIN')
        self.origin=(min(x for x,y in allpoints),min(y for x,y in allpoints))
        self.coverage={}
        for s in self.sources:
            need(type(s.native_scale)is int and s.native_scale>0,'AUTHENTIC_NATIVE_SCALE')
            rings=[]
            for r in s.covered+s.excluded:
                need(len(r)>=4 and r[0]==r[-1] and signed2(r)!=0,'COMPLETE_SOURCE_RINGS')
                p=Polygon(self.local(r)); need(p.is_valid,'INVALID_SOURCE_COVERAGE_NO_REPAIR'); rings.append(p)
            cover=unary_union(rings[:len(s.covered)])
            exclusion=unary_union(rings[len(s.covered):])
            g=cover.difference(exclusion)
            need(g.is_valid and not g.is_empty,'VALID_NONEMPTY_SOURCE_DOMAIN')
            self.coverage[s.cell_id]=g
        def rank(s):
            return (0,-s.native_scale,s.cell_id) if s.native_scale<=display_denominator else (1,s.native_scale,s.cell_id)
        self.priority=tuple(sorted(self.sources,key=rank))
        self.masks={}; before=Polygon()
        for s in self.priority:
            self.masks[s.cell_id]=self.coverage[s.cell_id].difference(before)
            before=before.union(self.coverage[s.cell_id])
        need(all(g.is_valid for g in self.masks.values()),'VALID_WINNER_PARTITION')
        for i,s in enumerate(self.priority):
            need(all(self.masks[s.cell_id].intersection(self.masks[t.cell_id]).area==0 for t in self.priority[i+1:]),'DISJOINT_WINNER_INTERIORS')

    def local(self, points):
        ox,oy=self.origin; return [(x-ox,y-oy) for x,y in points]
    def global_points(self, points):
        ox,oy=self.origin
        result=[(int(x)+ox,int(y)+oy) for x,y in points]
        need(all(0<=v<=Q for p in result for v in p),'OUTPUT_ROOT_U32_DOMAIN')
        return result

    def candidate_points(self, point, allowed):
        x,y=point
        if x==int(x) and y==int(y):
            # Exact original integer vertices remain unchanged.
            return [(int(x),int(y))]
        candidates=[]
        for a,b in product((math.floor(x),math.ceil(x)),(math.floor(y),math.ceil(y))):
            if allowed.covers(Point(a,b)):
                candidates.append((a,b))
        candidates=sorted(set(candidates),key=lambda p:((p[0]-x)**2+(p[1]-y)**2,p))
        need(candidates,'NO_SAFE_FLOOR_CEIL_VERTEX')
        return candidates

    def quantize_polygon(self, polygon):
        """Choose inward floor/ceil vertices; reject leaks, topology loss,
        collapse, or excessive displacement. No epsilon/repair/buffer.

        This is explicit bounded quantization, not exact preservation of
        fractional intersections. Retriangulation follows the whole polygon
        including its holes, never a list of independently discarded rings.
        """
        need(polygon.is_valid and polygon.area>0,'VALID_CLIPPED_POLYGON')
        rings=[list(polygon.exterior.coords)]+[list(r.coords)for r in polygon.interiors]
        out=[]
        for ring in rings:
            choices=[self.candidate_points(p,polygon) for p in ring[:-1]]
            # Finite cycle dynamic programming: at most 4 states per vertex.
            # Shared owner boundaries are quantized ONCE before tessellation,
            # so adjacent new triangles cannot choose different seam vertices.
            cycles=[]
            for first in choices[0]:
                states={first:((first[0]-ring[0][0])**2+(first[1]-ring[0][1])**2,[first])}
                for i,cs in enumerate(choices[1:],1):
                    nxt={}
                    for b in cs:
                        possible=[]
                        for a,(cost,path) in states.items():
                            if a!=b and LineString([a,b]).difference(polygon).length==0:
                                possible.append((cost+(b[0]-ring[i][0])**2+(b[1]-ring[i][1])**2,path+[b]))
                        if possible:nxt[b]=min(possible,key=lambda p:(p[0],p[1]))
                    states=nxt
                    if not states:break
                for last,(cost,path) in states.items():
                    if last!=first and LineString([last,first]).difference(polygon).length==0:
                        cycles.append((cost,path+[first]))
            need(cycles,'NO_SAFE_INTEGER_RING_CYCLE')
            out.append(min(cycles,key=lambda p:(p[0],p[1]))[1])
        g=Polygon(out[0],out[1:])
        need(g.is_valid and g.area>0 and len(g.interiors)==len(polygon.interiors),'NO_RING_TOPOLOGY_LOSS')
        need(g.difference(polygon).area==0,'NO_QUANTIZATION_FILL_LEAK')
        errors=[math.dist(p,q)for r,s in zip(rings,out)for p,q in zip(r,s)]
        need(max(errors,default=0)<=MAX_VERTEX_ERROR and g.hausdorff_distance(polygon)<=MAX_VERTEX_ERROR,'BOUNDARY_HAUSDORFF_BOUND')
        return g,dict(vertices=sum(len(r)-1 for r in rings),newFractionalVertices=sum(p[0]!=int(p[0])or p[1]!=int(p[1])for r in rings for p in r[:-1]),
            maxVertexDisplacementRootUnits=max(errors,default=0),removedAreaRootUnits2=polygon.area-g.area,
            holesBefore=len(polygon.interiors),holesAfter=len(g.interiors),outsideOriginalClipAreaRootUnits2=0,
            quantization='whole-owner inside floor/ceil ring cycles, strict GEOS difference-area zero',precision='Float64 translated integer Root units')

    def clip_polygon(self, polygon, cell_id):
        need(cell_id in self.masks and polygon.is_valid,'BOUND_VALID_FILL_OWNER')
        clipped=polygon.intersection(self.masks[cell_id])
        inputparts=parts(clipped,'Polygon'); output=[]; details=[]
        for p in inputparts:
            g,d=self.quantize_polygon(p); output.append(g); details.append(d)
        result=unary_union(output)
        need(result.is_valid and result.difference(self.masks[cell_id]).area==0,'NO_WINNER_COVERAGE_LEAK')
        need(result.difference(polygon).area==0,'NO_INVENTED_OWNER_FILL')
        need(len(parts(result,'Polygon'))==len(inputparts),'NO_POSITIVE_COMPONENT_COLLAPSE')
        return result,dict(inputAreaRootUnits2=polygon.area,exactFloatClipAreaRootUnits2=clipped.area,
            quantizedAreaRootUnits2=result.area,positiveComponents=len(inputparts),quantization=details,
            newFillOutsideWinnerAreaRootUnits2=0,newFillOutsideOwnerAreaRootUnits2=0,
            nativePaintPixelProof=False,exactRationalIntersectionClaim=False)

    def clip_line(self, line, cell_id):
        need(cell_id in self.masks and line.is_valid,'BOUND_VALID_LINE_OWNER')
        pieces=parts(line.intersection(self.masks[cell_id]),'LineString'); output=[]; details=[]
        for piece in pieces:
            old=list(piece.coords)
            choices=[self.candidate_points(p,self.masks[cell_id])for p in old]
            need(math.prod(len(c)for c in choices)<=65536,'BOUNDED_LINE_QUANTIZATION')
            selected=None
            for pts in product(*choices):
                g=LineString(pts)
                if g.length==0 or not g.is_valid: continue
                if g.difference(self.masks[cell_id]).length!=0: continue
                if g.hausdorff_distance(piece)>MAX_VERTEX_ERROR: continue
                selected=g; break
            need(selected is not None,'NO_SAFE_INTEGER_LINE_FRAGMENT')
            output.append(selected); details.append(dict(originalClipPoints=len(old),outputPoints=len(selected.coords),
                maxVertexDisplacementRootUnits=max(math.dist(a,b)for a,b in zip(old,selected.coords)),
                insideWinner=True,originalLineDisplacementBoundRootUnits=MAX_VERTEX_ERROR))
        return output,dict(inputLengthRootUnits=line.length,exactFloatClipLengthRootUnits=sum(p.length for p in pieces),
            outputLengthRootUnits=sum(p.length for p in output),positiveLineFragments=len(pieces),quantization=details,
            lineRasterBoundaryEndpointOwnershipPending=True,complexOrDashedLinePhaseAdmission=False)

def decode_owner(coordinates, indices, record, *, source_tile, coordinate_format):
    need(type(coordinates)is bytes and type(indices)is bytes and len(record)==32,'COMPLETE_NATIVE_OWNER_BYTES')
    need(coordinate_format==2 and tuple(source_tile)==(0,0,0),'GENUINE_L0_ENUM2')
    kind=record[28]; need(kind in (2,3),'ONLY_KIND2_OR_CLOSED4_KIND3')
    n=struct.unpack_from('<I',indices)[0]; need(n>0 and len(indices)==4+4*n,'COMPLETE_INDEX_ARRAY')
    refs=struct.unpack_from('<'+'I'*n,indices,4); elements=[]
    for word in refs:
        at=word&LOW24; need(at+4<=len(coordinates),'ELEMENT_BOUND')
        h=struct.unpack_from('<I',coordinates,at)[0]; count=h&LOW24; flags=h>>24
        need(flags in (0,1) and count>1 and at+4+8*count<=len(coordinates),'U32_XY_ONLY_NO_EXTRA_FLAGS')
        role=word>>24; need(role&3 in (0,1),'BOUND_REVERSE_FLAG')
        points=list(struct.iter_unpack('<II',coordinates[at+4:at+4+8*count]))
        need(all(0<=v<=Q for p in points for v in p),'ROOT_U32_POINTS')
        if role&3==1: points.reverse()
        if kind==3:
            need(role&12==8 and count==4 and points[0]==points[-1] and len(set(points[:-1]))==3 and signed2(points)!=0,'ONLY_AUTHENTIC_CLOSED4_FILL_TRIANGLES')
            need(role&0xf0==0,'NO_MASK_OR_OPAQUE_TRIANGLE_ROLE')
        else: need(role==16,'ONLY_AUTHENTIC_SIMPLE_LINE_ROLE16')
        elements.append(dict(points=points,role=role,rootFlags=flags,originalOffset=at))
    return kind,elements

def clip_native_owner(coordinates, indices, record, *, cell_id, clipper, source_tile=(0,0,0), coordinate_format=2):
    """Return complete standalone coords/index/record + receipt.

    UID/class/Word20/RenderOffset and all remaining StoredFeature bytes stay
    exact; only IndexOffset0 changes. Caller rebases shared streams and retains
    the whole original query/footer. No Z or unknown ring semantics accepted.
    """
    kind,elements=decode_owner(coordinates,indices,record,source_tile=source_tile,coordinate_format=coordinate_format)
    shapes=[Polygon(clipper.local(e['points'])) if kind==3 else LineString(clipper.local(e['points']))for e in elements]
    pieces=[]; retained=0; clipped=0; details=[]
    if kind==3:
        need(len({(e['role'],signed2(e['points'])>0)for e in elements})==1,'UNIFORM_ORIGINAL_TRIANGLE_ROLE_AND_SIGN')
        whole=unary_union(shapes);need(whole.is_valid,'VALID_COMPLETE_FILL_TRIANGLE_UNION')
        if clipper.masks[cell_id].covers(whole):
            pieces=[(e['points'],e['role'])for e in elements];retained=len(elements)
        else:
            clipped=len(elements)
            out,d=clipper.clip_polygon(whole,cell_id);details.append(dict(wholeOriginalOwner=True,**d))
            for polygon in parts(out,'Polygon'):
                tris=list(constrained_delaunay_triangles(polygon).geoms)
                need(tris and unary_union(tris).equals(polygon),'WHOLE_POLYGON_HOLE_SAFE_TESSELLATION')
                for t in tris:
                    pts=clipper.global_points(t.exterior.coords)
                    need(len(pts)==4 and pts[0]==pts[-1] and signed2(pts)!=0,'NONCOLLAPSED_CLOSED4_OUTPUT')
                    if (signed2(pts)>0)!=(signed2(elements[0]['points'])>0):pts=list(reversed(pts))
                    pieces.append((pts,elements[0]['role']))
    for e,g in zip(elements,shapes):
        if kind==3:break
        if clipper.masks[cell_id].covers(g):
            # Carry contained primitives byteexact except Root bit, no rerouting
            # precision loss or unnecessary tessellation/line segmentation.
            pieces.append((e['points'],e['role'])); retained+=1; continue
        clipped+=1
        out,d=clipper.clip_line(g,cell_id)
        pieces += [(clipper.global_points(p.coords),e['role'])for p in out]
        details.append(dict(inputElementOffset=e['originalOffset'],**d))
    co=bytearray(); words=[]
    for pts,role in pieces:
        need(len(co)<=LOW24 and 1<len(pts)<=LOW24,'LOW24_COUNT_AND_START')
        words.append((role<<24)|len(co))
        # Preserve Reverse usage: serialized sequence is reversed back; the
        # genuine native consumer recovers the directed output sequence.
        stored=list(reversed(pts)) if role&3==1 else pts
        co += struct.pack('<I',len(pts)|(1<<24))+b''.join(struct.pack('<II',*p)for p in stored)
    need(len(co)<1<<24,'FULL_COORDINATE_STREAM_LOW24')
    ix=struct.pack('<I',len(words))+struct.pack('<'+'I'*len(words),*words)
    rec=bytearray(record) if words else None
    if rec is not None: struct.pack_into('<I',rec,8,0)
    receipt=dict(policy=POLICY,sourceCell=cell_id,level=clipper.level,sourcePriority=[s.cell_id for s in clipper.priority],
        nativeScaleBySource={s.cell_id:s.native_scale for s in clipper.sources},denominator=str(clipper.denominator),
        kind=kind,inputIndexOccurrences=len(elements),unchangedContainedPrimitives=retained,clippedOrRemovedPrimitives=clipped,
        outputIndexOccurrences=len(words),outputCoordinateBytes=len(co),coordinateSHA256=sha(co),indexSHA256=sha(ix),
        rootFlags=1,originalUIDClassWord20RenderBytesPreserved=True,inputSourceTile=[0,0,0],coordinateFormat=2,
        fullOriginalQueryFooter='CALLER_MUST_RETAIN',kind8AndGeneralRingOwnersPending=True,
        nativeCalls=0,countryBuild=False,nativePaintOrGPUClaim=False,geosVersion=geos_version_string,clipDetails=details)
    return bytes(co),ix,bytes(rec) if rec is not None else None,receipt
