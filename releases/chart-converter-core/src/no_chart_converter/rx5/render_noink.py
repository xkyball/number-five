"""Keep canonical query geometry and omit proved invisible pure AC draw work.

This pure helper prepares one complete scene for an explicitly bound source
window. It appends RO frames, emptying only draw values 0x30000/0x30001 for
selected, independently certified owners. All geometry, query attributes,
category/detail contexts, owner order and other record fields remain exact.
A caller must bind the returned scene to the corresponding actual child source;
a sidecar or this receipt does not enforce source selection in the reader.
"""
from __future__ import annotations

import hashlib
import json
import re
import struct

from . import locality_empty, locality_parts, metadata
from .model import parts
from .wire import FEATURE, need

POLICY = 'full-query-geometry-source-window-empty-pure-ac-draw-contexts/1'
DRAW_CONTEXTS = (0x30000, 0x30001)
NAMES = ('features', 'indices', 'coordinates', 'render', 'additional')
AC = re.compile(rb'AC\([A-Z0-9_]+\)')
ROOT = 2147483649
MAX_LEVEL = 29


def _sha(raw):
    return hashlib.sha256(raw).hexdigest()


def _pins(buffers):
    return {key: {'bytes': len(raw), 'sha256': _sha(raw)} for key, raw in zip(NAMES, buffers)}


def _tile(tile):
    need(type(tile) in (tuple, list) and len(tile) == 3 and all(type(v) is int for v in tile),
         'EXPLICIT_INTEGER_SOURCE_TILE')
    z, x, y = tile
    need(0 <= z <= MAX_LEVEL and 0 <= x < 1 << z and 0 <= y < 1 << z, 'SOURCE_TILE_BOUNDS')
    return z, x, y


def _frame(render, offset):
    need(0 <= offset <= len(render)-6, 'COMPLETE_RENDER_FRAME_HEADER')
    count, query = struct.unpack_from('<HI', render, offset)
    pos, contexts, seen = offset+6, [], set()
    for _ in range(count):
        need(pos+6 <= len(render), 'COMPLETE_RENDER_CONTEXT_HEADER')
        key, size = struct.unpack_from('<IH', render, pos)
        pos += 6
        need(key not in seen and pos+size <= len(render), 'UNIQUE_COMPLETE_RENDER_CONTEXT')
        seen.add(key)
        contexts.append((key, render[pos:pos+size]))
        pos += size
    return contexts, query, render[offset:pos]


def _pure_ac(programme):
    tokens = programme.split(b';')
    if tokens and tokens[-1] == b'':
        tokens.pop()
    return bool(tokens) and all(AC.fullmatch(token) is not None for token in tokens)


