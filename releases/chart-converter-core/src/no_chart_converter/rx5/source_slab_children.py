"""Optional two-level source slab with sixteen complete restore grandchildren.

This materializer extends an already prepared exact-level source. It reuses
its unchanged feature/render bytes and replaces its four full restore leaves
with routing nodes and sixteen copies of that same complete restore scene.
The original file remains an inverse-verifiable prefix. No default pipeline
or file writing is enabled; native source/scale acceptance is separate.
"""
from __future__ import annotations

import hashlib
import inspect
import json
from pathlib import Path
import struct
import uuid

from .header import validate_plain_vector_header
from .model import native_tree
from .no_ink_children import _validate_policy_output
from .wire import blocks, need

POLICY = 'existing-source-two-level-slab-sixteen-original-restores-append/1'
NAMES = ('features', 'indices', 'coordinates', 'render', 'additional')


def _sha(raw):
    return hashlib.sha256(raw).hexdigest()


def _canonical(value):
    return json.dumps(value, sort_keys=True, separators=(',', ':'), ensure_ascii=True).encode('ascii')


def _policy_module():
    from . import bounded_noink_slab
    return bounded_noink_slab


def _children(tile):
    z, x, y = tile
    return [(z+1, 2*x+(s & 1), 2*y+(s >> 1)) for s in range(4)]


def _profiles(profiles):
    need(type(profiles) in (list, tuple), 'EXPLICIT_SOURCE_SLAB_PROFILES')
    result = []
    for r in profiles:
        need(type(r) is dict and set(r) == {'sourceTile', 'ownerUIDs', 'expectedPreparationSHA256'},
             'SOURCE_SLAB_PROFILE_FIELDS')
        tile = r['sourceTile']
        need(type(tile) in (list, tuple) and len(tile) == 3 and all(type(n) is int for n in tile),
             'INTEGER_SOURCE_SLAB_TILE')
        z, x, y = tile
        need(z in (17, 18) and 0 <= x < 1 << z and 0 <= y < 1 << z, 'FINITE_SOURCE_SLAB_TILE')
        uids = r['ownerUIDs']
        need(type(uids) in (list, tuple) and bool(uids)
             and all(type(u) is int and 0 < u < 1 << 64 for u in uids)
             and len(set(uids)) == len(uids), 'EXPLICIT_UNIQUE_SOURCE_SLAB_UIDS')
        digest = r['expectedPreparationSHA256']
        need(type(digest) is str and len(digest) == 64 and all(c in '0123456789abcdef' for c in digest),
             'EXPLICIT_SLAB_PREPARATION_SHA256')
        result.append(dict(sourceTile=list(tile), ownerUIDs=sorted(uids), expectedPreparationSHA256=digest))
    tiles = [tuple(r['sourceTile']) for r in result]
    need(len(set(tiles)) == len(tiles), 'UNIQUE_SOURCE_SLAB_TILES')
    for a in tiles:
        for b in tiles:
            if a[0] < b[0]:
                need((b[1] >> (b[0]-a[0]), b[2] >> (b[0]-a[0])) != a[1:],
                     'DISJOINT_SOURCE_SLAB_BRANCHES')
    return sorted(result, key=lambda r: r['sourceTile'])


def _buffers(parsed, at):
    n = parsed[at]
    need(n.flags == 3 and n.next_offset == 0 and len(n.prefix) == 64, 'ACTUAL_FULL_VECTOR_NODE')
    ptrs = struct.unpack_from('<4Q', n.prefix, 32)
    values = [bytes(n.payload)]
    for ptr, disk_id in zip(ptrs, (0x1101, 0x1102, 0x1103, 0x1104)):
        need(ptr in parsed, 'CANONICAL_STREAM_POINTER')
        b = parsed[ptr]
        need((b.disk_id, b.flags, bytes(b.prefix), b.next_offset) == (disk_id, 0, b'', 0),
             'CANONICAL_UNCHAINED_STREAM')
        values.append(bytes(b.payload))
    return tuple(values), ptrs


