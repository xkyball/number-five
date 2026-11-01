"""Source-only complete spatial contexts, using one shared quantizer per tile.

No merge of old quantized maps; no Native or device calls. Geometry failures
refuse the generation. Original-owner IDs and explicit emitted IDs are separate.
"""
from collections import Counter
from fractions import Fraction
import hashlib
import json
import math
import struct

from shapely.geometry import Point, LineString, MultiLineString, Polygon, MultiPolygon, box
from shapely.geometry.polygon import orient
from shapely.ops import unary_union
from shapely.validation import explain_validity
from . import exact_rectangle_clip as er

GRID = 32769
RANGE_POLICY = 'DIAGNOSTIC_NATIVE_RANGE_0_15_NOT_SCAMIN/1'


def need(ok, why):
    if not ok:
        raise ValueError(why)


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(',', ':'), ensure_ascii=False, allow_nan=False).encode()


def digest(value):
    return hashlib.sha256(canonical(value)).hexdigest()


def parts_for_atom(atom):
    """Already projected Source F64 values from the existing authoring adapter."""
    at = 0
    out = []
    for part in atom['parts']:
        n = len(part)
        out.append(atom['projected'][at:at+n])
        at += n
    need(at == len(atom['projected']), 'ALL_ORDERED_ORIGINAL_PROJECTED_VERTICES')
    return out


def source_geometry(atom):
    if atom['kind'] in (40,44):
        return Point(atom['projected'][0])
    parts = parts_for_atom(atom)
    if atom['kind'] == 32:
        shape = MultiLineString(parts)
    else:
        rings = {m['sourceRingId']:p for m,p in zip(atom['part_metadata'],parts,strict=True)}
        shape = MultiPolygon([Polygon(rings[p['exteriorRingId']],
            [rings[r] for r in p['interiorRingIds']]) for p in atom['polygon_roles']])
    need(not shape.is_empty and shape.is_valid, 'SOURCE_F64_GEOMETRY_INVALID_NO_REPAIR:'+atom['source_id']+':'+explain_validity(shape))
    return shape


def components(shape, kind):
    expected = 'Polygon' if kind == 24 else 'LineString'
    if shape.is_empty:
        return []
    if shape.geom_type == expected:
        return [shape]
    if hasattr(shape,'geoms'):
        return [p for child in shape.geoms for p in components(child,kind)]
    # A zero-dimensional touch is not a lost line/area interior. It is counted
    # by the caller and cannot become a fabricated zero-length native vector.
    return []


def exact_scale(bounds):
    sides = [bounds[axis+2]-bounds[axis] for axis in range(2)]
    need(all(type(v) is int and v > 0 and v & (v-1) == 0 for v in sides), 'POWER_OF_TWO_INTEGER_TILE_SIDES')
    scales=[]
    for side in sides:
        expected=Fraction(side,(GRID-1)<<14)
        value=struct.unpack('<f',struct.pack('<f',float(expected)))[0]
        need(Fraction.from_float(value)==expected,'EXACT_F32_BINARY_TILE_SCALE')
        scales.append(value)
    return scales


def quantize_part(part, bounds, error_cap, *, closed):
    scales=exact_scale(bounds)
    out=[]
    models=[]
    previous=None
    maximum=0.0
    for point in part:
        exact=[v if type(v)is Fraction else Fraction.from_float(float(v))for v in point]
        p=[float(v)for v in exact]
        raw=[]
        for axis in range(2):
            q=(exact[axis]-bounds[axis])*(GRID-1)/(bounds[axis+2]-bounds[axis])
            value=(2*q.numerator+q.denominator)//(2*q.denominator)
            need(0<=value<GRID,'CLIPPED_VERTEX_IN_EXACT_TILE_GRID')
            raw.append(value)
        need(not out or raw!=out[-1] or exact==previous,'DISTINCT_ADJACENT_VERTICES_COLLAPSED_NO_REPAIR')
        model=[bounds[axis]+(raw[axis]<<14)*scales[axis] for axis in range(2)]
        error=math.hypot(*(float(Fraction.from_float(model[axis])-exact[axis]) for axis in range(2)))
        need(error<=error_cap,'EXPLICIT_EUCLIDEAN_F64_ERROR_BUDGET')
        maximum=max(maximum,error)
        out.append(raw);models.append(model);previous=exact
    if closed:
        need(out[0]==out[-1] and len({tuple(p)for p in out[:-1]})>=3,'COMPLETE_NONCOLLAPSED_RING')
    else:
        need(len(out)>=2 and len({tuple(p)for p in out})>=2,'NONCOLLAPSED_COMPLETE_LINE_PART')
    return out,models,maximum


