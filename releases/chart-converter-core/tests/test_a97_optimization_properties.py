"""Independent invented source-wire invariants, not Original runtime or image oracles.

Exact part bytes/role/order are inspected without the production model parser.
The tiny rational parity helper only supplies an own local geometry control.
"""
import hashlib
import math
import struct
from fractions import Fraction

import pytest

from no_chart_converter.rx5.fill_compact import (
    SCOPE, compact_source_fill, require_source32_request,
)

F = struct.Struct('<Q5I4B')
TILE = (15, 1, 1)
Q = 2147483649
KEYS = ('featureBytes', 'indexBytes', 'coordinateBytes', 'renderBytes', 'additionalBytes')


def ring(x, y, width=5000):
    return [(x,y), (x+width,y), (x+width,y+width), (x,y+width), (x,y)]


INSIDE = ring(80000, 80000)
LAST = ring(100000, 100000)
FAR = ring(240000, 240000)
FAR2 = ring(270000, 270000)


def element(points, root=1):
    return struct.pack('<I', root << 24 | len(points)) + b''.join(struct.pack('<II', *p) for p in points)


def programmes(body=b'AC(TEST)'):
    rows = [(196608, body), (196609, body+b';')]
    return struct.pack('<HI', len(rows), 0)+b''.join(struct.pack('<IH', k,len(v))+v for k,v in rows)


def bundle(elements=None, owners=None, *, arena=None, physical_order=None):
    elements = elements or [element(INSIDE), element(FAR), element(LAST)]
    offsets = {}
    if arena is None:
        co = bytearray()
        for i in (range(len(elements)) if physical_order is None else physical_order):
            offsets[i] = len(co); co += elements[i]
        arena = bytes(co)
    else:
        offsets = {i: off for i, off in enumerate(elements)}
    # UID, kind, [(physical element ordinal, usage)], programme frame.
    owners = owners or [(7001,3,[(0,24),(1,24),(2,24)],programmes())]
    index = bytearray(); render = bytearray(); records = []
    for uid, kind, refs, frame in owners:
        at = len(index); index += struct.pack('<I',len(refs))
        index += b''.join(struct.pack('<I', usage << 24 | offsets[i]) for i,usage in refs)
        ro = len(render); render += frame
        records.append(F.pack(uid,at,ro,0x13579,0xabcd1234,42,kind,0x11,0x80,3))
    # Opaque but valid typed attributes and one relation; preserve every byte.
    additional = struct.pack('<QH',987654,1)+struct.pack('<H',0x1234)+b'own\xffattr\0'
    additional += struct.pack('<H',1)+struct.pack('<H',0x5678)+b'value\0'
    additional += struct.pack('<H',1)+bytes(9)+b'relation\0'
    return b''.join(records),bytes(index),arena,bytes(render),additional


def run(source, **kwargs):
    return compact_source_fill(*source,tile=kwargs.pop('tile',TILE),scope=kwargs.pop('scope',SCOPE),**kwargs)


def decode(source):
    features,index,co,ro,ao = source
    answer = []
    for at in range(0,len(features),32):
        record=features[at:at+32]; f=F.unpack(record); n=struct.unpack_from('<I',index,f[1])[0]; parts=[]
        for ordinal in range(n):
            word=struct.unpack_from('<I',index,f[1]+4+4*ordinal)[0];off=word&0xffffff;h=struct.unpack_from('<I',co,off)[0]
            size=4+(12 if f[6] in (4,9) else 8)*(h&0xffffff);raw=co[off:off+size]
            assert len(raw)==size;parts.append((word>>24,raw))
        answer.append((f[0],record,parts))
    return answer


def output(result):
    return tuple(result[k] for k in KEYS)


