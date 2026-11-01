"""Free, explicitly identified background cells accompanying an unchanged Pi cache.

The existing Pi manifest and every nautical payload are copied byte for byte.
No helper, account, device fingerprint, firmware or licensed source is accessed.
"""
from __future__ import annotations

import hashlib
import io
import json
import math
import os
from pathlib import Path
import struct
import tempfile
import zipfile

from shapely import constrained_delaunay_triangles, make_valid
from shapely.geometry import Polygon, box, shape
from shapely.ops import transform, unary_union

FORMAT = 'no5-map-cache-v2'
MANIFEST = 'no5-map-cache-v2.json'
PI_MANIFEST = 'no5-pi-map-cache.json'
PROVIDER = 'natural-earth-background-v1'
LAND_URL = 'https://naciscdn.org/naturalearth/10m/physical/ne_10m_land.zip'
LAND_SHA256 = 'e547d749445eaa0964aba76738090ec88f5e63c4585122170f98c67a7ea922dc'
LAND_VERSION = '5.1.1'
BATHY_URL = 'https://naciscdn.org/naturalearth/10m/physical/ne_10m_bathymetry_all.zip'
BATHY_SHA256 = '9c5ea31402e5eb322a49f56136bfdc2732ab4e60c85720a4f83606f08088e5c2'
BATHY_VERSION = '4.1.0'
BATHY_DEPTHS = (0, 200, 1000, 2000, 3000, 4000, 5000, 6000, 7000, 8000, 9000, 10000)
TERMS_URL = 'https://www.naturalearthdata.com/about/terms-of-use/'
RADIUS = 6378137.0 * .9996  # Matches the retained OpenCPN toSM_plib.


def canonical(value):
    return (json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(',', ':'), allow_nan=False) + '\n').encode()


def digest(raw):
    return hashlib.sha256(raw).hexdigest()


def record(kind, body):
    return struct.pack('<HI', kind, len(body) + 6) + body


def polygons(geometry):
    if geometry.is_empty:
        return []
    if geometry.geom_type == 'Polygon':
        return [geometry]
    return [p for g in geometry.geoms for p in polygons(g)] if hasattr(geometry, 'geoms') else []


def lines(geometry):
    if geometry.is_empty:
        return []
    if geometry.geom_type in ('LineString', 'LinearRing'):
        return [geometry]
    return [p for g in geometry.geoms for p in lines(g)] if hasattr(geometry, 'geoms') else []


def grid_bounds(extents, step=10):
    if not isinstance(step, int) or step <= 0 or 180 % step:
        raise ValueError('GRID_MUST_DIVIDE_180_DEGREES')
    west = math.floor(min(e['west'] for e in extents) / step) * step
    east = math.ceil(max(e['east'] for e in extents) / step) * step
    south = math.floor(min(e['south'] for e in extents) / step) * step
    north = math.ceil(max(e['north'] for e in extents) / step) * step
    if not (-180 <= west < east <= 180 and -85 <= south < north <= 85):
        raise ValueError('GRID_OUTSIDE_SUPPORTED_NONWRAPPED_MERCATOR_DOMAIN')
    if (east-west)*(north-south)//(step*step) > 1000:
        raise ValueError('BACKGROUND_GRID_TOO_LARGE')
    return dict(west=west, east=east, south=south, north=north)


def aspect_frame(bounds, size):
    """Contain the original rectangle in an undistorted Mercator display frame.

    The display frame is exact; complete globally aligned cells can extend
    beyond it so the existing v2 grid/import contract remains valid.
    """
    width, height = size
    if any(not isinstance(v, int) or not 1 <= v <= 20000 for v in size):
        raise ValueError('VALID_ASPECT_WIDTH_HEIGHT_REQUIRED')
    west, east = math.radians(bounds['west']), math.radians(bounds['east'])
    south = math.asinh(math.tan(math.radians(bounds['south'])))
    north = math.asinh(math.tan(math.radians(bounds['north'])))
    cx, cy = (west+east)/2, (south+north)/2
    dx, dy = east-west, north-south
    if dx/dy < width/height:
        dx = dy*width/height
    else:
        dy = dx*height/width
    extent = dict(west=math.degrees(cx-dx/2), east=math.degrees(cx+dx/2),
        south=math.degrees(math.atan(math.sinh(cy-dy/2))),
        north=math.degrees(math.atan(math.sinh(cy+dy/2))))
    if not (-180 <= extent['west'] < extent['east'] <= 180 and
            -85 <= extent['south'] < extent['north'] <= 85):
        raise ValueError('ASPECT_FRAME_OUTSIDE_NONWRAPPED_MERCATOR_DOMAIN')
    return dict(projection='Mercator', aspectWidth=width, aspectHeight=height,
        bounds=extent, center=dict(latitude=math.degrees(math.atan(math.sinh(cy))),
            longitude=math.degrees(cx)), pixelsPerMetre=height/(dy*RADIUS),
        application='Reference viewing frame; existing app does not automatically apply this provenance field')


