"""Independent metamorphic checks using invented tiny public fixture geometry.

These fixtures are authored here, contain no acquired chart/private runtime
data, and do not read a chart file, device, VM or external volume. The oracle is
the original ordered directed nonzero edge sequence, not a second deduplicator.
No native-query, original-painter or measured CPU-speed acceptance is implied.
"""
import hashlib
import math
import random
import struct
from fractions import Fraction

import pytest

from no_chart_converter.rx5.fill_duplicates import analyze_fill_duplicates
from no_chart_converter.rx5.metadata import Stream


ROOT_Q = 2147483649
FEATURE = struct.Struct('<Q5I4B')
TILE = (15, 100, 200)
ORIGIN = (100 * ROOT_Q // 32768, 200 * ROOT_Q // 32768)


def typed_ring(points, root=1):
    return struct.pack('<I', root << 24 | len(points)) + b''.join(
        struct.pack('<II', *point) for point in points)


def scene(rings, usages=None, *, roots=None, contexts=None):
    """One entirely invented Kind3 owner; unchanged query/DI/level sentinels."""
    usages = usages or [24] * len(rings)
    roots = roots or [1] * len(rings)
    coordinates = bytearray()
    words = []
    for points, usage, root in zip(rings, usages, roots):
        words.append(usage << 24 | len(coordinates))
        coordinates += typed_ring(points, root)
    indices = struct.pack('<I', len(words)) + b''.join(
        struct.pack('<I', word) for word in words)
    contexts = contexts or [(196608, b'AC(AOWN);'), (196609, b'AC(BOWN);')]
    render = struct.pack('<HI', len(contexts), 0) + b''.join(
        struct.pack('<IH', key, len(body)) + body for key, body in contexts)
    record = FEATURE.pack(987654321, 0, 0, 37, 12345, 42, 3, 6, 2, 17)
    additional = bytes(8) + struct.pack('<HH', 1, 27) + b'invented-own-fixture\0' + bytes(4)
    return record, indices, bytes(coordinates), render, additional


def analyse(value, *, streamed=False):
    record, indices, coordinates, render, additional = value
    if streamed:
        def wrap(raw):
            def read(at, n):
                assert 0 <= at <= len(raw) and 0 <= n <= len(raw) - at
                return raw[at:at+n]
            return Stream(len(raw), read)
        indices, coordinates, render, additional = map(
            wrap, (indices, coordinates, render, additional))
    return analyze_fill_duplicates(record, indices, coordinates, render,
                                   additional=additional)


def shifted(points):
    return [(ORIGIN[0] + x, ORIGIN[1] + y) for x, y in points]


def inject_runs(points, rng):
    return [point for point in points for _ in range(rng.randint(1, 4))]


def nonzero_edges(points):
    return [(a, b) for a, b in zip(points, points[1:]) if a != b]


def bbox(points):
    return (min(p[0] for p in points), min(p[1] for p in points),
            max(p[0] for p in points), max(p[1] for p in points))


def twice_area(points):
    return sum(a[0]*b[1] - a[1]*b[0] for a, b in zip(points, points[1:]))


def proposed_rings(value, result, rings, usages):
    owner, = result['owners']
    assert owner['recordOrdinal'] == 0
    assert str(owner['uid']) == '987654321'
    assert owner['sourceRecord32Hex'] == value[0].hex()
    assert len(owner['parts']) == len(rings)
    out = []
    for ordinal, (source, usage, part) in enumerate(zip(rings, usages, owner['parts'])):
        kept = part['keptVertexOrdinals']
        removed = part['removedVertexOrdinals']
        assert kept == sorted(set(kept))
        assert kept[0] == 0 and kept[-1] == len(source) - 1
        assert set(kept).isdisjoint(removed)
        assert sorted(kept + removed) == list(range(len(source)))
        target = [source[i] for i in kept]
        assert len(target) >= 4 and target[0] == target[-1]
        assert len(set(target[:-1])) >= 3
        # Any skipped chain can introduce neither a new directed edge nor a
        # deleted nonzero edge, including terminal closure and repeated starts.
        assert nonzero_edges(source) == nonzero_edges(target)
        assert bbox(source) == bbox(target)
        assert twice_area(source) == twice_area(target)
        assert part['sourcePointCount'] == len(source)
        assert part['proposedPointCount'] == len(target)
        assert part['usage'] == usage and part['rootFlag'] == 1
        assert part['sourceTypedElementSHA256'] == hashlib.sha256(typed_ring(source)).hexdigest()
        assert part['proposedTypedElementSHA256'] == hashlib.sha256(typed_ring(target)).hexdigest()
        original_word = struct.unpack_from('<I', value[1], 4 + 4*ordinal)[0]
        assert part['indexWord'] == original_word
        # Both raw direction and the preserved usage reversal are tested.
        effective_source = source[::-1] if usage & 1 else source
        effective_target = target[::-1] if usage & 1 else target
        assert nonzero_edges(effective_source) == nonzero_edges(effective_target)
        out.append(target)
    return out


@pytest.mark.parametrize('reverse', [False, True])
@pytest.mark.parametrize('usages', [(8, 9), (20, 21), (24, 25)])
def test_directed_edges_closure_holes_and_reversal_are_invariant(reverse, usages):
    rng = random.Random(20261017)
    outer = shifted([(0, 0), (8192, 0), (8192, 8192), (0, 8192), (0, 0)])
    hole = shifted([(1024, 1024), (1024, 4096), (4096, 4096), (4096, 1024), (1024, 1024)])
    assert twice_area(outer) * twice_area(hole) < 0
    rings = [inject_runs(r[::-1] if reverse else r, rng) for r in (outer, hole)]
    value = scene(rings, usages)
    out = proposed_rings(value, analyse(value), rings, usages)
    assert all(len(b) < len(a) for a, b in zip(rings, out))
    assert twice_area(out[0]) * twice_area(out[1]) < 0


def test_24_seeded_run_injections_preserve_entire_ordered_edge_sequence():
    rng = random.Random(884021)
    for _ in range(24):
        x, y = rng.randrange(1, 5000), rng.randrange(1, 5000)
        w, h = rng.randrange(10, 2000), rng.randrange(10, 2000)
        ring = shifted([(x, y), (x+w, y), (x+w, y+h), (x, y+h), (x, y)])
        ring = ring[::-1] if rng.randrange(2) else ring
        source = inject_runs(ring, rng)
        value = scene([source])
        proposed_rings(value, analyse(value), [source], [24])


def f32(value):
    return struct.unpack('<f', struct.pack('<f', value))[0]


def independent_qround(value):
    # Explicit old Qt4 F32 negative-half convention; signed-safe fixtures only.
    assert math.isfinite(value) and -(1 << 31) + 1024 < value < (1 << 31) - 1024
    if value >= 0:
        return math.trunc(f32(value + .5))
    integer = math.trunc(f32(value - 1.))
    return integer + math.trunc(f32(f32(value - f32(integer)) + .5))


def project(points, level, destination):
    return [tuple(independent_qround(f32((math.ldexp(u / ROOT_Q, level) - d) * 256.))
                  for u, d in zip(point, destination)) for point in points]


def test_identical_projected_nonzero_edges_through29_and_negative_child_origins():
    rng = random.Random(1183)
    outer = shifted([(1, 1), (8193, 1), (8193, 8193), (1, 8193), (1, 1)])
    hole = shifted([(1025, 1025), (1025, 4097), (4097, 4097), (4097, 1025), (1025, 1025)])
    rings = [inject_runs(ring, rng) for ring in (outer, hole)]
    value = scene(rings, [24, 25])
    out = proposed_rings(value, analyse(value), rings, [24, 25])
    negative_seen = False
    for level in range(15, 30):
        scale = 1 << (level - 15)
        for dx in sorted({0, scale // 2, scale - 1}):
            for dy in sorted({0, scale // 2, scale - 1}):
                destination = (TILE[1]*scale + dx, TILE[2]*scale + dy)
                for source, target in zip(rings, out):
                    a, b = project(source, level, destination), project(target, level, destination)
                    assert nonzero_edges(a) == nonzero_edges(b)
                    assert bbox(a) == bbox(b)
                    assert twice_area(a) == twice_area(b)
                    negative_seen |= any(x < 0 or y < 0 for x, y in a)
    assert negative_seen
    assert independent_qround(f32(-1.5)) == -1


def test_distinct_collinear_vertices_are_kept_even_if_low_zoom_pixels_coincide():
    ring = shifted([(0, 0), (1, 0), (2, 0), (8192, 0), (8192, 8192), (0, 8192), (0, 0)])
    ring.insert(1, ring[0])
    value = scene([ring])
    out, = proposed_rings(value, analyse(value), [ring], [24])
    assert ring[2] in out and ring[3] in out
    assert project([ring[0], ring[2], ring[3]], 15, TILE[1:]) == [project([ring[0]], 15, TILE[1:])[0]]*3


@pytest.mark.parametrize('bad_ring', [
    [(0, 0), (4, 0), (4, 4), (0, 4)],  # open
    [(0, 0)]*6,  # all coincident
    [(0, 0), (4, 0), (0, 0), (4, 0), (0, 0)],  # two distinct
    [(0, 0), (4, 0), (0, 0)],  # too short
    [(0, 0), (4, 0), (8, 0), (0, 0)],  # zero-area ring
    [(0, 0), (4, 4), (0, 4), (4, 0), (0, 0)],  # self intersection
])
def test_one_unsafe_part_keeps_entire_owner(bad_ring):
    good = shifted([(0, 0), (0, 0), (8192, 0), (8192, 8192), (0, 8192), (0, 0)])
    value = scene([good, shifted(bad_ring)])
    owner, = analyse(value)['owners']
    assert owner['disposition'].startswith('KEEP')
    for part in owner.get('parts', []):
        assert part.get('removedVertexOrdinals', []) == []
        if 'proposedTypedElementSHA256' in part:
            assert part['proposedTypedElementSHA256'] == part['sourceTypedElementSHA256']


def test_stream_and_immutable_bytes_observe_same_proposal():
    source = inject_runs(shifted([(0, 0), (8192, 0), (8192, 8192), (0, 8192), (0, 0)]), random.Random(9))
    value = scene([source])
    before = [hashlib.sha256(b).hexdigest() for b in value]
    plain, streamed = analyse(value), analyse(value, streamed=True)
    assert plain['owners'] == streamed['owners']
    proposed_rings(value, plain, [source], [24])
    assert [hashlib.sha256(b).hexdigest() for b in value] == before


def test_geometry_invariance_cannot_be_promoted_to_query_or_native_acceptance():
    ring = shifted([(0, 0), (0, 0), (8192, 0), (8192, 8192), (0, 8192), (0, 0)])
    value = scene([ring])
    result = analyse(value)
    target, = proposed_rings(value, result, [ring], [24])
    # An illustrative vertex-weighted mean changes with zero-edge multiplicity.
    # This does not emulate a native function; it prevents our edge oracle from
    # being presented as proof of every possible query/anchor fallback.
    mean = lambda points: tuple(Fraction(sum(p[k] for p in points), len(points))
                                for k in (0, 1))
    assert mean(ring) != mean(target)
    assert mean(ring[:-1]) != mean(target[:-1])
    for flag in ('nativeQueryEquivalenceApproved', 'nativeAnchorFallbackEquivalenceApproved',
                 'operativeApplyAllowed', 'wholeSourceAliasSafetyApproved',
                 'materializationApproved', 'originalNativeBinaryUsed',
                 'nativeCPUInstructionsMeasured', 'coordinateOrIndexOrMetadataBuffersReturned'):
        assert result[flag] is False
    assert result['analyzeOnly'] is True
    assert result['nativeQueryDisposition'] == 'UNPROVEN'
    assert result['originalCentroidPreservation'] == 'UNPROVEN_DUPLICATE_WEIGHTED_F32_MEAN'
    assert result['hypotheticalDecoderWork']['measured'] is False


@pytest.mark.parametrize('bad_field', ['features', 'indices', 'coordinates', 'render'])
def test_malformed_wire_bounds_fail_explicitly(bad_field):
    ring = shifted([(0, 0), (0, 0), (8192, 0), (8192, 8192), (0, 8192), (0, 0)])
    value = list(scene([ring]))
    index = ['features', 'indices', 'coordinates', 'render'].index(bad_field)
    value[index] = value[index][:-1]
    with pytest.raises(ValueError):
        analyse(value)
