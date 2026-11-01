from . import wire, localize_metadata as local
from ..rx5.header import validate_plain_vector_header
from ..rx5.soundings import materialize_sounding_twins, validate_sounding_pairs

"""Losslessly localize V15 metadata and inventory the persisted native graph."""

from pathlib import Path

import collections, hashlib, json, os, re, struct, time

F=struct.Struct('<Q5I4B')

def need(v,why):
    if not v:raise ValueError(why)

def sha(b):return hashlib.sha256(b).hexdigest()

def jb(v):return (json.dumps(v,indent=2,sort_keys=True,allow_nan=False)+'\n').encode()

def pin(p):
    digest=hashlib.sha256();size=0
    with Path(p).open('rb')as f:
        while True:
            block=f.read(4*1024*1024)
            if not block:break
            size+=len(block);digest.update(block)
    return dict(path=str(p),bytes=size,sha256=digest.hexdigest())

def tree(wire,blocks,raw):
    todo=[(struct.unpack_from('<Q',raw,160)[0],(0,0,0))];nodes={}
    while todo:
        at,tile=todo.pop();need(at not in nodes,'NO_SHARED_OR_CYCLIC_QUAD');nodes[at]=tile;b=blocks[at];l,x,y=tile
        need(b.flags in(1,3)and b.disk_id==0x6000+l,'GENUINE_NATIVE_NODE_ID_FLAGS')
        for i,q in enumerate(struct.unpack('<4Q',b.prefix[:32])):
            if q:todo.append((q,(l+1,x*2+(i&1),y*2+(i>>1))))
    return nodes

def stream(local,raw):return local.Stream(len(raw),lambda a,n:raw[a:a+n])

def composition_summary(path):
    """Read selected top-level fields without retaining a huge diagnostic JSON.

    The producer uses fixed json.dumps(indent=2,sort_keys=True).  Indentation
    identifies only its top-level fields; the selected values still go through
    the standard JSON parser. All diagnostic bytes are independently hashed.
    """
    wanted={'chart','sourceScalePolicy','zoomSelectorReceipt','pointStylePacketsAdded'};result={};current=None;parts=[];counts=collections.Counter()
    def finish():
        if current in wanted:
            value=''.join(parts).split(':',1)[1].strip()
            if value.endswith(','):value=value[:-1]
            result[current]=json.loads(value)
    with Path(path).open('rt',encoding='utf8')as f:
        for line in f:
            if line.startswith('}'):
                finish();current=None;break
            match=re.match(r'^  "([^"\\]+)":',line)
            if match:
                finish();current=match[1];parts=[]
            if current in wanted:parts.append(line)
            elif current=='pointProgramReceipts':
                status=re.match(r'^      "status": ("[^"\\]*")',line)
                if status:counts[json.loads(status[1])]+=1
    need(wanted<=result.keys(),'COMPLETE_SMALL_V15_COMPOSITION_SUMMARY')
    result['sourcePointDispositionCounts']=dict(counts);return result

