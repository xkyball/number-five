"""Read-only geometry-load budgets from the public RX5 Reader's metadata.

The caller authenticates the complete source and passes its binding including
verified ctimeNs. The explicit authenticate_reader_source helper can do this;
the older prepare_index stat4 alone is insufficient. The index itself reads
only headers/prefixes through region.Reader, without a repeated whole-file hash.
It never chooses a source, decodes geometry, or changes an export selection.
The reported byte counts are package sizes, not measured I/O or cache hits.
"""
from dataclasses import dataclass
import hashlib
import os
import struct
from typing import Mapping

from .region import Reader, statkey
from .wire import need

MAX_LEVEL = 21


def _natural(value, reason):
    need(type(value) is int and value >= 0, reason)
    return value


def _tile(value):
    need(isinstance(value, (tuple, list)) and len(value) == 3, 'PLANNING_TILE_TRIPLE')
    need(all(type(v) is int for v in value), 'PLANNING_INTEGER_TILE')
    z, x, y = value
    need(0 <= z <= MAX_LEVEL and 0 <= x < 1 << z and 0 <= y < 1 << z, 'PLANNING_TILE_BOUNDS')
    return z, x, y


def range_union_bytes(spans):
    """Exact byte union of half-open (offset, length) ranges; no reads."""
    intervals = []
    for offset, length in spans:
        _natural(offset, 'PLANNING_RANGE_OFFSET')
        _natural(length, 'PLANNING_RANGE_LENGTH')
        if length:
            intervals.append((offset, offset + length))
    total, end = 0, -1
    for first, last in sorted(intervals):
        if first >= end:
            total += last - first
        elif last > end:
            total += last - end
        end = max(end, last)
    return total


@dataclass(frozen=True)
class SourceBinding:
    sha256: str
    bytes: int
    uuid_hex: str
    stat: tuple
    ctime_ns: int

    @classmethod
    def from_mapping(cls, source: Mapping):
        digest = source.get('sha256')
        need(type(digest) is str and len(digest) == 64 and all(c in '0123456789abcdef' for c in digest), 'PLANNING_SOURCE_SHA256')
        size = _natural(source.get('bytes'), 'PLANNING_SOURCE_BYTES')
        need(size >= 240, 'PLANNING_SOURCE_HEADER_SIZE')
        uuid = source.get('uuidHex')
        need(type(uuid) is str and len(uuid) == 32 and all(c in '0123456789abcdef' for c in uuid), 'PLANNING_SOURCE_UUID')
        stat = source.get('stat')
        need(isinstance(stat, (tuple, list)) and len(stat) == 4, 'PLANNING_SOURCE_STAT')
        need(all(type(v) is int and v >= 0 for v in stat) and stat[2] == size, 'PLANNING_SOURCE_STAT_FIELDS')
        ctime = _natural(source.get('ctimeNs'), 'PLANNING_AUTHENTICATED_CTIME_NS')
        return cls(digest, size, uuid, tuple(stat), ctime)

    def as_dict(self):
        return dict(sha256=self.sha256, bytes=self.bytes, uuidHex=self.uuid_hex,
                    stat=list(self.stat), ctimeNs=self.ctime_ns)


def authenticate_reader_source(reader: Reader, *, expected_sha256, chunk_bytes=4<<20):
    """Explicit full-file authentication on the held public Reader FD.

    This step reads payload bytes solely to hash them; it does not decode them.
    Keep the returned binding for later metadata-only index calls. A binding
    obtained elsewhere must provide the ctimeNs verified with that full hash.
    """
    need(isinstance(reader, Reader) and reader.fd is not None, 'PLANNING_OPEN_PUBLIC_READER')
    need(type(expected_sha256) is str and len(expected_sha256) == 64 and all(c in '0123456789abcdef' for c in expected_sha256), 'PLANNING_EXPECTED_SHA256')
    need(type(chunk_bytes) is int and chunk_bytes >= 240, 'PLANNING_AUTH_CHUNK_BYTES')
    before = os.fstat(reader.fd)
    need(statkey(before) == statkey(reader.before) and before.st_ctime_ns == reader.before.st_ctime_ns, 'PLANNING_AUTH_SOURCE_GENERATION')
    digest = hashlib.sha256()
    for offset in range(0,before.st_size,chunk_bytes):
        data = reader.read(offset,min(chunk_bytes,before.st_size-offset))
        if offset == 0:
            need(data[:240] == reader.header, 'PLANNING_AUTH_SAME_HEADER')
        digest.update(data)
    after = os.fstat(reader.fd)
    need(statkey(after) == statkey(before) and after.st_ctime_ns == before.st_ctime_ns, 'PLANNING_AUTH_SOURCE_CHANGED')
    need(digest.hexdigest() == expected_sha256, 'PLANNING_AUTH_SHA256_MISMATCH')
    return SourceBinding.from_mapping(dict(sha256=expected_sha256,bytes=before.st_size,
        uuidHex=reader.header[56:72].hex(),stat=statkey(before),ctimeNs=before.st_ctime_ns)).as_dict()


