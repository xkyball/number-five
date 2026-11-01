import asyncio
import json
from types import SimpleNamespace
import struct
import time
import uuid
import numpy as np
import pytest
from fastapi.testclient import TestClient
from no5_sonard.config import ServiceConfig
from no5_sonard.pico_protocol import Packet, MessageType, PingFrame
from no5_sonard_openecho.service import HostService
from no5_sonard_openecho.clip_endurance import ClipProfile
from no5_sonard_openecho.transmit import ACK, EVENT
from no5_sonard_openecho.__main__ import create_host_app

def setup(root):
    profile=ClipProfile('0123456789ABCDEF','a'*64,())
    s=HostService(ServiceConfig(device='/no-device',storage_root=root,trigger_threshold=150,
        trigger_hysteresis=8,tx_marker_requested=True),transmit_profile=profile, archive_enabled=True)
    s.status.state='RUNNING'; tx=s.transmit
    c=SimpleNamespace(hello=SimpleNamespace(fw_major=0,fw_minor=7,fw_patch=6,
        capabilities=0xC00000EE,device_serial=profile.serial),
        accepted_config={'requested':{'reserved':1}},packet_seq_gaps=0,ping_id_gaps=0,
        missed_ping_queue=0,transport=SimpleNamespace(transmit_handler=None,crc_errors=0,frame_errors=0))
    commands=[]
    async def send(kind,payload):
        commands.append((int(kind),payload))
        tx.on_packet(Packet(MessageType.TX_ACK,1,0,ACK.pack(struct.unpack_from('<I',payload)[0],0,1,4,2000000)))
    c.send=send; tx.bind(c)
    return s,tx,c,commands


def test_arm_ack_can_arrive_after_recorded_one_second_usb_delay(tmp_path):
    async def run():
        _,tx,c,commands=setup(tmp_path)
        async def delayed(kind,payload):
            commands.append((int(kind),payload))
            request=struct.unpack_from('<I',payload)[0]
            asyncio.get_running_loop().call_later(1.2,tx.on_packet,
                Packet(MessageType.TX_ACK,1,0,ACK.pack(request,0,1,4,2000000)))
        c.send=delayed
        result=await tx._command(0x41,struct.pack('<II',4,2000000))
        assert result['result']==0 and len(commands)==1
    asyncio.run(run())


def test_lease_cadence_is_not_ack_latency_plus_another_half_second(tmp_path,monkeypatch):
    import no5_sonard_openecho.clip_endurance as clip
    async def run():
        _,tx,_,_=setup(tmp_path);now=[0.];sent=[]
        tx.mode='periodic';tx.run={'errors_at_start':tx._errors()}
        tx._lease_clock=lambda:now[0]
        async def sleep(delay):now[0]+=delay
        async def command(kind,body):
            assert kind==0x41 and struct.unpack('<II',body)==(4,2000000)
            sent.append(now[0]);now[0]+=1.29
            if len(sent)==3:tx.mode='off'
        monkeypatch.setattr(clip.asyncio,'sleep',sleep);tx._command=command
        await tx._renew()
        assert np.diff(sent)==pytest.approx([1.34,1.34])
        assert sent[0]==pytest.approx(.5)
    asyncio.run(run())

def test_profile_checks_bound_physical_receipts():
    from pathlib import Path
    p=Path(__file__).resolve().parents[1]/'deployment/clip-endurance-076.json'
    assert ClipProfile.load(p).serial=='0123456789ABCDEF'
    quiet=ClipProfile.load(p.with_name('clip-quiet-077.json'))
    assert quiet.quiet_window and quiet.firmware==(0,7,7)
    qualified=ClipProfile.load(p.with_name('clip-qualified-078.json'))
    assert qualified.quiet_window and qualified.firmware==(0,7,8)
    seven=ClipProfile.load(p.with_name('clip-qualified-079.json'))
    assert seven.quiet_window and seven.firmware==(0,7,9)
    headroom=ClipProfile.load(p.with_name('clip-qualified-0710.json'))
    assert headroom.quiet_window and headroom.firmware==(0,7,10)


