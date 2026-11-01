"""Self-generated records/programmes only; no chart/OEM/runtime fixtures."""
from copy import deepcopy
import hashlib
import json
import struct

import pytest

from no_chart_converter.rx5 import finite_view_draw_cull as cull, model
from no_chart_converter.rx5.wire import FEATURE


def sha(raw):
    return hashlib.sha256(raw).hexdigest()


def pin(raw):
    return dict(bytes=len(raw), sha256=sha(raw))


def fixture(extra=False, duplicate_uid=False):
    render, records, proof_rows = bytearray(), [], []
    for ordinal, uid in enumerate((701, 701 if duplicate_uid else 703, 709)):
        contexts = {'11': b'OWN_A();', '17': b'OWN_B();'}
        if extra:
            contexts['99'] = b'OWN_UNSELECTED();'
        frame = cull._encode(contexts, 100+ordinal)
        offset = len(render)
        render.extend(frame)
        record = FEATURE.pack(uid, ordinal*4, offset, 0, 0, 42, 2, 0, 0, 0)
        records.append(record)
        if ordinal < 2:
            proof_rows.append(dict(recordOrdinal=ordinal, uid=uid, recordSHA256=sha(record),
                programmeFrameSHA256=sha(frame), emptyContextIDs=[11, 17], sourceInkExcluded=True))
    buffers = (b''.join(records), b'own-index-data', b'own-full-xyz-data', bytes(render), b'own-query-attributes')
    evidence = dict(format=cull.FORMAT, status='SOURCE_INK_EXCLUSION_QUALIFIED',
        sourceTile=[9, 203, 207], parentPin=pin(b'own-parent'), proofPin=pin(b'own-exclusion-theorem'),
        inputPins=cull._pins(buffers), requestProfile='OWN_TEST_PROFILE',
        views=[dict(destinationTile=[9, 203, 207], owners=deepcopy(proof_rows)),
               dict(destinationTile=[10, 406, 414], owners=deepcopy(proof_rows))])
    return buffers, evidence


def invoke(buffers, evidence, **changes):
    raw = json.dumps(evidence, sort_keys=True).encode()
    kwargs = dict(policy=cull.POLICY, source_tile=evidence['sourceTile'],
        parent_pin=evidence['parentPin'], requested_views=((9, 203, 207), (10, 406, 414)),
        request_profile='OWN_TEST_PROFILE', evidence_bytes=raw, expected_evidence_sha256=sha(raw))
    kwargs.update(changes)
    return cull.prepare_finite_view_draw_cull(*buffers, **kwargs)


def test_default_is_byte_and_reference_identical_without_evidence():
    buffers, _ = fixture()
    result = cull.prepare_finite_view_draw_cull(*buffers)
    assert result['receipt']['reason'] == 'DEFAULT_OFF'
    assert all(result[key] is value for key, value in zip(cull.OUTPUTS, buffers))


def test_two_pointer_words_and_36_bytes_with_full_inverse():
    buffers, evidence = fixture()
    result = invoke(buffers, evidence)
    receipt = result['receipt']
    assert receipt['changedOwners'] == 2 and receipt['appendedRenderBytes'] == 36
    assert receipt['totalOwners'] == 3
    assert result['indexBytes'] is buffers[1] and result['coordinateBytes'] is buffers[2]
    assert result['additionalBytes'] is buffers[4]
    assert result['renderBytes'].startswith(buffers[3])
    inverse = bytearray(result['featureBytes'])
    for row in receipt['owners']:
        offset = row['recordOrdinal']*32+12
        inverse[offset:offset+4] = buffers[0][offset:offset+4]
        contexts, query = model.programmes(result['renderBytes'], row['newROOffset'])
        assert contexts == {'11': b'', '17': b''} and query == 100+row['recordOrdinal']
    assert bytes(inverse) == buffers[0]
    assert result['featureBytes'][64:] == buffers[0][64:]
    assert not receipt['globalSourceApplyAllowed'] and not receipt['appliedToFile']
    assert not receipt['nativePixelsQualified'] and not receipt['performanceClaim']


def test_extra_context_keys_order_and_payload_are_retained():
    buffers, evidence = fixture(extra=True)
    result = invoke(buffers, evidence)
    for row in result['receipt']['owners']:
        contexts, _ = model.programmes(result['renderBytes'], row['newROOffset'])
        assert list(contexts) == ['11', '17', '99']
        assert contexts['99'] == b'OWN_UNSELECTED();'


