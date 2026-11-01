"""Typed cache-IR geometry for the portable country writer.

No polygon repair, new triangulation, nautical ring inference, coverage bounding
box substitution, datum conversion, or executable input configuration occurs.
The complete original IR remains the query/provenance authority.
"""
from __future__ import annotations

from collections import Counter
from fractions import Fraction
from pathlib import Path
import hashlib
import json
import math
import os
import struct

from shapely.geometry import GeometryCollection, LineString, Polygon
from shapely.ops import polygonize, unary_union

from ..rx5.wire import FEATURE

Q = 2147483649
MAX_LATITUDE = math.degrees(math.atan(math.sinh(math.pi)))
DEPTH_POLICY = 'exact-represented-float64-times10-nearest-ties-even-signed-int32/1'
PROJECTION_POLICY = 'spherical-mercator-root-q2147483649-nearest-positive-half-up/1'


class GeometryRefusal(ValueError):
    """An explicit unrepresentable or unbound source-geometry contract."""

    def __init__(self, code, *, source_id=None, detail=None):
        self.code, self.source_id, self.detail = code, source_id, detail
        super().__init__(code + (':' + str(source_id) if source_id else '') +
                         (':' + str(detail) if detail else ''))


def _need(value, code, **details):
    if not value:
        raise GeometryRefusal(code, **details)


def _finite(value):
    return type(value) in (int, float) and math.isfinite(value)


def project_root(position):
    """Existing own RX5 WM codec: no clipping of poles or longitude wrapping.

    +180 and -180 retain their distinct world-edge coordinates. Connected paths
    crossing the antimeridian are refused by project_path, rather than drawing a
    nearly world-wide chord. This first adapter does not invent seam vertices.
    """
    _need(isinstance(position, (list, tuple)) and len(position) == 2 and
          all(_finite(value) for value in position), 'FINITE_LONGITUDE_LATITUDE_REQUIRED')
    longitude, latitude = position
    _need(-180 <= longitude <= 180 and -MAX_LATITUDE <= latitude <= MAX_LATITUDE,
          'POSITION_OUTSIDE_SUPPORTED_MERCATOR_ROOT')
    normalized = ((longitude + 180.0) / 360.0,
                  (1.0 - math.asinh(math.tan(math.radians(latitude))) / math.pi) / 2.0)
    # The mathematically exact Mercator boundary can acquire a final arithmetic
    # epsilon. Accept only the two explicitly represented boundary latitudes.
    y = 0.0 if latitude == MAX_LATITUDE else 1.0 if latitude == -MAX_LATITUDE else normalized[1]
    return tuple(math.floor(value * Q + 0.5) for value in (normalized[0], y))


def project_path(positions, *, source_id=None, closed=False):
    _need(isinstance(positions, (list, tuple)) and len(positions) >= (4 if closed else 2),
          'COMPLETE_CLOSED_RING' if closed else 'COMPLETE_LINE_PART', source_id=source_id)
    projected = [project_root(point) for point in positions]
    _need(not any(abs(a[0] - b[0]) > 180 for a, b in zip(positions, positions[1:])),
          'ANTIMERIDIAN_PATH_REQUIRES_EXPLICIT_SEAM_SPLIT', source_id=source_id)
    if closed:
        _need(tuple(positions[0]) == tuple(positions[-1]), 'SOURCE_RING_NOT_CLOSED', source_id=source_id)
        _need(projected[0] == projected[-1] and len(set(projected[:-1])) >= 3,
              'RING_COLLAPSES_UNDER_ROOT_QUANTIZATION', source_id=source_id)
    else:
        _need(len(set(projected)) >= 2, 'LINE_COLLAPSES_UNDER_ROOT_QUANTIZATION', source_id=source_id)
    return projected


def encode_depth_metres(value):
    """Reuse the exact own signed depth codec; metre semantics are caller data.

    Quantizes the exact represented binary value, not an intermediate value*10.
    The IR original scalar, unit declaration and datum remain in owner receipts.
    """
    _need(_finite(value), 'FINITE_DEPTH_VALUE_REQUIRED')
    numerator, denominator = float(value).as_integer_ratio()
    quotient, remainder = divmod(abs(numerator) * 10, denominator)
    if 2 * remainder > denominator or (2 * remainder == denominator and quotient & 1):
        quotient += 1
    result = -quotient if numerator < 0 else quotient
    _need(-(1 << 31) <= result < 1 << 31, 'SOUNDING_DEPTH_INT32_RANGE')
    return result


def _element(points, sounding=False):
    _need(0 < len(points) < 1 << 24, 'COORDINATE_ELEMENT_COUNT_LOW24')
    header = struct.pack('<I', 0x01000000 | len(points))
    return header + b''.join(struct.pack('<IIi' if sounding else '<II', *p) for p in points)


def _twice_area(points):
    return sum(a[0]*b[1] - b[0]*a[1] for a, b in zip(points, points[1:]))


def _polygon(points, source_id):
    polygon = Polygon(points)
    _need(polygon.is_valid and polygon.area > 0, 'INVALID_SOURCE_OR_QUANTIZED_RING_NO_REPAIR', source_id=source_id)
    return polygon


