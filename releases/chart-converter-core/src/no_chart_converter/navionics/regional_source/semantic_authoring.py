"""Pure own retained-IR authoring closure; extracted functions remain source-exact.
No decoder, filesystem, CLI, network, Native or Original execution entry point.
"""
from copy import deepcopy
from decimal import Decimal
from . import source_inventory as base
from . import source_ir as cache
from . import source_projection as geo
from no_chart_converter.navionics.typed_batch import EnumE, ScaledM

CONFIG_FORMAT = 'no5-semantic-cache-nav-scaling-config/1'

MAPPINGS = {'ACHARE': (4, 1, {'area': (24, 14)}),
 'ADMARE': (1, 3, {'area': (24, 10)}),
 'BERTHS': (10, 10, {'point': (40, 104)}),
 'BOYCAR': (14, 11, {'point': (40, 88)}),
 'BOYISD': (16, 13, {'point': (40, 88)}),
 'BOYLAT': (17, 14, {'point': (40, 88)}),
 'BOYSAW': (18, 15, {'point': (40, 88)}),
 'BOYSPP': (19, 16, {'point': (40, 88)}),
 'BRIDGE': (11, 17, {'area': (24, 34), 'line': (32, 33)}),
 'BUAARE': (13, 18, {'area': (24, 34)}),
 'BUISGL': (12, 19, {'area': (24, 66), 'point': (40, 64)}),
 'CANALS': (23, 22, {'area': (24, 174)}),
 'CBLSUB': (22, 26, {'line': (32, 25)}),
 'COALNE': (30, 29, {'line': (32, 61)}),
 'CONVYR': (34, 30, {'line': (32, 25)}),
 'CTNARE': (27, 34, {'area': (24, 150)}),
 'CTRPNT': (33, 35, {'point': (40, 28)}),
 'DEPARE': (42, 41, {'area': (24, 38)}),
 'DEPCNT': (43, 42, {'line': (32, 37)}),
 'DMPGRD': (48, 44, {'area': (24, 170)}),
 'DRGARE': (46, 46, {'area': (24, 38)}),
 'DRYDOC': (47, 47, {'area': (24, 106)}),
 'FOGSIG': (58, 56, {'point': (40, 88)}),
 'FORSTC': (59, 57, {'area': (24, 66)}),
 'GATCON': (61, 62, {'area': (24, 174), 'line': (32, 173)}),
 'HRBARE': (63, 64, {'area': (24, 10)}),
 'HRBFAC': (64, 65, {'area': (24, 106), 'point': (40, 104)}),
 'ISTZNE': (68, 69, {'area': (24, 150)}),
 'LAKARE': (69, 70, {'area': (24, 58)}),
 'LIGHTS': (75, 71, {'point': (40, 88)}),
 'LNDARE': (71, 74, {'area': (24, 62)}),
 'LNDELV': (72, 75, {'line': (32, 113), 'point': (40, 112)}),
 'LNDMRK': (74, 76, {'point': (40, 64)}),
 'LNDRGN': (73, 77, {'area': (24, 62), 'point': (40, 60)}),
 'LOKBSN': (79, 80, {'area': (24, 106)}),
 'MAGVAR': (81, 91, {'area': (24, 70)}),
 'M_COVR': (302, 82, {'area': (24, 78)}),
 'M_NSYS': (306, 86, {'area': (24, 78)}),
 'M_QUAL': (308, 87, {'area': (24, 78)}),
 'NAVLNE': (85, 95, {'line': (32, 149)}),
 'OBSTRN': (86, 96, {'area': (24, 94), 'point': (40, 92)}),
 'PILBOP': (91, 100, {'point': (40, 128)}),
 'PILPNT': (90, 101, {'point': (40, 104)}),
 'PIPSOL': (94, 104, {'line': (32, 101)}),
 'PONTON': (95, 105, {'line': (32, 105)}),
 'PRDARE': (97, 107, {'area': (24, 66)}),
 'RAILWY': (106, 113, {'line': (32, 109)}),
 'RDOCAL': (104, 117, {'point': (40, 148)}),
 'RECTRC': (109, 119, {'line': (32, 149)}),
 'RESARE': (112, 120, {'area': (24, 118)}),
 'RIVERS': (114, 122, {'area': (24, 174), 'line': (32, 173)}),
 'RSCSTA': (111, 123, {'point': (40, 128)}),
 'RTPBCN': (103, 124, {'point': (40, 88)}),
 'SBDARE': (121, 126, {'area': (24, 86), 'point': (40, 84)}),
 'SEAARE': (119, 127, {'area': (24, 162), 'point': (40, 160)}),
 'SISTAT': (123, 129, {'point': (40, 128)}),
 'SISTAW': (124, 130, {'point': (40, 128)}),
 'SLCONS': (122, 131, {'area': (24, 138), 'line': (32, 137)}),
 'SLOTOP': (126, 133, {'line': (32, 61)}),
 'SMCFAC': (128, 134, {'area': (24, 130)}),
 'SOUNDG': (129, 136, {'multipoint': (44, 140)}),
 'TOPMAR': (144, 143, {'point': (40, 88)}),
 'TSELNE': (145, 145, {'line': (32, 149)}),
 'TSEZNE': (150, 146, {'area': (24, 150)}),
 'TSSBND': (146, 147, {'line': (32, 149)}),
 'TSSCRS': (147, 148, {'area': (24, 150)}),
 'TSSLPT': (148, 149, {'area': (24, 150)}),
 'UWTROC': (153, 154, {'point': (40, 124)}),
 'WATTUR': (156, 157, {'line': (32, 145), 'point': (40, 144)}),
 'WEDKLP': (158, 158, {'point': (40, 84)}),
 'WRECKS': (159, 159, {'area': (24, 178), 'point': (40, 176)})}

