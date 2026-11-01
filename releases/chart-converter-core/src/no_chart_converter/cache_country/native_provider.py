"""Explicit public S52 compiler provider; no original/OEM libraries or pixels."""
import hashlib
import json
import math
import os
from pathlib import Path, PurePosixPath
import subprocess
import tempfile
import zipfile


def _canonical(value):
    return json.dumps(value, sort_keys=True, separators=(',', ':'),
                      ensure_ascii=False, allow_nan=False).encode()


def _pin(path):
    path = Path(path)
    before = path.stat()
    digest = hashlib.sha256()
    size = 0
    with path.open('rb') as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b''):
            digest.update(chunk)
            size += len(chunk)
    after = path.stat()
    stable = ('st_dev', 'st_ino', 'st_size', 'st_mtime_ns', 'st_ctime_ns')
    if any(getattr(before, key) != getattr(after, key) for key in stable):
        raise ValueError('S52_PROVIDER_INPUT_CHANGED')
    return dict(path=str(path.resolve()), bytes=size, sha256=digest.hexdigest())


def _write_new(path, value):
    raw = _canonical(value) + b'\n'
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, 'wb') as stream:
        stream.write(raw)
        stream.flush()
        os.fsync(stream.fileno())


def run_provider(request: Path, output: Path, *, engine: Path, assets: Path,
                 cache: Path) -> dict:
    """Bind fresh feature requests to actual complete-cell OpenCPN LUP/CSP output.

    The explicitly selected native engine is built from our public bridge source
    and an external No5/OpenCPN source/build tree. A reusable RX5 presentation
    profile must separately establish compatibility of its symbols and colours.
    This provider compiles instructions; it cannot certify a97 portrayal parity.
    """
    request, output, engine, assets, cache = map(Path, (request, output, engine, assets, cache))
    if output.exists():
        raise ValueError('S52_PROVIDER_OUTPUT_EXISTS')
    if not engine.is_absolute() or not engine.is_file() or not os.access(engine, os.X_OK):
        raise ValueError('S52_PROVIDER_EXPLICIT_ENGINE_REQUIRED')
    if not assets.is_dir() or not cache.is_dir():
        raise ValueError('S52_PROVIDER_ASSETS_AND_CACHE_REQUIRED')
    request_pin = _pin(request)
    raw = request.read_bytes()
    if hashlib.sha256(raw).hexdigest() != request_pin['sha256']:
        raise ValueError('S52_PROVIDER_REQUEST_CHANGED')
    data = json.loads(raw)
    if data.get('format') != 'no5-cache-country-s52-request/1':
        raise ValueError('S52_PROVIDER_REQUEST_FORMAT')
    profile_sha = data.get('profileSHA256')
    if not isinstance(profile_sha, str) or len(profile_sha) != 64:
        raise ValueError('S52_PROVIDER_PROFILE_PIN')
    try:
        bytes.fromhex(profile_sha)
    except ValueError as error:
        raise ValueError('S52_PROVIDER_PROFILE_PIN') from error
    settings = data.get('settings', {})
    defaults = dict(safetyDepthMetres=3.0, shallowDepthMetres=2.0,
                    deepDepthMetres=6.0, depthUnit='metres', twoShades=False)
    if not isinstance(settings, dict) or set(settings) - defaults.keys():
        raise ValueError('S52_PROVIDER_UNSUPPORTED_SETTING')
    settings = {**defaults, **settings}
    if type(settings['twoShades']) is not bool:
        raise ValueError('S52_PROVIDER_SETTINGS')
    values = [settings[key] for key in ('safetyDepthMetres', 'shallowDepthMetres', 'deepDepthMetres')]
    if any(type(value) not in (int, float) or not math.isfinite(value) for value in values):
        raise ValueError('S52_PROVIDER_SETTINGS')
    safety, shallow, deep = values
    if not 0 <= shallow <= safety <= deep or settings['depthUnit'] not in ('feet', 'metres', 'fathoms'):
        raise ValueError('S52_PROVIDER_SETTINGS')
    cells = data.get('cells')
    if not isinstance(cells, list) or not cells:
        raise ValueError('S52_PROVIDER_CELLS_REQUIRED')
    engine_pin = _pin(engine)
    asset_pins = [_pin(assets / name) for name in (
        'chartsymbols.xml', 's57objectclasses.csv', 's57attributes.csv', 'DejaVuSans.ttf')]
    source_pins, result_cells, cell_ids = [], [], set()
    # A fresh job prevents concurrent requests from sharing temporary files.
    with tempfile.TemporaryDirectory(prefix='no5-s52-job-', dir=cache) as temporary:
        temporary = Path(temporary)
        for ordinal, cell in enumerate(cells):
            cid = cell.get('cellId')
            if not isinstance(cid, str) or not cid or cid in cell_ids:
                raise ValueError('S52_PROVIDER_DUPLICATE_CELL')
            cell_ids.add(cid)
            if 'sourceCellPath' in cell:
                source = Path(cell['sourceCellPath']).resolve(strict=True)
                pin = _pin(source)
                if pin['sha256'] != cell.get('sourceCellSHA256'):
                    raise ValueError('S52_PROVIDER_SOURCE_PIN')
            else:
                package = data.get('packageArchive', {})
                actual = _pin(package['path'])
                if (actual['bytes'], actual['sha256']) != (package['bytes'], package['sha256']):
                    raise ValueError('S52_PROVIDER_PACKAGE_PIN')
                member = cell['source']['member']
                name = PurePosixPath(member)
                if name.is_absolute() or '..' in name.parts or '\\' in member:
                    raise ValueError('S52_PROVIDER_MEMBER_PATH')
                with zipfile.ZipFile(package['path']) as archive:
                    info = archive.getinfo(member)
                    if info.file_size > 128 * 1024 * 1024:
                        raise ValueError('S52_PROVIDER_SOURCE_LIMIT')
                    content = archive.read(member)
                source = temporary / f'cell-{ordinal}.senc'
                source.write_bytes(content)
                pin = _pin(source)
                if pin['sha256'] != cell['source']['sha256']:
                    raise ValueError('S52_PROVIDER_SOURCE_PIN')
            if pin['bytes'] > 128 * 1024 * 1024 or source.suffix.lower() != '.senc':
                raise ValueError('S52_PROVIDER_SOURCE_DIALECT')
            source_pins.append(pin)
            from ..cache_decoder import parse_senc
            parsed_source = parse_senc(source.read_bytes(), cell_id=cid,
                                       member='verified-provider-source.senc')
            source_features = {f['id']: f for f in parsed_source['features']}
            contexts = {}
            for context in ('paper', 'simple'):
                command = [str(engine), str(source), str(assets.resolve()), str(temporary), context,
                           str(safety), str(shallow), str(deep),
                           str(('feet', 'metres', 'fathoms').index(settings['depthUnit'])),
                           str(int(settings['twoShades']))]
                run = subprocess.run(command, capture_output=True, timeout=180, check=False)
                if run.returncode:
                    raise ValueError('S52_PROVIDER_ENGINE_FAILED:' + cid + ':' + context +
                        ':returncode=' + str(run.returncode) +
                        ':stderr=' + run.stderr[-1500:].decode(errors='replace') +
                        ':stdout=' + run.stdout[-512:].decode(errors='replace'))
                parsed = json.loads(run.stdout)
                if parsed.get('format') != 'no5-opencpn-s52-engine/1' or parsed.get('context') != context:
                    raise ValueError('S52_PROVIDER_ENGINE_FORMAT')
                by_id = {row['sourceFeatureId']: row for row in parsed['owners']}
                if len(by_id) != len(parsed['owners']):
                    raise ValueError('S52_PROVIDER_DUPLICATE_ENGINE_FEATURE')
                contexts[context] = by_id
            owners, ids = [], set()
            for requested in cell['owners']:
                fid = requested['sourceFeatureId']
                if type(fid) is not int or fid in ids:
                    raise ValueError('S52_PROVIDER_DUPLICATE_REQUEST_FEATURE')
                ids.add(fid)
                digest = requested['featureSHA256']
                feature = requested['feature']
                if digest != hashlib.sha256(_canonical(feature)).hexdigest():
                    raise ValueError('S52_PROVIDER_FEATURE_PIN')
                if feature['sourceFeatureId'] != fid:
                    raise ValueError('S52_PROVIDER_FEATURE_ID')
                original = source_features.get(fid)
                if original is None or (feature['classCode'], feature['sourcePrimitive']) != (
                        original['classCode'], original['primitive']):
                    raise ValueError('S52_PROVIDER_FEATURE_SOURCE_BINDING')
                fields = lambda rows: [(r['code'], r['wireType'], r['value']) for r in rows]
                if fields(feature['attributes']) != fields(original['attributes']):
                    raise ValueError('S52_PROVIDER_FEATURE_ATTRIBUTES_DIFFER')
                geometry, original_geometry = feature['geometry'], original['geometry']
                if geometry['kind'] != original_geometry['kind']:
                    raise ValueError('S52_PROVIDER_FEATURE_GEOMETRY_DIFFERS')
                kind = geometry['kind']
                if kind == 'point' and geometry['position'] != original_geometry['position']:
                    raise ValueError('S52_PROVIDER_FEATURE_GEOMETRY_DIFFERS')
                if kind == 'multipoint' and [r['positionSm'] + [r['depth']['value']]
                        for r in geometry['points']] != original_geometry['pointsSmDepth']:
                    raise ValueError('S52_PROVIDER_FEATURE_GEOMETRY_DIFFERS')
                if kind in ('area', 'line'):
                    refs = [[r['startNodeId'], r['signedEdgeId'], r['endNodeId']]
                            for r in geometry['edgeRefs']]
                    if refs != original_geometry['refs']:
                        raise ValueError('S52_PROVIDER_FEATURE_GEOMETRY_DIFFERS')
                triangles = lambda rows: [{k: r[k] for k in ('mode', 'coordinatesSm', 'bbox')}
                                           for r in rows]
                if kind == 'area' and (geometry['contourPointCounts'] != original_geometry['contourPointCounts'] or
                        triangles(geometry['trianglePrimitives']) != original_geometry['trianglePrimitives']):
                    raise ValueError('S52_PROVIDER_FEATURE_GEOMETRY_DIFFERS')
                programmes, selections = {}, {}
                for context, key in (('paper', '196608'), ('simple', '196609')):
                    row = contexts[context].get(fid)
                    if row is None or row.get('status') != 'resolved':
                        raise ValueError('S52_PROVIDER_UNRESOLVED_FEATURE:' + cid + ':' + context +
                                         ':' + str(fid) + ':' + str(row))
                    if row['classAcronym'] != feature['classAcronym']:
                        raise ValueError('S52_PROVIDER_FEATURE_CLASS')
                    programmes[key] = row['instruction'].encode('utf-8').hex()
                    category = {68: 'DISPLAYBASE', 83: 'STANDARD', 79: 'OTHER'}.get(row['category'])
                    if category is None:
                        raise ValueError('S52_PROVIDER_UNSUPPORTED_CATEGORY')
                    selections[key] = dict(sourceRCID=row['rcid'], sourceTable=row['table'],
                        priority=row['priority'], category=category, instruction=row['instruction'],
                        conditionals=row['conditionals'], originalInstruction=row['originalInstruction'],
                        symbolResolutions=row.get('symbolResolutions', []))
                owners.append(dict(sourceFeatureId=fid, featureSHA256=digest,
                    programmes=programmes, selections=selections, type=contexts['paper'][fid]['type']))
            if _pin(source)['sha256'] != pin['sha256']:
                raise ValueError('S52_PROVIDER_SOURCE_CHANGED')
            result_cells.append(dict(cellId=cid, owners=owners))
    if _pin(engine) != engine_pin or [_pin(Path(row['path'])) for row in asset_pins] != asset_pins:
        raise ValueError('S52_PROVIDER_RESOLVER_CHANGED')
    result = dict(format='no5-cache-country-s52-response/1', profileSHA256=profile_sha,
        settings=settings, cells=result_cells, resolver=dict(engine=engine_pin, assets=asset_pins,
        request=request_pin, sourceCells=source_pins, viewportFiltering=False,
        fullCellAssociations=True, originalOEMLibrariesUsed=False, physicalPortrayalParityClaim=False))
    _write_new(output, result)
    return result