def test_marker_quarantine_is_observable_and_not_a_physical_transmission(tmp_path):
    _,tx,_,_=setup(tmp_path)
    for detail in (5,6,10):
        tx.on_packet(Packet(MessageType.TX_EVENT,1,0,
                            EVENT.pack(8,detail,1360190,1000000,386208,(3<<32)|5)))
    d=tx.snapshot()['marker_reference']
    assert d['quarantines']==d['recoveries']==1 and not d['quarantined']
    assert d['skipped_slots']==1
    assert tx.output_starts==tx.physical_markers==tx.linked_pings==0


@pytest.mark.parametrize('useful,expected',[(24,True),(8,False)])
@pytest.mark.parametrize('patch',[7,8,9,10])
def test_quiet_profile_requires_observed_useful_echoes_not_just_transmissions(tmp_path,useful,expected,patch):
    from dataclasses import replace
    async def run():
        _,tx,c,commands=setup(tmp_path)
        tx.profile=replace(tx.profile,firmware=(0,7,patch),quiet_window=True)
        c.hello.fw_patch=patch;c.hello.capabilities=0xC0000FEE if patch==10 else 0xC00007EE if patch==9 else 0xC00003EE if patch==8 else 0xC00001EE
        await tx.update({'mode':'periodic','duration_s':2})
        assert tx.snapshot()['schedule']['maximum_slot_lateness_us'] == (4000 if patch==10 else 3000 if patch in (8,9) else 1000)
        config=next(payload for kind,payload in commands if kind==0x46)
        assert struct.unpack_from('<IIIIII',config,4)==(197000,1000,4,27000 if patch in (9,10) else 33000 if patch==8 else 40000,1000,2)
        for k in range(26):
            start=1000000+40000*k
            for event in (1,2,3):
                tx.on_packet(Packet(MessageType.TX_EVENT,1,0,EVENT.pack(event,0,100+k,start,start+3,0)))
            tx.on_ping(PingFrame(k+1,start+3,2000,0,40,40,1,np.full(8192,40,dtype=np.uint16)))
            tx.on_detection(SimpleNamespace(acquisition_source='own',valid_tx=True),
                {'quality':'LOCKED' if k<useful else 'SEARCHING','bottom':{} if k<useful else None})
            tx.on_packet(Packet(MessageType.TX_EVENT,1,0,EVENT.pack(6,0,100+k,start,start+3,k+1)))
        tx._first_host=time.monotonic()-2
        tx.on_packet(Packet(MessageType.TX_EVENT,1,0,EVENT.pack(7,0,125,start,start+3,0)))
        await tx._done_task
        s=tx.snapshot()['run']
        assert s['all_associated'] and s['usable_pings']==useful and s['usable_hz']==useful/2
        assert s['verified']==expected and tx.short_verified==expected
        # An unrelated native ping after completion must not rewrite the run.
        tx.on_ping(PingFrame(999,9000000,2000,0,40,40,1,np.full(8192,40,dtype=np.uint16)))
        assert tx.snapshot()['run']==s
    asyncio.run(run())

def test_long_run_requires_short_and_start_has_no_implicit_fire(tmp_path):
    async def run():
        _,tx,_,commands=setup(tmp_path)
        with pytest.raises(RuntimeError,match='short'): await tx.update({'mode':'periodic','duration_s':600})
        assert not commands
        await tx.update({'mode':'periodic','duration_s':2})
        assert [kind for kind,_ in commands]==[0x43,0x46,0x41]
        asyncio.get_running_loop().call_later(.02,lambda:setattr(tx,'_total',tx._total+1))
        key=str(uuid.uuid4()); first=await tx.fire({'request_id':key})
        assert first['command_accepted'] and not first['physical_tx_confirmed']
        assert await tx.fire({'request_id':key})==first
        assert sum(k==0x42 for k,_ in commands)==1
        await tx.abort()
    asyncio.run(run())

