"""Own compilation-scale/coverage policy; never a Raymarine DPI contract.

The caller supplies authentic coverage and native-scale companions. This module
does not load files or run any original code. Only point/sounding primitives are
filtered here; it does not pretend that a centroid clips a line or polygon.
"""

from dataclasses import dataclass

from fractions import Fraction

import hashlib

import math

import struct

Q = 2147483649

WORLD_MERCATOR_METRES = Fraction('40075016.68557849')

SOURCE_PPMM = 3

SOURCE_RADIUS_RATIO = Fraction(9996, 10000)

POLICY = 'own-source-parity-compilation-scale-position-winner/1'

def need(ok, name):
    if not ok:
        raise ValueError(name)

def own_display_denominator(level, tile_size=256):
    """Same projected view as the pinned Source PPMM=3 profile, before clamp.

    SourcePpm=1/(.9996*NativeMpp), then SourceScale=3000/SourcePpm.
    This is an explicit compiler policy, not a value measured on the plotter.
    """
    need(isinstance(level, int) and 0 <= level <= 29, 'LEVEL_DOMAIN')
    need(tile_size == 256, 'BOUND_NATIVE_TILE_SIZE')
    native_mpp = WORLD_MERCATOR_METRES / (tile_size * (1 << level))
    source_ppm = 1 / (SOURCE_RADIUS_RATIO * native_mpp)
    source_ppm = max(Fraction(1, 100000), min(Fraction(10), source_ppm))
    return Fraction(SOURCE_PPMM * 1000) / source_ppm

def ring_location(point, ring):
    """-1 outside, 0 boundary, +1 inside; exact integer/Fraction arithmetic."""
    x, y = point
    inside = False
    for (ax, ay), (bx, by) in zip(ring, ring[1:]):
        cross = (bx - ax) * (y - ay) - (by - ay) * (x - ax)
        if cross == 0 and min(ax, bx) <= x <= max(ax, bx) and min(ay, by) <= y <= max(ay, by):
            return 0
        if (ay > y) != (by > y) and ((cross > 0) == (by > ay)):
            inside = not inside
    return 1 if inside else -1

def _bounds(ring):
    return min(x for x, _ in ring), min(y for _, y in ring), max(x for x, _ in ring), max(y for _, y in ring)

def _box_contains(box, point):
    x, y = point
    return box[0] <= x <= box[2] and box[1] <= y <= box[3]

@dataclass(frozen=True)
class CoverageSource:
    cell_id: str
    native_scale: int
    covered: tuple
    excluded: tuple

    def __post_init__(self):
        need(bool(self.cell_id), 'CELL_ID_REQUIRED')
        need(isinstance(self.native_scale, int) and self.native_scale > 0, 'AUTHENTIC_NATIVE_SCALE_REQUIRED')
        need(bool(self.covered), 'AUTHENTIC_COVERED_RING_REQUIRED')
        for ring in self.covered + self.excluded:
            need(len(ring) >= 4 and ring[0] == ring[-1], 'COMPLETE_CLOSED_COVERAGE_RING')
            need(all(isinstance(v, int) for p in ring for v in p), 'INTEGER_ROOT_COVERAGE')
            need(sum(ax * by - bx * ay for (ax, ay), (bx, by) in zip(ring, ring[1:])) != 0, 'NONDEGENERATE_COVERAGE')
        object.__setattr__(self, '_covered_boxes', tuple(map(_bounds, self.covered)))
        object.__setattr__(self, '_excluded_boxes', tuple(map(_bounds, self.excluded)))

    def contains(self, point):
        # Covered boundaries are eligible. Closed excluded boundaries remain
        # excluded. A deterministic winning source owns overlap boundaries.
        return (any(_box_contains(b, point) and ring_location(point, r) >= 0
                    for b, r in zip(self._covered_boxes, self.covered)) and
                not any(_box_contains(b, point) and ring_location(point, r) >= 0
                        for b, r in zip(self._excluded_boxes, self.excluded)))

class SourceWinner:
    def __init__(self, sources):
        self.sources = tuple(sorted(sources, key=lambda s: s.cell_id))
        need(len({s.cell_id for s in self.sources}) == len(self.sources), 'UNIQUE_SOURCE_IDENTITIES')
        self.by_id = {s.cell_id: s for s in self.sources}
        self._covering_cache = {}

    def covering(self, point):
        key = tuple(point)
        if key not in self._covering_cache:
            self._covering_cache[key] = tuple(s for s in self.sources if s.contains(key))
        return self._covering_cache[key]

    def choose(self, point, level):
        """Closest available compilation scale with enough source resolution.

        At the point, choose the largest native denominator <= own display
        denominator. If all available scales are coarser, choose the smallest
        (explicit overzoom fallback). Same-scale ties use cell identity. Coverage
        exclusion is evaluated before ranking, so another real source can fill
        an exclusion. This deterministic policy is not a vendor selector claim.
        """
        candidates = self.covering(point)
        if not candidates:
            return None
        d = own_display_denominator(level)
        sufficient = tuple(s for s in candidates if s.native_scale <= d)
        chosen = min(sufficient, key=lambda s: (-s.native_scale, s.cell_id)) if sufficient else min(candidates, key=lambda s: (s.native_scale, s.cell_id))
        return chosen.cell_id

