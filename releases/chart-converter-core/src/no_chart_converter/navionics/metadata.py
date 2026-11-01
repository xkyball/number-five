"""Independently authored, finite Type16 metadata encoder, not a chart compiler.

This profile writes only the clear typed fields consumed by the observed
ChartPresence/ChartInfo/xf2 metadata paths. Other fields are own zero values,
not recovered vendor defaults, permissions, or a full format specification.
No original chart data, runtime, entitlement, key or identity is embedded.
"""
from datetime import date
import struct


METADATA_PAYLOAD_BYTES = 130
METADATA_RECORD_BYTES = 134
FORMAT_MARKER = 0x081273AB
PLAIN_METADATA_PAYLOAD_BYTES = 254
PLAIN_METADATA_RECORD_BYTES = 258
PLAIN_DESCRIPTOR_FILE_OFFSET = 144


def _uint(value, bits, label):
    if type(value) is not int or not 0 <= value < 1 << bits:
        raise ValueError(label)
    return value


def _date_text(value, label):
    if not isinstance(value, str) or len(value) != 8 or not value.isascii() or not value.isdigit():
        raise ValueError(label)
    try:
        date(int(value[:4]), int(value[4:6]), int(value[6:8]))
    except ValueError as error:
        raise ValueError(label) from error
    return value.encode('ascii')


def encode_metadata_record(*, declared_file_bytes, raw_bounds,
                           date_text_1='20261017', date_text_2='20261017',
                           raw_byte_8=0, raw_word_9=9):
    """Return a minimum-size Type16 record with a forced four-byte prefix.

    `declared_file_bytes` is the complete eventual own file size, including
    this134-byte record. The producer computes it from the final assembly.
    Bounds remain raw signed words; this function asserts no geographic CRS.
    The two dates are own calendar values. Their full downstream semantics,
    and the meanings of raw_byte_8/raw_word_9, are deliberately not invented.
    """
    total = _uint(declared_file_bytes, 32, 'declared_file_bytes')
    if total < METADATA_RECORD_BYTES:
        raise ValueError('declared_file_bytes_below_metadata_record')
    flag = _uint(raw_byte_8, 8, 'raw_byte_8')
    version_word = _uint(raw_word_9, 16, 'raw_word_9')
    if not isinstance(raw_bounds, (tuple, list)) or len(raw_bounds) != 4:
        raise ValueError('raw_bounds')
    if any(type(x) is not int or not -(1 << 31) <= x < 1 << 31 for x in raw_bounds):
        raise ValueError('raw_bounds_signed32')
    first = _date_text(date_text_1, 'date_text_1')
    second = _date_text(date_text_2, 'date_text_2')
    payload = bytearray(METADATA_PAYLOAD_BYTES)
    struct.pack_into('<I', payload, 0, FORMAT_MARKER)
    struct.pack_into('<BHI', payload, 4, flag, version_word, total)
    payload[25:33] = first   # File29..36 -> native OutInfo34..41; NUL added by reader.
    payload[33:41] = second  # File37..44 -> native OutInfo43..50; NUL added by reader.
    struct.pack_into('<4i', payload, 41, *raw_bounds)  # File45..60.
    # The forced two-byte length field is essential: ChartPresence reads the
    # format word at fixed FILE4, while xf2 reads metadata at fixed FILE8.
    # An automatic one-byte length would incorrectly shift every fixed field.
    result = struct.pack('<HH', 0x8050, METADATA_PAYLOAD_BYTES) + payload
    assert len(result) == METADATA_RECORD_BYTES
    return result


def encode_plain_metadata_record(*, declared_file_bytes, raw_bounds,
                                 date_text_1='20261017', date_text_2='20261017',
                                 raw_byte_8=0, raw_word_9=9):
    """Write extended own metadata with an explicit plain descriptor at File144.

    The fixed-offset GetEncryptInfo reader consumes that unsigned 16-bit word;
    zero declares the three decoded components [0, 0, 0]. All added reserve
    bytes are own zeros. This declaration does not set a native object's mode,
    provide keys, or promise admission through the separate LoadXF2 guard.
    """
    base = encode_metadata_record(declared_file_bytes=declared_file_bytes, raw_bounds=raw_bounds,
        date_text_1=date_text_1, date_text_2=date_text_2,
        raw_byte_8=raw_byte_8, raw_word_9=raw_word_9)
    if declared_file_bytes < PLAIN_METADATA_RECORD_BYTES:
        raise ValueError('declared_file_bytes_below_extended_metadata_record')
    payload = bytearray(base[4:])
    payload.extend(bytes(PLAIN_METADATA_PAYLOAD_BYTES - len(payload)))
    struct.pack_into('<H', payload, PLAIN_DESCRIPTOR_FILE_OFFSET - 4, 0)
    return struct.pack('<HH', 0x8050, PLAIN_METADATA_PAYLOAD_BYTES) + payload
