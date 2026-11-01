"""Own opt-in exact-L21 LC geographic correction, not a native pixel proof.

The separately pinned finite cell/placement theorem is a required premise.
This module verifies its binding to unchanged line geometry and prepares normal
FI/RO buffers. It neither invents a support radius nor clips/restarts LC phase.
"""
from __future__ import annotations
import hashlib
import json
import struct

from no_chart_converter.rx5 import header, metadata, model, wire
from no_chart_converter.rx5.locality_empty import Domain
from no_chart_converter.rx5.root_bbox_render_correction import _encode_programmes

ROOT = 2147483649
POLICY = 'EXPLICIT_EXACT21_LC_GEOGRAPHIC_PROGRAMME_CORRECTION/1'
NAMES = ('features', 'indices', 'coordinates', 'render', 'additional')
OUTPUTS = ('featureBytes', 'indexBytes', 'coordinateBytes', 'renderBytes', 'additionalBytes')
DRAW = ('196608', '196609')
CELLS = ('HODATA01', 'MARSYS51')
need = wire.need


def sha(raw):
    return hashlib.sha256(raw).hexdigest()


def pins(buffers):
    return {key:dict(bytes=len(v), sha256=sha(v)) for key,v in zip(NAMES,buffers)}


def _pin(p):
    need(type(p) is dict and type(p.get('bytes')) is int and p['bytes'] > 0 and
         type(p.get('sha256')) is str and len(p['sha256']) == 64 and
         all(c in '0123456789abcdef' for c in p['sha256']), 'COMPLETE_EXTERNAL_THEOREM_PIN')


def _trace(record, indices, coordinates, tile):
    """Bind every stored directed edge, including part-to-part connectors."""
    z,x,y = tile
    den = 256*(1 << z)
    d = Domain(den, (256*x-128)*ROOT, (256*y-128)*ROOT,
               (256*(x+1)+128)*ROOT, (256*(y+1)+128)*ROOT)
    parts = list(model.parts(record, indices, coordinates))
    need(parts, 'NONEMPTY_COMPLETE_LINE')
    previous = None
    edge_hash = hashlib.sha256()
    edges = points = 0
    rows = []
    for p in parts:
        need(p['rootFlag'] == 1 and p['usage'] == 16 and p['pointCount'] >= 2,
             'ONLY_ROOT1_FORWARD_UNMASKED_LINE_PARTS')
        for point in p['points']:
            need(all(0 <= v <= ROOT for v in point), 'ROOT_INTEGER_DOMAIN')
            if previous is not None:
                need(not d.hits(previous, point), 'DIRECTED_CENTERLINE_CONTACTS_128_HALO')
                edge_hash.update(struct.pack('<4I', *previous, *point))
                edges += 1
            previous = point
            points += 1
        rows.append(dict(partOrdinal=p['partOrdinal'], usage=p['usage'], rootFlag=p['rootFlag'],
                         points=p['pointCount'], typedElementSHA256=sha(p['raw'])))
    return dict(parts=rows, partCount=len(parts), points=points, edges=edges,
                connectorEdges=len(parts)-1, implicitClosingEdgesCreated=0,
                concatenatedDirectedEdgesSHA256=edge_hash.hexdigest(),
                everyOriginalCenterlineEdgeOutside128Halo=True,
                centerlineExclusionAloneDoesNotProveLCInkExclusion=True)


