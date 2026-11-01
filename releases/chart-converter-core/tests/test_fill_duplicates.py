"""Small invented buffers only; no chart, private library, or device inputs."""
import hashlib
import json
import os
import struct

import pytest

from no_chart_converter.rx5.fill_duplicates import analyze_fill_duplicates
from no_chart_converter.rx5.metadata import Stream
from no_chart_converter.rx5.wire import FEATURE

RING = [(10, 10), (10, 10), (20, 10), (20, 10), (20, 20), (10, 20), (10, 10), (10, 10)]


def fixture(rings=None, *, kind=3, root=1, usage=24, contexts=None):
    rings = [RING] if rings is None else rings
    coordinates, words = bytearray(), []
    for points in rings:
        words.append((usage << 24) | len(coordinates))
        coordinates += struct.pack('<I', (root << 24) | len(points))
        for x, y in points:
            coordinates += struct.pack('<II', x, y)
    indices = struct.pack('<I', len(words)) + b''.join(struct.pack('<I', word) for word in words)
    contexts = [(196608, b'AC(TEST);'), (196609, b'AC(TEST)')] if contexts is None else contexts
    render = struct.pack('<HI', len(contexts), 0) + b''.join(
        struct.pack('<IH', key, len(body)) + body for key, body in contexts)
    additional = struct.pack('<QHHH', 4242, 0, 0, 0)
    record = FEATURE.pack(4242, 0, 0, 12, 18, 42, kind, 7, 8, 9)
    return record, indices, bytes(coordinates), render, additional


def analyze(args):
    return analyze_fill_duplicates(*args[:4], additional=args[4])


def test_exact_subsequence_preserves_first_final_closure_and_signed_area():
    args = fixture()
    before = tuple(args)
    out = analyze(args)
    owner, = out['owners']
    part, = owner['parts']
    assert owner['disposition'] == 'PROPOSED_ADJACENT_DUPLICATE_SUBSEQUENCE'
    assert part['keptVertexOrdinals'] == [0, 2, 4, 5, 7]
    assert part['removedVertexOrdinals'] == [1, 3, 6]
    new = struct.pack('<I', 0x01000005) + b''.join(
        args[2][4+8*i:12+8*i] for i in part['keptVertexOrdinals'])
    assert part['sourceTypedElementSHA256'] == hashlib.sha256(args[2]).hexdigest()
    assert part['proposedTypedElementSHA256'] == hashlib.sha256(new).hexdigest()
    assert part['directedNonzeroEdgesSHA256'] == part['proposedDirectedNonzeroEdgesSHA256']
    assert part['bboxRootU32'] == [10, 10, 20, 20]
    assert part['signedAreaTwiceRootUnits'] == 200
    assert out['totals']['adjacentPointOccurrencesRemoved'] == 3
    assert out['totals']['partContainersRemoved'] == 0
    assert out['materializationApproved'] is out['nativeQueryEquivalenceApproved'] is False
    assert tuple(args) == before
    json.dumps(out)


@pytest.mark.parametrize('usage', [4, 5, 8, 9, 16, 17, 20, 21, 24, 25])
def test_hole_mask_visible_and_reversal_flags_remain_exact(usage):
    args = fixture([RING, list(reversed(RING))], usage=usage)
    out = analyze(args)
    for part in out['owners'][0]['parts']:
        assert part['usage'] == usage
        assert part['indexWord'] >> 24 == usage
        assert part['roleUsageReversalUnchanged'] is True
    a, b = out['owners'][0]['parts']
    assert a['signedAreaTwiceRootUnits'] == -b['signedAreaTwiceRootUnits']


@pytest.mark.parametrize('kind', [1, 2, 4, 9])
def test_excluded_kind_reads_no_stream_ranges(kind):
    args = fixture(kind=kind)
    out = analyze(args)
    assert out['owners'][0]['reason'] == 'NOT_KIND3_AREA'
    assert all(pin['readCalls'] == 0 for pin in out['inputStreams'].values())
    assert out['totals']['adjacentPointOccurrencesRemoved'] == 0


