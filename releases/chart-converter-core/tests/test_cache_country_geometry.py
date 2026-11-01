"""Invented typed-IR controls; no chart-derived fixture or native claim."""
import math
from pathlib import Path
import struct
from fractions import Fraction

import pytest

from no_chart_converter.cache_country.geometry import (
    GeometryRefusal, MAX_LATITUDE, Q, encode_depth_metres,
    expand_source_triangles, prepare_feature_geometry, project_path, project_root,
)


def feature(kind, geometry):
    return {'sourceId':'FRESH:f17','sourceFeatureId':17,'sourcePrimitive':{'point':0,'multipoint':0,'line':1,'area':2}[kind],
            'classCode':129 if kind=='multipoint'else 1,'classAcronym':'SOUNDG'if kind=='multipoint'else'SYNTH',
            'attributes':[{'code':133,'value':123456}], 'geometry':{'kind':kind,**geometry}}


def area(outer=None, holes=(), triangles=None):
    outer=outer or [[0,0],[1,0],[1,1],[0,1],[0,0]]
    rings=[{'id':'r0','role':'exterior','coordinates':outer,'derivation':'topology','edgeRefOrdinals':[0]}]
    rings += [{'id':'r'+str(i+1),'role':'interior','coordinates':ring,'derivation':'topology','edgeRefOrdinals':[i+1]}for i,ring in enumerate(holes)]
    return feature('area',{'rings':rings,'polygons':[{'exteriorRingId':'r0','interiorRingIds':['r'+str(i+1)for i in range(len(holes))]}],
        'trianglePrimitives':triangles or [],'reconstruction':{'status':'exact_topology','issues':[]}})


def test_existing_world_projection_policy_and_explicit_poles():
    assert project_root([0,0])==(1073741825,1073741825)
    assert project_root([-180,MAX_LATITUDE])==(0,0)
    assert project_root([180,-MAX_LATITUDE])==(Q,Q)
    with pytest.raises(GeometryRefusal,match='OUTSIDE_SUPPORTED_MERCATOR'):
        project_root([0,89])


def test_dateline_refuses_connected_chord_without_moving_point():
    assert project_root([179.9,0])[0]>Q*.99
    assert project_root([-179.9,0])[0]<Q*.01
    with pytest.raises(GeometryRefusal,match='ANTIMERIDIAN_PATH'):
        project_path([[179.9,0],[-179.9,0]])


def test_existing_exact_signed_decimetres_policy():
    assert [encode_depth_metres(v)for v in [0,-1.25,12.375,.25,.75]]==[0,-12,124,2,8]
    for value in [float('nan'),float('inf'),True]:
        with pytest.raises(GeometryRefusal):encode_depth_metres(value)
    with pytest.raises(GeometryRefusal,match='INT32_RANGE'):encode_depth_metres(2**31)


def test_soundings_preserve_order_sign_datum_and_visible_quantization_receipt():
    datum={'state':'unknown','raw':'OWN DATUM'}
    points=[{'ordinal':i,'position':[i*.1,0],'depth':{'value':v,'meaning':'depth','unit':{'state':'inferred','value':'m'},'datum':datum}}for i,v in enumerate([0,-1.25,12.375])]
    result=prepare_feature_geometry(feature('multipoint',{'points':points}))
    assert result['kind']==4
    assert struct.unpack_from('<I',result['coordinates'])[0]==0x01000003
    assert [struct.unpack_from('<i',result['coordinates'],4+12*i+8)[0]for i in range(3)]==[0,-12,124]
    rows=result['receipt']['depthRows']
    assert rows[2]['absoluteErrorMetresExact']==[1,40]
    assert rows[1]['absoluteErrorMetresExact']==[1,20]
    assert all(row['datum']==datum for row in rows)
    assert result['sourceSCAMINValues']==[123456]


def test_disconnected_directed_lines_remain_distinct_elements():
    f=feature('line',{'parts':[{'coordinates':[[0,0],[.1,0],[.2,0]],'edgeRefOrdinals':[0]},
                               {'coordinates':[[1,1],[2,1]],'edgeRefOrdinals':[1]}],
                       'reconstruction':{'status':'exact_topology','issues':[]}})
    result=prepare_feature_geometry(f,programmes=[b'LS(SOLD,1,OWNCL);'])
    assert result['indexOccurrences']==2
    assert [w>>24 for w, in struct.iter_unpack('<I',result['indices'][4:])]==[16,16]
    assert result['pointOccurrences']==5
    assert not result['receipt']['geometryRepair']


