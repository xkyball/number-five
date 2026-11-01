"""Own invented RX5 packets; no Original calls or native acceptance claim."""
import copy
import hashlib
import struct

import pytest
from no_chart_converter.rx5 import bounded_noink_slab as s
from no_chart_converter.rx5 import wire, model, render_noink
from no_chart_converter.rx5.header import new_plain_vector_header

TILE = (18,144483,157338)
Q = 2147483649

def hashes(b):
    return dict(zip(s.NAMES, (hashlib.sha256(v).hexdigest() for v in b)))


def fixture(*, near=False, root=1):
    x=TILE[1]*Q//(1<<18); y=TILE[2]*Q//(1<<18)
    p=[(x-2000000,y-40000000),(x-1990000,y-40000000),
       (x-1990000,y-39990000),(x-2000000,y-40000000)]
    if near:
        p=[(x+100,y+100),(x+200,y+100),(x+200,y+200),(x+100,y+100)]
    co=struct.pack('<I',root<<24|4)+b''.join(struct.pack('<II',*v)for v in p)
    ix=struct.pack('<II',1,24<<24)
    fi=b''.join(wire.FEATURE.pack(uid,0,0,70,0,42,3,0,0,0)for uid in(101,102))
    ctx=[(0x100ff,b'CATEGORY'),(0x30001,b'AC(DEPVS);'),
         (0x10103,b'DETAIL'),(0x30000,b'AC(DEPVS);')]
    ro=struct.pack('<HI',len(ctx),0)+b''.join(struct.pack('<IH',k,len(v))+v for k,v in ctx)
    ao=struct.pack('<QHHH',101,0,0,0)
    return fi,ix,co,ro,ao


def prepare(b=None, tile=TILE):
    b=fixture()if b is None else b
    return s.prepare_bounded_slab(*b,source_tile=tile,owner_uids=[101],expected_input_sha256=hashes(b))


def packet(p, *, missing_route=None, missing_restore=None, routing_vector=False,
           corrupt_restore=False, blank_restore=False, deeper_shadow=False):
    old=p['restoreBuffers']; new=p['blankBuffers']; z,x,y=p['plan']['sourceTile']
    authored=[wire.AuthoredBlock('i',0x1101,0,b'',old[1]),
      wire.AuthoredBlock('c',0x1102,0,b'',old[2]),wire.AuthoredBlock('a',0x1104,0,b'',old[4]),
      wire.AuthoredBlock('ro',0x1103,0,b'',old[3]),wire.AuthoredBlock('blank-ro',0x1103,0,b'',new[3]),
      wire.AuthoredBlock('resource',65,0,b'',b'OWN RESOURCE')]
    for level in range(z):
        child=level+1;slot=((x>>(z-child))&1)|(((y>>(z-child))&1)<<1)
        target='blank'if child==z else'ancestor-'+str(child)
        refs=tuple((8*i,target if i==slot else None)for i in range(4))
        authored.append(wire.AuthoredBlock('ancestor-'+str(level),0x6000+level,1,bytes(32),b'',references=refs))
    def streams(ro):return((32,'i'),(40,'c'),(48,ro),(56,'a'))
    routes=tuple((8*i,None if i==missing_route else'route-'+str(i))for i in range(4))
    authored.append(wire.AuthoredBlock('blank',0x6000+z,3,bytes(64),new[0],references=routes+streams('blank-ro')))
    for parent in range(4):
        if parent==missing_route:continue
        children=tuple((8*j,None if(parent,j)==missing_restore else'restore-%d-%d'%(parent,j))for j in range(4))
        if routing_vector and parent==0:
            authored.append(wire.AuthoredBlock('route-0',0x6001+z,3,bytes(64),new[0],references=children+streams('blank-ro')))
        else:
            authored.append(wire.AuthoredBlock('route-'+str(parent),0x6001+z,1,bytes(32),b'',references=children))
        for slot in range(4):
            if(parent,slot)==missing_restore:continue
            fi=bytearray(old[0]);ro='ro'
            if corrupt_restore and(parent,slot)==(3,3):fi[24]^=1
            if blank_restore and(parent,slot)==(3,3):fi=bytearray(new[0]);ro='blank-ro'
            refs=tuple((8*j,'extra'if deeper_shadow and(parent,slot,j)==(3,3,0)else None)for j in range(4))+streams(ro)
            authored.append(wire.AuthoredBlock('restore-%d-%d'%(parent,slot),0x6002+z,3,bytes(64),bytes(fi),references=refs))
    if deeper_shadow:
        authored.append(wire.AuthoredBlock('extra',0x6003+z,1,bytes(32),b'',references=tuple((8*i,None)for i in range(4))))
    return wire.assemble(new_plain_vector_header(bytes(range(16))),authored,
        {slot:'ancestor-0'if slot==160 else'resource'if slot==184 else None for slot in wire.HEADER_SLOTS})[0]


def verify(raw,p,b=None):
    b=fixture()if b is None else b
    return s.verify_materialized_slab(raw,b,source_tile=p['plan']['sourceTile'],owner_uids=[101],
        expected_input_sha256=hashes(b),expected_plan_sha256=s.plan_sha256(p['plan']))


def select_source(raw,request):
    # Algebraic own oracle. The genuine getChildNode/getVectorTile bodies are
    # pinned separately; these tests do not execute the Original reader.
    parsed=wire.blocks(raw)
    candidates=[t for at,t in model.native_tree(raw,parsed).items()if parsed[at].flags==3
       and t[0]<=request[0]and(request[1]>>(request[0]-t[0]),request[2]>>(request[0]-t[0]))==t[1:]]
    return max(candidates)


