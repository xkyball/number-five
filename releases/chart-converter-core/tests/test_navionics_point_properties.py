"""Independent properties for the own, finite Point40 diagnostic presets.

All wire fields here are invented. The 150-byte label preset is the exact input
of the separately recorded original component run 6393a3e6; its acceptance does
not establish acceptance of the 246-byte metadata preset, arbitrary features,
geographic units, licensing, collection discovery or rendering. No OEM reader,
library, chart, process or device is used by these tests.
"""
import hashlib
import struct

import pytest

from no_chart_converter.navionics.point import (
    build_absolute_point_fixture, read_absolute_point_fixture,
)


TITLE = b'NO5 SYNTHETIC COMPLETE HEADERS ONLY'
BOUNDS = (100000, 100000, 200000, 200000)
POINT = (125000, 175000)
GOLDEN = {
    'label': (150, '6767cff834bf266d7b6414840e860ebe1872acc020c2dc43fcb14df5b4738c89'),
    'type16': (246, '4d0239e8c081724e64a3a6ca955d213a79cf9eaa0606bb0a4c7b2d22956ff9c2'),
}


def envelope(tag, payload):
    """Independent one-byte-length envelope for the own fixed small records."""
    return struct.pack('<HB', tag, len(payload)) + payload


def fixture_oracle(prefix):
    """Absolute-file-offset oracle; it calls no product encoder or reader."""
    if prefix == 'label':
        first = envelope(7, TITLE)
    else:
        # Forced 2-byte length is essential: marker is at FILE4, not FILE3.
        first = bytearray(134)
        struct.pack_into('<HH', first, 0, 0x8050, 130)
        struct.pack_into('<I', first, 4, 0x081273ab)
        struct.pack_into('<BHI', first, 8, 0, 9, 246)
        first[29:37] = b'20261017'
        first[37:45] = b'20261017'
        struct.pack_into('<4i', first, 45, *BOUNDS)
        first = bytes(first)
    header = len(first) + 7
    layer = header + 55
    point = layer + 22 + 7
    directory = envelope(0x8004, struct.pack('<I', header))
    map_header = envelope(0x8011, struct.pack('<H2x4i4x2I4x2I4xI',
                                            0x1234, *BOUNDS, 4, 4, 8, 20, layer))
    layer_header = envelope(0x8012, struct.pack('<BHIIii', 60, 0, point, 0, 0, 100000))
    layer_end = envelope(0, b'\xff' * 4)
    feature = envelope(0x8028, struct.pack('<BBBii', 126, 0, 0, *POINT))
    feature_end = envelope(0x8000, b'\xff' * 4)
    return first + directory + map_header + layer_header + layer_end + feature + feature_end


@pytest.mark.parametrize('prefix', ['label', 'type16'])
def test_complete_bytes_offsets_and_fixed_fields_have_independent_oracle(prefix):
    expected = fixture_oracle(prefix)
    size, sha = GOLDEN[prefix]
    assert (len(expected), hashlib.sha256(expected).hexdigest()) == (size, sha)
    assert build_absolute_point_fixture(prefix=prefix) == expected
    result = read_absolute_point_fixture(expected)
    start = 38 if prefix == 'label' else 134
    assert result == {
        'format': 'no5-own-absolute-point-fixture/1', 'prefix': prefix, 'bytes': size,
        'headerOffset': start + 7, 'layerOffset': start + 62, 'pointOffset': start + 91,
        'layerId': 60, 'classCode': 126, 'flags': 0, 'attributeBytes': 0,
        'rawPointXY': list(POINT), 'rawBounds': list(BOUNDS),
        'records': result['records'], 'declaredFileBytes': size if prefix == 'type16' else None,
    }
    offsets = [0, start, start + 7, start + 62, start + 84, start + 91, start + 105]
    tags = [7 if prefix == 'label' else 0x8050, 0x8004, 0x8011, 0x8012, 0, 0x8028, 0x8000]
    widths = [1 if prefix == 'label' else 2] + [1] * 6
    lengths = [35 if prefix == 'label' else 130, 4, 52, 19, 4, 11, 4]
    ends = offsets[1:] + [size]
    for row, offset, tag, width, length, end in zip(result['records'], offsets, tags, widths, lengths, ends):
        assert row == {'offset': offset, 'kind': tag & 63, 'tag': tag,
                       'flags': tag & 0xff00, 'lengthWidth': width,
                       'payloadStart': offset + 2 + width,
                       'payloadBytes': length, 'nextOffset': end}


