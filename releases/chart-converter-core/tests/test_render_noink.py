"""Invented streams: pure preparation controls, no native frame/query claims."""
import hashlib
import struct

import pytest

from no_chart_converter.rx5 import render_noink as n
from no_chart_converter.rx5.model import programmes
from no_chart_converter.rx5.wire import FEATURE

TILE = (17, 72000, 78000)
Q = 2147483649


def sha(raw):
    return hashlib.sha256(raw).hexdigest()


def frame(contexts, query=0):
    return struct.pack('<HI', len(contexts), query)+b''.join(struct.pack('<IH', k, len(v))+v for k, v in contexts)


def fixture(*, near=False, root=1, closed=True, programme=b'AC(DEPVS);'):
    x, y = TILE[1]*Q//(1 << TILE[0]), TILE[2]*Q//(1 << TILE[0])
    points = [(x+1000000,y+1000000),(x+1010000,y+1000000),
              (x+1010000,y+1010000),(x+1000000,y+1000000)]
    if near:
        points = [(x+4000,y+4000),(x+8000,y+4000),(x+8000,y+8000),(x+4000,y+4000)]
    if not closed:
        points[-1] = (points[0][0]+1, points[0][1])
    co = struct.pack('<I', root << 24 | len(points))+b''.join(struct.pack('<II', *p) for p in points)
    ix = struct.pack('<II', 1, 24 << 24)
    # One ordinary unselected owner shares the original RO frame. Selected
    # owners may share the new frame, while the unselected reference stays old.
    f = b''.join(FEATURE.pack(uid, 0, 0, 70, 0, 42, 3, 0, 0, 0) for uid in (101,102,103))
    ro = frame([(0x100ff,b'CATEGORY\x00RAW'),(0x30001,programme),(0x10103,b'DETAIL'),
                (0x30000,programme),(0x30002,b'UNCHANGED')])
    ao = struct.pack('<QHHH', 101, 0, 0, 0)
    return (f, ix, co, ro, ao)


def prepare(buffers, **kwargs):
    return n.prepare_no_ink_render(*buffers, source_tile=kwargs.pop('source_tile', TILE),
          owner_uids=kwargs.pop('owner_uids', (101,102)),
          expected_input_sha256=kwargs.pop('expected_input_sha256', dict(zip(n.NAMES,map(sha,buffers)))), **kwargs)


def test_empty_only_two_programmes_keeps_canonical_geometry_queries_categories_and_original_aliases():
    buffers = fixture()
    r = prepare(buffers)
    f, ix, co, ro, ao = buffers
    assert r['indexBytes'] is ix and r['coordinateBytes'] is co and r['additionalBytes'] is ao
    assert r['renderBytes'][:len(ro)] == ro
    offsets=[]
    for i, uid in enumerate((101,102,103)):
        old,new=f[i*32:(i+1)*32],r['featureBytes'][i*32:(i+1)*32]
        assert old[:12]+old[16:] == new[:12]+new[16:]
        at=FEATURE.unpack(new)[2];offsets.append(at)
        before, query=programmes(ro,0);after,actual_query=programmes(r['renderBytes'],at)
        assert query == actual_query == 0
        assert list(before) == list(after)
        for key in before:
            assert after[key] == (b'' if i < 2 and int(key) in n.DRAW_CONTEXTS else before[key])
    assert offsets[0] == offsets[1] == len(ro) and offsets[2] == 0
    assert r['featureBytes'][64:] == f[64:]
    receipt=r['receipt']
    assert receipt['selectedOwnerUIDs'] == [101,102]
    assert receipt['appendedRenderFrames'] == 1
    assert receipt['geometryIndicesAdditionalAndAllUIDsExact']
    assert receipt['requiresActualChildSourceBinding']
    assert receipt['defaultPipelineChanged'] is False
    assert receipt['nativeQueryResultEqualityClaim'] is False
    assert receipt['nativeFrameParityClaim'] is False
    assert receipt['hardwareTimeClaim'] is False
    assert all(p['noInkCertificate']['maximumDescendantLevel'] == 29 for p in receipt['preparedOwners'])


def test_preparation_is_deterministic_with_sorted_uid_selection():
    a=prepare(fixture(),owner_uids=[101,102]);b=prepare(fixture(),owner_uids=[102,101])
    assert a == b


