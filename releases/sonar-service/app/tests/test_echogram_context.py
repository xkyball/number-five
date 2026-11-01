import asyncio
import copy
import base64
import hashlib
import json
from pathlib import Path
import struct
from types import SimpleNamespace

import numpy as np
import pytest
from fastapi.testclient import TestClient
from starlette.websockets import WebSocketDisconnect

from no5_sonard.pico_protocol import MessageType, Packet, PingFrame
from no5_sonard.protocol import unpack_echogram_frame
from no5_sonard_openecho.__main__ import create_host_app
from no5_sonard_openecho.echogram_context import (
    MAGIC, PREFIX, MAX_ENVELOPE_BYTES, MAX_METADATA_BYTES, MAX_M1_BYTES,
    UINT64_MAX, pack_context, unpack_context,
)
from no5_sonard_openecho.transmit import EVENT
from test_display_selection import make_service


def reference(index=1):
    import base64
    fixture = json.loads((Path(__file__).parent/'fixtures/stripe-interface-0415.json').read_text())
    case = fixture['cases'][index]
    return base64.b64decode(case['frame_base64']), copy.deepcopy(case['published_ping'])


def encoded_metadata(metadata, frame):
    data=json.dumps(metadata,ensure_ascii=False,separators=(',',':')).encode('utf-8')
    return PREFIX.pack(MAGIC,1,0,len(data),len(frame))+data+frame


@pytest.mark.parametrize('index',[0,1,2])
def test_original_m1_and_complete_http_row_remain_exact(index):
    frame,row=reference(index);original=copy.deepcopy(row)
    envelope=pack_context(frame,row,selected_source='own',publication_sequence=1)
    meta,decoded=unpack_context(envelope)
    assert decoded==frame and row==original
    expected=copy.deepcopy(row)
    for name in ['ping_id','time_utc_ns']:expected[name]=str(expected[name])
    expected['display_window']['ping_id']=str(expected['display_window']['ping_id'])
    assert meta['published_ping']==expected
    assert meta['publication_sequence']=='1' and meta['selected_source']=='own'
    assert len(envelope)<=MAX_ENVELOPE_BYTES==6200
    assert PREFIX.size==16 and MAX_METADATA_BYTES==4096 and MAX_M1_BYTES==2088


@pytest.mark.parametrize('pid,stamp,sequence',[
    (0,0,1),(2**53+1,1792170000000000123,2**53+1),
    (UINT64_MAX,2**63-1,UINT64_MAX),(UINT64_MAX,-2**63,1),
])
def test_all_uint64_and_signed_utc_bits_survive_without_float(pid,stamp,sequence):
    frame,row=reference();frame=bytearray(frame)
    struct.pack_into('<Qq',frame,4,pid,stamp);frame=bytes(frame)
    row['ping_id']=row['display_window']['ping_id']=pid;row['time_utc_ns']=stamp
    meta,decoded=unpack_context(pack_context(frame,row,selected_source='own',publication_sequence=sequence))
    assert meta['published_ping']['ping_id']==str(pid)
    assert meta['published_ping']['time_utc_ns']==str(stamp)
    assert meta['publication_sequence']==str(sequence)
    assert unpack_echogram_frame(decoded)[0].time_utc_ns==stamp


@pytest.mark.parametrize('source,evidence,selected',[
    ('own','physical_own_marker_raw_match','own'),
    ('native','qualified_native_marker_raw_match','native'),
    (None,'unconfirmed','all'),
])
def test_source_evidence_is_preserved_including_unknown(source,evidence,selected):
    frame,row=reference(1 if source=='own' else 0)
    row['actual_source']=source;row['source_evidence']=evidence
    meta,_=unpack_context(pack_context(frame,row,selected_source=selected,publication_sequence=1))
    assert meta['published_ping']['actual_source']==source
    assert meta['published_ping']['source_evidence']==evidence


@pytest.mark.parametrize('source,evidence',[
    ('native','qualified_native_marker_raw_match'),(None,'unconfirmed')])
