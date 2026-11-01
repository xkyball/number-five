"""Invented append/tree controls; no chart or Original runtime fixtures."""
import copy
import hashlib
import struct

import pytest

from no_chart_converter.rx5 import exact_level_children as c, exact_level_noink as e
from no_chart_converter.rx5 import header, wire
from no_chart_converter.rx5.model import native_tree, programmes

A = (17, 70000, 70000)
B = (18, 140003, 140002)
Q = 2147483649


def sha(raw):
    return hashlib.sha256(raw).hexdigest()


def fixture(*, root=1, add_child=False):
    x, y = A[1]*Q//(1 << A[0]), A[2]*Q//(1 << A[0])
    points = [(x-2000000, y-40000000), (x-1990000, y-40000000),
              (x-1990000, y-39990000), (x-2000000, y-40000000)]
    co = struct.pack('<I', root << 24 | 4)+b''.join(struct.pack('<II', *p) for p in points)
    ix = struct.pack('<II', 1, 0x18000000)
    features = b''.join(wire.FEATURE.pack(uid, 0, 0, 70, 0, 42, 3, 0, 0, 0) for uid in (11, 22))
    contexts = [(0x100ff, b'OWN_CATEGORY'), (0x30001, b'AC(OWN_COLOR);'),
                (0x10103, b'OWN_DETAIL'), (0x30000, b'AC(OWN_COLOR);')]
    ro = struct.pack('<HI', len(contexts), 0)+b''.join(struct.pack('<IH', k, len(v))+v for k, v in contexts)
    ao = struct.pack('<QHHH', 11, 0, 0, 0)
    nodes = set()
    for z, tx, ty in (A, B):
        nodes.update((l, tx >> (z-l), ty >> (z-l)) for l in range(z+1))
    if add_child:
        nodes.add((18, 2*A[1], 2*A[2]))
    name = lambda t: 'node-'+'.'.join(map(str, t))
    authored = [wire.AuthoredBlock(n, bid, 0, b'', payload) for n, bid, payload in
                (('i', 0x1101, ix), ('c', 0x1102, co), ('r', 0x1103, ro), ('a', 0x1104, ao))]
    authored.append(wire.AuthoredBlock('resource', 65, 0, b'', b'OWN_RESOURCE'))
    for t in sorted(nodes):
        z, tx, ty = t
        refs = tuple((8*s, name(k) if k in nodes else None) for s in range(4)
                     for k in [(z+1, 2*tx+(s & 1), 2*ty+(s >> 1))])
        vector = t in (A, B)
        if vector:
            refs += tuple((32+8*s, n) for s, n in enumerate(('i', 'c', 'r', 'a')))
        authored.append(wire.AuthoredBlock(name(t), 0x6000+z, 3 if vector else 1,
                        bytes(64 if vector else 32), features if vector else b'', references=refs))
    raw = wire.assemble(header.new_plain_vector_header(bytes(range(16))), authored,
          {s: name((0, 0, 0)) if s == 160 else 'resource' if s == 184 else None for s in wire.HEADER_SLOTS})[0]
    return raw, (features, ix, co, ro, ao)


def profiles(buffers, tiles=(A, B)):
    hashes = dict(zip(e.NAMES, map(sha, buffers)))
    rows = []
    for t in tiles:
        p = e.prepare_exact_level(*buffers, exact_tile=t, owner_uids=[11], expected_input_sha256=hashes)
        rows.append(dict(exactTile=t, ownerUIDs=[11], expectedPreparationSHA256=e.plan_sha256(p['plan'])))
    return rows


def apply(raw, rows):
    plan = c.plan_exact_level_children(raw, rows)
    return c.build_exact_level_children(raw, rows, expected_source_sha256=sha(raw),
             expected_plan_sha256=c.exact_level_children_plan_sha256(plan))


def test_two_replacements_all_four_restores_full_geometry_and_prefix_inverse():
    raw, buffers = fixture(); before = wire.blocks(raw); old = native_tree(raw)
    out, receipt = apply(raw, profiles(buffers)); after = wire.blocks(out); tree = native_tree(out)
    by = {t: at for at, t in tree.items()}; oldby = {t: at for at, t in old.items()}
    assert len(tree) == len(old)+8 and len(receipt['actualSubtreeBarriers']) == 2
    for tile in (A, B):
        assert by[tile] != oldby[tile]
        assert out[oldby[tile]:oldby[tile]+80+len(buffers[0])] == raw[oldby[tile]:oldby[tile]+80+len(buffers[0])]
        n = after[by[tile]]; children = struct.unpack_from('<4Q', n.prefix)
        oi, oc, ro, oa = struct.unpack_from('<4Q', before[oldby[tile]].prefix, 32)
        for slot, at in enumerate(children):
            child = after[at]
            assert tree[at] == (tile[0]+1, 2*tile[1]+(slot & 1), 2*tile[2]+(slot >> 1))
            assert struct.unpack_from('<4Q', child.prefix) == (0, 0, 0, 0)
            assert struct.unpack_from('<4Q', child.prefix, 32) == (oi, oc, ro, oa)
            assert bytes(child.payload) == buffers[0]
        ni, nc, nr, na = struct.unpack_from('<4Q', n.prefix, 32)
        assert (ni, nc, na) == (oi, oc, oa)
        assert bytes(after[nr].payload).startswith(buffers[3])
        assert bytes(n.payload[32:]) == buffers[0][32:]
        rec = wire.FEATURE.unpack(n.payload[:32]); ctx, query = programmes(after[nr].payload, rec[2])
        assert ctx[str(0x30000)] == ctx[str(0x30001)] == b'' and query == 0
        assert ctx[str(0x100ff)] == b'OWN_CATEGORY' and ctx[str(0x10103)] == b'OWN_DETAIL'
    inverse = bytearray(out[:len(raw)])
    for edit in receipt['allowedOriginalPrefixEdits']:
        inverse[edit['offset']:edit['offset']+edit['bytes']] = bytes.fromhex(edit['originalHex'])
    assert bytes(inverse) == raw and receipt['sourcePrefixInverseSHA256'] == sha(raw)
    assert len(receipt['allowedOriginalPrefixEdits']) == 3
    assert receipt['nativeSourceScaleQueryRasterAcceptance'] is False


