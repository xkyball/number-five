"""Optional two-level no-ink slab; not enabled in the conversion pipeline.

The bounded projection certificate is admissible only with four routing-only
children and sixteen real full canonical restore grandchildren.
verify_materialized_slab recomputes the plan and inspects actual RX5 bytes. A claimed max zoom or receipt boolean cannot
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

POLICY = 'staged-two-level-no-ink-sixteen-full-restore-leaves/1'
TREE_CONTRACT_SHA256 = '7d09867db8523f1be4893c587a60e17ee06d8d095e5b905ec66cda74d8915116'
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


def _slab_certificate(record, indices, coordinates, tile):
    z, x, y = tile
    pp = list(parts(record, indices, coordinates))
    need(FEATURE.unpack(record)[6] == 3 and bool(pp), 'COMPLETE_KIND3_OWNER_REQUIRED')
    guard = 128
    error_bounds = []
    for level in (z, z+1):
        error = (1 << max(level-12, 0))+8
        gap = (guard-32)*(1 << (level-z))
        need(error+3 < gap, 'SLAB_PROJECTION_ERROR_WITHIN_DRAW_HALO')
        error_bounds.append({'level': level, 'conversionErrorPixels': error,
                             'protectedHaloGapPixels': gap, 'supportPixels': 3})
    den = 256*(1 << z)
    domain = locality_empty.Domain(den, (256*x-guard)*ROOT, (256*y-guard)*ROOT,
                                  (256*(x+1)+guard)*ROOT, (256*(y+1)+guard)*ROOT)
    proof = []
    for p in pp:
        points = p['points']
        need(p['rootFlag'] == 1 and p['usage'] in locality_empty.USAGES
             and len(points) >= 4 and points[0] == points[-1], 'KNOWN_CLOSED_ROOT1_PART_REQUIRED')
        need(not any(domain.hits(a, b) for a, b in zip(points, points[1:]))
             and domain.winding(points) == 0, 'ALL_PARTS_OUTSIDE_COMPLETE_SOURCE_DRAW_WINDOW')
        projection = locality_parts.projection_safety(points, tile, max_level=z+1)
        need(projection is not None, 'SAFE_PROJECTION_FOR_BOTH_BLANK_LEVELS_REQUIRED')
        proof.append({'partOrdinal': p['partOrdinal'], 'usage': p['usage'], 'points': p['pointCount'],
                      'typedElementSHA256': _sha(p['raw']), 'projection': projection})
    return {'blankLevels': [z, z+1], 'sourceTile': list(tile), 'domain': domain.serial(),
            'geometryGuardSourcePixels': guard, 'protectedHaloSourcePixels': 32,
            'levelErrorBounds': error_bounds, 'conservativeSupportPixels': 3,
            'parts': proof, 'validAtRestoreLevelOrDeeper': False}


def prepare_bounded_slab(features, indices, coordinates, render, additional, *,
                        source_tile, owner_uids, expected_input_sha256):
    """Prepare one blank source over z/z+1 with a real restore barrier at z+2.

    Only source levels17/18 are in this finite prototype. No recursion or
    caller-provided maximum zoom is supported. Every deeper level selects
    canonical restore buffers; source-level native acceptance is separate.
    """
    original = (features, indices, coordinates, render, additional)
    need(all(type(b) is bytes for b in original), 'IMMUTABLE_FIVE_BUFFERS')
    need(len(features) % 32 == 0, 'COMPLETE_FEATURE32_ARRAY')
    tile = render_noink._tile(source_tile)
    need(tile[0] in (17, 18), 'FINITE_PILOT_SOURCE_LEVEL17_OR18_ONLY')
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
        certificate = _slab_certificate(record, indices, coordinates, tile)
        if fields[2] not in frames:
            values = [(k, b'' if k in render_noink.DRAW_CONTEXTS else value) for k, value in contexts]
            new_frame = struct.pack('<HI', len(values), query)+b''.join(struct.pack('<IH', k, len(v))+v for k, v in values)
            need(len(output_ro)+len(new_frame) <= 0xffffffff, 'RENDER_PAYLOAD_U32_EXTENT')
            frames[fields[2]] = len(output_ro)
            output_ro += new_frame
        struct.pack_into('<I', output_f, ordinal*32+12, frames[fields[2]])
        owners.append({'uid': uid, 'recordOrdinal': ordinal, 'oldRenderOffset': fields[2],
                       'blankRenderOffset': frames[fields[2]], 'originalRenderFrameSHA256': _sha(old_frame),
                       'originalQueryFrameSHA256': _sha(query_raw), 'slabCertificate': certificate})
    blank = (bytes(output_f), indices, coordinates, bytes(output_ro), additional)
    plan = {'format': 'no5-staged-two-level-sixteen-restore-plan/1', 'policy': POLICY,
            'sourceTile': list(tile), 'selectedOwnerUIDs': requested, 'inputPins': input_pins,
            'blankBufferPins': render_noink._pins(blank), 'restoreBufferPins': input_pins,
            'blankLevels': [tile[0], tile[0]+1],
            'routingChildren': [{'slot': slot, 'tile': list(child), 'flags': 1, 'payloadBytes': 0}
                                for slot, child in enumerate(_children(tile))],
            'restores': [{'parentSlot': parent_slot, 'slot': slot, 'tile': list(child),
                          'flags': 3, 'mustBeLeaf': True}
                         for parent_slot, parent in enumerate(_children(tile))
                         for slot, child in enumerate(_children(parent))],
            'countsRetainedPerNode': counts, 'owners': owners,
            'mandatoryActualWireSixteenRestoreBarrier': True, 'standaloneBlankSourceAllowed': False,
            'treeContractSHA256': TREE_CONTRACT_SHA256, 'queryContractSHA256': QUERY_CONTRACT_SHA256,
            'nativeSourceScaleFrameQueryAcceptance': False, 'currentPipelineChanged': False,
            'sourceLevelChangesRequireNativeAcceptance': True,
            'requiredNativeSentinelLevels': [tile[0], tile[0]+1, tile[0]+2, tile[0]+3]}
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


def verify_materialized_slab(raw, original_buffers, *, source_tile, owner_uids,
                             expected_input_sha256, expected_plan_sha256):
    """Recompute the proof and inspect all4 routing/16 restore nodes in bytes.

    The caller separately binds the complete source chart, prefix inverse and
    publication. This verifier never treats a receipt as a substitute for the
    actual tree and makes no native scale/frame/query parity claim.
    """
    prepared = prepare_bounded_slab(*original_buffers, source_tile=source_tile,
        owner_uids=owner_uids, expected_input_sha256=expected_input_sha256)
    plan = prepared['plan']
    need(type(expected_plan_sha256) is str and plan_sha256(plan) == expected_plan_sha256,
         'EXACT_RECOMPUTED_SLAB_PLAN_SHA256')
    need(type(raw) is bytes, 'IMMUTABLE_ACTUAL_CHART_BYTES')
    validate_plain_vector_header(raw[:240], file_bytes=len(raw))
    parsed = blocks(memoryview(raw))
    tree = native_tree(raw, parsed)
    by_tile = {tile: at for at, tile in tree.items()}
    tile = tuple(plan['sourceTile'])
    need(tile in by_tile, 'ACTUAL_BLANK_SOURCE_REQUIRED')
    at = by_tile[tile]
    actual, pointers = _node_buffers(parsed, at)
    need(render_noink._pins(actual) == plan['blankBufferPins'],
         'ACTUAL_BLANK_SCENE_MUST_MATCH_RECOMPUTED_PLAN')
    routing_offsets = struct.unpack_from('<4Q', parsed[at].prefix)
    need(all(routing_offsets) and len(set(routing_offsets)) == 4,
         'ALL_FOUR_DISTINCT_ACTUAL_ROUTING_CHILDREN_REQUIRED')
    restored, routing, used_restore, restore_ro = [], [], set(), None
    for route, route_at in zip(plan['routingChildren'], routing_offsets):
        need(tree.get(route_at) == tuple(route['tile']), 'ACTUAL_ROUTING_SLOT_AND_TILE_MATCH')
        node = parsed[route_at]
        need(node.flags == 1 and node.next_offset == 0 and bytes(node.payload) == b''
             and len(node.prefix) == 32, 'ACTUAL_ROUTING_ONLY_NODE_REQUIRED')
        child_offsets = struct.unpack_from('<4Q', node.prefix)
        need(all(child_offsets) and len(set(child_offsets)) == 4,
             'EVERY_ROUTING_CHILD_HAS_FOUR_DISTINCT_RESTORES')
        routing.append({'slot': route['slot'], 'tile': route['tile'], 'offset': route_at})
        specs = [r for r in plan['restores'] if r['parentSlot'] == route['slot']]
        for spec, child_at in zip(specs, child_offsets):
            need(child_at not in used_restore, 'SIXTEEN_DISTINCT_ACTUAL_RESTORE_NODES_REQUIRED')
            used_restore.add(child_at)
            need(tree.get(child_at) == tuple(spec['tile']), 'ACTUAL_RESTORE_SLOT_AND_TILE_MATCH')
            child, child_pointers = _node_buffers(parsed, child_at)
            need(struct.unpack_from('<4Q', parsed[child_at].prefix) == (0, 0, 0, 0),
                 'RESTORE_NODES_MUST_BE_ACTUAL_LEAVES')
            need(render_noink._pins(child) == plan['restoreBufferPins'],
                 'COMPLETE_ORIGINAL_RESTORE_SCENE_BYTES_REQUIRED')
            need((child_pointers[0], child_pointers[1], child_pointers[3]) ==
                 (pointers[0], pointers[1], pointers[3]),
                 'RESTORES_SHARE_CANONICAL_INDEX_COORDINATE_ADDITIONAL_BLOCKS')
            if restore_ro is None:
                restore_ro = child_pointers[2]
            need(restore_ro == child_pointers[2], 'RESTORES_SHARE_ONE_ORIGINAL_RO_BLOCK')
            restored.append({'parentSlot': spec['parentSlot'], 'slot': spec['slot'],
                             'tile': spec['tile'], 'offset': child_at})
    return {'format': 'no5-staged-actual-wire-two-level-slab-barrier/1',
            'planSHA256': expected_plan_sha256, 'sourceTile': list(tile),
            'blankNodeOffset': at, 'actualRoutingChildren': routing,
            'actualRestoreLeaves': restored, 'blankLevels': plan['blankLevels'],
            'everyRestoreLevelAndDeeperRequestSelectsOriginalSceneBuffers': True,
            'normalRequestMaximumLevelInSourceContract': 29,
            'originalSourceLevelEquivalenceClaim': False,
            'treeContractSHA256': TREE_CONTRACT_SHA256,
            'nativeSourceScaleFrameQueryAcceptance': False, 'fullCountryInverseClaim': False}