def test_cropped_context_requires_actual_own_even_if_time_reference_is_valid(source,evidence):
    frame,row=reference(1);row.update(actual_source=source,source_evidence=evidence)
    with pytest.raises(ValueError,match='crop requires confirmed own'):
        pack_context(frame,row,selected_source='all',publication_sequence=1)


def test_native_evidence_requires_valid_tx_and_m1_flag_must_agree():
    frame,row=reference(2)  # Actual own marker, invalid time reference, M1 flag 0 clear.
    row['actual_source']='native';row['source_evidence']='qualified_native_marker_raw_match'
    with pytest.raises(ValueError,match='native.*valid TX'):
        pack_context(frame,row,selected_source='native',publication_sequence=1)
    for index in [1,2]:
        frame,row=reference(index);changed=bytearray(frame)
        flags=struct.unpack_from('<I',changed,32)[0];struct.pack_into('<I',changed,32,flags^1)
        with pytest.raises(ValueError,match='M1 flag bit 0'):
            pack_context(bytes(changed),row,selected_source='own',publication_sequence=1)


def test_utf8_length_counts_bytes_and_rejects_invalid_utf8_or_surrogates():
    frame,row=reference();row['display_window']['reason']='Messfenster · 🐟'
    envelope=pack_context(frame,row,selected_source='own',publication_sequence=1)
    _,_,_,length,_=PREFIX.unpack_from(envelope)
    metadata=envelope[16:16+length]
    assert len(metadata)>len(metadata.decode('utf-8'))
    assert unpack_context(envelope)[0]['published_ping']['display_window']['reason']=='Messfenster · 🐟'
    with pytest.raises(ValueError):unpack_context(envelope[:16]+b'\xff'+envelope[17:])
    row['display_window']['reason']='\ud800'
    with pytest.raises(ValueError):pack_context(frame,row,selected_source='own',publication_sequence=1)


@pytest.mark.parametrize('offset,fmt,value',[
    (0,'4s',b'NOPE'),(4,'H',0),(4,'H',2),(6,'H',1),(6,'H',65535),
    (8,'I',0),(8,'I',4097),(12,'I',0),(12,'I',2089),
])
def test_invalid_prefix_versions_flags_and_bounds_are_rejected(offset,fmt,value):
    frame,row=reference();data=bytearray(pack_context(frame,row,selected_source='own',publication_sequence=1))
    struct.pack_into('<'+fmt,data,offset,value)
    with pytest.raises(ValueError):unpack_context(bytes(data))


@pytest.mark.parametrize('suffix',[b'',b'x'])
def test_no_truncated_or_trailing_envelope(suffix):
    frame,row=reference();data=pack_context(frame,row,selected_source='own',publication_sequence=1)
    with pytest.raises(ValueError):unpack_context(data[:-1] if not suffix else data+suffix)


@pytest.mark.parametrize('value',['01','+1',' 1','1.0','1e0','-0','-1',1,True,str(2**64)])
def test_noncanonical_sequence_is_never_coerced(value):
    frame,row=reference();meta,_=unpack_context(pack_context(frame,row,selected_source='own',publication_sequence=1))
    meta['publication_sequence']=value
    with pytest.raises(ValueError):unpack_context(encoded_metadata(meta,frame))


@pytest.mark.parametrize('field,value',[
    ('ping_id','09'),('ping_id',9),('time_utc_ns','-0'),('time_utc_ns',1792170000000000123),
    ('time_utc_ns',str(2**63)),('time_utc_ns',str(-2**63-1)),
    ('display_generation',True),('noise_revision',2**32),('sample_count',16385),
    ('actual_source','bad'),('source_evidence','command_ack'),('valid_tx_reference',1),
    ('session_id','NOT-A-UUID'),('session_id','FFFFFFFF-FFFF-4FFF-8FFF-FFFFFFFFFFFF'),
])
def test_invalid_integer_uuid_and_evidence_contract(field,value):
    frame,row=reference();meta,_=unpack_context(pack_context(frame,row,selected_source='own',publication_sequence=1))
    meta['published_ping'][field]=value
    with pytest.raises(ValueError):unpack_context(encoded_metadata(meta,frame))


