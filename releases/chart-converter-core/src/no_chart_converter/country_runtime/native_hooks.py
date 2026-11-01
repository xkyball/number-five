from . import source_presentation_resolver as resolver, localize_metadata as local, point_packets as packet
from .inputs import read

"""Native Point/CA programme and sparse routing hooks for the V15 compiler."""

from pathlib import Path

import collections, hashlib, json, re, struct, sys

CFG=None

FRAME_CACHE={}

AREA_FRAME_CACHE={}

F=struct.Struct('<Q5I4B')

def need(ok,why):
    if not ok:raise ValueError(why)

def sha(raw):return hashlib.sha256(raw).hexdigest()

def configure(cfg):
    global CFG,FRAME_CACHE,AREA_FRAME_CACHE
    CFG=cfg
    FRAME_CACHE={};AREA_FRAME_CACHE={}

def point_profiles(pv,common):
    """Fresh compiled profiles bind per owner; legacy tables keep their parser."""
    tables=json.loads(read(CFG['sourcePresentationIndex']))
    if tables.get('format')=='no5-cache-country-source-presentation/1':
        return {}
    return pv.common_point_profiles(common.decode())

def fresh_visibility_profiles(row):
    entries=[]
    for selected in row['resolution']['contextSelections'].values():
        need(type(selected['displayPriority']) is int and selected['category'] in
             ('DISPLAYBASE','STANDARD','OTHER'),'FRESH_POINT_EXPLICIT_VISIBILITY_PROFILE')
        entries.append(dict(lupId=selected['sourceRCID'],priority=selected['displayPriority'],
            category=selected['category'],conditionalProcedure=False,
            family='FRESH_CLOSED_PROFILE_CONTEXT'))
    need(bool(entries),'FRESH_POINT_VISIBILITY_CONTEXTS_REQUIRED')
    return {row['className']:entries}

def activate_fresh_cache_points(ns,tables,frames,stream):
    """Validate freshly compiled exact packets; do not run saved NXSTYL lookup.

    This separate input dialect binds already compiled programme bytes to the
    fresh feature digest and complete query actually carried by the country.
    It does not label a generic rule as an original saved-owner presentation.
    """
    rows=tables['pointProgramBindings']
    indexed={int(row['uid']):row for row in rows}
    owners=[owner for owner in ns['point_owners'] if owner['record'][28]==1]
    need(len(indexed)==len(rows) and set(indexed)=={owner['uid']for owner in owners},
         'FRESH_POINT_PROGRAMME_COMPLETE_OWNER_BIJECTION')
    FRAME_CACHE.update(frames)
    receipts=[]
    for owner in owners:
        fields=F.unpack(owner['record']);row=indexed[fields[0]]
        need((row['cellId'],row['sourceOwnerOrdinal'],row['className'])==
             (owner['cellId'],owner['sourceOwnerOrdinal'],owner['classAcronym']),
             'FRESH_POINT_PROGRAMME_SOURCE_OWNER_JOIN')
        need(row['featureSourceDigest']==owner['sourceBinding']['featureSourceDigest'],
             'FRESH_POINT_PROGRAMME_EXACT_IR_FEATURE')
        owner['freshPointVisibilityProfiles']=fresh_visibility_profiles(row)
        frame=frames[fields[2]]
        query=local.additional_frame(stream,frame.additional_offset)
        need(sha(query)==row['additionalFrameSHA256'], 'FRESH_POINT_PROGRAMME_QUERY_EXACT')
        need(resolver.attribute_rows_sha256(packet.regular_attrs(query))==row['attributeRowsSHA256'],
             'FRESH_POINT_PROGRAMME_TYPED_ATTRIBUTE_BINDING')
        need(row['profileSHA256']==tables['profileSHA256'],'FRESH_POINT_PROFILE_BINDING')
        for key in (196608,196609):
            declared=bytes.fromhex(row['programmes'][str(key)])
            # An explicitly present empty programme is a genuine no-ink
            # context. It remains distinct from a missing or altered context.
            need(sha(declared)==row['programmeSHA256'][str(key)] and
                 key in frame.entries and frame.entries[key]==declared,
                 'FRESH_POINT_PROGRAMME_EXACT_CONTEXT_BYTES')
        receipts.append(dict(uid=fields[0],cellId=owner['cellId'],className=owner['classAcronym'],
            querySHA256=sha(query),status='FRESH_CACHE_PROFILE_COMPILED',
            featureSourceDigest=row['featureSourceDigest'],profileSHA256=row['profileSHA256'],
            programmeSHA256={str(key):sha(frame.entries[key])for key in (196608,196609)},
            savedOriginalOwnerPresentationClaim=False,nativeWord20Preserved=True))
    return receipts

