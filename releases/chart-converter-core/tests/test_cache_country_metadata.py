"""Invented arbitrary-cell metadata; no existing nautical data or resources."""
import copy
import hashlib
import json
import struct
import xml.etree.ElementTree as ET

import pytest

from no_chart_converter.cache_country import metadata as m
from no_chart_converter.country_runtime import wire
from no_chart_converter.rx5.header import PLAIN_VECTOR_QWORDS, validate_plain_vector_header


def attr(code, kind, value, acronym):
    return dict(code=code, type=kind, wireType={'uint32': 0, 'float64': 2, 'utf8': 4}[kind],
                value=value, acronym=acronym, sourceRecord=dict(member='own.bin', offset=code, length=7, recordType=65))


def context():
    cells, owners = [], []
    for ci, cid in enumerate(('TEST-ALPHA-793', 'FRESH-ZETA-004')):
        attributes = [attr(4, 'uint32', 4, 'COLOUR'), attr(23, 'utf8', '1,3,7', 'CATLIT'),
                      attr(87, 'float64', -3.25, 'VALSOU'), attr(102, 'utf8', 'Üfer “α”\nA', 'OBJNAM'),
                      attr(158, 'utf8', 'notes.txt', 'TXTDSC'), attr(133, 'uint32', 17290, 'SCAMIN')]
        feature = dict(sourceFeatureId=65000-ci, sourceId=cid + ':f65000', classCode=17,
                       classAcronym='BOYCAR', attributes=attributes, geometry=None,
                       displayScale={'minimumDenominator': {'state': 'verified', 'value': 17290},
                                     'maximumDenominator': {'state': 'unknown', 'value': None}},
                       sourceRecord=dict(member='own.bin', offset=0, length=11, recordType=64))
        ring = [[1.0, 2.0], [2.0, 2.0], [2.0, 3.0], [1.0, 3.0], [1.0, 2.0]]
        cells.append(dict(id=cid, name='Own <' + cid + '> & Ä', features=[feature], nativeScale=12345 + ci,
                          bbox=dict(west=1., east=2., south=2., north=3., crossesAntimeridian=False),
                          datums={'sounding': {'state': 'unknown', 'value': None}},
                          units={'soundingDepth': {'state': 'inferred', 'value': 'm'}},
                          coverage={'covered': [{'coordinates': ring}], 'excluded': []},
                          source=dict(sha256=hashlib.sha256(cid.encode()).hexdigest(), edition=7,
                                      publishDate='20261016', updateDate='20261017', update=2),
                          textDocuments=[dict(name='notes.txt', content=f'Own document {ci}\r\nÄ', sourceReference='invented')]))
        owners.append(dict(cellId=cid, cellOrdinal=ci, sourceOwnerOrdinal=0,
                           uid=((ci+1) << 32) | feature['sourceFeatureId'], feature=feature))
    return dict(cells=cells, owners=owners, by_cell={c['id']: [o for o in owners if o['cellId'] == c['id']] for c in cells},
                package={'source': {'product': 'Invented'}})


def test_all_typed_values_and_documents_are_roundtrippable(tmp_path):
    ctx = context()
    result = m.prepare_metadata(ctx, tmp_path, {})
    ledger = json.loads(m.read_pin(result['ledger']))
    assert len(ledger['owners']) == 2
    for owner, row in zip(ctx['owners'], ledger['owners']):
        cell = result['cells'][owner['cellId']]
        assert m.decode_attributes(cell['additional'], row['attributes']) == owner['feature']['attributes']
        assert b'1,3,7\0' in cell['additional']
        assert b'-3.25\0' in cell['additional']
        assert owner['feature']['attributes'][4]['value'] == 'notes.txt'
        assert result['originalDocumentMaps'][owner['cellId']]['notes.txt'].encode() in cell['additional']
        assert list(result['documentMaps'][owner['cellId']].keys()) != ['notes.txt']
        rewritten, _, changes = wire.rewrite_additional(cell['additional'], {158}, result['documentMaps'][owner['cellId']])
        assert rewritten == cell['additional'] and changes == []  # Frozen compiler cannot change the bound fresh query.
    assert result['documentNames'][0] != result['documentNames'][1]
    assert result['documents'][0]['content'] != result['documents'][1]['content']
    assert result['dictionaryMaps'][ctx['cells'][0]['id']]['1'] == result['dictionary'].index('BOYCAR')
    with pytest.raises(FileExistsError):
        m.prepare_metadata(ctx, tmp_path, {})


@pytest.mark.parametrize('bad,code', [
    ([attr(4, 'uint32', 4, 'COLOUR'), attr(4, 'utf8', 'red', 'COLOUR')], 'DUPLICATE'),
    ([attr(4, 'uint32', -1, 'COLOUR')], 'UINT32'),
    ([attr(4, 'uint32', True, 'COLOUR')], 'UINT32'),
    ([attr(4, 'float64', float('nan'), 'VALSOU')], 'FLOAT64'),
    ([attr(4, 'utf8', 'a\0b', 'OBJNAM')], 'TEXT'),
    ([dict(code=4, type='list', wireType=4, value=[1, 2])], 'UNSUPPORTED')])
def test_unrepresentable_query_never_silently_loses_values(bad, code):
    with pytest.raises(ValueError, match=code):
        m.encode_attributes(bad)


def test_signed_zero_float_bits_preserved():
    attrs = [attr(87, 'float64', -0.0, 'VALSOU')]
    data, ledger = m.encode_attributes(attrs)
    assert struct.pack('<d', m.decode_attributes(data, ledger)[0]['value']) == struct.pack('<d', -0.0)


