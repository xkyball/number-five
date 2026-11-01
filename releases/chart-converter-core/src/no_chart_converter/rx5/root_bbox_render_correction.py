"""Explicit OWN Root-space raster correction, separate from native parity.

Only selected pure-AC programme values are emptied. Canonical geometry and
query data remain complete. This module never calls, replaces, or relaxes the
integer-region/native-parity guard and never writes a chart or changes a tree.
"""
from __future__ import annotations

import hashlib
import json
import re
import struct

from . import metadata, model
from .header import validate_plain_vector_header
from .wire import FEATURE, need

POLICY = 'EXPLICIT_ROOT_BBOX_CULL_BEFORE_ARM_QUANTIZATION'
PRODUCER_CONTRACT_SHA256 = '2487b1dbed00731bab992ea2fe69b999a3f85056ac4b404942967049c69d414e'
ROOT = 2147483649
HALO = 128
MAX_LEVEL = 29
DRAW_CONTEXTS = ('196608', '196609')
NAMES = ('features', 'indices', 'coordinates', 'render', 'additional')
OUTPUTS = ('featureBytes', 'indexBytes', 'coordinateBytes', 'renderBytes', 'additionalBytes')
AC = re.compile(rb'AC\([A-Z0-9_]+\)')


def _sha(raw):
    return hashlib.sha256(raw).hexdigest()


def _pins(buffers):
    return {key: {'bytes': len(raw), 'sha256': _sha(raw)} for key, raw in zip(NAMES, buffers)}


def _pure_ac(value):
    tokens = value.split(b';')
    if tokens and tokens[-1] == b'':
        tokens.pop()
    return bool(tokens) and all(AC.fullmatch(token) is not None for token in tokens)


def _encode_programmes(contexts, query):
    # Same ordered U16-count/U32-query + U32-key/U16-length framing parsed by
    # model.programmes. That existing module has no separate encoder API.
    return struct.pack('<HI', len(contexts), query) + b''.join(
        struct.pack('<IH', int(key), len(value)) + value
        for key, value in contexts.items())


def _root_exclusion(record, indices, coordinates, tile):
    """Stream one part at a time; retain only small bbox/hash proof rows."""
    z, x, y = tile
    denominator = 256 * (1 << z)
    domain = {'denominator': denominator,
              'xmin': (256*x-HALO)*ROOT, 'ymin': (256*y-HALO)*ROOT,
              'xmax': (256*(x+1)+HALO)*ROOT, 'ymax': (256*(y+1)+HALO)*ROOT}
    start = FEATURE.unpack(record)[1]
    need(0 <= start <= len(indices)-4, 'LIVE_INDEX_COUNT_RANGE')
    count = struct.unpack_from('<I', indices, start)[0]
    need(count > 0 and start+4+4*count <= len(indices), 'NONEMPTY_COMPLETE_INDEX_FRAME')
    arena = memoryview(coordinates)
    rows, total_points = [], 0
    for ordinal in range(count):
        word = struct.unpack_from('<I', indices, start+4+4*ordinal)[0]
        usage, offset = word >> 24, word & 0xffffff
        need(usage == 24, 'ONLY_USAGE24_EXTERIOR_WHOLE_RINGS')
        need(offset+4 <= len(coordinates), 'COORDINATE_HEADER_RANGE')
        head = struct.unpack_from('<I', coordinates, offset)[0]
        root, points = head >> 24, head & 0xffffff
        need(root == 1 and points >= 4, 'CLOSED_ROOT1_RING_REQUIRED')
        end = offset+4+8*points
        need(end <= len(coordinates), 'COMPLETE_TYPED_ROOT1_ELEMENT')
        first = struct.unpack_from('<II', coordinates, offset+4)
        last = struct.unpack_from('<II', coordinates, end-8)
        need(first == last, 'EXACT_STORED_CLOSURE_REQUIRED')
        xmin = xmax = first[0]
        ymin = ymax = first[1]
        for px, py in struct.iter_unpack('<II', arena[offset+4:end]):
            xmin, xmax = min(xmin, px), max(xmax, px)
            ymin, ymax = min(ymin, py), max(ymax, py)
        sides = []
        if xmax*denominator < domain['xmin']:
            sides.append('LEFT')
        if xmin*denominator > domain['xmax']:
            sides.append('RIGHT')
        if ymax*denominator < domain['ymin']:
            sides.append('ABOVE')
        if ymin*denominator > domain['ymax']:
            sides.append('BELOW')
        need(sides, 'EVERY_CANONICAL_RING_STRICTLY_OUTSIDE_WHOLE_SOURCE_HALO')
        rows.append({'partOrdinal': ordinal, 'points': points, 'indexWord': word,
                     'typedElementSHA256': _sha(arena[offset:end]),
                     'rootBBox': [xmin, ymin, xmax, ymax], 'strictOutsideSides': sides})
        total_points += points
    return {'format': 'no5-exact-root-whole-source-bbox-exclusion/1',
            'rootScale': ROOT, 'sourceTile': list(tile), 'sourceHaloPixels': HALO,
            'domain': domain, 'parts': rows, 'partCount': count,
            'pointCount': total_points, 'allWholeClosedExteriorRoot1RingsOutside': True,
            'intersectionVerticesCreated': 0, 'integerProjectionInvoked': False}


