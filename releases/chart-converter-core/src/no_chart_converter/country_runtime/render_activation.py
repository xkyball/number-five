"""Rewrite own RX5 render frames using separately resolved native commands.

This module performs no lookup, colour choice, conditional S52 expansion,
instruction-prefix removal or native execution. A resolver must supply the
canonical command bytes for each genuine feature and drawing context.
"""

from dataclasses import dataclass

from hashlib import sha256

from struct import Struct

from types import MappingProxyType

FEATURE = Struct('<Q5I4B')

HEADER = Struct('<HI')

ENTRY = Struct('<IH')

DRAW_KEYS = frozenset((0x30000, 0x30001))

def require(condition, reason):
    if not condition:
        raise ValueError(reason)

@dataclass(frozen=True)
class Frame:
    additional_offset: int
    entries: object

def decode_frames(render, valid_additional_offsets):
    """Require complete sequential frames and independently known query starts."""
    require(isinstance(render, bytes), 'IMMUTABLE_RENDER_BYTES_REQUIRED')
    starts = frozenset(valid_additional_offsets)
    frames = {}
    pos = 0
    while pos < len(render):
        begin = pos
        require(pos + HEADER.size <= len(render), 'TRUNCATED_RENDER_HEADER')
        count, additional = HEADER.unpack_from(render, pos)
        pos += HEADER.size
        require(additional in starts, 'REFERENCE_EXACT_ADDITIONAL_FRAME_START')
        entries = {}
        for _ in range(count):
            require(pos + ENTRY.size <= len(render), 'TRUNCATED_RENDER_ENTRY')
            key, length = ENTRY.unpack_from(render, pos)
            pos += ENTRY.size
            require(key not in entries, 'DUPLICATE_RENDER_KEY')
            require(pos + length <= len(render), 'TRUNCATED_RENDER_PAYLOAD')
            entries[key] = render[pos:pos + length]
            pos += length
        frames[begin] = Frame(additional, MappingProxyType(entries))
    require(pos == len(render), 'RENDER_STREAM_END')
    return frames

def encode_frame(additional, entries):
    require(isinstance(additional, int) and 0 <= additional <= 0xffffffff,
            'ADDITIONAL_OFFSET_U32')
    require(len(entries) <= 0xffff, 'RENDER_COUNT_U16')
    data = bytearray(HEADER.pack(len(entries), additional))
    for key, payload in sorted(entries.items()):
        require(isinstance(key, int) and not isinstance(key, bool)
                and 0 <= key <= 0xffffffff, 'RENDER_KEY_U32')
        require(isinstance(payload, bytes) and len(payload) <= 0xffff,
                'RENDER_PAYLOAD_BYTES_U16')
        data += ENTRY.pack(key, len(payload)) + payload
    return bytes(data)

def activate_features(features, render, additional, valid_additional_offsets,
                      resolve):
    """Resolve per owner; retain all query bytes and unrelated render contexts.

    ``resolve(record_bytes, original_frame)`` returns None to keep an owner,
    or a mapping of original drawing keys to canonical instruction bytes.
    Replacements are deliberately limited to the two proven draw contexts.
    Identical full frames share offsets; different instructions never alias
    merely because the original features shared an empty frame.
    """
    require(isinstance(features, bytes) and len(features) % FEATURE.size == 0,
            'COMPLETE_IMMUTABLE_FEATURE_RECORDS')
    require(isinstance(additional, bytes), 'IMMUTABLE_ADDITIONAL_BYTES_REQUIRED')
    starts = frozenset(valid_additional_offsets)
    require(all(isinstance(x, int) and not isinstance(x, bool)
                and 0 <= x < len(additional) for x in starts),
            'ADDITIONAL_FRAME_STARTS_WITHIN_PRESERVED_BYTES')
    frames = decode_frames(render, starts)
    feature_output = bytearray()
    render_output = bytearray()
    dedup = {}
    activated = contexts = unchanged = 0
    for pos in range(0, len(features), FEATURE.size):
        record = features[pos:pos + FEATURE.size]
        fields = list(FEATURE.unpack(record))
        require(fields[2] in frames, 'REFERENCE_EXACT_RENDER_FRAME_START')
        frame = frames[fields[2]]
        updates = resolve(record, frame)
        entries = dict(frame.entries)
        if updates is None:
            unchanged += 1
        else:
            require(bool(updates) and set(updates) <= DRAW_KEYS,
                    'ONLY_PROVEN_NONEMPTY_DRAW_CONTEXT_UPDATES')
            for key, value in updates.items():
                require(isinstance(value, bytes) and value and b'\0' not in value,
                        'NONEMPTY_CANONICAL_COMMAND_BYTES_WITHOUT_NUL')
                require(not 48 <= value[0] <= 57,
                        'TABLE_INST_DIGIT_PREFIX_REQUIRES_SEPARATE_NORMALIZATION')
                entries[key] = value
            activated += 1
            contexts += len(updates)
        encoded = encode_frame(frame.additional_offset, entries)
        if encoded not in dedup:
            require(len(render_output) <= 0xffffffff, 'RENDER_OFFSET_U32')
            dedup[encoded] = len(render_output)
            render_output += encoded
        fields[2] = dedup[encoded]
        rewritten = FEATURE.pack(*fields)
        require(rewritten[:12] == record[:12] and rewritten[16:] == record[16:],
                'ONLY_FEATURE_RENDER_OFFSET_MAY_CHANGE')
        feature_output += rewritten
    output = bytes(render_output)
    decoded = decode_frames(output, starts)
    require(all(f[2] in decoded for f in FEATURE.iter_unpack(feature_output)),
            'REWRITTEN_REFERENCES_EXACT_STARTS')
    return bytes(feature_output), output, dict(
        format='no5-resolved-render-frame-rewrite/1',
        featureOwners=len(features) // FEATURE.size,
        ownersResolved=activated, ownersUnresolvedKept=unchanged,
        drawingContextsResolved=contexts, uniqueCompleteFrames=len(decoded),
        additionalBytesUnchanged=True, additionalSHA256=sha256(additional).hexdigest(),
        UIDIndexDictionaryWord20ClassKindFlagsUnchanged=True,
        sourceStyleResolutionPerformed=False, nativePaintingProven=False)
