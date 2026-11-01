from . import robust_v3 as old, root_grid

helper=old.old

"""Productive Root-U32 unit-grid fill encoding.

The historical per-vertex pixel search stays below for audit/reproduction;
productive Kind3 clipping uses whole-reference GEOS valid_output snap rounding.
Kind2 line encoding is unchanged.
"""

from pathlib import Path

from itertools import product

import hashlib

import json

import math

import sys

from shapely import set_precision

from shapely.geometry import Point, Polygon

from shapely.ops import unary_union

helper = old.old

def collinear(points):
    unique = tuple(dict.fromkeys(points))
    if len(unique) < 3:
        return True
    a, b = unique[:2]
    return all((b[0] - a[0]) * (p[1] - a[1]) == (b[1] - a[1]) * (p[0] - a[0]) for p in unique[2:])

def vertex_candidate(reference, clipper):
    """Floor/ceil choices filtered through the actual GEOS point projection.

    The point constraints guide the finite search. Only the existing WHOLE
    polygon pixel-model equality authorizes a result, after integer validity.
    """
    levels = tuple(range(clipper.level, clipper.epoch_last_level + 1))
    helper.need(levels and 0 <= levels[0] <= levels[-1] <= 29, 'BOUND_AUTHORED_LEVEL_INTERVAL')
    helper.need(all(type(v) is int for v in clipper.origin), 'INTEGER_ROOT_TRANSLATION')
    axis_cache, chosen = {}, {}
    collapsed = []

    def axis_choices(value, axis):
        key = value, axis
        if key in axis_cache:
            return axis_cache[key]
        offset = clipper.origin[axis]
        options = tuple(n for n in sorted({math.floor(value), math.ceil(value)}) if 0 <= n + offset <= helper.Q)
        for level in levels:
            scale = clipper.tile_size * (1 << level) / helper.Q
            # Same separate Float64 multiply + offset as the frozen affine
            # path; GEOS itself supplies the grid=1 rounding, not an epsilon.
            target = set_precision(Point(value * scale + offset * scale, 0), 1, mode='valid_output').x
            options = tuple(n for n in options if set_precision(
                Point(n * scale + offset * scale, 0), 1, mode='valid_output').x == target)
        helper.need(options, 'NO_FLOOR_CEIL_PRESERVES_ALL_AUTHORED_VERTEX_PROJECTIONS')
        axis_cache[key] = options
        return options

    def ring_points(ring):
        result = []
        for x, y in ring.coords:
            key = x, y
            if key not in chosen:
                candidates = product(axis_choices(x, 0), axis_choices(y, 1))
                chosen[key] = min(candidates, key=lambda p: ((p[0] - x) ** 2 + (p[1] - y) ** 2, p))
            result.append(chosen[key])
        helper.need(result[0] == result[-1], 'INTEGER_TARGET_RING_CLOSURE')
        return result

    output = []
    for ordinal, part in enumerate(helper.parts(reference, 'Polygon')):
        exterior = ring_points(part.exterior)
        if collinear(exterior):
            # No general representability conclusion: prove ONLY that this
            # actual positive reference component has empty authored models.
            empty_proof = helper.projected_model_equal(part, Polygon(), clipper)
            collapsed.append(dict(componentOrdinal=ordinal, role='exterior',
                referenceWkbSHA256=helper.sha(part), referenceAreaRootUnits2=part.area,
                integerCollinearPoints=exterior, authoredEmptyPixelModel=empty_proof,
                generalU32UnrepresentabilityClaim=False))
            continue
        holes = []
        for hi, ring in enumerate(part.interiors):
            points = ring_points(ring)
            if collinear(points):
                original_hole = Polygon(ring)
                empty_proof = helper.projected_model_equal(original_hole, Polygon(), clipper)
                collapsed.append(dict(componentOrdinal=ordinal, holeOrdinal=hi, role='interior',
                    referenceWkbSHA256=helper.sha(original_hole), referenceAreaRootUnits2=original_hole.area,
                    integerCollinearPoints=points, authoredEmptyPixelModel=empty_proof,
                    generalU32UnrepresentabilityClaim=False))
            else:
                holes.append(points)
        candidate = Polygon(exterior, holes)
        helper.need(candidate.is_valid and candidate.area > 0, 'VALID_VERTEX_SELECTED_INTEGER_COMPONENT')
        output.append(candidate)
    result = unary_union(output)
    helper.need(result.is_valid, 'VALID_VERTEX_SELECTED_INTEGER_WHOLE_OWNER')
    whole = helper.projected_model_equal(reference, result, clipper)
    rows = [dict(originalLocalXY=list(p), selectedLocalIntegerXY=list(chosen[p]),
                 allowedX=list(axis_cache[(p[0], 0)]), allowedY=list(axis_cache[(p[1], 1)])) for p in sorted(chosen)]
    encoded = json.dumps(rows, separators=(',', ':'), allow_nan=False).encode()
    return result, dict(policy='own-finite-floor-ceil-vertex-authored-projection-search/1',
        authoredLevels=list(levels), uniqueVertices=len(chosen), axisConstraintSets=len(axis_cache),
        selection='minimum squared Root error then deterministic integer XY',
        allChoicesOriginalRootFloorCeil=True, allChosenGlobalU32InRange=True,
        allChosenVertexGEOSProjectionsEqual=True, vertexChoicesSHA256=hashlib.sha256(encoded).hexdigest(),
        vertexChoices=rows, collapsedIntegerRings=collapsed,
        generalU32UnrepresentabilityClaim=False, wholePixelModelReceipt=whole)

