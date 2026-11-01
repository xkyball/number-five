"""Own tiny V35 fixtures; no chart, runtime archive or external file inputs.

All wire coordinates, identifiers, attributes and registry strings are invented
in this file. Tests make no native-query, materialization or device claim.
"""
import hashlib
import json
import struct

import pytest

from no_chart_converter.rx5.fill_duplicates import analyze_fill_duplicates
from no_chart_converter.rx5.metadata import Stream
from no_chart_converter.rx5.area_polygon_groups import classify_polygon_groups


F = struct.Struct('<Q5I4B')
PAPER, SIMPLE = 196608, 196609
POLICY = 'preserve_native_polygon0'
AC = b'AC(OWNAC35);'
A, B, C = (100,100), (400,100), (400,400)
RING = [A,A,B,B,C,C,A,A]
OTHER = [(1000,1000),(1000,1000),(1400,1000),(1400,1000),
         (1400,1400),(1400,1400),(1000,1000),(1000,1000)]


def sha(raw):
    return hashlib.sha256(raw).hexdigest()


def fixture(*, parts=None, kinds=(3,), contexts=None, aliases=None):
    parts = [(24,1,RING),(24,1,OTHER)] if parts is None else parts
    contexts = [(PAPER,AC),(SIMPLE,AC)] if contexts is None else contexts
    aliases = {} if aliases is None else aliases
    co = bytearray(b'OWNCO35!')
    offsets, raws, words = [], [], []
    for ordinal, (usage, root, points) in enumerate(parts):
        if ordinal in aliases:
            target = aliases[ordinal]
            offsets.append(offsets[target]); raws.append(raws[target])
        else:
            raw = struct.pack('<I',(root<<24)|len(points))
            for p in points:
                raw += struct.pack('<IIi' if len(p)==3 else '<II',*p)
            offsets.append(len(co)); raws.append(raw); co += raw
        words.append((usage<<24)|offsets[-1])
    index = b'OWNI' + struct.pack('<I',len(words)) + b''.join(struct.pack('<I',w)for w in words)
    # A deliberately opaque DI/registry prefix and invented typed strings.
    ao_frame = (struct.pack('<II',0xBADC0FFE,0x13579BDF)
        +struct.pack('<HH',1,116)+b'OWN ANCHOR QA35\0'
        +struct.pack('<HH',1,116)+b'OWN NATIONAL35\0'
        +struct.pack('<HQB',1,0x1122334455667788,3)+b'OWN REGISTRY35\0')
    additional = b'A35' + ao_frame
    ro_frame = struct.pack('<HI',len(contexts),3)
    for key, text in contexts:
        ro_frame += struct.pack('<IH',key,len(text))+text
    render = b'R35!!' + ro_frame
    features = b''.join(F.pack(0x123456789ABCDEF0,4,5,0xA1B2C3D4,29,
        129 if k in(4,9)else 42,k,6,0xA3,0x5C)for k in kinds)
    return dict(features=features,indices=index,coordinates=bytes(co),render=render,
        additional=additional,roFrame=ro_frame,aoFrame=ao_frame,
        rawParts=[dict(indexWord=w,raw=r)for w,r in zip(words,raws)])


def analyze(data, *, forbid_geometry=False, policy=POLICY):
    snapshots={k:v for k,v in data.items()if type(v)is bytes}
    reads={name:[]for name in('indices','coordinates','render','additional')}
    values={}
    for name in reads:
        raw=data[name]
        def read(at,count,*,name=name,raw=raw):
            reads[name].append((at,count))
            if forbid_geometry and name in('indices','coordinates'):
                raise AssertionError('Excluded kind/context accessed geometry')
            return raw[at:at+count]
        values[name]=Stream(len(raw),read)
    result=analyze_fill_duplicates(data['features'],values['indices'],values['coordinates'],
        values['render'],additional=values['additional'],anchor_policy=policy)
    assert snapshots=={k:v for k,v in data.items()if type(v)is bytes}
    assert result['sourceFeatureSHA256']==sha(data['features'])
    assert result['analyzeOnly'] is True and result['operativeApplyAllowed'] is False
    assert result['materializationApproved'] is False
    assert result['nativeQueryEquivalenceApproved'] is False
    assert result['nativeQueryDisposition']=='UNPROVEN'
    assert result['coordinateOrIndexOrMetadataBuffersReturned'] is False
    json.dumps(result)
    for ordinal, owner in enumerate(result['owners']):
        record=data['features'][32*ordinal:32*(ordinal+1)]
        assert owner['sourceRecord32Hex']==record.hex()
        assert owner['sourceRecord32SHA256']==sha(record)
        assert owner['metadataChanged'] is False
    return result,reads