ATTRIBUTE_CODES = {'VALDCO': (174, 138), 'CATSLC': (60, 62), 'WATLEV': (187, 151),
    'DRVAL1': (87,82), 'DRVAL2': (88,83)}

def _mapping_registry(settings):
    """Keep legacy scope exact; generic mode consumes qualified parent routes.

    No table-size limit, name alias or class-code subtraction is introduced.
    The typed layer's low bits follow the parent's actual native geometry kind.
    """
    if 'attribute_encoding_profile' not in settings:
        return MAPPINGS
    from no_chart_converter.navionics.generic_registry import CLASSES
    kinds = {24: ('area', 2), 32: ('line', 1), 40: ('point', 0), 44: ('multipoint', 0)}
    mappings = {}
    for native_class, row in CLASSES.items():
        name, source_code = row['acronym'], row['sourceCode']
        if type(name) is not str or name in mappings:
            raise ValueError('PARENT_CLASS_ACRONYM_MUST_BE_EXACT_AND_UNIQUE')
        base._int(source_code, 1, 65535, 'parent source class code')
        base._int(native_class, 1, 255, 'parent native class code')
        layer_base = row['layerBase']
        base._int(layer_base, 0, 252, 'parent native layer base')
        if layer_base & 3:
            raise ValueError('PARENT_TYPED_LAYER_BASE_ALIGNMENT_REQUIRED')
        routes = {}
        for kind in row['allowedKinds']:
            if type(kind) is not int or kind not in kinds:
                raise ValueError('PARENT_GEOMETRY_KIND_NOT_SUPPORTED_BY_PUBLIC_WRITER')
            if kind == 44 and (native_class != 136 or layer_base != 140):
                raise ValueError('PARENT_DEPTH44_ROUTE_REMAINS_SOUNDG_ONLY')
            shape, bits = kinds[kind]
            routes[shape] = (kind, layer_base + bits)
        if not routes:
            raise ValueError('PARENT_CLASS_REQUIRES_EXPLICIT_GEOMETRY_ROUTES')
        mappings[name] = (source_code, native_class, routes)
    return mappings

