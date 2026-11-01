from . import source_winner as SW, ground_masks as GROUND, overlay_masks as DETAIL

"""Productive V15: proven V7 scale selection across ALL source layers.

The original tile selector is retained separately in zoom_quilt.py as an
executed reference. This product policy deliberately keeps a real coarser
background during overzoom, so it does not author millions of empty tiles.
No original AvNav/a97 selector or pixel parity is claimed by this module.
"""

from pathlib import Path

import hashlib

import json

import sys

from shapely.affinity import translate

from shapely.geometry import Polygon,box

from shapely.ops import unary_union

Q=2147483649

POLICY='own-stable-compilation-scale-authentic-source-quilt-v15/1'

TARGET_SCHEDULE='frozen-v7-sparse-source-owner-memberships-plus-scale-and-scamin-epochs'

def need(v,s):
    if not v:raise ValueError(s)

class PointWinner(SW.SourceWinner):
    def __init__(self,sources,own_denominator_function=None,clip_core=None,*,tile=None):
        super().__init__(sources);self.tile=tile
    def for_tile(self,level,x,y):
        need(type(level)is int and 0<=level<=21 and all(type(v)is int and 0<=v<1<<level for v in (x,y)),'PRODUCT_RX5_TILE')
        return PointWinner(self.sources,tile=(level,x,y))
    def choose(self,point,level):
        if self.tile is not None:need(self.tile[0]==level,'BOUND_PRODUCT_POINT_VIEW_LEVEL')
        # V7 SourceWinner is already exact coverage + native-scale selection.
        # It has a genuine coarser-source fallback and is independent of the
        # incidental 50px bbox heuristic in the separate original reference.
        return super().choose(point,level)

