"""Own opt-in L21 regular geometry clip for a completely covered tile halo.

This is a geographic correction, not preservation of an overflowing Original
QRegion. No chart/tree or provider is accessed. The existing ring clipper emits
normal Root1 geometry; there is no invented RO geometry field or duplicate UID.
"""
from fractions import Fraction as F
import hashlib
import math
import re
import struct

from shapely.geometry import box
from no_chart_converter.country_runtime import ring_clip, source_clip
from no_chart_converter.rx5 import header, metadata, model, wire
from no_chart_converter.rx5.locality_empty import Domain
from no_chart_converter.rx5.integer_region_certificate import ring_integer_domain

ROOT = 2147483649
NAMES = ('features', 'indices', 'coordinates', 'render', 'additional')
OUTPUTS = ('featureBytes', 'indexBytes', 'coordinateBytes', 'renderBytes', 'additionalBytes')
POLICY = 'EXPLICIT_EXACT_L21_FULL_COVER_AREA_GEOMETRY_CLIP/1'
SOURCE_CONTRACTS = {
    'nativeRaster256Identity': '2487b1dbed00731bab992ea2fe69b999a3f85056ac4b404942967049c69d414e',
    'qRound': '5fde31db68cd2555f1ea52d12c2f14236f443845774281b690a8e1f77a8ba600',
    'qRegionEdges': 'f30b7abf0fa9f2feee9585cfe7cd728d56494c62f9c9e072780a17c5e3762aea',
    'qRegionUnion': 'e18498199ed27546a0d19a1ff9e042fc044c4a5697942c4053ac36868b8e3583',
    'sharedFileGeometry': 'f0a7a787710b43aebbf2954ad9afe53eb4ca5827d420793366fecdae9a6c99f1',
    'explicitAnchorPriority': 'ae763bde1d56ca3bc7b269e8d5812328b7b6944abcd89d11cc815c301578a1f0',
}
need = wire.need


def sha(raw):
    return hashlib.sha256(raw).hexdigest()


def pins(buffers):
    return {k: dict(bytes=len(v), sha256=sha(v)) for k, v in zip(NAMES, buffers)}


def _rat(q):
    return [q.numerator, q.denominator]


