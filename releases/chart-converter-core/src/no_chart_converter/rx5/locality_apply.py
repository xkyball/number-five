"""Apply explicitly reviewed locality plans to existing plain L15 source leaves.

Planning is read-only. Applying requires the exact source, kernel and per-tile
plan hashes plus externally reviewed visual/admission/query certificates.
Hash binding does not independently prove the external certificates' mathematics.
The title and all original RO/AO resources remain unchanged.
"""
from __future__ import annotations

import hashlib
import inspect
import json
from pathlib import Path
import struct
import uuid

from . import arena, locality_empty, locality_parts
from .approvals import read_pin
from .model import native_tree, parts
from .header import validate_plain_vector_header
from .wire import FEATURE, blocks, need

SCHEMA = 'no5-bound-scoped-locality-allowlist/1'
PLAN_SCHEMA = 'no5-scoped-locality-candidate-plan/1'
IDENTICAL = 'IDENTICAL_WITHIN_PROVED_SOURCE_DOMAIN'
SOURCE_GATE = 'UNCHANGED_SOURCE_IDS_AND_NATIVE_SCALE'
_KERNELS = {
    'wholeOwner': (locality_empty, '786245e2c155f59b0b7c7961bf38786cc7b785a9b958ea38be1d8a5b7cf614d7'),
    'wholePart': (locality_parts, '0ef90896b6897139fa5c5bd25416247df1536d65de286c646056b85027445540'),
    'arena': (arena, 'c45d560d7093e61df5f3cbf8184c8e980aa78e42d9493c1170a0b0d86bfb9335'),
}


def _sha(raw):
    return hashlib.sha256(raw).hexdigest()


def _canonical(value):
    return json.dumps(value, sort_keys=True, separators=(',', ':'), ensure_ascii=True).encode('ascii')


def _kernel_pins():
    result = {}
    for name, (module, expected) in _KERNELS.items():
        body = Path(inspect.getfile(module)).read_bytes()
        need(_sha(body) == expected, 'UNCHANGED_LOCALITY_KERNELS_REQUIRED')
        result[name] = {'bytes': len(body), 'sha256': expected}
    return result


def _tiles(values):
    need(type(values) in (list, tuple) and bool(values), 'EXPLICIT_NONEMPTY_L15_TILE_SCOPE')
    result = []
    for value in values:
        need(type(value) in (list, tuple) and len(value) == 3
             and all(type(v) is int for v in value)
             and value[0] == 15 and 0 <= value[1] < 1 << 15 and 0 <= value[2] < 1 << 15,
             'ONLY_EXPLICIT_EXISTING_L15_LEAVES')
        result.append(tuple(value))
    need(len(set(result)) == len(result), 'UNIQUE_EXPLICIT_TILE_SCOPE')
    return sorted(result)


def _source(raw):
    need(isinstance(raw, bytes), 'IMMUTABLE_SOURCE_BYTES')
    validate_plain_vector_header(raw[:240], file_bytes=len(raw))
    parsed = blocks(raw)
    need(all(struct.unpack_from('<Q', raw, p)[0] == 0 for p in (152, 176, 192)),
         'OWN_PLAIN_SOURCE_ONLY')
    return parsed, native_tree(raw, parsed)


COMMAND_SCOPES = ('pure_ac_fill', 'reviewed_kernels')


def _command_scope(value):
    need(value in COMMAND_SCOPES, 'EXPLICIT_KNOWN_LOCALITY_COMMAND_SCOPE')
    return value


def _scope_allows(kind, contexts, scope):
    if scope == 'reviewed_kernels':
        return True  # Proposal only; application still requires exact proofs.
    if kind != 3 or any(key not in (196608, 196609) for key, _ in contexts) or not locality_parts.eligible(kind, contexts):
        return False
    # Eligibility already rejects internal/unknown empty clauses and opaque
    # keys. Default production plans use AC only: no unresolved pen-width,
    # pattern, text, symbol or centroid consumer is implicitly admitted.
    return all(token.startswith(b'AC(') for _, body in contexts
               for token in body.split(b';') if token)