def normalize_coverage_ring(positions, *, source_id=None):
    """Project source coverage; node self-intersecting linework by even/odd.

    No tolerance, buffer, snapping, bounds substitution or inferred nautical
    outline is involved. The narrow normalization accepts only valid, hole-free
    faces with exact integer Root vertices; original coordinates stay in IR.
    """
    root=project_path(positions,source_id=source_id,closed=True)
    shape=Polygon(root)
    if shape.is_valid and shape.area>0:
        return [root],None

    def odd_inside(point):
        x,y=map(Fraction,point);odd=False
        for a,b in zip(root,root[1:]):
            if (a[1]>y)!=(b[1]>y):
                crossing=Fraction(a[0])+Fraction(b[0]-a[0])*(y-a[1])/Fraction(b[1]-a[1])
                if x<crossing:odd=not odd
        return odd

    faces=list(polygonize(unary_union(LineString(root))))
    selected=[face for face in faces if odd_inside(tuple(face.representative_point().coords)[0])]
    _need(selected and all(face.is_valid and face.area>0 and not face.interiors for face in selected),
          'COVERAGE_EVEN_ODD_EMPTY_OR_UNSUPPORTED_NESTED_FACES',source_id=source_id)
    output=[]
    for face in selected:
        coordinates=list(face.exterior.coords)
        _need(all(float(v).is_integer()for point in coordinates for v in point),
              'COVERAGE_INTERSECTION_REQUIRES_EXPLICIT_SUBGRID_POLICY',source_id=source_id)
        output.append([tuple(map(int,point))for point in coordinates])
    event={'policy':'source-coverage-root-linework-exact-integer-node-polygonize-even-odd/1',
        'sourceId':source_id,'sourceRootRing':root,'sourcePointCount':len(root),
        'outputRootRings':output,'sourceLineworkFaces':len(faces),'selectedOddFaces':len(selected),
        'normalizedFilledAreaRootUnitsSquared':sum(face.area for face in selected),
        'sourceRootSignedTwiceAreaExact':_twice_area(root),
        'nativeCoverageRoleUnchanged':True,'allNodedCoordinatesExactlyOnRootGrid':True,
        'originalSourceCoordinatesRetainedInIR':True,'tolerance':None,
        'bufferOrSnappingOrBoundsRepair':False,'emptyFillAccepted':False}
    return output,event


def expand_source_triangles(primitives, *, source_id=None):
    """Expand only the explicit GL_TRIANGLES/STRIP/FAN source vertex lists.

    Strip parity is retained; exact source nulls are recorded, never repaired.
    No tessellation or outline reconstruction from triangle edges is used.
    """
    result, nulls = [], []
    for ordinal, primitive in enumerate(primitives):
        mode, points = primitive['mode'], primitive['coordinates']
        _need(mode in (4, 5, 6) and len(points) >= 3 and
              (mode != 4 or len(points) % 3 == 0), 'SOURCE_TRIANGLE_MODE_OR_COUNT', source_id=source_id)
        if mode == 4:
            triples = [(i, i+1, i+2) for i in range(0, len(points), 3)]
        elif mode == 5:
            triples = [(i+1, i, i+2) if i & 1 else (i, i+1, i+2) for i in range(len(points)-2)]
        else:
            triples = [(0, i, i+1) for i in range(1, len(points)-1)]
        for triangle_ordinal, indices in enumerate(triples):
            original = [points[i] for i in indices]
            if len(set(map(tuple, original))) < 3:
                nulls.append({'sourcePrimitiveOrdinal':ordinal, 'sourceTriangleOrdinal':triangle_ordinal,
                              'sourceVertexOrdinals':list(indices), 'reason':'SOURCE_REPEATED_VERTEX_NULL_TRIANGLE'})
                continue
            original_sm = primitive.get('coordinatesSm', points)
            original_xy = [[Fraction(v) for v in original_sm[i]] for i in indices]
            source_area = _twice_area(original_xy + [original_xy[0]])
            if source_area == 0:
                # GL strip/fan connector triangles with exact zero source-SM
                # area have no fill. Inverse-WM arithmetic and Root rounding
                # must not manufacture a positive area for these connectors.
                nulls.append({'sourcePrimitiveOrdinal':ordinal, 'sourceTriangleOrdinal':triangle_ordinal,
                              'sourceVertexOrdinals':list(indices), 'reason':'SOURCE_COLLINEAR_NULL_TRIANGLE',
                              'sourceTwiceAreaExact':[0,1]})
                continue
            closed=original+[original[0]]
            _need(not any(abs(a[0]-b[0])>180 for a,b in zip(closed,closed[1:])),
                  'ANTIMERIDIAN_PATH_REQUIRES_EXPLICIT_SEAM_SPLIT',source_id=source_id)
            root=[project_root(point)for point in closed]
            area = _twice_area(root)
            event=None
            if area == 0 or (area > 0) != (source_area < 0):
                event={'policy':'declared-root-u32-quantization-triangle-events/1',
                    'reason':'ROOT_U32_QUANTIZATION_NULL_TRIANGLE'if area==0 else'ROOT_U32_ORIENTATION_CHANGE_RETAIN_ORIGINAL_VERTEX_ORDER',
                    'sourceTwiceAreaExact':[source_area.numerator,source_area.denominator],
                    'rootTwiceAreaExact':area,'rootPoints':root,
                    'perAxisQuantizationBoundRootUnits':0.5,
                    'boundScope':'Declared rounding of computed Float64 WM coordinates, excluding source/fromSM precision and projection arithmetic',
                    'sourceOriginalBytesRetainedInIR':True,'orientationUsedAsHoleRole':False,
                    'newVerticesOrRepair':False}
            if area==0:
                # A MASK fill element whose quantized closed triangle has zero
                # area contributes no filled set. Its complete source binding
                # and projected vertices remain explicit in the receipt.
                nulls.append({'sourcePrimitiveOrdinal':ordinal,'sourceTriangleOrdinal':triangle_ordinal,
                    'sourceVertexOrdinals':list(indices),**event})
                continue
            result.append({'sourcePrimitiveOrdinal':ordinal, 'sourceTriangleOrdinal':triangle_ordinal,
                           'sourceVertexOrdinals':list(indices), 'root':root,
                           **({'quantizationEvent':event}if event else{})})
    _need(result, 'NO_POSITIVE_SOURCE_TRIANGLE_FILL', source_id=source_id)
    return result, nulls


def _source_scamin(feature):
    # These are original values, not an interpretation of RX5 word20.
    return [row['value'] for row in feature['attributes'] if row.get('code') == 133]


