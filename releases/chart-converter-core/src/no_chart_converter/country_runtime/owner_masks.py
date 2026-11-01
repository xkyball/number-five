"""Own rank-prefix coverage service. No file I/O or source/global repair.

Returned masks are reusable GEOS closures, with local omission authority in
the per-call receipt. Caller MUST invoke for_owner for every complete owner
bbox and preserve that receipt; a cached clipper is not globally authoritative
where an original ring/source was omitted. Pixel/end-point claims unchanged.
"""

import hashlib, struct

from fractions import Fraction

from shapely.geometry import Polygon,LineString,Point,box

from shapely.ops import unary_union

from shapely.validation import explain_validity

Q=2147483649

def need(ok,label):
    if not ok:raise ValueError(label)

def ring_bounds(r):return min(x for x,y in r),min(y for x,y in r),max(x for x,y in r),max(y for x,y in r)

def disjoint(a,b):return a[2]<b[0]or a[0]>b[2]or a[3]<b[1]or a[1]>b[3]

def bbox_geometry(b):
    if b[0]==b[2]and b[1]==b[3]:return Point(b[0],b[1])
    if b[0]==b[2]or b[1]==b[3]:return LineString([(b[0],b[1]),(b[2],b[3])])
    return box(*b)

class InvalidSourceMaskError(ValueError):
    def __init__(self,receipt):
        self.receipt=receipt
        super().__init__('RELEVANT_INVALID_SOURCE_MASK:'+','.join(r['ringId']for r in receipt['unresolvedRings']))

