"""Invented source-slab controls; no OEM/runtime/chart fixtures."""
import hashlib
import struct

import pytest

from no_chart_converter.rx5 import source_slab_children as c, bounded_noink_slab as p
from no_chart_converter.rx5 import exact_level_children as e, exact_level_noink, header, wire
from no_chart_converter.rx5.model import native_tree

A = (17, 70000, 70000)
B = (18, 140003, 140002)
Q = 2147483649


def sha(raw):
    return hashlib.sha256(raw).hexdigest()


def fixture():
    x, y = A[1]*Q//(1 << A[0]), A[2]*Q//(1 << A[0])
    points = [(x-2000000, y-40000000), (x-1990000, y-40000000),
              (x-1990000, y-39990000), (x-2000000, y-40000000)]
    co = struct.pack('<I', 1 << 24 | 4)+b''.join(struct.pack('<II', *v) for v in points)
    ix = struct.pack('<II', 1, 0x18000000)
    fi = b''.join(wire.FEATURE.pack(u, 0, 0, 70, 0, 42, 3, 0, 0, 0) for u in (11, 22))
    ctx = [(0x100ff, b'OWN_CATEGORY'), (0x30001, b'AC(OWN_COLOR);'),
           (0x10103, b'OWN_DETAIL'), (0x30000, b'AC(OWN_COLOR);')]
    ro = struct.pack('<HI', len(ctx), 0)+b''.join(struct.pack('<IH', k, len(v))+v for k, v in ctx)
    ao = struct.pack('<QHHH', 11, 0, 0, 0)
    buffers = fi, ix, co, ro, ao
    nodes = set()
    for z, tx, ty in (A, B):
        nodes.update((l, tx >> (z-l), ty >> (z-l)) for l in range(z+1))
    name = lambda t: 'node-'+'.'.join(map(str, t))
    authored = [wire.AuthoredBlock(n, bid, 0, b'', data) for n, bid, data in
                (('i', 0x1101, ix), ('c', 0x1102, co), ('r', 0x1103, ro), ('a', 0x1104, ao))]
    authored += [wire.AuthoredBlock('resource', 65, 0, b'', b'OWN_RESOURCE')]
    for t in sorted(nodes):
        z, tx, ty = t
        refs = tuple((8*s, name(k) if k in nodes else None) for s in range(4)
                     for k in [(z+1, 2*tx+(s & 1), 2*ty+(s >> 1))])
        vector = t in (A, B)
        if vector:
            refs += tuple((32+8*s, n) for s, n in enumerate(('i', 'c', 'r', 'a')))
        authored.append(wire.AuthoredBlock(name(t), 0x6000+z, 3 if vector else 1,
                        bytes(64 if vector else 32), fi if vector else b'', references=refs))
    raw = wire.assemble(header.new_plain_vector_header(bytes(range(16))), authored,
        {s: name((0, 0, 0)) if s == 160 else 'resource' if s == 184 else None for s in wire.HEADER_SLOTS})[0]
    hashes = dict(zip(p.NAMES, map(sha, buffers)))
    exact = []
    for tile in (A, B):
        q = exact_level_noink.prepare_exact_level(*buffers, exact_tile=tile, owner_uids=[11], expected_input_sha256=hashes)
        exact.append(dict(exactTile=tile, ownerUIDs=[11], expectedPreparationSHA256=exact_level_noink.plan_sha256(q['plan'])))
    ep = e.plan_exact_level_children(raw, exact)
    raw, _ = e.build_exact_level_children(raw, exact, expected_source_sha256=sha(raw), expected_plan_sha256=e.exact_level_children_plan_sha256(ep))
    rows = []
    for tile in (A, B):
        q = p.prepare_bounded_slab(*buffers, source_tile=tile, owner_uids=[11], expected_input_sha256=hashes)
        rows.append(dict(sourceTile=tile, ownerUIDs=[11], expectedPreparationSHA256=p.plan_sha256(q['plan'])))
    return raw, buffers, rows


def apply(raw, rows):
    plan = c.plan_source_slab_children(raw, rows)
    return c.build_source_slab_children(raw, rows, expected_source_sha256=sha(raw),
        expected_plan_sha256=c.source_slab_children_plan_sha256(plan))


def test_actual_slab_preserves_blank_source_and_all_sixteen_complete_restores():
    raw, buffers, rows = fixture(); out, receipt = apply(raw, rows)
    old, new = wire.blocks(raw), wire.blocks(out)
    ot, nt = native_tree(raw), native_tree(out)
    by = {t: at for at, t in nt.items()}
    assert len(nt) == len(ot)+32
    assert len(receipt['allowedOriginalPrefixEdits']) == 9
    assert len(receipt['actualSubtreeBarriers']) == 2
    for tile in (A, B):
        source = by[tile]; assert source in ot
        assert bytes(new[source].payload) == bytes(old[source].payload)
        assert struct.unpack_from('<4Q', new[source].prefix, 32) == struct.unpack_from('<4Q', old[source].prefix, 32)
        old_child = struct.unpack_from('<Q', old[source].prefix)[0]
        old_pointers = struct.unpack_from('<4Q', old[old_child].prefix, 32)
        for s, at in enumerate(struct.unpack_from('<4Q', new[source].prefix)):
            n = new[at]; assert n.flags == 1 and n.next_offset == 0 and bytes(n.payload) == b''
            assert len(n.prefix) == 32 and nt[at] == (tile[0]+1, 2*tile[1]+(s & 1), 2*tile[2]+(s >> 1))
            for k, child in enumerate(struct.unpack_from('<4Q', n.prefix)):
                leaf = new[child]; assert leaf.flags == 3 and leaf.next_offset == 0
                assert bytes(leaf.payload) == buffers[0]
                assert struct.unpack_from('<8Q', leaf.prefix) == (0, 0, 0, 0, *old_pointers)
    inverse = bytearray(out[:len(raw)])
    for r in receipt['allowedOriginalPrefixEdits']:
        inverse[r['offset']:r['offset']+r['bytes']] = bytes.fromhex(r['originalHex'])
    assert bytes(inverse) == raw and receipt['sourcePrefixInverseSHA256'] == sha(raw)
    assert receipt['nativeSourceScaleQueryRasterAcceptance'] is False