def prepare_feature_geometry(feature, *, programmes=(), target_kind=None, area_source_triangles=False):
    """Prepare one typed owner without metadata/render offsets.

    Area boundary commands require exact directed-topology rings. A fill-only
    source may retain the actual triangle mask. A topology ring is used for fill
    only if its quantized filled set exactly equals the explicit triangle fill;
    a disagreement is a concrete refusal, never hidden via tolerance/repair.
    """
    geometry, source_id = feature['geometry'], feature['sourceId']
    source_kind = geometry['kind']
    inferred = {'point':1, 'multipoint':4, 'line':2, 'area':3}.get(source_kind)
    _need(inferred is not None, 'UNSUPPORTED_IR_GEOMETRY_KIND', source_id=source_id)
    _need(target_kind in (None, inferred), 'UNBOUND_KIND8_OR_GEOMETRY_KIND_MUTATION', source_id=source_id)
    elements, receipts, fill = [], {}, GeometryCollection()
    texts = [v.decode('ascii') if isinstance(v, bytes) else str(v) for v in programmes]
    from .presentation import split_commands
    tokens = [token for text in texts for token in split_commands(text)]
    needs_boundary = any(token.startswith(('LS(', 'LC(')) for token in tokens)
    fill_only = area_source_triangles or bool(tokens) and all(token.startswith(('AC(', 'AP(')) for token in tokens)
    if source_kind == 'point':
        points = [project_root(geometry['position'])]
        elements.append((0, _element(points), points))
        profile = 'ROOT1_ORIGINAL_SINGLE_POINT'
    elif source_kind == 'multipoint':
        _need(feature.get('classAcronym')=='SOUNDG', 'GENUINE_SOUNDG_CLASS_FOR_TYPED_XYZ_REQUIRED', source_id=source_id)
        _need(geometry['points'], 'EMPTY_SOURCE_SOUNDING_OWNER', source_id=source_id)
        points, depths = [], []
        for ordinal, point in enumerate(geometry['points']):
            depth = point['depth']
            unit = depth.get('unit', {})
            _need(depth.get('meaning') == 'depth' and unit.get('value') == 'm' and
                  unit.get('state') in ('verified', 'inferred'), 'SOUNDING_METRE_SEMANTICS_REQUIRED', source_id=source_id)
            value = depth['value']; dcm = encode_depth_metres(value)
            points.append((*project_root(point['position']), dcm))
            depths.append({'sourceOrdinal':point.get('ordinal', ordinal), 'sourceValue':value,
                           'sourceValueExactRatio':list(float(value).as_integer_ratio()), 'depthDcm':dcm,
                           'unit':unit, 'datum':depth.get('datum'),
                           'absoluteErrorMetresExact':[abs(Fraction(dcm,10)-Fraction.from_float(float(value))).numerator,
                                                       abs(Fraction(dcm,10)-Fraction.from_float(float(value))).denominator]})
        elements.append((0, _element(points, True), points))
        receipts.update(depthPolicy=DEPTH_POLICY, depthRows=depths, datumConversion=False)
        profile = 'ROOT1_SIGNED_INT32_SOUNDING_XYZ'
    elif source_kind == 'line':
        _need(geometry.get('reconstruction', {}).get('status') == 'exact_topology' and
              not geometry['reconstruction'].get('issues'), 'UNRESOLVED_SOURCE_LINE_TOPOLOGY', source_id=source_id)
        _need(geometry['parts'], 'EMPTY_SOURCE_LINE_OWNER', source_id=source_id)
        for part in geometry['parts']:
            points = project_path(part['coordinates'], source_id=source_id)
            elements.append((16, _element(points), points))
        profile = 'SIMPLE_ROLE16_LINE'
    else:
        primitives = geometry.get('trianglePrimitives', [])
        triangles, nulls = expand_source_triangles(primitives, source_id=source_id) if primitives else ([],[])
        has_fill_command = any(token.startswith(('AC(', 'AP(')) for token in tokens)
        _need(triangles or not has_fill_command, 'FILL_COMMAND_REQUIRES_POSITIVE_EXPLICIT_SOURCE_TRIANGLES', source_id=source_id)
        triangle_fill = unary_union([_polygon(row['root'], source_id) for row in triangles])
        receipts.update(sourceTriangles=[{k:v for k,v in row.items() if k!='root'} for row in triangles],
                        sourceNullTriangles=nulls, sourceTriangleFillWKBSHA256=hashlib.sha256(triangle_fill.wkb).hexdigest(),
                        originalPgroupRingsAvailable=False)
        if fill_only:
            # All triangle sides remain MASK edges (usage8), never harbour or
            # contour lines. The union is used only as actual fill availability.
            for row in triangles:
                elements.append((8, _element(row['root']), row['root']))
            fill = triangle_fill
            profile = 'USAGE8_CLOSED4_FILL_TRIANGLES'
            receipts['areaPolicy'] = 'EXPLICIT_SOURCE_TRIANGLE_MASK_FILL_ONLY_NO_BOUNDARY_PROMOTION'
        else:
            _need(geometry.get('reconstruction', {}).get('status') == 'exact_topology' and
                  not geometry['reconstruction'].get('issues') and geometry.get('polygons'),
                  'BOUNDARY_OR_CENTROID_REQUIRES_EXACT_SOURCE_TOPOLOGY', source_id=source_id)
            by_id = {ring['id']:ring for ring in geometry['rings']}
            root_rings = {}
            for ring_id, ring in by_id.items():
                _need(ring['role'] in ('exterior','interior') and ring.get('derivation') == 'topology',
                      'EXPLICIT_TOPOLOGY_RING_ROLE_REQUIRED', source_id=source_id)
                root_rings[ring_id] = project_path(ring['coordinates'], source_id=source_id, closed=True)
            polygons = []
            for polygon in geometry['polygons']:
                outer = root_rings[polygon['exteriorRingId']]
                holes = [root_rings[r] for r in polygon['interiorRingIds']]
                value = Polygon(outer, holes)
                _need(value.is_valid and value.area > 0, 'QUANTIZED_TOPOLOGY_POLYGON_INVALID_NO_REPAIR', source_id=source_id)
                polygons.append(value)
            ring_fill = unary_union(polygons)
            # LS-only still preserves actual mask fill and excludes artificial
            # triangle boundaries. Different filled sets need a separate Kind8
            # ownership contract, which this V15 writer cannot silently infer.
            _need(not triangles or ring_fill.equals(triangle_fill), 'SOURCE_TRIANGLE_BOUNDARY_FILL_MISMATCH_REQUIRES_KIND8_CONTRACT', source_id=source_id)
            used = set()
            for polygon in geometry['polygons']:
                for ring_id, usage in [(polygon['exteriorRingId'],24)] + [(r,20)for r in polygon['interiorRingIds']]:
                    _need(ring_id not in used, 'TOPOLOGY_RING_REFERENCED_TWICE', source_id=source_id)
                    used.add(ring_id); points = root_rings[ring_id]
                    elements.append((usage, _element(points), points))
            _need(used == set(root_rings), 'ALL_TOPOLOGY_RINGS_ACCOUNTED_FOR', source_id=source_id)
            fill = triangle_fill
            profile = 'CLOSED_EXTERIOR24_INTERIOR20_RINGS'
            receipts.update(areaPolicy='EXACT_TOPOLOGY_RING_BOUNDARY_WITH_CERTIFIED_EQUAL_SOURCE_TRIANGLE_FILL',
                            nativeOriginalPgroupRingClaim=False, boundaryCommandsPresent=needs_boundary)
    co, refs = bytearray(), []
    for usage, raw, points in elements:
        _need(len(co) < 1 << 24 and len(co)+len(raw) < 1 << 24, 'PREPARED_OWNER_COORDINATE_LOW24', source_id=source_id)
        refs.append((usage << 24) | len(co)); co.extend(raw)
    points = [point for _,_,part in elements for point in part]
    bounds = [min(p[0]for p in points),min(p[1]for p in points),max(p[0]for p in points),max(p[1]for p in points)]
    ix = struct.pack('<I',len(refs)) + b''.join(struct.pack('<I',word)for word in refs)
    return {'kind':inferred, 'coordinates':bytes(co), 'indices':ix,
            'wholeOwnerRootBBox':bounds, 'geometryProfile':profile, 'fillGeometry':fill,
            'indexOccurrences':len(refs), 'pointOccurrences':len(points),
            'sourceSCAMINValues':_source_scamin(feature),
            'receipt':{'projection':PROJECTION_POLICY,'rootFlag':1,'sourceId':source_id,
                       'sourceGeometryKind':source_kind,'indexHigh8Roles':[r>>24 for r in refs],
                       'triangulationInvented':False,'geometryRepair':False,
                       'coordinateQuantization':{'method':PROJECTION_POLICY,'maximumPerAxisRootUnits':0.5,
                           'reference':'Computed Float64 projected coordinates; excludes projection arithmetic and source fromSM uncertainty',
                           'measuresSurveyAccuracy':False,'originalPreCacheF16PrecisionRecovered':False},**receipts}}