class OwnerMaskService:
    def __init__(self,sources,own_denominator_function,clip_core):
        self.sources=tuple(sources);self.by_id={s.cell_id:s for s in self.sources}
        need(self.sources and len(self.by_id)==len(self.sources),'UNIQUE_NONEMPTY_SOURCE_IDENTITIES')
        self.denominator=own_denominator_function;self.core=clip_core
        self.rings={};self.domains={};self.covered_upper={};self.clipper_cache={};self.rank_cache={};self.dominance_cache={}
        for s in self.sources:
            need(type(s.native_scale)is int and s.native_scale>0,'AUTHENTIC_NATIVE_SCALE')
            entries=[]
            for kind,rr in [('covered',s.covered),('excluded',s.excluded)]:
                for i,r in enumerate(rr):
                    need(len(r)>=4 and r[0]==r[-1]and all(type(v)is int and 0<=v<=Q for p in r for v in p),'COMPLETE_INTEGER_ROOT_RING')
                    g=Polygon(r)
                    entries.append(dict(kind=kind,index=i,ringId=s.cell_id+':'+kind+':'+str(i),ring=r,geometry=g,
                        bounds=ring_bounds(r),valid=g.is_valid,reason=explain_validity(g),
                        sha256=hashlib.sha256(b''.join(struct.pack('<II',*p)for p in r)).hexdigest()))
            self.rings[s.cell_id]=entries
            # A bad exclusion can only REMOVE from covered. An invalid covered
            # ring is used only as its enclosing BBox upper bound for proving
            # that earlier valid source coverage swallows all possible effect.
            uppers=[e['geometry']if e['valid']else box(*e['bounds'])for e in entries if e['kind']=='covered']
            self.covered_upper[s.cell_id]=unary_union(uppers)
            if all(e['valid']for e in entries):
                g=self._domain(entries);need(g.is_valid,'INVALID_DERIVED_VALID_SOURCE_DOMAIN');self.domains[s.cell_id]=g

    @staticmethod
    def _domain(entries):
        return unary_union([e['geometry']for e in entries if e['kind']=='covered']).difference(
            unary_union([e['geometry']for e in entries if e['kind']=='excluded']))

    def rank(self,level):
        need(type(level)is int and 0<=level<=29,'REQUESTED_VIEW_LEVEL')
        if level not in self.rank_cache:
            d=self.denominator(level);need(isinstance(d,Fraction)and d>0,'EXACT_OWN_POLICY_DENOMINATOR')
            rank=tuple(sorted(self.sources,key=lambda s:(0,-s.native_scale,s.cell_id)if s.native_scale<=d else(1,s.native_scale,s.cell_id)))
            self.rank_cache[level]=(d,rank)
        return self.rank_cache[level]

    def _ring_receipt(self,cid,e,reason):
        return dict(cellId=cid,ringId=e['ringId'],kind=e['kind'],ringOrdinal=e['index'],originalPointSHA256=e['sha256'],
            bboxRootQ=list(e['bounds']),originalValid=e['valid'],originalValidityReason=e['reason'],reason=reason)

    def for_owner(self,cellId,requestedLevel,completeOwnerBBox):
        need(cellId in self.by_id,'BOUND_OWN_CELL')
        b=tuple(completeOwnerBBox)
        need(len(b)==4 and all(type(v)is int and 0<=v<=Q for v in b)and b[0]<=b[2]and b[1]<=b[3],'COMPLETE_INTEGER_OWNER_BBOX')
        region=bbox_geometry(b);d,rank=self.rank(requestedLevel);own_at=next(i for i,s in enumerate(rank)if s.cell_id==cellId)
        prefix=rank[:own_at+1]
        receipt=dict(format='no5-owner-scoped-source-rank-mask/1',ownCell=cellId,requestedLevel=requestedLevel,
            ownerBBox=list(b),denominatorExact=str(d),globalRank=[s.cell_id for s in rank],prefix=[s.cell_id for s in prefix],
            skippedLowerPriority=[s.cell_id for s in rank[own_at+1:]],ringOmissions=[],sourceOmissions=[],unresolvedRings=[],
            globalOriginalCoverageChanged=False,globalValidityOrPixelClaim=False,
            boundaryScope='GEOS coverage closures as frozen clipper; source-point endpoint/raster ownership not newly admitted')
        # A purely VALID higher union is enough to remove the whole owner,
        # without asking GEOS to evaluate any original bad own exclusion.
        key=(requestedLevel,cellId)
        if key not in self.dominance_cache:
            valid_higher=[s.cell_id for s in prefix[:-1]if s.cell_id in self.domains]
            self.dominance_cache[key]=(valid_higher,unary_union([self.domains[c]for c in valid_higher]))
        valid_higher,global_earlier=self.dominance_cache[key]
        if not global_earlier.is_empty and global_earlier.covers(region):
            receipt.update(disposition='REMOVE_COMPLETE_OWNER_VALID_HIGHER_DOMINANCE',dominanceSourceIDs=valid_higher,
                actualCompleteOwnerBBoxCoveredByValidHigherUnion=True,remainingBBoxAreaRootUnits2=0,clipperCreated=False)
            return None,receipt
        selected=[];earlier=Polygon();cache_rows=[]
        for s in prefix:
            entries=self.rings[s.cell_id];bad=[e for e in entries if not e['valid']]
            remaining=[e for e in entries if e['valid']or not disjoint(e['bounds'],b)]
            removed=[e for e in bad if disjoint(e['bounds'],b)]
            for e in removed:receipt['ringOmissions'].append(self._ring_receipt(s.cell_id,e,'EXACT_COMPLETE_RING_AABB_DISJOINT_FROM_OWNER'))
            relevant_bad=[e for e in remaining if not e['valid']]
            if relevant_bad:
                effect=self.covered_upper[s.cell_id].intersection(region)
                if effect.is_empty or(not earlier.is_empty and earlier.covers(effect)):
                    rr=dict(cellId=s.cell_id,reason='ALL_POSSIBLE_COVERED_SOURCE_EFFECT_WITHIN_OWNER_SWALLOWED_BY_VALID_EARLIER_COVERAGE',
                        sourceCoveredUpperBound='union authentic valid covered polygons; enclosingBBox only for invalid covered rings',
                        possibleEffectBBox=list(effect.bounds)if not effect.is_empty else None,
                        possibleEffectAreaRootUnits2=effect.area,validEarlierSourceIDs=[x.cell_id for x in selected],
                        originalInvalidRings=[self._ring_receipt(s.cell_id,e,'SOURCE_EFFECT_PROVEN_DOMINATED')for e in relevant_bad])
                    receipt['sourceOmissions'].append(rr);cache_rows.append((s.cell_id,'SOURCE_DOMINATED'))
                    if s.cell_id==cellId:
                        receipt.update(disposition='REMOVE_OWN_SOURCE_EFFECT_VALID_EARLIER_DOMINANCE',clipperCreated=False)
                        return None,receipt
                    continue
                receipt['unresolvedRings']=[self._ring_receipt(s.cell_id,e,'INTERSECTS_OWNER_AND_NOT_DOMINATED')for e in relevant_bad]
                receipt.update(disposition='REFUSE_RELEVANT_INVALID_SOURCE_MASK',clipperCreated=False)
                raise InvalidSourceMaskError(receipt)
            covered=tuple(e['ring']for e in remaining if e['kind']=='covered')
            excluded=tuple(e['ring']for e in remaining if e['kind']=='excluded')
            if not covered:
                receipt['sourceOmissions'].append(dict(cellId=s.cell_id,reason='ALL_COVERED_RINGS_EXACTLY_DISJOINT_FROM_OWNER'))
                cache_rows.append((s.cell_id,'NO_COVERED_EFFECT'))
                if s.cell_id==cellId:
                    receipt.update(disposition='REMOVE_OWN_SOURCE_NO_COVERED_EFFECT',clipperCreated=False);return None,receipt
                continue
            candidate=type(s)(s.cell_id,s.native_scale,covered,excluded)
            localdomain=self._domain(remaining)
            need(localdomain.is_valid,'INVALID_LOCAL_DOMAIN_NO_REPAIR')
            if localdomain.is_empty:
                cache_rows.append((s.cell_id,'EMPTY_COVERAGE'))
                if s.cell_id==cellId:
                    receipt.update(disposition='REMOVE_OWN_EMPTY_COVERAGE',clipperCreated=False);return None,receipt
                continue
            selected.append(candidate);earlier=earlier.union(localdomain)
            cache_rows.append((s.cell_id,tuple((e['kind'],e['index'])for e in removed)))
        need(selected and selected[-1].cell_id==cellId,'OWN_SOURCE_PRESENT_IN_VALID_PREFIX')
        ck=(requestedLevel,tuple(cache_rows))
        reused=ck in self.clipper_cache
        if not reused:self.clipper_cache[ck]=self.core.GeometryWinner(selected,d,level=requestedLevel)
        clipper=self.clipper_cache[ck]
        receipt.update(disposition='VALID_OWNER_SCOPED_PREFIX_CLIPPER',clipperCreated=not reused,cacheHit=reused,
            retainedPrefix=[s.cell_id for s in selected],clipperMasksNotForcedToOwnerBBox=True,
            receiptScopeRequiredForEveryCallerOwner=True,
            cacheKeySHA256=hashlib.sha256(repr(ck).encode()).hexdigest())
        return clipper,receipt
