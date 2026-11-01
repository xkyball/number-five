"""Explicit local inputs/outputs; no automatic media or device operations."""
import argparse
from dataclasses import fields
import hashlib
import json
import os
from pathlib import Path
import stat
import struct
import uuid


def write_new(path, raw):
    path = Path(path)
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, 'wb') as stream:
        stream.write(raw)
        stream.flush()
        os.fsync(stream.fileno())


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='command', required=True)
    fill = sub.add_parser('cache-fill', help='Add public Natural Earth cells to an unchanged Pi cache')
    fill.add_argument('--source', type=Path, required=True)
    fill.add_argument('--land', type=Path, required=True, help='Pinned Natural Earth 5.1.1 ne_10m_land.zip')
    fill.add_argument('--bathymetry', type=Path, help='Pinned Natural Earth 4.1.0 ne_10m_bathymetry_all.zip')
    fill.add_argument('--terrain-grid', type=Path, help='Region-only ETOPO2022 60s NPZ from fetch-terrain')
    fill.add_argument('--terrain-sha256', help='Required exact NPZ input hash with --terrain-grid')
    fill.add_argument('--elevation-points', type=Path, help='Pinned Natural Earth 5.0.0 elevation points ZIP')
    fill.add_argument('--view-aspect', nargs=2, type=int, metavar=('WIDTH','HEIGHT'),
                      help='Expand public coverage around an exact Mercator display aspect; no vector rasterization')
    fill.add_argument('--output', type=Path, required=True)
    fill.add_argument('--grid-degrees', type=int, default=10)
    fill.add_argument('--expect-sha256')
    terrain = sub.add_parser('fetch-terrain', help='Fetch a bounded region of public ETOPO2022 heights')
    terrain.add_argument('--bounds', nargs=4, type=float, required=True, metavar=('WEST','SOUTH','EAST','NORTH'))
    terrain.add_argument('--output', type=Path, required=True)
    nav = sub.add_parser('write-navionics-directory', help='Write our bounded cleartext synthetic header directory; no geometry or native execution')
    nav_input = nav.add_mutually_exclusive_group(required=True)
    nav_input.add_argument('--spec', type=Path, help='Explicit JSON raw-header spec (maximum 64 KiB)')
    nav_input.add_argument('--synthetic-two-header', action='store_true', help='Reproduce the own original-reader-tested 166-byte fixture')
    nav.add_argument('--expect-spec-sha256', help='Required exact byte SHA256 with --spec')
    nav.add_argument('--output', type=Path, required=True)
    nav_point = sub.add_parser('write-navionics-point-fixture', help='Write one fixed own Point40 test preset; no native execution or rendering')
    nav_point.add_argument('--prefix', choices=('label', 'type16', 'type16-plain'), default='label',
                           help='Label, minimum Type16, or extended Type16 with own plain declaration; all pointers rebased, no loader admission claim')
    nav_point.add_argument('--output', type=Path, required=True)
    nav_line = sub.add_parser('write-navionics-line-fixture', help='Write one fixed own three-point NoScale Line32 preset; no native execution')
    nav_line.add_argument('--output', type=Path, required=True)
    for name in ('plan-navionics-batch', 'write-navionics-batch',
                 'plan-navionics-layered-batch', 'write-navionics-layered-batch'):
        batch = sub.add_parser(name, help='Own raw Plain FeatureBatch; no general native acceptance')
        batch.add_argument('--spec', type=Path, required=True)
        batch.add_argument('--expect-spec-sha256', required=True)
        batch.add_argument('--max-output-bytes', type=int, default=4096,
                           help='Own output budget 1..8192; above4096 is source-bound, general native qualification open')
        if name in ('write-navionics-batch', 'write-navionics-layered-batch'):
            batch.add_argument('--expect-plan-sha256', required=True)
            batch.add_argument('--output', type=Path, required=True)
    inspect = sub.add_parser('inspect', help='Check our unprotected RX5 wire and scene references')
    inspect.add_argument('source', type=Path)
    decode = sub.add_parser('decode', help='Decode no5-pi-map-cache-v1 ZIP to retained vector IR')
    decode.add_argument('--source', type=Path, required=True)
    decode.add_argument('--output', type=Path, required=True)
    decode.add_argument('--catalogs', type=Path, required=True)
    decode.add_argument('--expect-sha256')
    decode.add_argument('--cells', help='Comma separated diagnostic source selection; default all')
    area = sub.add_parser('area-picker', help='Serve the existing region-selection browser tool on loopback')
    area.add_argument('--source', type=Path, required=True)
    area.add_argument('--store', type=Path, required=True)
    area.add_argument('--expect-sha256')
    area.add_argument('--port', type=int, default=8767)
    area.add_argument('--tile-url', help='Explicit optional HTTPS template with {z}/{x}/{y}; default no external tiles')
    area.add_argument('--tile-attribution', default='© OpenStreetMap contributors')
    area.add_argument('--tile-attribution-url', default='https://www.openstreetmap.org/copyright')
    export = sub.add_parser('export-country', help='Compile a fresh cache ZIP into prepared country inputs and RX5')
    export.add_argument('--source', type=Path, required=True)
    export.add_argument('--catalogs', type=Path, required=True)
    export.add_argument('--config', type=Path, required=True)
    export.add_argument('--output', type=Path, required=True)
    export.add_argument('--expect-sha256')
    export.add_argument('--cells', help='Explicit diagnostic cell selection; default all source cells')
    export.add_argument('--prepare', action='store_true', help='Create/validate all prepared inputs without a country RX5')
    export.add_argument('--retained-ir-manifest', type=Path,
                        help='Explicit completed retained IR; recheck exact source/catalog/cell pins without copying or decoding again')
    export.add_argument('--expect-ir-sha256', help='Required manifest SHA256 with --retained-ir-manifest')
    provider = sub.add_parser('s52-provider', help='Run the optional explicit public-source S52 programme provider')
    provider.add_argument('--request', type=Path, required=True)
    provider.add_argument('--output', type=Path, required=True)
    provider.add_argument('--engine', type=Path, required=True)
    provider.add_argument('--assets', type=Path, required=True)
    provider.add_argument('--cache', type=Path, required=True)
    country = sub.add_parser('country', help='Compose scale-aware RX5 from explicit prepared source input pins')
    country.add_argument('--config', type=Path, required=True)
    country.add_argument('--prepare', action='store_true', help='Validate/prepare without publishing a country chart')
    region = sub.add_parser('region', help='Create a lossless complete-owner RX5 region')
    region.add_argument('--source', type=Path, required=True)
    region.add_argument('--store', type=Path, required=True)
    region.add_argument('--expect-sha256')
    region.add_argument('--bbox', type=float, nargs=4, metavar=('WEST', 'SOUTH', 'EAST', 'NORTH'), required=True)
    region.add_argument('--name', required=True)
    region.add_argument('--margin-nm', type=float, default=0)
    region.add_argument('--estimate', action='store_true')
    optimize = sub.add_parser('optimize-masks', help='Apply certified V22 convex MASK8 preparation')
    optimize.add_argument('--source', type=Path, required=True)
    optimize.add_argument('--output', type=Path, required=True)
    optimize.add_argument('--report', type=Path, required=True)
    sounding = sub.add_parser('materialize-soundings', help='Add exact original-gated same-UID Kind4/9 twins without new geometry')
    sounding.add_argument('--source', type=Path, required=True)
    sounding.add_argument('--expect-sha256', required=True)
    sounding.add_argument('--output', type=Path, required=True)
    sounding.add_argument('--report', type=Path, required=True)
    arena_plan = sub.add_parser('plan-source-arenas', help='Plan lossless full-element I/C compaction for explicit native source tiles')
    arena_plan.add_argument('--source', type=Path, required=True)
    arena_plan.add_argument('--expect-sha256', required=True)
    arena_plan.add_argument('--tile', type=int, nargs=3, action='append', required=True,
                            metavar=('LEVEL', 'X', 'Y'))
    arena_plan.add_argument('--output', type=Path, required=True)
    source_arena = sub.add_parser('compact-source-arenas', help='Apply an exact source/kernel/scope-bound lossless arena plan')
    source_arena.add_argument('--source', type=Path, required=True)
    source_arena.add_argument('--expect-sha256', required=True)
    source_arena.add_argument('--expect-plan-sha256', required=True)
    source_arena.add_argument('--tile', type=int, nargs=3, action='append', required=True,
                              metavar=('LEVEL', 'X', 'Y'))
    source_arena.add_argument('--output', type=Path, required=True)
    source_arena.add_argument('--report', type=Path, required=True)
    duplicates = sub.add_parser('analyze-fill-duplicates', help='Analyze consecutive identical XY vertices in explicit pure-AC source tiles; writes no chart')
    duplicates.add_argument('--source', type=Path, required=True)
    duplicates.add_argument('--expect-sha256', required=True)
    duplicates.add_argument('--tile', type=int, nargs=3, action='append', required=True,
                            metavar=('LEVEL', 'X', 'Y'))
    duplicates.add_argument('--anchor-policy', choices=('analyze_all','preserve_native_polygon0'),
                            default='analyze_all',
                            help='Optional closed-Root1 native Polygon0 input protection; remains analysis only')
    duplicates.add_argument('--output', type=Path, required=True)
    profiles = sub.add_parser('compare-decoder-profiles', help='Compare two pinned stored decoder reports; executes no decoder')
    profiles.add_argument('--baseline', type=Path, required=True)
    profiles.add_argument('--candidate', type=Path, required=True)
    profiles.add_argument('--expect-baseline-report-sha256', required=True)
    profiles.add_argument('--expect-candidate-report-sha256', required=True)
    profiles.add_argument('--expect-baseline-chart-sha256', required=True)
    profiles.add_argument('--expect-candidate-chart-sha256', required=True)
    profiles.add_argument('--allow-synthetic', action='store_true',
                          help='Explicitly allow invented profile controls, without hardware timing claims')
    profiles.add_argument('--output', type=Path, required=True)
    locality_plan = sub.add_parser('plan-locality', help='Prepare source-bound L15 locality candidates for independent review')
    locality_plan.add_argument('--source', type=Path, required=True)
    locality_plan.add_argument('--expect-sha256')
    locality_plan.add_argument('--tile', type=int, nargs=3, action='append', required=True,
                               metavar=('LEVEL', 'X', 'Y'))
    locality_plan.add_argument('--output', type=Path, required=True)
    locality = sub.add_parser('apply-locality', help='Apply an exact independently reviewed L15 locality allowlist')
    locality.add_argument('--source', type=Path, required=True)
    locality.add_argument('--allowlist', type=Path, required=True)
    locality.add_argument('--expect-allowlist-sha256', required=True)
    locality.add_argument('--output', type=Path, required=True)
    locality.add_argument('--report', type=Path, required=True)
    prune = sub.add_parser('prune', help='Apply explicit chart-bound independently approved decisions')
    prune.add_argument('--source', type=Path, required=True)
    approval = prune.add_mutually_exclusive_group(required=True)
    approval.add_argument('--decisions', type=Path, help='Advanced typed core decisions; caller validates external proofs')
    approval.add_argument('--allowlist', type=Path, help='V23 reviewed allowlist; all external evidence pins are rechecked')
    prune.add_argument('--expect-allowlist-sha256')
    prune.add_argument('--title', help='Optional appended menu title with the reviewed allowlist')
    prune.add_argument('--output', type=Path, required=True)
    args = parser.parse_args(argv)
    try:
        if args.command == 'fetch-terrain':
            from .terrain import fetch_grid
            result = fetch_grid(args.output, args.bounds)
        elif args.command == 'cache-fill':
            from .cache_fill import fill_cache
            result = fill_cache(args.source, args.land, args.output, step=args.grid_degrees,
                                expected_source_sha256=args.expect_sha256, bathymetry_archive=args.bathymetry,
                                terrain_grid=args.terrain_grid, terrain_sha256=args.terrain_sha256,
                                elevation_points_archive=args.elevation_points, view_aspect=args.view_aspect)
        elif args.command in ('plan-navionics-batch', 'write-navionics-batch',
                            'plan-navionics-layered-batch', 'write-navionics-layered-batch'):
            from .navionics.batch import plan_sha256
            if args.command in ('plan-navionics-layered-batch', 'write-navionics-layered-batch'):
                from .navionics.layered_batch import (
                    layered_batch_from_spec as batch_from_spec,
                    plan_layered_batch as plan_plain_batch,
                    write_layered_batch as write_plain_batch,
                )
            else:
                from .navionics.batch import batch_from_spec, plan_plain_batch, write_plain_batch
            descriptor = os.open(args.spec, os.O_RDONLY | os.O_NONBLOCK | os.O_NOFOLLOW)
            with os.fdopen(descriptor, 'rb') as stream:
                if not stat.S_ISREG(os.fstat(stream.fileno()).st_mode):
                    raise ValueError('REGULAR_SPEC_FILE_REQUIRED')
                snapshot = stream.read(65537)
            if len(snapshot) > 65536:
                raise ValueError('SPEC_EXCEEDS_64_KIB')
            digest = hashlib.sha256(snapshot).hexdigest()
            if digest != args.expect_spec_sha256:
                raise ValueError('SPEC_SHA256_MISMATCH')
            def unique_batch_keys(pairs):
                row = {}
                for key, val in pairs:
                    if key in row:
                        raise ValueError('DUPLICATE_SPEC_JSON_KEY')
                    row[key] = val
                return row
            spec = batch_from_spec(json.loads(snapshot, object_pairs_hook=unique_batch_keys))
            plan = plan_plain_batch(spec, max_output_bytes=args.max_output_bytes)
            plan_digest = plan_sha256(plan)
            result = {'status': 'OWN_PLAIN_BATCH_PLAN_READY', 'plan': plan,
                      'planSHA256': plan_digest, 'input': {'bytes': len(snapshot), 'sha256': digest},
                      'nativeReaderExecuted': False, 'nativeAcceptance': 'UNTESTED_GENERALIZATION'}
            if args.command in ('write-navionics-batch', 'write-navionics-layered-batch'):
                if args.expect_plan_sha256 != plan_digest:
                    raise ValueError('EXACT_EXPECTED_PLAN_SHA256_REQUIRED')
                descriptor = os.open(args.output, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
                with os.fdopen(descriptor, 'wb') as stream:
                    emission = write_plain_batch(spec, stream, expected_plan_sha256=plan_digest,
                                                 max_output_bytes=args.max_output_bytes)
                    stream.flush()
                    os.fsync(stream.fileno())
                stored = args.output.read_bytes()
                if len(stored) != emission['bytesWritten'] or hashlib.sha256(stored).hexdigest() != emission['emittedStreamSHA256']:
                    raise ValueError('PERSISTED_BATCH_BYTES_MISMATCH')
                result.update(status='OWN_PLAIN_BATCH_WRITTEN', emission=emission,
                              output={'path': str(args.output), 'bytes': len(stored),
                                      'sha256': hashlib.sha256(stored).hexdigest()},
                              persistedFileReadback=True)
        elif args.command == 'write-navionics-line-fixture':
            from .navionics.line import build_three_point_line_fixture, read_three_point_line_fixture
            if args.output.exists() or args.output.is_symlink():
                raise ValueError('OUTPUT_EXISTS')
            output = build_three_point_line_fixture()
            readback = read_three_point_line_fixture(output)
            write_new(args.output, output)
            if args.output.read_bytes() != output:
                raise ValueError('PERSISTED_LINE_BYTES_MISMATCH')
            result = {'format': 'no5-nav-line-fixture-build/1', 'status': 'OWN_FIXED_LINE_FIXTURE_WRITTEN',
                      'output': {'path': str(args.output), 'bytes': len(output),
                                 'sha256': hashlib.sha256(output).hexdigest()},
                      'wireReadback': readback, 'nativeReaderExecuted': False,
                      'supportedGeometry': 'ONE_FIXED_LINE32_NO_SCALE_RAW_U16_NO_ATTRIBUTES',
                      'worldScaleCRSPortrayal': 'UNPROVEN', 'renderingTested': False,
                      'nativeAcceptance': 'NOT_TESTED_BY_THIS_COMMAND'}
        elif args.command == 'write-navionics-point-fixture':
            from .navionics.point import build_absolute_point_fixture, read_absolute_point_fixture
            if args.output.exists() or args.output.is_symlink():
                raise ValueError('OUTPUT_EXISTS')
            output = build_absolute_point_fixture(prefix=args.prefix)
            readback = read_absolute_point_fixture(output)
            write_new(args.output, output)
            if args.output.read_bytes() != output:
                raise ValueError('PERSISTED_POINT_BYTES_MISMATCH')
            result = {'format': 'no5-nav-point-fixture-build/1', 'status': 'OWN_FIXED_POINT_FIXTURE_WRITTEN',
                      'output': {'path': str(args.output), 'bytes': len(output),
                                 'sha256': hashlib.sha256(output).hexdigest()},
                      'wireReadback': readback, 'nativeReaderExecuted': False,
                      'supportedGeometry': 'ONE_FIXED_POINT40_NO_ATTRIBUTES',
                      'geographicInterpretation': 'UNPROVEN', 'renderingTested': False,
                      'nativeAcceptance': 'NOT_TESTED_BY_THIS_COMMAND'}
        elif args.command == 'write-navionics-directory':
            from .navionics.writer import directory_from_spec, read_header_directory, synthetic_two_header_directory
            if args.output.exists() or args.output.is_symlink():
                raise ValueError('OUTPUT_EXISTS')
            source = {'kind': 'BUILTIN_OWN_TWO_HEADER_FIXTURE'}
            if args.spec is not None:
                if args.expect_spec_sha256 is None:
                    raise ValueError('EXPLICIT_SPEC_SHA256_REQUIRED')
                descriptor = os.open(args.spec, os.O_RDONLY | os.O_NONBLOCK)
                with os.fdopen(descriptor, 'rb') as stream:
                    if not stat.S_ISREG(os.fstat(stream.fileno()).st_mode):
                        raise ValueError('REGULAR_SPEC_FILE_REQUIRED')
                    snapshot = stream.read(65537)
                if len(snapshot) > 65536:
                    raise ValueError('SPEC_EXCEEDS_64_KIB')
                digest = hashlib.sha256(snapshot).hexdigest()
                if digest != args.expect_spec_sha256:
                    raise ValueError('SPEC_SHA256_MISMATCH')
                def unique_keys(pairs):
                    result = {}
                    for key, value in pairs:
                        if key in result:
                            raise ValueError('DUPLICATE_SPEC_JSON_KEY')
                        result[key] = value
                    return result
                output = directory_from_spec(json.loads(snapshot, object_pairs_hook=unique_keys))
                source = {'kind': 'EXPLICIT_RAW_HEADER_SPEC', 'bytes': len(snapshot), 'sha256': digest}
            else:
                if args.expect_spec_sha256 is not None:
                    raise ValueError('SPEC_SHA256_REQUIRES_SPEC')
                output = synthetic_two_header_directory()
            readback = read_header_directory(output)
            write_new(args.output, output)
            if args.output.read_bytes() != output:
                raise ValueError('PERSISTED_DIRECTORY_BYTES_MISMATCH')
            result = {'format': 'no5-nav-header-directory-build/1',
                      'status': 'OWN_HEADER_DIRECTORY_WRITTEN', 'input': source,
                      'output': {'path': str(args.output), 'bytes': len(output),
                                 'sha256': hashlib.sha256(output).hexdigest()},
                      'wireReadback': readback, 'nativeReaderExecuted': False,
                      'geometryWritten': False, 'geographicInterpretation': 'UNPROVEN',
                      'customSpecNativeAcceptance': 'NOT_TESTED_BY_THIS_COMMAND'}
        elif args.command == 'inspect':
            from .rx5.model import validate
            raw = args.source.read_bytes()
            result = {'status': 'PASS_OWN_WIRE', 'sha256': hashlib.sha256(raw).hexdigest(), **validate(raw)}
        elif args.command == 'decode':
            from .cache_decoder import decode
            path = decode(args.source, args.cells.split(',') if args.cells else None,
                          args.output, expected_sha256=args.expect_sha256,
                          catalog_dir=args.catalogs)
            result = {'status': 'DECODED_IR', 'manifest': str(path)}
        elif args.command == 'region':
            from .rx5.region import configure
            service = configure(args.source, args.store, expected_sha256=args.expect_sha256)
            service.prepare_index()
            request = {'bbox': args.bbox, 'name': args.name, 'marginNm': args.margin_nm,
                       'minLevel': 0, 'maxLevel': 21}
            result = service.estimate(request) if args.estimate else service.export(request)
        elif args.command == 'area-picker':
            from .area_picker import serve
            result = serve(args.source, args.store, port=args.port, expected_sha256=args.expect_sha256,
                           tile_url=args.tile_url, attribution=args.tile_attribution,
                           attribution_url=args.tile_attribution_url)
        elif args.command == 'export-country':
            from .cache_country import export_country
            result = export_country(args.source, args.catalogs, args.output, config=args.config,
                                    expected_sha256=args.expect_sha256,
                                    cell_filter=args.cells.split(',') if args.cells else None,
                                    prepare_only=args.prepare,
                                    retained_ir_manifest=args.retained_ir_manifest,
                                    expected_ir_sha256=args.expect_ir_sha256)
        elif args.command == 's52-provider':
            from .cache_country.native_provider import run_provider
            result = run_provider(args.request, args.output, engine=args.engine,
                                  assets=args.assets, cache=args.cache)
        elif args.command == 'country':
            from .country_runtime import build_country
            result = build_country(args.config, prepare_only=args.prepare)
        elif args.command == 'analyze-fill-duplicates':
            from .rx5.fill_duplicates import analyze_fill_duplicates
            from .rx5.header import validate_plain_vector_header
            from .rx5.metadata import Stream
            from .rx5.model import native_tree
            from .rx5.wire import blocks, need
            if args.output.exists():
                raise ValueError('OUTPUT_EXISTS')
            raw = args.source.read_bytes()
            digest = hashlib.sha256(raw).hexdigest()
            need(digest == args.expect_sha256, 'FILL_DUPLICATE_SOURCE_SHA256_MISMATCH')
            validate_plain_vector_header(raw[:240], file_bytes=len(raw))
            need(all(struct.unpack_from('<Q', raw, p)[0] == 0 for p in (152, 176, 192)),
                 'OWN_PLAIN_SOURCE_ONLY')
            tiles = [tuple(tile) for tile in args.tile]
            need(len(set(tiles)) == len(tiles), 'UNIQUE_FILL_DUPLICATE_SOURCE_TILES')
            need(all(0 <= z <= 29 and 0 <= x < 1 << z and 0 <= y < 1 << z for z,x,y in tiles),
                 'FILL_DUPLICATE_SOURCE_TILE_BOUNDS')
            parsed = blocks(memoryview(raw))
            by_tile = {tile:at for at,tile in native_tree(raw, parsed).items()}
            rows = []
            for tile in sorted(tiles):
                need(tile in by_tile, 'EXISTING_EXACT_FILL_DUPLICATE_SOURCE_TILE')
                at = by_tile[tile]
                node = parsed[at]
                need(node.flags == 3 and node.next_offset == 0, 'EXACT_UNCHAINED_FILL_DUPLICATE_VECTOR_SOURCE')
                addresses = struct.unpack_from('<4Q', node.prefix, 32)
                need(all(address in parsed for address in addresses), 'FILL_DUPLICATE_SOURCE_STREAMS_RESOLVE')
                streams, pins = [], {}
                for name,address,disk_id in zip(('indices','coordinates','render','additional'),addresses,
                                               (0x1101,0x1102,0x1103,0x1104)):
                    block = parsed[address]
                    need((block.disk_id,block.flags,bytes(block.prefix),block.next_offset) == (disk_id,0,b'',0),
                         'PLAIN_UNCHAINED_FILL_DUPLICATE_SOURCE_STREAM')
                    payload = block.payload
                    streams.append(Stream(len(payload), lambda p,n,b=payload: bytes(b[p:p+n])))
                    pins[name] = {'offset':address,'bytes':len(payload),'sha256':hashlib.sha256(payload).hexdigest()}
                analysis = analyze_fill_duplicates(bytes(node.payload), *streams[:3], additional=streams[3],
                                                   anchor_policy=args.anchor_policy)
                rows.append({'tile':list(tile),'nodeOffset':at,
                             'featuresSHA256':hashlib.sha256(node.payload).hexdigest(),
                             'sourceStreams':pins,'analysis':analysis})
            result = {'format':'no5-source-fill-duplicate-analysis/1',
                      'status':'ANALYSIS_ONLY_NO_CHART_CREATED',
                      'source':{'bytes':len(raw),'sha256':digest},'rows':rows,
                      'chartMaterialized':False,'nativeDecoderExecuted':False,
                      'physicalA97TimingMeasured':False,'operativeApplyAllowed':False,
                      'nativeQueryDisposition':'UNPROVEN',
                      'originalCentroidPreservation':'UNPROVEN_DUPLICATE_WEIGHTED_F32_MEAN',
                      'anchorPolicy':args.anchor_policy,
                      'aliasReviewScope':'EACH_SELECTED_SOURCE_PAYLOAD_INDEPENDENTLY',
                      'crossSourceAliasReviewComplete':False}
            write_new(args.output, (json.dumps(result, indent=2, sort_keys=True, allow_nan=False) + '\n').encode())
        elif args.command == 'compare-decoder-profiles':
            from .decoder_profile import compare_decoder_profile_files
            if args.output.exists():
                raise ValueError('OUTPUT_EXISTS')
            result = compare_decoder_profile_files(args.baseline, args.candidate,
                expected_baseline_report_sha256=args.expect_baseline_report_sha256,
                expected_candidate_report_sha256=args.expect_candidate_report_sha256,
                expected_baseline_chart_sha256=args.expect_baseline_chart_sha256,
                expected_candidate_chart_sha256=args.expect_candidate_chart_sha256,
                allow_synthetic=args.allow_synthetic)
            write_new(args.output, (json.dumps(result, indent=2, sort_keys=True, allow_nan=False) + '\n').encode())
        elif args.command in ('plan-source-arenas', 'compact-source-arenas'):
            from .rx5.source_arena import plan_source_arenas, plan_sha256, compact_source_arenas
            if args.output.exists():
                raise ValueError('OUTPUT_EXISTS')
            if args.command == 'compact-source-arenas' and (
                    args.report.exists() or args.report.resolve() == args.output.resolve()):
                raise ValueError('FRESH_DISTINCT_SOURCE_ARENA_OUTPUT_AND_REPORT_REQUIRED')
            raw = args.source.read_bytes()
            if hashlib.sha256(raw).hexdigest() != args.expect_sha256:
                raise ValueError('SOURCE_ARENA_SOURCE_SHA256_MISMATCH')
            if args.command == 'plan-source-arenas':
                plan = plan_source_arenas(raw, args.tile)
                document = (json.dumps(plan, indent=2, sort_keys=True) + '\n').encode()
                write_new(args.output, document)
                result = {'status': 'EXPLICIT_SOURCE_ARENA_PLAN_CREATED',
                          'canonicalPlanSHA256': plan_sha256(plan),
                          'plan': {'path': str(args.output), 'bytes': len(document),
                                   'sha256': hashlib.sha256(document).hexdigest()}}
            else:
                output, result = compact_source_arenas(raw, args.tile,
                    expected_source_sha256=args.expect_sha256,
                    expected_plan_sha256=args.expect_plan_sha256)
                write_new(args.output, output)
                if hashlib.sha256(args.output.read_bytes()).hexdigest() != result['output']['sha256']:
                    raise ValueError('SOURCE_ARENA_PERSISTED_SHA256_MISMATCH')
                result['chart'] = {'path': str(args.output), **result['output']}
                result['persistedSHA256Verified'] = True
                write_new(args.report, (json.dumps(result, indent=2, sort_keys=True) + '\n').encode())
        elif args.command == 'materialize-soundings':
            from .rx5.soundings import materialize_sounding_twins
            raw = args.source.read_bytes()
            if hashlib.sha256(raw).hexdigest() != args.expect_sha256:
                raise ValueError('SOUNDING_SOURCE_SHA256_MISMATCH')
            if args.output.exists() or args.report.exists():
                raise ValueError('OUTPUT_EXISTS')
            output, result = materialize_sounding_twins(raw)
            write_new(args.output, output)
            result['chart'] = {'path': str(args.output), 'bytes': len(output),
                               'sha256': hashlib.sha256(output).hexdigest()}
            if hashlib.sha256(args.output.read_bytes()).hexdigest() != result['chart']['sha256']:
                raise ValueError('SOUNDING_PERSISTED_SHA256_MISMATCH')
            write_new(args.report, (json.dumps(result, indent=2, sort_keys=True) + '\n').encode())
        elif args.command == 'optimize-masks':
            from .rx5.optimize import optimize_masks
            from .rx5.model import validate
            output, result = optimize_masks(args.source.read_bytes())
            result['wireReadback'] = validate(output)
            if args.output.exists() or args.report.exists():
                raise ValueError('OUTPUT_EXISTS')
            write_new(args.output, output)
            write_new(args.report, (json.dumps(result, indent=2, sort_keys=True) + '\n').encode())
        elif args.command == 'plan-locality':
            from .rx5.locality_apply import prepare_scoped_plan
            if args.output.exists():
                raise ValueError('OUTPUT_EXISTS')
            raw = args.source.read_bytes()
            if args.expect_sha256 is not None and hashlib.sha256(raw).hexdigest() != args.expect_sha256:
                raise ValueError('EXPLICIT_LOCALITY_SOURCE_SHA256_MISMATCH')
            result = prepare_scoped_plan(raw, args.tile)
            write_new(args.output, (json.dumps(result, indent=2, sort_keys=True) + '\n').encode())
        elif args.command == 'apply-locality':
            from .rx5.locality_apply import apply_scoped_locality
            from .rx5.model import validate
            if args.output.exists() or args.report.exists() or args.output == args.report:
                raise ValueError('FRESH_DISTINCT_LOCALITY_OUTPUT_AND_REPORT_REQUIRED')
            output, result = apply_scoped_locality(args.source.read_bytes(), args.allowlist,
                                                  args.expect_allowlist_sha256)
            result['wireReadback'] = validate(output)
            write_new(args.output, output)
            write_new(args.report, (json.dumps(result, indent=2, sort_keys=True) + '\n').encode())
        else:
            from .rx5.pruning import PruneDecision, apply_verified_prunes
            if args.allowlist is not None:
                if args.expect_allowlist_sha256 is None:
                    raise ValueError('EXPLICIT_REVIEWED_ALLOWLIST_SHA256_REQUIRED')
                from .rx5.approvals import apply_allowlist
                output, result = apply_allowlist(args.source.read_bytes(), args.allowlist,
                                                args.expect_allowlist_sha256, title=args.title)
            else:
                if args.expect_allowlist_sha256 is not None or args.title is not None:
                    raise ValueError('ALLOWLIST_OPTIONS_REQUIRE_REVIEWED_ALLOWLIST')
                decision_bytes = args.decisions.read_bytes()
                decision_data = json.loads(decision_bytes)
                required = {f.name for f in fields(PruneDecision)}
                if not isinstance(decision_data, list) or any(set(row) != required for row in decision_data):
                    raise ValueError('EXPLICIT_COMPLETE_DECISION_LIST_REQUIRED')
                if any(row['query_disposition'] != 'IDENTICAL_WITHIN_PROVED_CHILD_DOMAIN' for row in decision_data):
                    raise ValueError('V23_REQUIRES_QUERY_IDENTITY_NO_NAUTICAL_LOSS_OVERRIDE')
                decisions = [PruneDecision(**{**row, 'tile': tuple(row['tile']),
                             'fallback_tile': tuple(row['fallback_tile'])}) for row in decision_data]
                raw = args.source.read_bytes()
                identity = uuid.uuid5(uuid.NAMESPACE_URL, 'no5-portable-prunes:' + hashlib.sha256(raw).hexdigest()
                    + ':' + hashlib.sha256(decision_bytes).hexdigest()).bytes_le if decisions else None
                output = apply_verified_prunes(raw, decisions, uuid_bytes=identity)
                result = {'status': 'APPLIED_CALLER_APPROVED_POINTER_CHANGES', 'actualChildPrunes': len(decisions),
                          'outputSHA256': hashlib.sha256(output).hexdigest(),
                          'externalEvidencePinsRechecked': False}
            write_new(args.output, output)
        print(json.dumps(result, sort_keys=True, allow_nan=False))
        return 0
    except (ValueError, OSError, KeyError, TypeError, struct.error) as error:
        print(json.dumps({'status': 'REFUSED', 'reason': str(error), 'type': type(error).__name__}))
        return 2


if __name__ == '__main__':
    raise SystemExit(main())