def test_mismatched_header_window_source_and_extra_fields_are_rejected():
    frame,row=reference();meta,_=unpack_context(pack_context(frame,row,selected_source='own',publication_sequence=1))
    mutations=[lambda m:m.update(selected_source='native'),
        lambda m:m.update(extra=1),lambda m:m['published_ping'].update(extra=1),
        lambda m:m['published_ping'].update(ping_id='123'),
        lambda m:m['published_ping']['display_window'].update(ping_id='123'),
        lambda m:m['published_ping']['display_window'].update(tx_reference_verified=False),
        lambda m:m['published_ping']['display_window'].update(display_sample_count=1),
        lambda m:m['published_ping']['display_window'].update(display_range_m=1.5),
        lambda m:m['published_ping']['display_window'].update(capture_range_m=10**400),
        lambda m:m['published_ping']['display_window'].update(raw_samples_modified=True)]
    for mutate in mutations:
        bad=copy.deepcopy(meta);mutate(bad)
        with pytest.raises(ValueError):unpack_context(encoded_metadata(bad,frame))
    for offset,fmt,value in [(0,'B',2),(1,'B',2),(2,'H',42),(38,'H',1)]:
        bad=bytearray(frame);struct.pack_into('<'+fmt,bad,offset,value)
        with pytest.raises(ValueError):unpack_context(encoded_metadata(meta,bytes(bad)))


def test_strict_json_duplicate_nan_and_metadata_limit():
    frame,row=reference();data=pack_context(frame,row,selected_source='own',publication_sequence=1)
    _,_,_,n,_=PREFIX.unpack_from(data);raw=data[16:16+n]
    nonfinite=json.loads(raw);nonfinite['published_ping']['noise_revision']=float('nan')
    for bad in [b'[]',b'not-json',raw.replace(b'"schema":',b'"schema":null,"schema":',1),
                json.dumps(nonfinite).encode(),b'['*1500+b'0'+b']'*1500,b' '*4097]:
        envelope=PREFIX.pack(MAGIC,1,0,len(bad),len(frame))+bad+frame
        with pytest.raises(ValueError):unpack_context(envelope)
    padded=raw+b' '*(4096-len(raw))
    maximum=PREFIX.pack(MAGIC,1,0,4096,len(frame))+padded+frame
    assert len(maximum)==6200 and unpack_context(maximum)[1]==frame


def test_minimum_and_unsupported_m1_bin_counts():
    frame,row=reference();small=bytearray(frame[:42]);struct.pack_into('<H',small,36,1)
    assert len(unpack_context(pack_context(bytes(small),row,
        selected_source='own',publication_sequence=1))[1])==42
    for bins in [0,1025]:
        bad=bytearray(frame[:40]+b'\0\0'*bins);struct.pack_into('<H',bad,36,bins)
        with pytest.raises(ValueError):pack_context(bytes(bad),row,selected_source='own',publication_sequence=1)


def client():
    return SimpleNamespace(clock=SimpleNamespace(ready=True,
        pico_us_to_utc_ns=lambda us:1792170000000000123+us*1000),
        hello=SimpleNamespace(device_serial='0123456789ABCDEF'))


async def emit(service,pid,*,own=True,trigger=None):
    trigger=trigger or pid*100000
    if own:
        for event in [1,2]:
            service.transmit.on_packet(Packet(MessageType.TX_EVENT,1,0,
                EVENT.pack(event,0,100+pid,trigger-3,trigger,0)))
    raw=np.full(8192,40,dtype=np.uint16);raw[750:780]=200
    before=raw.tobytes()
    await service._handle_ping(PingFrame(pid,trigger,2000,0,40,200,1,raw),client())
    assert raw.tobytes()==before


