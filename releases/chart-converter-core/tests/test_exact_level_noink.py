"""Invented charts test actual wire barriers; no Original calls or acceptance."""
import copy
import hashlib
import struct

import pytest

from no_chart_converter.rx5 import exact_level_noink as e
from no_chart_converter.rx5 import render_noink,wire
from no_chart_converter.rx5.header import new_plain_vector_header
from no_chart_converter.rx5.model import native_tree

TILE=(17,72240,78668)
Q=2147483649


def sha(b):return hashlib.sha256(b).hexdigest()
def hashes(buffers):return dict(zip(e.NAMES,map(sha,buffers)))


def fixture(*, near=False, root=1):
    x=TILE[1]*Q//(1<<17);y=TILE[2]*Q//(1<<17)
    points=[(x-2000000,y-40000000),(x-1990000,y-40000000),
            (x-1990000,y-39990000),(x-2000000,y-40000000)]
    if near:points=[(x+1000,y+1000),(x+2000,y+1000),(x+2000,y+2000),(x+1000,y+1000)]
    co=struct.pack('<I',root<<24|4)+b''.join(struct.pack('<II',*p)for p in points)
    ix=struct.pack('<II',1,24<<24)
    fi=b''.join(wire.FEATURE.pack(uid,0,0,70,0,42,3,0,0,0)for uid in(101,102))
    contexts=[(0x100ff,b'CATEGORY'),(0x30001,b'AC(DEPVS);'),(0x10103,b'DETAIL'),(0x30000,b'AC(DEPVS);')]
    ro=struct.pack('<HI',len(contexts),0)+b''.join(struct.pack('<IH',k,len(v))+v for k,v in contexts)
    ao=struct.pack('<QHHH',101,0,0,0)
    return fi,ix,co,ro,ao


def prepare(buffers=None, tile=TILE):
    buffers=fixture()if buffers is None else buffers
    return e.prepare_exact_level(*buffers,exact_tile=tile,owner_uids=[101],expected_input_sha256=hashes(buffers))


def packet(prepared, *, missing=None, swapped=False, corrupt_restore=False, grandchild=False, blank_restore=False):
    old=prepared['restoreBuffers'];new=prepared['blankBuffers'];tile=tuple(prepared['plan']['exactTile']);z,x,y=tile
    authored=[wire.AuthoredBlock('i',0x1101,0,b'',old[1]),wire.AuthoredBlock('c',0x1102,0,b'',old[2]),
      wire.AuthoredBlock('a',0x1104,0,b'',old[4]),wire.AuthoredBlock('r-original',0x1103,0,b'',old[3]),
      wire.AuthoredBlock('r-blank',0x1103,0,b'',new[3]),wire.AuthoredBlock('resource',65,0,b'',b'OWN RESOURCE')]
    for level in range(z):
        next_level=level+1
        slot=((x>>(z-next_level))&1)|(((y>>(z-next_level))&1)<<1)
        target='blank'if next_level==z else'routing-'+str(next_level)
        refs=tuple((8*i,target if i==slot else None)for i in range(4))
        authored.append(wire.AuthoredBlock('routing-'+str(level),0x6000+level,1,bytes(32),b'',references=refs))
    slots=['restore-'+str(i)if i!=missing else None for i in range(4)]
    if swapped:slots[0],slots[1]=slots[1],slots[0]
    def streams(ro):return((32,'i'),(40,'c'),(48,ro),(56,'a'))
    authored.append(wire.AuthoredBlock('blank',0x6000+z,3,bytes(64),new[0],references=tuple((8*i,n)for i,n in enumerate(slots))+streams('r-blank')))
    for i in range(4):
        if i==missing:continue
        fi=bytearray(old[0]);ro='r-original'
        if corrupt_restore and i==0:fi[24]^=1
        if blank_restore and i==0:fi=bytearray(new[0]);ro='r-blank'
        refs=tuple((8*j,'extra'if grandchild and i==0 and j==0 else None)for j in range(4))+streams(ro)
        authored.append(wire.AuthoredBlock('restore-'+str(i),0x6000+z+1,3,bytes(64),bytes(fi),references=refs))
    if grandchild:
        authored.append(wire.AuthoredBlock('extra',0x6000+z+2,1,bytes(32),b'',references=tuple((8*i,None)for i in range(4))))
    return wire.assemble(new_plain_vector_header(bytes(range(16))),authored,
      {slot:'routing-0'if slot==160 else'resource'if slot==184 else None for slot in wire.HEADER_SLOTS})[0]


def verify(raw,prepared,buffers=None):
    buffers=fixture()if buffers is None else buffers
    return e.verify_materialized_subtree(raw,buffers,exact_tile=prepared['plan']['exactTile'],owner_uids=[101],
      expected_input_sha256=hashes(buffers),expected_plan_sha256=e.plan_sha256(prepared['plan']))


def selected_source(raw,request):
    # Own selector oracle for the algebraic partition; native bodies are a
    # separate pinned source contract, not executed by this test.
    tree=native_tree(raw);candidates=[t for at,t in tree.items()if wire.blocks(raw)[at].flags==3
      and t[0]<=request[0]and(request[1]>>(request[0]-t[0]),request[2]>>(request[0]-t[0]))==t[1:]]
    return max(candidates)


