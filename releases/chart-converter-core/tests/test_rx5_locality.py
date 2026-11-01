"""Own invented wire fixtures; no chart, OEM library or runtime proof data.

The eleven AP02 V24 preservation cases and four malformed cases are ported
to a synthetic L15 source so exhaustive descendant checks remain bounded.
Additional controls exercise the pure whole-owner certificate and raw contexts.
"""
import struct

import pytest

from no_chart_converter.rx5 import locality_empty as empty
from no_chart_converter.rx5 import locality_parts as parts


def fixture(programme=b'AP(SYNTHETIC);LS(DASH,2,TEST)', *, kind=3, root=1,
            alloutside=False, touch=False, contexts=None):
    tile = (15, 0, 0)
    unit = parts.Q // (1 << tile[0])
    inside = [(unit//8, unit//8), (7*unit//8, unit//8),
              (7*unit//8, 7*unit//8), (unit//8, 7*unit//8)]
    inside.append(inside[0])
    far = [(2*unit, 2*unit), (2*unit+1000, 2*unit),
           (2*unit+1000, 2*unit+1000), (2*unit, 2*unit)]
    if alloutside:
        inside = far
    if touch:
        far = [(0, 0), (2*unit, 0), (2*unit, 2*unit), (0, 0)]
    if kind == 2:
        inside, far = inside[:2], far[:2]
    coordinates = bytearray()
    words = []
    usage = 16 if kind == 2 else 24
    for points in (inside, far):
        words.append((usage << 24) | len(coordinates))
        coordinates += struct.pack('<I', (root << 24) | len(points))
        coordinates += b''.join(struct.pack('<II', *point) for point in points)
    indexes = struct.pack('<III', 2, *words)
    entries = contexts if contexts is not None else [(196608, programme)]
    render = struct.pack('<HI', len(entries), 123)
    render += b''.join(struct.pack('<IH', key, len(value)) + value
                       for key, value in entries)
    record = parts.F.pack(4242, 0, 0, 0x12345678, 77, 308, kind, 7, 8, 9)
    return record, indexes, bytes(coordinates), render, tile


def assert_unchanged(fixture_args):
    result = parts.local_whole_parts(*fixture_args)
    assert result['receipts'] == []
    assert result['featureBytes'] == fixture_args[0]
    assert result['indexBytes'] == fixture_args[1]
    assert result['coordinateBytesChanged'] is False
    assert result['renderBytesChanged'] is False
    return result


def test_partial_whole_part_preserves_complete_path_and_record_fields():
    args = fixture()
    result = parts.local_whole_parts(*args)
    assert len(result['receipts']) == 1
    row = result['receipts'][0]
    assert row['retainedPartOrdinals'] == [0]
    assert [p['ordinal'] for p in row['removedParts']] == [1]
    assert result['featureBytes'][:8] == args[0][:8]
    assert result['featureBytes'][12:] == args[0][12:]
    new_offset = parts.F.unpack(result['featureBytes'])[1]
    assert struct.unpack_from('<II', result['indexBytes'], new_offset) == (
        1, struct.unpack_from('<I', args[1], 4)[0])
    assert result['indexBytes'][:len(args[1])] == args[1]
    assert result['wholeOwnersRemoved'] == 0
    assert [p['level'] for p in row['removedParts'][0]['allDescendantExclusion']] == list(range(15, 30))
    assert all(p['strictOutsideBits'] for p in row['removedParts'][0]['allDescendantExclusion'])


@pytest.mark.parametrize('kwargs', [
    {'programme': b'AP(SYNTHETIC);SY(TEST)'},
    {'programme': b'TX(OBJNAM,1)'},
    {'programme': b'LC(TEST)'},
    {'programme': b'CS(TEST)'},
    {'programme': b'LS(SOLD,3,TEST)'},
    {'programme': b'AC(TEST,9)'},
    {'root': 0},
    {'alloutside': True},
    {'touch': True},
])
def test_original_nine_preservation_controls(kwargs):
    assert_unchanged(fixture(**kwargs))


def test_kind2_open_line_whole_part_preserves_original_endpoints():
    args = fixture(kind=2, programme=b'LS(SOLD,1,TEST)')
    result = parts.local_whole_parts(*args)
    row, = result['receipts']
    assert row['retainedPartOrdinals'] == [0]
    assert row['retainedParts'][0]['closed'] is False
    assert row['retainedParts'][0]['points'] == 2
    # The surviving index still points to the complete original coordinate span.
    at = parts.F.unpack(result['featureBytes'])[1]
    assert struct.unpack_from('<I', result['indexBytes'], at+4)[0] == struct.unpack_from('<I', args[1], 4)[0]


@pytest.mark.parametrize('case, expected', [
    ('index_truncated', 'INDEX_PART_LIMIT'),
    ('coordinate_truncated', 'COORDINATE_SPAN_LIMIT'),
    ('programme_truncated', 'PROGRAMME_CONTEXT_BYTES'),
    ('index_count_overflow', 'INDEX_PART_LIMIT'),
])
def test_original_four_malformed_wire_refusals(case, expected):
    args = list(fixture())
    if case == 'index_truncated':
        args[1] = args[1][:5]
    elif case == 'coordinate_truncated':
        args[2] = args[2][:-1]
    elif case == 'programme_truncated':
        args[3] = args[3][:-1]
    else:
        args[1] = struct.pack('<I', 0xffffffff)
    with pytest.raises(ValueError, match=expected):
        parts.local_whole_parts(*args)


def test_duplicate_raw_context_cannot_hide_forbidden_programme():
    args = fixture(contexts=[(196608, b'SY(TEST)'), (196608, b'AC(TEST)')])
    assert_unchanged(args)
    assert empty.eligible(3, [b'SY(TEST)', b'AC(TEST)']) is False


def test_kind4_kind9_signed_xyz_records_are_not_changed_or_reinterpreted():
    coordinates = struct.pack('<I', 0x01000001) + struct.pack('<IIi', 101, 203, -2147483648)
    indexes = struct.pack('<II', 1, 0)
    features = b''.join(parts.F.pack(123, 0, 0, 0, 12, 129, kind, 0, 0, 0)
                        for kind in (4, 9))
    result = assert_unchanged((features, indexes, coordinates, b'', (15, 0, 0)))
    assert result['featureBytes'] == features


def owner_part(points, *, root=1, usage=24):
    return {'partOrdinal': 0, 'rootFlag': root, 'usage': usage, 'points': points}


def far_ring():
    unit = empty.ROOT // (1 << 15)
    return [(2*unit, 2*unit), (2*unit+1000, 2*unit),
            (2*unit+1000, 2*unit+1000), (2*unit, 2*unit)]


def test_whole_empty_owner_is_certificate_only_with_explicit_guard_basis():
    original = far_ring()
    certificate = empty.certify(3, [b'AP(TEST);LS(DASH,2,TEST)'], [owner_part(original)], (15, 0, 0))
    assert certificate is not None
    assert certificate['geometryGuardSourcePixels'] == 64
    assert certificate['protectedQueryHaloSourcePixels'] == 32
    assert certificate['sourcePixelBasisLevel'] == 15
    assert certificate['parts'][0]['allSegmentsDisjointFromClosedGuard'] is True
    assert certificate['parts'][0]['interiorWinding'] == 0
    assert certificate['projectionErrorBounds'][-1]['level'] == 29
    assert original == far_ring()


@pytest.mark.parametrize('change', ['root', 'usage', 'open', 'crossing', 'containing', 'touch', 'out_of_u32'])
def test_whole_owner_conservative_geometric_exclusions(change):
    unit = empty.ROOT // (1 << 15)
    points = far_ring()
    tile = (15, 0, 0)
    kwargs = {}
    if change == 'root':
        kwargs['root'] = 0
    elif change == 'usage':
        kwargs['usage'] = 16
    elif change == 'open':
        points = points[:-1]
    elif change == 'out_of_u32':
        points = [(1 << 32, y) for x, y in points]
    elif change == 'containing':
        # No edge crosses the source rectangle, but the filled ring contains it.
        tile = (15, 2, 2)
        points = [(unit, unit), (4*unit, unit), (4*unit, 4*unit),
                  (unit, 4*unit), (unit, unit)]
        domain = empty.Domain.source(tile, 64)
        assert not any(domain.hits(a, b) for a, b in zip(points, points[1:]))
        assert domain.winding(points) == 1
    elif change == 'crossing':
        points = [(0, 0), (2*unit, 0), (2*unit, 2*unit), (0, 0)]
    else:
        # A shared closed rectangle edge is contact, not strict exclusion.
        domain = empty.Domain.source((0, 0, 0), 0)
        assert domain.hits((0, 0), (empty.ROOT, 0))
        points = [(0, 0), (unit, 0), (unit, unit), (0, 0)]
    assert empty.certify(3, [b'AC(TEST)'], [owner_part(points, **kwargs)], tile) is None


@pytest.mark.parametrize('programmes', [[], [b'SY(TEST)'], [b'AP(TEST);LC(TEST)'],
                                     [b'AC(TEST)', b'TE(TEST)'], [b'CS(TEST)'],
                                     [b'LS(SOLD,4,TEST)']])
def test_whole_owner_all_context_programme_whitelist(programmes):
    assert empty.certify(3, programmes, [owner_part(far_ring())], (15, 0, 0)) is None


def test_whole_owner_guard_and_level_fail_closed():
    with pytest.raises(ValueError, match='GEOMETRY64_GUARD'):
        empty.certify(3, [b'AC(TEST)'], [owner_part(far_ring())], (15, 0, 0), guard_pixels=32)
    with pytest.raises(ValueError, match='ORIGINAL_NATIVE_SOURCE_LEVEL'):
        empty.certify(3, [b'AC(TEST)'], [owner_part(far_ring())], (16, 0, 0))


def test_partial_invalid_record_or_level_bounds_are_refused():
    args = list(fixture())
    with pytest.raises(ValueError, match='WHOLE_FEATURE_RECORDS'):
        parts.local_whole_parts(args[0][:-1], *args[1:])
    with pytest.raises(ValueError, match='TILE_DOMAIN'):
        parts.local_whole_parts(*args[:4], (30, 0, 0))
