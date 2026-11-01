"""Lossless per-source I/C compaction; no selection, clipping or portrayal.

The existing arena kernel preserves full typed element spans and their address
aliases. This wrapper appends private I/C blocks for explicitly selected source
nodes, preserving every original node address, child link and RO/AO address.
Functions are pure in-memory operations; callers own immutable source reads and
fresh output writes. A plan is a source binding, not a device acceptance claim.
"""
from __future__ import annotations

import hashlib
import inspect
import json
from pathlib import Path
import struct
import uuid

from . import arena
from .model import native_tree, parts
from .header import validate_plain_vector_header
from .wire import FEATURE, blocks, need

POLICY = 'lossless-per-source-full-typed-span-union-append-arenas/1'
PLAN_SCHEMA = 'no5-lossless-source-arena-plan/1'


def _sha(raw):
    return hashlib.sha256(raw).hexdigest()


def _canonical(value):
    return json.dumps(value, sort_keys=True, separators=(',', ':'), ensure_ascii=True).encode('ascii')


def _kernel():
    raw = Path(inspect.getfile(arena)).read_bytes()
    return {'module': arena.__name__, 'bytes': len(raw), 'sha256': _sha(raw)}


def _tiles(tiles):
    need(type(tiles) in (tuple, list) and bool(tiles), 'EXPLICIT_NONEMPTY_SOURCE_TILE_SCOPE')
    result = []
    for tile in tiles:
        need(type(tile) in (tuple, list) and len(tile) == 3 and all(type(v) is int for v in tile),
             'INTEGER_SOURCE_TILE_TRIPLE')
        z, x, y = tile
        need(0 <= z <= 29 and 0 <= x < 1 << z and 0 <= y < 1 << z, 'SOURCE_TILE_BOUNDS')
        result.append(tuple(tile))
    need(len(set(result)) == len(result), 'UNIQUE_SOURCE_TILE_SCOPE')
    return sorted(result)


def _typed_inverse(before, old_index, old_co, after, new_index, new_co):
    """Actual typed bytes, order and aliases, including adjacent 4/9 twins."""
    need(len(before) == len(after) and len(before) % 32 == 0, 'UNCHANGED_COMPLETE_FEATURE_LIST')
    frame_map, reverse_frames, co_map, reverse_co = {}, {}, {}, {}
    intervals = set()
    counts = dict(records=0, parts=0, pointOccurrences=0, soundingPointOccurrences=0, adjacentSoundingTwins=0)
    for offset in range(0, len(before), 32):
        original, actual = bytes(before[offset:offset+32]), bytes(after[offset:offset+32])
        need(original[:8]+original[12:] == actual[:8]+actual[12:], 'EXACT28_NON_INDEX_RECORD_BYTES')
        a, b = FEATURE.unpack(original), FEATURE.unpack(actual)
        need(a[1] not in frame_map or frame_map[a[1]] == b[1], 'SHARED_INDEX_FRAME_ADDRESS_PRESERVED')
        need(b[1] not in reverse_frames or reverse_frames[b[1]] == a[1], 'DISTINCT_INDEX_ADDRESSES_NOT_DEDUPLICATED')
        frame_map[a[1]], reverse_frames[b[1]] = b[1], a[1]
        old_parts = list(parts(original, old_index, old_co))
        new_parts = list(parts(actual, new_index, new_co))
        need(len(old_parts) == len(new_parts), 'EXACT_INDEX_PART_COUNT')
        for pp, qq in zip(old_parts, new_parts):
            need(pp['usage'] == qq['usage'] and pp['raw'] == qq['raw'], 'FULL_TYPED_ELEMENT_AND_USAGE_ORDER_EXACT')
            need(pp['rootFlag'] in (0, 1), 'KNOWN_ROOT0_OR_ROOT1_ELEMENT')
            first, target = pp['coordinateOffset'], qq['coordinateOffset']
            need(first % 4 == target % 4, 'ORIGINAL_ELEMENT_ALIGNMENT')
            need(first not in co_map or co_map[first] == target, 'SHARED_COORDINATE_START_PRESERVED')
            need(target not in reverse_co or reverse_co[target] == first, 'DISTINCT_COORDINATE_ADDRESSES_NOT_DEDUPLICATED')
            co_map[first], reverse_co[target] = target, first
            intervals.add((first, first+len(pp['raw'])))
            counts['parts'] += 1
            counts['pointOccurrences'] += pp['pointCount']
            if a[6] in (4, 9):
                counts['soundingPointOccurrences'] += pp['pointCount']
        if a[6] == 4 and offset+64 <= len(before):
            following = bytes(before[offset+32:offset+64])
            if following[28] == 9 and original[:28]+original[29:] == following[:28]+following[29:]:
                next_actual = bytes(after[offset+32:offset+64])
                need(actual[:28]+actual[29:] == next_actual[:28]+next_actual[29:], 'ADJACENT4_9_PAIR_FULL_RECORD_AND_SHARED_INDEX_EXACT')
                counts['adjacentSoundingTwins'] += 1
        counts['records'] += 1
    # All nested/partially overlapping typed views remain the same physical
    # bytes at the same relative offsets, even with different 8/12-byte ends.
    ordered = sorted(intervals)
    for i, (start, end) in enumerate(ordered):
        for j in range(i+1, len(ordered)):
            other_start, _ = ordered[j]
            if other_start >= end:
                break
            need(co_map[other_start]-co_map[start] == other_start-start, 'PARTIAL_COORDINATE_ALIAS_RELATIVE_OFFSET')
    counts.update(uniqueIndexStarts=len(frame_map), uniqueCoordinateStarts=len(co_map))
    return counts


