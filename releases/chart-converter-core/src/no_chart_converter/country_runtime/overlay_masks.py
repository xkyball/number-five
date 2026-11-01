from . import owner_masks as old, source_walk as walk
from .diagnostic_area import intersection_area

"""Source-coverage masks for detail overlays; never ground-fill availability.

The caller keeps the original source/point coverage walk, calls for_owner for
every complete owner, and uses the unchanged RobustV3 clip/quantization API.
Only Kind2/3 programs are admitted. No source/native I/O or geometry repair.
"""

from collections import namedtuple

from pathlib import Path

import hashlib

import sys

from shapely.geometry import Polygon, box
from shapely.affinity import translate

from shapely.ops import unary_union

Q = 2147483649

def need(value, label):
    if not value:
        raise ValueError(label)

SourceSnapshot = namedtuple('SourceSnapshot', 'cell_id native_scale covered excluded')

def gsha(geometry):
    return hashlib.sha256(geometry.wkb).hexdigest()

class OwnerMaskService(old.OwnerMaskService):
    """Same owner-call ABI as GroundService, with coverage-only semantics."""

    def __init__(self, sources, own_denominator_function, clip_core):
        # Cache geometry depends on values, not mutable caller lists/objects.
        snapshots = tuple(SourceSnapshot(s.cell_id, s.native_scale,
            tuple(tuple(tuple(p) for p in r) for r in s.covered),
            tuple(tuple(tuple(p) for p in r) for r in s.excluded)) for s in sources)
        super().__init__(snapshots, own_denominator_function, clip_core)
        points = [p for s in self.sources for r in s.covered + s.excluded for p in r]
        need(points, 'NONEMPTY_ORIGINAL_COVERAGE_POINTS')
        self.origin = min(x for x, y in points), min(y for x, y in points)
        self.source_area_semantics = []
        for cid, entries in self.rings.items():
            for e in entries:
                try:
                    decoded = walk.decode_coverage_ring(e['ring'],cell_id=cid,role=e['kind'],ordinal=e['index'])
                except walk.UnresolvedCoverageArea:
                    # Keep the existing owner-scoped invalid-ring decisions.
                    continue
                e['geometry']=decoded['areaGeometry'];e['valid']=True
                e['areaSemanticsReceipt']=decoded['receipt']
                self.source_area_semantics.append(decoded['receipt'])
        self.local_upper = {}
        for cid, entries in self.rings.items():
            self.local_upper[cid] = unary_union([
                self._local_geometry(e) if e['valid'] else box(
                    e['bounds'][0] - self.origin[0], e['bounds'][1] - self.origin[1],
                    e['bounds'][2] - self.origin[0], e['bounds'][3] - self.origin[1])
                for e in entries if e['kind'] == 'covered'])
        self.domain_cache = {}
        self.domain_union_rows = {}
        self.overlay_clipper_cache = {}
        # All caches belong to this immutable source snapshot. Keep the exact
        # ordered binary union, including empty/domain-omission prefix rows;
        # unary_union or reordered inputs need not produce the same WKB.
        self.higher_union_cache = {(): Polygon()}

    def _local_geometry(self, entry):
        return translate(entry['geometry'],-self.origin[0],-self.origin[1])

    def _local_domain(self, entries):
        return unary_union([self._local_geometry(e) for e in entries if e['kind'] == 'covered']).difference(
            unary_union([self._local_geometry(e) for e in entries if e['kind'] == 'excluded']))

    def for_owner(self, cellId, requestedLevel, completeOwnerBBox, *, owner_kind=3):
        need(cellId in self.by_id and type(owner_kind) is int and owner_kind in (2, 3), 'BOUND_OVERLAY_SOURCE_KIND2_3')
        b = tuple(completeOwnerBBox)
        need(len(b) == 4 and all(type(v) is int and 0 <= v <= Q for v in b)
             and b[0] <= b[2] and b[1] <= b[3], 'COMPLETE_INTEGER_OWNER_BBOX')
        padded = max(0, b[0] - 2), max(0, b[1] - 2), min(Q, b[2] + 2), min(Q, b[3] + 2)
        region = old.bbox_geometry((padded[0] - self.origin[0], padded[1] - self.origin[1],
                                    padded[2] - self.origin[0], padded[3] - self.origin[1]))
        denominator, rank = self.rank(requestedLevel)
        at = next(i for i, s in enumerate(rank) if s.cell_id == cellId)
        prefix = rank[:at + 1]
        earlier = Polygon()
        selected, cache_rows, union_rows, domains = [], [], [], {}
        receipt = dict(format='no5-detail-overlay-original-source-coverage-mask/1',
            ownCell=cellId, requestedLevel=requestedLevel, ownerKind=owner_kind, ownerBBox=list(b),
            denominatorExact=str(denominator), globalRank=[s.cell_id for s in rank],
            prefix=[s.cell_id for s in prefix], ringOmissions=[], sourceOmissions=[], unresolvedRings=[],
            maskPolicy='authenticOwnCellCoverage minus ordered authenticHigherCellCoverage',
            groundFillAvailabilityRead=False, groundFillAvailabilityChanged=False,
            overlayGeometryAddedToGroundFill=False, pointSoundingWinnerChanged=False,
            originalStoredCoverageWalkUnchanged=True, kind8AreaAdmission=False)
        for source in prefix:
            cid = source.cell_id
            isown = cid == cellId
            entries = self.rings[cid]
            removed = [e for e in entries if not e['valid'] and old.disjoint(e['bounds'], padded)]
            remaining = [e for e in entries if e not in removed]
            for e in removed:
                receipt['ringOmissions'].append(self._ring_receipt(cid, e, 'EXACT_INVALID_RING_AABB_DISJOINT_FROM_PADDED_OWNER'))
            bad = [e for e in remaining if not e['valid']]
            if bad:
                effect = self.local_upper[cid].intersection(region)
                if effect.is_empty or (not earlier.is_empty and earlier.covers(effect)):
                    receipt['sourceOmissions'].append(dict(cellId=cid,
                        reason='POSSIBLE_COVERED_SOURCE_EFFECT_DOMINATED_BY_VALID_EARLIER_COVERAGE',
                        originalInvalidRings=[self._ring_receipt(cid, e, 'SOURCE_EFFECT_DOMINATED') for e in bad]))
                    cache_rows.append((cid, 'SOURCE_EFFECT_DOMINATED'))
                    union_rows.append((cid, 'SOURCE_EFFECT_DOMINATED',
                        tuple((e['kind'], e['index']) for e in removed)))
                    if isown:
                        return None, dict(receipt, disposition='REMOVE_COMPLETE_OWN_DOMINATED_EFFECT', clipperCreated=False)
                    continue
                receipt['unresolvedRings'] = [self._ring_receipt(cid, e, 'RELEVANT_TO_PADDED_OWNER_AND_NOT_DOMINATED') for e in bad]
                raise old.InvalidSourceMaskError(receipt)
            key = cid, tuple((e['kind'], e['index']) for e in removed)
            if key not in self.domain_cache:
                domain = self._local_domain(remaining)
                need(domain.is_valid, 'VALID_AUTHENTIC_LOCAL_SOURCE_COVERAGE')
                self.domain_cache[key] = domain
                # Capture exact immutable GEOS bytes once, rather than hash or
                # serialize the same potentially large domain for every owner.
                self.domain_union_rows[key] = (key, domain.wkb)
            domain = self.domain_cache[key]
            cache_rows.append(key)
            union_rows.append(self.domain_union_rows[key])
            if domain.is_empty:
                if isown:
                    return None, dict(receipt, disposition='REMOVE_EMPTY_OWN_COVERAGE', clipperCreated=False)
                continue
            selected.append(source)
            domains[cid] = domain
            if not isown:
                prefix_key = tuple(union_rows)
                if prefix_key not in self.higher_union_cache:
                    self.higher_union_cache[prefix_key] = earlier.union(domain)
                earlier = self.higher_union_cache[prefix_key]
        need(selected and selected[-1].cell_id == cellId, 'OWN_COVERAGE_SOURCE_IN_PREFIX')
        key = requestedLevel, tuple(cache_rows)
        reused = key in self.overlay_clipper_cache
        if not reused:
            own = domains[cellId]
            mask = own.difference(earlier)
            need(mask.is_valid and earlier.is_valid, 'VALID_AUTHENTIC_OVERLAY_MASK')
            clipper = object.__new__(self.core.GeometryWinner)
            clipper.origin, clipper.level = self.origin, requestedLevel
            clipper.sources = clipper.priority = tuple(selected)
            clipper.denominator, clipper.coverage = denominator, dict(domains)
            clipper.masks = {cellId: mask}
            clipper.ownerAuthenticSemantics = dict(own=cellId, higher=earlier, higherBoundary=earlier.boundary,
                ownCoverage=own, direct=mask, higherCells=[s.cell_id for s in selected[:-1]],
                authenticSourceCoverage=own, groundAvailability=False, overlayCoverageOnly=True)
            self.overlay_clipper_cache[key] = clipper
        clipper = self.overlay_clipper_cache[key]
        mask, semantics = clipper.masks[cellId], clipper.ownerAuthenticSemantics
        if not mask.intersects(region):
            return None, dict(receipt, disposition='NO_OWN_COVERAGE_WINNER_EFFECT', cacheHit=reused, clipperCreated=not reused)
        clipper.ownerScopeBBox = b
        clipper.omittedExcludedRings = [r for r in receipt['ringOmissions'] if r['kind'] == 'excluded']
        need(mask.wkb == semantics['direct'].wkb, 'EXACT_CACHED_OVERLAY_CONSTRUCTION_IDENTITY')
        receipt.update(disposition='VALID_SOURCE_COVERAGE_OVERLAY_CLIPPER', cacheHit=reused, clipperCreated=not reused,
            retainedPrefix=[s.cell_id for s in selected], translatedRootOrigin=list(self.origin),
            ownMaskTranslatedWkbSHA256=gsha(mask), higherCoverageTranslatedWkbSHA256=gsha(semantics['higher']),
            scopeProof=dict(paddedOwnerBBox=list(padded),
                ownOutsideCoverageArea=mask.intersection(region).difference(semantics['ownCoverage']).area,
                ownHigherCoverageOverlapArea=intersection_area(mask.intersection(region), semantics['higher']),
                positiveReoverlayResidualsDiagnosticOnly=True, maskConstructionIdentityExact=True,
                sourceAreaSemantics=self.source_area_semantics, freshEvenOnCacheHit=True,
                finalQuantizedOwnerValidationRequired=True), receiptScopeRequiredForEveryCallerOwner=True,
            quantizationPolicyChanged=False, nativePixelOrBoundaryEndpointProof=False)
        return clipper, receipt
