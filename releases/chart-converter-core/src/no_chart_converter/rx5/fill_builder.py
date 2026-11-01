"""Source-bound diagnostic chart authoring for consumer-protected Source32 fills.

No normal delivery, pipeline default, native loader or public query is changed.
The original public query can reach outside Source32: a sidecar cannot enforce
that bound. These functions therefore produce a separately named diagnostic
candidate with an exact original fallback pin, never global-apply approval.
For ordinary delivery use the globally lossless source_arena API instead.
"""
from __future__ import annotations

import hashlib
import inspect
import json
from pathlib import Path
import struct
import uuid

from . import arena, area_polygon_groups, fill_compact, fill_duplicates, locality_empty, locality_parts
from .header import validate_plain_vector_header
from .model import native_tree, parts
from .wire import FEATURE, blocks, need

POLICY = 'diagnostic-only-source32-consumer-fill-append-arenas/1'


def _sha(raw):
    return hashlib.sha256(raw).hexdigest()


def _canonical(value):
    return json.dumps(value, sort_keys=True, separators=(',', ':'), ensure_ascii=True).encode('ascii')


def source32_plan_sha256(plan):
    """Canonical plan binding; not a receipt or semantic approval."""
    return _sha(_canonical(plan))


def _tiles(tiles):
    need(type(tiles) in (list, tuple) and bool(tiles), 'EXPLICIT_NONEMPTY_SOURCE32_TILE_SCOPE')
    requested = []
    for tile in tiles:
        need(type(tile) in (tuple, list) and len(tile) == 3
             and all(type(v) is int for v in tile)
             and tile[0] == 15 and 0 <= tile[1] < 1 << 15 and 0 <= tile[2] < 1 << 15,
             'EXPLICIT_L15_SOURCE_TILE_REQUIRED')
        requested.append(tuple(tile))
    need(len(set(requested)) == len(requested), 'UNIQUE_SOURCE32_TILE_SCOPE')
    return sorted(requested)


def _kernels():
    modules = (fill_compact, fill_duplicates, locality_empty, locality_parts, area_polygon_groups, arena)
    return {m.__name__.rsplit('.', 1)[-1]: {'bytes': len(raw), 'sha256': _sha(raw)}
            for m in modules for raw in [Path(inspect.getfile(m)).read_bytes()]}


def _prepare(raw, tiles):
    need(type(raw) is bytes, 'IMMUTABLE_PLAIN_SOURCE_BYTES')
    requested = _tiles(tiles)
    validate_plain_vector_header(raw[:240], file_bytes=len(raw))
    parsed = blocks(memoryview(raw))
    need(all(struct.unpack_from('<Q', raw, p)[0] == 0 for p in (152, 176, 192)), 'OWN_PLAIN_SOURCE_ONLY')
    tree = native_tree(raw, parsed)
    by_tile = {tile: at for at, tile in tree.items()}
    rows, prepared = [], {}
    for tile in requested:
        need(tile in by_tile, 'EXISTING_EXACT_SOURCE_TILE')
        at = by_tile[tile]
        node = parsed[at]
        need(node.flags == 3 and node.next_offset == 0
             and not any(struct.unpack_from('<4Q', node.prefix)),
             'EXISTING_UNCHAINED_L15_VECTOR_LEAF_REQUIRED')
        addresses = struct.unpack_from('<4Q', node.prefix, 32)
        for address, disk_id in zip(addresses, (0x1101, 0x1102, 0x1103, 0x1104)):
            need(address in parsed, 'ORIGINAL_VECTOR_STREAMS_RESOLVE')
            b = parsed[address]
            need((b.disk_id, b.flags, bytes(b.prefix), b.next_offset) == (disk_id, 0, b'', 0),
                 'PLAIN_UNCHAINED_FOUR_SOURCE_ARENAS_REQUIRED')
        inputs = [bytes(node.payload)] + [bytes(parsed[q].payload) for q in addresses]
        result = fill_compact.compact_source_fill(*inputs, tile=tile, scope=fill_compact.SCOPE)
        row = {'tile': list(tile), 'nodeOffset': at, 'sourcePrefixHex': node.prefix.hex(),
               'sourceFeatureSHA256': _sha(node.payload),
               'sourceStreams': [{'offset': q, 'bytes': len(parsed[q].payload), 'sha256': _sha(parsed[q].payload)} for q in addresses],
               'changed': result['changed'], 'disposition': result['disposition'],
               'outputBuffers': result['receipt']['outputPins'],
               'consumerCompaction': result['receipt']}
        rows.append(row)
        prepared[at] = result
    plan = {'format': 'no5-source32-diagnostic-chart-plan/1', 'policy': POLICY,
            'scope': fill_compact.SCOPE, 'source': {'bytes': len(raw), 'sha256': _sha(raw)},
            'kernels': _kernels(), 'logicalTreeNodes': len(tree), 'rows': rows,
            'diagnosticOnly': True, 'globalChartApplyAllowed': False,
            'normalDeliveryReplacementAllowed': False,
            'originalQueryFallbackRequiredOutsideDomain': True,
            'originalLoaderEnforcesSidecar': False}
    return plan, parsed, tree, prepared


