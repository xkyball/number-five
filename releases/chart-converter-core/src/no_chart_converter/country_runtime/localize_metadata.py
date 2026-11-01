"""Lossless per-node RX5 metadata relocation. Coordinate arena and global DI/docs stay fixed."""

import hashlib, struct

F=struct.Struct('<Q5I4B')

def need(v,s):
    if not v:raise ValueError(s)

def sha(b):return hashlib.sha256(b).hexdigest()

class Stream:
    def __init__(self,size,read):self.size=size;self.reader=read;self.ranges=[]
    def read(self,at,n):
        need(type(at)is int and type(n)is int and 0<=at<=self.size and 0<=n<=self.size-at,'STREAM_RANGE')
        b=self.reader(at,n);need(type(b)is bytes and len(b)==n,'EXACT_IMMUTABLE_RANGE');self.ranges.append((at,n,sha(b)));return b
    def cend(self,at):
        pos=at
        while pos<self.size:
            b=self.read(pos,min(4096,self.size-pos));end=b.find(b'\0')
            if end>=0:return pos+end+1
            pos+=len(b)
        raise ValueError('MISSING_TYPED_STRING_NUL')

def index_frame(s,at):
    n=struct.unpack('<I',s.read(at,4))[0];return s.read(at,4+4*n)

def render_frame(s,at):
    n,ao=struct.unpack('<HI',s.read(at,6));end=at+6;seen=set()
    for _ in range(n):
        key,count=struct.unpack('<IH',s.read(end,6));need(key not in seen,'DUPLICATE_RENDER_KEY');seen.add(key);end+=6+count;need(end<=s.size,'RENDER_PAYLOAD_RANGE')
    return s.read(at,end-at),ao

def additional_frame(s,at):
    start=at;s.read(at,8);at+=8
    for _ in range(2):
        n=struct.unpack('<H',s.read(at,2))[0];at+=2
        for _ in range(n):s.read(at,2);at=s.cend(at+2)
    n=struct.unpack('<H',s.read(at,2))[0];at+=2
    for _ in range(n):s.read(at,9);at=s.cend(at+9)
    return s.read(start,at-start)

def localize(features,indices,render,additional,*,coordinate_bytes):
    """Streams have bounded read callbacks; return features,idx,render,AO,receipt.

    Only StoredFeature IO/RO and Render AO move. Every IDX word (usage+Low24),
    all render keys/payloads, typed query/FFPT bytes, UIDs, DI, level and flags
    remain exact. No SourceFooter, document or dictionary rewrite occurs.
    """
    need(type(features)is bytes and len(features)%32==0,'FEATURE32')
    out=bytearray();ip=bytearray();rp=bytearray();ap=bytearray();im={};rm={};am={};proof=[]
    for ordinal,row in enumerate(F.iter_unpack(features)):
        fields=list(row);io,ro=fields[1:3]
        if io not in im:
            raw=index_frame(indices,io)
            for w, in struct.iter_unpack('<I',raw[4:]):need((w&0xffffff)+4<=coordinate_bytes,'COORDINATE_ARENA_REFERENCE')
            im[io]=len(ip);ip+=raw
        if ro not in rm:
            raw,ao=render_frame(render,ro)
            if ao not in am:
                am[ao]=len(ap);ap+=additional_frame(additional,ao)
            rewritten=bytearray(raw);struct.pack_into('<I',rewritten,2,am[ao]);rm[ro]=len(rp);rp+=rewritten
            proof.append({'oldRenderOffset':ro,'newRenderOffset':rm[ro],'oldAdditionalOffset':ao,'newAdditionalOffset':am[ao],'originalRenderSHA256':sha(raw),'renderBytes':len(raw)})
        fields[1]=im[io];fields[2]=rm[ro];new=F.pack(*fields);old=F.pack(*row)
        need(new[:8]==old[:8] and new[16:]==old[16:],'ONLY_IO_RO_CHANGED');out+=new
    # Independent relocated-frame inverse, retaining all opaque render payloads.
    ni=Stream(len(ip),lambda a,n:bytes(ip[a:a+n]));nr=Stream(len(rp),lambda a,n:bytes(rp[a:a+n]));na=Stream(len(ap),lambda a,n:bytes(ap[a:a+n]))
    for old,new in zip(F.iter_unpack(features),F.iter_unpack(out)):
        need(index_frame(indices,old[1])==index_frame(ni,new[1]),'ALL_INDEX_WORDS_BYTE_EXACT')
        a,ao=render_frame(render,old[2]);b,bo=render_frame(nr,new[2]);need(a[:2]==b[:2] and a[6:]==b[6:],'ALL_RENDER_KEYS_PAYLOADS_BYTE_EXACT')
        need(additional_frame(additional,ao)==additional_frame(na,bo),'ALL_TYPED_QUERY_AND_FFPT_BYTES_EXACT')
    receipt={'format':'no5-node-local-metadata-rebase/1','records':len(features)//32,'indexArrays':len(im),'renderFrames':len(rm),'additionalFrames':len(am),'bytes':{'features':len(out),'indices':len(ip),'render':len(rp),'additional':len(ap)},'indexOldToNew':im,'renderOldToNew':rm,'additionalOldToNew':am,'renderProof':proof,'coordinateArenaUnchanged':True,'indexUsageAndLow24Unchanged':True,'dictionaryAndDocumentReferencesUnchanged':True,'sourceQueryBytesUnchanged':True,'unrelatedRenderKeyPayloadsUnchanged':True,'onlyStoredIO_ROAndRenderAOChanged':True}
    return bytes(out),bytes(ip),bytes(rp),bytes(ap),receipt
