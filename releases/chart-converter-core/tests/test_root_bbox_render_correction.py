"""Entirely invented bytes/coordinates; no chart or original-runtime fixtures."""
import hashlib
import struct

import pytest

from no_chart_converter.rx5 import model
from no_chart_converter.rx5 import root_bbox_render_correction as correction
from no_chart_converter.rx5.header import new_plain_vector_header
from no_chart_converter.rx5.integer_region_certificate import certify_exterior_integer_regions
from no_chart_converter.rx5.wire import FEATURE

Q = correction.ROOT
TILE = (20, 400000, 630000)
UID = 123456789
NAMES = correction.NAMES
OUTPUTS = correction.OUTPUTS


def sha(raw):
    return hashlib.sha256(raw).hexdigest()


def frame(contexts, query):
    return struct.pack('<HI', len(contexts), query) + b''.join(
        struct.pack('<IH', int(key), len(value)) + value for key, value in contexts.items())


def ring(x, y):
    return [(x, y), (x+100, y), (x+100, y+100), (x, y+100), (x, y)]


def fixture(*, rings=None, tile=TILE, contexts=None):
    z, x, y = tile
    left = x*Q//(1 << z)-2_000_000
    top = y*Q//(1 << z)
    if rings is None:
        rings = [ring(left, top), ring(left-10_000, top+20_000)]
    arena, words = bytearray(), []
    for points in rings:
        words.append((24 << 24) | len(arena))
        arena += struct.pack('<I', (1 << 24) | len(points))
        arena += b''.join(struct.pack('<II', *p) for p in points)
    indices = struct.pack('<I', len(words)) + b''.join(struct.pack('<I', v) for v in words)
    ao_prefix = b'OWN-AO-PREFIX'
    query = len(ao_prefix)
    # Typed/national/FFPT data are arbitrary OWN examples and must stay opaque.
    query_frame = (struct.pack('<QH', 9876, 1) + struct.pack('<H', 87) + b'12.5\0'
                   + struct.pack('<H', 1) + struct.pack('<H', 9) + b'OWN\0'
                   + struct.pack('<H', 1) + struct.pack('<QB', 7654, 3) + b'link\0')
    additional = ao_prefix + query_frame + b'OWN-UNUSED-AO'
    if contexts is None:
        contexts = {'65791': b'category\0\xff', '196608': b'AC(OWN_COLOR);',
                    '196610': b'SY(OWN_SYMBOL)', '196609': b'AC(OWN_COLOR);AC(OWN_OTHER)',
                    '65535': b'opaque\x00query\xff'}
    render = frame(contexts, query) + b'OWN-UNUSED-RO'
    record = FEATURE.pack(UID, 0, 0, 70, 0x12345678, 42, 3, 9, 8, 7)
    # Another logical owner aliases the original RO/I/CO: its complete FI/RO stay unchanged.
    alias = FEATURE.pack(UID+1, 0, 0, 71, 0x87654321, 99, 3, 6, 5, 4)
    buffers = (record+alias, indices, bytes(arena), render, additional)
    header = new_plain_vector_header(bytes(range(16)))
    args = dict(policy=correction.POLICY, source_tile=tile, owner_uid=UID,
                expected_input_sha256=dict(zip(NAMES, map(sha, buffers))),
                source_header=header, expected_header_sha256=sha(header), source_file_bytes=12345)
    return buffers, args


def apply(buffers, args):
    return correction.prepare_root_bbox_render_correction(*buffers, **args)


def repin(buffers, args):
    return dict(args, expected_input_sha256=dict(zip(NAMES, map(sha, buffers))))


def replace_bytes(buffers, index, replacement):
    return buffers[:index] + (replacement,) + buffers[index+1:]


