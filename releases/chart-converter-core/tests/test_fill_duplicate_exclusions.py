"""Invented tiny wire fixtures; no charts, native runtimes or external files.

Every coordinate, UID, attribute, document/registry label and programme in this
file is generated below. The tests exercise analyze-only exclusions, not native
render equivalence or permission to materialize a modified chart.
"""
import hashlib
import json
import struct

import pytest

from no_chart_converter.rx5.fill_duplicates import analyze_fill_duplicates
from no_chart_converter.rx5.metadata import Stream


FEATURE = struct.Struct('<Q5I4B')
PAPER, SIMPLE = 196608, 196609
UID = 0x1122334455667788
A, B, C = (100, 100), (300, 100), (300, 300)
STUTTER = [A, A, B, B, C, C, A, A]
AC = b'AC(OWNFILL1);'


def sha(raw):
    return hashlib.sha256(raw).hexdigest()


def own_additional_frame():
    # Explicit opaque registry prefix, regular/national attrs and an invented
    # FFPT/document-like entry, all framed independently of product helpers.
    regular = (struct.pack('<H', 2) + struct.pack('<H', 116) + b'OWN AREA\0'
               + struct.pack('<H', 174) + b'-1.25\0')
    national = struct.pack('<HH', 1, 116) + b'OWN NATIONAL NAME\0'
    related = (struct.pack('<H', 1) + struct.pack('<QB', 0xABCDEF0123456789, 2)
               + b'OWN-REGISTRY-ENTRY-77\0')
    return struct.pack('<II', 0x89ABCDEF, 0x76543210) + regular + national + related


def fixture(*, kinds=(3,), contexts=None, parts=None, uid=UID,
            dictionary=0x12345678, word20=29):
    contexts = [(PAPER, AC), (SIMPLE, AC)] if contexts is None else contexts
    parts = [(24, 1, STUTTER)] if parts is None else parts
    coordinates = bytearray(b'OWN-CO-PAD!!')  # 12 bytes; offsets are not assumed0.
    words = []
    for usage, root, points in parts:
        words.append((usage << 24) | len(coordinates))
        coordinates += struct.pack('<I', (root << 24) | len(points))
        for point in points:
            coordinates += struct.pack('<IIi' if len(point) == 3 else '<II', *point)
    indices = b'OWN-IDX!' + struct.pack('<I', len(words))
    indices += b''.join(struct.pack('<I', word) for word in words)
    additional = b'AO!' + own_additional_frame()
    frame = struct.pack('<HI', len(contexts), 3)
    for key, text in contexts:
        frame += struct.pack('<IH', key, len(text)) + text
    render = b'RO!!!' + frame
    features = b''.join(FEATURE.pack(uid, 8, 5, dictionary, word20,
                                   129 if kind in (4, 9) else 42,
                                   kind, 7, 0xA5, 0x5A) for kind in kinds)
    return dict(features=features, indices=indices, coordinates=bytes(coordinates),
                render=render, additional=additional, frame=frame,
                additionalFrame=additional[3:])


def run(data, *, streams=False, forbid_geometry=False):
    before = {key: value for key, value in data.items() if type(value) is bytes}
    calls = {name: [] for name in ('indices', 'coordinates', 'render', 'additional')}
    values = {}
    for name in calls:
        payload = data[name]
        if streams or forbid_geometry:
            def read(at, count, *, name=name, payload=payload):
                calls[name].append((at, count))
                if forbid_geometry and name in ('indices', 'coordinates'):
                    raise AssertionError('An excluded owner decoded geometry')
                return payload[at:at+count]
            values[name] = Stream(len(payload), read)
        else:
            values[name] = payload
    result = analyze_fill_duplicates(data['features'], values['indices'],
        values['coordinates'], values['render'], additional=values['additional'])
    assert before == {key: value for key, value in data.items() if type(value) is bytes}
    assert result['sourceFeatureSHA256'] == sha(data['features'])
    assert result['analyzeOnly'] is True
    assert result['operativeApplyAllowed'] is False
    assert result['coordinateOrIndexOrMetadataBuffersReturned'] is False
    assert result['nativeQueryDisposition'] == 'UNPROVEN'
    assert result['nativeAnchorFallbackEquivalenceApproved'] is False
    assert result['originalNativeBinaryUsed'] is False
    json.dumps(result)  # Receipt must not contain modified binary buffers.
    for ordinal, owner in enumerate(result['owners']):
        raw = data['features'][32*ordinal:32*(ordinal+1)]
        assert owner['sourceRecord32Hex'] == raw.hex()
        assert owner['sourceRecord32SHA256'] == sha(raw)
        assert owner['uid'] == str(FEATURE.unpack(raw)[0])
        assert owner['kind'] == raw[28]
        assert owner['metadataChanged'] is False
    return result, calls