def config(*, inferred_depth_unit_policy, date_text_1, date_text_2,
           allowed_unencoded_attribute_acronyms=(), declared_native_units=None,
           vector_projection_stage=None, attribute_encoding_profile=None,
           vector_error_reference=None,
           vector_header_policy=None,
           **numeric_budgets):
    value = base.config(attribute_policy='reject',
        inferred_depth_unit_policy=inferred_depth_unit_policy,
        date_text_1=date_text_1, date_text_2=date_text_2, **numeric_budgets)
    del value['attribute_policy']
    value.update(format=CONFIG_FORMAT,
        allowed_unencoded_attribute_acronyms=list(allowed_unencoded_attribute_acronyms),
        declared_native_units=declared_native_units)
    if vector_projection_stage is not None:value['vector_projection_stage']=vector_projection_stage
    if attribute_encoding_profile is not None:value['attribute_encoding_profile']=deepcopy(attribute_encoding_profile)
    if vector_error_reference is not None:value['vector_error_reference']=vector_error_reference
    if vector_header_policy is not None:value['vector_header_policy']=vector_header_policy
    return value

def _vector_stage(settings):
    return settings.get('vector_projection_stage')

def _projection_xy(model,settings):
    stage=_vector_stage(settings)
    return model['sourceF32XY'] if stage=='pre_i32_f32' else (
        model['formulaF64XY'] if stage=='pre_f32_f64' else model['sourceI32XY'])

def _configuration(value):
    expected = set(config(inferred_depth_unit_policy='reject',
        date_text_1='20000101', date_text_2='20000101'))
    if 'vector_projection_stage'in value:
        expected.add('vector_projection_stage')
        if value['vector_projection_stage']not in ('pre_i32_f32','pre_f32_f64'):
            raise ValueError('EXPLICIT_SUPPORTED_VECTOR_PROJECTION_STAGE_REQUIRED')
    if 'vector_error_reference' in value:
        expected.add('vector_error_reference')
        if (value['vector_error_reference'] != 'fractional_world_f64' or
                value.get('vector_projection_stage') != 'pre_f32_f64'):
            raise ValueError('FRACTIONAL_WORLD_REFERENCE_REQUIRES_EXPLICIT_PRE_F32_F64')
    if 'attribute_encoding_profile'in value:
        expected.add('attribute_encoding_profile')
        from .source_attributes import validate_profile
        validate_profile(value['attribute_encoding_profile'])
    if 'vector_header_policy' in value:
        expected.add('vector_header_policy')
        if (value['vector_header_policy'] != 'bounded_square_phase_search_v1' or
                value.get('vector_projection_stage') != 'pre_f32_f64' or
                value.get('vector_error_reference') != 'fractional_world_f64'):
            raise ValueError('SQUARE_PHASE_SEARCH_REQUIRES_EXPLICIT_FRACTIONAL_F64_REFERENCE')
    geo._exact_keys(value, expected, 'semantic scaling config')
    if value['format'] != CONFIG_FORMAT:
        raise ValueError('SEMANTIC_SCALING_CONFIG_REQUIRED')
    names = value['allowed_unencoded_attribute_acronyms']
    if (type(names) is not list or len(names) > 128 or
        any(type(n) is not str or not 1 <= len(n) <= 32 or '*' in n for n in names) or
        names != sorted(set(names))):
        raise ValueError('EXPLICIT_SORTED_UNIQUE_UNENCODED_ACRONYM_ALLOWLIST_REQUIRED')
    unit = value['declared_native_units']
    if unit is not None and (type(unit) is not int or unit != 2):
        raise ValueError('OPTIONAL_EXPLICIT_NATIVE_UNIT2_PREMISE_REQUIRED')
    numeric = dict(value, format=base.CONFIG_FORMAT, attribute_policy='reject')
    del numeric['allowed_unencoded_attribute_acronyms'], numeric['declared_native_units']
    numeric.pop('vector_projection_stage',None)
    numeric.pop('vector_error_reference',None)
    numeric.pop('vector_header_policy',None)
    numeric.pop('attribute_encoding_profile',None)
    # The parent inventory adapter's <=8192 map cap is an own legacy preset,
    # not a wire limit. Reuse its other guards and validation-only point with
    # that preset; actual proposals always use this explicit public-planner
    # budget, including the additive larger source-only profiles.
    base._int(value['max_map_bytes'],512,64*1024*1024,'max_map_bytes')
    numeric['max_map_bytes']=min(value['max_map_bytes'],8192)
    base._configuration(numeric)

