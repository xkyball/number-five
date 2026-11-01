"""Private, default-off postclip MASK8 alias prototype; no I/O or geometry work.

Caller source pins remain the provenance authority. Call only after the ordinary
Kind2 clip and final validator, with their exact source-bound owner and RO frame.
Raw enumeration and centroid parity are deliberately not claimed.
"""
from __future__ import annotations

from dataclasses import dataclass
import hashlib
import json
import struct

F = struct.Struct('<Q5I4B')
DRAW = frozenset((0x30000, 0x30001))
Q = 2147483649
LOW24 = (1 << 24) - 1
POLICY = 'EXPLICIT_FINAL_BOUNDARY_PART_IS_ONE_LC_RUN_MASK_ALIAS/1'
SPLIT_POLICY = 'SOURCE_TRIANGLE_AREA_MAIN_AND_SEPARATE_TOPOLOGY_LINE/1'


class Refusal(ValueError):
    pass


def need(ok, reason):
    if not ok:
        raise Refusal(reason)


def sha(raw):
    return hashlib.sha256(raw).hexdigest()


def canonical(value):
    try:
        return json.dumps(value, sort_keys=True, separators=(',', ':'),
                          allow_nan=False).encode()
    except (TypeError, ValueError) as error:
        raise Refusal('JSON_SOURCE_BINDING_REQUIRED') from error


def is_int(value, lo=0, hi=(1 << 32)-1):
    return type(value) is int and lo <= value <= hi


def is_sha(value):
    return type(value) is str and len(value) == 64 and all(c in '0123456789abcdef' for c in value)


@dataclass(frozen=True)
class Limits:
    """Caller work limits, not format limits or a request to enlarge a budget."""
    max_parts: int
    max_raw_point_occurrences: int
    max_referenced_frame_bytes: int
    max_output_index_bytes: int

    def check(self):
        need(all(is_int(v, 1) for v in (
            self.max_parts, self.max_raw_point_occurrences,
            self.max_referenced_frame_bytes, self.max_output_index_bytes)),
            'STRICT_POSITIVE_CALLER_LIMITS')


def _record(owner):
    need(type(owner) is dict and type(owner.get('record')) is bytes and
         len(owner['record']) == F.size, 'OWNER_RECORD32')
    fields = F.unpack(owner['record'])
    need(is_int(owner.get('uid'), 0, (1 << 64)-1) and fields[0] == owner['uid'],
         'OWNER_UID_RECORD_JOIN')
    return fields


def _commands(raw):
    """A narrow classifier. Any unknown syntax/command is KEEP, never removal."""
    if type(raw) is not bytes:
        return None
    try:
        text = raw.decode('ascii')
    except UnicodeDecodeError:
        return None
    if any(ord(c) < 32 for c in text):
        return None
    parts, start, depth, quoted = [], 0, 0, False
    for i, c in enumerate(text):
        if c == "'":
            quoted = not quoted
        elif not quoted:
            if c == '(':
                depth += 1
            elif c == ')':
                depth -= 1
                if depth < 0:
                    return None
            elif c == ';' and depth == 0:
                if text[start:i].strip():
                    parts.append(text[start:i].strip())
                start = i+1
    if quoted or depth:
        return None
    if text[start:].strip():
        parts.append(text[start:].strip())
    if any(not (x.startswith(('LC(', 'LS(')) and x.endswith(')')) for x in parts):
        return None
    # A second command without a separator is not a pure LC/LS argument list.
    for part in parts:
        depth, quoted = 0, False
        for i, c in enumerate(part):
            if c == "'":
                quoted = not quoted
            elif not quoted:
                if c == '(':
                    depth += 1
                elif c == ')':
                    depth -= 1
                    if depth == 0 and i != len(part)-1:
                        return None
    return parts


