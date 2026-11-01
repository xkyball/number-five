"""Actual portable V15 prepared-input build, geometry and downstream stages.

The factory invents one water polygon, one line and one sounding. No purchased
coordinates, OEM library, native oracle or historical data manifest is used.
"""
import json
from pathlib import Path
import struct
import subprocess
import sys

import pytest

from no_chart_converter.country_runtime import build_country
from no_chart_converter.country_runtime.synthetic import create_synthetic_config
from no_chart_converter.rx5.model import validate
from no_chart_converter.rx5.optimize import optimize_masks
from no_chart_converter.rx5.region import configure
from no_chart_converter.rx5.header import validate_plain_vector_header
from no_chart_converter.rx5.soundings import validate_sounding_pairs


def test_complete_synthetic_country_region_and_geometry(tmp_path):
    config=create_synthetic_config(tmp_path/'inputs')
    result=build_country(config)
    assert result['status']=='PASS_PERSISTED_NATIVE_GRAPH_METADATA_QUERY_UID_GEOMETRY'
    raw=Path(result['chart']['path']).read_bytes()
    validate_plain_vector_header(raw[:240], file_bytes=len(raw))
    wire=validate(raw)
    assert wire['owners']==4 and wire['ownersByKind']['9']==1
    receipt=json.loads(Path(result['runtimeInputReceipt']['path']).read_bytes())
    assert receipt['execution']['nativeCalls']==0
    assert receipt['execution']['cacheToPreparedManifestAdapterIncluded'] is False
    assert receipt['physicalPlotterAcceptanceClaim'] is False
    assert len(receipt['inputsActuallyRead'])>=15
    assert struct.pack('<i',789) in raw
    inventory=json.loads(Path(result['ownerInventory']['path']).read_bytes())
    sounding=next(r for r in inventory['rows']if r['kind']==4)
    assert inventory['nativeOccurrenceKindsRemainExplicit']
    assert sounding['nativeOccurrenceKinds']==[4,9]
    assert sounding['recordsByKind']=={'4':1,'9':1}
    # Persisted reports must describe the active fill quantization contract,
    # without inheriting the historical pixel-budget or identity claims.
    output=Path(result['runtimeInputReceipt']['path']).parent
    composition=json.loads((output/'raw/COMPOSITION.json').read_bytes())
    review=json.loads((output/'raw/ZOOM_REVIEW_INTERFACE.json').read_bytes())
    for report in (composition,review):
        assert report['ownQuantizationMaximumAuthoringReferencePixels'] is None
        assert report['maximumAppliesOnlyToPrimaryBoundaryDisposition'] is False
        policy=report['displayGeometryRoundtripPolicy']
        assert policy['kind3FillPolicy']=='own-root-u32-grid1-valid-output-fill/1'
        assert policy['serializedRolePolicy']=='own-root-u32-grid1-serialized-role-roundtrip/1'
        assert policy['gridSpacingRootUnits']==1 and policy['precisionMode']=='valid_output'
        assert not policy['wholePixelModelIdentityClaim'] and not policy['zeroAreaLeakClaim']
        assert policy['kind2LinePolicy']=='EXISTING_LINE_CONTINUATION_UNCHANGED_SEPARATE_SCOPE'
        assert policy['quantizedEmptyDisposition']=='EMPTY_DECLARED_ROOT_GRID_FILL_ORIGINAL_SOURCE_RETAINED'
        assert policy['quantizedEmptyRequiresSourceAndAreaLossReceipt'] is True
    # Real region index/export followed by the packaged V22 algorithm.
    service=configure(Path(result['chart']['path']),tmp_path/'regions')
    service.prepare_index()
    region=service.export({'name':'SYNTHETIC','bbox':[-1,-1,1,1],'minLevel':0,'maxLevel':21})
    selected=Path(region['chart']['path']).read_bytes()
    validate_plain_vector_header(selected[:240], file_bytes=len(selected))
    assert validate(selected)['owners']==4
    from no_chart_converter.rx5.model import native_tree
    from no_chart_converter.rx5.wire import blocks
    regional_blocks=blocks(selected)
    assert sum(validate_sounding_pairs(regional_blocks[at].payload)
               for at in native_tree(selected,regional_blocks)
               if regional_blocks[at].flags==3)==1
    optimized,report=optimize_masks(selected)
    assert validate(optimized)['owners']==4
    assert report['actualChildPrunes']==0
    assert struct.pack('<i',789) in optimized
    with pytest.raises(ValueError,match='FRESH_COUNTRY_OUTPUT'):
        build_country(config)


def test_country_prepare_and_input_pin_failure(tmp_path):
    config=create_synthetic_config(tmp_path/'inputs')
    prepared=build_country(config,prepare_only=True)
    receipt=json.loads(Path(prepared['runtimeInputReceipt']['path']).read_bytes())
    assert receipt['prepareOnly'] is True
    assert not list((config.parent/'output').rglob('*.rx5'))
    invalid=json.loads(config.read_bytes())
    invalid['baseDirectory']=str(config.parent)
    invalid['outputDirectory']='bad-output'
    invalid['inputs']['groundGeometry']['sha256']='0'*64
    with pytest.raises(ValueError,match='COUNTRY_INPUT_PIN_MISMATCH'):
        build_country(invalid)
    assert not (config.parent/'bad-output').exists()


def test_country_cli_outside_working_tree(tmp_path):
    config=create_synthetic_config(tmp_path/'inputs')
    result=subprocess.run([sys.executable,'-m','no_chart_converter.cli','country',
                           '--config',str(config)],cwd=tmp_path,
                          text=True,capture_output=True,timeout=20)
    assert result.returncode==0,result.stdout+result.stderr
    output=json.loads(result.stdout.strip().splitlines()[-1])
    assert output['status']=='PASS_PERSISTED_NATIVE_GRAPH_METADATA_QUERY_UID_GEOMETRY'
    assert Path(output['chart']['path']).is_file()
