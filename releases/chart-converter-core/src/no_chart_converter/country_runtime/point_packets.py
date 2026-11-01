"""Compile exact registered source Kind1 presentations; no guessed glyphs or Paper-family claim."""

import hashlib, json, struct

from .localize_metadata import Stream,additional_frame

F=struct.Struct('<Q5I4B')

def sha(b):return hashlib.sha256(b).hexdigest()

def need(v,s):
    if not v:raise ValueError(s)

def regular_attrs(raw):
    pos=8;n=struct.unpack_from('<H',raw,pos)[0];pos+=2;rows=[]
    for _ in range(n):
        code=struct.unpack_from('<H',raw,pos)[0];pos+=2;end=raw.index(0,pos);rows.append((code,raw[pos:end].decode('utf8')));pos=end+1
    return rows

def compile_points(features,render,additional,owners,*,cell_id,source_ordinal,tables,resolver,writer,policy_pin):
    need(type(features)is bytes and len(features)%32==0,'FEATURE32')
    stream=Stream(len(additional),lambda a,n:additional[a:a+n]);queries={};pos=0
    while pos<len(additional):
        raw=additional_frame(stream,pos);queries[pos]=raw;pos+=len(raw)
    need(pos==len(additional),'QUERY_EXACT_END')
    frames=writer.decode_frames(render,queries);by_hash={};receipts=[];seen=set()
    for owner in owners:
        if owner['kind']!=1 or owner.get('preparedOwnerOrdinal')is None:continue
        ordinal=owner['sourceOwnerOrdinal'];record=features[ordinal*32:(ordinal+1)*32];r=F.unpack(record)
        need(r[0]==owner['uid'] and r[6]==1 and r[5]==owner['classCode'] and sha(record)==owner['originalRecordSHA'],'EXACT_SOURCE_POINT_OWNER')
        need(r[0]>>32==source_ordinal+1 and (r[0]&0xffffffff)<1<<20,'ORIGINAL_SOURCE_UID_NAMESPACE')
        need(r[0]not in seen,'UNIQUE_POINT_UID');seen.add(r[0]);frame=frames[r[2]];query=queries[frame.additional_offset]
        need(frame.additional_offset==owner['originalAdditionalOffset'] and sha(query)==owner['additionalSHA'],'EXACT_ORIGINAL_POINT_QUERY')
        attrs=regular_attrs(query);binding={'sourceId':cell_id,'sourceOwnerOrdinal':ordinal,'uid':r[0],'record32SHA256':sha(record),'additionalFrameSHA256':sha(query),'attributeRowsSHA256':resolver.attribute_rows_sha256(attrs)}
        result=resolver.resolve_actual_source_style(tables,class_name=owner['classAcronym'],kind=1,attributes=attrs,owner_binding=binding)
        packets=None
        if result['status']=='resolved_saved_source_presentation':
            packets=resolver.render_packets(result,context_keys=[0x30000,0x30001],policy_binding={'policy':'COPY_EXACT_SAVED_SOURCE_PRESENTATION_TO_DECLARED_CONTEXTS','contextKeys':[0x30000,0x30001],'policyInputSHA256':policy_pin,'originalPaperChartLookupClaim':False})
        by_hash[sha(record)]=packets
        receipts.append({'uid':str(r[0]),'sourceStoredOwnerOrdinal':ordinal,'sourceFeatureId':r[0]&0xffffffff,'sourceId':cell_id+':f'+str(r[0]&0xffffffff),'className':owner['classAcronym'],'originalRecord32SHA256':sha(record),'originalAdditionalSHA256':sha(query),'actualNXStyle':resolver.nxstyle(attrs),'status':result['status'],'reason':result.get('reason'),'resolution':result,'packets':{str(k):{'bytes':len(v),'sha256':sha(v),'canonicalHex':v.hex()}for k,v in (packets or {}).items()}})
    def resolve(record,frame):return by_hash.get(sha(record))
    out,rr,control=writer.activate_features(features,render,additional,queries,resolve)
    nf=writer.decode_frames(rr,queries)
    for old,new in zip(F.iter_unpack(features),F.iter_unpack(out)):
        a,b=frames[old[2]],nf[new[2]];need(a.additional_offset==b.additional_offset,'QUERY_AO_UNCHANGED');updates=by_hash.get(sha(F.pack(*old)));expected=dict(a.entries);expected.update(updates or {});need(dict(b.entries)==expected,'EXACT_COMPLETE_FRAME_REPLAY')
    return out,rr,{'format':'no5-kind1-native-source-packet-activation/1','cellId':cell_id,'sourceOrdinal':source_ordinal,'sourcePointOwners':len(receipts),'activated':sum(bool(r['packets'])for r in receipts),'owners':receipts,'writerReceipt':control,'originalAdditionalBytesUnchanged':True,'originalCoordinatesAndIndicesUnchanged':True,'sourceFamily':'SIMPLIFIED','originalPaperChartLookupClaim':False,'copyToBothExistingRuntimeContexts':True,'actualNativeSYLayerOrPaintPass':False}
