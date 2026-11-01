"""Independent fresh-source decoder→adapter→country→region acceptance tests.

The generator owns the invented OSENC records, topology, styles and assertions.
No previous prepared country inputs, acquired source data, or native oracle is
used. Passing these host tests does not assert chart accuracy or a97 acceptance.
"""
from collections import Counter, defaultdict
from fractions import Fraction
import hashlib
import json
import math
from pathlib import Path
import struct
import xml.etree.ElementTree as ET

import pytest
from shapely.geometry import Point, Polygon
from shapely.ops import unary_union

from cache_country_fixture import (canonical, file_pin, geographic, rewrite_archive,
                                   write_package, write_presentation_config)
from no_chart_converter import cache_decoder as decoder
from no_chart_converter.cache_country import export_country
from no_chart_converter.country_runtime.inputs import REQUIRED
from no_chart_converter.rx5.model import native_tree, parts, programmes, validate
from no_chart_converter.rx5.region import configure
from no_chart_converter.rx5.wire import FEATURE, blocks

Q = 2147483649


def document(pin):
    raw = Path(pin['path']).read_bytes()
    assert len(raw) == pin['bytes']
    assert hashlib.sha256(raw).hexdigest() == pin['sha256']
    return json.loads(raw)


def root_coordinate(position):
    """Independent documented root coordinate convention, not adapter import."""
    lon, lat = position
    return (math.floor(((lon+180)/360)*Q+0.5),
            math.floor((1-math.log(math.tan(math.pi/4+math.radians(lat)/2))/math.pi)/2*Q+0.5))


def owners(raw):
    parsed = blocks(raw)
    for at, tile in native_tree(raw, parsed).items():
        node = parsed[at]
        if node.flags != 3:
            continue
        io, co, ro, ao = struct.unpack_from('<4Q', node.prefix, 32)
        for offset in range(0, len(node.payload), 32):
            record = node.payload[offset:offset+32]
            fields = FEATURE.unpack(record)
            contexts, query = programmes(parsed[ro].payload, fields[2])
            yield dict(fields=fields, tile=tile, contexts=contexts,
                       additional=parsed[ao].payload, query=query,
                       parts=list(parts(record, parsed[io].payload, parsed[co].payload)))


def query_values(raw, offset):
    """Read native QString key/value framing independently of metadata encoder."""
    assert raw[offset:offset+8] == bytes(8)
    count = struct.unpack_from('<H', raw, offset+8)[0]
    pos, result = offset+10, {}
    for _ in range(count):
        code = struct.unpack_from('<H', raw, pos)[0]
        pos += 2
        end = raw.index(b'\0', pos)
        assert code not in result
        result[code] = raw[pos:end].decode('utf8')
        pos = end+1
    assert raw[pos:pos+4] == bytes(4)
    return result


@pytest.fixture(scope='module')
def full_country(tmp_path_factory):
    root = tmp_path_factory.mktemp('fresh-cache-country').resolve()
    package = write_package(root/'source')
    config = write_presentation_config(root/'profile')
    original = package.source.read_bytes()
    result = export_country(package.source, package.catalogs, root/'output', config=config,
                            expected_sha256=hashlib.sha256(original).hexdigest())
    assert package.source.read_bytes() == original
    return root, package, config, result


def test_actual_fresh_multicell_decoder_topology_and_types(tmp_path):
    root = tmp_path.resolve()
    package = write_package(root/'source')
    output = root/'ir'
    manifest_path = decoder.decode(package.source, destination=output, catalog_dir=package.catalogs)
    manifest = json.loads(manifest_path.read_bytes())
    assert manifest['summary'] == dict(cells=2, features=8, attributes=22, edges=2,
                                       edgeRefs=22, nodes=26, soundings=6)
    for row, expected in zip(manifest['cells'], package.cells):
        cell = json.loads((output/row['path']).read_bytes())
        assert cell['id'] == expected['id']
        assert cell['nativeScale'] == expected['nativeScale']
        assert [f['sourceFeatureId'] for f in cell['features']] == expected['featureIds']
        area, line, buoy, sounding = cell['features']
        assert area['geometry']['reconstruction']['status'] == 'exact_topology'
        assert area['geometry']['polygons'] == [{'exteriorRingId':'r0', 'interiorRingIds':['r1']}]
        assert [p['coordinatesSm'] for p in line['geometry']['parts']] == [list(map(list, p)) for p in expected['linePartsSm']]
        assert buoy['geometry']['position'] == expected['buoyPosition']
        assert [p['depth']['value'] for p in sounding['geometry']['points']] == [p[2] for p in expected['soundings']]
        assert all(p['depth']['unit']['state'] == 'inferred' for p in sounding['geometry']['points'])
        assert next(a['value'] for a in area['attributes'] if a['code'] == 116).endswith(' α')
        assert len(cell['coverage']['covered']) == len(cell['coverage']['excluded']) == 1


