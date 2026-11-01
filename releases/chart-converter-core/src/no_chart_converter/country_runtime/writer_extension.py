from .inputs import read

"""Nonpoint detail attachment for the existing country writer.

The caller supplies the existing SourceCoverage service, unchanged quantizer,
explicit visibility policy, global streams and source dictionary/document maps.
This module never adds detail availability to the ground-fill winner masks.
"""

import hashlib, json, struct

from pathlib import Path

F = struct.Struct('<Q5I4B')

Q = 2147483649

def need(ok, label):
    if not ok: raise ValueError(label)

def sha(raw): return hashlib.sha256(raw).hexdigest()

def compact_owner(features, indices, coordinates, ordinal):
    record=features[32*ordinal:32*(ordinal+1)];need(len(record)==32,'DETAIL_RECORD32')
    fields=list(F.unpack(record));at=fields[1];need(at+4<=len(indices),'DETAIL_INDEX_START')
    n=struct.unpack_from('<I',indices,at)[0];need(n and at+4+4*n<=len(indices),'COMPLETE_DETAIL_INDEX')
    co=bytearray();words=[];aliases={}
    for word, in struct.iter_unpack('<I',indices[at+4:at+4+4*n]):
        at=word&0xffffff;need(at+4<=len(coordinates),'DETAIL_ELEMENT_START')
        h=struct.unpack_from('<I',coordinates,at)[0];size=4+8*(h&0xffffff)
        need(h>>24==1 and h&0xffffff and at+size<=len(coordinates),'COMPLETE_ROOT1_DETAIL_ELEMENT')
        if at not in aliases:aliases[at]=len(co);co+=coordinates[at:at+size]
        words.append((word&0xff000000)|aliases[at])
    fields[1]=0
    return bytes(co),struct.pack('<I',n)+struct.pack('<'+'I'*n,*words),F.pack(*fields)

def append_source_details(cell, *, source_stream_base, dictionary_map,
                          global_render, wire, source_sequence):
    """Append genuine frames and bind all prepared nonpoint source owners.

    Query frames already occupy the source's existing global Additional range.
    Their AO/document-name rebases are supplied by that original writer join.
    No additional stream is copied, fabricated or silently substituted here.
    """
    need(type(global_render)is bytearray,'MUTABLE_EXISTING_RENDER_STREAM')
    streams={k:read(v)for k,v in cell['preparedStreams'].items()}
    bindings=json.loads(read(cell['ownerBindings']))['owners']
    need(len(streams['features'])==32*len(bindings),'COMPLETE_DETAIL_OWNER_BIJECTION')
    amap={int(k):v for k,v in source_stream_base['additionalOffsetMap'].items()}
    render,starts=wire.rebase_render_map(streams['render'],source_stream_base['additionalBase'],amap)
    rbase=len(global_render);global_render+=render;dm={int(k):v for k,v in dictionary_map.items()}
    ready=[];unresolved=[]
    for row in bindings:
        co,ix,record=compact_owner(streams['features'],streams['indices'],streams['coordinates'],row['preparedOwnerOrdinal'])
        fields=list(F.unpack(record));need(fields[0]==row['uid'] and fields[6]==row['kind'],'DETAIL_SOURCE_UID_KIND_JOIN')
        need(fields[2]in starts,'DETAIL_RENDER_FRAME_START')
        fields[2]+=rbase;fields[3]=dm[fields[3]];record=F.pack(*fields)
        owner=dict(cellId=cell['cellId'],uid=row['uid'],sourceOwnerOrdinal=row['sourceOwnerOrdinal'],
            record=record,coordinates=co,indices=ix,bbox=tuple(row['wholeOwnerRootBBox']),
            profile=row['geometryProfile'],className=row['className'],sourceSCAMIN=row['sourceSCAMIN'],
            originalSequence=(source_sequence,row['sourceOwnerOrdinal']),sourceBinding=row,
            role='SOURCE_WINNER_DETAIL',groundAvailabilityContributor=False)
        if row['disposition']=='READY_GENUINE_SOURCE_NONPOINT_PROGRAM':
            need(fields[6]in(2,3) and row['resolution']['status'] in ('resolved_saved_source_presentation','resolved_fresh_cache_presentation'),'ONLY_EXPLICITLY_RESOLVED_DETAIL_COMMANDS')
            if row['resolution']['status']=='resolved_fresh_cache_presentation':
                need(row['featureSourceDigest']==row['resolution']['featureSourceDigest'] and bool(row['resolution']['profileSHA256']),'FRESH_DETAIL_EXACT_FEATURE_AND_PROFILE_BINDING')
            need(row['geometryProfile']!='UNBOUND_PRIMITIVE_ROLE_REQUIRES_EXACT_CONSUMER','UNBOUND_DETAIL_GEOMETRY')
            owner['paintPriority']=row['resolution']['displayPriority'];ready.append(owner)
        else:unresolved.append(owner)
    return ready,unresolved,dict(cellId=cell['cellId'],detailRenderBase=rbase,
        detailRenderBytes=len(render),detailRenderSHA256=sha(render),
        originalAdditional=cell['originalAdditional'],additionalBase=source_stream_base['additionalBase'],
        readyOwners=len(ready),unresolvedPreservedOwners=len(unresolved),
        sourceQueriesAndSCAMINPreserved=True,groundFillAvailabilityChanged=False)

