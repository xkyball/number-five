from . import scoped_v1 as old

"""Own authentic-coverage semantic continuation; frozen world masks unchanged.

Pairwise intersections of separately differenced masks are diagnostics, not
the definition of SourceWinner. Every caller validates its actual owner scope
against OwnCoverage minus the authentic higher-priority coverage union.
The caller must validate each final integer owner with validate_final_owner.
"""

from pathlib import Path

import hashlib, struct, sys

from shapely.geometry import Polygon, LineString, box

from shapely.ops import unary_union

Q = 2147483649

LOW24 = (1 << 24)-1

def need(ok, label):
    if not ok: raise ValueError(label)

def sha(g): return hashlib.sha256(g.wkb).hexdigest()

def construction_core(original_core):
    previous = old.construction_core(original_core)
    class GeometryWinner(previous.GeometryWinner):
        def __init__(self, sources, denominator, *, level):
            super().__init__(sources, denominator, level=level)
            own = self.priority[-1].cell_id
            # Use the same ordered original union operations as the frozen
            # constructor. This reproduces its actual mask byte for byte.
            higher = Polygon()
            for source in self.priority[:-1]: higher = higher.union(self.coverage[source.cell_id])
            direct = self.coverage[own].difference(higher)
            need(direct.wkb == self.masks[own].wkb, 'AUTHENTIC_OWN_MINUS_ORDERED_HIGHER_MASK_BYTES')
            self.ownerAuthenticSemantics = dict(own=own, higher=higher, ownCoverage=self.coverage[own],
                                               direct=direct, higherBoundary=higher.boundary,
                                               higherCells=[s.cell_id for s in self.priority[:-1]])
    class Core:
        def __getattr__(self, name): return getattr(original_core, name)
    result = Core(); result.GeometryWinner = GeometryWinner; return result

class OwnerMaskService:
    """Drop-in service API; fresh caller proof and final-owner validation API."""
    def __init__(self, sources, own_denominator_function, clip_core):
        self.service = old.frozen.OwnerMaskService(sources, own_denominator_function, construction_core(clip_core))
    def __getattr__(self, name): return getattr(self.service, name)
    def for_owner(self, cellId, requestedLevel, completeOwnerBBox):
        clipper, receipt = self.service.for_owner(cellId, requestedLevel, completeOwnerBBox)
        if clipper is None: return None, receipt
        e = clipper.ownerAuthenticSemantics
        need(e['own'] == cellId, 'AUTHENTIC_SEMANTIC_OWNER_CELL_BINDING')
        b = tuple(completeOwnerBBox)
        padded = (max(0,b[0]-2),max(0,b[1]-2),min(Q,b[2]+2),min(Q,b[3]+2))
        ox, oy = clipper.origin
        region = box(padded[0]-ox,padded[1]-oy,padded[2]-ox,padded[3]-oy)
        mask = clipper.masks[cellId]
        inside = mask.intersection(region)
        semantic_difference = mask.symmetric_difference(e['direct']).intersection(region).area
        ownoutside = inside.difference(e['ownCoverage']).area
        ownhigher = inside.intersection(e['higher']).area
        old_continuation = getattr(clipper,'ownerConstructionContinuation',None)
        pairs = []
        if old_continuation:
            for a, other, g in old_continuation['residuals']:
                pairs.append(dict(sourceA=a,sourceB=other,pairIncludesOwn=cellId in (a,other),
                                  globalResidualAreaRootUnits2=g.area,globalBBoxLocal=list(g.bounds),
                                  areaInsidePaddedOwnerBBox=g.intersection(region).area,
                                  rawResidualWkbSHA256=sha(g),acceptancePredicate=False))
        proof = dict(policy='own-mask-authentic-coverage-semantic-continuation/2',ownCell=cellId,
                     requestedLevel=requestedLevel,completeOwnerBBox=list(b),paddedOwnerBBox=list(padded),rootUnitPad=2,
                     directOwnCoverageMinusOrderedAuthenticHigherUnionMaskByteExact=True,
                     ownMaskWkbSHA256=sha(mask),authenticHigherCoverageUnionWkbSHA256=sha(e['higher']),
                     ownMaskVsSemanticDifferenceInScopeRootUnits2=semantic_difference,
                     ownMaskOutsideAuthenticOwnCoverageInScopeRootUnits2=ownoutside,
                     ownVsAuthenticHigherCoverageResidualInScopeRootUnits2=ownhigher,
                     residualPairs=pairs,pairwiseDifferencedMaskIntersectionsDiagnosticOnly=True,
                     sameFrozenConstructedMasksUsed=True,noToleranceRepairOrResidualDrop=True,
                     cacheHitStillFreshlyValidated=receipt.get('cacheHit',False),globalPartitionAdmission=False,
                     finalQuantizedOwnerValidationRequired=True,
                     exactOwnScopePassed=semantic_difference == ownoutside == ownhigher == 0)
        result = dict(receipt,constructionContinuation=proof)
        if not proof['exactOwnScopePassed']: raise old.ScopeConstructionError(result)
        return clipper,result