def test_full_adapter_fifteen_pins_and_fresh_country_readback(full_country):
    root, package, config, result = full_country
    assert result['status'] == 'PASS_CACHE_TO_COUNTRY'
    receipt = document(result['adapterReceipt'])
    prepared = document(result['preparedConfig'])
    assert set(prepared['inputs']) == set(REQUIRED)
    assert len(prepared['inputs']) == 15
    for pin in prepared['inputs'].values():
        path = Path(pin['path'])
        assert path.is_relative_to(root/'output')
        assert file_pin(path) == pin
    assert receipt['sourceCells'] == 2 and receipt['sourceFeatures'] == 8
    assert receipt['historicalOwnerManifestsUsed'] is False
    assert receipt['newHelperRequests'] == 0
    assert receipt['nativeScaminLevelIsSourceDenominator'] is False
    assert receipt['sourceDatumAndUnitClaimsUpgraded'] is False
    assert receipt['physicalA97AcceptanceClaim'] is False
    assert receipt['nativeTargetPolicy'] == {'nativeScaminLevelU32':0, 'nativeScaminMultiplierU32':1}
    assert result['country']['status'] == 'PASS_PERSISTED_NATIVE_GRAPH_METADATA_QUERY_UID_GEOMETRY'
    assert result['country']['uniqueUIDs'] == 8
    inventory = document(result['country']['ownerInventory'])
    expected_uids = {((i+1)<<32)|fid for i,c in enumerate(package.cells) for fid in c['featureIds']}
    assert {int(r['uid']) for r in inventory['rows']} == expected_uids
    assert Counter(r['kind'] for r in inventory['rows']) == {1:2, 2:2, 3:2, 4:2}
    raw = Path(result['chart']['path']).read_bytes()
    assert len(raw) == result['chart']['bytes'] and hashlib.sha256(raw).hexdigest() == result['chart']['sha256']
    assert validate(raw)['owners'] >= 8
    assert struct.unpack_from('<I', raw, 216)[0] == 1
    assert all(r['fields'][4] == 0 for r in owners(raw))
    runtime = document(result['country']['runtimeInputReceipt'])
    assert runtime['execution']['nativeCalls'] == 0
    assert runtime['physicalPlotterAcceptanceClaim'] is False


