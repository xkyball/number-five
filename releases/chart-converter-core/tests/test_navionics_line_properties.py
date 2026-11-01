"""Independent own-wire properties for the finite197-byte Line32 preset.

All bytes are invented. The separately saved original NoScale readback1dfe67af
accepts exactly this preset; these tests neither execute it nor turn that result
into a world-coordinate, scale, style, bank-prolog or arbitrary-geometry claim.
"""
import hashlib
import struct

import pytest

from no_chart_converter.navionics.line import (
    build_three_point_line_fixture, read_three_point_line_fixture,
)
from no_chart_converter.navionics.point import (
    build_absolute_point_fixture, read_absolute_point_fixture,
)


def envelope(tag, payload):
    return struct.pack('<HB', tag, len(payload)) + payload


def fixture_oracle():
    # Absolute offsets are independently fixed below, never borrowed from the
    # production builder. The retained carrier has no asserted native scale.
    stream = bytes.fromhex('000000008801000200880300010000')
    return (envelope(7, b'NO5 SYNTHETIC COMPLETE HEADERS ONLY')
            + envelope(0x8004, struct.pack('<I', 45))
            + envelope(0x8011, struct.pack('<H2x4i4x2I4x2I4xI',
                0x1234, 100000, 100000, 200000, 200000, 4, 4, 8, 20, 142))
            + envelope(0x8034, struct.pack('<2I4i', 4, 4, 100000, 100000, 200000, 200000) + stream)
            + envelope(0x8012, struct.pack('<BHIIii', 60, 0, 171, 0, 0, 100000))
            + envelope(0, b'\xff' * 4)
            + envelope(0x8020, struct.pack('<4HBBBBI', 0, 0, 3, 2, 29, 0, 0, 1, 127))
            + envelope(0x8000, b'\xff' * 4))


def test_complete197_byte_golden_and_exact_schema_have_independent_oracle():
    expected = fixture_oracle()
    assert len(expected) == 197
    assert hashlib.sha256(expected).hexdigest() == '2c87a6a11d703bc0a5b5f83aae010acfd04e4e21fe243c7ebf6e37e4208916fb'
    assert build_three_point_line_fixture() == expected
    result = read_three_point_line_fixture(expected)
    assert result == {'format': 'no5-own-three-point-line-fixture/1', 'bytes': 197,
        'headerOffset': 45, 'bankOffset': 100, 'layerOffset': 142, 'featureOffset': 171,
        'vectorStreamOffset': 127, 'vectorStreamBytes': 15,
        'rawPointSequence': [[0, 0], [1, 2], [3, 1]], 'lineBBoxU16': [0, 0, 3, 2],
        'partCount': 1, 'classCode': 29, 'flags': 0, 'attributeBytes': 0,
        'decodeMode': 'NO_SCALE_RAW_U16', 'records': result['records']}
    offsets = [0, 38, 45, 100, 142, 164, 171, 190]
    tags = [7, 0x8004, 0x8011, 0x8034, 0x8012, 0, 0x8020, 0x8000]
    lengths = [35, 4, 52, 39, 19, 4, 16, 4]
    ends = offsets[1:] + [197]
    for row, offset, tag, length, end in zip(result['records'], offsets, tags, lengths, ends):
        assert row == {'offset': offset, 'tag': tag, 'kind': tag & 63, 'flags': tag & 0xff00,
            'lengthWidth': 1, 'payloadStart': offset + 3, 'payloadBytes': length, 'nextOffset': end}
    assert not any(k in result for k in ('nativeAccepted', 'worldXY', 'scale', 'style', 'rendererAccepted', 'bankPrologConsumed'))


def test_all_absolute_refs_and_three_raw_pairs_have_independent_byte_offsets():
    raw = fixture_oracle()
    for field, target in [(41, 45), (96, 142), (148, 171), (186, 127)]:
        assert struct.unpack_from('<I', raw, field)[0] == target
    assert raw[100:103] == bytes.fromhex('348027')
    assert raw[127:142] == bytes.fromhex('000000008801000200880300010000')
    assert [struct.unpack_from('<2H', raw, p) for p in (127, 132, 137)] == [(0, 0), (1, 2), (3, 1)]
    assert raw[131] == raw[136] == 0x88 and raw[141] == 0
    assert raw[164:171] == bytes.fromhex('000004ffffffff')
    assert raw[190:197] == bytes.fromhex('008004ffffffff')


def test_every_strict_prefix_and_extra_tail_refuses_partial_or_merged_success():
    raw = fixture_oracle()
    for size in range(len(raw)):
        with pytest.raises(ValueError):
            read_three_point_line_fixture(raw[:size])
    for tail in (b'\0', raw[-7:], raw):
        with pytest.raises(ValueError):
            read_three_point_line_fixture(raw + tail)


@pytest.mark.parametrize('mask', [1, 0x80, 0xff])
def test_every_byte_of_the_fixed_preset_is_bound(mask):
    raw = fixture_oracle()
    for offset in range(len(raw)):
        changed = bytearray(raw)
        changed[offset] ^= mask
        with pytest.raises(ValueError):
            read_three_point_line_fixture(bytes(changed))


@pytest.mark.parametrize('field', [41, 96, 148, 186])
def test_pointer_interiors_aliases_swaps_eof_and_oob_have_no_fallback(field):
    raw = fixture_oracle()
    expected = struct.unpack_from('<I', raw, field)[0]
    for target in (0, 38, 45, 100, 127, 142, 171, 190, expected - 1, expected + 1, 197, 0xffffffff):
        if target == expected:
            continue
        changed = bytearray(raw)
        struct.pack_into('<I', changed, field, target)
        with pytest.raises(ValueError):
            read_three_point_line_fixture(bytes(changed))


@pytest.mark.parametrize('mode', [0x40000000, 0x80000000, 0xc0000000])
def test_pointer_high_mode_bits_are_not_silently_masked(mode):
    raw = bytearray(fixture_oracle())
    struct.pack_into('<I', raw, 186, 127 | mode)
    with pytest.raises(ValueError):
        read_three_point_line_fixture(bytes(raw))


@pytest.mark.parametrize('at,new', [(131, 0), (136, 0), (131, 0x81), (136, 0x84), (141, 0x88)])
def test_command_or_terminator_changes_never_drop_or_invent_vertices(at, new):
    raw = bytearray(fixture_oracle())
    raw[at] = new
    with pytest.raises(ValueError):
        read_three_point_line_fixture(bytes(raw))


@pytest.mark.parametrize('at,new', [(182, 0), (182, 1), (182, 255), (183, 1), (184, 1), (185, 0), (185, 2)])
def test_unknown_class_flags_attributes_and_part_counts_are_refused(at, new):
    raw = bytearray(fixture_oracle())
    raw[at] = new
    with pytest.raises(ValueError):
        read_three_point_line_fixture(bytes(raw))


@pytest.mark.parametrize('prefix', ['label', 'type16', 'type16-plain'])
def test_fixed_point_and_line_readers_do_not_silently_cross_accept_profiles(prefix):
    with pytest.raises(ValueError):
        read_three_point_line_fixture(build_absolute_point_fixture(prefix=prefix))
    with pytest.raises(ValueError):
        read_absolute_point_fixture(fixture_oracle())