def _prepare(raw, tiles):
    need(type(raw) is bytes, 'IMMUTABLE_PLAIN_SOURCE_BYTES')
    requested = _tiles(tiles)
    validate_plain_vector_header(raw[:240], file_bytes=len(raw))
    # memoryviews avoid copying every unrelated arena in a large Country.
    parsed = blocks(memoryview(raw))
    need(all(struct.unpack_from('<Q', raw, p)[0] == 0 for p in (152, 176, 192)), 'OWN_PLAIN_SOURCE_ONLY')
    tree = native_tree(raw, parsed)
    by_tile = {tile:at for at,tile in tree.items()}
    rows, packed = [], {}
    for tile in requested:
        need(tile in by_tile, 'EXISTING_EXACT_SOURCE_TILE')
        at = by_tile[tile]
        node = parsed[at]
        need(node.flags == 3 and node.next_offset == 0, 'EXISTING_UNCHAINED_VECTOR_SOURCE')
        io, co, ro, ao = struct.unpack_from('<4Q', node.prefix, 32)
        need(all(q in parsed for q in (io,co,ro,ao)), 'ORIGINAL_VECTOR_STREAMS_RESOLVE')
        for address, disk_id in ((io,0x1101),(co,0x1102)):
            block = parsed[address]
            need((block.disk_id,block.flags,bytes(block.prefix),block.next_offset) == (disk_id,0,b'',0),
                 'PLAIN_UNCHAINED_INDEX_COORDINATE_ARENAS')
        compact = arena.compact_arena(parsed[io].payload,parsed[co].payload,node.payload)
        need(compact['disposition'] != 'PRESERVED_CONFLICT', 'CONTRADICTORY_INDEX_ALIAS_PRESERVE_SOURCE_NO_APPLY')
        counts = _typed_inverse(node.payload,parsed[io].payload,parsed[co].payload,
            compact['featureBytes'],compact['indexBytes'],compact['coordinateBytes'])
        output_buffers = {name:{'bytes':len(compact[key]),'sha256':_sha(compact[key])}
            for name,key in (('features','featureBytes'),('indices','indexBytes'),('coordinates','coordinateBytes'))}
        # Bind every mapping and original span hash, without embedding private
        # coordinate bodies into the JSON plan.
        mappings = {key:compact[key] for key in ('indexComponents','coordinateComponents','records','typedElements','words')}
        row = dict(tile=list(tile),nodeOffset=at,nodePrefixHex=node.prefix.hex(),nodeFeaturesSHA256=_sha(node.payload),
            sourceStreams={name:{'offset':q,'payloadBytes':len(parsed[q].payload),'payloadSHA256':_sha(parsed[q].payload)}
                           for name,q in zip(('indices','coordinates','render','additional'),(io,co,ro,ao))},
            disposition=compact['disposition'],changed=bool(compact['changed'] or compact['featureBytes'] != node.payload),typedInverse=counts,
            outputBuffers=output_buffers,mappings=mappings,
            geometryPayloadBytesBefore=len(parsed[io].payload)+len(parsed[co].payload),
            geometryPayloadBytesAfter=len(compact['indexBytes'])+len(compact['coordinateBytes']))
        rows.append(row)
        packed[at] = compact
    plan = dict(format=PLAN_SCHEMA,policy=POLICY,source={'bytes':len(raw),'sha256':_sha(raw)},kernel=_kernel(),
        rows=rows,logicalTreeNodes=len(tree),geometryFiltering=False,sourceSelectionChanged=False,
        resourcesOrQueryOffsetsChanged=False,physicalNodeAddressesChanged=False)
    return plan,parsed,tree,packed


def plan_source_arenas(raw: bytes, tiles) -> dict:
    """Read-only exact source/kernel/scope plan; no output or acceptance claim."""
    return _prepare(raw, tiles)[0]


def plan_sha256(plan: dict) -> str:
    return _sha(_canonical(plan))


