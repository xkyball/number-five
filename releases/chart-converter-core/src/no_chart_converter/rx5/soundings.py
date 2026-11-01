"""Original-gated, same-UID Kind4/9 sounding representation materialization.

The retained V19C writer adds an immediate duplicate differing only at stored
byte28. Both kinds use the same XYZ12/index/programme/query arrays. At header
version8 the witnessed original render gate selects9 for source==destination
and4 for source!=destination. This is not a complete glyph/UI/query guarantee.
"""
from __future__ import annotations

import hashlib
import struct

from . import wire
from .header import validate_plain_vector_header
from .model import native_tree, parts


def _sha(raw):
    return hashlib.sha256(raw).hexdigest()


def sounding_kind9(record: bytes) -> bytes:
    """The exact existing writer rule: same32 bytes except Kind4→9 at28."""
    wire.need(type(record) is bytes and len(record) == 32 and record[28] == 4,
              'SOUNDING_ORDINARY_FEATURE32_REQUIRED')
    return record[:28] + b'\x09' + record[29:]


def validate_sounding_pairs(features: bytes):
    """Validate explicit adjacent4/9 occurrences without erasing either kind."""
    wire.need(len(features) % 32 == 0, 'SOUNDING_FEATURE32_RANGE')
    pairs, ordinal, seen = 0, 0, set()
    while ordinal < len(features) // 32:
        record = features[ordinal*32:(ordinal+1)*32]
        uid = struct.unpack_from('<Q', record)[0]
        wire.need(uid not in seen, 'SOUNDING_NONPAIR_UID_COLLISION')
        seen.add(uid)
        wire.need(record[28] != 9, 'SOUNDING_ORPHAN_KIND9')
        if record[28] == 4:
            twin = features[(ordinal+1)*32:(ordinal+2)*32]
            wire.need(twin == sounding_kind9(record), 'SOUNDING_EXACT_ADJACENT_SAME_UID_TWIN')
            pairs += 1
            ordinal += 1
        ordinal += 1
    return pairs


