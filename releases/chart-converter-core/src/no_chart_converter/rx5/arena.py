# Derived from frozen V18 arena_core.py af33962f02487b7d71c4b4a001cd851a75e67a3323ac9617dec22146c35d07b3.
# Explicit Kind9 XYZ companion support proven by original native 98ddbbe95da8439c91bcd8c7893f162282e658b210740fa4fe3cb3325da9c85c.
# Only two executable deltas: allow Kind9 and use XYZ12 stride for Kind4/9.
"""Pure, bounded, lossless RX5 index/coordinate arena compaction.

compact_arena(index_bytes, coordinate_bytes, feature32_bytes) returns new bytes
and an explicit inverse map. No files, native libraries or runtime imports.
"""
import bisect
import hashlib
import struct

FEATURE = struct.Struct('<Q5I4B')
LOW24 = 0x00ffffff


def require(condition, message):
    if not condition:
        raise ValueError(message)


def sha(data):
    return hashlib.sha256(data).hexdigest()


def union_copy(data, spans):
    """Copy overlapping/adjacent source components once, preserving address alias.

Distinct source starts are never deduplicated by byte equality. Padding of up to
three non-element bytes preserves each original start's modulo-four alignment.
"""
    components = []
    for start,end in sorted(set(spans)):
        require(0 <= start < end <= len(data), 'WHOLE_ORIGINAL_SPAN_IN_RANGE')
        if components and start <= components[-1][1]:
            components[-1][1] = max(components[-1][1],end)
        else:
            components.append([start,end])
    output = bytearray()
    mapping = []
    padding_total = 0
    for start,end in components:
        padding = (start-len(output)) % 4
        output.extend(bytes(padding))
        padding_total += padding
        new_start = len(output)
        output.extend(data[start:end])
        mapping.append(dict(oldStart=start,oldEnd=end,newStart=new_start,bytes=end-start,
                            originalSHA256=sha(data[start:end]),alignmentPadding=padding))
    starts = [row['oldStart']for row in mapping]
    def relocate(start,end):
        position = bisect.bisect_right(starts,start)-1
        require(position >= 0, 'ORIGINAL_COMPONENT_FOR_REFERENCE')
        row = mapping[position]
        require(row['oldStart'] <= start < end <= row['oldEnd'], 'REFERENCE_WHOLE_WITHIN_COMPONENT')
        target = row['newStart']+start-row['oldStart']
        require(target%4 == start%4, 'ORIGINAL_OFFSET_ALIGNMENT_PRESERVED')
        return target
    return bytes(output),mapping,padding_total,relocate


