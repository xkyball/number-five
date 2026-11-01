"""Pure tests for the own finite point preset, no original runtime."""
import hashlib
import json
import struct

import pytest

from no_chart_converter.cli import main
from no_chart_converter.navionics.point import build_absolute_point_fixture, read_absolute_point_fixture


@pytest.mark.parametrize('prefix,size,offsets', [('label', 150, (45, 100, 129)),
                                              ('type16', 246, (141, 196, 225))])
def test_fixed_point_complete_layout(prefix, size, offsets):
    raw = build_absolute_point_fixture(prefix=prefix)
    row = read_absolute_point_fixture(raw)
    assert len(raw) == size
    assert (row['headerOffset'], row['layerOffset'], row['pointOffset']) == offsets
    assert row['rawPointXY'] == [125000, 175000]
    assert (row['classCode'], row['flags'], row['attributeBytes'], row['layerId']) == (126, 0, 0, 60)
    assert [x['kind'] for x in row['records']] == [7 if prefix == 'label' else 16, 4, 17, 18, 0, 40, 0]
    assert row['records'][-1]['tag'] == 0x8000
    assert row['records'][-3]['tag'] == 0
    assert struct.unpack_from('<I', raw, offsets[0] + 3 + 48)[0] == offsets[1]
    assert struct.unpack_from('<I', raw, offsets[1] + 3 + 3)[0] == offsets[2]
    if prefix == 'type16':
        assert raw[:8] == bytes.fromhex('50808200ab731208')
        assert struct.unpack_from('<I', raw, 11)[0] == size
        assert row['declaredFileBytes'] == size
    else:
        assert hashlib.sha256(raw).hexdigest() == '6767cff834bf266d7b6414840e860ebe1872acc020c2dc43fcb14df5b4738c89'
        assert row['declaredFileBytes'] is None


@pytest.mark.parametrize('prefix', ['label', 'type16'])
def test_every_truncation_and_suffix_rejected(prefix):
    raw = build_absolute_point_fixture(prefix=prefix)
    for n in range(len(raw)):
        with pytest.raises(ValueError):
            read_absolute_point_fixture(raw[:n])
    with pytest.raises(ValueError):
        read_absolute_point_fixture(raw + b'\0')


@pytest.mark.parametrize('offset', [0, 1, 2, 41, 54, 96, 97, 98, 106, 110, 125, 129, 130, 131, 132, 133, 134, 135, 139, 144, 146])
def test_label_structural_and_value_mutants_rejected(offset):
    raw = bytearray(build_absolute_point_fixture())
    raw[offset] ^= 1
    with pytest.raises(ValueError):
        read_absolute_point_fixture(bytes(raw))


def test_unselected_feature_end_previous_failed_candidate_rejected():
    raw = bytearray(build_absolute_point_fixture())
    raw[144] = 0
    assert hashlib.sha256(raw).hexdigest() == '139d88f8f5dba906370e503f6b76b3f11c8be166ca6a648a7d6dcddc04cc0998'
    with pytest.raises(ValueError):
        read_absolute_point_fixture(bytes(raw))


@pytest.mark.parametrize('offset', [2, 4, 8, 9, 11, 15, 29, 37, 45, 61, 133])
def test_type16_wrong_size_magic_dates_reserved_rejected(offset):
    raw = bytearray(build_absolute_point_fixture(prefix='type16'))
    raw[offset] ^= 1
    with pytest.raises(ValueError):
        read_absolute_point_fixture(bytes(raw))


def test_prefix_must_be_explicit_supported_choice():
    for value in ('NV2', '', None, True, []):
        with pytest.raises(ValueError):
            build_absolute_point_fixture(prefix=value)


@pytest.mark.parametrize('prefix', ['label', 'type16'])
def test_cli_persisted_finite_scope_and_no_clobber(tmp_path, capsys, prefix):
    output = tmp_path / 'point.bin'
    args = ['write-navionics-point-fixture', '--prefix', prefix, '--output', str(output)]
    assert main(args) == 0
    value = json.loads(capsys.readouterr().out)
    assert value['supportedGeometry'] == 'ONE_FIXED_POINT40_NO_ATTRIBUTES'
    assert value['nativeReaderExecuted'] is value['renderingTested'] is False
    assert value['nativeAcceptance'] == 'NOT_TESTED_BY_THIS_COMMAND'
    assert output.read_bytes() == build_absolute_point_fixture(prefix=prefix)
    assert main(args) == 2
    assert json.loads(capsys.readouterr().out)['reason'] == 'OUTPUT_EXISTS'
    assert output.read_bytes() == build_absolute_point_fixture(prefix=prefix)
