"""Classify ordered polygon membership for the all-closed Root1 area subset.

The retained complete original Area.loadData/readPolygon listings establish:
source order is consumed without mask/hole filtering; a polygon closes when its
first decoded container front equals its last decoded container back. For a
single Root1 part whose source endpoints are identical, either local traversal
orientation preserves that equality. Thus each part closes immediately.

This module deliberately does not group open parts by unequal raw endpoints:
unknown decoding/projection effects must not be guessed. It does not predict
point traversal order, query/null-edge behaviour, raster output or apply safety.
No original code is imported or executed. Inputs are immutable caller-supplied
bytes, not receipt assertions or paths.
"""
from __future__ import annotations

import hashlib
import struct

FORMAT = 'no5-closed-root1-area-polygon-membership/1'
MODEL = 'SOURCE_ORDER_ALL_PARTS_CLOSED_ROOT1_NO_MASK_OR_HOLE_FILTER/1'
DEFAULT_MAX_PARTS = 65536
DEFAULT_MAX_TOTAL_TYPED_BYTES = 16 * 1024 * 1024  # Tool budget, not a format limit.
MODEL_EVIDENCE = {
    'interpretation': 'STATIC_ORIGINAL_METHOD_LISTINGS_NOT_NEW_ORIGINAL_EXECUTION',
    'areaReadPolygonCodeSHA256': '18909b23166525833895cc54fa2a4a83de5f145bb403770142c31e1108e1611d',
    'areaRoleListingReportSHA256': 'e6fbc96c6cfb609062b978baf395cc1db91543a88220ace13edd74497ef9cb9c',
    'areaLoadDataCodeSHA256': '60851af1767886198be5b5fd7224c6800d872ac5b96c48148bceef1a60adc3b8',
    'areaLoadDataListingReportSHA256': 'ed814acb8e0c0a30cd3f5bbba370fdb22a656a527265ead9de94985c9208f7a1',
}


def _need(ok, message):
    if not ok:
        raise ValueError(message)


def _sha(raw):
    return hashlib.sha256(raw).hexdigest()


def _result(rows, reasons):
    resolved = bool(rows) and not reasons
    groups = []
    if resolved:
        for row in rows:
            usage = row['usage']
            groups.append({
                'polygonOrdinal': row['partOrdinal'],
                'partOrdinals': [row['partOrdinal']],
                'role': 'exterior' if usage & 0x0c in (0, 8) else 'interior',
                'masked': usage & 0x30 == 0,
                'sourceIndexWord': row['indexWord'],
                'sourceTypedElementSHA256': row['suppliedElementSHA256'],
                'sourcePointCount': row['sourcePointCount'],
            })
    return {
        'format': FORMAT, 'model': MODEL,
        'status': 'RESOLVED_CLOSED_ROOT1_PARTS' if resolved else 'UNPROVEN_KEEP',
        'partToPolygon': list(range(len(rows))) if resolved else None,
        'polygon0PartOrdinals': [0] if resolved else None,
        'groups': groups, 'parts': rows, 'reasons': reasons,
        'modelEvidence': dict(MODEL_EVIDENCE),
        'nativeExecutionPerformed': False,
        'queryEquivalenceApproved': False,
        'materializationApproved': False,
        'scope': 'Closed Root1 part membership/order only; no global anchor/query/raster equivalence.',
    }


