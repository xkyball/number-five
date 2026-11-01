"""Invented diagnostic chart graphs. No global/native admission is asserted."""
import copy
import hashlib
import struct

import pytest

from no_chart_converter.rx5.fill_builder import (
    build_source32_fill, plan_source32_fill, source32_plan_sha256,
)
from no_chart_converter.rx5.fill_compact import SCOPE
from no_chart_converter.rx5.header import new_plain_vector_header
from no_chart_converter.rx5.model import native_tree, parts
from no_chart_converter.rx5.wire import AuthoredBlock, FEATURE, HEADER_SLOTS, assemble, blocks

A, B = (15, 0, 0), (15, 1, 0)


def sha(raw):
    return hashlib.sha256(raw).hexdigest()


def fixture(*, change=True, root=1, children=False):
    first = [(10000, 10000), (22000, 10000), (22000, 22000), (10000, 10000)]
    far = [(300000, 300000), (315000, 300000), (315000, 315000), (300000, 300000)]
    last = [(35000, 35000), (45000, 35000), (45000, 45000), (35000, 35000)]
    co, words = bytearray(), []
    for ring in (first, far if change else first, last):
        words.append(24 << 24 | len(co))
        co += struct.pack('<I', root << 24 | len(ring))
        co += b''.join(struct.pack('<II', *p) for p in ring)
    ix = struct.pack('<I3I', 3, *words)
    xyz_index = len(ix)
    ix += struct.pack('<II', 1, len(co))
    co += struct.pack('<I', 0x1000001)+struct.pack('<IIi', 18000, 18000, -17)
    f = FEATURE.pack(1001, 0, 0, 5, 17, 42, 3, 1, 2, 3)
    f += b''.join(FEATURE.pack(2001, xyz_index, 0, 6, 18, 129, k, 4, 5, 6) for k in (4, 9))
    p = b'AC(OWNTEST);'
    ro = struct.pack('<HI', 2, 0)+b''.join(struct.pack('<IH', k, len(p))+p for k in (196608, 196609))
    ao = struct.pack('<QHHH', 0x8877665544332211, 0, 0, 0)
    authored = [AuthoredBlock('i', 0x1101, 0, b'', ix), AuthoredBlock('c', 0x1102, 0, b'', bytes(co)),
                AuthoredBlock('r', 0x1103, 0, b'', ro), AuthoredBlock('ao', 0x1104, 0, b'', ao),
                AuthoredBlock('resource', 65, 0, b'', b'OWN COMPLETE RESOURCE')]
    for level in range(15):
        refs = [(8*j, ('a' if j == 0 else 'b' if j == 1 else None) if level == 14
                 else 'n'+str(level+1) if j == 0 else None) for j in range(4)]
        authored.append(AuthoredBlock('n'+str(level), 0x6000+level, 1, bytes(32), b'', references=tuple(refs)))
    streams = tuple((32+8*i, name) for i, name in enumerate(('i', 'c', 'r', 'ao')))
    for name in ('a', 'b'):
        refs = tuple((8*i, 'child16' if children and name == 'a' and i == 0 else None) for i in range(4))
        authored.append(AuthoredBlock(name, 0x600f, 3, bytes(64), f, references=refs+streams))
    if children:
        authored.append(AuthoredBlock('child16', 0x6010, 3, bytes(64), f,
                                     references=tuple((8*i, None) for i in range(4))+streams))
    return assemble(new_plain_vector_header(bytes(range(16))), authored,
                    {k: 'n0' if k == 160 else 'resource' if k == 184 else None for k in HEADER_SLOTS})[0]


def build(raw, tiles=(A,)):
    plan = plan_source32_fill(raw, list(tiles))
    return build_source32_fill(raw, list(tiles), expected_source_sha256=sha(raw),
                              expected_plan_sha256=source32_plan_sha256(plan), scope=SCOPE)


