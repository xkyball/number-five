"""Own opt-in validator for a finite physical RX5 template DAG.

No chart encoder, file I/O, native calls or legacy-validator changes. The reader
callback must refer to one externally bound stable file. This validates only the
declared graph/resources and supplied reserved intervals, not an unknown file.
"""
from __future__ import annotations

from dataclasses import dataclass
import hashlib
import re
import struct

from .header import validate_plain_vector_header

POLICY = 'EXPLICIT_SOURCE20_THROUGH28_CANONICAL29_TEMPLATE_DAG_V1'
ROLES = ('indices', 'coordinates', 'render', 'additional')
DISK_IDS = dict(zip(ROLES, range(0x1101, 0x1105)))
MAX_NODES = 256
MAX_STREAMS = 32
MAX_NODE_BYTES = 1 << 20
MAX_READ_BYTES = 16 << 20


def _need(value, reason):
    if not value:
        raise ValueError('RX5_LAYERED_DAG_' + reason)


def _int(value, lo, hi):
    return type(value) is int and lo <= value <= hi


def _hash(value):
    return type(value) is str and re.fullmatch('[0-9a-f]{64}', value) is not None


@dataclass(frozen=True)
class PayloadPin:
    bytes: int
    sha256: str


@dataclass(frozen=True)
class NodeSpec:
    offset: int
    level: int
    role: str  # profile-vector, router, canonical-restore
    sha256: str  # Complete node:16-byte header, prefixes and FI bytes.


@dataclass(frozen=True)
class StreamSpec:
    offset: int
    role: str
    payload: PayloadPin


def derive_child_tile(parent_tile, slot):
    _need(type(parent_tile) in (tuple, list) and len(parent_tile) == 3,
          'PARENT_TILE_TRIPLE')
    z, x, y = parent_tile
    _need(_int(z, 0, 28) and _int(x, 0, (1 << z)-1)
          and _int(y, 0, (1 << z)-1) and _int(slot, 0, 3), 'PARENT_TILE_OR_SLOT_BOUNDS')
    return (z+1, 2*x+(slot & 1), 2*y+(slot >> 1))