def test_slow_context_reader_drops_whole_pairs_without_affecting_fast_legacy(tmp_path):
    async def run():
        s=make_service(tmp_path,'own');s._ensure_session();s._open_segment('0123456789ABCDEF',8192,2000)
        old=await s.hub.subscribe();slow=await s.context_hub.subscribe();seen={}
        for pid in range(1,10):
            await emit(s,pid,own=pid!=3)
            if pid!=3:seen[pid]=old.get_nowait()
        assert list(seen)==[1,2,4,5,6,7,8,9] and slow.qsize()==slow.maxsize==4
        sequences=[]
        while not slow.empty():
            meta,frame=unpack_context(slow.get_nowait());header,_=unpack_echogram_frame(frame)
            assert str(header.ping_id)==meta['published_ping']['ping_id']
            assert frame==seen[header.ping_id]
            sequences.append(meta['publication_sequence'])
        assert sequences==['5','6','7','8'] and s._publication_sequence==8
        assert not s._context_encode_errors
        archive=s._writer.path;s._writer.close()
        import prototype as proto
        assert list(proto.read_zarr_array(archive,'ping_id'))==list(range(1,10))
    asyncio.run(run())


@pytest.mark.parametrize('new_session',[False,True])
def test_context_frozen_before_await_and_new_session_segment_may_reuse_ping_id(tmp_path,new_session):
    async def run():
        s=make_service(tmp_path);s._ensure_session();s._open_segment('0123456789ABCDEF',8192,2000)
        queue=await s.context_hub.subscribe();entered=asyncio.Event();release=asyncio.Event()
        old_session=s.status.session_id;old_segment=s.status.segment_id
        original=s.hub.publish
        async def paused(frame):entered.set();await release.wait();await original(frame)
        s.hub.publish=paused
        task=asyncio.create_task(emit(s,1,own=False));await entered.wait()
        s.set_display_source('native');s.set_noise_reduction_mode('off')
        s._writer.close()
        if new_session:s.status.session_id=None;s._ensure_session()
        s._open_segment('0123456789ABCDEF',8192,2000)
        assert s._publication_sequence==0
        release.set();await task;s.hub.publish=original
        meta,_=unpack_context(queue.get_nowait())
        assert meta['selected_source']=='all' and meta['publication_sequence']=='1'
        assert meta['published_ping']['session_id']==old_session
        assert meta['published_ping']['segment_id']==old_segment
        await emit(s,1,own=False,trigger=900000)
        new,_=unpack_context(queue.get_nowait())
        assert new['publication_sequence']=='1' and new['published_ping']['ping_id']=='1'
        assert (new['published_ping']['session_id']!=old_session)==new_session
        assert new['published_ping']['segment_id']!=old_segment
        assert new['selected_source']=='native'
        s._writer.close()
    asyncio.run(run())


def test_source_and_noise_switches_keep_sequence_and_existing_http_row_types(tmp_path):
    async def run():
        s=make_service(tmp_path,'own');s._ensure_session();s._open_segment('0123456789ABCDEF',8192,2000)
        q=await s.context_hub.subscribe()
        await emit(s,1);a,_=unpack_context(q.get_nowait())
        s.set_display_source('all');s.set_noise_reduction_mode('off')
        await emit(s,2,own=False);b,_=unpack_context(q.get_nowait())
        assert [a['publication_sequence'],b['publication_sequence']]==['1','2']
        assert [a['selected_source'],b['selected_source']]==['own','all']
        assert b['published_ping']['selection_generation']>a['published_ping']['selection_generation']
        http=TestClient(create_host_app(s)).get('/api/v1/sonar/published-pings').json()
        assert type(http['pings'][-1]['ping_id']) is int
        assert type(http['pings'][-1]['time_utc_ns']) is int
        assert type(http['pings'][-1]['display_window']['ping_id']) is int
        assert 'publication_sequence' not in http['pings'][-1] and 'selected_source' not in http['pings'][-1]
        s._writer.close()
    asyncio.run(run())


def test_silent_context_disconnect_unsubscribes_without_starting_acquisition(tmp_path):
    async def run():
        from no5_sonard_openecho.display_socket import serve_display
        s=make_service(tmp_path);messages=asyncio.Queue();accepted=asyncio.Event()
        async def accept():accepted.set()
        async def send(value):raise AssertionError('no ping was published')
        socket=SimpleNamespace(accept=accept,receive=messages.get,send_bytes=send)
        task=asyncio.create_task(serve_display(socket,s.context_hub));await accepted.wait()
        assert s.context_hub.client_count==1 and s.hub.client_count==0
        await messages.put({'type':'websocket.disconnect'});await asyncio.wait_for(task,.5)
        assert s.context_hub.client_count==0 and s._client is None and s._task is None
    asyncio.run(run())