def test_source_triangle_modes_expand_with_strip_parity():
    primitive={'mode':5,'coordinates':[[0,0],[1,0],[0,1],[1,1]]}
    triangles,nulls=expand_source_triangles([primitive])
    assert [row['sourceVertexOrdinals']for row in triangles]==[[0,1,2],[2,1,3]]
    assert not nulls
    triangles,_=expand_source_triangles([{'mode':6,'coordinates':[[0,0],[1,0],[1,1],[0,1]]}])
    assert [r['sourceVertexOrdinals']for r in triangles]==[[0,1,2],[0,2,3]]


def test_fill_only_keeps_source_triangles_masked_and_hole_empty():
    # Four rectangles around an unfilled central square; no invented rings.
    rectangles=[(0,0,3,1),(0,2,3,3),(0,1,1,2),(2,1,3,2)]
    coords=[]
    for x0,y0,x1,y1 in rectangles:
        a,b,c,d=[x0,y0],[x1,y0],[x1,y1],[x0,y1]
        coords.extend([a,b,c,a,c,d])
    f=feature('area',{'trianglePrimitives':[{'mode':4,'coordinates':coords}],
                     'reconstruction':{'status':'unresolved','issues':['raw nautical ring not exported']},'rings':[],'polygons':[]})
    result=prepare_feature_geometry(f,programmes=[b'AC(OWNCL);'])
    assert result['indexOccurrences']==8
    assert set(result['receipt']['indexHigh8Roles'])=={8}
    from shapely.geometry import Point
    assert not result['fillGeometry'].covers(Point(project_root([1.5,1.5])))
    assert result['fillGeometry'].covers(Point(project_root([.5,.5])))
    assert not result['receipt']['triangulationInvented']


def test_exact_boundary_only_ring_keeps_hole_roles_without_new_fill():
    f=area(holes=[[[.25,.25],[.25,.75],[.75,.75],[.75,.25],[.25,.25]]])
    result=prepare_feature_geometry(f,programmes=[b'LS(DASH,1,OWNCL);'])
    assert result['receipt']['indexHigh8Roles']==[24,20]
    assert result['fillGeometry'].is_empty


def test_mixed_area_refuses_unequal_source_triangle_and_boundary_fill():
    f=area(triangles=[{'mode':4,'coordinates':[[0,0],[1,0],[1,1]]}])
    with pytest.raises(GeometryRefusal,match='FILL_MISMATCH_REQUIRES_KIND8'):
        prepare_feature_geometry(f,programmes=[b'AC(OWNCL);LS(SOLD,1,OWNCL);'])


def test_no_silent_kind8_to_kind3_conversion():
    with pytest.raises(GeometryRefusal,match='KIND8_OR_GEOMETRY_KIND_MUTATION'):
        prepare_feature_geometry(area(),programmes=[b'LS(SOLD,1,OWNCL);'],target_kind=8)


def _preparation_context(features):
    import hashlib
    import json
    from no_chart_converter.country_runtime.render_activation import encode_frame
    cells,owners,by_cell,meta,paint,styles=[],[],{},{},{},{}
    for i,f in enumerate(features):
        cid='FRESH-'+str(i)
        f['sourceId']=cid+':f'+str(f['sourceFeatureId'])
        cell={'id':cid,'nativeScale':100000+i,'features':[f],
              'coverage':{'covered':[{'coordinates':[[-2,-2],[12,-2],[12,12],[-2,12],[-2,-2]]}],'excluded':[]}}
        uid=((i+1)<<32)|f['sourceFeatureId']
        digest=hashlib.sha256(json.dumps(f,sort_keys=True,separators=(',',':'),ensure_ascii=False,allow_nan=False).encode()).hexdigest()
        owner={'cellId':cid,'cellOrdinal':i,'sourceOwnerOrdinal':0,'uid':uid,'feature':f,'featureSourceDigest':digest}
        cells.append(cell);owners.append(owner);by_cell[cid]=[owner]
        meta[cid]={'additional':bytes(14),'additionalOffsets':{uid:0},'dictionaryIndices':{uid:0}}
        program=b'SY(OWN00001);'if f['geometry']['kind']=='point'else b'LS(SOLD,1,OWNCL);'
        paint[cid]={'render':encode_frame(0,{196608:program,196609:program}),'renderOffsets':{uid:0}}
        styles[uid]={'role':'point'if f['geometry']['kind']=='point'else'ground',
                     'kind':1 if f['geometry']['kind']=='point'else 3,
                     'nativeScaminLevel':0,'sourceSCAMIN':['123456'],
                     'resolution':{'status':'resolved_fresh_source_presentation','displayPriority':1}}
    return {'format':'no5-cache-country-context/1','cells':cells,'owners':owners,'by_cell':by_cell}, {'cells':meta}, {'cells':paint,'owners':styles}