def activate_points_and_ca(ns):
    """Attach saved exact source SIMPLE packets and exact CA120 programmes.

    Every programme remains bound to its complete original query.  This adds
    neither geometry nor invented properties, and never promotes Kind4/Kind8.
    """
    tables=json.loads(read(CFG['sourcePresentationIndex']));ca=json.loads(read(CFG['caOwnerProgramBindings']))['owners'];ca={int(r['uid']):r for r in ca}
    need(len(ca)==len(json.loads(read(CFG['caOwnerProgramBindings']))['owners']),'COMPLETE_CA_PROGRAMME_BIJECTION')
    source_lines=read(tables['originalCommon']).splitlines();symbol_definitions=set()
    for i,line in enumerate(source_lines):
        if line.startswith(b'SYMB  '):
            md=source_lines[i+1][6:];digits=re.match(rb'[0-9]+',md);need(digits is not None,'ORIGINAL_SYMD_PREFIX');symbol_definitions.add(md[len(digits[0]):len(digits[0])+8].decode('ascii'))
    rr=ns['global_render'];aa=bytes(ns['global_add']);stream=local.Stream(len(aa),lambda a,n:aa[a:a+n]);starts=ns['additional_starts'](aa)
    frames=ns['activation'].decode_frames(bytes(rr),starts);extra={};receipts=[]
    if tables.get('format')=='no5-cache-country-source-presentation/1':
        need(not ca,'FRESH_CACHE_PRESENTATION_REQUIRES_CLOSED_COMPILED_PROGRAMMES')
        return activate_fresh_cache_points(ns,tables,frames,stream)
    FRAME_CACHE.update(frames)
    for owner in ns['point_owners']:
        record=owner['record'];r=list(F.unpack(record))
        if r[6]!=1:continue
        frame=frames[r[2]];query=local.additional_frame(stream,frame.additional_offset)
        attrs=packet.regular_attrs(query)
        binding=dict(sourceId=owner['cellId'],sourceOwnerOrdinal=owner['sourceOwnerOrdinal'],uid=r[0],record32SHA256=sha(record),additionalFrameSHA256=sha(query),attributeRowsSHA256=resolver.attribute_rows_sha256(attrs),sourceGeneration='all56-original-owner-geometry-v15')
        resolution=resolver.resolve_actual_source_style(tables,class_name=owner['classAcronym'],kind=1,attributes=attrs,owner_binding=binding)
        entries=dict(frame.entries);programs={};status=resolution['status']
        missing_symbols=sorted(set(re.findall(r'SY\(([A-Z0-9]{8})',resolution.get('instruction')or''))-symbol_definitions)
        if missing_symbols:status='unresolved_required_source_symbol_definition'
        if status=='resolved_saved_source_presentation':
            programs=resolver.render_packets(resolution,context_keys=[196608,196609],policy_binding=dict(policy='COPY_EXACT_SAVED_SOURCE_PRESENTATION_TO_DECLARED_CONTEXTS',contextKeys=[196608,196609],policyInputSHA256=sha(json.dumps(CFG,sort_keys=True).encode()),originalPaperChartLookupClaim=False))
            entries.update(programs)
        if r[0] in ca:
            c=ca[r[0]];need(sha(query)==c['sourceBinding']['originalQuerySHA256'],'CA120_SOURCE_QUERY_EXACT')
            target=bytes.fromhex(c['targetProgrammeHex']);need(sha(target)==c['targetProgrammeSHA256'],'CA120_PROGRAM_PIN');entries.update({196608:target,196609:target});status='SOURCE_CA120_NATIVE_ATLAS_PROGRAM'
        encoded=ns['activation'].encode_frame(frame.additional_offset,entries)
        if encoded not in extra:extra[encoded]=len(rr);rr.extend(encoded)
        r[2]=extra[encoded];owner['record']=F.pack(*r)
        FRAME_CACHE[r[2]]=types_frame(frame,entries)
        receipts.append(dict(uid=r[0],cellId=owner['cellId'],className=owner['classAcronym'],querySHA256=sha(query),status=status,missingOriginalSymbols=missing_symbols,programmeSHA256={str(k):sha(v)for k,v in entries.items()if k in(196608,196609)},nativeWord20Preserved=True))
    return receipts

