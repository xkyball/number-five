"""Conservative render-local whole-part selection for immutable RX5 streams.

Only AC/AP and simple LS(SOLD/DASH,width1/2) instructions are eligible.
No whole owner is removed, no new vertex is made and retained part order,
start, usage, wire coordinates, programme and query bytes are unchanged.
The caller must separately close native admission/query semantics and repack
the active coordinate arena before advertising an I/O reduction.
"""
from functools import lru_cache
import hashlib
import math
import re
import struct

F=struct.Struct('<Q5I4B')
Q=2147483649
LIMIT=2000000
CLAUSE=re.compile(rb'(?:AC\([A-Z0-9]+(?:,[0-3])?\)|AP\([A-Z0-9]+\)|LS\((?:SOLD|DASH),[12],[A-Z0-9]+\))')

def need(ok,message):
    if not ok: raise ValueError(message)

def sha(b): return hashlib.sha256(b).hexdigest()

def programme_frame(render,offset):
    need(0<=offset<=len(render)-6,'PROGRAMME_HEADER')
    count,query=struct.unpack_from('<HI',render,offset)
    need(count<=256,'PROGRAMME_CONTEXT_COUNT')
    pos=offset+6; contexts=[]
    for _ in range(count):
        need(pos<=len(render)-6,'PROGRAMME_CONTEXT_HEADER')
        context,size=struct.unpack_from('<IH',render,pos);pos+=6
        need(size<=len(render)-pos,'PROGRAMME_CONTEXT_BYTES')
        contexts.append((context,render[pos:pos+size]));pos+=size
    return contexts,query,render[offset:pos]

def eligible(kind,contexts):
    if kind not in (2,3) or not contexts: return False
    for _,text in contexts:
        clauses=text.split(b';')
        if clauses and not clauses[-1]:clauses.pop()  # One normal terminal separator only.
        if not clauses or any(CLAUSE.fullmatch(c) is None for c in clauses):return False
        if kind==2 and any(not c.startswith(b'LS(') for c in clauses):return False
    return True

def f32(v):return struct.unpack('<f',struct.pack('<f',v))[0]

def qt_round(v):
    # Original Qt4 qRound negative branch, including -1.5 -> -1.
    if v>=0:return math.trunc(f32(v+0.5))
    i=math.trunc(f32(v-1.0))
    return i+math.trunc(f32(f32(v-f32(i))+0.5))

INT32_MIN=-(1<<31)
INT32_MAX=(1<<31)-1


def checked_qt_round(value):
    """Original Qt4 F32 expression, refusing every unsafe signed conversion."""
    if not math.isfinite(value):return None
    if value>=0:
        converted=f32(value+0.5)
        return math.trunc(converted)if INT32_MIN<=converted<=INT32_MAX else None
    converted=f32(value-1.0)
    if not INT32_MIN<=converted<=INT32_MAX:return None
    integer=math.trunc(converted)
    remainder=f32(f32(value-f32(integer))+0.5)
    if not INT32_MIN<=remainder<=INT32_MAX:return None
    result=integer+math.trunc(remainder)
    return result if INT32_MIN<=result<=INT32_MAX else None


