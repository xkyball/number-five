"""Own tiny invented anchor-policy counterexamples; no native/chart resources.

These controls do not infer native grouping from raw ordinal0. They exercise
the explicit closedRoot1 mapping contract and KEEP everything outside it.
F32 calculations below are illustrative own models, never original execution.
"""
import hashlib
import math
import struct

import pytest

from no_chart_converter.rx5.fill_duplicates import analyze_fill_duplicates

F = struct.Struct('<Q5I4B')
POLICY = 'preserve_native_polygon0'
P0 = [(1, 0), (1, 0), (1 << 24, 0), (1, 2), (1, 1), (1, 0)]
LATER = [(100, 100), (100, 100), (200, 100), (200, 200), (100, 200), (100, 100)]


def element(points):
    return struct.pack('<I', 0x01000000 | len(points)) + b''.join(
        struct.pack('<II', *point) for point in points)


def render_frame():
    return struct.pack('<HI', 2, 0) + b''.join(
        struct.pack('<IH', key, len(body)) + body
        for key, body in ((196608, b'AC(AOWN);'), (196609, b'AC(BOWN);')))


def feature(uid=7001, io=0):
    return F.pack(uid, io, 0, 81, 23456, 42, 3, 5, 2, 99)


def bundle(coordinates, words, *, features=None, indices=None):
    if indices is None:
        indices = struct.pack('<I', len(words)) + b''.join(struct.pack('<I', w) for w in words)
    return (features or feature(), indices, coordinates, render_frame(), bytes(14))


def scene(rings, usages=None, *, storage_order=None):
    usages = usages or [24] * len(rings)
    co = bytearray(); offsets = {}
    for ordinal in storage_order or list(range(len(rings))):
        offsets[ordinal] = len(co)
        co += element(rings[ordinal])
    return bundle(bytes(co), [u << 24 | offsets[i] for i, u in enumerate(usages)])


def analyse(value, policy=POLICY):
    f, i, c, r, a = value
    return analyze_fill_duplicates(f, i, c, r, additional=a, anchor_policy=policy)


def edges(points):
    return [(a, b) for a, b in zip(points, points[1:]) if a != b]


def f32(value):
    return struct.unpack('<f', struct.pack('<f', value))[0]


def illustrative_mean(points):
    total = [0.0, 0.0]
    for point in points[:-1]:
        total = [f32(total[k] + f32(point[k])) for k in (0, 1)]
    return tuple(f32(v / f32(len(points) - 1)) for v in total)


def no_operative_proof(result):
    assert result['analyzeOnly'] is True
    assert result['anchorPolicy'] == POLICY
    for key in ('operativeApplyAllowed', 'materializationApproved',
                'nativeQueryEquivalenceApproved', 'nativeAnchorFallbackEquivalenceApproved',
                'nativeCPUInstructionsMeasured', 'originalNativeBinaryUsed',
                'wholeSourceAliasSafetyApproved', 'coordinateOrIndexOrMetadataBuffersReturned'):
        assert result[key] is False
    assert result['nativeQueryDisposition'] == 'UNPROVEN'
    assert result['hypotheticalDecoderWork']['measured'] is False


def owner_of(result, uid=7001):
    return next(o for o in result['owners'] if o['uid'] == str(uid))


def proposed(part, points):
    keep = part['keptVertexOrdinals']
    assert keep == sorted(set(keep))
    assert keep[0] == 0 and keep[-1] == len(points) - 1
    output = [points[i] for i in keep]
    assert edges(points) == edges(output)
    assert part['sourceTypedElementSHA256'] == hashlib.sha256(element(points)).hexdigest()
    assert part['proposedTypedElementSHA256'] == hashlib.sha256(element(output)).hexdigest()
    return output


def protected(part, points, expected_class):
    assert part['anchorClassification'] == expected_class
    assert part['keptVertexOrdinals'] == list(range(len(points)))
    assert part['removedVertexOrdinals'] == []
    assert proposed(part, points) == points