def _certificate(record, indices, coordinates, tile):
    fields = FEATURE.unpack(record)
    need(fields[6] == 3, 'ONLY_COMPLETE_KIND3_PURE_AC_OWNER')
    pp = list(parts(record, indices, coordinates))
    need(pp, 'NONEMPTY_CANONICAL_AREA')
    z, x, y = tile
    # The gap is wider than the original F64/F32/Qt conversion error and the
    # retained 32-sourcepixel draw halo at every descendant level through29.
    guard = max(128, 32+(1 << max(z-12, 0))+16)
    den = 256*(1 << z)
    domain = locality_empty.Domain(den, (256*x-guard)*ROOT, (256*y-guard)*ROOT,
                                  (256*(x+1)+guard)*ROOT, (256*(y+1)+guard)*ROOT)
    level_bounds = []
    for level in range(z, MAX_LEVEL+1):
        error = (1 << max(level-12, 0))+8
        gap = (guard-32)*(1 << (level-z))
        need(error+3 < gap, 'PROJECTION_ERROR_WITHIN_PROTECTED_DRAW_HALO')
        level_bounds.append({'level': level, 'conversionErrorPixels': error,
                             'protectedHaloGapPixels': gap, 'conservativeSupportPixels': 3})
    rows = []
    for part in pp:
        points = part['points']
        need(part['rootFlag'] == 1 and part['usage'] in locality_empty.USAGES
             and len(points) >= 4 and points[0] == points[-1], 'KNOWN_CLOSED_ABSOLUTE_ROOT1_PART')
        need(not any(domain.hits(a, b) for a, b in zip(points, points[1:]))
             and domain.winding(points) == 0, 'ALL_CANONICAL_PARTS_OUTSIDE_SOURCE_DRAW_DOMAIN')
        projection = locality_parts.projection_safety(points, tile, max_level=MAX_LEVEL)
        need(projection is not None, 'ALL_CANONICAL_PARTS_SAFE_NATIVE_PROJECTION_THROUGH29')
        rows.append({'partOrdinal': part['partOrdinal'], 'usage': part['usage'],
                     'sourceTypedElementSHA256': _sha(part['raw']), 'points': part['pointCount'],
                     'allSegmentsDisjointFromGuard': True, 'guardCentreWinding': 0,
                     'nativeProjectionSafety': projection})
    return {'sourceTile': list(tile), 'domain': domain.serial(), 'geometryGuardSourcePixels': guard,
            'protectedDrawHaloSourcePixels': 32, 'maximumDescendantLevel': MAX_LEVEL,
            'levelErrorBounds': level_bounds, 'parts': rows,
            'conclusion': 'NO_AC_FILL_IN_PROTECTED_SOURCE_DRAW_DOMAIN'}


