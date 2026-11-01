"""Reproduce the own, original-reader-tested cleartext header directory.

This is a deliberately bounded writer, not a geographic chart compiler. Raw
header storage has no asserted CRS, physical units, zoom or style semantics.
No OEM payloads, native libraries, protected records or executable callbacks
are included or loaded.
"""
from dataclasses import asdict, dataclass
from collections.abc import Sequence
import struct


FORMAT = 'no5-synthetic-nav-header-directory/1'
MAX_HEADERS = 16  # Tested tool scope, not an inferred file-format limit.
PREFIX = b'NO5 SYNTHETIC COMPLETE HEADERS ONLY'


def _uint(value, bits, name):
    if type(value) is not int or not 0 <= value < 1 << bits:
        raise ValueError(f'{name} must be an unsigned {bits}-bit integer')
    return value


def encode_record(kind: int, payload: bytes, *, width: int | None = None,
                  flags: int = 0, long_selector: int = 0x80) -> bytes:
    """Encode the confirmed six-bit kind/high-byte flags/LE-length envelope."""
    _uint(kind, 6, 'kind')
    if type(payload) is not bytes:
        raise TypeError('payload must be bytes')
    _uint(flags, 16, 'flags')
    if flags & 0xff:
        raise ValueError('flags must be confined to the high tag byte')
    if width is None:
        width = 1 if len(payload) <= 0xff else 2 if len(payload) <= 0xffff else 4
    if type(width) is not int or width not in (1, 2, 4) or len(payload) >= 1 << (width * 8):
        raise ValueError('payload does not fit requested length width')
    if type(long_selector) is not int or long_selector not in (0x80, 0xc0):
        raise ValueError('invalid four-byte selector')
    selector = {1: 0, 2: 0x40, 4: long_selector}[width]
    return struct.pack('<H', flags | selector | kind) + len(payload).to_bytes(width, 'little') + payload


def decode_record(data: bytes, offset: int = 0) -> dict:
    """Read only an envelope, retaining raw flags and exact stream offsets."""
    if type(data) is not bytes:
        raise TypeError('data must be bytes')
    if type(offset) is not int or not 0 <= offset <= len(data) - 3:
        raise ValueError('truncated tag/length or invalid offset')
    tag = struct.unpack_from('<H', data, offset)[0]
    width = {0: 1, 0x40: 2, 0x80: 4, 0xc0: 4}[tag & 0xc0]
    start = offset + 2 + width
    if start > len(data):
        raise ValueError('truncated length')
    length = int.from_bytes(data[offset + 2:start], 'little')
    end = start + length
    if end > len(data):
        raise ValueError('truncated payload')
    return {'kind': tag & 0x3f, 'tag': tag, 'flags': tag & 0xff00,
            'lengthWidth': width, 'payloadStart': start,
            'payloadBytes': length, 'nextOffset': end}


@dataclass(frozen=True)
class RawMapHeader:
    flags: int
    raw_bounds: tuple[int, int, int, int]
    raw_grid: tuple[int, int]
    raw_levels: tuple[int, int]
    trailing_value: int

    def __post_init__(self):
        _uint(self.flags, 16, 'header flags')
        _uint(self.trailing_value, 32, 'trailing_value')
        for name, count, signed in (('raw_bounds', 4, True), ('raw_grid', 2, False),
                                    ('raw_levels', 2, False)):
            values = getattr(self, name)
            if not isinstance(values, (tuple, list)) or len(values) != count:
                raise ValueError(f'{name} requires {count} integers')
            for value in values:
                if signed:
                    if type(value) is not int or not -(1 << 31) <= value < 1 << 31:
                        raise ValueError('raw_bounds must contain signed 32-bit integers')
                else:
                    _uint(value, 32, name)
            object.__setattr__(self, name, tuple(values))


def encode_map_header(header: RawMapHeader) -> bytes:
    """Write the complete 52-byte Type17 payload, with all gaps zero."""
    if type(header) is not RawMapHeader:
        raise TypeError('header must be RawMapHeader')
    payload = bytearray(52)
    struct.pack_into('<H', payload, 0, header.flags)
    struct.pack_into('<4i', payload, 4, *header.raw_bounds)
    struct.pack_into('<2I', payload, 24, *header.raw_grid)
    struct.pack_into('<2I', payload, 36, *header.raw_levels)
    struct.pack_into('<I', payload, 48, header.trailing_value)
    return encode_record(17, bytes(payload), flags=0x8000)


