"""Default-off application of an external finite-view ink certificate.

This module does not prove ink exclusion or interpret drawing instructions.
It binds a reviewed runtime certificate to complete input banks and preserves
all query/geometry bytes while preparing ordinary FI/RO byte buffers.
"""
from __future__ import annotations

import hashlib
import json
import re
import struct

from . import metadata, model
from .wire import FEATURE, need

POLICY = 'EXPLICIT_FINITE_VIEW_DRAW_CONTEXT_CULL/1'
FORMAT = 'no5-finite-view-draw-admission/1'
NAMES = ('features', 'indices', 'coordinates', 'render', 'additional')
OUTPUTS = ('featureBytes', 'indexBytes', 'coordinateBytes', 'renderBytes', 'additionalBytes')
MAX_VIEWS = 64
MAX_OWNERS = 64
MAX_CONTEXTS = 64
MAX_BANK_BYTES = 8 << 20


def _sha(raw):
    return hashlib.sha256(raw).hexdigest()


def _pins(buffers):
    return {key: dict(bytes=len(raw), sha256=_sha(raw)) for key, raw in zip(NAMES, buffers)}


def _pin(value):
    need(type(value) is dict and set(value) == {'bytes', 'sha256'} and
         type(value['bytes']) is int and value['bytes'] > 0 and
         type(value['sha256']) is str and re.fullmatch('[0-9a-f]{64}', value['sha256']) is not None,
         'COMPLETE_EXTERNAL_PIN')


def _tile(value):
    need(type(value) in (list, tuple) and len(value) == 3 and
         all(type(v) is int for v in value), 'EXPLICIT_INTEGER_TILE')
    z, x, y = value
    need(0 <= z <= 29 and 0 <= x < 1 << z and 0 <= y < 1 << z, 'FINITE_LEGAL_TILE')
    return tuple(value)


def _frame(render, offset):
    need(0 <= offset <= len(render)-6 and
         struct.unpack_from('<H', render, offset)[0] <= MAX_CONTEXTS,
         'BOUNDED_RENDER_FRAME')
    stream = metadata.Stream(len(render), lambda at, count: render[at:at+count])
    raw, query = metadata.render_frame(stream, offset)
    contexts, parsed_query = model.programmes(render, offset)
    need(query == parsed_query, 'QUERY_REFERENCE_INVERSE')
    return contexts, query, raw


def _encode(contexts, query):
    return struct.pack('<HI', len(contexts), query) + b''.join(
        struct.pack('<IH', int(key), len(value)) + value
        for key, value in contexts.items())


