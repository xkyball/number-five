"""Portable materialization of the reviewed V15 scale-quilt composer."""
from pathlib import Path
from fractions import Fraction
import collections, hashlib, json, math, struct, time, uuid, sys
try:
    import resource
except ImportError:
    resource=None
from xml.sax.saxutils import quoteattr
from shapely.geometry import Polygon, box
from shapely import from_wkb
from shapely.ops import unary_union
from . import wire, source_winner as sw, point_visibility as pv
from . import render_activation as activation, source_clip as core
from . import ring_clip as ring, extra_clip as extra, ground_masks as mask_continuation
from . import scope_interning, productive_scale_quilt as ZOOM, native_hooks as HOOKS
from . import writer_extension as DETAIL_WRITER, producer_runtime as DETAIL_RUNTIME
from . import postclip_alias as BOUNDARY_ALIAS
from .inputs import read, input_pin, same_content, file_pin as pin, CURRENT
Q=2147483649
F=struct.Struct('<Q5I4B')
def need(v,s):
    if not v: raise ValueError(s)
def sha(b): return hashlib.sha256(b).hexdigest()
def jb(v): return (json.dumps(v,indent=2,sort_keys=True,ensure_ascii=False,allow_nan=False)+'\n').encode()
def intern_scope_json(scope,pool):
    # JSON arrays represent the original tuple/list content identically.
    # Normalize only metadata; reject nonfinite/unsupported JSON values.
    value=json.loads(json.dumps(scope,ensure_ascii=False,allow_nan=False))
    return scope_interning.intern_scope(value,pool)

def bbox_intersects(box,tile,halo=1):
    l,x,y=tile;s=1<<l
    return box[2]*s*256>=(x*256-halo)*Q and box[0]*s*256<=(x*256+256+halo)*Q and box[3]*s*256>=(y*256-halo)*Q and box[1]*s*256<=(y*256+256+halo)*Q
def elements(co,ix,kind):
    n=struct.unpack_from('<I',ix)[0];need(len(ix)==4+4*n,'COMPACT_INDEX');rows=[]
    for word, in struct.iter_unpack('<I',ix[4:]):
        at=word&0xffffff;h=struct.unpack_from('<I',co,at)[0];count=h&0xffffff;stride=12 if kind==4 else 8
        need(h>>24==1 and at+4+count*stride<=len(co),'ROOT1_TYPED_ELEMENT');raw=co[at:at+4+count*stride];xy=list(struct.iter_unpack('<III'if kind==4 else'<II',raw[4:]));box=(min(p[0]for p in xy),min(p[1]for p in xy),max(p[0]for p in xy),max(p[1]for p in xy))
        rows.append((word>>24,raw,box,xy))
    return rows
def compact_owner(features,indices,coordinates,ordinal):
    rec=features[ordinal*32:ordinal*32+32];fields=list(F.unpack(rec));at=fields[1];n=struct.unpack_from('<I',indices,at)[0];refs=struct.unpack_from('<'+'I'*n,indices,at+4);co=bytearray();words=[];aliases={};stride=12 if fields[6]==4 else 8
    for word in refs:
        offset=word&0xffffff
        if offset not in aliases:
            h=struct.unpack_from('<I',coordinates,offset)[0];size=4+(h&0xffffff)*stride;need(h>>24==1 and offset+size<=len(coordinates),'COMPLETE_PREPARED_ELEMENT');aliases[offset]=len(co);co+=coordinates[offset:offset+size]
        words.append((word&0xff000000)|aliases[offset])
    fields[1]=0
    return bytes(co),struct.pack('<I',n)+struct.pack('<'+'I'*n,*words),F.pack(*fields)
def additional_starts(raw):
    exact,starts,changes=wire.rewrite_additional(raw,set(),{})
    need(exact==raw and not changes,'TYPED_ADDITIONAL_FRAME_STARTS')
    return set(starts)

