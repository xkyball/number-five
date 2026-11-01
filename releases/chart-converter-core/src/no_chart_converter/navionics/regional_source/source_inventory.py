"""Pure own retained-IR authoring closure; extracted functions remain source-exact.
No decoder, filesystem, CLI, network, Native or Original execution entry point.
"""
from collections import Counter
import hashlib
from . import source_ir as cache
from . import source_projection as geo

CONFIG_FORMAT = 'no5-cache-nav-scaling-config/1'

LINE_TARGETS = {'COALNE': (30,29,61), 'PONTON': (95,105,105)}

def config(*, attribute_policy, inferred_depth_unit_policy, date_text_1, date_text_2,
           max_metric_error_source_units=2, max_depth_error_metres=.05,
           max_map_bytes=8192, max_total_map_bytes=64*1024*1024,
           max_map_count=1024, max_map_span_source_units=2048,
           max_features_per_layer=63, maps_per_atlas=8, header_flags=0,
           grid_candidates=(1024,)):
    """Policies/dates are mandatory; remaining values are own software budgets."""
    return dict(format=CONFIG_FORMAT, attribute_policy=attribute_policy,
        inferred_depth_unit_policy=inferred_depth_unit_policy,
        date_text_1=date_text_1, date_text_2=date_text_2,
        max_metric_error_source_units=max_metric_error_source_units,
        max_depth_error_metres=max_depth_error_metres, max_map_bytes=max_map_bytes,
        max_total_map_bytes=max_total_map_bytes, max_map_count=max_map_count,
        max_map_span_source_units=max_map_span_source_units,
        max_features_per_layer=max_features_per_layer, maps_per_atlas=maps_per_atlas,
        header_flags=header_flags,grid_candidates=list(grid_candidates))

def _digest(value):
    return hashlib.sha256(geo._json_bytes(value)).hexdigest()

def _int(value, low, high, label):
    if type(value) is not int or not low <= value <= high:
        raise ValueError(label+'_SOFTWARE_BUDGET_REQUIRED')
    return value

def _configuration(value):
    if any(cache.LINE_TARGETS.get(name)!=target for name,target in LINE_TARGETS.items()):
        raise ValueError('EXACT_QUALIFIED_LINE_MAPPING_DEPENDENCY_REQUIRED')
    required=set(config(attribute_policy='reject', inferred_depth_unit_policy='reject',
                        date_text_1='20000101', date_text_2='20000101'))
    geo._exact_keys(value, required, 'scaling config')
    if value['format'] != CONFIG_FORMAT:
        raise ValueError('SCALING_CONFIG_FORMAT_REQUIRED')
    if value['attribute_policy'] not in ('reject', 'drop_and_report'):
        raise ValueError('EXPLICIT_ATTRIBUTE_POLICY_REQUIRED')
    if value['inferred_depth_unit_policy'] not in ('reject', 'assume_metres_and_report'):
        raise ValueError('EXPLICIT_INFERRED_DEPTH_UNIT_POLICY_REQUIRED')
    for field,low,high in [('max_map_bytes',512,8192),('max_total_map_bytes',512,64*1024*1024),
                           ('max_map_count',1,1024),('max_map_span_source_units',2,1000000),
                           ('max_features_per_layer',1,63),('maps_per_atlas',1,16),
                           ('header_flags',0,65535)]:
        _int(value[field],low,high,field)
    for field,high in [('max_metric_error_source_units',1000),('max_depth_error_metres',1)]:
        number=geo._number(value[field],field)
        if not 0 <= number <= high:
            raise ValueError(field+'_EXPLICIT_BOUNDED_ERROR_REQUIRED')
    grids=value['grid_candidates']
    if (type(grids) is not list or not 1<=len(grids)<=16 or
        any(type(n) is not int or not 2<=n<=65536 for n in grids) or
        grids!=sorted(set(grids))):
        raise ValueError('EXPLICIT_ASCENDING_UNIQUE_GRID_CANDIDATES_2_TO_65536_REQUIRED')
    # Validate dates/header/write budget with an own validation point, never an
    # input/output feature. This reuses the exact existing planner guards.
    request=dict(format=geo.INPUT_FORMAT,header=dict(flags=value['header_flags'],
        raw_bounds=[0,0,2,2],raw_grid=[grids[0],grids[0]],raw_levels=[8,20],trailing_value=0),
        coastlines=[],soundings=[dict(source_id='__validation_only__',
            latitude_longitude=[0,0],mantissa=0,decimal_scale=0)],
        date_text_1=value['date_text_1'],date_text_2=value['date_text_2'],
        max_metric_error_source_units=value['max_metric_error_source_units'])
    geo.adapt_geographic_input(request,max_output_bytes=value['max_map_bytes'])

