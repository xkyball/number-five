"""Own software controls for typed layers/Depth44, not native acceptance."""
import hashlib
import io
from pathlib import Path
import struct

import pytest

from no_chart_converter.navionics.batch import (
    Point40, Line32, Area24, BatchSpec, plan_plain_batch, plan_payload_width, plan_sha256,
)
from no_chart_converter.navionics.layered_batch import (
    DepthPoint44, PlainLayer, LayeredBatchSpec, layered_batch_from_spec,
    plan_layered_batch, write_layered_batch,
)
from no_chart_converter.navionics.writer import RawMapHeader


def mini():
    return LayeredBatchSpec(
        RawMapHeader(0x1234, (2050000, -3992000, 2051000, -3991000), (1024, 1024), (8, 20), 0),
        (PlainLayer(61, (Line32('own:coast', 29, (((30, 533), (86, 602), (143, 533)),)),)),
         PlainLayer(140, (DepthPoint44('own:depth', (2050084, -3991452), 50),))),
    )


def document():
    return {
        'format': 'no5-own-plain-layered-feature-batch/1',
        'header': dict(flags=0x1234, raw_bounds=[2050000, -3992000, 2051000, -3991000],
                       raw_grid=[1024, 1024], raw_levels=[8, 20], trailing_value=0),
        'layers': [
            dict(layer_id=61, layer_range=[0, 100000], features=[dict(kind=32,
                source_id='own:coast', class_code=29, parts=[[[30, 533], [86, 602], [143, 533]]])]),
            dict(layer_id=140, layer_range=[0, 100000], features=[dict(kind=44,
                source_id='own:depth', xy=[2050084, -3991452], mantissa=50, decimal_scale=0)]),
        ], 'date_text_1': '20261017', 'date_text_2': '20261017',
    }


def emitted(spec):
    plan = plan_layered_batch(spec)
    sink = io.BytesIO()
    receipt = write_layered_batch(spec, sink, expected_plan_sha256=plan_sha256(plan))
    assert receipt['bytesWritten'] == len(sink.getvalue()) == plan['totalBytes']
    assert receipt['emittedStreamSHA256'] == hashlib.sha256(sink.getvalue()).hexdigest()
    return plan, sink.getvalue()


def test_two_layers_have_independent_features_and_real_end_records():
    plan, raw = emitted(mini())
    assert len(raw) == 462
    assert [r['recordOffset'] for r in plan['layers']] == [362, 384]
    assert [r['firstFeatureOffset'] for r in plan['layers']] == [413, 439]
    assert [r['featureEndOffset'] for r in plan['layers']] == [432, 455]
    assert struct.unpack_from('<I', raw, 11)[0] == 462
    assert struct.unpack_from('<I', raw, 316)[0] == 362
    for at, layer_id, first in [(362, 61, 413), (384, 140, 439)]:
        assert raw[at:at+3] == b'\x12\x80\x13'
        assert struct.unpack_from('<BHIIii', raw, at + 3) == (layer_id, 0, first, 0, 0, 100000)
    assert raw[406:413] == b'\x00\x00\x04' + b'\xff' * 4
    for at in (432, 455):
        assert raw[at:at+7] == b'\x00\x80\x04' + b'\xff' * 4
    assert raw[439:442] == b'\x2c\x80\x0d'
    assert struct.unpack_from('<BBBHii', raw, 442) == (136, 0, 0, 50, 2050084, -3991452)
    assert plan['features'][1]['sourcePredictedDepthF32LEHex'] == '0000a040'
    assert plan['nativeAcceptance'].startswith('UNTESTED')


@pytest.mark.parametrize('mantissa', [-8192, -1, 0, 50, 8191])
@pytest.mark.parametrize('scale', [0, 1, 2, 3])
def test_exact_signed14_and_all_two_bit_scales(mantissa, scale):
    point = DepthPoint44('word', (2050084, -3991452), mantissa, scale)
    word = point.depth_word
    signed = word & 0x3fff
    if signed & 0x2000:
        signed -= 0x4000
    assert signed == mantissa and word >> 14 == scale
    value = signed / 10 if scale == 0 else signed * {1: 1, 2: 10, 3: 100}[scale]
    assert struct.pack('<f', point.expected_depth_f32) == struct.pack('<f', value)


@pytest.mark.parametrize('mantissa,scale', [(-8193, 0), (8192, 0), (True, 0), (1.0, 0), (0, -1), (0, 4), (0, True)])
def test_depth_codec_domain_refuses(mantissa, scale):
    with pytest.raises(ValueError):
        DepthPoint44('bad', (0, 0), mantissa, scale)


@pytest.mark.parametrize('layer,feature', [
    (60, Line32('line', 29, (((0, 0), (1, 1)),))),
    (61, Point40('point', 1, (0, 0))),
    (60, DepthPoint44('depth', (0, 0), 50)),
    (141, DepthPoint44('depth', (0, 0), 50)),
])
def test_typed_layer_and_depth_unit_route_refusals(layer, feature):
    with pytest.raises(ValueError):
        PlainLayer(layer, [feature])


