"""Invented mixed-dimension coverage; no licensed geometry or native inputs."""
from fractions import Fraction
from types import SimpleNamespace

import pytest
from shapely.errors import GEOSException
from shapely.geometry import GeometryCollection, LineString, Polygon, box
from shapely.geometry.base import BaseGeometry

from no_chart_converter.country_runtime import overlay_masks, source_clip
from no_chart_converter.country_runtime.diagnostic_area import intersection_area


@pytest.mark.parametrize('reverse', [False, True])
def test_empty_intersection_uses_exact_set_identity_without_geos(monkeypatch, reverse):
    mixed = GeometryCollection([box(0, 0, 1, 1), LineString([(2, 0), (2, 1)])])
    empty = Polygon()
    before = mixed.wkb, empty.wkb

    def forbidden(*args, **kwargs):
        raise AssertionError('An empty intersection must not enter GEOS')

    monkeypatch.setattr(BaseGeometry, 'intersection', forbidden)
    left, right = (empty, mixed) if reverse else (mixed, empty)
    assert intersection_area(left, right) == 0.0
    assert (mixed.wkb, empty.wkb) == before


def test_nonempty_intersection_keeps_actual_geos_result_and_failures(monkeypatch):
    left, right = box(0, 0, 3, 3), box(2, 2, 4, 4)
    assert intersection_area(left, right) == 1.0

    def fail(*args, **kwargs):
        raise GEOSException('INVENTED_NONEMPTY_FAILURE')

    monkeypatch.setattr(BaseGeometry, 'intersection', fail)
    with pytest.raises(GEOSException, match='INVENTED_NONEMPTY_FAILURE'):
        intersection_area(left, right)


def test_full_owner_scope_keeps_mixed_geometry_with_empty_higher_coverage():
    def ring(x0, y0, x1, y1):
        return ((x0, y0), (x1, y0), (x1, y1), (x0, y1), (x0, y0))

    source = SimpleNamespace(cell_id='SYNTH-A', native_scale=100,
        covered=[ring(0, 0, 20, 20), ring(40, 2, 50, 10)], excluded=[])
    coverage_before = tuple(source.covered)
    service = overlay_masks.OwnerMaskService([source], lambda _: Fraction(1000), source_clip)
    own = service._local_domain(service.rings['SYNTH-A'])
    expected_mask = own.difference(Polygon())
    args = ('SYNTH-A', 0, (0, 0, 38, 10))
    for expected_hit in (False, True):
        clipper, receipt = service.for_owner(*args)
        assert clipper is not None
        assert clipper.masks['SYNTH-A'].wkb == expected_mask.wkb
        assert clipper.ownerAuthenticSemantics['higher'].is_empty
        # The second source polygon meets the padded owner only at an edge;
        # the first contributes area, yielding a valid GeometryCollection.
        scoped = expected_mask.intersection(box(0, 0, 40, 12))
        assert scoped.geom_type == 'GeometryCollection' and scoped.is_valid
        assert receipt['disposition'] == 'VALID_SOURCE_COVERAGE_OVERLAY_CLIPPER'
        assert receipt['cacheHit'] is expected_hit
        proof = receipt['scopeProof']
        assert proof['ownHigherCoverageOverlapArea'] == 0.0
        assert proof['ownOutsideCoverageArea'] == 0.0
        assert proof['maskConstructionIdentityExact']
        assert proof['finalQuantizedOwnerValidationRequired']
        assert proof['freshEvenOnCacheHit']
        assert receipt['retainedPrefix'] == ['SYNTH-A']
        assert tuple(source.covered) == coverage_before
