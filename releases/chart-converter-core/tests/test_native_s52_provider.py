"""Provider binding tests; real public engine is an explicit optional input."""
import hashlib
import json
import os
from pathlib import Path
import sys
import subprocess
import zipfile

import pytest

from cache_country_fixture import write_package
from no_chart_converter.cache_decoder import decode
from no_chart_converter.cache_country.native_provider import run_provider, _canonical


def inputs(tmp_path):
    package = write_package(tmp_path / 'source')
    manifest = decode(package.source, destination=tmp_path / 'ir', catalog_dir=package.catalogs)
    cells = []
    with zipfile.ZipFile(package.source) as archive:
        sources = {r['name']: r for r in json.loads(archive.read('no5-pi-map-cache.json'))['cells']}
        for descriptor in json.loads(manifest.read_bytes())['cells']:
            cell = json.loads((manifest.parent / descriptor['path']).read_bytes())
            raw = archive.read(sources[descriptor['id']]['base'])
            source = tmp_path / (descriptor['id'] + '.senc')
            source.write_bytes(raw)
            cells.append(dict(cellId=descriptor['id'], sourceCellPath=str(source),
                sourceCellSHA256=hashlib.sha256(raw).hexdigest(), owners=[dict(
                    sourceFeatureId=f['sourceFeatureId'],
                    featureSHA256=hashlib.sha256(_canonical(f)).hexdigest(), feature=f)
                    for f in cell['features']]))
    request = dict(format='no5-cache-country-s52-request/1', profileSHA256='a'*64,
                   settings={}, cells=cells)
    assets, cache = tmp_path / 'assets', tmp_path / 'cache'
    assets.mkdir(); cache.mkdir()
    for name in ('chartsymbols.xml', 's57objectclasses.csv', 's57attributes.csv', 'DejaVuSans.ttf'):
        (assets / name).write_bytes(b'INVENTED CONTRACT TEST RESOURCE')
    engine = tmp_path / 'engine'
    rows = {cell['cellId']: [dict(sourceFeatureId=r['sourceFeatureId'], classAcronym=r['feature']['classAcronym'],
                 status='resolved', type='programme', rcid=123, table=82, priority=3,
                 category=83, instruction='LS(SOLD,1,CHBLK);', originalInstruction='LS(SOLD,1,CHBLK);',
                 conditionals=[]) for r in cell['owners']] for cell in cells}
    # Invented engine checks only the wrapper contract; not S52 semantics.
    engine.write_text('#!' + sys.executable + '\nimport json,sys,pathlib\n'
        + 'rows=' + repr(rows) + '\n'
        + 'print(json.dumps(dict(format="no5-opencpn-s52-engine/1",context=sys.argv[4],owners=rows[pathlib.Path(sys.argv[1]).stem])))\n')
    engine.chmod(0o700)
    return request, assets, cache, engine


def invoke(tmp_path, data, assets, cache, engine):
    path = tmp_path / 'request.json'
    path.write_bytes(_canonical(data))
    return run_provider(path, tmp_path / 'response.json', engine=engine, assets=assets, cache=cache)


def test_fresh_typed_requests_both_contexts_and_no_clobber(tmp_path):
    data, assets, cache, engine = inputs(tmp_path)
    # Fresh FIDs and source geometry differ between both invented cells.
    result = invoke(tmp_path, data, assets, cache, engine)
    assert sum(len(c['owners']) for c in result['cells']) == 8
    assert set(result['cells'][0]['owners'][0]['programmes']) == {'196608', '196609'}
    assert result['resolver']['fullCellAssociations']
    original = (tmp_path / 'response.json').read_bytes()
    with pytest.raises(ValueError, match='OUTPUT_EXISTS'):
        invoke(tmp_path, data, assets, cache, engine)
    assert (tmp_path / 'response.json').read_bytes() == original


