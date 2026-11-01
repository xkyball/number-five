"""Invented full buffers and own ARM recurrence controls, no Original call."""
import hashlib
import random
import struct
from fractions import Fraction as R
import pytest
from no_chart_converter.rx5 import integer_region_certificate as c
from no_chart_converter.rx5 import render_integer_noink as render
from no_chart_converter.rx5 import wire,model,render_noink,header

TILE=(17,72240,78668);Q=2147483649


def buffers(*, usage=24, root=1, straddler=False, wide=False, repeats=1):
    x=TILE[1]*Q//(1<<17);y=TILE[2]*Q//(1<<17)
    points=[(x-2000000,y-40000000),(x-1990000,y-40000000),
            (x-1990000,y-39990000),(x-2000000,y-40000000)]
    if straddler:
        # Absolute rootY whose level29 projectedY crosses actual Qt ADD branch.
        y0=(TILE[2]*(1<<12)*256-(1<<31))*Q//(1<<37)
        points=[(x-2000000,y0-20000),(x-1990000,y0-20000),
                (x-1990000,y0+20000),(x-2000000,y0-20000)]
    if wide:
        points=[(x-20000000,y-40000000),(x-1000000,y-40000000),
                (x-1000000,y-39990000),(x-20000000,y-40000000)]
    co=struct.pack('<I',root<<24|len(points))+b''.join(struct.pack('<II',*p)for p in points)
    ix=struct.pack('<I',repeats)+b''.join(struct.pack('<I',usage<<24)for _ in range(repeats))
    fi=wire.FEATURE.pack(101,0,0,70,123,42,3,1,2,3)+wire.FEATURE.pack(102,0,0,70,456,42,3,4,5,6)
    ctx=[(0x100ff,b'CATEGORIES'),(0x30000,b'AC(DEPVS);'),(0x10103,b'DETAIL'),(0x30001,b'AC(DEPVS);')]
    ro=struct.pack('<HI',len(ctx),0)+b''.join(struct.pack('<IH',k,len(v))+v for k,v in ctx)
    ao=struct.pack('<QHHH',101,0,0,0)
    return fi,ix,co,ro,ao


def hashes(b):return dict(zip(render.NAMES,(hashlib.sha256(v).hexdigest()for v in b)))


def prepare(b, **overrides):
    source_header=header.new_plain_vector_header(b'OWN-HEADER-256!!')
    kwargs=dict(source_tile=TILE,owner_uids=[101],expected_input_sha256=hashes(b),
        source_header=source_header,expected_header_sha256=hashlib.sha256(source_header).hexdigest(),
        source_file_bytes=4096)
    kwargs.update(overrides)
    return render.prepare_integer_no_ink_render(*b,**kwargs)


def test_ordinary_signed_refusal_can_be_proved_by_actual_integer_arm_branch():
    b=buffers()
    with pytest.raises(ValueError,match='PROJECTION_THROUGH29'):
        render_noink.prepare_no_ink_render(*b,source_tile=TILE,owner_uids=[101],expected_input_sha256=hashes(b))
    p=c.certify_exterior_integer_regions(b[0][:32],b[1],b[2],TILE)
    last=p['parts'][0]['levels'][-1]
    assert last['mode']=='NEGATIVE_T_MIN_COMMON_POSITIVE_ADD_WRAP'
    assert last['integerXBounds'][1]<-35
    assert last['maximumDoubledEdgeProductBound']<c.INT_MAX
    assert p['accumulation'][-1]['maximumCoalescedHeightBySumOfInputRegionHeights']<c.INT_MAX
    assert p['viewModel'].startswith('SOURCE_BOUND_UNROTATED_256')and not p['arbitraryPainterTransformClaim']


def test_only_two_draw_values_and_selected_ROpointer_can_change():
    b=buffers();r=prepare(b)
    out=tuple(r[k]for k in('featureBytes','indexBytes','coordinateBytes','renderBytes','additionalBytes'))
    assert out[1]is b[1]and out[2]is b[2]and out[4]is b[4]
    assert out[0][:12]+out[0][16:]==b[0][:12]+b[0][16:]
    assert out[3][:len(b[3])]==b[3]
    old,q=model.programmes(b[3],0);new,q2=model.programmes(out[3],wire.FEATURE.unpack_from(out[0])[2])
    assert q==q2 and list(old)==list(new)
    assert all(new[k]==(b''if int(k)in(0x30000,0x30001)else v)for k,v in old.items())
    assert model.programmes(out[3],wire.FEATURE.unpack_from(out[0],32)[2])==(old,q)
    assert r['receipt']['requiresSourceBoundUnrotated256RasterProducer']is True
    assert r['receipt']['sourceHeaderContract']['tileSize']==256
    assert r['receipt']['requiresSameFileHeaderAndBufferNodeBinding']is True


