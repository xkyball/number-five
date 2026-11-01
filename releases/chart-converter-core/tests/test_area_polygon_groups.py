"""Invented Root1 bytes only; no original decoder or chart fixtures/execution."""
import copy
import struct

import pytest

from no_chart_converter.rx5.area_polygon_groups import classify_polygon_groups


def ring(points=None, *, usage=8, offset=0):
    points = points or [(11, 20), (30, 20), (30, 40), (11, 20)]
    return {'indexWord': usage << 24 | offset,
            'raw': struct.pack('<I', 0x01000000 | len(points)) +
                   b''.join(struct.pack('<II', *point) for point in points)}


def test_all_closed_parts_preserve_source_order_and_first_hole_or_mask_is_polygon0():
    parts = [ring(usage=12, offset=80), ring(usage=24, offset=0), ring(usage=20, offset=160)]
    before = copy.deepcopy(parts)
    result = classify_polygon_groups(parts)
    assert result['status'] == 'RESOLVED_CLOSED_ROOT1_PARTS'
    assert result['partToPolygon'] == [0, 1, 2]
    assert result['polygon0PartOrdinals'] == [0]
    assert [group['partOrdinals'] for group in result['groups']] == [[0], [1], [2]]
    assert [group['role'] for group in result['groups']] == ['interior', 'exterior', 'interior']
    assert [group['masked'] for group in result['groups']] == [True, False, False]
    assert parts == before
    assert result['nativeExecutionPerformed'] is False
    assert result['queryEquivalenceApproved'] is result['materializationApproved'] is False


@pytest.mark.parametrize('usage', range(256))
def test_closed_membership_does_not_guess_local_reversal_or_filter_usage(usage):
    result = classify_polygon_groups([ring(usage=usage)])
    assert result['status'] == 'RESOLVED_CLOSED_ROOT1_PARTS'
    assert result['partToPolygon'] == [0]
    assert result['polygon0PartOrdinals'] == [0]
    group = result['groups'][0]
    assert group['role'] == ('interior' if usage & 4 else 'exterior')
    assert group['masked'] == (usage & 0x30 == 0)
    assert 'reversed' not in group  # No assumed flag&1 reverse predicate.


def test_reversing_entire_closed_source_part_preserves_membership_without_oracle_claim():
    points = [(1, 2), (3, 4), (5, 2), (1, 2)]
    a, b = ring(points, usage=24), ring(list(reversed(points)), usage=25)
    assert classify_polygon_groups([a])['partToPolygon'] == classify_polygon_groups([b])['partToPolygon'] == [0]
    assert classify_polygon_groups([a])['parts'][0]['suppliedElementSHA256'] != classify_polygon_groups([b])['parts'][0]['suppliedElementSHA256']


def test_aliased_occurrences_are_not_deduplicated_or_reordered():
    a, b = ring(usage=8, offset=32), ring(usage=20, offset=32)
    result = classify_polygon_groups([a, b, dict(a)])
    assert result['partToPolygon'] == [0, 1, 2]
    assert [group['sourceTypedElementSHA256'] for group in result['groups']] == [result['groups'][0]['sourceTypedElementSHA256']]*3
    assert result['polygon0PartOrdinals'] == [0]


def test_open_parts_do_not_get_grouped_even_when_the_chain_would_close_in_raw_space():
    a = ring([(1, 1), (2, 1), (3, 1), (4, 1)], offset=0)
    b = ring([(4, 1), (3, 3), (2, 3), (1, 1)], offset=64)
    result = classify_polygon_groups([a, b, ring(offset=128)])
    assert result['status'] == 'UNPROVEN_KEEP'
    assert result['partToPolygon'] is result['polygon0PartOrdinals'] is None
    assert result['groups'] == []
    assert {r['reason'] for r in result['reasons']} == {'OPEN_PART_GROUPING_UNPROVEN'}


def test_later_unresolved_part_invalidates_the_complete_mapping_not_just_its_suffix():
    raw0 = struct.pack('<I', 0x00000020)  # Header only; no guessed U16/U32 body.
    result = classify_polygon_groups([ring(), {'indexWord': 64, 'raw': raw0}])
    assert result['status'] == 'UNPROVEN_KEEP'
    assert result['groups'] == [] and result['polygon0PartOrdinals'] is None
    assert result['reasons'][0]['reason'] == 'UNSUPPORTED_ROOT_ENCODING'


@pytest.mark.parametrize('root', [0, 2, 3, 128, 255])
def test_unknown_root_encodings_are_kept_without_inferred_body_stride(root):
    result = classify_polygon_groups([{'indexWord': 0x18000000, 'raw': struct.pack('<I', root << 24 | 1000)}])
    assert result['status'] == 'UNPROVEN_KEEP'
    assert result['reasons'][0]['reason'] == 'UNSUPPORTED_ROOT_ENCODING'


