"""Portable wire codec for regular DisplayControl storage D (attribute164).

This module only reads/writes explicit values. It does not admit a class,
activate an attribute, select chart owners, import a runtime or access files.
"""
from dataclasses import dataclass
import struct


def _integer(value, low, high, label):
    if type(value) is not int or not low <= value <= high:
        raise ValueError(label)
    return value


def _row_count(min_level, max_level):
    _integer(min_level, 0, 15, 'feature min_level must be an integer0..15')
    _integer(max_level, 0, 15, 'feature max_level must be an integer0..15')
    if min_level > max_level:
        raise ValueError('feature min_level must not exceed max_level')
    return max_level - min_level + 1


@dataclass(frozen=True)
class DisplayControlRow:
    """Raw row values; style_word retains all16 bits without interpretation.

    style_byte255 is reserved for the wire repeat marker and is not a value.
    dx/dy are signed byte offsets. Bool values are not integer inputs here.
    """

    style_byte: int
    style_word: int
    dx: int
    dy: int

    def __post_init__(self):
        _integer(self.style_byte, 0, 254, 'style_byte must be an integer0..254')
        _integer(self.style_word, 0, 65535, 'style_word must be an integer0..65535')
        _integer(self.dx, -128, 127, 'dx must be an integer-128..127')
        _integer(self.dy, -128, 127, 'dy must be an integer-128..127')


def encode_display_control(min_level, max_level, rows, *, repeat_previous=False):
    """Encode exactly one row per inclusive feature level, returning bytes.

    Both range inputs are exact integers0..15; bools are refused. rows is an
    explicit list/tuple of DisplayControlRow. No count or memory padding is
    emitted. The default emits every five-byte row. With repeat_previous=True,
    each row equal to its predecessor becomes a single0xff repeat marker.
    """
    count = _row_count(min_level, max_level)
    if type(repeat_previous) is not bool:
        raise ValueError('repeat_previous must be a bool')
    if not isinstance(rows, (list, tuple)) or len(rows) != count:
        raise ValueError('one explicit display row per feature level')
    if any(type(row) is not DisplayControlRow for row in rows):
        raise ValueError('rows must contain DisplayControlRow values')
    output = bytearray()
    previous = None
    for row in rows:
        if repeat_previous and row == previous:
            output.append(255)
        else:
            output.extend(struct.pack('<BHbb', row.style_byte, row.style_word, row.dx, row.dy))
        previous = row
    return bytes(output)


def decode_display_control(raw, min_level, max_level):
    """Decode an entire D payload into an immutable tuple of raw rows.

    Uses the encoder's identical range contract. raw must be bytes. Truncated
    rows, trailing bytes and a repeat marker before the first row are refused.
    Repeat markers expand values, without inventing an initial prior row.
    """
    count = _row_count(min_level, max_level)
    if type(raw) is not bytes:
        raise ValueError('display payload must be bytes')
    at = 0
    result = []
    for _ in range(count):
        if at >= len(raw):
            raise ValueError('truncated display row')
        style_byte = raw[at]
        at += 1
        if style_byte == 255:
            if not result:
                raise ValueError('repeat marker requires a preceding display row')
            result.append(result[-1])
            continue
        if len(raw) - at < 4:
            raise ValueError('truncated display row')
        style_word, dx, dy = struct.unpack_from('<Hbb', raw, at)
        at += 4
        result.append(DisplayControlRow(style_byte, style_word, dx, dy))
    if at != len(raw):
        raise ValueError('trailing display payload bytes')
    return tuple(result)