def compose(cfg, prepare_only, execution):
    runtime=CURRENT.get(); OUT=runtime.output/'raw'; HERE=runtime.output
    PREPARE_ONLY=prepare_only; EXECUTION=execution
    V15_PARENT=input_pin('presentationParent')
    DETAIL_INPUT_PIN=input_pin('detailInputs'); REMAINING_DETAIL_INPUT_PIN=input_pin('remainingDetailInputs')
    from . import vertex_projection
    integer_continuation=HOOKS.capturing_quantizer(vertex_projection)
    need(not OUT.exists(),'FRESH_RAW_GENERATION_ONLY')
    started=time.monotonic()
    meta_pin=input_pin('composition'); meta=json.loads(read(meta_pin))
    catalog_pin=input_pin('groundCatalog'); catalog=json.loads(read(catalog_pin))
    geometry_pin=input_pin('groundGeometry'); geom=json.loads(read(geometry_pin))
    point_pin=input_pin('pointInputs'); points=json.loads(read(point_pin))
    fixture_pin=input_pin('coverageFixture'); source_rows=json.loads(read(fixture_pin))['sources']
    cell_ids=meta['cells']; need(cell_ids and len(cell_ids)==len(set(cell_ids)),'UNIQUE_COMPOSITION_CELLS')
    for rows in (catalog['cells'],geom['cells'],points['cells'],source_rows):
        ids=[row['cellId'] for row in rows]
        need(len(ids)==len(set(ids)) and set(ids)==set(cell_ids),'COMPLETE_SOURCE_CELL_BIJECTION')
    sources=[sw.CoverageSource(r['cellId'],r['nativeScale'],tuple(tuple(tuple(p)for p in ring)for ring in r['covered']),tuple(tuple(tuple(p)for p in ring)for ring in r['excluded']))for r in source_rows];winner=ZOOM.PointWinner(sources,sw.own_display_denominator,core)
    cpt_cfg=json.loads(read(input_pin('containerInputs')));common41=next(read(r['data'])for r in cpt_cfg['presentation']if r['id']==65);profiles=HOOKS.point_profiles(pv,common41)
    # This own mask helper is authored/reviewed separately against the frozen
    # geometry kernels. It never repairs invalid source exclusion rings.
    mask_input=dict(policy=ZOOM.POLICY,implementation='country_runtime.ground_masks')
    fill_pin=input_pin('fillAvailability'); fill_manifest=json.loads(read(fill_pin))
    need(fill_manifest['sourceCells']==len(cell_ids) and same_content(fill_manifest['inputGeometryPin'],geometry_pin) and same_content(fill_manifest['sourceGroundCatalog'],catalog_pin),'BOUND_ACTIVATED_FILL_AVAILABILITY')
    need({r['cellId']for r in fill_manifest['cells']}==set(meta['cells']),'FILL_SOURCE_BIJECTION')
    fill_geometries={r['cellId']:from_wkb(read(r['fillAvailabilityWKB']))for r in fill_manifest['cells']}
    fill_bindings={r['cellId']:dict(cellId=r['cellId'],fillAvailabilityWKB=r['fillAvailabilityWKB'],ownersKind3=r['ownersKind3'],kind8OrUnresolvedAdmission=r['kind8OrUnresolvedAdmission'],coverageOrBBoxNotUsedAsFill=r['coverageOrBBoxNotUsedAsFill'],manifestPin=fill_pin)for r in fill_manifest['cells']}
    service=ZOOM.QuiltService(sources,sw.own_display_denominator,core,fill_availability=fill_geometries,fill_availability_bindings=fill_bindings,layer='ground')
    detail_manifest=json.loads(read(DETAIL_INPUT_PIN));detail_cells={c['cellId']:c for c in detail_manifest['cells']};remaining_manifest=json.loads(read(REMAINING_DETAIL_INPUT_PIN));remaining_cells={c['cellId']:c for c in remaining_manifest['cells']};need(set(detail_cells)==set(remaining_cells)==set(cell_ids),'COMPLETE_DETAIL_INPUT_JOIN');detail_owners=[];detail_unresolved=[];detail_by_uid={};source_cell_order={c['cellId']:i for i,c in enumerate(catalog['cells'])}
    overlay_service=ZOOM.QuiltService(sources,sw.own_display_denominator,core,layer='detail')
    source_data={};global_render=bytearray();global_add=bytearray();ground_by_uid={};point_owners=[];optional_text=[]
    for cell in catalog['cells']:
        cid=cell['cellId'];gr=next(r for r in geom['cells']if r['cellId']==cid);pr=next(r for r in points['cells']if r['cellId']==cid)
        gf={k:read(v)for k,v in gr['preparedStreams'].items()};pf={k:read(v)for k,v in pr['preparedStreams'].items()}
        ga=json.loads(read(gr['ownerBindings']))['owners'];pa=json.loads(read(pr['ownerBindings']))['owners'];additional=read(pr['originalAdditional']);render=read(cell['compiledStreams']['render']);feature_catalog=read(cell['compiledStreams']['features'])
        # Original canonical instructions are kept in the source footer. The
        # basic profile defers optional TX/TE area labels while retaining every
        # original fill/line/pattern command; no colour is guessed.
        styles={r['uid']:r for r in ga};frames=activation.decode_frames(render,additional_starts(additional))
        def text_policy(record,frame):
            uid=F.unpack(record)[0]
            if uid not in styles or styles[uid]['className']!='LNDARE' or record[28]!=3:return None
            entries={}
            for key in (0x30000,0x30001):
                original=frame.entries[key];tokens=original.split(b';');kept=[t for t in tokens if not t.startswith((b'TX(',b'TE('))]
                need(kept and any(t.startswith(b'AC(')for t in kept),'LNDARE_BASE_FILL_RETAINED')
                entries[key]=b';'.join(kept)
                if len(kept)!=len(tokens):optional_text.append(dict(cellId=cid,uid=uid,key=key,originalSHA256=sha(original),basicSHA256=sha(entries[key]),deferred=[t.decode('ascii')for t in tokens if t not in kept]))
            return entries
        features,render,text_receipt=activation.activate_features(feature_catalog,render,additional,additional_starts(additional),text_policy)
        # Actual TXTDSC codes come from the original source attribute dictionary.
        attrmap=next(read(r['data']).decode()for r in cpt_cfg['presentation']if r['id']==66)
        codes={int(line.split('\t',2)[0])for line in attrmap.splitlines()if line.split('\t',2)[2]in ('TXTDSC','NTXTDS')}
        add,amap,changes=wire.rewrite_additional(additional,codes,meta['documentMaps'][cid]);render,rs=wire.rebase_render_map(render,len(global_add),amap)
        rbase=len(global_render);abase=len(global_add);global_render+=render;global_add+=add;dm={int(k):v for k,v in meta['dictionaryMaps'][cid].items()}
        # Map each retained original UID to its actual source-ordinal record.
        for r in ga:
            ordinal=r['preparedOwnerOrdinal'];co,ix,record=compact_owner(gf['features'],gf['indices'],gf['coordinates'],ordinal);fields=list(F.unpack(record));source_record=F.unpack_from(features,32*r['sourceOwnerOrdinal']);need(source_record[0]==fields[0],'GROUND_RENDER_UID_JOIN');fields[2]=source_record[2]+rbase;fields[3]=dm[fields[3]];record=F.pack(*fields)
            need(fields[0]not in ground_by_uid,'GLOBAL_GROUND_SOURCE_UID_UNIQUE');ground_by_uid[fields[0]]=dict(cellId=cid,sourceOwnerOrdinal=r['sourceOwnerOrdinal'],uid=fields[0],record=record,coordinates=co,indices=ix,profile=r['geometryProfile'],bbox=tuple(r['wholeOwnerRootBBox']),sourceBinding=r,queryChanges=changes,paintPriority=r['resolution']['displayPriority'],originalSequence=len(ground_by_uid))
        for r in pa:
            ordinal=r['preparedOwnerOrdinal'];co,ix,record=compact_owner(pf['features'],pf['indices'],pf['coordinates'],ordinal);fields=list(F.unpack(record));source_record=F.unpack_from(features,32*r['sourceOwnerOrdinal']);need(source_record[0]==fields[0],'POINT_RENDER_UID_JOIN');fields[2]=source_record[2]+rbase;fields[3]=dm[fields[3]];record=F.pack(*fields)
            point_owners.append(dict(cellId=cid,sourceOwnerOrdinal=r['sourceOwnerOrdinal'],uid=fields[0],record=record,coordinates=co,indices=ix,sourceSCAMIN=r['sourceSCAMIN'],classAcronym=r['classAcronym'],eligible=r.get('pointSemanticsEligible',True),sourceBinding=r))
        source_data[cid]=dict(renderBase=rbase,additionalBase=abase,additionalOffsetMap=amap,textPolicy=text_receipt,documentReferenceChanges=changes)
        for input_set,detail_cell in [('detailAttachment',detail_cells[cid]),('remainingDetailAttachment',remaining_cells[cid])]:
            ready,unresolved,attached=DETAIL_WRITER.append_source_details(detail_cell,source_stream_base=source_data[cid],dictionary_map=meta['dictionaryMaps'][cid],global_render=global_render,wire=wire,source_sequence=source_cell_order[cid])
            detail_owners.extend(ready);detail_unresolved.extend(unresolved);source_data[cid][input_set]=attached
            for owner in ready:
                need(owner['uid']not in detail_by_uid and owner['uid']not in ground_by_uid,'GROUND_DETAIL_SOURCE_UID_UNIQUE');owner['detailInputSet']=input_set;detail_by_uid[owner['uid']]=owner
    need(fill_manifest['ownersKind3']==sum(g['record'][28]==3 for g in ground_by_uid.values()),'COMPLETE_GROUND_FILL_OWNER_COUNT')
    expected=cfg.get('expectedSourceCounts',{})
    for key,actual in [('groundOwners',len(ground_by_uid)),('detailOwners',len(detail_owners)),('pointOwners',len(point_owners)),('sourceCells',len(cell_ids))]:
        need(key not in expected or expected[key]==actual,'EXPECTED_SOURCE_COUNT:'+key)
    point_program_receipts=HOOKS.activate_points_and_ca(dict(globals(),**locals()))
    scamin_summary={}
    for kind in (1,4):
        own=[o for o in point_owners if o['record'][28]==kind];positive=[];unknown=0;zero=0
        for o in own:
            value=o['sourceSCAMIN']
            if type(value)is int or isinstance(value,str)and value.isascii()and value.isdigit():
                value=int(value)
                if 0<value<=0xffffffff:positive.append(value)
                elif value==0:zero+=1
                else:unknown+=1
            else:unknown+=1
        scamin_summary[str(kind)]=dict(sourceOwners=len(own),validPositive=len(positive),minimumPositive=min(positive,default=None),maximumPositive=max(positive,default=None),zeroUnspecified=zero,missingOrInvalid=unknown)
    print(json.dumps(dict(phase='source inputs loaded',groundOwners=len(ground_by_uid),pointOwners=len(point_owners))),flush=True)
    # Only a changed source rank or point eligibility/ownership needs a new
    # epoch. Current source-rank orders stabilize after the finest fallback.
    def rank(level):
        return tuple(s.cell_id for s in service.rank(level)[1])
    ground_epochs=list(service.epoch_levels)
    need(ground_epochs and ground_epochs[0]==0 and all(0<=l<=21 for l in ground_epochs),'DECLARED_SELECTION_EPOCHS')
    detail_states,detail_changed=DETAIL_RUNTIME.visibility_epochs(detail_owners,sw.own_display_denominator)
    # Assemble current point-slot state once per integer scale. Retired points
    # are routed as explicit guards even when the replacing source has no point.
    point_states={};deltas={};retired={};last={}
    for level in range(22):
        current={};state=[]
        for owner in point_owners:
            need(owner['eligible'],'COMPLETE_KIND1_POINT_SEMANTICS_REQUIRED')
            co,ix,record,receipt=HOOKS.filter_scamin_only(owner,level,pv,profiles,winner)
            if record is None:continue
            es=elements(co,ix,record[28]);state.append(dict(owner=owner,record=record,elements=es))
            for role,raw,b,slots in es:
                for slot in slots:current[(owner['uid'],role,tuple(slot))]=slot
        changed=set(last)^set(current);deltas[level]=[current.get(k,last.get(k))for k in changed];retired[level]=[(k,last[k])for k in set(last)-set(current)];point_states[level]=state;last=current
        print(json.dumps(dict(phase='point scale state',level=level,owners=len(state),slots=len(current),changed=len(changed))),flush=True)
    epochs=sorted(set(ground_epochs)|{l for l in range(22)if deltas[l]or detail_changed[l]});nodes={};payload_levels=collections.Counter()
    covered={s.cell_id:unary_union([Polygon(r)for r in s.covered])for s in sources}
    target_tiles={0:{(0,0,0)}};retirement_tiles=collections.defaultdict(list);source_rank_routing={}
    for level in epochs:
        if level==0:continue
        tiles=set()
        # Source-rank inversions only matter where authentic covered domains
        # overlap. These routing regions conservatively retain exclusions; the
        # owner mask itself uses exact excluded-ring decisions.
        if level in ground_epochs:
            previous=rank(level-1);now=rank(level);pa={cid:i for i,cid in enumerate(previous)};na={cid:i for i,cid in enumerate(now)}
            regions=[];inversion_rows=[]
            for i,a in enumerate(meta['cells']):
                for b in meta['cells'][i+1:]:
                    if (pa[a]<pa[b])==(na[a]<na[b]):continue
                    inter=covered[a].intersection(covered[b])
                    inversion_rows.append(dict(sourceA=a,sourceB=b,coverageIntersectionAreaObserved=inter.area,isEmpty=inter.is_empty,geometryType=inter.geom_type,intersectionWKBSHA256=sha(inter.wkb)))
                    if not inter.is_empty and inter.area>0:regions.append(inter)
            changed_region=unary_union(regions)
            source_rank_routing[level]=dict(rankBefore=list(previous),rankAfter=list(now),invertedPairs=inversion_rows,positiveCoverageOverlapPairs=len(regions),sourceCoverageFixture=fixture_pin,higherFillRestrictedToAuthenticCoverage=True,actualFillAvailabilityManifest=fill_pin)
            for polygon in core.parts(changed_region,'Polygon'):
                x0,y0,x1,y1=polygon.bounds;count=1<<level
                for x in range(max(0,int(x0*count//Q)),min(count-1,int(x1*count//Q))+1):
                    for y in range(max(0,int(y0*count//Q)),min(count-1,int(y1*count//Q))+1):
                        if polygon.intersects(box(Fraction(x*Q,count),Fraction(y*Q,count),Fraction((x+1)*Q,count),Fraction((y+1)*Q,count))):tiles.add((level,x,y))
        for slot in deltas[level]:
            x,y=slot[:2];count=1<<level;tx=x*count//Q;ty=y*count//Q
            for dx in (-1,0,1):
                for dy in (-1,0,1):
                    if 0<=tx+dx<count and 0<=ty+dy<count:tiles.add((level,tx+dx,ty+dy))
        for key,slot in retired[level]:
            x,y=slot[:2];count=1<<level;tx=x*count//Q;ty=y*count//Q
            for dx in (-1,0,1):
                for dy in (-1,0,1):
                    if 0<=tx+dx<count and 0<=ty+dy<count:retirement_tiles[(level,tx+dx,ty+dy)].append(dict(uid=key[0],usage=key[1],originalSlot=list(slot),newWinner=winner.choose(slot[:2],level)))
        target_tiles[level]=tiles
        print(json.dumps(dict(phase='sparse target tiles',level=level,tiles=len(tiles))),flush=True)
    # Only profiles referenced by a real nonempty target set are authored.
    # The own quantization budget is measured at authored native Source levels,
    # separately from arbitrarily magnified runtime Dest/ancestor overzoom.
    detail_added_targets=DETAIL_RUNTIME.add_detail_targets(target_tiles,detail_changed,epochs,Q)
    selected_ground_profiles={level:max(e for e in ground_epochs if e<=level)for level,tiles in target_tiles.items()if tiles}
    needed_ground_epochs=sorted(set(selected_ground_profiles.values()))
    ground_authoring_bounds={epoch:max(level for level,g in selected_ground_profiles.items()if g==epoch)for epoch in needed_ground_epochs}
    unmaterialized_ground_epochs=[e for e in ground_epochs if e not in needed_ground_epochs]
    for epoch in unmaterialized_ground_epochs:
        need(not target_tiles.get(epoch)and source_rank_routing[epoch]['positiveCoverageOverlapPairs']==0,'UNUSED_PROFILE_NO_POSITIVE_COVERAGE_INVERSION')
        source_rank_routing[epoch]['unmaterializedProfileDisposition']='NO_AUTHORED_TARGET_NO_POSITIVE_INVERTED_COVERAGE_INTERSECTION'
    print(json.dumps(dict(phase='actual ground profile requirements',needed=needed_ground_epochs,authoringLastLevel=ground_authoring_bounds,unmaterialized=unmaterialized_ground_epochs)),flush=True)
    if PREPARE_ONLY:
        smoke=dict(format='no5-country-runtime-prepare/1',status='PREPARED_SOURCE_AND_SPARSE_LAYOUT_NO_CHART_BUILT',sourceCells=len(sources),groundOwners=len(ground_by_uid),detailOwners=len(detail_owners),pointOwners=len(point_owners),epochs=ground_epochs,neededGroundProfiles=needed_ground_epochs,groundAuthoringLastLevels=ground_authoring_bounds,targets={str(l):len(t)for l,t in target_tiles.items()},selector=service.selection_receipt(None),execution=EXECUTION,countryBuilds=0,nativeCalls=0); target=HERE/'PREPARE_RESULT.json';target.write_bytes(jb(smoke));target.chmod(0o600);return smoke
    ground_profiles={};clip_receipts=[];shared_scope_pool=scope_interning.new_pool()
    for epoch in needed_ground_epochs:
        rows=[]
        epoch_last_level=ground_authoring_bounds[epoch]
        for ground_index,g in enumerate(ground_by_uid.values()):
            if ground_index and ground_index%1000==0:print(json.dumps(dict(phase='ground profile progress',epoch=epoch,processed=ground_index,seconds=time.monotonic()-started)),flush=True)
            try:clipper,scope=service.for_owner(g['cellId'],epoch,g['bbox'],owner_kind=g['record'][28])
            except ValueError as error:
                failure=dict(cellId=g['cellId'],uid=g['uid'],sourceOwnerOrdinal=g['sourceOwnerOrdinal'],geometryProfile=g['profile'],requestedLevel=epoch,ownerBBox=list(g['bbox']),error=str(error),phase='owner mask construction',maskScope=getattr(error,'receipt',None))
                (HERE/'LAST_PREASSEMBLY_FAILURE.json').write_bytes(jb(failure));print(json.dumps({k:v for k,v in failure.items()if k!='maskScope'}),flush=True);raise
            if clipper is None:clip_receipts.append(dict(uid=g['uid'],cellId=g['cellId'],level=epoch,disposition='DOMINATED_OR_DISJOINT',scope=intern_scope_json(scope,shared_scope_pool)));continue
            clipper=integer_continuation.wrap_clipper(clipper,epoch_last_level=epoch_last_level,owner_binding=dict(cellId=g['cellId'],uid=g['uid'],sourceOwnerOrdinal=g['sourceOwnerOrdinal']))
            kw=dict(cell_id=g['cellId'],clipper=clipper,source_tile=(0,0,0),coordinate_format=2)
            try:
                co,ix,record,receipt=integer_continuation.clip_owner(g['coordinates'],g['indices'],g['record'],**kw)
                final_owner_semantics=integer_continuation.validate_final_owner(clipper,co,ix,record,cell_id=g['cellId'])
            except ValueError as error:
                failure=dict(cellId=g['cellId'],uid=g['uid'],sourceOwnerOrdinal=g['sourceOwnerOrdinal'],geometryProfile=g['profile'],requestedLevel=epoch,ownerBBox=list(g['bbox']),error=str(error),maskScope=scope)
                (HERE/'LAST_PREASSEMBLY_FAILURE.json').write_bytes(jb(failure));print(json.dumps(dict(phase='preassembly geometry refusal',**{k:v for k,v in failure.items()if k!='maskScope'})),flush=True);raise
            clip_receipts.append(dict(uid=g['uid'],cellId=g['cellId'],level=epoch,disposition=DETAIL_WRITER.clip_disposition(record,final_owner_semantics),scope=intern_scope_json(scope,shared_scope_pool),geometry=receipt,integerContinuationEvents=clipper.events,finalOwnerMaskValidation=final_owner_semantics))
            if record is None:continue
            es=elements(co,ix,record[28]);bbox=(min(e[2][0]for e in es),min(e[2][1]for e in es),max(e[2][2]for e in es),max(e[2][3]for e in es))
            tri=record[28]==3 and all(role in(8,9)and len(pts)==4 and pts[0]==pts[-1]and len(set(pts[:-1]))==3 for role,raw,eb,pts in es)
            rows.append(dict(uid=g['uid'],cellId=g['cellId'],sourceOwnerOrdinal=g['sourceOwnerOrdinal'],record=record,elements=es,bbox=bbox,triangle=tri,paintPriority=g['paintPriority'],originalSequence=g['originalSequence']))
        rows.sort(key=lambda r:r['paintPriority']) # Python stable: original owner order for true DPRI ties.
        ground_profiles[epoch]=rows;print(json.dumps(dict(phase='ground profile compiled',epoch=epoch,owners=len(rows),coordinates=sum(len(raw)for r in rows for role,raw,b,pts in r['elements']))),flush=True)
    # Regional coordinate arenas share complete immutable Root1 elements among
    # descendants. Native low24 references always address that one arena.
    detail_profiles={};detail_clip_receipts=[]
    postclip_kwargs={}
    alias_cfg=cfg.get('postclipBoundaryRunAliases')
    if alias_cfg is not None:
        need(type(alias_cfg)is dict and set(alias_cfg)=={'policy','limits'} and
             alias_cfg['policy']==BOUNDARY_ALIAS.POLICY and type(alias_cfg['limits'])is dict,
             'EXPLICIT_POSTCLIP_ALIAS_POLICY_AND_LIMITS')
        alias_limits=BOUNDARY_ALIAS.Limits(**alias_cfg['limits']);alias_limits.check()
        # Existing in-RAM RO/AO only. This new callback must preserve exact bytes,
        # not re-encode a synthetic frame or infer a programme from a class name.
        ro_snapshot=bytes(global_render)
        ro_frames=activation.decode_frames(ro_snapshot,additional_starts(bytes(global_add)))
        ro_starts=sorted(ro_frames)
        ro_ends={at:ro_starts[i+1]if i+1<len(ro_starts)else len(ro_snapshot)
                 for i,at in enumerate(ro_starts)}
        postclip_kwargs=dict(postclip_alias=BOUNDARY_ALIAS.separate_boundary_runs,
            postclip_policy=alias_cfg['policy'],postclip_limits=alias_limits,
            render_stream=ro_snapshot,render_frame_ends=ro_ends)
    for epoch in needed_ground_epochs:
        detail_scene,receipts=DETAIL_WRITER.compile_overlay_profile(detail_owners,requested_level=epoch,authoring_last_level=ground_authoring_bounds[epoch],coverage_service=overlay_service,quantizer=integer_continuation,visibility=lambda owner,level:dict(eligible=True,policy='DECLARED_SCALE_QUILT_THEN_PER_LEVEL_SOURCE133',sourceSCAMIN=owner['sourceSCAMIN']),**postclip_kwargs)
        detail_profiles[epoch]=detail_scene
        compact=HOOKS.compact_receipts(receipts)
        # The ordinary compactor keeps a digest, not arbitrary new fields.
        # Preserve this bounded reference-only ledger without WKB/CO bodies.
        for full,small in zip(receipts,compact):
            if 'postclipAliasLedger'in full:
                small['postclipAliasLedger']=full['postclipAliasLedger']
        detail_clip_receipts.extend(compact)
        print(json.dumps(dict(phase='zoom detail profile compiled',epoch=epoch,owners=len(detail_scene))),flush=True)
    arenas={};index_pool=bytearray();node_reports=[];empty_guards=[]
    for level in epochs:
        if not target_tiles[level]:continue
        ground_epoch=max(l for l in ground_epochs if l<=level);scene=ground_profiles[ground_epoch];state=point_states[level]
        # Owner AABBs form a coarse L8 candidate index; individual triangles and
        # SOUNDG slots are still tested against each actual native target tile.
        region_ground=collections.defaultdict(list)
        for g in scene:
            x0,y0,x1,y1=g['bbox'];pad=(Q+65535)//65536
            for x in range(max(0,(x0-pad)*256//Q),min(255,(x1+pad)*256//Q)+1):
                for y in range(max(0,(y0-pad)*256//Q),min(255,(y1+pad)*256//Q)+1):region_ground[(x,y)].append(g)
        detail_visible=[r for r in detail_profiles[ground_epoch] if detail_states[level][r['uid']]];region_details=DETAIL_RUNTIME.region_index(detail_visible,Q)
        point_bins=collections.defaultdict(list)
        for item in state:
            owner=item['owner'];bytile=collections.defaultdict(list);size=1<<level
            for role,raw,eb,slots in item['elements']:
                for slot in slots:
                    x,y=slot[:2];tx=x*size//Q;ty=y*size//Q
                    for dx in (-1,0,1):
                        for dy in (-1,0,1):
                            tile=(level,tx+dx,ty+dy)
                            if tile in target_tiles[level]:bytile[tile].append((role,slot))
            for tile,slots in bytile.items():point_bins[tile].append((item,slots))
        for tile in sorted(target_tiles[level]):
            _,tx,ty=tile;region=(tx>>(level-8),ty>>(level-8))if level>=8 else None;akey=(ground_epoch,region)
            arena=arenas.setdefault(akey,dict(name='arena:'+str(ground_epoch)+':'+('global'if region is None else str(region[0])+':'+str(region[1])),data=bytearray(),dedup={}))
            candidates=scene if region is None else region_ground.get(region,());features=bytearray();owner_rows=[]
            def emit(record,cell,ordinal,es,role,alias_ledger=None):
                if not es:return
                fields=list(F.unpack(record));mode=service.mode_for_tile(cell,*tile)
                if mode is None:return
                if mode=='SOFT':
                    if fields[6]!=3:return
                    fields[2]=HOOKS.area_only_frame(fields[2],global_render,activation)
                fields[1]=len(index_pool);refs=[]
                for usage,raw in es:
                    if raw not in arena['dedup']:arena['dedup'][raw]=len(arena['data']);arena['data']+=raw
                    off=arena['dedup'][raw];need(off<=0xffffff and len(arena['data'])<1<<24,'REGIONAL_COORDINATE_ARENA_LOW24');refs.append((usage<<24)|off)
                index_pool.extend(struct.pack('<I',len(refs))+struct.pack('<'+'I'*len(refs),*refs))
                packed=F.pack(*fields)
                emitted_alias=None
                if alias_ledger is not None and alias_ledger['status']in('APPLIED','ELIGIBLE_NOOP'):
                    emitted_alias=BOUNDARY_ALIAS.join_serialized_owner(alias_ledger,
                        pre_emit_record=record,final_record=packed,index_pool=index_pool,
                        arena=arena['data'],arena_name=arena['name'],tile=tile)
                features.extend(packed)
                row=dict(cellId=cell,sourceOwnerOrdinal=ordinal,uid=fields[0],kind=fields[6],role=role,indexOffset=fields[1],renderOffset=fields[2],dictionaryIndex=fields[3],indexOccurrences=len(refs),paintPriority=ground_by_uid[fields[0]]['paintPriority']if role=='SOURCE_WINNER_GROUND'else detail_by_uid[fields[0]]['paintPriority']if role=='SOURCE_WINNER_DETAIL'else None,originalGroundSequence=ground_by_uid[fields[0]]['originalSequence']if role=='SOURCE_WINNER_GROUND'else None,originalDetailSequence=detail_by_uid[fields[0]]['originalSequence']if role=='SOURCE_WINNER_DETAIL'else None)
                if emitted_alias is not None:row['postclipAliasEmittedBinding']=emitted_alias
                owner_rows.append(row)
            detail_candidates=detail_visible if region is None else region_details.get(region,())
            for g in DETAIL_WRITER.merge_draw_order(candidates,detail_candidates,source_cell_order):
                if not bbox_intersects(g['bbox'],tile):continue
                selected=[(usage,raw)for usage,raw,eb,pts in g['elements']if not g['triangle']or bbox_intersects(eb,tile)]
                emit(g['record'],g['cellId'],g['sourceOwnerOrdinal'],selected,g.get('role','SOURCE_WINNER_GROUND'),g.get('postclipAliasLedger'))
            for item,slots in point_bins.get(tile,()):
                owner=item['owner'];kind=item['record'][28];group=collections.defaultdict(list)
                tile_winner=winner.for_tile(*tile)
                for usage,slot in slots:
                    if tile_winner.choose(slot[:2],level)==owner['cellId']:group[usage].append(slot)
                es=[]
                for usage,ss in group.items():es.append((usage,struct.pack('<I',len(ss)|1<<24)+b''.join(struct.pack('<III'if kind==4 else'<II',*s)for s in ss)))
                emit(item['record'],owner['cellId'],owner['sourceOwnerOrdinal'],es,'POSITION_WINNER_SCAMIN_POINT_OR_SOUNDING')
            if not features:
                previous_tile=next((t for l in range(level-1,-1,-1)for t in[(l,tx>>(level-l),ty>>(level-l))]if t in nodes),None)
                empty_guards.append(dict(tile=list(tile),currentGroundAndPointRecords=0,previousSelectedTile=list(previous_tile)if previous_tile else None,reason='EXPLICIT_SELECTION_OR_SCAMIN_RETIREMENT_NATIVE_FLAGS3_EMPTY_VECTOR'))
            ids=[r['uid']for r in owner_rows];need(len(ids)==len(set(ids)),'TILE_SOURCE_UID_UNIQUE')
            name='tile:'+':'.join(map(str,tile));nodes[tile]=dict(name=name,features=bytes(features),arena=arena['name']);payload_levels[level]+=1
            node_reports.append(dict(tile=list(tile),groundProfileLevel=ground_epoch,coordinateArena=arena['name'],featuresBytes=len(features),featuresSHA256=sha(features),owners=owner_rows,
                ownDenominatorExact=[sw.own_display_denominator(level).numerator,sw.own_display_denominator(level).denominator],guardOrDeltaTarget=True,explicitEmptyLODGuard=not bool(features)))
        print(json.dumps(dict(phase='payload epoch materialized',level=level,nodes=payload_levels[level],arenas=len(arenas))),flush=True)
    need((0,0,0)in nodes,'FILLED_COUNTRY_ROOT')
    authored=[]
    for arena in arenas.values():authored.append(wire.AuthoredBlock(arena['name'],0x1102,0,b'',bytes(arena['data'])))
    authored.extend([wire.AuthoredBlock('global-indices',0x1101,0,b'',bytes(index_pool)),wire.AuthoredBlock('global-render',0x1103,0,b'',bytes(global_render)),wire.AuthoredBlock('global-additional',0x1104,0,b'',bytes(global_add))])
    payload_tiles=set(nodes)
    for level,x,y in tuple(nodes):
        while level:level-=1;x//=2;y//=2;nodes.setdefault((level,x,y),dict(name='tile:'+':'.join(map(str,(level,x,y))),features=b'',arena=None))
    for tile,n in sorted(nodes.items()):
        l,x,y=tile;refs=[(8*i,nodes[(l+1,2*x+(i&1),2*y+((i>>1)&1))]['name']if(l+1,2*x+(i&1),2*y+((i>>1)&1))in nodes else None)for i in range(4)]
        fl=3 if tile in payload_tiles else 1
        if fl==3:refs.extend([(32,'global-indices'),(40,n['arena']),(48,'global-render'),(56,'global-additional')])
        authored.append(wire.AuthoredBlock(n['name'],0x6000+l,fl,bytes(64 if fl==3 else 32),n['features'],None,tuple(refs)))
    # Clone original56 source XMLs/ONEcoverage and all ordinary dictionaries,
    # style/atlas bodies and original query/footer attachments by symbolic graph.
    runtime.assert_unchanged('presentationParent')
    chart_path=Path(V15_PARENT['path']);before=chart_path.stat();need(before.st_size==V15_PARENT['bytes'],'IMMUTABLE_BASE_SIZE');ranges=[];oldblocks={}
    with chart_path.open('rb')as f:
        def rawblock(at):
            if at in oldblocks:return oldblocks[at]
            f.seek(at);h=f.read(16);bid,flags,size,nxt=struct.unpack('<HHIQ',h);need(flags in wire.PREFIX and size>=wire.PREFIX[flags],'BASE_RAW_BLOCK');data=f.read(size);need(len(data)==size,'BASE_RANGE_READ');k=wire.PREFIX[flags];b=dict(id=bid,flags=flags,prefix=data[:k],body=data[k:],next=nxt);oldblocks[at]=b;ranges.append(dict(offset=at,bytes=16+size,sha256=sha(h+data)));return b
        f.seek(0);header=bytearray(f.read(240));need(header[:8]==b'\x01RayRX5\0','BASE_HEADER')
        starts={slot:struct.unpack_from('<Q',header,slot)[0]for slot in(144,168,184,200)};required=set()
        def collect(at):
            if not at or at in required:return
            required.add(at);b=rawblock(at);collect(b['next'])
            if b['flags']in(1,2,3):
                for i in range(0,len(b['prefix']),8):collect(struct.unpack_from('<Q',b['prefix'],i)[0])
        for at in starts.values():collect(at)
        names={at:'preserved:'+str(at)for at in required}
        for at in sorted(required):
            b=oldblocks[at];refs=tuple((i,names[q]if q else None)for i in range(0,len(b['prefix']),8)for q in[struct.unpack_from('<Q',b['prefix'],i)[0]])if b['flags']in(1,2,3)else()
            authored.append(wire.AuthoredBlock(names[at],b['id'],b['flags'],bytes(len(b['prefix']))if refs else b['prefix'],b['body'],names[b['next']]if b['next']else None,refs))
    after=chart_path.stat();sf=('st_dev','st_ino','st_size','st_mtime_ns','st_ctime_ns');need(all(getattr(before,k)==getattr(after,k)for k in sf),'BASE_RANGES_STAT_STABLE')
    own_uuid=uuid.uuid5(uuid.NAMESPACE_URL,'no5-portable-scale-quilt-native-v15:'+EXECUTION['transformedExecutableSHA256']+':'+EXECUTION['buildInputs']['sha256']);header[56:72]=own_uuid.bytes_le
    title=('<?xml version="1.0" encoding="utf8"?><rx5><chart_info name='+quoteattr(cfg.get('title','No5 Country'))+'><edition date='+quoteattr(cfg.get('editionDate',''))+'/></chart_info></rx5>').encode('utf8');authored.append(wire.AuthoredBlock('chart-info',2,0,b'',title))
    raw,offsets=wire.assemble(bytes(header),authored,{136:'chart-info',144:names[starts[144]],160:nodes[(0,0,0)]['name'],168:names[starts[168]],184:names[starts[184]],200:names[starts[200]]})
    need(len(raw)<1<<32,'FILE_U32_TRANSPORT_BOUND');OUT.mkdir(parents=True,mode=0o700);chart=OUT/'COUNTRY.rx5';chart.write_bytes(raw);chart.chmod(0o600)
    result=dict(format='no5-current56-ground-source-scale-country/1',status='ACTUAL_COUNTRY_BUILT_PERSISTED_READER_PENDING',chart=pin(chart),parentComposition=meta_pin,parentKnownFullChart=meta['chart'],groundCatalog=catalog_pin,groundGeometry=geometry_pin,pointInputs=point_pin,maskHelper=mask_input,
        sourceCells=meta['cells'],sourceOwnerCatalogCount=len(ground_by_uid),ownChartUUID=str(own_uuid),sourceScalePolicy=ZOOM.POLICY,pointSCAMINPolicy=pv.POLICY,groundFillAvailability=fill_pin,groundAvailabilityBindings=fill_bindings,groundMaskHelper=pin(Path(mask_continuation.__file__)),integerQuantizer=pin(Path(integer_continuation.__file__)),groundEpochs=needed_ground_epochs,plannedSourceRankEpochs=ground_epochs,groundAuthoringLastLevels=ground_authoring_bounds,unmaterializedGroundEpochs=unmaterialized_ground_epochs,sourceRankRoutingDiagnostics=source_rank_routing,allPayloadEpochs=sorted(payload_levels),plannedPayloadEpochs=epochs,
        nativeGraphOffsets=offsets,payloadNodes=node_reports,coordinateArenas=[dict(name=a['name'],bytes=len(a['data']),sha256=sha(a['data']))for a in arenas.values()],treeNodes=len(nodes),payloadNodesByLevel=dict(payload_levels),pureQuadNodes=len(nodes)-len(payload_tiles),emptyVectorNodes=len(empty_guards),explicitEmptyLODGuards=empty_guards,
        dictionaryMaps=meta['dictionaryMaps'],documentMaps=meta['documentMaps'],preservedAttachments=meta['documents'],fullOriginal56QueryFooterPreserved=False,ordinarySourceQueriesPreserved=True,ownFullReproductionFooterOmitted=True,
        originalGroundOwnerUIDClassFlagsWord20Preserved=True,sharedClipScopePool=shared_scope_pool,sharedClipScopePoolSHA256=scope_interning.pool_sha(shared_scope_pool),scopeInterningModule=pin(Path(scope_interning.__file__)),sourceOwnerClipReceipts=HOOKS.compact_receipts(clip_receipts),sourceStreamBases=source_data,optionalAreaTextDeferred=optional_text,
        pointSceneSummary=[dict(level=l,eligibleOwners=len(point_states[l]),deltaSlots=len(deltas[l]))for l in range(22)],pointSCAMINInputSummary=scamin_summary,ownDenominatorStabilizedAfterLevel21=True,sourceInfoMetadataInheritedFromV6=True,metadataLevelsAreNotRuntimeDrawFilters=True,
        globalStreamPins=dict(indices=dict(bytes=len(index_pool),sha256=sha(index_pool)),render=dict(bytes=len(global_render),sha256=sha(global_render)),additional=dict(bytes=len(global_add),sha256=sha(global_add))),
        ownQuantizationPolicyScope='KIND3_ROOT_U32_GRID1_VALID_OUTPUT_FILL_KIND2_UNCHANGED',ownQuantizationMaximumAuthoringReferencePixels=None,maximumAppliesOnlyToPrimaryBoundaryDisposition=False,exceptionGlobalMaxPixelDeviationClaim=False,ownQuantizationBoundaryMethod='GEOS_ROOT_GRID1_VALID_OUTPUT_FILL_WITH_SERIALIZED_ROLE_ROUNDTRIP',displayGeometryRoundtripPolicy=dict(
            format='no5-own-authored-ground-roundtrip-policy/3',primaryMaximumAuthoringReferencePixels=None,
            kind3FillPolicy='own-root-u32-grid1-valid-output-fill/1',
            serializedRolePolicy='own-root-u32-grid1-serialized-role-roundtrip/1',
            gridSpacingRootUnits=1,precisionMode='valid_output',
            actualAreaAndTopologyDifferencesReported=True,wholePixelModelIdentityClaim=False,
            quantizedEmptyDisposition=DETAIL_WRITER.QUANTIZED_EMPTY_DISPOSITION,
            quantizedEmptyRequiresSourceAndAreaLossReceipt=True,
            zeroAreaLeakClaim=False,nativeQRegionAntialiasingOrA97EqualityClaim=False,
            kind2LinePolicy='EXISTING_LINE_CONTINUATION_UNCHANGED_SEPARATE_SCOPE',
            originalFullQueriesAndPointXYZPreserved=True),noArbitraryRuntimeOverzoomPixelErrorClaim=True,noSourceOrField20ScaleUnitGuess=True,pointStylePacketsAdded=False,kind8AreaPacketsAdded=False,originalV6Changed=False,currentRegistryChanged=False,sourceCalls=0,nativeCalls=0,mediaActions=0,
        baseRangePins=ranges,resource=dict(seconds=time.monotonic()-started,rssBytes=(resource.getrusage(resource.RUSAGE_SELF).ru_maxrss*(1 if sys.platform=='darwin' else 1024)) if resource else None),code=pin(Path(__file__)))
    result.update(detailInputs=DETAIL_INPUT_PIN,remainingDetailInputs=REMAINING_DETAIL_INPUT_PIN,zoomSelectorCode=pin(Path(ZOOM.__file__)),zoomSelectorReceipt=service.selection_receipt(None),pointProgramReceipts=point_program_receipts,selectionBakedIntoGeometry=True,detailSourceOwnerCatalogCount=len(detail_owners),detailPreservedKind8Owners=len(detail_unresolved),detailOwnerClipReceipts=detail_clip_receipts,detailVisibilityPolicy=DETAIL_RUNTIME.VISIBILITY_POLICY,detailAddedTargets=detail_added_targets,detailSceneSummary=[dict(level=l,eligibleSourceOwners=sum(detail_states[l].values()),deltaSourceOwners=len(detail_changed[l]))for l in range(22)],producerExtension=EXECUTION)
    target=OUT/'COMPOSITION.json';target.write_bytes(jb(result));target.chmod(0o600)
    owner_index=OUT/'SOURCE_OWNER_INDEX.json';owner_index.write_bytes(jb(dict(
        ground=[dict(cellId=g['cellId'],uid=g['uid'],sourceOwnerOrdinal=g['sourceOwnerOrdinal'],kind=g['record'][28],className=g['sourceBinding']['className'],canonicalRecord32Hex=g['record'].hex(),paintPriority=g['paintPriority'],originalGroundSequence=g['originalSequence'])for g in ground_by_uid.values()],
        details=[dict(cellId=o['cellId'],uid=o['uid'],sourceOwnerOrdinal=o['sourceOwnerOrdinal'],kind=o['record'][28],className=o['className'],canonicalRecord32Hex=o['record'].hex(),paintPriority=o['paintPriority'],originalSourceSequence=o['originalSequence'],sourceSCAMIN=o['sourceSCAMIN'],originalAdditionalFrameSHA256=o['sourceBinding']['additionalFrameSHA256'],originalRecord32Hex=o['sourceBinding']['originalRecord32Hex'],canonicalInstructionHex=o['sourceBinding']['resolution']['instruction'].encode('ascii').hex(),oneProgramForBothModes=True,canonicalInstructionContextKeys=[0x30000,0x30001],sourceFinalState=DETAIL_RUNTIME.visibility(o,0,sw.own_display_denominator)['actualFinalState'],detailInputSet=o['detailInputSet'],sourceStyleBinding=o['sourceBinding']['resolution']['selected'])for o in detail_owners],
        points=[dict(cellId=o['cellId'],uid=o['uid'],sourceOwnerOrdinal=o['sourceOwnerOrdinal'],kind=o['record'][28],className=o['classAcronym'],canonicalRecord32Hex=o['record'].hex(),sourceSCAMIN=o['sourceSCAMIN'])for o in point_owners])));owner_index.chmod(0o600)
    interface=OUT/'ZOOM_REVIEW_INTERFACE.json';interface.write_bytes(jb(dict(chart=result['chart'],composition=pin(target),sourceOwnerIndex=pin(owner_index),sourceCoverageFixture=fixture_pin,sources=[dict(cellId=s.cell_id,nativeScale=s.native_scale)for s in sources],sourceScalePolicy=ZOOM.POLICY,pointSCAMINPolicy=pv.POLICY,groundFillAvailability=fill_pin,groundAvailabilityBindings=fill_bindings,groundMaskHelper=result['groundMaskHelper'],integerQuantizer=result['integerQuantizer'],groundEpochs=result['groundEpochs'],plannedSourceRankEpochs=ground_epochs,groundAuthoringLastLevels=ground_authoring_bounds,sourceRankRoutingDiagnostics=source_rank_routing,allPayloadEpochs=result['allPayloadEpochs'],plannedPayloadEpochs=epochs,ownQuantizationPolicyScope=result['ownQuantizationPolicyScope'],ownQuantizationMaximumAuthoringReferencePixels=result['ownQuantizationMaximumAuthoringReferencePixels'],maximumAppliesOnlyToPrimaryBoundaryDisposition=result['maximumAppliesOnlyToPrimaryBoundaryDisposition'],exceptionGlobalMaxPixelDeviationClaim=False,ownQuantizationBoundaryMethod=result['ownQuantizationBoundaryMethod'],displayGeometryRoundtripPolicy=result['displayGeometryRoundtripPolicy'],scopeInterningModule=result['scopeInterningModule'],noArbitraryRuntimeOverzoomPixelErrorClaim=True,pointSCAMINInputSummary=scamin_summary,ownDenominatorStabilizedAfterLevel21=True,emptyClearingGuards=len(empty_guards),metadataLevelsAreNotRuntimeDrawFilters=True)));interface.chmod(0o600)
    print(json.dumps(dict(chart=result['chart'],composition=pin(target),reviewInterface=pin(interface),payloads=len(payload_tiles),nodes=len(nodes),resource=result['resource'])),flush=True)

    return result