def _pin(path):
    path = Path(path)
    raw = path.read_bytes()
    return {'path':str(path.resolve()), 'bytes':len(raw), 'sha256':hashlib.sha256(raw).hexdigest()}


def _write(directory, name, raw):
    path = Path(directory) / name
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    with path.open('xb') as handle:
        os.chmod(path, 0o600)
        handle.write(raw)
        handle.flush()
        os.fsync(handle.fileno())
    return _pin(path)


def _json(directory, name, value):
    return _write(directory, name, (json.dumps(value, indent=2, sort_keys=True, ensure_ascii=False, allow_nan=False)+'\n').encode())


def _programmes(render, offset):
    _need(type(offset) is int and 0 <= offset <= len(render)-6, 'PRESENTATION_RENDER_OFFSET')
    count, additional = struct.unpack_from('<HI', render, offset)
    position, programs = offset+6, {}
    for _ in range(count):
        _need(position+6 <= len(render), 'PRESENTATION_COMPLETE_FRAME')
        key, size = struct.unpack_from('<IH', render, position); position += 6
        _need(key not in programs and position+size <= len(render), 'PRESENTATION_COMPLETE_UNIQUE_CONTEXT')
        programs[key] = render[position:position+size]; position += size
    return programs, additional


def _value(mapping, key):
    return mapping[key] if key in mapping else mapping[str(key)]


def _append_prepared(streams, prepared, record):
    """Relocate complete element/indices only; high8 roles remain unchanged."""
    fields = list(FEATURE.unpack(record))
    coordinates, indices = prepared['coordinates'], prepared['indices']
    base = len(streams['coordinates'])
    _need(base+len(coordinates) < 1 << 24, 'PER_CELL_PREPARED_COORDINATE_ARENA_LOW24')
    count = struct.unpack_from('<I', indices)[0]
    words = []
    for word, in struct.iter_unpack('<I',indices[4:]):
        offset = (word & 0xffffff)+base
        _need(offset < 1 << 24, 'PREPARED_ELEMENT_OFFSET_LOW24')
        words.append((word & 0xff000000) | offset)
    fields[1] = len(streams['indices'])
    ordinal = len(streams['features']) // 32
    streams['coordinates'].extend(coordinates)
    streams['indices'].extend(struct.pack('<I',count)+b''.join(struct.pack('<I',word)for word in words))
    streams['features'].extend(FEATURE.pack(*fields))
    return ordinal



ANCHOR_POLICY = 'own-source-topology-polygon0-exterior-root-u32-mean-drop-closing-vertex-f64-world/1'
BOUNDARY_UID_BIT = 0x80000000