def exact_segment_interval(p0,p1,bounds):
    """Liang-Barsky interval in exact promoted binary64 rational arithmetic."""
    a=[Fraction.from_float(float(v))for v in p0]
    b=[Fraction.from_float(float(v))for v in p1]
    lo,hi=Fraction(0),Fraction(1)
    for axis in range(2):
        delta=b[axis]-a[axis]
        if not delta:
            if not bounds[axis]<=a[axis]<=bounds[axis+2]:return None
            continue
        x=(Fraction(bounds[axis])-a[axis])/delta
        y=(Fraction(bounds[axis+2])-a[axis])/delta
        lo,hi=max(lo,min(x,y)),min(hi,max(x,y))
        if lo>hi:return None
    points=[[a[axis]+t*(b[axis]-a[axis])for axis in range(2)]for t in(lo,hi)]
    return lo,hi,points


def exact_clipped_line_parts(part,bounds):
    pieces=[];current=[];intervals=[]
    for ordinal,(p0,p1)in enumerate(zip(part,part[1:])):
        item=exact_segment_interval(p0,p1,bounds)
        if item is None or item[0]==item[1]:
            if len(current)>=2:pieces.append(current)
            current=[]
            continue
        lo,hi,(a,b)=item
        intervals.append((ordinal,lo,hi))
        if current and current[-1]==a:current.append(b)
        else:
            if len(current)>=2:pieces.append(current)
            current=[a,b]
    if len(current)>=2:pieces.append(current)
    return pieces,intervals


def exact_interval_cover(expected,observed):
    if expected is None or expected[0]==expected[1]:return not observed
    need(observed,'EXACT_SEGMENT_INTERVAL_NOT_DROPPED')
    lo,hi=expected[:2];rows=sorted(set(observed));at=lo
    for a,b in rows:
        need(lo<=a<b<=hi and a<=at,'NO_RATIONAL_SEGMENT_COVERAGE_GAP')
        at=max(at,b)
    return at==hi


def quantize_component(shape, kind, bounds, error_cap):
    if kind==32:
        raw,model,maximum=quantize_part(list(shape.coords),bounds,error_cap,closed=False)
        recreated=LineString(model)
        need(recreated.is_valid and recreated.length>0,'RECONSTRUCTED_LINE_VALID')
        parts=[raw]
        ring_roles=[]
    else:
        # Explicit ring-role normalization, not geometric repair. Each original
        # polygon component is clipped independently; outer/inner roles remain.
        shape=orient(shape,sign=1.0)
        raw_parts=[];model_parts=[];maximum=0.0
        for ring in [shape.exterior,*shape.interiors]:
            raw,model,error=quantize_part(list(ring.coords),bounds,error_cap,closed=True)
            raw_parts.append(raw);model_parts.append(model);maximum=max(maximum,error)
        raw_shape=Polygon(raw_parts[0],raw_parts[1:])
        recreated=Polygon(model_parts[0],model_parts[1:])
        need(raw_shape.is_valid and recreated.is_valid and recreated.area>0,
             'QUANTIZED_AREA_TOPOLOGY_INVALID_NO_REPAIR:'+explain_validity(recreated))
        need(len(raw_shape.interiors)==len(shape.interiors)==len(recreated.interiors),
             'EXACT_CLIPPED_INTERIOR_RING_COUNT')
        parts=raw_parts
        ring_roles=['exterior']+['interior']*len(shape.interiors)
    distance=shape.hausdorff_distance(recreated)
    need(math.isfinite(distance) and distance<=error_cap,'ENTIRE_CLIPPED_GEOMETRY_HAUSDORFF_BUDGET')
    return parts,recreated,dict(maximumVertexErrorSourceUnits=maximum,
        hausdorffErrorSourceUnits=distance,clippedRingRoles=ring_roles,
        topologyValid=True,sourceClippedGeometrySHA256=hashlib.sha256(shape.wkb).hexdigest(),
        modelGeometrySHA256=hashlib.sha256(recreated.wkb).hexdigest(),
        originalClippedVertexCount=sum(len(p)for p in parts),storedVertexCount=sum(len(p)for p in parts),
        clippingEngine='GEOS intersection in projected Source F64; no make_valid/buffer/simplify/snap')