def test_metadata_rebase_preserves_lower_wire_fields_and_rebinds_every_absolute_pointer():
    a, b = fixture_oracle('label'), fixture_oracle('type16')
    delta = 96
    assert b[:4] == bytes.fromhex('50808200')
    assert struct.unpack_from('<I', b, 4)[0] == 0x081273ab
    assert struct.unpack_from('<I', b, 11)[0] == len(b) == 246
    for old_pos in (41, 96, 106):  # Directory, Type17 layer cursor, Type18 point cursor.
        assert struct.unpack_from('<I', b, old_pos + delta)[0] == struct.unpack_from('<I', a, old_pos)[0] + delta
    normalized = bytearray(b[134:])
    for at in (3, 58, 68):
        struct.pack_into('<I', normalized, at, struct.unpack_from('<I', normalized, at)[0] - delta)
    assert normalized == a[38:]
    assert b[225:239] == a[129:143]  # Point payload is unchanged, not reprojected.
    assert b[239:] == a[143:]       # Active feature end stays selected.


@pytest.mark.parametrize('prefix', ['label', 'type16'])
def test_every_strict_prefix_and_trailing_bytes_are_rejected(prefix):
    raw = fixture_oracle(prefix)
    for length in range(len(raw)):
        with pytest.raises(ValueError):
            read_absolute_point_fixture(raw[:length])
    for tail in (b'\0', b'\xff', raw[-7:], raw):
        with pytest.raises(ValueError):
            read_absolute_point_fixture(raw + tail)


@pytest.mark.parametrize('prefix', ['label', 'type16'])
@pytest.mark.parametrize('mask', [1, 0x80, 0xff])
def test_every_byte_is_bound_by_the_strict_fixed_preset_reader(prefix, mask):
    # This is deliberately a fixed-preset validator, not a general NV2 decoder.
    raw = fixture_oracle(prefix)
    for offset in range(len(raw)):
        changed = bytearray(raw)
        changed[offset] ^= mask
        with pytest.raises(ValueError):
            read_absolute_point_fixture(bytes(changed))


@pytest.mark.parametrize('prefix', ['label', 'type16'])
@pytest.mark.parametrize('pointer_slot', ['directory', 'layer', 'point'])
def test_pointer_aliases_interiors_wrong_layout_and_oob_have_no_fallback(prefix, pointer_slot):
    raw = fixture_oracle(prefix)
    start = 38 if prefix == 'label' else 134
    at = {'directory': start + 3, 'layer': start + 58, 'point': start + 68}[pointer_slot]
    expected = struct.unpack_from('<I', raw, at)[0]
    for target in (0, 38, 45, 100, 129, start, expected - 1, expected + 1, len(raw), 0xffffffff):
        if target == expected:
            continue
        changed = bytearray(raw)
        struct.pack_into('<I', changed, at, target)
        with pytest.raises(ValueError):
            read_absolute_point_fixture(bytes(changed))


@pytest.mark.parametrize('prefix', ['label', 'type16'])
def test_layer_stop_and_feature_stop_have_distinct_required_selection_flags(prefix):
    raw = fixture_oracle(prefix)
    layer_end = 122 if prefix == 'label' else 218
    feature_end = 143 if prefix == 'label' else 239
    assert raw[layer_end:layer_end+3] == b'\0\0\x04'
    assert raw[feature_end:feature_end+3] == b'\0\x80\x04'
    for at, value in ((layer_end + 1, 0x80), (feature_end + 1, 0)):
        changed = bytearray(raw)
        changed[at] = value
        with pytest.raises(ValueError):
            read_absolute_point_fixture(bytes(changed))


@pytest.mark.parametrize('prefix', ['', 'unknown', 'LABEL', b'label', None, True, 1])
def test_unsupported_profile_never_selects_a_guessed_layout(prefix):
    with pytest.raises(ValueError):
        build_absolute_point_fixture(prefix=prefix)


def test_default_is_exact_label_preset_and_reader_claims_only_typed_wire_fields():
    raw = build_absolute_point_fixture()
    assert raw == fixture_oracle('label')
    result = read_absolute_point_fixture(raw)
    assert not any(key in result for key in ('nativeAccepted', 'rendererAccepted', 'geographicCRS', 'licenseAccepted'))