def _frame(actual_ro, offset):
    need(type(actual_ro) is dict and is_int(actual_ro.get('offset')) and
         actual_ro['offset'] == offset and type(actual_ro.get('raw')) is bytes,
         'ACTUAL_RO_OFFSET_AND_BYTES_REQUIRED')
    raw = actual_ro['raw']
    need(6 <= len(raw) <= 262144, 'BOUNDED_COMPLETE_RO_FRAME')
    count, query = struct.unpack_from('<HI', raw)
    entries, at = {}, 6
    for _ in range(count):
        need(at+6 <= len(raw), 'TRUNCATED_RO_ENTRY')
        key, size = struct.unpack_from('<IH', raw, at)
        at += 6
        need(key not in entries and at+size <= len(raw), 'UNIQUE_COMPLETE_RO_ENTRY')
        entries[key] = raw[at:at+size]
        at += size
    need(at == len(raw), 'EXACT_RO_FRAME_END')
    return entries, query


def _source(owner, sb, fields):
    """Validate the existing derived-role joins, not a class/UID heuristic."""
    need(type(sb) is dict and owner.get('sourceBinding') == sb, 'EXACT_SOURCE_BINDING_ARGUMENT')
    rel, receipt, resolution = (sb.get(k) for k in
                               ('primarySourceRelationship', 'sourceGeometryReceipt', 'resolution'))
    need(all(type(x) is dict for x in (rel, receipt, resolution)), 'COMPLETE_SOURCE_CARRIER_BINDING')
    deriv = resolution.get('sourceRoleDerivation')
    selected = resolution.get('selected')
    need(type(deriv) is dict and type(selected) is dict, 'DERIVED_SOURCE_STYLE_BINDING')
    uid, primary = fields[0], rel.get('primaryUID')
    need(all(is_int(v,0,(1 << 64)-1) for v in (
             primary,sb.get('uid'),rel.get('derivedUID'),deriv.get('authoredUID'),
             rel.get('derivedFromUID'),deriv.get('primaryUID'))) and primary != uid and
         uid == (primary | 0x80000000) and
         sb.get('uid') == rel.get('derivedUID') == deriv.get('authoredUID') == uid and
         rel.get('derivedFromUID') == deriv.get('primaryUID') == primary,
         'DERIVED_UID_CHAIN')
    digest = sb.get('featureSourceDigest')
    need(is_sha(digest) and digest == rel.get('featureSourceDigest') == deriv.get('featureSourceDigest'),
         'EXACT_SOURCE_FEATURE_DIGEST')
    need(type(sb.get('sourceId')) is str and sb['sourceId'] and
         sb['sourceId'] == rel.get('originalSourceId') == deriv.get('sourceId') and
         sb.get('cellId') == owner.get('cellId') and
         is_int(owner.get('sourceOwnerOrdinal')) and
         type(sb.get('sourceOwnerOrdinal')) is int and
         type(rel.get('nativeSourceOwnerOrdinal')) is int and
         rel.get('nativeSourceOwnerOrdinal') == sb['sourceOwnerOrdinal'] == owner['sourceOwnerOrdinal'] and
         is_int(sb.get('sourceOriginalOwnerOrdinal')) and
         type(rel.get('sourceOriginalOwnerOrdinal')) is int and
         rel.get('sourceOriginalOwnerOrdinal') == sb['sourceOriginalOwnerOrdinal'] and
         is_int(sb.get('sourceFeatureId')) and
         type(rel.get('originalSourceFeatureId')) is int and
         rel.get('originalSourceFeatureId') == sb['sourceFeatureId'], 'SOURCE_OWNER_CELL_ORDINAL')
    need(rel.get('role') == deriv.get('role') == 'source_boundary_line' and
         rel.get('queryAttrsAliasPrimary') is True and
         receipt.get('derivedBoundaryLineCarrier') is True and
         receipt.get('sourceGeometryKind') == 'area' and
         receipt.get('geometryRepair') is False and
         deriv.get('splitPolicy') == SPLIT_POLICY and
         is_sha(deriv.get('profileSHA256')) and is_sha(deriv.get('originalSourceKey')) and
         selected.get('effectiveSourceKeySHA256') == sha(canonical(deriv)),
         'DERIVED_BOUNDARY_ROLE_AND_STYLE_KEY')
    try:
        original = bytes.fromhex(sb['originalRecord32Hex'])
    except (KeyError, TypeError, ValueError) as error:
        raise Refusal('ORIGINAL_RECORD32_BINDING') from error
    need(len(original) == 32 and original[:8] == owner['record'][:8] and
         original[20:] == owner['record'][20:] and sb.get('kind') == fields[6] == 2,
         'RECORD_COMPLEMENT_AFTER_EXISTING_I_RO_DICT_REBASE')
    return deriv


