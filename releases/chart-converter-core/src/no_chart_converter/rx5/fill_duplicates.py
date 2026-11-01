"""Analyze adjacent XY stutters in pure AC Root1 area rings, without writes.

Only a source subsequence is proposed. No feature, index, render, additional,
coordinate arena or chart is returned for application. A future materializer
must separately close aliasing and native query/anchor consumer semantics.
Callback Streams must represent one stable caller-owned source snapshot.
Root1 headers establish U32 XY. Other headers are retained after their four-byte
header only: this API never assumes an eight-byte stride for Root0/U16 data.
"""
from __future__ import annotations

from bisect import bisect_left
import hashlib
import re
import struct

from shapely.geometry import Polygon

from .metadata import Stream, additional_frame, index_frame, render_frame
from .wire import FEATURE, need

FORMAT = 'no5-pure-ac-adjacent-duplicate-analysis/1'
POLICY = 'EXACT_ADJACENT_ROOT1_XY_SUBSEQUENCE_PRESERVE_FIRST_AND_CLOSURE/1'
_CONTEXTS = frozenset((196608, 196609))
_AREA_USAGES = frozenset((4, 5, 8, 9, 16, 17, 20, 21, 24, 25))
_AC = re.compile(rb'AC\([A-Z0-9]+(?:,[0-3])?\)')


def _sha(body):
    return hashlib.sha256(body).hexdigest()


def _stream(value):
    if type(value) is bytes:
        return Stream(len(value), lambda at, count: value[at:at+count]), _sha(value)
    need(isinstance(value, Stream), 'IMMUTABLE_BYTES_OR_BOUNDED_STREAM_REQUIRED')
    return value, None


def _pure_ac(frame):
    """Parse the already bounded full frame; reject opaque and unknown keys."""
    count, query = struct.unpack_from('<HI', frame)
    at, seen, contexts = 6, set(), []
    for _ in range(count):
        key, size = struct.unpack_from('<IH', frame, at)
        at += 6
        body = frame[at:at+size]
        at += size
        if key not in _CONTEXTS or key in seen:
            return False, query, 'UNKNOWN_OPAQUE_OR_DUPLICATE_CONTEXT'
        seen.add(key)
        clauses = body.split(b';')
        if clauses and clauses[-1] == b'':
            clauses.pop()  # Exactly one conventional trailing separator.
        if not clauses or any(_AC.fullmatch(clause) is None for clause in clauses):
            return False, query, 'CONTEXT_NOT_PURE_AC'
        contexts.append(key)
    if not contexts:
        return False, query, 'NO_DRAW_CONTEXT'
    if seen != _CONTEXTS:
        return False, query, 'BOTH_PAPER_AND_SIMPLE_AC_CONTEXTS_REQUIRED'
    return True, query, None


def _edges(points):
    return [(a, b) for a, b in zip(points, points[1:]) if a != b]


def _edge_sha(points):
    digest = hashlib.sha256()
    for (x, y), (u, v) in _edges(points):
        digest.update(struct.pack('<4I', x, y, u, v))
    return digest.hexdigest()


def _area_twice(points):
    return sum(x*v-u*y for (x, y), (u, v) in zip(points, points[1:]))


def _bbox(points):
    return [min(x for x, _ in points), min(y for _, y in points),
            max(x for x, _ in points), max(y for _, y in points)]


