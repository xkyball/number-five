"""Pure exact-L20 whole-ring pilot: no new vertices or implicit global apply."""
import hashlib
import json
import struct

from no_chart_converter.rx5 import header, model, wire
from no_chart_converter.rx5.integer_region_certificate import ring_integer_domain, INT_MAX

NAMES=('features','indices','coordinates','render','additional')
OUTPUTS=('featureBytes','indexBytes','coordinateBytes','renderBytes','additionalBytes')
POLICY='exact-L20-root1-exterior-whole-ring-subset/1'
HALO=128
ROOT=2147483649


def _sha(raw):return hashlib.sha256(raw).hexdigest()
def _pins(buffers):return {name:dict(bytes=len(raw),sha256=_sha(raw))for name,raw in zip(NAMES,buffers)}


def _extent(proofs):
    xlo=min(p['integerXBounds'][0]for p in proofs)
    xhi=max(p['integerXBounds'][1]for p in proofs)
    height=sum(p['maximumNonemptyRegionHeight']for p in proofs)
    wire.need(xhi-xlo+2<=INT_MAX and height+2<=INT_MAX,'S32_UNION_AND_FILLRECT_EXTENTS')
    return dict(globalIntegerXBounds=[xlo,xhi],maximumOutputWidth=xhi-xlo+2,
        maximumCoalescedHeightBySumOfInputHeights=height,validExteriorUnionEndpointContract=True)


def _outside(bounds, low, high):return bounds[1]<low or bounds[0]>high