def separate_boundary_runs(owner, elements, actual_ro, source_binding, *, policy=None, limits):
    """Return elements + ledger. Default-off KEEP; never changes any CO bytes.

    Noneligible/mixed/unknown/LS-only returns the original elements object.
    A malformed declared positive binding refuses rather than pretending success.
    """
    fields = _record(owner)
    base = {'format':'no5-postclip-boundary-mask-alias-ledger/1',
            'uid':fields[0], 'policy':policy, 'centroidOrGeneralQueryParity':False,
            'nativePixelOrPatternPhaseParity':False}
    def keep(reason):
        return elements, dict(base, status='KEEP', reason=reason, aliases=[])
    if policy is None:
        return keep('DEFAULT_OFF')
    need(policy == POLICY, 'EXPLICIT_SUPPORTED_RUN_POLICY')
    need(isinstance(limits, Limits), 'EXPLICIT_CALLER_LIMITS')
    limits.check()
    rel = source_binding.get('primarySourceRelationship') if type(source_binding) is dict else None
    if fields[6] != 2 or owner.get('role') != 'SOURCE_WINNER_DETAIL' or \
            type(rel) is not dict or rel.get('role') != 'source_boundary_line':
        return keep('UNBOUND_OR_NON_BOUNDARY_CARRIER')
    deriv = _source(owner, source_binding, fields)
    entries, query = _frame(actual_ro, fields[2])
    if set(entries) != DRAW:
        return keep('EXTRA_OR_MISSING_RO_CONTEXT_OR_ANCHOR')
    commands = [_commands(entries[k]) for k in sorted(DRAW)]
    if any(x is None for x in commands):
        return keep('MIXED_OR_UNKNOWN_PROGRAMME')
    if not any(t.startswith('LC(') for row in commands for t in row):
        return keep('LS_ONLY_OR_EMPTY_NO_CHANGE_NEEDED')
    expected = deriv.get('programmes')
    need(type(expected) is dict and set(expected) == {str(k) for k in DRAW} and
         all(expected[str(k)] == entries[k].hex() for k in DRAW),
         'ACTUAL_BOTH_RO_CONTEXTS_EXACT_TO_SOURCE_DERIVATION')
    ctx = owner.get('postclipContext')
    need(type(ctx) is dict and ctx.get('stage') == 'AFTER_CLIP_AND_FINAL_VALIDATION' and
         ctx.get('validationPassed') is True and
         is_int(ctx.get('requestedLevel'), 0, 29) and
         is_int(ctx.get('authoringLastLevel'), ctx['requestedLevel'], 29),
         'POSTCLIP_FINAL_VALIDATION_REQUIRED')
    need(type(elements) in (list, tuple) and 1 <= len(elements) <= limits.max_parts,
         'BOUNDED_FINAL_PARTS')
    n = len(elements)
    need(4+4*(2*n-1) <= limits.max_output_index_bytes and 2*n-1 < 1 << 32,
         'OUTPUT_INDEX_WORK_BUDGET')
    frame_rows, total_bytes, points = [], 0, 0
    for i, element in enumerate(elements):
        need(type(element) in (tuple, list) and len(element) == 4, 'ELEMENT4')
        usage, raw, bbox, pts = element
        need(type(usage) is int and usage == 16, 'ONLY_FORWARD16_NO_DOUBLE_APPLICATION')
        need(type(raw) is bytes and len(raw) >= 20, 'EXACT_NONEMPTY_CO_FRAME')
        h, = struct.unpack_from('<I', raw)
        count = h & LOW24
        total_bytes += len(raw)
        points += count
        need(h >> 24 == 1 and 2 <= count and len(raw) == 4+8*count and
             total_bytes <= limits.max_referenced_frame_bytes and
             points <= limits.max_raw_point_occurrences, 'FRAME_EXTENT_OR_INPUT_WORK_BUDGET')
        need(type(pts) in (list, tuple) and len(pts) == count and
             all(type(p) in (tuple, list) and len(p) == 2 and
                 all(is_int(v, 0, Q) for v in p) for p in pts) and
             [tuple(p) for p in pts] == list(struct.iter_unpack('<II', raw[4:])),
             'EXACT_RAW_POINT_ROWS')
        box = (min(p[0] for p in pts), min(p[1] for p in pts),
               max(p[0] for p in pts), max(p[1] for p in pts))
        need(type(bbox) in (tuple, list) and len(bbox) == 4 and
             all(type(v) is int for v in bbox) and tuple(bbox) == box, 'EXACT_FRAME_BBOX')
        frame_rows.append({'originalFinalPartOrdinal':i, 'usage':16, 'bytes':len(raw),
                           'sha256':sha(raw), 'vertexCount':count, 'bbox':list(box)})
    # Alias duplication is itself resource work, even though there is no new CO.
    raw_points = points + sum(x['vertexCount'] for x in frame_rows[1:])
    need(raw_points <= limits.max_raw_point_occurrences, 'ALIAS_POINT_WORK_BUDGET')
    raw_frame_bytes = total_bytes + sum(x['bytes'] for x in frame_rows[1:])
    need(raw_frame_bytes <= limits.max_referenced_frame_bytes, 'ALIAS_FRAME_WORK_BUDGET')
    result, aliases = [elements[0]], []
    for i, e in enumerate(elements[1:], 1):
        mask_ordinal = len(result)
        result.extend([(8, e[1], e[2], e[3]), e])
        aliases.append({'rawOrdinal':mask_ordinal, 'role':'LC_RUN_SEPARATOR_ALIAS',
                        'aliasOfNextVisibleRawOrdinal':mask_ordinal+1,
                        'originalFinalPartOrdinal':i, 'originalSourceRingId':None,
                        'frameSHA256':frame_rows[i]['sha256']})
    need([e for e in result if e[0] == 16] == list(elements), 'VISIBLE_REFERENCE_INVERSE')
    ledger = dict(base, status='APPLIED' if aliases else 'ELIGIBLE_NOOP', reason='FINAL_PART_RUN_POLICY',
        cellId=owner['cellId'], sourceOwnerOrdinal=owner['sourceOwnerOrdinal'],
        sourceId=source_binding['sourceId'], sourceFeatureDigest=source_binding['featureSourceDigest'],
        sourceBindingSHA256=sha(canonical(source_binding)),
        sourceCarrierKey=source_binding['resolution']['selected']['effectiveSourceKeySHA256'],
        primaryUID=source_binding['primarySourceRelationship']['primaryUID'],
        postclipContext=dict(ctx), preEmitRecordSHA256=sha(owner['record']),
        recordOther28SHA256=sha(owner['record'][:8]+owner['record'][12:]),
        actualRenderOffset=fields[2], actualAdditionalOffset=query,
        actualROFrameSHA256=sha(actual_ro['raw']),
        actualProgrammeSHA256={str(k):sha(entries[k]) for k in sorted(DRAW)},
        beforeRawParts=n, afterRawParts=len(result), visibleParts=n,
        beforeRawPoints=points, afterRawPoints=raw_points, visiblePoints=points,
        beforeReferencedFrameBytes=total_bytes, afterReferencedFrameBytes=raw_frame_bytes,
        inputVisibleFrames=frame_rows, aliases=aliases, newCOBodies=0,
        inputReferenceListSHA256=sha(canonical(frame_rows)))
    return result, ledger