@pytest.mark.parametrize('args,reason',[({'usage':20},'USAGE24'),({'usage':8},'USAGE24'),({'root':0},'ROOT1'),
   ({'straddler':True},'INTEGER_BRANCH'),({'wide':True},'INTEGER_BRANCH')])
def test_mask_hole_relative_branch_straddler_and_real_edge_overflow_refuse(args,reason):
    b=buffers(**args)
    with pytest.raises(ValueError,match=reason):c.certify_exterior_integer_regions(b[0][:32],b[1],b[2],TILE)


def test_bad_input_hash_or_owner_does_not_silently_partial_apply():
    b=buffers();h=hashes(b);h['coordinates']='0'*64
    with pytest.raises(ValueError,match='SHA256'):prepare(b,expected_input_sha256=h)
    with pytest.raises(ValueError,match='PRESENT'):prepare(b,owner_uids=[999])


def test_header_sha_is_a_required_input_not_an_asserted_tile_size():
    with pytest.raises(ValueError,match='SOURCE_HEADER_SHA256'):prepare(buffers(),expected_header_sha256='0'*64)
    with pytest.raises(TypeError):render.prepare_integer_no_ink_render(*buffers(),source_tile=TILE,owner_uids=[101],expected_input_sha256=hashes(buffers()))


@pytest.mark.parametrize('size',[0,128,512,1<<32|256])
def test_source_header_actual_u64_tile_size_must_be_256(size):
    raw=bytearray(header.new_plain_vector_header(b'OWN-HEADER-256!!'))
    struct.pack_into('<Q',raw,104,size)
    with pytest.raises(ValueError,match='UNSIGNED64_AT_104'):
        prepare(buffers(),source_header=bytes(raw),expected_header_sha256=hashlib.sha256(raw).hexdigest())


@pytest.mark.parametrize('extent',[None,True,239,(1<<30)+1])
def test_header_requires_explicit_supported_file_extent(extent):
    with pytest.raises(ValueError,match='SOURCE_FILE_EXTENT|SINGLE_SEGMENT_EXTENT'):
        prepare(buffers(),source_file_bytes=extent)


def test_source_scope_does_not_accept_a_caller_asserted_zoom_or_transform():
    b=buffers()
    with pytest.raises(TypeError):c.certify_exterior_integer_regions(b[0][:32],b[1],b[2],TILE,maximum_zoom=20)
    with pytest.raises(TypeError):c.certify_exterior_integer_regions(b[0][:32],b[1],b[2],TILE,painter_is_identity=True)


def own_edge_steps(dx,dy):
    # Exact integer fields/branch arms in Qt2bb7e0..2bbbcc/2bb9e4..2bbeec.
    m=abs(dx)//dy*(-1 if dx<0 else 1)
    if dx>=0:
        other=m+1;decision=2*dx-2*m*dy;inc_take=2*dx-2*(m+1)*dy;inc_other=2*dx-2*m*dy
        take=lambda d:d>0
    else:
        other=m-1;decision=2*m*dy-2*dx;inc_take=2*(m-1)*dy-2*dx;inc_other=2*m*dy-2*dx
        take=lambda d:d>=0
    x=0
    for row in range(dy):
        yield row,x,decision
        if take(decision):x+=other;decision+=inc_take
        else:x+=m;decision+=inc_other


def test_source_integer_recurrence_stays_between_endpoint_x_plus_one():
    rng=random.Random(684)
    for _ in range(600):
        dx=rng.randrange(-10000,10001);dy=rng.randrange(1,251)
        for row,x,decision in own_edge_steps(dx,dy):
            assert min(0,dx)-1<=x<=max(0,dx)+1
            assert -2*dy<=decision<=2*dy
            assert abs(R(dx*row,dy)-x)<=1
