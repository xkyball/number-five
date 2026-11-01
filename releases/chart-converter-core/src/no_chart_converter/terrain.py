"""Bounded public land relief inputs; no app/device or licensed-source actions."""
from __future__ import annotations
import hashlib
import io
import json
import math
from pathlib import Path
import tempfile
import os
import zipfile

ETOPO_URL = ('https://www.ngdc.noaa.gov/thredds/dodsC/global/ETOPO2022/60s/'
             '60s_bed_elev_netcdf/ETOPO_2022_v1_60s_N90W180_bed.nc')
ETOPO_DOI = 'https://doi.org/10.25921/fd45-gt74'
ETOPO_LICENSE = 'https://www.ncei.noaa.gov/access/metadata/landing-page/bin/iso?id=gov.noaa.ngdc.mgg.dem%3Aetopo_2022'
PEAK_URL = 'https://naciscdn.org/naturalearth/10m/physical/ne_10m_geography_regions_elevation_points.zip'
PEAK_SHA256 = 'c7465b5df43045c3b639ab26ce61128273c2391b597074077a55f06fe63e1c99'
PEAK_VERSION = '5.0.0'


def sha(raw):
    return hashlib.sha256(raw).hexdigest()


def fetch_grid(output, bounds):
    """Fetch just the region plus two grid points, not the 491 MB global DEM."""
    import numpy as np
    import netCDF4
    target = Path(output)
    if target.exists() or target.is_symlink():
        raise ValueError('FRESH_TERRAIN_OUTPUT_REQUIRED')
    w,s,e,n = bounds
    if not (-180 <= w < e <= 180 and -85 <= s < n <= 85 and (e-w)*(n-s) <= 5000):
        raise ValueError('TERRAIN_REGION_OUTSIDE_BOUNDED_DOMAIN')
    with netCDF4.Dataset(ETOPO_URL) as dataset:
        x = np.asarray(dataset['lon'][:]); y = np.asarray(dataset['lat'][:])
        margin = 2/60
        xi = np.flatnonzero((x >= w-margin)&(x <= e+margin))
        yi = np.flatnonzero((y >= s-margin)&(y <= n+margin))
        if len(xi)*len(yi) > 20_000_000:
            raise ValueError('TERRAIN_GRID_TOO_LARGE')
        source = dataset['z']
        if source.units != 'meters' or source.positive != 'up' or source.vert_crs_name != 'EGM2008':
            raise ValueError('ETOPO_UNITS_OR_VERTICAL_REFERENCE_CHANGED')
        z = source[int(yi[0]):int(yi[-1])+1,int(xi[0]):int(xi[-1])+1].filled(np.nan)
        x, y = x[xi], y[yi]
    meta = dict(format='no5-etopo-elevation-grid-v1', sourceUrl=ETOPO_URL, version='2022-v1-60s',
                citation=ETOPO_DOI, license='CC0-1.0', licenseUrl=ETOPO_LICENSE,
                horizontalDatum='WGS84', verticalDatum='EGM2008', units='metres', positive='up',
                requestedBounds=dict(west=w,south=s,east=e,north=n),
                sliceIndices=dict(xStart=int(xi[0]),xEndInclusive=int(xi[-1]),yStart=int(yi[0]),yEndInclusive=int(yi[-1])))
    target.parent.mkdir(parents=True,exist_ok=True)
    fd, temporary = tempfile.mkstemp(prefix='.terrain-',dir=target.parent)
    try:
        with os.fdopen(fd,'wb') as out:
            np.savez_compressed(out,lon=x,lat=y,z=z,metadata=json.dumps(meta,sort_keys=True))
            out.flush();os.fsync(out.fileno())
        os.link(temporary,target)
    finally:
        Path(temporary).unlink(missing_ok=True)
    return dict(output=str(target),bytes=target.stat().st_size,sha256=sha(target.read_bytes()),shape=list(z.shape),source=meta)


