import json
import zipfile
from unittest.mock import patch

import pytest
from shapely.geometry import Polygon, box

from no_chart_converter.cache_decoder import parse_senc
from no_chart_converter.cache_fill import MANIFEST, digest, fill_cache, senc_background
from no_chart_converter.terrain import ETOPO_URL, read_contours
from test_cache_fill import fixture


def grid(tmp_path, **changes):
    np = pytest.importorskip('numpy')
    pytest.importorskip('contourpy')
    x = np.arange(-2, 63)/60
    y = np.arange(-2, 63)/60
    z = np.broadcast_to(x*1000, (len(y),len(x))).copy()
    meta = dict(format='no5-etopo-elevation-grid-v1', sourceUrl=ETOPO_URL,
        version='2022-v1-60s', license='CC0-1.0', horizontalDatum='WGS84',
        verticalDatum='EGM2008', units='metres', positive='up')
    data = dict(lon=x, lat=y, z=z, metadata=json.dumps(meta))
    data.update(changes)
    path=tmp_path/'elevation.npz'
    np.savez_compressed(path, **data)
    return path, digest(path.read_bytes())


BOUNDS=dict(west=0,south=0,east=1,north=1)


def test_contours_follow_actual_height_and_keep_land_holes(tmp_path):
    path,pin=grid(tmp_path)
    land=box(0,0,1,1).difference(box(.4,.4,.6,.6))
    contours,origin=read_contours(path,pin,BOUNDS,land)
    assert set(contours)=={250,500,750,1000}
    assert contours[500].length==pytest.approx(.8)
    assert all(p[0]==pytest.approx(.5) for g in contours[500].geoms for p in g.coords)
    assert origin['verticalDatum']=='EGM2008' and origin['subsetSHA256']==pin
    assert origin['contourStepMetres']==250


def test_grid_pin_axes_and_vertical_reference_are_checked(tmp_path):
    np=pytest.importorskip('numpy')
    path,pin=grid(tmp_path)
    with pytest.raises(ValueError,match='SHA256'):
        read_contours(path,'0'*64,BOUNDS,box(0,0,1,1))
    path,pin=grid(tmp_path,lat=np.arange(62,-3,-1)/60)
    with pytest.raises(ValueError,match='METADATA'):
        read_contours(path,pin,BOUNDS,box(0,0,1,1))
    path,pin=grid(tmp_path,metadata=json.dumps(dict(format='no5-etopo-elevation-grid-v1',
        sourceUrl=ETOPO_URL,verticalDatum='Unknown',units='metres',positive='up')))
    with pytest.raises(ValueError,match='METADATA'):
        read_contours(path,pin,BOUNDS,box(0,0,1,1))


def test_missing_samples_do_not_bridge_contours(tmp_path):
    np=pytest.importorskip('numpy')
    z=np.broadcast_to(np.arange(-2,63)/60*1000,(65,65)).copy()
    z[25:40,:]=np.nan
    path,pin=grid(tmp_path,z=z)
    contours,_=read_contours(path,pin,BOUNDS,box(0,0,1,1))
    assert contours[500].geom_type=='MultiLineString'
    assert contours[500].length<.8


def test_senc_land_elevations_keep_primitives_values_and_single_point_owner(tmp_path):
    path,pin=grid(tmp_path)
    land=box(0,0,1,1)
    contours,_=read_contours(path,pin,BOUNDS,land)
    point=dict(longitude=.5,latitude=.5,elevation=873,name='Source summit',scaleRank=5)
    all_features=[]
    for west,east in ((0,.5),(.5,1)):
        raw,_,_=senc_background('TEST',(west,0,east,1),land,Polygon(),terrain=contours,elevation_points=[point])
        all_features.extend(parse_senc(raw,cell_id='TEST',member='test.senc')['features'])
    heights=[f for f in all_features if f['classCode']==72]
    peaks=[f for f in heights if f['primitive']==0]
    assert len(peaks)==1
    attrs={a['code']:a['value'] for a in peaks[0]['attributes']}
    assert attrs[90]==873 and attrs[116]=='Source summit'
    assert attrs[133]==20_000_000
    lines=[f for f in heights if f['primitive']==1]
    assert lines and all(f['geometry']['kind']=='line' for f in lines)
    assert all({a['code'] for a in f['attributes']}=={90,133,102} for f in lines)
    assert not any(f['classCode']==129 for f in all_features)


def test_terrain_origin_is_bound_without_changing_nautical_members_or_manifest_contract(tmp_path):
    src=tmp_path/'source.zip';original=fixture(src);out=tmp_path/'out.zip'
    origin=dict(sourceUrl=ETOPO_URL,license='CC0-1.0',verticalDatum='EGM2008')
    with patch('no_chart_converter.cache_fill.read_land',return_value=(Polygon(),0)), patch(
        'no_chart_converter.terrain.read_contours',return_value=({},origin)):
        result=fill_cache(src,tmp_path/'land',out,terrain_grid=tmp_path/'grid',terrain_sha256='f'*64)
    assert result['terrainIncluded'] and result['totalCells']==17
    with zipfile.ZipFile(out) as archive:
        assert all(archive.read(name)==data for name,data in original.items())
        manifest=json.loads(archive.read(MANIFEST))
        assert 'topography' not in manifest
        for cell in manifest['cells']:
            name='provenance/'+cell['name']+'.json';proof=json.loads(archive.read(name))
            assert proof['topography']==origin and 'helperStatus' not in proof
            assert digest(archive.read(name))==cell['files'][name]
