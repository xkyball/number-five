"""Fresh empty contexts keep their exact feature/query/profile bindings."""
import hashlib
import struct

import pytest

from no_chart_converter.country_runtime import native_hooks as hooks
from no_chart_converter.country_runtime import render_activation as activation
from no_chart_converter.country_runtime import source_presentation_resolver as resolver
from no_chart_converter.country_runtime.localize_metadata import Stream
from no_chart_converter.country_runtime.point_packets import regular_attrs


def digest(raw):
    return hashlib.sha256(raw).hexdigest()


def invented_point(paper, simple):
    uid = (1 << 32) | 7
    query = (struct.pack('<QHH', uid, 1, 116) + b'Invented point\0' +
             struct.pack('<HH', 0, 0))
    entries = {196608: paper, 196609: simple, 65682: struct.pack('<dd', .25, .75)}
    render = activation.encode_frame(0, entries)
    frames = activation.decode_frames(render, {0})
    record = hooks.F.pack(uid, 0, 0, 0, 3, 74, 1, 0, 0, 0)
    owner = {'uid': uid, 'record': record, 'cellId': 'INVENTED',
             'sourceOwnerOrdinal': 0, 'classAcronym': 'TOPMAR',
             'sourceBinding': {'featureSourceDigest': digest(b'invented feature')}}
    selected = {str(key): {'sourceRCID': 9, 'displayPriority': 4,
                          'category': 'STANDARD'} for key in (196608, 196609)}
    row = {'uid': uid, 'cellId': owner['cellId'], 'sourceOwnerOrdinal': 0,
           'className': owner['classAcronym'], 'featureSourceDigest':
           owner['sourceBinding']['featureSourceDigest'],
           'resolution': {'contextSelections': selected},
           'additionalFrameSHA256': digest(query),
           'attributeRowsSHA256': resolver.attribute_rows_sha256(regular_attrs(query)),
           'profileSHA256': digest(b'invented profile'),
           'programmes': {str(key): entries[key].hex() for key in (196608, 196609)},
           'programmeSHA256': {str(key): digest(entries[key]) for key in (196608, 196609)}}
    tables = {'pointProgramBindings': [row], 'profileSHA256': row['profileSHA256']}
    stream = Stream(len(query), lambda at, count: query[at:at + count])
    return {'point_owners': [owner]}, tables, frames, stream, render


@pytest.fixture(autouse=True)
def clean_hooks():
    hooks.configure(None)
    yield
    hooks.configure(None)


@pytest.mark.parametrize('paper,simple', [
    (b'SY(FAKE0001);', b''), (b'', b'SY(FAKE0002);'), (b'', b'')])
def test_exact_explicit_empty_contexts_remain_queryable_and_unchanged(paper, simple):
    ns, tables, frames, stream, render = invented_point(paper, simple)
    record = ns['point_owners'][0]['record']
    receipts = hooks.activate_fresh_cache_points(ns, tables, frames, stream)
    assert len(receipts) == 1
    assert receipts[0]['programmeSHA256'] == {
        '196608': digest(paper), '196609': digest(simple)}
    assert receipts[0]['querySHA256'] == tables['pointProgramBindings'][0]['additionalFrameSHA256']
    assert ns['point_owners'][0]['record'] == record
    assert activation.encode_frame(0, frames[0].entries) == render
    assert hooks.FRAME_CACHE[0].entries[65682] == struct.pack('<dd', .25, .75)
    assert ns['point_owners'][0]['freshPointVisibilityProfiles']['TOPMAR']
    assert receipts[0]['savedOriginalOwnerPresentationClaim'] is False


@pytest.mark.parametrize('mutation,reason', [
    ('hash', 'FRESH_POINT_PROGRAMME_EXACT_CONTEXT_BYTES'),
    ('declared', 'FRESH_POINT_PROGRAMME_EXACT_CONTEXT_BYTES'),
    ('frame', 'FRESH_POINT_PROGRAMME_EXACT_CONTEXT_BYTES'),
    ('missing-context', 'FRESH_POINT_PROGRAMME_EXACT_CONTEXT_BYTES'),
    ('query', 'FRESH_POINT_PROGRAMME_QUERY_EXACT'),
    ('attributes', 'FRESH_POINT_PROGRAMME_TYPED_ATTRIBUTE_BINDING'),
    ('profile', 'FRESH_POINT_PROFILE_BINDING'),
    ('feature', 'FRESH_POINT_PROGRAMME_EXACT_IR_FEATURE'),
    ('owner', 'FRESH_POINT_PROGRAMME_SOURCE_OWNER_JOIN'),
    ('duplicate', 'FRESH_POINT_PROGRAMME_COMPLETE_OWNER_BIJECTION')])
def test_empty_programmes_do_not_relax_other_exact_bindings(mutation, reason):
    ns, tables, frames, stream, _ = invented_point(b'', b'')
    row = tables['pointProgramBindings'][0]
    if mutation == 'hash':
        row['programmeSHA256']['196609'] = digest(b'different')
    elif mutation == 'declared':
        row['programmes']['196609'] = b'SY(FAKE0001);'.hex()
        row['programmeSHA256']['196609'] = digest(b'SY(FAKE0001);')
    elif mutation in ('frame', 'missing-context'):
        entries = dict(frames[0].entries)
        if mutation == 'frame':
            entries[196609] = b'SY(FAKE0002);'
        else:
            del entries[196609]
        frames = activation.decode_frames(activation.encode_frame(0, entries), {0})
    elif mutation == 'query':
        row['additionalFrameSHA256'] = digest(b'other query')
    elif mutation == 'attributes':
        row['attributeRowsSHA256'] = digest(b'other attributes')
    elif mutation == 'profile':
        row['profileSHA256'] = digest(b'other profile')
    elif mutation == 'feature':
        row['featureSourceDigest'] = digest(b'other feature')
    elif mutation == 'owner':
        row['cellId'] = 'OTHER'
    else:
        tables['pointProgramBindings'].append(dict(row))
    with pytest.raises(ValueError, match=reason):
        hooks.activate_fresh_cache_points(ns, tables, frames, stream)
