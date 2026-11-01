"""Invented coverage walks: original closed boundaries stay separate from fill."""
from fractions import Fraction
import hashlib

import pytest
from shapely.geometry import Point, Polygon

from no_chart_converter.country_runtime import source_walk as walk
from no_chart_converter.country_runtime.source_winner import CoverageSource
from no_chart_converter.country_runtime.ground_masks import GroundOwnerMaskService
from no_chart_converter.country_runtime.overlay_masks import OwnerMaskService

COVER=((0,0),(30,0),(30,30),(0,30),(0,0))
SPUR=((10,10),(20,10),(20,20),(15,20),(15,25),(15,20),(10,20),(10,10))


def _services(source):
    fill=Polygon(COVER);raw=fill.wkb
    binding={'cellId':source.cell_id,'fillAvailabilityWKB':{'sha256':hashlib.sha256(raw).hexdigest(),'bytes':len(raw)},
             'ownersKind3':1,'kind8OrUnresolvedAdmission':False,'coverageOrBBoxNotUsedAsFill':True}
    return (GroundOwnerMaskService([source],lambda _:Fraction(1000),None,
                fill_availability={source.cell_id:fill},fill_availability_bindings={source.cell_id:binding}),
            OwnerMaskService([source],lambda _:Fraction(1000),None))


@pytest.mark.parametrize('cell_id',['FRESH-CELL-X','OC-27-040003'])
def test_generic_compiler_accepts_supplied_simple_ring_without_historical_count(cell_id):
    ring=((10,10),(20,10),(20,20),(10,20),(10,10))
    decoded=walk.decode_coverage_ring(ring,cell_id=cell_id,role='excluded',ordinal=7)
    assert decoded['originalRing']==ring
    assert decoded['areaGeometry'].equals(Polygon(ring))
    assert not decoded['receipt']['historicalCellOrPointCountRequired']
    assert not decoded['receipt']['areaNormalizationApplied']
    source=CoverageSource(cell_id,1000,(COVER,),(ring,))
    for service in _services(source):
        assert service.sources[0].excluded==(ring,)
        assert all(e['valid']for e in service.rings[cell_id])


def test_zero_area_spike_boundary_stays_excluded_for_point_winner_but_not_fill():
    source=CoverageSource('FRESH-SPUR',1000,(COVER,),(SPUR,))
    decoded=walk.decode_coverage_ring(SPUR,cell_id=source.cell_id,role='excluded',ordinal=0)
    assert decoded['originalRing']==SPUR
    assert decoded['areaGeometry'].area==100
    spike_point=(15,23)
    assert not source.contains(spike_point)  # Original closed excluded boundary.
    assert not decoded['areaGeometry'].covers(Point(spike_point))
    assert decoded['receipt']['pointWinnerCoverageWalkUnchanged']
    assert not decoded['receipt']['areaMaskBoundaryEquivalenceClaim']
    ground,overlay=_services(source)
    for service in(ground,overlay):
        assert service.sources[0].excluded==(SPUR,)
        excluded=next(e for e in service.rings[source.cell_id]if e['kind']=='excluded')
        assert excluded['ring']==SPUR and excluded['valid']
    assert ground.local_domain(ground.rings[source.cell_id]).covers(Point(spike_point))
    assert overlay._local_domain(overlay.rings[source.cell_id]).covers(Point(spike_point))
    assert not source.contains(spike_point)


def test_unresolved_subgrid_ring_keeps_existing_owner_scoped_invalid_handling():
    ring=((10,10),(13,14),(10,14),(14,10),(10,10))
    with pytest.raises(walk.UnresolvedCoverageArea,match='SUBGRID_INTERSECTION'):
        walk.decode_coverage_ring(ring,cell_id='FRESH-UNRESOLVED',role='excluded',ordinal=0)
    source=CoverageSource('FRESH-UNRESOLVED',1000,(COVER,),(ring,))
    for service in _services(source):
        excluded=next(e for e in service.rings[source.cell_id]if e['kind']=='excluded')
        assert not excluded['valid'] and excluded['ring']==ring
        assert service.sources[0].excluded==(ring,)
