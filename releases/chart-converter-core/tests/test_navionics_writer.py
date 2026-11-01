"""Own invented wire values; no OEM/native/runtime inputs."""
from dataclasses import asdict
import hashlib
import struct

import pytest

from no_chart_converter.navionics.writer import (
    FORMAT, RawMapHeader, build_header_directory, decode_record, directory_from_spec,
    encode_map_header, encode_record, read_header_directory, synthetic_two_header_directory,
)


def header():
    return RawMapHeader(65535, (-(1 << 31), -1, 0, (1 << 31) - 1),
                        (0, (1 << 32) - 1), (1, 2), 0xffffffff)


def test_accepted_own_fixture_identity():
    raw = synthetic_two_header_directory()
    assert len(raw) == 166
    assert hashlib.sha256(raw).hexdigest() == 'dc5c48b1db5f4a01cdd8c266bc8cf293dbf161ae36a7f149f2ba0ca14d05ab89'
    parsed = read_header_directory(raw)
    assert parsed['headerOffsets'] == [49, 104]
    assert [row['flags'] for row in parsed['headers']] == [0x1234, 0x2345]
    assert [decode_record(raw, at)['kind'] for at in (0, 38, 49, 104, 159)] == [7, 4, 17, 17, 0]


@pytest.mark.parametrize('count', [1, 2, 16])
def test_order_duplicates_and_signed_unsigned_extremes(count):
    expected = header()
    raw = build_header_directory([expected] * count)
    parsed = read_header_directory(raw)
    assert parsed['headers'] == [asdict(expected)] * count
    assert directory_from_spec({'format': FORMAT, 'headers': parsed['headers']}) == raw
    record = encode_map_header(expected)
    assert len(record) == 55
    assert struct.unpack_from('<i', record, 7)[0] == -(1 << 31)


@pytest.mark.parametrize('width,selector', [(1, 0x80), (2, 0x80), (4, 0x80), (4, 0xc0)])
def test_envelope_width_and_flags(width, selector):
    raw = encode_record(63, b'abc', width=width, flags=0xff00, long_selector=selector)
    row = decode_record(raw)
    assert (row['kind'], row['flags'], row['lengthWidth']) == (63, 0xff00, width)
    assert raw[row['payloadStart']:row['nextOffset']] == b'abc'
    assert row['payloadBytes'] == 3


@pytest.mark.parametrize('kwargs', [dict(kind=True), dict(kind=64), dict(flags=1),
    dict(flags=65536), dict(width=True), dict(width=3), dict(long_selector=0x40)])
def test_bad_envelope(kwargs):
    args = dict(kind=7, payload=b'ab')
    args.update(kwargs)
    with pytest.raises((ValueError, TypeError)):
        encode_record(**args)


@pytest.mark.parametrize('field,value', [('flags', True), ('flags', 65536),
    ('trailing_value', -1), ('raw_bounds', [0, 0, 0, 1 << 31]),
    ('raw_grid', [0, -1]), ('raw_levels', [False, 1]), ('raw_levels', [1])])
def test_bad_raw_fields(field, value):
    row = asdict(header())
    row[field] = value
    with pytest.raises(ValueError):
        RawMapHeader(**row)


def test_immutable_snapshot():
    bounds = [1, 2, 3, 4]
    value = RawMapHeader(0, bounds, [4, 4], [8, 20], 0)
    before = encode_map_header(value)
    bounds[0] = 100
    assert value.raw_bounds == (1, 2, 3, 4)
    assert encode_map_header(value) == before


def test_canonical_reader_rejects_every_truncation():
    raw = synthetic_two_header_directory()
    for end in range(len(raw)):
        with pytest.raises(ValueError):
            read_header_directory(raw[:end])


@pytest.mark.parametrize('at,value', [(0, 8), (39, 0), (41, 48), (49, 18),
    (50, 0), (54, 1), (159, 1), (162, 0)])
def test_canonical_reader_rejects_structural_mutations(at, value):
    raw = bytearray(synthetic_two_header_directory())
    raw[at] = value
    with pytest.raises(ValueError):
        read_header_directory(bytes(raw))


def test_scope_and_spec_rejections():
    for rows in ([], [header()] * 17):
        with pytest.raises(ValueError):
            build_header_directory(rows)
    spec = {'format': FORMAT, 'headers': [asdict(header())]}
    with pytest.raises(ValueError):
        directory_from_spec({**spec, 'geometry': []})
    with pytest.raises(ValueError):
        directory_from_spec({**spec, 'headers': [{**asdict(header()), 'latitude': 1}]})
    with pytest.raises(ValueError):
        read_header_directory(synthetic_two_header_directory() + b'\x00')
