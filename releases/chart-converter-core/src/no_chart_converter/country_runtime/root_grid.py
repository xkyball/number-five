"""Declared Root-U32 unit-grid fill encoding, independent of display pixels.

Whole valid clipped fills are snap-rounded by GEOS. Source geometry and query
payloads are caller-owned and remain unchanged. Topology and area differences
are measured, including an empty result for a positive clipped subgrid rest.
"""
from __future__ import annotations

import hashlib

from shapely import geos_version_string, set_precision, __version__ as SHAPELY_VERSION
from shapely.geometry import Polygon

Q = 2147483649
POLICY = 'own-root-u32-grid1-valid-output-fill/1'


def parts(geometry):
    if geometry.is_empty:
        return []
    if geometry.geom_type == 'Polygon':
        return [geometry]
    return [p for child in getattr(geometry, 'geoms', ()) for p in parts(child)]


def sha(geometry):
    return hashlib.sha256(geometry.wkb).hexdigest()


def unit_grid(geometry):
    # Reset only the precision MODEL before Float64 diagnostic overlays. The
    # already rounded integer coordinates stay unchanged.
    return set_precision(set_precision(geometry, 1, mode='valid_output'), 0)


def quantize_fill(reference, origin, *, owner_binding=None):
    if not (reference.is_valid and all(type(v) is int for v in origin) and len(origin) == 2):
        raise ValueError('VALID_FLOAT_FILL_AND_INTEGER_ROOT_ORIGIN_REQUIRED')
    before = parts(reference)
    if not reference.is_empty and (not before or reference.area <= 0):
        raise ValueError('POSITIVE_POLYGON_FILL_REFERENCE_REQUIRED')
    result = unit_grid(reference) if before else Polygon()
    if not result.is_valid:
        raise ValueError('GEOS_UNIT_GRID_VALID_OUTPUT_REQUIRED')
    after = parts(result)
    for p in after:
        for ring in (p.exterior, *p.interiors):
            if not all(float(v).is_integer() and 0 <= v + origin[axis] <= Q
                       for point in ring.coords for axis, v in enumerate(point)):
                raise ValueError('SNAP_ROUNDED_GLOBAL_ROOT_U32_COORDINATES_REQUIRED')
    collapsed_parts = []
    collapsed_holes = []
    for i, p in enumerate(before):
        if unit_grid(p).is_empty:
            collapsed_parts.append(dict(componentOrdinal=i, referenceWkbSHA256=sha(p),
                areaRootUnits2=p.area, boundsLocalRoot=list(p.bounds)))
        for j, ring in enumerate(p.interiors):
            hole = Polygon(ring)
            if unit_grid(hole).is_empty:
                collapsed_holes.append(dict(componentOrdinal=i, holeOrdinal=j,
                    referenceWkbSHA256=sha(hole), areaRootUnits2=hole.area,
                    boundsLocalRoot=list(hole.bounds)))
    added = result.difference(reference).area
    lost = reference.difference(result).area
    distance = reference.boundary.hausdorff_distance(result.boundary) if before and after else None
    holes_before = sum(len(p.interiors) for p in before)
    holes_after = sum(len(p.interiors) for p in after)
    event = dict(policy=POLICY, gridSizeRootUnits=1, shapelyPrecisionMode='valid_output',
        shapelyVersion=SHAPELY_VERSION, geosVersion=geos_version_string,
        ownerBinding=owner_binding, rootOrigin=list(origin),
        sourceUID=owner_binding.get('uid') if owner_binding else None,
        referenceFloatWkbSHA256=sha(reference), actualIntegerOutputWkbSHA256=sha(result),
        inputBoundsLocalRoot=list(reference.bounds) if before else [],
        inputReferenceEmpty=reference.is_empty, postGridEmpty=result.is_empty,
        positiveFloatClipCollapsedOnGrid=reference.area>0 and result.is_empty,
        disposition='EMPTY_DECLARED_ROOT_GRID_FILL_ORIGINAL_SOURCE_RETAINED' if result.is_empty else 'NONEMPTY_DECLARED_ROOT_GRID_FILL',
        inputAreaRootUnits2=reference.area, outputAreaRootUnits2=result.area,
        signedAddedAreaRootUnits2=added, signedLostAreaRootUnits2=lost,
        symmetricDifferenceAreaRootUnits2=added + lost,
        discreteBoundaryHausdorffRootUnits=distance,
        inputComponents=len(before), outputComponents=len(after),
        holesBefore=holes_before, holesAfter=holes_after,
        observedTopologyCountsChanged=(len(before), holes_before) != (len(after), holes_after),
        inputVertices=sum(len(r.coords) for p in before for r in (p.exterior, *p.interiors)),
        outputVertices=sum(len(r.coords) for p in after for r in (p.exterior, *p.interiors)),
        collapsedInputComponents=collapsed_parts, collapsedInputHoles=collapsed_holes,
        topologyIdentityClaim=False, clippedDisplayRepresentationOmitted=result.is_empty,
        originalSourceFeatureDeleted=False, sourceFeatureFilteredBeforeQuantization=False,
        nativeFillRecordExpected=not result.is_empty,
        authoringPixelBudgetEnforced=False, wholePixelModelIdentityClaim=False,
        exactZeroOutsideMaskClaim=False, unlimitedOverzoomOrNativePixelEqualityClaim=False,
        originalSourceGeometryAndQueriesRetainedByCaller=True,
        interpretation='Whole-reference unit-grid valid_output snap rounding; component/holes and area changes are explicit')
    return result, event