def exact_subset(source, result, kept):
    before,after = decode(source),decode(output(result))
    assert [x[0] for x in before]==[x[0] for x in after]
    for (uid,record,parts),(newuid,newrecord,newparts) in zip(before,after):
        assert newuid==uid and newrecord[:8]==record[:8] and newrecord[12:]==record[12:]
        assert newparts==[parts[i] for i in kept.get(uid,range(len(parts)))]
    assert result['renderBytes']==source[3] and result['additionalBytes']==source[4]
    receipt=result['receipt']
    assert receipt['wholeOwnersRemoved']==0
    assert receipt['globalQueryEqualityClaim'] is receipt['globalChartApplyAllowed'] is False
    assert receipt['originalNativeExecution'] is receipt['hardwareTimeClaim'] is receipt['nativeSourceSelectionVerified'] is False
    for name,body in zip(KEYS,source):
        assert receipt['sourcePins'][name]=={'bytes':len(body),'sha256':hashlib.sha256(body).hexdigest()}
    for name,body in zip(KEYS,output(result)):
        assert receipt['outputPins'][name]=={'bytes':len(body),'sha256':hashlib.sha256(body).hexdigest()}


def unchanged(source,result,disposition='UNCHANGED'):
    assert result['changed'] is False and result['disposition']==disposition and output(result)==source


def parity(points, query):
    # Exact own OddEven geometric control; no native query arithmetic claim.
    x,y=map(Fraction,query);inside=False
    for (a,b),(c,d) in zip(points,points[1:]):
        if (b>y)!=(d>y) and x < Fraction(c-a,d-b)*(y-b)+a:inside=not inside
    return inside


def test_local_whole_part_removal_preserves_all_retained_bytes_metadata_and_source_scale():
    source=bundle();result=run(source)
    assert result['changed'] is True and result['disposition']=='COMPACTED_SOURCE32_PARTS'
    exact_subset(source,result,{7001:[0,2]})
    row,=result['receipt']['owners'];assert row['removedOriginalPartOrdinals']==[1]
    assert row['protectedConsumerPartOrdinals']==[0,2]
    assert result['receipt']['removedXYOccurrences']==5
    assert len(result['coordinateBytes'])<len(source[2])
    # Full-world geometry really differs: this is not a global parity claim.
    assert parity(FAR,(242000,242000)) is True
    assert not any(parity(r,(242000,242000)) for r in (INSIDE,LAST))
    for x in (60000,80000,110000,135000):
        for y in (60000,80000,110000,135000):
            assert not parity(FAR,(x,y))


@pytest.mark.parametrize('first_usage',[8,9,16,17,20,21,24,25])
@pytest.mark.parametrize('last_usage',[16,20,25])
def test_consumer_identity_follows_index_order_including_holes_masks_and_reverse(first_usage,last_usage):
    source=bundle(owners=[(7001,3,[(0,first_usage),(1,24),(2,last_usage)],programmes())],physical_order=[2,1,0])
    result=run(source);exact_subset(source,result,{7001:[0,2]})
    row,=result['receipt']['owners'];assert row['polygon0PartOrdinal']==0 and row['finalQueryVisiblePartOrdinal']==2
    assert row['protectedConsumerPartOrdinals']==[0,2]


def test_trailing_mask_does_not_replace_the_last_visible_hole_query_polygon():
    source=bundle([element(INSIDE),element(FAR),element(FAR2),element(FAR)],owners=[(7001,3,[(0,24),(1,24),(2,20),(3,8)],programmes())])
    result=run(source);exact_subset(source,result,{7001:[0,2]})
    row,=result['receipt']['owners'];assert row['finalQueryVisiblePartOrdinal']==2 and row['removedOriginalPartOrdinals']==[1,3]


def test_all_masked_owner_has_no_visible_query_polygon_but_retains_complete_polygon0():
    source=bundle(owners=[(7001,3,[(0,8),(1,9),(2,8)],programmes())])
    result=run(source);exact_subset(source,result,{7001:[0,2]})
    row,=result['receipt']['owners'];assert row['finalQueryVisiblePartOrdinal'] is None and row['protectedConsumerPartOrdinals']==[0]


@pytest.mark.parametrize('refs',[[(0,24),(2,20)],[(0,8),(2,20)],[(0,24)]])
def test_no_removable_internal_part_returns_every_original_buffer_without_repacking(refs):
    source=bundle(owners=[(7001,3,refs,programmes())]);unchanged(source,run(source))