def _analyze_ring(raw, word, ordinal, offset):
    """Return an audit-only subsequence, or a named conservative KEEP reason."""
    header, = struct.unpack_from('<I', raw)
    count, root = header & 0xffffff, header >> 24
    row = {'partOrdinal': ordinal, 'coordinateOffset': offset,
           'indexWord': word, 'usage': word >> 24, 'rootFlag': root,
           'sourcePointCount': count, 'sourceTypedElementBytes': len(raw),
           'sourceTypedElementSHA256': _sha(raw)}
    if root != 1:
        return row, 'UNSUPPORTED_ROOT_BASIS'
    if word >> 24 not in _AREA_USAGES:
        return row, 'UNSUPPORTED_AREA_USAGE'
    points = list(struct.iter_unpack('<II', raw[4:]))
    if count < 4 or len(set(points[:-1])) < 3:
        return row, 'INSUFFICIENT_DISTINCT_CLOSED_RING_VERTICES'
    if points[0] != points[-1]:
        return row, 'OPEN_AREA_RING'
    polygon = Polygon(points)
    if polygon.is_empty or not polygon.is_valid or not _area_twice(points):
        return row, 'INVALID_OR_ZERO_AREA_SOURCE_RING'
    # Keep original first and final source ordinals. Collapse interior stutters,
    # including the interior part of a terminal run, without cyclic rotation.
    keep = [0]
    for i in range(1, count-1):
        if points[i] != points[keep[-1]]:
            keep.append(i)
    while len(keep) > 1 and points[keep[-1]] == points[-1]:
        keep.pop()
    keep.append(count-1)
    proposed = [points[i] for i in keep]
    need(len(proposed) >= 4 and len(set(proposed[:-1])) >= 3,
         'SUBSEQUENCE_VALID_MINIMUM_INVARIANT')
    need(keep[0] == 0 and keep[-1] == count-1 and proposed[0] == proposed[-1],
         'SOURCE_START_AND_FINAL_CLOSURE_INVARIANT')
    need(_edges(points) == _edges(proposed), 'EXACT_DIRECTED_NONZERO_EDGE_SEQUENCE')
    need(_bbox(points) == _bbox(proposed) and _area_twice(points) == _area_twice(proposed),
         'EXACT_INTEGER_BBOX_AND_SIGNED_AREA')
    proposed_raw = struct.pack('<I', (root << 24) | len(keep)) + b''.join(
        raw[4+8*i:12+8*i] for i in keep)
    selected = set(keep)
    row.update(proposedPointCount=len(keep), proposedTypedElementBytes=len(proposed_raw),
               proposedTypedElementSHA256=_sha(proposed_raw),
               keptVertexOrdinals=keep,
               removedVertexOrdinals=[i for i in range(count) if i not in selected],
               firstSourceVertexOrdinal=0, closureSourceVertexOrdinal=count-1,
               bboxRootU32=_bbox(points), signedAreaTwiceRootUnits=_area_twice(points),
               directedNonzeroEdgesSHA256=_edge_sha(points),
               proposedDirectedNonzeroEdgesSHA256=_edge_sha(proposed),
               roleUsageReversalUnchanged=True, allProposedXYAreExactSourceSubsequence=True)
    return row, None


