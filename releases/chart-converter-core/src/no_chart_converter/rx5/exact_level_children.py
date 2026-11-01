"""Opt-in exact-level no-ink nodes with four actual full restore children.

The original file is an inverse-verifiable prefix. Existing selected vector
leaves are replaced through their parent links; their physical bytes remain.
Each replacement keeps complete canonical query geometry and has four full
original-scene restore leaves. This API never writes files or activates itself
in the normal conversion pipeline. Native source/scale/render acceptance is a
separate caller obligation.
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

POLICY = 'exact-level-full-geometry-four-original-restore-children-append/1'
NAMES = ('features', 'indices', 'coordinates', 'render', 'additional')


def _sha(raw):
    return hashlib.sha256(raw).hexdigest()


def _canonical(value):
    return json.dumps(value, sort_keys=True, separators=(',', ':'), ensure_ascii=True).encode('ascii')


def _policy_module():
    from . import exact_level_noink
    return exact_level_noink


def _profiles(profiles):
    need(type(profiles) in (list, tuple), 'EXPLICIT_EXACT_LEVEL_PROFILES')
    result = []
    for row in profiles:
        need(type(row) is dict and set(row) == {'exactTile', 'ownerUIDs', 'expectedPreparationSHA256'},
             'EXACT_LEVEL_PROFILE_FIELDS')
        tile = row['exactTile']
        need(type(tile) in (list, tuple) and len(tile) == 3 and all(type(x) is int for x in tile),
             'INTEGER_EXACT_TILE')
        z, x, y = tile
        need(z in (17, 18) and 0 <= x < 1 << z and 0 <= y < 1 << z, 'EXACT_LEVEL_PILOT_TILE')
        uids = row['ownerUIDs']
        need(type(uids) in (list, tuple) and bool(uids)
             and all(type(u) is int and 0 < u < 1 << 64 for u in uids)
             and len(set(uids)) == len(uids), 'EXPLICIT_UNIQUE_EXACT_LEVEL_UIDS')
        digest = row['expectedPreparationSHA256']
        need(type(digest) is str and len(digest) == 64 and all(c in '0123456789abcdef' for c in digest),
             'EXPLICIT_PREPARATION_SHA256')
        result.append(dict(exactTile=list(tile), ownerUIDs=sorted(uids), expectedPreparationSHA256=digest))
    need(len({tuple(r['exactTile']) for r in result}) == len(result), 'UNIQUE_EXACT_LEVEL_TILES')
    return sorted(result, key=lambda r: r['exactTile'])


def _source_buffers(parsed, at):
    node = parsed[at]
    need(node.flags == 3 and node.next_offset == 0 and struct.unpack_from('<4Q', node.prefix) == (0, 0, 0, 0),
         'EXISTING_FULL_VECTOR_LEAF_REQUIRED')
    pointers = struct.unpack_from('<4Q', node.prefix, 32)
    buffers = [bytes(node.payload)]
    for ptr, disk_id in zip(pointers, (0x1101, 0x1102, 0x1103, 0x1104)):
        need(ptr in parsed, 'EXISTING_CANONICAL_STREAM')
        stream = parsed[ptr]
        need((stream.disk_id, stream.flags, bytes(stream.prefix), stream.next_offset) == (disk_id, 0, b'', 0),
             'CANONICAL_UNCHAINED_STREAM')
        buffers.append(bytes(stream.payload))
    return tuple(buffers), pointers


def _prepare(raw, profiles):
    need(type(raw) is bytes, 'IMMUTABLE_PLAIN_SOURCE_BYTES')
    requested = _profiles(profiles)
    validate_plain_vector_header(raw[:240], file_bytes=len(raw))
    need(all(struct.unpack_from('<Q', raw, o)[0] == 0 for o in (152, 176, 192)), 'OWN_PLAIN_CHART_ONLY')
    parsed = blocks(memoryview(raw)); tree = native_tree(raw, parsed)
    by_tile = {tile: at for at, tile in tree.items()}
    policy = _policy_module() if requested else None
    kernel = None if policy is None else dict(module=policy.__name__, sha256=_sha(Path(inspect.getfile(policy)).read_bytes()))
    links = {}
    for parent in tree:
        for slot, child in enumerate(struct.unpack_from('<4Q', parsed[parent].prefix)):
            if child:
                need(child not in links, 'UNIQUE_ORIGINAL_PARENT_LINK')
                links[child] = parent + 16 + 8*slot
    prepared, rows, all_new_tiles = {}, [], set()
    for row in requested:
        tile = tuple(row['exactTile'])
        need(tile in by_tile and by_tile[tile] in links, 'EXISTING_NONROOT_EXACT_SOURCE')
        at = by_tile[tile]; original, pointers = _source_buffers(parsed, at)
        hashes = {k: _sha(v) for k, v in zip(NAMES, original)}
        result = policy.prepare_exact_level(*original, exact_tile=tile,
            owner_uids=tuple(row['ownerUIDs']), expected_input_sha256=hashes)
        proof, blank, restore = result['plan'], result['blankBuffers'], result['restoreBuffers']
        need(policy.plan_sha256(proof) == row['expectedPreparationSHA256'], 'RECOMPUTED_EXACT_LEVEL_PREPARATION')
        need(type(blank) is tuple and type(restore) is tuple and len(blank) == len(restore) == 5,
             'EXACT_FIVE_BLANK_AND_RESTORE_BUFFERS')
        need(restore == original, 'RESTORES_PRESERVE_COMPLETE_ORIGINAL_SCENE')
        changes = _validate_policy_output(original, blank, row['ownerUIDs'])
        need(blank[3].startswith(original[3]), 'BLANK_RO_RETAINS_ORIGINAL_PREFIX')
        children = [(tile[0]+1, 2*tile[1]+(s & 1), 2*tile[2]+(s >> 1)) for s in range(4)]
        need(all(c not in by_tile and c not in all_new_tiles for c in children), 'ALL_FOUR_RESTORE_TILES_MUST_BE_NEW')
        all_new_tiles.update(children)
        item = dict(**row, originalNodeOffset=at, originalParentLinkOffset=links[at],
            originalStreams=list(pointers), inputSHA256=hashes,
            blankBufferPins={k: dict(bytes=len(v), sha256=_sha(v)) for k, v in zip(NAMES, blank)},
            restoreChildren=[list(c) for c in children], renderOffsetChanges=changes,
            preparationBinding=dict(bytes=len(_canonical(proof)), sha256=policy.plan_sha256(proof)),
            retained=proof['countsRetainedPerNode'])
        rows.append(item); prepared[tile] = item, original, blank
    plan = dict(format='no5-exact-level-four-restore-child-plan/1', policy=POLICY,
        source=dict(bytes=len(raw), sha256=_sha(raw)), preparationModule=kernel, profiles=rows,
        originalTreeNodes=len(tree), replacementNodes=len(rows), actualRestoreLeaves=4*len(rows),
        standaloneBlankSourceAllowed=False, allOriginalQueryGeometryRetained=True,
        nativeSourceScaleQueryRasterAcceptance=False, automaticPipelineActivation=False)
    return plan, parsed, tree, by_tile, prepared, policy


def plan_exact_level_children(raw: bytes, profiles) -> dict:
    """Recompute all source-bound no-ink proofs and bind four restore leaves."""
    return _prepare(raw, profiles)[0]


def exact_level_children_plan_sha256(plan: dict) -> str:
    return _sha(_canonical(plan))


def build_exact_level_children(raw: bytes, profiles, *, expected_source_sha256: str,
                               expected_plan_sha256: str) -> tuple[bytes, dict]:
    """Append replacements/restores and verify their actual complete subtree."""
    plan, before, tree, by_tile, prepared, policy = _prepare(raw, profiles)
    need(plan['source']['sha256'] == expected_source_sha256, 'EXACT_SOURCE_SHA256')
    digest = exact_level_children_plan_sha256(plan)
    need(digest == expected_plan_sha256, 'EXACT_RECOMPUTED_MATERIALIZER_PLAN')
    if not prepared:
        return raw, dict(format='no5-exact-level-four-restore-child-result/1', status='NO_CHANGE',
            sourcePlanSHA256=digest, appendBytes=0, allowedOriginalPrefixEdits=[], nativeAcceptance=False)
    output = bytearray(raw); ro_offsets = {}; node_specs = {}; offsets = {}; edits = []
    for tile, (item, original, blank) in sorted(prepared.items()):
        ro_offsets[tile] = len(output)
        output += struct.pack('<HHIQ', 0x1103, 0, len(blank[3]), 0)+blank[3]
        node_specs[tile] = tile, blank[0], True
        for child in item['restoreChildren']:
            node_specs[tuple(child)] = tile, original[0], False
    cursor = len(output)
    for tile, (_, features, _) in sorted(node_specs.items()):
        offsets[tile] = cursor; cursor += 80+len(features)
    for tile, (parent, features, is_blank) in sorted(node_specs.items()):
        item, original, blank = prepared[parent]; io, co, old_ro, ao = item['originalStreams']
        children = [offsets[tuple(c)] for c in item['restoreChildren']] if is_blank else [0]*4
        prefix = struct.pack('<8Q', *children, io, co, ro_offsets[parent] if is_blank else old_ro, ao)
        need(len(output) == offsets[tile], 'DETERMINISTIC_RESTORE_NODE_OFFSET')
        output += struct.pack('<HHIQ', 0x6000+tile[0], 3, 64+len(features), 0)+prefix+features
    need(len(output) == cursor, 'EXACT_APPEND_EXTENT')
    for tile, (item, _, _) in sorted(prepared.items()):
        at = item['originalParentLinkOffset']; old = raw[at:at+8]
        need(struct.unpack('<Q', old)[0] == item['originalNodeOffset'], 'EXACT_ORIGINAL_SOURCE_PARENT_LINK')
        edits.append(dict(offset=at, bytes=8, originalHex=old.hex()))
        struct.pack_into('<Q', output, at, offsets[tile])
    edits.append(dict(offset=56, bytes=16, originalHex=raw[56:72].hex()))
    output[56:72] = uuid.uuid5(uuid.NAMESPACE_URL, POLICY+':'+digest).bytes_le
    result = bytes(output); validate_plain_vector_header(result[:240], file_bytes=len(result))
    after = blocks(memoryview(result)); actual_tree = native_tree(result, after)
    replaced = {item['originalNodeOffset'] for item, _, _ in prepared.values()}
    need(all(actual_tree.get(at) == tile for at, tile in tree.items() if at not in replaced),
         'ALL_UNREPLACED_ORIGINAL_NODE_ADDRESSES_RETAINED')
    expected_tiles = set(tree.values()) | (set(node_specs)-set(prepared))
    need(set(actual_tree.values()) == expected_tiles and len(actual_tree) == len(tree)+4*len(prepared),
         'EXACT_REPLACEMENTS_AND_FOUR_RESTORE_LEAVES')
    need(all(actual_tree.get(offsets[t]) == t for t in node_specs), 'ACTUAL_NEW_NODE_TILE_BINDING')
    barriers = []
    for tile, (item, original, blank) in sorted(prepared.items()):
        for selected in [tile]+[tuple(c) for c in item['restoreChildren']]:
            node = after[offsets[selected]]; is_blank = selected == tile
            need(node.flags == 3 and node.next_offset == 0 and bytes(node.payload) == (blank[0] if is_blank else original[0]),
                 'COMPLETE_ACTUAL_FEATURE_LIST')
            io, co, ro, ao = struct.unpack_from('<4Q', node.prefix, 32)
            oi, oc, old_ro, oa = item['originalStreams']
            need((io, co, ao) == (oi, oc, oa), 'CANONICAL_GEOMETRY_AND_QUERY_STORAGE_REUSED')
            need(ro == (ro_offsets[tile] if is_blank else old_ro), 'EXACT_BLANK_OR_ORIGINAL_RESTORE_RO_POINTER')
            need(bytes(after[ro].payload) == (blank[3] if is_blank else original[3]), 'COMPLETE_ACTUAL_RENDER_VALUES')
            children = struct.unpack_from('<4Q', node.prefix)
            expected = tuple(offsets[tuple(c)] for c in item['restoreChildren']) if is_blank else (0, 0, 0, 0)
            need(children == expected, 'ACTUAL_FOUR_CHILD_POINTERS_OR_RESTORE_LEAF')
        barrier = policy.verify_materialized_subtree(result, original, exact_tile=tile,
            owner_uids=tuple(item['ownerUIDs']), expected_input_sha256=item['inputSHA256'],
            expected_plan_sha256=item['expectedPreparationSHA256'])
        barriers.append(barrier)
    previous = 0; inverse = hashlib.sha256()
    for e in sorted(edits, key=lambda e: e['offset']):
        at = e['offset']; old = bytes.fromhex(e['originalHex'])
        need(at >= previous and result[previous:at] == raw[previous:at], 'ALL_UNDECLARED_ORIGINAL_PREFIX_BYTES_EXACT')
        inverse.update(result[previous:at]); inverse.update(old); previous = at+len(old)
    need(result[previous:len(raw)] == raw[previous:], 'ALL_UNDECLARED_ORIGINAL_SUFFIX_BYTES_EXACT')
    inverse.update(result[previous:len(raw)]); need(inverse.hexdigest() == plan['source']['sha256'], 'FULL_SOURCE_PREFIX_INVERSE')
    return result, dict(format='no5-exact-level-four-restore-child-result/1', policy=POLICY,
        status='HOST_BUILT_ACTUAL_RESTORE_BARRIER_PASS_NATIVE_PENDING', source=plan['source'],
        output=dict(bytes=len(result), sha256=_sha(result), uuidHex=result[56:72].hex()),
        sourcePlanSHA256=digest, preparationModule=plan['preparationModule'],
        appendBytes=len(result)-len(raw), allowedOriginalPrefixEdits=sorted(edits, key=lambda e: e['offset']),
        sourcePrefixInverseSHA256=inverse.hexdigest(), originalTreeNodes=len(tree), outputTreeNodes=len(actual_tree),
        actualSubtreeBarriers=barriers,
        appliedProfiles=[dict(exactTile=list(t), blankNodeOffset=offsets[t], originalNodeOffset=item['originalNodeOffset'],
            newRenderBlockOffset=ro_offsets[t], renderOffsetChanges=item['renderOffsetChanges'], retained=item['retained'],
            restoreLeaves=[dict(tile=c, offset=offsets[tuple(c)]) for c in item['restoreChildren']])
            for t, (item, _, _) in sorted(prepared.items())],
        allOriginalPhysicalBlocksRetained=True, fullCanonicalQueryGeometryRetained=True,
        originalOtherOwnerRenderValuesRetained=True, standaloneBlankSourceAllowed=False,
        nativeSourceScaleQueryRasterAcceptance=False, automaticPipelineActivation=False)
