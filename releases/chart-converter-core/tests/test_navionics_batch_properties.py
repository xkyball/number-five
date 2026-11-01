"""Independent invented-byte QA. No OEM/reader/runtime fixture is used or run.

The oracle uses only stdlib arithmetic/struct, never product record/planning
helpers. Source labels are ledger labels, not native IDs. Positive pure-byte
results do not establish native multi-feature/part acceptance or portrayal.
"""
import copy
import hashlib
import io
import json
import random
import struct

import pytest
from no_chart_converter.navionics.batch import (
    Area24, BatchSpec, Line32, Point40, batch_from_spec,
    plan_payload_width, plan_plain_batch, plan_sha256, write_plain_batch,
)
from no_chart_converter.navionics.writer import RawMapHeader


def width_oracle(base, fields=()):
    # Monotone fixedpoint in byte capacity, independently from product's trials.
    width = 1
    while True:
        extent = base + len(fields) * width
        most = max((extent, *fields))
        required = max(1, (most.bit_length() + 7) // 8)
        capacity = 1 if required <= 1 else 2 if required <= 2 else 4
        if required > 4:
            raise ValueError('own oracle U32 overflow')
        if width == capacity:
            return width, extent
        width = capacity


def env(kind, body, width=None, active=True):
    width = width or width_oracle(len(body))[0]
    assert len(body) < 1 << (8 * width)
    return (struct.pack('<H', kind | {1: 0, 2: 64, 4: 128}[width] |
                        (32768 if active else 0))
            + len(body).to_bytes(width, 'little') + body)


def decode(raw, at):
    assert 0 <= at <= len(raw) - 3
    tag = int.from_bytes(raw[at:at+2], 'little')
    width = (1, 2, 4, 4)[(tag >> 6) & 3]
    start = at + 2 + width
    assert start <= len(raw)
    size = int.from_bytes(raw[at+2:start], 'little')
    assert start + size <= len(raw)
    return {'at': at, 'tag': tag, 'kind': tag & 63, 'width': width,
            'size': size, 'bodyAt': start, 'end': start + size,
            'body': raw[start:start+size]}


def kind(f):
    return {'Point40': 40, 'Line32': 32, 'Area24': 24}[type(f).__name__]


def oracle(spec):
    # Pass1: own source-point streams and feature shapes, then real positions.
    descriptions, bank_data = [], bytearray()
    for f in spec.features:
        k = kind(f)
        parts = () if k == 40 else f.parts
        offsets = []
        for p in parts:
            stream = struct.pack('<2H', *p[0])
            stream += b''.join(b'\x88' + struct.pack('<2H', *v) for v in p[1:]) + b'\0'
            offsets.append((len(bank_data), stream))
            bank_data.extend(stream)
        w, size = width_oracle(10 if k == 40 else 10 + 4 * len(parts) + (k == 24),
                               (0,) if k == 40 else (0, len(parts)))
        descriptions.append({'feature': f, 'kind': k, 'width': w, 'size': size, 'parts': offsets})
    has_bank = bool(bank_data)
    bank_body = struct.pack('<2I4i', *spec.header.raw_grid, *spec.header.raw_bounds) + bank_data
    bw = width_oracle(len(bank_body))[0] if has_bank else None
    bank_at = 320 if has_bank else None
    bank_first = bank_at + 2 + bw + 24 if has_bank else None
    bank = env(52, bytes(bank_body), bw) if has_bank else b''
    layer_at = 320 + len(bank)
    cursor = layer_at + 29
    feature_bytes = []
    for d in descriptions:
        f, k, w = d['feature'], d['kind'], d['width']
        if k == 40:
            body = bytes((f.class_code, 0)) + bytes(w) + struct.pack('<2i', *f.xy)
            bbox = None
        else:
            flat = [xy for p in f.parts for xy in p]
            bbox = (min(p[0] for p in flat), min(p[1] for p in flat),
                    max(p[0] for p in flat), max(p[1] for p in flat))
            body = struct.pack('<4H', *bbox) + bytes((f.class_code, 0)) + bytes(w)
            body += len(f.parts).to_bytes(w, 'little')
            body += b''.join(struct.pack('<I', bank_first + rel) for rel, _ in d['parts'])
            if k == 24:
                body += b'\0'
        assert len(body) == d['size']
        encoded = env(k, body, w)
        d.update(recordAt=cursor, body=body, bbox=bbox)
        cursor += len(encoded)
        feature_bytes.append(encoded)
    total = cursor + 7
    meta = bytearray(258)
    meta[:4] = bytes.fromhex('5080fe00')
    struct.pack_into('<I', meta, 4, 0x081273ab)
    struct.pack_into('<HI', meta, 9, 9, total)
    meta[29:37] = spec.date_text_1.encode('ascii')
    meta[37:45] = spec.date_text_2.encode('ascii')
    struct.pack_into('<4i', meta, 45, *spec.header.raw_bounds)
    header = struct.pack('<H2x4i4x2I4x2I4xI', spec.header.flags,
                         *spec.header.raw_bounds, *spec.header.raw_grid,
                         *spec.header.raw_levels, layer_at)
    layer = struct.pack('<BHIIii', spec.layer_id, 0, layer_at + 29, 0, *spec.layer_range)
    raw = (bytes(meta) + env(4, struct.pack('<I', 265)) + env(17, header) + bank +
           env(18, layer) + env(0, b'\xff' * 4, active=False) + b''.join(feature_bytes) +
           env(0, b'\xff' * 4))
    assert len(raw) == total
    return raw, {'features': descriptions, 'bankAt': bank_at, 'bankFirst': bank_first,
                 'bankWidth': bw, 'bankPayload': len(bank_body) if has_bank else 0,
                 'layerAt': layer_at, 'featureEndAt': cursor}


def inspect(raw, spec):
    expected, layout = oracle(spec)
    assert raw == expected
    meta = decode(raw, 0)
    assert (meta['tag'], meta['width'], meta['size'], meta['end']) == (0x8050, 2, 254, 258)
    assert struct.unpack_from('<I', raw, 11)[0] == len(raw)
    assert raw[144:146] == b'\0\0'
    directory = decode(raw, 258)
    assert directory['tag'] == 0x8004 and struct.unpack('<I', directory['body']) == (265,)
    header = decode(raw, 265)
    assert struct.unpack_from('<I', header['body'], 48)[0] == layout['layerAt']
    if layout['bankAt'] is not None:
        bank = decode(raw, layout['bankAt'])
        assert (bank['kind'], bank['width'], bank['size'], bank['end']) == (
            52, layout['bankWidth'], layout['bankPayload'], layout['layerAt'])
    layer = decode(raw, layout['layerAt'])
    assert struct.unpack('<BHIIii', layer['body']) == (
        spec.layer_id, 0, layout['layerAt'] + 29, 0, *spec.layer_range)
    assert decode(raw, layer['end'])['tag'] == 0
    for d in layout['features']:
        f, k, w = d['feature'], d['kind'], d['width']
        record = decode(raw, d['recordAt'])
        assert record['kind'] == k and record['width'] == w and record['body'] == d['body']
        at = 2 if k == 40 else 10
        assert record['body'][at:at+w] == bytes(w)
        if k != 40:
            at += w
            assert int.from_bytes(record['body'][at:at+w], 'little') == len(f.parts)
            at += w
            pointers = struct.unpack_from('<' + 'I' * len(f.parts), record['body'], at)
            for p, (relative, stream) in zip(pointers, d['parts']):
                assert p < 1 << 30 and p == layout['bankFirst'] + relative
                assert raw[p:p+len(stream)] == stream
    end = decode(raw, layout['featureEndAt'])
    assert end['tag'] == 0x8000 and end['body'] == b'\xff' * 4 and end['end'] == len(raw)
    return layout


def spec(features, *, grid=(4, 4), bounds=(100000, 100000, 200000, 200000), **kw):
    return BatchSpec(RawMapHeader(0x1234, bounds, grid, (8, 20), 0), features, **kw)


def emit(s):
    plan = plan_plain_batch(s)
    out = io.BytesIO()
    report = write_plain_batch(s, out, expected_plan_sha256=plan_sha256(plan))
    raw = out.getvalue()
    layout = inspect(raw, s)
    assert report['bytesWritten'] == plan['totalBytes'] == len(raw)
    assert report['emittedStreamSHA256'] == hashlib.sha256(raw).hexdigest()
    assert report['nativeReaderExecuted'] is report['persistedFileReadback'] is False
    assert plan['nativeAcceptance'] == 'UNTESTED_GENERALIZATION'
    assert plan['sourceIds'] == 'CALLER_LEDGER_LABELS_NOT_NATIVE_UIDS'
    assert plan['bankOffset'] == layout['bankAt'] and plan['bankLengthWidth'] == layout['bankWidth']
    assert plan['layerOffset'] == layout['layerAt'] and plan['featureEndOffset'] == layout['featureEndAt']
    assert [r['sourceId'] for r in plan['features']] == [f.source_id for f in s.features]
    for row, d in zip(plan['features'], layout['features']):
        assert (row['recordOffset'], row['lengthWidth'], row['payloadBytes']) == (d['recordAt'], d['width'], d['size'])
        assert row['recordBytes'] == 2 + d['width'] + d['size']
        for r, (relative, stream), points in zip(row['parts'], d['parts'], getattr(d['feature'], 'parts', ())):
            assert r['streamOffset'] == layout['bankFirst'] + relative and r['streamBytes'] == len(stream)
            assert r['rawU16PointSHA256'] == hashlib.sha256(b''.join(struct.pack('<HH', *p) for p in points)).hexdigest()
    return raw, plan, report


TRIANGLE = ((0, 0), (3, 0), (1, 2), (0, 0))
LINE = ((0, 0), (1, 2), (3, 1))


def test_point_only_is_exact_historical370_whollyown_regression():
    raw, plan, _ = emit(spec((Point40('ledger-only', 126, (125000, 175000)),)))
    assert len(raw) == 370 and hashlib.sha256(raw).hexdigest() == 'e4a9c11fad40af1adeab9c09fbb9634fb46661552d4643fa3acb1326493218b9'
    assert plan['bankOffset'] is None and plan['bankPayloadBytes'] == 0


def test_mixed_features_multipart_duplicate_occurrences_order_and_metadata():
    s = spec((Point40('p0', 0, (100000, 100000)),
              Line32('l0', 29, (LINE, LINE[::-1], LINE)),
              Area24('a0', 74, (TRIANGLE, TRIANGLE[::-1])),
              Point40('p1', 255, (200000, 200000)),
              Line32('l1', 42, (((2, 1), (0, 3)),))),
             layer_id=255, layer_range=(-123, 456), date_text_1='20000229', date_text_2='20240229')
    raw, plan, _ = emit(s)
    assert len(plan['features']) == 5
    assert len(plan['features'][1]['parts']) == 3
    assert len({p['streamOffset'] for r in plan['features'] for p in r['parts']}) == 6
    assert raw[29:45] == b'2000022920240229'


@pytest.mark.parametrize('cls,payloads', [(Line32, (252, 258)), (Area24, (253, 259))])
def test_shared_width_changes_two_internal_fields_and_rebases_following_feature(cls, payloads):
    part = ((0, 0), (1, 1)) if cls is Line32 else TRIANGLE
    samples = []
    for count, width, payload in zip((60, 61), (1, 2), payloads):
        raw, plan, _ = emit(spec((cls('many', 74, (part,) * count), Point40('after', 126, (150000, 150000)))))
        row = plan['features'][0]
        assert row['lengthWidth'] == width and row['payloadBytes'] == payload
        assert plan['features'][1]['recordOffset'] == row['recordOffset'] + row['recordBytes']
        body = decode(raw, row['recordOffset'])['body']
        assert body[10:10+width] == bytes(width)
        assert int.from_bytes(body[10+width:10+2*width], 'little') == count
        samples.append((raw, plan))
    assert len(samples[1][0]) - len(samples[0][0]) == 5 * len(part) + 7


@pytest.mark.parametrize('n,bank_width,bank_payload', [(46, 1, 254), (47, 2, 259)])
def test_bank_header_width_rebases_all_feature_and_member_offsets(n, bank_width, bank_payload):
    points = tuple((i % 4, (i // 4) % 4) for i in range(n))
    _, plan, _ = emit(spec((Line32('bank-boundary', 29, (points,)), Point40('after', 126, (150000, 150000)))))
    assert plan['bankLengthWidth'] == bank_width and plan['bankPayloadBytes'] == bank_payload
    assert plan['features'][0]['parts'][0]['streamOffset'] == 320 + 2 + bank_width + 24


@pytest.mark.parametrize('base,fields,expected', [
    (255, (), (1, 255)), (256, (), (2, 256)), (65535, (), (2, 65535)), (65536, (), (4, 65536)),
    (254, (0, 0), (2, 258)), (65532, (0, 0), (4, 65540)),
    (10 + 4 * 16380, (0, 16380), (2, 65534)),
    (10 + 4 * 16381, (0, 16381), (4, 65542)),
    (11 + 4 * 16380, (0, 16380), (2, 65535)),
    (11 + 4 * 16381, (0, 16381), (4, 65543)),
    (0, (256,), (2, 2)), (0, (65536,), (4, 4)), (0, (0xffffffff,), (4, 4)),
])
def test_scalar_width_boundaries_are_not_large_physical_file_claims(base, fields, expected):
    assert width_oracle(base, fields) == expected
    assert plan_payload_width(base, sized_fields=fields) == dict(zip(('lengthWidth', 'payloadBytes'), expected))


@pytest.mark.parametrize('base,fields', [(-1, ()), (True, ()), (1.0, ()), (1 << 32, ()),
    (0xffffffff, (0,)), (1, (-1,)), (1, (True,)), (1, (1 << 32,)), (1, (None,)), (1, iter([0]))])
def test_scalar_width_invalid_values_or_unrepresentable_final_extent_refused(base, fields):
    with pytest.raises(ValueError):
        plan_payload_width(base, sized_fields=fields)


def test_plan_binds_interior_geometry_not_merely_bbox_count_or_source_label():
    a = spec((Line32('same', 29, (((0, 0), (1, 1), (3, 3)),)),))
    b = spec((Line32('same', 29, (((0, 0), (2, 1), (3, 3)),)),))
    pa, pb = plan_plain_batch(a), plan_plain_batch(b)
    assert pa['features'][0]['bboxU16'] == pb['features'][0]['bboxU16']
    assert pa['features'][0]['parts'][0]['pointCount'] == pb['features'][0]['parts'][0]['pointCount']
    assert plan_sha256(pa) != plan_sha256(pb)
    sink = NoSeekSink()
    with pytest.raises(ValueError, match='PLAN_SHA256'):
        write_plain_batch(b, sink, expected_plan_sha256=plan_sha256(pa))
    assert sink.calls == 0 and not sink.data
    assert emit(a)[0] != emit(b)[0]


def test_ledger_source_id_changes_plan_hash_but_not_native_wire_identity():
    a, b = [spec((Point40(label, 126, (150000, 150000)),)) for label in ('A', '\u00c4')]
    ra, pa, _ = emit(a); rb, pb, _ = emit(b)
    assert ra == rb and plan_sha256(pa) != plan_sha256(pb)


class NoSeekSink:
    def __init__(self, short=7):
        self.data = bytearray(); self.calls = 0; self.short = short
    def write(self, raw):
        self.calls += 1
        n = min(self.short, len(raw)); self.data.extend(raw[:n]); return n
    def seek(self, *args): raise AssertionError('unexpected seek')
    def tell(self): raise AssertionError('unexpected tell')
    def flush(self): raise AssertionError('unexpected flush')
    def close(self): raise AssertionError('unexpected close')


@pytest.mark.parametrize('short', [1, 2, 7, 127, 65536])
def test_short_writes_preserve_all_bytes_hash_and_only_write_contract(short):
    s = spec((Line32('line', 29, (LINE,)), Area24('area', 74, (TRIANGLE,))))
    sink = NoSeekSink(short)
    plan = plan_plain_batch(s)
    report = write_plain_batch(s, sink, expected_plan_sha256=plan_sha256(plan))
    assert bytes(sink.data) == oracle(s)[0]
    assert report['emittedStreamSHA256'] == hashlib.sha256(sink.data).hexdigest()
    assert report['bytesWritten'] == len(sink.data)


@pytest.mark.parametrize('bad', [None, 0, True, -1, 99999, 1.5])
def test_invalid_progress_never_produces_success_receipt(bad):
    class Broken(NoSeekSink):
        def write(self, raw): self.calls += 1; return bad
    s = spec((Point40('p', 126, (150000, 150000)),)); sink = Broken()
    with pytest.raises(OSError):
        write_plain_batch(s, sink, expected_plan_sha256=plan_sha256(plan_plain_batch(s)))
    assert sink.calls == 1


def test_midstream_sink_exception_preserves_failure_and_does_not_claim_rollback():
    error = OSError('invented sink failure')
    class Broken(NoSeekSink):
        def write(self, raw):
            if self.calls == 2: raise error
            return super().write(raw)
    s = spec((Point40('p', 126, (150000, 150000)),)); sink = Broken(65536)
    with pytest.raises(OSError) as got:
        write_plain_batch(s, sink, expected_plan_sha256=plan_sha256(plan_plain_batch(s)))
    assert got.value is error and bytes(sink.data) == oracle(s)[0][:265]


def test_exact4096_budget_and_oversize_refusal_before_first_sink_write():
    def make(n):
        return spec((Line32('line', 29, (tuple((i % 4, (i // 4) % 4) for i in range(n)),)),)
                    + tuple(Point40('p' + str(i), 126, (150000, 150000)) for i in range(261)))
    s = make(8); raw, plan, _ = emit(s)
    assert len(raw) == plan['totalBytes'] == 4096
    too_large = make(9); assert len(oracle(too_large)[0]) == 4101
    sink = NoSeekSink()
    with pytest.raises(ValueError, match='UNQUALIFIED_PHYSICAL_PACKING'):
        write_plain_batch(too_large, sink, expected_plan_sha256='0' * 64)
    assert sink.calls == 0 and not sink.data


@pytest.mark.parametrize('invalid', ['', 'x' * 129, None, 1])
def test_invalid_source_ids_refused(invalid):
    with pytest.raises(ValueError): Point40(invalid, 126, (100000, 100000))


@pytest.mark.parametrize('cls,parts', [(Line32, ()), (Line32, (((0, 0),),)),
    (Line32, (((0, 0), (65536, 0)),)), (Line32, (((0, 0), (-1, 0)),)),
    (Area24, (((0, 0), (1, 0), (0, 1)),)),
    (Area24, (((0, 0), (1, 0), (1, 0), (0, 0)),))])
def test_sparse_invalid_or_open_area_inputs_refuse_without_geometry_repair(cls, parts):
    with pytest.raises(ValueError): cls('bad', 74, parts)


def test_batch_refuses_duplicate_labels_nonzero_cursor_and_grid_domain():
    p = Point40('same', 126, (150000, 150000))
    with pytest.raises(ValueError): spec((p, p))
    with pytest.raises(ValueError): BatchSpec(RawMapHeader(0, (100000, 100000, 200000, 200000), (4, 4), (8, 20), 1), (p,))
    with pytest.raises(ValueError): spec((Line32('bad', 29, (((0, 0), (4, 0)),)),))
    # Constructor machine range is broader than this builder's own header domain.
    extreme = Point40('edge', 126, (-(1 << 31), (1 << 31) - 1))
    with pytest.raises(ValueError): spec((extreme,))
    emit(spec((Line32('u16', 29, (((0, 0), (65535, 65535)),)),), grid=(65536, 65536)))


def test_deep_mutable_input_lists_are_copied_to_immutable_tuples():
    xy = [150000, 150000]; part = [[0, 0], [1, 1]]; parts = [part]
    p = Point40('p', 126, xy); line = Line32('l', 29, parts)
    fs = [p, line]; s = spec(fs); before = emit(s)[0]
    xy[0] = 100000; part[0][0] = 3; parts.append([[2, 2], [3, 3]]); fs.reverse()
    assert emit(s)[0] == before


def test_exact_json_parser_refuses_unproved_flags_attributes_and_unsupported_kinds():
    value = {'format':'no5-own-plain-feature-batch/1', 'header':{
        'flags':0x1234,'raw_bounds':[100000,100000,200000,200000], 'raw_grid':[4,4],
        'raw_levels':[8,20], 'trailing_value':0},
        'features':[{'kind':40,'source_id':'p','class_code':126,'xy':[125000,175000]}],
        'layer_id':60,'layer_range':[0,100000],'date_text_1':'20261017','date_text_2':'20261017'}
    assert emit(batch_from_spec(value))[0] == oracle(spec((Point40('p',126,(125000,175000)),)))[0]
    for key in ('flags', 'attributes', 'hole', 'reverse'):
        altered = copy.deepcopy(value); altered['features'][0][key] = 0
        with pytest.raises(ValueError): batch_from_spec(altered)
    for k in (True, 9, 63, 40.0):
        altered = copy.deepcopy(value); altered['features'][0]['kind'] = k
        with pytest.raises(ValueError): batch_from_spec(altered)


def test_independent_inspector_detects_pointer_count_end_and_size_corruption():
    s = spec((Line32('l', 29, (LINE, LINE[::-1])), Point40('p', 126, (150000,150000))))
    raw, plan, _ = emit(s); row = plan['features'][0]; rec = decode(raw, row['recordOffset'])
    positions = [11,144,258+3,265+3+48,plan['layerOffset']+3+3,
                 rec['bodyAt']+10+row['lengthWidth'],
                 rec['bodyAt']+10+2*row['lengthWidth'],
                 row['recordOffset'],row['recordOffset']+2,plan['featureEndOffset']+1]
    for at in positions:
        bad = bytearray(raw); bad[at] ^= 0x80
        with pytest.raises(AssertionError): inspect(bytes(bad),s)
    # A legitimate changed spec must pass its own independent oracle.
    emit(spec((Line32('l', 29, (LINE,)), Point40('p', 126, (175000,150000)))))


def test_deterministic_small_mixed_batches_against_independent_complete_oracle():
    rng = random.Random(370197203)
    for case in range(30):
        features=[]
        for i in range(rng.randrange(1,8)):
            label=f'{case}:{i}'; choice=rng.randrange(3)
            if choice==0:
                features.append(Point40(label,rng.randrange(256),(rng.randrange(100000,200001),rng.randrange(100000,200001))))
            elif choice==1:
                parts=tuple(tuple((rng.randrange(4),rng.randrange(4)) for _ in range(rng.randrange(2,9))) for _ in range(rng.randrange(1,5)))
                features.append(Line32(label,rng.randrange(256),parts))
            else:
                features.append(Area24(label,rng.randrange(256),(TRIANGLE,) * rng.randrange(1,4)))
        emit(spec(tuple(features)))