def _prepare(raw, profiles):
    need(type(raw) is bytes, 'IMMUTABLE_PLAIN_SOURCE_BYTES')
    requested = _profiles(profiles)
    validate_plain_vector_header(raw[:240], file_bytes=len(raw))
    need(all(struct.unpack_from('<Q', raw, o)[0] == 0 for o in (152, 176, 192)), 'OWN_PLAIN_CHART_ONLY')
    parsed = blocks(memoryview(raw)); tree = native_tree(raw, parsed)
    by_tile = {tile: at for at, tile in tree.items()}
    policy = _policy_module() if requested else None
    kernel = None if policy is None else dict(module=policy.__name__, sha256=_sha(Path(inspect.getfile(policy)).read_bytes()))
    rows, prepared, used = [], {}, set()
    for row in requested:
        tile = tuple(row['sourceTile'])
        need(tile in by_tile, 'EXISTING_BLANK_SOURCE_REQUIRED')
        at = by_tile[tile]; blank, blank_ptrs = _buffers(parsed, at)
        child_offsets = struct.unpack_from('<4Q', parsed[at].prefix)
        need(all(child_offsets) and len(set(child_offsets)) == 4, 'FOUR_DISTINCT_EXISTING_RESTORES')
        original = original_ptrs = None
        for t, child_at in zip(_children(tile), child_offsets):
            need(tree.get(child_at) == t, 'EXISTING_RESTORE_SLOT_AND_TILE')
            need(struct.unpack_from('<4Q', parsed[child_at].prefix) == (0, 0, 0, 0),
                 'EXISTING_RESTORES_MUST_BE_LEAVES')
            full, ptrs = _buffers(parsed, child_at)
            if original is None:
                original, original_ptrs = full, ptrs
            need(full == original and ptrs == original_ptrs, 'ALL_EXISTING_RESTORES_ONE_COMPLETE_SCENE')
            need(child_at not in used, 'DISJOINT_EXISTING_RESTORE_OWNERSHIP')
            used.add(child_at)
        need((blank_ptrs[0], blank_ptrs[1], blank_ptrs[3]) ==
             (original_ptrs[0], original_ptrs[1], original_ptrs[3]), 'SHARED_CANONICAL_GEOMETRY_QUERY_BLOCKS')
        hashes = {k: _sha(v) for k, v in zip(NAMES, original)}
        p = policy.prepare_bounded_slab(*original, source_tile=tile,
            owner_uids=tuple(row['ownerUIDs']), expected_input_sha256=hashes)
        need(policy.plan_sha256(p['plan']) == row['expectedPreparationSHA256'], 'RECOMPUTED_SLAB_PREPARATION')
        need(p['restoreBuffers'] == original and p['blankBuffers'] == blank,
             'EXISTING_BLANK_AND_FULL_RESTORE_BYTES_MUST_MATCH_PROOF')
        changes = _validate_policy_output(original, blank, row['ownerUIDs'])
        routes = _children(tile); leaves = [c for t in routes for c in _children(t)]
        need(all(t not in by_tile for t in leaves), 'ALL_SIXTEEN_RESTORE_TILES_MUST_BE_NEW')
        item = dict(**row, sourceNodeOffset=at, originalRestoreOffsets=list(child_offsets),
            originalStreams=list(original_ptrs), blankStreams=list(blank_ptrs),
            inputSHA256=hashes, blankBufferPins={k: dict(bytes=len(v), sha256=_sha(v)) for k, v in zip(NAMES, blank)},
            routingChildren=[list(t) for t in routes], restoreGrandchildren=[list(t) for t in leaves],
            existingRenderOffsetChanges=changes,
            preparationBinding=dict(bytes=len(_canonical(p['plan'])), sha256=policy.plan_sha256(p['plan'])),
            retained=p['plan']['countsRetainedPerNode'])
        rows.append(item); prepared[tile] = item, original
    plan = dict(format='no5-existing-source-slab-sixteen-restores-plan/1', policy=POLICY,
        source=dict(bytes=len(raw), sha256=_sha(raw)), preparationModule=kernel, profiles=rows,
        originalTreeNodes=len(tree), existingBlankSources=len(rows), routingNodes=4*len(rows),
        actualRestoreLeaves=16*len(rows), allOriginalQueryGeometryRetained=True,
        nativeSourceScaleQueryRasterAcceptance=False, automaticPipelineActivation=False)
    return plan, parsed, tree, prepared, policy


def plan_source_slab_children(raw: bytes, profiles) -> dict:
    """Bind actual existing blank/restores to recomputed finite slab proofs."""
    return _prepare(raw, profiles)[0]


def source_slab_children_plan_sha256(plan: dict) -> str:
    return _sha(_canonical(plan))