def quantize_exact_polygon(polygon,bounds,error_cap):
    raw_parts=[];models=[];maximum=0.0
    for ring in polygon['rings']:
        raw,model,error=quantize_part(ring,bounds,error_cap,closed=True)
        need((er.area([tuple(v)for v in raw])>0)==(er.area(ring)>0),'EXACT_RING_ROLE_WINDING_PRESERVED')
        raw_parts.append(raw);models.append(model);maximum=max(maximum,error)
    raw_shape=Polygon(raw_parts[0],raw_parts[1:]);model_shape=Polygon(models[0],models[1:])
    need(raw_shape.is_valid and model_shape.is_valid and model_shape.area>0,
         'EXACT_CLIPPED_QUANTIZED_AREA_TOPOLOGY_NO_REPAIR:'+explain_validity(model_shape))
    need(len(model_shape.interiors)==len(polygon['rings'])-1,'ALL_EXACT_CLIPPED_HOLES_RETAINED')
    float_shape=Polygon([[float(v)for v in p]for p in polygon['rings'][0]],
        [[[float(v)for v in p]for p in r]for r in polygon['rings'][1:]])
    measured=float_shape.hausdorff_distance(model_shape)
    need(math.isfinite(measured)and measured<=error_cap,'EXPLICIT_CLIPPED_AREA_F64_MODEL_HAUSDORFF')
    return raw_parts,model_shape,dict(maximumVertexErrorSourceUnits=maximum,
        hausdorffErrorSourceUnits=measured,boundaryDisplacementUpperBoundSourceUnits=maximum,
        edgeErrorProof='Same ordered exact rational vertices; convex Euclidean norm bounds every corresponding segment.',
        clippedRingRoles=['exterior']+['interior']*(len(raw_parts)-1),
        sourceClippedExactArea=er.rational(polygon['area']),topologyValid=True,
        originalClippedVertexCount=sum(len(r)for r in polygon['rings']),
        storedVertexCount=sum(len(r)for r in raw_parts),
        clippingEngine='EXACT_RATIONAL_ORIENTED_BOUNDARY_GRAPH_NO_EPSILON',
        ringOrientationPolicy='Explicit outerCCW/innerCW role normalization; exact geometry unchanged.')


def interval_union(rows):
    out=[]
    for a,b in sorted(set(rows)):
        if out and a<=out[-1][1]:out[-1]=(out[-1][0],max(out[-1][1],b))
        else:out.append((a,b))
    return out


def world_contact_key(shape):
    """Geometric boundary equality, not a vertex-count/segmentation heuristic."""
    return shape.normalize().wkb.hex()