class RobustClipper(old.RobustClipper):
    def clip_polygon(self, polygon, cell_id):
        helper.need(polygon.is_valid and self.owner_binding and self.owner_binding['cellId'] == cell_id,
                    'VALID_ACTUAL_BOUND_ORIGINAL_FILL_OWNER')
        reference = unary_union(helper.parts(polygon.intersection(self.masks[cell_id]), 'Polygon'))
        result, event = root_grid.quantize_fill(reference, self.origin, owner_binding=self.owner_binding)
        event.update(requestedLevel=self.level, authoredLastLevel=self.epoch_last_level,
                     actualOriginalGeometryInput=getattr(self,'actual_original_geometry_input',None))
        self.events.append(event)
        self.reference, self.emitted = reference, result
        self.root_grid_policy_used = True
        self.pixel_model_fallback_used = False
        return result, event

def wrap_clipper(base, *, epoch_last_level=None, tile_size=256, owner_binding=None):
    return RobustClipper(base, epoch_last_level=epoch_last_level, tile_size=tile_size, owner_binding=owner_binding)

def clip_owner(co, ix, record, *, cell_id, clipper, source_tile=(0, 0, 0), coordinate_format=2,
               epoch_last_level=None, owner_binding=None):
    if not isinstance(clipper, RobustClipper):
        clipper = wrap_clipper(clipper, epoch_last_level=epoch_last_level, owner_binding=owner_binding)
    return old.clip_owner(co, ix, record, cell_id=cell_id, clipper=clipper, source_tile=source_tile,
        coordinate_format=coordinate_format, epoch_last_level=epoch_last_level, owner_binding=owner_binding)

def validate_final_owner(clipper, co, ix, rec, *, cell_id):
    # The existing decoder still checks the ACTUAL serialized U32/role inverse
    # against emitted geometry and reports own/higher-coverage overlap amounts.
    result = old.validate_final_owner(clipper,co,ix,rec,cell_id=cell_id)
    if getattr(clipper,'root_grid_policy_used',False) or rec is not None and rec[28]==3:
        result.pop('ownAuthoringPixelBudget',None)
        result.update(policy='own-root-u32-grid1-serialized-role-roundtrip/1',
            fillQuantizationPolicy=root_grid.POLICY,gridSizeRootUnits=1,
            authoringPixelBudgetEnforced=False,wholePixelModelIdentityClaim=False,
            exactZeroOutsideAuthenticDomainClaim=False,
            sourceGeometryAndQueriesUnchangedByCaller=True)
        if rec is None:
            result.update(disposition='EMPTY_DECLARED_ROOT_GRID_FILL_ORIGINAL_SOURCE_RETAINED',
                inputReferenceEmpty=clipper.reference.is_empty,postGridEmpty=clipper.emitted.is_empty,
                positiveFloatClipCollapsedOnGrid=clipper.reference.area>0 and clipper.emitted.is_empty,
                inputAreaRootUnits2=clipper.reference.area,outputAreaRootUnits2=0,
                signedAddedAreaRootUnits2=0,signedLostAreaRootUnits2=clipper.reference.area,
                ownerBinding=clipper.owner_binding,clippedDisplayRepresentationOmitted=True,
                originalSourceFeatureDeleted=False,sourceFeatureFilteredBeforeQuantization=False)
    return result
