"""Invented source-domain controls, never original/painter/device measurements."""
import struct

import pytest
from shapely.geometry import Polygon, box
from shapely.ops import unary_union

from no_chart_converter.rx5.fill_compact import (
    SCOPE, compact_source_fill, require_source32_request,
)
from no_chart_converter.rx5.model import parts
from no_chart_converter.rx5.wire import FEATURE

TILE = (15, 0, 0)
FIRST = [(10000, 10000), (22000, 10000), (22000, 22000), (10000, 10000)]
FAR = [(300000, 300000), (315000, 300000), (315000, 315000), (300000, 300000)]
LAST = [(35000, 35000), (45000, 35000), (45000, 45000), (35000, 35000)]


def fixture(rings=None, usages=None, *, programme=b'AC(TEST);'):
    rings = [FIRST, FAR, LAST] if rings is None else rings
    usages = [24]*len(rings) if usages is None else usages
    co, offsets = bytearray(), []
    for ring in rings:
        offsets.append(len(co))
        co += struct.pack('<I', 0x1000000 | len(ring))
        co += b''.join(struct.pack('<II', *p) for p in ring)
    ix = struct.pack('<I', len(rings)) + b''.join(
        struct.pack('<I', u << 24 | at) for u, at in zip(usages, offsets))
    ro = struct.pack('<HI', 2, 0) + b''.join(
        struct.pack('<IH', k, len(programme)) + programme for k in (196608, 196609))
    ao = struct.pack('<QHHH', 0x8877665544332211, 0, 0, 0)
    f = FEATURE.pack(1001, 0, 0, 7, 17, 42, 3, 1, 2, 3)
    return f, ix, bytes(co), ro, ao


def run(args):
    return compact_source_fill(*args, tile=TILE, scope=SCOPE)


def unpack(args, ordinal=0):
    return list(parts(args[0][ordinal*32:(ordinal+1)*32], args[1], args[2]))


def output_args(out):
    return tuple(out[k] for k in ('featureBytes', 'indexBytes', 'coordinateBytes',
                                 'renderBytes', 'additionalBytes'))


def test_actual_buffers_drop_only_offsource_middle_and_keep_native_consumers():
    args = fixture()
    out = run(args)
    assert out['changed'] and out['disposition'] == 'COMPACTED_SOURCE32_PARTS'
    assert out['receipt']['removedPartCount'] == 1
    assert out['receipt']['removedXYOccurrences'] == 4
    before, after = unpack(args), unpack(output_args(out))
    assert [p['raw'] for p in after] == [before[0]['raw'], before[2]['raw']]
    assert [p['usage'] for p in after] == [24, 24]
    assert out['featureBytes'][:8] == args[0][:8]
    assert out['featureBytes'][12:] == args[0][12:]
    assert (out['renderBytes'], out['additionalBytes']) == args[3:]
    assert len(out['coordinateBytes']) < len(args[2])
    assert args == fixture()


def test_host_fill_oracle_equal_inside_domain_and_deliberately_not_global():
    args = fixture()
    out = run(args)
    before = unary_union([Polygon(p['points']) for p in unpack(args)])
    after = unary_union([Polygon(p['points']) for p in unpack(output_args(out))])
    # Actual native proof remains separately required; this is exact invented
    # integer geometry illustrating the declared spatial scope and its limit.
    source32 = box(-8192, -8192, 73728, 73728)
    assert before.symmetric_difference(after).intersection(source32).is_empty
    assert not before.equals(after)
    assert out['receipt']['globalQueryEqualityClaim'] is False
    assert out['receipt']['globalChartApplyAllowed'] is False


def test_whole_offsource_owner_is_not_removed_and_retains_first_and_last():
    rings = [[(x+k, y+k) for x, y in FAR] for k in (0, 40000, 80000)]
    args = fixture(rings)
    out = run(args)
    assert out['receipt']['wholeOwnersRemoved'] == 0
    assert len(out['featureBytes']) == 32
    owner, = out['receipt']['owners']
    assert owner['keptOriginalPartOrdinals'] == [0, 2]
    assert owner['exclusion']['kernel'] == 'wholeOwner64_AS_PART_EXCLUSION_ONLY'


def test_last_visible_is_hole_and_trailing_mask_is_not_query_boundary():
    args = fixture([FIRST, FAR, LAST, FAR], [8, 24, 20, 8])
    out = run(args)
    row, = out['receipt']['owners']
    assert row['protectedConsumerPartOrdinals'] == [0, 2]
    assert row['finalQueryVisiblePartOrdinal'] == 2
    assert row['removedOriginalPartOrdinals'] == [1, 3]
    assert [p['usage'] for p in unpack(output_args(out))] == [8, 20]