def classify_polygon_groups(parts, *, max_parts=DEFAULT_MAX_PARTS,
                            max_total_typed_bytes=DEFAULT_MAX_TOTAL_TYPED_BYTES) -> dict:
    """Resolve every part, or conservatively keep the whole owner unresolved.

    ``parts`` is a list/tuple of ``{'indexWord': u32, 'raw': bytes}``. Extra
    assertion fields are ignored. A successful result includes ALL occurrences,
    including holes, masks and aliased spans; first polygon membership is [0].
    Unsupported root/closure/count semantics return UNPROVEN_KEEP without any
    partial polygon mapping. Corrupt Root1 framing, types, or conflicting bytes
    for overlapping coordinate intervals raise ValueError. Explicit tool budgets
    return KEEP rather than claiming a chart format constraint.
    """
    _need(type(parts) in (list, tuple), 'EXPLICIT_ORDERED_PART_SEQUENCE_REQUIRED')
    _need(type(max_parts) is int and max_parts > 0, 'POSITIVE_PART_TOOL_BUDGET_REQUIRED')
    _need(type(max_total_typed_bytes) is int and max_total_typed_bytes > 0,
          'POSITIVE_TYPED_BYTE_TOOL_BUDGET_REQUIRED')
    if not parts:
        return _result([], [{'reason': 'NO_SOURCE_PARTS'}])
    if len(parts) > max_parts:
        return _result([], [{'reason': 'PART_TOOL_BUDGET_EXCEEDED_NOT_FORMAT_LIMIT'}])
    rows, intervals, reasons = [], [], []
    total_bytes = 0
    for ordinal, item in enumerate(parts):
        _need(type(item) is dict, 'PART_OBJECT_REQUIRED')
        word, raw = item.get('indexWord'), item.get('raw')
        _need(type(word) is int and 0 <= word <= 0xffffffff, 'UNSIGNED32_INDEX_WORD_REQUIRED')
        _need(type(raw) is bytes and len(raw) >= 4, 'IMMUTABLE_ELEMENT_WITH_WHOLE_HEADER_REQUIRED')
        total_bytes += len(raw)
        if total_bytes > max_total_typed_bytes:
            return _result([], [{'reason': 'TYPED_BYTE_TOOL_BUDGET_EXCEEDED_NOT_FORMAT_LIMIT'}])
        header, = struct.unpack_from('<I', raw)
        root, count = header >> 24, header & 0xffffff
        offset = word & 0xffffff
        row = {'partOrdinal': ordinal, 'indexWord': word, 'coordinateOffset': offset,
               'usage': word >> 24, 'rootFlag': root, 'sourcePointCount': count,
               'suppliedElementBytes': len(raw), 'suppliedElementSHA256': _sha(raw)}
        rows.append(row)
        intervals.append((offset, offset+len(raw), raw))
        if root != 1:
            # No Root0/U16 stride is inferred and no endpoints are decoded.
            reasons.append({'partOrdinal': ordinal, 'reason': 'UNSUPPORTED_ROOT_ENCODING'})
            continue
        _need(len(raw) == 4 + 8*count, 'WHOLE_EXACT_ROOT1_XY_ELEMENT_REQUIRED')
        if count < 4:
            reasons.append({'partOrdinal': ordinal, 'reason': 'OUTSIDE_CLOSED_RING_MINIMUM_SUBSET'})
            continue
        first = struct.unpack_from('<II', raw, 4)
        last = struct.unpack_from('<II', raw, len(raw)-8)
        row['closedSourceEndpointIdentity'] = first == last
        if first != last:
            reasons.append({'partOrdinal': ordinal, 'reason': 'OPEN_PART_GROUPING_UNPROVEN'})
    # All supplied occurrences must be compatible with one coordinate snapshot.
    # The furthest-reaching interval suffices: sorted starts and prior overlap
    # comparisons transitively cover older intervals, including contained views.
    cover_start = cover_end = 0
    cover_raw = b''
    for start, end, raw in sorted(intervals, key=lambda entry: (entry[0], entry[1])):
        if start < cover_end:
            overlap_end = min(end, cover_end)
            _need(raw[:overlap_end-start] == cover_raw[start-cover_start:overlap_end-cover_start],
                  'CONFLICTING_OVERLAPPING_COORDINATE_SNAPSHOT')
        if end > cover_end:
            cover_start, cover_end, cover_raw = start, end, raw
    return _result(rows, reasons)