def validate_layered_template_dag(read_at, *, source_header, expected_header_sha256,
        source_file_sha256, file_bytes, source_root, root_offset, nodes, streams,
        profile_fi, canonical_restore_fi, profile_stream_offsets,
        canonical_restore_stream_offsets, profile_receipt_sha256,
        allow_ancestor, enabled=False, policy=None, reserved_ranges=()):
    """Validate source20..28/full29, visiting each physical template only once.

    File SHA/prepost, whole-file block inventory, corrected Root-domain evidence,
    actual native source IDs/allowAncestor caller and retirement remain external
    admission obligations. Neither supplied SHA nor bool is observed natively.
    Canonical is relative to the explicitly supplied full input profile, not a
    claim that an entire OEM chart has been emitted.
    """
    _need(enabled is True and policy == POLICY, 'EXPLICIT_DEFAULT_OFF_POLICY')
    _need(callable(read_at), 'READER_CALLBACK')
    _need(_hash(source_file_sha256) and _hash(profile_receipt_sha256), 'EXPLICIT_EXTERNAL_PINS')
    _need(type(source_header) is bytes and _hash(expected_header_sha256)
          and hashlib.sha256(source_header).hexdigest() == expected_header_sha256,
          'HEADER_PIN')
    header_contract = validate_plain_vector_header(source_header, file_bytes=file_bytes)
    _need(type(source_root) in (tuple, list) and len(source_root) == 3,
          'SOURCE_ROOT_TRIPLE')
    z, x, y = source_root
    _need(z == 20 and _int(z,20,20) and _int(x,0,(1 << z)-1)
          and _int(y,0,(1 << z)-1), 'EXACT_SOURCE20_ROOT')
    _need(_int(root_offset,240,file_bytes), 'ROOT_OFFSET_U64')
    _need(type(nodes) in (tuple, list) and 1 <= len(nodes) <= MAX_NODES,
          'PHYSICAL_NODE_COUNT')
    _need(type(streams) in (tuple, list) and 4 <= len(streams) <= MAX_STREAMS,
          'PHYSICAL_STREAM_COUNT')
    _need(type(allow_ancestor) is bool, 'EXPLICIT_ALLOW_ANCESTOR_BOOL')
    for fi in (profile_fi, canonical_restore_fi):
        _need(type(fi) is PayloadPin and _int(fi.bytes,32,MAX_NODE_BYTES-80)
              and fi.bytes % 32 == 0 and _hash(fi.sha256), 'WHOLE_FI32_PIN')
    node_map, stream_map = {}, {}
    for n in nodes:
        _need(type(n) is NodeSpec and _int(n.offset,240,(1 << 64)-1)
              and _int(n.level,20,29) and _hash(n.sha256), 'NODE_SPEC')
        _need(n.offset not in node_map, 'UNIQUE_PHYSICAL_NODE_OFFSET_SPEC')
        _need(n.role in ('profile-vector','router','canonical-restore'), 'NODE_ROLE')
        _need((n.role == 'canonical-restore') == (n.level == 29), 'RESTORE_ROLE_LEVEL29')
        _need(n.role != 'router' or (21 <= n.level <= 28 and allow_ancestor is True),
              'ROUTER_REQUIRES_EXPLICIT_TRUE_ANCESTOR')
        node_map[n.offset] = n
    _need(root_offset in node_map and node_map[root_offset].level == 20
          and node_map[root_offset].role == 'profile-vector', 'ROOT_PROFILE_VECTOR')
    for s in streams:
        _need(type(s) is StreamSpec and _int(s.offset,240,(1 << 64)-1)
              and s.role in ROLES and type(s.payload) is PayloadPin
              and _int(s.payload.bytes,0,MAX_READ_BYTES-16) and _hash(s.payload.sha256),
              'STREAM_SPEC')
        _need(s.offset not in stream_map and s.offset not in node_map,
              'UNIQUE_PHYSICAL_STREAM_OFFSET_SPEC')
        stream_map[s.offset] = s
    for refs in (profile_stream_offsets, canonical_restore_stream_offsets):
        _need(type(refs) in (tuple,list) and len(refs) == 4, 'FOUR_STREAM_POINTERS')
        for role, off in zip(ROLES,refs):
            _need(type(off) is int and off in stream_map and stream_map[off].role == role,
                  'BOUND_STREAM_ROLE_POINTER')
    read_bytes = 0
    def read(offset, count):
        nonlocal read_bytes
        _need(_int(offset,240,file_bytes) and _int(count,0,MAX_READ_BYTES)
              and count <= file_bytes-offset, 'BLOCK_EXTENT')
        _need(read_bytes+count <= MAX_READ_BYTES, 'READ_BUDGET')
        value = read_at(offset,count)
        _need(type(value) is bytes and len(value) == count, 'EXACT_IMMUTABLE_READER_RETURN')
        read_bytes += count
        return value
    parsed, intervals = {}, [(0,240,'header')]
    for off, n in node_map.items():
        head = read(off,16)
        bid,flags,size,nxt = struct.unpack('<HHIQ',head)
        wanted = 1 if n.role == 'router' else 3
        _need(bid == 0x6000+n.level and flags == wanted and nxt == 0,
              'NODE_HEADER_ROLE_LEVEL_FLAGS_NEXT')
        _need((n.role == 'router' and size == 32)
              or (n.role != 'router' and 64+32 <= size <= MAX_NODE_BYTES-16
                  and (size-64) % 32 == 0), 'U32_NODE_PAYLOAD_LAYOUT')
        body = read(off+16,size)
        _need(hashlib.sha256(head+body).hexdigest() == n.sha256, 'NODE_BYTES_PIN')
        children = struct.unpack_from('<4Q',body)
        if n.role == 'canonical-restore':
            _need(children == (0,0,0,0), 'RESTORE_CHILDREN_ZERO')
        else:
            _need(all(children), 'FOUR_CHILDREN_TO_PHYSICAL_BOUNDARY')
        refs = None
        if n.role != 'router':
            refs = struct.unpack_from('<4Q',body,32)
            fi = body[64:]
            fp = canonical_restore_fi if n.role == 'canonical-restore' else profile_fi
            _need(len(fi) == fp.bytes and hashlib.sha256(fi).hexdigest() == fp.sha256,
                  'CANONICAL_RESTORE_FI_PIN' if n.role == 'canonical-restore' else 'PROFILE_FI_PIN')
            expected = canonical_restore_stream_offsets if n.role == 'canonical-restore' else profile_stream_offsets
            _need(refs == tuple(expected), 'CANONICAL_RESTORE_STREAM_POINTERS'
                  if n.role == 'canonical-restore' else 'PROFILE_STREAM_POINTERS')
        parsed[off] = (children,refs)
        intervals.append((off,off+16+size,'node'))
    for off,s in stream_map.items():
        head=read(off,16)
        bid,flags,size,nxt=struct.unpack('<HHIQ',head)
        _need((bid,flags,size,nxt) == (DISK_IDS[s.role],0,s.payload.bytes,0),
              'STREAM_ROLE_HEADER_U32_SIZE_NEXT')
        data=read(off+16,size)
        _need(hashlib.sha256(data).hexdigest() == s.payload.sha256, 'STREAM_PAYLOAD_PIN')
        intervals.append((off,off+16+size,'stream'))
    _need(type(reserved_ranges) in (tuple,list) and len(reserved_ranges) <= 100000,
          'BOUNDED_RESERVED_INTERVALS')
    for item in reserved_ranges:
        _need(type(item) in (tuple,list) and len(item) == 2
              and _int(item[0],240,file_bytes) and _int(item[1],item[0]+1,file_bytes),
              'RESERVED_INTERVAL_EXTENT')
        intervals.append((item[0],item[1],'external-reserved'))
    intervals.sort()
    _need(all(a[1] <= b[0] for a,b in zip(intervals,intervals[1:])),
          'OVERLAPPING_DECLARED_PHYSICAL_EXTENTS')
    # One offset has exactly one declared level. Every edge increases it by1.
    # This rejects cycles and sameoffset-at-differentlevels without expanding
    # the4**9 logical instances represented by the finite physical DAG.
    visited,first_tile = set(),{root_offset:tuple(source_root)}
    pending=[root_offset]
    used_streams=set()
    while pending:
        off=pending.pop()
        if off in visited:
            continue
        visited.add(off)
        n=node_map[off]
        children,refs=parsed[off]
        if refs:
            used_streams.update(refs)
        for slot,child in enumerate(children):
            if not child:
                continue
            _need(child in node_map, 'CHILD_REFERENCE_SPEC_MISSING')
            _need(node_map[child].level == n.level+1, 'CHILD_LEVEL_PLUS_ONE_NO_CYCLES')
            logical_child=derive_child_tile(first_tile[off],slot)
            _need(logical_child[0] == node_map[child].level, 'LOGICAL_CHILD_LEVEL')
            first_tile.setdefault(child,logical_child)
            pending.append(child)
    _need(visited == set(node_map), 'UNREACHABLE_NODE_SPEC')
    _need(used_streams == set(stream_map), 'UNREFERENCED_STREAM_SPEC')
    occurrences={off:0 for off in node_map};occurrences[root_offset]=1
    for off,n in sorted(node_map.items(),key=lambda item:item[1].level):
        for child in parsed[off][0]:
            if child:
                occurrences[child] += occurrences[off]
    levels={level:sum(occurrences[off] for off,n in node_map.items() if n.level == level)
            for level in range(20,30)}
    _need(all(levels[level] == 4**(level-20) for level in levels),
          'ALL_DESCENDANTS_REACH_CANONICAL29')
    return dict(format='no5-explicit-layered-template-dag-validation/1',policy=policy,
        sourceRoot=list(source_root),rootOffset=root_offset,admittedProfileLevels=list(range(20,29)),
        canonicalPhysicalBoundaryLevel=29,uniquePhysicalNodes=len(node_map),
        uniquePhysicalStreams=len(stream_map),physicalEdges=sum(sum(bool(c)for c in parsed[o][0])for o in node_map),
        logicalOccurrencesByLevel=levels,logicalBoundaryOccurrences=levels[29],
        logicalNodesEnumerated=False,readBytes=read_bytes,maximumReadBytes=MAX_READ_BYTES,
        headerContract=header_contract,externalSourceFileSHA256=source_file_sha256,
        profileReceiptSHA256=profile_receipt_sha256,explicitAllowAncestor=allow_ancestor,
        physicalNodes=[dict(offset=o,level=n.level,role=n.role,sha256=n.sha256,
            firstLogicalTileExample=list(first_tile[o]),logicalOccurrenceCount=occurrences[o])
            for o,n in sorted(node_map.items())],
        sameOffsetAtDifferentLevelAllowed=False,allCanonicalRestorePinsChecked=True,
        declaredPhysicalExtentOverlapChecked=True,reservedRangeCount=len(reserved_ranges),
        fullFileBlockInventoryAndStableReadBindingExternal=True,
        actualOriginalAllowAncestorCallerBindingPending=True,
        actualNativeCallbackSourceIDsRequireSeparateAdmission=True,
        nativePhysicalCompatibilityClaim=False,nativeRetirementMeasured=False,
        nativePixelParityClaim=False,normalAggregationClaim=False,
        defaultLegacyTreeValidatorChanged=False,chartOrFileWrites=0)