def finalize(raw_directory,output_directory):
    start=time.monotonic();src=Path(raw_directory);out=Path(output_directory);need(not out.exists(),'FRESH_FINAL_GENERATION_ONLY')
    composition=composition_summary(src/'COMPOSITION.json');parent=Path(composition['chart']['path']).read_bytes();need((len(parent),sha(parent))==(composition['chart']['bytes'],composition['chart']['sha256']),'ACTUAL_V15_RAW_CHART')
    old=wire.blocks(parent);nodes=tree(wire,old,parent);payloads={a for a in nodes if old[a].flags==3};metadata=set();localized={};intern={};counts=collections.Counter()
    for number,at in enumerate(sorted(payloads),1):
        b=old[at];io,co,ro,ao=struct.unpack_from('<4Q',b.prefix,32);metadata.update((io,ro,ao))
        f,ix,rr,aa,receipt=local.localize(b.payload,stream(local,old[io].payload),stream(local,old[ro].payload),stream(local,old[ao].payload),coordinate_bytes=len(old[co].payload));names=[]
        for role,raw,bid in [('indices',ix,0x1101),('render',rr,0x1103),('additional',aa,0x1104)]:
            key=(role,sha(raw))
            if key in intern:need(intern[key][1]==raw,'METADATA_BYTE_EXACT_INTERNING')
            else:intern[key]=('local:'+role+':'+key[1],raw,bid)
            names.append(intern[key][0])
        localized[at]=(f,names);counts['records']+=len(f)//32
        if number%1000==0:print(json.dumps(dict(phase='v15 metadata localized',payloads=number,seconds=round(time.monotonic()-start,3))),flush=True)
    names={a:'old:'+str(a)for a in old if a not in metadata};authored=[wire.AuthoredBlock(n,bid,0,b'',raw)for n,raw,bid in intern.values()];preserved=[]
    for at,b in old.items():
        if at in metadata:continue
        refs=[];payload=b.payload
        if b.flags in(1,2,3):
            for pos in range(0,len(b.prefix),8):
                q=struct.unpack_from('<Q',b.prefix,pos)[0]
                if at in payloads and pos in(32,48,56):n=localized[at][1][{32:0,48:1,56:2}[pos]]
                else:need(q not in metadata,'NO_NONTILE_REFERENCE_TO_REPLACED_METADATA');n=names[q]if q else None
                refs.append((pos,n))
        if at in payloads:payload=localized[at][0]
        need(b.next_offset not in metadata,'NO_NEXT_REFERENCE_TO_REPLACED_METADATA')
        authored.append(wire.AuthoredBlock(names[at],b.disk_id,b.flags,bytes(len(b.prefix))if refs else b.prefix,payload,names[b.next_offset]if b.next_offset else None,tuple(refs)))
        if at not in nodes:preserved.append((at,sha(b.payload)))
    href={slot:names[q]if q else None for slot in wire.HEADER_SLOTS for q in[struct.unpack_from('<Q',parent,slot)[0]]};raw,offsets=wire.assemble(parent[:240],authored,href);parsed=wire.blocks(raw)
    for at,digest in preserved:need(sha(parsed[offsets[names[at]]].payload)==digest,'ALL_NON_GEOMETRY_METADATA_BYTES_EXACT')
    for at,tile in nodes.items():
        new=parsed[offsets[names[at]]];need(new.disk_id==old[at].disk_id and new.flags==old[at].flags,'ALL_NODE_TYPES_UNCHANGED')
        if at in payloads:
            need(new.payload==localized[at][0],'ALL_LOCAL_FEATURE_RECORDS_EXACT')
            oc=struct.unpack_from('<Q',old[at].prefix,40)[0];nc=struct.unpack_from('<Q',new.prefix,40)[0];need(old[oc].payload==parsed[nc].payload,'EVERY_COORDINATE_ARENA_UNCHANGED')
    raw,sounding_twins=materialize_sounding_twins(raw)
    validate_plain_vector_header(raw[:240],file_bytes=len(raw))
    out.mkdir(parents=True,mode=0o700);chart=out/'COUNTRY.rx5'
    with chart.open('xb')as h:h.write(raw);h.flush();os.fsync(h.fileno())
    chart.chmod(0o600)
    result=dict(format='no5-native-scale-quilt-country-v15/1',status='BUILT_NATIVE_LOCAL_METADATA_PERSISTED_READBACK_PENDING',chart=dict(path=str(chart),bytes=len(raw),sha256=sha(raw),uuidHex=raw[56:72].hex()),sourceChart=composition['chart'],sourceComposition=pin(src/'COMPOSITION.json'),sourceOwnerIndex=pin(src/'SOURCE_OWNER_INDEX.json'),selectionBakedIntoGeometry=True,sourceScalePolicy=composition['sourceScalePolicy'],selectionReceipt=composition['zoomSelectorReceipt'],nodes=len(nodes),payloads=len(payloads),records=counts['records'],metadataStreams=len(intern),sourceQueriesProgrammesGeometryExactThroughLocalize=True,atlas1075AndOriginalSourceInfoInherited=any(b.disk_id==1075 for b in old.values()),allExistingSourceMetadataBodiesInherited=True,sourcePointProgrammesAttached=bool(composition['sourcePointDispositionCounts']),sourcePointDispositionCounts=composition['sourcePointDispositionCounts'],legacyBaseMetadataCorrection=dict(pointStylePacketsAdded=composition.get('pointStylePacketsAdded'),actualV15Value=True,basis='V15 pointProgramReceipts and native_hooks.activate_points_and_ca; inherited V7 false flag is not authoritative for the extension'),physicalA97Accepted=False,deviceActions=0,seconds=time.monotonic()-start,code=pin(Path(__file__)))
    result['sourceRecordsBeforeSoundingTwins']=result['records'];result['records']+=sounding_twins['addedKind9Records'];result['soundingTwinMaterialization']=sounding_twins
    (out/'BUILD_RESULT.json').write_bytes(jb(result));print(json.dumps(result),flush=True);return result