def test_unknown_programme_keeps_only_that_owner():
    buffers, evidence = fixture()
    evidence['views'][0]['owners'][0]['programmeFrameSHA256'] = sha(b'other-own-programme')
    result = invoke(buffers, evidence)
    assert [r['recordOrdinal'] for r in result['receipt']['owners']] == [1]
    assert result['featureBytes'][:32] == buffers[0][:32]
    assert result['receipt']['keptOwners'] == [dict(recordOrdinal=0, reason='UNRECOGNIZED_PROGRAMME')]


def test_every_requested_view_must_exclude_the_complete_owner():
    buffers, evidence = fixture()
    evidence['views'][1]['owners'][0]['sourceInkExcluded'] = False
    result = invoke(buffers, evidence)
    assert [r['recordOrdinal'] for r in result['receipt']['owners']] == [1]


def test_missing_owner_in_one_view_does_not_invent_exclusion():
    buffers, evidence = fixture()
    evidence['views'][1]['owners'].pop(0)
    result = invoke(buffers, evidence)
    assert [r['recordOrdinal'] for r in result['receipt']['owners']] == [1]


def test_unknown_view_and_profile_are_unchanged():
    buffers, evidence = fixture()
    for options, reason in [({'requested_views': ((11, 812, 828),)}, 'UNCONFIGURED_VIEW'),
                            ({'request_profile': 'OTHER_PROFILE'}, 'UNCONFIGURED_PROFILE')]:
        result = invoke(buffers, evidence, **options)
        assert result['receipt']['reason'] == reason
        assert all(result[key] is value for key, value in zip(cull.OUTPUTS, buffers))


def test_changed_record_is_kept_without_reclassifying():
    buffers, evidence = fixture()
    evidence['views'][0]['owners'][0]['recordSHA256'] = sha(b'another-own-record')
    result = invoke(buffers, evidence)
    assert [r['recordOrdinal'] for r in result['receipt']['owners']] == [1]


def test_equal_uids_do_not_deduplicate_distinct_records():
    buffers, evidence = fixture(duplicate_uid=True)
    result = invoke(buffers, evidence)
    assert len(result['featureBytes']) == len(buffers[0])
    assert result['receipt']['changedOwners'] == 2
    assert result['receipt']['noOwnerAddedRemovedOrDeduplicated']


def test_context_policies_must_agree_in_every_view():
    buffers, evidence = fixture()
    evidence['views'][1]['owners'][0]['emptyContextIDs'] = [17]
    result = invoke(buffers, evidence)
    assert [r['recordOrdinal'] for r in result['receipt']['owners']] == [1]


def test_input_parent_and_evidence_seals_refuse_mutation():
    buffers, evidence = fixture()
    with pytest.raises(ValueError, match='FIVE_COMPLETE_INPUT_BANK_PINS'):
        invoke(buffers[:2]+(buffers[2]+b'changed',)+buffers[3:], evidence)
    with pytest.raises(ValueError, match='EXACT_PARENT'):
        invoke(buffers, evidence, parent_pin=pin(b'other-own-parent'))
    with pytest.raises(ValueError, match='SEALED_FINITE'):
        invoke(buffers, evidence, expected_evidence_sha256=sha(b'other-own-evidence'))


def test_malformed_and_oversized_requests_refuse():
    buffers, evidence = fixture()
    with pytest.raises(ValueError, match='DISTINCT_VIEW'):
        invoke(buffers, evidence, requested_views=((9, 203, 207),)*2)
    with pytest.raises(ValueError, match='EXPLICIT_INTEGER_TILE'):
        invoke(buffers, evidence, requested_views=((True, 0, 0),))
    with pytest.raises(ValueError, match='FINITE_EXPLICIT_VIEW'):
        invoke(buffers, evidence, requested_views=((9, 203, 207),)*65)


def test_duplicate_owner_evidence_and_invalid_contexts_refuse():
    buffers, evidence = fixture()
    evidence['views'][0]['owners'].append(deepcopy(evidence['views'][0]['owners'][0]))
    with pytest.raises(ValueError, match='UNIQUE_VALID_RECORD'):
        invoke(buffers, evidence)
    buffers, evidence = fixture()
    for view in evidence['views']:
        view['owners'][0]['emptyContextIDs'] = [True]
    with pytest.raises(ValueError, match='EXPLICIT_DISTINCT_DRAW'):
        invoke(buffers, evidence)


def test_external_certificate_cannot_claim_qualification_with_wrong_schema():
    buffers, evidence = fixture()
    evidence['status'] = 'UNCHECKED'
    with pytest.raises(ValueError, match='QUALIFIED_EXTERNAL_INK'):
        invoke(buffers, evidence)


def test_wrong_policy_never_enables_the_transform():
    buffers, evidence = fixture()
    with pytest.raises(ValueError, match='EXPLICIT_KNOWN_FINITE'):
        invoke(buffers, evidence, policy='UNRECOGNIZED_POLICY')