def kept(result):
    assert result['totals']['ownersWithProposedChanges']==0
    assert result['totals']['adjacentPointOccurrencesRemoved']==0
    assert all(o['disposition']=='KEEP'for o in result['owners'])
    assert all(not p.get('removedVertexOrdinals',[])for o in result['owners']for p in o['parts'])


def untouched(part):
    assert not part.get('removedVertexOrdinals',[])
    assert part.get('proposedPointCount',part['sourcePointCount'])==part['sourcePointCount']
    if 'proposedTypedElementSHA256'in part:
        assert part['proposedTypedElementSHA256']==part['sourceTypedElementSHA256']


@pytest.mark.parametrize('usage',[4,5,8,9,16,17,20,21,24,25])
def test_closed_first_mask_hole_or_visible_part_is_preserved_as_true_polygon0(usage):
    data=fixture(parts=[(usage,1,RING),(24,1,OTHER)])
    result,_=analyze(data)
    first,later=result['owners'][0]['parts']
    assert first['decodedPolygonOrdinal']==0
    assert later['decodedPolygonOrdinal']==1
    untouched(first)
    assert later['removedVertexOrdinals']==[1,3,5,6]
    assert result['totals']['adjacentPointOccurrencesRemoved']==4
    assert first['usage']==usage
    assert result['owners'][0]['sourceProgrammeFrameSHA256']==sha(data['roFrame'])
    assert result['owners'][0]['sourceAdditionalFrameSHA256']==sha(data['aoFrame'])
    assert F.unpack(bytes.fromhex(result['owners'][0]['sourceRecord32Hex']))[3]==0xA1B2C3D4


def test_single_polygon0_never_changes_even_when_it_contains_many_adjacent_duplicates():
    result,_=analyze(fixture(parts=[(24,1,RING)]))
    kept(result)
    untouched(result['owners'][0]['parts'][0])


@pytest.mark.parametrize('first_usage,later_usage',[(24,24),(8,24),(20,24),(25,21)])
def test_exact_first_later_coordinate_span_alias_is_protected_not_cloned(first_usage,later_usage):
    data=fixture(parts=[(first_usage,1,RING),(later_usage,1,RING)],aliases={1:0})
    result,_=analyze(data)
    kept(result)
    parts=result['owners'][0]['parts']
    assert parts[0]['coordinateOffset']==parts[1]['coordinateOffset']
    assert parts[0]['decodedPolygonOrdinal']==0 and parts[1]['decodedPolygonOrdinal']==1
    assert parts[1]['anchorClassification']=='PROTECTED_POLYGON0_SPAN_ALIAS'
    untouched(parts[0]);untouched(parts[1])
    assert result['wholeSourceAliasSafetyApproved'] is False


@pytest.mark.parametrize('kind',[0,1,2,4,9,7,255])
def test_nonac_geometry_kinds_never_decode_even_an_apparently_valid_area(kind):
    result,reads=analyze(fixture(kinds=(kind,)),forbid_geometry=True)
    kept(result)
    assert not reads['indices']and not reads['coordinates']


def test_signed_depth_pair_shared_iframe_keeps_explicit_roles_and_all_depth_bits():
    xyz=[(100,100,-17),(100,100,-2147483648),(400,100,2147483647),(100,100,-1)]
    data=fixture(parts=[(0,1,xyz)],kinds=(4,9),
        contexts=[(PAPER,b'CS(SOUNDG01);'),(SIMPLE,b'CS(SOUNDG01);')])
    result,reads=analyze(data,forbid_geometry=True)
    kept(result)
    a,b=data['features'][:32],data['features'][32:]
    twin=bytearray(a);twin[28]=9;assert b==twin
    assert F.unpack(a)[1]==F.unpack(b)[1]
    assert [o['kind']for o in result['owners']]==[4,9]
    assert [struct.unpack_from('<i',data['coordinates'],8+4+12*j+8)[0]for j in range(4)]==[-17,-2147483648,2147483647,-1]
    assert not reads['coordinates']