def test_matching_real_marker_and_ping_are_required_and_raw_unchanged(tmp_path):
    async def run():
        s,tx,_,commands=setup(tmp_path)
        await tx.update({'mode':'periodic','duration_s':2})
        samples=np.full(8192,40,dtype=np.uint16); samples[750:780]=200; before=samples.copy()
        tx._first_host=time.monotonic()-2
        for k in range(21):
            start=1000000+100000*k
            for event in (1,2,3):
                tx.on_packet(Packet(MessageType.TX_EVENT,1,0,EVENT.pack(event,0,100+k,start,start+3,0)))
            ping=PingFrame(k+1,start+4,2000,0,40,200,1,samples)
            tx.on_ping(ping)
            tx.on_packet(Packet(MessageType.TX_EVENT,1,0,EVENT.pack(6,0,100+k,start,start+3,k+1)))
        tx._first_host=time.monotonic()-2
        tx.on_packet(Packet(MessageType.TX_EVENT,1,0,EVENT.pack(7,0,120,start,start+3,0)))
        await tx._done_task
        assert tx.short_verified and tx.snapshot()['run']['verified']
        assert tx.snapshot()['run']['raw_ping_links']==21
        assert all(p['own'] for p in tx.profiles)
        np.testing.assert_array_equal(samples,before)
        await tx.update({'mode':'periodic','duration_s':600})
        assert tx.run['state']=='ARMED'
        await tx.abort()
    asyncio.run(run())

def test_api_rejects_invalid_duration_and_exposes_no_fake_samples(tmp_path):
    s,tx,_,_=setup(tmp_path); api=TestClient(create_host_app(s))
    assert api.put('/api/v1/transmit',json={'mode':'periodic','duration_s':True}).status_code==422
    assert api.put('/api/v1/transmit',json={'mode':'periodic','duration_s':601}).status_code==422
    assert api.get('/api/v1/transmit/live').json()['profiles']==[]
    assert api.get('/api/v1/transmit').json()['run'] is None


def test_run_finished_event_keeps_link_until_queued_raw_ping_arrives(tmp_path):
    _,tx,_,_=setup(tmp_path)
    start=1000000
    # Serial RX can handle the terminal event before the acquisition task
    # consumes the last PING. The terminal packet contains no ping ID.
    for event in (1,2,6,7):
        tx.on_packet(Packet(MessageType.TX_EVENT,1,0,
            EVENT.pack(event,0,123,start,start+3,42 if event==6 else 0)))
    assert tx._ledger[123]['ping_id']==42
    tx.on_ping(PingFrame(42,start+3,2000,0,40,40,1,np.full(8192,40,dtype=np.uint16)))
    assert tx.linked_pings==1
    tx.on_ping(PingFrame(42,start+3,2000,0,40,40,1,np.full(8192,40,dtype=np.uint16)))
    assert tx.linked_pings==1


def test_fast_durable_host_does_not_fire_while_peer_retires_passive_capture(tmp_path,monkeypatch):
    from no5_sonard_openecho.transmit import TransmitController
    async def run():
        _,tx,_,_=setup(tmp_path);tx.run={'state':'ARMED'};tx.mode='periodic'
        peer_ready=asyncio.Event();fires=[]
        async def fire(self,payload):
            fires.append(payload)
            if not peer_ready.is_set():raise RuntimeError('Pico TX: busy')
            return {'command_accepted':True}
        monkeypatch.setattr(TransmitController,'fire',fire)
        task=asyncio.create_task(tx.fire({'request_id':str(uuid.uuid4())}))
        await asyncio.sleep(.005);tx._total+=1
        asyncio.get_running_loop().call_later(.005,peer_ready.set)
        assert (await task)['command_accepted'] and len(fires)==1
    asyncio.run(run())


def test_operator_abort_during_passive_drain_guard_never_sends_fire(tmp_path,monkeypatch):
    from no5_sonard_openecho.transmit import TransmitController
    async def run():
        _,tx,_,_=setup(tmp_path);tx.run={'state':'ARMED'};tx.mode='periodic';fires=[]
        async def fire(self,payload):fires.append(payload);return {'command_accepted':True}
        monkeypatch.setattr(TransmitController,'fire',fire)
        task=asyncio.create_task(tx.fire({'request_id':str(uuid.uuid4())}))
        await asyncio.sleep(.005);tx._total+=1;await asyncio.sleep(.005)
        tx.mode='off';tx.run['state']='ABORTED'
        with pytest.raises(RuntimeError,match='aborted'):await task
        assert not fires
    asyncio.run(run())