def assert_keep(result):
    assert result['totals']['ownersWithProposedChanges'] == 0
    assert result['totals']['adjacentPointOccurrencesRemoved'] == 0
    assert result['totals']['partContainersRemoved'] == 0
    assert all(owner['disposition'] == 'KEEP' for owner in result['owners'])
    assert all(not part.get('removedVertexOrdinals', []) for owner in result['owners']
               for part in owner['parts'])


@pytest.mark.parametrize('usage', [8, 9, 20, 21, 24, 25])
@pytest.mark.parametrize('streams', [False, True])
def test_positive_ac_control_preserves_start_closure_usage_registry_and_framing(usage, streams):
    data = fixture(parts=[(usage, 1, STUTTER)])
    result, _ = run(data, streams=streams)
    owner = result['owners'][0]
    part = owner['parts'][0]
    assert owner['disposition'] == 'PROPOSED_ADJACENT_DUPLICATE_SUBSEQUENCE'
    assert part['keptVertexOrdinals'] == [0, 2, 4, 7]
    assert part['removedVertexOrdinals'] == [1, 3, 5, 6]
    assert part['usage'] == usage
    assert part['sourcePointCount'] == 8 and part['proposedPointCount'] == 4
    assert part['sourceTypedElementSHA256'] == sha(data['coordinates'][12:])
    expected = struct.pack('<I', 0x01000004) + b''.join(struct.pack('<II', *p) for p in (A, B, C, A))
    assert part['proposedTypedElementSHA256'] == sha(expected)
    assert owner['sourceProgrammeFrameSHA256'] == sha(data['frame'])
    assert owner['sourceAdditionalFrameSHA256'] == sha(data['additionalFrame'])
    assert owner['queryOffset'] == 3
    assert FEATURE.unpack(bytes.fromhex(owner['sourceRecord32Hex']))[3:5] == (0x12345678, 29)
    assert result['totals']['partContainersRemoved'] == 0


def test_ac_control_does_not_simplify_collinear_but_nonduplicate_vertices():
    middle = (200, 100)
    data = fixture(parts=[(24, 1, [A, middle, B, B, C, A, A])])
    result, _ = run(data)
    assert result['owners'][0]['parts'][0]['keptVertexOrdinals'] == [0, 1, 2, 4, 6]


@pytest.mark.parametrize('kind', [0, 1, 2, 4, 9, 7, 255])
def test_every_nonarea_kind_keeps_uid_and_fields_without_geometry_reads(kind):
    result, calls = run(fixture(kinds=(kind,)), forbid_geometry=True)
    assert_keep(result)
    assert not calls['indices'] and not calls['coordinates']


@pytest.mark.parametrize('style', [b'LS(SOLD,2,OWNLINE);', b'LS(DASH,1,OWNLINE);'])
def test_real_line_style_families_are_not_area_simplified(style):
    result, calls = run(fixture(kinds=(2,), contexts=[(PAPER, style), (SIMPLE, style)]),
                        forbid_geometry=True)
    assert_keep(result)
    assert not calls['coordinates']


@pytest.mark.parametrize('streams', [False, True])
def test_signed_xyz12_kind4_kind9_shared_iframe_and_pair_are_untouched(streams):
    xyz = [(100,100,-17), (100,100,-2147483648), (300,100,2147483647),
           (100,100,-1)]
    data = fixture(kinds=(4,9), parts=[(0,1,xyz)],
                   contexts=[(PAPER,b'CS(SOUNDG01);'),(SIMPLE,b'CS(SOUNDG01);')])
    four, nine = data['features'][:32], data['features'][32:]
    expected = bytearray(four); expected[28] = 9
    assert nine == expected
    assert FEATURE.unpack(four)[1] == FEATURE.unpack(nine)[1] == 8
    result, calls = run(data, streams=streams, forbid_geometry=True)
    assert_keep(result)
    assert [row['kind'] for row in result['owners']] == [4,9]
    assert [struct.unpack_from('<i',data['coordinates'],12+4+12*i+8)[0]
            for i in range(4)] == [-17,-2147483648,2147483647,-1]
    assert result['inputStreams']['coordinates']['readCalls'] == 0
    assert not calls['coordinates']


EXCLUDED_PROGRAMMES = [
    b'LS(SOLD,1,OWNLINE);', b'LS(DASH,2,OWNLINE);', b'AP(OWNPAT01);',
    b'SY(OWNSYM01);', b"TX('OWN',3,2,3,'15110',0,0,CHBLK,1);",
    b"TE('%s','OBJNAM',3,2,3,'15110',0,0,CHBLK,1);", b'CS(OWNRULE1);',
    b'AC(OWNFILL1);SY(OWNSYM01);', b'AC(OWNFILL1);LS(SOLD,1,OWNLINE);',
    b'AC(OWNFILL1);;', b' AC(OWNFILL1);', b'AC(OWNFILL1,4);', b'\xffAC(OWNFILL1);',
]


