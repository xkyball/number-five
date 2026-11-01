"""Bounded plain RX5 scene access; no native or private runtime imports."""
import struct

from .wire import FEATURE, blocks, need


def native_tree(raw, parsed=None):
    parsed = blocks(raw) if parsed is None else parsed
    found = {}
    pending = [(struct.unpack_from('<Q', raw, 160)[0], (0, 0, 0))]
    while pending:
        at, tile = pending.pop()
        need(at not in found and at in parsed, 'ACYCLIC_COMPLETE_NATIVE_TREE')
        block = parsed[at]
        need(block.flags in (1, 3) and block.disk_id == 0x6000 + tile[0], 'NATIVE_NODE_TYPE_AND_LEVEL')
        if block.flags == 3:
            need(len(block.payload) % 32 == 0, 'WHOLE_FEATURE32_PAYLOAD')
        found[at] = tile
        level, x, y = tile
        for slot, child in enumerate(struct.unpack_from('<4Q', block.prefix)):
            if child:
                pending.append((child, (level + 1, 2*x + (slot & 1), 2*y + (slot >> 1))))
    return found


def programmes(render, offset):
    need(0 <= offset <= len(render)-6, 'WHOLE_PROGRAMME_HEADER')
    count, query = struct.unpack_from('<HI', render, offset)
    position, contexts = offset + 6, {}
    for _ in range(count):
        need(position + 6 <= len(render), 'WHOLE_PROGRAMME_CONTEXT')
        context, size = struct.unpack_from('<IH', render, position)
        position += 6
        need(context not in contexts and position + size <= len(render), 'UNIQUE_COMPLETE_PROGRAMME_CONTEXT')
        contexts[str(context)] = render[position:position + size]
        position += size
    return contexts, query


def parts(record, indices, coordinates):
    fields = FEATURE.unpack(record)
    start = fields[1]
    need(0 <= start <= len(indices)-4, 'LIVE_INDEX_COUNT_RANGE')
    count = struct.unpack_from('<I', indices, start)[0]
    need(start + 4 + 4*count <= len(indices), 'COMPLETE_INDEX_FRAME')
    need(fields[6] in (1, 2, 3, 4, 9), 'SUPPORTED_GEOMETRY_KIND')
    stride = 12 if fields[6] in (4, 9) else 8
    for ordinal in range(count):
        word = struct.unpack_from('<I', indices, start + 4 + 4*ordinal)[0]
        at = word & 0xffffff
        need(at + 4 <= len(coordinates), 'COORDINATE_HEADER_RANGE')
        header = struct.unpack_from('<I', coordinates, at)[0]
        n = header & 0xffffff
        end = at + 4 + stride*n
        need(end <= len(coordinates), 'COMPLETE_TYPED_ELEMENT')
        raw = coordinates[at:end]
        points = [struct.unpack_from('<II', raw, 4 + stride*i) for i in range(n)] if header >> 24 == 1 else []
        yield dict(partOrdinal=ordinal, word=word, usage=word >> 24,
                   coordinateOffset=at, header=header, rootFlag=header >> 24,
                   pointCount=n, raw=raw, points=points)


def validate(raw):
    parsed = blocks(raw)
    need(all(struct.unpack_from('<Q', raw, o)[0] == 0 for o in (152, 176, 192)), 'OWN_PLAIN_CHART_ONLY')
    tree = native_tree(raw, parsed)
    owners = elements = points = 0
    kinds = {}
    for at in tree:
        node = parsed[at]
        if node.flags != 3:
            continue
        io, co, ro, ao = struct.unpack_from('<4Q', node.prefix, 32)
        need(all(q in parsed for q in (io, co, ro, ao)), 'RESOLVED_VECTOR_STREAMS')
        for offset in range(0, len(node.payload), 32):
            record = node.payload[offset:offset + 32]
            fields = FEATURE.unpack(record)
            _, query = programmes(parsed[ro].payload, fields[2])
            need(query < len(parsed[ao].payload), 'QUERY_OFFSET_RANGE')
            owners += 1
            kinds[str(fields[6])] = kinds.get(str(fields[6]), 0) + 1
            for part in parts(record, parsed[io].payload, parsed[co].payload):
                elements += 1
                points += part['pointCount']
    return {'blocks': len(parsed), 'nativeNodes': len(tree), 'owners': owners,
            'elements': elements, 'pointOccurrences': points, 'ownersByKind': kinds}
