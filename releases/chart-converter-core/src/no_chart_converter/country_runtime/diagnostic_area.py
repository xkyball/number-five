"""Diagnostic area measurements without changing source or clipping geometry."""


def intersection_area(left, right):
    """Apply the empty-set identity before asking GEOS to reoverlay.

    Some GEOS versions reject a valid GeometryCollection intersected with an
    empty Polygon. Its intersection is exactly empty, so its area is zero.
    Nonempty operands use the original GEOS operation; errors propagate.
    """
    if left.is_empty or right.is_empty:
        return 0.0
    return left.intersection(right).area
