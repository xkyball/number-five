"""Pure retained-IR region adapter; no decoding, filesystem, Native or network IO.

This diagnostic candidate deliberately refuses product/SCAMIN claims. It wraps
existing authoring registries and the complete shared-grid geometry generator.
There are no private paths, chart fixtures or Original binaries in this module.
"""
from collections import Counter
from copy import deepcopy
import math

from shapely.geometry import Point, MultiPolygon, MultiLineString, box
from shapely import wkb

from no_chart_converter.navionics.regional_source import complete_tiles as tiles
from . import semantic_authoring as semantic
from . import source_attributes as generic
from . import diagnostic_settings as authoring
from . import road_integration as road

REQUEST_FORMAT='no5-retained-ir-complete-region-request/1'
POLICY='diagnostic_native_range_0_15_explicit_source_scamin_unqualified'
KEYS={'format','region_bounds','tile_side_source_units','maximum_maps',
      'maximum_vector_error_source_units','date_text','range_policy',
      'acknowledge_attribute_premises','unsupported_owner_policy'}


def need(ok,label):
    if not ok:raise ValueError(label)


def validate_request(value):
    need(type(value)is dict and set(value)-{'road_authoring_policy','magvar_portrayal_policy'}==KEYS and value['format']==REQUEST_FORMAT,'EXACT_REGION_REQUEST')
    if 'magvar_portrayal_policy'in value:
        from .magvar_portrayal import require_policy
        require_policy(value['magvar_portrayal_policy'])
    if 'road_authoring_policy'in value:
        road.require_policy(value['road_authoring_policy'])
    need(value['range_policy']==POLICY,'EXPLICIT_DIAGNOSTIC_ONLY_RANGE_POLICY')
    need(value['acknowledge_attribute_premises']is True,'EXPLICIT_ATTRIBUTE_PREMISES_ACKNOWLEDGED')
    need(value['unsupported_owner_policy']in('refuse_region_gaps','retain_diagnostic_gaps'),'EXPLICIT_UNSUPPORTED_OWNER_POLICY')
    need(type(value['maximum_maps'])is int and 1<=value['maximum_maps']<=256,'EXPLICIT_BOUNDED_SOURCE_MAP_BUDGET')
    need(type(value['date_text'])is str and len(value['date_text'])==8 and value['date_text'].isdigit(),'EXPLICIT_DATE8')
    return deepcopy(value)


def _source_shape(feature,semantic):
    g=feature['geometry'];kind=g['kind']
    if kind in('area','line'):
        parts=semantic._parts(feature)
        projected=[[semantic.geo.forward_model(p)['formulaF64XY']for p in part]for part in parts]
        if kind=='line':shape=MultiLineString(projected)
        else:
            rings={r['id']:part for r,part in zip(g['rings'],projected,strict=True)}
            shape=MultiPolygon([(rings[p['exteriorRingId']],[rings[r]for r in p['interiorRingIds']])for p in g['polygons']])
    elif kind=='point':
        shape=Point(semantic.geo.forward_model(semantic.cache._degrees(g['position']))['formulaF64XY'])
    elif kind=='multipoint':
        # No silent aggregate omission; currently unsupported real regions have
        # only Area/Line gaps. A new shape requires explicit qualified handling.
        raise ValueError('UNSUPPORTED_MULTIPOINT_REGION_GAP_NEEDS_EXPLICIT_SAMPLE_HANDLING')
    else:raise ValueError('UNKNOWN_OR_UNQUALIFIED_REGION_GAP_GEOMETRY')
    need(not shape.is_empty and shape.is_valid,'REGION_GAP_GEOMETRY_INVALID_NO_REPAIR')
    return shape


