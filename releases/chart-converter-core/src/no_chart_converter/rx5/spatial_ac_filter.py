"""Own default-KEEP, local pure-AC rectangle-intersection proposals.

No chart, tree, pointer or query replacement is applied here. The caller keeps
the complete original owner and its original FI/I/CO/RO/AO for every other view
and for queries. The existing ring clipper proposes geometry; a separate exact
rational boundary check admits only its EXACT integer rectangle intersection.
No approximation, repair, fallback colour, inferred anchor or native call.
"""
from dataclasses import dataclass
from fractions import Fraction as F
from collections import defaultdict
from math import gcd, lcm, isfinite
import hashlib
import re
import struct

from shapely.geometry import box
from shapely.errors import GEOSException
from no_chart_converter.country_runtime import ring_clip, source_clip
from no_chart_converter.rx5 import area_polygon_groups, model, metadata, wire
from no_chart_converter.rx5.mask_merge import f32, qt_round

POLICY = 'EXPLICIT_LOCAL_ROOT1_PURE_AC_EXACT_RECTANGLE_PROPOSAL/1'
QUERY_SCOPE = 'LOCAL_FILL_PROPOSAL_ONLY_RETAIN_COMPLETE_SOURCE_FOR_QUERIES'
TRANSFORM = 'SOURCE_BOUND_ROOT1_NORMAL_TILE256/1'
Q = 2147483649
AC = re.compile(rb'AC\([A-Z][A-Z0-9_]{0,15}\);')


def need(value, reason):
    if not value:
        raise ValueError(reason)


def sha(raw):
    return hashlib.sha256(raw).hexdigest()


@dataclass(frozen=True)
class Limits:
    # Own work budgets, not format limits. Validation precedes expensive work.
    input_bytes: int = 2 * 1024 * 1024
    source_parts: int = 64
    source_points: int = 2048
    pair_checks: int = 2 * 1024 * 1024
    output_parts: int = 128
    output_points: int = 4096
    output_bytes: int = 65536
    oracle_edges: int = 16384
    rational_bits: int = 256


@dataclass(frozen=True)
class View:
    # These are explicit logical projection inputs, never a UID/node guess.
    level: int
    x: int
    y: int
    halo_pixels: int = 128
    transform: str = TRANSFORM

    def rectangle(self):
        need(self.transform == TRANSFORM, 'UNQUALIFIED_VIEW_TRANSFORM')
        need(all(type(v) is int for v in
                 (self.level, self.x, self.y, self.halo_pixels)), 'STRICT_VIEW_INTEGERS')
        need(0 <= self.level <= 29 and 0 <= self.x < 1 << self.level
             and 0 <= self.y < 1 << self.level, 'LOGICAL_TILE_DOMAIN')
        need(4 <= self.halo_pixels <= 128, 'EXPLICIT_HALO_FOUR_TO_128_PIXELS')
        den = 256 * (1 << self.level)
        h = self.halo_pixels
        exact = (F((256*self.x-h)*Q, den), F((256*self.y-h)*Q, den),
                 F((256*(self.x+1)+h)*Q, den), F((256*(self.y+1)+h)*Q, den))
        bounds = (exact[0].__floor__(), exact[1].__floor__(),
                  exact[2].__ceil__(), exact[3].__ceil__())
        need(0 <= bounds[0] < bounds[2] <= Q and
             0 <= bounds[1] < bounds[3] <= Q, 'HALO_INSIDE_ROOT_DOMAIN')
        return bounds, exact


@dataclass(frozen=True)
class PaintScope:
    # Assertions are an explicit caller contract, not authentication. Actual RO
    # contents are parsed independently. Unknown semantics remain KEEP.
    pure_ac: bool = False
    anchor: str = 'UNKNOWN'  # NONE_PROVEN or EXISTING_EXPLICIT_F64 only.
    query_scope: str = ''
    coordinate_format: int = 2


def input_pins(record, indices, coordinates, render, additional):
    return {name: dict(bytes=len(raw), sha256=sha(raw)) for name, raw in zip(
        ('FI', 'I', 'CO', 'RO', 'AO'), (record, indices, coordinates, render, additional))}


def _orient(a, b, c):
    return (b[0]-a[0])*(c[1]-a[1])-(b[1]-a[1])*(c[0]-a[0])


def _on(p, a, b):
    return _orient(a, b, p) == 0 and all(min(a[k], b[k]) <= p[k] <= max(a[k], b[k]) for k in (0, 1))


