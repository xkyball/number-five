"""Invented coverage: reuse GEOS work, preserving the original ordered fold."""
from fractions import Fraction
from types import SimpleNamespace

import pytest
from shapely.geometry import Polygon
from shapely.geometry.base import BaseGeometry

from no_chart_converter.country_runtime import overlay_masks, owner_masks, source_clip


def ring(x0, y0, x1, y1):
    return ((x0,y0),(x1,y0),(x1,y1),(x0,y1),(x0,y0))


def source(cid, scale, covered, excluded=()):
    # Mutable caller containers deliberately exercise the service snapshot.
    return SimpleNamespace(cell_id=cid,native_scale=scale,
        covered=[[list(p)for p in r]for r in covered],
        excluded=[[list(p)for p in r]for r in excluded])


def service(sources=None):
    if sources is None:
        sources=[source('A',300,[ring(0,0,40,100)]),
                 source('B',200,[ring(20,0,80,100)]),
                 source('C',100,[ring(0,0,120,120)])]
    return overlay_masks.OwnerMaskService(sources,
        lambda level:Fraction(1000 if level<15 else 250),source_clip)


def signature(result):
    clipper,receipt=result
    unchanged={k:v for k,v in receipt.items()if k not in('cacheHit','clipperCreated')}
    if clipper is None:
        return None,unchanged
    return (clipper.masks[receipt['ownCell']].wkb,
        clipper.ownerAuthenticSemantics['higher'].wkb,
        tuple((k,g.wkb)for k,g in clipper.coverage.items()),
        tuple(s.cell_id for s in clipper.sources)),unchanged


def union_spy(monkeypatch):
    real=BaseGeometry.union
    calls=[]
    def counted(a,b,*args,**kwargs):
        calls.append((a.wkb,b.wkb))
        return real(a,b,*args,**kwargs)
    monkeypatch.setattr(BaseGeometry,'union',counted)
    return calls


def test_many_owners_reuse_exact_ordered_prefix_and_fresh_scope_proof(monkeypatch):
    cold,warm=service(),service()
    calls=union_spy(monkeypatch)
    requests=[('C',14,(90,i,110,i+5))for i in range(10,40)]
    expected=[]
    for args in requests:
        cold.higher_union_cache.clear()
        cold.overlay_clipper_cache.clear()
        expected.append(signature(cold.for_owner(*args)))
    cold_calls=len(calls);calls.clear()
    actual=[]
    for n,args in enumerate(requests):
        result=warm.for_owner(*args)
        actual.append(signature(result))
        assert result[1]['cacheHit']==(n>0)
        assert result[1]['scopeProof']['freshEvenOnCacheHit']
        assert result[1]['ownerBBox']==list(args[2])
    assert actual==expected
    assert cold_calls==60 and len(calls)==2
    assert len(warm.higher_union_cache)==3  # Empty + A + ordered A,B.
    # Rows contain exact immutable domain bytes, not just a cell/hash shortcut.
    for key in warm.higher_union_cache:
        for domain_key,raw in key:
            assert raw==warm.domain_cache[domain_key].wkb


def test_prefixes_are_shared_across_own_cells_and_kinds(monkeypatch):
    actual=service();calls=union_spy(monkeypatch)
    _,r1=actual.for_owner('B',14,(50,20,70,30),owner_kind=2)
    assert len(calls)==1
    clipper,r2=actual.for_owner('C',14,(90,20,110,30),owner_kind=3)
    assert len(calls)==2  # A is already present; only A union B is new.
    for kind in(2,3):
        cold=service();expected=signature(cold.for_owner('C',14,(90,20,110,30),owner_kind=kind))
        assert signature(actual.for_owner('C',14,(90,20,110,30),owner_kind=kind))==expected
    assert r1['ownerKind']==2 and r2['ownerKind']==3
    assert clipper.ownerAuthenticSemantics['higherCells']==['A','B']


def test_rank_changes_use_actual_order_and_level_still_binds_clipper(monkeypatch):
    actual=service();calls=union_spy(monkeypatch)
    low=actual.for_owner('C',14,(90,20,110,30))
    high=actual.for_owner('C',15,(90,20,110,30))
    assert len(calls)==3  # A; A,B; then a distinct B prefix.
    assert low[1]['globalRank']==['A','B','C']
    assert high[1]['globalRank']==['B','C','A']
    assert high[0].level==15 and low[0].level==14
    assert high[0].denominator==250 and low[0].denominator==1000
    assert signature(high)==signature(service().for_owner('C',15,(90,20,110,30)))