def test_all_four_child_slots_fallback_and_all_deeper_slots_restore():
    raw, buffers, rows = fixture(); out, _ = apply(raw, rows[:1])
    parsed = wire.blocks(out); tree = native_tree(out)
    for z in range(A[0], 30):
        span = 1 << (z-A[0])
        for dx, dy in ((0, 0), (span-1, 0), (0, span-1), (span-1, span-1)):
            rx, ry = A[1]*span+dx, A[2]*span+dy
            vectors = [(t, at) for at, t in tree.items() if parsed[at].flags == 3 and t[0] <= z
                and (rx >> (z-t[0]), ry >> (z-t[0])) == t[1:]]
            t, at = max(vectors)
            if z <= A[0]+1:
                assert t == A
            else:
                assert t == (A[0]+2, rx >> (z-A[0]-2), ry >> (z-A[0]-2))
                assert bytes(parsed[at].payload) == buffers[0]


def test_deterministic_profile_order_and_empty_request_identity():
    raw, _, rows = fixture()
    a, _ = apply(raw, rows); b, _ = apply(raw, list(reversed(rows)))
    assert a == b and a[56:72] != raw[56:72]
    out, receipt = apply(raw, [])
    assert out is raw and receipt['status'] == 'NO_CHANGE'


@pytest.mark.parametrize('which', ['source', 'plan', 'preparation'])
def test_source_and_plan_hashes_are_enforced(which):
    raw, _, rows = fixture(); plan = c.plan_source_slab_children(raw, rows)
    source, digest = sha(raw), c.source_slab_children_plan_sha256(plan)
    if which == 'source': source = '0'*64
    elif which == 'plan': digest = '0'*64
    else: rows[0]['expectedPreparationSHA256'] = '0'*64
    with pytest.raises(ValueError):
        c.build_source_slab_children(raw, rows, expected_source_sha256=source, expected_plan_sha256=digest)


@pytest.mark.parametrize('which', ['missingChild', 'nonleaf', 'wrongRO', 'changedFI', 'changedBlank', 'missingSource'])
def test_actual_existing_restore_or_blank_mismatch_refuses(which):
    raw, _, rows = fixture(); parsed = wire.blocks(raw); by = {t: at for at, t in native_tree(raw).items()}
    source = by[A]; child = struct.unpack_from('<Q', parsed[source].prefix)[0]
    changed = bytearray(raw)
    if which == 'missingChild': struct.pack_into('<Q', changed, source+16, 0)
    elif which == 'nonleaf': struct.pack_into('<Q', changed, child+16, by[B])
    elif which == 'wrongRO': struct.pack_into('<Q', changed, child+16+48, struct.unpack_from('<Q', parsed[source].prefix, 48)[0])
    elif which == 'changedFI': changed[child+80+24] ^= 1
    elif which == 'changedBlank': changed[source+80+24] ^= 1
    else: rows[0]['sourceTile'] = (17, 1, 1)
    with pytest.raises(ValueError): c.plan_source_slab_children(bytes(changed), rows[:1])


@pytest.mark.parametrize('which', ['duplicate', 'nested', 'badUID', 'extraField', 'level19'])
def test_explicit_finite_disjoint_profiles(which):
    raw, _, rows = fixture()
    if which == 'duplicate': rows = rows[:1]*2
    elif which == 'nested': rows[1]['sourceTile'] = (18, 2*A[1], 2*A[2])
    elif which == 'badUID': rows[0]['ownerUIDs'] = [True]
    elif which == 'extraField': rows[0]['approved'] = True
    else: rows[0]['sourceTile'] = (19, 1, 1)
    with pytest.raises(ValueError): c.plan_source_slab_children(raw, rows)


def test_materialized_actual_wire_verifier_runs_for_every_profile(monkeypatch):
    raw, _, rows = fixture(); original = p.verify_materialized_slab; calls = []
    def observed(actual, original_buffers, **kwargs):
        calls.append((kwargs['source_tile'], len(actual)))
        return original(actual, original_buffers, **kwargs)
    monkeypatch.setattr(p, 'verify_materialized_slab', observed)
    out, receipt = apply(raw, rows)
    assert calls == [(A, len(out)), (B, len(out))]
    assert all(len(r['actualRoutingChildren']) == 4 and len(r['actualRestoreLeaves']) == 16
               for r in receipt['actualSubtreeBarriers'])