def _touch(a, b, c, d):
    ab, cd = (_orient(a, b, c), _orient(a, b, d)), (_orient(c, d, a), _orient(c, d, b))
    return (ab[0]*ab[1] < 0 and cd[0]*cd[1] < 0) or any(
        (_on(p, u, v)) for p, u, v in ((a,c,d),(b,c,d),(c,a,b),(d,a,b)))


def _inside(p, ring):
    winding = 0
    for a, b in zip(ring, ring[1:]):
        need(not _on(p, a, b), 'SOURCE_RING_BOUNDARY_CONTACT')
        if a[1] <= p[1] < b[1] and _orient(a,b,p) > 0:
            winding += 1
        elif b[1] <= p[1] < a[1] and _orient(a,b,p) < 0:
            winding -= 1
    return winding != 0


def _topology(rings, limits):
    """Exact simple disjoint exterior/hole subset; never repair or guess roles."""
    edges = []
    for ri, ring in enumerate(rings):
        pts = ring['points']
        need(pts[0] == pts[-1] and len(set(pts[:-1])) == len(pts)-1,
             'SOURCE_OR_OUTPUT_RING_REPEATED_VERTEX')
        need(source_clip.signed2(pts) != 0, 'NONZERO_EXACT_RING')
        for ei, (a,b) in enumerate(zip(pts, pts[1:])):
            need(a != b, 'NO_ADJACENT_COLLAPSE')
            edges.append((ri,ei,a,b,len(pts)-1))
    need(len(edges)*(len(edges)-1)//2 <= limits.pair_checks, 'EXACT_EDGE_PAIR_WORK_BUDGET')
    for i, (ri,ei,a,b,n) in enumerate(edges):
        for rj,ej,c,d,m in edges[i+1:]:
            if ri == rj and ((ei-ej) % n in (1,n-1)):
                # Adjacent collinear continuation is valid; backtracking is not.
                common = set((a,b)) & set((c,d))
                need(len(common) == 1, 'ADJACENT_RING_EDGE_OVERLAP')
                shared = next(iter(common)); u = b if a == shared else a; v = d if c == shared else c
                need(not (_orient(u,shared,v) == 0 and _on(u,shared,v)), 'ADJACENT_RING_BACKTRACK')
                need(not (_orient(u,shared,v) == 0 and _on(v,shared,u)), 'ADJACENT_RING_BACKTRACK')
                continue
            need(not _touch(a,b,c,d), 'RING_CROSSING_OR_TOUCH_UNQUALIFIED')
    exteriors = [r for r in rings if r['role'] & 12 == 8]
    holes = [r for r in rings if r['role'] & 12 == 4]
    need(exteriors, 'EXTERIOR_REQUIRED')
    for i, a in enumerate(exteriors):
        for b in exteriors[i+1:]:
            need(not _inside(a['points'][0],b['points']) and not _inside(b['points'][0],a['points']),
                 'OVERLAPPING_OR_NESTED_EXTERIORS_KEEP')
    for i, a in enumerate(holes):
        need(sum(_inside(a['points'][0], e['points']) for e in exteriors) == 1,
             'HOLE_MUST_BELONG_TO_EXACTLY_ONE_EXTERIOR')
        for b in holes[i+1:]:
            need(not _inside(a['points'][0],b['points']) and not _inside(b['points'][0],a['points']),
                 'OVERLAPPING_OR_NESTED_HOLES_KEEP')


def _clip_walk(points, bounds):
    """Exact Sutherland-Hodgman oriented CHAIN, not a guessed simple polygon.

    Concave/disconnected intersections can contain cancelling bridge edges.
    Those are cancelled in the exact line-interval ledger below; this chain is
    never emitted as native geometry. Holes use the opposite orientation.
    """
    out = [(F(x),F(y)) for x,y in points[:-1]]
    for axis, wall, sign in ((0,bounds[0],1),(0,bounds[2],-1),(1,bounds[1],1),(1,bounds[3],-1)):
        old, out = out, []
        if not old:
            break
        a = old[-1]; ai = sign*(a[axis]-wall) >= 0
        for b in old:
            bi = sign*(b[axis]-wall) >= 0
            if ai != bi:
                t = (F(wall)-a[axis])/(b[axis]-a[axis])
                out.append((a[0]+t*(b[0]-a[0]), a[1]+t*(b[1]-a[1])))
            if bi:
                out.append(b)
            a, ai = b, bi
    return out+[out[0]] if out else []


def _boundary(rings, bounds, limits):
    """Exact signed support-line intervals, invariant to collinear subdivision."""
    ledger = defaultdict(lambda: defaultdict(int)); area2 = F(0); edge_count = 0
    for ring in rings:
        p = ring['points']
        want_positive = ring['role'] & 12 == 8
        if (source_clip.signed2(p) > 0) != want_positive:
            p = list(reversed(p))
        p = _clip_walk(p,bounds) if bounds is not None else [(F(x),F(y)) for x,y in p]
        for a,b in zip(p,p[1:]):
            if a == b:
                continue
            edge_count += 1
            need(edge_count <= limits.oracle_edges, 'EXACT_ORACLE_EDGE_WORK_BUDGET')
            need(all(max(v.numerator.bit_length(),v.denominator.bit_length()) <= limits.rational_bits
                     for v in a+b), 'EXACT_RATIONAL_WORK_BUDGET')
            area2 += a[0]*b[1]-b[0]*a[1]
            dx,dy = b[0]-a[0], b[1]-a[1]
            coeff = (dy,-dx,dx*a[1]-dy*a[0]); den = lcm(*(v.denominator for v in coeff))
            ints = [int(v*den) for v in coeff]; g = gcd(gcd(abs(ints[0]),abs(ints[1])),abs(ints[2]))
            ints = [v//g for v in ints]
            if next(v for v in ints if v) < 0:
                ints = [-v for v in ints]
            need(max(abs(v).bit_length() for v in ints) <= limits.rational_bits, 'EXACT_LINE_WORK_BUDGET')
            aa,bb = (a[0],b[0]) if dx else (a[1],b[1]); direction = 1 if bb > aa else -1
            lo,hi = min(aa,bb),max(aa,bb); events = ledger[tuple(ints)]
            events[lo] += direction; events[hi] -= direction
    result = []
    for line, events in sorted(ledger.items()):
        active = 0; previous = None; pieces = []
        for t, change in sorted(events.items()):
            if previous is not None and previous != t and active:
                if pieces and pieces[-1][1] == previous and pieces[-1][2] == active:
                    pieces[-1] = (pieces[-1][0],t,active)
                else:
                    pieces.append((previous,t,active))
            active += change; previous = t
        need(active == 0, 'CLOSED_BOUNDARY_LEDGER_REQUIRED')
        result.extend((line,a,b,n) for a,b,n in pieces)
    return tuple(result),area2


def _integral_boundary(ledger):
    # Native Root1 is U32 integer XY. Check AFTER cancelling chain bridges and
    # redundant subdivisions, before invoking the parent quantization proposer.
    for (a,b,c),lo,hi,_ in ledger:
        for t in (lo,hi):
            p = (t,-(a*t+c)/b) if b else (F(-c,a),t)
            need(all(v.denominator == 1 for v in p), 'NONINTEGER_EXACT_INTERSECTION_KEEP')


class _Rectangle(source_clip.GeometryWinner):
    def __init__(self,bounds,level):
        self.origin = bounds[:2]; self.level = level
        self.masks = {'local':box(0,0,bounds[2]-bounds[0],bounds[3]-bounds[1])}


def _projection(rings, view, *, output):
    maximum = 0; spans = []
    for ring in rings:
        exact = [(F(a*(1 << (view.level+8)),Q)-256*view.x,
                  F(b*(1 << (view.level+8)),Q)-256*view.y) for a,b in ring['points']]
        # Under2**23px, final F32 ulp<=1px; F64 arithmetic at level<=29
        # contributes <2**-14px. Reserve4px for normal rounding plus qRound,
        # independently of the nearest reference below. No FPSCR is observed.
        need(all(abs(v) < 1 << 23 for p in exact for v in p), 'SOURCE_PROJECTION_MAGNITUDE_KEEP')
        rounded = []
        for a,b in ring['points']:
            p = (f32(((a/Q)*(1 << view.level)-view.x)*256),
                 f32(((b/Q)*(1 << view.level)-view.y)*256))
            need(all(isfinite(v) for v in p), 'FINITE_NORMAL_F32_PROJECTION')
            r = tuple(qt_round(v) for v in p)
            need(all(-(1 << 31)+8 < v < (1 << 31)-8 for v in r), 'NORMAL_SIGNED32_NO_WRAP')
            rounded.append(r); maximum = max(maximum,*(abs(v) for v in p))
        span = [max(p[k] for p in rounded)-min(p[k] for p in rounded) for k in (0,1)]
        spans.append(span)
        if output:
            need(max(span) < 100000, 'OUTPUT_POLYGON_QREGION_SPAN_KEEP')
    return dict(nearestReferenceOnly=True,actualFPSCRObserved=False,
                maximumAbsoluteF32Pixels=maximum,perPolygonRoundedSpans=spans,
                conservativeProjectionAndQRoundErrorPixels=4,clampOrWrapApplied=False)


def prepare(record, indices, coordinates, render, additional, *, view,
            paint_scope=None, policy=None, expected_input_pins=None, limits=Limits()):
    """Return KEEP or an unapplied local CLIP/OUTSIDE proposal.

    ``indices`` is exactly this owner's complete standalone index frame; its
    references still address the supplied immutable CO snapshot. FI remains
    the ORIGINAL32 bytes even when its normal index pointer is nonzero. A caller
    may not use a local OUTSIDE/CLIP proposal as a global query/anchor rewrite.
    """
    original = dict(recordBytes=record,indexBytes=indices,coordinateBytes=coordinates,
                    renderBytes=render,additionalBytes=additional)
    def keep(reason):
        return dict(action='KEEP',reason=reason,original=original,proposal=None,
                    applied=False,nativePixelParityQualified=False)
    if policy != POLICY:
        return keep('DEFAULT_KEEP_EXPLICIT_OPT_IN_REQUIRED')
    try:
        need(type(limits) is Limits and all(type(v) is int and v > 0 for v in vars(limits).values()),
             'STRICT_POSITIVE_OWN_WORK_LIMITS')
        raw = (record,indices,coordinates,render,additional)
        need(all(type(v) is bytes for v in raw) and sum(map(len,raw)) <= limits.input_bytes,
             'BOUNDED_IMMUTABLE_OWNER_BUFFERS')
        need(type(record) is bytes and len(record) == 32, 'WHOLE_FEATURE32')
        need(expected_input_pins == input_pins(*raw), 'EXACT_FIVE_INPUT_PINS')
        need(type(paint_scope) is PaintScope and paint_scope.pure_ac is True and
             paint_scope.query_scope == QUERY_SCOPE, 'UNKNOWN_PROGRAMME_OR_QUERY_SCOPE_KEEP')
        need(type(paint_scope.coordinate_format) is int and paint_scope.coordinate_format == 2,
             'EXPLICIT_ROOT1_ENUM2_SCOPE')
        need(type(view) is View, 'EXPLICIT_VIEW_REQUIRED'); bounds, exact = view.rectangle()
        fields = wire.FEATURE.unpack(record)
        need(fields[6] == 3, 'NON_AREA_KIND_KEEP')
        ctx, query = model.programmes(render,fields[2])
        need(set(ctx) in ({'196608','196609'},{'196608','196609','65682'}),
             'UNKNOWN_OR_EXTRA_RENDER_CONTEXT_KEEP')
        need(all(AC.fullmatch(ctx[k]) is not None for k in ('196608','196609')),
             'NOT_EXACT_SINGLE_PURE_AC_KEEP')
        if paint_scope.anchor == 'NONE_PROVEN':
            need('65682' not in ctx, 'UNDECLARED_EXPLICIT_ANCHOR_KEEP')
        else:
            need(paint_scope.anchor == 'EXISTING_EXPLICIT_F64' and '65682' in ctx and
                 len(ctx['65682']) == 16 and all(isfinite(v) for v in struct.unpack('<dd',ctx['65682'])),
                 'IMPLICIT_OR_UNQUALIFIED_ANCHOR_KEEP')
        ao_frame = metadata.additional_frame(metadata.Stream(len(additional),lambda a,n:additional[a:a+n]),query)
        need(len(indices) >= 4, 'WHOLE_INDEX_FRAME')
        count, = struct.unpack_from('<I',indices)
        need(0 < count <= limits.source_parts and len(indices) == 4+4*count, 'SOURCE_PART_WORK_BUDGET')
        typed = []; total = 0
        for word, in struct.iter_unpack('<I',indices[4:]):
            at = word & 0xffffff; need(at+4 <= len(coordinates), 'TYPED_COORDINATE_HEADER')
            h, = struct.unpack_from('<I',coordinates,at); n = h & 0xffffff
            total += n
            need(h >> 24 == 1 and word >> 24 in (24,25,20,21), 'UNKNOWN_ROOT_ROLE_OR_MASK_KEEP')
            need(4 <= n and total <= limits.source_points and at+4+8*n <= len(coordinates),
                 'SOURCE_POINT_WORK_BUDGET')
            typed.append(dict(indexWord=word,raw=coordinates[at:at+4+8*n]))
        grouping = area_polygon_groups.classify_polygon_groups(typed,max_parts=limits.source_parts,
            max_total_typed_bytes=limits.input_bytes)
        need(grouping['status'] == 'RESOLVED_CLOSED_ROOT1_PARTS' and
             all(not g['masked'] for g in grouping['groups']), 'UNRESOLVED_SOURCE_POLYGON_GROUPING_KEEP')
        rings = ring_clip.decode_ring_owner(coordinates,indices,record,source_tile=(view.level,view.x,view.y))
        _topology(rings,limits); before_projection = _projection(rings,view,output=False)
        oracle, exact_area2 = _boundary(rings,bounds,limits)
        source_boundary, source_area2 = _boundary(rings,None,limits)
        if oracle == source_boundary and exact_area2 == source_area2:
            return keep('WHOLE_OWNER_ALREADY_WITHIN_PROTECTED_RECTANGLE')
        _integral_boundary(oracle)
        clipper = _Rectangle(bounds,view.level)
        co, ix, rec, detail = ring_clip.clip_ring_owner(coordinates,indices,record,
            cell_id='local',clipper=clipper,source_tile=(view.level,view.x,view.y))
        if rec is None:
            need(not oracle and exact_area2 == 0, 'OUTSIDE_PROPOSAL_NOT_EXACTLY_EMPTY_KEEP')
            output_rings = []; after_projection = None; action = 'OUTSIDE_LOCAL_PROPOSAL'
        else:
            need(len(co)+len(ix) <= limits.output_bytes, 'OUTPUT_BYTE_WORK_BUDGET')
            n, = struct.unpack_from('<I',ix)
            need(n <= limits.output_parts, 'OUTPUT_PART_WORK_BUDGET')
            output_rings = ring_clip.decode_ring_owner(co,ix,rec,source_tile=(view.level,view.x,view.y))
            need(sum(len(r['points']) for r in output_rings) <= limits.output_points, 'OUTPUT_POINT_WORK_BUDGET')
            _topology(output_rings,limits)
            actual_boundary, actual_area2 = _boundary(output_rings,None,limits)
            need(actual_boundary == oracle and actual_area2 == exact_area2,
                 'NONEXACT_OR_QUANTIZED_RECTANGLE_INTERSECTION_KEEP')
            need(rec[:8] == record[:8] and rec[12:] == record[12:] and rec[8:12] == bytes(4),
                 'PROPOSAL_RECORD_COMPLEMENT_CHANGED_KEEP')
            after_projection = _projection(output_rings,view,output=True); action = 'CLIP_LOCAL_PROPOSAL'
        receipt = dict(format='no5-local-pure-ac-exact-rectangle-proposal/1',policy=POLICY,
            inputPins=input_pins(*raw),view=vars(view),exactHaloRoot=[[v.numerator,v.denominator] for v in exact],
            outwardIntegerProtectedRootRectangle=list(bounds),sourceGrouping=grouping,
            inputParts=len(rings),inputPoints=total,inputHoles=sum(r['role'] & 12 == 4 for r in rings),
            outputParts=len(output_rings),outputPoints=sum(len(r['points']) for r in output_rings),
            outputHoles=sum(r['role'] & 12 == 4 for r in output_rings),
            exactIntersectionArea2=[exact_area2.numerator,exact_area2.denominator],
            exactDirectedBoundaryEqualsProtectedIntersection=True,quantizationOrRepairAdmitted=False,
            originalRecordROAOAndAnchorsByteExact=True,sourceQueryFrameSHA256=sha(ao_frame),
            originalPartRoles=[r['role'] for r in rings],proposalPartRoles=[r['role'] for r in output_rings],
            originalPartOrderAndP0RetainedInSource=True,proposalPartOrderOrP0IdentityQualified=False,
            sourceProjection=before_projection,proposalProjection=after_projection,existingClipper=detail,
            localFillGeometryOnly=True,sourceQueryGeometryRetained=True,globalQueryParity=False,
            actualSourceSelectionOrRasterQualified=False,applied=False,nativeCalls=0,
            outputCoordinateBytes=len(co),outputIndexBytes=len(ix),
            outputCoordinateSHA256=sha(co),outputIndexSHA256=sha(ix))
        return dict(action=action,reason='EXACT_INTEGER_LOCAL_INTERSECTION_PROPOSAL',original=original,
                    proposal=dict(indexBytes=ix,coordinateBytes=co),receipt=receipt,applied=False,
                    nativePixelParityQualified=False)
    except (ValueError, GEOSException) as error:
        return keep(str(error))