@pytest.mark.parametrize('programme', EXCLUDED_PROGRAMMES)
def test_nonac_text_symbol_anchor_and_style_programmes_never_read_geometry(programme):
    result, calls = run(fixture(contexts=[(PAPER,AC),(SIMPLE,programme)]),
                        forbid_geometry=True)
    assert_keep(result)
    assert not calls['indices'] and not calls['coordinates']


@pytest.mark.parametrize('extra', [(65682,struct.pack('<dd',0.25,0.5)),
                                  (123456,b'AC(OWNFILL1);'), (0,b'')])
def test_unknown_or_binary_anchor_context_is_preserved_without_geometry(extra):
    result, calls = run(fixture(contexts=[(PAPER,AC),(SIMPLE,AC),extra]),
                        forbid_geometry=True)
    assert_keep(result)
    assert not calls['coordinates']


@pytest.mark.parametrize('contexts', [[],[(PAPER,AC)],[(SIMPLE,AC)]])
def test_both_known_drawing_contexts_are_required(contexts):
    result, _ = run(fixture(contexts=contexts), forbid_geometry=True)
    assert_keep(result)


UNSAFE_RINGS = [[], [A], [A,A], [A,B,A], [A]*8, [A,A,B,B,A,A],
                [A,A,B,B,(500,100),(500,100),A,A],
                [A,A,B,B,C,C],
                [(100,100),(300,300),(100,300),(300,100),(100,100)]]


@pytest.mark.parametrize('points', UNSAFE_RINGS)
def test_open_short_collapsed_collinear_and_invalid_rings_keep_whole_owner(points):
    result, _ = run(fixture(parts=[(24,1,points)]))
    assert_keep(result)


@pytest.mark.parametrize('points', UNSAFE_RINGS)
def test_one_unsafe_part_cancels_valid_part_proposals_for_entire_owner(points):
    result, _ = run(fixture(parts=[(24,1,STUTTER),(20,1,points)]))
    assert_keep(result)


@pytest.mark.parametrize('root', [0,2,255])
def test_unresolved_root_reads_only_four_byte_header_and_keeps(root):
    data = fixture()
    data['coordinates'] = data['coordinates'][:12] + struct.pack('<I',(root<<24)|1000)
    result, calls = run(data, streams=True)
    assert_keep(result)
    assert calls['coordinates'] == [(12,4)]
    part = result['owners'][0]['parts'][0]
    assert part['sourceTypedElementSHA256'] is None
    assert part['sourceTypedElementBytes'] is None


@pytest.mark.parametrize('mutation', ['feature31','ro_header','ro_payload','index_count',
                                      'coordinate_count','coordinate_body','ao_empty','ao_nul'])
def test_malformed_eligible_wire_framing_is_refused(mutation):
    data = fixture()
    if mutation == 'feature31': data['features'] = data['features'][:-1]
    elif mutation == 'ro_header': data['render'] = data['render'][:8]
    elif mutation == 'ro_payload': data['render'] = data['render'][:-1]
    elif mutation == 'index_count': data['indices'] = data['indices'][:-1]
    elif mutation == 'coordinate_count':
        data['coordinates'] = data['coordinates'][:12] + struct.pack('<I',0x01FFFFFF)
    elif mutation == 'coordinate_body': data['coordinates'] = data['coordinates'][:-1]
    elif mutation == 'ao_empty': data['additional'] = b''
    elif mutation == 'ao_nul':
        data['additional'] = b'AO!' + bytes(8) + struct.pack('<HH',1,116) + b'OWN WITHOUT TERMINATOR'
    with pytest.raises(ValueError): run(data)


def test_duplicate_render_keys_are_refused_instead_of_overwriting_one_context():
    data = fixture(contexts=[(PAPER,AC),(SIMPLE,AC),(PAPER,b'SY(OWNSYM01);')])
    with pytest.raises(ValueError): run(data, forbid_geometry=True)


def test_no_adjacent_duplicates_is_keep_not_permission_to_materialize():
    result, _ = run(fixture(parts=[(24,1,[A,B,C,A])]))
    assert_keep(result)
    assert result['materializationApproved'] is False
    assert result['wholeSourceAliasSafetyApproved'] is False
    assert result['actualCountryDuplicateCountAvailable'] is False


def test_nonadjacent_repetition_is_not_removed_as_a_global_unique_operation():
    # Self-touching polygon is a conservative whole-owner KEEP, not a chance
    # to remove the second B or rewrite its topology.
    result, _ = run(fixture(parts=[(24,1,[A,B,C,B,(100,300),A])]))
    assert_keep(result)
