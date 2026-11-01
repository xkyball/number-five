"""Invented anchor-preserving analysis cases; no original binary or chart."""
import hashlib
import struct

import pytest

from no_chart_converter.rx5.fill_duplicates import analyze_fill_duplicates
from no_chart_converter.rx5.wire import FEATURE

RING = [(10, 10), (10, 10), (20, 10), (20, 10), (20, 20), (10, 20), (10, 10), (10, 10)]
LATER = [(x+30, y+40) for x, y in RING]
MODE = 'preserve_native_polygon0'


def fixture(rings=None, *, usages=None, index_order=None):
    rings = [RING, LATER] if rings is None else rings
    usages = [24]*len(rings) if usages is None else usages
    coordinates, offsets = bytearray(), []
    for points in rings:
        offsets.append(len(coordinates))
        coordinates += struct.pack('<I', 0x01000000 | len(points))
        coordinates += b''.join(struct.pack('<II', *point) for point in points)
    index_order = list(range(len(rings))) if index_order is None else index_order
    indices = struct.pack('<I', len(index_order)) + b''.join(
        struct.pack('<I', (usages[i] << 24) | offsets[i]) for i in index_order)
    contexts = [(196608, b'AC(TEST);'), (196609, b'AC(TEST)')]
    render = struct.pack('<HI', len(contexts), 0) + b''.join(
        struct.pack('<IH', key, len(body)) + body for key, body in contexts)
    record = FEATURE.pack(4242, 0, 0, 7, 19, 42, 3, 1, 2, 3)
    return record, indices, bytes(coordinates), render


def analyze(args):
    return analyze_fill_duplicates(*args, anchor_policy=MODE)


def test_later_ring_reduces_real_proposed_counts_while_polygon0_is_exact():
    args = fixture()
    before = tuple(args)
    legacy = analyze_fill_duplicates(*args)
    out = analyze(args)
    owner, = out['owners']
    first, later = owner['parts']
    assert legacy['totals']['adjacentPointOccurrencesRemoved'] == 6
    assert first['anchorClassification'] == 'PRESERVED_DECODED_POLYGON0'
    assert first['keptVertexOrdinals'] == list(range(8))
    assert first['removedVertexOrdinals'] == []
    assert first['proposedTypedElementSHA256'] == first['sourceTypedElementSHA256']
    assert later['anchorClassification'] == 'LATER_DECODED_POLYGON_CANDIDATE'
    assert later['keptVertexOrdinals'] == [0, 2, 4, 5, 7]
    assert later['decodedPolygonOrdinal'] == 1
    assert out['totals']['adjacentPointOccurrencesRemoved'] == 3
    assert out['anchorCandidateTotals']['polygon0DuplicatePointOccurrencesBlocked'] == 3
    model = out['hypotheticalDecoderWork']
    assert model['partContainersBeforeAndAfter'] == 2
    assert model['getXCallsBefore'] == model['getYCallsBefore'] == 36
    assert model['getXCallsAfter'] == model['getYCallsAfter'] == 30
    assert owner['anchorInputDisposition'] == 'ORIGINAL_POLYGON0_INPUT_ORDER_FLAGS_RETAINED'
    assert out['nativeQueryDisposition'] == owner['queryAdmission'] == 'UNPROVEN'
    assert out['operativeApplyAllowed'] is False
    assert out['nativeAnchorFallbackEquivalenceApproved'] is False
    assert tuple(args) == before


@pytest.mark.parametrize('usage', [4, 5, 8, 9, 16, 17, 20, 21, 24, 25])
def test_first_polygon_is_never_skipped_by_hole_mask_or_reversal(usage):
    out = analyze(fixture(usages=[usage, 24]))
    owner = out['owners'][0]
    assert owner['polygonMappingStatus'] == 'RESOLVED_CLOSED_ROOT1_PARTS'
    assert owner['polygon0PartOrdinals'] == [0]
    assert owner['parts'][0]['decodedPolygonOrdinal'] == 0
    assert owner['parts'][0]['usage'] == usage
    assert owner['parts'][0]['removedVertexOrdinals'] == []
    assert out['totals']['adjacentPointOccurrencesRemoved'] == 3


def test_logical_index_order_not_physical_coordinate_offset_controls_polygon0():
    out = analyze(fixture(index_order=[1, 0]))
    first, later = out['owners'][0]['parts']
    assert first['coordinateOffset'] > later['coordinateOffset']
    assert first['anchorClassification'] == 'PRESERVED_DECODED_POLYGON0'
    assert later['anchorClassification'] == 'LATER_DECODED_POLYGON_CANDIDATE'
    assert out['totals']['adjacentPointOccurrencesRemoved'] == 3