def projection_safety(points,tile,*,max_level=29):
    """Finite interval certificate, never an all-destination phase enumeration.

    Root U32/Q is in[0,2); ideal |pixel| < 2**(L+9). Four upper-bound
    F32 ULPs plus eight pixels cover the original arithmetic and Qt expression:
    E=2**max(L-12,0)+8. The exact pre-F32 range is kept E pixels inside signed32
    endpoints. This reserves the same arithmetic error and all Qt conversion
    intermediates; endpoints additionally execute checked_qt_round itself.
    Every descendant translation lies between j=0 and j=scale-1. Parent-relative
    projection bounds cancel j algebraically and widen by E, so no phase sample
    is mistaken for a proof over unsampled translations. Values outside this
    conservative interval keep the original owner/part.
    """
    level,x,y=tile
    need(0<=level<=max_level<=29,'NATIVE_PROJECTION_LEVEL_DOMAIN')
    if not points or any(not(0<=u<1<<32 and 0<=v<1<<32)for u,v in points):return None
    bbox=(min(u for u,v in points),min(v for u,v in points),max(u for u,v in points),max(v for u,v in points))
    proof=[]
    for requested in range(level,max_level+1):
        scale=1<<(requested-level);error=(1<<max(requested-12,0))+8;factor=1<<(requested+8)
        ranges=[];parent_bounds=[];endpoint_values=[];endpoint_rounds=[]
        for axis,base in enumerate((x,y)):
            lo,hi=bbox[axis],bbox[axis+2]
            left=lo*factor-base*scale*256*Q
            right=hi*factor-base*scale*256*Q
            # Exact pre-F32 viewport ranges, across all descendant origins.
            lower=left-(scale-1)*256*Q;upper=right
            if lower<(INT32_MIN+error)*Q or upper>(INT32_MAX-error)*Q:return None
            vals=[f32((math.ldexp(lo/Q,requested)-(base*scale+scale-1))*256.),
                  f32((math.ldexp(hi/Q,requested)-base*scale)*256.)]
            rounds=[checked_qt_round(v)for v in vals]
            if any(v is None for v in rounds):return None
            ranges.append([lower,upper]);parent_bounds.append([left//Q-error,-((-right)//Q)+error])
            endpoint_values.append(vals);endpoint_rounds.append(rounds)
        proof.append(dict(level=requested,axisTranslations=scale,
            intervalDenominator=Q,exactViewportPixelRangeNumerators=ranges,
            parentRelativeIntegerProjectionBounds=[parent_bounds[0][0],parent_bounds[1][0],parent_bounds[0][1],parent_bounds[1][1]],
            conservativeAxisProjectionQtErrorPixels=error,signed32SafetyReservePixels=error,
            actualF32ViewportEndpointValues=endpoint_values,actualQtEndpointValues=endpoint_rounds,
            everyDescendantTranslationCoveredByInterval=True,scalarEndpointEvaluations=4,
            signedConversionIntermediatesSafe=True,bboxRootQ=list(bbox)))
    return proof


def local_whole_parts(features,indexes,coordinates,render,tile,*,guard_pixels=32,max_level=29):
    """Return new feature/index bytes and source-bound, per-part evidence.

    Original coordinate/render buffers are never mutated or copied into the
    result. Strict rational halfplane exclusion is strengthened by conservative
    F64/F32/Qt bounds over every descendant translation through level29.
    Actual signed32 safety is required; uncertain whole parts stay unchanged.
    Leaving at least one part avoids whole-owner/empty-owner policy changes.
    """
    need(len(features)%F.size==0,'WHOLE_FEATURE_RECORDS')
    level,x,y=tile
    need(0<=level<=max_level<=29 and 0<=x<1<<level and 0<=y<1<<level,'TILE_DOMAIN')
    need(type(guard_pixels)is int and 0<=guard_pixels<=4096,'GUARD_DOMAIN')
    d=1<<(level+8);s=Q*256;g=guard_pixels*Q
    domain=(x*s-g,y*s-g,(x+1)*s+g,(y+1)*s+g)
    need(max((1<<32)*d,*map(abs,domain))<1<<63,'SIGNED64_ROOT_COMPARISON')
    def outside(p):
        a,b=p;a*=d;b*=d
        return (1 if a<domain[0] else 0)|(2 if a>domain[2] else 0)|(4 if b<domain[1] else 0)|(8 if b>domain[3] else 0)
    def projected_exclusion(points,bits):
        proof=projection_safety(points,tile,max_level=max_level)
        if proof is None:return None
        for row in proof:
            scale=row['axisTranslations'];side=256*scale;guard=guard_pixels*scale
            bounds=row['parentRelativeIntegerProjectionBounds'];support=3
            common=(1 if bounds[2]+support < -guard else 0)|(2 if bounds[0]-support > side+guard else 0)|(4 if bounds[3]+support < -guard else 0)|(8 if bounds[1]-support > side+guard else 0)
            if not(common&bits):return None
            row.update(strictOutsideBits=common&bits,windowPixels=side,guardPixels=guard,
                simpleStrokeSupportPixels=support,actualScalarEvaluations=4)
        return proof
    output=bytearray(features);newindex=bytearray(indexes);rows=[]
    for ordinal in range(len(features)//F.size):
        wire=features[ordinal*F.size:(ordinal+1)*F.size];fields=list(F.unpack(wire))
        if fields[6] not in(2,3):continue
        contexts,query,frame=programme_frame(render,fields[2])
        if not eligible(fields[6],contexts):continue
        at=fields[1];need(at<=len(indexes)-4,'INDEX_COUNT')
        count=struct.unpack_from('<I',indexes,at)[0]
        need(count<=LIMIT and at+4+4*count<=len(indexes),'INDEX_PART_LIMIT')
        parts=[];valid=True
        for i in range(count):
            word=struct.unpack_from('<I',indexes,at+4+4*i)[0];off=word&0xffffff;usage=word>>24
            need(off<=len(coordinates)-4,'COORDINATE_HEADER')
            header=struct.unpack_from('<I',coordinates,off)[0];n=header&0xffffff
            need(n<=LIMIT and off+4+8*n<=len(coordinates),'COORDINATE_SPAN_LIMIT')
            if header>>24!=1 or usage not in(8,9,16,17,20,21,24,25):valid=False;break
            points=[struct.unpack_from('<II',coordinates,off+4+8*j)for j in range(n)]
            closed=bool(points)and points[0]==points[-1]
            if fields[6]==3 and (n<4 or not closed):valid=False;break
            if fields[6]==2 and n<2:valid=False;break
            bits=15
            for p in points:bits &= outside(p)
            proof=projected_exclusion(points,bits)if bits else None
            parts.append(dict(ordinal=i,word=word,usage=usage,coordinateOffset=off,points=n,
                rootFlag=1,closed=closed,bbox=[min(a for a,b in points),min(b for a,b in points),max(a for a,b in points),max(b for a,b in points)],
                originalSpanSHA256=sha(coordinates[off:off+4+8*n]),strictRationalOutsideBits=bits,
                allDescendantExclusion=proof))
        if not valid:continue
        keep=[p for p in parts if p['allDescendantExclusion'] is None]
        remove=[p for p in parts if p['allDescendantExclusion'] is not None]
        if not remove or not keep:continue
        newoff=len(newindex);need(newoff<1<<32,'NEW_INDEX_OFFSET')
        newindex+=struct.pack('<I',len(keep))+b''.join(struct.pack('<I',p['word'])for p in keep)
        fields[1]=newoff;newwire=F.pack(*fields);output[ordinal*F.size:(ordinal+1)*F.size]=newwire
        need(newwire[:8]==wire[:8] and newwire[12:]==wire[12:],'ONLY_INDEX_POINTER_CHANGED')
        rows.append(dict(uid=str(fields[0]),kind=fields[6],classCode=fields[5],recordOrdinal=ordinal,
            originalStored32Hex=wire.hex(),derivedStored32Hex=newwire.hex(),queryOffset=query,
            originalFullProgrammeFrameSHA256=sha(frame),programmes={str(k):v.decode('ascii')for k,v in contexts},
            originalParts=len(parts),retainedPartOrdinals=[p['ordinal']for p in keep],
            originalPointOccurrences=sum(p['points']for p in parts),derivedPointOccurrences=sum(p['points']for p in keep),
            removedParts=remove,retainedParts=keep))
    return dict(featureBytes=bytes(output),indexBytes=bytes(newindex),receipts=rows,tile=list(tile),
        guardPixels=guard_pixels,throughDestinationLevel=max_level,rootQ=Q,
        coordinateBytesChanged=False,renderBytesChanged=False,wholeOwnersRemoved=0)