def build_region(cell,request):
    """Return explicit document and complete ledgers from an already decoded cell.

    Authoring dependencies are the package's complete own pure Source modules.
    The caller pins their provenance. The retained IR is immutable; no cache/decoder
    fallback or filesystem publication is hidden inside this function.
    """
    r=validate_request(request);cell_before=tiles.digest(cell)
    need(type(cell)is dict and type(cell.get('features'))is list,'RETAINED_IR_CELL_REQUIRED')
    settings,premises=authoring.diagnostic_settings(cell,date_text=r['date_text'],semantic=semantic,
        generic=generic,attribute_rows=generic.ATTRIBUTES,vector_error_reference='fractional_world_f64')
    semantic._configuration(settings)
    outcomes,attributes,losses,classes,reserved,samples=semantic.base._inventory(cell)
    atoms=semantic._atoms(cell,outcomes,attributes,settings,reserved)
    road_authoring=None
    if 'road_authoring_policy'in r:
        atoms,road_authoring=road.append_source_atoms(cell,outcomes,atoms,semantic=semantic,
            policy=r['road_authoring_policy'])
    q=tiles.build_complete_tiles(atoms,region=r['region_bounds'],tile_side=r['tile_side_source_units'],
        error_cap=r['maximum_vector_error_source_units'],range_policy=tiles.RANGE_POLICY,
        header_flags=settings['header_flags'],date_text=r['date_text'],maximum_maps=r['maximum_maps'])
    region=box(*q['coverageBounds']);source_by_id={f['sourceId']:f for f in cell['features']}
    road_ids={x['sourceId']for x in road_authoring['owners']}if road_authoring is not None else set()
    emitted_owners={x['originalOwnerSourceId']for x in q['featureLedger']}
    gap_rows=[]
    for row in outcomes:
        if not row['unsupported']:continue
        f=source_by_id[row['sourceId']];shape=_source_shape(f,semantic);clipped=shape.intersection(region)
        if clipped.is_empty:continue
        gap_rows.append(dict(sourceId=row['sourceId'],sourceClass=f['classAcronym'],sourceGeometryKind=f['geometry']['kind'],
            sourceGeometrySHA256=tiles.digest(f['geometry']),dimension='area'if shape.area>0 else'line'if shape.length>0 else'point',
            sourceRegionIntersectionAreaF64=clipped.area,sourceRegionIntersectionLengthF64=clipped.length,
            unsupported=deepcopy(row['unsupported']),nativeEmission=False,
            qualification='GEOS_F64_DIAGNOSTIC_REGION_INTERSECTION_NO_NATIVE_CLASS_INFERENCE'))
        if row['sourceId']in road_ids:
            # A policy-authored208 line does not close the original class and
            # attribute-identity gap or promote a native/595 owner.
            gap_rows[-1].update(nativeEmission=row['sourceId']in emitted_owners,
                explicitRoadAuthoringPolicy=r['road_authoring_policy'],
                originalUnsupportedRetained=True,nativeExecution=False,
                nativeClassIdentityQualified=False,sourceAttributeNativeParity=False,
                qualification='EXPLICIT_ROAD_GEOMETRY_AUTHORING_ORIGINAL_SEMANTIC_GAP_RETAINED')
    if gap_rows and r['unsupported_owner_policy']=='refuse_region_gaps':
        raise ValueError('SOURCE_REGION_HAS_UNREPRESENTED_OWNERS:'+','.join(x['sourceId']for x in gap_rows))
    emitted_atoms={x['sourceAtomId']for x in q['featureLedger']};represented={x['originalOwnerSourceId']for x in q['featureLedger']}
    point_precision=[]
    for atom in atoms:
        if atom['kind']not in(40,44)or atom['source_id']not in emitted_atoms:continue
        projected=semantic.geo.forward_model(atom['point']);stored=atom['projected'][0]
        require=projected['sourceI32XY'];need(require==stored,'EXISTING_POINT_I32_PROJECTION_EXACT')
        error=math.hypot(*(stored[i]-projected['formulaF64XY'][i]for i in(0,1)))
        point_precision.append(dict(sourceAtomId=atom['source_id'],originalOwnerSourceId=atom['parent']['sourceId'],
            kind=atom['kind'],storedI32=stored,sourceFormulaF64=projected['formulaF64XY'],errorSourceUnits=error,
            sourceProjectionPolicy='Existing sourceF64->F32->I32 truncation; no additional tile quantization.'))
    source_scamin=[]
    for sid in sorted(represented):
        f=source_by_id[sid]
        values=[dict(ordinal=i,value=a['value'],type=a['type'])for i,a in enumerate(f['attributes'])if a.get('acronym')=='SCAMIN']
        source_scamin.append(dict(sourceId=sid,sourceDisplayScale=deepcopy(f.get('displayScale')),sourceSCAMINAttributes=values,
            sourceDisplayScaleSHA256=tiles.digest(f.get('displayScale')),nativeConversionQualified=False))
    # Retract the prototype's unenumerated initialized diagnostic counter.
    q.pop('zeroDimensionalTouchesNotNativeVectors',None)
    q['zeroDimensionalContactCountQualified']=False
    q['seamComparisonCount']=len(q['seams'])
    q['nonemptySharedContactCount']=sum(not wkb.loads(bytes.fromhex(x['leftNormalizedWKB'])).is_empty for x in q['seams'])
    need(tiles.digest(cell)==cell_before,'RETAINED_IR_CELL_UNCHANGED')
    result=dict(format='no5-retained-ir-complete-region-diagnostic/1',request=r,document=q.pop('document'),geometryLedger=q,
        authoringSettings=settings,explicitAttributePremises=premises,
        inventory=dict(originalFeatureCount=len(cell['features']),originalAttributeCount=len(attributes),sourceSampleCount=samples,
            sourceAtomCount=len(atoms),sourceMappedOriginalOwners=len({a['parent']['sourceId']for a in atoms}),
            allOriginalOwnerOutcomes=outcomes,allOriginalAttributeRows=attributes,upstreamLosses=losses),
        regionalGaps=gap_rows,regionalGapCount=len(gap_rows),globalGapCount=sum(bool(x['unsupported'])for x in outcomes),
        precision=dict(vectorMaximumSourceUnits=q['maximumMeasuredErrorSourceUnits'],
            pointI32ProjectionMaximumSourceUnits=max((x['errorSourceUnits']for x in point_precision),default=0),
            pointProjectionRows=point_precision),sourceDisplayScaleLedger=source_scamin,
        SourceIRUnchanged=True,sourceOwnerDeduplication=False,sourceDecoderCalls=0,
        Native=0,Original=0,SSH=0,GUI=0,nativeProductAcceptance=False,completeS57Product=False,
        sourceSCAMINParity=False,diagnosticPolicyMustNotBePublishedAsProductPolicy=True)
    if road_authoring is not None:
        road_authoring['emittedOccurrences']=[deepcopy(x)for x in q['featureLedger']
            if x['originalOwnerSourceId']in road_ids]
        result['roadAuthoring']=road_authoring
        result['inventory']['sourceMappedOriginalOwners']=len({a['parent']['sourceId']
            for a in atoms if a['parent']['sourceId']not in road_ids})
        result['inventory']['sourcePolicyAuthoredOriginalOwners']=len(road_ids)
    return result