def invalid_sources():
    # Genuine subgrid crossing: the existing area decoder leaves it invalid.
    bad=((60,60),(63,64),(60,64),(64,60),(60,60))
    return [source('A',300,[ring(0,0,100,100)]),
            source('B',200,[ring(50,50,150,150)],[bad]),
            source('C',100,[ring(0,0,200,200)])]


def test_omission_and_dominance_recomputed_before_cache_hits(monkeypatch):
    actual=service(invalid_sources());calls=union_spy(monkeypatch)
    # Bad exclusion is disjoint here, so source B participates.
    omitted=('C',14,(160,160,170,170))
    first=actual.for_owner(*omitted)
    assert first[0]is not None and len(first[1]['ringOmissions'])==1
    assert len(calls)==2
    assert signature(actual.for_owner(*omitted))==signature(first)
    assert len(calls)==2
    # The invalid exclusion is relevant, but the exact owner region is covered
    # by earlier A: B must now be omitted as a whole, not reuse its old domain.
    dominated=('C',14,(60,60,65,65))
    got=actual.for_owner(*dominated)
    assert got[0]is None
    assert got[1]['sourceOmissions'][0]['cellId']=='B'
    assert got[1]['ringOmissions']==[]
    assert signature(got)==signature(service(invalid_sources()).for_owner(*dominated))
    # A third bbox makes the same bad ring relevant without dominance. Cached
    # successes must never bypass the original refusal.
    with pytest.raises(owner_masks.InvalidSourceMaskError)as exc:
        actual.for_owner('C',14,(60,60,140,140))
    assert exc.value.receipt['unresolvedRings'][0]['cellId']=='B'
    assert signature(actual.for_owner(*omitted))==signature(first)


def test_dominated_source_marker_is_in_later_union_prefix():
    sources=invalid_sources()
    sources.insert(2,source('MID',150,[ring(160,0,180,200)]))
    actual=service(sources)
    actual.for_owner('C',14,(60,60,65,65))
    dominated=[key for key in actual.higher_union_cache
               if any(row[1]=='SOURCE_EFFECT_DOMINATED'for row in key)]
    assert len(dominated)==1
    assert dominated[0][1]==('B','SOURCE_EFFECT_DOMINATED',())
    assert dominated[0][2][0][0]=='MID'


def test_empty_domain_row_is_in_later_union_prefix():
    sources=[source('A',300,[ring(0,0,40,100)]),
             source('EMPTY',250,[ring(0,0,20,20)],[ring(0,0,20,20)]),
             source('B',200,[ring(20,0,80,100)]),
             source('C',100,[ring(0,0,120,120)])]
    actual=service(sources)
    result=actual.for_owner('C',14,(90,20,110,30))
    assert result[1]['retainedPrefix']==['A','B','C']
    full=max(actual.higher_union_cache,key=len)
    assert len(full)==3 and full[1][0][0]=='EMPTY'
    assert actual.domain_cache[full[1][0]].is_empty
    expected=Polygon()
    for cid in('A','B'):
        expected=expected.union(actual.domain_cache[(cid,())])
    assert result[0].ownerAuthenticSemantics['higher'].wkb==expected.wkb


def test_new_generation_and_mutable_input_cannot_reuse_stale_domains(monkeypatch):
    sources=[source('A',300,[ring(0,0,40,100)]),source('C',100,[ring(0,0,120,120)])]
    first=service(sources);calls=union_spy(monkeypatch)
    before=signature(first.for_owner('C',14,(90,20,110,30)))
    assert len(calls)==1
    sources[0].covered[0][1][0]=60;sources[0].covered[0][2][0]=60
    # Existing service keeps its copied source values; a fresh generation has
    # a new independent cache and computes the changed domain.
    assert signature(first.for_owner('C',14,(90,20,110,30)))==before
    assert len(calls)==1
    second=service(sources)
    after=signature(second.for_owner('C',14,(90,20,110,30)))
    assert len(calls)==2 and before!=after
    assert first.higher_union_cache is not second.higher_union_cache
