"""Separate supplied coverage walks from their even/odd area interpretation.

SourceInfo and point winners retain the complete supplied closed walk,
including retraced zero-area boundary edges. Area masks use separate faces.
The historical specimen decoder below remains audit-only.
"""

from collections import Counter

from fractions import Fraction

import hashlib

import struct

from shapely.geometry import LineString, Polygon
from shapely.ops import polygonize, unary_union

Q = 2147483649

RING_SHA256 = "be515104224036e595dc70d068b11eeaef222874158df00e610a961ac020b04c"


class UnresolvedCoverageArea(ValueError):
    """Retain the original invalid walk for existing owner-scoped handling."""

def need(value, label):
    if not value:
        raise ValueError(label)

def cross(a, b, p):
    return (b[0] - a[0]) * (p[1] - a[1]) - (b[1] - a[1]) * (p[0] - a[0])

def on_segment(p, a, b):
    return cross(a, b, p) == 0 and all(min(a[k], b[k]) <= p[k] <= max(a[k], b[k]) for k in (0, 1))

def twice_area(ring):
    return sum(a[0] * b[1] - b[0] * a[1] for a, b in zip(ring, ring[1:]))

def odd_even(point, ring):
    """Exact half-open horizontal ray parity; not an executed Qt getter."""
    x, y = point
    inside = False
    for a, b in zip(ring, ring[1:]):
        if (a[1] > y) != (b[1] > y):
            c = cross(a, b, (x, y))
            if (c > 0) == (b[1] > a[1]):
                inside = not inside
    return inside

def on_boundary(point, ring):
    return any(on_segment(point, a, b) for a, b in zip(ring, ring[1:]))

def segment_contact(a, b, c, d):
    # Explicit null-segment cases avoid false collinearity on disjoint points.
    if a == b:
        return "touch" if on_segment(a, c, d) else "none"
    if c == d:
        return "touch" if on_segment(c, a, b) else "none"
    o = (cross(a, b, c), cross(a, b, d), cross(c, d, a), cross(c, d, b))
    if all(v == 0 for v in o):
        axis = 0 if a[0] != b[0] else 1
        lo = max(min(a[axis], b[axis]), min(c[axis], d[axis]))
        hi = min(max(a[axis], b[axis]), max(c[axis], d[axis]))
        return "overlap" if lo < hi else "touch" if lo == hi else "none"
    if o[0] * o[1] < 0 and o[2] * o[3] < 0:
        return "cross"
    return "touch" if any((v == 0 and on_segment(p, u, w)) for v, p, u, w in (
        (o[0], c, a, b), (o[1], d, a, b), (o[2], a, c, d), (o[3], b, c, d))) else "none"