@pytest.mark.parametrize('first_usage', [4, 5, 8, 9, 16, 17, 20, 21, 24, 25])
def test_first_polygon_is_logical_first_even_when_mask_hole_or_reversed(first_usage):
    value = scene([P0, LATER], [first_usage, 24])
    result = analyse(value); owner = owner_of(result)
    assert owner['polygonMappingStatus'] == 'RESOLVED_CLOSED_ROOT1_PARTS'
    assert owner['polygon0PartOrdinals'] == [0]
    assert owner['anchorInputDisposition'] == 'ORIGINAL_POLYGON0_INPUT_ORDER_FLAGS_RETAINED'
    assert owner['sourceRecord32Hex'] == value[0].hex()
    first, later = owner['parts']
    assert first['usage'] == first_usage and later['usage'] == 24
    assert (first['decodedPolygonOrdinal'], later['decodedPolygonOrdinal']) == (0, 1)
    protected(first, P0, 'PRESERVED_DECODED_POLYGON0')
    assert first['blockedDuplicateVertexOrdinals']
    assert later['anchorClassification'] == 'LATER_DECODED_POLYGON_CANDIDATE'
    target = proposed(later, LATER)
    assert len(target) < len(LATER)
    assert illustrative_mean(P0) == illustrative_mean(proposed(first, P0))
    witness, = owner['protectedPolygon0Inputs']
    assert witness['indexWord'] == first['indexWord']
    assert witness['sourceTypedElementSHA256'] == hashlib.sha256(element(P0)).hexdigest()
    no_operative_proof(result)


def test_physical_arena_order_does_not_reorder_native_polygon_inputs():
    result = analyse(scene([P0, LATER], storage_order=[1, 0]))
    first, later = owner_of(result)['parts']
    assert first['coordinateOffset'] > later['coordinateOffset']
    protected(first, P0, 'PRESERVED_DECODED_POLYGON0')
    assert len(proposed(later, LATER)) < len(LATER)
    no_operative_proof(result)


def test_exact_alias_to_first_span_is_kept_without_a_clone_approval():
    raw = element(P0) + element(LATER)
    value = bundle(raw, [8 << 24, 25 << 24, 24 << 24 | len(element(P0))])
    result = analyse(value); a, alias, independent = owner_of(result)['parts']
    protected(a, P0, 'PRESERVED_DECODED_POLYGON0')
    protected(alias, P0, 'PROTECTED_POLYGON0_SPAN_ALIAS')
    assert alias['blockedDuplicateVertexOrdinals']
    assert len(proposed(independent, LATER)) < len(LATER)
    no_operative_proof(result)


def test_partial_typed_span_overlap_is_protected_beyond_equal_offsets():
    # Both contours are valid, closed and Root1. The later header lives at the
    # first polygon's D.x field; its first16bytes overlap the anchor's tail.
    anchor = [(100, 200), (50, 100), (50, 3000000), (0x01000006, 4000000), (100, 200)]
    alias = [(4000000, 100), (200, 100), (200, 100), (200, 200), (4000000, 200), (4000000, 100)]
    raw = element(anchor) + struct.pack('<9I', 100, 200, 100, 200, 200, 4000000, 200, 4000000, 100)
    assert raw[28:80] == element(alias)
    raw += bytes(16)
    offset = len(raw); raw += element(LATER)
    value = bundle(raw, [24 << 24, 24 << 24 | 28, 24 << 24 | offset])
    result = analyse(value); first, overlap, independent = owner_of(result)['parts']
    protected(first, anchor, 'PRESERVED_DECODED_POLYGON0')
    protected(overlap, alias, 'PROTECTED_POLYGON0_SPAN_ALIAS')
    assert overlap['coordinateOffset'] != first['coordinateOffset']
    assert len(proposed(independent, LATER)) < len(LATER)
    no_operative_proof(result)


@pytest.mark.parametrize('reverse_owners', [False, True])
def test_cross_owner_alias_protection_is_independent_of_processing_order(reverse_owners):
    raw = element(P0) + element(LATER)
    another = [(500, 500), (500, 500), (600, 500), (600, 600), (500, 600), (500, 500)]
    other_offset = len(raw); raw += element(another)
    b_offset = len(element(P0))
    indices = struct.pack('<4I', 3, 24 << 24, 24 << 24 | b_offset, 24 << 24 | other_offset)
    indices += struct.pack('<2I', 1, 24 << 24 | b_offset)
    records = [feature(7001, 0), feature(7002, 16)]
    value = bundle(raw, [], features=b''.join(records[::-1] if reverse_owners else records), indices=indices)
    result = analyse(value)
    assert result['protectedAliasScope'] == 'ALL_RESOLVED_ELIGIBLE_OWNERS_IN_THIS_ANALYSIS'
    a, b = owner_of(result, 7001), owner_of(result, 7002)
    protected(a['parts'][1], LATER, 'PROTECTED_POLYGON0_SPAN_ALIAS')
    protected(b['parts'][0], LATER, 'PRESERVED_DECODED_POLYGON0')
    assert len(proposed(a['parts'][2], another)) < len(another)
    no_operative_proof(result)