def prepare_scoped_lc_render_correction(features, indices, coordinates, render, additional, *,
        source_tile, owner_uids, policy=None, expected_input_sha256,
        source_header, expected_header_sha256, source_file_bytes,
        placement_evidence_bytes, expected_placement_evidence_sha256):
    """Return exact metadata-only prospective correction after theorem binding.

    Cell ink and repeated F32 placement are NOT inferred from centerline BBoxes.
    The caller's finite source-bound theorem must cover the same complete trace.
    Later stroke/raster intermediate parity is deliberately not a premise made
    true by this API; the native functional comparison remains separately open.
    """
    need(policy == POLICY, 'EXPLICIT_CORRECTION_POLICY_DEFAULT_OFF')
    buffers = (features, indices, coordinates, render, additional)
    need(all(type(v) is bytes for v in buffers), 'IMMUTABLE_FIVE_BUFFERS')
    source_pins = pins(buffers)
    need(type(expected_input_sha256) is dict and set(expected_input_sha256) == set(NAMES) and
         all(expected_input_sha256[k] == source_pins[k]['sha256'] for k in NAMES), 'FIVE_INPUT_SHA_BINDING')
    need(type(source_header) is bytes and sha(source_header) == expected_header_sha256, 'EXACT_HEADER_SHA')
    hc = header.validate_plain_vector_header(source_header, file_bytes=source_file_bytes)
    need(type(source_tile) in (list,tuple) and len(source_tile) == 3 and
         all(type(v) is int for v in source_tile), 'EXPLICIT_TILE')
    z,x,y = source_tile
    need(z == 21 and 0 <= x < 1 << z and 0 <= y < 1 << z, 'EXACT21_ONLY')
    need(type(owner_uids) in (list,tuple) and 1 <= len(owner_uids) <= 4 and
         all(type(u) is int and 0 <= u < 1 << 64 for u in owner_uids) and
         len(set(owner_uids)) == len(owner_uids), 'EXPLICIT_ONE_TO_FOUR_DISTINCT_UIDS')
    need(type(placement_evidence_bytes) is bytes and
         sha(placement_evidence_bytes) == expected_placement_evidence_sha256, 'FINITE_PLACEMENT_EVIDENCE_SHA')
    evidence = json.loads(placement_evidence_bytes)
    need(evidence.get('format') == 'no5-exact21-lc-bound-placement-premise/1' and
         evidence.get('status') == 'SOURCE_BOUND_FINITE_PLACEMENT_PREMISE' and
         evidence.get('sourceTile') == list(source_tile) and evidence.get('qualifiedLevels') == [21] and
         evidence.get('protectedHaloPixels') == 128, 'EXACT_SOURCE21_PLACEMENT_PREMISE')
    need(evidence.get('inputGeometrySHA256') == {k:source_pins[k]['sha256'] for k in ('indices','coordinates')}
         and evidence.get('sourceHeaderSHA256') == expected_header_sha256, 'EVIDENCE_BOUND_TO_GEOMETRY_AND_HEADER')
    for key in ('currentCellContract', 'actualQtEvaluation', 'finitePlacementContract'):
        _pin(evidence.get(key))
    need(evidence.get('fullRasterIntermediateClosure') is False and
         evidence.get('nativePixelProof') is False and evidence.get('support200000IsUniformTube') is False,
         'PLACEMENT_MUST_NOT_MASQUERADE_AS_FULL_RASTER_PARITY_OR_UNIFORM_TUBE')
    proof_rows = evidence.get('owners')
    need(type(proof_rows) is list and len(proof_rows) == len(owner_uids), 'COMPLETE_OWNER_EVIDENCE')
    proofs = {}
    for p in proof_rows:
        need(type(p) is dict and type(p.get('uid')) is int and p['uid'] not in proofs,
             'UNIQUE_OWNER_EVIDENCE')
        proofs[p['uid']] = p
    need(set(proofs) == set(owner_uids), 'EXACT_SELECTED_OWNER_EVIDENCE_SET')
    need(len(features) % 32 == 0, 'WHOLE_FEATURE32')
    fi, tail, rows = bytearray(features), bytearray(), []
    selected = set(owner_uids)
    found = set()
    for ordinal, f in enumerate(wire.FEATURE.iter_unpack(features)):
        if f[0] not in selected:
            continue
        need(f[0] not in found, 'ONE_SELECTED_FI_PER_UID')
        found.add(f[0])
        p = proofs[f[0]]
        record = features[ordinal*32:(ordinal+1)*32]
        need(f[6] == 2 and p.get('recordOrdinal') == ordinal and p.get('classCode') == f[5]
             and p.get('recordSHA256') == sha(record) and p.get('cell') in CELLS,
             'EXACT_KIND2_OWNER_CLASS_RECORD_CELL')
        contexts, query = model.programmes(render, f[2])
        recipe = ('LC('+p['cell']+');').encode('ascii')
        need(all(contexts.get(k) == recipe for k in DRAW), 'BOTH_EXACT_SINGLE_LC_PROGRAMMES')
        trace = _trace(record, indices, coordinates, tuple(source_tile))
        need(p.get('trace') == trace, 'EVERY_EDGE_AND_TYPED_PART_BOUND_TO_PLACEMENT_PREMISE')
        methods = p.get('placementMethods')
        need(type(methods) is dict and set(methods) == {'orientationFreeStartBall','transferredZeroYAxis'} and
             all(type(v) is int and v >= 0 for v in methods.values()) and sum(methods.values()) == trace['edges'],
             'ALL_EDGES_COVERED_BY_TWO_FINITE_PLACEMENT_CASES')
        _pin(p.get('placementEdgeRows'))
        old_frame, old_query = metadata.render_frame(metadata.Stream(len(render), lambda a,n:render[a:a+n]), f[2])
        query_frame = metadata.additional_frame(metadata.Stream(len(additional), lambda a,n:additional[a:a+n]), query)
        need(old_query == query, 'QUERY_REFERENCE_EXACT')
        changed = {k:b'' if k in DRAW else v for k,v in contexts.items()}
        frame = _encode_programmes(changed, query)
        new_offset = len(render)+len(tail)
        need(new_offset+len(frame) <= 0xffffffff, 'APPENDED_RO_U32')
        struct.pack_into('<I', fi, ordinal*32+12, new_offset)
        tail += frame
        inv, inv_query = model.programmes(frame,0)
        need(inv == changed and list(inv) == list(contexts) and inv_query == query, 'NORMAL_FRAME_INVERSE')
        rows.append(dict(uid=f[0],recordOrdinal=ordinal,classCode=f[5],cell=p['cell'],
            oldROOffset=f[2],newROOffset=new_offset,sourceRecordSHA256=sha(record),
            sourceFrameSHA256=sha(old_frame),queryOffset=query,queryFrameSHA256=sha(query_frame),
            newFrameBytes=len(frame),newFrameSHA256=sha(frame),newFrameHex=frame.hex(),
            allOtherContextKeysAndPayloadsExact=True, trace=trace, placementMethods=methods))
    need(found == selected, 'ALL_SELECTED_OWNERS_EXIST')
    output = (bytes(fi),indices,coordinates,render+bytes(tail),additional)
    inverse = bytearray(output[0])
    for r in rows:
        at = r['recordOrdinal']*32+12
        inverse[at:at+4] = features[at:at+4]
    need(bytes(inverse) == features and output[3][:len(render)] == render, 'FI_RO_PREFIX_FULL_INVERSE')
    receipt = dict(format='no5-exact21-lc-geographic-programme-correction/1',policy=POLICY,
        status='PREPARED_SOURCE_BOUND_GEOGRAPHIC_CORRECTION_NATIVE_RASTER_PENDING',
        sourceTile=list(source_tile),qualifiedLevels=[21],haloPixels=128,
        inputPins=source_pins,outputPins=pins(output),sourceHeaderSHA256=expected_header_sha256,headerContract=hc,
        placementEvidencePin=dict(bytes=len(placement_evidence_bytes),sha256=sha(placement_evidence_bytes)),
        owners=rows,changedFIROWords=len(rows),appendedROBytes=len(tail),
        indexCoordinatesAndAdditionalByteExact=True,fullDirectedGeometryQueriesAndAnchorsRetained=True,
        noLCPhaseRestartOrGeometryClip=True,noFeatureAddedOrRemoved=True,
        noUnknownCellAdmission=True,selectedContextIDs=[196608,196609],allOtherContextsExact=True,
        priorAreaClipsAndSeaCorrectionPreserved=True,fullRasterIntermediateClosure=False,nativePixelProof=False,
        noUniform200000EdgeRadiusAssumption=True,defaultPipelineChanged=False,globalChartApplyAllowed=False,
        sidecarEnforcesScope=False,existingAncestorsMustRemainUnchanged=True,
        requiresActualFourFullLCLevel22Restores=True,
        requiredRestoreChildren=[dict(slot=s,tile=[22,2*x+(s&1),2*y+(s>>1)],flags=3,
            allOriginalLCAndFullBaselineGeometryRequired=True) for s in range(4)],
        actualSourceSelectionAndRestoreBindingPending=True,performanceClaim=False,OriginalCalls=0,chartWrites=0)
    return dict(zip(OUTPUTS,output),receipt=receipt)