@pytest.mark.parametrize('programme', [b'',b'CS(DEPARE01);',b'LS(SOLD,1,CHBLK);',b'AC(DEPVS);TX(X);',b'AC(DEPVS);;'])
def test_empty_or_non_pure_ac_programme_is_not_an_unproven_noink_gate(programme):
    with pytest.raises(ValueError, match='NONEMPTY_PURE_AC'):
        prepare(fixture(programme=programme))


def test_multiple_pure_ac_commands_are_allowed_but_never_changed_in_original_frame():
    buffers=fixture(programme=b'AC(DEPVS);AC(DEPMD);')
    r=prepare(buffers)
    assert r['renderBytes'][:len(buffers[3])] == buffers[3]


@pytest.mark.parametrize('args', [{'near':True},{'root':0},{'closed':False}])
def test_local_unknown_or_open_geometry_refuses_whole_preparation(args):
    with pytest.raises(ValueError, match='OUTSIDE_SOURCE_DRAW_DOMAIN|KNOWN_CLOSED_ABSOLUTE_ROOT1'):
        prepare(fixture(**args))


def test_far_geometry_with_unsafe_native_int32_projection_is_kept_by_refusal():
    b=list(fixture())
    b[2]=struct.pack('<I',0x1000004)+b''.join(struct.pack('<II',*p) for p in ((0,0),(1000,0),(1000,1000),(0,0)))
    with pytest.raises(ValueError,match='SAFE_NATIVE_PROJECTION_THROUGH29'):
        prepare(tuple(b))


@pytest.mark.parametrize('uids', [[],[101,101],[True],[999]])
def test_explicit_unique_actual_owner_binding_is_required(uids):
    with pytest.raises(ValueError,match='EXPLICIT_UNIQUE_OWNER_UIDS|EVERY_REQUESTED_UID_PRESENT'):
        prepare(fixture(),owner_uids=uids)


def test_ambiguous_uid_or_non_area_owner_is_refused():
    b=list(fixture());b[0]=b[0][:32]*2
    with pytest.raises(ValueError,match='SINGLE_FEATURE'):
        prepare(tuple(b),owner_uids=[101])
    b=list(fixture());f=bytearray(b[0]);f[28]=2;b[0]=bytes(f)
    with pytest.raises(ValueError,match='ONLY_COMPLETE_KIND3'):
        prepare(tuple(b),owner_uids=[101])


def test_no_caller_approval_can_replace_the_five_actual_input_hashes():
    buffers=fixture();pins=dict(zip(n.NAMES,map(sha,buffers)));pins['coordinates']='0'*64
    with pytest.raises(ValueError,match='EXACT_FIVE_INPUT_SHA256'):
        prepare(buffers,expected_input_sha256=pins)
    pins=dict(zip(n.NAMES,map(sha,buffers)));pins['approved']=True
    with pytest.raises(ValueError,match='EXACT_FIVE_INPUT_SHA256'):
        prepare(buffers,expected_input_sha256=pins)


def test_all_selected_owner_proofs_finish_before_any_candidate_is_returned():
    b=list(fixture());f=bytearray(b[0]);f[60]=1;b[0]=bytes(f)
    with pytest.raises(ValueError,match='ONLY_COMPLETE_KIND3'):
        prepare(tuple(b))
    # Inputs are immutable and remain byte exact despite first owner's success.
    assert b[3] == fixture()[3]


def test_query_pointer_and_context_structure_are_checked_from_actual_bytes():
    b=list(fixture());b[3]=frame([(0x30000,b'AC(DEPVS);'),(0x30000,b'AC(DEPVS);')])
    with pytest.raises(ValueError,match='UNIQUE_COMPLETE_RENDER_CONTEXT'):
        prepare(tuple(b))
    b=list(fixture());b[3]=b[3][:2]+struct.pack('<I',len(b[4]))+b[3][6:]
    with pytest.raises(ValueError,match='STREAM_RANGE'):
        prepare(tuple(b))


@pytest.mark.parametrize('tile', [(17,True,0),(30,0,0),(17,-1,0),(17,1 << 17,0)])
def test_invalid_source_binding_is_refused(tile):
    with pytest.raises(ValueError,match='SOURCE_TILE'):
        prepare(fixture(),source_tile=tile)