@dataclass(frozen=True)
class SourcePackage:
    tile: tuple
    node_offset: int
    stored_owners: int
    geometry_payload_ranges: tuple
    geometry_package_ranges: tuple

    @property
    def empty_replacement(self):
        return self.stored_owners == 0


@dataclass(frozen=True)
class LoadIndex:
    source: SourceBinding
    packages: tuple
    tree_nodes: int


def build_load_index(reader: Reader, *, source_binding: Mapping, max_nodes=100000):
    """Index existing replacement payloads using only block header metadata.

    source_binding contains previously verified sha256, bytes, uuidHex,
    stat=[dev,ino,size,mtime_ns] AND ctimeNs authenticated with that hash. The old
    public region index stat4 alone is insufficient. The source hash is
    caller-authenticated, not newly verified here. Actual held-FD generation,
    length and header UUID are checked before and after these metadata reads.
    The caller retains ownership of the Reader and must close it normally.
    """
    need(isinstance(reader, Reader), 'PLANNING_PUBLIC_READER_REQUIRED')
    _natural(max_nodes, 'PLANNING_NODE_LIMIT')
    source = SourceBinding.from_mapping(source_binding)
    need(reader.fd is not None, 'PLANNING_OPEN_READER_REQUIRED')
    before = reader.before
    need(tuple(statkey(before)) == source.stat and before.st_ctime_ns == source.ctime_ns, 'PLANNING_HELD_FD_BINDING')
    need(len(reader.header) == 240 and reader.header[:8] == b'\x01RayRX5\0', 'PLANNING_RX5_HEADER')
    need(reader.header[56:72].hex() == source.uuid_hex, 'PLANNING_HEADER_UUID')
    need(all(struct.unpack_from('<Q', reader.header, off)[0] == 0 for off in (152,176,192)), 'PLANNING_OWN_PLAIN_SOURCE_REQUIRED')

    def check_fd():
        now = os.fstat(reader.fd)
        need(tuple(statkey(now)) == source.stat and now.st_ctime_ns == source.ctime_ns, 'PLANNING_SOURCE_GENERATION_CHANGED')

    check_fd()

    def geometry_ranges(start, expected_id):
        seen, payloads, packages = set(), [], []
        while start:
            need(start not in seen and len(seen) < max_nodes, 'PLANNING_GEOMETRY_CHAIN_BOUND')
            seen.add(start)
            bid, flags, payload, size, nxt, prefix = reader.block(start)
            need(bid == expected_id and flags == 0 and prefix == b'', 'PLANNING_PLAIN_GEOMETRY_BLOCK')
            need(payload == start + 16 and payload + size <= source.bytes, 'PLANNING_GEOMETRY_BLOCK_RANGE')
            payloads.append((payload, size))
            packages.append((start, 16 + size))
            start = nxt
        need(bool(seen), 'PLANNING_PRESENT_GEOMETRY_STREAM')
        return payloads, packages

    pending = [(struct.unpack_from('<Q', reader.header, 160)[0], (0,0,0))]
    seen, packages = set(), []
    while pending:
        offset, tile = pending.pop()
        _tile(tile)
        need(offset not in seen and len(seen) < max_nodes, 'PLANNING_ACYCLIC_TREE_BOUND')
        seen.add(offset)
        bid, flags, payload, size, nxt, prefix = reader.block(offset)
        need(flags in (1,3) and bid == 0x6000 + tile[0] and nxt == 0, 'PLANNING_NATIVE_NODE_TYPE')
        need(len(prefix) == (64 if flags == 3 else 32) and payload + size <= source.bytes, 'PLANNING_NODE_METADATA_RANGE')
        if flags == 3:
            need(size % 32 == 0, 'PLANNING_COMPLETE_FEATURE32_SIZE')
            indices, coordinates, _, _ = struct.unpack_from('<4Q', prefix, 32)
            ix, ix_package = geometry_ranges(indices, 0x1101)
            xy, xy_package = geometry_ranges(coordinates, 0x1102)
            packages.append(SourcePackage(tile, offset, size//32, tuple(ix+xy), tuple(ix_package+xy_package)))
        z, x, y = tile
        for quadrant, child in enumerate(struct.unpack_from('<4Q', prefix)):
            if child:
                pending.append((child, (z+1, 2*x+(quadrant & 1), 2*y+(quadrant >> 1))))
    check_fd()
    return LoadIndex(source, tuple(sorted(packages, key=lambda p:p.tile)), len(seen))


def estimate_selected_requests(index: LoadIndex, requests, *, source_binding: Mapping,
                               byte_budget=None, max_requests=100000):
    """Report sizes for (destination, supplied source tile or None) requests.

    Supplied coarse sources, None and explicit empty replacement sources remain
    authoritative; there is no fallback/selector here. A byte_budget applies to
    freshGeometryPackageBytes (header-inclusive package union per request).
    Exceeding it is reported, never resolved by dropping requests or details.
    Caller must supply its current authenticated source binding on each call.
    """
    need(isinstance(index, LoadIndex), 'PLANNING_LOAD_INDEX_REQUIRED')
    need(SourceBinding.from_mapping(source_binding) == index.source, 'PLANNING_STALE_SOURCE_BINDING')
    _natural(max_requests, 'PLANNING_REQUEST_LIMIT')
    if byte_budget is not None:
        _natural(byte_budget, 'PLANNING_BYTE_BUDGET')
    by_tile = {package.tile:package for package in index.packages}
    selected, destinations = [], []
    for number, (destination, source) in enumerate(requests):
        need(number < max_requests, 'PLANNING_REQUEST_LIMIT_EXCEEDED')
        destination = _tile(destination)
        destinations.append(list(destination))
        if source is None:
            selected.append(None)
            continue
        source = _tile(source)
        need(source in by_tile, 'PLANNING_SELECTED_SOURCE_ABSENT')
        z, x, y = destination
        sz, sx, sy = source
        need(sz <= z and (x >> (z-sz), y >> (z-sz)) == (sx,sy), 'PLANNING_SELECTED_SOURCE_ANCESTOR')
        selected.append(by_tile[source])
    present = [package for package in selected if package is not None]
    unique = {package.node_offset:package for package in present}
    fresh_payload = sum(range_union_bytes(p.geometry_payload_ranges) for p in present)
    fresh_package = sum(range_union_bytes(p.geometry_package_ranges) for p in present)
    result = dict(format='no5-rx5-load-planning/1', sourceSHA256=index.source.sha256,
        sourceBytes=index.source.bytes, requestCount=len(selected),
        destinations=destinations, selectedSources=[None if p is None else list(p.tile) for p in selected],
        noSourceRequests=sum(p is None for p in selected),
        explicitEmptyRequests=sum(p is not None and p.empty_replacement for p in selected),
        uniqueSourcePackages=len(unique),
        freshGeometryPayloadBytes=fresh_payload, freshGeometryPackageBytes=fresh_package,
        uniqueSourceGeometryPayloadBytes=sum(range_union_bytes(p.geometry_payload_ranges) for p in unique.values()),
        uniqueSourceGeometryPackageBytes=sum(range_union_bytes(p.geometry_package_ranges) for p in unique.values()),
        unionGeometryPayloadBytes=range_union_bytes(span for p in unique.values() for span in p.geometry_payload_ranges),
        unionGeometryPackageBytes=range_union_bytes(span for p in unique.values() for span in p.geometry_package_ranges),
        budget=dict(basis='freshGeometryPackageBytes', limitBytes=byte_budget,
                    withinBudget=None if byte_budget is None else fresh_package <= byte_budget,
                    excessBytes=None if byte_budget is None else max(0, fresh_package-byte_budget)),
        sourceSelectionChanged=False, actualIOClaim=False, cacheHitClaim=False)
    return result
