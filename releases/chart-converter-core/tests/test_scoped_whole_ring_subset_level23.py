"""Invented Root1 fixtures for the opt-in exact23 API; no OEM execution."""
import hashlib
import struct
import pytest
from no_chart_converter.rx5 import scoped_whole_ring_subset_level23 as s
from no_chart_converter.rx5 import header,model,wire
from no_chart_converter.rx5 import scoped_whole_ring_profile as core

TILE=(23,4000000,4800000)

def fixture(*,usage=24,root=1,closed=True,parts=4):
    den=256*(1<<23)
    def ring(x,y):
        points=[(x,y),(x+20,y),(x+20,y+20),(x,y)]
        if not closed:points[-1]=(x+1,y)
        return [((TILE[1]*256+a)*s.ROOT//den,(TILE[2]*256+b)*s.ROOT//den)for a,b in points]
    rings=[ring(-4000,-4000),ring(4000,4000),ring(20,20),ring(-5000,5000)][:parts]
    co=bytearray();words=[]
    for points in rings:
        words.append(usage<<24|len(co));co+=struct.pack('<I',root<<24|len(points))+b''.join(struct.pack('<II',*p)for p in points)
    ix=struct.pack('<I',len(words))+b''.join(struct.pack('<I',w)for w in words)
    xyz_io=len(ix);xyz_co=len(co)
    ix+=struct.pack('<II',1,8<<24|xyz_co)
    co+=struct.pack('<IIII',1<<24|1,*rings[0][0],1234)
    fi=(wire.FEATURE.pack(101,0,0,70,0,42,3,1,2,3)
        +wire.FEATURE.pack(102,0,0,70,0,42,3,3,2,1)
        +wire.FEATURE.pack(103,xyz_io,0,70,0,129,4,0,0,0)
        +wire.FEATURE.pack(103,xyz_io,0,70,0,129,9,0,0,0))
    contexts=[(0x30000,b'AC(DEPVS);'),(0x100ff,b'CATEGORY'),(0x30001,b'AC(DEPVS);')]
    ro=struct.pack('<HI',len(contexts),0)+b''.join(struct.pack('<IH',k,len(v))+v for k,v in contexts)
    ao=struct.pack('<QHHH',0,0,0,0)
    return fi,ix,bytes(co),ro,ao

def arguments(buffers,**override):
    h=header.new_plain_vector_header(b'OWN-HALO-FIXTURE')
    args=dict(source_tile=TILE,owner_uid=101,expected_input_sha256=dict(zip(s.NAMES,(hashlib.sha256(v).hexdigest()for v in buffers))),source_header=h,expected_header_sha256=hashlib.sha256(h).hexdigest(),source_file_bytes=4096)
    args.update(override);return args

def prepare(buffers,**override):
    return s.prepare_scoped_whole_ring_subset_level23(*buffers,**arguments(buffers,**override))

def test_whole_part_subsequence_preserves_p0_last_alias_and_xyz():
    b=fixture();out=prepare(b);r=out['receipt']
    assert r['keptPartOrdinals']==[0,2,3]and r['removedPartOrdinals']==[1]
    before=list(model.parts(b[0][:32],b[1],b[2]));after=list(model.parts(out['featureBytes'][:32],out['indexBytes'],out['coordinateBytes']))
    assert [(p['word'],p['raw'])for p in after]==[(before[i]['word'],before[i]['raw'])for i in(0,2,3)]
    assert out['featureBytes'][32:]==b[0][32:]and out['indexBytes'][:len(b[1])]==b[1]
    assert out['coordinateBytes']is b[2]and out['renderBytes']is b[3]and out['additionalBytes']is b[4]
    for ordinal in(1,2,3):
        record=b[0][ordinal*32:(ordinal+1)*32]
        assert list(model.parts(record,b[1],b[2]))==list(model.parts(record,out['indexBytes'],out['coordinateBytes']))

def test_two_protected_parts_are_exact_noop():
    b=fixture(parts=2);out=prepare(b)
    assert out['receipt']['disposition']=='NO_OP'
    assert all(out[k]is v for k,v in zip(s.OUTPUTS,b))

@pytest.mark.parametrize('options',[dict(usage=8),dict(usage=20),dict(root=0),dict(closed=False)])
def test_unproved_geometry_refuses(options):
    with pytest.raises(ValueError,match='ROOT1_USAGE24'):prepare(fixture(**options))

@pytest.mark.parametrize('tile',[(20,500000,600000),(21,1000000,1200000),(22,2000000,2400000),(24,8000000,9600000),(23,-1,0),(23,1<<23,0)])
def test_exact23_scope_refuses_other_levels_or_invalid_tiles(tile):
    with pytest.raises(ValueError,match='LEVEL23_ONLY'):prepare(fixture(),source_tile=tile)

def test_header_hash_owner_and_programme_qualifiers():
    b=fixture()
    with pytest.raises(ValueError,match='SHA_BINDING'):prepare(b,expected_input_sha256={})
    with pytest.raises(ValueError,match='ONE_SELECTED'):prepare(b,owner_uid=999)
    h=bytearray(header.new_plain_vector_header(b'OWN-HALO-FIXTURE'));struct.pack_into('<Q',h,104,512)
    with pytest.raises(ValueError,match='104'):prepare(b,source_header=bytes(h),expected_header_sha256=hashlib.sha256(h).hexdigest())
    changed=list(b);changed[3]=b[3].replace(b'AC(DEPVS);',b'AP(DEPVS);')
    with pytest.raises(ValueError,match='PURE_AC'):prepare(tuple(changed))

def test_fresh23_error_and_four_full24_children_are_explicit():
    r=prepare(fixture())['receipt']
    assert r['exactViewLevel']==r['maximumQualifiedViewLevel']==23
    assert r['coordinateErrorPixels']==[2064,2064]
    assert all(p['integerProof']['level']==23 and p['integerProof']['coordinateErrorPixels']==[2064,2064]for p in r['parts'])
    assert r['canonicalRestoreLevel']==24 and r['requiresActualFourCanonicalRestoreChildrenLevel24']
    assert [c['tile']for c in r['requiredCanonicalRestoreChildren']]==[[24,8000000,9600000],[24,8000001,9600000],[24,8000000,9600001],[24,8000001,9600001]]
    assert all(c['flags']==3 and c['leaf']and c['fullCanonicalGeometryAndProgrammesRequired']for c in r['requiredCanonicalRestoreChildren'])
    assert r['existingAncestorsMustRemainUnchanged']and r['canonicalAncestorsOrSeparatelyQualifiedPriorScopesRequired']
    assert not r['globalChartApplyAllowed']and not r['sidecarEnforcesScope']and not r['globalQueryEqualityClaim']

def test_old20_and21_entrypoints_still_refuse23():
    from no_chart_converter.rx5 import scoped_whole_ring_subset as old20
    from no_chart_converter.rx5 import scoped_whole_ring_subset_level21 as old21
    from no_chart_converter.rx5 import scoped_whole_ring_subset_level22 as old22
    b=fixture()
    for api,match in((old20.prepare_scoped_whole_ring_subset,'LEVEL20_ONLY'),(old21.prepare_scoped_whole_ring_subset_level21,'LEVEL21_ONLY'),(old22.prepare_scoped_whole_ring_subset_level22,'LEVEL22_ONLY')):
        with pytest.raises(ValueError,match=match):api(*b,**arguments(b))

def test_stale_error_or_saturated_branch_cannot_gain23_authority(monkeypatch):
    actual=core.ring_integer_domain
    def stale(*args):
        proof=actual(*args);proof['coordinateErrorPixels']=[528,528];return proof
    monkeypatch.setattr(core,'ring_integer_domain',stale)
    with pytest.raises(ValueError,match='ERROR2064_REQUIRED'):prepare(fixture())
    def saturated(*args):
        proof=actual(*args);proof['mode']='NEGATIVE_T_MIN_COMMON_POSITIVE_ADD_WRAP';return proof
    monkeypatch.setattr(core,'ring_integer_domain',saturated)
    with pytest.raises(ValueError,match='INTEGER_OPERANDS_VALID'):prepare(fixture())

def test_full_union_overflow_refuses_before_output(monkeypatch):
    actual=core.ring_integer_domain
    def tall(*args):
        proof=actual(*args);proof['maximumNonemptyRegionHeight']=core.INT_MAX;return proof
    monkeypatch.setattr(core,'ring_integer_domain',tall)
    with pytest.raises(ValueError,match='UNION_AND_FILLRECT_EXTENTS'):prepare(fixture())

def test_actual_unsafe_integer_edge_refuses():
    b=list(fixture());part=list(model.parts(b[0][:32],b[1],b[2]))[1]
    raw=struct.pack('<I',1<<24|4)+b''.join(struct.pack('<II',*p)for p in[(0,0),(0xffffffff,0xffffffff),(0xffffffff,0),(0,0)])
    co=bytearray(b[2]);start=part['coordinateOffset'];co[start:start+len(raw)]=raw;b[2]=bytes(co)
    with pytest.raises(ValueError,match='INTEGER_OPERANDS_VALID'):prepare(tuple(b))

def test_deterministic_and_only_selected_fi_index_changes():
    b=fixture();a=prepare(b);assert a==prepare(b)
    differences=[i for i,(x,y)in enumerate(zip(b[0],a['featureBytes']))if x!=y]
    assert differences and all(8<=i<12 for i in differences)
    assert a['receipt']['policy']=='exact-L23-root1-exterior-whole-ring-subset/1'

def test_local_root_containment_and_anchor_last_are_exact():
    from no_chart_converter.rx5.locality_empty import Domain
    b=fixture();out=prepare(b);q=out['receipt']['rootQueryHalo'];d=Domain(q['denominator'],q['xmin'],q['ymin'],q['xmax'],q['ymax'])
    before=list(model.parts(b[0][:32],b[1],b[2]));after=list(model.parts(out['featureBytes'][:32],out['indexBytes'],b[2]))
    assert sum(d.winding(p['points'])for p in before)==sum(d.winding(p['points'])for p in after)
    assert before[0]['raw']==after[0]['raw']and before[-1]['raw']==after[-1]['raw']


def test_core_has_no_arbitrary_profile_or_default():
    b=fixture()
    for value in ('exact-L22', 'exact-L24', 23, None, {'level':23}):
        with pytest.raises(ValueError, match='SUPPORTED_PROFILE'):
            core.prepare_whole_ring_profile(*b, **arguments(b), profile=value)
