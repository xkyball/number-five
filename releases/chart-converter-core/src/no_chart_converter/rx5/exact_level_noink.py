"""Optional exact-level planner; never activated by the default pipeline.

The bounded projection certificate is admissible only with four real full
restore children. verify_materialized_subtree recomputes the plan and inspects
actual RX5 node/stream bytes. A claimed max zoom or receipt boolean cannot
replace that barrier. Original query geometry and metadata remain complete.
"""
from __future__ import annotations
import hashlib
import json
import struct

from no_chart_converter.rx5 import locality_empty, locality_parts, metadata, render_noink
from no_chart_converter.rx5.header import validate_plain_vector_header
from no_chart_converter.rx5.model import native_tree, parts
from no_chart_converter.rx5.wire import FEATURE, blocks, need

POLICY = 'staged-exact-level-no-ink-four-full-restore-leaves/1'
TREE_CONTRACT_SHA256 = '37af24b6d8958baf9baa39076b601ddd7d2df0ee7a653e56c6fedf30b3dc4173'
QUERY_CONTRACT_SHA256 = 'fe8233a8d86d9957e0e357091ff7ba644d2813703d7ea13d527830b463a32e12'
NAMES = render_noink.NAMES
RESULT_KEYS = ('featureBytes', 'indexBytes', 'coordinateBytes', 'renderBytes', 'additionalBytes')
ROOT = 2147483649


def _sha(raw):
    return hashlib.sha256(raw).hexdigest()


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(',', ':'), ensure_ascii=True).encode('ascii')


def plan_sha256(plan):
    return _sha(canonical(plan))


def _children(tile):
    z, x, y = tile
    return [(z+1, 2*x+(slot & 1), 2*y+(slot >> 1)) for slot in range(4)]


def _exact_certificate(record, indices, coordinates, tile):
    z, x, y = tile
    pp = list(parts(record, indices, coordinates))
    need(FEATURE.unpack(record)[6] == 3 and bool(pp), 'COMPLETE_KIND3_OWNER_REQUIRED')
    guard, error = 128, (1 << max(z-12, 0))+8
    need(error+3 < guard-32, 'EXACT_LEVEL_PROJECTION_ERROR_WITHIN_DRAW_HALO')
    den = 256*(1 << z)
    domain = locality_empty.Domain(den, (256*x-guard)*ROOT, (256*y-guard)*ROOT,
                                  (256*(x+1)+guard)*ROOT, (256*(y+1)+guard)*ROOT)
    proof = []
    for p in pp:
        points = p['points']
        need(p['rootFlag'] == 1 and p['usage'] in locality_empty.USAGES
             and len(points) >= 4 and points[0] == points[-1], 'KNOWN_CLOSED_ROOT1_PART_REQUIRED')
        need(not any(domain.hits(a, b) for a, b in zip(points, points[1:]))
             and domain.winding(points) == 0, 'ALL_PARTS_OUTSIDE_EXACT_LEVEL_DRAW_WINDOW')
        projection = locality_parts.projection_safety(points, tile, max_level=z)
        need(projection is not None, 'SAFE_PROJECTION_AT_EXACT_LEVEL_REQUIRED')
        proof.append({'partOrdinal': p['partOrdinal'], 'usage': p['usage'], 'points': p['pointCount'],
                      'typedElementSHA256': _sha(p['raw']), 'projection': projection})
    return {'exactLevel': z, 'sourceTile': list(tile), 'domain': domain.serial(),
            'geometryGuardSourcePixels': guard, 'protectedHaloSourcePixels': 32,
            'conversionErrorPixels': error, 'conservativeSupportPixels': 3,
            'parts': proof, 'validForDeeperRequestsWithoutRestoreBarrier': False}


