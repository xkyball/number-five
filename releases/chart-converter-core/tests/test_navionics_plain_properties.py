"""Independent own-byte checks for the finite extended-metadata point preset.

Every value is invented. These pure tests do not execute a native reader or
establish LoadXF2 mode/admission, entitlement, geography, style or rendering.
An all-zero reserve cannot reveal the instruction offset of a zero store:
bytes prove the complete layout and rejection of mutations, while the explicit
File144 store remains separately reviewable source evidence.
"""
import hashlib
import struct

import pytest

from no_chart_converter.navionics.metadata import encode_plain_metadata_record
from no_chart_converter.navionics.point import (
    build_absolute_point_fixture, read_absolute_point_fixture,
)


def metadata_oracle(total=370, bounds=(100000, 100000, 200000, 200000),
                    date1=b'20261017', date2=b'20261017', byte8=0, word9=9):
    raw = bytearray(258)
    raw[0:4] = bytes.fromhex('5080fe00')
    struct.pack_into('<I', raw, 4, 0x081273ab)
    struct.pack_into('<BHI', raw, 8, byte8, word9, total)
    raw[29:37], raw[37:45] = date1, date2
    struct.pack_into('<4i', raw, 45, *bounds)
    # The whole reserve, including the two bytes at File144, is own zero data.
    return bytes(raw)


def record(tag, payload):
    return struct.pack('<HB', tag, len(payload)) + payload


def point_oracle():
    return (metadata_oracle()
            + record(0x8004, struct.pack('<I', 265))
            + record(0x8011, struct.pack('<H2x4i4x2I4x2I4xI',
                0x1234, 100000, 100000, 200000, 200000, 4, 4, 8, 20, 320))
            + record(0x8012, struct.pack('<BHIIii', 60, 0, 349, 0, 0, 100000))
            + record(0, b'\xff' * 4)
            + record(0x8028, struct.pack('<BBBii', 126, 0, 0, 125000, 175000))
            + record(0x8000, b'\xff' * 4))


def test_complete_plain370_has_independent_absolute_offset_and_schema_oracle():
    expected = point_oracle()
    assert len(expected) == 370
    assert build_absolute_point_fixture(prefix='type16-plain') == expected
    view = read_absolute_point_fixture(expected)
    assert view == {'format': 'no5-own-absolute-point-fixture/1', 'prefix': 'type16-plain',
        'bytes': 370, 'headerOffset': 265, 'layerOffset': 320, 'pointOffset': 349,
        'layerId': 60, 'classCode': 126, 'flags': 0, 'attributeBytes': 0,
        'rawPointXY': [125000, 175000], 'rawBounds': [100000, 100000, 200000, 200000],
        'declaredFileBytes': 370, 'plainDescriptorWord': 0, 'metadataPayloadBytes': 254,
        'records': view['records']}
    assert [r['offset'] for r in view['records']] == [0, 258, 265, 320, 342, 349, 363]
    assert [r['nextOffset'] for r in view['records']] == [258, 265, 320, 342, 349, 363, 370]
    assert [r['tag'] for r in view['records']] == [0x8050, 0x8004, 0x8011, 0x8012, 0, 0x8028, 0x8000]
    assert [r['lengthWidth'] for r in view['records']] == [2, 1, 1, 1, 1, 1, 1]
    assert expected[:4] == b'\x50\x80\xfe\0'
    assert struct.unpack_from('<H', expected, 144)[0] == 0
    assert expected[342:349] == bytes.fromhex('000004ffffffff')
    assert expected[363:370] == bytes.fromhex('008004ffffffff')
    assert not any(k in view for k in ('nativeAccepted', 'loadXF2Accepted', 'licenseAccepted', 'rendererAccepted'))