def build_header_directory(headers: Sequence[RawMapHeader]) -> bytes:
    """Build canonical own records with absolute byte-position directory refs.

    One to sixteen headers is the supported synthetic tool scope. Duplicate
    headers are retained in order; they are not interpreted as chart identities.
    """
    if not isinstance(headers, (tuple, list)) or not 1 <= len(headers) <= MAX_HEADERS:
        raise ValueError('synthetic header-directory scope is 1..16 headers')
    encoded = [encode_map_header(header) for header in headers]
    prefix = encode_record(7, PREFIX)
    pointer = len(prefix) + len(encode_record(4, bytes(4 * len(encoded)), flags=0x8000))
    pointers = []
    for header in encoded:
        pointers.append(pointer)
        pointer += len(header)
    directory = encode_record(4, struct.pack('<' + 'I' * len(pointers), *pointers), flags=0x8000)
    return prefix + directory + b''.join(encoded) + encode_record(0, struct.pack('<I', 0xffffffff))


def synthetic_two_header_directory() -> bytes:
    """Reproduce the own 166-byte input accepted by the original directory API."""
    return build_header_directory([
        RawMapHeader(0x1234, (100000, 100000, 200000, 200000), (4, 4), (8, 20), 0x13579bdf),
        RawMapHeader(0x2345, (-200000, -200000, -100000, -100000), (8, 4), (8, 20), 0x2468ace0),
    ])


def directory_from_spec(spec: dict) -> bytes:
    """Build from an explicit raw-field spec; unknown fields are refused."""
    if type(spec) is not dict or set(spec) != {'format', 'headers'} or spec['format'] != FORMAT:
        raise ValueError('expected explicit synthetic header-directory spec')
    rows = spec['headers']
    if type(rows) is not list or not 1 <= len(rows) <= MAX_HEADERS:
        raise ValueError('synthetic header-directory scope is 1..16 headers')
    keys = set(RawMapHeader.__dataclass_fields__)
    if any(type(row) is not dict or set(row) != keys for row in rows):
        raise ValueError('each header must contain exactly the six raw fields')
    return build_header_directory([RawMapHeader(**row) for row in rows])


def read_header_directory(raw: bytes) -> dict:
    """Validate/read our canonical header-only layout, not arbitrary NV2 files."""
    prefix = decode_record(raw)
    if prefix['tag'] != 7 or prefix['lengthWidth'] != 1 or raw[prefix['payloadStart']:prefix['nextOffset']] != PREFIX:
        raise ValueError('unsupported synthetic prefix')
    directory = decode_record(raw, prefix['nextOffset'])
    count, remainder = divmod(directory['payloadBytes'], 4)
    if directory['tag'] != 0x8004 or directory['lengthWidth'] != 1 or remainder or not 1 <= count <= MAX_HEADERS:
        raise ValueError('unsupported canonical directory')
    pointers = list(struct.unpack_from('<' + 'I' * count, raw, directory['payloadStart']))
    cursor = directory['nextOffset']
    headers = []
    for pointer in pointers:
        if pointer != cursor:
            raise ValueError('directory pointer must reference the next complete header')
        record = decode_record(raw, pointer)
        if record['tag'] != 0x8011 or record['lengthWidth'] != 1 or record['payloadBytes'] != 52:
            raise ValueError('expected canonical Type17 header')
        at = record['payloadStart']
        header = RawMapHeader(struct.unpack_from('<H', raw, at)[0],
            struct.unpack_from('<4i', raw, at + 4), struct.unpack_from('<2I', raw, at + 24),
            struct.unpack_from('<2I', raw, at + 36), struct.unpack_from('<I', raw, at + 48)[0])
        if raw[pointer:record['nextOffset']] != encode_map_header(header):
            raise ValueError('unsupported nonzero header gaps')
        headers.append(asdict(header))
        cursor = record['nextOffset']
    stop = decode_record(raw, cursor)
    if stop['tag'] != 0 or stop['lengthWidth'] != 1 or raw[stop['payloadStart']:stop['nextOffset']] != b'\xff' * 4 or stop['nextOffset'] != len(raw):
        raise ValueError('expected final canonical stop record and end of stream')
    return {'format': FORMAT, 'headerOffsets': pointers, 'headers': headers, 'bytes': len(raw)}