@pytest.mark.parametrize('change,code', [
    ('source', 'SOURCE_PIN'), ('feature', 'FEATURE_PIN'),
    ('attribute', 'ATTRIBUTES_DIFFER'), ('point', 'GEOMETRY_DIFFERS'),
    ('settings', 'UNSUPPORTED_SETTING'), ('duplicate', 'DUPLICATE_CELL'),
])
def test_mismatches_refuse_without_response(tmp_path, change, code):
    data, assets, cache, engine = inputs(tmp_path)
    if change == 'source':
        data['cells'][0]['sourceCellSHA256'] = '0'*64
    elif change == 'feature':
        data['cells'][0]['owners'][0]['featureSHA256'] = '0'*64
    elif change in ('attribute', 'point'):
        row = data['cells'][0]['owners'][0 if change == 'attribute' else 2]
        if change == 'attribute': row['feature']['attributes'][0]['value'] = 'ALTERED'
        else: row['feature']['geometry']['position'][0] += 1
        row['featureSHA256'] = hashlib.sha256(_canonical(row['feature'])).hexdigest()
    elif change == 'settings':
        data['settings']['unknownFutureSetting'] = True
    else:
        data['cells'][1]['cellId'] = data['cells'][0]['cellId']
    with pytest.raises(ValueError, match=code):
        invoke(tmp_path, data, assets, cache, engine)
    assert not (tmp_path / 'response.json').exists()


def test_actual_public_s52_engine(tmp_path):
    engine, assets = os.environ.get('NO5_S52_ENGINE'), os.environ.get('NO5_S52_ASSETS')
    if not engine or not assets:
        pytest.skip('Explicit separately built public S52 engine/assets not selected')
    data, _, cache, _ = inputs(tmp_path)
    result = invoke(tmp_path, data, Path(assets), cache, Path(engine))
    assert sum(len(c['owners']) for c in result['cells']) == 8
    conditionals = {name for c in result['cells'] for owner in c['owners']
                    for row in owner['selections'].values() for name in row['conditionals']}
    assert {'DEPARE01', 'QUAPOS01'} <= conditionals
    assert not result['resolver']['viewportFiltering']
    assert result['resolver']['originalOEMLibrariesUsed'] is False


def test_actual_public_native_rule_lexing():
    engine, assets = os.environ.get('NO5_S52_ENGINE'), os.environ.get('NO5_S52_ASSETS')
    if not engine or not assets:
        pytest.skip('Explicit separately built public S52 engine/assets not selected')
    run = subprocess.run([engine, '--self-test-programmes', assets], capture_output=True,
                         timeout=30, check=False)
    assert run.returncode == 0, run.stderr.decode(errors='replace')
    result = json.loads(run.stdout)
    assert result == {'format': 'no5-s52-native-rule-self-test/1', 'cases': 5, 'passed': True}


def test_unresolved_provider_diagnostic_has_cell_context_and_feature(tmp_path):
    data, assets, cache, engine = inputs(tmp_path)
    engine.write_text(engine.read_text().replace("'status': 'resolved'", "'status': 'unresolved'"))
    with pytest.raises(ValueError, match='S52_PROVIDER_UNRESOLVED_FEATURE:TEST-ALPHA-X9:paper:101:'):
        invoke(tmp_path, data, assets, cache, engine)
    assert not (tmp_path/'response.json').exists()


def test_engine_failure_preserves_both_diagnostic_channels(tmp_path):
    data, assets, cache, engine = inputs(tmp_path)
    engine.write_text('#!' + sys.executable + '\nimport sys\nprint("actual stdout reason")\n'
                      'print("actual stderr reason",file=sys.stderr)\nsys.exit(2)\n')
    with pytest.raises(ValueError, match='ENGINE_FAILED:TEST-ALPHA-X9:paper:') as caught:
        invoke(tmp_path, data, assets, cache, engine)
    assert 'actual stdout reason' in str(caught.value)
    assert 'actual stderr reason' in str(caught.value)
