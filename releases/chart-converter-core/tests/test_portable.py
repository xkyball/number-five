import hashlib
import json
from pathlib import Path
import struct
import subprocess
import sys

import pytest

from fixtures import chart
from no_chart_converter.rx5.model import validate
from no_chart_converter.rx5.optimize import optimize_masks
from no_chart_converter.rx5.pruning import apply_verified_prunes
from no_chart_converter.rx5.arena import compact_arena
from no_chart_converter.rx5.wire import FEATURE


def test_v22_merge_preserves_tree_and_complete_source_inverse():
    source = chart()
    output, report = optimize_masks(source)
    assert report['sourcePrefixInverse'] is True
    assert report['mergedGroups'] == 1
    assert report['actualChildPrunes'] == 0
    assert validate(output)['elements'] == 1
    assert validate(source)['elements'] == 2
    assert validate(source)['owners'] == validate(output)['owners'] == 1
    assert report['certificates'][0]['projectedIntegerLevels'][-1]['level'] == 21


def test_kind4_and9_keep_signed_depth_and_same_coordinate_alias():
    coordinates = struct.pack('<I', 0x01000001) + struct.pack('<IIi', 10, 20, -17)
    index = struct.pack('<II', 1, 0)
    features = b''.join(FEATURE.pack(i, 0, 0, 0, 0, 1, kind, 0, 0, 0) for i,kind in [(1,4),(2,9)])
    result = compact_arena(index, coordinates, features)
    assert result['coordinateBytes'] == coordinates
    assert result['indexBytes'] == index


def test_corrupt_wire_and_unapproved_prune_fail_closed():
    raw = chart()
    assert apply_verified_prunes(raw, []) == raw
    with pytest.raises(ValueError):
        apply_verified_prunes(raw, [object()])
    with pytest.raises(ValueError):
        validate(raw[:-1])


def test_portable_region_roundtrip(tmp_path):
    from no_chart_converter.rx5.region import configure
    source = tmp_path/'source.rx5'
    source.write_bytes(chart())
    service = configure(source, tmp_path/'store')
    indexed = service.prepare_index()
    assert indexed['actual']['records'] == 1
    result = service.export({'name':'SYNTHETIC REGION','bbox':[-1,-1,1,1],'minLevel':0,'maxLevel':21})
    output = Path(result['chart']['path']).read_bytes()
    manifest = json.loads(Path(result['manifest']['path']).read_bytes())
    assert manifest['automaticInverse']['status'] == 'PASS_EVERY_SELECTED_OWNER_AND_RESOURCE'
    assert manifest['allOriginalQueryAndProgrammeAndSCAMINAndXYZPreserved'] is True
    assert validate(output)['owners'] == 1
    assert source.read_bytes() == chart()
    with pytest.raises(ValueError, match='ALL_LEVELS'):
        service.export({'name':'BAD','bbox':[-1,-1,1,1],'minLevel':1,'maxLevel':21})


def test_installed_cli_outside_source_directory(tmp_path):
    source = tmp_path/'chart.rx5'
    source.write_bytes(chart())
    result = subprocess.run([sys.executable, '-m', 'no_chart_converter.cli', 'inspect', str(source)],
                            cwd=tmp_path, capture_output=True, text=True)
    assert result.returncode == 0, result.stdout + result.stderr
    assert json.loads(result.stdout)['sha256'] == hashlib.sha256(source.read_bytes()).hexdigest()