def build_source_slab_children(raw: bytes, profiles, *, expected_source_sha256: str,
                               expected_plan_sha256: str) -> tuple[bytes, dict]:
    """Append actual routing/restores and verify the complete materialized slab."""
    plan, before, tree, prepared, policy = _prepare(raw, profiles)
    need(plan['source']['sha256'] == expected_source_sha256, 'EXACT_SOURCE_SHA256')
    digest = source_slab_children_plan_sha256(plan)
    need(digest == expected_plan_sha256, 'EXACT_RECOMPUTED_MATERIALIZER_PLAN')
    if not prepared:
        return raw, dict(format='no5-source-slab-result/1', status='NO_CHANGE', sourcePlanSHA256=digest,
            appendBytes=0, allowedOriginalPrefixEdits=[], nativeAcceptance=False)
    output = bytearray(raw); specs = {}; offsets = {}; edits = []
    for parent, (item, original) in sorted(prepared.items()):
        for child in map(tuple, item['routingChildren']):
            specs[child] = parent, True
        for child in map(tuple, item['restoreGrandchildren']):
            specs[child] = parent, False
    cursor = len(output)
    for tile, (parent, route) in sorted(specs.items()):
        offsets[tile] = cursor
        cursor += 48 if route else 80+len(prepared[parent][1][0])
    for tile, (parent, route) in sorted(specs.items()):
        item, original = prepared[parent]
        need(len(output) == offsets[tile], 'DETERMINISTIC_SLAB_NODE_OFFSET')
        if route:
            prefix = struct.pack('<4Q', *(offsets[t] for t in _children(tile)))
            output += struct.pack('<HHIQ', 0x6000+tile[0], 1, 32, 0)+prefix
        else:
            prefix = struct.pack('<8Q', 0, 0, 0, 0, *item['originalStreams'])
            output += struct.pack('<HHIQ', 0x6000+tile[0], 3, 64+len(original[0]), 0)+prefix+original[0]
    need(len(output) == cursor, 'EXACT_APPEND_EXTENT')
    for tile, (item, _) in sorted(prepared.items()):
        for slot, child in enumerate(map(tuple, item['routingChildren'])):
            at = item['sourceNodeOffset']+16+8*slot
            old = raw[at:at+8]
            need(struct.unpack('<Q', old)[0] == item['originalRestoreOffsets'][slot], 'EXACT_EXISTING_SOURCE_CHILD_LINK')
            edits.append(dict(offset=at, bytes=8, originalHex=old.hex()))
            struct.pack_into('<Q', output, at, offsets[child])
    edits.append(dict(offset=56, bytes=16, originalHex=raw[56:72].hex()))
    output[56:72] = uuid.uuid5(uuid.NAMESPACE_URL, POLICY+':'+digest).bytes_le
    result = bytes(output); validate_plain_vector_header(result[:240], file_bytes=len(result))
    after = blocks(memoryview(result)); actual_tree = native_tree(result, after)
    old_leaves = {at for item, _ in prepared.values() for at in item['originalRestoreOffsets']}
    need(all(actual_tree.get(at) == tile for at, tile in tree.items() if at not in old_leaves),
         'ALL_UNREPLACED_ORIGINAL_NODE_ADDRESSES_RETAINED')
    need(set(actual_tree.values()) == set(tree.values()) | set(specs)
         and len(actual_tree) == len(tree)+16*len(prepared), 'EXACT_ROUTING_AND_SIXTEEN_RESTORE_TREE')
    need(all(actual_tree.get(offsets[t]) == t for t in specs), 'ACTUAL_NEW_NODE_TILE_BINDING')
    barriers = []
    for tile, (item, original) in sorted(prepared.items()):
        need(bytes(after[item['sourceNodeOffset']].payload) == bytes(before[item['sourceNodeOffset']].payload),
             'EXISTING_BLANK_FEATURES_UNCHANGED')
        barriers.append(policy.verify_materialized_slab(result, original, source_tile=tile,
            owner_uids=tuple(item['ownerUIDs']), expected_input_sha256=item['inputSHA256'],
            expected_plan_sha256=item['expectedPreparationSHA256']))
    previous = 0; inverse = hashlib.sha256()
    for e in sorted(edits, key=lambda e: e['offset']):
        at = e['offset']; old = bytes.fromhex(e['originalHex'])
        need(at >= previous and result[previous:at] == raw[previous:at], 'ALL_UNDECLARED_ORIGINAL_PREFIX_BYTES_EXACT')
        inverse.update(result[previous:at]); inverse.update(old); previous = at+len(old)
    need(result[previous:len(raw)] == raw[previous:], 'ALL_UNDECLARED_ORIGINAL_SUFFIX_BYTES_EXACT')
    inverse.update(result[previous:len(raw)])
    need(inverse.hexdigest() == plan['source']['sha256'], 'FULL_SOURCE_PREFIX_INVERSE')
    return result, dict(format='no5-source-slab-result/1', policy=POLICY,
        status='HOST_BUILT_ACTUAL_SLAB_BARRIER_PASS_NATIVE_PENDING', source=plan['source'],
        output=dict(bytes=len(result), sha256=_sha(result), uuidHex=result[56:72].hex()),
        sourcePlanSHA256=digest, preparationModule=plan['preparationModule'],
        appendBytes=len(result)-len(raw), allowedOriginalPrefixEdits=sorted(edits, key=lambda e: e['offset']),
        sourcePrefixInverseSHA256=inverse.hexdigest(), originalTreeNodes=len(tree), outputTreeNodes=len(actual_tree),
        actualSubtreeBarriers=barriers,
        appliedProfiles=[dict(sourceTile=list(t), sourceNodeOffset=item['sourceNodeOffset'],
            originalRestoreOffsets=item['originalRestoreOffsets'], retained=item['retained'],
            routingNodes=[dict(tile=list(c), offset=offsets[tuple(c)]) for c in item['routingChildren']],
            restoreLeaves=[dict(tile=list(c), offset=offsets[tuple(c)]) for c in item['restoreGrandchildren']])
            for t, (item, _) in sorted(prepared.items())],
        allOriginalPhysicalBlocksRetained=True, fullCanonicalQueryGeometryRetained=True,
        originalOtherOwnerRenderValuesRetained=True, nativeSourceScaleQueryRasterAcceptance=False,
        automaticPipelineActivation=False)
