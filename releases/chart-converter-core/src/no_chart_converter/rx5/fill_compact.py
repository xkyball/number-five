"""Opt-in Source32 whole-part compaction with original consumer inputs retained.

This pure-buffer transform does not write or approve a chart. Only complete
pure-AC Root1 parts with the existing signed15..29 exclusion certificate can
be omitted. Polygon0, the final query-visible polygon and all source-interval
aliases of either remain byte exact. Global containment outside Source32 is
deliberately not promised. A chart-wide publisher must retain that domain.
"""
from __future__ import annotations

from bisect import bisect_left
import hashlib
import struct

from . import arena, locality_empty, locality_parts
from .area_polygon_groups import classify_polygon_groups
from .fill_duplicates import _pure_ac
from .metadata import Stream, additional_frame, index_frame, render_frame
from .model import parts
from .wire import FEATURE, need

FORMAT = 'no5-consumer-protected-source32-fill-compaction/1'
SCOPE = 'SOURCE32_DESCENDANTS_15_TO_29'
_KINDS = frozenset((1, 2, 3, 4, 9))
_USAGES = frozenset((8, 9, 16, 17, 20, 21, 24, 25))


def _sha(raw):
    return hashlib.sha256(raw).hexdigest()


def _tile(tile):
    need(type(tile) in (tuple, list) and len(tile) == 3
         and all(type(v) is int for v in tile) and tile[0] == 15
         and 0 <= tile[1] < 1 << 15 and 0 <= tile[2] < 1 << 15,
         'EXPLICIT_L15_SOURCE_TILE_REQUIRED')
    return tuple(tile)


def require_source32_request(tile, destination, *, query_root=None):
    """Refuse a consumer request outside the computed preparation domain.

    The caller must bind ``tile`` to the real selected source. No result/receipt
    boolean can widen this domain. Queries are exact Root-U32 XY, not metres or
    a projected float. This guard performs no native call or chart selection.
    """
    level, x, y = _tile(tile)
    need(type(destination) in (tuple, list) and len(destination) == 3
         and all(type(v) is int for v in destination), 'INTEGER_DESTINATION_TILE')
    z, dx, dy = destination
    need(level <= z <= 29 and 0 <= dx < 1 << z and 0 <= dy < 1 << z
         and dx >> (z-level) == x and dy >> (z-level) == y,
         'REQUEST_OUTSIDE_CERTIFIED_SOURCE_DESCENDANTS')
    if query_root is not None:
        need(type(query_root) in (tuple, list) and len(query_root) == 2
             and all(type(v) is int and 0 <= v < 1 << 32 for v in query_root),
             'ROOT_U32_QUERY_POINT_REQUIRED')
        d = locality_empty.Domain.source((level, x, y), 32)
        qx, qy = (v*d.denominator for v in query_root)
        need(d.xmin <= qx <= d.xmax and d.ymin <= qy <= d.ymax,
             'QUERY_OUTSIDE_CERTIFIED_SOURCE32')


def _intervals(spans):
    result = []
    for start, end in sorted(set(spans)):
        if result and start <= result[-1][1]:
            result[-1] = (result[-1][0], max(result[-1][1], end))
        else:
            result.append((start, end))
    return result