def _plan(raw, parsed, tree, tile, command_scope):
    found = [at for at, value in tree.items() if value == tile]
    need(len(found) == 1, 'EXISTING_EXACT_SOURCE_TILE')
    at = found[0]
    node = parsed[at]
    need(node.flags == 3 and not any(struct.unpack_from('<4Q', node.prefix)),
         'EXISTING_L15_VECTOR_LEAF_NO_DESCENDANTS')
    io, co, ro, ao = struct.unpack_from('<4Q', node.prefix, 32)
    need(all(q in parsed for q in (io, co, ro, ao)), 'ORIGINAL_VECTOR_STREAMS_RESOLVE')
    for address, disk_id in ((io, 0x1101), (co, 0x1102)):
        b = parsed[address]
        need((b.disk_id, b.flags, b.prefix, b.next_offset) == (disk_id, 0, b'', 0),
             'KNOWN_PLAIN_UNCHAINED_COMPACT_ARENAS')
    index, coordinates, render = [parsed[q].payload for q in (io, co, ro)]
    partial = locality_parts.local_whole_parts(node.payload, index, coordinates, render, tile)
    changes = {r['recordOrdinal']: r for r in partial['receipts']}
    features, owners = bytearray(), []
    totals = {'wholeOwnersRemoved': 0, 'partialOwnersChanged': 0, 'partsRemoved': 0, 'nonSoundingXYRemoved': 0}
    for ordinal in range(len(node.payload)//32):
        record = node.payload[ordinal*32:(ordinal+1)*32]
        fields = FEATURE.unpack(record)
        original = list(parts(record, index, coordinates))
        entries, query, frame = locality_parts.programme_frame(render, fields[2])
        scope_eligible = _scope_allows(fields[6], entries, command_scope)
        cert = locality_empty.certify(fields[6], [body for _, body in entries], original, tile) if scope_eligible else None
        row = {'sourceOrdinal': ordinal, 'commandScopeEligible': scope_eligible, 'sourceRecord32Hex': record.hex(),
               'rawContextEntries': [{'context': k, 'hex': body.hex()} for k, body in entries],
               'wholeProgrammeFrameSHA256': _sha(frame), 'queryOffset': query,
               'sourceParts': [{k: p[k] for k in ('partOrdinal', 'word', 'header', 'rootFlag', 'pointCount')}
                               | {'elementSHA256': _sha(p['raw'])} for p in original]}
        if cert is not None:
            row.update(disposition='OMITTED_WHOLE_NO_INK_OWNER', outputOrdinal=None,
                       keptOriginalPartOrdinals=[], wholeOwnerCertificate=cert)
            totals['wholeOwnersRemoved'] += 1
            totals['partsRemoved'] += len(original)
            totals['nonSoundingXYRemoved'] += sum(p['pointCount'] for p in original)
        else:
            row['outputOrdinal'] = len(features)//32
            if scope_eligible and ordinal in changes:
                receipt = changes[ordinal]
                keep = receipt['retainedPartOrdinals']
                row.update(disposition='ORIGINAL_WHOLE_PART_SUBSET', partialCertificate=receipt)
                totals['partialOwnersChanged'] += 1
                totals['partsRemoved'] += len(original)-len(keep)
                totals['nonSoundingXYRemoved'] += receipt['originalPointOccurrences']-receipt['derivedPointOccurrences']
            else:
                keep = list(range(len(original)))
                row['disposition'] = 'ORIGINAL_TYPED_ELEMENTS_EXACT'
            row['keptOriginalPartOrdinals'] = keep
            features += partial['featureBytes'][ordinal*32:(ordinal+1)*32] if scope_eligible else record
        owners.append(row)
    need(bool(features), 'NONEMPTY_RETAINED_SOURCE_VECTOR_REQUIRED')
    compact = arena.compact_arena(partial['indexBytes'], coordinates, bytes(features))
    need(compact['disposition'] != 'PRESERVED_CONFLICT', 'NONCONTRADICTORY_ARENA_ALIAS_REMAP')
    references = []
    for parent_at in tree:
        for slot, child in enumerate(struct.unpack_from('<4Q', parsed[parent_at].prefix)):
            if child == at:
                references.append({'parentNodeOffset': parent_at, 'parentChildSlot': slot,
                                   'fileOffset': parent_at+16+8*slot,
                                   'sourceRefHex': raw[parent_at+16+8*slot:parent_at+24+8*slot].hex()})
    need(len(references) == 1, 'ONE_EXACT_ANCESTOR_CHILD_REFERENCE')
    metadata = {
        'tile': list(tile), 'commandScope': command_scope, 'sourceNodeOffset': at, 'sourceNodePrefixHex': node.prefix.hex(),
        'sourceNodeNextOffset': node.next_offset, 'sourceFeaturesSHA256': _sha(node.payload),
        'sourceStreams': [{'offset': q, 'bytes': len(parsed[q].payload), 'sha256': _sha(parsed[q].payload)}
                          for q in (io, co, ro, ao)],
        'ancestorReference': references[0], 'owners': owners, 'totals': totals,
        'compactedBuffers': {name: {'bytes': len(compact[key]), 'sha256': _sha(compact[key])}
                            for name, key in (('features', 'featureBytes'), ('index', 'indexBytes'), ('coordinates', 'coordinateBytes'))},
        'geometryGuardSourcePixels': 64, 'partialGuardSourcePixels': 32,
        'proofDestinationLevels': [15, 29], 'hasActualChange': bool(totals['partsRemoved']),
    }
    return metadata, compact


def prepare_scoped_plan(raw: bytes, tiles, *, command_scope='pure_ac_fill') -> dict:
    """Return source-bound candidate metadata, never an applied chart or approval.

    Each row's planSHA256 hashes its canonical plan, including every original
    record, full RO/AO stream hashes, retained part mapping and guard certificates.
    The caller must obtain independent visual/admission/query review of that plan.
    """
    requested = _tiles(tiles)
    command_scope = _command_scope(command_scope)
    kernels = _kernel_pins()
    parsed, tree = _source(raw)
    rows = []
    for tile in requested:
        metadata, _ = _plan(raw, parsed, tree, tile, command_scope)
        rows.append({'tile': list(tile), 'planSHA256': _sha(_canonical(metadata)), 'plan': metadata})
    return {'format': PLAN_SCHEMA, 'status': 'CANDIDATE_PLAN_ONLY_NOT_APPROVAL', 'commandScope': command_scope,
            'source': {'bytes': len(raw), 'sha256': _sha(raw)}, 'kernels': kernels, 'rows': rows}


def _append(output, disk_id, flags, prefix, payload, next_offset=0):
    at = len(output)
    output += struct.pack('<HHIQ', disk_id, flags, len(prefix)+len(payload), next_offset)+prefix+payload
    return at


def apply_scoped_locality(raw: bytes, allowlist: Path, expected_sha256: str) -> tuple[bytes, dict]:
    """Apply only an exact externally reviewed allowlist; preserve menu metadata.

    expected_sha256 pins the allowlist file (not the source chart). Its source
    hash, immutable kernel pins and each freshly recomputed plan must all match.
    The separate proof pins bind existing review; no query-loss override exists.
    This function returns bytes and a report and never writes a chart file.
    """
    allowlist = Path(allowlist)
    document_raw = allowlist.read_bytes()
    need(_sha(document_raw) == expected_sha256, 'EXPLICIT_REVIEWED_ALLOWLIST_PIN')
    document = json.loads(document_raw)
    need(type(document) is dict and document.get('format') == SCHEMA
         and document.get('status') == 'APPROVED_FOR_SCOPED_PHYSICAL_A97_TEST',
         'REVIEWED_LOCALITY_APPROVAL_REQUIRED')
    command_scope = _command_scope(document.get('commandScope', 'pure_ac_fill'))
    source_sha = _sha(raw)
    need(document.get('source') == {'bytes': len(raw), 'sha256': source_sha},
         'LOCALITY_APPROVAL_BOUND_TO_EXACT_SOURCE')
    kernels = _kernel_pins()
    need(document.get('kernels') == kernels, 'APPROVED_UNCHANGED_LOCALITY_KERNEL_PINS')
    rows = document.get('rows')
    need(type(rows) is list and rows and all(type(r) is dict for r in rows), 'EXPLICIT_APPROVED_TILE_ROWS')
    tiles = _tiles([r.get('tile') for r in rows])
    approved = {tuple(r['tile']): r for r in rows}
    parsed, tree = _source(raw)
    output, expected_tree = bytearray(raw), dict(tree)
    edits, reports, checked = [], [], []
    for tile in tiles:
        row = approved[tile]
        need(row.get('approved') is True, 'EXPLICIT_TILE_APPROVAL_REQUIRED')
        need(row.get('visualSemanticDisposition') == IDENTICAL
             and row.get('querySemanticDisposition') == IDENTICAL,
             'IDENTICAL_SOURCE_DOMAIN_REQUIRED_NO_QUERY_LOSS_OVERRIDE')
        need(row.get('nativeSourceGateDisposition') == SOURCE_GATE, 'UNCHANGED_SOURCE_IDS_AND_SCALE_REQUIRED')
        evidence = {}
        for role in ('visualCertificate', 'admissionCertificate', 'querySemanticReport'):
            proof = read_pin(row.get(role), allowlist.parent)
            evidence[role] = {'bytes': len(proof), 'sha256': _sha(proof)}
        metadata, compact = _plan(raw, parsed, tree, tile, command_scope)
        plan_sha = _sha(_canonical(metadata))
        need(row.get('planSHA256') == plan_sha, 'EXACT_REVIEWED_PER_TILE_PLAN_SHA')
        need(metadata['hasActualChange'], 'NO_ACTUAL_LOCALITY_CHANGE_NO_APPLY')
        old_at = metadata['sourceNodeOffset']
        old = parsed[old_at]
        io = _append(output, 0x1101, 0, b'', compact['indexBytes'])
        co = _append(output, 0x1102, 0, b'', compact['coordinateBytes'])
        prefix = bytearray(old.prefix)
        struct.pack_into('<QQ', prefix, 32, io, co)
        new_at = _append(output, old.disk_id, 3, bytes(prefix), compact['featureBytes'], old.next_offset)
        reference = metadata['ancestorReference']
        offset = reference['fileOffset']
        struct.pack_into('<Q', output, offset, new_at)
        edits.append((offset, raw[offset:offset+8]))
        del expected_tree[old_at]
        expected_tree[new_at] = tile
        # Verify actual kept elements, including usage/reversal and full XYZ/Dcm.
        original_index, original_coordinates = [parsed[p['offset']].payload for p in metadata['sourceStreams'][:2]]
        for owner in metadata['owners']:
            if owner['outputOrdinal'] is None:
                continue
            before = old.payload[owner['sourceOrdinal']*32:(owner['sourceOrdinal']+1)*32]
            after = compact['featureBytes'][owner['outputOrdinal']*32:(owner['outputOrdinal']+1)*32]
            need(before[:8]+before[12:] == after[:8]+after[12:], 'ALL_RETAINED28_RECORD_BYTES_EXACT')
            pp = list(parts(before, original_index, original_coordinates))
            actual = list(parts(after, compact['indexBytes'], compact['coordinateBytes']))
            need([(p['usage'], p['raw']) for p in actual]
                 == [(pp[k]['usage'], pp[k]['raw']) for k in owner['keptOriginalPartOrdinals']],
                 'ALL_RETAINED_TYPED_PARTS_ORDER_EXACT')
        reports.append({'tile': list(tile), 'planSHA256': plan_sha, 'sourceNodeOffset': old_at,
                        'outputNodeOffset': new_at, 'ancestorReference': reference,
                        'totals': metadata['totals'],
                        'geometryPayloadBytesBefore': sum(p['bytes'] for p in metadata['sourceStreams'][:2]),
                        'geometryPayloadBytesAfter': len(compact['indexBytes'])+len(compact['coordinateBytes'])})
        checked.append({'tile': list(tile), 'checkedEvidence': evidence})
    output[56:72] = uuid.uuid5(uuid.NAMESPACE_URL, 'no5-scoped-locality:'+source_sha+':'+expected_sha256).bytes_le
    result = bytes(output)
    validate_plain_vector_header(result[:240], file_bytes=len(result))
    need(native_tree(result) == expected_tree, 'IDENTICAL_LOGICAL_SOURCE_TREE_AND_NO_PRUNING')
    inverse = bytearray(result[:len(raw)])
    inverse[56:72] = raw[56:72]
    for offset, original in edits:
        inverse[offset:offset+8] = original
    need(bytes(inverse) == raw, 'COMPLETE_ORIGINAL_SOURCE_PREFIX_INVERSE')
    need(result[136:144] == raw[136:144], 'ORIGINAL_TITLE_POINTER_UNCHANGED')
    return result, {
        'status': 'APPLIED_REVIEWED_SCOPED_LOCALITY', 'commandScope': command_scope, 'sourceSHA256': source_sha,
        'allowlistSHA256': expected_sha256, 'outputSHA256': _sha(result), 'kernels': kernels,
        'appliedTiles': reports, 'sourceTreeNodes': len(tree), 'outputTreeNodes': len(expected_tree),
        'actualChildPrunes': 0, 'newSpatialChildren': 0, 'sourceIDsAndScaleUnchanged': True,
        'titleChanged': False, 'originalProgrammesQueryAndResourceAddressesUnchanged': True,
        'sourcePrefixInverseSHA256': _sha(inverse), 'physicalResourceBlocksRemoved': 0,
        'checkedEvidence': checked, 'externalProofMathIndependentlyRecomputed': False,
        'nativeDeviceOrLoadTimeAcceptanceClaim': False,
    }
