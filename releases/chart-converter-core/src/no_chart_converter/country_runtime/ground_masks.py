from . import owner_masks as old, source_walk as walk, scoped_v2 as final
from .diagnostic_area import intersection_area

"""Own ground source winner from activated Kind3 fill availability.

Availability is actual complete fill-owner geometry, never cell coverage,
an ownerBBox, Kind8, or unresolved instructions. Point/SOUNDG is unrelated.
No file I/O, native execution, geometry repair, tolerance or invented fill.
"""

from pathlib import Path

import hashlib, json, struct, sys

from shapely.affinity import translate

from shapely.geometry import Polygon,LineString,box

from shapely.ops import unary_union

Q=2147483649

LOW24=(1<<24)-1

def need(ok,label):
    if not ok:raise ValueError(label)

def gsha(g):return hashlib.sha256(g.wkb).hexdigest()

def decode_shapes(co,ix,rec):
    need(len(rec)==32 and rec[28]in(2,3),'BOUND_KIND2_3_OWNER')
    n=struct.unpack_from('<I',ix)[0]
    need(n>0 and len(ix)==4+4*n and struct.unpack_from('<I',rec,8)[0]==0,'COMPLETE_STANDALONE_OWNER_INDEX')
    exteriors=[];interiors=[];lines=[]
    for word,in struct.iter_unpack('<I',ix[4:]):
        at=word&LOW24;role=word>>24
        need(at+4<=len(co),'ELEMENT_HEADER_RANGE')
        h=struct.unpack_from('<I',co,at)[0];count=h&LOW24
        need(h>>24==1 and count>1 and at+4+8*count<=len(co),'GENUINE_ROOT1_ENUM2_XY')
        pts=list(struct.iter_unpack('<II',co[at+4:at+4+8*count]))
        need(all(0<=v<=Q for p in pts for v in p),'ROOT_U32_DOMAIN')
        need(role&3 in(0,1),'BOUND_REVERSE');pts=pts[::-1]if role&3==1 else pts
        if rec[28]==2:
            need(role in(16,17),'BOUND_COALNE_ROLES');lines.append(LineString(pts))
        else:
            need(role in(8,9,24,25,20,21) and pts[0]==pts[-1],'BOUND_COMPLETE_KIND3_RING_ROLES')
            g=Polygon(pts);need(g.is_valid and g.area>0,'VALID_NONZERO_AUTHENTIC_FILL_RING')
            (exteriors if role&12==8 else interiors).append(g)
    if rec[28]==2:return unary_union(lines)
    need(exteriors,'COMPLETE_KIND3_HAS_EXTERIOR')
    exterior=unary_union(exteriors);inside=unary_union(interiors)
    need(inside.difference(exterior).area==0,'COMPLETE_INTERIOR_WITHIN_EXTERIOR_UNION')
    g=exterior.difference(inside);need(g.is_valid and g.area>0,'VALID_COMPLETE_ACTIVATED_FILL_OWNER')
    return g

