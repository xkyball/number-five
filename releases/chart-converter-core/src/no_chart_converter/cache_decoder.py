#!/usr/bin/env python3
"""Bounded MAP OSENC 201 → no5-chart-ir/1; no helper or device operations."""
import argparse
from collections import Counter, defaultdict
import csv
import ctypes
from datetime import datetime, timezone
import errno
import hashlib
import json
import math
import os
from pathlib import Path, PurePosixPath
import platform
import re
import resource
import shutil
import stat
import struct
import sys
import tempfile
import time
import zipfile

from importlib.resources import files
import shapely
from shapely.geometry import Polygon
from shapely.strtree import STRtree
from shapely.validation import explain_validity

HERE = Path(__file__).resolve().parent
IR_SCHEMA_SHA = '974ed38e899a345dec1500d0baf21b9d3c2c1301b659c964e280930e30280e52'
MAX_CELL = 128 * 1024 * 1024
MAX_PROVENANCE = 8 * 1024 * 1024
MAX_ARCHIVE = 512 * 1024 * 1024
MAX_EXPANDED = 2 * 1024 * 1024 * 1024
Z = 6378137.0 * 0.9996
DEGREE = math.pi / 180.0
VERSION = '1.0.0'


def now():
    return datetime.now(timezone.utc).isoformat()


def sha(data):
    return hashlib.sha256(data).hexdigest()


def file_sha(path):
    h = hashlib.sha256()
    with Path(path).open('rb') as stream:
        for part in iter(lambda: stream.read(1024 * 1024), b''):
            h.update(part)
    return h.hexdigest()


def canonical(value):
    return (json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(',', ':'), allow_nan=False) + '\n').encode('utf-8')


class DecodeError(ValueError):
    def __init__(self, code, detail, cell_id=None, source_record_offset=None):
        self.code, self.detail = code, detail
        self.cell_id, self.source_record_offset = cell_id, source_record_offset
        super().__init__(f'{code}: {detail}')

    def as_dict(self):
        return {'code': self.code, 'cell_id': self.cell_id, 'source_record_offset': self.source_record_offset, 'detail': self.detail}


def need(condition, code, detail, cell=None, offset=None):
    if not condition:
        raise DecodeError(code, detail, cell, offset)


def strict_json(data, context):
    def pairs(items):
        out = {}
        for key, value in items:
            need(key not in out, 'DUPLICATE_JSON_KEY', context)
            out[key] = value
        return out
    def nonfinite(value):
        raise DecodeError('NONFINITE_VALUE', context)
    try:
        result = json.loads(data, object_pairs_hook=pairs, parse_constant=nonfinite)
        pending = [(result, 0)]
        while pending:
            value, depth = pending.pop()
            need(depth <= 64, 'SOURCE_LIMIT', 'JSON nesting exceeds limit')
            need(not isinstance(value, float) or math.isfinite(value), 'NONFINITE_VALUE', context)
            if isinstance(value, dict):
                pending.extend((item, depth + 1) for item in value.values())
            elif isinstance(value, list):
                pending.extend((item, depth + 1) for item in value)
        return result
    except (UnicodeError, json.JSONDecodeError, RecursionError) as error:
        raise DecodeError('INVALID_JSON', context) from error


def pointer(member, offset, length, code):
    return {'member': member, 'offset': offset, 'length': length, 'recordType': code}


class Cursor:
    def __init__(self, body, cell, offset):
        self.body, self.cell, self.offset, self.pos = body, cell, offset, 0

    def take(self, fmt):
        size = struct.calcsize('<' + fmt)
        need(self.pos + size <= len(self.body), 'COUNT_OUT_OF_BOUNDS', 'record data truncated', self.cell, self.offset)
        values = struct.unpack_from('<' + fmt, self.body, self.pos)
        self.pos += size
        need(all(not isinstance(v, float) or math.isfinite(v) for v in values), 'NONFINITE_VALUE', 'nonfinite record value', self.cell, self.offset)
        return values

    def array(self, fmt, count):
        width = struct.calcsize('<' + fmt)
        need(0 <= count <= 20_000_000 and self.pos + width * count <= len(self.body), 'COUNT_OUT_OF_BOUNDS', 'array count exceeds remaining record data', self.cell, self.offset)
        return [list(self.take(fmt)) for _ in range(count)]

    def string(self):
        data = bytes(self.body[self.pos:])
        need(data.endswith(b'\0') and b'\0' not in data[:-1], 'INVALID_TEXT', 'text must have one terminal NUL', self.cell, self.offset)
        try:
            result = data[:-1].decode('utf-8')
        except UnicodeError as error:
            raise DecodeError('INVALID_TEXT', 'text is not UTF-8', self.cell, self.offset) from error
        self.pos = len(self.body)
        return result

    def finish(self):
        need(self.pos == len(self.body), 'INVALID_RECORD_LENGTH', 'record has trailing bytes', self.cell, self.offset)


def bbox(values, cell=None, offset=None, triangle=False):
    if triangle:
        west, east, south, north = values
    else:
        south, north, west, east = values
    need(all(math.isfinite(v) for v in values), 'NONFINITE_VALUE', 'nonfinite bounds', cell, offset)
    need(-90 <= south <= north <= 90 and -180 <= west <= 180 and -180 <= east <= 180, 'INVALID_COORDINATE', 'geographic bounds outside valid range', cell, offset)
    return {'west': west, 'south': south, 'east': east, 'north': north, 'crossesAntimeridian': west > east}