def test_marker_labels_queries_original_scamin_and_signed_depths(full_country):
    _, package, _, result = full_country
    raw = Path(result['chart']['path']).read_bytes()
    actual = defaultdict(list)
    for row in owners(raw):
        actual[row['fields'][0]].append(row)
    for i, cell in enumerate(package.cells):
        base = (i+1)<<32
        area_id, line_id, buoy_id, sounding_id = cell['featureIds']
        for fid, expected_name, expected_scamin in [
            (area_id,f"Area {cell['id']} α",None),
            (line_id,f"Harbour line {cell['id']}",4000+i*2000),
            (buoy_id,f"Buoy {cell['id']}",10000),
            (sounding_id,f"Depth set {cell['id']}",20000)]:
            for row in actual[base|fid]:
                attrs = query_values(row['additional'], row['query'])
                assert attrs[116] == expected_name
                assert (int(attrs[133]) if 133 in attrs else None) == expected_scamin
                assert set(row['contexts']) == {'196608', '196609'}
        buoy = actual[base|buoy_id]
        assert all(r['parts'][0]['points'] == [root_coordinate(cell['buoyPosition'])] for r in buoy)
        assert any(b'SY(FAKE0001)' in p and b'TX(OBJNAM,' in p for r in buoy for p in r['contexts'].values())
        assert all(query_values(r['additional'],r['query'])[75] == '3,1' for r in buoy)
        sounding = actual[base|sounding_id]
        depths = {v[2] for r in sounding for p in r['parts'] for v in struct.iter_unpack('<IIi', p['raw'][4:])}
        assert depths == {0, -12, 124+i*10}
        assert all(b'CS(SOUNDG01)' in p for r in sounding for p in r['contexts'].values())
    receipt = document(result['adapterReceipt'])
    geometry = document(receipt['geometry'])
    sounding_receipts = [r for r in geometry['owners'] if r['kind'] == 4]
    assert len(sounding_receipts) == 2
    for row in sounding_receipts:
        depth_rows = row['geometryReceipt']['depthRows']
        assert Fraction(*depth_rows[1]['absoluteErrorMetresExact']) == Fraction(1,20)
        assert Fraction(*depth_rows[2]['absoluteErrorMetresExact']) == Fraction(1,40)
        assert row['geometryReceipt']['datumConversion'] is False
        assert all(v['unit']['state'] == 'inferred' for v in depth_rows)


def test_holes_authentic_coverage_and_disconnected_lines(full_country):
    _, package, _, result = full_country
    prepared = document(result['preparedConfig'])
    # Check exact un-clipped authored line parts independently, before country quilting.
    detail = document(prepared['inputs']['detailInputs'])
    for cell, expected in zip(detail['cells'], package.cells):
        streams = {name:Path(pin['path']).read_bytes() for name,pin in cell['preparedStreams'].items()}
        rows = list(FEATURE.iter_unpack(streams['features']))
        assert len(rows) == 1
        native = list(parts(FEATURE.pack(*rows[0]),streams['indices'],streams['coordinates']))
        lon,lat = expected['reference']
        assert [p['points'] for p in native] == [[root_coordinate(geographic(x,y,lon,lat)) for x,y in line] for line in expected['linePartsSm']]
        assert [p['usage'] for p in native] == [16,16]
    raw = Path(result['chart']['path']).read_bytes()
    areas = defaultdict(lambda: {'outer':[], 'inner':[]})
    for row in owners(raw):
        if row['fields'][6] == 3:
            for p in row['parts']:
                assert p['usage'] in (8,9,24,25,20,21)
                role = 'outer' if p['usage'] & 12 == 8 else 'inner'
                areas[row['fields'][0]][role].append(Polygon(p['points']))
    for i, cell in enumerate(package.cells):
        rings = areas[((i+1)<<32)|101]
        filled = unary_union(rings['outer']).difference(unary_union(rings['inner']))
        lon,lat = cell['reference']
        assert not filled.covers(Point(root_coordinate(geographic(0,0,lon,lat))))
        assert filled.covers(Point(root_coordinate(geographic(-300,0,lon,lat))))
        assert not filled.covers(Point(root_coordinate(geographic(350,150,lon,lat))))
    service = configure(Path(result['chart']['path']), Path(result['chart']['path']).parent/'coverage-index')
    coverage = service.coverage()['coverageGeoJSON']['features']
    assert Counter((r['properties']['sourceCell'],r['properties']['role']) for r in coverage) == Counter((c['id'],role) for c in package.cells for role in ('covered','excluded'))
    for c in package.cells:
        actual = next(r for r in coverage if r['properties'] == dict(sourceCell=c['id'],role='covered'))
        assert len(actual['geometry']['coordinates'][0]) == 5


def test_real_region_selects_only_requested_fresh_cell(full_country):
    root, package, _, result = full_country
    service = configure(Path(result['chart']['path']), root/'regions')
    service.prepare_index()
    c = package.cells[0]
    box = c['extent']
    region = service.export(dict(name='INVENTED ALPHA ONLY',bbox=[box['west'],box['south'],box['east'],box['north']],minLevel=0,maxLevel=21))
    raw = Path(region['chart']['path']).read_bytes()
    assert {r['fields'][0] for r in owners(raw)} == {(1<<32)|fid for fid in c['featureIds']}
    assert validate(raw)['owners'] >= 4


