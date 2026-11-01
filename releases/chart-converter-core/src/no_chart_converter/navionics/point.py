"""Own finite Point40 test preset, with raw coordinates and no style claims.

The label-prefix variant reproduces the peer-authored 150-byte input. Optional
Type16 metadata uses only own typed values and rebases every record pointer.
This module neither loads an original reader nor tests native acceptance.
"""
import struct

from .metadata import (METADATA_RECORD_BYTES, PLAIN_METADATA_RECORD_BYTES,
                       encode_metadata_record, encode_plain_metadata_record)
from .writer import PREFIX, RawMapHeader, decode_record, encode_map_header, encode_record


RAW_BOUNDS = (100000, 100000, 200000, 200000)
RAW_POINT_XY = (125000, 175000)


def build_absolute_point_fixture(*, prefix: str = 'label') -> bytes:
    """Build one fixed, attribute-free Point40; no arbitrary feature API.

    ``label`` is the lower-level diagnostic stream. ``type16`` replaces that
    label with minimum metadata; ``type16-plain`` uses extended metadata with an
    own zero descriptor at File144. Each declared length binds the complete file.
    Neither option promises collection discovery, rendering or geographic units.
    """
    if prefix not in ('label', 'type16', 'type16-plain'):
        raise ValueError('prefix must be label, type16 or type16-plain')
    extended = prefix == 'type16-plain'
    metadata_encoder = encode_plain_metadata_record if extended else encode_metadata_record
    first = (encode_record(7, PREFIX) if prefix == 'label' else
             metadata_encoder(declared_file_bytes=PLAIN_METADATA_RECORD_BYTES if extended else METADATA_RECORD_BYTES,
                              raw_bounds=RAW_BOUNDS))
    directory_size = len(encode_record(4, bytes(4), flags=0x8000))
    header_offset = len(first) + directory_size
    layer_offset = header_offset + len(encode_map_header(
        RawMapHeader(0x1234, RAW_BOUNDS, (4, 4), (8, 20), 0)))
    stop = encode_record(0, struct.pack('<I', 0xffffffff))
    layer_size = len(encode_record(18, bytes(19), flags=0x8000))
    point_offset = layer_offset + layer_size + len(stop)
    point = encode_record(40, bytes((126, 0, 0)) + struct.pack('<ii', *RAW_POINT_XY), flags=0x8000)
    # GetFeature skips unselected records, including an unselected kind0.
    # Its end record must therefore retain the active selected flag itself.
    feature_end = encode_record(0, struct.pack('<I', 0xffffffff), flags=0x8000)
    total = point_offset + len(point) + len(feature_end)
    if prefix != 'label':
        first = metadata_encoder(declared_file_bytes=total, raw_bounds=RAW_BOUNDS)
    directory = encode_record(4, struct.pack('<I', header_offset), flags=0x8000)
    header = encode_map_header(RawMapHeader(0x1234, RAW_BOUNDS, (4, 4), (8, 20), layer_offset))
    layer = encode_record(18, struct.pack('<BHIIii', 60, 0, point_offset, 0, 0, 100000), flags=0x8000)
    result = first + directory + header + layer + stop + point + feature_end
    if len(result) != total:
        raise ValueError('point fixture assembly extent mismatch')
    return result


def read_absolute_point_fixture(raw: bytes) -> dict:
    """Validate exactly this preset's layouts and return typed wire values.

    This is not an arbitrary Navionics feature decoder. Values outside the
    fixed own preset are refused rather than assigned unproved semantics.
    """
    records = []
    def record(at, tag, width, size):
        row = decode_record(raw, at)
        if (row['tag'], row['lengthWidth'], row['payloadBytes']) != (tag, width, size):
            raise ValueError('unsupported fixed point record')
        records.append({'offset': at, **row})
        return row

    first = decode_record(raw)
    if first['kind'] == 7:
        first = record(0, 7, 1, len(PREFIX))
        if raw[first['payloadStart']:first['nextOffset']] != PREFIX:
            raise ValueError('unsupported point label')
        prefix = 'label'
    elif first['kind'] == 16:
        if first['payloadBytes'] == 130:
            prefix, size, encoder = 'type16', 130, encode_metadata_record
        elif first['payloadBytes'] == 254:
            prefix, size, encoder = 'type16-plain', 254, encode_plain_metadata_record
        else:
            raise ValueError('unsupported own metadata payload size')
        first = record(0, 0x8050, 2, size)
        if raw[:first['nextOffset']] != encoder(declared_file_bytes=len(raw), raw_bounds=RAW_BOUNDS):
            raise ValueError('unsupported own metadata or declared file size')
    else:
        raise ValueError('unsupported point prefix')
    directory = record(first['nextOffset'], 0x8004, 1, 4)
    header_offset = struct.unpack_from('<I', raw, directory['payloadStart'])[0]
    if header_offset != directory['nextOffset']:
        raise ValueError('point directory pointer mismatch')
    header = record(header_offset, 0x8011, 1, 52)
    layer_offset = header['nextOffset']
    expected_header = encode_map_header(RawMapHeader(0x1234, RAW_BOUNDS, (4, 4), (8, 20), layer_offset))
    if raw[header_offset:layer_offset] != expected_header:
        raise ValueError('fixed point header fields or layer pointer mismatch')
    layer = record(layer_offset, 0x8012, 1, 19)
    layer_id, layer_word, point_offset, raw_word, range_a, range_b = struct.unpack_from('<BHIIii', raw, layer['payloadStart'])
    layer_end = record(layer['nextOffset'], 0, 1, 4)
    if raw[layer_end['payloadStart']:layer_end['nextOffset']] != b'\xff' * 4:
        raise ValueError('point layer stop payload mismatch')
    if point_offset != layer_end['nextOffset'] or (layer_id, layer_word, raw_word, range_a, range_b) != (60, 0, 0, 0, 100000):
        raise ValueError('fixed layer fields or feature pointer mismatch')
    point = record(point_offset, 0x8028, 1, 11)
    class_code, flags, attribute_bytes, x, y = struct.unpack_from('<BBBii', raw, point['payloadStart'])
    if (class_code, flags, attribute_bytes, x, y) != (126, 0, 0, *RAW_POINT_XY):
        raise ValueError('unsupported fixed point fields')
    end = record(point['nextOffset'], 0x8000, 1, 4)
    if raw[end['payloadStart']:end['nextOffset']] != b'\xff' * 4 or end['nextOffset'] != len(raw):
        raise ValueError('expected active feature end and exact end of stream')
    result = {'format': 'no5-own-absolute-point-fixture/1', 'prefix': prefix, 'bytes': len(raw),
            'headerOffset': header_offset, 'layerOffset': layer_offset, 'pointOffset': point_offset,
            'layerId': layer_id, 'classCode': class_code, 'flags': flags, 'attributeBytes': attribute_bytes,
            'rawPointXY': [x, y], 'rawBounds': list(RAW_BOUNDS), 'records': records,
            'declaredFileBytes': len(raw) if prefix != 'label' else None}
    if prefix == 'type16-plain':
        result['plainDescriptorWord'] = struct.unpack_from('<H', raw, 144)[0]
        result['metadataPayloadBytes'] = 254
    return result