def test_through29_refusal_is_not_relabeled_as_exact_level_failure():
    b=fixture()
    with pytest.raises(ValueError,match='SAFE_NATIVE_PROJECTION_THROUGH29'):
        render_noink.prepare_no_ink_render(*b,source_tile=TILE,owner_uids=[101],expected_input_sha256=hashes(b))
    p=prepare(b)
    cert=p['plan']['owners'][0]['exactLevelCertificate']
    assert cert['exactLevel']==17 and len(cert['parts'])==1
    assert cert['validForDeeperRequestsWithoutRestoreBarrier']is False
    assert p['plan']['mandatoryActualWireRestoreBarrier']is True
    assert p['plan']['standaloneBlankSourceAllowed']is False
    assert p['restoreBuffers']==b
    assert p['blankBuffers'][1]is b[1]and p['blankBuffers'][2]is b[2]and p['blankBuffers'][4]is b[4]


@pytest.mark.parametrize('tile',[TILE,(18,144483,157338)])
def test_actual_four_full_restore_nodes_cover_all_deeper_levels(tile):
    b=fixture();p=prepare(b,tile);raw=packet(p);v=verify(raw,p,b)
    assert len(v['actualRestoreLeaves'])==4
    assert v['everyDeeperNormalRequestSelectsOriginalSceneBuffers']is True
    assert v['nativeSourceScaleFrameQueryAcceptance']is False
    assert v['fullCountryInverseClaim']is False
    assert selected_source(raw,tile)==tile
    z,x,y=tile
    for level in range(z+1,30):
        span=1<<(level-z)
        for dx,dy in((0,0),(span-1,0),(0,span-1),(span-1,span-1),(span//2,span//2)):
            req=(level,x*span+dx,y*span+dy)
            expected=(z+1,req[1]>>(level-z-1),req[2]>>(level-z-1))
            assert selected_source(raw,req)==expected


@pytest.mark.parametrize('slot',range(4))
def test_every_missing_restore_slot_is_refused_from_actual_bytes(slot):
    p=prepare()
    with pytest.raises(ValueError,match='ALL_FOUR_DISTINCT_ACTUAL_RESTORE_CHILDREN'):
        verify(packet(p,missing=slot),p)


@pytest.mark.parametrize('change,reason',[
    ({'corrupt_restore':True},'COMPLETE_ORIGINAL_RESTORE_SCENE'),
    ({'blank_restore':True},'COMPLETE_ORIGINAL_RESTORE_SCENE'),
    ({'grandchild':True},'RESTORE_NODES_MUST_BE_ACTUAL_LEAVES'),
])
def test_wrong_restore_geometry_metadata_or_descendant_shadow_is_refused(change,reason):
    p=prepare()
    with pytest.raises(ValueError,match=reason):verify(packet(p,**change),p)


def test_no_source_approval_boolean_or_claimed_zoom_can_waive_barrier():
    p=prepare();forged=copy.deepcopy(p['plan']);forged['mandatoryActualWireRestoreBarrier']=False;forged['approved']=True
    with pytest.raises(ValueError,match='EXACT_RECOMPUTED_PLAN_SHA256'):
        e.verify_materialized_subtree(packet(p,missing=0),fixture(),exact_tile=TILE,owner_uids=[101],
          expected_input_sha256=hashes(fixture()),expected_plan_sha256=e.plan_sha256(forged))
    with pytest.raises(TypeError):
        e.prepare_exact_level(*fixture(),exact_tile=TILE,owner_uids=[101],expected_input_sha256=hashes(fixture()),maximum_zoom=17)


def test_actual_empty_node_without_children_is_never_standalone_qualified():
    p=prepare();raw=packet(p,missing=0)
    with pytest.raises(ValueError):verify(raw,p)


@pytest.mark.parametrize('args,reason',[
  ({'near':True},'OUTSIDE_EXACT_LEVEL_DRAW_WINDOW'),({'root':0},'WHOLE_CLONED_SCENE_ABSOLUTE_ROOT1')])
def test_exact_level_geometry_and_full_root1_clone_obligations_remain_required(args,reason):
    with pytest.raises(ValueError,match=reason):prepare(fixture(**args))


@pytest.mark.parametrize('tile',[(16,36120,39334),(19,288966,314676),(17,True,1)])
def test_pilot_scope_is_explicit_and_does_not_invent_a_runtime_zoom_limit(tile):
    with pytest.raises(ValueError,match='PILOT_EXACT_LEVEL17_OR18|INTEGER_SOURCE_TILE'):
        prepare(tile=tile)


def test_changed_input_cannot_reuse_a_plan_or_hash_binding():
    b=list(fixture());wrong=hashes(b);wrong['additional']='0'*64
    with pytest.raises(ValueError,match='EXACT_FIVE_INPUT_SHA256'):
        e.prepare_exact_level(*b,exact_tile=TILE,owner_uids=[101],expected_input_sha256=wrong)