def decode_coverage_ring(ring, *, cell_id, role, ordinal):
    """Interpret declared Root-U32 walks without changing their boundary.

    Exact half-open ray parity selects noded linework faces. The returned area
    geometry is exclusively for Boolean masks; originalRing remains the
    SourceInfo and point-winner walk, including zero-area retraced edges.
    """
    need(isinstance(cell_id,str) and cell_id and role in ('covered','excluded') and
         type(ordinal)is int and ordinal>=0,'DECLARED_COVERAGE_RING_IDENTITY')
    ring=tuple(tuple(p)for p in ring)
    need(len(ring)>=4 and ring[0]==ring[-1],'COMPLETE_CLOSED_SOURCE_COVERAGE_WALK')
    need(all(len(p)==2 and all(type(v)is int and 0<=v<=Q for v in p)for p in ring),
         'SOURCE_COVERAGE_U32_ROOT_POINTS')
    digest=hashlib.sha256(b''.join(struct.pack('<II',*p)for p in ring)).hexdigest()
    shape=Polygon(ring);normalized=not(shape.is_valid and shape.area>0)
    faces=[shape]if not normalized else [face for face in polygonize(unary_union(LineString(ring)))
        if odd_even(tuple(Fraction(v)for v in face.representative_point().coords[0]),ring)]
    if not(faces and all(face.is_valid and face.area>0 for face in faces)):
        raise UnresolvedCoverageArea('NONEMPTY_VALID_EVEN_ODD_COVERAGE_FACES')
    for face in faces:
        if not all(float(v).is_integer()for boundary in (face.exterior,*face.interiors)
                   for point in boundary.coords for v in point):
            raise UnresolvedCoverageArea('COVERAGE_SUBGRID_INTERSECTION_NOT_ROUNDED')
    area=unary_union(faces)
    if not(area.is_valid and area.area>0):
        raise UnresolvedCoverageArea('VALID_SOURCE_COVERAGE_AREA_INTERPRETATION')
    return dict(originalRing=ring,areaGeometry=area,
        areaRings=[tuple(tuple(map(int,p))for p in face.exterior.coords)for face in faces],
        receipt=dict(format='no5-generic-source-coverage-walk-area/1',cellId=cell_id,role=role,ordinal=ordinal,
            sourceRingSHA256=digest,sourceRingXYBytes=len(ring)*8,originalPoints=len(ring),
            areaInterpretation='exact integer linework faces selected by half-open even/odd parity',
            areaNormalizationApplied=normalized,areaFaceCount=len(faces),areaInteriorRingCount=sum(len(f.interiors)for f in faces),
            areaRootUnitsSquared=area.area,originalStoredCoverageWalkUnchanged=True,
            pointWinnerCoverageWalkUnchanged=True,closedSourceBoundaryPolicyUnchanged=True,
            originalZeroAreaBoundaryEdgesRetained=True,areaMaskBoundaryEquivalenceClaim=not normalized,
            historicalCellOrPointCountRequired=False,bufferOrSnappingOrTolerance=False,nativeCalls=0))


def decode_040003_excluded0(ring, *, cell_id, role, ordinal):
    """Return areaRings + original zeroAreaCycles + exact decomposition proof."""
    need((cell_id, role, ordinal) == ("OC-27-040003", "excluded", 0), "EXACT_SOURCE_RING_IDENTITY")
    ring = tuple(tuple(p) for p in ring)
    need(len(ring) == 42 and ring[0] == ring[-1], "AUTHENTIC_42_POINT_CLOSURE")
    need(all(len(p) == 2 and all(type(v) is int and 0 <= v <= Q for v in p) for p in ring), "ORIGINAL_U32_ROOT_POINTS")
    digest = hashlib.sha256(b"".join(struct.pack("<II", *p) for p in ring)).hexdigest()
    need(digest == RING_SHA256, "AUTHENTIC_ORIGINAL_RING_BYTES")
    need(ring[12] == ring[13] and ring[22] == ring[23], "TWO_ORIGINAL_NULL_EDGES")
    a, b, c = ring[26:29]
    need(a != b and a != c and b != c and on_segment(a, b, c), "EXISTING_POINT_ON_RETRACED_EDGE")
    area_ring = tuple(p for i, p in enumerate(ring) if i not in (13, 23, 27))
    zero_cycles = ((ring[12], ring[13]), (ring[22], ring[23]), (a, b, a))
    # Split exactly one original segment at an already stored source vertex.
    expanded = []
    for i, (u, v) in enumerate(zip(ring, ring[1:])):
        expanded.extend(((u, a), (a, v)) if i == 27 else ((u, v),))
    decomposed = list(zip(area_ring, area_ring[1:]))
    decomposed.extend(edge for cycle in zero_cycles for edge in zip(cycle, cycle[1:]))
    need(Counter(expanded) == Counter(decomposed), "EXACT_ORIENTED_EDGE_MULTISET_DECOMPOSITION")
    # Qt 4.8.7's qt_polygon_isect_line ignores horizontal edges. The entire
    # ordered sequence of potentially contributing edges is byte-value equal.
    need([(u, v) for u, v in zip(ring, ring[1:]) if u[1] != v[1]] ==
         [(u, v) for u, v in zip(area_ring, area_ring[1:]) if u[1] != v[1]],
         "UNCHANGED_ORDERED_NONHORIZONTAL_EDGE_SEQUENCE")
    need(all(twice_area(cycle) == 0 for cycle in zero_cycles), "DEGENERATE_CYCLES_HAVE_EXACT_ZERO_AREA")
    need(twice_area(area_ring) == twice_area(ring) > 0, "ORIGINAL_SIGN_AND_EXACT_TWICE_AREA")
    need(area_ring[0] == area_ring[-1] and len(set(area_ring[:-1])) == len(area_ring) - 1, "SIMPLE_CYCLE_DISTINCT_VERTICES")
    edges = list(zip(area_ring, area_ring[1:]))
    checks = 0
    for i, (u, v) in enumerate(edges):
        for j in range(i + 1, len(edges)):
            if j == i + 1 or (i == 0 and j == len(edges) - 1):
                continue
            checks += 1
            need(segment_contact(u, v, *edges[j]) == "none", "MAIN_CYCLE_NO_NONADJACENT_CONTACT")
    return dict(areaRings=[area_ring], zeroAreaCycles=list(zero_cycles), originalRing=ring,
        receipt=dict(format="no5-authentic-040003-excluded0-odd-even-walk/1", cellId=cell_id,
            role=role, ordinal=ordinal, sourceRingXYBytes=336, sourceRingSHA256=digest,
            originalPoints=42, areaCyclePoints=len(area_ring), exactTwiceArea=twice_area(ring),
            nonAdjacentAreaEdgeChecks=checks, originalEdgeSplit=dict(edge=27, existingPointOrdinal=26, point=a),
            originalNullEdges=[12, 22], originalRetracedSpikeOrdinals=[26, 27, 26],
            orientedEdgeMultisetExact=True, zeroAreaCyclesRetained=True,
            orderedNonHorizontalEdgesExact=True,
            allOffBoundaryOddEvenParitiesPreservedByOppositeEdgeCancellation=True,
            originalStoredCoverageMustRemainUnchanged=True, originalPointWinnerWalkMustRemainUnchanged=True,
            fullBoundaryEquivalenceToAreaPolygonClaim=False, nativeCalls=0,
            coordinateShift=False, inventedVertex=False, bufferOrMakeValid=False))