def _inventory(cell):
    if (type(cell) is not dict or cell.get('format')!='no5-chart-ir/1' or
        cell.get('kind')!='cell' or type(cell.get('id')) is not str or
        type(cell.get('features')) is not list):
        raise ValueError('REGULAR_CACHE_IR_CELL_REQUIRED')
    if len(cell['features'])>cache.MAX_FEATURES:
        raise ValueError('INPUT_FEATURE_SOFTWARE_BUDGET_EXCEEDED')
    seen=set();outcomes=[];attributes=[];classes=Counter();sample_total=0
    for ordinal,f in enumerate(cell['features']):
        if type(f) is not dict or type(f.get('geometry')) is not dict or type(f.get('attributes')) is not list:
            raise ValueError('REGULAR_FEATURE_GEOMETRY_AND_ATTRIBUTES_REQUIRED')
        sid=f.get('sourceId')
        if type(sid) is not str or not 1<=len(sid)<=128 or sid in seen:
            raise ValueError('UNIQUE_BOUNDED_SOURCE_IDS_REQUIRED')
        seen.add(sid);classes[str(f.get('classAcronym'))]+=1
        sample_total+=cache._coordinates_count(f)
        if sample_total>cache.MAX_SAMPLES:
            raise ValueError('INPUT_SAMPLE_SOFTWARE_BUDGET_EXCEEDED')
        row=dict(sourceId=sid,sourceOrdinal=ordinal,sourceClassCode=f.get('classCode'),
            sourceClassAcronym=f.get('classAcronym'),sourceGeometryKind=f['geometry'].get('kind'),
            sourceGeometrySHA256=_digest(f['geometry']),sourceAttributeCount=len(f['attributes']),
            sourceDisplayScale=f.get('displayScale',{}),displayScaleEncoded=False,
            emittedSourceIds=[],emissions=[],unsupported=[],status='UNSUPPORTED')
        if f['geometry'].get('kind')=='line':
            row.update(sourcePartCount=len(f['geometry'].get('parts',[])),
                       sourceVertexCount=cache._coordinates_count(f))
        if f['geometry'].get('kind')=='multipoint':
            source_points=f['geometry'].get('points',[])
            row.update(sourceSampleCount=len(source_points),samples=[dict(
                sourceSampleOrdinal=i,status='PENDING',emittedSourceId=None,
                mapOrdinal=None,unsupportedReason=None) for i in range(len(source_points))])
        outcomes.append(row)
        for ai,attribute in enumerate(f['attributes']):
            name=attribute.get('acronym') if type(attribute) is dict else None
            attributes.append(dict(sourceId=sid,sourceAttributeOrdinal=ai,
                acronym=name,sourceValueSHA256=_digest(attribute),encoded=False))
    coverage=cell.get('coverage',{})
    coverage_count=(sum(len(coverage.get(k,[])) if type(coverage.get(k,[])) is list else 0
                        for k in ('covered','excluded')) if type(coverage) is dict else 0)
    losses=dict(attributeOccurrencesNotEncoded=len(attributes),
        displayScaleDeclarationsNotEncoded=sum(len(f.get('displayScale',{}))
            if type(f.get('displayScale',{})) is dict else 0 for f in cell['features']),
        coverageRingsNotEncoded=coverage_count,textDocumentsNotEncoded=len(cell.get('textDocuments',[])),
        upstreamLossDeclarationsNotRecovered=len(cell.get('losses',[])))
    return outcomes,attributes,losses,classes,seen,sample_total

def _refuse(row,reason,sample=None):
    item=dict(reason=str(reason))
    if sample is not None:item['sourceSampleOrdinal']=sample
    elif row.get('samples'):item['appliesToAllSamples']=True
    row['unsupported'].append(item)
    targets=row.get('samples',[]) if sample is None else [row['samples'][sample]]
    for target in targets:target.update(status='UNSUPPORTED',unsupportedReason=str(reason))