def test_open_join_countermodel_does_not_turn_raw_part0_into_a_native_oracle():
    first = [(0, 0), (4, 0), (8, 0), (8, 2)]
    second = [(8, 2), (8, 2), (8, 8), (0, 8), (0, 0)]
    # A possible endpoint-join model consumes both raw parts for polygon0.
    # Keeping only raw part0 would not protect a mean over that combined input.
    # This countermodel is not an emulation of the unavailable native routine.
    assert first[0] == second[-1] and first[-1] != first[0]
    assert illustrative_mean(first + second) != illustrative_mean(first + second[1:])
    result = analyse(scene([first, second, LATER]))
    owner = owner_of(result)
    assert owner['disposition'] == 'KEEP'
    assert owner['polygonMappingStatus'] == 'NOT_RESOLVED_WHOLE_OWNER_UNCHANGED'
    for part in owner['parts']:
        assert not part.get('removedVertexOrdinals')
    no_operative_proof(result)


def test_reversing_the_same_closed_ring_can_change_a_finite_f32_mean():
    ring = [(1, 0), (1 << 24, 0), (1, 2), (1, 1), (1, 0)]
    assert set(ring) == set(ring[::-1]) and ring[0] == ring[::-1][0]
    assert illustrative_mean(ring) == (4194304.0, .75)
    assert illustrative_mean(ring[::-1]) == (4194305.0, .75)
    for direction in (ring, ring[::-1]):
        result = analyse(scene([direction, LATER], [25, 24]))
        protected(owner_of(result)['parts'][0], direction, 'PRESERVED_DECODED_POLYGON0')
        no_operative_proof(result)


def test_unsigned_root_limits_do_not_authorize_equal_f32_vertex_simplification():
    top = (1 << 32) - 1
    first = [(top-32, 100), (top-32, 100), (top, 100), (top, 200), (top-32, 200), (top-32, 100)]
    later = [(1 << 24, 0), ((1 << 24)+1, 0), ((1 << 24)+2, 0),
             ((1 << 24)+100, 0), ((1 << 24)+100, 100), (1 << 24, 100), (1 << 24, 0)]
    assert f32(later[0][0]) == f32(later[1][0])
    result = analyse(scene([first, later])); a, b = owner_of(result)['parts']
    protected(a, first, 'PRESERVED_DECODED_POLYGON0')
    assert b['removedVertexOrdinals'] == [] and proposed(b, later) == later
    assert all(math.isfinite(v) for v in illustrative_mean(first))
    no_operative_proof(result)


def test_empty_or_unknown_nonfinite_encoding_is_unresolved_and_kept():
    values = [bundle(b'', []), bundle(struct.pack('<I', 0x02000004) + struct.pack('<8d', *([float('nan'), float('inf')]*4)), [24 << 24])]
    for value in values:
        result = analyse(value); owner = owner_of(result)
        assert owner['disposition'] == 'KEEP'
        assert owner['polygonMappingStatus'] == 'NOT_RESOLVED_WHOLE_OWNER_UNCHANGED'
        no_operative_proof(result)


@pytest.mark.parametrize('bad', [True, 1, {}, 'APPROVED_NATIVE', ''])
def test_policy_boolean_or_receipt_like_values_cannot_grant_proof(bad):
    with pytest.raises(ValueError):
        analyse(scene([P0, LATER]), bad)


@pytest.mark.parametrize('at', [0, 1, 2, 3])
def test_malformed_frame_bounds_refuse_without_partial_anchor_approval(at):
    value = list(scene([P0, LATER])); value[at] = value[at][:-1]
    with pytest.raises(ValueError):
        analyse(value)


def test_explicit_legacy_analysis_policy_matches_default_without_changing_v34():
    value = scene([P0, LATER]); f, i, c, r, a = value
    assert analyze_fill_duplicates(f, i, c, r, additional=a) == analyse(value, 'analyze_all')
