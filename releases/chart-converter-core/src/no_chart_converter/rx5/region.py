"""Portable lossless RX5 bbox subsets. No geometry clipping, native CPU or network access."""
import collections,hashlib,json,math,os,sqlite3,struct,threading,time,uuid
from pathlib import Path
from xml.sax.saxutils import escape

SOURCE = None
SOURCE_BYTES = None
SOURCE_SHA = None
STORE = None
Q = 2147483649
MAX_LEVEL = 21
GRID = Q * (1 << MAX_LEVEL)
F = struct.Struct('<Q5I4B')
PREFIX = {0:0, 1:32, 2:32, 3:64, 16:24, 64:0}
HEADER_SLOTS = (136,144,160,168,184,200)
from . import wire
from . import metadata as local


def configure(source, store, *, expected_sha256=None):
    global SOURCE, SOURCE_BYTES, SOURCE_SHA, STORE
    SOURCE = Path(source).resolve(strict=True)
    STORE = Path(store).resolve()
    need(STORE != SOURCE and not STORE.is_relative_to(SOURCE), 'SEPARATE_EXPLICIT_OUTPUT_STORE')
    SOURCE_BYTES = SOURCE.stat().st_size
    SOURCE_SHA = pin(SOURCE)['sha256']
    if expected_sha256 is not None:
        need(SOURCE_SHA == expected_sha256, 'EXPLICIT_SOURCE_SHA256')
    return Service()

def need(v,s):
    if not v:raise ValueError(s)
def sha(b):return hashlib.sha256(b).hexdigest()
def jb(v):return(json.dumps(v,indent=2,sort_keys=True,ensure_ascii=False,allow_nan=False)+'\n').encode()
def pin(p):
    h=hashlib.sha256();n=0
    with Path(p).open('rb')as f:
        while b:=f.read(4<<20):h.update(b);n+=len(b)
    return {'path':str(p),'bytes':n,'sha256':h.hexdigest()}
def emit(cb,phase,completed,total,message):
    if cb:cb(dict(phase=phase,completed=completed,total=total,message=message))
def statkey(s):return [s.st_dev,s.st_ino,s.st_size,s.st_mtime_ns]
def store_info():
    need(STORE is not None, 'EXPLICIT_CONFIGURE_REQUIRED')
    return {'path': str(STORE), 'available': STORE.parent.is_dir(), 'reason': None}

def require_store():
    need(STORE is not None and STORE.parent.is_dir(), 'EXISTING_EXPLICIT_OUTPUT_PARENT_REQUIRED')
    STORE.mkdir(parents=False, exist_ok=True, mode=0o700)
    need(not STORE.is_symlink() and STORE.is_dir(), 'REAL_OUTPUT_STORE_DIRECTORY')
    return STORE

def root_to_geo(x,y):return [x*360-180,math.degrees(math.atan(math.sinh(math.pi*(1-2*y))))]
def geo_to_root(lon,lat):return ((lon+180)/360,(1-math.asinh(math.tan(math.radians(lat)))/math.pi)/2)
def request(r):
    need(type(r)is dict,'REQUEST_OBJECT');box=r.get('bbox');need(type(box)is list and len(box)==4 and all(type(v)in(int,float)and math.isfinite(v)for v in box),'FINITE_WGS84_BBOX')
    w,s,e,n=map(float,box);need(-180<=w<e<=180 and -85.0511287798066<s<n<85.0511287798066,'ORDERED_BBOX_NO_ANTIMERIDIAN_OR_POLE')
    name=r.get('name','Region');need(type(name)is str and 1<=len(name.strip())<=100 and all(ord(c)>=32 for c in name),'REGION_NAME');name=name.strip()
    if 'marginNm'in r and 'overlapNm'in r:need(r['marginNm']==r['overlapNm'],'ONE_MARGIN_VALUE')
    margin=r.get('marginNm',r.get('overlapNm',0));need(type(margin)in(int,float)and math.isfinite(margin)and margin>=0,'FINITE_NONNEGATIVE_MARGIN_NM')
    # Explicit own conservative spherical bounding rectangle, R=WGS84 semimajor.
    dy=math.degrees(margin*1852/6378137);ss=s-dy;nn=n+dy;need(-85.0511287798066<ss<nn<85.0511287798066,'MARGIN_REACHES_MERCATOR_POLE')
    dx=dy/math.cos(math.radians(max(abs(ss),abs(nn))));ww=w-dx;ee=e+dx;need(-180<=ww<ee<=180,'MARGIN_REACHES_ANTIMERIDIAN')
    lo=r.get('minLevel',0);hi=r.get('maxLevel',MAX_LEVEL);need(type(lo)is int and type(hi)is int and lo==0 and hi==MAX_LEVEL,'ALL_LEVELS_REQUIRED_TO_PRESERVE_RETIREMENT')
    x0,y1=geo_to_root(ww,ss);x1,y0=geo_to_root(ee,nn)
    return dict(name=name,bbox=[w,s,e,n],exportBBox=[ww,ss,ee,nn],marginNm=margin,minLevel=lo,maxLevel=hi,rootBBox=[x0,y0,x1,y1],integerBBox=[math.floor(x0*GRID),math.floor(y0*GRID),math.ceil(x1*GRID),math.ceil(y1*GRID)])

