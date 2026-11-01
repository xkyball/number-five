"""Invented structural controls, no private chart or Original runtime fixtures."""
import copy
import hashlib
import struct
import types

import pytest

from no_chart_converter.rx5 import header, no_ink_children as child
from no_chart_converter.rx5.model import native_tree, programmes
from no_chart_converter.rx5.wire import AuthoredBlock, FEATURE, HEADER_SLOTS, assemble, blocks

PARENT=(1,0,0)
A=(3,0,0)
B=(4,3,2)
PROFILES=[dict(sourceTile=PARENT,childTile=A,ownerUIDs=[11]),dict(sourceTile=PARENT,childTile=B,ownerUIDs=[11,22])]


def sha(x):return hashlib.sha256(x).hexdigest()


def frame(text=b'own-values',category=b'own-category',query=0):
    return struct.pack('<HI',3,query)+struct.pack('<IH',0x100ff,len(category))+category+struct.pack('<IH',0x30000,len(text))+text+struct.pack('<IH',0x30001,len(text))+text


def fixture(root=1):
    # Full triangle (not a geometric no-ink proof); a fake qualified policy is
    # isolated below to exercise materializer contracts independently.
    co=struct.pack('<I8I',root<<24|4,100,200,300,200,100,400,100,200)
    ix=struct.pack('<II',1,0x18000000)
    ro=frame();features=b''.join(FEATURE.pack(uid,0,0,3,17,42,3,0,0,0)for uid in(11,22))
    streams=tuple((8*i,None)for i in range(4))+tuple((32+8*i,n)for i,n in enumerate(('i','c','r','a')))
    authored=[AuthoredBlock('i',0x1101,0,b'',ix),AuthoredBlock('c',0x1102,0,b'',co),AuthoredBlock('r',0x1103,0,b'',ro),AuthoredBlock('a',0x1104,0,b'',b'own-query-attributes'),AuthoredBlock('p',0x6001,3,bytes(64),features,references=streams),AuthoredBlock('root',0x6000,1,bytes(32),b'',references=((0,'p'),(8,None),(16,None),(24,None)))]
    return assemble(header.new_plain_vector_header(bytes(range(16))),authored,{s:'root'if s==160 else None for s in HEADER_SLOTS})[0]


@pytest.fixture(autouse=True)
def qualified_policy_control(monkeypatch):
    module=types.ModuleType('own_qualified_policy_fixture');module.__file__=__file__
    def prepare(f,i,c,r,a,*,source_tile,owner_uids,expected_input_sha256):
        assert expected_input_sha256=={k:sha(v)for k,v in zip(child.BUFFER_NAMES,(f,i,c,r,a))}
        out=bytearray(f);ro=bytearray(r);found=set()
        for ordinal,rec in enumerate(FEATURE.iter_unpack(f)):
            if rec[0]not in owner_uids:continue
            found.add(rec[0]);contexts,query=programmes(r,rec[2]);new=frame(text=b'',category=contexts[str(0x100ff)],query=query)
            at=len(ro);ro+=new;struct.pack_into('<I',out,ordinal*32+12,at)
        if found!=set(owner_uids):raise ValueError('REQUESTED_UID_NOT_ELIGIBLE')
        return dict(zip(child.RESULT_KEYS,(bytes(out),i,c,bytes(ro),a)),receipt=dict(ownFixture=True,tile=list(source_tile)))
    module.prepare_no_ink_render=prepare
    monkeypatch.setattr(child,'_policy_module',lambda:module)
    return module


def apply(raw,profiles=PROFILES):
    plan=child.plan_no_ink_children(raw,profiles)
    return child.build_no_ink_children(raw,profiles,expected_source_sha256=sha(raw),expected_plan_sha256=child.no_ink_children_plan_sha256(plan))


def test_shared_path_two_complete_vectors_preserves_parent_and_prefix_inverse():
    raw=fixture();before=blocks(raw);tree=native_tree(raw);at=next(at for at,t in tree.items()if t==PARENT)
    output,receipt=apply(raw);after=blocks(output);by={t:at for at,t in native_tree(output).items()}
    assert set(by)-set(tree.values())=={(2,0,0),A,(3,1,1),B}
    assert [after[by[t]].flags for t in((2,0,0),A,(3,1,1),B)]==[1,3,1,3]
    assert bytes(after[at].payload)==bytes(before[at].payload)
    assert after[at].prefix[32:]==before[at].prefix[32:]
    streams=struct.unpack_from('<4Q',before[at].prefix,32)
    for tile in(A,B):
        node=after[by[tile]];i,c,r,a=struct.unpack_from('<4Q',node.prefix,32)
        assert(i,c,a)==(streams[0],streams[1],streams[3])
        assert len(node.payload)==len(before[at].payload)
        for old,new in zip(FEATURE.iter_unpack(before[at].payload),FEATURE.iter_unpack(node.payload)):
            assert old[:2]+old[3:]==new[:2]+new[3:]
            oldctx,oldq=programmes(before[streams[2]].payload,old[2]);newctx,newq=programmes(after[r].payload,new[2])
            assert oldq==newq and oldctx[str(0x100ff)]==newctx[str(0x100ff)]
    inverse=bytearray(output[:len(raw)])
    for e in receipt['allowedOriginalPrefixEdits']:inverse[e['offset']:e['offset']+e['bytes']]=bytes.fromhex(e['originalHex'])
    assert bytes(inverse)==raw and receipt['sourcePrefixInverseSHA256']==sha(raw)
    assert len(receipt['allowedOriginalPrefixEdits'])==2  # one existing empty slot + UUID
    assert receipt['nativeSourceScaleQueryRasterAcceptance']is False