def prepare_area_roles(feature, programmes):
    """Split source fill from original boundary without mixing native hit areas.

    The primary native area contains only source triangle masks. Original
    topology becomes a separate line carrier; only LS/LC draw there. SY/TX/TE
    stay on the primary with a provenance-bound, view-independent F64 anchor.
    This is an explicit own anchor policy, not global bit parity with the
    original renderer's view-dependent F32 fallback.
    """
    from .presentation import split_commands
    geometry=feature['geometry']; source_id=feature['sourceId']
    operations={key:split_commands(value.decode('ascii')) for key,value in programmes.items() if key in (196608,196609)}
    main_programmes=dict(programmes); boundary_programmes={}
    for key,tokens in operations.items():
        main_programmes[key]=(''.join(token+';' for token in tokens if not token.startswith(('LS(','LC(')))).encode('ascii')
        boundary_programmes[key]=(''.join(token+';' for token in tokens if token.startswith(('LS(','LC(')))).encode('ascii')
    all_tokens=[token for tokens in operations.values() for token in tokens]
    pure_fill=bool(all_tokens) and all(token.startswith(('AC(','AP(')) for token in all_tokens)
    if not geometry.get('trianglePrimitives'):
        # A genuine boundary-only area has no exported fill representation to
        # split. Retain its already certified topology under the existing path.
        main=prepare_feature_geometry(feature,programmes=[programmes[k]for k in operations],target_kind=3)
        return {'main':main,'boundary':None,'mainProgrammes':programmes,'boundaryProgrammes':{},
                'receipt':{'policy':'SOURCE_TOPOLOGY_ONLY_NO_TRIANGLE_REPRESENTATION','split':False}}
    main=prepare_feature_geometry(feature,programmes=[main_programmes[k]for k in operations],target_kind=3,area_source_triangles=True)
    if pure_fill:
        return {'main':main,'boundary':None,'mainProgrammes':main_programmes,'boundaryProgrammes':{},
                'receipt':{'policy':'EXPLICIT_SOURCE_TRIANGLE_FILL_ONLY','split':False}}
    _need(geometry.get('reconstruction',{}).get('status')=='exact_topology' and
          not geometry['reconstruction'].get('issues') and geometry.get('polygons'),
          'SOURCE_BOUNDARY_OR_LABEL_REQUIRES_EXACT_TOPOLOGY',source_id=source_id)
    ring_map={ring['id']:ring for ring in geometry['rings']}; ordered=[]; used=set()
    for polygon in geometry['polygons']:
        for ring_id in [polygon['exteriorRingId']]+polygon['interiorRingIds']:
            _need(ring_id not in used,'TOPOLOGY_RING_REFERENCED_TWICE',source_id=source_id)
            used.add(ring_id);ring=ring_map[ring_id]
            _need(ring['role'] in ('exterior','interior') and ring.get('derivation')=='topology',
                  'EXPLICIT_TOPOLOGY_RING_ROLE_REQUIRED',source_id=source_id)
            root=project_path(ring['coordinates'],source_id=source_id,closed=True)
            _polygon(root,source_id);ordered.append((ring_id,root))
    _need(used==set(ring_map),'ALL_TOPOLOGY_RINGS_ACCOUNTED_FOR',source_id=source_id)
    # A separate Kind2 carrier has only visible, original directed boundary
    # lines. Its normal line clipper intersects original lines with coverage;
    # it never invents visible polygon closing edges at the coverage boundary.
    line_feature={**feature,'geometry':{'kind':'line','parts':[{'coordinates':ring_map[rid]['coordinates'],'edgeRefOrdinals':ring_map[rid]['edgeRefOrdinals']}for rid,_ in ordered],
        'reconstruction':geometry['reconstruction']}}
    boundary=prepare_feature_geometry(line_feature,programmes=list(boundary_programmes.values()),target_kind=2)
    first_id,first=ordered[0]; count=len(first)-1
    _need(count>0 and first[0]==first[-1],'AUTHENTIC_CLOSED_ANCHOR_RING',source_id=source_id)
    numerator=[sum(point[axis] for point in first[:-1]) for axis in (0,1)]
    world=[value/(count*Q) for value in numerator]
    _need(0x10090 not in main_programmes,'CONFLICTING_SOURCE_F32_ANCHOR',source_id=source_id)
    anchor=struct.pack('<dd',*world)
    _need(0x10092 not in main_programmes or main_programmes[0x10092]==anchor,'CONFLICTING_SOURCE_F64_ANCHOR',source_id=source_id)
    main_programmes[0x10092]=anchor
    anchor_receipt={'policy':ANCHOR_POLICY,'sourceId':source_id,'sourceRingId':first_id,
        'sourceRootRingSHA256':hashlib.sha256(b''.join(struct.pack('<II',*p)for p in first)).hexdigest(),
        'sourceRootPointCount':len(first),'meanDenominatorPoints':count,'excludeClosingVertex':True,
        'exactWorldNumerators':numerator,'exactWorldDenominator':count*Q,'worldFloat64':world,
        'nativeROKey':0x10092,'nativeROBytesHex':anchor.hex(),'shapelyAreaWeightedCentroid':False,
        'sourceGeometryUnchanged':True,'originalProviderAnchorClaim':False,
        'originalViewDependentF32FallbackGlobalBitParityClaim':False}
    main['receipt']['explicitSourceTopologyAnchor']=anchor_receipt
    boundary['receipt']['sourceGeometryKind']='area'
    boundary['receipt']['derivedBoundaryLineCarrier']=True
    return {'main':main,'boundary':boundary,'mainProgrammes':main_programmes,
            'boundaryProgrammes':boundary_programmes,'receipt':{'policy':'SOURCE_TRIANGLE_AREA_MAIN_AND_SEPARATE_TOPOLOGY_LINE/1',
            'split':True,'anchor':anchor_receipt,'mainQueryRole':'original_source_triangle_fill',
            'boundaryQueryRole':'original_source_topology_line_distance','nativeCombinedAreaHitCountClaim':False,
            'sourceBeforeProgrammesHex':{str(k):v.hex()for k,v in programmes.items()},
            'mainAfterProgrammesHex':{str(k):v.hex()for k,v in main_programmes.items()},
            'boundaryAfterProgrammesHex':{str(k):v.hex()for k,v in boundary_programmes.items()}}}