def senc_background(name, bounds, land, coast, bathymetry=None, terrain=None, elevation_points=()):
    """Encode OSENC201 geometry, with ordinary SEAARE/LNDARE/COALNE classes.

    SEAARE carries no depth or survey attributes. Its neutral water fill is
    explicitly scoped to the background provider by the consumer, not by S52
    depth defaults. All polygon holes are triangulated before device use.
    """
    w, s, e, n = bounds
    lat0, lon0 = (s+n)/2, (w+e)/2
    y0 = math.asinh(math.tan(math.radians(lat0))) * RADIUS

    def project(x, y, z=None):
        # Shapely 2 supplies scalar coordinates or tuples, depending on path.
        if hasattr(x, '__iter__'):
            return ([math.radians(v-lon0)*RADIUS for v in x],
                    [math.asinh(math.tan(math.radians(v)))*RADIUS-y0 for v in y])
        return math.radians(x-lon0)*RADIUS, math.asinh(math.tan(math.radians(y)))*RADIUS-y0

    def points(coords):
        return b''.join(struct.pack('<ff', *project(x, y)) for x, y in coords)

    out = bytearray(record(1, struct.pack('<H', 201)))
    out += record(2, name.encode() + b'\0')
    for k in (3, 5, 8):
        out += record(k, b'20261025\0')
    out += record(4, struct.pack('<H', 1)) + record(6, struct.pack('<H', 0))
    out += record(7, struct.pack('<I', 10_000_000))
    out += record(100, struct.pack('<8d', s, w, n, w, n, e, s, e))
    # A complete tile is a real coarse background footprint, never ENC coverage.
    out += record(98, struct.pack('<I10f', 5, s, w, n, w, n, e, s, e, s, w))
    edges = []
    feature_count = 0
    triangle_count = 0

    def feature(class_code, primitive=None):
        nonlocal feature_count
        feature_count += 1
        if feature_count > 65535:
            raise ValueError('BACKGROUND_FEATURE_ID_OVERFLOW')
        return record(64, struct.pack('<HHB', class_code, feature_count, primitive if primitive is not None else (1 if class_code in (30, 43) else 2)))

    def edge(coords):
        edges.append(list(coords))
        return struct.pack('<iii', 0, len(edges), 0)

    def attribute(code, value):
        if code == 133:
            return record(65, struct.pack('<HBI', code, 0, int(value)))
        if isinstance(value, str):
            return record(65, struct.pack('<HB', code, 4) + value.encode() + b'\0')
        return record(65, struct.pack('<HBd', code, 2, float(value)))

    def area(class_code, geometry, attrs=b''):
        nonlocal out, triangle_count
        for poly in polygons(geometry):
            if poly.area == 0:
                continue
            projected = transform(project, poly)
            triangles = list(constrained_delaunay_triangles(projected).geoms)
            if not triangles:
                raise ValueError('BACKGROUND_TRIANGULATION_EMPTY')
            total = sum(t.area for t in triangles)
            if abs(total-projected.area) > max(1e-5, projected.area*1e-10):
                raise ValueError('BACKGROUND_TRIANGULATION_CHANGED_AREA')
            coords = [p for t in triangles for p in list(t.exterior.coords)[:3]]
            triangle_count += len(triangles)
            rings = [poly.exterior, *poly.interiors]
            refs = b''.join(edge(r.coords) for r in rings)
            pw, ps, pe, pn = poly.bounds
            body = struct.pack('<4dIII', ps, pn, pw, pe, len(rings), 1, len(rings))
            body += struct.pack('<'+'I'*len(rings), *(len(r.coords) for r in rings))
            body += struct.pack('<BI4d', 4, len(coords), pw, pe, ps, pn)
            body += b''.join(struct.pack('<ff', x, y) for x, y in coords)
            out += feature(class_code) + attrs + record(82, body + refs)

    def line_features(class_code, geometry, attrs=b''):
        nonlocal out
        parts = [line for line in lines(geometry) if line.length > 0]
        if not parts:
            return
        # All parts in one call have identical class and attributes. Native
        # OSENC line references retain separate disconnected edges; grouping
        # avoids thousands of duplicate feature/portrayal objects without
        # simplifying coordinates or drawing connectors between islands.
        lw = min(line.bounds[0] for line in parts)
        ls = min(line.bounds[1] for line in parts)
        le = max(line.bounds[2] for line in parts)
        ln = max(line.bounds[3] for line in parts)
        refs = b''.join(edge(line.coords) for line in parts)
        out += feature(class_code, 1) + attrs + record(81, struct.pack('<4dI', ls, ln, lw, le, len(parts)) + refs)

    tile = box(w, s, e, n)
    local_land = land.intersection(tile)
    water = tile.difference(local_land)
    area(119, water)
    area(71, local_land)
    if bathymetry is not None:
        # The source has nested >=depth polygons. Deepest source wins where
        # independently generalized boundaries are not perfectly nested.
        # Boundaries derive before tile clipping, so grid cuts never become isobaths.
        deeper = Polygon()
        info = attribute(102, 'Natural Earth 4.1.0 / SRTM Plus; coarse reference bathymetry, not a hydrographic survey')
        for depth in reversed(BATHY_DEPTHS):
            local = bathymetry[depth].intersection(water)
            band = local.difference(deeper)
            attrs = attribute(87, depth) + info
            next_depth = next((d for d in BATHY_DEPTHS if d > depth), None)
            if next_depth is not None:
                attrs += attribute(88, next_depth)
            area(42, band, attrs)
            deeper = deeper.union(local)
        for depth in BATHY_DEPTHS[1:]:
            if bathymetry[depth].is_empty:
                continue
            contour = bathymetry[depth].boundary.intersection(water)
            line_features(43, contour, attribute(174, depth) + info)
    if terrain is not None:
        info = attribute(102, 'NOAA ETOPO 2022 v1; 60 arc-second model; elevation above EGM2008 in metres')
        for height, geometry in terrain.items():
            # Generalized cartographic visibility only; actual height is unchanged.
            scamin = 80_000_000 if height % 1000 == 0 else 6_000_000 if height % 500 == 0 else 1_500_000
            line_features(72, geometry.intersection(tile), attribute(90, height) + attribute(133, scamin) + info)
    for point in elevation_points:
        lon,lat = point['longitude'],point['latitude']
        if not (w <= lon < e and s <= lat < n):
            continue
        attrs = attribute(90, point['elevation']) + attribute(116, point['name'])
        attrs += attribute(133, 20_000_000 if point['scaleRank'] <= 6 else 5_000_000)
        attrs += attribute(102, 'Natural Earth 5.0.0 elevation point; source height preserved in metres')
        out += feature(72, 0) + attrs + record(80, struct.pack('<dd',lat,lon))
    line_features(30, coast.intersection(tile))
    body = bytearray(struct.pack('<I', len(edges)))
    for i, coords in enumerate(edges, 1):
        body += struct.pack('<iI', i, len(coords)) + points(coords)
    out += record(96, body) + record(97, struct.pack('<I', 0))
    if len(out) > 128*1024*1024:
        raise ValueError('BACKGROUND_CELL_TOO_LARGE')
    return bytes(out), feature_count, triangle_count