EXCLUDED=[b'LS(SOLD,1,OWN35);',b'LS(DASH,2,OWN35);',b'AP(OWNPAT35);',
    b'SY(OWNSYM35);',b"TX('OWN',3,2,3,'15110',0,0,CHBLK,1);",
    b"TE('%s','OBJNAM',3,2,3,'15110',0,0,CHBLK,1);",b'CS(OWNCS35);',
    b'AC(OWNAC35);LS(SOLD,1,OWN35);',b'AC(OWNAC35);SY(OWNSYM35);']


@pytest.mark.parametrize('programme',EXCLUDED)
def test_nonac_programme_in_either_drawing_context_cannot_gain_anchor_exception(programme):
    for contexts in ([(PAPER,programme),(SIMPLE,AC)],[(PAPER,AC),(SIMPLE,programme)]):
        result,reads=analyze(fixture(contexts=contexts),forbid_geometry=True)
        kept(result)
        assert not reads['indices']and not reads['coordinates']


@pytest.mark.parametrize('extra',[(65680,struct.pack('<dd',12.25,-4.5)),
    (65682,struct.pack('<dd',0.25,0.75)),(123456,AC),(0,b'')])
def test_explicit_opaque_anchors_and_unknown_contexts_remain_excluded(extra):
    result,reads=analyze(fixture(contexts=[(PAPER,AC),(SIMPLE,AC),extra]),forbid_geometry=True)
    kept(result)
    assert not reads['coordinates']


BAD=[[],[A],[A,A],[A,B,A],[A]*8,[A,A,B,B,A,A],
    [A,A,B,B,(600,100),(600,100),A,A],[A,A,B,B,C,C],
    [(100,100),(400,400),(100,400),(400,100),(100,100)]]


@pytest.mark.parametrize('bad',BAD)
def test_invalid_later_ring_keeps_entire_owner_not_just_the_first_polygon(bad):
    result,_=analyze(fixture(parts=[(24,1,RING),(24,1,OTHER),(20,1,bad)]))
    kept(result)


@pytest.mark.parametrize('bad',BAD)
def test_invalid_first_ring_does_not_promote_a_later_part_to_an_invented_polygon0(bad):
    result,_=analyze(fixture(parts=[(24,1,bad),(24,1,OTHER)]))
    kept(result)


@pytest.mark.parametrize('root',[0,2,255])
def test_unresolved_root_basis_cannot_be_relabelled_as_polygon0(root):
    result,_=analyze(fixture(parts=[(24,root,RING),(24,1,OTHER)]))
    kept(result)


def test_open_endpoint_chain_is_not_assumed_to_have_one_polygon_per_stored_part():
    # At decoded-endpoint level these two consecutive segments can close one
    # polygon together. The bounded closed-ring classifier must not guess.
    data=fixture(parts=[(16,1,[A,B]),(16,1,[B,A]),(24,1,OTHER)])
    mapping=classify_polygon_groups(data['rawParts'])
    assert mapping['status']=='UNPROVEN_KEEP'
    assert mapping['partToPolygon'] is None
    assert mapping['polygon0PartOrdinals'] is None
    result,_=analyze(data)
    kept(result)


def test_closed_aliases_have_separate_polygon_ordinals_without_mask_or_role_filter():
    data=fixture(parts=[(8,1,RING),(20,1,RING),(24,1,OTHER)],aliases={1:0})
    mapping=classify_polygon_groups(data['rawParts'])
    assert mapping['status']=='RESOLVED_CLOSED_ROOT1_PARTS'
    assert mapping['partToPolygon']==[0,1,2]
    assert mapping['polygon0PartOrdinals']==[0]


def test_unknown_anchor_policy_does_not_silently_use_legacy_all_polygons_mode():
    with pytest.raises(ValueError):analyze(fixture(),policy='invented_unknown_policy')