class QuiltService:
    def __init__(self,sources,own_denominator_function,clip_core,*,fill_availability=None,fill_availability_bindings=None,layer=None):
        self.sources=tuple(sources);self.by_id={s.cell_id:s for s in sources}
        self.denominator=own_denominator_function;self.core=clip_core
        self.layer=layer or('ground'if fill_availability is not None else'detail')
        need(self.layer in ('ground','detail'),'PRODUCT_LAYER')
        if self.layer=='ground':
            self.delegate=GROUND.OwnerMaskService(sources,own_denominator_function,clip_core,
                fill_availability=fill_availability,fill_availability_bindings=fill_availability_bindings)
        else:self.delegate=DETAIL.OwnerMaskService(sources,own_denominator_function,clip_core)
        self.origin=self.delegate.origin;self.source_area_semantics=self.delegate.source_area_semantics
        self.epoch_levels=tuple([0]+[l for l in range(1,22)if self._rank_ids(l)!=self._rank_ids(l-1)])
        self.target_schedule=TARGET_SCHEDULE;self.calls=0;self._changes={}

    def _rank_ids(self,level):return tuple(s.cell_id for s in self.delegate.rank(level)[1])
    def rank(self,level):return self.delegate.rank(level)

    def mode_for_tile(self,cellId,level,x,y):
        need(cellId in self.by_id and type(level)is int and 0<=level<=21,'BOUND_PRODUCT_SOURCE_LEVEL')
        need(all(type(v)is int and 0<=v<1<<level for v in (x,y)),'BOUND_PRODUCT_TILE_XY')
        # ABI value means full source programme admission after the mask/point
        # decision, not membership in an original AvNav USED result. There is
        # no SOFT source mode in the stable productive scale policy.
        return 'USED'

    def select_tile(self,level,x,y):
        need(type(level)is int and 0<=level<=21 and all(type(v)is int and 0<=v<1<<level for v in (x,y)),'BOUND_PRODUCT_TILE')
        scale=1<<level;pad=50*Q/(256*scale)
        region=box(x*Q/scale-pad,y*Q/scale-pad,(x+1)*Q/scale+pad,(y+1)*Q/scale+pad)
        _,rank=self.rank(level);candidates=[]
        for s in reversed(rank):
            # Diagnostic candidates only. Actual owner admission is decided by
            # the same point winner or complete geometry mask at this level.
            bbox=(min(p[0]for r in s.covered for p in r),min(p[1]for r in s.covered for p in r),
                max(p[0]for r in s.covered for p in r),max(p[1]for r in s.covered for p in r))
            if box(*bbox).intersects(region):candidates.append(dict(cellId=s.cell_id,nativeScale=s.native_scale,kind='USED'))
        return dict(policy=POLICY,requestedLevel=level,selected=candidates,originalSelectorExecuted=False,
            selectionMeaning='own scale-quilt source candidates; actual point and geometry masks decide emitted owners',
            ownDenominatorExact=str(self.denominator(level)),stableAuthenticCoarseFallback=True,
            candidateSearchHaloPixels=50,originalAvNavModeClaim=False)

    def for_owner(self,cellId,requestedLevel,completeOwnerBBox,*,owner_kind=3):
        clipper,scope=self.delegate.for_owner(cellId,requestedLevel,completeOwnerBBox,owner_kind=owner_kind)
        self.calls+=1
        return clipper,dict(scope,productiveZoomQuiltPolicy=POLICY,stableAuthenticCoarseFallback=True,
            allGroundDetailAndPointUseSameScaleRank=True,originalSelectorParityClaim=False,
            sourceGeometryAndCompleteQueryPreservedByCaller=True,targetSchedule=TARGET_SCHEDULE)

    def selection_receipt(self,level=None):
        return dict(policy=POLICY,layer=self.layer,epochLevels=list(self.epoch_levels),targetSchedule=TARGET_SCHEDULE,
            selectionRule='At each real covered position choose largest native denominator <= own display denominator; if all are coarser, choose smallest native denominator; ties by cell identity.',
            ownDenominator='existing exact SourcePPMM3 compiler scale, no measured a97 DPI claim',
            geometry='authentic covered minus excluded masks, ground higher priority intersect actual activated Kind3 fill union',
            allGroundDetailPointSameNativeScaleOrdering=True,coverageHolesRetained=True,
            authenticCoarseFallbackRetainedDuringOverzoom=True,originalSelectorExecuted=False,
            originalSelectorReference='original-selector-reference-not-required-by-productive-policy',originalTileSelectorCoverageBBoxParityClaim=False,
            softUnderProductMode=False,ownerCalls=self.calls,
            actualRank={str(l):list(self._rank_ids(l))for l in (self.epoch_levels if level is None else[level])},
            nativePixelProof=False,level21='normal existing own compiler scale state; not claimed original AvNav zoom21')

    def selection_change_regions(self,level):
        """Actual mask delta diagnostic, NEVER a dense scheduling instruction.

        Product writer must use target_schedule (existing sparse source-owner
        memberships and epoch payloads). Expanding any large mask to every
        level20 leaf would recreate the rejected million-blank-tile design.
        """
        if level in self._changes:return self._changes[level]
        if level not in self.epoch_levels:self._changes[level]=[];return []
        pieces=[];ox,oy=self.origin
        for source in self.sources:
            bbox=(min(p[0]for r in source.covered for p in r),min(p[1]for r in source.covered for p in r),
                  max(p[0]for r in source.covered for p in r),max(p[1]for r in source.covered for p in r))
            for kind in (2,3):
                current,_=self.for_owner(source.cell_id,level,bbox,owner_kind=kind)
                prior,_=self.for_owner(source.cell_id,level-1,bbox,owner_kind=kind)if level else(None,None)
                a=current.masks[source.cell_id]if current is not None else Polygon()
                b=prior.masks[source.cell_id]if prior is not None else Polygon()
                d=a.symmetric_difference(b)
                if not d.is_empty:pieces.append(d)
        result=unary_union(pieces)
        self._changes[level]=[]if result.is_empty else[translate(result,ox,oy)]
        return self._changes[level]

validate_final_owner=GROUND.validate_final_owner
