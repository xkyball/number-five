"""Own extended metadata wire values, never original loader execution."""
import hashlib
import json
import struct

import pytest

from no_chart_converter.cli import main
from no_chart_converter.navionics.metadata import encode_metadata_record, encode_plain_metadata_record
from no_chart_converter.navionics.point import build_absolute_point_fixture, read_absolute_point_fixture


def test_extended_metadata_field_and_reserve_oracle():
    bounds = (-100, -200, 300, 400)
    raw = encode_plain_metadata_record(declared_file_bytes=370, raw_bounds=bounds)
    assert len(raw) == 258
    assert struct.unpack_from('<HHI', raw) == (0x8050, 254, 0x081273ab)
    assert struct.unpack_from('<I', raw, 11)[0] == 370
    assert struct.unpack_from('<4i', raw, 45) == bounds
    assert struct.unpack_from('<H', raw, 144)[0] == 0
    assert raw[134:] == bytes(124)
    minimal = encode_metadata_record(declared_file_bytes=370, raw_bounds=bounds)
    assert raw[4:134] == minimal[4:]


@pytest.mark.parametrize('declared', [0, 134, 257, True, 1 << 32])
def test_extended_metadata_requires_its_complete_extent(declared):
    with pytest.raises(ValueError):
        encode_plain_metadata_record(declared_file_bytes=declared, raw_bounds=(1, 2, 3, 4))


def test_old_accepted_presets_byte_exact():
    for prefix, expected in [('label', '6767cff834bf266d7b6414840e860ebe1872acc020c2dc43fcb14df5b4738c89'),
                             ('type16', '4d0239e8c081724e64a3a6ca955d213a79cf9eaa0606bb0a4c7b2d22956ff9c2')]:
        assert hashlib.sha256(build_absolute_point_fixture(prefix=prefix)).hexdigest() == expected


def test_extended_point_rebase_and_raw_readback():
    raw = build_absolute_point_fixture(prefix='type16-plain')
    row = read_absolute_point_fixture(raw)
    assert len(raw) == row['declaredFileBytes'] == 370
    assert (row['headerOffset'], row['layerOffset'], row['pointOffset']) == (265, 320, 349)
    assert [r['offset'] for r in row['records']] == [0, 258, 265, 320, 342, 349, 363]
    assert row['plainDescriptorWord'] == 0 and row['metadataPayloadBytes'] == 254
    assert struct.unpack_from('<I', raw, 261)[0] == 265
    assert struct.unpack_from('<I', raw, 316)[0] == 320
    assert struct.unpack_from('<I', raw, 326)[0] == 349
    assert struct.unpack_from('<H', raw, 363)[0] == 0x8000
    assert row['rawPointXY'] == [125000, 175000]


@pytest.mark.parametrize('offset', [2, 11, 134, 143, 144, 145, 146, 257, 261, 316, 326, 364])
def test_extended_preset_refuses_descriptor_reserve_size_pointer_mutants(offset):
    raw = bytearray(build_absolute_point_fixture(prefix='type16-plain'))
    raw[offset] ^= 1
    with pytest.raises(ValueError):
        read_absolute_point_fixture(bytes(raw))


def test_cli_extended_is_fresh_file_and_no_native_admission(tmp_path, capsys):
    output = tmp_path / 'own-plain-point.bin'
    args = ['write-navionics-point-fixture', '--prefix', 'type16-plain', '--output', str(output)]
    assert main(args) == 0
    result = json.loads(capsys.readouterr().out)
    assert result['output']['bytes'] == 370
    assert result['wireReadback']['plainDescriptorWord'] == 0
    assert result['nativeReaderExecuted'] is result['renderingTested'] is False
    assert result['nativeAcceptance'] == 'NOT_TESTED_BY_THIS_COMMAND'
    assert output.read_bytes() == build_absolute_point_fixture(prefix='type16-plain')
    assert main(args) == 2
    assert json.loads(capsys.readouterr().out)['reason'] == 'OUTPUT_EXISTS'