def test_prepared_uid_uses_dense_selected_source_ordinal_and_keeps_original_ids(tmp_path):
    import json
    from no_chart_converter.cache_country.geometry import prepare_geometry
    ctx,metadata,presentation=_preparation_context([
        feature('point',{'position':[0,0]}),feature('point',{'position':[10,10]})])
    result=prepare_geometry(ctx,metadata,presentation,tmp_path/'geometry')
    assert result['expectedSourceCounts']=={'sourceCells':2,'groundOwners':0,'pointOwners':2,'detailOwners':0}
    point_inputs=json.loads(open(result['pins']['pointInputs']['path']).read())
    for i,row in enumerate(point_inputs['cells']):
        bindings=json.loads(open(row['ownerBindings']['path']).read())['owners']
        assert bindings[0]['uid']==((i+1)<<32)|17
        assert bindings[0]['sourceFeatureId']==17
        assert bindings[0]['sourceId']==f'FRESH-{i}:f17'
        assert bindings[0]['featureSourceDigest']==ctx['owners'][i]['featureSourceDigest']
    fill=json.loads(open(result['fillAvailability']['path']).read())
    assert fill['ownersKind3']==0


def test_area_kind_alone_never_becomes_ground_fill_availability(tmp_path):
    from no_chart_converter.cache_country.geometry import prepare_geometry
    ctx,metadata,presentation=_preparation_context([area()])
    with pytest.raises(GeometryRefusal,match='GROUND_KIND3_REQUIRES_ACTIVE_AC_AP'):
        prepare_geometry(ctx,metadata,presentation,tmp_path/'geometry')
    assert not (tmp_path/'geometry').exists()


@pytest.mark.parametrize('programme', [
    b'',
    b'SY(CTNARE51);LS(DASH,2,TRFCD);',
    b'AP(DQUALB01);LS(DASH,2,CHGRD);',
    b"TX(OBJNAM,3,1,2,'15110',1,0,CHMGD,29);LS(DASH,2,CHMGD);SY(INFARE51);",
    b"AC(LANDA);TX(OBJNAM,1,2,3,'15118',-1,-1,CHBLK,26);",
])
def test_separate_source_triangle_main_and_boundary_never_mix_hit_areas(programme):
    from no_chart_converter.cache_country.geometry import prepare_area_roles, ANCHOR_POLICY
    from no_chart_converter.cache_country.presentation import split_commands
    f=area(triangles=[{'mode':4,'coordinates':[[0,0],[1,0],[1,1]]}])
    result=prepare_area_roles(f,{196608:programme,196609:programme})
    main,boundary=result['main'],result['boundary']
    assert main['kind']==3 and boundary['kind']==2
    assert main['receipt']['indexHigh8Roles']==[8]
    assert boundary['receipt']['indexHigh8Roles']==[16]
    assert main['pointOccurrences']==4 and boundary['pointOccurrences']==5
    # Query geometry is one original triangle, never the sum of a topological
    # exterior and a second overlapping exterior (which changes native hits).
    for obj,expected in [(main,[[0,0],[1,0],[1,1],[0,0]]),
                         (boundary,f['geometry']['rings'][0]['coordinates'])]:
        offset=struct.unpack_from('<I',obj['indices'],4)[0]&0xffffff
        count=struct.unpack_from('<I',obj['coordinates'],offset)[0]&0xffffff
        assert list(struct.iter_unpack('<II',obj['coordinates'][offset+4:offset+4+8*count]))==[project_root(point)for point in expected]
    for key in (196608,196609):
        before=split_commands(programme.decode())
        assert split_commands(result['mainProgrammes'][key].decode())==[t for t in before if not t.startswith(('LS(','LC('))]
        assert split_commands(result['boundaryProgrammes'][key].decode())==[t for t in before if t.startswith(('LS(','LC('))]
    anchor=result['receipt']['anchor'];root=[project_root(p)for p in f['geometry']['rings'][0]['coordinates']]
    assert anchor['policy']==ANCHOR_POLICY and anchor['meanDenominatorPoints']==4
    assert anchor['exactWorldNumerators']==[sum(p[i]for p in root[:-1])for i in(0,1)]
    assert anchor['exactWorldDenominator']==4*Q
    assert struct.unpack('<dd',result['mainProgrammes'][65682])==tuple(n/(4*Q)for n in anchor['exactWorldNumerators'])
    assert 65680 not in result['mainProgrammes']
    assert not anchor['originalProviderAnchorClaim']
    assert not anchor['originalViewDependentF32FallbackGlobalBitParityClaim']


