"""Default-off, query-exact simple-LS preparation for a whole Source19 subtree.

This is a pure buffer API. It makes no file, routing, renderer or native call.
An explicit caller must bind the real route and normal painter profile, and
qualify the resulting original-renderer images separately. Unsupported inputs
KEEP their complete original draw programme, geometry and query payloads.
"""
from fractions import Fraction
import hashlib
import re
import struct

from .locality_parts import projection_safety

POLICY = 'SOURCE19_COMPLETE_SUBTREE_ROOT1_SIMPLE_LS_DRAW_CULL/1'
TRANSFORM = 'ROOT1_F64_TILE256_F32_QROUND_IDENTITY/1'
PAINTER = 'NORMAL_256_SIMPLE_LS_WIDTH1_2_FLATCAP/1'
Q = 2147483649
LEVELS = list(range(19, 30))
DRAW = (196608, 196609)
STROKE_SUPPORT = 16
FEATURE = struct.Struct('<Q5I4B')
LS = re.compile(rb'LS\((?:SOLD|DASH),[12],[A-Z0-9_]+\);')
NAMES = ('FI', 'I', 'CO', 'RO', 'AO')


def need(value, reason):
    if not value:
        raise ValueError(reason)


def pin(raw):
    return dict(bytes=len(raw), sha256=hashlib.sha256(raw).hexdigest())


def pins(*buffers):
    return {name: pin(raw) for name, raw in zip(NAMES, buffers)}


def pair(value):
    return [value.numerator, value.denominator]


def source_box(tile):
    """Closed Root1 rectangle containing every descendant's 128-pixel halo."""
    level, x, y = tile
    denominator = 256 * (1 << level)
    return [Fraction((256*x-128)*Q, denominator),
            Fraction((256*y-128)*Q, denominator),
            Fraction((256*(x+1)+128)*Q, denominator),
            Fraction((256*(y+1)+128)*Q, denominator)]


def separation(a, b, box):
    """Strict Linf halfplane clearance for a complete edge and a closed box.

    A separating coordinate or the edge's own normal is required. Returning
    None is conservative: contact, crossing and uncertain configurations KEEP.
    The result bounds displacement of every point along the complete edge.
    """
    corners = [(box[0], box[1]), (box[2], box[1]),
               (box[2], box[3]), (box[0], box[3])]
    best = None
    for nx, ny in ((1, 0), (0, 1), (a[1]-b[1], b[0]-a[0])):
        if not (nx or ny):
            continue
        for sign in (1, -1):
            x, y = nx*sign, ny*sign
            edge_max = max(x*a[0]+y*a[1], x*b[0]+y*b[1])
            box_min = min(x*p[0]+y*p[1] for p in corners)
            gap = box_min - edge_max
            if gap > 0:
                clearance = Fraction(gap, abs(x)+abs(y))
                best = clearance if best is None else max(best, clearance)
    return best


def _domain(domain):
    need(type(domain) is dict and set(domain) == {
        'sourceTile', 'levels', 'haloPixels', 'transformContract',
        'painterContract', 'routingBindingSHA256'}, 'EXPLICIT_WHOLE_SUBTREE_DOMAIN')
    tile = domain['sourceTile']
    need(type(tile) in (list, tuple) and len(tile) == 3 and
         all(type(v) is int for v in tile), 'EXPLICIT_INTEGER_SOURCE19')
    level, x, y = tile
    need(level == 19 and 0 <= x < 1 << level and 0 <= y < 1 << level,
         'SOURCE19_ONLY_NO_DIFFERENT_LOD_ADMISSION')
    need(domain['levels'] == LEVELS and domain['haloPixels'] == 128 and
         type(domain['haloPixels']) is int, 'ALL19_TO29_LEVELS_AND_FULL128_HALO')
    need(domain['transformContract'] == TRANSFORM and
         domain['painterContract'] == PAINTER and
         type(domain['routingBindingSHA256']) is str and
         re.fullmatch('[0-9a-f]{64}', domain['routingBindingSHA256']) is not None,
         'EXPLICIT_NORMAL_PROJECTION_PAINTER_AND_ROUTING_BINDING')
    return tuple(tile)