def prepare_no_ink_render(features, indices, coordinates, render, additional, *,
                          source_tile, owner_uids, expected_input_sha256):
    """Return five buffers and a source-bound preparation receipt.

    Every requested UID must occur exactly once, be Kind3, have two nonempty
    pure AC draw contexts and pass the full canonical geometry certificate.
    Any failed obligation refuses the entire preparation before returning.
    Values of every other context and each RO query offset remain byte exact.
    This helper does not write a chart or alter the normal selection pipeline.
    """
    buffers = (features, indices, coordinates, render, additional)
    need(all(type(b) is bytes for b in buffers), 'IMMUTABLE_FIVE_SOURCE_BUFFERS')
    need(len(features) % FEATURE.size == 0, 'COMPLETE_FEATURE32_ARRAY')
    tile = _tile(source_tile)
    need(type(owner_uids) in (tuple, list) and bool(owner_uids)
         and all(type(u) is int and 0 <= u < 1 << 64 for u in owner_uids)
         and len(set(owner_uids)) == len(owner_uids), 'EXPLICIT_UNIQUE_OWNER_UIDS')
    requested = sorted(owner_uids)
    source_pins = _pins(buffers)
    need(type(expected_input_sha256) is dict and set(expected_input_sha256) == set(NAMES)
         and all(type(expected_input_sha256[k]) is str
                 and expected_input_sha256[k] == source_pins[k]['sha256'] for k in NAMES),
         'EXACT_FIVE_INPUT_SHA256_BINDING')
    selected = {}
    for ordinal, f in enumerate(FEATURE.iter_unpack(features)):
        if f[0] in requested:
            need(f[0] not in selected, 'SELECTED_UID_SINGLE_FEATURE_REQUIRED')
            selected[f[0]] = ordinal
    need(set(selected) == set(requested), 'EVERY_REQUESTED_UID_PRESENT')
    prepared = []
    for uid in requested:
        ordinal = selected[uid]
        record = features[ordinal*32:(ordinal+1)*32]
        fields = FEATURE.unpack(record)
        contexts, query, original = _frame(render, fields[2])
        by_key = dict(contexts)
        need(all(key in by_key and _pure_ac(by_key[key]) for key in DRAW_CONTEXTS),
             'BOTH_SELECTED_DRAW_CONTEXTS_NONEMPTY_PURE_AC')
        # Validate the unchanged real query frame; never fabricate or rebase AO.
        query_raw = metadata.additional_frame(metadata.Stream(len(additional),
            lambda at, size: additional[at:at+size]), query)
        certificate = _certificate(record, indices, coordinates, tile)
        changed = [(k, b'' if k in DRAW_CONTEXTS else v) for k, v in contexts]
        rewritten = struct.pack('<HI', len(changed), query)+b''.join(
            struct.pack('<IH', k, len(v))+v for k, v in changed)
        prepared.append((ordinal, fields[2], rewritten,
            {'uid': uid, 'recordOrdinal': ordinal, 'sourceRecordSHA256': _sha(record),
             'sourceRenderOffset': fields[2], 'sourceRenderFrameSHA256': _sha(original),
             'queryOffset': query, 'queryFrameSHA256': _sha(query_raw),
             'emptyDrawContextKeys': list(DRAW_CONTEXTS),
             'retainedContextKeys': [k for k, _ in contexts if k not in DRAW_CONTEXTS],
             'noInkCertificate': certificate}))
    output_f, output_ro = bytearray(features), bytearray(render)
    frames, rows = {}, []
    for ordinal, old_offset, rewritten, row in prepared:
        if old_offset not in frames:
            need(len(output_ro)+len(rewritten) <= 0xffffffff, 'RENDER_BLOCK_PAYLOAD_U32_EXTENT')
            frames[old_offset] = len(output_ro)
            output_ro += rewritten
        row['newRenderOffset'] = frames[old_offset]
        struct.pack_into('<I', output_f, ordinal*32+12, frames[old_offset])
        rows.append(row)
    output = (bytes(output_f), indices, coordinates, bytes(output_ro), additional)
    need(output[3][:len(render)] == render, 'ORIGINAL_RENDER_PREFIX_EXACT')
    for ordinal in range(len(features)//32):
        old, new = features[ordinal*32:(ordinal+1)*32], output[0][ordinal*32:(ordinal+1)*32]
        need(old[:12]+old[16:] == new[:12]+new[16:], 'ONLY_SELECTED_FEATURE_RO_OFFSET_CHANGED')
        if ordinal not in selected.values():
            need(old == new, 'UNSELECTED_RECORD_EXACT')
    for row in rows:
        before, query, _ = _frame(render, row['sourceRenderOffset'])
        after, actual_query, _ = _frame(output[3], row['newRenderOffset'])
        need(query == actual_query and [k for k, _ in before] == [k for k, _ in after], 'CONTEXT_ORDER_AND_QUERY_POINTER_EXACT')
        need(all(v == b'' if k in DRAW_CONTEXTS else v == original
                 for (k, original), (_, v) in zip(before, after)), 'ONLY_TWO_DRAW_VALUES_EMPTIED')
    receipt = {'format': 'no5-full-query-geometry-no-ink-render-preparation/1', 'policy': POLICY,
               'sourceTile': list(tile), 'inputPins': source_pins, 'outputPins': _pins(output),
               'selectedOwnerUIDs': requested, 'preparedOwners': rows,
               'appendedRenderFrames': len(frames), 'appendedRenderBytes': len(output[3])-len(render),
               'geometryIndicesAdditionalAndAllUIDsExact': True,
               'allQueryCategoryDetailAndOtherContextValuesExact': True,
               'originalRenderPrefixExact': True, 'onlyFeatureROOffsetsChanged': True,
               'requiresActualChildSourceBinding': True, 'defaultPipelineChanged': False,
               'nativeQueryResultEqualityClaim': False, 'nativeFrameParityClaim': False,
               'hardwareTimeClaim': False}
    receipt['preparationSHA256'] = _sha(json.dumps(receipt, sort_keys=True, separators=(',', ':')).encode('ascii'))
    return dict(zip(('featureBytes', 'indexBytes', 'coordinateBytes', 'renderBytes', 'additionalBytes'), output), receipt=receipt)