def test_original_label150_and_minimum246_bytes_and_schemas_are_unchanged():
    for prefix, size, sha in [
        ('label', 150, '6767cff834bf266d7b6414840e860ebe1872acc020c2dc43fcb14df5b4738c89'),
        ('type16', 246, '4d0239e8c081724e64a3a6ca955d213a79cf9eaa0606bb0a4c7b2d22956ff9c2'),
    ]:
        raw = build_absolute_point_fixture(prefix=prefix)
        assert len(raw) == size and hashlib.sha256(raw).hexdigest() == sha
        view = read_absolute_point_fixture(raw)
        assert 'plainDescriptorWord' not in view and 'metadataPayloadBytes' not in view
        assert view['prefix'] == prefix
    # Full lower representation is unchanged except three absolute pointers.
    old = build_absolute_point_fixture(prefix='type16')
    normalized = bytearray(point_oracle()[258:])
    for at in (3, 58, 68):
        struct.pack_into('<I', normalized, at, struct.unpack_from('<I', normalized, at)[0] - 124)
    assert normalized == old[134:]


@pytest.mark.parametrize('total,bounds,date1,date2,byte8,word9', [
    (258, (0, 1, -1, 2), b'00010101', b'99991231', 0, 0),
    (370, (-(1 << 31), (1 << 31)-1, 0, -1), b'20000229', b'20240229', 255, 65535),
    (0xffffffff, (1, 2, 3, 4), b'20261017', b'20261017', 17, 273),
])
def test_extended_metadata_preserves_exact_machine_fields_and_zero_reserve(total, bounds, date1, date2, byte8, word9):
    raw = encode_plain_metadata_record(declared_file_bytes=total, raw_bounds=bounds,
        date_text_1=date1.decode('ascii'), date_text_2=date2.decode('ascii'),
        raw_byte_8=byte8, raw_word_9=word9)
    assert raw == metadata_oracle(total, bounds, date1, date2, byte8, word9)
    assert raw[61:] == bytes(197)
    assert raw[144:146] == b'\0\0'


def test_every_strict_prefix_and_every_extra_tail_refuses_incomplete_success():
    raw = point_oracle()
    for size in range(len(raw)):
        with pytest.raises(ValueError):
            read_absolute_point_fixture(raw[:size])
    for tail in (b'\0', raw[-7:], raw):
        with pytest.raises(ValueError):
            read_absolute_point_fixture(raw + tail)


@pytest.mark.parametrize('mask', [1, 0x80, 0xff])
def test_every_plain370_byte_is_bound_by_the_fixed_preset_reader(mask):
    raw = point_oracle()
    for at in range(len(raw)):
        altered = bytearray(raw)
        altered[at] ^= mask
        with pytest.raises(ValueError):
            read_absolute_point_fixture(bytes(altered))


@pytest.mark.parametrize('at', [142, 143, 144, 145, 146, 147])
def test_nonzero_descriptor_or_shifted_nonzero_reserve_is_not_silently_accepted(at):
    # Rejection of nonzero bytes proves only the exact fixed reserve policy;
    # it does not independently observe an invisible zero-store instruction.
    raw = bytearray(point_oracle())
    raw[at] = 1
    with pytest.raises(ValueError):
        read_absolute_point_fixture(bytes(raw))


@pytest.mark.parametrize('at,old_target', [(261, 141), (316, 196), (326, 225)])
def test_all_old_layout_pointer_values_are_refused_after_124byte_extension(at, old_target):
    raw = bytearray(point_oracle())
    struct.pack_into('<I', raw, at, old_target)
    with pytest.raises(ValueError):
        read_absolute_point_fixture(bytes(raw))


@pytest.mark.parametrize('bad', [-1, 0, 134, 257, 1 << 32, True, 258.0])
def test_extended_declared_size_requires_exact_u32_and_at_least258(bad):
    with pytest.raises(ValueError):
        encode_plain_metadata_record(declared_file_bytes=bad, raw_bounds=(1, 2, 3, 4))


@pytest.mark.parametrize('mutation', ['short-length', 'automatic-width', 'omitted-extension', 'stale-file-size'])
def test_old_header_size_or_shifted_payload_cannot_masquerade_as_extended_plain(mutation):
    raw = bytearray(point_oracle())
    if mutation == 'short-length':
        struct.pack_into('<H', raw, 2, 130)
    elif mutation == 'automatic-width':
        raw[0:4] = bytes.fromhex('1080fe')  # Incorrect3-byte prefix shifts File4.
    elif mutation == 'omitted-extension':
        del raw[134:258]
    else:
        struct.pack_into('<I', raw, 11, 246)
    with pytest.raises(ValueError):
        read_absolute_point_fixture(bytes(raw))