def read_land(archive, bounds):
    import shapefile
    raw = Path(archive).read_bytes()
    if digest(raw) != LAND_SHA256:
        raise ValueError('NATURAL_EARTH_5_1_1_SHA256_MISMATCH')
    region = box(bounds['west']-1, bounds['south']-1, bounds['east']+1, bounds['north']+1)
    selected = []
    repairs = 0
    with zipfile.ZipFile(io.BytesIO(raw)) as z:
        if z.read('ne_10m_land.VERSION.txt').decode().strip() != LAND_VERSION:
            raise ValueError('NATURAL_EARTH_VERSION_MISMATCH')
        reader = shapefile.Reader(shp=io.BytesIO(z.read('ne_10m_land.shp')),
                                 shx=io.BytesIO(z.read('ne_10m_land.shx')),
                                 dbf=io.BytesIO(z.read('ne_10m_land.dbf')))
        for part in reader.iterShapes():
            if not box(*part.bbox).intersects(region):
                continue
            polygon = shape(part.__geo_interface__)
            if not polygon.is_valid:
                polygon = make_valid(polygon)
                repairs += 1
            selected.extend(polygons(polygon.intersection(region)))
    return unary_union(selected), repairs


def read_bathymetry(archive, bounds):
    """Read pinned public depth thresholds; preserve all actual source levels."""
    import shapefile
    raw = Path(archive).read_bytes()
    if digest(raw) != BATHY_SHA256:
        raise ValueError('NATURAL_EARTH_BATHYMETRY_4_1_0_SHA256_MISMATCH')
    region = box(bounds['west']-1, bounds['south']-1, bounds['east']+1, bounds['north']+1)
    values = {}
    repairs = 0
    with zipfile.ZipFile(io.BytesIO(raw)) as z:
        for depth in BATHY_DEPTHS:
            names = [n[:-4] for n in z.namelist() if n.endswith(f'_{depth}.shp')]
            if len(names) != 1:
                raise ValueError('BATHYMETRY_THRESHOLD_MISSING_OR_DUPLICATED')
            stem = names[0]
            if z.read(stem+'.VERSION.txt').decode().strip() != BATHY_VERSION:
                raise ValueError('BATHYMETRY_VERSION_MISMATCH')
            reader = shapefile.Reader(shp=io.BytesIO(z.read(stem+'.shp')),
                shx=io.BytesIO(z.read(stem+'.shx')), dbf=io.BytesIO(z.read(stem+'.dbf')))
            selected = []
            for row in reader.iterShapeRecords():
                if int(row.record.as_dict()['depth']) != depth:
                    raise ValueError('BATHYMETRY_DEPTH_ATTRIBUTE_MISMATCH')
                if not box(*row.shape.bbox).intersects(region):
                    continue
                geometry = shape(row.shape.__geo_interface__)
                if not geometry.is_valid:
                    geometry = make_valid(geometry)
                    repairs += 1
                selected.extend(polygons(geometry.intersection(region)))
            values[depth] = unary_union(selected) if selected else Polygon()
    return values, repairs