def test_deterministic_order_and_empty_request_identity():
    raw, buffers = fixture(); rows = profiles(buffers)
    a, _ = apply(raw, rows); b, _ = apply(raw, list(reversed(rows)))
    assert a == b and a[56:72] != raw[56:72]
    out, receipt = apply(raw, [])
    assert out is raw and receipt['status'] == 'NO_CHANGE' and receipt['appendBytes'] == 0


def test_all_levels_after_exact_window_resolve_to_a_complete_restore_leaf():
    raw, buffers = fixture(); out, _ = apply(raw, profiles(buffers, (A,)))
    parsed = wire.blocks(out); tree = native_tree(out)
    for z in range(18, 30):
        span = 1 << (z-A[0])
        for dx, dy in ((0, 0), (span-1, 0), (0, span-1), (span-1, span-1)):
            rx, ry = A[1]*span+dx, A[2]*span+dy
            candidates = [(t, at) for at, t in tree.items() if parsed[at].flags == 3 and t[0] <= z
                          and (rx >> (z-t[0]), ry >> (z-t[0])) == t[1:]]
            t, at = max(candidates)
            assert t == (18, rx >> (z-18), ry >> (z-18))
            assert bytes(parsed[at].payload) == buffers[0]


@pytest.mark.parametrize('bad', ['source', 'materializer', 'preparation'])
def test_changed_source_or_any_plan_binding_refuses(bad):
    raw, buffers = fixture(); rows = profiles(buffers); plan = c.plan_exact_level_children(raw, rows)
    source, materializer = sha(raw), c.exact_level_children_plan_sha256(plan)
    if bad == 'source': source = '0'*64
    elif bad == 'materializer': materializer = '0'*64
    else: rows[0]['expectedPreparationSHA256'] = '0'*64
    with pytest.raises(ValueError, match='SHA256|PREPARATION|MATERIALIZER_PLAN'):
        c.build_exact_level_children(raw, rows, expected_source_sha256=source, expected_plan_sha256=materializer)


@pytest.mark.parametrize('change', ['root0', 'nonleaf', 'unknownUID', 'duplicateTile', 'boolUID', 'extraField', 'maxlevel'])
def test_scope_and_complete_restore_source_obligations_refuse(change):
    raw, buffers = fixture(); rows = profiles(buffers, (A,))
    if change == 'root0': raw, _ = fixture(root=0)
    elif change == 'nonleaf': raw, _ = fixture(add_child=True)
    elif change == 'unknownUID': rows[0]['ownerUIDs'] = [999]
    elif change == 'duplicateTile': rows *= 2
    elif change == 'boolUID': rows[0]['ownerUIDs'] = [True]
    elif change == 'extraField': rows[0]['approved'] = True
    elif change == 'maxlevel': rows[0]['exactTile'] = (29, 1, 1)
    with pytest.raises(ValueError): c.plan_exact_level_children(raw, rows)


def test_policy_cannot_change_an_unrequested_owner(monkeypatch):
    raw, buffers = fixture(); rows = profiles(buffers, (A,)); original = e.prepare_exact_level
    def changed(*args, **kwargs):
        result = original(*args, **kwargs); bb = list(result['blankBuffers']); fi = bytearray(bb[0]); fi[56] ^= 1
        bb[0] = bytes(fi); result['blankBuffers'] = tuple(bb); return result
    monkeypatch.setattr(e, 'prepare_exact_level', changed)
    with pytest.raises(ValueError, match='ONLY_FEATURE_RENDER_OFFSET|UNREQUESTED_OWNER'):
        c.plan_exact_level_children(raw, rows)


def test_build_recomputes_actual_wire_barrier_for_every_profile(monkeypatch):
    raw, buffers = fixture(); rows = profiles(buffers); calls = []; original = e.verify_materialized_subtree
    def inspected(actual, original_buffers, **kwargs):
        calls.append((kwargs['exact_tile'], len(actual)))
        return original(actual, original_buffers, **kwargs)
    monkeypatch.setattr(e, 'verify_materialized_subtree', inspected)
    out, receipt = apply(raw, rows)
    assert calls == [(A, len(out)), (B, len(out))]
    assert all(len(r['actualRestoreLeaves']) == 4 for r in receipt['actualSubtreeBarriers'])
