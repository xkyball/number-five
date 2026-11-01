"""Own finite Line32 fixture accepted through the original NoScale reader.

Only the fixed three-point raw-U16 case is supported. The retained own Bank52
carrier does not establish a scale, geographic coordinate system or portrayal.
No native code is loaded or executed by this builder or its strict wire reader.
"""
import struct

from .writer import PREFIX, RawMapHeader, decode_record, encode_map_header, encode_record


RAW_LINE_POINTS = ((0, 0), (1, 2), (3, 1))
RAW_BOUNDS = (100000, 100000, 200000, 200000)


def build_three_point_line_fixture() -> bytes:
    """Reproduce the own 197-byte, one-part, attribute-free Line32 preset."""
    stream = (struct.pack('<HH', *RAW_LINE_POINTS[0]) +
              b''.join(b'\x88' + struct.pack('<HH', *p) for p in RAW_LINE_POINTS[1:]) + b'\0')
    prefix = encode_record(7, PREFIX)
    header_offset = len(prefix) + len(encode_record(4, bytes(4), flags=0x8000))
    header_size = len(encode_map_header(RawMapHeader(0x1234, RAW_BOUNDS, (4, 4), (8, 20), 0)))
    bank_offset = header_offset + header_size
    bank_prolog = struct.pack('<IIiiii', 4, 4, *RAW_BOUNDS)
    stream_offset = bank_offset + 3 + len(bank_prolog)
    bank = encode_record(52, bank_prolog + stream, flags=0x8000)
    layer_offset = bank_offset + len(bank)
    stop = encode_record(0, struct.pack('<I', 0xffffffff))
    layer_size = len(encode_record(18, bytes(19), flags=0x8000))
    feature_offset = layer_offset + layer_size + len(stop)
    directory = encode_record(4, struct.pack('<I', header_offset), flags=0x8000)
    header = encode_map_header(RawMapHeader(0x1234, RAW_BOUNDS, (4, 4), (8, 20), layer_offset))
    layer = encode_record(18, struct.pack('<BHIIii', 60, 0, feature_offset, 0, 0, 100000), flags=0x8000)
    line = encode_record(32, struct.pack('<4HBBB', 0, 0, 3, 2, 29, 0, 0) +
                         struct.pack('<BI', 1, stream_offset), flags=0x8000)
    feature_end = encode_record(0, struct.pack('<I', 0xffffffff), flags=0x8000)
    return prefix + directory + header + bank + layer + stop + line + feature_end


def read_three_point_line_fixture(raw: bytes) -> dict:
    """Check exactly this own preset; decode only its raw NoScale U16 stream."""
    records = []
    def record(at, tag, size):
        row = decode_record(raw, at)
        if (row['tag'], row['lengthWidth'], row['payloadBytes']) != (tag, 1, size):
            raise ValueError('unsupported fixed line record')
        records.append({'offset': at, **row})
        return row

    prefix = record(0, 7, len(PREFIX))
    if raw[prefix['payloadStart']:prefix['nextOffset']] != PREFIX:
        raise ValueError('unsupported line label')
    directory = record(prefix['nextOffset'], 0x8004, 4)
    header_offset = struct.unpack_from('<I', raw, directory['payloadStart'])[0]
    if header_offset != directory['nextOffset']:
        raise ValueError('line directory pointer mismatch')
    header = record(header_offset, 0x8011, 52)
    bank_offset = header['nextOffset']
    bank = record(bank_offset, 0x8034, 39)
    layer_offset = bank['nextOffset']
    expected_header = encode_map_header(RawMapHeader(0x1234, RAW_BOUNDS, (4, 4), (8, 20), layer_offset))
    if raw[header_offset:bank_offset] != expected_header:
        raise ValueError('fixed line header or layer pointer mismatch')
    if struct.unpack_from('<IIiiii', raw, bank['payloadStart']) != (4, 4, *RAW_BOUNDS):
        raise ValueError('unsupported fixed own bank carrier')
    stream_offset = bank['payloadStart'] + 24
    layer = record(layer_offset, 0x8012, 19)
    layer_id, layer_word, feature_offset, word, range_a, range_b = struct.unpack_from('<BHIIii', raw, layer['payloadStart'])
    layer_end = record(layer['nextOffset'], 0, 4)
    if raw[layer_end['payloadStart']:layer_end['nextOffset']] != b'\xff' * 4:
        raise ValueError('line layer end payload mismatch')
    if (layer_id, layer_word, word, range_a, range_b) != (60, 0, 0, 0, 100000) or feature_offset != layer_end['nextOffset']:
        raise ValueError('fixed line layer or feature pointer mismatch')
    feature = record(feature_offset, 0x8020, 16)
    x0, y0, x1, y1, class_code, flags, attributes = struct.unpack_from('<4HBBB', raw, feature['payloadStart'])
    part_count, pointer = struct.unpack_from('<BI', raw, feature['payloadStart'] + 11)
    if (class_code, flags, attributes, part_count) != (29, 0, 0, 1) or pointer & 0xc0000000 or pointer != stream_offset:
        raise ValueError('unsupported line fields or direct stream pointer')
    end = record(feature['nextOffset'], 0x8000, 4)
    if raw[end['payloadStart']:end['nextOffset']] != b'\xff' * 4 or end['nextOffset'] != len(raw):
        raise ValueError('expected active line feature end and exact end of stream')
    at = pointer
    points = [list(struct.unpack_from('<HH', raw, at))]
    at += 4
    while at < bank['nextOffset']:
        command = raw[at]
        at += 1
        if command == 0:
            if at != bank['nextOffset']:
                raise ValueError('unsupported trailing NoScale stream bytes')
            break
        if command != 0x88 or at + 4 > bank['nextOffset']:
            raise ValueError('unsupported fixed NoScale command')
        points.append(list(struct.unpack_from('<HH', raw, at)))
        at += 4
    else:
        raise ValueError('missing NoScale stream terminator')
    if points != [list(p) for p in RAW_LINE_POINTS] or (x0, y0, x1, y1) != (0, 0, 3, 2):
        raise ValueError('unsupported fixed raw line points or bounds')
    return {'format': 'no5-own-three-point-line-fixture/1', 'bytes': len(raw),
            'headerOffset': header_offset, 'bankOffset': bank_offset, 'layerOffset': layer_offset,
            'featureOffset': feature_offset, 'vectorStreamOffset': stream_offset,
            'vectorStreamBytes': bank['nextOffset'] - stream_offset, 'rawPointSequence': points,
            'lineBBoxU16': [x0, y0, x1, y1], 'partCount': part_count,
            'classCode': class_code, 'flags': flags, 'attributeBytes': attributes,
            'decodeMode': 'NO_SCALE_RAW_U16', 'records': records}
