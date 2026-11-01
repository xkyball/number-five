"""Own bounded byte recipes for source-qualified native attribute storage.

No runtime imports, native calls, file access, inferred source enum meanings,
numeric units, float rounding, registration or automatic product integration.
Values for M/J/H/K/O are selected raw integers, not source measurements.
"""
import struct


def _integer(value, low, high, label):
    if type(value) is not int or not low <= value <= high:
        raise ValueError(label)
    return value


def encode_native_value(storage, value, *, allowed_enums=()):
    """Return bytes for ONE attribute, using actual dictionary storage.

    E/L values require explicit finite semantic qualification by the caller.
    A/S take raw bytes. B/T take an ID assigned by plan_bmp_text_pool.
    Empty L is explicit presence, not missing. Genuine sentinels are refused.
    """
    if storage in ('E', 'L'):
        allowed = frozenset(allowed_enums)
        if any(type(v) is not int or not 1 <= v <= 253 for v in allowed):
            raise ValueError('finite native enum domain')
        values = (value,) if storage == 'E' else value
        if not isinstance(values, (tuple, list)):
            raise ValueError('ordered enum list')
        if storage == 'L' and len(values) > 253:
            raise ValueError('native U8 list length excluding status sentinels')
        if any(type(v) is not int or v not in allowed for v in values):
            raise ValueError('unqualified enum value')
        return bytes(values) if storage == 'E' else bytes((len(values),)) + bytes(values)
    if storage == 'M':
        _integer(value, -(1 << 31), (1 << 31) - 1, 'raw signed M word')
        if value in (0x7fffffff, 0x7ffffffe):
            raise ValueError('M status sentinel')
        return struct.pack('<i', value)
    if storage == 'J':
        return struct.pack('<H', _integer(value, 0, 65533, 'raw unsigned J word'))
    if storage in ('H', 'O'):
        raw = _integer(value, 0, 65535, 'raw unsigned H/O word')
        sentinels = (0x7fff, 0x7ffe) if storage == 'H' else (0xffff, 0xfffe)
        if raw in sentinels:
            raise ValueError('H/O status sentinel')
        # Genuine read_word_from_file assembles two U8 values without SXTH.
        # H's later signed conversion does not turn this into a signed I16.
        return struct.pack('<H', raw)
    if storage == 'K':
        raw = _integer(value, 0, 0xffffffff, 'raw unsigned K word')
        if raw in (0xffffffff, 0xfffffffe):
            raise ValueError('K status sentinel')
        return struct.pack('<I', raw)
    if storage in ('A', 'S'):
        if type(value) is not bytes or len(value) > 253:
            raise ValueError('raw byte string length excluding status sentinels')
        return bytes((len(value),)) + value
    if storage in ('B', 'T'):
        return struct.pack('<H', _integer(value, 9, 65535, 'own text resource ID'))
    raise ValueError('storage recipe not admitted')


def presence_payload(native_order, encoded_by_native_code):
    """Return (mask0,mask1,payload) for one complete class presence set.

    The original code-to-position helper chooses the first matching slot.
    Code/value semantic mapping is external. Presence absent is represented by
    not supplying the code, never by inventing a null/sentinel value.
    """
    if not isinstance(native_order, (tuple, list)) or not 0 <= len(native_order) <= 64:
        raise ValueError('native class attribute order')
    if any(type(v) is not int or not 1 <= v <= 264 for v in native_order):
        raise ValueError('native code in class order')
    if type(encoded_by_native_code) is not dict:
        raise ValueError('explicit attribute presence mapping')
    rows = []
    for code, data in encoded_by_native_code.items():
        if type(code) is not int or code not in native_order or type(data) is not bytes:
            raise ValueError('qualified class member and encoded bytes')
        rows.append((native_order.index(code), data))
    mask0 = mask1 = 0
    data = bytearray()
    for position, encoded in sorted(rows):
        if position < 32:
            mask0 |= 1 << (31 - position)
        else:
            mask1 |= 1 << (63 - position)
        data += encoded
    if len(data) > 65535:
        raise ValueError('native Feature attribute length is U16')
    return mask0, mask1, bytes(data)


def _record(kind, payload):
    n = len(payload)
    if n <= 255:
        tag, length = 0x8000 | kind, struct.pack('<B', n)
    elif n <= 65535:
        tag, length = 0x8040 | kind, struct.pack('<H', n)
    elif n <= 0xffffffff:
        tag, length = 0x8080 | kind, struct.pack('<I', n)
    else:
        raise ValueError('record extent')
    return struct.pack('<H', tag) + length + payload


def plan_bmp_text_pool(strings, absolute_offset, *, max_pool_bytes=1048576):
    """Return (kind5 bytes,kind48 bytes,IDs in original input order).

    English/UTF16LE is an explicit own language input. Exact strings are
    deduplicated in first-occurrence order; no normalization, case folding,
    truncation or missing-text substitution occurs. Native GetAttributeText's
    observed1023-byte UTF8 capacity motivates own UTF8<=1022 (not format limit).
    Caller rebases the whole public layout and retains the existing end record.
    The generated bytes contain the caller's text and are not public telemetry.
    """
    if not isinstance(strings, (tuple, list)) or not 1 <= len(strings) <= 65527:
        raise ValueError('nonempty bounded text sequence')
    _integer(absolute_offset, 0, 0xffffffff, 'absolute record offset')
    _integer(max_pool_bytes, 1, 67108864, 'explicit own pool budget')
    unique = {}
    encoded = []
    ids = []
    payload_bytes = 4
    for value in strings:
        if type(value) is not str or any(ord(c) == 0 or ord(c) > 0xffff or 0xd800 <= ord(c) <= 0xdfff for c in value):
            raise ValueError('non-NUL BMP scalar text')
        if len(value.encode('utf-8')) > 1022:
            raise ValueError('explicit own normal consumer text budget')
        if value not in unique:
            number = 9 + len(unique)
            _integer(number, 9, 65535, 'text ID count')
            raw = value.encode('utf-16le')
            if len(raw) > 65535:
                raise ValueError('text block U16 byte length')
            next_payload = payload_bytes + 4 + len(raw)
            frame_bytes = 3 if next_payload <= 255 else 4 if next_payload <= 65535 else 6
            if 10 + frame_bytes + next_payload > max_pool_bytes:
                raise ValueError('explicit own pool budget')
            unique[value] = number
            encoded.append(struct.pack('<HH', number, len(raw)) + raw)
            payload_bytes = next_payload
        ids.append(unique[value])
    payload = struct.pack('<HH', 9, 8 + len(unique)) + b''.join(encoded)
    texts = _record(48, payload)
    # Fixed one-language directory payload7; its ordinary envelope is3 bytes.
    text_offset = absolute_offset + 10
    _integer(text_offset + len(texts), 0, 0xffffffff, 'public file extent')
    directory = _record(5, b'EN\x01' + struct.pack('<I', text_offset))
    if len(directory) + len(texts) > max_pool_bytes:
        raise ValueError('explicit own pool budget')
    return directory, texts, tuple(ids)