def prepare_geometry(context, metadata, presentation, output_dir):
    """Author primary source records first, then explicit boundary companions."""
    from ..country_runtime.render_activation import encode_frame
    from ..country_runtime.localize_metadata import Stream,additional_frame
    _need(context.get('format')=='no5-cache-country-context/1','CACHE_COUNTRY_CONTEXT_FORMAT')
    cells=context['cells'];ids=[cell['id']for cell in cells]
    _need(cells and len(ids)==len(set(ids)),'UNIQUE_NONEMPTY_IR_CELLS')
    directory=Path(output_dir);_need(not directory.exists(),'FRESH_GEOMETRY_DIRECTORY_REQUIRED')
    source_registry_pin=presentation.get('pins',{}).get('sourceRegistry') or presentation.get('sourceRegistry')
    registry=[];registry_by_key={};next_style_id=1
    if source_registry_pin is not None:
        registry_raw=Path(source_registry_pin['path']).read_bytes()
        _need((len(registry_raw),hashlib.sha256(registry_raw).hexdigest())==(source_registry_pin['bytes'],source_registry_pin['sha256']),'SOURCE_REGISTRY_INPUT_PIN')
        registry=json.loads(registry_raw);registry_by_key={row['keySHA256']:row for row in registry}
        next_style_id=max([row['ownEffectiveStyleID']for row in registry]or[0])+1
    prepared_cells=[];relationships=[];coverage_normalizations=[];source_uids={owner['uid']for owner in context['owners']}

    def split_style(style, programmes, *, uid, primary_uid, source_id, digest, role, kind, policy):
        nonlocal next_style_id
        _need(source_registry_pin is not None,'SOURCE_REGISTRY_REQUIRED_FOR_ROLE_BINDINGS',source_id=source_id)
        selected=style['resolution']['selected'];oldkey=selected['effectiveSourceKeySHA256'];base=registry_by_key[oldkey]
        programme_hex={str(k):v.hex()for k,v in programmes.items()}
        key_material={'originalSourceKey':oldkey,'featureSourceDigest':digest,
            'profileSHA256':base['profileSHA256'],'sourceId':source_id,'primaryUID':primary_uid,
            'authoredUID':uid,'role':role,'splitPolicy':policy,'programmes':programme_hex}
        newkey=hashlib.sha256(json.dumps(key_material,sort_keys=True,separators=(',',':')).encode()).hexdigest()
        newentry={**base,'keySHA256':newkey,'ownEffectiveStyleID':next_style_id,
            'effectiveCandidateINSTHex':programmes[196608].hex(),'derivedFromSourceKeySHA256':oldkey,
            'derivedFromUID':primary_uid,'derivedRole':role,'derivedRenderKind':kind,
            'derivation':key_material,'derivedProgrammesHex':programme_hex}
        registry.append(newentry);registry_by_key[newkey]=newentry
        derived_selected={**selected,'effectiveSourceKeySHA256':newkey,'effectiveStyleID':next_style_id};next_style_id+=1
        return {**style,'kind':kind,'resolution':{**style['resolution'],'selected':derived_selected,
            'instruction':programmes[196608].decode('ascii'),'sourceRoleDerivation':key_material}}
    for cell_ordinal,cell in enumerate(cells):
        cid=cell['id'];owners=context['by_cell'][cid];meta=metadata['cells'][cid];paint=presentation['cells'][cid]
        _need(len(owners)==len(cell['features']),'IR_OWNER_BIJECTION',source_id=cid)
        _need(type(cell['nativeScale'])is int and cell['nativeScale']>0,'POSITIVE_IR_NATIVE_SCALE',source_id=cid)
        coverage={'cellId':cid,'nativeScale':cell['nativeScale'],'covered':[],'excluded':[]}
        for role in ('covered','excluded'):
            for ring_ordinal,ring in enumerate(cell['coverage'][role]):
                normalized,event=normalize_coverage_ring(ring['coordinates'],source_id=cid)
                # Native SourceInfo and point ownership retain the complete
                # source walk. Area masks have a separate even/odd cycle.
                coverage[role].append(project_path(ring['coordinates'],source_id=cid,closed=True))
                if event:coverage_normalizations.append({**event,'role':role,'sourceRingOrdinal':ring_ordinal,
                    'normalizationAppliesOnlyToAreaMasks':True,'pointWinnerSourceWalkPreserved':True})
        _need(coverage['covered'],'AUTHENTIC_COVERAGE_REQUIRED_NO_BBOX_SUBSTITUTE',source_id=cid)
        render=bytearray(paint['render']);primary=[];derived=[]
        for ordinal,owner in enumerate(owners):
            feature=owner['feature'];uid=owner['uid'];sid=feature['sourceId']
            _need(owner['sourceOwnerOrdinal']==ordinal and owner['cellOrdinal']==cell_ordinal and owner['cellId']==cid and feature==cell['features'][ordinal],'EXACT_SOURCE_OWNER_ORDER_BINDING',source_id=sid)
            _need(uid==((cell_ordinal+1)<<32)|feature['sourceFeatureId'],'STABLE_SOURCE_UID_NAMESPACE',source_id=sid)
            digest=hashlib.sha256(json.dumps(feature,sort_keys=True,separators=(',',':'),ensure_ascii=False,allow_nan=False).encode()).hexdigest()
            _need(owner.get('featureSourceDigest')==digest,'EXACT_FEATURE_SOURCE_DIGEST',source_id=sid)
            style=_value(presentation['owners'],uid);offset=_value(paint['renderOffsets'],uid)
            programs,query_offset=_programmes(paint['render'],offset)
            _need({196608,196609}<=programs.keys(),'BOTH_EXPLICIT_DRAW_CONTEXTS_REQUIRED',source_id=sid)
            _need(query_offset==_value(meta['additionalOffsets'],uid),'EXACT_OWNER_RENDER_QUERY_BINDING',source_id=sid)
            _need(style['role']in('ground','detail','point'),'EXPLICIT_OWNER_LAYER_ROLE',source_id=sid)
            component=None
            if feature['geometry']['kind']=='area':
                component=prepare_area_roles(feature,programs);geometry=component['main']
                new_frame=encode_frame(query_offset,component['mainProgrammes']);offset=len(render);render.extend(new_frame)
                main_style={**style,'kind':3,'resolution':{**style['resolution'],'instruction':component['mainProgrammes'][196608].decode('ascii')}}
                if component['boundary']is not None:
                    main_style=split_style(style,component['mainProgrammes'],uid=uid,primary_uid=uid,
                        source_id=sid,digest=digest,role='source_triangle_area_main',kind=3,policy=component['receipt']['policy'])
            else:
                geometry=prepare_feature_geometry(feature,programmes=[programs[k]for k in(196608,196609)],target_kind=style['kind']);main_style=style
            if style['role']=='ground'and geometry['kind']==3:
                from .presentation import split_commands
                active_fill=any(token.startswith(('AC(','AP('))for key in(196608,196609)for token in split_commands(programs[key].decode('ascii')))
                _need(active_fill and geometry['receipt'].get('areaPolicy')=='EXPLICIT_SOURCE_TRIANGLE_MASK_FILL_ONLY_NO_BOUNDARY_PROMOTION' and not geometry['fillGeometry'].is_empty,'GROUND_KIND3_REQUIRES_ACTIVE_AC_AP_SOURCE_MASK_FILL',source_id=sid)
            _need((style['role']=='point')==(geometry['kind']in(1,4)),'POINT_LAYER_TYPED_KIND',source_id=sid)
            level=style['nativeScaminLevel'];zorder=style.get('nativeZOrder',0)
            _need(type(level)is int and 0<=level<=0xffffffff,'EXPLICIT_NATIVE_SCAMIN_LEVEL_U32',source_id=sid)
            _need(type(zorder)is int and 0<=zorder<=255,'NATIVE_Z_ORDER_U8')
            dictionary_index=_value(meta['dictionaryIndices'],uid)
            record=FEATURE.pack(uid,0,offset,dictionary_index,level,feature['classCode'],geometry['kind'],zorder,0,0)
            query=additional_frame(Stream(len(meta['additional']),lambda at,n:meta['additional'][at:at+n]),query_offset)
            qsha=hashlib.sha256(query).hexdigest()
            _need(style.get('additionalFrameSHA256')in(None,qsha),'SOURCE_PRESENTATION_QUERY_HASH_BINDING',source_id=sid)
            relationship={'primaryUID':uid,'originalSourceId':sid,'originalSourceFeatureId':feature['sourceFeatureId'],'sourceOriginalOwnerOrdinal':ordinal,'featureSourceDigest':digest,'querySHA256':qsha,'role':'source_primary'}
            primary.append((owner,main_style,geometry,record,relationship))
            if component and component['boundary']is not None:
                _need(source_registry_pin is not None,'SOURCE_REGISTRY_REQUIRED_FOR_ROLE_BINDINGS',source_id=sid)
                duid=uid|BOUNDARY_UID_BIT
                _need(duid not in source_uids,'DERIVED_BOUNDARY_UID_COLLISION',source_id=sid);source_uids.add(duid)
                boundary_geometry=component['boundary'];boundary_programmes=component['boundaryProgrammes']
                boffset=len(render);render.extend(encode_frame(query_offset,boundary_programmes))
                dstyle=split_style(style,boundary_programmes,uid=duid,primary_uid=uid,
                    source_id=sid,digest=digest,role='source_boundary_line',kind=2,policy=component['receipt']['policy'])
                dstyle['role']='detail'
                drecord=FEATURE.pack(duid,0,boffset,dictionary_index,level,feature['classCode'],2,zorder,0,0)
                dordinal=len(owners)+len(derived)
                downer={**owner,'uid':duid,'sourceOwnerOrdinal':dordinal,'sourceOriginalOwnerOrdinal':ordinal,'derivedFromUID':uid,'derivedRole':'source_boundary_line'}
                drel={**relationship,'role':'source_boundary_line','derivedUID':duid,'derivedFromUID':uid,'nativeSourceOwnerOrdinal':dordinal,'queryAttrsAliasPrimary':True,'drawCommands':['LS','LC'],'visibleProgramme':any(boundary_programmes.values()),'sourceBeforeAndSplit':component['receipt']}
                derived.append((downer,dstyle,boundary_geometry,drecord,drel));relationships.append(drel)
        prepared_cells.append((cell,coverage,primary+derived,bytes(render)))
    directory.mkdir(parents=True,mode=0o700)
    catalogs=[];ground_rows=[];point_rows=[];detail_rows=[];remaining_rows=[];fill_rows=[];coverage_rows=[];ledger=[];counts=Counter();ground3=0
    for ci,(cell,coverage,owners,render)in enumerate(prepared_cells):
        cid=cell['id'];meta=metadata['cells'][cid];cell_dir=directory/('cell-'+str(ci));cell_dir.mkdir(mode=0o700)
        ap=_write(cell_dir,'additional.bin',meta['additional']);rp=_write(cell_dir,'render.bin',render)
        groups={name:{'features':bytearray(),'indices':bytearray(),'coordinates':bytearray()}for name in('all','ground','point','detail')};bindings={name:[]for name in('ground','point','detail')};fills=[]
        for owner,style,geometry,record,relationship in owners:
            feature=owner['feature'];uid=owner['uid'];role=style['role'];ordinal=_append_prepared(groups[role],geometry,record);_append_prepared(groups['all'],geometry,record)
            values=geometry['sourceSCAMINValues'];scamin=values[0]if len(values)==1 and type(values[0])in(int,str)else None
            row={'cellId':cid,'uid':uid,'sourceId':feature['sourceId'],'sourceFeatureId':feature['sourceFeatureId'],'featureSourceDigest':owner['featureSourceDigest'],'sourceOwnerOrdinal':owner['sourceOwnerOrdinal'],'sourceOriginalOwnerOrdinal':relationship['sourceOriginalOwnerOrdinal'],'preparedOwnerOrdinal':ordinal,'kind':geometry['kind'],'className':feature['classAcronym'],'classAcronym':feature['classAcronym'],'geometryProfile':geometry['geometryProfile'],'wholeOwnerRootBBox':geometry['wholeOwnerRootBBox'],'resolution':style.get('resolution',{}),'sourceSCAMIN':scamin if role=='point'else style['sourceSCAMIN'],'sourceSCAMINValues':values,'pointSemanticsEligible':True,'disposition':'READY_GENUINE_SOURCE_NONPOINT_PROGRAM'if role=='detail'else'ACTIVATED_GENUINE_SOURCE_CFG2_KIND2_OR3'if role=='ground'else'READY_SOURCE_POINT','originalRecord32Hex':record.hex(),'additionalFrameSHA256':relationship['querySHA256'],'nativeScaminLevel':style['nativeScaminLevel'],'sourceGeometryReceipt':geometry['receipt'],'primarySourceRelationship':relationship}
            if 'derivedFromUID'in owner:row.update(derivedFromUID=owner['derivedFromUID'],derivedRole=owner['derivedRole'])
            bindings[role].append(row)
            if role=='ground'and geometry['kind']==3:ground3+=1;fills.append(geometry['fillGeometry'])
            counts[role]+=1;counts['kind'+str(geometry['kind'])]+=1
            ledger.append({'uid':uid,'cellId':cid,'sourceId':feature['sourceId'],'featureSourceDigest':owner['featureSourceDigest'],'sourceOwnerOrdinal':owner['sourceOwnerOrdinal'],'role':role,'kind':geometry['kind'],'indexOccurrences':geometry['indexOccurrences'],'pointOccurrences':geometry['pointOccurrences'],'primarySourceRelationship':relationship,'geometryReceipt':geometry['receipt']})
        stream_pins={name:{kind:_write(cell_dir,name+'-'+kind+'.bin',bytes(value))for kind,value in streams.items()}for name,streams in groups.items()}
        bp={name:_json(cell_dir,name+'-owners.json',{'cellId':cid,'owners':rows})for name,rows in bindings.items()};empty=_write(cell_dir,'empty.bin',b'');noowners=_json(cell_dir,'empty-owners.json',{'owners':[]})
        catalogs.append({'cellId':cid,'compiledStreams':{'features':stream_pins['all']['features'],'render':rp}})
        ground_rows.append({'cellId':cid,'preparedStreams':stream_pins['ground'],'ownerBindings':bp['ground']})
        point_rows.append({'cellId':cid,'preparedStreams':stream_pins['point'],'ownerBindings':bp['point'],'originalAdditional':ap})
        detail_rows.append({'cellId':cid,'preparedStreams':{**stream_pins['detail'],'render':rp},'ownerBindings':bp['detail'],'originalAdditional':ap})
        remaining_rows.append({'cellId':cid,'preparedStreams':{k:empty for k in('features','indices','coordinates','render')},'ownerBindings':noowners,'originalAdditional':ap})
        fill=unary_union(fills)if fills else GeometryCollection();_need(fill.is_valid,'SOURCE_GROUND_FILL_UNION_VALID_NO_REPAIR',source_id=cid)
        fill_rows.append({'cellId':cid,'ownersKind3':sum(row['kind']==3 for row in bindings['ground']),'fillAvailabilityWKB':_write(cell_dir,'ground-fill.wkb',fill.wkb),'kind8OrUnresolvedAdmission':False,'coverageOrBBoxNotUsedAsFill':True});coverage_rows.append(coverage)
    catalog=_json(directory,'CATALOG.json',{'cells':catalogs,'sourceCells':len(cells)});ground=_json(directory,'GEOMETRY_INPUTS.json',{'cells':ground_rows});points=_json(directory,'POINT_INPUTS.json',{'cells':point_rows});coverage=_json(directory,'COVERAGE_FIXTURE.json',{'sources':coverage_rows});fill=_json(directory,'FILL_AVAILABILITY.json',{'sourceCells':len(cells),'ownersKind3':ground3,'inputGeometryPin':ground,'sourceGroundCatalog':catalog,'cells':fill_rows});details=_json(directory,'DETAIL_INPUTS.json',{'cells':detail_rows});remaining=_json(directory,'REMAINING_DETAIL_INPUTS.json',{'cells':remaining_rows})
    relationship_pin=_json(directory,'PRIMARY_SOURCE_RELATIONSHIPS.json',{'sourcePrimaryFeatures':len(context['owners']),'authoredRepresentationRecords':len(ledger),'derivedBoundaryRecords':len(relationships),'rows':relationships})
    receipt=_json(directory,'GEOMETRY_RECEIPT.json',{'format':'no5-cache-country-geometry-receipt/2','projection':PROJECTION_POLICY,'depthPolicy':DEPTH_POLICY,'anchorPolicy':ANCHOR_POLICY,'counts':dict(counts),'sourcePrimaryFeatures':len(context['owners']),'authoredRepresentationRecords':len(ledger),'derivedBoundaryRecords':len(relationships),'primarySourceRelationships':relationship_pin,'owners':ledger,'coverageFromOriginalIR':True,'coverageNormalizations':coverage_normalizations,'coverageBBoxSubstitution':False,'newTriangulation':False,'nativeCalls':0,'physicalPlotterAcceptanceClaim':False})
    pins={'groundCatalog':catalog,'groundGeometry':ground,'pointInputs':points,'coverageFixture':coverage,'fillAvailability':fill,'detailInputs':details,'remainingDetailInputs':remaining}
    overrides={}
    if relationships:overrides['sourceRegistry']=_json(directory,'DERIVED_SOURCE_REGISTRY.json',registry)
    return {**pins,'pins':pins,'receipt':receipt,'geometryReceipt':receipt,'coverage':coverage_rows,'counts':dict(counts),'sourceCells':len(cells),'sourcePrimaryFeatures':len(context['owners']),'authoredRepresentationRecords':len(ledger),'derivedDrawRecords':len(relationships),'derivedVisibleDrawRecords':sum(row['visibleProgramme']for row in relationships),'presentationOverrides':overrides,'primarySourceRelationships':relationship_pin,'expectedSourceCounts':{'sourceCells':len(cells),'groundOwners':counts['ground'],'pointOwners':counts['point'],'detailOwners':counts['detail']}}