def refusal(root, package, config, reason, *, source=None, **kwargs):
    destination = root/'refused'
    with pytest.raises(ValueError, match=reason):
        export_country(source or package.source,package.catalogs,destination,config=config,**kwargs)
    report = json.loads((destination/'ADAPTER_REFUSAL.json').read_bytes())
    assert report['status'] == 'REFUSED' and report['successClaim'] is False
    assert report['historicalOwnerManifestsUsed'] is False
    assert not list(destination.glob('country/country/*.rx5'))
    return report


@pytest.mark.parametrize('option,reason', [
    ({'unknown_class':True},'MISSING_CLASS_CATALOG_NAME'),
    ({'ambiguous_name':True},'DUPLICATE_ATTRIBUTE_CODE'),
    ({'scale':0},'INVALID_SCALE'),
])
def test_source_semantic_refusals_never_drop_owners(tmp_path, option, reason):
    root = tmp_path.resolve()
    package = write_package(root/'source',**option)
    config = write_presentation_config(root/'profile')
    report = refusal(root,package,config,reason)
    if option.get('scale') != 0:
        assert report['irManifest'] is not None
        ir = document(report['irManifest'])
        assert ir['summary']['features'] == 8


@pytest.mark.parametrize('option,reason', [
    ({'missing_class':'BOYLAT'},'NO_MATCHING_GENERIC_CLASS_PRESENTATION'),
    ({'unknown_instruction':True},'UNSUPPORTED'),
])
def test_presentation_refusals_are_explicit_and_keep_ir(tmp_path, option, reason):
    root = tmp_path.resolve()
    package = write_package(root/'source')
    config = write_presentation_config(root/'profile',**option)
    report = refusal(root,package,config,reason)
    assert report['stage'] == 'presentation'
    assert document(report['irManifest'])['summary']['features'] == 8


def test_bad_source_hash_and_member_hash_fail_closed(tmp_path):
    root = tmp_path.resolve()
    package = write_package(root/'source')
    config = write_presentation_config(root/'profile')
    report = refusal(root,package,config,'SOURCE_HASH_MISMATCH',expected_sha256='0'*64)
    assert report['stage'] == 'decode'
    other = root/'member-case';other.mkdir()
    corrupt = rewrite_archive(package.source,other/'corrupt.zip',lambda members,manifest: members.__setitem__(manifest['cells'][0]['base'],b'corrupt'))
    report = refusal(other,package,config,'HASH_MISMATCH',source=corrupt)
    assert report['stage'] == 'decode'


def test_duplicate_source_cells_and_header_scale_mismatch_refused(tmp_path):
    root = tmp_path.resolve()
    package = write_package(root/'source')
    config = write_presentation_config(root/'profile')
    duplicate = rewrite_archive(package.source,root/'duplicate.zip',lambda members,manifest: manifest['cells'].append(dict(manifest['cells'][0])))
    report = refusal(root,package,config,'DUPLICATE|UNIQUE|MANIFEST',source=duplicate)
    assert report['stage'] == 'decode'
    mismatch_root = root/'mismatch';mismatch_root.mkdir()
    def mismatch(members,manifest):
        path = next(p for p in manifest['cells'][0]['files'] if p.endswith('.json'))
        provenance = json.loads(members[path]);provenance['originalHeader']['native_scale'] += 1
        members[path] = canonical(provenance)
    changed = rewrite_archive(package.source,mismatch_root/'mismatch.zip',mismatch,repin=True)
    report = refusal(mismatch_root,package,config,'SOURCE_HEADER_MISMATCH',source=changed)
    assert report['stage'] == 'decode'


def test_output_no_clobber_retains_existing_bytes(full_country):
    root, package, config, result = full_country
    before = Path(result['chart']['path']).read_bytes()
    with pytest.raises(ValueError,match='FRESH_OUTPUT'):
        export_country(package.source,package.catalogs,root/'output',config=config)
    assert Path(result['chart']['path']).read_bytes() == before