def test_both_levels_are_proved_and_all_geometry_query_metadata_stays_exact():
    b=fixture(); p=prepare(b); cert=p['plan']['owners'][0]['slabCertificate']; blank=p['blankBuffers']
    assert cert['blankLevels']==[18,19]
    assert [v['level']for v in cert['parts'][0]['projection']]==[18,19]
    assert cert['levelErrorBounds']==[
      {'level':18,'conversionErrorPixels':72,'protectedHaloGapPixels':96,'supportPixels':3},
      {'level':19,'conversionErrorPixels':136,'protectedHaloGapPixels':192,'supportPixels':3}]
    assert len(p['plan']['restores'])==16 and len(p['plan']['routingChildren'])==4
    assert p['restoreBuffers']==b and blank[1]is b[1]and blank[2]is b[2]and blank[4]is b[4]
    assert blank[0][32:]==b[0][32:] and blank[0][:12]+blank[0][16:32]==b[0][:12]+b[0][16:32]
    before,q=model.programmes(b[3],0); after,q2=model.programmes(blank[3],wire.FEATURE.unpack(blank[0][:32])[2])
    assert q==q2 and list(before)==list(after)
    assert all(after[k]==(b''if int(k) in(0x30000,0x30001)else v)for k,v in before.items())
    assert p['plan']['nativeSourceScaleFrameQueryAcceptance']is False


@pytest.mark.parametrize('tile',[(17,72240,78668),TILE])
def test_all_four_child_windows_use_parent_and_all_deeper_levels_restore(tile):
    p=prepare(tile=tile);raw=packet(p);v=verify(raw,p)
    assert len(v['actualRoutingChildren'])==4 and len(v['actualRestoreLeaves'])==16
    z,x,y=tile
    assert select_source(raw,tile)==tile
    for slot in range(4):
        assert select_source(raw,(z+1,2*x+(slot&1),2*y+(slot>>1)))==tile
    for level in range(z+2,30):
        span=1<<(level-z)
        for dx,dy in((0,0),(span-1,0),(0,span-1),(span-1,span-1),(span//2,span//2)):
            request=(level,x*span+dx,y*span+dy)
            assert select_source(raw,request)==(z+2,request[1]>>(level-z-2),request[2]>>(level-z-2))
    assert v['originalSourceLevelEquivalenceClaim']is False


@pytest.mark.parametrize('slot',range(4))
def test_each_missing_routing_child_is_refused(slot):
    p=prepare()
    with pytest.raises(ValueError):verify(packet(p,missing_route=slot),p)


@pytest.mark.parametrize('parent',range(4))
@pytest.mark.parametrize('slot',range(4))
def test_each_of_sixteen_missing_restore_slots_is_refused(parent,slot):
    p=prepare()
    with pytest.raises(ValueError):verify(packet(p,missing_restore=(parent,slot)),p)


@pytest.mark.parametrize('changes,match',[
  ({'routing_vector':True},'ROUTING_ONLY'),({'corrupt_restore':True},'ORIGINAL_RESTORE_SCENE'),
  ({'blank_restore':True},'ORIGINAL_RESTORE_SCENE'),({'deeper_shadow':True},'ACTUAL_LEAVES')])
def test_routing_payload_changed_restore_and_deeper_shadow_refused(changes,match):
    p=prepare()
    with pytest.raises(ValueError,match=match):verify(packet(p,**changes),p)


def test_forged_plan_and_arbitrary_zoom_cannot_waive_real_barrier():
    p=prepare(); forged=copy.deepcopy(p['plan']);forged['mandatoryActualWireSixteenRestoreBarrier']=False
    with pytest.raises(ValueError,match='RECOMPUTED_SLAB_PLAN'):
        s.verify_materialized_slab(packet(p),fixture(),source_tile=TILE,owner_uids=[101],
          expected_input_sha256=hashes(fixture()),expected_plan_sha256=s.plan_sha256(forged))
    with pytest.raises(TypeError):
        s.prepare_bounded_slab(*fixture(),source_tile=TILE,owner_uids=[101],expected_input_sha256=hashes(fixture()),maximum_zoom=18)


@pytest.mark.parametrize('args,match',[
  ({'near':True},'OUTSIDE_COMPLETE_SOURCE_DRAW_WINDOW'),({'root':0},'WHOLE_CLONED_SCENE_ABSOLUTE_ROOT1')])
def test_uncertified_geometry_or_relative_scene_refused(args,match):
    with pytest.raises(ValueError,match=match):prepare(fixture(**args))


def test_through29_refusal_is_not_waived_for_unbounded_use():
    b=fixture()
    with pytest.raises(ValueError,match='PROJECTION_THROUGH29'):
        render_noink.prepare_no_ink_render(*b,source_tile=TILE,owner_uids=[101],expected_input_sha256=hashes(b))
    assert prepare(b)['plan']['standaloneBlankSourceAllowed']is False


@pytest.mark.parametrize('tile',[(16,36120,39334),(19,288966,314676),(18,True,1)])
def test_no_general_recursion_or_implicit_runtime_zoom_cap(tile):
    with pytest.raises(ValueError):prepare(tile=tile)


def test_actual_input_hash_cannot_be_reused_after_change():
    b=fixture();h=hashes(b);h['render']='0'*64
    with pytest.raises(ValueError,match='EXACT_FIVE_INPUT'):
        s.prepare_bounded_slab(*b,source_tile=TILE,owner_uids=[101],expected_input_sha256=h)
