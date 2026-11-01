"""Invented geometry and headers only; no private inputs or native calls."""
import hashlib
import struct
import pytest
from no_chart_converter.rx5 import scoped_area_tile_clip as s
from no_chart_converter.rx5 import header, model, wire

TILE = (21, 1000000, 1200000)


def rectangle(b):
    a,c,d,e = b
    return [(a,c),(d,c),(d,e),(a,e),(a,c)]


def fixture(*, hole=None, overlap=False, reversed_usage=False, bad_order=False, tx=False, anchor=False):
    b,_ = s.root_window(TILE)
    outer=rectangle((b[0]-10000,b[1]-10000,b[2]+10000,b[3]+10000))
    rings=[(outer,25 if reversed_usage else 24)]
    if overlap:rings.append((outer,24))
    if hole == 'outside':rings.append((rectangle((b[0]-5000,b[1],b[0]-4000,b[3])),20))
    if hole == 'inside':rings.append((rectangle((b[0]+100,b[1]+100,b[0]+200,b[1]+200)),20))
    if hole == 'cover':rings.append((outer,20))
    if bad_order:rings=[(rectangle((b[0]-5000,b[1],b[0]-4000,b[3])),20),(outer,24)]
    co=bytearray(); words=[]
    for pts,usage in rings:
        words.append(usage<<24 | len(co))
        stored=list(reversed(pts)) if usage&1 else pts
        co+=struct.pack('<I',1<<24|len(stored))+b''.join(struct.pack('<II',*p) for p in stored)
    ix=struct.pack('<I',len(words))+b''.join(struct.pack('<I',w) for w in words)
    fi=wire.FEATURE.pack(101,0,0,1,0,71,3,0,0,0)+wire.FEATURE.pack(102,0,0,67,0,13,3,3,2,1)
    ac=b'AC(OWN);'
    if tx:ac+=b"TX(OBJNAM,1,2,3,'15118',0,0,CHBLK,26);"
    entries=[(0x30000,ac),(0x100ff,b'CATEGORY'),(0x30001,ac)]
    if anchor:entries.insert(0,(65682,struct.pack('<dd',0.4,0.6)))
    ro=struct.pack('<HI',len(entries),0)+b''.join(struct.pack('<IH',k,len(v))+v for k,v in entries)
    ao=struct.pack('<QHHH',0,0,0,0)
    return fi,ix,bytes(co),ro,ao


def prepare(b,**override):
    h=header.new_plain_vector_header(bytes(range(16)))
    args=dict(source_tile=TILE,owner_uids=[101],policy=s.POLICY,
              expected_input_sha256={k:hashlib.sha256(v).hexdigest() for k,v in zip(s.NAMES,b)},
              source_header=h,expected_header_sha256=hashlib.sha256(h).hexdigest(),source_file_bytes=4096)
    args.update(override)
    return s.prepare_scoped_area_tile_clip(*b,**args)


def test_true_intersection_uses_normal_single_uid_and_only_fi_io_word():
    b=fixture(); out=prepare(b); r=out['receipt']; o=r['owners'][0]
    assert o['outputXY']==5 and o['trueIntersectionEqualsIntegerHaloWindow']
    assert out['featureBytes'][:8]==b[0][:8]
    assert out['featureBytes'][12:]==b[0][12:]
    assert out['indexBytes'][:len(b[1])]==b[1]
    assert out['coordinateBytes'][:len(b[2])]==b[2]
    assert out['renderBytes']is b[3] and out['additionalBytes']is b[4]
    assert r['appendedIndexBytes']==8 and r['appendedCoordinateBytes']==44
    after=list(model.parts(out['featureBytes'][:32],out['indexBytes'],out['coordinateBytes']))
    assert len(after)==1 and after[0]['rootFlag']==1 and after[0]['usage']==24
    assert not r['duplicateUIDAdded'] and r['sharedDrawAndQueryGeometryIntentionallyChanged']


def test_hole_outside_halo_preserves_true_fill_and_winding():
    b=fixture(hole='outside'); out=prepare(b); row=out['receipt']['owners'][0]
    assert [x['coversWholeWindow'] for x in row['coverage']]==[True,False]
    assert row['originalLocalContainmentSum']==1
    assert row['clipping']['clipDetail']['quantization'][0]['newFractionalVertices']==0


@pytest.mark.parametrize('hole',['inside','cover'])
def test_hole_that_changes_window_fill_is_not_dropped(hole):
    with pytest.raises(ValueError,match='BOUNDARY_INSIDE_HALO|SUM_MUST_EQUAL_ONE'):
        prepare(fixture(hole=hole))


def test_two_covering_exteriors_do_not_silently_normalize_native_query_sum_two():
    with pytest.raises(ValueError,match='SUM_MUST_EQUAL_ONE'):prepare(fixture(overlap=True))


def test_interleaved_exterior_after_hole_refuses_grouped_geos_assumption():
    with pytest.raises(ValueError,match='EXTERIOR_BEFORE_INTERIOR'):prepare(fixture(bad_order=True))