@pytest.mark.parametrize('reference,reason', [
    ([179.999,-16.98],'ANTIMERIDIAN_PATH_REQUIRES_EXPLICIT_SEAM_SPLIT'),
    ([21.02,86.0],'POSITION_OUTSIDE_SUPPORTED_MERCATOR_ROOT'),
])
def test_geographic_limits_refuse_without_silent_seam_or_pole_clamp(tmp_path, reference, reason):
    root = tmp_path.resolve()
    package = write_package(root/'source',reference=reference)
    config = write_presentation_config(root/'profile')
    report = refusal(root,package,config,reason)
    assert report['stage'] == 'geometry'
    assert document(report['irManifest'])['summary']['features'] == 8


def test_unknown_feature_datum_remains_unknown_and_never_transformed(tmp_path):
    root = tmp_path.resolve()
    package = write_package(root/'source',feature_datum=True)
    config = write_presentation_config(root/'profile')
    result = export_country(package.source,package.catalogs,root/'output',config=config)
    receipt = document(result['adapterReceipt'])
    assert receipt['sourceDatumAndUnitClaimsUpgraded'] is False
    ir = document(result['irManifest'])
    cell = json.loads((Path(result['irManifest']['path']).parent/ir['cells'][0]['path']).read_bytes())
    sounding = next(f for f in cell['features'] if f['classAcronym'] == 'SOUNDG')
    assert next(a['value'] for a in sounding['attributes'] if a['code'] == 185) == 99
    rows = document(receipt['geometry'])['owners']
    owner = next(r for r in rows if r['uid'] == (1<<32)|404)
    assert owner['geometryReceipt']['datumConversion'] is False
    assert [r['depthDcm'] for r in owner['geometryReceipt']['depthRows']] == [0,-12,124]
    assert all(r['datum']['state'] == 'unknown' and r['datum']['raw'] == 99 for r in owner['geometryReceipt']['depthRows'])


def test_prepare_only_produces_all_pins_without_country_materialization(tmp_path):
    root = tmp_path.resolve()
    package = write_package(root/'source')
    config = write_presentation_config(root/'profile')
    result = export_country(package.source,package.catalogs,root/'output',config=config,prepare_only=True)
    assert result['status'] == 'PASS_CACHE_TO_PREPARED'
    assert 'chart' not in result
    assert set(document(result['preparedConfig'])['inputs']) == set(REQUIRED)
    assert not list((root/'output'/'country').rglob('*.rx5'))
    assert document(result['adapterReceipt'])['prepareOnly'] is True


def test_source_metadata_types_document_and_programme_provenance(full_country):
    _, package, _, result = full_country
    receipt = document(result['adapterReceipt'])
    metadata = document(receipt['metadata'])
    assert metadata['originalChartParentUsed'] is False
    assert metadata['nativeScaleToLevelMapping'] is False
    assert metadata['sourceSCAMINToWord20Mapping'] is False
    assert metadata['nativeCalls'] == 0
    ledger = document(metadata['typedLedger'])
    assert len(ledger['owners']) == 8
    assert ledger['nativeQueryStoresQStringOnly'] is True
    ir = document(result['irManifest'])
    original = {}
    for row in ir['cells']:
        cell = json.loads((Path(result['irManifest']['path']).parent/row['path']).read_bytes())
        for feature in cell['features']:
            original[f"{cell['id']}:f{feature['sourceFeatureId']}"] = feature['attributes']
    for owner in ledger['owners']:
        assert [{k:v for k,v in item.items() if k not in ('sourceOrdinal','targetLexical','documentReference')} for item in owner['attributes']] == original[owner['sourceId']]
    assert [row['nativeScale'] for row in metadata['sources']] == [100000,25000]
    assert [row['sourceGeneration']['edition'] for row in metadata['sources']] == [3,4]
    assert [row['sourceGeneration']['update'] for row in metadata['sources']] == [0,1]
    assert len(metadata['documents']) == 2
    raw = Path(result['chart']['path']).read_bytes()
    parsed = blocks(raw)
    source_ids = [ET.fromstring(b.payload).find('source_chart_info').get('id') for b in parsed.values() if b.disk_id == 0x30]
    assert source_ids == [cell['id'] for cell in package.cells]
    for i,cell in enumerate(package.cells):
        doc = metadata['documents'][i]
        content = f"Entirely invented test document for {cell['id']}; never navigation data.".encode()
        assert doc['sha256'] == hashlib.sha256(content).hexdigest()
        assert any(b.flags == 64 and b.payload == content for b in parsed.values())
        area_uid = ((i+1)<<32)|101
        assert all(query_values(row['additional'],row['query'])[158] == doc['name'] for row in owners(raw) if row['fields'][0] == area_uid)
    report = document(receipt['presentation']['reportPin'])
    assert report['allOwnersCompiled'] is True and report['features'] == 8
    assert report['savedOwnerManifestReused'] is False
    assert report['originalNativeOEMLookupExecutionClaim'] is False


