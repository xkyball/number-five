"""Bounded own host wire parsing. No CPU, original-library or native imports."""

import struct

from dataclasses import dataclass

PREFIX={0:0,1:32,2:32,3:64,16:24,64:0}

HEADER_SLOTS=(136,144,160,168,184,200)

FEATURE=struct.Struct('<Q5I4B')

def need(v,s):
    if not v:raise ValueError(s)

@dataclass
class Block:
    offset:int
    disk_id:int
    flags:int
    prefix:bytes
    payload:bytes
    next_offset:int
    @property
    def data_offset(self):return self.offset+16+len(self.prefix)

def blocks(raw):
    need(len(raw)>=240 and raw[:8]==b'\x01RayRX5\0','RX5_OWN_WIRE_HEADER')
    pos=240;out={}
    while pos<len(raw):
        need(pos+16<=len(raw),'BLOCK_HEADER_RANGE')
        bid,flags,n,nxt=struct.unpack_from('<HHIQ',raw,pos)
        need(flags in PREFIX and n>=PREFIX[flags] and pos+16+n<=len(raw),'BLOCK_BODY_RANGE')
        k=PREFIX[flags];out[pos]=Block(pos,bid,flags,raw[pos+16:pos+16+k],raw[pos+16+k:pos+16+n],nxt)
        pos+=16+n
    need(pos==len(raw),'EXACT_WIRE_END')
    for b in out.values():
        need(not b.next_offset or b.next_offset in out,'NEXT_OFFSET_RESOLVES')
        if b.flags in (1,2,3):
            for off in range(0,len(b.prefix),8):
                q=struct.unpack_from('<Q',b.prefix,off)[0];need(not q or q in out,'PREFIX_OFFSET_RESOLVES')
    for slot in HEADER_SLOTS:
        q=struct.unpack_from('<Q',raw,slot)[0];need(not q or q in out,'HEADER_OFFSET_RESOLVES')
    return out

def chain(all_blocks,start):
    seen=set();out=[]
    while start:
        need(start in all_blocks and start not in seen,'CHAIN_CYCLE_OR_MISSING')
        seen.add(start);b=all_blocks[start];out.append(b);start=b.next_offset
    return out

def dictionary(payload):
    need(len(payload)>=4,'DICT_COUNT');n=struct.unpack_from('<I',payload)[0]
    need(n<=100000 and 4+8*n<=len(payload),'DICT_TABLE_RANGE');out=[]
    for i in range(n):
        off,size=struct.unpack_from('<II',payload,4+8*i)
        need(off>=4+8*n and off+size<=len(payload),'DICT_TEXT_RANGE')
        out.append(payload[off:off+size].decode('utf8'))
    return out

def encode_dictionary(strings):
    need(len(strings)<1<<32,'DICT_COUNT_U32');cursor=4+8*len(strings);tab=[];body=[]
    for text in strings:
        b=text.encode('utf8');need(cursor+len(b)<1<<32,'DICT_OFFSET_U32')
        tab.append(struct.pack('<II',cursor,len(b)));body.append(b);cursor+=len(b)
    return struct.pack('<I',len(strings))+b''.join(tab)+b''.join(body)

def rebase_indices(raw,coordinate_base):
    need(coordinate_base>=0 and coordinate_base%4==0,'COORD_BASE_ALIGNMENT')
    out=bytearray(raw);pos=0;words=0;maximum=0
    while pos<len(raw):
        need(pos+4<=len(raw),'INDEX_COUNT_RANGE');n=struct.unpack_from('<I',raw,pos)[0];pos+=4
        need(n<=1<<24 and pos+4*n<=len(raw),'INDEX_WORD_RANGE')
        for i in range(n):
            off=pos+4*i;v=struct.unpack_from('<I',raw,off)[0];target=(v&0xffffff)+coordinate_base
            need(target<=0xffffff,'PACKED_COORDINATE_START_LOW24_OVERFLOW')
            struct.pack_into('<I',out,off,(v&0xff000000)|target);maximum=max(maximum,target);words+=1
        pos+=4*n
    need(pos==len(raw),'INDEX_EXACT_END');return bytes(out),dict(words=words,maximumCoordinateStart=maximum)

def rebase_render(raw,additional_base):
    out=bytearray(raw);pos=0;starts=[]
    while pos<len(raw):
        starts.append(pos);need(pos+6<=len(raw),'RENDER_HEAD_RANGE');n,ao=struct.unpack_from('<HI',raw,pos)
        need(ao+additional_base<1<<32,'ADDITIONAL_OFFSET_U32');struct.pack_into('<I',out,pos+2,ao+additional_base);pos+=6
        for _ in range(n):
            need(pos+6<=len(raw),'RENDER_ENTRY_RANGE');key,size=struct.unpack_from('<IH',raw,pos);pos+=6
            need(pos+size<=len(raw),'RENDER_PAYLOAD_RANGE');pos+=size
    need(pos==len(raw),'RENDER_EXACT_END');return bytes(out),starts

