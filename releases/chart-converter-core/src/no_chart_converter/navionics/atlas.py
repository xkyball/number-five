"""Own contiguous multi-map Plain writer, with a bounded streaming sink.

Supplied local maps are emitted as given. This raw writer cannot establish cell
completeness, nautical attributes, hole semantics, portrayal or native admission.
The 16-map and 64-MiB bounds are explicit own software budgets, not format limits.
"""
from copy import deepcopy
from dataclasses import dataclass
import hashlib
import struct

from .batch import _envelope, _integer, plan_payload_width, plan_sha256
from .layered_batch import (LayeredBatchSpec, _chunks, layered_batch_from_spec,
                            plan_layered_batch)
from .typed_batch import (TypedBatchSpec, typed_batch_from_spec, plan_typed_batch,
                          _chunks as _typed_chunks)
from .generic_batch import (GenericAttributedBatchSpec, generic_batch_from_spec,
                            _plan_generic, _text_resources, _text_values,
                            _chunks as _generic_chunks)
from .metadata import encode_plain_metadata_record
from .writer import RawMapHeader, encode_map_header

MAX_ATLAS_MAPS = 16
# Explicit host work profile; not an Original/native acceptance guarantee.
ATLAS_256_WORK_PROFILE = 'own_atlas256_host_work_profile_v1'
MAX_ATLAS_256_MAPS = 256
ATLAS_2048_WORK_PROFILE = 'own_atlas2048_host_work_profile_v1'
MAX_ATLAS_2048_MAPS = 2048
MAX_ATLAS_BYTES = 64 * 1024 * 1024
SINK_CHUNK_BYTES = 64 * 1024


@dataclass(frozen=True)
class AtlasSpec:
    maps: tuple[LayeredBatchSpec | TypedBatchSpec | GenericAttributedBatchSpec, ...]
    work_profile: str | None = None

    def __post_init__(self):
        if self.work_profile is not None and (type(self.work_profile) is not str or
                self.work_profile not in (ATLAS_256_WORK_PROFILE, ATLAS_2048_WORK_PROFILE)):
            raise ValueError('explicit supported Atlas work profile required')
        maximum = (MAX_ATLAS_MAPS if self.work_profile is None else
                   MAX_ATLAS_256_MAPS if self.work_profile == ATLAS_256_WORK_PROFILE else MAX_ATLAS_2048_MAPS)
        if not isinstance(self.maps, (tuple, list)) or not 1 <= len(self.maps) <= maximum:
            raise ValueError('own Atlas scope requires 1..16 maps' if self.work_profile is None
                             else 'own explicit Atlas256 scope requires 1..256 maps' if self.work_profile == ATLAS_256_WORK_PROFILE
                             else 'own explicit Atlas2048 scope requires 1..2048 maps')
        if any(type(m) not in (LayeredBatchSpec, TypedBatchSpec, GenericAttributedBatchSpec) for m in self.maps):
            raise ValueError('explicit raw, finite typed or generic attributed maps required')
        object.__setattr__(self, 'maps', tuple(self.maps))
        dates = {(m.date_text_1, m.date_text_2) for m in self.maps}
        if len(dates) != 1:
            raise ValueError('all Atlas maps require identical metadata dates')
        ids = [f.source_id for m in self.maps for l in m.layers for f in l.features]
        if len(set(ids)) != len(ids):
            raise ValueError('source_id must be unique across Atlas maps')


def atlas_from_spec(value):
    if type(value) is not dict or set(value) != {'format', 'maps'} or value['format'] != 'no5-own-plain-atlas/1':
        raise ValueError('exact own Plain Atlas schema required')
    if type(value['maps']) is not list:
        raise ValueError('maps must be a finite JSON list')
    maps = []
    for m in value['maps']:
        if type(m) is not dict:
            raise ValueError('explicit map document required')
        kind = m.get('format')
        if kind == 'no5-own-plain-layered-feature-batch/1':
            maps.append(layered_batch_from_spec(m))
        elif kind == 'no5-own-plain-typed-attribute-batch/1':
            maps.append(typed_batch_from_spec(m))
        elif kind == 'no5-own-plain-generic-attribute-batch/1':
            maps.append(generic_batch_from_spec(m))
        else:
            raise ValueError('unsupported explicit Atlas map format')
    return AtlasSpec(tuple(maps))


def atlas_work_profile_from_spec(value):
    """Separate opt-in schema; the original Atlas JSON parser stays unchanged."""
    if (type(value) is not dict or set(value) != {'format', 'maps', 'work_profile'} or
            value['format'] != 'no5-own-plain-atlas-work-profile/1' or
            type(value['work_profile']) is not str or value['work_profile'] != ATLAS_256_WORK_PROFILE or
            type(value['maps']) is not list or not 1 <= len(value['maps']) <= MAX_ATLAS_256_MAPS):
        raise ValueError('exact explicit Atlas256 work-profile schema required')
    # Reuse every existing map parser/validator, never install another registry.
    maps = [atlas_from_spec({'format': 'no5-own-plain-atlas/1', 'maps': [m]}).maps[0]
            for m in value['maps']]
    return AtlasSpec(tuple(maps), work_profile=value['work_profile'])