class GroundOwnerMaskService(old.OwnerMaskService):
    def __init__(self,sources,own_denominator_function,clip_core,*,fill_availability,fill_availability_bindings):
        super().__init__(sources,own_denominator_function,clip_core)
        need(set(fill_availability)==set(self.by_id)==set(fill_availability_bindings),'COMPLETE_EXPLICIT_SOURCE_FILL_AVAILABILITY')
        points=[p for s in self.sources for r in s.covered+s.excluded for p in r]
        self.origin=(min(x for x,y in points),min(y for x,y in points))
        self.source_area_semantics=[]
        for cid,entries in self.rings.items():
            for entry in entries:
                try:
                    decoded=walk.decode_coverage_ring(entry['ring'],cell_id=cid,role=entry['kind'],ordinal=entry['index'])
                except walk.UnresolvedCoverageArea:
                    # Existing owner-scoped disjoint/dominance proofs still
                    # decide whether an unresolved ring can affect an owner.
                    continue
                entry['geometry']=decoded['areaGeometry'];entry['valid']=True
                entry['areaSemanticsReceipt']=decoded['receipt']
                self.source_area_semantics.append(decoded['receipt'])
        self.availability={};self.availability_receipts={};self.local_availability={};self.covered_upper_local={}
        for cid,g in fill_availability.items():
            need(g.is_valid and(g.is_empty or g.geom_type in('Polygon','MultiPolygon')),'VALID_EXPLICIT_ACTIVE_FILL_GEOMETRY')
            need(g.is_empty or all(0<=v<=Q for v in g.bounds),'ACTIVE_FILL_ROOT_WORLD_DOMAIN')
            binding=fill_availability_bindings[cid]
            need(binding['cellId']==cid and binding['fillAvailabilityWKB']['sha256']==gsha(g)
                 and binding['fillAvailabilityWKB']['bytes']==len(g.wkb),'ACTIVE_FILL_CELL_GEOMETRY_PIN_JOIN')
            need(binding['kind8OrUnresolvedAdmission']is False and binding['coverageOrBBoxNotUsedAsFill']is True,'AUTHENTIC_ACTIVATED_FILL_ONLY_BINDING')
            need(type(binding['ownersKind3'])is int and binding['ownersKind3']>=0,'ACTUAL_ACTIVATED_OWNER_COUNT')
            self.availability_receipts[cid]={k:binding[k]for k in('cellId','fillAvailabilityWKB','ownersKind3',
                'inputGeometryPin','ownerSetDigest','manifestPin','kind8OrUnresolvedAdmission','coverageOrBBoxNotUsedAsFill')if k in binding}
            self.availability[cid]=g
            self.local_availability[cid]=translate(g,-self.origin[0],-self.origin[1])
            upper=[]
            for e in self.rings[cid]:
                if e['kind']!='covered':continue
                upper.append(translate(e['geometry'],-self.origin[0],-self.origin[1])if e['valid']else
                             box(e['bounds'][0]-self.origin[0],e['bounds'][1]-self.origin[1],e['bounds'][2]-self.origin[0],e['bounds'][3]-self.origin[1]))
            self.covered_upper_local[cid]=unary_union(upper)
        self.ground_domain_cache={};self.ground_clipper_cache={};self.higher_union_cache={():Polygon()}

    def local_domain(self,entries):
        covered=[];excluded=[]
        for e in entries:
            g=translate(e['geometry'],-self.origin[0],-self.origin[1])
            (covered if e['kind']=='covered'else excluded).append(g)
        return unary_union(covered).difference(unary_union(excluded))

    def for_owner(self,cellId,requestedLevel,completeOwnerBBox,*,owner_kind=3):
        need(cellId in self.by_id and owner_kind in(2,3),'BOUND_GROUND_SOURCE_KIND')
        b=tuple(completeOwnerBBox)
        need(len(b)==4 and all(type(v)is int and 0<=v<=Q for v in b)and b[0]<=b[2]and b[1]<=b[3],'COMPLETE_INTEGER_OWNER_BBOX')
        padded=(max(0,b[0]-2),max(0,b[1]-2),min(Q,b[2]+2),min(Q,b[3]+2))
        region=old.bbox_geometry((padded[0]-self.origin[0],padded[1]-self.origin[1],padded[2]-self.origin[0],padded[3]-self.origin[1]))
        d,rank=self.rank(requestedLevel);at=next(i for i,s in enumerate(rank)if s.cell_id==cellId)
        prefix=rank[:at+1];earlier=Polygon();selected=[];omissions=[];cache_rows=[];domains={};authentic={}
        receipt=dict(format='no5-owner-active-ground-availability-mask/1',ownCell=cellId,requestedLevel=requestedLevel,
            ownerKind=owner_kind,ownerBBox=list(b),denominatorExact=str(d),globalRank=[s.cell_id for s in rank],
            prefix=[s.cell_id for s in prefix],ringOmissions=[],sourceOmissions=[],unresolvedRings=[],
            availabilityPolicy='higher Coverage intersect actual activated Kind3Fill; Kind3 own same; Kind2 own authentic Coverage',
            pointSoundingWinnerChanged=False,globalCoverageRepair=False,kind8FillAdmission=False)
        for s in prefix:
            cid=s.cell_id;isown=cid==cellId;availability=self.local_availability[cid]
            bad_source=any(not e['valid']for e in self.rings[cid])
            if(not isown or owner_kind==3)and(availability.is_empty or bad_source and not availability.intersects(region)):
                receipt['sourceOmissions'].append(dict(cellId=cid,reason='NO_ACTIVATED_KIND3_FILL_EFFECT_WITHIN_COMPLETE_OWNER',availabilityRootWkbSHA256=gsha(self.availability[cid])))
                cache_rows.append((cid,'NO_ACTIVE_FILL_EFFECT'))
                if isown:receipt.update(disposition='EMPTY_OWN_ACTIVATED_FILL',clipperCreated=False);return None,receipt
                continue
            entries=self.rings[cid];removed=[e for e in entries if not e['valid']and old.disjoint(e['bounds'],padded)]
            remaining=[e for e in entries if e not in removed]
            for e in removed:receipt['ringOmissions'].append(self._ring_receipt(cid,e,'EXACT_INVALID_RING_AABB_DISJOINT_FROM_OWNER'))
            bad=[e for e in remaining if not e['valid']]
            if bad:
                effect=self.covered_upper_local[cid].intersection(region)
                if not isown or owner_kind==3:effect=effect.intersection(availability)
                if effect.is_empty or(not earlier.is_empty and earlier.covers(effect)):
                    receipt['sourceOmissions'].append(dict(cellId=cid,reason='ALL_POSSIBLE_ACTIVE_FILL_EFFECT_DOMINATED_BY_VALID_EARLIER_ACTIVE_FILL',
                        possibleEffectAreaRootUnits2=effect.area,originalInvalidRings=[self._ring_receipt(cid,e,'ACTIVE_SOURCE_EFFECT_DOMINATED')for e in bad]))
                    cache_rows.append((cid,'INVALID_ACTIVE_SOURCE_EFFECT_DOMINATED'))
                    if isown:receipt.update(disposition='REMOVE_COMPLETE_OWN_ACTIVE_EFFECT',clipperCreated=False);return None,receipt
                    continue
                receipt['unresolvedRings']=[self._ring_receipt(cid,e,'RELEVANT_TO_OWNER_AND_NOT_ACTIVE_FILL_DOMINATED')for e in bad]
                raise old.InvalidSourceMaskError(receipt)
            key=(cid,tuple((e['kind'],e['index'])for e in removed),isown and owner_kind==2)
            if key not in self.ground_domain_cache:
                coverage=self.local_domain(remaining);need(coverage.is_valid,'VALID_AUTHENTIC_LOCAL_COVERAGE')
                domain=coverage if isown and owner_kind==2 else coverage.intersection(availability)
                need(domain.is_valid,'VALID_AUTHENTIC_ACTIVE_FILL_DOMAIN')
                self.ground_domain_cache[key]=(coverage,domain)
            coverage,domain=self.ground_domain_cache[key]
            if domain.is_empty:
                cache_rows.append((cid,'EMPTY_AUTHENTIC_ACTIVE_DOMAIN'))
                if isown:receipt.update(disposition='REMOVE_EMPTY_OWN_ACTIVE_DOMAIN',clipperCreated=False);return None,receipt
                continue
            selected.append(s);authentic[cid]=coverage;domains[cid]=domain
            cache_rows.append(key)
            if not isown:
                uk=tuple(cache_rows)
                if uk not in self.higher_union_cache:self.higher_union_cache[uk]=earlier.union(domain)
                earlier=self.higher_union_cache[uk]
        need(selected and selected[-1].cell_id==cellId,'OWN_ACTIVE_SOURCE_IN_PREFIX')
        ck=(requestedLevel,owner_kind,tuple(cache_rows));reused=ck in self.ground_clipper_cache
        if not reused:
            ownmask=domains[cellId].difference(earlier);need(ownmask.is_valid,'VALID_OWN_ACTIVE_MASK')
            clipper=object.__new__(self.core.GeometryWinner)
            clipper.origin=self.origin;clipper.level=requestedLevel;clipper.sources=tuple(selected);clipper.priority=tuple(selected);clipper.denominator=d
            clipper.coverage=dict(domains)
            clipper.masks={cellId:ownmask}
            higher=earlier;own=domains[cellId]
            clipper.ownerAuthenticSemantics=dict(own=cellId,higher=higher,higherBoundary=higher.boundary,ownCoverage=own,
                direct=clipper.masks[cellId],higherCells=[s.cell_id for s in selected[:-1]],
                authenticSourceCoverage=authentic[cellId],groundAvailability=True)
            self.ground_clipper_cache[ck]=clipper
        clipper=self.ground_clipper_cache[ck]
        ownmask=clipper.masks[cellId]
        if not ownmask.intersects(region):receipt.update(disposition='NO_OWN_ACTIVE_WINNER_EFFECT',clipperCreated=not reused,cacheHit=reused);return None,receipt
        clipper.ownerScopeBBox=b
        clipper.omittedExcludedRings=[r for r in receipt['ringOmissions']if r['kind']=='excluded']
        padded=(max(0,b[0]-2),max(0,b[1]-2),min(Q,b[2]+2),min(Q,b[3]+2))
        local=box(padded[0]-self.origin[0],padded[1]-self.origin[1],padded[2]-self.origin[0],padded[3]-self.origin[1])
        e=clipper.ownerAuthenticSemantics;mask=clipper.masks[cellId]
        overlap=intersection_area(mask.intersection(local),e['higher'])
        outside=mask.intersection(local).difference(e['ownCoverage']).area
        # The constructed mask is authoritative ownDomain.difference(higher).
        # Additional GEOS reoverlays may have positive Float64 residuals. Keep
        # them as diagnostics; the FINAL INTEGER OWNER must still pass exact
        # authentic own/higher containment, with no numerical tolerance.
        need(mask.is_valid and e['ownCoverage'].is_valid and e['higher'].is_valid,'VALID_ACTUAL_GROUND_CONSTRUCTION_DOMAINS')
        need(mask.wkb==e['direct'].wkb,'EXACT_CACHED_GROUND_CONSTRUCTION_MASK_IDENTITY')
        receipt.update(disposition='VALID_ACTIVE_GROUND_AVAILABILITY_CLIPPER',cacheHit=reused,clipperCreated=not reused,
            availabilityBindings={s.cell_id:self.availability_receipts[s.cell_id]for s in selected},
            retainedPrefix=[s.cell_id for s in selected],translatedRootOrigin=list(self.origin),
            ownMaskTranslatedWkbSHA256=gsha(ownmask),higherActiveFillTranslatedWkbSHA256=gsha(earlier),
            scopeProof=dict(paddedOwnerBBox=list(padded),ownOutsideDomainArea=outside,ownHigherActiveFillOverlapArea=overlap,
                            positiveReoverlayResidualsDiagnosticOnly=True,maskConstructionIdentityExact=True,
                            sourceAreaSemantics=self.source_area_semantics,freshEvenOnCacheHit=True,finalQuantizedOwnerValidationRequired=True),receiptScopeRequiredForEveryCallerOwner=True)
        return clipper,receipt

def validate_final_owner(clipper,coordinates,indices,record,*,cell_id):
    r=final.validate_final_owner(clipper,coordinates,indices,record,cell_id=cell_id)
    return dict(r,authoritativeHigherDomain='authenticCoverage intersect actualActivatedKind3FillUnion',
                authoritativeOwnDomain='Kind3:authenticCoverage intersect ownActiveFillUnion; Kind2:authenticCoverage',
                pointSoundingPolicyChanged=False,coverageNeverInventedAsFill=True)

OwnerMaskService=GroundOwnerMaskService
