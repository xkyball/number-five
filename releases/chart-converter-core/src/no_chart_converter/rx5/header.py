"""Own, single-segment plain-vector RX5 header contract.

Unsigned field widths/names come from the retained reader's stored-header
operator and file constructor. This module contains no original binary data.
The physical header is 240 bytes; the reader's internal 256-byte copy includes
16 zero padding bytes. A zero HeaderSize would read no bytes at all.
"""
from __future__ import annotations

import struct

HEADER_SIZE = 240
FILE_SEGMENT_SIZE = 1 << 30
MAGIC = b'\x01RayRX5\0'

# These are this writer's explicit plain, vector, single-file format choices.
# FileSegmentSize is a descriptor, not a claimed device/card capacity limit.
PLAIN_VECTOR_QWORDS = {
    8: 240,   # HeaderSize
    16: 16,   # BlockHeaderSize
    24: 32,   # QuadTreeHeaderSize
    32: 32,   # VectorHeaderSize
    40: FILE_SEGMENT_SIZE,
    48: 1,    # FileSegmentCount
    72: 8,    # Version
    80: 5,    # MinimumVersion
    88: 4,    # Flags: vector
    104: 256, # TileSize
}
PLAIN_ZERO_QWORDS = (96, 152, 176, 192)


def _need(value, reason):
    if not value:
        raise ValueError('RX5_PLAIN_HEADER_' + reason)


def _plain_identity(header: bytes):
    _need(type(header) is bytes and len(header) == HEADER_SIZE, 'EXACT_240_BYTES')
    _need(header[:8] == MAGIC, 'FORMAT_ID')
    _need(all(struct.unpack_from('<Q', header, at)[0] == 0
              for at in PLAIN_ZERO_QWORDS), 'UNSUPPORTED_AUTH_DRM_VENDOR_FIELDS')


def validate_plain_vector_header(header: bytes, *, file_bytes: int | None = None):
    """Validate this writer's layout without reading or decoding any body.

    A supplied extent must fit the single physical segment this writer emits.
    Multi-file segmentation requires a separate writer and is not inferred.
    """
    _plain_identity(header)
    for at, expected in PLAIN_VECTOR_QWORDS.items():
        actual = struct.unpack_from('<Q', header, at)[0]
        _need(actual == expected, f'UNSIGNED64_AT_{at}')
    if file_bytes is not None:
        _need(type(file_bytes) is int and HEADER_SIZE <= file_bytes <= FILE_SEGMENT_SIZE,
              'SINGLE_SEGMENT_EXTENT_REQUIRED')
    return {'format': 'no5-own-plain-vector-header/1', 'headerBytes': HEADER_SIZE,
            'fileSegmentBytes': FILE_SEGMENT_SIZE, 'fileSegments': 1,
            'version': 8, 'minimumVersion': 5, 'flags': 4, 'tileSize': 256,
            'uuidHex': header[56:72].hex()}


def new_plain_vector_header(identity: bytes, *, native_scamin_multiplier: int = 0) -> bytes:
    """Initialize every required unsigned layout field for a fresh own chart."""
    _need(type(identity) is bytes and len(identity) == 16, 'UUID_16_BYTES')
    _need(type(native_scamin_multiplier) is int
          and 0 <= native_scamin_multiplier <= 0xffffffff, 'SCAMIN_MULTIPLIER_U32')
    header = bytearray(HEADER_SIZE)
    header[:8] = MAGIC
    header[56:72] = identity
    for at, value in PLAIN_VECTOR_QWORDS.items():
        struct.pack_into('<Q', header, at, value)
    struct.pack_into('<I', header, 216, native_scamin_multiplier)
    result = bytes(header)
    validate_plain_vector_header(result)
    return result


def repair_own_plain_vector_header(header: bytes, *, file_bytes: int) -> bytes:
    """Correct only omitted format words in an own plain, single-file chart.

    Nonzero incompatible values are refused. UUID, source multiplier, block
    pointers and every byte outside the explicit qwords remain untouched.
    The caller binds the full source and verifies/publishes the body separately.
    """
    _plain_identity(header)
    result = bytearray(header)
    for at, expected in PLAIN_VECTOR_QWORDS.items():
        old = struct.unpack_from('<Q', header, at)[0]
        _need(old in (0, expected), f'REPAIR_CONFLICT_AT_{at}')
        struct.pack_into('<Q', result, at, expected)
    result = bytes(result)
    validate_plain_vector_header(result, file_bytes=file_bytes)
    return result
