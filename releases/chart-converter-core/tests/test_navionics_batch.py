"""Own batch planning/sink/CLI controls; no Original or native calls."""
from dataclasses import FrozenInstanceError
import hashlib
import io
import json
import struct

import pytest

from no_chart_converter.cli import main
from no_chart_converter.navionics.batch import (
    Area24, BatchSpec, Line32, Point40, batch_from_spec, plan_payload_width,
    plan_plain_batch, plan_sha256, write_plain_batch,
)
from no_chart_converter.navionics.point import build_absolute_point_fixture
from no_chart_converter.navionics.writer import RawMapHeader


def header():
    return RawMapHeader(0x1234, (100000, 100000, 200000, 200000), (4, 4), (8, 20), 0)


def mixed():
    return BatchSpec(header(), (
        Point40('point', 126, (125000, 175000)),
        Line32('line', 29, (((0, 0), (1, 2), (3, 1)), ((1, 1), (2, 2)))),
        Area24('area', 74, (((0, 0), (3, 0), (1, 2), (0, 0)),)),
    ))


def emit(spec, sink=None):
    if sink is None:
        sink = io.BytesIO()
    plan = plan_plain_batch(spec)
    return sink, write_plain_batch(spec, sink, expected_plan_sha256=plan_sha256(plan))


@pytest.mark.parametrize('base,values,width,size', [
    (10, (0,), 1, 11), (254, (0,), 1, 255), (255, (0,), 2, 257),
    (65533, (0,), 2, 65535), (65534, (0,), 4, 65538),
    (250, (0, 60), 1, 252), (254, (0, 61), 2, 258),
    (65530, (0, 16380), 2, 65534), (65534, (0, 16381), 4, 65542),
    (65532, (2, 16380), 4, 65540),  # rebuilding W2 crosses 65535 again
    (0, (256,), 2, 2), (0, (65536,), 4, 4),
])
def test_joint_width_fixed_point_without_large_arrays(base, values, width, size):
    assert plan_payload_width(base, sized_fields=values) == {'lengthWidth': width, 'payloadBytes': size}


def test_point_only_general_interface_preserves_existing_plain_wire():
    spec = BatchSpec(header(), (Point40('user-id', 126, (125000, 175000)),))
    sink, report = emit(spec)
    assert sink.getvalue() == build_absolute_point_fixture(prefix='type16-plain')
    assert report['bytesWritten'] == 370
    assert report['emittedStreamSHA256'] == hashlib.sha256(sink.getvalue()).hexdigest()
    assert report['persistedFileReadback'] is report['nativeReaderExecuted'] is False


def test_mixed_offsets_parts_and_exact_metadata_extent():
    sink, result = emit(mixed())
    raw, plan = sink.getvalue(), result['plan']
    assert struct.unpack_from('<I', raw, 11)[0] == len(raw) == plan['totalBytes']
    assert struct.unpack_from('<H', raw, 144)[0] == 0
    assert [row['kind'] for row in plan['features']] == [40, 32, 24]
    assert struct.unpack_from('<I', raw, 261)[0] == plan['headerOffset']
    assert struct.unpack_from('<I', raw, plan['headerOffset'] + 51)[0] == plan['layerOffset']
    assert struct.unpack_from('<I', raw, plan['layerOffset'] + 6)[0] == plan['firstFeatureOffset']
    assert raw[plan['layerEndOffset']:plan['layerEndOffset'] + 2] == b'\0\0'
    assert raw[plan['featureEndOffset']:plan['featureEndOffset'] + 2] == b'\0\x80'
    for row in plan['features']:
        at = row['recordOffset']
        assert struct.unpack_from('<H', raw, at)[0] & 63 == row['kind']
        width = row['lengthWidth']
        assert int.from_bytes(raw[at + 2:at + 2 + width], 'little') == row['payloadBytes']
        if row['parts']:
            pointers = at + 2 + width + 10 + width * 2
            for i, part in enumerate(row['parts']):
                assert struct.unpack_from('<I', raw, pointers + 4 * i)[0] == part['streamOffset']
                assert raw[part['streamOffset'] + part['streamBytes'] - 1] == 0


def test_bank_and_feature_width_rebases_are_computed():
    part = ((0, 0), (1, 1))
    a = plan_plain_batch(BatchSpec(header(), (Line32('many', 29, (part,) * 60),)))
    b = plan_plain_batch(BatchSpec(header(), (Line32('many', 29, (part,) * 61),)))
    assert a['bankLengthWidth'] == b['bankLengthWidth'] == 2
    assert a['features'][0]['lengthWidth'] == 1
    assert b['features'][0]['lengthWidth'] == 2
    assert b['features'][0]['payloadBytes'] == 258
    assert b['features'][0]['recordOffset'] - a['features'][0]['recordOffset'] == 10
    for p in (a, b):
        assert p['features'][0]['parts'][0]['streamOffset'] == p['bankOffset'] + 4 + 24


def test_same_bbox_and_count_still_bind_every_raw_coordinate():
    a = BatchSpec(header(), (Line32('same', 29, (((0, 0), (1, 1), (3, 3)),)),))
    b = BatchSpec(header(), (Line32('same', 29, (((0, 0), (2, 1), (3, 3)),)),))
    pa, pb = plan_plain_batch(a), plan_plain_batch(b)
    assert pa['features'][0]['bboxU16'] == pb['features'][0]['bboxU16']
    assert plan_sha256(pa) != plan_sha256(pb)
    sink = io.BytesIO()
    with pytest.raises(ValueError, match='PLAN_SHA256'):
        write_plain_batch(b, sink, expected_plan_sha256=plan_sha256(pa))
    assert sink.getvalue() == b''


