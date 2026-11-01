"""Invented own wire bytes, never private chart or native-runtime fixtures."""
import copy
import hashlib
import struct

import pytest

from no_chart_converter.rx5.source_arena import compact_source_arenas,plan_sha256,plan_source_arenas
from no_chart_converter.rx5.model import native_tree,parts
from no_chart_converter.rx5 import header
from no_chart_converter.rx5.wire import AuthoredBlock,FEATURE,HEADER_SLOTS,assemble,blocks

A=(1,0,0)
B=(1,1,0)


def sha(raw):return hashlib.sha256(raw).hexdigest()


def fixture(*,root=1,alias=True):
    # Same coordinate address can be interpreted as XY8 and XYZ12. The union
    # must keep the longer span, not the first kind seen, and signed depth bits.
    co=bytes(20)+struct.pack('<I',root<<24|2)+struct.pack('<IIiIIi',123,456,-17,987,654,-2147483648)+bytes(28)
    ix=bytes(12)+struct.pack('<II',1,0x18000014)+struct.pack('<II',1,0x00000014)+bytes(12)
    frame=20 if alias else 12
    features=FEATURE.pack(10,12,0,3,21,42,3,4,5,6)
    features+=FEATURE.pack(20,frame,0,3,21,129,4,4,5,6)
    features+=FEATURE.pack(20,frame,0,3,21,129,9,4,5,6)
    ro=struct.pack('<HI',1,0)+struct.pack('<IH',196608,4)+b'OWN;'
    head=header.new_plain_vector_header(bytes(range(16)))
    authored=[AuthoredBlock('i',0x1101,0,b'',ix),AuthoredBlock('c',0x1102,0,b'',co),
              AuthoredBlock('r',0x1103,0,b'',ro),AuthoredBlock('a',0x1104,0,b'',b'ORIGINAL QUERY ATTRS'),
              AuthoredBlock('resource',0x41,0,b'',b'UNCHANGED PRESENTATION RESOURCE')]
    streams=tuple((8*i,None)for i in range(4))+tuple((32+8*i,name)for i,name in enumerate(('i','c','r','a')))
    authored+=[AuthoredBlock('aNode',0x6001,3,bytes(64),features,references=streams),
               AuthoredBlock('bNode',0x6001,3,bytes(64),features,references=streams),
               AuthoredBlock('root',0x6000,1,bytes(32),b'',references=((0,'aNode'),(8,'bNode'),(16,None),(24,None)))]
    raw,_=assemble(head,authored,{k:('root'if k==160 else'resource'if k==184 else None)for k in HEADER_SLOTS})
    return raw


def apply(raw,tiles):
    plan=plan_source_arenas(raw,tiles)
    return compact_source_arenas(raw,tiles,expected_source_sha256=sha(raw),expected_plan_sha256=plan_sha256(plan))


@pytest.mark.parametrize('root',[0,1])
def test_append_only_inverse_keeps_full_typed_bytes_twins_and_other_source(root):
    raw=fixture(root=root);source=blocks(raw);tree=native_tree(raw)
    output,report=apply(raw,[A]);after=blocks(output)
    assert native_tree(output)==tree and output[136:240]==raw[136:240]
    assert report['appendBytes']>0 and report['appendedBlocks']==2
    assert report['sourcePrefixInverseSHA256']==sha(raw)
    assert report['geometryDecodeCPUWorkChanged']is False
    at=next(at for at,t in tree.items()if t==A);sib=next(at for at,t in tree.items()if t==B)
    assert after[sib]==source[sib]
    oi,oc,orr,oa=struct.unpack_from('<4Q',source[at].prefix,32)
    ni,nc,nr,na=struct.unpack_from('<4Q',after[at].prefix,32)
    assert (orr,oa)==(nr,na)and after[orr]==source[orr]and after[oa]==source[oa]
    assert after[oc]==source[oc]and after[oi]==source[oi]
    for ordinal in range(3):
        old=source[at].payload[ordinal*32:(ordinal+1)*32]
        new=after[at].payload[ordinal*32:(ordinal+1)*32]
        assert old[:8]+old[12:]==new[:8]+new[12:]
        beforeparts=list(parts(old,source[oi].payload,source[oc].payload))
        afterparts=list(parts(new,after[ni].payload,after[nc].payload))
        assert [(p['usage'],p['raw'])for p in beforeparts]==[(p['usage'],p['raw'])for p in afterparts]
    first,second=after[at].payload[32:64],after[at].payload[64:96]
    assert first[:28]+first[29:]==second[:28]+second[29:]
    assert FEATURE.unpack(first)[1]==FEATURE.unpack(second)[1]
    el=list(parts(first,after[ni].payload,after[nc].payload))[0]['raw']
    assert struct.unpack_from('<i',el,12)[0]==-17
    assert struct.unpack_from('<i',el,24)[0]==-2147483648
    assert len(after[nc].payload)==28  # Maximum XY8/XYZ12 span, shared once.
    assert len(after[ni].payload)==16
    assert report['appliedSources'][0]['typedInverse']['adjacentSoundingTwins']==1
    # Only declared UUID/I/C/+8 fields changed in the complete original file.
    inverse=bytearray(output[:len(raw)])
    for edit in report['allowedOriginalPrefixEdits']:
        inverse[edit['offset']:edit['offset']+edit['bytes']]=bytes.fromhex(edit['originalHex'])
    assert inverse==raw