@pytest.mark.parametrize('reverse_owners',[False,True])
@pytest.mark.parametrize('protected_role',[8,20,25])
def test_non_ac_cross_owner_polygon0_alias_is_protected_before_any_source_rewrite(reverse_owners,protected_role):
    defs=[(7001,3,[(0,24),(1,24),(2,24),(3,24)],programmes()),(7002,3,[(1,protected_role)],programmes(b'SY(TEST)'))]
    source=bundle([element(INSIDE),element(FAR),element(FAR2),element(LAST)],owners=defs[::-1] if reverse_owners else defs)
    result=run(source);exact_subset(source,result,{7001:[0,1,3]})
    row=next(v for v in result['receipt']['owners'] if v['uid']=='7001')
    assert row['removedOriginalPartOrdinals']==[2] and 1 in row['protectedCandidatePartOrdinals']


def test_partial_overlap_with_polygon0_span_is_protected_without_equal_offsets():
    anchor=[(100,200),(50,100),(50,3000000),(0x01000006,4000000),(100,200)]
    alias=[(4000000,100),(200,100),(200,100),(200,200),(4000000,200),(4000000,100)]
    raw=element(anchor)+struct.pack('<9I',100,200,100,200,200,4000000,200,4000000,100)
    assert raw[28:80]==element(alias)
    far_at=len(raw);raw+=element(FAR);last_at=len(raw);raw+=element(LAST)
    source=bundle([0,28,far_at,last_at],arena=raw,owners=[(7001,3,[(0,24),(1,24),(2,24),(3,24)],programmes())])
    result=run(source);exact_subset(source,result,{7001:[0,1,3]})
    row,=result['receipt']['owners'];assert row['removedOriginalPartOrdinals']==[2] and 1 in row['protectedCandidatePartOrdinals']


def test_sounding_twins_preserve_signed_xyz_role_pair_and_shared_native_index():
    xyz=struct.pack('<I',0x01000002)+struct.pack('<IIiIIi',90000,90000,-32769,100000,100000,77)
    source=bundle([element(INSIDE),element(FAR),element(LAST),xyz],owners=[
        (7001,3,[(0,24),(1,24),(2,24)],programmes()),
        (7002,4,[(3,0)],programmes(b'CS(SOUNDG01)')),
        (7002,9,[(3,0)],programmes(b'CS(SOUNDG01)'))])
    # The input twins genuinely share an index frame; equal distinct frames
    # would not establish an alias-identity obligation.
    fields=list(F.unpack(source[0][64:96]));fields[1]=F.unpack(source[0][32:64])[1]
    source=(source[0][:64]+F.pack(*fields),*source[1:])
    result=run(source);exact_subset(source,result,{7001:[0,2]})
    rows=decode(output(result));assert rows[1][2]==rows[2][2]==[(0,xyz)]
    assert F.unpack(rows[1][1])[1]==F.unpack(rows[2][1])[1]
    assert result['receipt']['declaredWork']['XYZBeforeAndAfter']==4


@pytest.mark.parametrize('root',[0,2])
def test_unknown_root_keeps_entire_source_without_guessing_u16_or_unknown_stride(root):
    short=struct.pack('<I8H',root<<24|4,1,1,2,1,2,2,1,1)
    source=bundle([element(INSIDE),element(FAR),element(LAST),short],owners=[(7001,3,[(0,24),(1,24),(2,24)],programmes()),(7002,3,[(3,24)],programmes())])
    unchanged(source,run(source),'KEEP_SOURCE')


def test_unknown_kind_retains_source_even_when_its_opaque_index_would_not_parse():
    source=list(bundle());source[0]+=F.pack(8000,0xffffffff,0xffffffff,0,0,0,250,0,0,0)
    source=tuple(source);unchanged(source,run(source),'KEEP_SOURCE')


@pytest.mark.parametrize('part',[
    [(240000,240000),(245000,240000),(245000,245000),(240000,245000)],
    [(240000,240000),(245000,240000),(240000,240000)],
])
def test_unresolved_open_or_short_area_grouping_retains_the_owner(part):
    source=bundle([element(INSIDE),element(part),element(LAST)]);unchanged(source,run(source))


def test_all_far_rings_cannot_remove_whole_owner_or_consumer_polygons():
    source=bundle([element(FAR),element(FAR2),element(ring(300000,300000))])
    result=run(source);exact_subset(source,result,{7001:[0,2]})
    assert result['receipt']['wholeOwnersRemoved']==0