def test_two_polygons_and_noop_do_not_repack_unreferenced_padding():
    f, i, c, r, a = fixture([FIRST, FAR])
    args = f, i+b'UNUSED-INDEX', c+b'UNUSED-CO', r+b'UNUSED-RO', a+b'UNUSED-AO'
    out = run(args)
    assert out['disposition'] == 'UNCHANGED' and not out['changed']
    assert output_args(out) == args


def test_cross_owner_p0_alias_blocks_removal_even_for_non_ac_owner():
    f, i, c, r, a = fixture()
    word, = struct.unpack_from('<I', i, 8)
    extra = struct.pack('<II', 1, word)
    # The alias owner has a symbol programme, so it is excluded from omission
    # while its actual polygon0 still contributes global protected intervals.
    off = len(r)
    p = b'SY(TEST);'
    r += struct.pack('<HI', 2, 0)+b''.join(struct.pack('<IH', k, len(p))+p for k in (196608, 196609))
    f += FEATURE.pack(1002, len(i), off, 9, 20, 77, 3, 3, 2, 1)
    args = f, i+extra, c, r, a
    out = run(args)
    assert output_args(out) == args
    assert not out['changed']
    assert out['receipt']['owners'][0]['protectedCandidatePartOrdinals'] == [1]


def test_xyz_twins_keep_signed_depth_and_aliases_while_other_owner_changes():
    f, i, c, r, a = fixture()
    ix = len(i)
    i += struct.pack('<II', 1, len(c))
    c += struct.pack('<I', 0x1000002)+struct.pack('<IIiIIi', 123, 456, -18, 987, 654, 35060)
    f += b''.join(FEATURE.pack(2001, ix, 0, 8, 17, 99, k, 5, 6, 7) for k in (4, 9))
    args = f, i, c, r, a
    out = run(args)
    assert out['changed']
    for ordinal in (1, 2):
        assert unpack(args, ordinal)[0]['raw'] == unpack(output_args(out), ordinal)[0]['raw']
    u, v = (FEATURE.unpack_from(out['featureBytes'], ordinal*32) for ordinal in (1, 2))
    assert u[1] == v[1]
    assert out['receipt']['declaredWork']['XYZBeforeAndAfter'] == 4


@pytest.mark.parametrize('kind, root', [(8, 1), (3, 0), (3, 2)])
def test_unknown_kind_or_coordinate_basis_keeps_all_five_buffers(kind, root):
    f, i, c, r, a = fixture()
    fields = list(FEATURE.unpack(f)); fields[6] = kind
    c = struct.pack('<I', root << 24 | 4) + c[4:]
    args = FEATURE.pack(*fields), i, c, r, a
    out = run(args)
    assert out['disposition'] == 'KEEP_SOURCE' and not out['changed']
    assert output_args(out) == args


@pytest.mark.parametrize('programme', [b'LS(SOLD,1,TEST);', b'AP(TEST);', b'AC(TEST);SY(TEST);', b'AC(TEST);;'])
def test_non_pure_ac_programmes_are_never_materialized(programme):
    args = fixture(programme=programme)
    out = run(args)
    assert output_args(out) == args and not out['changed']


def test_malformed_known_root1_body_refused_instead_of_claiming_safe_keep():
    f, i, c, r, a = fixture()
    bad = struct.pack('<I', 0x10fffff)+c[4:]
    with pytest.raises(ValueError, match='COMPLETE_TYPED_ELEMENT'):
        run((f, i, bad, r, a))


def test_scope_and_request_domain_cannot_be_widened_by_flags():
    args = fixture()
    for scope in (None, True, 'GLOBAL', SCOPE+' '):
        with pytest.raises(ValueError, match='EXPLICIT_SOURCE32_SCOPE'):
            compact_source_fill(*args, tile=TILE, scope=scope)
    require_source32_request(TILE, (18, 0, 0), query_root=(0, 0))
    for dest in ((14, 0, 0), (30, 0, 0), (18, 8, 0)):
        with pytest.raises(ValueError, match='OUTSIDE_CERTIFIED_SOURCE_DESCENDANTS'):
            require_source32_request(TILE, dest)
    with pytest.raises(ValueError, match='QUERY_OUTSIDE_CERTIFIED_SOURCE32'):
        require_source32_request(TILE, (18, 0, 0), query_root=(100000, 100000))


def test_compaction_is_idempotent_without_incidental_second_repacking():
    once = run(fixture())
    twice = run(output_args(once))
    assert twice['disposition'] == 'UNCHANGED'
    assert output_args(twice) == output_args(once)