def prepare_scoped_whole_ring_subset(features,indices,coordinates,render,additional,*,
        source_tile,owner_uid,expected_input_sha256,source_header,
        expected_header_sha256,source_file_bytes):
    """Keep P0,last and every unproved part; remove only whole outside rings.

    Only one explicit source20/unrotated256 view with128px halo is admitted.
    All input rings must have normal signed, valid integer QRegion operands.
    No every-descendant or global query/containment equality is claimed.
    The materializer must keep canonical parents and create all4 actual full
    canonical children21 before using this scoped scene in an offline file.
    """
    buffers=(features,indices,coordinates,render,additional)
    wire.need(all(type(v)is bytes for v in buffers),'IMMUTABLE_FIVE_BUFFERS')
    pins=_pins(buffers)
    wire.need(type(expected_input_sha256)is dict and set(expected_input_sha256)==set(NAMES)
        and all(expected_input_sha256[n]==pins[n]['sha256']for n in NAMES),'EXACT_FIVE_INPUT_SHA_BINDING')
    wire.need(type(source_header)is bytes and _sha(source_header)==expected_header_sha256,'EXACT_HEADER_SHA_BINDING')
    wire.need(type(source_file_bytes)is int,'EXPLICIT_FILE_EXTENT')
    hc=header.validate_plain_vector_header(source_header,file_bytes=source_file_bytes)
    wire.need(type(source_tile)in(tuple,list)and len(source_tile)==3 and all(type(v)is int for v in source_tile),'EXACT_SOURCE_TILE')
    z,x,y=source_tile
    wire.need(z==20 and 0<=x<1<<z and 0<=y<1<<z,'EXACT_SOURCE_LEVEL20_ONLY')
    wire.need(type(owner_uid)is int and 0<=owner_uid<1<<64,'OWNER_UID_U64')
    wire.need(len(features)%32==0,'WHOLE_FEATURE32')
    owners=[i for i in range(len(features)//32)if struct.unpack_from('<Q',features,32*i)[0]==owner_uid]
    wire.need(len(owners)==1,'EXACTLY_ONE_SELECTED_OWNER')
    ordinal=owners[0];record=features[ordinal*32:(ordinal+1)*32];f=wire.FEATURE.unpack(record)
    wire.need(f[6]==3,'KIND3_REQUIRED')
    contexts,query=model.programmes(render,f[2])
    for key in('196608','196609'):
        wire.need(key in contexts and contexts[key],'TWO_NONEMPTY_PURE_AC_PROGRAMMES')
        tokens=contexts[key].split(b';')
        if tokens and tokens[-1]==b'':tokens.pop()
        import re
        wire.need(all(re.fullmatch(rb'AC\([A-Z0-9_]+\)',s)for s in tokens),'TWO_NONEMPTY_PURE_AC_PROGRAMMES')
    pp=list(model.parts(record,indices,coordinates));wire.need(len(pp)>=2,'P0_AND_LAST_REQUIRED')
    rows=[];keep=[];removed=[]
    for part in pp:
        pts=part['points']
        wire.need(part['rootFlag']==1 and part['usage']==24 and len(pts)>=4 and pts[0]==pts[-1],
            'ALL_CLOSED_ROOT1_USAGE24_EXTERIOR')
        proof=ring_integer_domain(pts,tuple(source_tile),z)
        wire.need(proof is not None and proof['mode']=='NORMAL_SIGNED_QROUND',
            'ALL_ORIGINAL_RING_INTEGER_OPERANDS_VALID_AT_EXACT_LEVEL')
        # Bounds include actual rounding/error, edge stepping and right-1.
        outside_integer=(_outside(proof['integerXBounds'],-HALO,256+HALO)
            or _outside(proof['integerYBounds'],-HALO,256+HALO))
        # Exact mathematical Root1 bbox witness for the same query halo.
        den=256*(1<<z)
        rx=[min(p[0]for p in pts)*den,max(p[0]for p in pts)*den]
        ry=[min(p[1]for p in pts)*den,max(p[1]for p in pts)*den]
        outside_root=(_outside(rx,(256*x-HALO)*ROOT,(256*(x+1)+HALO)*ROOT)
            or _outside(ry,(256*y-HALO)*ROOT,(256*(y+1)+HALO)*ROOT))
        protected=part['partOrdinal']in(0,len(pp)-1)
        drop=not protected and outside_integer and outside_root
        (removed if drop else keep).append(part['partOrdinal'])
        rows.append(dict(partOrdinal=part['partOrdinal'],points=part['pointCount'],
            typedElementSHA256=_sha(part['raw']),protectedAnchorOrLast=protected,
            exactRootBBoxOutsideHalo=outside_root,integerRegionBBoxOutsideHalo=outside_integer,
            disposition='REMOVE_WHOLE_RING'if drop else'KEEP',integerProof=proof))
    before_extent=_extent([p['integerProof']for p in rows])
    after_extent=_extent([rows[i]['integerProof']for i in keep])
    if removed:
        new_frame=struct.pack('<I',len(keep))+b''.join(struct.pack('<I',pp[i]['word'])for i in keep)
        wire.need(len(indices)+len(new_frame)<=0xffffffff,'APPENDED_I_FRAME_U32_EXTENT')
        updated=bytearray(features);struct.pack_into('<I',updated,ordinal*32+8,len(indices))
        output=(bytes(updated),indices+new_frame,coordinates,render,additional)
    else:output=buffers
    wire.need(output[0][:ordinal*32+8]+output[0][ordinal*32+12:]==features[:ordinal*32+8]+features[ordinal*32+12:],'ONLY_SELECTED_FI_IO_WORD_CHANGED')
    wire.need(output[1][:len(indices)]==indices and output[2]is coordinates and output[3]is render and output[4]is additional,'CANONICAL_PREFIX_CO_RO_AO_EXACT')
    outparts=list(model.parts(output[0][ordinal*32:(ordinal+1)*32],output[1],output[2]))
    wire.need([(p['word'],p['raw'])for p in outparts]==[(pp[i]['word'],pp[i]['raw'])for i in keep],'DIRECTED_TYPED_SUBSEQUENCE_EXACT')
    receipt=dict(format='no5-scoped-whole-ring-subset/1',policy=POLICY,sourceTile=list(source_tile),
        exactViewLevel=z,protectedHaloPixels=HALO,headerPin=dict(bytes=len(source_header),sha256=_sha(source_header)),
        headerContract=hc,sourceFileBytes=source_file_bytes,inputPins=pins,outputPins=_pins(output),
        ownerUID=owner_uid,recordOrdinal=ordinal,oldIndexOffset=f[1],newIndexOffset=wire.FEATURE.unpack_from(output[0],ordinal*32)[1],
        keptPartOrdinals=keep,removedPartOrdinals=removed,partsBefore=len(pp),partsAfter=len(keep),
        XYBefore=sum(p['pointCount']for p in pp),XYAfter=sum(pp[i]['pointCount']for i in keep),parts=rows,
        originalUnionExtentProof=before_extent,subsetUnionExtentProof=after_extent,
        rootQueryHalo=dict(denominator=256*(1<<z),xmin=(256*x-HALO)*ROOT,ymin=(256*y-HALO)*ROOT,
            xmax=(256*(x+1)+HALO)*ROOT,ymax=(256*(y+1)+HALO)*ROOT),
        originalP0AndLastVisibleTypedInputsExact=True,noNewCoordinates=True,onlySelectedFIIndexWordChanged=True,
        allROAOUIDAttributesOtherOwnersExact=True,rootMathematicalContainmentEqualWithinHalo=True,
        nativeLocalQueryParityPending=True,nativeFrameParityPending=True,globalQueryEqualityClaim=False,
        globalChartApplyAllowed=False,sidecarEnforcesScope=False,defaultPipelineChanged=False,
        requiresActualFourCanonicalRestoreChildrenLevel21=True,canonicalParentsMustRemain=True,
        sameFileHeaderNodeBufferBindingRequired=True,normalSourceBoundUnrotated256ProducerRequired=True,
        disposition='SCOPED_WHOLE_RING_SUBSET'if removed else'NO_OP')
    receipt['preparationSHA256']=_sha(json.dumps(receipt,sort_keys=True,separators=(',',':')).encode('ascii'))
    return dict(zip(OUTPUTS,output),receipt=receipt)