def analyze_fill_duplicates(features: bytes, indices, coordinates, render, *, additional=None,
                            anchor_policy='analyze_all') -> dict:
    """Read only explicit buffers/ranges and return a JSON-serializable dry run.

    Unsupported kind/programme/root/ring semantics KEEP the entire owner.
    Malformed wire framing/ranges raise ValueError. The result never authorizes
    application, promises global native query identity, or reports measured CPU
    speed. Repeated span occurrences are counted separately, as native decoding
    visits occurrences; physical arena savings must not be inferred from them.
    """
    need(type(anchor_policy) is str and anchor_policy in ('analyze_all', 'preserve_native_polygon0'),
         'KNOWN_ANCHOR_ANALYSIS_POLICY_REQUIRED')
    need(type(features) is bytes and len(features) % FEATURE.size == 0,
         'IMMUTABLE_WHOLE_FEATURE32_RECORDS_REQUIRED')
    streams = {}
    pins = {}
    for name, value in (('indices', indices), ('coordinates', coordinates), ('render', render)):
        streams[name], whole_sha = _stream(value)
        pins[name] = {'bytes': streams[name].size, 'wholeBufferSHA256': whole_sha}
    if additional is not None:
        streams['additional'], whole_sha = _stream(additional)
        pins['additional'] = {'bytes': streams['additional'].size, 'wholeBufferSHA256': whole_sha}
    initial_reads = {name: len(stream.ranges) for name, stream in streams.items()}
    owners = []
    totals = dict(ownersTotal=len(features)//32, ownersGeometryAnalyzed=0,
                  ownersWithProposedChanges=0, ownersKept=0,
                  partContainersAnalyzed=0, partContainersRemoved=0,
                  pointOccurrencesAnalyzed=0, proposedPointOccurrencesRetained=0,
                  adjacentPointOccurrencesRemoved=0)
    spans = {}
    owner_typed_parts = {}
    modeled_owners = modeled_parts = modeled_before = modeled_after = 0
    for ordinal, fields in enumerate(FEATURE.iter_unpack(features)):
        record = features[32*ordinal:32*(ordinal+1)]
        row = {'recordOrdinal': ordinal, 'uid': str(fields[0]), 'kind': fields[6],
               'sourceRecord32Hex': record.hex(), 'sourceRecord32SHA256': _sha(record),
               'disposition': 'KEEP', 'parts': [], 'metadataChanged': False}
        owners.append(row)
        if fields[6] != 3:
            row['reason'] = 'NOT_KIND3_AREA'
            totals['ownersKept'] += 1
            continue
        frame, query = render_frame(streams['render'], fields[2])
        eligible, _, reason = _pure_ac(frame)
        row.update(sourceProgrammeFrameSHA256=_sha(frame), queryOffset=query)
        if not eligible:
            row['reason'] = reason
            totals['ownersKept'] += 1
            continue
        if additional is not None:
            ao = additional_frame(streams['additional'], query)
            row.update(sourceAdditionalFrameSHA256=_sha(ao), sourceAdditionalFrameBytes=len(ao))
        iframe = index_frame(streams['indices'], fields[1])
        row.update(sourceIndexOffset=fields[1], sourceIndexFrameSHA256=_sha(iframe))
        count, = struct.unpack_from('<I', iframe)
        if not count:
            row['reason'] = 'NO_AREA_PARTS'
            totals['ownersKept'] += 1
            continue
        reasons, original_count = [], 0
        typed_parts = []
        if anchor_policy == 'preserve_native_polygon0':
            owner_typed_parts[ordinal] = typed_parts
        for part_ordinal in range(count):
            word, = struct.unpack_from('<I', iframe, 4+4*part_ordinal)
            offset = word & 0xffffff
            header_raw = streams['coordinates'].read(offset, 4)
            header, = struct.unpack('<I', header_raw)
            if header >> 24 != 1:
                # Root0 can use a caller-specific U16 encoding. Do not infer
                # its body stride or misclassify a valid short span as corrupt.
                row['parts'].append({'partOrdinal': part_ordinal,
                    'coordinateOffset': offset, 'indexWord': word, 'usage': word >> 24,
                    'rootFlag': header >> 24, 'sourcePointCount': header & 0xffffff,
                    'sourceElementHeaderSHA256': _sha(header_raw),
                    'sourceTypedElementSHA256': None, 'sourceTypedElementBytes': None,
                    'coordinateEncodingUnresolved': True})
                original_count += header & 0xffffff
                reasons.append({'partOrdinal': part_ordinal,
                                'reason': 'UNSUPPORTED_ROOT_BASIS_COORDINATE_STRIDE_UNRESOLVED'})
                continue
            size = 4+8*(header & 0xffffff)
            raw = streams['coordinates'].read(offset, size)
            need(raw[:4] == header_raw, 'STABLE_COORDINATE_HEADER_SNAPSHOT_REQUIRED')
            part, reason = _analyze_ring(raw, word, part_ordinal, offset)
            if anchor_policy == 'preserve_native_polygon0':
                typed_parts.append({'indexWord': word, 'raw': raw})
            row['parts'].append(part)
            original_count += part['sourcePointCount']
            span_key = (offset, size)
            previous = spans.setdefault(span_key, {'sha256': _sha(raw), 'occurrences': 0})
            need(previous['sha256'] == _sha(raw), 'STABLE_REPEATED_TYPED_SPAN_REQUIRED')
            previous['occurrences'] += 1
            if reason:
                reasons.append({'partOrdinal': part_ordinal, 'reason': reason})
        totals['ownersGeometryAnalyzed'] += 1
        totals['partContainersAnalyzed'] += count
        totals['pointOccurrencesAnalyzed'] += original_count
        if reasons:
            row.update(reason='UNSUPPORTED_PART_WHOLE_OWNER_KEEP', unsupportedParts=reasons)
            # A rejected owner has no proposed removals, including its valid parts.
            for part in row['parts']:
                for key in list(part):
                    if key.startswith('proposed') or key in ('keptVertexOrdinals', 'removedVertexOrdinals'):
                        del part[key]
            row['pointOccurrencesBefore'] = row['pointOccurrencesAfter'] = original_count
            totals['proposedPointOccurrencesRetained'] += original_count
            totals['ownersKept'] += 1
            continue
        after = sum(part['proposedPointCount'] for part in row['parts'])
        modeled_owners += 1
        modeled_parts += count
        modeled_before += original_count
        modeled_after += after
        row.update(pointOccurrencesBefore=original_count, pointOccurrencesAfter=after,
                   adjacentPointOccurrencesRemoved=original_count-after)
        totals['proposedPointOccurrencesRetained'] += after
        totals['adjacentPointOccurrencesRemoved'] += original_count-after
        if original_count == after:
            row['reason'] = 'NO_ADJACENT_DUPLICATES'
            totals['ownersKept'] += 1
        else:
            row['disposition'] = 'PROPOSED_ADJACENT_DUPLICATE_SUBSEQUENCE'
            totals['ownersWithProposedChanges'] += 1
    for name, stream in streams.items():
        reads = stream.ranges[initial_reads[name]:]
        pins[name].update(rangeReads=[{'offset': at, 'bytes': size, 'sha256': sha}
                                      for at, size, sha in reads],
                          readCalls=len(reads), readBytes=sum(size for _, size, _ in reads))
    result = {'format': FORMAT, 'policy': POLICY,
            'status': 'ANALYSIS_ONLY_NOT_MATERIALIZATION_APPROVAL', 'analyzeOnly': True,
            'sourceFeatureBytes': len(features), 'sourceFeatureSHA256': _sha(features),
            'inputStreams': pins, 'owners': owners, 'totals': totals,
            'observedEligibleTypedSpans': [{'offset': at, 'bytes': size, **entry}
                                           for (at, size), entry in sorted(spans.items())],
            'partContainersChanged': False, 'coordinateOrIndexOrMetadataBuffersReturned': False,
            'nativeQueryEquivalenceApproved': False, 'nativeAnchorFallbackEquivalenceApproved': False,
            'nativeQueryDisposition': 'UNPROVEN',
            'originalCentroidPreservation': 'UNPROVEN_DUPLICATE_WEIGHTED_F32_MEAN',
            'operativeApplyAllowed': False,
            'wholeSourceAliasSafetyApproved': False, 'materializationApproved': False,
            'actualCountryDuplicateCountAvailable': False, 'originalNativeBinaryUsed': False,
            'nativeCPUInstructionsMeasured': False,
            'hypotheticalDecoderWork': {
                'scope': 'FULLY_VALIDATED_ELIGIBLE_NONEMPTY_XY_PARTS_ONLY',
                'basis': 'EXISTING_INTERNAL_GETTER_COUNT_FORMULA_NOT_A_NEW_NATIVE_RUN',
                'modeledOwners': modeled_owners,
                'unmodeledOwners': totals['ownersTotal']-modeled_owners,
                'unsafeEmptyOrExcludedOwnerCallsNotInferred': True,
                'partContainersBeforeAndAfter': modeled_parts,
                'getXCallsBefore': 2*modeled_before+2*modeled_parts,
                'getXCallsAfter': 2*modeled_after+2*modeled_parts,
                'getYCallsBefore': 2*modeled_before+2*modeled_parts,
                'getYCallsAfter': 2*modeled_after+2*modeled_parts,
                'measured': False},
            'sourceSnapshotContract': 'BYTES_IMMUTABLE_CALLBACK_STREAM_SNAPSHOT_IS_CALLER_OWNED',
            'limits': ['Geometric directed-edge/BBox/area invariance is not a complete native query proof.',
                       'Only adjacent exact source XY values are considered; no collinear simplification.',
                       'Known explicit anchor keys and every non-AC context are excluded.',
                       'An unsaved polygon-vertex-mean query fallback is not proved invariant.',
                       'No chart or modified geometry buffer is materialized.']}

    if anchor_policy == 'preserve_native_polygon0':
        return _preserve_polygon0(result, owner_typed_parts)
    return result