def test_only_two_programmes_and_selected_fi_word_change():
    buffers, args = fixture()
    result = apply(buffers, args)
    output = tuple(result[k] for k in OUTPUTS)
    assert output[1] is buffers[1] and output[2] is buffers[2] and output[4] is buffers[4]
    assert output[0][:12]+output[0][16:] == buffers[0][:12]+buffers[0][16:]
    assert output[0][32:] == buffers[0][32:]
    assert output[3][:len(buffers[3])] == buffers[3]
    old, old_query = model.programmes(buffers[3], 0)
    offset = struct.unpack_from('<I', output[0], 12)[0]
    assert offset == len(buffers[3])
    new, query = model.programmes(output[3], offset)
    assert query == old_query and list(new) == list(old)
    assert new == {k: b'' if k in correction.DRAW_CONTEXTS else v for k, v in old.items()}
    assert model.programmes(output[3], 0) == (old, old_query)
    assert sha(buffers[4]) == result['receipt']['inputPins']['additional']['sha256']
    assert len(output[3])-len(buffers[3]) == len(frame(new, query))


def test_explicit_correction_receipt_preserves_admission_and_restore_obligations():
    buffers, args = fixture()
    receipt = apply(buffers, args)['receipt']
    assert receipt['policy'] == correction.POLICY
    assert receipt['nativePixelParityClaim'] is False
    assert receipt['nativeIntegerRegionGuardInvokedOrChanged'] is False
    assert receipt['globalChartApplyAllowed'] is False
    assert receipt['defaultPipelineChanged'] is False
    assert receipt['actualNativeAdmittedDestinationDomainEstablished'] is False
    assert receipt['nativeTileSourceSelectionBindingPending'] is True
    assert receipt['requiresEveryActiveNativeTileUseBoundSourceScope'] is True
    assert receipt['thisHelperDoesNotWaiveRestoreOrAdmissionRequirements'] is True
    assert receipt['restoreRequirementsUnchangedAndIntegratorOwned'] is True
    assert receipt['treeLinksModified'] is False
    domain = receipt['mathematicalRootExclusionDestinationDomain']
    assert domain['minimumLevel'] == TILE[0] and domain['maximumLevel'] == 29
    certificate = receipt['rootBBoxCertificate']
    assert certificate['partCount'] == 2 and certificate['pointCount'] == 10
    assert certificate['intersectionVerticesCreated'] == 0
    assert certificate['integerProjectionInvoked'] is False
    assert all('LEFT' in r['strictOutsideSides'] for r in certificate['parts'])
    assert args['source_tile'] == TILE


@pytest.mark.parametrize('policy', [None, False, '', 'NO_INK', correction.POLICY.lower()])
def test_policy_never_implicitly_enabled(policy):
    buffers, args = fixture()
    with pytest.raises(ValueError, match='EXPLICIT_CORRECTION_POLICY_REQUIRED'):
        apply(buffers, dict(args, policy=policy))


def test_policy_is_required_keyword():
    buffers, args = fixture()
    del args['policy']
    with pytest.raises(TypeError):
        apply(buffers, args)


@pytest.mark.parametrize('index', range(5))
def test_every_complete_input_sha_is_required(index):
    buffers, args = fixture()
    pins = dict(args['expected_input_sha256'])
    pins[NAMES[index]] = '0'*64
    with pytest.raises(ValueError, match='EXACT_FIVE_INPUT_SHA256_BINDING'):
        apply(buffers, dict(args, expected_input_sha256=pins))


@pytest.mark.parametrize('index', range(5))
def test_mutable_or_nonbytes_input_is_refused(index):
    buffers, args = fixture()
    with pytest.raises(ValueError, match='IMMUTABLE_FIVE_SOURCE_BUFFERS'):
        apply(replace_bytes(buffers, index, bytearray(buffers[index])), args)


def test_header_hash_extent_and_unsigned_256_profile_are_bound():
    buffers, args = fixture()
    with pytest.raises(ValueError, match='EXACT_SOURCE_HEADER_SHA256_BINDING'):
        apply(buffers, dict(args, expected_header_sha256='f'*64))
    for extent in (True, 239, (1 << 30)+1):
        with pytest.raises(ValueError):
            apply(buffers, dict(args, source_file_bytes=extent))
    header = bytearray(args['source_header'])
    struct.pack_into('<Q', header, 104, 256+(1 << 32))
    header = bytes(header)
    with pytest.raises(ValueError, match='UNSIGNED64_AT_104'):
        apply(buffers, dict(args, source_header=header, expected_header_sha256=sha(header)))