def test_diagnostic_chart_keeps_source_selection_resources_consumers_and_full_inverse():
    raw = fixture()
    output, report = build(raw)
    before, after = blocks(raw), blocks(output)
    tree = native_tree(raw)
    assert native_tree(output) == tree
    assert report['status'] == 'SCOPED_DIAGNOSTIC_CANDIDATE'
    assert report['appendedBlocks'] == 2 and report['appendBytes'] > 0
    assert output[56:72] != raw[56:72]
    assert report['globalChartApplyAllowed'] is False
    assert report['normalDeliveryReplacementAllowed'] is False
    assert report['originalLoaderEnforcesSidecar'] is False
    assert report['originalFallback'] == {'bytes': len(raw), 'sha256': sha(raw)}
    at = next(k for k, t in tree.items() if t == A)
    sibling = next(k for k, t in tree.items() if t == B)
    assert before[sibling] == after[sibling]
    oi, oc, ro, ao = struct.unpack_from('<4Q', before[at].prefix, 32)
    ni, nc, nr, na = struct.unpack_from('<4Q', after[at].prefix, 32)
    assert (ro, ao) == (nr, na)
    assert before[ro] == after[ro] and before[ao] == after[ao]
    for ordinal in range(3):
        old, new = before[at].payload[ordinal*32:(ordinal+1)*32], after[at].payload[ordinal*32:(ordinal+1)*32]
        assert old[:8]+old[12:] == new[:8]+new[12:]
        pp, qq = list(parts(old, before[oi].payload, before[oc].payload)), list(parts(new, after[ni].payload, after[nc].payload))
        kept = [0, 2] if ordinal == 0 else [0]
        assert [(p['usage'], p['raw']) for p in qq] == [(pp[i]['usage'], pp[i]['raw']) for i in kept]
    inverse = bytearray(output[:len(raw)])
    for edit in report['allowedOriginalPrefixEdits']:
        inverse[edit['offset']:edit['offset']+edit['bytes']] = bytes.fromhex(edit['originalHex'])
    assert inverse == raw and report['sourcePrefixInverseSHA256'] == sha(raw)


def test_noop_and_unknown_basis_do_not_repack_change_uuid_or_append():
    for raw in (fixture(change=False), fixture(root=0)):
        output, report = build(raw)
        assert output is raw
        assert report['status'] == 'UNCHANGED_DIAGNOSTIC_SOURCE'
        assert report['allowedOriginalPrefixEdits'] == []
        assert report['appendBytes'] == report['appendedBlocks'] == 0


def test_deterministic_scope_order_and_repeated_build_is_exact_noop():
    raw = fixture()
    aa, _ = build(raw, [A, B])
    bb, _ = build(raw, [B, A])
    assert aa == bb
    twice, report = build(aa, [A, B])
    assert twice is aa and report['appendedBlocks'] == 0


def test_source_scope_and_recomputed_plan_cannot_be_waived_by_approval_fields():
    raw = fixture()
    plan = plan_source32_fill(raw, [A])
    digest = source32_plan_sha256(plan)
    with pytest.raises(ValueError, match='EXACT_SOURCE_SHA256'):
        build_source32_fill(raw, [A], expected_source_sha256='0'*64, expected_plan_sha256=digest, scope=SCOPE)
    with pytest.raises(ValueError, match='EXACT_SOURCE32_DIAGNOSTIC_PLAN'):
        build_source32_fill(raw, [B], expected_source_sha256=sha(raw), expected_plan_sha256=digest, scope=SCOPE)
    forged = copy.deepcopy(plan)
    forged['globalChartApplyAllowed'] = True
    forged['normalDeliveryReplacementAllowed'] = True
    forged['approved'] = True
    with pytest.raises(ValueError, match='EXACT_SOURCE32_DIAGNOSTIC_PLAN'):
        build_source32_fill(raw, [A], expected_source_sha256=sha(raw),
                            expected_plan_sha256=source32_plan_sha256(forged), scope=SCOPE)
    for scope in (None, True, 'GLOBAL'):
        with pytest.raises(ValueError, match='EXPLICIT_SOURCE32_SCOPE'):
            build_source32_fill(raw, [A], expected_source_sha256=sha(raw), expected_plan_sha256=digest, scope=scope)


@pytest.mark.parametrize('tiles', [[], [A, A], [(15, True, 0)], [(14, 0, 0)], [(15, -1, 0)], [(15, 7, 0)]])
def test_invalid_or_absent_exact_leaf_scope_is_refused(tiles):
    with pytest.raises(ValueError):
        plan_source32_fill(fixture(), tiles)


def test_child_source_and_chained_arena_are_refused_before_candidate_return():
    with pytest.raises(ValueError, match='UNCHAINED_L15_VECTOR_LEAF'):
        plan_source32_fill(fixture(children=True), [A])
    raw = bytearray(fixture())
    co = next(b for b in blocks(raw).values() if b.disk_id == 0x1102)
    struct.pack_into('<Q', raw, co.offset+8, co.offset)
    with pytest.raises(ValueError, match='PLAIN_UNCHAINED_FOUR_SOURCE_ARENAS'):
        plan_source32_fill(bytes(raw), [A])


def test_final_file_extent_remains_subject_to_real_header_profile(monkeypatch):
    from no_chart_converter.rx5 import header
    raw = fixture()
    plan = plan_source32_fill(raw, [A])
    monkeypatch.setattr(header, 'FILE_SEGMENT_SIZE', len(raw))
    with pytest.raises(ValueError, match='SINGLE_SEGMENT_EXTENT_REQUIRED'):
        build_source32_fill(raw, [A], expected_source_sha256=sha(raw),
                            expected_plan_sha256=source32_plan_sha256(plan), scope=SCOPE)