def test_anchor_aware_pure_ac_never_infers_query_or_materialization_approval():
    result,_=analyze(fixture())
    assert result['totals']['ownersWithProposedChanges']==1
    assert result['nativeQueryDisposition']=='UNPROVEN'
    assert result['nativeQueryEquivalenceApproved'] is False
    assert result['materializationApproved'] is False
    assert result['operativeApplyAllowed'] is False
    assert result['wholeSourceAliasSafetyApproved'] is False


def test_classifier_ignores_invented_mapping_and_approval_assertions():
    data=fixture()
    asserted=[dict(part,decodedPolygonOrdinal=77,polygon0PartOrdinals=[1],
        queryEquivalenceApproved=True,materializationApproved=True,
        mappingResolved=True)for part in data['rawParts']]
    original=[dict(part)for part in asserted]
    result=classify_polygon_groups(asserted)
    assert asserted==original
    assert result['status']=='RESOLVED_CLOSED_ROOT1_PARTS'
    assert result['partToPolygon']==[0,1]
    assert result['polygon0PartOrdinals']==[0]
    assert result['queryEquivalenceApproved'] is False
    assert result['materializationApproved'] is False


def test_asserted_closed_mapping_cannot_override_actual_open_endpoint_bytes():
    data=fixture(parts=[(24,1,RING[:-1]+[B]),(24,1,OTHER)])
    asserted=[dict(part,closed=True,mappingResolved=True,
        polygon0PartOrdinals=[0])for part in data['rawParts']]
    result=classify_polygon_groups(asserted)
    assert result['status']=='UNPROVEN_KEEP'
    assert result['partToPolygon'] is None
    assert result['polygon0PartOrdinals'] is None
    assert result['queryEquivalenceApproved'] is False
    assert result['materializationApproved'] is False


def test_asserted_valid_receipt_cannot_override_truncated_root1_element():
    data=fixture()
    asserted=[dict(part)for part in data['rawParts']]
    asserted[0].update(raw=asserted[0]['raw'][:-1],valid=True,
        queryEquivalenceApproved=True,materializationApproved=True)
    with pytest.raises(ValueError,match='WHOLE_EXACT_ROOT1_XY_ELEMENT_REQUIRED'):
        classify_polygon_groups(asserted)


def test_partial_later_span_overlap_with_polygon0_is_protected():
    # Two valid, closed invented Root1 rings overlap by 64 wire bytes. A header
    # is deliberately also a legal U32 coordinate in the other typed element.
    h=(1<<24)|8
    ring=[(h,100),(h,100),(h+100,100),(h+100,100),
          (h+100,400),(h+100,400),(h,100),(h,100)]
    data=fixture(parts=[(24,1,ring),(24,1,OTHER)])
    first=struct.pack('<I',h)+b''.join(struct.pack('<II',*p)for p in ring)
    co=b'OWNCO35!'+first+struct.pack('<I',h)
    data['coordinates']=co
    data['indices']=b'OWNI'+struct.pack('<III',2,(24<<24)|8,(24<<24)|12)
    result,_=analyze(data)
    kept(result)
    a,b=result['owners'][0]['parts']
    assert a['coordinateOffset']==8 and b['coordinateOffset']==12
    assert b['anchorClassification']=='PROTECTED_POLYGON0_SPAN_ALIAS'
    untouched(a);untouched(b)


def test_later_span_alias_to_other_owners_polygon0_is_globally_protected():
    data=fixture()
    first_word,second_word=struct.unpack_from('<II',data['indices'],8)
    second_index=len(data['indices'])
    data['indices']+=struct.pack('<III',2,second_word,first_word)
    original=list(F.unpack(data['features']))
    original[0]+=1
    original[1]=second_index
    data['features']+=F.pack(*original)
    result,_=analyze(data)
    kept(result)
    for owner in result['owners']:
        first,later=owner['parts']
        assert first['anchorClassification']=='PRESERVED_DECODED_POLYGON0'
        assert later['anchorClassification']=='PROTECTED_POLYGON0_SPAN_ALIAS'
        untouched(first);untouched(later)
    assert result['protectedAliasScope']=='ALL_RESOLVED_ELIGIBLE_OWNERS_IN_THIS_ANALYSIS'