def test_order_independent_deterministic_uuid_and_noop_empty_request():
    raw=fixture();a,report=apply(raw);b,_=apply(raw,list(reversed(PROFILES)))
    assert a==b and a[56:72]!=raw[56:72]
    unchanged,report=apply(raw,[])
    assert unchanged is raw and report['appendBytes']==0 and report['allowedOriginalPrefixEdits']==[]


def test_exact_source_and_plan_pins():
    raw=fixture();p=child.plan_no_ink_children(raw,PROFILES);digest=child.no_ink_children_plan_sha256(p)
    with pytest.raises(ValueError,match='EXACT_SOURCE_SHA256'):child.build_no_ink_children(raw,PROFILES,expected_source_sha256='0'*64,expected_plan_sha256=digest)
    altered=copy.deepcopy(p);altered['renderPolicy']['sha256']='0'*64
    with pytest.raises(ValueError,match='EXACT_RECOMPUTED_CHILD_PLAN'):child.build_no_ink_children(raw,PROFILES,expected_source_sha256=sha(raw),expected_plan_sha256=child.no_ink_children_plan_sha256(altered))


@pytest.mark.parametrize('profiles',[
 [dict(sourceTile=PARENT,childTile=(1,0,0),ownerUIDs=[11])],
 [dict(sourceTile=PARENT,childTile=(2,2,0),ownerUIDs=[11])],
 [dict(sourceTile=PARENT,childTile=(2,False,0),ownerUIDs=[11])],
 [dict(sourceTile=PARENT,childTile=A,ownerUIDs=[])],
 [dict(sourceTile=PARENT,childTile=A,ownerUIDs=[11,11])],
 [dict(sourceTile=PARENT,childTile=A,ownerUIDs=[99])],
 [PROFILES[0],PROFILES[0]],
 [dict(sourceTile=PARENT,childTile=A,ownerUIDs=[11],autoApply=True)],
 ])
def test_invalid_or_unqualified_profiles_fail_closed(profiles):
    with pytest.raises(ValueError):child.plan_no_ink_children(fixture(),profiles)


def test_root0_and_existing_child_refused():
    with pytest.raises(ValueError,match='COMPLETE_PARENT_ROOT1_ONLY'):apply(fixture(root=0))
    output,_=apply(fixture())
    with pytest.raises(ValueError):apply(output)


@pytest.mark.parametrize('mutation', ['geometry','additional','feature','category','query','context_order','unrequested','ro_not_append','draw_not_empty'])
def test_policy_result_cannot_broaden_allowed_delta(qualified_policy_control,mutation):
    original=qualified_policy_control.prepare_no_ink_render
    def corrupt(*args,**kwargs):
        result=original(*args,**kwargs)
        if mutation=='geometry':result['coordinateBytes']+=b'X'
        elif mutation=='additional':result['additionalBytes']+=b'X'
        elif mutation=='feature':b=bytearray(result['featureBytes']);b[24]^=1;result['featureBytes']=bytes(b)
        elif mutation=='unrequested':b=bytearray(result['featureBytes']);struct.pack_into('<I',b,44,123);result['featureBytes']=bytes(b)
        elif mutation=='draw_not_empty':
            contexts,query=programmes(args[3],0)
            result['renderBytes']=args[3]+frame(text=b'NOT EMPTY',category=contexts[str(0x100ff)],query=query)
        else:
            b=bytearray(result['renderBytes']);off=struct.unpack_from('<I',result['featureBytes'],12)[0]
            if mutation=='category':b[off+12]^=1
            elif mutation=='query':b[off+2]^=1
            elif mutation=='context_order':struct.pack_into('<I',b,off+6,0x30001)
            elif mutation=='ro_not_append':b[0]^=1
            result['renderBytes']=bytes(b)
        return result
    qualified_policy_control.prepare_no_ink_render=corrupt
    with pytest.raises(ValueError):child.plan_no_ink_children(fixture(),[PROFILES[0]])


def test_single_segment_limit_checked(monkeypatch):
    raw=fixture();p=child.plan_no_ink_children(raw,PROFILES)
    monkeypatch.setattr(header,'FILE_SEGMENT_SIZE',len(raw))
    with pytest.raises(ValueError,match='SINGLE_SEGMENT_EXTENT_REQUIRED'):
        child.build_no_ink_children(raw,PROFILES,expected_source_sha256=sha(raw),expected_plan_sha256=child.no_ink_children_plan_sha256(p))
