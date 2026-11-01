from . import owner_masks as frozen

"""Own scoped continuation of one GLOBAL pairwise GEOS partition refusal.

Frozen world masks stay unchanged. Every cached caller gets a new exact area
proof over its complete ownerBBox padded2Root units. No numeric tolerance,
repair, dropping residuals, snap/buffer, source or native execution.
"""

from pathlib import Path

import hashlib, sys

from shapely.geometry import box

from shapely.ops import unary_union

Q=2147483649

class ScopeConstructionError(ValueError):
    def __init__(self,receipt):
        self.receipt=receipt;super().__init__('ACTUAL_OWNER_SCOPE_MASK_OVERLAP')

def construction_core(original_core):
    class GeometryWinner(original_core.GeometryWinner):
        def __init__(self,sources,denominator,*,level):
            sources=tuple(sources)
            try:super().__init__(sources,denominator,level=level)
            except ValueError as error:
                if str(error)!='DISJOINT_WINNER_INTERIORS':raise
                own=sources[-1].cell_id
                if self.priority[-1].cell_id!=own:raise ValueError('CONTINUATION_REQUIRES_EXACT_HIGHER_PLUS_OWN_RANKPREFIX')
                residuals=[]
                for i,a in enumerate(self.priority):
                    for b in self.priority[i+1:]:
                        g=self.masks[a.cell_id].intersection(self.masks[b.cell_id])
                        if g.area>0:residuals.append((a.cell_id,b.cell_id,g))
                higher=unary_union([self.coverage[s.cell_id]for s in self.priority[:-1]])
                ownhigher=self.masks[own].intersection(higher)
                self.ownerConstructionContinuation=dict(own=own,residuals=residuals,ownHigherResidual=ownhigher,
                    masksUnchanged=True,origin=self.origin,trigger='DISJOINT_WINNER_INTERIORS')
    class Core:
        def __getattr__(self,name):return getattr(original_core,name)
    out=Core();out.GeometryWinner=GeometryWinner;return out

class OwnerMaskService:
    """Drop-in API; frozen service cache plus per-caller owner-scope proof."""
    def __init__(self,sources,own_denominator_function,clip_core):
        self.service=frozen.OwnerMaskService(sources,own_denominator_function,construction_core(clip_core))

    def __getattr__(self,name):return getattr(self.service,name)

    def for_owner(self,cellId,requestedLevel,completeOwnerBBox):
        clipper,receipt=self.service.for_owner(cellId,requestedLevel,completeOwnerBBox)
        if clipper is None:return None,receipt
        e=getattr(clipper,'ownerConstructionContinuation',None)
        if e is None:return clipper,receipt
        if e['own']!=cellId:raise ValueError('CONTINUATION_OWNER_CELL_BINDING')
        b=tuple(completeOwnerBBox);padded=(max(0,b[0]-2),max(0,b[1]-2),min(Q,b[2]+2),min(Q,b[3]+2))
        ox,oy=clipper.origin;region=box(padded[0]-ox,padded[1]-oy,padded[2]-ox,padded[3]-oy)
        ownregion=clipper.masks[cellId].intersection(region)
        ownhigher=e['ownHigherResidual'].intersection(region).area
        pairs=[];unsafe=ownhigher>0
        for a,b,g in e['residuals']:
            in_scope=g.intersection(region)
            # Unused higher-vs-higher masks cannot invalidate own geometry.
            # Where own participates, the whole padded bbox must be clear.
            relevant=in_scope.area if cellId in(a,b)else in_scope.intersection(ownregion).area
            pairs.append(dict(sourceA=a,sourceB=b,globalResidualAreaRootUnits2=g.area,
                globalBBoxLocal=list(g.bounds),areaInsidePaddedOwnerBBox=in_scope.area,
                areaRelevantToActualOwnDrawingMask=relevant,
                pairIncludesOwn=cellId in(a,b)))
            unsafe=unsafe or relevant>0
        proof=dict(policy='own-mask-scope-global-pairwise-continuation/1',trigger=e['trigger'],ownCell=cellId,
            requestedLevel=requestedLevel,completeOwnerBBox=list(completeOwnerBBox),paddedOwnerBBox=list(padded),rootUnitPad=2,
            ownVsAuthenticHigherCoverageResidualInScopeRootUnits2=ownhigher,residualPairs=pairs,
            sameFrozenConstructedMasksUsed=True,noToleranceOrRepair=True,
            cacheHitStillFreshlyValidated=receipt.get('cacheHit',False),globalPartitionAdmission=False,
            exactOwnScopePassed=not unsafe,nativePixelOrRootU32GeometryChange=False)
        receipt=dict(receipt,constructionContinuation=proof)
        if unsafe:raise ScopeConstructionError(receipt)
        return clipper,receipt