def _attribute_plan(feature, rows, settings, native_class=None):
    encoded, failures = [], []
    if 'attribute_encoding_profile' in settings and native_class is None:
        native_class = _mapping_registry(settings)[feature['classAcronym']][1]
    names = [a.get('acronym') if type(a) is dict else None for a in feature['attributes']]
    for attr, row in zip(feature['attributes'], rows):
        name = row['acronym']
        row.update(encodingStatus='UNSUPPORTED_REFUSED', nativeAttribute=None,
            emittedOccurrences=[], representedByGeometry=False, sourceAttribute=deepcopy(attr))
        if name=='catgeo':
            expected={'point':1,'multipoint':1,'line':2,'area':3}.get(feature['geometry'].get('kind'))
            if (attr.get('code')!=50000 or type(attr.get('value'))is not int or
                attr['value']!=expected or type(feature.get('sourcePrimitive'))is not int or
                feature['sourcePrimitive']!=expected-1):
                row['failureReason']='SOURCE_GEOMETRY_CATEGORY_MISMATCH'
                failures.append('SOURCE_GEOMETRY_CATEGORY_MISMATCH');continue
            row.update(encodingStatus='STRUCTURAL_PENDING_GEOMETRY',
                structuralGeometryCategory=expected)
            continue
        if 'attribute_encoding_profile'in settings:
            try:
                if names.count(name)!=1:
                    raise ValueError('DUPLICATE_SOURCE_ATTRIBUTE_ACRONYM')
                from .source_attributes import encode_source_attribute
                _,wire,qualification=encode_source_attribute(attr,native_class,
                    settings['attribute_encoding_profile'])
                row.update(encodingStatus='QUALIFIED_PENDING_GEOMETRY',nativeAttribute=wire,
                    genericQualification=qualification)
                encoded.append((row,wire))
            except (ValueError,ArithmeticError,UnicodeError)as error:
                row['failureReason']=str(error)
                if name in settings['allowed_unencoded_attribute_acronyms']and names.count(name)==1:
                    row['encodingStatus']='UNSUPPORTED_ALLOWED_EXTERNAL'
                else:failures.append(str(error)+':'+str(name))
            continue
        supported = (feature['classAcronym'] == 'DEPCNT' and name == 'VALDCO' or
                     feature['classAcronym'] == 'SLCONS' and name in ('CATSLC', 'WATLEV') or
                     feature['classAcronym'] in ('DEPARE','DRGARE') and name in ('DRVAL1','DRVAL2'))
        if not supported:
            if name in settings['allowed_unencoded_attribute_acronyms']:
                row['encodingStatus'] = 'UNSUPPORTED_ALLOWED_EXTERNAL'
            else:
                failures.append('UNENCODED_ATTRIBUTE_NOT_ALLOWED:' + str(name))
            continue
        try:
            if names.count(name) != 1:
                raise ValueError('DUPLICATE_TYPED_ATTRIBUTE')
            if attr.get('code') != ATTRIBUTE_CODES[name][0]:
                raise ValueError('SOURCE_ATTRIBUTE_CODE_ACRONYM_MISMATCH')
            value = attr.get('value')
            if name in ('VALDCO','DRVAL1','DRVAL2'):
                if settings['declared_native_units'] != 2:
                    raise ValueError('EXPLICIT_NATIVE_UNIT2_PREMISE_REQUIRED')
                if type(value) not in (int, float):
                    raise ValueError('NUMERIC_SOURCE_MEASUREMENT_REQUIRED')
                scalar = ScaledM(ATTRIBUTE_CODES[name][1], Decimal(str(value)), 2)
                wire = dict(storage='M', native_code_u16=ATTRIBUTE_CODES[name][1],
                    value_decimal=str(scalar.value), declared_native_units=2)
            else:
                scalar = EnumE(ATTRIBUTE_CODES[name][1], value)
                wire = dict(storage='E', native_code_u16=scalar.native_code_u16,
                    enum_u8=scalar.enum_u8)
            row.update(encodingStatus='QUALIFIED_PENDING_GEOMETRY', nativeAttribute=wire)
            encoded.append((row, wire))
        except (ValueError, ArithmeticError) as error:
            row['failureReason'] = str(error)
            failures.append(str(error) + ':' + str(name))
    return encoded, failures