def parse_senc(data, *, cell_id, member, complete=True):
    """Parse the exact MAP 201 dialect. complete=False supports isolated tests."""
    need(len(data) <= MAX_CELL, 'SOURCE_LIMIT', 'cell too large', cell_id)
    raw = {'headers': {}, 'headerPointers': {}, 'features': [], 'edges': {}, 'nodes': {}, 'coverage': [], 'excluded': [], 'counts': Counter(), 'records': Counter()}
    pos, current = 0, None
    allowed = {1, 2, 3, 4, 5, 6, 7, 8, 64, 65, 80, 81, 82, 83, 96, 97, 98, 99, 100}
    ids = set()
    while pos < len(data):
        need(pos + 6 <= len(data), 'TRUNCATED_RECORD_HEADER', 'record header needs six bytes', cell_id, pos)
        code, length = struct.unpack_from('<HI', data, pos)
        need(length >= 6 and pos + length <= len(data), 'INVALID_RECORD_LENGTH', 'record length outside member', cell_id, pos)
        need(code in allowed, 'UNSUPPORTED_RECORD', f'unsupported record type {code}', cell_id, pos)
        p = pointer(member, pos, length, code)
        c = Cursor(memoryview(data)[pos + 6:pos + length], cell_id, pos)
        raw['records'][code] += 1
        if code <= 8 or code == 100:
            need(code not in raw['headers'], 'DUPLICATE_HEADER', 'duplicate source header', cell_id, pos)
            if code in (1, 4, 6):
                value = c.take('H')[0]
            elif code == 7:
                value = c.take('I')[0]
                need(value > 0, 'INVALID_SCALE', 'native scale is not positive', cell_id, pos)
            elif code == 100:
                value = list(c.take('8d'))
            else:
                value = c.string()
            raw['headers'][code], raw['headerPointers'][code] = value, p
        elif code == 64:
            need(not complete or current is None or current['geometry'] is not None, 'MISSING_GEOMETRY', 'preceding feature has no geometry', cell_id, pos)
            class_code, feature_id, primitive = c.take('HHB')
            need(feature_id not in ids, 'DUPLICATE_FEATURE_ID', 'feature ID repeats within cell', cell_id, pos)
            need(primitive in (0, 1, 2), 'UNSUPPORTED_TARGET_FEATURE', 'unsupported source primitive', cell_id, pos)
            ids.add(feature_id)
            current = {'id': feature_id, 'classCode': class_code, 'primitive': primitive, 'attributes': [], 'geometry': None, 'sourceRecord': p}
            raw['features'].append(current)
            raw['counts']['features'] += 1
        elif code == 65:
            attr_code, wire = c.take('HB')
            need(wire in (0, 2, 4), 'UNSUPPORTED_ATTRIBUTE_TYPE', f'unsupported attribute wire type {wire}', cell_id, pos)
            value = c.string() if wire == 4 else c.take('I' if wire == 0 else 'd')[0]
            need(not complete or current is not None and current['geometry'] is None, 'ATTRIBUTE_OWNERSHIP', 'attribute is not before its feature geometry', cell_id, pos)
            if current is not None:
                current['attributes'].append({'code': attr_code, 'wireType': wire, 'type': {0: 'uint32', 2: 'float64', 4: 'utf8'}[wire], 'value': value, 'sourceRecord': p})
            raw['counts']['attributes'] += 1
        elif code in (80, 81, 82, 83):
            geometry = {'sourceRecord': p}
            if code == 80:
                lat, lon = c.take('dd')
                need(-90 <= lat <= 90 and -180 <= lon <= 180, 'INVALID_COORDINATE', 'point outside geographic range', cell_id, pos)
                geometry.update(kind='point', position=[lon, lat])
            else:
                geometry['bbox'] = bbox(c.take('4d'), cell_id, pos)
                if code == 83:
                    count = c.take('I')[0]
                    geometry.update(kind='multipoint', pointsSmDepth=c.array('fff', count))
                    raw['counts']['soundings'] += count
                elif code == 81:
                    count = c.take('I')[0]
                    geometry.update(kind='line', refs=c.array('iii', count))
                    raw['counts']['edgeRefs'] += count
                else:
                    contours, triangles, refs = c.take('III')
                    contour_counts = [v[0] for v in c.array('I', contours)]
                    need(triangles <= (len(c.body) - c.pos) // 37, 'COUNT_OUT_OF_BOUNDS', 'triangle count exceeds remaining source bytes', cell_id, pos)
                    tri = []
                    for _ in range(triangles):
                        mode, count = c.take('BI')
                        need(mode in (4, 5, 6) and count >= 3 and (mode != 4 or count % 3 == 0), 'INVALID_TRIANGLE', 'invalid triangle primitive mode or vertex count', cell_id, pos)
                        bounds = bbox(c.take('4d'), cell_id, pos, triangle=True)
                        tri.append({'mode': mode, 'coordinatesSm': c.array('ff', count), 'bbox': bounds})
                    geometry.update(kind='area', contourPointCounts=contour_counts, trianglePrimitives=tri, refs=c.array('iii', refs))
                    raw['counts']['edgeRefs'] += refs
            need(not complete or current is not None and current['geometry'] is None, 'GEOMETRY_OWNERSHIP', 'geometry needs a unique preceding feature', cell_id, pos)
            if current is not None:
                need(current['primitive'] == {80: 0, 83: 0, 81: 1, 82: 2}[code], 'PRIMITIVE_MISMATCH', 'record geometry conflicts with primitive', cell_id, pos)
                current['geometry'] = geometry
            raw['counts'][geometry['kind']] += 1
        elif code in (96, 97):
            need(raw['records'][code] == 1, 'DUPLICATE_TOPOLOGY_TABLE', 'topology table repeats', cell_id, pos)
            count = c.take('I')[0]
            need(count <= (len(c.body) - c.pos) // (8 if code == 96 else 12), 'COUNT_OUT_OF_BOUNDS', 'topology count exceeds remaining record', cell_id, pos)
            table = raw['edges' if code == 96 else 'nodes']
            for _ in range(count):
                index = c.take('i')[0]
                need(index > 0 and index not in table, 'INVALID_TOPOLOGY_ID', 'nonpositive or duplicate topology ID', cell_id, pos)
                if code == 96:
                    entry = {'id': index, 'coordinatesSm': c.array('ff', c.take('I')[0]), 'sourceRecord': p}
                else:
                    entry = {'id': index, 'positionSm': list(c.take('ff')), 'sourceRecord': p}
                table[index] = entry
            raw['counts']['edges' if code == 96 else 'nodes'] += count
        elif code in (98, 99):
            points = c.array('ff', c.take('I')[0])
            coordinates = [[lon, lat] for lat, lon in points]
            need(all(-180 <= lon <= 180 and -90 <= lat <= 90 for lon, lat in coordinates), 'INVALID_COORDINATE', 'coverage outside geographic range', cell_id, pos)
            raw['coverage' if code == 98 else 'excluded'].append({'coordinates': coordinates, 'sourceRecord': p})
        c.finish()
        pos += length
    if complete:
        need(set(raw['headers']) == {1, 2, 3, 4, 5, 6, 7, 8, 100} and raw['headers'][1] == 201, 'UNSUPPORTED_DIALECT', 'expected complete MAP OSENC 201 headers', cell_id, 0)
        need(raw['records'][96] == 1 and raw['records'][97] == 1, 'MISSING_TOPOLOGY_TABLE', 'complete cell requires node and edge tables', cell_id)
        need(all(f['geometry'] is not None for f in raw['features']), 'MISSING_GEOMETRY', 'feature has no geometry', cell_id)
    return raw


def evidence(kind, reference, detail, digest=None):
    return {'kind': kind, 'reference': reference, 'sha256': digest, 'detail': detail}


def declaration(state='unknown', value=None, raw=None, proof=None):
    return {'state': state, 'value': value, 'raw': raw, 'evidence': [] if proof is None else [proof]}


class Transform:
    def __init__(self, reference):
        self.lon0, self.lat0 = reference
        need(-180 <= self.lon0 <= 180 and -90 < self.lat0 < 90, 'INVALID_COORDINATE', 'invalid SM reference')
        sine = math.sin(self.lat0 * DEGREE)
        self.y0 = Z / 2 * math.log((1 + sine) / (1 - sine))
        self.max_half = [0.0, 0.0, 0.0]
        self.max_geo = [0.0, 0.0]
        self.samples = 0

    def convert(self, xy):
        x, y = xy
        need(math.isfinite(x) and math.isfinite(y), 'NONFINITE_VALUE', 'nonfinite SM position')
        lon = self.lon0 + x / (DEGREE * Z)
        q = (self.y0 + y) / Z
        # atan(exp(q)) can overflow for malformed finite source coordinates.
        lat = ((math.pi / 2 - 2 * math.atan(math.exp(-q))) if q >= 0 else (2 * math.atan(math.exp(q)) - math.pi / 2)) / DEGREE
        need(math.isfinite(lon) and math.isfinite(lat), 'NONFINITE_VALUE', 'SM inversion overflow')
        if not -180 <= lon <= 180:
            lon = (lon + 180) % 360 - 180
        for axis, v in enumerate(xy):
            half = half_ulp32(v)
            self.max_half[axis] = max(self.max_half[axis], half)
            bound = half / (DEGREE * Z) * (1 if axis == 0 else abs(math.cos(lat * DEGREE)))
            self.max_geo[axis] = max(self.max_geo[axis], bound)
        self.samples += 1
        return [lon, lat]

    def precision(self):
        return {'localCoordinateSamplesVisited': self.samples, 'maxXFloat32HalfUlpMetres': self.max_half[0], 'maxYFloat32HalfUlpMetres': self.max_half[1], 'maxDepthFloat32HalfUlpNativeValue': self.max_half[2], 'maxLongitudeQuantizationDegrees': self.max_geo[0], 'maxLatitudeQuantizationDegrees': self.max_geo[1], 'meaning': 'Float32 rounding bound only; neither prior F16 quantization nor survey accuracy is recovered', 'originalF16IntegerPrecisionAvailable': False}


def half_ulp32(value):
    exponent = (struct.unpack('<I', struct.pack('<f', abs(value)))[0] >> 23) & 255
    return math.ldexp(0.5, -149 if exponent == 0 else exponent - 127 - 23)


def dedup_adjacent(points):
    out = []
    for point in points:
        if not out or point != out[-1]:
            out.append(point)
    return out


def normalize_refs(raw_refs):
    return [{'startNodeId': s if s > 0 else None, 'signedEdgeId': e, 'endNodeId': n if n > 0 else None, 'sourceOrdinal': i} for i, (s, e, n) in enumerate(raw_refs)]


def connector(ref, nodes, edges):
    start, end, signed = ref['startNodeId'], ref['endNodeId'], ref['signedEdgeId']
    issues = []
    if start not in nodes or end not in nodes:
        issues.append(f"UNRESOLVED_NODE: ref {ref['sourceOrdinal']}")
    if signed != 0 and abs(signed) not in edges:
        issues.append(f"UNRESOLVED_EDGE: ref {ref['sourceOrdinal']}")
    if issues:
        return None, issues
    middle = [] if signed == 0 else edges[abs(signed)]['coordinatesSm']
    if signed < 0:
        middle = list(reversed(middle))
    return dedup_adjacent([nodes[start]['positionSm']] + middle + [nodes[end]['positionSm']]), []


def assemble_line(refs, nodes, edges, transform):
    parts, issues, current, previous_end, ordinals = [], [], [], None, []
    def flush():
        if len(current) >= 2:
            parts.append({'coordinatesSm': current.copy(), 'coordinates': [transform.convert(p) for p in current], 'edgeRefOrdinals': ordinals.copy()})
        elif current:
            issues.append('BROKEN_CHAIN: connector has fewer than two distinct vertices')
    for ref in refs:
        points, errors = connector(ref, nodes, edges)
        issues.extend(errors)
        if points is None:
            flush()
            current, ordinals, previous_end = [], [], None
            continue
        if current and previous_end == ref['startNodeId'] and current[-1] == points[0]:
            current.extend(points[1:])
            ordinals.append(ref['sourceOrdinal'])
        else:
            flush()
            current, ordinals = points.copy(), [ref['sourceOrdinal']]
        previous_end = ref['endNodeId']
    flush()
    if not refs:
        issues.append('BROKEN_CHAIN: line has no boundary references')
    return parts, issues


def reconstruct_area(refs, nodes, edges, transform):
    rings, issues = [], []
    paths = {}
    outgoing = defaultdict(list)
    for ref in refs:
        path, errors = connector(ref, nodes, edges)
        issues.extend(errors)
        if path is not None:
            paths[ref['sourceOrdinal']] = path
            outgoing[ref['startNodeId']].append(ref['sourceOrdinal'])
    unused = set(paths)
    while unused:
        first = min(unused)
        current = first
        points, ordinals = [], []
        start_node = refs[first]['startNodeId']
        closed = False
        while current in unused:
            ref = refs[current]
            path = paths[current]
            unused.remove(current)
            ordinals.append(current)
            if points and points[-1] != path[0]:
                issues.append('BROKEN_CHAIN: equal node identity has unequal positions')
                break
            points.extend(path if not points else path[1:])
            if ref['endNodeId'] == start_node:
                closed = True
                break
            candidates = [i for i in outgoing.get(ref['endNodeId'], []) if i in unused]
            if len(candidates) != 1:
                issues.append('BROKEN_CHAIN: open boundary' if not candidates else 'AMBIGUOUS_RING: multiple directed continuations')
                break
            current = candidates[0]
        if not closed:
            continue
        points = dedup_adjacent(points)
        closure = 'source_closed'
        if points and points[-1] != points[0]:
            points.append(points[0])
            closure = 'explicit_closure_added'
        if len(points) < 4 or len({tuple(p) for p in points[:-1]}) < 3:
            issues.append('AMBIGUOUS_RING: degenerate closed boundary')
            continue
        rings.append({'id': f'r{len(rings)}', 'role': 'unclassified', 'coordinatesSm': points, 'coordinates': [transform.convert(p) for p in points], 'edgeRefOrdinals': ordinals, 'derivation': 'topology', 'closure': closure})
    polygons = []
    geometries = []
    for ring in rings:
        poly = Polygon(ring['coordinatesSm'])
        if not poly.is_valid or poly.area == 0:
            why = explain_validity(poly).split('[')[0]
            issues.append(f'AMBIGUOUS_RING: {ring["id"]} {why}')
        geometries.append(poly)
    if rings and not issues:
        boundaries = [p.boundary for p in geometries]
        tree = STRtree(boundaries)
        pairs = set()
        for i, boundary in enumerate(boundaries):
            for j in tree.query(boundary, predicate='intersects'):
                j = int(j)
                if j != i:
                    pairs.add(tuple(sorted((i, j))))
        if pairs:
            issues.append('AMBIGUOUS_RING: recovered boundaries intersect or touch')
        else:
            tree = STRtree(geometries)
            parents = {}
            for i, child in enumerate(geometries):
                containers = [int(j) for j in tree.query(child) if int(j) != i and geometries[int(j)].contains(child)]
                parents[i] = min(containers, key=lambda j: geometries[j].area) if containers else None
                rings[i]['role'] = 'exterior' if len(containers) % 2 == 0 else 'interior'
            for i, ring in enumerate(rings):
                if ring['role'] == 'exterior':
                    polygons.append({'exteriorRingId': ring['id'], 'interiorRingIds': [rings[j]['id'] for j in range(len(rings)) if parents[j] == i and rings[j]['role'] == 'interior']})
    if not rings:
        issues.append('AMBIGUOUS_RING: no valid closed boundary recovered')
    if issues:
        for ring in rings:
            ring['role'] = 'unclassified'
        polygons = []
    return rings, polygons, list(dict.fromkeys(issues))


def catalogs(directory, expected_hashes=None):
    result, pins = [], []
    need(directory is not None, 'CATALOGS_REQUIRED', 'provide the S57 object and attribute CSV directory')
    for name in ('s57objectclasses.csv', 's57attributes.csv'):
        path = Path(directory) / name
        digest = file_sha(path)
        if expected_hashes is not None:
            need(digest == expected_hashes[name], 'CATALOG_HASH_MISMATCH', 'explicit catalog hash differs')
        with path.open(newline='') as stream:
            rows = list(csv.DictReader(stream))
        codes = [int(r['Code']) for r in rows]
        need(len(set(codes)) == len(codes), 'INVALID_CATALOG', 'duplicate catalog code')
        result.append({int(r['Code']): r['Acronym'] for r in rows})
        pins.append({'path': str(path), 'sha256': digest, 'bytes': path.stat().st_size})
    return result[0], result[1], pins


def loss(key, classification, detail, source_ids=None, stage='decode', proof=None):
    return {'id': key, 'stage': stage, 'classification': classification, 'scope': 'cell', 'detail': detail, 'sourceIds': source_ids or [], 'evidence': [] if proof is None else [proof]}


def decode_cell(data, provenance, row, class_names, attr_names, *, source_format='no5-pi-map-cache-v1'):
    cell_id, member = row['name'], row['base']
    raw = parse_senc(data, cell_id=cell_id, member=member)
    h, ph = raw['headers'], provenance['originalHeader']
    required_header = {'edition', 'update', 'native_scale', 'extent_corners_lat_lon', 'reference_lat_lon', 'publish_date', 'update_date', 'created_date'}
    need(required_header <= set(ph), 'UNKNOWN_REQUIRED_DECLARATION', 'provenance lacks required original header fields', cell_id)
    need(isinstance(ph['reference_lat_lon'], list) and len(ph['reference_lat_lon']) == 2 and all(isinstance(v, (int, float)) and not isinstance(v, bool) and math.isfinite(v) for v in ph['reference_lat_lon']), 'UNKNOWN_REQUIRED_DECLARATION', 'invalid original SM reference declaration', cell_id)
    need(h[2] == cell_id == (ph.get('cell_id') or ph.get('name')), 'SOURCE_HEADER_MISMATCH', 'cell identity differs from manifest/provenance', cell_id)
    need(h[4] == row['edition'] == ph['edition'] and h[6] == row['update'] == ph['update'], 'SOURCE_HEADER_MISMATCH', 'edition/update mismatch', cell_id)
    need(h[7] == ph['native_scale'] and h[100] == ph['extent_corners_lat_lon'], 'SOURCE_HEADER_MISMATCH', 'scale/extent mismatch', cell_id)
    need(h[3] == ph['publish_date'] and h[5] == ph['update_date'] and h[8] == ph['created_date'], 'SOURCE_HEADER_MISMATCH', 'date header mismatch', cell_id)
    c = h[100]
    need(row['extent'] == {'south': c[6], 'north': c[2], 'west': c[3], 'east': c[7]}, 'SOURCE_HEADER_MISMATCH', 'manifest bounds mismatch', cell_id)
    reference = [ph['reference_lat_lon'][1], ph['reference_lat_lon'][0]]
    transform = Transform(reference)
    psha = row['files'][f'provenance/{cell_id}.json']
    source_proof = evidence('source_provenance', f'provenance/{cell_id}.json', 'Original helper export provenance retained exactly', psha)
    code_proof = evidence('source_code', str(Path(__file__)), 'Packaged decoder preserves the original MAP export SM convention and fromSM_plib constants', file_sha(Path(__file__)))
    sounding_raw = ph.get('sounding_datum')
    sounding = declaration('verified', sounding_raw, sounding_raw, source_proof) if isinstance(sounding_raw, str) and sounding_raw else declaration()
    units = {'localHorizontal': declaration('verified', 'm', 'SM metres', code_proof), 'soundingDepth': declaration('inferred', 'm', None, code_proof), 'height': declaration()}
    edges, nodes = raw['edges'], raw['nodes']
    for edge in edges.values():
        edge['coordinates'] = [transform.convert(p) for p in edge['coordinatesSm']]
    for node in nodes.values():
        node['position'] = transform.convert(node['positionSm'])
    features, unresolved, losses = [], [], []
    for f in raw['features']:
        source_id = f'{cell_id}:f{f["id"]}'
        attrs = [{**a, 'acronym': attr_names.get(a['code'])} for a in f['attributes']]
        display = {}
        for key, acronym in [('minimumDenominator', 'SCAMIN'), ('maximumDenominator', 'SCAMAX')]:
            candidates = [a for a in attrs if a['acronym'] == acronym]
            if len(candidates) == 1 and isinstance(candidates[0]['value'], (int, float)):
                a = candidates[0]
                proof = evidence('source_record', f'{member}@{a["sourceRecord"]["offset"]}', f'Observed {acronym} attribute retained without reinterpretation', row['files'][member])
                display[key] = declaration('verified', a['value'], a['value'], proof)
            else:
                display[key] = declaration()
        g = f['geometry']
        kind = g['kind']
        if kind == 'point':
            geometry = dict(g)
        elif kind == 'multipoint':
            points = []
            datum = sounding
            feature_datum = [a for a in attrs if a['acronym'] == 'VERDAT']
            if len(feature_datum) == 1:
                a = feature_datum[0]
                proof = evidence('source_record', f'{member}@{a["sourceRecord"]["offset"]}', 'Per-feature VERDAT retained but not mapped to a global datum', row['files'][member])
                datum = declaration('unknown', None, a['value'], proof)
            for ordinal, (x, y, depth) in enumerate(g['pointsSmDepth']):
                transform.max_half[2] = max(transform.max_half[2], half_ulp32(depth))
                points.append({'ordinal': ordinal, 'positionSm': [x, y], 'position': transform.convert([x, y]), 'depth': {'value': depth, 'meaning': 'depth', 'unit': units['soundingDepth'], 'datum': datum, 'wireStorage': 'float32'}})
            geometry = {'kind': kind, 'bbox': g['bbox'], 'points': points, 'sourceRecord': g['sourceRecord']}
        else:
            refs = normalize_refs(g['refs'])
            if kind == 'line':
                parts, issues = assemble_line(refs, nodes, edges, transform)
                geometry = {'kind': kind, 'bbox': g['bbox'], 'edgeRefs': refs, 'parts': parts, 'sourceRecord': g['sourceRecord']}
                algorithm = 'source-order directed connectors; signed edge reverses internal points only; disconnected parts retained'
            else:
                rings, polygons, issues = reconstruct_area(refs, nodes, edges, transform)
                triangles = [{**tri, 'coordinates': [transform.convert(p) for p in tri['coordinatesSm']]} for tri in g['trianglePrimitives']]
                geometry = {'kind': kind, 'bbox': g['bbox'], 'edgeRefs': refs, 'contourPointCounts': g['contourPointCounts'], 'rings': rings, 'polygons': polygons, 'trianglePrimitives': triangles, 'sourceRecord': g['sourceRecord']}
                algorithm = 'unique directed-node connectivity; GEOS validity/intersection; STRtree containment nesting; no repairs'
            geometry['reconstruction'] = {'status': 'unresolved' if issues else 'exact_topology', 'algorithm': algorithm, 'issues': issues, 'evidence': [code_proof]}
            if issues:
                unresolved.append({'sourceId': source_id, 'kind': kind, 'sourceRecordOffset': g['sourceRecord']['offset'], 'issues': issues})
                losses.append(loss(f'{source_id}-polygon-unresolved' if kind == 'area' else f'{source_id}-line-unresolved', 'reconstructed', 'Raw source geometry preserved; complete validated assembly unavailable: ' + '; '.join(issues), [source_id]))
        features.append({'sourceId': source_id, 'sourceFeatureId': f['id'], 'classCode': f['classCode'], 'classAcronym': class_names.get(f['classCode']), 'sourcePrimitive': f['primitive'], 'attributes': attrs, 'displayScale': display, 'geometry': geometry, 'sourceRecord': f['sourceRecord']})
    coverage = {'covered': [], 'excluded': []}
    for source_key, target_key in [('coverage', 'covered'), ('excluded', 'excluded')]:
        for ring in raw[source_key]:
            coords = ring['coordinates'].copy()
            n = len(coords)
            closure = 'source_closed'
            if coords and coords[-1] != coords[0]:
                coords.append(coords[0])
                closure = 'explicit_closure_added'
            need(len(coords) >= 4 and len({tuple(v) for v in coords[:-1]}) >= 3, 'AMBIGUOUS_RING', 'degenerate coverage or exclusion ring', cell_id, ring['sourceRecord']['offset'])
            coverage[target_key].append({'coordinates': coords, 'sourcePointCount': n, 'closure': closure, 'sourceRecord': ring['sourceRecord']})
    summary = {key: raw['counts'].get(key, 0) for key in ('features', 'attributes', 'soundings', 'edgeRefs', 'edges', 'nodes')}
    summary['cells'] = 1
    need(summary['features'] == row['featureCount'], 'SOURCE_HEADER_MISMATCH', 'feature count differs from manifest', cell_id)
    losses.extend([loss(f'{cell_id}-preserved', 'preserved', 'All observed attributes, feature IDs, raw SM geometry, topology, triangle modes/bounds, source text and provenance preserved.'), loss(f'{cell_id}-axis-transform', 'representation_change', 'Source geographic latitude/longitude changed to IR longitude/latitude; Float32 locals promoted exactly to Float64 before inverse SM.'), loss(f'{cell_id}-assembled', 'representation_change', 'Line/area boundary chains assembled from directed refs; adjacent duplicate positions omitted only from assembly; raw refs/tables retained.'), loss(f'{cell_id}-prior-source-limitations', 'unavailable_in_source', 'Original S-57 long names/relations and raw pgroup_geom ring vertices are not carried by this renderer export.', stage='source_before_ir'), loss(f'{cell_id}-prior-f16', 'unavailable_in_source', 'Original F16 integers/scale and prior per-vertex quantization bounds cannot be recovered from Float32-expanded MAP source.', stage='source_before_ir'), loss(f'{cell_id}-unknown-datums-units', 'unavailable_in_source', 'No explicit cell horizontal datum, height datum or verified chart depth unit is carried. Native metre interpretation is inferred; sounding datum text retained exactly.', stage='source_before_ir'), loss(f'{cell_id}-float32-bound', 'representation_change', 'Per-cell Float32 numerical rounding bounds retained in decoder-report.json; they do not measure hydrographic accuracy.')])
    result = {'format': 'no5-chart-ir/1', 'kind': 'cell', 'id': cell_id, 'name': ph.get('display_name') or ph.get('name') or cell_id, 'source': {'format': source_format, 'dialect': 'map-osenc-201-three-index', 'member': member, 'sha256': row['files'][member], 'provenanceMember': f'provenance/{cell_id}.json', 'provenanceSha256': psha, 'edition': h[4], 'update': h[6], 'publishDate': h[3], 'updateDate': h[5], 'originalHeader': ph, 'authorizationSnapshot': provenance['helperStatus'], 'authorizationMeaning': 'historical_original_helper_export_check_only' if source_format == 'no5-pi-map-cache-v1' else 'synthetic_fixture_no_license_claim'}, 'bbox': bbox([c[6], c[2], c[3], c[7]], cell_id), 'referencePosition': reference, 'nativeScale': h[7], 'datums': {'horizontal': declaration(), 'sounding': sounding, 'vertical': declaration()}, 'units': units, 'coverage': coverage, 'topology': {'edges': [edges[i] for i in sorted(edges)], 'nodes': [nodes[i] for i in sorted(nodes)]}, 'features': features, 'textDocuments': [{'name': name, 'content': content, 'sourceReference': f'provenance/{cell_id}.json#/text/{name}'} for name, content in provenance['text'].items()], 'summary': summary, 'losses': losses}
    metrics = {'id': cell_id, 'summary': summary, 'geometryKinds': {k: raw['counts'].get(k, 0) for k in ('point', 'line', 'area', 'multipoint')}, 'precision': transform.precision(), 'unresolved': unresolved, 'unknownClassFeatures': sum(f['classAcronym'] is None for f in features), 'unknownAttributeOccurrences': sum(a['acronym'] is None for f in features for a in f['attributes']), 'zeroEdgeRefs': sum(e['signedEdgeId'] == 0 for f in features if f['geometry']['kind'] in ('line', 'area') for e in f['geometry']['edgeRefs']), 'negativeEdgeRefs': sum(e['signedEdgeId'] < 0 for f in features if f['geometry']['kind'] in ('line', 'area') for e in f['geometry']['edgeRefs']), 'textDocuments': len(result['textDocuments'])}
    return result, metrics


def private_parent(path):
    path = Path(path).absolute()
    need(not path.is_symlink() and not any(p.is_symlink() for p in path.parents),
         'UNSAFE_OUTPUT_PATH', 'output path traverses symlink')
    path.mkdir(parents=True, exist_ok=True, mode=0o700)
    need(path.is_dir(), 'UNSAFE_OUTPUT_PATH', 'output parent is not a directory')
    return path


def atomic_publish(staging, destination):
    libc = ctypes.CDLL(None, use_errno=True)
    if platform.system() == 'Darwin' and hasattr(libc, 'renamex_np'):
        function = libc.renamex_np
        function.argtypes = [ctypes.c_char_p, ctypes.c_char_p, ctypes.c_uint]
        function.restype = ctypes.c_int
        ctypes.set_errno(0)
        code = function(os.fsencode(staging), os.fsencode(destination), 4)  # RENAME_EXCL
    elif platform.system() == 'Linux' and hasattr(libc, 'renameat2'):
        function = libc.renameat2
        function.argtypes = [ctypes.c_int, ctypes.c_char_p, ctypes.c_int, ctypes.c_char_p, ctypes.c_uint]
        function.restype = ctypes.c_int
        ctypes.set_errno(0)
        code = function(-100, os.fsencode(staging), -100, os.fsencode(destination), 1)
    else:
        raise DecodeError('ATOMIC_PUBLISH_UNAVAILABLE', 'OS lacks supported no-clobber directory rename')
    if code:
        error = ctypes.get_errno()
        detail = f'atomic no-clobber publication refused (errno={error} {errno.errorcode.get(error, "UNKNOWN")}: {os.strerror(error)})'
        if error in (errno.EEXIST, errno.ENOTEMPTY):
            raise DecodeError('OUTPUT_EXISTS', detail)
        if error in (errno.ENOSYS, errno.ENOTSUP, errno.EOPNOTSUPP):
            raise DecodeError('ATOMIC_PUBLISH_UNAVAILABLE', detail + '; use a supported filesystem for the complete generation, then a separate verified-copy receipt for transfer')
        raise DecodeError('ATOMIC_PUBLISH_FAILED', detail)


def write_private(path, data):
    with Path(path).open('xb') as output:
        output.write(data)
        output.flush()
        os.fsync(output.fileno())
    os.chmod(path, 0o600)


def inspect_archive(archive):
    infos = archive.infolist()
    names = [i.filename for i in infos]
    need(1 <= len(infos) <= 3001 and len(set(names)) == len(names) and len({n.casefold() for n in names}) == len(names), 'INVALID_ARCHIVE_INVENTORY', 'member count or duplicate/case-colliding members')
    for info in infos:
        name = info.filename
        need(not name.startswith('/') and '\\' not in name and ':' not in name and '..' not in PurePosixPath(name).parts and not info.is_dir(), 'UNSAFE_SOURCE_PATH', 'unsafe archive member name')
        need(not info.flag_bits & 1 and not stat.S_ISLNK(info.external_attr >> 16), 'UNSAFE_ARCHIVE_MEMBER', 'encrypted or symlink archive member')
        limit = MAX_PROVENANCE if name.startswith('provenance/') else 2 * 1024 * 1024 if name == 'no5-pi-map-cache.json' else MAX_CELL
        need(info.file_size <= limit, 'SOURCE_LIMIT', 'archive member exceeds declared limit')
    need(sum(i.file_size for i in infos) <= MAX_EXPANDED, 'SOURCE_LIMIT', 'archive expands beyond limit')
    need('no5-pi-map-cache.json' in names, 'MISSING_MANIFEST', 'export manifest missing')
    manifest = strict_json(archive.read('no5-pi-map-cache.json'), 'source manifest')
    need(isinstance(manifest, dict) and manifest.get('format') == 'no5-pi-map-cache-v1', 'UNSUPPORTED_DIALECT', 'expected No5 MAP export package')
    need(isinstance(manifest.get('product'), str) and 1 <= len(manifest['product']) <= 200, 'INVALID_MANIFEST', 'product declaration missing or exceeds limit')
    rows = manifest.get('cells')
    need(isinstance(rows, list) and 1 <= len(rows) <= 1500, 'INVALID_MANIFEST', 'cell list missing or beyond limit')
    identifiers = []
    bound = {'no5-pi-map-cache.json'}
    for row in rows:
        need(isinstance(row, dict) and isinstance(row.get('name'), str) and re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_.-]{0,79}', row['name']), 'INVALID_MANIFEST', 'invalid cell identifier')
        identifier = row['name']
        identifiers.append(identifier)
        paths = {f'cells/{identifier}.senc', f'provenance/{identifier}.json'}
        need(row.get('base') == f'cells/{identifier}.senc' and isinstance(row.get('files'), dict) and set(row['files']) == paths, 'INVALID_MANIFEST', 'cell payload binding differs from expected paths')
        need({'edition', 'update', 'featureCount', 'generation', 'issueDate', 'extent'} <= set(row), 'INVALID_MANIFEST', 'missing cell metadata')
        need(all(isinstance(row[k], int) and not isinstance(row[k], bool) and 0 <= row[k] <= 65535 for k in ('edition', 'update')), 'INVALID_MANIFEST', 'invalid cell edition/update')
        need(isinstance(row['featureCount'], int) and not isinstance(row['featureCount'], bool) and 0 <= row['featureCount'] <= 65536, 'INVALID_MANIFEST', 'invalid cell feature count')
        need(isinstance(row['generation'], str) and re.fullmatch('[0-9a-f]{64}', row['generation']) and isinstance(row['issueDate'], str), 'INVALID_MANIFEST', 'invalid cell generation/date')
        need(isinstance(row['extent'], dict) and set(row['extent']) == {'west', 'south', 'east', 'north'} and all(isinstance(v, (float, int)) and not isinstance(v, bool) and math.isfinite(v) for v in row['extent'].values()), 'INVALID_MANIFEST', 'invalid cell bounds')
        for digest in row['files'].values():
            need(isinstance(digest, str) and re.fullmatch('[0-9a-f]{64}', digest), 'INVALID_MANIFEST', 'invalid cell hash')
        bound.update(paths)
    need(len(set(identifiers)) == len(identifiers) and len({n.casefold() for n in identifiers}) == len(identifiers), 'INVALID_MANIFEST', 'duplicate cell IDs')
    need(set(names) == bound, 'INVALID_ARCHIVE_INVENTORY', 'unbound or missing export member')
    return manifest


def decode(source_zip, cell_filter=None, destination=None, *, expected_sha256=None, progress=False, catalog_dir=None, catalog_hashes=None, max_output_bytes=None):
    need(max_output_bytes is None or type(max_output_bytes) is int and max_output_bytes > 0,
         'INVALID_OUTPUT_BUDGET', 'max_output_bytes must be a positive explicit byte budget')
    started, clock_start = now(), time.monotonic()
    os.umask(0o077)
    source = Path(source_zip).absolute()
    need(source.is_file() and not source.is_symlink(), 'UNSAFE_SOURCE_PATH', 'source is not a regular non-symlink archive')
    before = source.stat()
    need(before.st_size <= MAX_ARCHIVE, 'SOURCE_LIMIT', 'source archive too large')
    source_digest = file_sha(source)
    need(expected_sha256 is None or source_digest == expected_sha256, 'SOURCE_HASH_MISMATCH', 'source archive digest differs from expected pin')
    schema_path = files('no_chart_converter').joinpath('resources/ir-schema.json')
    need(file_sha(schema_path) == IR_SCHEMA_SHA, 'SCHEMA_HASH_MISMATCH', 'frozen IR contract changed')
    class_names, attr_names, csv_pins = catalogs(catalog_dir, catalog_hashes)
    need(destination is not None, 'OUTPUT_REQUIRED', 'provide an explicit output generation directory')
    destination = Path(destination)
    destination = destination.absolute()
    private_parent(destination.parent)
    need(not destination.exists() and not destination.is_symlink(), 'OUTPUT_EXISTS', 'output generation already exists')
    staging = Path(tempfile.mkdtemp(prefix='.ir-staging-', dir=destination.parent))
    (staging / 'cells').mkdir(mode=0o700)
    written_output = 0
    def write_stage(relative, data):
        nonlocal written_output
        need(max_output_bytes is None or written_output + len(data) <= max_output_bytes,
             'OUTPUT_BYTE_BUDGET', 'decoded IR exceeds explicit temporary/output budget')
        write_private(staging / relative, data)
        written_output += len(data)
    report = {'schema': 'no5-decoder-report/1', 'package': 'AP02', 'startedUtc': started, 'source': {'path': str(source), 'bytes': before.st_size, 'sha256': source_digest}, 'schemaSha256': IR_SCHEMA_SHA, 'dialect': 'map-osenc-201-three-index', 'destination': str(destination), 'stagingPathDuringDecode': str(staging), 'catalogs': csv_pins, 'cells': [], 'newHelperRequests': 0, 'piAccess': False, 'labAccess': False, 'plotterAcceptance': 'pending', 'tools': {'python': sys.version.split()[0], 'shapely': shapely.__version__, 'geos': shapely.geos_version_string}, 'sourcePayloadsCopiedIntoProject': False}
    try:
        totals = Counter()
        descriptors = []
        with zipfile.ZipFile(source) as archive:
            manifest = inspect_archive(archive)
            selected = set(cell_filter) if cell_filter is not None else None
            known_ids = {r['name'] for r in manifest['cells']}
            need(selected is None or selected and selected <= known_ids, 'INVALID_CELL_FILTER', 'diagnostic cell filter is empty or unknown')
            for row in manifest['cells']:
                bodies = {}
                for member, expected in row['files'].items():
                    bodies[member] = archive.read(member)
                    need(sha(bodies[member]) == expected, 'SOURCE_HASH_MISMATCH', 'cell/provenance digest differs from manifest', row['name'])
                provenance_name = f'provenance/{row["name"]}.json'
                provenance = strict_json(bodies[provenance_name], 'cell provenance')
                need(isinstance(provenance, dict) and set(provenance) == {'originalHeader', 'helperStatus', 'text'}, 'INVALID_PROVENANCE', 'provenance has unexpected members', row['name'])
                need(isinstance(provenance['originalHeader'], dict) and isinstance(provenance['helperStatus'], dict) and isinstance(provenance['text'], dict), 'INVALID_PROVENANCE', 'invalid provenance object types', row['name'])
                need(all(isinstance(k, str) and isinstance(v, str) for k, v in provenance['text'].items()), 'INVALID_PROVENANCE', 'invalid provenance text documents', row['name'])
                # Retain the original status. This is not a current entitlement check.
                need(provenance['helperStatus'].get('decrypt_status') not in (None, 0) and provenance['helperStatus'].get('expire_status') not in (None, 0), 'INVALID_PROVENANCE', 'retained export status says refused/expired or is missing', row['name'])
                if selected is not None and row['name'] not in selected:
                    continue
                cell, metrics = decode_cell(bodies[row['base']], provenance, row, class_names, attr_names)
                payload = canonical(cell)
                relative = f'cells/{row["name"]}.json'
                write_stage(relative, payload)
                descriptors.append({'id': row['name'], 'path': relative, 'bytes': len(payload), 'sha256': sha(payload), 'featureCount': cell['summary']['features'], 'attributeCount': cell['summary']['attributes']})
                totals.update(cell['summary'])
                report['cells'].append(metrics)
                if progress:
                    print(json.dumps({'package': 'AP02', 'cell': row['name'], 'cellsDecoded': len(descriptors), 'features': cell['summary']['features'], 'unresolved': len(metrics['unresolved']), 'staging': str(staging)}), flush=True)
                del cell, payload, bodies
            model = {'format': 'no5-chart-ir/1', 'kind': 'package', 'source': {'format': manifest['format'], 'sha256': source_digest, 'bytes': before.st_size, 'product': manifest['product'], 'declaredCellCount': len(manifest['cells'])}, 'converter': {'name': 'AP02-map-senc-ir', 'version': VERSION, 'sourceSha256': file_sha(Path(__file__))}, 'coordinatePolicy': {'name': 'opencpn-fromSM_plib', 'semimajorAxisMeters': 6378137.0, 'mercatorScale': 0.9996, 'axisOrder': 'longitude_latitude', 'numeric': 'float64', 'longitudeWrap': 'normalize_preserve_crossing', 'sourcePrecision': 'float32_local_float64_geographic', 'evidence': [evidence('source_code', str(Path(__file__)), 'Pinned inverse SM constants; Float32 source values exactly promoted before transformation', file_sha(Path(__file__)))]}, 'cells': descriptors, 'summary': dict(totals), 'losses': [loss('package-prior-source-limitations', 'unavailable_in_source', 'Renderer export does not restore original S-57 relations, raw ring vertices, prior F16 integers/scale or verified unknown datum/unit declarations.', stage='source_before_ir')]}
            if selected is not None:
                model['losses'].append(loss('diagnostic-cell-filter', 'representation_change', f'Diagnostic selection contains {len(descriptors)} of {len(manifest["cells"])} source cells; this is not the complete source product.', sorted(selected)))
            write_stage('ir-manifest.json', canonical(model))
        after = source.stat()
        need((before.st_size, before.st_mtime_ns, before.st_ino) == (after.st_size, after.st_mtime_ns, after.st_ino) and file_sha(source) == source_digest, 'SOURCE_CHANGED', 'input archive changed while decoding')
        report.update(completedUtc=now(), elapsedSeconds=round(time.monotonic() - clock_start, 3), summary=dict(totals), decodedAllDeclaredCells=len(descriptors) == len(manifest['cells']), diagnosticFilter=sorted(selected) if selected is not None else None, sourceHashAndStatUnchanged=True, rawDataPreserved=True, unresolvedFeatures=sum(len(r['unresolved']) for r in report['cells']), outputBytes=sum(d['bytes'] for d in descriptors), manifestSha256=file_sha(staging / 'ir-manifest.json'), peakRssBytes=resource.getrusage(resource.RUSAGE_SELF).ru_maxrss * (1 if platform.system() == 'Darwin' else 1024))
        report['maximumOutputBytes'] = max_output_bytes
        report['writtenBytesBeforeReceipt'] = written_output
        write_stage('decode-receipt.json', canonical(report))
        atomic_publish(staging, destination)
        return destination / 'ir-manifest.json'
    except Exception:
        shutil.rmtree(staging)
        raise