def build_complete_tiles(atoms, *, region, tile_side, error_cap, range_policy,
                         header_flags, date_text, maximum_maps=256):
    need(range_policy==RANGE_POLICY,'EXPLICIT_DIAGNOSTIC_NATIVE_RANGE_POLICY')
    need(type(tile_side)is int and 1<=tile_side<=4096 and tile_side&(tile_side-1)==0,'FINITE_POWER_OF_TWO_TILE_SIDE')
    need(type(region)is list and len(region)==4 and all(type(v)is int for v in region)and
         region[0]<region[2]and region[1]<region[3],'EXACT_INTEGER_REGION')
    need(type(error_cap)in(int,float)and math.isfinite(error_cap)and 0<error_cap<=2,'EXPLICIT_UNCHANGED_MAXIMUM_ERROR_CAP')
    need(type(maximum_maps)is int and maximum_maps>0,'EXPLICIT_COMPLETE_TILE_WORK_BUDGET')
    x0,x1=region[0]//tile_side,(region[2]-1)//tile_side+1
    y0,y1=region[1]//tile_side,(region[3]-1)//tile_side+1
    # Refuse oversized regions before creating ranges, keys or geometries.
    need(1<=(x1-x0)*(y1-y0)<=maximum_maps,'EXPLICIT_COMPLETE_TILE_WORK_BUDGET')
    xs=range(x0,x1)
    ys=range(y0,y1)
    tile_keys=[(x,y)for y in ys for x in xs]
    need(1<=len(tile_keys)<=maximum_maps,'EXPLICIT_COMPLETE_TILE_WORK_BUDGET')
    coverage=[min(xs)*tile_side,min(ys)*tile_side,(max(xs)+1)*tile_side,(max(ys)+1)*tile_side]
    global_clip=box(*coverage)
    geometries=[source_geometry(a)for a in atoms]
    maps=[];ledger=[];models={};pieces={};line_intervals={};area_accum={};point_assignments=Counter();zero_dimension=0
    maximum=0.0
    for tx,ty in tile_keys:
        bounds=[tx*tile_side,ty*tile_side,(tx+1)*tile_side,(ty+1)*tile_side]
        exact_scale(bounds)
        tile=box(*bounds);layers=[];previous=None;tile_ordinal=len(maps)
        for atom,source in zip(atoms,geometries,strict=True):
            sid=atom['source_id'];kind=atom['kind']
            if kind in(40,44):
                x,y=atom['projected'][0]
                # Half-open grid ownership; exact outer maximum belongs to the
                # final tile. Each source point/sounding is emitted once.
                xx=min(math.floor(x/tile_side),max(xs));yy=min(math.floor(y/tile_side),max(ys))
                if (xx,yy)!=(tx,ty)or not global_clip.covers(source):
                    continue
                feature=dict(kind=kind,source_id=sid,xy=[x,y])
                if kind==44:feature.update(mantissa=atom['depth']['mantissa'],decimal_scale=atom['depth']['decimal_scale'])
                else:feature['class_code']=atom['native_class']
                info=dict(sourceAtomId=sid,originalOwnerSourceId=atom['parent']['sourceId'],
                    kind=kind,sourceSampleOrdinal=atom['sample'],clipping=False,
                    absolutePointI32Unchanged=True,sourceAttributeRowsSHA256=digest(atom['attributes']))
                emitted=[(feature,None,info)];point_assignments[sid]+=1
            elif kind==32:
                emitted=[]
                for source_component_ordinal,part in enumerate(parts_for_atom(atom)):
                    exact_parts,intervals=exact_clipped_line_parts(part,bounds)
                    for segment_ordinal,lo,hi in intervals:
                        line_intervals.setdefault((sid,source_component_ordinal,segment_ordinal),[]).append((lo,hi))
                    for component_ordinal,part_points in enumerate(exact_parts):
                        raw,model_points,maximum_error=quantize_part(part_points,bounds,error_cap,closed=False)
                        model=LineString(model_points)
                        need(model.is_valid and model.length>0,'EXACT_CLIPPED_MODEL_LINE_VALID')
                        emitted_id=sid+'#t'+str(tx)+'_'+str(ty)+'#g'+str(source_component_ordinal)+'_'+str(component_ordinal)
                        need(len(emitted_id)<=128,'BOUNDED_EXPLICIT_EMITTED_FRAGMENT_ID')
                        feature=dict(kind=kind,source_id=emitted_id,class_code=atom['native_class'],parts=[raw])
                        info=dict(sourceAtomId=sid,originalOwnerSourceId=atom['parent']['sourceId'],
                            sourceComponentOrdinal=source_component_ordinal,clippedComponentOrdinal=component_ordinal,
                            kind=kind,sourceAttributeRowsSHA256=digest(atom['attributes']),
                            maximumVertexErrorSourceUnits=maximum_error,hausdorffErrorSourceUnits=maximum_error,
                            edgeErrorProof='Corresponding exact rational segment endpoints, convex Euclidean norm bound.',
                            clippingEngine='EXACT_RATIONAL_LIANG_BARSKY_NO_EPSILON',
                            originalClippedVertexCount=len(part_points),storedVertexCount=len(raw),topologyValid=True)
                        emitted.append((feature,model,info));maximum=max(maximum,maximum_error)
            else:
                if not source.intersects(tile):continue
                emitted=[]
                parts=parts_for_atom(atom)
                rings={m['sourceRingId']:p for m,p in zip(atom['part_metadata'],parts,strict=True)}
                for source_component_ordinal,definition in enumerate(atom['polygon_roles']):
                    original_rings=[rings[definition['exteriorRingId']],*[rings[r]for r in definition['interiorRingIds']]]
                    clipped=er.clip_polygon(original_rings,bounds)
                    accum=area_accum.setdefault((sid,source_component_ordinal),dict(area=Fraction(0),intervals={},rectangleEdges=[]))
                    accum['area']+=clipped['area'];accum['rectangleEdges'].extend(clipped['rectangleEdges'])
                    for ri,si,lo,hi in clipped['sourceIntervals']:
                        accum['intervals'].setdefault((ri,si),[]).append((lo,hi))
                    for component_ordinal,component in enumerate(clipped['polygons']):
                        raw,model,info=quantize_exact_polygon(component,bounds,error_cap)
                        emitted_id=sid+'#t'+str(tx)+'_'+str(ty)+'#g'+str(source_component_ordinal)+'_'+str(component_ordinal)
                        need(len(emitted_id)<=128,'BOUNDED_EXPLICIT_EMITTED_FRAGMENT_ID')
                        feature=dict(kind=kind,source_id=emitted_id,class_code=atom['native_class'],parts=raw)
                        info.update(sourceAtomId=sid,originalOwnerSourceId=atom['parent']['sourceId'],
                            sourceComponentOrdinal=source_component_ordinal,clippedComponentOrdinal=component_ordinal,
                            kind=kind,sourceAttributeRowsSHA256=digest(atom['attributes']))
                        emitted.append((feature,model,info))
                        maximum=max(maximum,info['hausdorffErrorSourceUnits'],info['maximumVertexErrorSourceUnits'])
            for feature,model,info in emitted:
                feature['attributes']=[wire for _,wire in atom['attributes']]
                feature['native_level_range']=dict(min_level=0,max_level=15)
                key=(atom['layer'],atom['native_class'],tuple(w['native_code_u16']for w in feature['attributes']))
                if key!=previous:
                    layers.append(dict(layer_id=atom['layer'],layer_range=[0,100000],features=[]));previous=key
                li=len(layers)-1;fi=len(layers[-1]['features']);layers[-1]['features'].append(feature)
                ledger.append(dict(info,mapOrdinal=tile_ordinal,tileXY=[tx,ty],layerOrdinal=li,
                    featureOrdinal=fi,emittedId=feature['source_id'],featureValueSHA256=digest(feature)))
                if model is not None:models.setdefault((tile_ordinal,info['sourceAtomId']),[]).append(model)
        need(layers,'EMPTY_COMPLETE_TILE_NEEDS_EXPLICIT_NO_DATA_POLICY')
        maps.append(dict(format='no5-own-plain-generic-attribute-batch/1',header=dict(flags=header_flags,
            raw_bounds=bounds,raw_grid=[GRID,GRID],raw_levels=[8,20],trailing_value=0),
            layers=layers,date_text_1=date_text,date_text_2=date_text))
    coverage_checks=[]
    for atom,shape in zip(atoms,geometries,strict=True):
        sid=atom['source_id'];kind=atom['kind']
        if kind in(40,44):
            required=int(global_clip.covers(shape));need(point_assignments[sid]==required,'EACH_POINT_SOUNDING_OCCURRENCE_EXACTLY_ONCE')
            continue
        if kind==32:
            segment_count=0
            for pi,part in enumerate(parts_for_atom(atom)):
                for si,(p0,p1)in enumerate(zip(part,part[1:])):
                    need(exact_interval_cover(exact_segment_interval(p0,p1,coverage),
                         line_intervals.get((sid,pi,si),[])),'EXACT_RATIONAL_SOURCE_SEGMENT_PARTITION:'+sid)
                    segment_count+=1
            coverage_checks.append(dict(sourceAtomId=sid,originalOwnerSourceId=atom['parent']['sourceId'],
                exactRationalSegmentIntervalPartitionEqual=True,sourceSegmentCount=segment_count,
                fragmentCount=sum(r['sourceAtomId']==sid for r in ledger),toleranceOrEpsilonUsed=False))
            continue
        parts=parts_for_atom(atom);rings={m['sourceRingId']:p for m,p in zip(atom['part_metadata'],parts,strict=True)}
        exact_area=Fraction(0);source_interval_records=0
        for pi,definition in enumerate(atom['polygon_roles']):
            expected=er.clip_polygon([rings[definition['exteriorRingId']],*[rings[r]for r in definition['interiorRingIds']]],coverage)
            observed=area_accum.get((sid,pi),dict(area=Fraction(0),intervals={},rectangleEdges=[]))
            need(expected['area']==observed['area'],'EXACT_RATIONAL_POLYGON_AREA_PARTITION:'+sid)
            expected_intervals={}
            for ri,si,lo,hi in expected['sourceIntervals']:expected_intervals.setdefault((ri,si),[]).append((lo,hi))
            need({k:interval_union(v)for k,v in expected_intervals.items()}==
                 {k:interval_union(v)for k,v in observed['intervals'].items()},'EXACT_RATIONAL_SOURCE_BOUNDARY_INTERVAL_PARTITION:'+sid)
            need(er.normalize_axis_edges(expected['rectangleEdges'])==er.normalize_axis_edges(observed['rectangleEdges']),
                 'EXACT_INTERNAL_RECTANGLE_EDGE_CANCELLATION:'+sid)
            exact_area+=expected['area'];source_interval_records+=sum(map(len,expected_intervals.values()))
        coverage_checks.append(dict(sourceAtomId=sid,originalOwnerSourceId=atom['parent']['sourceId'],
            exactRationalPolygonAreaAndBoundaryPartitionEqual=True,exactClippedArea=er.rational(exact_area),
            sourceBoundaryIntervalRecords=source_interval_records,allInternalRectangleEdgesCancelExactly=True,
            fragmentCount=sum(r['sourceAtomId']==sid for r in ledger),toleranceOrEpsilonUsed=False))
    for values in models.values():
        if values and all(v.geom_type=='Polygon'for v in values):
            need(MultiPolygon(values).is_valid and len(components(unary_union(values),24))==len(values),
                 'DISTINCT_POLYGON_COMPONENTS_NOT_MERGED_OR_OVERLAPPED_BY_QUANTIZATION')
    seams=[]
    tile_index={tuple(row['tileXY']):row['mapOrdinal']for row in ledger}
    for tx,ty in tile_keys:
        for dx,dy in((1,0),(0,1)):
            if (tx+dx,ty+dy)not in tile_index:continue
            left=tile_index[(tx,ty)];right=tile_index[(tx+dx,ty+dy)]
            if dx:edge=LineString([((tx+1)*tile_side,ty*tile_side),((tx+1)*tile_side,(ty+1)*tile_side)])
            else:edge=LineString([(tx*tile_side,(ty+1)*tile_side),((tx+1)*tile_side,(ty+1)*tile_side)])
            owners={sid for mi,sid in models if mi in(left,right)}
            for sid in sorted(owners):
                a=unary_union(models.get((left,sid),[])).intersection(edge)
                b=unary_union(models.get((right,sid),[])).intersection(edge)
                # A source line/area lying exactly on the boundary can have a
                # legitimate one-sided interior. Require its shared contacts
                # only when both source clips have a matching-dimensional body.
                if (left,sid)not in models or(right,sid)not in models:continue
                need(a.equals(b),'EXACT_QUANTIZED_SHARED_EDGE_CONTACT:'+sid)
                seams.append(dict(leftMap=left,rightMap=right,sourceAtomId=sid,
                    exactGeometryContactEqual=True,leftNormalizedWKB=world_contact_key(a),
                    rightNormalizedWKB=world_contact_key(b)))
    need(len(ledger)==len({row['emittedId']for row in ledger}),'ALL_EXPLICIT_FRAGMENT_AND_SOUNDING_IDS_UNIQUE')
    return dict(document=dict(format='no5-own-plain-atlas/1',maps=maps),featureLedger=ledger,
        partitionCoverage=coverage_checks,seams=seams,coverageBounds=coverage,
        settings=dict(tileSideSourceUnits=tile_side,rawGrid=GRID,exactStep=[tile_side,GRID-1],
            maximumErrorSourceUnits=error_cap,rangePolicy=range_policy),
        maximumMeasuredErrorSourceUnits=maximum,zeroDimensionalTouchesNotNativeVectors=zero_dimension,
        mapCount=len(maps),emittedFeatures=len(ledger),sourceAtomCount=len(atoms),
        nativeQualification=False,SCAMINParity=False,Original=0,Native=0,SSH=0)