class Reader:
    def __init__(self,path=None):
        path=SOURCE if path is None else path
        need(path is not None,'EXPLICIT_CONFIGURE_REQUIRED')
        self.fd=os.open(path,os.O_RDONLY|os.O_NOFOLLOW);self.before=os.fstat(self.fd);self.bytes_read=0;self.calls=0;self.blocks={};self.cache=collections.OrderedDict();self.cache_bytes=0;self.header=self.read(0,240)
        need(self.before.st_size==SOURCE_BYTES and self.header[:8]==b'\1RayRX5\0','CURRENT_RX5_HEADER_AND_SIZE')
    def read(self,a,n):
        need(0<=a<=self.before.st_size and 0<=n<=self.before.st_size-a,'SOURCE_RANGE');b=os.pread(self.fd,n,a);need(len(b)==n,'EXACT_SOURCE_READ');self.bytes_read+=n;self.calls+=1;return b
    def block(self,a):
        if a not in self.blocks:
            bid,flags,z,nxt=struct.unpack('<HHIQ',self.read(a,16));need(flags in PREFIX and z>=PREFIX[flags],'BLOCK_LAYOUT');k=PREFIX[flags];pre=self.read(a+16,k);self.blocks[a]=(bid,flags,a+16+k,z-k,nxt,pre)
        return self.blocks[a]
    def payload(self,a):
        b=self.block(a);return self.read(b[2],b[3])
    def range(self,block,off,n):
        b=self.block(block);need(0<=off<=b[3]and n<=b[3]-off,'STREAM_RANGE');return self.read(b[2]+off,n)
    def cached(self,a):
        if a in self.cache:self.cache.move_to_end(a);return self.cache[a]
        b=self.payload(a)
        if len(b)<=32<<20:
            while self.cache and self.cache_bytes+len(b)>64<<20:
                _,old=self.cache.popitem(last=False);self.cache_bytes-=len(old)
            self.cache[a]=b;self.cache_bytes+=len(b)
        return b
    def chain(self,a):
        out=[];seen=set()
        while a:
            need(a not in seen,'CHAIN_CYCLE');seen.add(a);out.append(a);a=self.block(a)[4]
        return out
    def stream(self,a):
        raw=self.cached(a);return local.Stream(len(raw),lambda off,n:raw[off:off+n])
    def idx(self,a,off):return local.index_frame(self.stream(a),off)
    def element(self,a,off,kind):
        h=struct.unpack('<I',self.range(a,off,4))[0];need(h>>24 in(0,1),'ORIGINAL_ELEMENT_ROOT_FLAG');n=h&0xffffff;stride=12 if kind in(4,9) else 8;return self.range(a,off,4+n*stride),stride
    def close(self):
        if self.fd is None:return
        need(statkey(os.fstat(self.fd))==statkey(self.before),'SOURCE_STAT_UNCHANGED');os.close(self.fd);self.fd=None