@pytest.mark.parametrize('contexts', [
    [], [(196608, b'')], [(196608, b'AC(TEST);;')],
    [(196608, b'AC(TEST);LS(SOLD,1,TEST)')], [(196608, b'AP(TEST)')],
    [(196608, b'SY(TEST)')], [(196608, b'TX(TEST)')], [(196608, b'TE(TEST)')],
    [(196608, b'LC(TEST)')], [(196608, b'CS(TEST)')],
    [(65682, struct.pack('<dd', 0.5, 0.5))], [(65680, struct.pack('<ff', 0.5, 0.5))],
    [(999, b'AC(TEST)')], [(196608, b'AC(TEST)'), (196608, b'AC(TEST)')],
    [(196608, b'AC(TEST)'), (65682, struct.pack('<dd', 0.5, 0.5))],
])
def test_unsafe_contexts_keep_whole_owner_without_geometry_read(contexts):
    args = fixture(contexts=contexts)
    if len(contexts) > 1 and contexts[0][0] == contexts[1][0]:
        with pytest.raises(ValueError, match='DUPLICATE_RENDER_KEY'):
            analyze(args)
        return
    out = analyze(args)
    assert out['owners'][0]['disposition'] == 'KEEP'
    assert out['inputStreams']['coordinates']['readCalls'] == 0
    assert out['inputStreams']['indices']['readCalls'] == 0
    assert out['totals']['adjacentPointOccurrencesRemoved'] == 0


@pytest.mark.parametrize('points', [
    [(0, 0), (1, 0), (1, 1)],
    [(0, 0), (1, 0), (1, 1), (0, 1)],  # Open.
    [(0, 0)]*8,
    [(0, 0), (1, 1), (2, 2), (0, 0)],  # Zero area.
    [(0, 0), (3, 3), (0, 3), (3, 0), (0, 0)],  # Self-crossing.
])
def test_unsafe_part_retains_entire_owner_and_revokes_other_part_proposals(points):
    out = analyze(fixture([RING, points]))
    owner, = out['owners']
    assert owner['reason'] == 'UNSUPPORTED_PART_WHOLE_OWNER_KEEP'
    assert all('removedVertexOrdinals' not in part for part in owner['parts'])
    assert owner['pointOccurrencesBefore'] == owner['pointOccurrencesAfter']
    assert out['totals']['adjacentPointOccurrencesRemoved'] == 0


@pytest.mark.parametrize('kwargs', [{'root': 0}, {'root': 2}, {'usage': 255}])
def test_unknown_root_or_usage_is_preserved(kwargs):
    out = analyze(fixture(**kwargs))
    assert out['owners'][0]['disposition'] == 'KEEP'
    assert out['totals']['adjacentPointOccurrencesRemoved'] == 0


def test_nonadjacent_repeated_vertex_is_not_a_simplification_candidate():
    # A valid ring without stutters retains every original ordinate, including
    # collinear vertices that a generic simplifier might otherwise remove.
    ring = [(0, 0), (5, 0), (10, 0), (10, 10), (0, 10), (0, 0)]
    out = analyze(fixture([ring]))
    assert out['owners'][0]['reason'] == 'NO_ADJACENT_DUPLICATES'
    assert out['owners'][0]['parts'][0]['keptVertexOrdinals'] == list(range(len(ring)))


def test_repeated_alias_occurrences_are_not_counted_as_unique_physical_savings():
    f, i, c, r, a = fixture()
    f += FEATURE.pack(4243, 0, 0, 12, 18, 42, 3, 7, 8, 9)
    out = analyze((f, i, c, r, a))
    assert out['totals']['adjacentPointOccurrencesRemoved'] == 6
    assert out['observedEligibleTypedSpans'][0]['occurrences'] == 2
    assert out['wholeSourceAliasSafetyApproved'] is False


def test_real_fd_range_adapter_matches_immutable_buffers(tmp_path):
    args = fixture()
    fds, streams = [], []
    try:
        for name, body in zip(('i', 'c', 'r', 'a'), args[1:]):
            path = tmp_path/name
            path.write_bytes(body)
            fd = os.open(path, os.O_RDONLY)
            fds.append(fd)
            streams.append(Stream(len(body), lambda at, n, fd=fd: os.pread(fd, n, at)))
        out = analyze_fill_duplicates(args[0], *streams[:3], additional=streams[3])
        expected = analyze(args)
        assert out['owners'] == expected['owners']
        assert out['totals'] == expected['totals']
        assert all(pin['wholeBufferSHA256'] is None for pin in out['inputStreams'].values())
        assert all(pin['readCalls'] for pin in out['inputStreams'].values())
    finally:
        for fd in fds:
            os.close(fd)


