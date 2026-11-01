import io
import json
import struct
import zipfile
from unittest.mock import patch

import pytest
from shapely.geometry import Polygon, box

from no_chart_converter.cache_fill import (MANIFEST, PI_MANIFEST, PROVIDER,
    aspect_frame, canonical, digest, fill_cache, grid_bounds, senc_background)


def records(raw):
    pos = 0
    while pos < len(raw):
        kind, size = struct.unpack_from('<HI', raw, pos)
        assert size >= 6 and pos + size <= len(raw)
        yield kind, raw[pos+6:pos+size]
        pos += size


def test_grid_contains_all_sources_and_uses_global_origin():
    assert grid_bounds([dict(west=4.5,east=38.5,south=-47.0666667,north=-16.3333333)]) == dict(west=0,east=40,south=-50,north=-10)
    with pytest.raises(ValueError):
        grid_bounds([dict(west=0,east=1,south=0,north=1)], 7)


def test_display_aspect_uses_projected_height_and_preserves_original_rectangle():
    import math
    original=dict(west=0,east=40,south=-50,north=-10)
    frame=aspect_frame(original,(2000,1070));b=frame['bounds']
    merc=lambda lat:math.asinh(math.tan(math.radians(lat)))
    assert math.radians(b['east']-b['west'])/(merc(b['north'])-merc(b['south']))==pytest.approx(2000/1070,abs=1e-12)
    assert b['west']==pytest.approx(-24.72590792498247)
    assert b['east']==pytest.approx(64.72590792498247)
    assert b['south']==pytest.approx(-50) and b['north']==pytest.approx(-10)
    assert frame['center']['latitude']==pytest.approx(-32.14670140048012)
    assert grid_bounds([b])==dict(west=-30,east=70,south=-50,north=-10)
    portrait=aspect_frame(original,(1070,2000))['bounds']
    assert portrait['west']==pytest.approx(0) and portrait['east']==pytest.approx(40)
    assert portrait['south'] < -50 and portrait['north'] > -10
    with pytest.raises(ValueError):aspect_frame(original,(0,1070))
    with pytest.raises(ValueError):aspect_frame(dict(west=100,east=170,south=-80,north=80),(2000,1070))


def test_aspect_export_stays_within_existing_strict_import_contract(tmp_path):
    src=tmp_path/'source.zip';original=fixture(src);out=tmp_path/'landscape.zip'
    with patch('no_chart_converter.cache_fill.read_land',return_value=(Polygon(),0)):
        result=fill_cache(src,tmp_path/'land',out,view_aspect=(2000,1070))
    assert result['backgroundCells']==40 and result['totalCells']==41
    with zipfile.ZipFile(out) as z:
        assert all(z.read(n)==b for n,b in original.items())
        manifest=json.loads(z.read(MANIFEST))
        assert 'displayFrame' not in manifest
        assert manifest['bounds']==dict(west=-30,east=70,south=-50,north=-10)
        for cell in manifest['cells']:
            e=cell['extent']
            assert e['east']-e['west']==e['north']-e['south']==10
            assert e['west']%10==e['south']%10==0
            origin=json.loads(z.read('provenance/'+cell['name']+'.json'))
            assert origin['displayFrame']==result['displayFrame']
            assert digest(z.read('provenance/'+cell['name']+'.json'))==cell['files']['provenance/'+cell['name']+'.json']


def test_holes_are_triangulated_and_tile_cuts_never_become_coasts():
    land = Polygon([(-1,-1),(11,-1),(11,11),(-1,11)], holes=[[(2,2),(4,2),(4,4),(2,4)]])
    raw, count, triangles = senc_background('NO5BG_N00_E000', (0,0,10,10), land, land.boundary)
    rows=list(records(raw)); classes=[struct.unpack('<HHB',b)[0] for k,b in rows if k==64]
    assert set(classes)=={119,71,30} and len(classes)==count and triangles>0
    # Four genuine lake-hole segments; no 0/10-degree tile-edge coast.
    assert classes.count(30)==1
    assert not any(k==65 for k,b in rows)  # No invented depths or survey attributes.
    assert sum(k==82 for k,b in rows)==2


