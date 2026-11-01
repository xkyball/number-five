"""Own three-point wire values; no original reader or scale conversion."""
import hashlib
import json
import struct

import pytest

from no_chart_converter.cli import main
from no_chart_converter.navionics.line import build_three_point_line_fixture, read_three_point_line_fixture


def test_accepted_finite_line_identity_and_typed_fields():
    raw = build_three_point_line_fixture()
    assert len(raw) == 197
    assert hashlib.sha256(raw).hexdigest() == '2c87a6a11d703bc0a5b5f83aae010acfd04e4e21fe243c7ebf6e37e4208916fb'
    row = read_three_point_line_fixture(raw)
    assert (row['headerOffset'], row['bankOffset'], row['layerOffset'], row['featureOffset']) == (45, 100, 142, 171)
    assert (row['vectorStreamOffset'], row['vectorStreamBytes'], row['classCode'], row['partCount']) == (127, 15, 29, 1)
    assert row['rawPointSequence'] == [[0, 0], [1, 2], [3, 1]]
    assert row['lineBBoxU16'] == [0, 0, 3, 2]
    xs, ys = zip(*row['rawPointSequence'])
    assert row['lineBBoxU16'] == [min(xs), min(ys), max(xs), max(ys)]
    assert [r['offset'] for r in row['records']] == [0, 38, 45, 100, 142, 164, 171, 190]
    assert [r['tag'] for r in row['records']] == [7, 0x8004, 0x8011, 0x8034, 0x8012, 0, 0x8020, 0x8000]
    assert struct.unpack_from('<I', raw, 96)[0] == 142
    assert struct.unpack_from('<I', raw, 148)[0] == 171
    assert struct.unpack_from('<I', raw, 186)[0] == 127


def test_every_truncation_and_extra_bytes_refuse():
    raw = build_three_point_line_fixture()
    for n in range(len(raw)):
        with pytest.raises(ValueError):
            read_three_point_line_fixture(raw[:n])
    with pytest.raises(ValueError):
        read_three_point_line_fixture(raw + b'\0')


@pytest.mark.parametrize('offset', [0, 38, 41, 45, 50, 96, 100, 103, 127, 131, 136, 141,
                                    142, 148, 165, 174, 182, 183, 184, 185, 186, 189, 191, 193])
def test_fixed_record_fields_pointers_stream_and_terminators_refuse_mutants(offset):
    raw = bytearray(build_three_point_line_fixture())
    raw[offset] ^= 1
    with pytest.raises(ValueError):
        read_three_point_line_fixture(bytes(raw))


@pytest.mark.parametrize('pointer', [0, 100, 126, 128, 197, 0xffffffff, 127 | (1 << 30), 127 | (1 << 31)])
def test_only_exact_direct_stream_pointer(pointer):
    raw = bytearray(build_three_point_line_fixture())
    struct.pack_into('<I', raw, 186, pointer)
    with pytest.raises(ValueError):
        read_three_point_line_fixture(bytes(raw))


def test_cli_persisted_scope_and_no_clobber(tmp_path, capsys):
    output = tmp_path / 'own-line.bin'
    args = ['write-navionics-line-fixture', '--output', str(output)]
    assert main(args) == 0
    result = json.loads(capsys.readouterr().out)
    assert output.read_bytes() == build_three_point_line_fixture()
    assert result['output']['bytes'] == 197
    assert result['nativeReaderExecuted'] is result['renderingTested'] is False
    assert result['nativeAcceptance'] == 'NOT_TESTED_BY_THIS_COMMAND'
    assert result['wireReadback']['decodeMode'] == 'NO_SCALE_RAW_U16'
    assert result['worldScaleCRSPortrayal'] == 'UNPROVEN'
    assert main(args) == 2
    assert json.loads(capsys.readouterr().out)['reason'] == 'OUTPUT_EXISTS'