@pytest.mark.parametrize('target', [0, 1, 2, 3, 4])
def test_malformed_relevant_wire_is_refused(target):
    args = list(fixture())
    args[target] = args[target][:-1]
    with pytest.raises(ValueError):
        analyze(args)


def test_analysis_boundary_does_not_claim_weighted_centroid_query_identity():
    # Ordinary polygon vertex means weight stutters; the source and proposed
    # means differ even though nonzero directed edges and exact area coincide.
    args = fixture()
    out = analyze(args)
    keep = out['owners'][0]['parts'][0]['keptVertexOrdinals']
    old_mean = tuple(sum(p[axis] for p in RING[:-1]) / (len(RING)-1) for axis in (0, 1))
    new = [RING[i] for i in keep]
    new_mean = tuple(sum(p[axis] for p in new[:-1]) / (len(new)-1) for axis in (0, 1))
    assert old_mean != new_mean
    assert out['nativeQueryDisposition'] == 'UNPROVEN'
    assert out['originalCentroidPreservation'] == 'UNPROVEN_DUPLICATE_WEIGHTED_F32_MEAN'
    assert out['operativeApplyAllowed'] is False
    assert out['materializationApproved'] is False
    assert out['coordinateOrIndexOrMetadataBuffersReturned'] is False


@pytest.mark.parametrize('key', [196608, 196609])
def test_both_paper_and_simple_contexts_are_required(key):
    out = analyze(fixture(contexts=[(key, b'AC(TEST);')]))
    assert out['owners'][0]['reason'] == 'BOTH_PAPER_AND_SIMPLE_AC_CONTEXTS_REQUIRED'
    assert out['inputStreams']['coordinates']['readCalls'] == 0


def test_hypothetical_work_formula_does_not_claim_a_native_measurement():
    out = analyze(fixture())
    model = out['hypotheticalDecoderWork']
    assert model['getXCallsBefore'] == model['getYCallsBefore'] == 2*len(RING)+2
    assert model['getXCallsAfter'] == model['getYCallsAfter'] == 2*5+2
    assert model['partContainersBeforeAndAfter'] == 1
    assert model['measured'] is False
    assert out['actualCountryDuplicateCountAvailable'] is False
    assert out['originalNativeBinaryUsed'] is False


def test_root0_u16_short_body_keeps_owner_without_guessing_u32_stride():
    record, indices, _, render, additional = fixture()
    # Four packed U16 pairs are a complete source-specific Root0 payload, but
    # would be too short for the incorrect generic U32-body interpretation.
    coordinates = struct.pack('<I8H', 4, 1, 1, 2, 1, 2, 2, 1, 1)
    out = analyze((record, indices, coordinates, render, additional))
    assert out['owners'][0]['disposition'] == 'KEEP'
    assert out['inputStreams']['coordinates']['readCalls'] == 1
    assert out['inputStreams']['coordinates']['readBytes'] == 4
    part = out['owners'][0]['parts'][0]
    assert part['coordinateEncodingUnresolved'] is True
    assert part['sourceTypedElementSHA256'] is None
    assert out['originalNativeBinaryUsed'] is False


@pytest.mark.parametrize('args', [fixture([[]]), fixture(root=0), fixture([RING, [(0, 0)]*4])])
def test_unknown_or_empty_container_native_work_is_not_inferred(args):
    out = analyze(args)
    model = out['hypotheticalDecoderWork']
    assert model['modeledOwners'] == 0
    assert model['unmodeledOwners'] == 1
    assert model['partContainersBeforeAndAfter'] == 0
    assert model['getXCallsBefore'] == model['getYCallsBefore'] == 0
    assert model['unsafeEmptyOrExcludedOwnerCallsNotInferred'] is True