def _parts(feature):
    g = feature['geometry']
    if g.get('reconstruction', {}).get('status') != 'exact_topology' or g.get('reconstruction', {}).get('issues') != []:
        raise ValueError('EXACT_SOURCE_TOPOLOGY_REQUIRED')
    if g['kind'] == 'line':
        parts = g.get('parts')
        if type(parts) is not list or not parts:
            raise ValueError('NONEMPTY_WHOLE_LINE_PARTS_REQUIRED')
        source = [p.get('coordinates') if type(p) is dict else None for p in parts]
    else:
        rings, polygons = g.get('rings'), g.get('polygons')
        if type(rings) is not list or not rings or type(polygons) is not list or not polygons:
            raise ValueError('COMPLETE_AREA_RING_AND_POLYGON_ROLES_REQUIRED')
        ids=[r.get('id')for r in rings]
        if any(type(i)is not str for i in ids) or len(set(ids))!=len(ids):
            raise ValueError('UNIQUE_SOURCE_RING_IDS_REQUIRED')
        roles={r['id']:r.get('role')for r in rings};references=[]
        for polygon in polygons:
            outer=polygon.get('exteriorRingId');inners=polygon.get('interiorRingIds')
            if roles.get(outer)!='exterior' or type(inners)is not list or any(roles.get(i)!='interior'for i in inners):
                raise ValueError('EXACT_SOURCE_POLYGON_RING_ROLES_REQUIRED')
            references.extend([outer,*inners])
        if len(references)!=len(ids) or set(references)!=set(ids):
            raise ValueError('ALL_SOURCE_RINGS_EXACTLY_ONCE_REQUIRED')
        # Source-bound native fill accumulates all closed parts, then alternates
        # sorted crossings (EvenOdd). Preserve all source rings and their order;
        # never flatten holes, change winding, close or repair a ring here.
        source = [r.get('coordinates')for r in rings]
    if any(type(p) is not list or len(p) < (4 if g['kind'] == 'area' else 2) for p in source):
        raise ValueError('COMPLETE_SOURCE_PART_COORDINATES_REQUIRED')
    if g['kind'] == 'area' and any(part[0]!=part[-1]for part in source):
        raise ValueError('SOURCE_CLOSED_AREA_REQUIRED_NO_REPAIR')
    return [[cache._degrees(p) for p in part] for part in source]

