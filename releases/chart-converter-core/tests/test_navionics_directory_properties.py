"""Independent wire properties for the explicitly synthetic header-only writer.

All field values here are invented. The two-header case reproduces the own
166-byte fixture whose finite original directory read is recorded as 9ceb1cf2;
it contains no geographic/OEM payload or native execution. These tests establish
wire properties, never native acceptance for the other invented raw values.
"""
from dataclasses import FrozenInstanceError, asdict
import hashlib
import random
import struct

import pytest

from no_chart_converter.navionics.writer import (
    RawMapHeader, build_header_directory, decode_record, directory_from_spec,
    encode_map_header, encode_record, read_header_directory,
    synthetic_two_header_directory,
)


FORMAT = 'no5-synthetic-nav-header-directory/1'
TITLE = b'NO5 SYNTHETIC COMPLETE HEADERS ONLY'
GOLDEN_SHA = 'dc5c48b1db5f4a01cdd8c266bc8cf293dbf161ae36a7f149f2ba0ca14d05ab89'


def envelope(kind, payload, width=1, flags=0, selector4=0x80):
    """Own byte oracle: LE16 tag, then selector-sized LE unsigned length."""
    selector = {1: 0, 2: 0x40, 4: selector4}[width]
    return (kind | selector | flags).to_bytes(2, 'little') + len(payload).to_bytes(width, 'little') + payload


def payload(header):
    # Explicit zero spans, not a call to the writer or its reader.
    return struct.pack('<H2x4i4x2I4x2I4xI', header.flags,
                       *header.raw_bounds, *header.raw_grid,
                       *header.raw_levels, header.trailing_value)


def directory_oracle(headers):
    start = 3 + len(TITLE) + 3 + 4 * len(headers)
    pointers = [start + 55 * i for i in range(len(headers))]
    return (envelope(7, TITLE)
            + envelope(4, b''.join(p.to_bytes(4, 'little') for p in pointers), flags=0x8000)
            + b''.join(envelope(17, payload(h), flags=0x8000) for h in headers)
            + envelope(0, b'\xff' * 4))


def original_own_headers():
    return [
        RawMapHeader(0x1234, (100000, 100000, 200000, 200000), (4, 4), (8, 20), 0x13579bdf),
        RawMapHeader(0x2345, (-200000, -200000, -100000, -100000), (8, 4), (8, 20), 0x2468ace0),
    ]


def test_own_166_byte_golden_has_independent_complete_wire_oracle():
    expected = directory_oracle(original_own_headers())
    assert len(expected) == 166
    assert hashlib.sha256(expected).hexdigest() == GOLDEN_SHA
    assert synthetic_two_header_directory() == expected
    assert build_header_directory(original_own_headers()) == expected
    result = read_header_directory(expected)
    assert result == {'format': FORMAT, 'headerOffsets': [49, 104],
                      'headers': [asdict(h) for h in original_own_headers()], 'bytes': 166}


def test_order_duplicate_occurrences_and_signed_raw_extremes_are_preserved():
    rng = random.Random(0x4E355137)
    special = RawMapHeader(0xffff, (-(1 << 31), (1 << 31)-1, 0, -1),
                           (0, 0xffffffff), (0xffffffff, 0), 0xffffffff)
    for count in (1, 2, 3, 7, 16):
        headers = [special]
        headers += [RawMapHeader(rng.randrange(1 << 16),
                                 tuple(rng.randrange(-(1 << 31), 1 << 31) for _ in range(4)),
                                 tuple(rng.randrange(1 << 32) for _ in range(2)),
                                 tuple(rng.randrange(1 << 32) for _ in range(2)),
                                 rng.randrange(1 << 32)) for _ in range(count - 1)]
        if count > 2:
            headers[-1] = headers[0]  # Equal payloads remain two separate occurrences.
        for ordered in (headers, list(reversed(headers))):
            raw = build_header_directory(ordered)
            assert raw == directory_oracle(ordered)
            view = read_header_directory(raw)
            assert view['headers'] == [asdict(h) for h in ordered]
            assert view['headerOffsets'] == [41 + 4*count + 55*i for i in range(count)]
            assert len(set(view['headerOffsets'])) == count
            assert len(raw) == 48 + 59*count


def test_raw_header_frozen_value_detaches_input_lists_and_zero_gaps():
    bounds, grid, levels = [1, -2, 3, 4], [5, 6], [7, 8]
    header = RawMapHeader(9, bounds, grid, levels, 10)
    bounds[0] = 99
    grid[0] = 99
    levels[0] = 99
    assert header.raw_bounds == (1, -2, 3, 4)
    assert header.raw_grid == (5, 6)
    assert header.raw_levels == (7, 8)
    with pytest.raises(FrozenInstanceError):
        header.flags = 99
    assert encode_map_header(header) == envelope(17, payload(header), flags=0x8000)
    assert len(encode_map_header(header)) == 55