def join_serialized_owner(ledger, *, pre_emit_record, final_record, index_pool,
                          arena, arena_name, tile):
    """Pure post-write check against actual existing writer buffers, not a builder."""
    need(type(ledger) is dict and ledger.get('status') in ('APPLIED','ELIGIBLE_NOOP'),
         'QUALIFIED_ALIAS_LEDGER')
    need(type(pre_emit_record) is bytes and type(final_record) is bytes and
         len(pre_emit_record) == len(final_record) == 32 and
         sha(pre_emit_record) == ledger['preEmitRecordSHA256'] and
         pre_emit_record[:8] == final_record[:8] and pre_emit_record[12:] == final_record[12:],
         'FI_ONLY_INDEX_OFFSET_REBASED')
    fields = F.unpack(final_record)
    need(fields[0] == ledger['uid'] and fields[6] == 2, 'FINAL_UID_KIND')
    need(type(arena_name) is str and arena_name and type(tile) in (tuple,list) and
         len(tile) == 3 and is_int(tile[0],0,29) and
         all(is_int(x,0,(1 << tile[0])-1) for x in tile[1:]), 'TILE_ARENA_BINDING')
    need(type(index_pool) in (bytes,bytearray) and type(arena) in (bytes,bytearray) and
         len(arena) < 1 << 24, 'EXISTING_BOUNDED_WRITER_BUFFERS')
    at, count = fields[1], ledger['afterRawParts']
    need(is_int(ledger.get('visibleParts'),1) and is_int(count,1) and
         count == 2*ledger['visibleParts']-1 and
         len(ledger['inputVisibleFrames']) == ledger['visibleParts'] and
         [r['rawOrdinal'] for r in ledger['aliases']] == list(range(1,count,2)) and
         [r['originalFinalPartOrdinal'] for r in ledger['aliases']] ==
             list(range(1,ledger['visibleParts'])), 'STRUCTURAL_ALIAS_LEDGER')
    size = 4+4*count
    need(at+size <= len(index_pool) and struct.unpack_from('<I',index_pool,at)[0] == count,
         'ACTUAL_GLOBAL_I_EXTENT_COUNT')
    aliases = {r['rawOrdinal']:r for r in ledger['aliases']}
    offsets, visible, alias_rows = [], [], []
    for i in range(count):
        word, = struct.unpack_from('<I',index_pool,at+4+4*i)
        usage, off = word >> 24, word & LOW24
        j = aliases[i]['originalFinalPartOrdinal'] if i in aliases else len(visible)
        need(j < len(ledger['inputVisibleFrames']), 'OUTPUT_VISIBLE_ORDINAL')
        f = ledger['inputVisibleFrames'][j]
        need(usage == (8 if i in aliases else 16) and off+f['bytes'] <= len(arena) and
             sha(memoryview(arena)[off:off+f['bytes']]) == f['sha256'],
             'ACTUAL_ARENA_FRAME_AND_USAGE')
        offsets.append(off)
        if i not in aliases:
            visible.append(j)
    need(visible == list(range(ledger['visibleParts'])), 'FINAL_VISIBLE_ORDER')
    for i, row in aliases.items():
        nxt = row['aliasOfNextVisibleRawOrdinal']
        need(nxt == i+1 < count and offsets[i] == offsets[nxt], 'ACTUAL_MASK_NEXT_OFFSET_EQUAL')
        alias_rows.append(dict(row, actualCOOffset=offsets[i]))
    return {'format':'no5-postclip-mask-alias-emitted-binding/1', 'uid':fields[0],
            'postclipAliasLedgerSHA256':sha(canonical(ledger)),
            'tile':list(tile), 'arenaName':arena_name, 'indexOffset':at,
            'indexBytes':size, 'indexSHA256':sha(memoryview(index_pool)[at:at+size]),
            'record32SHA256':sha(final_record), 'FIExceptIndexExact':True,
            'renderOffset':fields[2], 'rawParts':count, 'visibleParts':len(visible),
            'aliasOffsets':alias_rows, 'nativeQueryOrPixelParity':False}