class Service:
    def __init__(self,index_path=None):
        self.index_path=Path(index_path)if index_path else STORE/('index-'+SOURCE_SHA[:16]+'.sqlite');self.lock=threading.Lock()
        need(self.index_path.parent==STORE,'FIXED_INDEX_NAMESPACE')
    def _db(self):
        need(self.index_path.is_file(),'INDEX_NOT_READY');db=sqlite3.connect('file:'+str(self.index_path)+'?mode=ro',uri=True);meta=json.loads(db.execute("select value from meta where key='source'").fetchone()[0]);need(meta['sha256']==SOURCE_SHA and meta['stat']==statkey(SOURCE.stat()),'STALE_SOURCE_INDEX');return db
    def coverage(self):
        c=Reader();a=struct.unpack_from('<Q',c.header,144)[0];chain=c.chain(a);cov=next(z for z in chain if c.block(z)[0]==0x34);b=c.block(cov);io,co,ro,ao=struct.unpack('<4Q',b[5]);features=c.payload(cov);xy=c.payload(co);ix=c.payload(io);out=[];bounds=[180,90,-180,-90];sources=[]
        import xml.etree.ElementTree as ET
        for z in chain:
            if c.block(z)[0]==0x30:
                root=ET.fromstring(c.payload(z));info=root.find('source_chart_info');sources.append(info.get('id'))
        for ordinal,f in enumerate(F.iter_unpack(features)):
            n=struct.unpack_from('<I',ix,f[1])[0]
            for word, in struct.iter_unpack('<I',ix[f[1]+4:f[1]+4+4*n]):
                off=word&0xffffff;count=struct.unpack_from('<I',xy,off)[0];pts=[root_to_geo(x/Q,y/Q)for x,y in struct.iter_unpack('<II',xy[off+4:off+4+8*count])];role=word>>24
                if role==18:
                    for x,y in pts:bounds=[min(bounds[0],x),min(bounds[1],y),max(bounds[2],x),max(bounds[3],y)]
                out.append({'type':'Feature','properties':{'sourceCell':sources[ordinal],'role':'covered'if role==18 else'excluded'},'geometry':{'type':'Polygon','coordinates':[pts]}})
        src={'path':str(SOURCE),'bytes':SOURCE_BYTES,'sha256':SOURCE_SHA,'uuidHex':c.header[56:72].hex()};c.close();ready=False
        try:db=self._db();db.close();ready=True
        except (ValueError,sqlite3.Error,FileNotFoundError):pass
        return {'coverageGeoJSON':{'type':'FeatureCollection','features':out},'bounds':bounds,'source':src,'outputStore':store_info(),'index':{'ready':ready,'status':'ready'if ready else'not_prepared','path':str(self.index_path)},'supportedLevels':list(range(MAX_LEVEL+1)),'presets':[{'name':'Granger Bay','bbox':[18.405,-33.910,18.432,-33.890],'marginNm':0.25},{'name':'Cape Town','bbox':[18.30,-34.05,18.55,-33.78],'marginNm':0.5}]}
    def prepare_index(self,progress=None):
        with self.lock:
            require_store()
            if self.index_path.exists():
                db=self._db();m=dict(db.execute('select key,value from meta'));db.close();return {'ready':True,'status':'ready','path':str(self.index_path),'source':json.loads(m['source']),'actual':json.loads(m['actual'])}
            start=time.monotonic();c=Reader();emit(progress,'verify_source',0,SOURCE_BYTES,'Verify the immutable generated RX5 once');h=hashlib.sha256()
            for a in range(0,SOURCE_BYTES,4<<20):h.update(c.read(a,min(4<<20,SOURCE_BYTES-a)));emit(progress,'verify_source',min(a+(4<<20),SOURCE_BYTES),SOURCE_BYTES,'Verify RX5 bytes')
            need(h.hexdigest()==SOURCE_SHA,'EXACT_RX5_SHA256');temp=self.index_path.with_name('index-'+uuid.uuid4().hex+'.preparing.sqlite');db=sqlite3.connect(temp);db.executescript('pragma journal_mode=DELETE;pragma synchronous=FULL;create table meta(key text primary key,value text);create table blocks(at integer primary key,bid integer,flags integer,payload integer,bytes integer,nxt integer,prefix blob);create table nodes(at integer primary key,level integer,x integer,y integer,flags integer,minx integer,miny integer,maxx integer,maxy integer);create table features(node integer,ordinal integer,uid text,kind integer,minx integer,miny integer,maxx integer,maxy integer,record blob,primary key(node,ordinal)) without rowid;')
            at=240
            while at<SOURCE_BYTES:
                b=c.block(at);db.execute('insert into blocks values(?,?,?,?,?,?,?)',(at,*b));at=b[2]+b[3]
            need(at==SOURCE_BYTES,'COMPLETE_PHYSICAL_BLOCK_INDEX');root=struct.unpack_from('<Q',c.header,160)[0];todo=[(root,0,0,0)];nodes=[]
            while todo:
                at,l,x,y=todo.pop();b=c.block(at);need(b[1]in(1,3)and l<=MAX_LEVEL,'CURRENT_TREE_LAYOUT');scale=Q*(1<<(MAX_LEVEL-l));db.execute('insert into nodes values(?,?,?,?,?,?,?,?,?)',(at,l,x,y,b[1],x*scale,y*scale,(x+1)*scale,(y+1)*scale));nodes.append((at,l,x,y))
                for q,z in enumerate(struct.unpack('<4Q',b[5][:32])):
                    if z:todo.append((z,l+1,x*2+(q&1),y*2+(q>>1)))
            cache=collections.OrderedDict();record_count=0;max_points=0
            for ni,(at,l,x,y)in enumerate(nodes):
                b=c.block(at)
                if not b[1]&2:continue
                io,co,ro,ao=struct.unpack_from('<4Q',b[5],32);raw=c.payload(at);need(len(raw)%32==0,'STORED32');batch=[]
                for ordinal,fields in enumerate(F.iter_unpack(raw)):
                    idx=c.idx(io,fields[1]);key=(co,fields[6],idx);v=cache.get(key)
                    if v is not None and v[1]is None:box=v[0];cache.move_to_end(key)
                    elif v is not None and v[1]==(l,x,y):box=v[0];cache.move_to_end(key)
                    else:
                        xs=[];ys=[];local_basis=False
                        for word, in struct.iter_unpack('<I',idx[4:]):
                            el,stride=c.element(co,word&0xffffff,fields[6]);eh=struct.unpack_from('<I',el)[0];rootflag=eh>>24;ll,xx,yy=(0,0,0)if rootflag else(l,x,y);local_basis|=not rootflag;factor=1<<(MAX_LEVEL-ll)
                            for point in struct.iter_unpack('<III'if stride==12 else'<II',el[4:]):xs.append((xx*Q+point[0])*factor);ys.append((yy*Q+point[1])*factor)
                        need(xs,'REGIONAL_OWNER_REQUIRES_ORIGINAL_GEOMETRY');box=(min(xs),min(ys),max(xs),max(ys));max_points=max(max_points,len(xs));cache[key]=(box,(l,x,y)if local_basis else None)
                        if len(cache)>100000:cache.popitem(last=False)
                    batch.append((at,ordinal,str(fields[0]),fields[6],*box,F.pack(*fields)));record_count+=1
                db.executemany('insert into features values(?,?,?,?,?,?,?,?,?)',batch)
                if ni%100==0:emit(progress,'spatial_index',ni,len(nodes),'Index exact original owner bounds')
            source={'path':str(SOURCE),'bytes':SOURCE_BYTES,'sha256':SOURCE_SHA,'uuidHex':c.header[56:72].hex(),'stat':statkey(c.before)};actual={'nodes':len(nodes),'records':record_count,'blocks':len(c.blocks),'readBytes':c.bytes_read,'readCalls':c.calls,'seconds':time.monotonic()-start,'largestOwnerPointCount':max_points,'rootIntegerGridDivisor':GRID,'noCoordinateChanges':True};db.executemany('insert into meta values(?,?)',[('source',json.dumps(source)),('actual',json.dumps(actual)),('header',c.header.hex())]);db.commit();db.close();c.close();os.replace(temp,self.index_path);emit(progress,'spatial_index',len(nodes),len(nodes),'Index ready');return {'ready':True,'status':'ready','path':str(self.index_path),'source':source,'actual':actual}
    def _selection(self,r,db):
        box=r['integerBBox'];params=(r['maxLevel'],box[2],box[3],box[0],box[1]);nodes=list(db.execute('select at,level,x,y,flags from nodes where level<=? and minx<=? and miny<=? and maxx>=? and maxy>=? order by level,x,y',params));selected=[];uids=set();counts=collections.Counter()
        for n in nodes:
            rows=[]
            if n[1]>=r['minLevel']and n[4]&2:
                rows=list(db.execute('select ordinal,uid,kind,record,minx,miny,maxx,maxy from features where node=? and minx<=? and miny<=? and maxx>=? and maxy>=? order by ordinal',(n[0],box[2],box[3],box[0],box[1])))
            for row in rows:uids.add(row[1]);counts[row[2]]+=1
            selected.append((n,rows))
        return selected,uids,counts
    def estimate(self,r):
        return self.export(r,_estimate_only=True)
    def export(self,r,progress=None,_estimate_only=False):
        r=request(r)
        with self.lock:
            require_store();db=self._db();selected,uids,counts=self._selection(r,db);need(uids,'NO_REGIONAL_GEOMETRY');c=Reader();source_binding=json.loads(db.execute("select value from meta where key='source'").fetchone()[0]);need(statkey(c.before)==source_binding['stat'],'HELD_SOURCE_FD_MATCHES_INDEX');start=time.monotonic();job=uuid.uuid4();out=STORE/'exports'/str(job);need(not out.exists(),'FRESH_EXPORT');authored=[];intern={};node_rows=[];owner_rows=[];tile_names={tuple(n[1:4]):'tile:'+':'.join(map(str,n[1:4]))for n,rows in selected};node_data={};original_footer_refs=[]
            def add(role,raw,bid):
                key=(role,sha(raw))
                if key in intern:need(intern[key][1]==raw,'EXACT_BYTE_DEDUP_NOT_HASH_ONLY')
                else:intern[key]=(role+':'+key[1],raw,bid)
                return intern[key][0]
            for i,(n,rows)in enumerate(selected):
                at,l,x,y,flags=n;body=bytearray();coords=bytearray();index=bytearray();cm={};im={};receipt_rows=[]
                if flags&2:
                    io,co,ro,ao=struct.unpack_from('<4Q',c.block(at)[5],32)
                    for row in rows:
                        ordinal,uid,kind,record,*bbox=row;fields=list(F.unpack(record));old_idx=c.idx(io,fields[1]);ik=(fields[1],4 if kind==9 else kind)
                        if ik not in im:
                            words=[]
                            for word, in struct.iter_unpack('<I',old_idx[4:]):
                                off=word&0xffffff;ek=(off,kind in(4,9))
                                if ek not in cm:
                                    el,stride=c.element(co,off,kind);need(len(coords)<=0xffffff,'NATIVE_LOW24');cm[ek]=len(coords);coords+=el
                                words.append((word&0xff000000)|cm[ek])
                            im[ik]=len(index);index+=struct.pack('<I',len(words))+struct.pack('<'+'I'*len(words),*words)
                        fields[1]=im[ik];body+=F.pack(*fields);receipt_rows.append({'parentNodeOffset':at,'parentTile':[l,x,y],'parentOrdinal':ordinal,'outputOrdinal':len(receipt_rows),'uid':uid,'kind':kind,'classCode':fields[5],'originalRecord32Hex':record.hex(),'originalOwnerBBoxInteger':bbox})
                    if rows:
                        body,ix,rr,aa,loc=local.localize(bytes(body),local.Stream(len(index),lambda a,n:bytes(index[a:a+n])),c.stream(ro),c.stream(ao),coordinate_bytes=len(coords))
                    else:body,ix,rr,aa=b'',b'',b'',b''
                    need(b'NO5-ORIGINAL-L0/'not in rr and b'NO5-ORIGINAL-L0/'not in aa,'EXPLICIT_OWN_FULL_SOURCE_FOOTER_REFERENCE_NEEDS_CLOSURE')
                    names=[add(role,raw,bid)for role,raw,bid in [('indices',ix,0x1101),('coordinates',bytes(coords),0x1102),('render',rr,0x1103),('additional',aa,0x1104)]];node_data[(l,x,y)]=(body,names)
                    for j,p in enumerate(receipt_rows):p['outputRecord32Hex']=body[j*32:j*32+32].hex()
                    owner_rows.extend(receipt_rows)
                node_rows.append({'parentNodeOffset':at,'tile':[l,x,y],'parentFlags':flags,'outputFlags':3 if flags&2 and l>=r['minLevel']else 1,'records':len(rows),'emptyScenePreserved':bool(flags&2 and not rows and l>=r['minLevel'])})
                if i%50==0:emit(progress,'materialize',i,len(selected),'Compact exact referenced coordinate elements and metadata')
            for name,raw,bid in intern.values():authored.append(wire.AuthoredBlock(name,bid,0,b'',raw))
            for n,rows in selected:
                at,l,x,y,flags=n;t=(l,x,y);refs=[(q*8,tile_names.get((l+1,2*x+(q&1),2*y+(q>>1))))for q in range(4)];data=l>=r['minLevel']and bool(flags&2)
                if data:
                    body,names=node_data[t];refs.extend((32+8*i,z)for i,z in enumerate(names))
                else:body=b''
                authored.append(wire.AuthoredBlock(tile_names[t],0x6000+l,3 if data else 1,bytes(64 if data else 32),body,None,tuple(refs)))
            resources=[];names={};header=bytearray(c.header)
            def collect(a):
                if not a or a in names:return
                names[a]='resource:'+str(a);b=c.block(a);collect(b[4])
                if b[1]in(1,2,3):
                    for q, in struct.iter_unpack('<Q',b[5]):collect(q)
            for slot in(144,168,184):collect(struct.unpack_from('<Q',header,slot)[0])
            for a,name in names.items():
                bid,flags,payload,size,nxt,pre=c.block(a);raw=c.payload(a);refs=tuple((i,names[q]if q else None)for i in range(0,len(pre),8)for q in[struct.unpack_from('<Q',pre,i)[0]])if flags in(1,2,3)else();authored.append(wire.AuthoredBlock(name,bid,flags,bytes(len(pre))if refs else pre,raw,names[nxt]if nxt else None,refs));resources.append({'parentOffset':a,'name':name,'id':bid,'flags':flags,'bytes':len(raw),'sha256':sha(raw)})
            # Native document IDs 0..cut-1 unchanged. Only trailing OWN raw-source
            # reproduction attachments are omitted; no original nautical doc.
            docs=struct.unpack_from('<Q',header,200)[0];dbid,df,dp,dz,dnext,dpre=c.block(docs);need(dbid==7 and df==16,'DOCUMENT_DIRECTORY');docnames=wire.dictionary(c.payload(docs));cut=next((i for i,n in enumerate(docnames)if n.startswith('NO5-ORIGINAL-L0/')),len(docnames));need(all(n.startswith('NO5-ORIGINAL-L0/')for n in docnames[cut:]),'OWN_FOOTER_TRAILING_ONLY');docblocks=c.chain(dnext);need(len(docblocks)==len(docnames),'DOCUMENT_BIJECTION');kept=docnames[:cut]
            authored.append(wire.AuthoredBlock('documents',7,16,dpre,wire.encode_dictionary(kept),'doc:0'if kept else None));documents=[]
            for i,name in enumerate(kept):
                a=docblocks[i];bid,flags,*_=c.block(a);need(bid==i and flags==64,'ORIGINAL_DOCUMENT_ID_ORDER');raw=c.payload(a);authored.append(wire.AuthoredBlock('doc:'+str(i),i,64,b'',raw,'doc:'+str(i+1)if i+1<cut else None));documents.append({'name':name,'id':i,'bytes':len(raw),'sha256':sha(raw)})
            header[56:72]=job.bytes_le;title=('<?xml version="1.0" encoding="utf8"?><rx5><chart_info name="'+escape(r['name'],{'"':'&quot;'})+'"><edition date="2026-10-16"/></chart_info></rx5>').encode();authored.append(wire.AuthoredBlock('chartinfo',2,0,b'',title));hrefs={136:'chartinfo',144:names[struct.unpack_from('<Q',header,144)[0]],160:tile_names[(0,0,0)],168:names[struct.unpack_from('<Q',header,168)[0]],184:names[struct.unpack_from('<Q',header,184)[0]],200:'documents'};raw,offsets=wire.assemble(bytes(header),authored,hrefs);parsed=wire.blocks(raw)
            for x in node_rows:x['nodeOffset']=offsets[tile_names[tuple(x['tile'])]]
            for x in owner_rows:x['nodeOffset']=offsets[tile_names[tuple(x['parentTile'])]]
            inverse=verify_inverse(c,raw,node_rows,owner_rows,resources,documents)
            stream_bytes=collections.Counter()
            for (role,digest),(name,data,bid)in intern.items():stream_bytes[role]+=len(data)
            if _estimate_only:
                actual={'indexReady':True,'name':r['name'],'coreBBox':r['bbox'],'exportBBox':r['exportBBox'],'marginNm':r['marginNm'],'levels':list(range(r['minLevel'],r['maxLevel']+1)),'candidateNodes':len(selected),'recordOccurrences':len(owner_rows),'uniqueUIDs':len(uids),'kindCounts':dict(counts),'estimatedChartBytes':len(raw),'estimateKind':'exact_materialized_wire_bytes_same_parent_and_request','sourceReadEstimateBytes':c.bytes_read,'streamBytes':dict(stream_bytes),'measuredPreparationSeconds':time.monotonic()-start,'inverse':inverse,'source':source_binding,'packageAvailable':False,'ownerAdmission':'WHOLE_OWNER_BBOX_INTERSECTION_NO_GEOMETRY_CLIP','allSourceWinnerAndSCAMINValuesUnchanged':True,'outputFilesWritten':0};c.close();db.close();return actual
            c.close();db.close()
            out.mkdir(parents=True,mode=0o700)
            target=out/'REGION.rx5'
            with os.fdopen(os.open(target,os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600),'wb')as f:f.write(raw);f.flush();os.fsync(f.fileno())
            cp=pin(target);need(cp['sha256']==sha(raw),'PERSISTED_REGION_SHA');recordpin=out/'OWNER_INVENTORY.json';recordpin.write_bytes(jb({'format':'no5-region-owner-occurrence-map/1','parentChartSHA256':SOURCE_SHA,'chart':cp,'rows':owner_rows}));inventory=pin(recordpin)
            manifest={'format':'no5.rx5.region-export/1','status':'PASS_AUTOMATIC_ALL_OWNER_RESOURCE_INVERSE_AND_PERSISTED_SHA_NATIVE_DEVICE_OPEN','packageId':str(job),'title':r['name'],'chart':dict(cp,uuidHex=job.bytes_le.hex()),'sourceChart':{'path':str(SOURCE),'bytes':SOURCE_BYTES,'sha256':SOURCE_SHA,'uuidHex':c.header[56:72].hex()},'requestedBoundsWsen':r['bbox'],'effectiveBoundsWsen':r['exportBBox'],'marginNm':r['marginNm'],'levels':list(range(r['minLevel'],r['maxLevel']+1)),'actualPayloadLevels':sorted({x['tile'][0]for x in node_rows if x['outputFlags']&2}),'nodes':node_rows,'ownerInventory':inventory,'physicalOwnerUIDs':sorted(uids,key=int),'recordOccurrences':len(owner_rows),'uniqueUIDs':len(uids),'kindCounts':dict(counts),'metadataBytes':sum(v for k,v in stream_bytes.items()if k!='coordinates'),'coordinateBytes':stream_bytes['coordinates'],'renderWireBytes':stream_bytes['render'],'streamBytes':dict(stream_bytes),'automaticInverse':inverse,'resources':resources,'documents':documents,'omittedOwnReproductionFooterDocuments':len(docnames)-cut,'optionalFullSourceShapeResolverIncluded':False,'selectedNativeQueryAttributeBytesIncluded':True,'originalNativeDocumentsAndDocumentIDsUnchanged':True,'allOriginalQueryAndProgrammeAndSCAMINAndXYZPreserved':True,'ownerAdmission':'COMPLETE_OWNER_EXACT_BBOX_INTERSECTION; no clipping, snap, projection or source winner change','coordinateReferenceBasisAndRootFlagsUnchanged':True,'originalSourceInfoPreserved':True,'originalSourceCoverageNotRelabeledAsRegionCoverage':True,'geometryMayOverhangEffectiveBounds':'whole original owner/primitive conservation; no outside-bounds draw guarantee','emptyPayloadNodesKeptToAvoidAncestorSceneChange':True,'portrayalPackageAvailable':False,'nativeA97OrPerformanceAccepted':False,'metrics':{'readBytes':c.bytes_read,'readCalls':c.calls,'buildSeconds':time.monotonic()-start,'candidateNodes':len(selected),'persistedReadbackBytes':len(raw)},'code':pin(Path(__file__))};mp=out/'manifest.json';mp.write_bytes(jb(manifest));c.close();db.close();emit(progress,'complete',len(selected),len(selected),'RX5 region exported, inverse checked and persisted');return {'chart':dict(cp,uuidHex=job.bytes_le.hex()),'manifest':pin(mp),'ownerInventory':inventory,'name':r['name'],'coreBBox':r['bbox'],'exportBBox':r['exportBBox'],'candidateNodes':len(selected),'recordOccurrences':len(owner_rows),'uniqueUIDs':len(uids),'metrics':manifest['metrics'],'package':None}