def prepare_exact_level(features, indices, coordinates, render, additional, *,
                        exact_tile, owner_uids, expected_input_sha256):
    """Prepare one blank node and four identical original-scene restore leaves.

    This finite pilot supports exactlyL17/L18. The deeper-level boundary is
    represented by real child nodes, never by assuming the user cannot zoom.
    Returned buffers alone are not a qualified materialization.
    """
    original = (features, indices, coordinates, render, additional)
    need(all(type(b) is bytes for b in original), 'IMMUTABLE_FIVE_BUFFERS')
    need(len(features) % 32 == 0, 'COMPLETE_FEATURE32_ARRAY')
    tile = render_noink._tile(exact_tile)
    need(tile[0] in (17, 18), 'FINITE_PILOT_EXACT_LEVEL17_OR18_ONLY')
    need(type(owner_uids) in (list, tuple) and bool(owner_uids)
         and all(type(u) is int and 0 < u < 1 << 64 for u in owner_uids)
         and len(set(owner_uids)) == len(owner_uids), 'EXPLICIT_UNIQUE_OWNER_UIDS')
    requested = sorted(owner_uids)
    input_pins = render_noink._pins(original)
    need(type(expected_input_sha256) is dict and set(expected_input_sha256) == set(NAMES)
         and all(type(expected_input_sha256[k]) is str and expected_input_sha256[k] == input_pins[k]['sha256'] for k in NAMES),
         'EXACT_FIVE_INPUT_SHA256')
    counts = dict(records=len(features)//32, parts=0, XY=0, XYZ=0)
    selected = {}
    # Restore-source relocation must never change a relative/root0 geometry.
    for ordinal, f in enumerate(FEATURE.iter_unpack(features)):
        record = features[ordinal*32:(ordinal+1)*32]
        for p in parts(record, indices, coordinates):
            need(p['rootFlag'] == 1, 'WHOLE_CLONED_SCENE_ABSOLUTE_ROOT1_REQUIRED')
            counts['parts'] += 1
            counts['XYZ' if f[6] in (4, 9) else 'XY'] += p['pointCount']
        if f[0] in requested:
            need(f[0] not in selected, 'SELECTED_UID_MUST_BE_UNAMBIGUOUS')
            selected[f[0]] = ordinal
    need(set(selected) == set(requested), 'EVERY_SELECTED_UID_PRESENT')
    output_f, output_ro = bytearray(features), bytearray(render)
    frames, owners = {}, []
    for uid in requested:
        ordinal = selected[uid]
        record = features[ordinal*32:(ordinal+1)*32]
        fields = FEATURE.unpack(record)
        contexts, query, old_frame = render_noink._frame(render, fields[2])
        by_key = dict(contexts)
        need(all(k in by_key and render_noink._pure_ac(by_key[k]) for k in render_noink.DRAW_CONTEXTS),
             'BOTH_ORIGINAL_DRAW_VALUES_PURE_AC_REQUIRED')
        query_raw = metadata.additional_frame(metadata.Stream(len(additional), lambda a, n: additional[a:a+n]), query)
        certificate = _exact_certificate(record, indices, coordinates, tile)
        if fields[2] not in frames:
            values = [(k, b'' if k in render_noink.DRAW_CONTEXTS else value) for k, value in contexts]
            new_frame = struct.pack('<HI', len(values), query)+b''.join(struct.pack('<IH', k, len(v))+v for k, v in values)
            need(len(output_ro)+len(new_frame) <= 0xffffffff, 'RENDER_PAYLOAD_U32_EXTENT')
            frames[fields[2]] = len(output_ro)
            output_ro += new_frame
        struct.pack_into('<I', output_f, ordinal*32+12, frames[fields[2]])
        owners.append({'uid': uid, 'recordOrdinal': ordinal, 'oldRenderOffset': fields[2],
                       'blankRenderOffset': frames[fields[2]], 'originalRenderFrameSHA256': _sha(old_frame),
                       'originalQueryFrameSHA256': _sha(query_raw), 'exactLevelCertificate': certificate})
    blank = (bytes(output_f), indices, coordinates, bytes(output_ro), additional)
    plan = {'format': 'no5-staged-exact-level-four-restore-plan/1', 'policy': POLICY,
            'exactTile': list(tile), 'selectedOwnerUIDs': requested, 'inputPins': input_pins,
            'blankBufferPins': render_noink._pins(blank), 'restoreBufferPins': input_pins,
            'restores': [{'slot': slot, 'tile': list(child), 'flags': 3, 'mustBeLeaf': True}
                         for slot, child in enumerate(_children(tile))],
            'countsRetainedPerNode': counts, 'owners': owners,
            'mandatoryActualWireRestoreBarrier': True, 'standaloneBlankSourceAllowed': False,
            'treeContractSHA256': TREE_CONTRACT_SHA256, 'queryContractSHA256': QUERY_CONTRACT_SHA256,
            'nativeSourceScaleFrameQueryAcceptance': False, 'currentPipelineChanged': False,
            'sourceLevelChangesRequireNativeAcceptance': True,
            'requiredNativeSentinelLevels': [tile[0], tile[0]+1, tile[0]+2]}
    return {'plan': plan, 'blankBuffers': blank, 'restoreBuffers': original}


def _node_buffers(parsed, at):
    node = parsed[at]
    need(node.flags == 3 and node.next_offset == 0, 'ACTUAL_FULL_UNCHAINED_VECTOR_SOURCE_REQUIRED')
    pointers = struct.unpack_from('<4Q', node.prefix, 32)
    result = [bytes(node.payload)]
    for ptr, disk_id in zip(pointers, (0x1101, 0x1102, 0x1103, 0x1104)):
        need(ptr in parsed, 'ACTUAL_STREAM_POINTER_RESOLVES')
        b = parsed[ptr]
        need((b.disk_id, b.flags, bytes(b.prefix), b.next_offset) == (disk_id, 0, b'', 0), 'ACTUAL_CANONICAL_STREAM_REQUIRED')
        result.append(bytes(b.payload))
    return tuple(result), pointers


def verify_materialized_subtree(raw, original_buffers, *, exact_tile, owner_uids,
                                expected_input_sha256, expected_plan_sha256):
    """Verify the barrier from actual bytes, recomputing every original proof.

    The caller separately owns full-file prefix/source binding and publication.
    This inspects only the exact node and its four restore leaves; it does not
    claim normal native source/scale/frame or query acceptance.
    """
    prepared = prepare_exact_level(*original_buffers, exact_tile=exact_tile, owner_uids=owner_uids,
                                  expected_input_sha256=expected_input_sha256)
    plan = prepared['plan']
    need(type(expected_plan_sha256) is str and plan_sha256(plan) == expected_plan_sha256, 'EXACT_RECOMPUTED_PLAN_SHA256')
    need(type(raw) is bytes, 'IMMUTABLE_ACTUAL_CHART_BYTES')
    validate_plain_vector_header(raw[:240], file_bytes=len(raw))
    parsed = blocks(memoryview(raw)); tree = native_tree(raw, parsed); by_tile = {v: k for k, v in tree.items()}
    tile = tuple(plan['exactTile'])
    need(tile in by_tile, 'ACTUAL_EXACT_SOURCE_NODE_REQUIRED')
    at = by_tile[tile]; actual, pointers = _node_buffers(parsed, at)
    need(render_noink._pins(actual) == plan['blankBufferPins'], 'ACTUAL_BLANK_SCENE_MUST_MATCH_RECOMPUTED_PLAN')
    child_offsets = struct.unpack_from('<4Q', parsed[at].prefix)
    need(all(child_offsets) and len(set(child_offsets)) == 4, 'ALL_FOUR_DISTINCT_ACTUAL_RESTORE_CHILDREN_REQUIRED')
    restored = []; restore_ro = None
    for spec, child_at in zip(plan['restores'], child_offsets):
        need(tree.get(child_at) == tuple(spec['tile']), 'ACTUAL_RESTORE_SLOT_AND_TILE_MATCH')
        child, child_pointers = _node_buffers(parsed, child_at)
        need(struct.unpack_from('<4Q', parsed[child_at].prefix) == (0, 0, 0, 0), 'RESTORE_NODES_MUST_BE_ACTUAL_LEAVES')
        need(render_noink._pins(child) == plan['restoreBufferPins'], 'COMPLETE_ORIGINAL_RESTORE_SCENE_BYTES_REQUIRED')
        need((child_pointers[0], child_pointers[1], child_pointers[3]) == (pointers[0], pointers[1], pointers[3]),
             'RESTORES_MUST_SHARE_CANONICAL_INDEX_COORDINATE_ADDITIONAL_BLOCKS')
        if restore_ro is None: restore_ro = child_pointers[2]
        need(restore_ro == child_pointers[2], 'RESTORES_SHARE_ONE_ORIGINAL_RO_BLOCK')
        restored.append({'slot': spec['slot'], 'tile': spec['tile'], 'offset': child_at})
    return {'format': 'no5-staged-actual-wire-exact-level-barrier/1', 'planSHA256': expected_plan_sha256,
            'exactTile': list(tile), 'blankNodeOffset': at, 'actualRestoreLeaves': restored,
            'exactLevelUsesBlankNode': True, 'everyDeeperNormalRequestSelectsOriginalSceneBuffers': True,
            'originalSourceLevelEquivalenceClaim': False,
            'knownNormalRequestMaximumLevel': 29, 'treeContractSHA256': TREE_CONTRACT_SHA256,
            'nativeSourceScaleFrameQueryAcceptance': False, 'fullCountryInverseClaim': False}