def test_pure_fill_needs_no_boundary_topology_or_derived_records():
    from no_chart_converter.cache_country.geometry import prepare_area_roles
    f=feature('area',{'trianglePrimitives':[{'mode':4,'coordinates':[[0,0],[1,0],[1,1]]}],
                     'rings':[],'polygons':[],'reconstruction':{'status':'unresolved','issues':['none exported']}})
    result=prepare_area_roles(f,{196608:b'AC(LANDA);',196609:b'AP(DQUALB01);'})
    assert result['boundary'] is None
    assert 65682 not in result['mainProgrammes']
    assert result['main']['receipt']['indexHigh8Roles']==[8]


def test_companions_append_after_all_primaries_with_exact_query_and_registry_bindings(tmp_path):
    import copy
    import hashlib
    import json
    from no_chart_converter.cache_country.geometry import prepare_geometry, _programmes
    from no_chart_converter.country_runtime.render_activation import encode_frame
    from no_chart_converter.rx5.wire import FEATURE
    fs=[area(triangles=[{'mode':4,'coordinates':[[0,0],[1,0],[1,1]]}]),
        feature('point',{'position':[.5,.5]}),
        area(triangles=[{'mode':4,'coordinates':[[0,0],[1,0],[1,1]]}])]
    programmes=[b'AP(DQUALB01);LS(DASH,2,CHGRD);',b'SY(OWN00001);',b'']
    ctx,metadata,presentation=_preparation_context([fs[0]])
    cid='FRESH-0';ctx['cells'][0]['features']=fs;ctx['owners']=[];ctx['by_cell'][cid]=[]
    render=bytearray();offsets={};styles={};registry=[]
    for i,(f,program) in enumerate(zip(fs,programmes)):
        f['sourceFeatureId']=i+100;f['sourceId']=f'{cid}:f{i+100}'
        uid=(1<<32)|f['sourceFeatureId'];digest=hashlib.sha256(json.dumps(f,sort_keys=True,separators=(',',':'),ensure_ascii=False).encode()).hexdigest()
        owner={'cellId':cid,'cellOrdinal':0,'sourceOwnerOrdinal':i,'uid':uid,'feature':f,'featureSourceDigest':digest}
        ctx['owners'].append(owner);ctx['by_cell'][cid].append(owner)
        metadata['cells'][cid]['additionalOffsets'][uid]=0;metadata['cells'][cid]['dictionaryIndices'][uid]=0
        offsets[uid]=len(render);render.extend(encode_frame(0,{196608:program,196609:program}))
        key=f'key-{i}';kind=1 if i==1 else 3
        selected={'effectiveSourceKeySHA256':key,'effectiveStyleID':i+1,'sourceTable':'PLAIN_BOUNDARIES','sourceRCID':1,'sourcePrimitive':kind}
        styles[uid]={'role':'point'if i==1 else'detail','kind':kind,'nativeScaminLevel':7,'sourceSCAMIN':['123456'],
                     'resolution':{'status':'resolved_fresh_cache_presentation','displayPriority':2,'selected':selected,
                                   'featureSourceDigest':digest,'profileSHA256':'profile'}}
        registry.append({'keySHA256':key,'ownEffectiveStyleID':i+1,'profileSHA256':'profile','classAcronym':f['classAcronym'],
                         'sourceTable':'PLAIN_BOUNDARIES','sourceRCID':1,'sourcePrimitive':kind,'actualAfterPriority':2,'actualAfterCategory':79,
                         'effectiveCandidateINSTHex':program.hex()})
    registry_path=tmp_path/'registry.json';raw=json.dumps(registry).encode();registry_path.write_bytes(raw)
    presentation={'cells':{cid:{'render':bytes(render),'renderOffsets':offsets}},'owners':styles,
                  'pins':{'sourceRegistry':{'path':str(registry_path),'bytes':len(raw),'sha256':hashlib.sha256(raw).hexdigest()}}}
    original=copy.deepcopy(ctx)
    result=prepare_geometry(ctx,metadata,presentation,tmp_path/'geometry')
    assert ctx==original
    assert result['sourcePrimaryFeatures']==3 and result['authoredRepresentationRecords']==5
    assert result['derivedDrawRecords']==2 and result['derivedVisibleDrawRecords']==1
    catalog=json.loads(Path(result['groundCatalog']['path']).read_text())
    records=Path(catalog['cells'][0]['compiledStreams']['features']['path']).read_bytes()
    fields=list(FEATURE.iter_unpack(records));uids=[row[0]for row in fields]
    primaries=[owner['uid']for owner in ctx['owners']]
    assert uids==primaries+[primaries[0]|0x80000000,primaries[2]|0x80000000]
    assert [row[6]for row in fields]==[3,1,3,2,2]
    rawrender=Path(catalog['cells'][0]['compiledStreams']['render']['path']).read_bytes()
    assert _programmes(rawrender,fields[0][2])[0][196608]==b'AP(DQUALB01);'
    assert _programmes(rawrender,fields[3][2])[0][196608]==b'LS(DASH,2,CHGRD);'
    assert _programmes(rawrender,fields[2][2])[0][196608]==b''
    assert _programmes(rawrender,fields[4][2])[0][196608]==b''
    assert all(_programmes(rawrender,row[2])[1]==0 for row in fields)
    relationships=json.loads(Path(result['primarySourceRelationships']['path']).read_text())['rows']
    assert [r['nativeSourceOwnerOrdinal']for r in relationships]==[3,4]
    assert [r['sourceOriginalOwnerOrdinal']for r in relationships]==[0,2]
    assert all(r['queryAttrsAliasPrimary']for r in relationships)
    new_registry=json.loads(Path(result['presentationOverrides']['sourceRegistry']['path']).read_text())
    assert len(new_registry)==7 and len({r['keySHA256']for r in new_registry})==7
    assert registry_path.read_bytes()==raw