def read_contours(path, expected_sha256, bounds, land, step=250):
    import numpy as np
    import contourpy
    from shapely.geometry import LineString
    from shapely.ops import unary_union
    raw = Path(path).read_bytes()
    if expected_sha256 is None or sha(raw) != expected_sha256:
        raise ValueError('TERRAIN_GRID_SHA256_MISMATCH')
    with zipfile.ZipFile(io.BytesIO(raw)) as archive:
        if sum(m.file_size for m in archive.infolist()) > 128*1024*1024:
            raise ValueError('TERRAIN_GRID_TOO_LARGE')
    with np.load(io.BytesIO(raw),allow_pickle=False) as data:
        meta = json.loads(str(data['metadata']))
        x,y,z = data['lon'],data['lat'],data['z']
    if (meta.get('format') != 'no5-etopo-elevation-grid-v1' or meta.get('sourceUrl') != ETOPO_URL or
        meta.get('version') != '2022-v1-60s' or meta.get('license') != 'CC0-1.0' or
        meta.get('horizontalDatum') != 'WGS84' or
        meta.get('verticalDatum') != 'EGM2008' or meta.get('units') != 'metres' or meta.get('positive') != 'up' or
        x.ndim != 1 or y.ndim != 1 or len(x)<2 or len(y)<2 or
        z.shape != (len(y),len(x)) or z.size > 20_000_000 or
        not np.all(np.isfinite(x)) or not np.all(np.isfinite(y)) or
        not np.any(np.isfinite(z)) or np.any(np.isinf(z)) or
        not np.allclose(np.diff(x),1/60,atol=1e-9,rtol=0) or not np.allclose(np.diff(y),1/60,atol=1e-9,rtol=0) or
        not (x[0]<bounds['west'] and x[-1]>bounds['east'] and y[0]<bounds['south'] and y[-1]>bounds['north']) or
        not isinstance(step,int) or step < 100 or 1000%step != 0):
        raise ValueError('TERRAIN_GRID_METADATA_OR_DOMAIN_INVALID')
    generator = contourpy.contour_generator(x=x,y=y,z=z,name='serial',line_type='Separate',corner_mask=False)
    levels = range(step,int(math.floor(float(np.nanmax(z))/step))*step+1,step)
    contours = {}
    for height in levels:
        parts = [LineString(points) for points in generator.lines(height) if len(points)>1]
        contours[height] = unary_union(parts).intersection(land)
    origin = dict(sourceUrl=ETOPO_URL,version='2022-v1-60s',subsetSHA256=expected_sha256,
        citation=ETOPO_DOI,license='CC0-1.0',licenseUrl=ETOPO_LICENSE,
        horizontalDatum='WGS84',verticalDatum='EGM2008',units='metres',gridArcSeconds=60,
        contourStepMetres=step,contourAlgorithm='ContourPy serial linear interpolation',
        basis='Coarse ETOPO model heights; no invented spot elevations',
        display='1000 m major contours at overview, 500 m then 250 m on closer zoom; no relief shading')
    return contours,origin


def read_points(path, bounds, land):
    import shapefile
    from shapely.geometry import Point
    raw=Path(path).read_bytes()
    if sha(raw) != PEAK_SHA256:
        raise ValueError('NATURAL_EARTH_ELEVATION_POINTS_SHA256_MISMATCH')
    stem='ne_10m_geography_regions_elevation_points'
    with zipfile.ZipFile(io.BytesIO(raw)) as archive:
        if archive.read(stem+'.VERSION.txt').decode().strip() != PEAK_VERSION:
            raise ValueError('NATURAL_EARTH_ELEVATION_POINTS_VERSION_MISMATCH')
        reader=shapefile.Reader(shp=io.BytesIO(archive.read(stem+'.shp')),
            shx=io.BytesIO(archive.read(stem+'.shx')),dbf=io.BytesIO(archive.read(stem+'.dbf')))
        result=[]
        for item in reader.iterShapeRecords():
            lon,lat=item.shape.points[0];row=item.record.as_dict();height=row.get('elevation')
            if (height is None or not math.isfinite(height) or height<=0 or
                not(bounds['west']<=lon<=bounds['east'] and bounds['south']<=lat<=bounds['north']) or
                not land.covers(Point(lon,lat))):continue
            result.append(dict(longitude=lon,latitude=lat,elevation=height,name=row.get('name') or '',
                               scaleRank=row.get('scalerank',9)))
    return result,dict(sourceUrl=PEAK_URL,sourceSHA256=PEAK_SHA256,version=PEAK_VERSION,
        license='Public Domain',licenseUrl='https://www.naturalearthdata.com/about/terms-of-use/',
        basis='Natural Earth named mountains and spot elevations; source values preserved')