def test_noop_does_not_append_or_change_uuid_and_rebuild_is_deterministic():
    raw=fixture();out,report=apply(raw,[A,B])
    assert apply(raw,[B,A])[0]==out
    again,nochange=apply(out,[A,B])
    assert again is out and nochange['appendBytes']==0 and nochange['appendedBlocks']==0
    assert nochange['status']=='ALREADY_COMPACT_NO_CHANGE'
    assert nochange['allowedOriginalPrefixEdits']==[]
    assert out[56:72]==again[56:72]
    parsed=blocks(out);tree=native_tree(out)
    aa,bb=[next(at for at,t in tree.items()if t==tile)for tile in(A,B)]
    # Per-source private blocks remain independent, not content-deduplicated.
    assert parsed[aa].prefix[32:48]!=parsed[bb].prefix[32:48]


def test_exact_source_and_plan_binding_fail_closed():
    raw=fixture();plan=plan_source_arenas(raw,[A]);digest=plan_sha256(plan)
    assert plan_source_arenas(raw,[A])==plan
    with pytest.raises(ValueError,match='EXACT_SOURCE_SHA256'):
        compact_source_arenas(raw,[A],expected_source_sha256='0'*64,expected_plan_sha256=digest)
    with pytest.raises(ValueError,match='EXACT_RECOMPUTED_SOURCE_ARENA_PLAN'):
        compact_source_arenas(raw,[B],expected_source_sha256=sha(raw),expected_plan_sha256=digest)
    changed=copy.deepcopy(plan);changed['kernel']['sha256']='0'*64
    with pytest.raises(ValueError,match='EXACT_RECOMPUTED_SOURCE_ARENA_PLAN'):
        compact_source_arenas(raw,[A],expected_source_sha256=sha(raw),expected_plan_sha256=plan_sha256(changed))


@pytest.mark.parametrize('tiles',[[],[A,A],[(1,True,0)],[(1,-1,0)],[(1,2,0)],[(1,0,1)],[(0,0,0)]])
def test_invalid_absent_or_routing_scope_does_not_apply(tiles):
    with pytest.raises(ValueError):plan_source_arenas(fixture(),tiles)


def test_unknown_root_flag_is_not_reinterpreted():
    with pytest.raises(ValueError,match='KNOWN_ROOT0_OR_ROOT1'):
        plan_source_arenas(fixture(root=2),[A])


@pytest.mark.parametrize('offset',[8,16,24,32,40,48,72,80,88,104])
def test_missing_required_header_words_are_not_silently_repaired(offset):
    raw=bytearray(fixture());struct.pack_into('<Q',raw,offset,0)
    with pytest.raises(ValueError,match='RX5_PLAIN_HEADER_UNSIGNED64_AT_'):
        plan_source_arenas(bytes(raw),[A])


def test_final_append_extent_is_checked_against_single_segment_profile(monkeypatch):
    raw=fixture();plan=plan_source_arenas(raw,[A])
    # Small branch probe: the real fixed-format descriptor remains unchanged;
    # simulate reaching its physical extent without allocating a GiB fixture.
    monkeypatch.setattr(header,'FILE_SEGMENT_SIZE',len(raw))
    with pytest.raises(ValueError,match='SINGLE_SEGMENT_EXTENT_REQUIRED'):
        compact_source_arenas(raw,[A],expected_source_sha256=sha(raw),expected_plan_sha256=plan_sha256(plan))
