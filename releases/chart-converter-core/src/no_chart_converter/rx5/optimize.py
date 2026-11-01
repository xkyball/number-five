"""V22 mask preparation with explicit certificates and source-byte inverse."""
import hashlib
import struct
import uuid

from .arena import compact_arena
from . import mask_merge
from .model import native_tree, parts, programmes
from .wire import FEATURE, blocks, need


def optimize_masks(raw: bytes) -> tuple[bytes, dict]:
    parsed = blocks(raw)
    need(all(struct.unpack_from('<Q', raw, p)[0] == 0 for p in (152, 176, 192)), 'OWN_PLAIN_CHART_ONLY')
    tree = native_tree(raw, parsed)
    output = bytearray(raw)
    edits, certificates = [], []
    for at, tile in sorted(tree.items()):
        node = parsed[at]
        if node.flags != 3 or not mask_merge.MIN_SOURCE_LEVEL <= tile[0] <= 15:
            continue
        io, co, ro, _ = struct.unpack_from('<4Q', node.prefix, 32)
        index, coordinates, render = [parsed[q].payload for q in (io, co, ro)]
        ni, nc, features = bytearray(index), bytearray(coordinates), bytearray(node.payload)
        changed = False
        for ordinal in range(len(features)//32):
            record = node.payload[ordinal*32:(ordinal+1)*32]
            fields = FEATURE.unpack(record)
            if fields[6] != 3:
                continue
            contexts, query = programmes(render, fields[2])
            original = list(parts(record, index, coordinates))
            groups = mask_merge.plan(fields[6], contexts, original, tile)
            if not groups:
                continue
            heads = {g['ordinals'][0]: g for g in groups}
            removed = {i for g in groups for i in g['ordinals'][1:]}
            words = []
            for part in original:
                number = part['partOrdinal']
                if number in removed:
                    continue
                if number not in heads:
                    words.append(part['word'])
                    continue
                group = heads[number]
                boundary = group['boundary'] + [group['boundary'][0]]
                encoded = struct.pack('<I', 0x01000000 | len(boundary)) + b''.join(struct.pack('<II', *p) for p in boundary)
                target = len(nc)
                need(target <= 0xffffff, 'MERGED_COORDINATE_LOW24')
                nc += encoded
                words.append((8 << 24) | target)
                certificates.append({'tile': list(tile), 'node': at, 'ownerOrdinal': ordinal,
                    'sourcePartOrdinals': group['ordinals'], 'boundaryU32': group['boundary'],
                    'projectedIntegerLevels': group['levels'], 'queryOffset': query,
                    'sourceElementSHA256': [hashlib.sha256(original[i]['raw']).hexdigest() for i in group['ordinals']]})
            start = len(ni)
            ni += struct.pack('<I', len(words)) + b''.join(struct.pack('<I', w) for w in words)
            struct.pack_into('<I', features, ordinal*32 + 8, start)
            changed = True
        if not changed:
            continue
        compact = compact_arena(bytes(ni), bytes(nc), bytes(features))
        need(compact['disposition'] != 'PRESERVED_CONFLICT', 'NONCONTRADICTORY_ARENA_ALIASES')
        new_offsets = []
        for disk_id, payload in ((0x1101, compact['indexBytes']), (0x1102, compact['coordinateBytes'])):
            new_offsets.append(len(output))
            output += struct.pack('<HHIQ', disk_id, 0, len(payload), 0) + payload
        pointer_at, body_at = at + 16 + 32, at + 16 + 64
        edits.extend([(pointer_at, raw[pointer_at:pointer_at+16]),
                      (body_at, raw[body_at:body_at+len(node.payload)])])
        struct.pack_into('<QQ', output, pointer_at, *new_offsets)
        output[body_at:body_at+len(node.payload)] = compact['featureBytes']
    source_sha = hashlib.sha256(raw).hexdigest()
    if edits:
        output[56:72] = uuid.uuid5(uuid.NAMESPACE_URL, 'no5-v22-mask:' + source_sha).bytes_le
    result = bytes(output)
    need(native_tree(result) == tree, 'IDENTICAL_NATIVE_SOURCE_TREE')
    inverse = bytearray(result[:len(raw)])
    inverse[56:72] = raw[56:72]
    for position, original in edits:
        inverse[position:position+len(original)] = original
    need(bytes(inverse) == raw, 'WHOLE_ORIGINAL_PREFIX_INVERSE')
    return result, {'format': 'no5-v22-portable-mask-optimization/1',
        'sourceSHA256': source_sha, 'outputSHA256': hashlib.sha256(result).hexdigest(),
        'mergedGroups': len(certificates), 'certificates': certificates,
        'sourceBytes': len(raw), 'outputBytes': len(result),
        'sourcePrefixInverse': True, 'sourceLevels': [13, 15],
        'destinationProjectionProofThroughLevel': 21, 'nativeDeviceAcceptance': 'pending',
        'actualChildPrunes': 0, 'appendOnly': True}