def owner_elements(co,ix,kind):
    need(kind in(2,3),'DETAIL_DRAW_KIND2_OR3')
    rows=[];n=struct.unpack_from('<I',ix)[0];need(len(ix)==4+4*n,'EXACT_DETAIL_INDEX_ARRAY')
    for word, in struct.iter_unpack('<I',ix[4:]):
        at=word&0xffffff;h=struct.unpack_from('<I',co,at)[0];size=4+8*(h&0xffffff)
        need(h>>24==1 and at+size<=len(co),'ROOT1_DETAIL_SERIALIZED_ELEMENT')
        raw=co[at:at+size];pts=list(struct.iter_unpack('<II',raw[4:]));bbox=(min(x for x,y in pts),min(y for x,y in pts),max(x for x,y in pts),max(y for x,y in pts))
        rows.append((word>>24,raw,bbox,pts))
    return rows

QUANTIZED_EMPTY_DISPOSITION='EMPTY_DECLARED_ROOT_GRID_FILL_ORIGINAL_SOURCE_RETAINED'

def clip_disposition(record, final_validation):
    """Keep a quantized-empty clip distinct from source-winner exclusion."""
    if record is not None:return 'RETAINED'
    if final_validation.get('disposition')==QUANTIZED_EMPTY_DISPOSITION:
        return QUANTIZED_EMPTY_DISPOSITION
    return 'EMPTY_SOURCE_WINNER'

def compile_overlay_profile(owners, *, requested_level, authoring_last_level,
                            coverage_service, quantizer, visibility,
                            postclip_alias=None, postclip_policy=None,
                            postclip_limits=None, render_stream=None,
                            render_frame_ends=None):
    """Use authentic CellCoverage, never the ground availability substitute.

    visibility(owner, requested_level) must return {eligible, policy, ...}.
    The caller must bind actual SourceSCAMIN meaning and routing epochs; this
    function cannot infer a vendor scale unit from Word20 or an XML leaf level.
    """
    scene=[];receipts=[]
    for owner in owners:
        visible=visibility(owner,requested_level)
        need(type(visible)is dict and type(visible.get('eligible'))is bool and visible.get('policy'),'DECLARED_DETAIL_VISIBILITY_POLICY_REQUIRED')
        if not visible['eligible']:
            receipts.append(dict(uid=owner['uid'],cellId=owner['cellId'],level=requested_level,disposition='EXPLICIT_DETAIL_VISIBILITY_POLICY',visibility=visible));continue
        clipper,scope=coverage_service.for_owner(owner['cellId'],requested_level,owner['bbox'],owner_kind=owner['record'][28])
        base=dict(uid=owner['uid'],cellId=owner['cellId'],sourceOwnerOrdinal=owner['sourceOwnerOrdinal'],level=requested_level,scope=scope,visibility=visible)
        if clipper is None:receipts.append(dict(base,disposition='DOMINATED_OR_DISJOINT'));continue
        wrapped=quantizer.wrap_clipper(clipper,epoch_last_level=authoring_last_level,
            owner_binding=dict(cellId=owner['cellId'],uid=owner['uid'],sourceOwnerOrdinal=owner['sourceOwnerOrdinal']))
        co,ix,record,receipt=quantizer.clip_owner(owner['coordinates'],owner['indices'],owner['record'],cell_id=owner['cellId'],clipper=wrapped,source_tile=(0,0,0),coordinate_format=2)
        final=quantizer.validate_final_owner(wrapped,co,ix,record,cell_id=owner['cellId'])
        audit=dict(base,disposition=clip_disposition(record,final),geometry=receipt,integerContinuationEvents=wrapped.events,finalOwnerMaskValidation=final)
        receipts.append(audit)
        if record is None:continue
        elements=owner_elements(co,ix,record[28])
        alias_ledger=None
        if postclip_alias is not None:
            need(type(render_stream)is bytes and type(render_frame_ends)is dict,
                 'EXPLICIT_ACTUAL_POSTCLIP_RENDER_STREAM')
            ro_at=F.unpack(record)[2]
            need(ro_at in render_frame_ends and type(render_frame_ends[ro_at])is int and
                 0<=ro_at<render_frame_ends[ro_at]<=len(render_stream),
                 'ACTUAL_FINAL_FI_RO_FRAME_START_AND_END')
            actual_ro=dict(offset=ro_at,raw=render_stream[ro_at:render_frame_ends[ro_at]])
            context=dict(stage='AFTER_CLIP_AND_FINAL_VALIDATION',
                validationPassed=final.get('passed'),requestedLevel=requested_level,
                authoringLastLevel=authoring_last_level)
            elements,alias_ledger=postclip_alias(
                dict(owner,record=record,postclipContext=context),elements,
                actual_ro,owner['sourceBinding'],
                policy=postclip_policy,limits=postclip_limits)
            audit['postclipAliasLedger']=alias_ledger
        bbox=(min(r[2][0]for r in elements),min(r[2][1]for r in elements),max(r[2][2]for r in elements),max(r[2][3]for r in elements))
        triangle=record[28]==3 and all(r[0]in(8,9) and len(r[3])==4 and r[3][0]==r[3][-1] and len(set(r[3][:-1]))==3 for r in elements)
        row=dict(owner,record=record,elements=elements,bbox=bbox,triangle=triangle)
        if alias_ledger is not None:row['postclipAliasLedger']=alias_ledger
        scene.append(row)
    scene.sort(key=lambda r:(r['paintPriority'],r['originalSequence']))
    return scene,receipts