@pytest.mark.parametrize('count', [0, 1, 2, 3])
def test_small_native_closure_edge_cases_are_outside_the_supported_ring_subset(count):
    points = [(7, 8)] * count
    raw = struct.pack('<I', 0x01000000 | count) + b''.join(struct.pack('<II', *p) for p in points)
    result = classify_polygon_groups([{'indexWord': 0, 'raw': raw}])
    assert result['status'] == 'UNPROVEN_KEEP'
    assert result['reasons'][0]['reason'] == 'OUTSIDE_CLOSED_RING_MINIMUM_SUBSET'


def test_receipt_booleans_do_not_override_source_endpoints_or_root_type():
    part = ring([(1, 2), (3, 2), (4, 4), (5, 5)])
    part.update(closed=True, polygonOrdinal=0, anchorProven=True, materializationApproved=True)
    result = classify_polygon_groups([part])
    assert result['status'] == 'UNPROVEN_KEEP'
    assert result['queryEquivalenceApproved'] is result['materializationApproved'] is False


def test_raw_ieee_nan_bit_patterns_remain_unsigned_integer_coordinates():
    points = [(0x7fc00000, 0xffffffff), (0, 1), (1, 0), (0x7fc00000, 0xffffffff)]
    assert classify_polygon_groups([ring(points)])['status'] == 'RESOLVED_CLOSED_ROOT1_PARTS'


@pytest.mark.parametrize('bad', [True, -1, 1 << 32, float('nan'), float('inf'), 1.5, '24'])
def test_index_words_must_be_unsigned_integer_values_not_bool_or_nonfinite(bad):
    part = ring();part['indexWord'] = bad
    with pytest.raises(ValueError, match='UNSIGNED32'):classify_polygon_groups([part])


@pytest.mark.parametrize('bad', [bytearray(ring()['raw']), memoryview(ring()['raw']), [], None, b'123'])
def test_only_immutable_complete_element_headers_are_accepted(bad):
    with pytest.raises(ValueError, match='IMMUTABLE_ELEMENT'):
        classify_polygon_groups([{'indexWord': 0, 'raw': bad}])


@pytest.mark.parametrize('suffix', [b'X', b'ABCDEFGH'])
def test_complete_root1_typed_extent_is_exact_no_unbound_trailing_bytes(suffix):
    part = ring();part['raw'] += suffix
    with pytest.raises(ValueError, match='WHOLE_EXACT_ROOT1'):classify_polygon_groups([part])


def test_truncated_root1_body_is_not_misclassified_as_a_keep():
    part = ring();part['raw'] = part['raw'][:-1]
    with pytest.raises(ValueError, match='WHOLE_EXACT_ROOT1'):classify_polygon_groups([part])


def test_conflicting_identical_or_partial_coordinate_intervals_are_refused():
    a, b = ring(), ring([(10, 20), (30, 20), (30, 40), (10, 20)])
    with pytest.raises(ValueError, match='CONFLICTING_OVERLAPPING'):
        classify_polygon_groups([a, b])
    b['indexWord'] = 0x08000008
    with pytest.raises(ValueError, match='CONFLICTING_OVERLAPPING'):
        classify_polygon_groups([a, b])


def test_consistent_nested_and_chained_overlaps_are_not_false_conflicts():
    # Root0 deliberately stays UNPROVEN; byte-overlap consistency is independent
    # of its unknown coordinate stride. Intervals include a contained span and
    # an extension followed by another span inside the earlier union.
    raw = bytes(range(64))
    parts = [{'indexWord': offset, 'raw': raw[offset:offset+length]}
             for offset, length in [(0, 40), (4, 12), (24, 32), (32, 16)]]
    result = classify_polygon_groups(parts)
    assert result['status'] == 'UNPROVEN_KEEP'


def test_empty_input_and_explicit_tool_budgets_never_claim_a_format_limit():
    assert classify_polygon_groups([])['reasons'] == [{'reason': 'NO_SOURCE_PARTS'}]
    assert classify_polygon_groups([ring(), ring(offset=64)], max_parts=1)['status'] == 'UNPROVEN_KEEP'
    result = classify_polygon_groups([ring()], max_total_typed_bytes=4)
    assert result['reasons'][0]['reason'] == 'TYPED_BYTE_TOOL_BUDGET_EXCEEDED_NOT_FORMAT_LIMIT'
    assert classify_polygon_groups([ring()], max_total_typed_bytes=100)['status'] == 'RESOLVED_CLOSED_ROOT1_PARTS'


@pytest.mark.parametrize('kwargs', [{'max_parts': True}, {'max_parts': 0}, {'max_total_typed_bytes': -1}])
def test_invalid_tool_budgets_are_not_interpreted_as_semantics(kwargs):
    with pytest.raises(ValueError):classify_polygon_groups([ring()], **kwargs)


def test_returned_evidence_is_static_model_metadata_not_new_execution_or_mutable_global_state():
    result = classify_polygon_groups([ring()])
    assert result['modelEvidence']['interpretation'] == 'STATIC_ORIGINAL_METHOD_LISTINGS_NOT_NEW_ORIGINAL_EXECUTION'
    result['modelEvidence']['interpretation'] = 'caller changed result'
    assert classify_polygon_groups([ring()])['modelEvidence']['interpretation'].startswith('STATIC_')