def _position_from_computed_root(x,y):
    return [x/Q*360-180,math.degrees(math.atan(math.sinh(math.pi*(1-2*y/Q))))]


def test_exact_source_sm_null_connector_never_becomes_phantom_root_fill():
    # Distinct source-SM vertices on one exact line. Float64 inverse WM followed
    # by Root rounding may give different integer steps; it must remain null.
    r=6378137.0;refx=r*math.radians(20);refy=r*math.asinh(math.tan(math.radians(-34)))
    sm=[[0,0],[.1,.1],[.2,.2]]
    positions=[[math.degrees((refx+x)/r),math.degrees(math.atan(math.sinh((refy+y)/r)))]for x,y in sm]
    primitive={'mode':4,'coordinates':[[0,0],[1,0],[1,1]]+positions,
               'coordinatesSm':[[0,0],[1,0],[1,1]]+sm}
    result,nulls=expand_source_triangles([primitive])
    assert len(result)==1 and len(nulls)==1
    assert nulls[0]['reason']=='SOURCE_COLLINEAR_NULL_TRIANGLE'
    assert nulls[0]['sourceTwiceAreaExact']==[0,1]
    assert nulls[0]['sourceVertexOrdinals']==[3,4,5]


def test_nonzero_source_triangle_collapsed_by_declared_root_rounding_is_receipted():
    tiny=[[0,0],[1e-10,0],[0,1e-10]]
    primitive={'mode':4,'coordinates':[[0,0],[1,0],[1,1]]+tiny}
    result,nulls=expand_source_triangles([primitive])
    assert len(result)==1 and len(nulls)==1
    assert nulls[0]['reason']=='ROOT_U32_QUANTIZATION_NULL_TRIANGLE'
    assert nulls[0]['rootTwiceAreaExact']==0
    assert nulls[0]['perAxisQuantizationBoundRootUnits']==.5
    assert nulls[0]['sourceOriginalBytesRetainedInIR']
    assert len(nulls[0]['rootPoints'])==4