def prepare(record, index, coordinates, render, additional, *, policy=None,
            domain=None, expected_input_pins=None, max_parts=2048,
            max_points=65536):
    """Prepare an empty draw frame only after complete geometric exclusion.

    Both complete maps must contain a single simple LS instruction. Pure LC,
    symbol/text instructions, extra contexts, reverse/mask roles, contact and
    unsafe signed projection retain the original owner. No clipped vertices,
    changed phase, shortened index, query rewrite or duplicate owner is made.
    """
    buffers = (record, index, coordinates, render, additional)
    original = dict(zip(('record', 'indices', 'coordinates', 'render',
                         'additional'), buffers))

    def keep(reason):
        return dict(action='KEEP', reason=reason, original=original,
                    proposal=None, applied=False)

    if policy != POLICY:
        return keep('DEFAULT_OFF_EXPLICIT_POLICY_REQUIRED')
    try:
        need(all(type(v) is bytes for v in buffers) and len(record) == 32 and
             sum(map(len, buffers)) <= 8 << 20, 'BOUNDED_IMMUTABLE_FIVE_BUFFERS')
        need(expected_input_pins == pins(*buffers), 'EXACT_FIVE_INPUT_PINS')
        tile = _domain(domain)
        need(type(max_parts) is type(max_points) is int and
             0 < max_parts <= 2048 and 0 < max_points <= 65536,
             'EXPLICIT_FINITE_WORK_CAPS')
        fields = FEATURE.unpack(record)
        need(fields[6] == 2 and fields[7:] == (0, 0, 0), 'ONLY_KIND2_FLAGS0')
        at = fields[2]
        need(at <= len(render)-6, 'WHOLE_RO_HEADER')
        count, query = struct.unpack_from('<HI', render, at)
        need(count == 2, 'EXACT_TWO_DRAW_CONTEXTS')
        pos, contexts = at+6, []
        for _ in range(count):
            need(pos <= len(render)-6, 'WHOLE_CONTEXT_HEADER')
            key, size = struct.unpack_from('<IH', render, pos)
            pos += 6
            need(size <= len(render)-pos, 'WHOLE_CONTEXT_BODY')
            text = render[pos:pos+size]
            pos += size
            contexts.append((key, text))
        need({key for key, _ in contexts} == set(DRAW) and
             all(LS.fullmatch(text) for _, text in contexts),
             'ONLY_BOTH_PURE_SIMPLE_LS_PROGRAMMES')
        at = fields[1]
        need(at <= len(index)-4, 'WHOLE_INDEX_HEADER')
        count = struct.unpack_from('<I', index, at)[0]
        need(0 < count <= max_parts and at+4+4*count <= len(index),
             'WHOLE_FINITE_INDEX_FRAME')
        points, rows, joins = [], [], set()
        for ordinal in range(count):
            word = struct.unpack_from('<I', index, at+4+4*ordinal)[0]
            offset, usage = word & 0xffffff, word >> 24
            need(usage == 16 and offset <= len(coordinates)-4,
                 'ONLY_FORWARD_UNMASKED_USAGE16')
            header = struct.unpack_from('<I', coordinates, offset)[0]
            size = header & 0xffffff
            need(header >> 24 == 1 and size >= 2 and
                 size <= max_points-len(points) and
                 offset+4+8*size <= len(coordinates), 'WHOLE_FINITE_ROOT1_PART')
            raw = coordinates[offset:offset+4+8*size]
            part = list(struct.iter_unpack('<II', raw[4:]))
            need(all(value <= Q for point in part for value in point),
                 'ONLY_ROOT1_INTEGER_COORDINATES')
            if points:
                joins.add(len(points)-1)
            points.extend(part)
            rows.append(dict(part=ordinal, indexWord=word, points=size,
                             typedElementPin=pin(raw)))
        # Include hypothetical part connectors as an additional conservative
        # guard; this does not assert that LS introduces LC-style connectors.
        box, clearance, trace = source_box(tile), None, hashlib.sha256()
        for a, b in zip(points, points[1:]):
            gap = separation(a, b, box)
            need(gap is not None, 'EDGE_OR_CONSERVATIVE_CONNECTOR_TOUCHES_DOMAIN')
            clearance = gap if clearance is None else min(clearance, gap)
            trace.update(struct.pack('<4I', *a, *b))
        safety = projection_safety(points, tile, max_level=29)
        need(safety is not None and [r['level'] for r in safety] == LEVELS,
             'ALL_DESCENDANT_SIGNED_PROJECTION_INTERMEDIATES_SAFE')
        levels = []
        for row in safety:
            level = row['level']
            gap = clearance * Fraction(1 << (level+8), Q)
            error = row['conservativeAxisProjectionQtErrorPixels']
            need(gap > error+STROKE_SUPPORT,
                 'ROUNDED_SIMPLE_STROKE_MAY_TOUCH_PROTECTED_SUBTREE')
            width = 1 << (level-19)
            levels.append(dict(level=level,
                x=[tile[1]*width, (tile[1]+1)*width-1],
                y=[tile[2]*width, (tile[2]+1)*width-1],
                descendantTiles=width*width, minimumGapPixels=pair(gap),
                projectionErrorPixels=error,
                simpleStrokeSupportPixels=STROKE_SUPPORT,
                strictResidualPixels=pair(gap-error-STROKE_SUPPORT)))
        frame = struct.pack('<HI', 2, query) + b''.join(
            struct.pack('<IH', key, 0) for key, _ in contexts)
        receipt = dict(format='no5-whole-source19-simple-ls-proof/1',
            policy=POLICY, domain=domain, inputPins=expected_input_pins,
            UID=fields[0], kind=2, queryOffset=query, sourceParts=count,
            sourcePointOccurrences=len(points), completeParts=rows,
            directedEdges=len(points)-1,
            extraConservativeConnectors=len(joins), traceSHA256=trace.hexdigest(),
            minimumClearanceRootUnits=pair(clearance),
            protectedSourceRootBox=[pair(v) for v in box],
            nativeProjectionSafety=safety, completeSubtreeDomains=levels,
            allDescendantAxesCoveredByIntervals=True,
            centerSamplesUsedAsProof=False, queryGeometryAndAOExact=True,
            noLCPhaseRestartOrGeometryClip=True,
            originalFramePin=pin(render[fields[2]:pos]),
            replacementFramePin=pin(frame), nativePixelsQualified=False,
            allZoomProductQualified=False, actualNativeAdmissionQualified=False,
            callerMustBindActualRouteAndNormalPainter=True, applied=False)
        return dict(action='PROPOSE_EMPTY_DRAW_CONTEXTS', original=original,
                    proposal=dict(renderFrame=frame), receipt=receipt,
                    applied=False)
    except (ValueError, struct.error, OverflowError) as error:
        return keep(str(error))