def compact_source_fill(features, indices, coordinates, render, additional, *, tile, scope):
    """Return five buffers and a domain-bound receipt, with no files or approval.

    ``scope`` must explicitly equal SCOPE. All feature ordinals, UIDs, metadata,
    sounding roles, flags, retained part order and complete typed elements stay
    exact. Unknown coordinate encoding keeps the whole source byte identical;
    unsupported area grouping/programmes keep that owner. A no-op returns all
    five original bytes, without incidental arena/UUID changes. Root0 stride is
    never guessed. Malformed known wire framing raises ValueError.
    """
    need(type(scope) is str and scope == SCOPE, 'EXPLICIT_SOURCE32_SCOPE_REQUIRED')
    tile = _tile(tile)
    buffers = dict(featureBytes=features, indexBytes=indices, coordinateBytes=coordinates,
                   renderBytes=render, additionalBytes=additional)
    need(all(type(v) is bytes for v in buffers.values()), 'FIVE_IMMUTABLE_SOURCE_BUFFERS')
    need(len(features) % 32 == 0, 'WHOLE_FEATURE32_RECORDS_REQUIRED')
    receipt = {
        'format': FORMAT, 'scope': scope, 'sourceTile': list(tile),
        'sourcePins': {k: {'bytes': len(v), 'sha256': _sha(v)} for k, v in buffers.items()},
        'domain': {'source32': locality_empty.Domain.source(tile, 32).serial(),
                   'destinationLevels': [15, 29], 'sourceSelectionMustBeBoundByCaller': True},
        'owners': [], 'changedOwnerCount': 0, 'removedPartCount': 0,
        'removedXYOccurrences': 0, 'wholeOwnersRemoved': 0,
        'originalNativeExecution': False, 'hardwareTimeClaim': False,
        'globalQueryEqualityClaim': False, 'globalChartApplyAllowed': False,
        'queryContract': 'CONTAINMENT_AND_FILL_WITHIN_SOURCE32; FINAL_VISIBLE_DISTANCE_INPUT_EXACT',
        'anchorContract': 'COMPLETE_ORIGINAL_POLYGON0_ORDER_USAGE_AND_TYPED_BYTES_EXACT',
        'nativeSourceSelectionVerified': False,
    }

    def finish(disposition, output=None, reason=None):
        out = buffers if output is None else output
        changed = any(out[k] != buffers[k] for k in buffers)
        receipt.update(disposition=disposition, changed=changed,
                       outputPins={k: {'bytes': len(v), 'sha256': _sha(v)} for k, v in out.items()})
        if reason:
            receipt['reason'] = reason
        return dict(out, changed=changed, disposition=disposition, receipt=receipt)

    records = list(FEATURE.iter_unpack(features))
    if any(f[6] not in _KINDS for f in records):
        return finish('KEEP_SOURCE', reason='UNSUPPORTED_GEOMETRY_KIND')
    ii, rr, aa = (Stream(len(b), lambda at, n, b=b: b[at:at+n])
                  for b in (indices, render, additional))
    source_parts, frames, eligible, mappings = [], [], [], {}
    protected, protected_inputs = [], []
    for ordinal, f in enumerate(records):
        iframe = index_frame(ii, f[1])
        # Read only the header before deciding whether a body stride is known.
        for word, in struct.iter_unpack('<I', iframe[4:]):
            at = word & 0xffffff
            need(at <= len(coordinates)-4, 'COMPLETE_COORDINATE_HEADER')
            header, = struct.unpack_from('<I', coordinates, at)
            if header >> 24 != 1:
                return finish('KEEP_SOURCE', reason='UNSUPPORTED_ROOT_ENCODING_NO_STRIDE_GUESS')
        ps = list(parts(features[32*ordinal:32*(ordinal+1)], indices, coordinates))
        source_parts.append(ps)
        frame, query = render_frame(rr, f[2])
        additional_frame(aa, query)
        frames.append(frame)
        owner = {'recordOrdinal': ordinal, 'uid': str(f[0]), 'kind': f[6],
                 'sourceRecord32Hex': features[32*ordinal:32*(ordinal+1)].hex(),
                 'sourcePartCount': len(ps), 'keptOriginalPartOrdinals': list(range(len(ps))),
                 'removedOriginalPartOrdinals': [], 'disposition': 'KEEP'}
        receipt['owners'].append(owner)
        if f[6] != 3:
            continue
        mapping = classify_polygon_groups([{'indexWord': p['word'], 'raw': p['raw']} for p in ps])
        owner['polygonMappingStatus'] = mapping['status']
        if mapping['status'] != 'RESOLVED_CLOSED_ROOT1_PARTS':
            continue
        visible = [i for i, p in enumerate(ps) if p['usage'] & 0x30]
        members = sorted({0} | ({visible[-1]} if visible else set()))
        mappings[ordinal] = members
        owner.update(polygon0PartOrdinal=0, finalQueryVisiblePartOrdinal=visible[-1] if visible else None,
                     protectedConsumerPartOrdinals=members)
        for i in members:
            p = ps[i]
            protected.append((p['coordinateOffset'], p['coordinateOffset']+len(p['raw'])))
            protected_inputs.append({'recordOrdinal': ordinal, 'partOrdinal': i,
                'indexWord': p['word'], 'sourceTypedElementSHA256': _sha(p['raw'])})
        pure, _, _ = _pure_ac(frame)
        if pure and all(p['usage'] in _USAGES for p in ps):
            eligible.append(ordinal)
    merged = _intervals(protected)
    starts = [a for a, _ in merged]
    receipt.update(protectedConsumerInputs=protected_inputs,
                   protectedCoordinateIntervals=[list(x) for x in merged],
                   protectedAliasScope='ALL_RESOLVED_ROOT1_AREA_OWNERS_IN_THIS_SOURCE')
    selected_features = b''.join(features[32*i:32*(i+1)] for i in eligible)
    # Immutable existing certificate kernels; no less strict projection/domain.
    partial = locality_parts.local_whole_parts(selected_features, indices, coordinates, render,
                                              tile, guard_pixels=32, max_level=29)
    partial_rows = {eligible[r['recordOrdinal']]: r for r in partial['receipts']}
    new_features, new_index = bytearray(features), bytearray(indices)
    for ordinal in eligible:
        ps, owner = source_parts[ordinal], receipt['owners'][ordinal]
        contexts, _, _ = locality_parts.programme_frame(render, records[ordinal][2])
        whole = locality_empty.certify(3, [body for _, body in contexts], ps, tile, guard_pixels=64)
        if whole is not None:
            candidates = set(range(len(ps)))
            certificate = {'kernel': 'wholeOwner64_AS_PART_EXCLUSION_ONLY', 'certificate': whole}
        elif ordinal in partial_rows:
            candidates = {p['ordinal'] for p in partial_rows[ordinal]['removedParts']}
            certificate = {'kernel': 'wholePart32', 'certificate': partial_rows[ordinal]}
        else:
            continue
        remove, blocked = [], []
        for i in sorted(candidates):
            p = ps[i]
            at, end = p['coordinateOffset'], p['coordinateOffset']+len(p['raw'])
            position = bisect_left(starts, end)-1
            if i in mappings[ordinal] or position >= 0 and merged[position][1] > at:
                blocked.append(i)
            else:
                remove.append(i)
        owner['protectedCandidatePartOrdinals'] = blocked
        if not remove:
            continue
        keep = [i for i in range(len(ps)) if i not in set(remove)]
        need(keep and all(i in keep for i in mappings[ordinal]), 'WHOLE_CONSUMER_POLYGONS_RETAINED')
        at = len(new_index)
        need(at < 1 << 32, 'INDEX_FRAME_OFFSET_U32')
        new_index += struct.pack('<I', len(keep)) + b''.join(struct.pack('<I', ps[i]['word']) for i in keep)
        struct.pack_into('<I', new_features, 32*ordinal+8, at)
        owner.update(disposition='OMITTED_CERTIFIED_WHOLE_PARTS_ONLY',
                     keptOriginalPartOrdinals=keep, removedOriginalPartOrdinals=remove,
                     exclusion=certificate,
                     removedParts=[{'partOrdinal': i, 'usage': ps[i]['usage'],
                         'sourceTypedElementSHA256': _sha(ps[i]['raw']),
                         'pointCount': ps[i]['pointCount']} for i in remove])
        receipt['changedOwnerCount'] += 1
        receipt['removedPartCount'] += len(remove)
        receipt['removedXYOccurrences'] += sum(ps[i]['pointCount'] for i in remove)
    if not receipt['removedPartCount']:
        return finish('UNCHANGED')
    compact = arena.compact_arena(bytes(new_index), coordinates, bytes(new_features))
    if compact['disposition'] == 'PRESERVED_CONFLICT':
        receipt.update(changedOwnerCount=0, removedPartCount=0, removedXYOccurrences=0)
        return finish('KEEP_SOURCE', reason='CONTRADICTORY_INDEX_ALIAS_REMAP')
    output = dict(buffers, featureBytes=compact['featureBytes'], indexBytes=compact['indexBytes'],
                  coordinateBytes=compact['coordinateBytes'])
    old_xy = old_xyz = new_xy = new_xyz = old_count = new_count = 0
    for ordinal, (f, owner, before) in enumerate(zip(records, receipt['owners'], source_parts)):
        record = output['featureBytes'][32*ordinal:32*(ordinal+1)]
        original = features[32*ordinal:32*(ordinal+1)]
        need(record[:8] == original[:8] and record[12:] == original[12:], 'ONLY_FEATURE_INDEX_OFFSET_CHANGED')
        after = list(parts(record, output['indexBytes'], output['coordinateBytes']))
        keep = owner['keptOriginalPartOrdinals']
        need(len(after) == len(keep), 'EXACT_RETAINED_PART_COUNT')
        for p, i in zip(after, keep):
            need(p['raw'] == before[i]['raw'] and p['usage'] == before[i]['usage'],
                 'EXACT_RETAINED_TYPED_ELEMENTS_USAGE_AND_ORDER')
        for i in mappings.get(ordinal, []):
            need(i in keep and after[keep.index(i)]['raw'] == before[i]['raw'],
                 'ORIGINAL_ANCHOR_AND_FINAL_QUERY_POLYGON_INPUT_EXACT')
        b, a = sum(p['pointCount'] for p in before), sum(p['pointCount'] for p in after)
        if f[6] in (4, 9):
            old_xyz += b
            new_xyz += a
        else:
            old_xy += b
            new_xy += a
        old_count += len(before)
        new_count += len(after)
    need(old_xy-new_xy == receipt['removedXYOccurrences'] and old_xyz == new_xyz,
         'EXACT_DECLARED_XY_REDUCTION_AND_UNCHANGED_SIGNED_XYZ')
    receipt['declaredWork'] = {'recordsBeforeAndAfter': len(records),
        'partContainersBefore': old_count, 'partContainersAfter': new_count,
        'XYBefore': old_xy, 'XYAfter': new_xy, 'XYZBeforeAndAfter': old_xyz,
        'originalInstructionVisitsMeasured': False}
    receipt['arenaAliasIdentity'] = compact['aliasIdentity']
    return finish('COMPACTED_SOURCE32_PARTS', output)
