"""Invented Root1 buffers; no private input or Original execution."""
import hashlib
import struct
import pytest
from no_chart_converter.rx5 import scoped_whole_ring_subset as s
from no_chart_converter.rx5 import header,model,wire

TILE=(20,500000,600000)


def fixture(*,usage=24,root=1,closed=True,parts=4):
    def ring(x,y):
        den=256*(1<<20)
        p=[(x,y),(x+20,y),(x+20,y+20),(x,y)]
        if not closed:p[-1]=(x+1,y)
        return [((TILE[1]*256+a)*s.ROOT//den,(TILE[2]*256+b)*s.ROOT//den)for a,b in p]
    points=[ring(-4000,-4000),ring(4000,4000),ring(20,20),ring(-5000,5000)][:parts]
    co=bytearray();words=[]
    for p in points:
        words.append(usage<<24|len(co));co+=struct.pack('<I',root<<24|len(p))+b''.join(struct.pack('<II',*q)for q in p)
    ix=struct.pack('<I',len(words))+b''.join(struct.pack('<I',w)for w in words)
    fi=wire.FEATURE.pack(101,0,0,70,0,42,3,1,2,3)+wire.FEATURE.pack(102,0,0,70,0,42,3,3,2,1)
    contexts=[(0x30000,b'AC(DEPVS);'),(0x100ff,b'CATEGORY'),(0x30001,b'AC(DEPVS);')]
    ro=struct.pack('<HI',len(contexts),0)+b''.join(struct.pack('<IH',k,len(v))+v for k,v in contexts)
    ao=struct.pack('<QHHH',0,0,0,0)
    return fi,ix,bytes(co),ro,ao


def prepare(b,**override):
    h=header.new_plain_vector_header(b'OWN-HALO-FIXTURE')
    args=dict(source_tile=TILE,owner_uid=101,expected_input_sha256=dict(zip(s.NAMES,(hashlib.sha256(v).hexdigest()for v in b))),
        source_header=h,expected_header_sha256=hashlib.sha256(h).hexdigest(),source_file_bytes=4096)
    args.update(override)
    return s.prepare_scoped_whole_ring_subset(*b,**args)


def test_outside_whole_ring_removed_but_p0_last_and_intersecting_ring_kept():
    b=fixture();out=prepare(b);r=out['receipt']
    assert r['keptPartOrdinals']==[0,2,3]and r['removedPartOrdinals']==[1]
    assert r['XYBefore']==16 and r['XYAfter']==12
    before=list(model.parts(b[0][:32],b[1],b[2]))
    after=list(model.parts(out['featureBytes'][:32],out['indexBytes'],out['coordinateBytes']))
    assert [(p['word'],p['raw'])for p in after]==[(before[i]['word'],before[i]['raw'])for i in(0,2,3)]
    assert out['coordinateBytes']is b[2]and out['renderBytes']is b[3]and out['additionalBytes']is b[4]
    assert out['indexBytes'][:len(b[1])]==b[1]
    assert out['featureBytes'][32:]==b[0][32:] # Alias to old I frame is exact.
    assert not r['globalChartApplyAllowed']and not r['globalQueryEqualityClaim']and not r['sidecarEnforcesScope']
    assert r['requiresActualFourCanonicalRestoreChildrenLevel21']


def test_two_protected_rings_is_exact_noop():
    b=fixture(parts=2);out=prepare(b)
    assert out['receipt']['disposition']=='NO_OP'
    assert all(out[k]is v for k,v in zip(s.OUTPUTS,b))


@pytest.mark.parametrize('options',[dict(usage=8),dict(usage=20),dict(root=0),dict(closed=False)])
def test_unsupported_role_relative_coordinates_or_open_ring_refuses(options):
    with pytest.raises(ValueError,match='ROOT1_USAGE24'):prepare(fixture(**options))


@pytest.mark.parametrize('tile',[(19,250000,300000),(21,1000000,1200000),(20,-1,0),(20,1<<20,0)])
def test_scope_is_exact_level20_not_unbounded_descendants(tile):
    with pytest.raises(ValueError,match='LEVEL20_ONLY'):prepare(fixture(),source_tile=tile)


def test_broken_hash_owner_and_non256_header_refuse():
    b=fixture()
    with pytest.raises(ValueError,match='SHA_BINDING'):prepare(b,expected_input_sha256={})
    with pytest.raises(ValueError,match='ONE_SELECTED'):prepare(b,owner_uid=999)
    h=bytearray(header.new_plain_vector_header(b'OWN-HALO-FIXTURE'));struct.pack_into('<Q',h,104,512)
    with pytest.raises(ValueError,match='104'):prepare(b,source_header=bytes(h),expected_header_sha256=hashlib.sha256(h).hexdigest())


def test_primitive_or_unknown_programme_does_not_become_a_fill_proof():
    b=list(fixture());raw=bytearray(b[0]);raw[28]=2;b[0]=bytes(raw)
    with pytest.raises(ValueError,match='KIND3'):prepare(tuple(b))
    b=list(fixture());b[3]=b[3].replace(b'AC(DEPVS);',b'AP(DEPVS);')
    with pytest.raises(ValueError,match='PURE_AC'):prepare(tuple(b))


def test_radius_box_inside_halo_has_unchanged_exact_root_containment():
    b=fixture();out=prepare(b)
    before=list(model.parts(b[0][:32],b[1],b[2]));after=list(model.parts(out['featureBytes'][:32],out['indexBytes'],b[2]))
    from no_chart_converter.rx5.locality_empty import Domain
    q=out['receipt']['rootQueryHalo'];d=Domain(q['denominator'],q['xmin'],q['ymin'],q['xmax'],q['ymax'])
    assert sum(d.winding(p['points'])for p in before)==sum(d.winding(p['points'])for p in after)
    assert before[0]['raw']==after[0]['raw']and before[-1]['raw']==after[-1]['raw']