def rewrite_additional(raw,document_codes,document_map):
    """Preserve typed frames; rename only exact document references for bound codes."""
    pos=0;out=bytearray();offset_map={};changes=[]
    while pos<len(raw):
        old=pos;offset_map[old]=len(out);need(pos+10<=len(raw),'ADDITIONAL_HEADER_RANGE')
        out+=raw[pos:pos+8];pos+=8
        for section in ('regular','national'):
            need(pos+2<=len(raw),'ADDITIONAL_COUNT_RANGE');n=struct.unpack_from('<H',raw,pos)[0];out+=raw[pos:pos+2];pos+=2
            for _ in range(n):
                need(pos+2<=len(raw),'ATTRIBUTE_CODE_RANGE');code=struct.unpack_from('<H',raw,pos)[0];out+=raw[pos:pos+2];pos+=2
                end=raw.find(b'\0',pos);need(end>=pos,'ATTRIBUTE_TERMINATOR');text=raw[pos:end].decode('utf8')
                replacement=document_map.get(text,text)if code in document_codes else text
                if replacement!=text:changes.append(dict(oldFrameOffset=old,section=section,code=code,oldName=text,newName=replacement))
                out+=replacement.encode('utf8')+b'\0';pos=end+1
        need(pos+2<=len(raw),'FFPT_COUNT_RANGE');n=struct.unpack_from('<H',raw,pos)[0];out+=raw[pos:pos+2];pos+=2
        for _ in range(n):
            need(pos+9<=len(raw),'FFPT_HEADER_RANGE');out+=raw[pos:pos+9];pos+=9
            end=raw.find(b'\0',pos);need(end>=pos,'FFPT_TERMINATOR');out+=raw[pos:end+1];pos=end+1
    need(pos==len(raw),'ADDITIONAL_EXACT_END');return bytes(out),offset_map,changes

def rebase_render_map(raw,additional_base,offset_map):
    out=bytearray(raw);pos=0;starts=[]
    while pos<len(raw):
        starts.append(pos);need(pos+6<=len(raw),'RENDER_HEAD_RANGE');n,ao=struct.unpack_from('<HI',raw,pos)
        need(ao in offset_map,'REAL_ADDITIONAL_FRAME_START');target=additional_base+offset_map[ao]
        need(target<1<<32,'ADDITIONAL_OFFSET_U32');struct.pack_into('<I',out,pos+2,target);pos+=6
        for _ in range(n):
            need(pos+6<=len(raw),'RENDER_ENTRY_RANGE');_,size=struct.unpack_from('<IH',raw,pos);pos+=6
            need(pos+size<=len(raw),'RENDER_PAYLOAD_RANGE');pos+=size
    need(pos==len(raw),'RENDER_EXACT_END');return bytes(out),starts

@dataclass
class AuthoredBlock:
    name:str
    disk_id:int
    flags:int
    prefix:bytes
    payload:bytes
    next_name:str|None=None
    references:tuple=()

def assemble(header,authored,header_refs):
    need(len(header)==240,'EXPLICIT240_HEADER')
    need(all(struct.unpack_from('<Q',header,o)[0]==0 for o in (152,176,192)),'PLAIN_AUTH_AND_VENDOR_POINTERS_ZERO')
    need(set(header_refs)==set(HEADER_SLOTS),'ALL_KNOWN_HEADER_POINTERS_EXPLICIT')
    cursor=240;offsets={}
    for b in authored:
        need(b.name not in offsets and b.flags in PREFIX and len(b.prefix)==PREFIX[b.flags],'AUTHORED_BLOCK_BINDING')
        need(len(b.prefix)+len(b.payload)<1<<32 and 0<=b.disk_id<1<<16,'BLOCK_FIELDS_RANGE')
        if b.flags in (1,2,3):
            need(b.prefix==bytes(len(b.prefix)),'NO_STALE_ABSOLUTE_PREFIX_POINTERS')
            need(len(b.references)==len(b.prefix)//8 and {o for o,_ in b.references}==set(range(0,len(b.prefix),8)),'EVERY_PREFIX_POINTER_EXPLICIT')
        offsets[b.name]=cursor;cursor+=16+len(b.prefix)+len(b.payload)
    def resolve(name):
        need(name is None or name in offsets,'SYMBOLIC_OFFSET_BINDING');return 0 if name is None else offsets[name]
    out=bytearray(header)
    for slot in HEADER_SLOTS:struct.pack_into('<Q',out,slot,resolve(header_refs.get(slot)))
    for b in authored:
        prefix=bytearray(b.prefix)
        for off,name in b.references:need(off%8==0 and off+8<=len(prefix),'PREFIX_REFERENCE');struct.pack_into('<Q',prefix,off,resolve(name))
        out+=struct.pack('<HHIQ',b.disk_id,b.flags,len(prefix)+len(b.payload),resolve(b.next_name))+prefix+b.payload
    need(len(out)==cursor,'ASSEMBLY_EXACT_SIZE');return bytes(out),offsets

def rebase_features(raw,index_base,render_base,dictionary_map):
    need(len(raw)%32==0,'FEATURE32_RANGE');out=bytearray();ids=[]
    for row in FEATURE.iter_unpack(raw):
        a=list(row);need(a[1]+index_base<1<<32 and a[2]+render_base<1<<32,'STORED_U32_REBASE')
        need(a[3] in dictionary_map,'STORED_DICTIONARY_MAPPING');a[1]+=index_base;a[2]+=render_base;a[3]=dictionary_map[a[3]]
        out+=FEATURE.pack(*a);ids.append(a[0])
    if len(set(ids))!=len(ids):
        from ..rx5.soundings import validate_sounding_pairs
        validate_sounding_pairs(raw)
    return bytes(out),ids