def test_filtered_cell_has_explicit_collection_local_uid_and_exact_source_binding(tmp_path):
    root = tmp_path.resolve()
    package = write_package(root/'source')
    config = write_presentation_config(root/'profile')
    selected = package.cells[1]
    result = export_country(package.source,package.catalogs,root/'output',config=config,
                            cell_filter=[selected['id']])
    assert result['country']['uniqueUIDs'] == 4
    assert {row['fields'][0] for row in owners(Path(result['chart']['path']).read_bytes())} == {(1<<32)|fid for fid in selected['featureIds']}
    receipt = document(result['adapterReceipt'])
    assert receipt['uidPolicy'] == '(selected-IR-cell-ordinal+1)<<32 | original-source-feature-id'
    ir = document(result['irManifest'])
    cell = json.loads((Path(result['irManifest']['path']).parent/ir['cells'][0]['path']).read_bytes())
    assert cell['id'] == selected['id'] and cell['nativeScale'] == 25000
    assert [f['sourceFeatureId'] for f in cell['features']] == selected['featureIds']
    geometry = document(receipt['geometry'])
    assert {r['cellId'] for r in geometry['owners']} == {selected['id']}
    assert {r['sourceId'] for r in geometry['owners']} == {f"{selected['id']}:f{fid}" for fid in selected['featureIds']}
    prepared = document(result['preparedConfig'])
    catalog = document(prepared['inputs']['groundGeometry'])
    bindings = document(catalog['cells'][0]['ownerBindings'])['owners']
    area = next(f for f in cell['features'] if f['sourceFeatureId'] == 101)
    digest = hashlib.sha256(canonical(area).rstrip(b'\n')).hexdigest()
    assert bindings[0]['featureSourceDigest'] == digest
    assert bindings[0]['sourceId'] == selected['id']+':f101'


def test_missing_symbol_resource_is_not_silently_replaced(tmp_path):
    root = tmp_path.resolve()
    package = write_package(root/'source')
    config = write_presentation_config(root/'profile')
    cfg = json.loads(config.read_bytes())
    profile_path = Path(cfg['presentationProfile']['path'])
    profile = json.loads(profile_path.read_bytes())
    common = profile_path.parent/profile['resources'][0]['data']['path']
    common.write_bytes(b'NO SYMBOL DEFINITIONS\n')
    profile['resources'][0]['data'] = file_pin(common)
    profile_path.write_bytes(canonical(profile))
    cfg['presentationProfile'] = file_pin(profile_path)
    config.write_bytes(canonical(cfg))
    report = refusal(root,package,config,'SYMBOL')
    assert report['stage'] == 'presentation'


def test_source_scamax_is_an_explicit_unsupported_visibility_contract(tmp_path):
    root = tmp_path.resolve()
    package = write_package(root/'source',source_scamax=True)
    config = write_presentation_config(root/'profile')
    report = refusal(root,package,config,'SOURCE_SCAMAX_VISIBILITY_NOT_IMPLEMENTED')
    assert report['stage'] == 'context'
    ir = document(report['irManifest'])
    cell = json.loads((Path(report['irManifest']['path']).parent/ir['cells'][0]['path']).read_bytes())
    buoy = next(f for f in cell['features'] if f['classAcronym'] == 'BOYLAT')
    assert next(a['value'] for a in buoy['attributes'] if a['code'] == 132) == 50000
