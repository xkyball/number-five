"""Finite all-level ARM/Qt operand certificate for unchanged exterior rings.

This proves constructor/union operand domains in the existing source-bound
unrotated256 raster model. It does not author a chart or grant arbitrary painter
transform, query, native execution, or product-delivery authority.
"""
from fractions import Fraction as R
import hashlib
import math

from . import locality_empty,model,wire
from .arm_qround_interval import (saturated_negative_qround_interval,INT_MIN,INT_MAX,
    QT_ROUNDING_CONTRACT_SHA256)

ROOT=2147483649
UNION_CONTRACT='e18498199ed27546a0d19a1ff9e042fc044c4a5697942c4053ac36868b8e3583'
QREGION_CONTRACT='f30b7abf0fa9f2feee9585cfe7cd728d56494c62f9c9e072780a17c5e3762aea'


def _ceil(value):return -((-value.numerator)//value.denominator)
def _floor(value):return value.numerator//value.denominator


def _point_range(value,axis,source,level):
    z=source[0];scale=1<<(level-z)
    upper=R(value*(1<<(level+8)),ROOT)-axis*scale*256
    return upper-(scale-1)*256,upper


def ring_integer_domain(points,source,level):
    """Return a fully bounded ring or None; never relax a branch straddler.

    E is the existing four-global-F32-ULP+8 envelope. The actual Root1
    projection is F64 until its final F32 conversion. Adding8 also encloses
    normal Qt qRound integer intermediates in this finite source model.
    For the saturated negative branch two local F32 operations have magnitude
    <=2**31 and each normal-mode error <=256; +1024 safely includes both,
    +0.5 and the fixed integer truncation. All lifted points must stay on this
    SAME branch; no cross-wrap interpolation is called safe.
    """
    error=(1<<max(level-12,0))+8
    dx_error=error+8
    scale=1<<(level-source[0]);factor=1<<(level+8)
    ranges=[[ _point_range(p[a],source[a+1],source,level) for p in points]for a in(0,1)]
    x_bounds=[min(v[0]for v in ranges[0]),max(v[1]for v in ranges[0])]
    y_bounds=[min(v[0]for v in ranges[1]),max(v[1]for v in ranges[1])]
    if x_bounds[0]<INT_MIN+dx_error+8 or x_bounds[1]>INT_MAX-dx_error-8:return None
    out_x=[_floor(x_bounds[0]-dx_error),_ceil(x_bounds[1]+dx_error)]
    if y_bounds[0]>=INT_MIN+dx_error+8 and y_bounds[1]<=INT_MAX-dx_error-8:
        mode='NORMAL_SIGNED_QROUND';dy_error=dx_error
        out_y=[_floor(y_bounds[0]-dy_error),_ceil(y_bounds[1]+dy_error)]
    else:
        converted=[]
        for lo,hi in ranges[1]:
            p=saturated_negative_qround_interval(lo-error,hi+error)
            if p is None or p['remainderIntegerInterval'][0]<=INT_MIN or p['outputS32Interval'][0]<=0:
                return None
            converted.append(p['outputS32Interval'])
        mode='NEGATIVE_T_MIN_COMMON_POSITIVE_ADD_WRAP';dy_error=error+1024
        out_y=[min(v[0]for v in converted),max(v[1]for v in converted)]
        # This branch changes the integer Y lift. Only a common outside-X
        # halfplane, not the original mathematical Y winding, can exclude it.
        if not(out_x[1]<-35 or out_x[0]>256+35):return None
    if not all(INT_MIN+8<=v<=INT_MAX-8 for v in out_x+out_y):return None
    bx=max(x for x,y in points)-min(x for x,y in points)
    by=max(y for x,y in points)-min(y for x,y in points)
    span_x=_ceil(R(bx*factor,ROOT)+2*dx_error)
    span_y=_ceil(R(by*factor,ROOT)+2*dy_error)
    if max(span_x,span_y)+2>INT_MAX:return None
    worst=0
    for a,b in zip(points,points[1:]):
        dx=_ceil(R(abs(a[0]-b[0])*factor,ROOT)+2*dx_error)
        dy=_ceil(R(abs(a[1]-b[1])*factor,ROOT)+2*dy_error)
        # Bounds SUBS, doubled deltas and both quotient-product arms, because
        # 2*dy*(abs(trunc(dx/dy))+1) <= 2*(abs(dx)+dy). The standard integer
        # edge recurrence keeps its error within2*dy and X between endpoints.
        term=2*(dx+dy)
        if term+4>INT_MAX:return None
        worst=max(worst,term)
    # Four/five-point fast rectangles may bypass the generic100000-span cap.
    # Treat every such ring as potentially rectangular, never assume its shape.
    max_height=span_y+2 if len(points)in(4,5)else min(span_y+2,100001)
    return dict(level=level,mode=mode,coordinateErrorPixels=[dx_error,dy_error],
        integerXBounds=[out_x[0]-2,out_x[1]+2],integerYBounds=[out_y[0]-2,out_y[1]+2],
        projectedPointXBounds=out_x,projectedPointYBounds=out_y,
        coherentRingSpanBounds=[span_x,span_y],maximumDoubledEdgeProductBound=worst,
        potentialRectangleFastPath=len(points)in(4,5),maximumNonemptyRegionHeight=max_height,
        everyDescendantTranslationCovered=True,constructorOperandsS32AndEndpointMarginsSafe=True)


def certify_exterior_integer_regions(record,indices,coordinates,source):
    """Actual full-part certificate; ValueError refuses the whole owner."""
    source=tuple(source)
    wire.need(len(source)==3 and all(type(v)is int for v in source),'EXPLICIT_SOURCE_TILE')
    z,x,y=source
    wire.need(17<=z<=20 and 0<=x<1<<z and 0<=y<1<<z,'FINITE_SOURCE17_TO20_DOMAIN')
    fields=wire.FEATURE.unpack(record)
    wire.need(fields[6]==3,'COMPLETE_AREA_OWNER_REQUIRED')
    pp=list(model.parts(record,indices,coordinates));wire.need(pp,'NONEMPTY_AREA')
    guard=max(128,32+(1<<max(z-12,0))+32)
    domain=locality_empty.Domain(256*(1<<z),(256*x-guard)*ROOT,(256*y-guard)*ROOT,
        (256*(x+1)+guard)*ROOT,(256*(y+1)+guard)*ROOT)
    rows=[];accumulation=[]
    for p in pp:
        pts=p['points']
        wire.need(p['rootFlag']==1 and p['usage']==24 and len(pts)>=4 and pts[0]==pts[-1],
            'ALL_CLOSED_ROOT1_USAGE24_EXTERIOR_REQUIRED')
        wire.need(not any(domain.hits(a,b)for a,b in zip(pts,pts[1:]))and domain.winding(pts)==0,
            'ALL_CANONICAL_RINGS_OUTSIDE_PROTECTED_SOURCE_DOMAIN')
        row=dict(partOrdinal=p['partOrdinal'],points=p['pointCount'],typedElementSHA256=hashlib.sha256(p['raw']).hexdigest(),levels=[])
        for level in range(z,30):
            error=(1<<max(level-12,0))+8
            wire.need(error+8+3<(guard-32)*(1<<(level-z)),'NORMAL_PROJECTION_ERROR_WITHIN_GUARD')
            proof=ring_integer_domain(pts,source,level)
            wire.need(proof is not None,'QREGION_INTEGER_BRANCH_OR_OPERAND_REFUSAL_PART_'+str(p['partOrdinal'])+'_LEVEL_'+str(level))
            row['levels'].append(proof)
        rows.append(row)
    for index,level in enumerate(range(z,30)):
        domains=[r['levels'][index]for r in rows]
        xlo=min(r['integerXBounds'][0]for r in domains);xhi=max(r['integerXBounds'][1]for r in domains)
        max_height=sum(r['maximumNonemptyRegionHeight']for r in domains)
        wire.need(xhi-xlo+2<=INT_MAX and max_height+2<=INT_MAX,'ACCUMULATED_FILLRECT_S32_EXTENT_REQUIRED')
        accumulation.append(dict(level=level,globalXBounds=[xlo,xhi],maximumOutputWidth=xhi-xlo+2,
          maximumCoalescedHeightBySumOfInputRegionHeights=max_height,
          allXYEndpointsHaveTwoIntegerMargin=True,allPartsExteriorUniteOnly=True))
    return dict(format='no5-exterior-integer-region-operand-certificate/1',sourceTile=list(source),
      maximumDescendantLevel=29,geometryGuardSourcePixels=guard,protectedDrawHaloPixels=32,
      partCount=len(rows),pointCount=sum(r['points']for r in rows),parts=rows,accumulation=accumulation,
      qRegionContractSHA256=QREGION_CONTRACT,unionContractSHA256=UNION_CONTRACT,
      qRoundContractSHA256=QT_ROUNDING_CONTRACT_SHA256,
      viewModel='SOURCE_BOUND_UNROTATED_256_WITH_32PX_PROTECTED_HALO',
      arbitraryPainterTransformClaim=False,OriginalCalls=0)