def prepare_source(features, index, coordinates, render, additional, *,
                   policy=None, domain=None, owner_uids=None,
                   expected_input_pins=None):
    """Prepare ordinary FI/RO buffers; complete I/CO/AO stay byte-identical.

    Selection is explicit and bounded. A duplicate selected UID is ambiguous
    and refused. Unsupported selected owners KEEP their original complete
    programmes. This API does not activate a file-tree route or CLI default.
    """
    buffers = (features, index, coordinates, render, additional)
    original = dict(zip(('featureBytes', 'indexBytes', 'coordinateBytes',
                         'renderBytes', 'additionalBytes'), buffers))
    if policy != POLICY:
        return dict(**original, receipt=dict(status='DEFAULT_OFF',
                    changedOwners=0, appliedToFile=False))
    need(all(type(v) is bytes for v in buffers) and len(features) % 32 == 0 and
         0 < len(features) <= 2 << 20 and sum(map(len, buffers)) <= 8 << 20,
         'COMPLETE_BOUNDED_SOURCE_BANKS')
    need(expected_input_pins == pins(*buffers), 'FIVE_COMPLETE_SOURCE_PINS')
    _domain(domain)
    need(type(owner_uids) is list and 0 < len(owner_uids) <= 16 and
         all(type(v) is int and 0 <= v < 1 << 64 for v in owner_uids) and
         len(set(owner_uids)) == len(owner_uids), 'EXPLICIT_FINITE_UID_SELECTION')
    owners = {}
    for ordinal in range(len(features)//32):
        uid = struct.unpack_from('<Q', features, 32*ordinal)[0]
        owners.setdefault(uid, []).append(ordinal)
    need(all(len(owners.get(uid, [])) == 1 for uid in owner_uids),
         'ONE_ORIGINAL_OWNER_PER_SELECTED_UID')
    out_f, out_ro, frames, changed, kept = bytearray(features), bytearray(render), {}, [], []
    for uid in owner_uids:
        ordinal = owners[uid][0]
        record = features[32*ordinal:32*(ordinal+1)]
        result = prepare(record, index, coordinates, render, additional,
                         policy=policy, domain=domain,
                         expected_input_pins=pins(record, *buffers[1:]))
        if result['action'] == 'KEEP':
            kept.append(dict(UID=uid, sourceFI=ordinal, reason=result['reason']))
            continue
        frame = result['proposal']['renderFrame']
        if frame not in frames:
            frames[frame] = len(out_ro)
            out_ro.extend(frame)
        struct.pack_into('<I', out_f, 32*ordinal+12, frames[frame])
        changed.append(dict(UID=uid, sourceFI=ordinal,
                            newROOffset=frames[frame], proof=result['receipt']))
    inverse = bytearray(out_f)
    for row in changed:
        at = 32*row['sourceFI']+12
        inverse[at:at+4] = features[at:at+4]
    need(bytes(inverse) == features and out_ro[:len(render)] == render and
         len(out_ro) <= 8 << 20, 'ONLY_FI_RO_WORDS_AND_FINITE_APPEND_FRAMES')
    return dict(featureBytes=bytes(out_f), indexBytes=index,
        coordinateBytes=coordinates, renderBytes=bytes(out_ro),
        additionalBytes=additional, receipt=dict(
            format='no5-whole-source19-simple-ls-buffers/1', policy=POLICY,
            domain=domain, inputPins=expected_input_pins,
            sourceOwners=len(features)//32, selectedUIDs=owner_uids,
            changedOwners=len(changed), changed=changed, kept=kept,
            appendedRenderBytes=len(out_ro)-len(render),
            fullI_CO_AOAndQueryGeometryExact=True, allOtherFIFieldsExact=True,
            originalRenderPrefixExact=True, defaultOff=True, appliedToFile=False,
            nativePixelsQualified=False, allZoomProductQualified=False))
