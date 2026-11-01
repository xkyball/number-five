"""Own invented wire/proof fixtures test binding, not native visual proof."""
import hashlib
import json
import struct

import pytest

from no_chart_converter.rx5.locality_apply import (
    IDENTICAL, SCHEMA, SOURCE_GATE, apply_scoped_locality, prepare_scoped_plan,
)
from no_chart_converter.rx5.model import native_tree, parts, validate
from no_chart_converter.rx5.header import new_plain_vector_header
from no_chart_converter.rx5.wire import AuthoredBlock, FEATURE, assemble, blocks, encode_dictionary

TILE = (15, 1, 1)


def sha(raw):
    return hashlib.sha256(raw).hexdigest()


def fixture(*, descendants=False, actual_change=True, whole_only=False):
    q, unit = 2147483649, 2147483649//32768
    inside = [(5*unit//4, 5*unit//4), (7*unit//4, 5*unit//4),
              (7*unit//4, 7*unit//4), (5*unit//4, 7*unit//4)]
    inside.append(inside[0])
    far = [(4*unit, 4*unit), (4*unit+100, 4*unit),
           (4*unit+100, 4*unit+100), (4*unit, 4*unit+100)]
    far.append(far[0])
    if not actual_change:
        far = inside
    coordinates, words = bytearray(), []
    for ring in (inside, far):
        words.append((24 << 24) | len(coordinates))
        coordinates += struct.pack('<I', 0x01000000 | len(ring))
        coordinates += b''.join(struct.pack('<II', *p) for p in ring)
    xyz_at = len(coordinates)
    coordinates += struct.pack('<I', 0x01000001) + struct.pack('<IIi', 3*unit//2, 3*unit//2, -17)
    index = struct.pack('<III', 2, *words)
    whole_index = len(index)
    index += struct.pack('<II', 1, words[1])
    xyz_index = len(index)
    index += struct.pack('<II', 1, xyz_at)
    body = b'AC(SYNTHETIC)'
    render = struct.pack('<HI', 1, 0)+struct.pack('<IH', 196608, len(body))+body
    no_programme = len(render)
    render += struct.pack('<HI', 0, 0)
    features = FEATURE.pack(100, 0, 0, 0, 15, 1, 3, 0, 0, 0)
    features += FEATURE.pack(200, whole_index, 0, 0, 15, 1, 3, 0, 0, 0)
    features += b''.join(FEATURE.pack(300, xyz_index, no_programme, 0, 15, 129, kind, 0, 0, 0)
                         for kind in (4, 9))
    if whole_only:
        features = FEATURE.pack(200, whole_index, 0, 0, 15, 1, 3, 0, 0, 0)
    header = new_plain_vector_header(bytes(range(16)))
    authored = [AuthoredBlock('index', 0x1101, 0, b'', index),
                AuthoredBlock('coords', 0x1102, 0, b'', bytes(coordinates)),
                AuthoredBlock('render', 0x1103, 0, b'', render),
                AuthoredBlock('query', 0x1104, 0, b'', bytes(14)),
                AuthoredBlock('info', 2, 0, b'', b'<rx5><chart_info name="OWN SYNTHETIC"/></rx5>'),
                AuthoredBlock('source', 0x30, 0, b'', b'<rx5><source_chart_info id="SYNTHETIC"/></rx5>'),
                AuthoredBlock('dictionary', 8, 16, bytes(24), encode_dictionary([''])),
                AuthoredBlock('presentation', 65, 0, b'', b'OWN RESOURCE'),
                AuthoredBlock('documents', 7, 16, bytes(24), encode_dictionary([]))]
    streams = tuple((32+8*i, name) for i, name in enumerate(('index', 'coords', 'render', 'query')))
    for level in range(15):
        refs = tuple((8*i, 'n'+str(level+1) if i == (3 if level == 14 else 0) else
                      ('sibling' if level == 14 and i == 2 else None)) for i in range(4))
        authored.append(AuthoredBlock('n'+str(level), 0x6000+level, 1, bytes(32), b'', references=refs))
    refs = tuple((8*i, 'child16' if descendants and i == 0 else None) for i in range(4))
    authored.append(AuthoredBlock('n15', 0x600f, 3, bytes(64), features, references=refs+streams))
    authored.append(AuthoredBlock('sibling', 0x600f, 3, bytes(64), features,
                                 references=tuple((8*i, None) for i in range(4))+streams))
    if descendants:
        authored.append(AuthoredBlock('child16', 0x6010, 3, bytes(64), features,
                                     references=tuple((8*i, None) for i in range(4))+streams))
    return assemble(bytes(header), authored, {136:'info', 144:'source', 160:'n0',
                    168:'dictionary', 184:'presentation', 200:'documents'})[0]


def approval(tmp_path, raw):
    plan = prepare_scoped_plan(raw, [TILE])
    evidence = {}
    for name in ('visualCertificate', 'admissionCertificate', 'querySemanticReport'):
        data = json.dumps({'status': 'SYNTHETIC_BINDING_TEST_ONLY', 'role': name}).encode()
        path = tmp_path/(name+'.json')
        path.write_bytes(data)
        evidence[name] = {'path': path.name, 'bytes': len(data), 'sha256': sha(data)}
    document = {'format': SCHEMA, 'status': 'APPROVED_FOR_SCOPED_PHYSICAL_A97_TEST',
                'source': plan['source'], 'kernels': plan['kernels'],
                'rows': [{'tile': list(TILE), 'approved': True,
                          'planSHA256': plan['rows'][0]['planSHA256'],
                          'visualSemanticDisposition': IDENTICAL,
                          'querySemanticDisposition': IDENTICAL,
                          'nativeSourceGateDisposition': SOURCE_GATE, **evidence}]}
    path = tmp_path/'allowlist.json'
    path.write_text(json.dumps(document))
    return path, sha(path.read_bytes())


def mutate(path, edit):
    doc = json.loads(path.read_bytes())
    edit(doc)
    path.write_text(json.dumps(doc))
    return sha(path.read_bytes())


def test_prepare_is_readonly_candidate_metadata_with_complete_bindings():
    raw = fixture()
    plan = prepare_scoped_plan(raw, [TILE])
    assert raw == fixture()
    assert plan['status'] == 'CANDIDATE_PLAN_ONLY_NOT_APPROVAL'
    assert plan['source'] == {'bytes': len(raw), 'sha256': sha(raw)}
    row, = plan['rows']
    assert row['plan']['hasActualChange'] is True
    assert row['plan']['totals'] == {'wholeOwnersRemoved': 1, 'partialOwnersChanged': 1,
                                   'partsRemoved': 2, 'nonSoundingXYRemoved': 10}
    assert len(row['plan']['sourceStreams']) == 4
    assert len(row['plan']['owners']) == 4
    assert all('wholeProgrammeFrameSHA256' in o for o in row['plan']['owners'])
    assert prepare_scoped_plan(raw, [TILE]) == plan


def test_apply_exact_scope_preserves_all_original_resources_title_source_and_xyz(tmp_path):
    raw = fixture()
    path, pin = approval(tmp_path, raw)
    output, report = apply_scoped_locality(raw, path, pin)
    assert report['status'] == 'APPLIED_REVIEWED_SCOPED_LOCALITY'
    assert report['actualChildPrunes'] == report['newSpatialChildren'] == 0
    assert report['sourcePrefixInverseSHA256'] == sha(raw)
    assert report['titleChanged'] is False
    assert output[136:144] == raw[136:144]
    assert report['externalProofMathIndependentlyRecomputed'] is False
    assert report['nativeDeviceOrLoadTimeAcceptanceClaim'] is False
    assert apply_scoped_locality(raw, path, pin)[0] == output
    assert validate(output)['owners'] == validate(raw)['owners']-1
    before, after = blocks(raw), blocks(output)
    oldtree, newtree = native_tree(raw), native_tree(output)
    old_at = next(a for a, t in oldtree.items() if t == TILE)
    new_at = next(a for a, t in newtree.items() if t == TILE)
    old, new = before[old_at], after[new_at]
    assert set(oldtree.values()) == set(newtree.values())
    sibling = next(a for a, t in oldtree.items() if t == (15, 0, 1))
    assert sibling in newtree and after[sibling] == before[sibling]
    oldio, oldco, ro, ao = struct.unpack_from('<4Q', old.prefix, 32)
    io, co, newro, newao = struct.unpack_from('<4Q', new.prefix, 32)
    assert (ro, ao) == (newro, newao)
    assert after[ro] == before[ro] and after[ao] == before[ao]
    records = [new.payload[i:i+32] for i in range(0, len(new.payload), 32)]
    assert [FEATURE.unpack(r)[6] for r in records] == [3, 4, 9]
    twins = [list(parts(r, after[io].payload, after[co].payload))[0] for r in records[1:]]
    assert twins[0]['coordinateOffset'] == twins[1]['coordinateOffset']
    assert twins[0]['raw'] == twins[1]['raw']
    assert struct.unpack_from('<i', twins[0]['raw'], 12)[0] == -17
    inverse = bytearray(output[:len(raw)])
    inverse[56:72] = raw[56:72]
    for row in report['appliedTiles']:
        ref = row['ancestorReference']
        inverse[ref['fileOffset']:ref['fileOffset']+8] = bytes.fromhex(ref['sourceRefHex'])
    assert bytes(inverse) == raw


@pytest.mark.parametrize('field, value, error', [
    ('approved', False, 'EXPLICIT_TILE_APPROVAL'),
    ('planSHA256', '0'*64, 'EXACT_REVIEWED_PER_TILE_PLAN'),
    ('querySemanticDisposition', 'DIFFERENT_ACCEPTED_LOSS', 'NO_QUERY_LOSS_OVERRIDE'),
    ('visualSemanticDisposition', 'UNCHECKED', 'IDENTICAL_SOURCE_DOMAIN'),
    ('nativeSourceGateDisposition', 'BYPASS', 'UNCHANGED_SOURCE_IDS'),
])
def test_refuses_incomplete_or_changed_decision(tmp_path, field, value, error):
    raw = fixture()
    path, _ = approval(tmp_path, raw)
    pin = mutate(path, lambda doc: doc['rows'][0].update({field: value}))
    with pytest.raises(ValueError, match=error):
        apply_scoped_locality(raw, path, pin)


def test_exact_allowlist_source_kernel_and_external_proof_pins_required(tmp_path):
    raw = fixture()
    path, pin = approval(tmp_path, raw)
    with pytest.raises(ValueError, match='EXPLICIT_REVIEWED_ALLOWLIST_PIN'):
        apply_scoped_locality(raw, path, '0'*64)
    with pytest.raises(ValueError, match='BOUND_TO_EXACT_SOURCE'):
        apply_scoped_locality(raw+b'CHANGED', path, pin)
    bad = mutate(path, lambda doc: doc['kernels']['wholeOwner'].update(sha256='0'*64))
    with pytest.raises(ValueError, match='APPROVED_UNCHANGED_LOCALITY_KERNEL'):
        apply_scoped_locality(raw, path, bad)
    path, pin = approval(tmp_path, raw)
    (tmp_path/'querySemanticReport.json').write_bytes(b'CHANGED')
    with pytest.raises(ValueError, match='EXACT_EXTERNAL_APPROVAL_EVIDENCE'):
        apply_scoped_locality(raw, path, pin)


@pytest.mark.parametrize('tiles', [[], [(13, 1, 1)], [(15, True, 1)], [(15, -1, 1)],
                                   [(15, 32768, 1)], [TILE, TILE], [(15, 22, 22)]])
def test_prepare_refuses_default_duplicate_invalid_or_absent_scope(tiles):
    with pytest.raises(ValueError):
        prepare_scoped_plan(fixture(), tiles)


def test_descendants_preserved_by_refusing_nonleaf_target():
    with pytest.raises(ValueError, match='NO_DESCENDANTS'):
        prepare_scoped_plan(fixture(descendants=True), [TILE])


def test_owner_elision_cannot_introduce_an_empty_vector_state():
    with pytest.raises(ValueError, match='NONEMPTY_RETAINED_SOURCE_VECTOR_REQUIRED'):
        prepare_scoped_plan(fixture(whole_only=True), [TILE])


def test_no_change_is_not_advertised_as_applied_optimisation(tmp_path):
    raw = fixture(actual_change=False)
    path, pin = approval(tmp_path, raw)
    with pytest.raises(ValueError, match='NO_ACTUAL_LOCALITY_CHANGE'):
        apply_scoped_locality(raw, path, pin)


def test_candidate_pool_and_empty_apply_scope_do_not_activate(tmp_path):
    raw = fixture()
    path, _ = approval(tmp_path, raw)
    pin = mutate(path, lambda doc: doc.update(status='CANDIDATE_PLAN_ONLY_NOT_APPROVAL'))
    with pytest.raises(ValueError, match='REVIEWED_LOCALITY_APPROVAL_REQUIRED'):
        apply_scoped_locality(raw, path, pin)
    path, _ = approval(tmp_path, raw)
    pin = mutate(path, lambda doc: doc.update(rows=[]))
    with pytest.raises(ValueError, match='EXPLICIT_APPROVED_TILE_ROWS'):
        apply_scoped_locality(raw, path, pin)