def test_owner_and_catalog_refusals(tmp_path):
    ctx = context()
    ctx['owners'][1]['uid'] = ctx['owners'][0]['uid']
    with pytest.raises(ValueError, match='OWNER_ORDER_UID'):
        m.prepare_metadata(ctx, tmp_path/'uid', {})
    ctx = context()
    ctx['cells'][0]['features'][0]['attributes'][0]['acronym'] = None
    with pytest.raises(ValueError, match='MISSING_ATTRIBUTE'):
        m.prepare_metadata(ctx, tmp_path/'catalog', {})
    ctx = context()
    ctx['cells'][0]['features'][0]['attributes'][4]['value'] = 'missing.txt'
    with pytest.raises(ValueError, match='UNRESOLVED_DOCUMENT'):
        m.prepare_metadata(ctx, tmp_path/'doc', {})


def test_fresh_parent_sources_and_resources_bind_new_inputs(tmp_path):
    ctx = context()
    prepared = m.prepare_metadata(ctx, tmp_path, {})
    common = m.write_bytes(tmp_path, 'own-common41.txt', b'OWN SYNTHETIC COMMON41\n')
    colour = m.write_bytes(tmp_path, 'own-common40.txt', b'OWN SYNTHETIC COLOURS\n')
    # Invalid stale attribute resources are intentionally replaced from fresh IR.
    stale_attrs = m.write_bytes(tmp_path, 'stale66.txt', b'99\tSTALE\tOLD\n')
    profile = {'sourceInfo': {'level': 14, 'minimumLevel': -1}}
    config = dict(_presentationProfile=profile, _presentationBaseDirectory=tmp_path)
    presentation = {'resources': [{'id': 65, 'data': common}, {'id': 64, 'data': colour}, {'id': 66, 'data': stale_attrs}],
                    'nativeScaminMultiplierU32': 1}
    root_ring = [[100, 200], [150, 200], [150, 240], [100, 240], [100, 200]]
    coverage = [dict(cellId=c['id'], nativeScale=c['nativeScale'], covered=[root_ring],
                     excluded=[[[110,210],[120,210],[120,220],[110,220],[110,210]]]) for c in ctx['cells']]
    result = m.finalize_metadata(ctx, prepared, {'coverage': coverage}, presentation, tmp_path, config)
    assert set(result['pins']) == {'composition', 'containerInputs', 'presentationParent'}
    raw = m.read_pin(result['presentationParent'])
    validate_plain_vector_header(raw[:240], file_bytes=len(raw))
    assert all(struct.unpack_from('<Q', raw, at)[0] == value
               for at, value in PLAIN_VECTOR_QWORDS.items())
    blocks = wire.blocks(raw)
    source = wire.chain(blocks, struct.unpack_from('<Q', raw, 144)[0])
    assert [b.disk_id for b in source] == [0x30, 0x30, 0x34]
    uuids = set()
    for cell, block in zip(ctx['cells'], source[:2]):
        xml = ET.fromstring(block.payload)
        assert xml.find('source_chart_info').get('id') == cell['id']
        assert xml.find('source_chart_info').get('name') == cell['name']
        assert xml.find('render_target').attrib == {'level': '14', 'minimum_level': '-1'}
        assert xml.find('source_chart_info/parameters') is None  # Inferred is not asserted as verified.
        uuids.add(xml.get('uuid'))
    assert len(uuids) == 2
    ro_offset = struct.unpack_from('<Q', source[-1].prefix, 16)[0]
    ro = blocks[ro_offset].payload
    assert ro.count(struct.pack('<IH', 0x20000, 16)) == 2
    resources = wire.chain(blocks, struct.unpack_from('<Q', raw, 184)[0])
    attrs = next(b.payload for b in resources if b.disk_id == 66)
    assert b'COLOUR' in attrs and b'STALE' not in attrs
    di = wire.dictionary(blocks[struct.unpack_from('<Q', raw, 168)[0]].payload)
    amap = next(b.payload for b in resources if b.disk_id == 71)
    assert all(di[index] == prepared['attributeNames'][code] for code, index in struct.iter_unpack('<II', amap))
    doc_chain = wire.chain(blocks, struct.unpack_from('<Q', raw, 200)[0])
    assert wire.dictionary(doc_chain[0].payload) == prepared['documentNames']
    assert [b.payload for b in doc_chain[1:]] == [d['content'] for d in prepared['documents']]
    assert all(b.flags == 64 for b in doc_chain[1:])
    assert struct.unpack_from('<I', raw, 216)[0] == 1
    assert struct.unpack_from('<Q', raw, 104)[0] == 256


def test_resource_pin_change_refused_before_parent(tmp_path):
    ctx = context()
    prepared = m.prepare_metadata(ctx, tmp_path, {})
    pin = m.write_bytes(tmp_path, 'common.txt', b'OWN')
    (tmp_path/'common.txt').write_bytes(b'changed')
    root = [[1,1],[2,1],[2,2],[1,2],[1,1]]
    coverage = [dict(cellId=c['id'], nativeScale=c['nativeScale'], covered=[root], excluded=[]) for c in ctx['cells']]
    with pytest.raises(ValueError, match='DATA_PIN_MISMATCH'):
        m.finalize_metadata(ctx, prepared, {'coverage': coverage},
                            {'resources': [{'id':65,'data':pin}], 'nativeScaminMultiplierU32':1}, tmp_path,
                            {'_presentationProfile':{}, '_presentationBaseDirectory':tmp_path})
    assert not (tmp_path/'metadata/presentation-parent.rx5').exists()