@pytest.mark.parametrize('tile', [(30, 0, 0), (-1, 0, 0), (20, 1 << 20, 0),
                                  (20, 0, -1), (20, True, 1), (20, 1), '20/1/1'])
def test_source_domain_is_explicit_and_bounded(tile):
    buffers, args = fixture()
    with pytest.raises(ValueError):
        apply(buffers, dict(args, source_tile=tile))


def test_any_intersecting_or_enclosing_whole_bbox_refuses_the_entire_owner():
    z, x, y = TILE
    center = ((2*x+1)*Q//(2 << z), (2*y+1)*Q//(2 << z))
    for points in (ring(*center),
                   [(center[0]-10000, center[1]-10000), (center[0]+10000, center[1]-10000),
                    (center[0]+10000, center[1]+10000), (center[0]-10000, center[1]+10000),
                    (center[0]-10000, center[1]-10000)]):
        outside = ring(x*Q//(1 << z)-2_000_000, y*Q//(1 << z))
        buffers, args = fixture(rings=[outside, points])
        with pytest.raises(ValueError, match='EVERY_CANONICAL_RING_STRICTLY_OUTSIDE'):
            apply(buffers, args)


def test_root_boundary_is_compared_rationally_without_float_rounding():
    z, x, y = TILE
    den = 256*(1 << z)
    left_numerator = (256*x-128)*Q
    floor_left = left_numerator//den
    cy = (2*y+1)*Q//(2 << z)
    # This odd ROOT produces a fractional edge: floor is strictly outside,
    # floor+1 is inside. No approximate float boundary comparison is used.
    assert floor_left*den < left_numerator < (floor_left+1)*den
    points = [(floor_left-20, cy), (floor_left, cy), (floor_left, cy+20),
              (floor_left-20, cy+20), (floor_left-20, cy)]
    buffers, args = fixture(rings=[points])
    assert apply(buffers, args)['receipt']['rootBBoxCertificate']['partCount'] == 1
    points = [(floor_left-20, cy), (floor_left+1, cy), (floor_left+1, cy+20),
              (floor_left-20, cy+20), (floor_left-20, cy)]
    buffers, args = fixture(rings=[points])
    with pytest.raises(ValueError, match='EVERY_CANONICAL_RING_STRICTLY_OUTSIDE'):
        apply(buffers, args)


@pytest.mark.parametrize('usage', [0, 8, 20, 25, 255])
def test_only_actual_usage24_exterior_rings_are_eligible(usage):
    buffers, args = fixture()
    raw = bytearray(buffers[1])
    struct.pack_into('<I', raw, 4, usage << 24)
    buffers = replace_bytes(buffers, 1, bytes(raw))
    with pytest.raises(ValueError, match='ONLY_USAGE24_EXTERIOR_WHOLE_RINGS'):
        apply(buffers, repin(buffers, args))


def test_root_open_short_and_incomplete_elements_are_refused():
    base, args = fixture()
    mutations = []
    raw = bytearray(base[2]); struct.pack_into('<I', raw, 0, 5); mutations.append(bytes(raw))
    raw = bytearray(base[2]); struct.pack_into('<II', raw, 4+4*8, 1, 2); mutations.append(bytes(raw))
    raw = bytearray(base[2]); struct.pack_into('<I', raw, 0, (1 << 24)|3); mutations.append(bytes(raw))
    raw = bytearray(base[2]); struct.pack_into('<I', raw, 0, (1 << 24)|0xffffff); mutations.append(bytes(raw))
    mutations.append(base[2][:-1])
    for coordinates in mutations:
        buffers = replace_bytes(base, 2, coordinates)
        with pytest.raises(ValueError):
            apply(buffers, repin(buffers, args))


def test_empty_incomplete_and_out_of_range_indices_are_refused():
    base, args = fixture()
    for indices in (b'', struct.pack('<I', 0), base[1][:-1],
                    struct.pack('<II', 1, (24 << 24)|0xfffffe)):
        buffers = replace_bytes(base, 1, indices)
        with pytest.raises(ValueError):
            apply(buffers, repin(buffers, args))


def test_owner_must_be_unique_whole_kind3_record():
    base, args = fixture()
    with pytest.raises(ValueError, match='EXACTLY_ONE_SELECTED_OWNER'):
        apply(base, dict(args, owner_uid=UID+999))
    for features in (base[0]+base[0][:32], base[0][:-1]):
        buffers = replace_bytes(base, 0, features)
        with pytest.raises(ValueError):
            apply(buffers, repin(buffers, args))
    raw = bytearray(base[0]); raw[28] = 2
    buffers = replace_bytes(base, 0, bytes(raw))
    with pytest.raises(ValueError, match='KIND3_AREA_REQUIRED'):
        apply(buffers, repin(buffers, args))
    for uid in (True, -1, 1 << 64):
        with pytest.raises(ValueError, match='OWNER_UID_U64'):
            apply(base, dict(args, owner_uid=uid))


@pytest.mark.parametrize('bad', [b'', b'AC(OWN);SY(OTHER)', b'AC(OWN,128)',
                                  b'AC(OWN);;', b'LS(OWN)', b'AC(OWN)\x00'])
def test_both_draw_contexts_must_already_be_nonempty_pure_ac(bad):
    buffers, args = fixture(contexts={'196608': bad, '196609': b'AC(OWN)'})
    with pytest.raises(ValueError, match='BOTH_NONEMPTY_PURE_AC_CONTEXTS_REQUIRED'):
        apply(buffers, args)


def test_missing_duplicate_render_context_or_broken_query_data_refuses():
    buffers, args = fixture(contexts={'196608': b'AC(OWN)'})
    with pytest.raises(ValueError, match='BOTH_NONEMPTY_PURE_AC_CONTEXTS_REQUIRED'):
        apply(buffers, args)
    buffers, args = fixture()
    payload = b'AC(OWN)'
    duplicate = struct.pack('<HI', 3, 0)+b''.join(struct.pack('<IH', k, len(payload))+payload
                                                  for k in (196608, 196609, 196608))
    buffers = replace_bytes(buffers, 3, duplicate)
    with pytest.raises(ValueError, match='DUPLICATE_RENDER_KEY'):
        apply(buffers, repin(buffers, args))
    buffers, args = fixture()
    buffers = replace_bytes(buffers, 4, b'bad')
    with pytest.raises(ValueError):
        apply(buffers, repin(buffers, args))


def test_invented_mixed_qround_ring_still_fails_original_integer_guard():
    # Entirely OWN coordinates model a signed-qRound discontinuity at L29.
    # The correction deliberately does not reclassify this as native parity.
    z, x, y = TILE
    left = x*Q//(1 << z)-2_000_000
    transition = ((y*(1 << (29-z))*256-(1 << 31))*Q)//(1 << 37)
    points = [(left, transition-50_000), (left+1_000, transition-50_000),
              (left+2_000, transition+50_000), (left+1_000, transition+100_000),
              (left, transition+50_000), (left, transition-50_000)]
    buffers, args = fixture(rings=[points])
    with pytest.raises(ValueError, match='LEVEL_29'):
        certify_exterior_integer_regions(buffers[0][:32], buffers[1], buffers[2], TILE)
    result = apply(buffers, args)
    assert result['coordinateBytes'] is buffers[2]
    assert result['indexBytes'] is buffers[1]
    with pytest.raises(ValueError, match='LEVEL_29'):
        certify_exterior_integer_regions(result['featureBytes'][:32], result['indexBytes'],
                                         result['coordinateBytes'], TILE)
    assert result['receipt']['nativePixelParityClaim'] is False


def test_last_level_and_root_level_are_mathematical_domains_only():
    for tile, points in (((29, 1000, 1000), ring(1_000_000, 1_000_000)),
                         ((0, 0, 0), ring(4_000_000_000, 1_000_000))):
        buffers, args = fixture(tile=tile, rings=[points])
        result = apply(buffers, args)
        receipt = result['receipt']
        assert receipt['mathematicalRootExclusionDestinationDomain']['maximumLevel'] == 29
        assert receipt['actualNativeAdmittedDestinationDomainEstablished'] is False
        assert receipt['thisHelperDoesNotWaiveRestoreOrAdmissionRequirements'] is True
        assert result['coordinateBytes'] is buffers[2]