def atlas_2048_work_profile_from_spec(value):
    """Separate opt-in schema; legacy16 and Atlas256 parsers stay unchanged."""
    if (type(value) is not dict or set(value) != {'format', 'maps', 'work_profile'} or
            value['format'] != 'no5-own-plain-atlas2048-work-profile/1' or
            type(value['work_profile']) is not str or value['work_profile'] != ATLAS_2048_WORK_PROFILE or
            type(value['maps']) is not list or not 1 <= len(value['maps']) <= MAX_ATLAS_2048_MAPS):
        raise ValueError('exact explicit Atlas2048 work-profile schema required')
    maps = [atlas_from_spec({'format': 'no5-own-plain-atlas/1', 'maps': [m]}).maps[0]
            for m in value['maps']]
    return AtlasSpec(tuple(maps), work_profile=value['work_profile'])


def _rebase(local, delta, header_offset):
    row = deepcopy(local)
    row['headerOffset'] = header_offset
    for key in ('bankOffset', 'firstLayerOffset', 'layerEndOffset'):
        if row[key] is not None:
            row[key] += delta
    row['totalBytes'] += delta
    for layer in row['layers']:
        for key in ('recordOffset', 'firstFeatureOffset', 'featureEndOffset'):
            layer[key] += delta
    for feature in row['features']:
        for key in ('layerOffset', 'recordOffset'):
            feature[key] += delta
        for part in feature['parts']:
            part['streamOffset'] += delta
            _integer(part['streamOffset'], 0, (1 << 30) - 1, 'rebased direct stream low30')
    return row


def plan_atlas(spec: AtlasSpec, *, max_output_bytes=4096):
    """Plan all offsets before output; maps share one contiguous header group.

    Only the Kind17 group is a candidate encrypted overlay span. Bank/layer/
    feature/vector bytes remain Plain because native *_from_file skips overlay.
    Large-file and multi-map native qualification remain separate from planning.
    """
    if type(spec) is not AtlasSpec:
        raise TypeError('spec must be AtlasSpec')
    _integer(max_output_bytes, 1, MAX_ATLAS_BYTES, 'own Atlas output budget')
    count = len(spec.maps)
    directory = plan_payload_width(4 * count)
    header_start = 258 + 2 + directory['lengthWidth'] + directory['payloadBytes']
    cursor = header_start + 55 * count
    generic = [i for i, m in enumerate(spec.maps) if type(m) is GenericAttributedBatchSpec]
    strings = [value for m in spec.maps if type(m) is GenericAttributedBatchSpec
               for value in _text_values(m)]
    resources, text_ids, text_rows = _text_resources(strings, cursor)
    resource_offset = cursor
    resource_bytes = sum(map(len, resources))
    cursor += resource_bytes
    bounds = [min(m.header.raw_bounds[0] for m in spec.maps),
              min(m.header.raw_bounds[1] for m in spec.maps),
              max(m.header.raw_bounds[2] for m in spec.maps),
              max(m.header.raw_bounds[3] for m in spec.maps)]
    maps = []
    for ordinal, m in enumerate(spec.maps):
        if type(m) is GenericAttributedBatchSpec:
            local = _plan_generic(m, max_output_bytes, shared_text_ids=text_ids)
        else:
            planner = plan_typed_batch if type(m) is TypedBatchSpec else plan_layered_batch
            local = planner(m, max_output_bytes=max_output_bytes)
        body_start = cursor
        body = _rebase(local, body_start - 320, header_start + 55 * ordinal)
        cursor += local['totalBytes'] - 320
        maps.append(dict(ordinal=ordinal, headerOffset=body['headerOffset'],
            bodyOffset=body_start, bodyBytes=local['totalBytes'] - 320,
            localPlanSHA256=plan_sha256(local), bodyPlan=body))
    _integer(cursor, 0, 0xffffffff, 'Atlas U32 extent')
    if cursor > max_output_bytes:
        raise ValueError('OUTPUT_BUDGET_EXCEEDED: Atlas extent exceeds explicit own budget')
    first = spec.maps[0]
    # Validate the final outer metadata union and extent through the public encoder.
    encode_plain_metadata_record(declared_file_bytes=cursor, raw_bounds=bounds,
        date_text_1=first.date_text_1, date_text_2=first.date_text_2)
    plan = dict(format='no5-own-plain-atlas-plan/1', totalBytes=cursor,
        outputBudgetBytes=max_output_bytes, mapCount=count, metadataBytes=258,
        directoryOffset=258, directoryPayloadBytes=directory['payloadBytes'],
        directoryLengthWidth=directory['lengthWidth'],
        headerGroup=dict(firstOffset=header_start, lastOffset=header_start+55*count-1,
                         bytes=55*count, recordKind=17, recordBytes=55, recordCount=count),
        metadataRawBounds=bounds, dateText1=first.date_text_1, dateText2=first.date_text_2,
        maps=maps, softwareProfile=dict(maxMaps=MAX_ATLAS_MAPS if spec.work_profile is None else MAX_ATLAS_256_MAPS if spec.work_profile == ATLAS_256_WORK_PROFILE else MAX_ATLAS_2048_MAPS, maxFileBytes=MAX_ATLAS_BYTES,
            budgetIsFormatLimit=False, sinkChunkBytes=SINK_CHUNK_BYTES),
        physicalLayout='SOURCE_BOUND_CONTIGUOUS_ABSOLUTE_OFFSETS',
        encryptedOverlayCandidate='CONTIGUOUS_KIND17_GROUP_ONLY_LOWER_BODIES_PLAIN',
        nativeQualification='PENDING_MULTI_MAP_AND_SCALED_NATIVE_READBACK',
        semanticAttributes='UNSUPPORTED_EMPTY_ONLY',
        inputCompleteness='EXTERNAL_INPUT_LEDGER_REQUIRED_NOT_INFERRED_FROM_SUPPLIED_MAPS',
        geographicDatumPortrayal='UNQUALIFIED')
    if spec.work_profile is not None:
        plan['softwareProfile']['workProfile'] = spec.work_profile
    typed = [i for i, m in enumerate(spec.maps) if type(m) is TypedBatchSpec]
    if typed:
        plan['typedMapOrdinals'] = typed
        plan['semanticAttributes'] = 'EXPLICIT_NATIVE_E_M_SUBSET_AND_EMPTY_RAW_MAPS'
    if generic:
        plan['genericMapOrdinals'] = generic
        plan['semanticAttributes'] = 'EXPLICIT_GENERIC_STORAGE_CLASS_PRESENCE_AND_BMP_TEXT'
        plan['textResources'] = dict(offset=resource_offset if resources else None,
            bytes=resource_bytes, entries=text_rows,
            resourceSHA256=hashlib.sha256(b''.join(resources)).hexdigest())
    return plan