def types_frame(frame,entries):
    import types
    return types.SimpleNamespace(additional_offset=frame.additional_offset,entries=entries)

def area_only_frame(offset,global_render,activation):
    """SOFT source pass zero: retain only actual AC/AP instructions."""
    if offset in AREA_FRAME_CACHE:return AREA_FRAME_CACHE[offset]
    frame=FRAME_CACHE[offset];entries=dict(frame.entries)
    for key in(196608,196609):
        if key in entries:entries[key]=b';'.join(t for t in entries[key].split(b';')if t.startswith((b'AC(',b'AP(')))
    raw=activation.encode_frame(frame.additional_offset,entries);at=len(global_render);global_render.extend(raw);AREA_FRAME_CACHE[offset]=at
    return at

def filter_scamin_only(owner,level,pv,profiles,winner):
    # Membership is deferred to the actual target tile, including its original
    # 50px candidate search.  An anchor-tile winner here would discard neighbours.
    import types
    own=types.SimpleNamespace(by_id=winner.by_id,choose=lambda point,requested:owner['cellId'])
    return pv.filter_visible_point_owner(owner['coordinates'],owner['indices'],owner['record'],cell_id=owner['cellId'],class_acronym=owner['classAcronym'],source_scamin=owner['sourceSCAMIN'],level=level,winner=own,source_tile=(0,0,0),input_root=1,point_profiles=owner.get('freshPointVisibilityProfiles',profiles))

def capturing_quantizer(actual):
    import types
    def clip_owner(co,ix,record,**kwargs):
        try:return actual.clip_owner(co,ix,record,**kwargs)
        except ValueError as error:
            wrapped=kwargs['clipper'];row=dict(format='no5-v15-actual-geometry-refusal/1',error=str(error),errorReceipt=getattr(error,'receipt',None),ownerBinding=getattr(wrapped,'owner_binding',None),sourceCoordinatesHex=co.hex(),sourceIndicesHex=ix.hex(),record32Hex=record.hex(),coordinateSHA256=sha(co),indexSHA256=sha(ix),level=getattr(wrapped,'level',None),epochLastLevel=getattr(wrapped,'epoch_last_level',None),rootOrigin=list(getattr(wrapped,'origin',())),actualMasks={k:v.wkb.hex()for k,v in getattr(wrapped,'masks',{}).items()},events=getattr(wrapped,'events',[]))
            raw=(json.dumps(row,indent=2,sort_keys=True,default=str)+'\n').encode();d=Path(CFG['diagnosticDirectory'])/'refusals';d.mkdir(parents=True,exist_ok=True);target=d/(str(int.from_bytes(record[:8],'little'))+'-'+sha(raw)[:16]+'.json')
            if not target.exists():target.write_bytes(raw);target.chmod(0o600)
            print(json.dumps(dict(phase='actual V15 geometry refusal',error=str(error),fixture=str(target),sha256=sha(raw))),flush=True);raise
    return types.SimpleNamespace(__file__=actual.__file__,wrap_clipper=actual.wrap_clipper,clip_owner=clip_owner,validate_final_owner=actual.validate_final_owner)

def compact_receipts(rows):
    """Retain the proof digest and key disposition without GB of repeated WKB."""
    result=[]
    for r in rows:
        digest=sha(json.dumps(r,sort_keys=True,separators=(',',':'),default=str).encode())
        result.append({**{k:r[k]for k in('uid','cellId','level','sourceOwnerOrdinal','disposition')if k in r},'completeReceiptSHA256':digest,'finalOwnerMaskValidation':r.get('finalOwnerMaskValidation')})
    return result

def add_region_targets(tiles,regions,level,Q):
    from shapely.geometry import box
    from shapely.prepared import prep
    for region in regions:
        if region.is_empty:continue
        prepared=prep(region)
        # Traverse the actual changed region, not its country-wide AABB. A thin
        # slanted boundary must cost perimeter tiles rather than its bbox area.
        pending=[(0,0,0)]
        while pending:
            z,x,y=pending.pop();n=1<<z;tilebox=box(x*Q/n,y*Q/n,(x+1)*Q/n,(y+1)*Q/n)
            if not prepared.intersects(tilebox):continue
            if z==level:tiles.add((level,x,y));continue
            pending.extend((z+1,2*x+dx,2*y+dy)for dx in(0,1)for dy in(0,1))