def test_codec_failure_or_sequence_overflow_never_breaks_legacy_or_raw(tmp_path,monkeypatch):
    async def run():
        s=make_service(tmp_path);s._ensure_session();s._open_segment('0123456789ABCDEF',8192,2000)
        legacy=await s.hub.subscribe();context=await s.context_hub.subscribe()
        import no5_sonard_openecho.service as module
        original=module.pack_context
        def broken(*args,**kwargs):raise ValueError('test failure')
        monkeypatch.setattr(module,'pack_context',broken)
        await emit(s,1);assert legacy.get_nowait() and context.empty()
        assert s.published_pings()['pings'][-1]['ping_id']==1 and s._context_encode_errors==1
        monkeypatch.setattr(module,'pack_context',original)
        await emit(s,2);assert legacy.get_nowait()
        assert unpack_context(context.get_nowait())[0]['publication_sequence']=='2'
        s._publication_sequence=UINT64_MAX;await emit(s,3)
        assert legacy.get_nowait() and context.empty() and s._context_encode_errors==2
        assert s._publication_sequence==UINT64_MAX+1
        s._writer.close()
    asyncio.run(run())


def test_new_route_is_read_only_and_legacy_route_still_returns_original_m1(tmp_path):
    frame,row=reference();envelope=pack_context(frame,row,selected_source='own',publication_sequence=1)
    s=make_service(tmp_path)
    class Hub:
        def __init__(self,value):self.value=value;self.clients=0
        async def subscribe(self):
            self.clients+=1;q=asyncio.Queue();q.put_nowait(self.value);return q
        async def unsubscribe(self,q):self.clients-=1
    old=Hub(frame);new=Hub(envelope);s.hub=old;s.context_hub=new
    api=TestClient(create_host_app(s))
    for path,expected in [('/ws/v1/echogram',frame),('/ws/v1/echogram-context',envelope)]:
        with api.websocket_connect(path) as socket:
            assert socket.receive_bytes()==expected
            socket.send_text('ARM')
            with pytest.raises(WebSocketDisconnect) as exc:socket.receive_bytes()
            assert exc.value.code==1003
    assert old.clients==new.clients==0 and s._client is None and s._task is None


@pytest.mark.parametrize('index',range(7))
def test_public_codec_fixture_hashes_and_exact_payload(index):
    fixture=json.loads((Path(__file__).parent/'fixtures/echogram-context-0416.json').read_text())
    case=fixture['cases'][index];envelope=base64.b64decode(case['envelope_base64'])
    metadata,frame=unpack_context(envelope)
    assert metadata==case['metadata'] and hashlib.sha256(envelope).hexdigest()==case['envelope_sha256']
    assert hashlib.sha256(frame).hexdigest()==case['m1_sha256']
    if case['name']=='confirmed_native':
        row=metadata['published_ping']
        assert row['display_window']['display_end_sample_exclusive']==row['sample_count']
        assert frame==reference(0)[0]
    if case['m1_byte_identical_to_retained_0415'] is not None:
        assert frame==reference(case['m1_byte_identical_to_retained_0415'])[0]


def test_public_codec_negative_vectors_and_document_hash():
    root=Path(__file__).resolve().parents[1]
    fixture=json.loads((root/'tests/fixtures/echogram-context-0416.json').read_text())
    assert hashlib.sha256((root/'docs/INTERFACE_ECHOGRAM_CONTEXT_0416.md').read_bytes()).hexdigest()==fixture['wire_document_sha256']
    cases={c['name']:c for c in fixture['cases']}
    for vector in fixture['negative_prefix_vectors']:
        encoded=bytearray(base64.b64decode(cases[vector['base_case']]['envelope_base64']))
        value=bytes.fromhex(vector['replacement_hex']);offset=vector['offset']
        encoded[offset:offset+len(value)]=value
        with pytest.raises(ValueError):unpack_context(bytes(encoded))