def verify_inverse(parent,raw,nodes,owners,resources,documents):
    """Every retained native byte, including whole rings/holes/Z/reverse, per export."""
    blocks=wire.blocks(raw);bytile={tuple(x['tile']):x for x in nodes};payload_count=0;elements=0;queries=0;maximum=0
    need(raw[56:72]!=parent.header[56:72]and uuid.UUID(bytes_le=raw[56:72]).int!=0,'OWN_REGION_UUID')
    for n in nodes:
        t=tuple(n['tile']);b=blocks[n['nodeOffset']];need(b.flags==n['outputFlags'],'EXPECTED_REGION_NODE_FLAGS');l,x,y=t
        for q in range(4):
            child=bytile.get((l+1,2*x+(q&1),2*y+(q>>1)));need(struct.unpack_from('<Q',b.prefix,q*8)[0]==(child['nodeOffset']if child else 0),'EXACT_REGION_CHILD_PATHS')
        if b.flags&2:need(len(b.payload)==32*n['records'],'EXPECTED_REGION_RECORD_COUNT');payload_count+=1
    for row in owners:
        old_record=parent.range(row['parentNodeOffset'],row['parentOrdinal']*32,32);need(old_record.hex()==row['originalRecord32Hex'],'EXACT_INDEXED_PARENT_RECORD');old=F.unpack(old_record);nb=blocks[row['nodeOffset']];new_record=nb.payload[row['outputOrdinal']*32:row['outputOrdinal']*32+32];new=F.unpack(new_record);need(new_record.hex()==row['outputRecord32Hex']and old[:1]==new[:1]and old[3:]==new[3:],'ONLY_IO_RO_CHANGED')
        oi,oc,orr,oa=struct.unpack_from('<4Q',parent.block(row['parentNodeOffset'])[5],32);ni,nc,nr,na=struct.unpack_from('<4Q',nb.prefix,32);old_idx=parent.idx(oi,old[1]);ix=blocks[ni].payload;n=struct.unpack_from('<I',ix,new[1])[0];new_idx=ix[new[1]:new[1]+4+4*n];need(old_idx[:4]==new_idx[:4],'ALL_PRIMITIVES_PRESENT_ORDERED');geometry_hash=hashlib.sha256()
        for (a,),(b,)in zip(struct.iter_unpack('<I',old_idx[4:]),struct.iter_unpack('<I',new_idx[4:])):
            need(a>>24==b>>24,'EXACT_USAGE_AND_REVERSE');el,stride=parent.element(oc,a&0xffffff,old[6]);off=b&0xffffff;maximum=max(maximum,off);need(blocks[nc].payload[off:off+len(el)]==el,'EXACT_ORIGINAL_ELEMENT_HEADER_XYZ_RING_CLOSURE');geometry_hash.update(bytes([a>>24])+el);elements+=1
        of,ao=local.render_frame(parent.stream(orr),old[2]);nf,bo=local.render_frame(local.Stream(len(blocks[nr].payload),lambda a,n:blocks[nr].payload[a:a+n]),new[2]);need(of[:2]==nf[:2]and of[6:]==nf[6:],'ALL_NATIVE_PROGRAMMES_AND_OPAQUE_ENTRIES_EXACT');oq=local.additional_frame(parent.stream(oa),ao);nq=local.additional_frame(local.Stream(len(blocks[na].payload),lambda a,n:blocks[na].payload[a:a+n]),bo);need(oq==nq,'ALL_QUERY_ATTRIBUTES_FFPT_AND_DOC_REFERENCES_EXACT');queries+=1;row['querySHA256']=sha(oq);row['programmeEntriesSHA256']=sha(of[:2]+of[6:]);row['geometryUsageSequenceSHA256']=geometry_hash.hexdigest()
    for r in resources:
        matches=[b for b in blocks.values()if b.disk_id==r['id']and len(b.payload)==r['bytes']and sha(b.payload)==r['sha256']];need(matches,'EXACT_PRESENTATION_DICTIONARY_SOURCE_RESOURCE')
    chain=wire.chain(blocks,struct.unpack_from('<Q',raw,200)[0]);need(wire.dictionary(chain[0].payload)==[d['name']for d in documents]and len(chain)==len(documents)+1,'EXACT_NATIVE_DOCUMENT_CATALOG_CLOSURE')
    for b,d in zip(chain[1:],documents):need(b.disk_id==d['id']and len(b.payload)==d['bytes']and sha(b.payload)==d['sha256'],'EXACT_ORIGINAL_DOCUMENT_BYTES_AND_IDS')
    return {'status':'PASS_EVERY_SELECTED_OWNER_AND_RESOURCE','nodes':len(nodes),'payloadNodes':payload_count,'recordOccurrences':len(owners),'originalCompleteElements':elements,'originalQueryFrames':queries,'maximumCoordinateStartLow24':maximum,'allNativeProgrammeAndOpaqueBytesExact':True,'noNewGeometryClipOrQuantization':True,'sourceTileAndRootFlagsUnchanged':True,'fullSourceReproductionFooterRequiredByThisInverse':False,'nativeCalls':0}