def test_mutable_input_is_snapshotted_and_ids_do_not_become_native_uids():
    points = [[0, 0], [1, 1]]
    line = Line32('caller-label', 29, [points])
    spec = BatchSpec(header(), [line])
    points[1][0] = 3
    assert line.parts[0][1] == (1, 1)
    with pytest.raises(FrozenInstanceError):
        line.source_id = 'changed'
    assert plan_plain_batch(spec)['sourceIds'] == 'CALLER_LEDGER_LABELS_NOT_NATIVE_UIDS'
    with pytest.raises(ValueError, match='unique'):
        BatchSpec(header(), (line, line))


class ShortSink:
    def __init__(self):
        self.data = bytearray()
        self.max_chunk = 0

    def write(self, raw):
        self.max_chunk = max(self.max_chunk, len(raw))
        count = min(len(raw), 3)
        self.data.extend(raw[:count])
        return count


def test_sequential_write_only_sink_retries_short_writes():
    normal, _ = emit(mixed())
    sink = ShortSink()
    _, result = emit(mixed(), sink)
    assert bytes(sink.data) == normal.getvalue()
    assert result['bytesWritten'] == len(sink.data) and sink.max_chunk <= 258


@pytest.mark.parametrize('bad', [None, False, True, 0, -1, 100000, '1'])
def test_invalid_sink_progress_never_returns_success(bad):
    class Sink:
        def write(self, raw):
            return bad
    with pytest.raises(OSError, match='PROGRESS'):
        emit(mixed(), Sink())


def test_sink_exception_propagates_with_partial_output():
    class Broken(ShortSink):
        def write(self, raw):
            if self.data:
                raise OSError('own sink failure')
            return super().write(raw)
    sink = Broken()
    with pytest.raises(OSError, match='own sink failure'):
        emit(mixed(), sink)
    assert len(sink.data) == 3


def test_unqualified_multiblock_refuses_before_any_write():
    spec = BatchSpec(header(), tuple(Point40(str(i), 126, (125000, 175000)) for i in range(400)))
    sink = ShortSink()
    with pytest.raises(ValueError, match='UNQUALIFIED_PHYSICAL_PACKING'):
        write_plain_batch(spec, sink, expected_plan_sha256='0' * 64)
    assert sink.data == b''


@pytest.mark.parametrize('factory', [
    lambda: Point40('', 126, (1, 2)), lambda: Point40('p', True, (1, 2)),
    lambda: Point40('p', 126, (False, 2)), lambda: Point40('p', 126, (1 << 31, 2)),
    lambda: Line32('l', 29, ()), lambda: Line32('l', 29, (((0, 0),),)),
    lambda: Line32('l', 29, (((0, 0), (65536, 0)),)),
    lambda: Area24('a', 74, (((0, 0), (1, 0), (1, 1), (0, 1)),)),
    lambda: Area24('a', 74, (((0, 0), (1, 0), (1, 0), (0, 0)),)),
    lambda: BatchSpec(header(), (Point40('p', 126, (0, 0)),)),
    lambda: BatchSpec(header(), (Line32('l', 29, (((0, 0), (4, 0)),)),)),
])
def test_typed_input_refusals(factory):
    with pytest.raises(ValueError):
        factory()


def json_spec():
    return {'format': 'no5-own-plain-feature-batch/1',
        'header': {'flags': 0x1234, 'raw_bounds': [100000, 100000, 200000, 200000],
                   'raw_grid': [4, 4], 'raw_levels': [8, 20], 'trailing_value': 0},
        'features': [{'kind': 40, 'source_id': 'my-point', 'class_code': 126, 'xy': [125000, 175000]}],
        'layer_id': 60, 'layer_range': [0, 100000], 'date_text_1': '20261017', 'date_text_2': '20261017'}


def test_json_scope_refuses_semantic_attributes_unknown_options_and_bool_kind():
    for key, value in [('attributes', []), ('flags', 0), ('hole', True)]:
        spec = json_spec(); spec['features'][0][key] = value
        with pytest.raises(ValueError, match='unsupported'):
            batch_from_spec(spec)
    spec = json_spec(); spec['features'][0]['kind'] = True
    with pytest.raises(ValueError):
        batch_from_spec(spec)


def test_cli_plan_then_persisted_stream_and_no_clobber(tmp_path, capsys):
    path, output = tmp_path / 'spec.json', tmp_path / 'output.bin'
    raw = json.dumps(json_spec()).encode(); path.write_bytes(raw)
    args = ['--spec', str(path), '--expect-spec-sha256', hashlib.sha256(raw).hexdigest()]
    assert main(['plan-navionics-batch', *args]) == 0
    plan = json.loads(capsys.readouterr().out)
    write = ['write-navionics-batch', *args, '--expect-plan-sha256', plan['planSHA256'], '--output', str(output)]
    assert main(write) == 0
    result = json.loads(capsys.readouterr().out)
    assert output.read_bytes() == build_absolute_point_fixture(prefix='type16-plain')
    assert result['persistedFileReadback'] and result['nativeReaderExecuted'] is False
    assert main(write) == 2


def test_cli_wrong_bindings_and_symlink_refuse_before_output(tmp_path, capsys):
    path, output = tmp_path / 'spec.json', tmp_path / 'output.bin'
    raw = json.dumps(json_spec()).encode(); path.write_bytes(raw)
    args = ['write-navionics-batch', '--spec', str(path), '--expect-spec-sha256',
            hashlib.sha256(raw).hexdigest(), '--expect-plan-sha256', '0' * 64, '--output', str(output)]
    assert main(args) == 2 and not output.exists()
    capsys.readouterr()
    path.unlink(); target = tmp_path / 'real.json'; target.write_bytes(raw); path.symlink_to(target)
    assert main(args) == 2 and not output.exists()