def _atoms(cell, outcomes, attributes, settings, reserved):
    mappings = _mapping_registry(settings)
    by_source = {}
    for attr in attributes:
        by_source.setdefault(attr['sourceId'], []).append(attr)
    atoms = []
    for f, row in zip(cell['features'], outcomes):
        name, sid, g = f.get('classAcronym'), f['sourceId'], f['geometry']
        row.update(sourceRingCount=len(g.get('rings',[])),sourceInteriorRingCount=sum(
            r.get('role')=='interior'for r in g.get('rings',[])))
        if g.get('kind')=='area':
            row.update(sourcePartCount=len(g.get('rings',[])),sourceVertexCount=sum(
                len(r.get('coordinates',[]))for r in g.get('rings',[])))
        attrrows = by_source.get(sid, [])
        row['geometryMappingStatus']='UNPROVEN'
        for attr in attrrows:
            attr.update(encodingStatus='CLASS_OR_GEOMETRY_UNSUPPORTED', nativeAttribute=None,
                emittedOccurrences=[], representedByGeometry=False,
                sourceAttribute=deepcopy(f['attributes'][attr['sourceAttributeOrdinal']]))
        mapping = mappings.get(name)
        if mapping is None:
            base._refuse(row, 'CLASS_MAPPING_UNPROVEN'); continue
        if type(f.get('classCode')) is not int or f['classCode'] != mapping[0]:
            base._refuse(row, 'SOURCE_CLASS_CODE_ACRONYM_MISMATCH'); continue
        if g.get('kind') not in mapping[2]:
            base._refuse(row, 'GEOMETRY_MAPPING_UNPROVEN'); continue
        kind, layer = mapping[2][g['kind']]
        row.update(nativeClassCode=mapping[1], nativeLayerId=layer, nativeKind=kind,
            geometryMappingStatus='QUALIFIED_CLASS_AND_KIND')
        encoded, failures = _attribute_plan(f, attrrows, settings, native_class=mapping[1])
        if failures:
            for failure in failures: base._refuse(row, failure)
            continue
        omitted = [a for a in attrrows if a['encodingStatus'] == 'UNSUPPORTED_ALLOWED_EXTERNAL']
        row['allowedUnencodedAttributeOrdinals'] = [a['sourceAttributeOrdinal'] for a in omitted]
        try:
            common = dict(parent=row, source_id=sid, native_class=mapping[1], layer=layer,
                kind=kind, name=name, attributes=encoded, omitted=omitted,
                mode=('generic'if 'attribute_encoding_profile'in settings else
                    'typed'if name in ('DEPCNT','SLCONS','DEPARE','DRGARE')else'raw'),depth=None,sample=None)
            common['structuralAttributes']=[a for a in attrrows if a['encodingStatus']=='STRUCTURAL_PENDING_GEOMETRY']
            if kind in (24, 32):
                parts = _parts(f)
                row.update(sourcePartCount=len(parts), sourceVertexCount=sum(map(len, parts)),
                    sourceRingCount=len(g.get('rings', [])), sourceInteriorRingCount=sum(
                        r.get('role') == 'interior' for r in g.get('rings', [])))
                atoms.append(dict(common, parts=parts,part_metadata=(
                    [dict(sourceRingId=r['id'],sourceRingRole=r['role'])for r in g['rings']]
                    if kind==24 else [{}for _ in parts]),polygon_roles=(deepcopy(g['polygons'])if kind==24 else []),
                    projected=[_projection_xy(geo.forward_model(p),settings) for part in parts for p in part]))
            elif kind == 40:
                point = cache._degrees(g.get('position'))
                atoms.append(dict(common, point=point, projected=[geo.forward_model(point)['sourceI32XY']]))
            else:
                points = g.get('points')
                if type(points) is not list or not points:
                    raise ValueError('NONEMPTY_SOUNDING_SAMPLES_REQUIRED')
                for ordinal, point in enumerate(points):
                    try:
                        if type(point) is not dict or type(point.get('ordinal')) is not int or point['ordinal'] != ordinal:
                            raise ValueError('IR_SAMPLE_ORDINAL_NOT_SOURCE_ORDER')
                        target = sid + '#p' + str(ordinal)
                        if len(target) > 128 or target in reserved:
                            raise ValueError('SPLIT_SOURCE_ID_COLLISION_OR_LENGTH')
                        reserved.add(target)
                        depth = point.get('depth', {}); unit = depth.get('unit', {})
                        if (depth.get('meaning') != 'depth' or unit.get('value') != 'm' or
                            unit.get('state') not in ('verified', 'inferred')):
                            raise ValueError('SOURCE_METRE_DEPTH_REQUIRED')
                        if unit['state'] == 'inferred' and settings['inferred_depth_unit_policy'] != 'assume_metres_and_report':
                            raise ValueError('INFERRED_DEPTH_UNIT_NOT_ACCEPTED')
                        quantized = cache.quantize_depth_metres(depth.get('value'),
                            max_error_metres=settings['max_depth_error_metres'])
                        p = cache._degrees(point.get('position'))
                        atoms.append(dict(common, source_id=target, sample=ordinal, point=p,
                            projected=[geo.forward_model(p)['sourceI32XY']], depth=dict(quantized,
                                sourceId=sid, sourceSampleOrdinal=ordinal, emittedSourceId=target,
                                inputUnitState=unit['state'], inferredMetresAssumed=unit['state']=='inferred', datumEncoded=False)))
                    except (ValueError, TypeError, ArithmeticError, AttributeError) as error:
                        base._refuse(row, error, ordinal)
        except (ValueError, TypeError, ArithmeticError, AttributeError) as error:
            base._refuse(row, error)
    return atoms