def compact_source_arenas(raw: bytes, tiles, *, expected_source_sha256: str,
                          expected_plan_sha256: str) -> tuple[bytes,dict]:
    """Append exact typed I/C buffers only for changed selected source nodes.

    The same logical tree, source node positions, all RO/AO/resources and all
    original I/C arenas remain present. Only header UUID, selected source I/C
    pointers and Feature+8 index offsets may differ in the original prefix.
    No-op scopes return the identical source bytes and unchanged UUID.
    """
    plan, before, tree, packed = _prepare(raw, tiles)
    need(plan['source']['sha256'] == expected_source_sha256, 'EXACT_SOURCE_SHA256')
    digest = plan_sha256(plan)
    need(digest == expected_plan_sha256, 'EXACT_RECOMPUTED_SOURCE_ARENA_PLAN')
    changed = [r for r in plan['rows'] if r['changed']]
    output = bytearray(raw) if changed else None
    edits, applied = [], []
    for row in changed:
        at = row['nodeOffset'];node = before[at];compact = packed[at]
        offsets = []
        for disk_id,key in ((0x1101,'indexBytes'),(0x1102,'coordinateBytes')):
            payload = compact[key]
            need(len(payload) < 1 << 32, 'APPENDED_BLOCK_SIZE_U32')
            offsets.append(len(output))
            output += struct.pack('<HHIQ',disk_id,0,len(payload),0)+payload
        pointer_at = at+16+32
        edits.append((pointer_at,raw[pointer_at:pointer_at+16]))
        struct.pack_into('<QQ',output,pointer_at,*offsets)
        for ordinal in range(len(node.payload)//32):
            index_at = node.data_offset+ordinal*32+8
            new_offset = compact['featureBytes'][ordinal*32+8:ordinal*32+12]
            if raw[index_at:index_at+4] != new_offset:
                edits.append((index_at,raw[index_at:index_at+4]))
                output[index_at:index_at+4] = new_offset
        applied.append(dict(tile=row['tile'],nodeOffset=at,newIndexBlock=offsets[0],newCoordinateBlock=offsets[1],
            geometryPayloadBytesBefore=row['geometryPayloadBytesBefore'],geometryPayloadBytesAfter=row['geometryPayloadBytesAfter'],
            typedInverse=row['typedInverse']))
    if changed:
        edits.append((56,raw[56:72]))
        output[56:72] = uuid.uuid5(uuid.NAMESPACE_URL,POLICY+':'+digest).bytes_le
    result = bytes(output) if changed else raw
    validate_plain_vector_header(result[:240], file_bytes=len(result))
    after = blocks(memoryview(result))
    need(native_tree(result,after) == tree, 'EXACT_PHYSICAL_AND_LOGICAL_TREE')
    for row in changed:
        at = row['nodeOffset'];old = before[at];new = after[at]
        need(old.prefix[:32] == new.prefix[:32] and old.prefix[48:] == new.prefix[48:], 'CHILD_RO_AO_POINTERS_EXACT')
        oi,oc = struct.unpack_from('<QQ',old.prefix,32);ni,nc = struct.unpack_from('<QQ',new.prefix,32)
        _typed_inverse(old.payload,before[oi].payload,before[oc].payload,new.payload,after[ni].payload,after[nc].payload)
    # Verify every original byte outside the declared patch fields without a
    # second whole-Country inverse copy. Restoring those fields gives raw.
    previous = 0;inverse_hash = hashlib.sha256()
    for start,original in sorted(edits):
        need(start >= previous, 'DISJOINT_DECLARED_PATCH_FIELDS')
        need(result[previous:start] == raw[previous:start], 'ALL_UNDECLARED_ORIGINAL_PREFIX_BYTES_EXACT')
        inverse_hash.update(result[previous:start]);inverse_hash.update(original)
        previous = start+len(original)
    need(result[previous:len(raw)] == raw[previous:], 'ALL_UNDECLARED_ORIGINAL_SUFFIX_BYTES_EXACT')
    inverse_hash.update(result[previous:len(raw)])
    need(inverse_hash.hexdigest() == plan['source']['sha256'], 'COMPLETE_ORIGINAL_PREFIX_INVERSE')
    report = dict(format='no5-lossless-source-arena-result/1',status='LOSSLESS_SOURCE_ARENAS_COMPACTED'if changed else'ALREADY_COMPACT_NO_CHANGE',
        policy=POLICY,source=plan['source'],sourcePlanSHA256=digest,kernel=plan['kernel'],
        output={'bytes':len(result),'sha256':_sha(result),'uuidHex':result[56:72].hex()},
        appliedSources=applied,appendBytes=len(result)-len(raw),appendedBlocks=2*len(changed),
        allowedOriginalPrefixEdits=[{'offset':at,'bytes':len(original),'originalHex':original.hex()}for at,original in sorted(edits)],
        sourcePrefixInverseSHA256=inverse_hash.hexdigest(),logicalTreeNodes=len(tree),
        sourceNodeAddressesUnchanged=True,sourceGeometryAndOrderExact=True,programmesQueriesResourcesExact=True,
        originalPhysicalBlocksRetained=True,sourceSelectionChanged=False,actualNativeReadsMeasured=False,
        geometryDecodeCPUWorkChanged=False,nativeDeviceOrLoadTimeAcceptanceClaim=False)
    return result,report