def validate_final_owner(clipper, coordinates, indices, record, *, cell_id):
    """Return exact final-owner semantic receipt; area and line closures differ.

    Read only the newly serialized owner bytes. No source/runtime/device read.
    Usage8/24 exteriors and Usage20 interiors retain their existing semantics;
    Reverse is applied once to obtain the directed shape. Kind8 is excluded.
    """
    e = clipper.ownerAuthenticSemantics
    need(e['own'] == cell_id,'FINAL_AUTHENTIC_OWNER_CELL')
    result = dict(policy='own-final-integer-owner-authentic-source-containment/2',ownCell=cell_id,
                  requestedLevel=clipper.level,authenticHigherCells=e['higherCells'],
                  originalMaskWkbSHA256=sha(clipper.masks[cell_id]),
                  authenticOwnCoverageWkbSHA256=sha(e['ownCoverage']),
                  authenticHigherCoverageUnionWkbSHA256=sha(e['higher']),
                  pairwiseDifferencedMaskResidualNotAcceptancePredicate=True,
                  originalQueryFooter='CALLER_MUST_RETAIN_UNCHANGED',nativePixelProof=False)
    if record is None:
        need(coordinates == b'' and indices == struct.pack('<I',0),'FINAL_EMPTY_OWNER_SERIALIZATION')
        return dict(result,disposition='EMPTY_DRAW_OWNER_ORIGINAL_QUERIES_RETAINED_BY_CALLER',passed=True)
    need(len(record)==32 and record[28] in (2,3),'FINAL_ONLY_BOUND_KIND2_3')
    n = struct.unpack_from('<I',indices)[0]
    need(n>0 and len(indices)==4+4*n and struct.unpack_from('<I',record,8)[0]==0,'FINAL_COMPLETE_STANDALONE_INDEX')
    outside = []; interiors = []; lines = []
    for word, in struct.iter_unpack('<I',indices[4:]):
        at=word&LOW24; role=word>>24
        need(at+4<=len(coordinates),'FINAL_ELEMENT_HEADER_RANGE')
        h=struct.unpack_from('<I',coordinates,at)[0];count=h&LOW24
        need(h>>24==1 and count>1 and at+4+8*count<=len(coordinates),'FINAL_ROOT1_XY_ELEMENT_RANGE')
        points=list(struct.iter_unpack('<II',coordinates[at+4:at+4+8*count]))
        need(all(0<=v<=Q for p in points for v in p),'FINAL_ROOT_U32_DOMAIN')
        if role&3==1: points.reverse()
        need(role&3 in (0,1),'FINAL_BOUND_REVERSE')
        local=clipper.local(points)
        if record[28]==2:
            need(role in (16,17),'FINAL_BOUND_SIMPLE_LINE_ROLES16_17'); lines.append(LineString(local))
        else:
            need(role in (8,9,24,25,20,21) and points[0]==points[-1],'FINAL_BOUND_CLOSED_EXTERIOR_INTERIOR')
            g=Polygon(local);need(g.is_valid and g.area>0,'FINAL_VALID_POSITIVE_RING')
            (outside if role&12==8 else interiors).append(g)
    mask=clipper.masks[cell_id]
    if record[28]==3:
        g=unary_union(outside).difference(unary_union(interiors))
        need(g.is_valid and g.area>0,'FINAL_VALID_POSITIVE_FILL_OWNER')
        outsideOwn=g.difference(e['ownCoverage']).area
        outsideMask=g.difference(mask).area
        higherFill=g.intersection(e['higher']).area
        need(outsideOwn==outsideMask==higherFill==0,'FINAL_AUTHENTIC_SOURCE_FILL_CONTAINMENT')
        return dict(result,disposition='VALID_POSITIVE_FILL',kind=3,
                    fillAreaRootUnits2=g.area,outsideAuthenticOwnAreaRootUnits2=outsideOwn,
                    outsideConstructedOwnMaskAreaRootUnits2=outsideMask,
                    insideAuthenticHigherCoverageAreaRootUnits2=higherFill,
                    finalOwnerGeometryWkbSHA256=sha(g),passed=True)
    g=unary_union(lines);need(g.is_valid and g.length>0,'FINAL_VALID_POSITIVE_SIMPLE_LINE_OWNER')
    outsideOwn=g.difference(e['ownCoverage']).length
    outsideMask=g.difference(mask).length
    higherIntersection=g.intersection(e['higher'])
    interiorLength=higherIntersection.difference(e['higherBoundary']).length
    closureLength=higherIntersection.intersection(e['higherBoundary']).length
    need(outsideOwn==outsideMask==interiorLength==0,'FINAL_AUTHENTIC_SOURCE_LINE_INTERIOR_CONTAINMENT')
    return dict(result,disposition='VALID_SIMPLE_LINE_WITH_DECLARED_CLOSURE_BOUNDARIES',kind=2,
                lineLengthRootUnits=g.length,outsideAuthenticOwnLengthRootUnits=outsideOwn,
                outsideConstructedOwnMaskLengthRootUnits=outsideMask,
                insideAuthenticHigherInteriorLengthRootUnits=interiorLength,
                commonHigherCoverageClosureLengthRootUnits=closureLength,
                closureEndpointOrRasterOwnershipNotNewlyAdmitted=True,
                finalOwnerGeometryWkbSHA256=sha(g),passed=True)
