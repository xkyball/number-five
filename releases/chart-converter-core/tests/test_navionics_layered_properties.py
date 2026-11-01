"""Invented, independent wire oracle. No native/OEM/Pi/runtime inputs or calls.

The full expected stream uses stdlib packing and independent positions, not
product encoders/planners. Pure host passes cannot qualify native admission,
depth units/datum, layer styling, scale, holes, rendering or lifecycle.
"""
import copy
from dataclasses import FrozenInstanceError
import hashlib
import io
import json
import random
import struct

import pytest

from no_chart_converter.navionics.batch import Area24, Line32, Point40
from no_chart_converter.navionics.layered_batch import (
    DepthPoint44, PlainLayer, LayeredBatchSpec, layered_batch_from_spec,
    plan_layered_batch, write_layered_batch,
)
from no_chart_converter.navionics.writer import RawMapHeader


def sha(raw):
    return hashlib.sha256(raw).hexdigest()


def plan_sha(plan):
    return sha(json.dumps(plan, sort_keys=True, separators=(',', ':')).encode())


def width(base, fields=()):
    # Independent monotone fixed point including EVERY same-width field.
    w = 1
    while True:
        most = max((base + w * len(fields), *fields))
        required_bytes = max(1, (most.bit_length() + 7) // 8)
        if required_bytes > 4:
            raise ValueError('oracle U32 overflow')
        need = 1 if required_bytes == 1 else 2 if required_bytes == 2 else 4
        if w == need:
            return w, base + w * len(fields)
        w = need


def envelope(kind, payload, w=None, selected=True):
    w = w or width(len(payload))[0]
    tag = kind | (0x8000 if selected else 0) | {1: 0, 2: 0x40, 4: 0x80}[w]
    return struct.pack('<H', tag) + len(payload).to_bytes(w, 'little') + payload


def record(raw, pos):
    assert 0 <= pos <= len(raw) - 3
    tag = int.from_bytes(raw[pos:pos+2], 'little')
    w = (1, 2, 4, 4)[(tag >> 6) & 3]
    begin = pos + 2 + w
    length = int.from_bytes(raw[pos+2:begin], 'little')
    assert begin <= len(raw) and begin + length <= len(raw)
    return dict(at=pos, tag=tag, kind=tag & 63, w=w,
                start=begin, length=length, end=begin+length, body=raw[begin:begin+length])


def feature_kind(f):
    return {Point40: 40, DepthPoint44: 44, Line32: 32, Area24: 24}[type(f)]


def oracle(spec):
    bank_data, features = bytearray(), []
    for lid, layer in enumerate(spec.layers):
        for f in layer.features:
            kind = feature_kind(f)
            parts = () if kind in (40, 44) else f.parts
            spans = []
            for part in parts:
                data = struct.pack('<HH', *part[0])
                data += b''.join(b'\x88' + struct.pack('<HH', *xy) for xy in part[1:]) + b'\0'
                spans.append((len(bank_data), data))
                bank_data.extend(data)
            flat = [xy for part in parts for xy in part]
            box = ((min(x for x, y in flat), min(y for x, y in flat),
                    max(x for x, y in flat), max(y for x, y in flat)) if flat else None)
            fixed = (12 if kind == 44 else 10) if not parts else 10 + 4*len(parts) + (kind == 24)
            w, length = width(fixed, (0,) if not parts else (0, len(parts)))
            features.append(dict(f=f, kind=kind, layerOrdinal=lid, spans=spans, box=box, w=w, length=length))
    bank = b''
    if bank_data:
        bank_body = struct.pack('<2I4i', *spec.header.raw_grid, *spec.header.raw_bounds) + bank_data
        bw = width(len(bank_body))[0]
        bank = envelope(52, bytes(bank_body), bw)
        stream_base = 320 + 2 + bw + 24
    else:
        bw, stream_base = None, None
    first_layer = 320 + len(bank)
    layer_stop = first_layer + 22*len(spec.layers)
    cursor, index, layers, feature_bytes = layer_stop+7, 0, [], []
    for lid, layer in enumerate(spec.layers):
        desc = dict(at=first_layer+22*lid, first=cursor, count=len(layer.features))
        for f in layer.features:
            d = features[index]; k, w = d['kind'], d['w']
            body = b'' if k in (40, 44) else struct.pack('<4H', *d['box'])
            body += bytes((f.class_code, 0)) + bytes(w)
            if k == 44:
                # No property from the product is used as the bit-word oracle.
                unsigned_signed14 = f.mantissa if f.mantissa >= 0 else 16384 + f.mantissa
                depth_word = f.decimal_scale*16384 + unsigned_signed14
                body += struct.pack('<Hii', depth_word, *f.xy)
            elif k == 40:
                body += struct.pack('<ii', *f.xy)
            else:
                body += len(f.parts).to_bytes(w, 'little')
                body += b''.join(struct.pack('<I', stream_base+rel) for rel, _ in d['spans'])
                if k == 24:
                    body += b'\0'
            assert len(body) == d['length']
            data = envelope(k, body, w)
            d.update(at=cursor, body=body)
            feature_bytes.append(data)
            cursor += len(data); index += 1
        desc['end'] = cursor
        feature_bytes.append(envelope(0, b'\xff'*4))
        cursor += 7; layers.append(desc)
    metadata = bytearray(258)
    metadata[:4] = bytes.fromhex('5080fe00')
    struct.pack_into('<I', metadata, 4, 0x081273ab)
    struct.pack_into('<HI', metadata, 9, 9, cursor)
    metadata[29:45] = (spec.date_text_1 + spec.date_text_2).encode('ascii')
    struct.pack_into('<4i', metadata, 45, *spec.header.raw_bounds)
    header = struct.pack('<H2x4i4x2I4x2I4xI', spec.header.flags, *spec.header.raw_bounds,
                         *spec.header.raw_grid, *spec.header.raw_levels, first_layer)
    layer_bytes = b''.join(envelope(18, struct.pack('<BHIIii', layer.layer_id, 0,
        d['first'], 0, *layer.layer_range)) for layer, d in zip(spec.layers, layers))
    data = (bytes(metadata) + envelope(4, struct.pack('<I', 265)) + envelope(17, header)
            + bank + layer_bytes + envelope(0, b'\xff'*4, selected=False) + b''.join(feature_bytes))
    assert len(data) == cursor
    return data, dict(firstLayer=first_layer, layerEnd=layer_stop, layers=layers,
                      features=features, bankWidth=bw, streamBase=stream_base,
                      bankPayload=len(bank_data)+24 if bank_data else 0)


def inspect(raw, spec):
    expected, layout = oracle(spec)
    assert raw == expected
    assert record(raw, 0)['tag'] == 0x8050 and record(raw, 0)['length'] == 254
    assert struct.unpack_from('<I', raw, 11)[0] == len(raw) and raw[144:146] == b'\0\0'
    assert struct.unpack('<I', record(raw, 258)['body']) == (265,)
    assert struct.unpack_from('<I', record(raw, 265)['body'], 48)[0] == layout['firstLayer']
    if layout['bankWidth'] is not None:
        bank = record(raw, 320)
        assert bank['kind'] == 52 and bank['w'] == layout['bankWidth']
        assert bank['length'] == layout['bankPayload'] and bank['end'] == layout['firstLayer']
        assert bank['body'][:24] == struct.pack('<2I4i', *spec.header.raw_grid, *spec.header.raw_bounds)
    for layer, d in zip(spec.layers, layout['layers']):
        row = record(raw, d['at'])
        assert row['tag'] == 0x8012 and row['length'] == 19
        assert struct.unpack('<BHIIii', row['body']) == (layer.layer_id, 0, d['first'], 0, *layer.layer_range)
        assert record(raw, d['end'])['tag'] == 0x8000
    assert record(raw, layout['layerEnd'])['tag'] == 0
    for d in layout['features']:
        r = record(raw, d['at']); k, w, f = d['kind'], d['w'], d['f']
        assert (r['kind'], r['w'], r['body']) == (k, w, d['body'])
        off = 2 if k in (40, 44) else 10
        assert r['body'][off:off+w] == bytes(w)
        if k in (40, 44):
            if k == 44:
                word = struct.unpack_from('<H', r['body'], off+w)[0]
                signed = word & 16383
                signed -= 16384 if signed >= 8192 else 0
                assert (signed, word >> 14) == (f.mantissa, f.decimal_scale)
            continue
        assert int.from_bytes(r['body'][off+w:off+2*w], 'little') == len(f.parts)
        for i, (rel, span) in enumerate(d['spans']):
            pointer = struct.unpack_from('<I', r['body'], off+2*w+4*i)[0]
            assert pointer < 1 << 30 and pointer == layout['streamBase']+rel
            assert raw[pointer:pointer+len(span)] == span
    assert record(raw, layout['layers'][-1]['end'])['end'] == len(raw)
    return layout


def make(layers, *, grid=(4, 4), bounds=(100000, 100000, 200000, 200000), **kwargs):
    return LayeredBatchSpec(RawMapHeader(0x1234, bounds, grid, (8, 20), 0), layers, **kwargs)


def emit(spec, budget=4096):
    plan = plan_layered_batch(spec, max_output_bytes=budget)
    sink = io.BytesIO()
    report = write_layered_batch(spec, sink, expected_plan_sha256=plan_sha(plan), max_output_bytes=budget)
    raw = sink.getvalue(); layout = inspect(raw, spec)
    assert plan['totalBytes'] == report['bytesWritten'] == len(raw)
    assert report['emittedStreamSHA256'] == sha(raw) and report['planSHA256'] == plan_sha(plan)
    assert report['nativeReaderExecuted'] is report['persistedFileReadback'] is False
    assert plan['nativeAcceptance'] == 'UNTESTED_LAYERED_AND_DEPTH44_GENERALIZATION'
    assert plan['depthQualification'] == 'SOURCE_BOUND_LAYER140_ZERO_TAG_UNITS_NOT_NATIVE_EXECUTED'
    assert plan['geographicDatumPortrayal'] == 'UNQUALIFIED'
    assert plan['sourceIds'] == 'CALLER_LEDGER_LABELS_NOT_NATIVE_UIDS'
    assert (plan['firstLayerOffset'], plan['layerEndOffset'], plan['bankLengthWidth']) == (
        layout['firstLayer'], layout['layerEnd'], layout['bankWidth'])
    for row, d, layer in zip(plan['layers'], layout['layers'], spec.layers):
        assert (row['recordOffset'], row['firstFeatureOffset'], row['featureEndOffset'], row['featureCount']) == (
            d['at'], d['first'], d['end'], len(layer.features))
        assert row['depthUnitTagBits'] == row['preservedOpaqueWord'] == 0
    for row, d in zip(plan['features'], layout['features']):
        assert (row['recordOffset'], row['lengthWidth'], row['payloadBytes']) == (d['at'], d['w'], d['length'])
        assert row['sourceId'] == d['f'].source_id and row['layerOrdinal'] == d['layerOrdinal']
        assert row['layerOffset'] == layout['layers'][d['layerOrdinal']]['at']
        assert row['recordBytes'] == 2+d['w']+d['length']
        for part, (rel, span), xy in zip(row['parts'], d['spans'], getattr(d['f'], 'parts', ())):
            assert part['streamOffset'] == layout['streamBase']+rel and part['streamBytes'] == len(span)
            assert part['pointCount'] == len(xy)
            assert part['rawU16PointSHA256'] == sha(b''.join(struct.pack('<HH', *p) for p in xy))
    return raw, plan, report


LINE = ((0, 0), (1, 2), (3, 1))
TRI = ((0, 0), (3, 0), (1, 2), (0, 0))


def mini():
    return make((PlainLayer(61, (Line32('shore', 29, (LINE,)),)),
                 PlainLayer(140, (DepthPoint44('depth', (125000, 175000), -15, 0),))))


def test_complete_fine462_reference_has_two_layers_and_exact_depth_word():
    raw, p, _ = emit(mini())
    assert len(raw) == 462 and p['headerOffset'] == 265 and p['bankOffset'] == 320
    assert p['features'][0]['parts'][0]['streamOffset'] == 347
    assert [r['recordOffset'] for r in p['layers']] == [362, 384]
    assert p['layerEndOffset'] == 406
    assert [r['recordOffset'] for r in p['features']] == [413, 439]
    assert [r['featureEndOffset'] for r in p['layers']] == [432, 455]
    assert raw[445:447] == b'\xf1\x3f'  # signed14 -15, scale0; not a sign16 word
    assert struct.unpack_from('<ii', raw, 447) == (125000, 175000)


@pytest.mark.parametrize('scale', range(4))
@pytest.mark.parametrize('mantissa', [-8192, -8191, -1, 0, 1, 8191])
def test_signed14_word_and_all_four_scale_tags_roundtrip_without_unit_inference(mantissa, scale):
    f = DepthPoint44('signed', (125000, 175000), mantissa, scale)
    raw, p, _ = emit(make((PlainLayer(140, (f,)),)))
    assert len(raw) == 372 and p['bankOffset'] is None
    body = record(raw, p['features'][0]['recordOffset'])['body']
    word = int.from_bytes(body[3:5], 'little')
    assert word == ((mantissa % 16384) + 16384*scale) == f.depth_word
    assert body[:3] == bytes((136, 0, 0))
    assert p['features'][0]['depthWord'] == word and p['features'][0]['classCode'] == 136


def test_single_ordinary_point_wire_remains_370_golden_but_new_profile_stays_unqualified():
    raw, _, _ = emit(make((PlainLayer(60, (Point40('p', 126, (125000, 175000)),)),)))
    assert sha(raw) == 'e4a9c11fad40af1adeab9c09fbb9634fb46661552d4643fa3acb1326493218b9'


def test_multiple_layers_features_parts_reversals_and_exact_duplicate_points_survive():
    repeated = ((1, 3), (1, 3), (0, 0), (1, 3))
    s = make((PlainLayer(62, (Area24('area', 74, (TRI, TRI[::-1])),)),
              PlainLayer(140, (DepthPoint44('d0', (100000, 200000), -8192, 3),
                               Point40('plain-on140', 126, (200000, 100000)),
                               DepthPoint44('d1', (150000, 150000), 8191, 0))),
              PlainLayer(61, (Line32('line', 29, (LINE, LINE[::-1], repeated)),)),
              PlainLayer(60, (Point40('ordinary', 0, (100000, 100000)),))),
             date_text_1='20000229', date_text_2='20240229')
    raw, p, _ = emit(s)
    assert [r['layerId'] for r in p['layers']] == [62, 140, 61, 60]
    assert [r['sourceId'] for r in p['features']] == ['area', 'd0', 'plain-on140', 'd1', 'line', 'ordinary']
    assert len({x['streamOffset'] for r in p['features'] for x in r['parts']}) == 5
    assert raw[29:45] == b'2000022920240229'


def test_reordering_layers_rebases_each_feature_list_and_geometry_sequence_without_dedup():
    s = mini(); swapped = make(s.layers[::-1])
    a, pa, _ = emit(s); b, pb, _ = emit(swapped)
    assert len(a) == len(b) and a != b and plan_sha(pa) != plan_sha(pb)
    assert [r['kind'] for r in pb['features']] == [44, 32]
    assert [r['firstFeatureOffset'] for r in pb['layers']] == [413, 436]


@pytest.mark.parametrize('cls,lid,payloads,part', [(Line32,61,(252,258),LINE[:2]), (Area24,62,(253,259),TRI)])
def test_coupled_feature_w1_to_w2_rebases_later_same_and_other_layer(cls, lid, payloads, part):
    outputs = []
    for count, w, length in zip((60,61),(1,2),payloads):
        s = make((PlainLayer(lid,(cls('many',74,(part,)*count),cls('same-layer-after',74,(part,)))),
                  PlainLayer(140,(DepthPoint44('after-other-layer',(125000,175000),77,0),))))
        raw,p,_ = emit(s); row = p['features'][0]
        body=record(raw,row['recordOffset'])['body']
        assert row['lengthWidth']==w and row['payloadBytes']==length
        assert body[10:10+w]==bytes(w) and int.from_bytes(body[10+w:10+2*w],'little')==count
        assert p['features'][1]['recordOffset']==row['recordOffset']+row['recordBytes']
        assert p['features'][2]['recordOffset']==p['layers'][0]['featureEndOffset']+7
        outputs.append((raw,p))
    assert len(outputs[1][0])-len(outputs[0][0])==5*len(part)+7


@pytest.mark.parametrize('n,bw',[(46,1),(47,2)])
def test_bank_width_boundary_rebases_all_layer_lists_and_depth_record(n,bw):
    pts=tuple((i%4,(i//4)%4) for i in range(n))
    _,p,_=emit(make((PlainLayer(61,(Line32('l',29,(pts,)),)),PlainLayer(140,(DepthPoint44('d',(125000,175000),15),)))))
    assert p['bankLengthWidth']==bw and p['bankPayloadBytes']==24+5*n
    assert p['features'][0]['parts'][0]['streamOffset']==320+2+bw+24


def test_same_bbox_interior_xy_layer_range_and_depth_word_are_separately_plan_bound():
    base=mini(); pa=plan_layered_batch(base)
    variants=(
        make((PlainLayer(61,(Line32('shore',29,(((0,0),(2,2),(3,1)),)),)),base.layers[1])),
        make((PlainLayer(61,base.layers[0].features,(-1,100000)),base.layers[1])),
        make((base.layers[0],PlainLayer(140,(DepthPoint44('depth',(125000,175000),-14,0),)))),
        make((base.layers[0],PlainLayer(140,(DepthPoint44('depth',(125000,175000),-15,1),)))),
    )
    for s in variants:
        pb=plan_layered_batch(s); assert plan_sha(pa)!=plan_sha(pb)
        sink=WriteOnly()
        with pytest.raises(ValueError,match='PLAN_SHA256'):
            write_layered_batch(s,sink,expected_plan_sha256=plan_sha(pa))
        assert sink.calls==0
    assert pa['features'][0]['bboxU16']==plan_layered_batch(variants[0])['features'][0]['bboxU16']


def test_source_labels_bind_only_ledger_not_wire_or_native_uid():
    a=mini(); b=make((a.layers[0],PlainLayer(140,(DepthPoint44('Ä',(125000,175000),-15),))))
    ra,pa,_=emit(a); rb,pb,_=emit(b)
    assert ra==rb and plan_sha(pa)!=plan_sha(pb)


class WriteOnly:
    def __init__(self,limit=7): self.calls=0; self.data=bytearray(); self.limit=limit
    def write(self,raw):
        self.calls+=1; count=min(self.limit,len(raw)); self.data.extend(raw[:count]); return count
    def seek(self,*args): raise AssertionError('seek not allowed')
    def tell(self): raise AssertionError('tell not allowed')
    def flush(self): raise AssertionError('flush not allowed')
    def close(self): raise AssertionError('close not allowed')


@pytest.mark.parametrize('limit',[1,2,7,257,99999])
def test_short_writes_are_complete_and_hash_exact_without_sink_lifetime_calls(limit):
    s=mini(); p=plan_layered_batch(s); out=WriteOnly(limit)
    r=write_layered_batch(s,out,expected_plan_sha256=plan_sha(p))
    assert bytes(out.data)==oracle(s)[0] and r['emittedStreamSHA256']==sha(out.data)
    assert r['bytesWritten']==462


@pytest.mark.parametrize('bad',[None,0,True,-1,99999,1.5])
def test_bad_write_progress_has_no_success_receipt(bad):
    class Bad(WriteOnly):
        def write(self,raw): self.calls+=1; return bad
    s=mini(); out=Bad()
    with pytest.raises(OSError): write_layered_batch(s,out,expected_plan_sha256=plan_sha(plan_layered_batch(s)))
    assert out.calls==1


def test_midstream_exception_is_preserved_without_claiming_rollback():
    error=OSError('invented sink failure')
    class Bad(WriteOnly):
        def write(self,raw):
            if self.calls==2: raise error
            return super().write(raw)
    s=mini(); out=Bad(99999)
    with pytest.raises(OSError) as got:
        write_layered_batch(s,out,expected_plan_sha256=plan_sha(plan_layered_batch(s)))
    assert got.value is error and bytes(out.data)==oracle(s)[0][:265]


def test_exact_budget_replan_bound_and_one_byte_small_refuses_before_sink():
    s=mini(); p=plan_layered_batch(s,max_output_bytes=462); sink=WriteOnly()
    write_layered_batch(s,sink,expected_plan_sha256=plan_sha(p),max_output_bytes=462)
    assert len(sink.data)==462
    for budget in (461,4096):
        sink=WriteOnly()
        with pytest.raises(ValueError):
            write_layered_batch(s,sink,expected_plan_sha256=plan_sha(p),max_output_bytes=budget)
        assert sink.calls==0
    assert p['outputBudgetBytes']==462


@pytest.mark.parametrize('n,scale',[(-8193,0),(8192,0),(True,0),(1.5,0),(0,-1),(0,4),(0,True),(0,None)])
def test_depth_bit_domain_refuses_clamping_wrapping_or_bool_inputs(n,scale):
    with pytest.raises(ValueError): DepthPoint44('d',(125000,175000),n,scale)


@pytest.mark.parametrize('lid,feature',[
    (60,Line32('l',29,(LINE,))), (61,Point40('p',126,(125000,175000))),
    (62,Line32('l',29,(LINE,))), (63,Area24('a',74,(TRI,))),
    (60,DepthPoint44('d',(125000,175000),1)),(0,DepthPoint44('d',(125000,175000),1)),
    (256,Point40('p',126,(125000,175000))), (True,Point40('p',126,(125000,175000))),
])
def test_layer_type_low2_and_depth140_binding_are_strict(lid,feature):
    with pytest.raises(ValueError): PlainLayer(lid,(feature,))


def test_empty_layer_duplicate_layer_or_global_source_label_refuses():
    p=Point40('same',126,(125000,175000))
    with pytest.raises(ValueError): PlainLayer(60,())
    with pytest.raises(ValueError): make(())
    with pytest.raises(ValueError): make((PlainLayer(60,(p,)),PlainLayer(60,(Point40('other',0,p.xy),))))
    with pytest.raises(ValueError): make((PlainLayer(60,(p,)),PlainLayer(140,(DepthPoint44('same',p.xy,1),))))


def test_deep_mutable_inputs_are_snapshotted_and_dataclasses_frozen():
    xy=[125000,175000]; d=DepthPoint44('d',xy,-15)
    points=[[0,0],[1,2],[3,1]]; f=Line32('l',29,[points])
    fs=[d]; layers=[PlainLayer(61,[f]),PlainLayer(140,fs)]; s=make(layers)
    before=emit(s)[0]
    xy[0]=200000; points[1][0]=2; fs.clear(); layers.reverse()
    assert emit(s)[0]==before
    with pytest.raises(FrozenInstanceError): d.mantissa=4


def json_spec():
    return {'format':'no5-own-plain-layered-feature-batch/1', 'header':{
        'flags':0x1234,'raw_bounds':[100000,100000,200000,200000], 'raw_grid':[4,4],
        'raw_levels':[8,20],'trailing_value':0}, 'layers':[
            {'layer_id':61,'layer_range':[0,100000],'features':[
                {'kind':32,'source_id':'shore','class_code':29,'parts':[list(LINE)]}]},
            {'layer_id':140,'layer_range':[0,100000],'features':[
                {'kind':44,'source_id':'depth','xy':[125000,175000],'mantissa':-15,'decimal_scale':0}]}],
        'date_text_1':'20261017','date_text_2':'20261017'}


def test_json_roundtrip_and_no_unsupported_unit_style_opaque_or_attribute_knobs():
    assert emit(layered_batch_from_spec(json_spec()))[0]==oracle(mini())[0]
    for location,key,value in [('root','dates',[]),('layer','units',2),('layer','opaque_word',1),
        ('depth','class_code',136),('depth','flags',0),('depth','attributes',[]),
        ('depth','unit','m'),('depth','datum','unknown'),('depth','depth',1.5)]:
        v=copy.deepcopy(json_spec())
        target=v if location=='root' else v['layers'][1] if location=='layer' else v['layers'][1]['features'][0]
        target[key]=value
        with pytest.raises(ValueError): layered_batch_from_spec(v)


def test_inspector_detects_layer_cursor_depth_word_and_feature_end_corruption():
    s=mini(); raw,p,_=emit(s)
    positions=[11,144,261,316,p['layers'][0]['recordOffset']+6,
               p['layers'][1]['recordOffset']+6,p['layerEndOffset']+1,
               p['features'][0]['recordOffset']+15,445,447,p['layers'][0]['featureEndOffset']+1]
    for at in positions:
        changed=bytearray(raw); changed[at]^=0x80
        with pytest.raises(AssertionError): inspect(bytes(changed),s)


def test_bounded_random_multilayer_complete_stream_oracle():
    rng=random.Random(46214044)
    for case in range(25):
        layers=[]
        for lid in rng.sample([60,61,62,140],rng.randrange(1,5)):
            fs=[]
            for i in range(rng.randrange(1,5)):
                sid=f'{case}:{lid}:{i}'
                if lid==140:
                    fs.append(DepthPoint44(sid,(rng.randrange(100000,200001),rng.randrange(100000,200001)),
                                           rng.randrange(-8192,8192),rng.randrange(4)))
                elif lid==60:
                    fs.append(Point40(sid,rng.randrange(256),(150000,150000)))
                elif lid==61:
                    parts=tuple(tuple((rng.randrange(4),rng.randrange(4)) for _ in range(rng.randrange(2,8)))
                                for _ in range(rng.randrange(1,4)))
                    fs.append(Line32(sid,29,parts))
                else:
                    fs.append(Area24(sid,74,(TRI,TRI[::-1])[:rng.randrange(1,3)]))
            layers.append(PlainLayer(lid,tuple(fs),(-12,34567)))
        emit(make(tuple(layers)))