def test_mixed_parts_layers_and_points_keep_order():
    header = RawMapHeader(1, (0, 0, 100, 100), (4, 4), (8, 20), 0)
    spec = LayeredBatchSpec(header, [
        PlainLayer(61, [Line32('a', 29, (((0, 0), (1, 2)), ((1, 2), (3, 3)))),
                        Line32('b', 29, (((2, 2), (1, 1)),))]),
        PlainLayer(62, [Area24('area', 74, (((0, 0), (3, 0), (1, 2), (0, 0)),))]),
        PlainLayer(140, [DepthPoint44('depth', (20, 30), -12), Point40('p', 136, (40, 50))]),
    ])
    plan, raw = emitted(spec)
    assert [r['sourceId'] for r in plan['features']] == ['a', 'b', 'area', 'depth', 'p']
    assert [r['layerOrdinal'] for r in plan['features']] == [0, 0, 1, 2, 2]
    for layer in plan['layers']:
        assert struct.unpack_from('<I', raw, layer['recordOffset'] + 6)[0] == layer['firstFeatureOffset']
        assert raw[layer['featureEndOffset']:layer['featureEndOffset']+3] == b'\x00\x80\x04'


@pytest.mark.parametrize('parts,width,payload', [(60, 1, 252), (61, 2, 258)])
def test_part_count_and_body_width_rebuild_together(parts, width, payload):
    header = RawMapHeader(1, (0, 0, 100, 100), (4, 4), (8, 20), 0)
    spec = LayeredBatchSpec(header, [PlainLayer(61, [Line32('line', 29,
        tuple(((0, 0), (1, 1)) for _ in range(parts)))])])
    plan, raw = emitted(spec)
    row = plan['features'][0]
    assert (row['lengthWidth'], row['payloadBytes']) == (width, payload)
    start = row['recordOffset'] + 2 + width
    assert raw[start+10:start+10+width] == bytes(width)
    assert int.from_bytes(raw[start+10+width:start+10+2*width], 'little') == parts


def test_scalar_one_to_two_to_four_fixedpoint_is_unchanged():
    assert plan_payload_width(10 + 2 + 4 * 16380, sized_fields=(2, 16380)) == {
        'lengthWidth': 4, 'payloadBytes': 65540,
    }


def test_same_bbox_different_interior_binds_geometry():
    first = mini()
    changed = LayeredBatchSpec(first.header, [
        PlainLayer(61, [Line32('own:coast', 29, (((30, 533), (87, 602), (143, 533)),))]),
        first.layers[1],
    ])
    a, b = plan_layered_batch(first), plan_layered_batch(changed)
    assert a['features'][0]['bboxU16'] == b['features'][0]['bboxU16']
    assert a['totalBytes'] == b['totalBytes']
    assert plan_sha256(a) != plan_sha256(b)


def test_duplicate_layers_or_global_source_ids_refuse():
    spec = mini()
    with pytest.raises(ValueError, match='unique layer'):
        LayeredBatchSpec(spec.header, [spec.layers[0], spec.layers[0]])
    with pytest.raises(ValueError, match='source_id'):
        LayeredBatchSpec(spec.header, [spec.layers[0],
            PlainLayer(140, [DepthPoint44('own:coast', (2050084, -3991452), 50)])])


def test_stale_plan_or_budget_refuses_before_sink_write():
    class Unused:
        def write(self, raw):
            raise AssertionError('sink touched before refusal')
    spec = mini()
    digest = plan_sha256(plan_layered_batch(spec))
    with pytest.raises(ValueError, match='PLAN_SHA256'):
        write_layered_batch(spec, Unused(), expected_plan_sha256='0' * 64)
    with pytest.raises(ValueError, match='BUDGET'):
        write_layered_batch(spec, Unused(), expected_plan_sha256=digest, max_output_bytes=461)
    with pytest.raises(ValueError, match='PLAN_SHA256'):
        write_layered_batch(spec, Unused(), expected_plan_sha256=digest, max_output_bytes=8192)


def test_positive_short_writes_are_retried():
    class Short:
        def __init__(self):
            self.raw = bytearray()
        def write(self, raw):
            count = min(3, len(raw)); self.raw.extend(raw[:count]); return count
    spec = mini(); sink = Short()
    write_layered_batch(spec, sink, expected_plan_sha256=plan_sha256(plan_layered_batch(spec)))
    assert bytes(sink.raw) == emitted(spec)[1]


@pytest.mark.parametrize('value', [None, 0, -1, True, 10000])
def test_invalid_sink_progress_has_no_success(value):
    class Bad:
        def write(self, raw):
            return value
    spec = mini()
    with pytest.raises(OSError):
        write_layered_batch(spec, Bad(), expected_plan_sha256=plan_sha256(plan_layered_batch(spec)))


def test_exact_additive_schema_roundtrip():
    assert layered_batch_from_spec(document()) == mini()


@pytest.mark.parametrize('field', ['attributes', 'flags', 'class_code', 'depth_m', 'unit'])
def test_arbitrary_depth_fields_cannot_escape_typed_word_contract(field):
    doc = document(); doc['layers'][1]['features'][0][field] = 1
    with pytest.raises(ValueError):
        layered_batch_from_spec(doc)


def test_legacy_plan_digest_and_reviewed_batch_source_checkpoint():
    header = RawMapHeader(0x1234, (100000, 100000, 200000, 200000), (4, 4), (8, 20), 0)
    old = BatchSpec(header, [Point40('compat', 126, (125000, 175000))])
    assert plan_sha256(plan_plain_batch(old)) == 'b9fd0574807201920bec28b54413a66b0160724607ec56d06f2c4e634ed44044'
    # The reviewed additive NativeLevelRange API changes source, while absent
    # ranges retain the legacy plan and wire goldens checked by the range tests.
    source = Path(__file__).parents[1] / 'src/no_chart_converter/navionics/batch.py'
    assert hashlib.sha256(source.read_bytes()).hexdigest() == (
        '5487aa030415a640022ea6238f563a633b3da1a9644eb3a22d08b9be3542896e')