@pytest.mark.parametrize('width,size,flags,selector', [
    (1, 0, 0, 0x80), (1, 255, 0xab00, 0xc0),
    (2, 256, 0x0100, 0x80), (2, 65535, 0xfe00, 0xc0),
    (4, 65536, 0x8000, 0x80), (4, 257, 0x1200, 0xc0),
])
def test_generic_envelope_preserves_unknown_kind_flags_width_and_offsets(width, size, flags, selector):
    raw_payload = bytes((i * 73 + 17) & 255 for i in range(size))
    raw = encode_record(63, raw_payload, width=width, flags=flags, long_selector=selector)
    assert raw == envelope(63, raw_payload, width, flags, selector)
    for prefix in (b'', b'own-prefix'):
        record = decode_record(prefix + raw + b'own-tail', len(prefix))
        assert record['kind'] == 63
        assert record['flags'] == flags
        assert record['lengthWidth'] == width
        assert record['payloadBytes'] == size
        assert record['payloadStart'] == len(prefix) + 2 + width
        assert record['nextOffset'] == len(prefix) + len(raw)
        assert (prefix + raw)[record['payloadStart']:record['nextOffset']] == raw_payload


def test_every_strict_prefix_of_canonical_directory_refuses_incomplete_success():
    raw = directory_oracle(original_own_headers())
    for length in range(len(raw)):
        with pytest.raises(ValueError):
            read_header_directory(raw[:length])


@pytest.mark.parametrize('pointer_slot,replacement', [
    (0, 0), (0, 38), (0, 50), (0, 104), (0, 166), (0, 0xffffffff),
    (1, 49), (1, 105), (1, 159),
])
def test_directory_never_accepts_interiors_aliases_swaps_or_out_of_file_pointers(pointer_slot, replacement):
    raw = bytearray(directory_oracle(original_own_headers()))
    struct.pack_into('<I', raw, 41 + 4*pointer_slot, replacement)
    with pytest.raises(ValueError):
        read_header_directory(bytes(raw))


@pytest.mark.parametrize('gap_byte', [2, 3, 20, 21, 22, 23, 32, 33, 34, 35, 44, 45, 46, 47])
def test_each_reserved_payload_byte_refuses_silent_nonzero_loss(gap_byte):
    raw = bytearray(directory_oracle(original_own_headers()))
    raw[49 + 3 + gap_byte] = 1
    with pytest.raises(ValueError):
        read_header_directory(bytes(raw))


@pytest.mark.parametrize('mutation', ['prefix', 'directory-kind', 'header-kind', 'stop-value', 'trailing', 'directory-length'])
def test_canonical_reader_refuses_unsupported_structure_instead_of_dropping_it(mutation):
    raw = bytearray(directory_oracle(original_own_headers()))
    if mutation == 'prefix':
        raw[3] ^= 1
    elif mutation == 'directory-kind':
        raw[38] = 5
    elif mutation == 'header-kind':
        raw[49] = 18
    elif mutation == 'stop-value':
        raw[-1] = 0
    elif mutation == 'trailing':
        raw += b'\0'
    else:
        raw[40] = 7
    with pytest.raises(ValueError):
        read_header_directory(bytes(raw))


@pytest.mark.parametrize('field,bad', [
    ('flags', True), ('flags', -1), ('flags', 1 << 16),
    ('raw_bounds', (0, 0, 0)), ('raw_bounds', (0, 0, 0, 1 << 31)),
    ('raw_bounds', (False, 0, 1, 1)), ('raw_bounds', (0.0, 0, 1, 1)),
    ('raw_grid', (1, -1)), ('raw_grid', (1, 1 << 32)),
    ('raw_levels', (True, 2)), ('raw_levels', (1,)),
    ('trailing_value', -1), ('trailing_value', 1 << 32),
])
def test_raw_fields_are_exact_machine_integers_without_coercion(field, bad):
    args = asdict(original_own_headers()[0])
    args[field] = bad
    with pytest.raises((TypeError, ValueError)):
        RawMapHeader(**args)


def test_explicit_spec_and_scope_have_no_unknown_field_or_count_fallback():
    headers = original_own_headers()
    spec = {'format': FORMAT, 'headers': [asdict(h) for h in headers]}
    assert directory_from_spec(spec) == directory_oracle(headers)
    for altered in ({**spec, 'crs': 'invented'}, {**spec, 'format': 'unknown'},
                    {**spec, 'headers': [{**asdict(headers[0]), 'geometry': []}]},
                    {**spec, 'headers': []}, {**spec, 'headers': [asdict(headers[0])] * 17}):
        with pytest.raises(ValueError):
            directory_from_spec(altered)
    for values in ([], headers * 9):
        with pytest.raises(ValueError):
            build_header_directory(values)


@pytest.mark.parametrize('call', [
    lambda: encode_record(True, b''), lambda: encode_record(64, b''),
    lambda: encode_record(1, bytearray()), lambda: encode_record(1, b'', flags=1),
    lambda: encode_record(1, b'x'*256, width=1),
    lambda: decode_record(b'\x00\x00\x00', True),
    lambda: decode_record(b'\x00\x00\x00', -1),
    lambda: decode_record(b'\x80\x00\x01\x02'),
])
def test_envelope_rejects_invalid_types_ranges_and_truncated_long_lengths(call):
    with pytest.raises((TypeError, ValueError)):
        call()