def _keep_source_part(part, classification):
    """Reset an analysis proposal to the complete original source element."""
    blocked = list(part['removedVertexOrdinals'])
    part.update(anchorClassification=classification,
                blockedDuplicateVertexOrdinals=blocked,
                keptVertexOrdinals=list(range(part['sourcePointCount'])),
                removedVertexOrdinals=[],
                proposedPointCount=part['sourcePointCount'],
                proposedTypedElementBytes=part['sourceTypedElementBytes'],
                proposedTypedElementSHA256=part['sourceTypedElementSHA256'])
    return len(blocked)


def _preserve_polygon0(result, owner_typed_parts):
    """Bind real closed-part grouping and retain exact original polygon0 inputs.

    This is an analysis refinement, not a native query admission. Polygon0 is
    determined by the shared source-byte classifier, never a role/receipt hint.
    Every source interval overlapping a known polygon0 span is also protected;
    original arenas must never be edited in place based on this proposal.
    """
    from .area_polygon_groups import classify_polygon_groups

    resolved = {}
    protected = []
    for owner in result['owners']:
        ordinal = owner['recordOrdinal']
        if ordinal not in owner_typed_parts or owner.get('unsupportedParts'):
            owner.update(polygonMappingStatus='NOT_RESOLVED_WHOLE_OWNER_UNCHANGED',
                         anchorInputDisposition='WHOLE_OWNER_KEEP')
            continue
        mapping = classify_polygon_groups(owner_typed_parts[ordinal])
        owner['polygonMapping'] = mapping
        owner['polygonMappingStatus'] = mapping['status']
        if mapping['status'] != 'RESOLVED_CLOSED_ROOT1_PARTS':
            for part in owner['parts']:
                _keep_source_part(part, 'UNRESOLVED_POLYGON_ORDER_WHOLE_OWNER_KEEP')
            owner.update(disposition='KEEP', reason='UNRESOLVED_NATIVE_POLYGON_MAPPING',
                         anchorInputDisposition='WHOLE_OWNER_KEEP',
                         pointOccurrencesAfter=owner['pointOccurrencesBefore'],
                         adjacentPointOccurrencesRemoved=0)
            continue
        members = mapping['polygon0PartOrdinals']
        # This public mode supports exactly the fully closed Root1 subset.
        # A future broader classifier must not silently widen this contract.
        need(mapping['partToPolygon'] == list(range(len(owner['parts'])))
             and members == [0], 'CLOSED_ROOT1_SINGLE_PART_POLYGON_MAPPING')
        resolved[ordinal] = mapping
        owner.update(anchorInputDisposition='ORIGINAL_POLYGON0_INPUT_ORDER_FLAGS_RETAINED',
                     polygon0PartOrdinals=members,
                     protectedPolygon0Inputs=[{k: owner['parts'][i][k] for k in
                         ('partOrdinal', 'coordinateOffset', 'indexWord', 'usage',
                          'rootFlag', 'sourceTypedElementBytes', 'sourceTypedElementSHA256')}
                         for i in members])
        for i in members:
            part = owner['parts'][i]
            protected.append((part['coordinateOffset'],
                              part['coordinateOffset']+part['sourceTypedElementBytes']))
    # All resolved eligible owners share this call's coordinate arena. Preserve
    # cross-owner aliases too, without an O(parts * owners) overlap search.
    intervals = []
    for start, end in sorted(set(protected)):
        if intervals and start <= intervals[-1][1]:
            intervals[-1] = (intervals[-1][0], max(end, intervals[-1][1]))
        else:
            intervals.append((start, end))
    starts = [start for start, _ in intervals]
    protected_points = blocked_first = blocked_alias = later_points = 0
    modeled_owners = modeled_parts = modeled_before = modeled_after = 0
    for owner in result['owners']:
        ordinal = owner['recordOrdinal']
        if ordinal not in resolved:
            owner['queryAdmission'] = 'UNPROVEN'
            continue
        mapping = resolved[ordinal]
        for i, part in enumerate(owner['parts']):
            part['decodedPolygonOrdinal'] = mapping['partToPolygon'][i]
            start, size = part['coordinateOffset'], part['sourceTypedElementBytes']
            candidate = bisect_left(starts, start+size)-1
            overlaps = candidate >= 0 and intervals[candidate][1] > start
            if i in mapping['polygon0PartOrdinals']:
                blocked_first += _keep_source_part(part, 'PRESERVED_DECODED_POLYGON0')
                protected_points += part['sourcePointCount']
            elif overlaps:
                blocked_alias += _keep_source_part(part, 'PROTECTED_POLYGON0_SPAN_ALIAS')
            else:
                part['anchorClassification'] = 'LATER_DECODED_POLYGON_CANDIDATE'
                later_points += len(part['removedVertexOrdinals'])
            # A mask does not imply full query parity: containsPoint still
            # visits polygons and original zero-edge behavior is not closed.
            part['queryObligation'] = ('MASK_CONTAINMENT_UNPROVEN'
                if (part['usage'] & 0x30) == 0 else 'VISIBLE_NULL_SEGMENT_QUERY_UNPROVEN')
        before = owner['pointOccurrencesBefore']
        after = sum(part['proposedPointCount'] for part in owner['parts'])
        owner.update(pointOccurrencesAfter=after, adjacentPointOccurrencesRemoved=before-after,
                     queryAdmission='UNPROVEN',
                     drawEdgeDisposition='EXACT_ORDERED_DIRECTED_NONZERO_EDGES')
        if before == after:
            owner.update(disposition='KEEP', reason='NO_UNPROTECTED_LATER_POLYGON_DUPLICATES')
        else:
            owner.update(disposition='PROPOSED_ADJACENT_DUPLICATE_SUBSEQUENCE')
            owner.pop('reason', None)
        modeled_owners += 1
        modeled_parts += len(owner['parts'])
        modeled_before += before
        modeled_after += after
    totals = result['totals']
    totals['ownersWithProposedChanges'] = sum(
        owner['disposition'] == 'PROPOSED_ADJACENT_DUPLICATE_SUBSEQUENCE'
        for owner in result['owners'])
    totals['ownersKept'] = totals['ownersTotal']-totals['ownersWithProposedChanges']
    totals['proposedPointOccurrencesRetained'] = sum(
        owner.get('pointOccurrencesAfter', 0) for owner in result['owners'])
    totals['adjacentPointOccurrencesRemoved'] = sum(
        owner.get('adjacentPointOccurrencesRemoved', 0) for owner in result['owners'])
    need(totals['adjacentPointOccurrencesRemoved'] == later_points,
         'ONLY_UNPROTECTED_LATER_POLYGON_SAVINGS')
    result.update(anchorPolicy='preserve_native_polygon0',
        originalCentroidPreservation='ORIGINAL_POLYGON0_INPUT_ORDER_FLAGS_RETAINED_ONLY',
        nativeAnchorFallbackEquivalenceApproved=False,
        nativeQueryDisposition='UNPROVEN', operativeApplyAllowed=False,
        anchorCandidateTotals={
            'resolvedOwnerMappings': len(resolved),
            'knownProtectedPolygon0SpanCount': len(protected),
            'polygon0PointOccurrencesRetained': protected_points,
            'polygon0DuplicatePointOccurrencesBlocked': blocked_first,
            'aliasDuplicatePointOccurrencesBlocked': blocked_alias,
            'laterPolygonDuplicatePointOccurrencesProposed': later_points},
        protectedAliasScope='ALL_RESOLVED_ELIGIBLE_OWNERS_IN_THIS_ANALYSIS',
        hypotheticalDecoderWork={
            'scope': 'FULLY_VALIDATED_CLOSED_ROOT1_MAPPED_NONEMPTY_XY_PARTS_ONLY',
            'basis': 'EXISTING_INTERNAL_GETTER_COUNT_FORMULA_NOT_A_NEW_NATIVE_RUN',
            'modeledOwners': modeled_owners,
            'unmodeledOwners': totals['ownersTotal']-modeled_owners,
            'unsafeEmptyOrExcludedOwnerCallsNotInferred': True,
            'partContainersBeforeAndAfter': modeled_parts,
            'getXCallsBefore': 2*modeled_before+2*modeled_parts,
            'getXCallsAfter': 2*modeled_after+2*modeled_parts,
            'getYCallsBefore': 2*modeled_before+2*modeled_parts,
            'getYCallsAfter': 2*modeled_after+2*modeled_parts,
            'measured': False})
    result['limits'].extend([
        'Only original polygon0 source input/order/flags are retained; no generic all-anchor parity is asserted.',
        'Unknown source consumers and native zero-segment query behavior remain unproven.',
        'Known polygon0 span aliases are kept; unseen aliases and any chart materialization require separate review.'])
    return result
