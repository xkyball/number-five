"""Invented finite theorem premises; these are not actual Qt observations."""
import hashlib
import json
import struct
import pytest
from no_chart_converter.rx5 import scoped_lc_render_correction as s
from no_chart_converter.rx5 import header, model, wire

TILE=(21,1000000,1200000)


def encoded(value):return json.dumps(value,sort_keys=True,separators=(',',':')).encode()


def fixture(*,cell='HODATA01',usage=16,root=1,connector=False,extra_program=False):
    def p(x,y):return tuple((TILE[a+1]*256+v)*s.ROOT//(256*(1<<21)) for a,v in enumerate((x,y)))
    paths=[[(2000,3000),(3000,4000)],[(4000,4000),(5000,5000)]]
    if connector:paths=[[(-1000,100),(-900,100)],[(900,100),(1000,100)]]
    co=bytearray();words=[]
    for path in paths:
        words.append(usage<<24|len(co));points=[p(*v)for v in path]
        co+=struct.pack('<I',root<<24|len(points))+b''.join(struct.pack('<II',*q)for q in points)
    ix=struct.pack('<I',len(words))+b''.join(struct.pack('<I',w)for w in words)
    fi=wire.FEATURE.pack(1001,0,0,99,0,302,2,0,0,0)+wire.FEATURE.pack(1002,0,0,99,0,302,2,1,2,3)
    program=('LC('+cell+');').encode()
    if extra_program:program+=b'LS(SOLD,1,OWN);'
    entries=[(0x30000,program),(65682,struct.pack('<dd',0.2,0.3)),(0x100ff,b'CATEGORY'),(0x30001,program)]
    ro=struct.pack('<HI',len(entries),0)+b''.join(struct.pack('<IH',k,len(v))+v for k,v in entries)
    ao=struct.pack('<QHHH',0,0,0,0)
    return fi,ix,bytes(co),ro,ao


def args(b,*,trace=None):
    h=header.new_plain_vector_header(bytes(range(16)))
    tr=trace or s._trace(b[0][:32],b[1],b[2],TILE)
    pin=dict(bytes=10,sha256=hashlib.sha256(b'OWN-fixture').hexdigest())
    evidence=dict(format='no5-exact21-lc-bound-placement-premise/1',status='SOURCE_BOUND_FINITE_PLACEMENT_PREMISE',
        sourceTile=list(TILE),qualifiedLevels=[21],protectedHaloPixels=128,
        inputGeometrySHA256={k:s.sha(v)for k,v in zip(s.NAMES,b)if k in('indices','coordinates')},
        sourceHeaderSHA256=s.sha(h),currentCellContract=pin,actualQtEvaluation=pin,finitePlacementContract=pin,
        fullRasterIntermediateClosure=False,nativePixelProof=False,support200000IsUniformTube=False,
        owners=[dict(uid=1001,recordOrdinal=0,classCode=302,recordSHA256=s.sha(b[0][:32]),cell='HODATA01',
            trace=tr,placementMethods=dict(orientationFreeStartBall=tr['edges'],transferredZeroYAxis=0),
            placementEdgeRows=pin)])
    raw=encoded(evidence)
    return dict(source_tile=TILE,owner_uids=[1001],policy=s.POLICY,
        expected_input_sha256={k:s.sha(v)for k,v in zip(s.NAMES,b)},source_header=h,
        expected_header_sha256=s.sha(h),source_file_bytes=4096,
        placement_evidence_bytes=raw,expected_placement_evidence_sha256=s.sha(raw))


def run(b,**override):
    a=args(b);a.update(override)
    return s.prepare_scoped_lc_render_correction(*b,**a)


def mutate_evidence(a,fn):
    j=json.loads(a['placement_evidence_bytes']);fn(j);a['placement_evidence_bytes']=encoded(j)
    a['expected_placement_evidence_sha256']=s.sha(a['placement_evidence_bytes'])
    return a


def test_only_two_exact_lc_programmes_empty_all_geometry_queries_attrs_anchors_stay():
    b=fixture();out=run(b);r=out['receipt']
    assert out['featureBytes'][:12]==b[0][:12]and out['featureBytes'][16:]==b[0][16:]
    assert out['indexBytes']is b[1]and out['coordinateBytes']is b[2]and out['additionalBytes']is b[4]
    assert out['renderBytes'][:len(b[3])]==b[3]
    before,q=model.programmes(b[3],0)
    off=wire.FEATURE.unpack_from(out['featureBytes'])[2]
    after,aq=model.programmes(out['renderBytes'],off)
    assert list(before)==list(after)and aq==q
    assert after['196608']==after['196609']==b''
    assert {k:v for k,v in before.items()if k not in s.DRAW}=={k:v for k,v in after.items()if k not in s.DRAW}
    assert r['owners'][0]['trace']['connectorEdges']==1
    assert r['owners'][0]['trace']['edges']==3
    assert r['noLCPhaseRestartOrGeometryClip']and not r['nativePixelProof']


@pytest.mark.parametrize('key,value',[('policy',None),('source_tile',(20,500000,600000)),
    ('source_tile',(22,2000000,2400000)),('owner_uids',[1001,1001]),
    ('owner_uids',[999]),('expected_input_sha256',{}),('expected_placement_evidence_sha256','0'*64)])
def test_explicit_default_level_uid_and_hash_refusals(key,value):
    with pytest.raises(ValueError):run(fixture(),**{key:value})


@pytest.mark.parametrize('mutation',[
    lambda j:j.update(qualifiedLevels=[21,22]),
    lambda j:j.update(protectedHaloPixels=32),
    lambda j:j.update(fullRasterIntermediateClosure=True),
    lambda j:j.update(support200000IsUniformTube=True),
    lambda j:j.update(actualQtEvaluation={}),
    lambda j:j['owners'][0].update(trace={}),
    lambda j:j['owners'][0]['placementMethods'].update(orientationFreeStartBall=2),
])
def test_theorem_cannot_silently_widen_or_claim_unproved_native_parity(mutation):
    b=fixture();a=mutate_evidence(args(b),mutation)
    with pytest.raises(ValueError):s.prepare_scoped_lc_render_correction(*b,**a)


def test_each_part_outside_is_insufficient_if_native_connector_crosses_halo():
    b=fixture(connector=True)
    with pytest.raises(ValueError,match='CONTACTS_128_HALO'):s._trace(b[0][:32],b[1],b[2],TILE)


@pytest.mark.parametrize('opts',[dict(usage=17),dict(usage=48),dict(root=0)])
def test_reverse_mask_and_relative_parts_not_admitted_by_forward_lc_theorem(opts):
    b=fixture(**opts)
    with pytest.raises(ValueError,match='ROOT1_FORWARD_UNMASKED'):s._trace(b[0][:32],b[1],b[2],TILE)


def test_unknown_cell_or_mixed_recipe_not_silently_emptied():
    b=fixture(cell='ESSARE01');a=args(b)
    a=mutate_evidence(a,lambda j:j['owners'][0].update(cell='ESSARE01'))
    with pytest.raises(ValueError,match='CELL'):s.prepare_scoped_lc_render_correction(*b,**a)
    with pytest.raises(ValueError,match='SINGLE_LC'):run(fixture(extra_program=True))


def test_four_deeper_restores_native_comparison_and_normal_selection_remain_explicit():
    r=run(fixture())['receipt']
    assert r['requiresActualFourFullLCLevel22Restores']
    assert [c['tile']for c in r['requiredRestoreChildren']]==[
        [22,2000000,2400000],[22,2000001,2400000],
        [22,2000000,2400001],[22,2000001,2400001]]
    assert r['actualSourceSelectionAndRestoreBindingPending']
    assert not r['fullRasterIntermediateClosure']and not r['nativePixelProof']
    assert not r['sidecarEnforcesScope']and not r['globalChartApplyAllowed']
    assert r['fullDirectedGeometryQueriesAndAnchorsRetained']