def fill_cache(source, land_archive, output, *, step=10, expected_source_sha256=None, bathymetry_archive=None, terrain_grid=None, terrain_sha256=None, elevation_points_archive=None, view_aspect=None):
    source, output = Path(source), Path(output)
    if output.exists() or output.is_symlink() or output.resolve() == source.resolve():
        raise ValueError('FRESH_DISTINCT_OUTPUT_REQUIRED')
    source_hash = digest(source.read_bytes())
    if expected_source_sha256 and source_hash != expected_source_sha256:
        raise ValueError('SOURCE_CACHE_SHA256_MISMATCH')
    with zipfile.ZipFile(source) as z:
        if len(z.infolist()) > 20000 or len(z.namelist()) != len(set(z.namelist())):
            raise ValueError('INVALID_SOURCE_ARCHIVE_INVENTORY')
        manifest_raw = z.read(PI_MANIFEST)
        manifest = json.loads(manifest_raw)
        if manifest['format'] != 'no5-pi-map-cache-v1' or not 1 <= len(manifest['cells']) <= 1500:
            raise ValueError('PI_CACHE_V1_REQUIRED')
        declared = {PI_MANIFEST}
        for cell in manifest['cells']:
            if cell['base'] != f"cells/{cell['name']}.senc" or cell['name'].startswith('NO5BG_'):
                raise ValueError('INVALID_OR_RESERVED_SOURCE_CELL_NAME')
            for name, sha in cell['files'].items():
                if name not in (cell['base'], f"provenance/{cell['name']}.json") or name in declared:
                    raise ValueError('UNEXPECTED_SOURCE_PATH')
                declared.add(name)
                info = z.getinfo(name)
                if info.file_size > 128*1024*1024 or digest(z.read(name)) != sha:
                    raise ValueError('SOURCE_CELL_PIN_MISMATCH')
        if set(z.namelist()) != declared:
            raise ValueError('UNEXPECTED_SOURCE_ARCHIVE_MEMBERS')
        bounds = grid_bounds([c['extent'] for c in manifest['cells']], step)
        frame = aspect_frame(bounds, view_aspect) if view_aspect is not None else None
        if frame is not None:
            bounds = grid_bounds([frame['bounds']], step)
        land, repairs = read_land(land_archive, bounds)
        coast = land.boundary
        bathymetry = None
        if bathymetry_archive is not None:
            bathymetry, bathy_repairs = read_bathymetry(bathymetry_archive, bounds)
            repairs += bathy_repairs
        terrain = None
        terrain_origin = None
        elevation_points = []
        points_origin = None
        if terrain_grid is not None:
            from .terrain import read_contours
            terrain, terrain_origin = read_contours(terrain_grid, terrain_sha256, bounds, land)
        if elevation_points_archive is not None:
            from .terrain import read_points
            elevation_points, points_origin = read_points(elevation_points_archive, bounds, land)
        rows = []
        payloads = {}
        for south in range(bounds['south'], bounds['north'], step):
            for west in range(bounds['west'], bounds['east'], step):
                name = f"NO5BG_{'S' if south < 0 else 'N'}{abs(south):02d}_{'W' if west < 0 else 'E'}{abs(west):03d}"
                body, count, triangles = senc_background(name, (west, south, west+step, south+step), land, coast, bathymetry, terrain, elevation_points)
                base, provenance = f'cells/{name}.senc', f'provenance/{name}.json'
                origin = dict(provider=PROVIDER, sourceUrl=LAND_URL, sourceSHA256=LAND_SHA256,
                    version=LAND_VERSION, license='Public Domain', licenseUrl=TERMS_URL,
                    attribution='Made with Natural Earth', nauticalDetails=False)
                if bathymetry is not None:
                    # Extra origin fields live in the already extensible public
                    # provenance object, preserving the released MAP104 v2 reader.
                    origin['bathymetry'] = dict(sourceUrl=BATHY_URL, sourceSHA256=BATHY_SHA256,
                        version=BATHY_VERSION, license='Public Domain', licenseUrl=TERMS_URL,
                        depthThresholdsMetres=list(BATHY_DEPTHS), geometry='DEPARE depth bands and DEPCNT isobaths',
                        basis='SRTM Plus; generalized coarse reference, not survey soundings',
                        coverage='All source bathymetry within tile; water without source depths stays unmeasured')
                if terrain_origin is not None:
                    origin['topography'] = terrain_origin
                if points_origin is not None:
                    origin['elevationPoints'] = points_origin
                if frame is not None:
                    origin['displayFrame'] = frame
                metadata = canonical(origin)
                payloads[base], payloads[provenance] = body, metadata
                rows.append(dict(name=name, base=base, edition=1, update=0, issueDate='20261025',
                    generation=digest(body+metadata), featureCount=count,
                    extent=dict(west=west, east=west+step, south=south, north=south+step),
                    files={base:digest(body), provenance:digest(metadata)}))
        envelope = dict(format=FORMAT, product=manifest['product'], gridDegrees=step, bounds=bounds,
            nauticalManifestSHA256=digest(manifest_raw), backgroundProvider=PROVIDER,
            sourceUrl=LAND_URL, sourceSHA256=LAND_SHA256, sourceVersion=LAND_VERSION,
            license='Public Domain', licenseUrl=TERMS_URL, attribution='Made with Natural Earth',
            sourceRepairs=repairs, cells=rows)
        output.parent.mkdir(parents=True, exist_ok=True)
        fd, temporary = tempfile.mkstemp(prefix='.cache-fill-', dir=output.parent)
        try:
            with os.fdopen(fd, 'wb') as target, zipfile.ZipFile(target, 'w', compression=zipfile.ZIP_DEFLATED, compresslevel=6) as out:
                def put(name, data):
                    info=zipfile.ZipInfo(name, date_time=(1980,1,1,0,0,0));info.compress_type=zipfile.ZIP_DEFLATED
                    info.external_attr=0o600 << 16
                    out.writestr(info, data)
                for name in sorted(declared):
                    put(name, z.read(name))
                for name, data in sorted(payloads.items()):
                    put(name, data)
                put(MANIFEST, canonical(envelope))
            with open(temporary, 'rb') as f:
                os.fsync(f.fileno())
            with zipfile.ZipFile(temporary) as check:
                if check.testzip() is not None:
                    raise ValueError('OUTPUT_CRC_FAILURE')
                for name in declared:
                    if digest(check.read(name)) != digest(z.read(name)):
                        raise ValueError('NAUTICAL_BYTES_CHANGED')
            os.link(temporary, output)
        finally:
            Path(temporary).unlink(missing_ok=True)
    return dict(format=FORMAT, output=str(output), bytes=output.stat().st_size,
        sha256=digest(output.read_bytes()), sourceSHA256=source_hash,
        nauticalCells=len(manifest['cells']), backgroundCells=len(rows),
        totalCells=len(manifest['cells'])+len(rows), bounds=bounds,
        nauticalFilesUnchanged=True, sourceRepairs=repairs, bathymetryIncluded=bathymetry is not None, terrainIncluded=terrain is not None, elevationPoints=len(elevation_points), displayFrame=frame)