def materialize_sounding_twins(raw: bytes) -> tuple[bytes, dict]:
    """Add missing twins in every active vector profile; never rebuild geometry.

    All stream and non-vector payload bytes are copied exactly. Only feature
    vectors grow; the normal wire assembler rebinds every absolute reference.
    Tree shape, UUID and every original feature record remain unchanged.
    An orphan/conflicting Kind9 is refused, and canonical input is idempotent.
    """
    validate_plain_vector_header(raw[:240], file_bytes=len(raw))
    parsed = wire.blocks(raw)
    tree = native_tree(raw, parsed)
    rewritten, nodes, added, existing = {}, [], 0, 0
    for at, tile in sorted(tree.items()):
        block = parsed[at]
        if block.flags != 3:
            continue
        features, ordinal, new_pairs, old_pairs = bytearray(), 0, 0, 0
        io, co, _, _ = struct.unpack_from('<4Q', block.prefix, 32)
        while ordinal < len(block.payload) // 32:
            record = block.payload[ordinal*32:(ordinal+1)*32]
            wire.need(record[28] != 9, 'SOUNDING_ORPHAN_KIND9')
            features += record
            if record[28] == 4:
                # The existing bounded typed decoder validates every referenced
                # element as XYZ12; it does not convert depths or coordinates.
                list(parts(record, parsed[io].payload, parsed[co].payload))
                twin = sounding_kind9(record)
                following = block.payload[(ordinal+1)*32:(ordinal+2)*32]
                if len(following) == 32 and following[28] == 9:
                    wire.need(following == twin, 'SOUNDING_CONFLICTING_EXISTING_TWIN')
                    old_pairs += 1
                    ordinal += 1
                else:
                    new_pairs += 1
                features += twin
            ordinal += 1
        validate_sounding_pairs(bytes(features))
        if new_pairs:
            rewritten[at] = bytes(features)
        if new_pairs or old_pairs:
            nodes.append({'sourceNodeOffset': at, 'tile': list(tile),
                          'addedPairs': new_pairs, 'existingPairs': old_pairs,
                          'sourceFeatureSHA256': _sha(block.payload),
                          'outputFeatureSHA256': _sha(features)})
        added += new_pairs
        existing += old_pairs
    report = {'format': 'no5-original-gated-sounding-twins/1',
              'policy': 'ADJACENT_SAME_UID_KIND4_KIND9_IDENTICAL_XYZ12_QUERY_PROGRAMME/1',
              'sourceSHA256': _sha(raw), 'sourceBytes': len(raw),
              'addedKind9Records': added, 'existingPairs': existing,
              'allActiveSourceProfilesCovered': True, 'soundingNodes': nodes,
              'geometryCoordinatesOrDepthsRecomputed': False,
              'newPrimaryUIDs': 0, 'chartUUIDPreserved': True,
              'originalNativeGate': {'headerVersion': 8, 'equalSourceDestinationKind': 9,
                                     'differentSourceDestinationKind': 4},
              'physicalPlotterAcceptanceClaim': False,
              'wholeRendererOrAllQueryDeduplicationClaim': False}
    if not added:
        return raw, {**report, 'outputSHA256': _sha(raw), 'outputBytes': len(raw),
                     'status': 'ALREADY_COMPLETE_OR_NO_SOUNDINGS'}
    names = {at: 'old:' + str(at) for at in parsed}
    authored = []
    for at, block in parsed.items():
        refs = ()
        prefix = block.prefix
        if block.flags in (1, 2, 3):
            refs = tuple((off, names[q] if q else None)
                         for off in range(0, len(prefix), 8)
                         for q in [struct.unpack_from('<Q', prefix, off)[0]])
            prefix = bytes(len(prefix))
        authored.append(wire.AuthoredBlock(names[at], block.disk_id, block.flags,
            prefix, rewritten.get(at, block.payload),
            names[block.next_offset] if block.next_offset else None, refs))
    header_refs = {off: names[q] if q else None for off in wire.HEADER_SLOTS
                   for q in [struct.unpack_from('<Q', raw, off)[0]]}
    result, offsets = wire.assemble(raw[:240], authored, header_refs)
    validate_plain_vector_header(result[:240], file_bytes=len(result))
    after = wire.blocks(result)
    rebuilt_tree = native_tree(result, after)
    wire.need({offsets[names[at]]: tile for at, tile in tree.items()} == rebuilt_tree,
              'SOUNDING_IDENTICAL_LOGICAL_SOURCE_TREE')
    for at, before in parsed.items():
        current = after[offsets[names[at]]]
        wire.need((before.disk_id, before.flags) == (current.disk_id, current.flags),
                  'SOUNDING_BLOCK_TYPES_UNCHANGED')
        if at in rewritten:
            restored, pos = bytearray(), 0
            while pos < len(current.payload):
                record = current.payload[pos:pos+32]
                restored += record
                if record[28] == 4:
                    wire.need(current.payload[pos+32:pos+64] == sounding_kind9(record),
                              'SOUNDING_PERSISTED_EXACT_TWIN')
                    old_has_twin = before.payload[len(restored):len(restored)+32]
                    if old_has_twin == sounding_kind9(record):
                        restored += old_has_twin
                    pos += 32
                pos += 32
            wire.need(bytes(restored) == before.payload, 'SOUNDING_ALL_ORIGINAL_FEATURES_EXACT')
        else:
            wire.need(current.payload == before.payload, 'SOUNDING_ALL_OTHER_PAYLOADS_EXACT')
    wire.need(result[56:72] == raw[56:72], 'SOUNDING_CHART_UUID_UNCHANGED')
    return result, {**report, 'status': 'COMPLETE_ORIGINAL_GATED_TWINS_MATERIALIZED',
                    'outputSHA256': _sha(result), 'outputBytes': len(result),
                    'allOriginalFeatureRecordsAndStreamBodiesExact': True}
