"""Explicit full-geometry child sources with independently qualified no-ink RO.

No source/part/owner is omitted. Existing nodes, resources and fallback vectors
remain at their original addresses; only empty child pointers and UUID change
in the original prefix. This opt-in in-memory materializer never edits a file or
activates itself in the ordinary conversion pipeline. Native scale/query/raster
parity remains a caller-owned acceptance step.
"""
from __future__ import annotations

import hashlib
import inspect
import json
from pathlib import Path
import struct
import uuid

from .header import validate_plain_vector_header
from .model import native_tree, parts, programmes
from .wire import FEATURE, blocks, need

POLICY = 'full-root1-child-clone-qualified-empty-text-values-append/1'
TEXT_CONTEXTS = frozenset((0x30000, 0x30001))
BUFFER_NAMES = ('features', 'indices', 'coordinates', 'render', 'additional')
RESULT_KEYS = ('featureBytes', 'indexBytes', 'coordinateBytes', 'renderBytes', 'additionalBytes')


def _sha(raw):
    return hashlib.sha256(raw).hexdigest()


def _canonical(value):
    return json.dumps(value, sort_keys=True, separators=(',', ':'), ensure_ascii=True).encode('ascii')


def _tile(value):
    need(type(value) in (list, tuple) and len(value) == 3 and all(type(v) is int for v in value), 'INTEGER_CHILD_TILE')
    z, x, y = value
    need(0 <= z <= 29 and 0 <= x < 1 << z and 0 <= y < 1 << z, 'CHILD_TILE_BOUNDS')
    return tuple(value)


def _ancestor(parent, child):
    d = child[0]-parent[0]
    return d > 0 and (child[1] >> d, child[2] >> d) == parent[1:]


def _profiles(profiles):
    need(type(profiles) in (list, tuple), 'EXPLICIT_CHILD_PROFILE_LIST')
    normalized = []
    for row in profiles:
        need(type(row) is dict and set(row) == {'sourceTile', 'childTile', 'ownerUIDs'}, 'EXACT_CHILD_PROFILE_FIELDS')
        parent, child = _tile(row['sourceTile']), _tile(row['childTile'])
        need(_ancestor(parent, child), 'STRICT_CHILD_DESCENDANT')
        uids = row['ownerUIDs']
        need(type(uids) in (list, tuple) and bool(uids) and all(type(v) is int and 0 < v < 1 << 64 for v in uids), 'EXPLICIT_OWNER_UIDS')
        need(len(set(uids)) == len(uids), 'UNIQUE_OWNER_UIDS')
        normalized.append(dict(sourceTile=list(parent), childTile=list(child), ownerUIDs=sorted(uids)))
    need(len({tuple(r['childTile']) for r in normalized}) == len(normalized), 'UNIQUE_CHILD_SOURCE_TILES')
    return sorted(normalized, key=lambda r: (r['sourceTile'], r['childTile']))


def _policy_module():
    from . import render_noink
    return render_noink