def test_guard32_contact_is_kept_while_farther_proved_part_is_removed():
    # Closed source+32 boundary is exactly rational; the close contour straddles it.
    boundary=Fraction((2*256+32)*Q,32768*256)
    close=ring(math.floor(boundary)-100,80000,500)
    source=bundle([element(INSIDE),element(close),element(FAR),element(LAST)],owners=[(7001,3,[(0,24),(1,24),(2,24),(3,24)],programmes())])
    result=run(source);exact_subset(source,result,{7001:[0,1,3]})


@pytest.mark.parametrize('query',( [80000,80000], [math.ceil(Fraction(224*Q,32768*256)),80000], [math.floor(Fraction(544*Q,32768*256)),80000]))
@pytest.mark.parametrize('level',[15,16,29])
def test_request_guard_accepts_exact_integer_local_points_at_each_supported_scale(query,level):
    d=1<<(level-15);assert require_source32_request(TILE,(level,d,d),query_root=query) is None


@pytest.mark.parametrize('destination',[(14,0,0),(30,1<<15,1<<15),(16,4,2),(15,True,1),('15',1,1)])
def test_request_guard_refuses_other_source_scale_or_untyped_destination(destination):
    with pytest.raises(ValueError):require_source32_request(TILE,destination)


@pytest.mark.parametrize('query',[(0,0),(10**10,1),(True,80000),(80000.0,80000),(math.floor(Fraction(224*Q,32768*256)),80000),(math.ceil(Fraction(544*Q,32768*256)),80000)])
def test_request_guard_does_not_widen_the_scoped_query_domain(query):
    with pytest.raises(ValueError):require_source32_request(TILE,TILE,query_root=query)


@pytest.mark.parametrize('scope',[None,True,{},'GLOBAL','SOURCE32_DESCENDANTS_15_TO_30'])
def test_scope_is_explicit_not_an_approval_boolean_or_unknown_global_policy(scope):
    with pytest.raises(ValueError):run(bundle(),scope=scope)


def test_scope_has_no_default_and_mutable_buffers_are_not_accepted():
    source=bundle()
    with pytest.raises(TypeError):compact_source_fill(*source,tile=TILE)
    bad=list(source);bad[2]=bytearray(bad[2])
    with pytest.raises(ValueError):run(bad)


@pytest.mark.parametrize('position',[0,1,2,3,4])
def test_malformed_known_wire_refuses_without_a_partial_success(position):
    source=list(bundle());source[position]=source[position][:-1]
    with pytest.raises(ValueError):run(source)


@pytest.mark.parametrize('reverse_owners',[False,True])
def test_non_ac_last_visible_hole_alias_also_protects_an_internal_candidate(reverse_owners):
    definitions=[
        (7001,3,[(0,24),(1,24),(2,24),(3,24)],programmes()),
        # Its polygon0 is unrelated; the candidate aliases its final visible
        # hole and must be kept independently of the programme eligibility.
        (7002,3,[(3,8),(1,20)],programmes(b'AP(TEST)')),
    ]
    source=bundle([element(INSIDE),element(FAR),element(FAR2),element(LAST)],
                  owners=definitions[::-1] if reverse_owners else definitions)
    result=run(source);exact_subset(source,result,{7001:[0,1,3]})
    row=next(v for v in result['receipt']['owners'] if v['uid']=='7002')
    assert row['finalQueryVisiblePartOrdinal']==1 and row['protectedConsumerPartOrdinals']==[0,1]


def test_same_f32_coordinates_do_not_replace_retained_distinct_u32_vertices():
    top=Q-40000
    tiny=[(top,top),(top+1,top),(top+1,top+1),(top,top+1),(top,top)]
    f32=lambda x:struct.unpack('<f',struct.pack('<f',x))[0]
    assert f32(top)==f32(top+1) and top!=top+1
    source=bundle([element(tiny),element(ring(Q-200000,Q-200000)),element(ring(Q-30000,Q-30000))])
    result=run(source,tile=(15,32767,32767));assert result['changed'] is True
    exact_subset(source,result,{7001:[0,2]})
    assert decode(output(result))[0][2][0][1]==element(tiny)


def test_unsafe_u32_projection_range_is_kept_not_overflowed_into_a_false_exclusion():
    maximum=(1<<32)-1
    far=ring(maximum-5000,maximum-5000)
    source=bundle([element(INSIDE),element(far),element(LAST)])
    unchanged(source,run(source))