def test_orientation_flip_keeps_original_vertex_order_and_exterior_role():
    a=1_000_000_000
    points=[[a+.49,a+.49],[a+.51,a+1.49],[a+1.49,a+1.51]]
    primitive={'mode':4,'coordinates':[_position_from_computed_root(x,y)for x,y in points],
               'coordinatesSm':[[x,-y]for x,y in points]}
    result,nulls=expand_source_triangles([primitive])
    assert not nulls and len(result)==1
    assert result[0]['root']==[(a,a),(a+1,a+1),(a+1,a+2),(a,a)]
    event=result[0]['quantizationEvent']
    assert event['reason']=='ROOT_U32_ORIENTATION_CHANGE_RETAIN_ORIGINAL_VERTEX_ORDER'
    assert not event['orientationUsedAsHoleRole']
    assert not event['newVerticesOrRepair']


def test_whole_quantized_owner_empty_remains_explicit_material_refusal():
    with pytest.raises(GeometryRefusal,match='NO_POSITIVE_SOURCE_TRIANGLE_FILL'):
        expand_source_triangles([{'mode':4,'coordinates':[[0,0],[1e-10,0],[0,1e-10]]}])


def test_coverage_even_odd_removes_zero_area_spur_without_bounds_or_tolerance():
    from no_chart_converter.cache_country.geometry import normalize_coverage_ring
    from shapely.geometry import Polygon
    positions=[[0,0],[2,0],[2,2],[1,2],[1,3],[1,2],[0,2],[0,0]]
    rings,event=normalize_coverage_ring(positions,source_id='INVENTED')
    assert len(rings)==1 and event
    assert Polygon(rings[0]).is_valid
    assert Polygon(rings[0]).area==Polygon([project_root(p)for p in positions]).area
    assert event['sourcePointCount']==len(positions)
    assert event['allNodedCoordinatesExactlyOnRootGrid']
    assert event['tolerance'] is None and not event['bufferOrSnappingOrBoundsRepair']
    assert event['sourceRootRing']==[project_root(p)for p in positions]


def test_coverage_self_crossing_uses_even_odd_faces_independent_of_winding():
    from no_chart_converter.cache_country.geometry import normalize_coverage_ring
    from shapely.geometry import Polygon
    a=1_000_000_000
    points=[[a,a],[a+8,a+8],[a,a+8],[a+8,a],[a,a]]
    positions=[_position_from_computed_root(x,y)for x,y in points]
    rings,event=normalize_coverage_ring(positions)
    reverse,reverse_event=normalize_coverage_ring(list(reversed(positions)))
    assert len(rings)==len(reverse)==2
    assert all(Polygon(r).is_valid for r in rings)
    assert sum(Polygon(r).area for r in rings)==32
    assert sum(Polygon(r).area for r in reverse)==32
    assert event['selectedOddFaces']==reverse_event['selectedOddFaces']==2


def test_coverage_subgrid_intersection_still_requires_explicit_policy():
    from no_chart_converter.cache_country.geometry import normalize_coverage_ring
    a=1_000_000_000
    positions=[_position_from_computed_root(x,y)for x,y in [[a,a],[a+3,a+4],[a,a+4],[a+3,a],[a,a]]]
    with pytest.raises(GeometryRefusal,match='SUBGRID_POLICY'):
        normalize_coverage_ring(positions)


def test_prepared_coverage_preserves_original_point_boundary_walk(tmp_path):
    import json
    from no_chart_converter.cache_country.geometry import prepare_geometry
    ctx,metadata,presentation=_preparation_context([feature('point',{'position':[0,0]})])
    coordinates=[[1,1],[2,1],[2,2],[1.5,2],[1.5,3],[1.5,2],[1,2],[1,1]]
    ctx['cells'][0]['coverage']['excluded']=[{'coordinates':coordinates}]
    result=prepare_geometry(ctx,metadata,presentation,tmp_path/'geometry')
    expected=[list(project_root(p))for p in coordinates]
    fixture=json.loads(Path(result['coverageFixture']['path']).read_text())
    assert fixture['sources'][0]['excluded'][0]==expected
    assert [list(p)for p in result['coverage'][0]['excluded'][0]]==expected
    receipt=json.loads(Path(result['receipt']['path']).read_text())
    event=receipt['coverageNormalizations'][0]
    assert event['sourceRootRing']==expected
    assert event['normalizationAppliesOnlyToAreaMasks']
    assert event['pointWinnerSourceWalkPreserved']
    assert event['outputRootRings'][0]!=expected