def prepare_finite_view_draw_cull(features, indices, coordinates, render, additional, *,
        policy=None, source_tile=None, parent_pin=None, requested_views=(),
        request_profile=None, evidence_bytes=None, expected_evidence_sha256=None):
    """Prepare a scoped draw variant; never write a file or enable a route.

    The sealed external certificate supplies the complete ink/phase/raster
    theorem. Each selected record must qualify in every requested view.
    Unknown views, profiles, owners and programmes KEEP their original bytes.
    Changed input-bank/parent seals refuse preparation before any output edit.
    The caller must enforce allowedNativeRequests outside this byte transform.
    """
    buffers = (features, indices, coordinates, render, additional)
    original = dict(zip(OUTPUTS, buffers))

    def keep(reason):
        return dict(**original, receipt=dict(status='KEEP', reason=reason,
            changedOwners=0, appliedToFile=False, nativePixelsQualified=False,
            globalSourceApplyAllowed=False, callerMustEnforceFiniteRequests=True))

    if policy is None:
        return keep('DEFAULT_OFF')
    need(policy == POLICY, 'EXPLICIT_KNOWN_FINITE_DRAW_POLICY')
    need(all(type(v) is bytes for v in buffers) and 0 < len(features) <= 2 << 20 and
         len(features) % FEATURE.size == 0 and sum(map(len, buffers)) <= MAX_BANK_BYTES,
         'COMPLETE_BOUNDED_IMMUTABLE_BANKS')
    need(type(evidence_bytes) is bytes and 0 < len(evidence_bytes) <= 1 << 20 and
         type(expected_evidence_sha256) is str and
         _sha(evidence_bytes) == expected_evidence_sha256, 'SEALED_FINITE_VIEW_EVIDENCE')
    evidence = json.loads(evidence_bytes)
    need(type(evidence) is dict and evidence.get('format') == FORMAT and
         evidence.get('status') == 'SOURCE_INK_EXCLUSION_QUALIFIED', 'QUALIFIED_EXTERNAL_INK_CERTIFICATE')
    _pin(evidence.get('proofPin'))
    _pin(parent_pin)
    need(parent_pin == evidence.get('parentPin') and
         _tile(source_tile) == _tile(evidence.get('sourceTile')), 'EXACT_PARENT_AND_SOURCE_TILE')
    need(evidence.get('inputPins') == _pins(buffers), 'FIVE_COMPLETE_INPUT_BANK_PINS')
    need(type(request_profile) is str and 0 < len(request_profile) <= 128, 'EXPLICIT_REQUEST_PROFILE')
    if request_profile != evidence.get('requestProfile'):
        return keep('UNCONFIGURED_PROFILE')
    need(type(requested_views) in (tuple, list) and 0 < len(requested_views) <= MAX_VIEWS,
         'FINITE_EXPLICIT_VIEW_REQUESTS')
    requested = tuple(_tile(tile) for tile in requested_views)
    need(len(set(requested)) == len(requested), 'DISTINCT_VIEW_REQUESTS')
    views = evidence.get('views')
    need(type(views) is list and 0 < len(views) <= MAX_VIEWS, 'FINITE_CERTIFIED_VIEWS')
    indexed = {}
    for view in views:
        need(type(view) is dict, 'EXPLICIT_VIEW_RECORD')
        tile = _tile(view.get('destinationTile'))
        need(tile not in indexed, 'UNIQUE_CERTIFIED_VIEW')
        indexed[tile] = view
    if any(tile not in indexed for tile in requested):
        return keep('UNCONFIGURED_VIEW')
    proofs = {}
    for tile in requested:
        rows = indexed[tile].get('owners')
        need(type(rows) is list and len(rows) <= MAX_OWNERS, 'FINITE_OWNER_PROOFS')
        seen = set()
        for row in rows:
            need(type(row) is dict and type(row.get('recordOrdinal')) is int and
                 0 <= row['recordOrdinal'] < len(features)//FEATURE.size and
                 row['recordOrdinal'] not in seen, 'UNIQUE_VALID_RECORD_ORDINAL')
            seen.add(row['recordOrdinal'])
            proofs.setdefault(row['recordOrdinal'], []).append(row)

    fi, tail, changed, kept = bytearray(features), bytearray(), [], []
    for ordinal, rows in sorted(proofs.items()):
        def retain(reason):
            kept.append(dict(recordOrdinal=ordinal, reason=reason))
        if len(rows) != len(requested) or not all(row.get('sourceInkExcluded') is True for row in rows):
            retain('NOT_EXCLUDED_IN_EVERY_REQUESTED_VIEW')
            continue
        record = features[ordinal*FEATURE.size:(ordinal+1)*FEATURE.size]
        fields = FEATURE.unpack(record)
        if not all(type(row.get('uid')) is int and row['uid'] == fields[0] and
                   row.get('recordSHA256') == _sha(record) for row in rows):
            retain('UNRECOGNIZED_COMPLETE_OWNER')
            continue
        try:
            contexts, query, old_frame = _frame(render, fields[2])
        except (ValueError, struct.error):
            retain('UNSUPPORTED_RENDER_FRAME')
            continue
        if not all(row.get('programmeFrameSHA256') == _sha(old_frame) for row in rows):
            retain('UNRECOGNIZED_PROGRAMME')
            continue
        keys = rows[0].get('emptyContextIDs')
        need(type(keys) is list and 0 < len(keys) <= MAX_CONTEXTS and
             all(type(key) is int and 0 <= key < 1 << 32 for key in keys) and
             len(set(keys)) == len(keys), 'EXPLICIT_DISTINCT_DRAW_CONTEXTS')
        if not all(row.get('emptyContextIDs') == keys for row in rows) or any(str(key) not in contexts for key in keys):
            retain('CONTEXT_POLICY_NOT_IDENTICAL_IN_EVERY_VIEW')
            continue
        updated = {key: b'' if int(key) in keys else value for key, value in contexts.items()}
        if updated == contexts:
            retain('DRAW_CONTEXTS_ALREADY_EMPTY')
            continue
        replacement = _encode(updated, query)
        offset = len(render)+len(tail)
        need(offset+len(replacement) <= MAX_BANK_BYTES, 'BOUNDED_APPENDED_RENDER')
        inverse_contexts, inverse_query = model.programmes(replacement, 0)
        need(inverse_contexts == updated and list(inverse_contexts) == list(contexts) and
             inverse_query == query, 'ORDERED_CONTEXT_AND_QUERY_INVERSE')
        struct.pack_into('<I', fi, ordinal*FEATURE.size+12, offset)
        tail.extend(replacement)
        changed.append(dict(recordOrdinal=ordinal, uid=fields[0], oldROOffset=fields[2],
            newROOffset=offset, queryOffset=query, newFrameBytes=len(replacement),
            oldFrameSHA256=_sha(old_frame), newFrameSHA256=_sha(replacement)))

    output = (bytes(fi), indices, coordinates, render+bytes(tail), additional)
    inverse = bytearray(output[0])
    for row in changed:
        at = row['recordOrdinal']*FEATURE.size+12
        inverse[at:at+4] = features[at:at+4]
    need(bytes(inverse) == features and output[3][:len(render)] == render,
         'COMPLETE_FI_AND_RENDER_PREFIX_INVERSE')
    receipt = dict(format='no5-finite-view-draw-application/1', policy=POLICY,
        status='PREPARED' if changed else 'KEEP', changedOwners=len(changed),
        owners=changed, keptOwners=kept, totalOwners=len(features)//FEATURE.size,
        allowedNativeRequests=[list(tile) for tile in requested], sourceTile=list(source_tile),
        requestProfile=request_profile, evidenceSHA256=expected_evidence_sha256,
        proofPin=evidence['proofPin'], parentPin=parent_pin,
        inputPins=_pins(buffers), outputPins=_pins(output), appendedRenderBytes=len(tail),
        fullQueryUIDClassAttributesAndGeometryExact=True, originalRenderPrefixExact=True,
        allUnselectedContextKeysAndBytesExact=True, noOwnerAddedRemovedOrDeduplicated=True,
        sidecarEnforcesScope=False, callerMustEnforceFiniteRequests=True,
        globalSourceApplyAllowed=False, appliedToFile=False, nativePixelsQualified=False,
        performanceClaim=False, OriginalCalls=0, chartWrites=0)
    return dict(zip(OUTPUTS, output), receipt=receipt)
