"""Portable V23 product policy: exact external proofs and no query-loss override.

Proof files remain private runtime inputs. Hash equality binds the reviewed
certificates; it does not independently establish their visual mathematics.
"""
from __future__ import annotations

import hashlib
import json
from pathlib import Path
import struct
import uuid
import xml.etree.ElementTree as ET

from .pruning import PruneDecision, apply_verified_prunes, native_tree
from .wire import blocks

SCHEMA = 'no5-bound-visual-leaf-prune-allowlist/1'


def need(condition, reason):
    if not condition:
        raise ValueError(reason)


def digest(raw):
    return hashlib.sha256(raw).hexdigest()


def read_pin(pin, base):
    need(type(pin) is dict and {'path', 'bytes', 'sha256'} <= pin.keys(), 'EXPLICIT_EVIDENCE_PIN')
    path = Path(pin['path'])
    if not path.is_absolute():
        path = base / path
    raw = path.read_bytes()
    need(type(pin['bytes']) is int and len(raw) == pin['bytes']
         and digest(raw) == pin['sha256'], 'EXACT_EXTERNAL_APPROVAL_EVIDENCE')
    return raw


def apply_allowlist(raw: bytes, allowlist: Path, expected_sha256: str, *, title: str | None = None):
    """Apply the actual V23 policy from a separately reviewed pinned allowlist."""
    allowlist = Path(allowlist)
    proof_raw = allowlist.read_bytes()
    need(digest(proof_raw) == expected_sha256, 'EXPLICIT_REVIEWED_ALLOWLIST_PIN')
    document = json.loads(proof_raw)
    need(document['format'] == SCHEMA
         and document['status'] == 'APPROVED_FOR_SCOPED_PHYSICAL_A97_TEST',
         'REVIEW_APPROVAL_REQUIRED_NOT_CANDIDATE_POOL')
    source_sha = digest(raw)
    need(document['chart']['sha256'] == source_sha and document['chart']['bytes'] == len(raw),
         'APPROVAL_BOUND_TO_EXACT_SOURCE')
    decisions = []
    evidence = []
    for row in document['rows']:
        need(row['approved'] is True and row['sameWindowFillAndHaloApproved'] is True
             and row['nonACFootprintsAndAdmissionApproved'] is True,
             'COMPLETE_VISUAL_AND_SOURCE_ADMISSION_REQUIRED')
        need(row['querySemanticDisposition'] == 'IDENTICAL_WITHIN_PROVED_CHILD_DOMAIN',
             'V23_REQUIRES_QUERY_IDENTITY_NO_NAUTICAL_LOSS_OVERRIDE')
        checked = {}
        for key in ('visualCertificate', 'admissionCertificate', 'querySemanticReport'):
            proof = read_pin(row[key], allowlist.parent)
            checked[key] = {'bytes': len(proof), 'sha256': digest(proof)}
        decisions.append(PruneDecision(
            source_sha256=source_sha, tile=tuple(row['tile']),
            child_offset=row['childNodeOffset'], parent_offset=row['parentNodeOffset'],
            parent_slot=row['parentChildSlot'], expected_child_ref_hex=row['expectedChildRefHex'],
            fallback_offset=row['fallbackVectorNodeOffset'], fallback_tile=tuple(row['fallbackTile']),
            visual_certificate_sha256=checked['visualCertificate']['sha256'],
            admission_certificate_sha256=checked['admissionCertificate']['sha256'],
            query_certificate_sha256=checked['querySemanticReport']['sha256'],
            query_disposition=row['querySemanticDisposition'],
            source_gate_disposition=row['nativeSourceGateDisposition'], approved=True,
        ))
        evidence.append({'tile': row['tile'], 'checkedEvidence': checked})
    need(bool(decisions), 'NO_APPROVED_LEAF_NO_APPLY_OR_FAKE_PRUNE')
    identity = uuid.uuid5(uuid.NAMESPACE_URL,
                          'no5-portable-v23:' + source_sha + ':' + expected_sha256).bytes_le
    pruned = apply_verified_prunes(raw, decisions, uuid_bytes=identity)
    output = pruned
    title_change = None
    if title is not None:
        need(type(title) is str and 1 <= len(title.strip()) <= 100
             and all(ord(c) >= 32 for c in title), 'EXPLICIT_VALID_MENU_TITLE')
        old_at = struct.unpack_from('<Q', raw, 136)[0]
        block = blocks(raw)[old_at]
        need((block.disk_id, block.flags, block.next_offset, block.prefix) == (2, 0, 0, b''),
             'KNOWN_PLAIN_CHARTINFO_XML')
        root = ET.fromstring(block.payload)
        info = root.find('chart_info')
        need(info is not None, 'CHARTINFO_NAME_ELEMENT')
        previous = info.get('name')
        info.set('name', title.strip())
        payload = ET.tostring(root, encoding='utf-8', xml_declaration=True)
        data = bytearray(pruned)
        new_at = len(data)
        data += struct.pack('<HHIQ', 2, 0, len(payload), 0) + payload
        struct.pack_into('<Q', data, 136, new_at)
        output = bytes(data)
        title_change = {'previous': previous, 'title': title.strip(),
                        'oldOffset': old_at, 'newOffset': new_at}
    inverse = bytearray(output[:len(raw)])
    inverse[56:72] = raw[56:72]
    inverse[136:144] = raw[136:144]
    for decision in decisions:
        at = decision.parent_offset + 16 + 8 * decision.parent_slot
        inverse[at:at + 8] = bytes.fromhex(decision.expected_child_ref_hex)
    need(bytes(inverse) == raw, 'COMPLETE_SOURCE_PREFIX_INVERSE')
    return output, {
        'status': 'APPLIED_REVIEWED_ALLOWLIST_WITH_EXACT_QUERY_POLICY',
        'actualLeafPrunes': len(decisions), 'sourceSHA256': source_sha,
        'allowlistSHA256': expected_sha256, 'outputSHA256': digest(output),
        'sourceTreeNodes': len(native_tree(raw)), 'outputTreeNodes': len(native_tree(output)),
        'physicalResourceBlocksRemoved': 0, 'geometryOrProgrammeBodiesModified': 0,
        'sourcePrefixInverseSHA256': digest(bytes(inverse)), 'titleChange': title_change,
        'checkedEvidence': evidence, 'externalProofMathIndependentlyRecomputed': False,
        'nativeDeviceOrLoadTimeAcceptanceClaim': False,
    }