def _atlas_chunks(spec, plan):
    yield encode_plain_metadata_record(declared_file_bytes=plan['totalBytes'],
        raw_bounds=plan['metadataRawBounds'], date_text_1=plan['dateText1'],
        date_text_2=plan['dateText2'])
    yield _envelope(4, plan['directoryPayloadBytes'], plan['directoryLengthWidth'])
    yield b''.join(struct.pack('<I', m['headerOffset']) for m in plan['maps'])
    for m, row in zip(spec.maps, plan['maps']):
        h = m.header
        yield encode_map_header(RawMapHeader(h.flags, h.raw_bounds, h.raw_grid,
            h.raw_levels, row['bodyPlan']['firstLayerOffset']))
    if plan.get('textResources', {}).get('bytes'):
        strings = [value for m in spec.maps if type(m) is GenericAttributedBatchSpec
                   for value in _text_values(m)]
        resources, _, _ = _text_resources(strings, plan['textResources']['offset'])
        yield from resources
    for m, row in zip(spec.maps, plan['maps']):
        emitter = _generic_chunks if type(m) is GenericAttributedBatchSpec else _typed_chunks if type(m) is TypedBatchSpec else _chunks
        chunks = iter(emitter(m, row['bodyPlan']))
        # Existing generator's first three chunks are metadata, directory, header.
        for _ in range(3):
            next(chunks)
        yield from chunks


def _coalesced(chunks):
    pending = bytearray()
    for chunk in chunks:
        at = 0
        while at < len(chunk):
            take = min(SINK_CHUNK_BYTES-len(pending), len(chunk)-at)
            pending.extend(chunk[at:at+take])
            at += take
            if len(pending) == SINK_CHUNK_BYTES:
                yield bytes(pending)
                pending.clear()
    if pending:
        yield bytes(pending)


def write_atlas(spec: AtlasSpec, sink, *, expected_plan_sha256, max_output_bytes=4096):
    """Write from byte0; validate exact plan before first write, retry short writes.

    Output buffering is at most 64KiB. No seek/flush/close/native calls occur.
    An IO error leaves a partial sink; successful return alone does not establish
    persisted-file readback, native acceptance or source-cell completeness.
    """
    plan = plan_atlas(spec, max_output_bytes=max_output_bytes)
    digest = plan_sha256(plan)
    if type(expected_plan_sha256) is not str or expected_plan_sha256 != digest:
        raise ValueError('EXACT_EXPECTED_PLAN_SHA256_REQUIRED')
    if not callable(getattr(sink, 'write', None)):
        raise TypeError('sink must provide write(bytes)->int')
    sha, written = hashlib.sha256(), 0
    for chunk in _coalesced(_atlas_chunks(spec, plan)):
        at = 0
        while at < len(chunk):
            count = sink.write(chunk[at:])
            if type(count) is not int or not 0 < count <= len(chunk)-at:
                raise OSError('SINK_WRITE_DID_NOT_MAKE_VALID_PROGRESS')
            sha.update(chunk[at:at+count])
            at += count
            written += count
    if written != plan['totalBytes']:
        raise RuntimeError('emission extent differs from Atlas plan')
    return dict(format='no5-own-plain-atlas-emission/1', bytesWritten=written,
        emittedStreamSHA256=sha.hexdigest(), planSHA256=digest, plan=plan,
        nativeReaderExecuted=False, persistedFileReadback=False)