def _ceil(q):
    return -((-q.numerator) // q.denominator)


def root_window(source_tile):
    """Smallest integer rectangle enclosing the true tile+128px halo."""
    need(type(source_tile) in (tuple, list) and len(source_tile) == 3 and
         all(type(v) is int for v in source_tile), 'EXPLICIT_TILE')
    z, x, y = source_tile
    need(z == 21 and 0 <= x < 1 << z and 0 <= y < 1 << z, 'EXACT_LEVEL21_ONLY')
    den = 256 * (1 << z)
    q = (F((256*x-128)*ROOT, den), F((256*y-128)*ROOT, den),
         F((256*(x+1)+128)*ROOT, den), F((256*(y+1)+128)*ROOT, den))
    b = (q[0].numerator//q[0].denominator, q[1].numerator//q[1].denominator,
         _ceil(q[2]), _ceil(q[3]))
    need(0 <= b[0] < b[2] <= ROOT and 0 <= b[1] < b[3] <= ROOT,
         'COMPLETE_HALO_INSIDE_ROOT_DOMAIN')
    return b, q


class _WindowClipper(source_clip.GeometryWinner):
    """Reuse only clipping/quantization; this window is not chart coverage."""
    def __init__(self, bounds):
        self.origin = bounds[:2]
        self.level = 21
        self.masks = {'window': box(0, 0, bounds[2]-bounds[0], bounds[3]-bounds[1])}


def _coverage(rings, bounds):
    # Exact integer crossing/winding precondition, independently of GEOS.
    domain = Domain(1, *bounds)
    rows = []
    query_sum = 0
    seen_interior = False
    for i, ring in enumerate(rings):
        exterior = ring['role'] & 12 == 8
        need(not (seen_interior and exterior), 'EXTERIOR_BEFORE_INTERIOR_REQUIRED')
        seen_interior |= not exterior
        pts = ring['points']
        need(not any(domain.hits(a, b) for a, b in zip(pts, pts[1:])),
             'PILOT_REQUIRES_NO_ORIGINAL_BOUNDARY_INSIDE_HALO')
        winding = domain.winding(pts)
        need(abs(winding) <= 1, 'SIMPLE_ODDEVEN_RING_REQUIRED')
        covers = winding != 0
        query_sum += (1 if exterior else -1) if covers else 0
        rows.append(dict(partOrdinal=i, usage=ring['role'], points=len(pts),
                         exactWinding=winding, coversWholeWindow=covers,
                         boundaryDisjointFromClosedWindow=True))
    # Area.distanceToPoint tests EXACT1, not unionContains. Do not normalize
    # overlapping exteriors whose native containment sum differs from one.
    need(query_sum == 1, 'ORIGINAL_LOCAL_CONTAINMENT_SUM_MUST_EQUAL_ONE')
    return rows


def _rectangle_projection(points, bounds, source_tile):
    need(len(points) == 5 and points[0] == points[-1] and
         set(points[:-1]) == {(bounds[0], bounds[1]), (bounds[2], bounds[1]),
                             (bounds[2], bounds[3]), (bounds[0], bounds[3])},
         'EXACT_TRUE_CLIP_RECTANGLE_REQUIRED')
    z, x, y = source_tile
    projected = [(F(a*(1 << (z+8)), ROOT)-x*256,
                  F(b*(1 << (z+8)), ROOT)-y*256) for a, b in points]
    need(max(abs(v) for p in projected for v in p) < 4096,
         'LOCAL_PROJECTION_MAGNITUDE_BELOW_4096')
    lo = [min(p[a] for p in projected) for a in (0, 1)]
    hi = [max(p[a] for p in projected) for a in (0, 1)]
    # Root integers are exact F64. Division/scale/subtract/multiply at z21
    # have <2**-20 pixel total error even under normal directed rounding.
    # Final F32 and qRound local arithmetic below4096 have <1px aggregate
    # error; reserve2px including truncation. There is no S32 wrap branch.
    need(all(v+2 < 0 for v in lo) and all(v-2-1 >= 255 for v in hi),
         'ROUNDED_RECTANGLE_MUST_COVER_ALL_256_PIXELS')
    proof = ring_integer_domain(points, tuple(source_tile), 21)
    need(proof is not None and proof['mode'] == 'NORMAL_SIGNED_QROUND',
         'NORMAL_OUTPUT_INTEGER_REGION_REQUIRED')
    need(max(proof['coherentRingSpanBounds']) < 100000,
         'OUTPUT_FAR_BELOW_GENERIC_QREGION_SPAN_CAP')
    return dict(exactProjectedPoints=[[_rat(a), _rat(b)] for a, b in projected],
                normalModeProjectionAndQRoundErrorPixels=2,
                allRoundedRectangleEdgesOutsideOriginalTile=True,
                rightBottomMinusOneIncluded=True, finiteNormalS32Branch=True,
                rectangleFourFivePointPathExplicit=True, integerOperandProof=proof,
                nativePixelClaim=False)


def prepare_scoped_area_tile_clip(features, indices, coordinates, render, additional, *,
        source_tile, owner_uids, policy=None, expected_input_sha256,
        source_header, expected_header_sha256, source_file_bytes):
    """Append genuine I/C fragments, update selected FI+8, leave RO/AO exact.

    This first pilot refuses boundaries or holes touching its halo. It proves
    true full-owner/window intersection, not arbitrary clipping pixel parity.
    Every selected UID remains once, in its original FI ordinal. Ancestors must
    stay unchanged and all4 next-level children must restore the original scene.
    """
    need(policy == POLICY, 'EXPLICIT_CORRECTION_POLICY_REQUIRED_DEFAULT_OFF')
    original = (features, indices, coordinates, render, additional)
    need(all(type(v) is bytes for v in original), 'IMMUTABLE_FIVE_BUFFERS')
    ppins = pins(original)
    need(type(expected_input_sha256) is dict and set(expected_input_sha256) == set(NAMES)
         and all(expected_input_sha256[k] == ppins[k]['sha256'] for k in NAMES), 'FIVE_SOURCE_SHA_BINDING')
    need(type(source_header) is bytes and sha(source_header) == expected_header_sha256,
         'EXACT_SOURCE_HEADER_SHA')
    hc = header.validate_plain_vector_header(source_header, file_bytes=source_file_bytes)
    bounds, rational = root_window(source_tile)
    need(len(features) % 32 == 0, 'WHOLE_FEATURE32')
    need(type(owner_uids) in (list, tuple) and 1 <= len(owner_uids) <= 2 and
         all(type(u) is int and 0 <= u < 1 << 64 for u in owner_uids) and
         len(set(owner_uids)) == len(owner_uids), 'ONE_OR_TWO_EXPLICIT_DISTINCT_UIDS')
    clipper = _WindowClipper(bounds)
    fi, ix, co = bytearray(features), bytearray(indices), bytearray(coordinates)
    rows = []
    for uid in owner_uids:
        ordinals = [i for i in range(len(features)//32) if wire.FEATURE.unpack_from(features, i*32)[0] == uid]
        need(len(ordinals) == 1, 'ONE_CANONICAL_OWNER_PER_UID')
        ordinal = ordinals[0]
        record = features[ordinal*32:(ordinal+1)*32]
        fields = wire.FEATURE.unpack(record)
        need(fields[6] == 3, 'KIND3_ONLY')
        contexts, query = model.programmes(render, fields[2])
        explicit_anchor = contexts.get('65682')
        if explicit_anchor is not None:
            need(len(explicit_anchor) == 16 and
                 all(math.isfinite(v) for v in struct.unpack('<dd', explicit_anchor)),
                 'FINITE_F64_EXPLICIT_ANCHOR_CONTEXT')
        for key in ('196608', '196609'):
            value = contexts.get(key, b'')
            need(value and re.fullmatch(rb"AC\([A-Z0-9_]+\);(?:TX\([^;\x00]*\);)?", value),
                 'TWO_AC_OPTIONAL_TX_PROGRAMMES_REQUIRED')
        need(query < len(additional), 'LIVE_QUERY_AO_REFERENCE')
        areader = metadata.Stream(len(additional), lambda a, n: additional[a:a+n])
        metadata.additional_frame(areader, query)
        ireader = metadata.Stream(len(indices), lambda a, n: indices[a:a+n])
        frame = metadata.index_frame(ireader, fields[1])
        rings = ring_clip.decode_ring_owner(coordinates, frame, record, source_tile=source_tile)
        need(all(r['rootFlags'] == 1 for r in rings), 'ROOT1_ONLY')
        cover = _coverage(rings, bounds)
        whole = ring_clip.whole_ring_geometry(rings, clipper)
        need(whole.covers(clipper.masks['window']), 'WHOLE_OWNER_COVERS_CLIP_WINDOW')
        local_co, local_ix, clipped_record, clip_receipt = ring_clip.clip_ring_owner(
            coordinates, frame, record, cell_id='window', clipper=clipper,
            source_tile=source_tile)
        need(clipped_record is not None, 'NONEMPTY_TRUE_INTERSECTION_REQUIRED')
        output_rings = ring_clip.decode_ring_owner(local_co, local_ix, clipped_record, source_tile=source_tile)
        need(len(output_rings) == 1 and output_rings[0]['role'] & 12 == 8,
             'ONE_EXTERIOR_TRUE_CLIP_RESULT_REQUIRED')
        projection = _rectangle_projection(output_rings[0]['points'], bounds, source_tile)
        base = len(co)
        need(base + len(local_co) < 1 << 24, 'APPENDED_COORDINATE_LOW24')
        rebased = bytearray(local_ix)
        for at in range(4, len(rebased), 4):
            word = struct.unpack_from('<I', rebased, at)[0]
            struct.pack_into('<I', rebased, at, (word & 0xff000000) | (base + (word & 0xffffff)))
        new_io = len(ix)
        need(new_io + len(rebased) <= 0xffffffff, 'APPENDED_INDEX_U32')
        co += local_co
        ix += rebased
        struct.pack_into('<I', fi, ordinal*32+8, new_io)
        rows.append(dict(uid=uid, recordOrdinal=ordinal, oldIndexOffset=fields[1], newIndexOffset=new_io,
                         originalRecordSHA256=sha(record), originalParts=len(rings),
                         originalXY=sum(len(r['points']) for r in rings), outputParts=1, outputXY=5,
                         coverage=cover, originalLocalContainmentSum=1,
                         hasTXProgramme=any(b'TX(' in contexts[k] for k in ('196608', '196609')),
                         explicitF64AnchorPin=None if explicit_anchor is None else
                            dict(bytes=16, sha256=sha(explicit_anchor)),
                         explicitF32AnchorContextPresent='65680' in contexts,
                         explicitAnchorContextsByteExact=True,
                         geometryDerivedFallbackAnchorMayChange=explicit_anchor is None,
                         outputRootPoints=[list(p) for p in output_rings[0]['points']],
                         trueIntersectionEqualsIntegerHaloWindow=True, clipping=clip_receipt,
                         rectangleProjection=projection))
    output = (bytes(fi), bytes(ix), bytes(co), render, additional)
    restored = bytearray(output[0])
    for row in rows:
        at = row['recordOrdinal']*32+8
        restored[at:at+4] = features[at:at+4]
    need(bytes(restored) == features and output[1][:len(indices)] == indices and
         output[2][:len(coordinates)] == coordinates, 'EXACT_PREFIX_AND_ONLY_SELECTED_FI_IO_WORDS')
    for row in rows:
        actual = list(model.parts(output[0][row['recordOrdinal']*32:(row['recordOrdinal']+1)*32], output[1], output[2]))
        need(len(actual) == 1 and actual[0]['rootFlag'] == 1 and actual[0]['pointCount'] == 5,
             'REGULAR_NATIVE_FI_I_C_READBACK')
    z, x, y = source_tile
    receipt = dict(format='no5-exact21-full-cover-area-geometry-clip/1', policy=POLICY,
        sourceTile=list(source_tile), exactViewLevel=21, haloPixels=128,
        trueRootHaloBounds=[_rat(v) for v in rational], integerOutwardRootBounds=list(bounds),
        inputPins=ppins, outputPins=pins(output), sourceHeaderPin=dict(bytes=len(source_header), sha256=sha(source_header)),
        sourceFileBytes=source_file_bytes, headerContract=hc, owners=rows,
        selectedFIIndexWordBytes=4*len(rows), appendedIndexBytes=len(output[1])-len(indices),
        appendedCoordinateBytes=len(output[2])-len(coordinates), renderAndAdditionalByteExact=True,
        allUIDsClassesScalesFlagsOwnerOrderExact=True, duplicateUIDAdded=False,
        sharedDrawAndQueryGeometryIntentionallyChanged=True,
        localRootContainmentWithinTileAndHaloExact=True,
        outsideHaloBoundaryDistanceAndFallbackCentroidMayChange=True,
        explicitAnchorContextsByteExact=True,
        unchangedTXProgrammesDoNotProveTextPositionParity=True,
        textPositionAndSymbolOutcomeComparisonPending=True,
        sourceProgrammesAndAttributesRetained=True, globalQueryEqualityClaim=False,
        originalBuggyTransparentImageParityClaim=False, nativePixelProof=False,
        correctedGeographicFillPendingNativeImageCheck=True, globalChartApplyAllowed=False,
        defaultPipelineChanged=False, sidecarEnforcesScope=False,
        canonicalInputsMustRemainAvailable=True, existingAncestorsMustRemainUnchanged=True,
        requiresActualFourFullRestoreChildrenLevel22=True,
        requiredRestoreChildren=[dict(slot=s, tile=[22, 2*x+(s&1), 2*y+(s>>1)], flags=3,
            originalGeometryAndProgrammesForAllOwnersRequired=True) for s in range(4)],
        actualTreeRestoreAndNormalSelectionBindingPending=True,
        sourceContracts=SOURCE_CONTRACTS, OriginalCalls=0, wholeChartBuilds=0)
    return dict(zip(OUTPUTS, output), receipt=receipt)