def filter_point_owner(coordinates, index_array, stored_record, *, cell_id,
                       level, winner, source_tile, input_root):
    """Compact one genuine Root/L0 point or sounding owner.

    Input arrays are complete per-owner data with source offsets. The output is
    a complete standalone stream starting at offset0, with a new SpatialIndex
    array and record IndexOffset0. RenderOffset, DI, Word20, class, UID and flags
    in the stored record stay unchanged. The composer must separately rebase
    Render/Additional and preserve the original full-owner query footer.
    """
    need(len(stored_record) == 32, 'STORED_RECORD_32')
    kind = stored_record[28]
    need(kind in (1, 4), 'ONLY_POINT_OR_SOUNDING_OWNER')
    need(cell_id in winner.by_id, 'BOUND_SOURCE_ID')
    need(input_root in (0, 1), 'DECLARED_ELEMENT_ROOT_CONTEXT')
    need(input_root == 1 or tuple(source_tile) == (0, 0, 0), 'L0_ONLY_ROOT_PROMOTION')
    n = struct.unpack_from('<I', index_array)[0]
    need(len(index_array) == 4 + 4 * n and n > 0, 'COMPLETE_NONEMPTY_INDEX_ARRAY')
    refs = struct.unpack_from('<' + 'I' * n, index_array, 4)
    if kind == 1:
        need(n == 1, 'POINT_OWNER_SINGLE_INDEX_SEMANTICS')
    stride = 12 if kind == 4 else 8
    out_co = bytearray()
    out_refs = []
    alias_map = {}
    input_slots = kept_slots = 0
    decisions = []
    for word in refs:
        at = word & 0xffffff
        need(at + 4 <= len(coordinates), 'ELEMENT_START_BOUND')
        h = struct.unpack_from('<I', coordinates, at)[0]
        count = h & 0xffffff
        flags = h >> 24
        need((flags & 1) == input_root and count > 0, 'ELEMENT_ROOT_AND_COUNT')
        end = at + 4 + count * stride
        need(end <= len(coordinates), 'COMPLETE_TYPED_ELEMENT')
        if kind == 1:
            need(count == 1, 'POINT_OWNER_SINGLE_POINT_SEMANTICS')
        if at not in alias_map:
            kept = []
            for i in range(count):
                slot = coordinates[at + 4 + i * stride:at + 4 + (i + 1) * stride]
                xy = struct.unpack_from('<II', slot)
                chosen = winner.choose(xy, level)
                input_slots += 1
                if chosen == cell_id:
                    kept.append(slot)
                    kept_slots += 1
            decisions.append((at, count, len(kept)))
            if kept:
                new_at = len(out_co)
                need(new_at <= 0xffffff, 'LOW24_COORDINATE_START')
                out_co += struct.pack('<I', ((flags | 1) << 24) | len(kept)) + b''.join(kept)
                alias_map[at] = new_at
            else:
                alias_map[at] = None
        if alias_map[at] is not None:
            out_refs.append((word & 0xff000000) | alias_map[at])
    need(len(out_co) < 1 << 24, 'OWN_FULL_STREAM_LOW24_BUDGET')
    new_ix = struct.pack('<I', len(out_refs)) + struct.pack('<' + 'I' * len(out_refs), *out_refs)
    new_record = bytearray(stored_record) if out_refs else None
    if new_record is not None:
        struct.pack_into('<I', new_record, 8, 0)
    receipt = dict(policy=POLICY, sourceCell=cell_id, destinationLevel=level,
                   inputRoot=input_root, sourceTile=list(source_tile),
                   inputCoordinateSlots=input_slots, retainedCoordinateSlots=kept_slots,
                   originalIndexOccurrences=n, retainedIndexOccurrences=len(out_refs),
                   unchangedXYZSlots=True, originalUIDPreserved=True,
                   fullOwnerQueryRecovery='CALLER_MUST_PRESERVE_ORIGINAL_OWNER_FOOTER',
                   nativeGetShape='tile_selected_point_subset',
                   sourceSCAMINApplied=False, areaLineOrOutlineAdmission=False,
                   elementDecisions=decisions,
                   coordinateSHA256=hashlib.sha256(out_co).hexdigest(),
                   indexSHA256=hashlib.sha256(new_ix).hexdigest())
    return bytes(out_co), new_ix, bytes(new_record) if new_record is not None else None, receipt