def prepare_root_bbox_render_correction(features, indices, coordinates, render, additional, *,
        policy, source_tile, owner_uid, expected_input_sha256,
        source_header, expected_header_sha256, source_file_bytes):
    """Return five immutable buffers and an explicit correction receipt.

    Required policy selects corrected Root-space no-fill semantics, not pixel
    equivalence to the original ARM quantization/overflow path. There is no
    default admission, arbitrary view assertion, integer-guard bypass or I/O.
    Actual file/header/node/source-selection binding belongs to the integrator.
    """
    need(type(policy) is str and policy == POLICY, 'EXPLICIT_CORRECTION_POLICY_REQUIRED')
    buffers = (features, indices, coordinates, render, additional)
    need(all(type(raw) is bytes for raw in buffers), 'IMMUTABLE_FIVE_SOURCE_BUFFERS')
    source_pins = _pins(buffers)
    need(type(expected_input_sha256) is dict and set(expected_input_sha256) == set(NAMES)
         and all(type(expected_input_sha256[key]) is str
                 and expected_input_sha256[key] == source_pins[key]['sha256'] for key in NAMES),
         'EXACT_FIVE_INPUT_SHA256_BINDING')
    need(type(source_header) is bytes and type(expected_header_sha256) is str
         and _sha(source_header) == expected_header_sha256, 'EXACT_SOURCE_HEADER_SHA256_BINDING')
    need(type(source_file_bytes) is int, 'EXPLICIT_SOURCE_FILE_EXTENT')
    hc = validate_plain_vector_header(source_header, file_bytes=source_file_bytes)
    need(type(source_tile) in (tuple, list) and len(source_tile) == 3
         and all(type(value) is int for value in source_tile), 'EXPLICIT_INTEGER_SOURCE_TILE')
    z, x, y = source_tile
    need(0 <= z <= MAX_LEVEL and 0 <= x < 1 << z and 0 <= y < 1 << z,
         'SOURCE_TILE_BOUNDS')
    need(type(owner_uid) is int and 0 <= owner_uid < 1 << 64, 'OWNER_UID_U64')
    need(len(features) % FEATURE.size == 0, 'COMPLETE_FEATURE32_ARRAY')
    selected = [i for i, row in enumerate(FEATURE.iter_unpack(features)) if row[0] == owner_uid]
    need(len(selected) == 1, 'EXACTLY_ONE_SELECTED_OWNER')
    ordinal = selected[0]
    record = features[ordinal*32:(ordinal+1)*32]
    fields = FEATURE.unpack(record)
    need(fields[6] == 3, 'KIND3_AREA_REQUIRED')
    contexts, query = model.programmes(render, fields[2])
    need(all(key in contexts and _pure_ac(contexts[key]) for key in DRAW_CONTEXTS),
         'BOTH_NONEMPTY_PURE_AC_CONTEXTS_REQUIRED')
    original, frame_query = metadata.render_frame(metadata.Stream(len(render),
        lambda at, size: render[at:at+size]), fields[2])
    need(frame_query == query, 'MODEL_QUERY_POINTER_EXACT')
    query_raw = metadata.additional_frame(metadata.Stream(len(additional),
        lambda at, size: additional[at:at+size]), query)
    exclusion = _root_exclusion(record, indices, coordinates, (z, x, y))
    changed = {key: b'' if key in DRAW_CONTEXTS else value for key, value in contexts.items()}
    rewritten = _encode_programmes(changed, query)
    new_offset = len(render)
    need(new_offset+len(rewritten) <= 0xffffffff, 'APPENDED_RENDER_U32_EXTENT')
    result_f = bytearray(features)
    struct.pack_into('<I', result_f, ordinal*32+12, new_offset)
    output = (bytes(result_f), indices, coordinates, render+rewritten, additional)
    need(output[0][:ordinal*32+12]+output[0][ordinal*32+16:]
         == features[:ordinal*32+12]+features[ordinal*32+16:], 'ONLY_SELECTED_FI_RO_WORD_CHANGED')
    need(output[1] is indices and output[2] is coordinates and output[4] is additional,
         'COMPLETE_I_C_AO_IDENTITY')
    need(output[3][:len(render)] == render, 'ORIGINAL_RENDER_PREFIX_EXACT')
    actual_contexts, actual_query = model.programmes(output[3], new_offset)
    actual_frame, _ = metadata.render_frame(metadata.Stream(len(output[3]),
        lambda at, size: output[3][at:at+size]), new_offset)
    need(actual_contexts == changed and list(actual_contexts) == list(contexts)
         and actual_query == query and actual_frame == rewritten, 'NORMAL_PROGRAMME_ENCODING_INVERSE')
    receipt = {
        'format': 'no5-explicit-root-bbox-render-correction/1', 'policy': policy,
        'semantics': 'CORRECTED_ROOT_SPACE_RASTER_BEFORE_ARM_QUANTIZATION',
        'sourceTile': [z, x, y], 'ownerUID': owner_uid, 'recordOrdinal': ordinal,
        'inputPins': source_pins, 'outputPins': _pins(output),
        'sourceHeaderPin': {'bytes': len(source_header), 'sha256': _sha(source_header)},
        'sourceFileBytes': source_file_bytes, 'sourceHeaderContract': hc,
        'sourceRenderOffset': fields[2], 'newRenderOffset': new_offset,
        'sourceRecordSHA256': _sha(record), 'sourceRenderFrameSHA256': _sha(original),
        'queryOffset': query, 'queryFrameSHA256': _sha(query_raw),
        'emptyDrawContextKeys': [int(key) for key in DRAW_CONTEXTS],
        'retainedContextKeysInOriginalOrder': [int(k) for k in contexts if k not in DRAW_CONTEXTS],
        'appendedRenderBytes': len(rewritten), 'rootBBoxCertificate': exclusion,
        'canonicalIndicesCoordinatesAdditionalByteExact': True,
        'allUIDsAttributesQueryAndOtherContextsExact': True,
        'onlySelectedFIROWordChanged': True, 'originalRenderPrefixExact': True,
        'nativeIntegerRegionGuardInvokedOrChanged': False,
        'nativePixelParityClaim': False, 'preserveOriginalOverflowArtifactsClaim': False,
        'nativeGlobalQueryEqualityClaim': False, 'hardwareOrTimingClaim': False,
        'normalRasterProducerContractSHA256': PRODUCER_CONTRACT_SHA256,
        'requiresNormalSourceBoundUnrotated256PureACProducer': True,
        'requiresSameFileHeaderFiveBuffersAndActualSourceNodeBinding': True,
        'mathematicalRootExclusionDestinationDomain': {'ancestorSourceRequired': [z, x, y],
            'minimumLevel': z, 'maximumLevel': MAX_LEVEL,
            'destinationPredicate': 'xD>>(L-z)==x AND yD>>(L-z)==y',
            'meaning': 'Only a geometric domain: whole source tile and every descendant 128px halo lie inside the proved source halo; this does not establish native admission.'},
        'actualTreeBindingPending': True, 'treeLinksModified': False,
        'existingAncestorsChildrenAndPriorScopesMustRemainUnchanged': True,
        'actualNativeAdmittedDestinationDomainEstablished': False,
        'nativeTileSourceSelectionBindingPending': True,
        'requiresEveryActiveNativeTileUseBoundSourceScope': True,
        'thisHelperDoesNotWaiveRestoreOrAdmissionRequirements': True,
        'restoreRequirementsUnchangedAndIntegratorOwned': True,
        'finalPhysicalFileExtentAndRelocationValidationRequired': True,
        'restorePolicy': 'Existing ancestors, children, canonical restores and separately qualified prior scopes stay unchanged. The integrator must establish actual native source selection and preserve every existing restore/admission obligation; this helper only proves the mathematical Root exclusion domain.',
        'sidecarEnforcesScope': False, 'globalChartApplyAllowed': False,
        'defaultPipelineChanged': False,
    }
    receipt['preparationSHA256'] = _sha(json.dumps(receipt, sort_keys=True,
        separators=(',', ':')).encode('ascii'))
    return dict(zip(OUTPUTS, output), receipt=receipt)