def compact_arena(index_bytes, coordinate_bytes, feature32_bytes):
    """Lossless compact buffers; only Feature+8 and index low24 may change.

Input geometry kind is Feature byte28: kinds1/2/3 use 8-byte XY points, kind4
uses 12-byte XYZ points. Element header/count/flags and all payload bytes are
copied exactly. If index alias roles demand contradictory byte writes, return
the entire original node unchanged with disposition PRESERVED_CONFLICT.
    """
    require(len(feature32_bytes)%FEATURE.size == 0, 'WHOLE_FEATURE32_PAYLOAD')
    frames = {}
    typed = {}
    words = []
    record_rows = []
    for ordinal in range(len(feature32_bytes)//FEATURE.size):
        record = FEATURE.unpack_from(feature32_bytes,ordinal*FEATURE.size)
        uid,index_start,kind = record[0],record[1],record[6]
        require(kind in(1,2,3,4,9), 'KNOWN_NATIVE_GEOMETRY_KIND')
        require(0 <= index_start <= len(index_bytes)-4, 'LIVE_INDEX_COUNT_RANGE')
        count = struct.unpack_from('<I',index_bytes,index_start)[0]
        index_end = index_start+4+4*count
        require(index_end <= len(index_bytes), 'COMPLETE_LIVE_INDEX_FRAME')
        frames[index_start] = index_end
        record_rows.append(dict(ordinal=ordinal,uid=str(uid),kind=kind,oldIndexOffset=index_start,indexEnd=index_end))
        stride = 12 if kind in(4,9) else 8
        for part in range(count):
            at = index_start+4+4*part
            word = struct.unpack_from('<I',index_bytes,at)[0]
            coordinate_start = word&LOW24
            require(coordinate_start <= len(coordinate_bytes)-4, 'ORIGINAL_COORDINATE_HEADER_RANGE')
            header = struct.unpack_from('<I',coordinate_bytes,coordinate_start)[0]
            points = header&LOW24
            coordinate_end = coordinate_start+4+points*stride
            require(coordinate_end <= len(coordinate_bytes), 'WHOLE_ORIGINAL_XY_XYZ_ELEMENT_RANGE')
            # Same old address with another stride/end remains one physical
            # union alias, rather than a first-kind-wins memoization.
            typed[(coordinate_start,coordinate_end,stride)] = dict(oldStart=coordinate_start,oldEnd=coordinate_end,
                 stride=stride,points=points,headerHex=coordinate_bytes[coordinate_start:coordinate_start+4].hex(),
                 originalSHA256=sha(coordinate_bytes[coordinate_start:coordinate_end]))
            words.append(dict(indexByte=at,word=word,oldCoordinateOffset=coordinate_start,
                              coordinateEnd=coordinate_end,recordOrdinal=ordinal,partOrdinal=part))
    coordinate_output,coordinate_components,coordinate_padding,coordinate_relocate = union_copy(
        coordinate_bytes,[(row['oldStart'],row['oldEnd'])for row in typed.values()])
    index_original,index_components,index_padding,index_relocate = union_copy(index_bytes,list(frames.items()))
    # Protect every live count field. Patch bytes are keyed by their ORIGINAL
    # physical index address, so all aliased interpretations must agree.
    edits = {}
    def assign(at,data):
        for offset,value in enumerate(data):
            old = edits.get(at+offset)
            if old is not None and old != value:
                return False
            edits[at+offset] = value
        return True
    compatible = True
    for start in frames:
        compatible &= assign(start,index_bytes[start:start+4])
    for row in words:
        new_offset = coordinate_relocate(row['oldCoordinateOffset'],row['coordinateEnd'])
        require(new_offset <= LOW24, 'LIVE_PACKED_COORDINATE_START_LOW24')
        new_word = (row['word']&0xff000000)|new_offset
        compatible &= assign(row['indexByte'],struct.pack('<I',new_word))
    if not compatible:
        return dict(disposition='PRESERVED_CONFLICT',reason='LIVE_INDEX_COUNT_OR_WORD_ALIAS_REQUIRES_CONTRADICTORY_BYTES',
                    indexBytes=index_bytes,coordinateBytes=coordinate_bytes,featureBytes=feature32_bytes,
                    changed=False,indexComponents=[],coordinateComponents=[],records=[],typedElements=[],words=[])
    index_output = bytearray(index_original)
    for old_at,value in edits.items():
        new_at = index_relocate(old_at,old_at+1)
        index_output[new_at] = value
    features = bytearray(feature32_bytes)
    for row in record_rows:
        new_start = index_relocate(row['oldIndexOffset'],row['indexEnd'])
        require(new_start < 1<<32, 'RECORD_INDEX_OFFSET_U32')
        row['newIndexOffset'] = new_start
        struct.pack_into('<I',features,row['ordinal']*FEATURE.size+8,new_start)
    for row in typed.values():
        row['newStart'] = coordinate_relocate(row['oldStart'],row['oldEnd'])
        row['newEnd'] = row['newStart']+row['oldEnd']-row['oldStart']
    for row in words:
        row['newIndexByte'] = index_relocate(row['indexByte'],row['indexByte']+4)
        row['newCoordinateOffset'] = coordinate_relocate(row['oldCoordinateOffset'],row['coordinateEnd'])
    index_output = bytes(index_output)
    features = bytes(features)
    return dict(disposition='COMPACTED' if(index_output != index_bytes or coordinate_output != coordinate_bytes)else'ALREADY_FULLY_LIVE',
                changed=index_output != index_bytes or coordinate_output != coordinate_bytes,
                indexBytes=index_output,coordinateBytes=coordinate_output,featureBytes=features,
                indexComponents=index_components,coordinateComponents=coordinate_components,
                indexAlignmentPaddingBytes=index_padding,coordinateAlignmentPaddingBytes=coordinate_padding,
                records=record_rows,typedElements=sorted(typed.values(),key=lambda row:(row['oldStart'],row['oldEnd'],row['stride'])),
                words=words,aliasIdentity='SOURCE_ADDRESS_UNION_NOT_BYTE_EQUALITY',
                oldIndexBytes=len(index_bytes),newIndexBytes=len(index_output),
                oldCoordinateBytes=len(coordinate_bytes),newCoordinateBytes=len(coordinate_output))