def readback(output_directory):
    start=time.monotonic();out=Path(output_directory);build=json.loads((out/'BUILD_RESULT.json').read_bytes());chart=Path(build['chart']['path']);before=chart.stat();raw=chart.read_bytes();after=chart.stat();need((before.st_dev,before.st_ino,before.st_size,before.st_mtime_ns)==(after.st_dev,after.st_ino,after.st_size,after.st_mtime_ns),'PERSISTED_CHART_STAT_STABLE');need((len(raw),sha(raw))==(build['chart']['bytes'],build['chart']['sha256']),'PERSISTED_FULL_CHART_SHA256')
    validate_plain_vector_header(raw[:240],file_bytes=len(raw))
    blocks=wire.blocks(raw);nodes=tree(wire,blocks,raw);inventory={};counts=collections.Counter();query_cache={};frame_cache={};coord_cache={}
    for at,tile in sorted(nodes.items()):
        b=blocks[at]
        if b.flags!=3:continue
        counts['payloads']+=1
        if not b.payload:counts['explicitEmptyRetirementVectors']+=1
        need(len(b.payload)%32==0,'EXACT_FEATURE32_STREAM');io,co,ro,ao=struct.unpack_from('<4Q',b.prefix,32)
        counts['soundingTwinPairs']+=validate_sounding_pairs(b.payload)
        for off,bid in((io,0x1101),(co,0x1102),(ro,0x1103),(ao,0x1104)):need(blocks[off].disk_id==bid and blocks[off].flags==0,'EXACT_TYPED_NATIVE_STREAM')
        ix=stream(local,blocks[io].payload);rr=stream(local,blocks[ro].payload);aa=stream(local,blocks[ao].payload);xy=blocks[co].payload
        for r in F.iter_unpack(b.payload):
            counts['records']+=1;counts['kind'+str(r[6])]+=1
            key=(ro,r[2],ao)
            if key not in frame_cache:
                frame,ap=local.render_frame(rr,r[2]);query=local.additional_frame(aa,ap);pos=6;programmes={};program_hex={}
                for _ in range(struct.unpack_from('<H',frame)[0]):
                    context,n=struct.unpack_from('<IH',frame,pos);pos+=6;programmes[str(context)]=sha(frame[pos:pos+n]);program_hex[str(context)]=frame[pos:pos+n].hex();pos+=n
                frame_cache[key]=(sha(query),programmes,program_hex)
            query,programmes,program_hex=frame_cache[key]
            primary_kind=4 if r[6]==9 else r[6]
            item=inventory.setdefault(r[0],dict(uid=str(r[0]),kind=primary_kind,classCode=r[5],levelsMask=0,querySHA256=query,programmes={},programHexByContext={},programmeVariants={},occurrences=0,nativeOccurrenceKinds=[],maskLevelsByKind={},recordsByKind={}))
            need((item['kind'],item['classCode'],item['querySHA256'])==(primary_kind,r[5],query),'SAME_UID_NATIVE_CLASS_KIND_QUERY_AT_ALL_ZOOMS')
            item['nativeOccurrenceKinds']=sorted(set(item['nativeOccurrenceKinds'])|{r[6]});k=str(r[6]);item['maskLevelsByKind'][k]=item['maskLevelsByKind'].get(k,0)|(1<<tile[0]);item['recordsByKind'][k]=item['recordsByKind'].get(k,0)+1
            item['levelsMask']|=1<<tile[0];item['occurrences']+=1
            for context,digest in programmes.items():
                item['programmes'].setdefault(context,set()).add(digest)
                if len(program_hex[context])>len(item['programHexByContext'].get(context,'')):item['programHexByContext'][context]=program_hex[context]
                variant=item['programmeVariants'].setdefault(context,{}).setdefault(digest,dict(programHex=program_hex[context],maskLevels=0));variant['maskLevels']|=1<<tile[0]
            words=local.index_frame(ix,r[1])
            for word, in struct.iter_unpack('<I',words[4:]):
                offset=word&0xffffff;ck=(co,offset,r[6])
                if ck in coord_cache:continue
                need(offset+4<=len(xy),'NATIVE_COORDINATE_ELEMENT_START');header=struct.unpack_from('<I',xy,offset)[0];n=header&0xffffff;stride=12 if r[6]in(4,9) else 8
                need(header>>24==1 and n>0 and offset+4+n*stride<=len(xy),'COMPLETE_ROOT1_TYPED_COORDINATE_ELEMENT');coord_cache[ck]=True
    for item in inventory.values():item['programmes']={k:sorted(v)for k,v in item['programmes'].items()};item['maskLevels']=item['levelsMask']
    ip=out/'UID_PROGRAM_INVENTORY.json';ip.write_bytes(jb(dict(format='no5-native-zoom-v15-uid-program-inventory/2',chart=build['chart'],kindFieldIsPrimarySourceRole=True,nativeOccurrenceKindsRemainExplicit=True,rows=[inventory[k]for k in sorted(inventory)])))
    result=dict(format='no5-native-scale-quilt-country-readback-v15/1',status='PASS_PERSISTED_NATIVE_GRAPH_METADATA_QUERY_UID_GEOMETRY',chart=build['chart'],chartStat=[after.st_dev,after.st_ino,after.st_size,after.st_mtime_ns],buildResult=pin(out/'BUILD_RESULT.json'),fullChartSHA256Verified=True,nodes=len(nodes),uniqueUIDs=len(inventory),counts=dict(counts),ownerInventory=pin(ip),nativeSchemaAndRetirementFlagsPreserved=True,physicalA97Accepted=False,deviceActions=0,seconds=time.monotonic()-start,readerCode=pin(Path(__file__)))
    (out/'PERSISTED_READBACK.json').write_bytes(jb(result));print(json.dumps(result),flush=True);return result