def fraction_samples(original, area_ring, covered):
    """Finite controls beside every segment, spike and bounding-box sectors."""
    samples = set()
    for a, b in zip(original, original[1:]):
        middle = ((Fraction(a[0]) + b[0]) / 2, (Fraction(a[1]) + b[1]) / 2)
        samples.add(middle)
        dx, dy = b[0] - a[0], b[1] - a[1]
        norm = max(abs(dx), abs(dy), 1) * 4096
        delta = (Fraction(-dy, norm), Fraction(dx, norm))
        for sign in (-1, 1):
            samples.add((middle[0] + sign * delta[0], middle[1] + sign * delta[1]))
    xs = (min(p[0] for p in original) - 1, (min(p[0] for p in original) + max(p[0] for p in original)) // 2, max(p[0] for p in original) + 1)
    ys = (min(p[1] for p in original) - 1, (min(p[1] for p in original) + max(p[1] for p in original)) // 2, max(p[1] for p in original) + 1)
    samples.update((Fraction(x), Fraction(y)) for x in xs for y in ys)
    rows = []
    for p in sorted(samples):
        old, new = odd_even(p, original), odd_even(p, area_ring)
        need(old == new, "EXACT_FRACTION_RAY_PARITY_AGREES")
        cover = sum(odd_even(p, r) for r in covered)
        old_boundary, new_boundary = on_boundary(p, original), on_boundary(p, area_ring)
        rows.append(dict(point=[[v.numerator, v.denominator] for v in p], originalOddEven=old,
            areaCycleOddEven=new, coveredParityCount=cover, originalExcludedBoundary=old_boundary,
            areaCycleBoundary=new_boundary, exactCoveredMinusExcludedEqualsOne=(cover - int(old) == 1),
            originalClosedBoundaryPolicyExcluded=(old or old_boundary),
            areaOnlyClosedBoundaryPolicyExcluded=(new or new_boundary)))
    return rows