def plan_source32_fill(raw, tiles):
    """Read-only plan of exact existing sources. Never authorizes global apply."""
    return _prepare(raw, tiles)[0]


def _typed_subset(before, old_index, old_co, after, new_index, new_co, owners):
    need(len(before) == len(after) and len(owners) == len(before)//32, 'UNCHANGED_OWNER_COUNT_AND_ORDER')
    count = 0
    for ordinal, owner in enumerate(owners):
        old, new = bytes(before[32*ordinal:32*(ordinal+1)]), bytes(after[32*ordinal:32*(ordinal+1)])
        need(old[:8]+old[12:] == new[:8]+new[12:], 'ONLY_STORED_INDEX_POINTER_CHANGED')
        pp, qq = list(parts(old, old_index, old_co)), list(parts(new, new_index, new_co))
        keep = owner['keptOriginalPartOrdinals']
        need(len(qq) == len(keep), 'EXACT_RETAINED_PART_COUNT')
        for i, part in zip(keep, qq):
            need(part['raw'] == pp[i]['raw'] and part['usage'] == pp[i]['usage'],
                 'EXACT_RETAINED_TYPED_ELEMENT_ORDER_AND_FLAGS')
        for i in owner.get('protectedConsumerPartOrdinals', []):
            need(i in keep, 'PROTECTED_CONSUMER_PART_RETAINED')
        f = FEATURE.unpack(old)
        if f[6] in (4, 9):
            need(keep == list(range(len(pp))), 'ALL_SOUNDING_ROLE_PARTS_RETAINED')
        count += len(qq)
    return count


def build_source32_fill(raw, tiles, *, expected_source_sha256, expected_plan_sha256, scope):
    """Return a diagnostic RX5 and full-prefix inverse; no filesystem writes.

    This API cannot replace the normal-delivery chart. No asserted receipt can
    waive the source, plan, leaf, encoding, consumer or spatial-domain checks.
    For queries not bound to the proved Source32 domain, use the exact original
    chart named by ``originalFallback``; the Original loader does not implement
    this selection for a caller. Unknown/no-change scopes preserve every byte
    and the UUID. Actual changed candidates get a deterministic distinct UUID.
    """
    need(type(scope) is str and scope == fill_compact.SCOPE, 'EXPLICIT_SOURCE32_SCOPE_REQUIRED')
    need(type(raw) is bytes, 'IMMUTABLE_PLAIN_SOURCE_BYTES')
    need(type(expected_source_sha256) is str and _sha(raw) == expected_source_sha256, 'EXACT_SOURCE_SHA256')
    plan, before, tree, prepared = _prepare(raw, tiles)
    digest = source32_plan_sha256(plan)
    need(type(expected_plan_sha256) is str and digest == expected_plan_sha256, 'EXACT_SOURCE32_DIAGNOSTIC_PLAN')
    changed = [r for r in plan['rows'] if r['changed']]
    output = bytearray(raw) if changed else None
    edits, applied = [], []
    for row in changed:
        at = row['nodeOffset']; node = before[at]; compact = prepared[at]
        offsets = []
        for disk_id, key in ((0x1101, 'indexBytes'), (0x1102, 'coordinateBytes')):
            payload = compact[key]
            need(len(payload) < 1 << 32, 'APPENDED_BLOCK_SIZE_U32')
            offsets.append(len(output))
            output += struct.pack('<HHIQ', disk_id, 0, len(payload), 0)+payload
        pointer = at+16+32
        edits.append((pointer, raw[pointer:pointer+16]))
        struct.pack_into('<QQ', output, pointer, *offsets)
        for ordinal in range(len(node.payload)//32):
            pos = node.data_offset+ordinal*32+8
            value = compact['featureBytes'][ordinal*32+8:ordinal*32+12]
            if raw[pos:pos+4] != value:
                edits.append((pos, raw[pos:pos+4]))
                output[pos:pos+4] = value
        applied.append({'tile': row['tile'], 'nodeOffset': at, 'newICOffsets': offsets,
                        'declaredWork': compact['receipt']['declaredWork']})
    if changed:
        edits.append((56, raw[56:72]))
        output[56:72] = uuid.uuid5(uuid.NAMESPACE_URL, POLICY+':'+digest).bytes_le
    result = bytes(output) if changed else raw
    validate_plain_vector_header(result[:240], file_bytes=len(result))
    after = blocks(memoryview(result))
    need(native_tree(result, after) == tree, 'EXACT_SOURCE_SELECTION_TREE_AND_ADDRESSES')
    for row in changed:
        at = row['nodeOffset']; old, new = before[at], after[at]
        need(old.prefix[:32] == new.prefix[:32] and old.prefix[48:] == new.prefix[48:], 'CHILD_RO_AO_POINTERS_EXACT')
        oi, oc, ro, ao = struct.unpack_from('<4Q', old.prefix, 32)
        ni, nc = struct.unpack_from('<QQ', new.prefix, 32)
        need(after[ro] == before[ro] and after[ao] == before[ao], 'RENDER_AND_TYPED_QUERY_ARENAS_EXACT')
        _typed_subset(old.payload, before[oi].payload, before[oc].payload,
                      new.payload, after[ni].payload, after[nc].payload,
                      row['consumerCompaction']['owners'])
    previous = 0; inverse = hashlib.sha256()
    for pos, old in sorted(edits):
        need(pos >= previous, 'DISJOINT_DECLARED_PREFIX_PATCHES')
        need(result[previous:pos] == raw[previous:pos], 'UNDECLARED_PREFIX_BYTES_EXACT')
        inverse.update(result[previous:pos]); inverse.update(old)
        previous = pos+len(old)
    need(result[previous:len(raw)] == raw[previous:], 'UNDECLARED_PREFIX_SUFFIX_EXACT')
    inverse.update(result[previous:len(raw)])
    need(inverse.hexdigest() == plan['source']['sha256'], 'WHOLE_ORIGINAL_PREFIX_INVERSE')
    report = {'format': 'no5-source32-diagnostic-chart-result/1', 'policy': POLICY,
              'status': 'SCOPED_DIAGNOSTIC_CANDIDATE' if changed else 'UNCHANGED_DIAGNOSTIC_SOURCE',
              'source': plan['source'], 'originalFallback': plan['source'],
              'sourcePlanSHA256': digest, 'kernels': plan['kernels'], 'scope': scope,
              'output': {'bytes': len(result), 'sha256': _sha(result), 'uuidHex': result[56:72].hex()},
              'appliedSources': applied, 'appendBytes': len(result)-len(raw), 'appendedBlocks': 2*len(changed),
              'allowedOriginalPrefixEdits': [{'offset': p, 'bytes': len(b), 'originalHex': b.hex()} for p, b in sorted(edits)],
              'sourcePrefixInverseSHA256': inverse.hexdigest(),
              'sourceSelectionTreeExact': True, 'ownerUIDOrderROAOFlagsAndRetainedTypedElementsExact': True,
              'globalChartApplyAllowed': False, 'normalDeliveryReplacementAllowed': False,
              'originalLoaderEnforcesSidecar': False, 'originalQueryFallbackRequiredOutsideDomain': True,
              'nativeAcceptanceClaim': False, 'hardwareTimeClaim': False}
    return result, report