def _validate_policy_output(original, actual, owner_uids):
    """Protect full FI/geometry/query/category semantics outside text contexts."""
    f, ix, co, ro, ao = original
    nf, ni, nc, nr, na = actual
    need(all(type(x) is bytes for x in actual), 'IMMUTABLE_POLICY_OUTPUT_BUFFERS')
    need((ix, co, ao) == (ni, nc, na), 'FULL_INDEX_COORDINATE_ADDITIONAL_BYTES_EXACT')
    need(len(f) == len(nf) and len(f) % 32 == 0, 'FULL_ORDERED_FEATURE_LIST_EXACT_SIZE')
    changed = []
    for ordinal in range(len(f)//32):
        before, after = f[ordinal*32:(ordinal+1)*32], nf[ordinal*32:(ordinal+1)*32]
        need(before[:12]+before[16:] == after[:12]+after[16:], 'ONLY_FEATURE_RENDER_OFFSET_MAY_CHANGE')
        a, b = FEATURE.unpack(before), FEATURE.unpack(after)
        if a[0] not in owner_uids:
            need(before == after, 'UNREQUESTED_OWNER_FEATURE_BYTES_EXACT')
        old_contexts, old_query = programmes(ro, a[2])
        new_contexts, new_query = programmes(nr, b[2])
        need(old_query == new_query and list(old_contexts) == list(new_contexts), 'QUERY_POINTER_AND_CONTEXT_ORDER_EXACT')
        if a[0] in owner_uids:
            need(all(str(k) in new_contexts and new_contexts[str(k)] == b'' for k in TEXT_CONTEXTS),
                 'ONLY_CANONICAL_EMPTY_DRAW_VALUES')
        for key, value in old_contexts.items():
            if a[0] not in owner_uids or int(key) not in TEXT_CONTEXTS:
                need(value == new_contexts[key], 'ALL_NON_TARGET_TEXT_AND_CATEGORY_CONTEXTS_EXACT')
        if before != after:
            changed.append(dict(ordinal=ordinal, uid=a[0], oldRenderOffset=a[2], newRenderOffset=b[2]))
    need({r['uid'] for r in changed} == set(owner_uids), 'EVERY_REQUESTED_OWNER_HAS_QUALIFIED_RO_REPLACEMENT')
    return changed


def _prepare(raw, profiles):
    need(type(raw) is bytes, 'IMMUTABLE_PLAIN_SOURCE_BYTES')
    requested = _profiles(profiles)
    validate_plain_vector_header(raw[:240], file_bytes=len(raw))
    need(all(struct.unpack_from('<Q', raw, o)[0] == 0 for o in (152, 176, 192)), 'OWN_PLAIN_CHART_ONLY')
    parsed = blocks(memoryview(raw)); tree = native_tree(raw, parsed)
    by_tile = {tile: at for at, tile in tree.items()}
    policy = _policy_module() if requested else None
    kernel = None if policy is None else dict(module=policy.__name__, sha256=_sha(Path(inspect.getfile(policy)).read_bytes()))
    rows, prepared, new_nodes = [], {}, {}
    for row in requested:
        parent, child = tuple(row['sourceTile']), tuple(row['childTile'])
        need(parent in by_tile, 'EXISTING_CANONICAL_PARENT_SOURCE')
        need(child not in by_tile, 'CHILD_TILE_MUST_BE_ABSENT')
        at = by_tile[parent]; node = parsed[at]
        need(node.flags == 3 and node.next_offset == 0 and struct.unpack_from('<4Q', node.prefix) == (0, 0, 0, 0), 'ONLY_FULL_VECTOR_LEAF_PARENT')
        io, co, ro, ao = struct.unpack_from('<4Q', node.prefix, 32)
        for ptr, disk_id in zip((io, co, ro, ao), (0x1101, 0x1102, 0x1103, 0x1104)):
            b = parsed[ptr]
            need((b.disk_id, b.flags, bytes(b.prefix), b.next_offset) == (disk_id, 0, b'', 0), 'CANONICAL_UNCHAINED_PARENT_STREAMS')
        buffers = tuple(bytes(x) for x in (node.payload, parsed[io].payload, parsed[co].payload, parsed[ro].payload, parsed[ao].payload))
        root_parts = points = 0
        for offset in range(0, len(buffers[0]), 32):
            for p in parts(buffers[0][offset:offset+32], buffers[1], buffers[2]):
                need(p['rootFlag'] == 1, 'COMPLETE_PARENT_ROOT1_ONLY')
                root_parts += 1; points += p['pointCount']
        hashes = {name: _sha(buf) for name, buf in zip(BUFFER_NAMES, buffers)}
        result = policy.prepare_no_ink_render(*buffers, source_tile=child,
            owner_uids=tuple(row['ownerUIDs']), expected_input_sha256=hashes)
        actual = tuple(result[k] for k in RESULT_KEYS)
        changes = _validate_policy_output(buffers, actual, row['ownerUIDs'])
        need(actual[3].startswith(buffers[3]), 'QUALIFIED_RO_IS_APPEND_ONLY')
        output_pins = {name: dict(bytes=len(buf), sha256=_sha(buf)) for name, buf in zip(BUFFER_NAMES, actual)}
        # The exact policy certificate is recomputed on every build. Bind its
        # canonical digest rather than duplicating per-vertex proof arrays in
        # every materializer plan; immutable input/code pins reproduce it.
        proof = result['receipt']; proof_bytes = _canonical(proof)
        proof_binding = dict(bytes=len(proof_bytes), sha256=_sha(proof_bytes),
            preparationSHA256=proof.get('preparationSHA256'), policy=proof.get('policy'),
            sourceTile=proof.get('sourceTile'), selectedOwnerUIDs=proof.get('selectedOwnerUIDs'),
            appendedRenderFrames=proof.get('appendedRenderFrames'), appendedRenderBytes=proof.get('appendedRenderBytes'))
        item = dict(**row, parentNodeOffset=at, parentStreams=[io,co,ro,ao], parentFeatureBytes=len(buffers[0]),
                    inputSHA256=hashes, outputBuffers=output_pins, renderOffsetChanges=changes,
                    retained=dict(records=len(buffers[0])//32, parts=root_parts, pointOccurrences=points),
                    policyReceiptBinding=proof_binding)
        rows.append(item); prepared[child] = (item, actual)
        for level in range(parent[0]+1, child[0]+1):
            tile = (level, child[1] >> (child[0]-level), child[2] >> (child[0]-level))
            need(tile not in by_tile, 'NEW_PATH_MUST_NOT_OVERWRITE_EXISTING_NODE')
            old_parent = new_nodes.get(tile)
            need(old_parent is None or old_parent == parent, 'NO_CONFLICTING_PARENT_PATH')
            new_nodes[tile] = parent
    plan = dict(format='no5-full-geometry-no-ink-child-plan/1', policy=POLICY,
        source=dict(bytes=len(raw), sha256=_sha(raw)), renderPolicy=kernel, profiles=rows,
        originalTreeNodes=len(tree), addedTreeNodes=[dict(tile=list(t), flags=3 if t in prepared else 1) for t in sorted(new_nodes)],
        fullGeometryRetained=True, parentFallbacksRetained=True, automaticPipelineActivation=False,
        nativeSourceScaleQueryRasterAcceptance=False)
    return plan, parsed, tree, by_tile, prepared, new_nodes


def plan_no_ink_children(raw: bytes, profiles) -> dict:
    """Bind the exact source, requested children and qualified RO generation."""
    return _prepare(raw, profiles)[0]


def no_ink_children_plan_sha256(plan: dict) -> str:
    return _sha(_canonical(plan))


def build_no_ink_children(raw: bytes, profiles, *, expected_source_sha256: str,
                          expected_plan_sha256: str) -> tuple[bytes, dict]:
    """Append full-geometry vectors and routing nodes; preserve all fallbacks."""
    plan, before, tree, by_tile, prepared, new_nodes = _prepare(raw, profiles)
    need(plan['source']['sha256'] == expected_source_sha256, 'EXACT_SOURCE_SHA256')
    digest = no_ink_children_plan_sha256(plan)
    need(digest == expected_plan_sha256, 'EXACT_RECOMPUTED_CHILD_PLAN')
    if not prepared:
        return raw, dict(format='no5-full-geometry-no-ink-child-result/1', status='NO_CHANGE',
            sourcePlanSHA256=digest, appendBytes=0, allowedOriginalPrefixEdits=[], nativeAcceptance=False)
    output = bytearray(raw); new_ro = {}; offsets = {}; edits = []
    for tile in sorted(prepared):
        item, actual = prepared[tile]; payload = actual[3]
        new_ro[tile] = len(output)
        output += struct.pack('<HHIQ', 0x1103, 0, len(payload), 0)+payload
    cursor = len(output)
    for tile in sorted(new_nodes):
        offsets[tile] = cursor
        cursor += 16+(64+len(prepared[tile][1][0]) if tile in prepared else 32)
    for tile in sorted(new_nodes):
        z,x,y = tile
        children = [offsets.get((z+1,2*x+(s&1),2*y+(s>>1)),0) for s in range(4)]
        if tile in prepared:
            item, actual = prepared[tile]; io,co,_,ao = item['parentStreams']
            prefix = struct.pack('<8Q', *children, io,co,new_ro[tile],ao); payload = actual[0]; flags = 3
        else:
            prefix = struct.pack('<4Q', *children); payload = b''; flags = 1
        need(len(output) == offsets[tile], 'DETERMINISTIC_APPENDED_NODE_OFFSET')
        output += struct.pack('<HHIQ', 0x6000+z, flags, len(prefix)+len(payload), 0)+prefix+payload
    need(len(output) == cursor, 'EXACT_APPEND_EXTENT')
    patched = set()
    for tile, parent in sorted(new_nodes.items()):
        if tile[0] != parent[0]+1:
            continue
        slot = (tile[1]&1)+2*(tile[2]&1); at = by_tile[parent]+16+8*slot
        need(at not in patched and raw[at:at+8] == bytes(8), 'ONLY_EMPTY_ORIGINAL_CHILD_SLOT')
        patched.add(at); edits.append(dict(offset=at, bytes=8, originalHex=raw[at:at+8].hex()))
        struct.pack_into('<Q',output,at,offsets[tile])
    edits.append(dict(offset=56,bytes=16,originalHex=raw[56:72].hex()))
    output[56:72] = uuid.uuid5(uuid.NAMESPACE_URL, POLICY+':'+digest).bytes_le
    result = bytes(output); validate_plain_vector_header(result[:240], file_bytes=len(result))
    after = blocks(memoryview(result)); actual_tree = native_tree(result,after)
    need(all(actual_tree.get(at) == tile for at,tile in tree.items()), 'ALL_ORIGINAL_TREE_ADDRESSES_RETAINED')
    need({tile for at,tile in actual_tree.items() if at not in tree} == set(new_nodes), 'EXACT_REQUESTED_NEW_PATH_TREE')
    need(len(actual_tree) == len(tree)+len(new_nodes), 'NO_UNEXPECTED_TREE_NODE')
    for tile,(item,actual) in prepared.items():
        node = after[offsets[tile]]; parent = before[item['parentNodeOffset']]
        need(bytes(node.payload) == actual[0], 'EXACT_FULL_CHILD_FEATURE_PAYLOAD')
        ni,nc,nr,na = struct.unpack_from('<4Q', node.prefix,32)
        oi,oc,_,oa = item['parentStreams']
        need((ni,nc,na) == (oi,oc,oa), 'FULL_GEOMETRY_AND_QUERY_STORAGE_ALIASES_PARENT')
        need(bytes(after[nr].payload) == actual[3], 'EXACT_QUALIFIED_CHILD_RO')
        _validate_policy_output(tuple(bytes(x) for x in (parent.payload,before[oi].payload,before[oc].payload,before[item['parentStreams'][2]].payload,before[oa].payload)), actual, item['ownerUIDs'])
    previous = 0; inverse = hashlib.sha256()
    for e in sorted(edits,key=lambda e:e['offset']):
        at=e['offset']; old=bytes.fromhex(e['originalHex'])
        need(at>=previous and result[previous:at] == raw[previous:at], 'ALL_UNDECLARED_ORIGINAL_BYTES_EXACT')
        inverse.update(result[previous:at]); inverse.update(old); previous=at+len(old)
    need(result[previous:len(raw)] == raw[previous:], 'ALL_UNDECLARED_ORIGINAL_SUFFIX_EXACT')
    inverse.update(result[previous:len(raw)]);need(inverse.hexdigest()==plan['source']['sha256'],'FULL_ORIGINAL_PREFIX_INVERSE')
    report = dict(format='no5-full-geometry-no-ink-child-result/1', status='HOST_BUILT_NATIVE_PARITY_PENDING',
        policy=POLICY,source=plan['source'],sourcePlanSHA256=digest,renderPolicy=plan['renderPolicy'],
        output=dict(bytes=len(result),sha256=_sha(result),uuidHex=result[56:72].hex()),
        appliedProfiles=[dict(childTile=list(t),nodeOffset=offsets[t],renderBlockOffset=new_ro[t],retained=item['retained'],renderOffsetChanges=item['renderOffsetChanges'])for t,(item,_)in sorted(prepared.items())],
        appendedNodes=[dict(tile=list(t),offset=offsets[t],flags=3 if t in prepared else 1)for t in sorted(new_nodes)],
        appendBytes=len(result)-len(raw),allowedOriginalPrefixEdits=sorted(edits,key=lambda e:e['offset']),sourcePrefixInverseSHA256=inverse.hexdigest(),
        originalTreeNodes=len(tree),outputTreeNodes=len(actual_tree),originalPhysicalBlocksRetained=True,parentFallbacksRetained=True,
        ownersPartsAndFullRoot1GeometryRetained=True,allCategoryAndQueryContextsRetained=True,sourceSelectionChanged=True,
        nativeSourceScaleQueryRasterAcceptance=False,automaticPipelineActivation=False)
    return result,report