def test_ocean_cell_has_real_geometry_and_no_coast():
    raw,count,triangles=senc_background('NO5BG_S50_E000',(0,-50,10,-40),Polygon(),Polygon())
    assert count==1 and triangles==2
    assert [struct.unpack('<HHB',b)[0] for k,b in records(raw) if k==64]==[119]


def test_disconnected_equal_contours_share_one_feature_without_joining_edges():
    from shapely.geometry import MultiLineString
    from no_chart_converter.cache_decoder import parse_senc
    geometry=MultiLineString([[(1,1),(2,2),(3,2)],[(7,7),(8,8)]])
    raw,_,_=senc_background('TEST',(0,0,10,10),Polygon(),Polygon(),terrain={500:geometry})
    decoded=parse_senc(raw,cell_id='TEST',member='test.senc')
    features=[f for f in decoded['features'] if f['classCode']==72]
    assert len(features)==1
    refs=features[0]['geometry']['refs'];assert len(refs)==2
    assert [len(decoded['edges'][ref[1]]['coordinatesSm']) for ref in refs]==[3,2]
    assert {a['code']:a['value'] for a in features[0]['attributes']}[90]==500


def fixture(path):
    payload=b'synthetic private byte-preservation fixture'
    proof=b'{"helperStatus":{"decrypt_status":1,"expire_status":1}}'
    row=dict(name='ZA-TEST',base='cells/ZA-TEST.senc',edition=1,update=0,issueDate='20261025',
        generation='a'*64,featureCount=1,extent=dict(west=4.5,east=38.5,south=-47.0666667,north=-16.3333333),
        files={'cells/ZA-TEST.senc':digest(payload),'provenance/ZA-TEST.json':digest(proof)})
    files={PI_MANIFEST:canonical(dict(format='no5-pi-map-cache-v1',product='Synthetic test',licenseHost='Test',
        exportedUtc='2026-10-11T12:00:00Z',authorization='Synthetic fixture',cells=[row])),
        'cells/ZA-TEST.senc':payload,'provenance/ZA-TEST.json':proof}
    with zipfile.ZipFile(path,'w') as z:
        for n,b in files.items():z.writestr(n,b)
    return files


def test_composite_preserves_every_source_byte_and_marks_public_origin(tmp_path):
    src=tmp_path/'source.zip';files=fixture(src);out=tmp_path/'filled.zip'
    with patch('no_chart_converter.cache_fill.read_land', return_value=(box(15,-35,25,-25),0)):
        result=fill_cache(src,tmp_path/'unused-land.zip',out)
    assert result['totalCells']==17 and result['backgroundCells']==16
    with zipfile.ZipFile(out) as z:
        for n,b in files.items():assert z.read(n)==b
        m=json.loads(z.read(MANIFEST));assert m['backgroundProvider']==PROVIDER
        for c in m['cells']:
            for n,h in c['files'].items():assert digest(z.read(n))==h
            proof=json.loads(z.read('provenance/'+c['name']+'.json'))
            assert 'helperStatus' not in proof and proof['license']=='Public Domain'
    with pytest.raises(ValueError,match='FRESH'):
        fill_cache(src,tmp_path/'unused',out)


def test_undeclared_or_modified_source_is_rejected(tmp_path):
    src=tmp_path/'source.zip';fixture(src)
    with zipfile.ZipFile(src,'a') as z:z.writestr('unexpected.xml',b'x')
    with pytest.raises(ValueError,match='UNEXPECTED'):
        fill_cache(src,tmp_path/'unused',tmp_path/'out.zip')