def merge_draw_order(ground,details,source_cell_order):
    """Original Source DPRI ASC; stable actual source/ordinal ties."""
    rows=list(ground)+list(details)
    return sorted(rows,key=lambda r:(r['paintPriority'],source_cell_order[r['cellId']],r['sourceOwnerOrdinal']))

def bbox_intersects(bbox,tile,halo=1):
    level,x,y=tile;scale=1<<level
    return bbox[2]*scale*256>=(x*256-halo)*Q and bbox[0]*scale*256<=(x*256+256+halo)*Q and bbox[3]*scale*256>=(y*256-halo)*Q and bbox[1]*scale*256<=(y*256+256+halo)*Q

def emit_detail_tile(scene,tile,*,arena,index_pool,feature_bytes):
    """Write actual records/indices into existing country stream arenas.

    Whole rings and lines retain their complete owner association. Only proven
    independent closed triangle elements use the established per-tile AABB test.
    """
    need(type(index_pool)is bytearray and type(feature_bytes)is bytearray,'EXISTING_WRITER_MUTABLE_STREAMS')
    rows=[]
    for owner in scene:
        if not bbox_intersects(owner['bbox'],tile):continue
        selected=[(usage,raw)for usage,raw,bbox,pts in owner['elements']if not owner['triangle']or bbox_intersects(bbox,tile)]
        if not selected:continue
        fields=list(F.unpack(owner['record']));fields[1]=len(index_pool);words=[]
        for usage,raw in selected:
            if raw not in arena['dedup']:arena['dedup'][raw]=len(arena['data']);arena['data']+=raw
            offset=arena['dedup'][raw];need(offset<=0xffffff and len(arena['data'])<1<<24,'REGIONAL_COORDINATE_ARENA_LOW24')
            words.append((usage<<24)|offset)
        index_pool+=struct.pack('<I',len(words))+struct.pack('<'+'I'*len(words),*words);feature_bytes+=F.pack(*fields)
        rows.append(dict(cellId=owner['cellId'],sourceOwnerOrdinal=owner['sourceOwnerOrdinal'],uid=fields[0],kind=fields[6],
            role='SOURCE_WINNER_DETAIL',indexOffset=fields[1],renderOffset=fields[2],dictionaryIndex=fields[3],
            indexOccurrences=len(words),paintPriority=owner['paintPriority'],originalSourceSequence=owner['originalSequence']))
    return rows