def test_polygon0_exact_alias_later_is_kept_without_clone_approval():
    out = analyze(fixture(index_order=[0, 0]))
    first, later = out['owners'][0]['parts']
    assert first['sourceTypedElementSHA256'] == later['sourceTypedElementSHA256']
    assert later['anchorClassification'] == 'PROTECTED_POLYGON0_SPAN_ALIAS'
    assert later['removedVertexOrdinals'] == []
    assert out['anchorCandidateTotals']['aliasDuplicatePointOccurrencesBlocked'] == 3
    assert out['totals']['adjacentPointOccurrencesRemoved'] == 0
    assert out['wholeSourceAliasSafetyApproved'] is False


def test_global_cross_owner_polygon0_alias_protects_later_occurrence():
    features, indices, coordinates, render = fixture()
    second_index = len(indices)
    second_word, = struct.unpack_from('<I', indices, 8)
    indices += struct.pack('<II', 1, second_word)
    features += FEATURE.pack(5000, second_index, 0, 7, 19, 42, 3, 1, 2, 3)
    out = analyze((features, indices, coordinates, render))
    assert out['protectedAliasScope'] == 'ALL_RESOLVED_ELIGIBLE_OWNERS_IN_THIS_ANALYSIS'
    assert out['owners'][0]['parts'][1]['anchorClassification'] == 'PROTECTED_POLYGON0_SPAN_ALIAS'
    assert out['owners'][1]['parts'][0]['anchorClassification'] == 'PRESERVED_DECODED_POLYGON0'
    assert out['totals']['adjacentPointOccurrencesRemoved'] == 0


def test_single_polygon_has_no_eligible_savings_from_its_stutters():
    out = analyze(fixture([RING]))
    assert out['owners'][0]['disposition'] == 'KEEP'
    assert out['owners'][0]['reason'] == 'NO_UNPROTECTED_LATER_POLYGON_DUPLICATES'
    assert out['totals']['adjacentPointOccurrencesRemoved'] == 0
    assert out['hypotheticalDecoderWork']['getXCallsBefore'] == out['hypotheticalDecoderWork']['getXCallsAfter']


def test_open_or_unsafe_part_keeps_entire_owner_and_does_not_bless_later_ring():
    out = analyze(fixture([RING[:-2], LATER]))
    owner, = out['owners']
    assert owner['disposition'] == 'KEEP'
    assert owner['polygonMappingStatus'] == 'NOT_RESOLVED_WHOLE_OWNER_UNCHANGED'
    assert owner['anchorInputDisposition'] == 'WHOLE_OWNER_KEEP'
    assert out['totals']['adjacentPointOccurrencesRemoved'] == 0
    assert all('decodedPolygonOrdinal' not in part for part in owner['parts'])


def test_shared_classifier_unresolved_mapping_cannot_be_a_receipt_approval(monkeypatch):
    from no_chart_converter.rx5 import area_polygon_groups
    monkeypatch.setattr(area_polygon_groups, 'classify_polygon_groups', lambda _: {
        'status': 'UNPROVEN_KEEP', 'partToPolygon': None,
        'polygon0PartOrdinals': None, 'groups': [], 'reasons': ['TOOL_BUDGET']})
    out = analyze(fixture())
    assert out['owners'][0]['reason'] == 'UNRESOLVED_NATIVE_POLYGON_MAPPING'
    assert out['totals']['adjacentPointOccurrencesRemoved'] == 0
    assert out['hypotheticalDecoderWork']['modeledOwners'] == 0
    assert out['operativeApplyAllowed'] is False


@pytest.mark.parametrize('bad', [None, False, 1, 'native_pass', 'preserve_part0'])
def test_unknown_policy_cannot_promote_a_candidate(bad):
    with pytest.raises(ValueError, match='KNOWN_ANCHOR_ANALYSIS_POLICY_REQUIRED'):
        analyze_fill_duplicates(*fixture(), anchor_policy=bad)


def test_later_proposed_typed_hash_is_the_exact_source_subsequence():
    args = fixture()
    part = analyze(args)['owners'][0]['parts'][1]
    raw = args[2][part['coordinateOffset']:part['coordinateOffset']+part['sourceTypedElementBytes']]
    proposed = struct.pack('<I', 0x01000000 | len(part['keptVertexOrdinals'])) + b''.join(
        raw[4+8*i:12+8*i] for i in part['keptVertexOrdinals'])
    assert hashlib.sha256(proposed).hexdigest() == part['proposedTypedElementSHA256']
    assert part['queryObligation'] == 'VISIBLE_NULL_SEGMENT_QUERY_UNPROVEN'