def test_reversal_usage_is_preserved_with_same_directed_winding():
    b=fixture(reversed_usage=True);out=prepare(b)
    p=list(model.parts(out['featureBytes'][:32],out['indexBytes'],out['coordinateBytes']))[0]
    assert p['usage']==25
    actual=s.ring_clip.decode_ring_owner(out['coordinateBytes'],out['indexBytes'][len(b[1]):],
                                       out['featureBytes'][:32],source_tile=TILE)
    original=s.ring_clip.decode_ring_owner(b[2],b[1],b[0][:32],source_tile=TILE)
    assert (s.source_clip.signed2(actual[0]['points'])>0)==(s.source_clip.signed2(original[0]['points'])>0)


def test_both_owners_get_distinct_regular_index_frames_without_reordering():
    b=fixture();out=prepare(b,owner_uids=[102,101]);r=out['receipt']
    assert [wire.FEATURE.unpack_from(out['featureBytes'],i*32)[0]for i in range(2)]==[101,102]
    assert {x['recordOrdinal']for x in r['owners']}=={0,1}
    assert r['appendedIndexBytes']==16 and r['appendedCoordinateBytes']==88
    assert wire.FEATURE.unpack_from(out['featureBytes'],0)[1]!=wire.FEATURE.unpack_from(out['featureBytes'],32)[1]


def test_tx_is_not_removed_and_anchor_change_is_explicit():
    b=fixture(tx=True);out=prepare(b);r=out['receipt']
    assert out['renderBytes']is b[3] and r['owners'][0]['hasTXProgramme']
    assert r['unchangedTXProgrammesDoNotProveTextPositionParity']
    assert r['textPositionAndSymbolOutcomeComparisonPending']


def test_explicit_f64_anchor_payload_stays_exact_before_geometry_fallback():
    b=fixture(tx=True,anchor=True);out=prepare(b);row=out['receipt']['owners'][0]
    assert row['explicitF64AnchorPin']==dict(bytes=16,sha256=hashlib.sha256(struct.pack('<dd',0.4,0.6)).hexdigest())
    assert not row['geometryDerivedFallbackAnchorMayChange']
    assert not row['explicitF32AnchorContextPresent']
    assert out['renderBytes']is b[3]


def test_nonfinite_explicit_anchor_is_refused_instead_of_claimed_preserved():
    b=list(fixture(tx=True,anchor=True));b[3]=b[3].replace(struct.pack('<dd',0.4,0.6),struct.pack('<dd',float('nan'),0.6))
    with pytest.raises(ValueError,match='FINITE_F64_EXPLICIT_ANCHOR'):prepare(tuple(b))


@pytest.mark.parametrize('kwargs',[{'policy':None},{'source_tile':(20,500000,600000)},
                                  {'source_tile':(22,2000000,2400000)},
                                  {'expected_input_sha256':{}},{'owner_uids':[101,101]},
                                  {'owner_uids':[999]}])
def test_default_scope_source_and_uid_refusals(kwargs):
    with pytest.raises(ValueError):prepare(fixture(),**kwargs)


def test_boundary_touch_and_nonroot_and_ap_refuse():
    b=list(fixture());bounds,_=s.root_window(TILE)
    pts=rectangle((bounds[0],bounds[1]-10000,bounds[2]+10000,bounds[3]+10000))
    b[2]=struct.pack('<I',1<<24|5)+b''.join(struct.pack('<II',*p)for p in pts)
    with pytest.raises(ValueError,match='BOUNDARY_INSIDE_HALO'):prepare(tuple(b))
    b=list(fixture());b[2]=struct.pack('<I',5)+b[2][4:]
    with pytest.raises(ValueError,match='ROOT_OR_GENUINE_L0'):prepare(tuple(b))
    b=list(fixture());b[3]=b[3].replace(b'AC(OWN)',b'AP(OWN)')
    with pytest.raises(ValueError,match='AC_OPTIONAL_TX'):prepare(tuple(b))


def test_outward_integer_halo_rounding_and_corrected_qregion_span():
    r=prepare(fixture())['receipt'];bounds=r['integerOutwardRootBounds'];q=r['trueRootHaloBounds']
    assert bounds[0]<=s.F(*q[0]) and bounds[1]<=s.F(*q[1])
    assert bounds[2]>=s.F(*q[2]) and bounds[3]>=s.F(*q[3])
    proof=r['owners'][0]['rectangleProjection']
    assert max(proof['integerOperandProof']['coherentRingSpanBounds'])<100000
    assert proof['allRoundedRectangleEdgesOutsideOriginalTile']
    assert not proof['nativePixelClaim']


def test_full22_restore_and_canonical_retention_not_sidecar_enforcement():
    r=prepare(fixture())['receipt']
    assert r['requiresActualFourFullRestoreChildrenLevel22']
    assert [c['tile']for c in r['requiredRestoreChildren']]==[
        [22,2000000,2400000],[22,2000001,2400000],
        [22,2000000,2400001],[22,2000001,2400001]]
    assert all(c['originalGeometryAndProgrammesForAllOwnersRequired']for c in r['requiredRestoreChildren'])
    assert r['canonicalInputsMustRemainAvailable'] and r['actualTreeRestoreAndNormalSelectionBindingPending']
    assert not r['globalChartApplyAllowed'] and not r['sidecarEnforcesScope']
    assert not r['originalBuggyTransparentImageParityClaim'] and not r['nativePixelProof']