def test_bathymetry_uses_actual_depth_attributes_without_tile_edge_isobaths():
    from no_chart_converter.cache_fill import BATHY_DEPTHS
    bathy = {depth: Polygon() for depth in BATHY_DEPTHS}
    bathy[0] = box(-1,-1,11,11)
    bathy[200] = box(-1,-1,5,11)
    bathy[1000] = box(-1,-1,2,11)
    raw, count, _ = senc_background('NO5BG_N00_E000',(0,0,10,10),Polygon(),Polygon(),bathy)
    features=[]
    for kind,body in records(raw):
        if kind==64:features.append({'class':struct.unpack_from('<H',body)[0],'attrs':{}})
        if kind==65:
            code,wire=struct.unpack_from('<HB',body)
            features[-1]['attrs'][code]=struct.unpack_from('<d',body,3)[0] if wire==2 else body[3:-1].decode()
    assert len(features)==count
    bands=[f['attrs'] for f in features if f['class']==42]
    assert sorted((a[87],a[88]) for a in bands)==[(0,200),(200,1000),(1000,2000)]
    contours=[f for f in features if f['class']==43]
    assert len(contours)==2 and {f['attrs'][174] for f in contours}=={200,1000}
    assert all('coarse reference bathymetry' in f['attrs'][102] for f in features if f['class'] in (42,43))
    assert not any(f['class']==129 for f in features)  # No survey soundings invented.
    # Genuine threshold boundaries are x=2 and x=5; no tile-cut x/y=0 or10 contours.
    from no_chart_converter.cache_decoder import parse_senc
    from no_chart_converter.cache_fill import RADIUS
    import math
    decoded=parse_senc(raw,cell_id='tile',member='tile.senc')
    for feature in decoded['features']:
        if feature['classCode'] != 43:continue
        attrs={a['code']:a['value'] for a in feature['attributes']}
        _,edge_id,_=feature['geometry']['refs'][0]
        coords=decoded['edges'][edge_id]['coordinatesSm']
        expected_lon=5 if attrs[174]==200 else 2
        expected_x=math.radians(expected_lon-5)*RADIUS
        assert all(x==pytest.approx(expected_x,abs=.02) for x,y in coords)
        assert len(coords)==2  # One genuine vertical contour; no tile-border connector.
        assert coords[0][1] != coords[1][1]


def test_bathymetry_provenance_is_extensible_for_released_v2_importer(tmp_path):
    from no_chart_converter.cache_fill import BATHY_DEPTHS, BATHY_SHA256
    src=tmp_path/'source.zip';files=fixture(src);out=tmp_path/'filled.zip'
    bathy={d:Polygon() for d in BATHY_DEPTHS};bathy[0]=box(-1,-51,41,-9)
    with patch('no_chart_converter.cache_fill.read_land',return_value=(Polygon(),0)), patch(
            'no_chart_converter.cache_fill.read_bathymetry',return_value=(bathy,0)):
        result=fill_cache(src,tmp_path/'land',out,bathymetry_archive=tmp_path/'bathy')
    assert result['bathymetryIncluded']
    with zipfile.ZipFile(out) as z:
        for name,raw in files.items():assert z.read(name)==raw
        manifest=json.loads(z.read(MANIFEST))
        assert 'bathymetry' not in manifest  # Released strict manifest contract remains unchanged.
        for cell in manifest['cells']:
            proof=json.loads(z.read('provenance/'+cell['name']+'.json'))
            assert proof['bathymetry']['sourceSHA256']==BATHY_SHA256
            assert proof['bathymetry']['version']=='4.1.0' and 'helperStatus' not in proof


def test_deepest_source_threshold_has_no_invented_upper_depth():
    from no_chart_converter.cache_fill import BATHY_DEPTHS
    from no_chart_converter.cache_decoder import parse_senc
    bathy={d:Polygon() for d in BATHY_DEPTHS};bathy[10000]=box(-1,-1,11,11)
    raw,_,_=senc_background('NO5BG_N00_E000',(0,0,10,10),Polygon(),Polygon(),bathy)
    features=parse_senc(raw,cell_id='tile',member='tile.senc')['features']
    bands=[f for f in features if f['classCode']==42]
    assert len(bands)==1
    attrs={a['code']:a['value'] for a in bands[0]['attributes']}
    assert attrs[87]==10000 and 88 not in attrs
