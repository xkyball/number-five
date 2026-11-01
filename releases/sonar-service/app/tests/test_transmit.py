import asyncio
from dataclasses import replace
import hashlib
import json
from pathlib import Path
import struct
from types import SimpleNamespace
import uuid
import numpy as np
import pytest
from fastapi.testclient import TestClient
from no5_sonard.config import ServiceConfig
from no5_sonard.pico_protocol import MessageType, Packet, PingFrame, Hello, pack_hello, pack_ping_frame, decode_packet, encode_packet
from no5_sonard_openecho.__main__ import create_host_app
from no5_sonard_openecho.service import HostService
from no5_sonard_openecho.transmit import ACK, EVENT, TransmitController, TransmitProfile
import prototype as proto

SERIAL='TXPTY-'+'0'*9+'1'

def profile():
    return TransmitProfile(SERIAL,'pico_loopback',197000,2500,8,200000,100000,'f'*64,())

def service(root, qualified=True):
    return HostService(ServiceConfig(device='/no-physical-device',storage_root=root,trigger_threshold=150,
        trigger_hysteresis=8,tx_marker_requested=True),transmit_profile=profile() if qualified else None, archive_enabled=True)

class FakeClient:
    def __init__(self, controller):
        self.transport=SimpleNamespace(transmit_handler=None)
        self.hello=SimpleNamespace(capabilities=0xC000006E,device_serial=SERIAL)
        self.accepted_config={'requested':{'reserved':1}}
        self.commands=[]; self.controller=controller
    async def send(self,kind,payload):
        # Actual wheel framing must round-trip every additive command.
        packet=decode_packet(encode_packet(Packet(kind,3,0,payload))[:-1])
        assert packet.payload==payload
        self.commands.append(int(kind))
        request=struct.unpack_from('<I',payload)[0]
        self.controller.on_packet(Packet(MessageType.TX_ACK,4,0,ACK.pack(request,0,1,1,2000000)))

def test_no_profile_api_validation_and_default_off(tmp_path):
    host=service(tmp_path,False); client=TestClient(create_host_app(host))
    assert client.get('/api/v1/transmit').json()['mode']=='off'
    amplitude=host.snapshot()['acquisition']['amplitude']
    assert amplitude['significant_bits']==12 and amplitude['fullscale_code']==4095
    assert amplitude['raw_samples_modified'] is False
    assert client.put('/api/v1/transmit',json={'mode':'manual'}).status_code==409
    assert client.put('/api/v1/transmit',json={'mode':'periodic','cycles':True}).status_code==422
    assert client.post('/api/v1/transmit/fire',json={'request_id':'bad'}).status_code==422
    assert client.post('/api/v1/transmit/abort').json()['mode']=='off'

def test_manual_uuid_dedup_is_durable_and_ack_is_not_marker(tmp_path):
    async def run():
        host=service(tmp_path);host.status.state='RUNNING'; c=FakeClient(host.transmit);host.transmit.bind(c)
        await host.transmit.update({'mode':'manual'})
        key=str(uuid.uuid4());first=await host.transmit.fire({'request_id':key})
        assert first['command_accepted'] and not first['physical_tx_confirmed']
        assert await host.transmit.fire({'request_id':key})==first
        assert c.commands.count(0x42)==1
        await host.transmit.abort()
        restored=TransmitController(host,profile())
        assert await restored.fire({'request_id':key})==first
        assert c.commands.count(0x42)==1
    asyncio.run(run())

def test_physical_event_and_raw_ping_must_agree(tmp_path):
    host=service(tmp_path); tx=host.transmit
    samples=np.arange(32,dtype=np.uint16)
    original=samples.copy()
    tx.on_packet(Packet(MessageType.TX_EVENT,1,0,EVENT.pack(1,0,9,1000,0,0)))
    assert not tx.snapshot()['last_transmissions'][0]['physical_tx_confirmed']
    tx.on_packet(Packet(MessageType.TX_EVENT,2,0,EVENT.pack(2,0,9,1000,1002,0)))
    tx.on_packet(Packet(MessageType.TX_EVENT,3,0,EVENT.pack(6,0,9,1000,1002,7)))
    assert tx.linked_pings==0
    tx.on_ping(PingFrame(7,1002,2000,0,0,31,1,samples))
    assert tx.linked_pings==1
    np.testing.assert_array_equal(samples,original)
    tx.on_packet(Packet(MessageType.TX_EVENT,4,0,EVENT.pack(2,0,10,2000,2002,0)))
    tx.on_ping(PingFrame(8,2500,2000,0,0,31,1,samples))
    tx.on_packet(Packet(MessageType.TX_EVENT,5,0,EVENT.pack(6,0,10,2000,2002,8)))
    assert tx.linked_pings==1 and tx.mode=='off' and 'mismatch' in tx.reason

@pytest.mark.parametrize('policy',[{'cycles':9},{'high_ns':2501},{'period_us':199999},
                                 {'cycles':8,'high_ns':2500,'period_us':200000,'frequency_hz':196000},
                                 {'cycles':8,'high_ns':2500,'period_us':200000,'marker_timeout_us':0}])
def test_qualified_limits_cannot_be_bypassed(tmp_path,policy):
    async def run():
        host=service(tmp_path);host.status.state='RUNNING';c=FakeClient(host.transmit);host.transmit.bind(c)
        with pytest.raises(ValueError): await host.transmit.update({'mode':'periodic',**policy})
        assert c.commands==[]
    asyncio.run(run())

def test_profile_requires_measurement_receipts_and_hashes(tmp_path):
    receipt=tmp_path/'receipt.json'; receipt.write_text(json.dumps({'schema':'no5.hardware-check/1','pico_serial':SERIAL,'checks':{check:{'passed':True,'basis':'operator_measurement','fixture_only':True} for check in ['setup_isolated','output_idle']}}))
    row={'schema':'no5.transmit-profile/1','qualified':True,'serial':SERIAL,'kind':'pico_loopback',
         'frequency_hz':197000,'max_high_ns':1000,'max_cycles':8,'min_period_us':200000,'max_high_ns_per_second':40000,
         'evidence':[{'check':check,'path':receipt.name,'sha256':hashlib.sha256(receipt.read_bytes()).hexdigest()}
                     for check in ['setup_isolated','output_idle']]}
    path=tmp_path/'profile.json';path.write_text(json.dumps(row)); assert TransmitProfile.load(path).serial==SERIAL
    receipt.write_text('changed')
    with pytest.raises(ValueError,match='hash'): TransmitProfile.load(path)
    row['qualified']=False;path.write_text(json.dumps(row))
    with pytest.raises(ValueError,match='not qualified'): TransmitProfile.load(path)

def test_arm_failure_leaves_host_off_and_attempts_abort(tmp_path):
    async def run():
        host=service(tmp_path);host.status.state='RUNNING'
        class FailedArm(FakeClient):
            async def send(self,kind,payload):
                if int(kind)==0x41: raise ConnectionError('lost ARM acknowledgement')
                await super().send(kind,payload)
        c=FailedArm(host.transmit);host.transmit.bind(c)
        with pytest.raises(ConnectionError):await host.transmit.update({'mode':'periodic'})
        assert host.transmit.mode=='off' and host.transmit._renewal is None
        assert c.commands.count(0x43)==2
    asyncio.run(run())

def test_raw_link_mismatch_aborts_an_active_device(tmp_path):
    async def run():
        host=service(tmp_path);host.status.state='RUNNING';c=FakeClient(host.transmit);host.transmit.bind(c)
        await host.transmit.update({'mode':'manual'})
        tx=host.transmit
        tx.on_packet(Packet(MessageType.TX_EVENT,1,0,EVENT.pack(2,0,9,1000,1002,0)))
        tx.on_ping(PingFrame(7,2000,2000,0,0,31,1,np.arange(32,dtype=np.uint16)))
        tx.on_packet(Packet(MessageType.TX_EVENT,2,0,EVENT.pack(6,0,9,1000,1002,7)))
        await asyncio.sleep(.01)
        assert tx.mode=='off' and c.commands[-1]==0x43 and tx._renewal is None
    asyncio.run(run())

def test_venterior_profile_cannot_claim_more_than_native_measurement(tmp_path):
    checks={k:{'passed':True,'basis':'physical_capture'} for k in ['q13_role','original_isolation','idle_no_tx','drive_load','native_reference']}
    checks['native_reference']['limits']={'frequency_min_hz':196000,'frequency_max_hz':198000,
        'high_ns_lower_bound':1000,'cycles_lower_bound':8,'high_ns_per_second_lower_bound':40000}
    checks['drive_load']['measurements']={'series_ohms':1000,'gpio_peak_source_ma':3}
    receipt=tmp_path/'native.json';receipt.write_text(json.dumps({'schema':'no5.hardware-check/1','pico_serial':SERIAL,'checks':checks}))
    row={'schema':'no5.transmit-profile/1','qualified':True,'serial':SERIAL,'kind':'venterior_q13',
         'frequency_hz':197000,'max_high_ns':1000,'max_cycles':8,'min_period_us':200000,'max_high_ns_per_second':40000,
         'evidence':[{'check':k,'path':receipt.name,'sha256':hashlib.sha256(receipt.read_bytes()).hexdigest()} for k in checks]}
    path=tmp_path/'profile.json';path.write_text(json.dumps(row));assert TransmitProfile.load(path).series_ohms==1000
    row['max_high_ns']=2000;path.write_text(json.dumps(row))
    with pytest.raises(ValueError,match='native limits'):TransmitProfile.load(path)

def test_owned_depth_requires_own_echo_receipt_profile(tmp_path):
    cfg=ServiceConfig(device='/no-physical-device',storage_root=tmp_path,trigger_threshold=150,
        trigger_hysteresis=8,tx_marker_requested=True,tx_reference_verified=True,tx_reference_basis='owned_marker_aba_v1')
    with pytest.raises(ValueError,match='measured marker'):HostService(cfg,transmit_profile=profile(), archive_enabled=True)
    verified=replace(profile(),kind='venterior_q13',depth_reference_verified=True)
    host=HostService(cfg,transmit_profile=verified, archive_enabled=True)
    assert host.config.tx_reference_verified and not host.config.empirical_trigger_fallback

class TXReplay(proto.ReplayDevice):
    def __init__(self):
        samples=np.arange(32,dtype=np.uint16)+100
        super().__init__([],[PingFrame(1,1000000,2000,0,100,131,1,samples)])
        self.fires=0
    def _valid_config(self,payload):
        if len(payload)!=28: return None
        return proto.m1.parse_config_set(payload)
    async def _handle(self,packet):
        kind=int(packet.type)
        if kind==2:
            await self._send(MessageType.HELLO,pack_hello(Hello(0,7,0,0xC000006E,500000,16384,12,3300,SERIAL)))
        elif 0x40<=kind<=0x43:
            request=struct.unpack_from('<I',packet.payload)[0]
            await self._send(MessageType.TX_ACK,ACK.pack(request,0,1,1,self.device_us()+2000000))
            if kind==0x42:
                tx_id=struct.unpack_from('<Q',packet.payload,4)[0];self.fires+=1
                start=self.device_us(); marker=start+2
                await self._send(MessageType.TX_EVENT,EVENT.pack(1,0,tx_id,start,0,0))
                await self._send(MessageType.TX_EVENT,EVENT.pack(2,0,tx_id,start,marker,0))
                self._ping_id+=1
                source=self.pings[0]
                ping=replace(source,ping_id=self._ping_id,trigger_device_time_us=marker)
                await self._send(MessageType.PING_FRAME,pack_ping_frame(ping))
                await self._send(MessageType.TX_EVENT,EVENT.pack(6,0,tx_id,start,marker,ping.ping_id))
        else: await super()._handle(packet)
    async def _run(self):
        while True: await self._handle(await self.commands.get())

def test_real_pty_control_archive_and_physical_ping_association(tmp_path):
    async def run():
        device=TXReplay();await device.start()
        cfg=ServiceConfig(device=device.slave_path,storage_root=tmp_path,sample_count=32,pretrigger_samples=0,
                          sample_period_ns=2000,trigger_threshold=150,trigger_hysteresis=8,
                          tx_marker_requested=True,clock_initial_exchanges=4,zarr_chunk_pings=4)
        host=HostService(cfg,transmit_profile=profile(), archive_enabled=True)
        try:
            await host.start()
            for _ in range(500):
                if host.status.state=='RUNNING': break
                await asyncio.sleep(.01)
            assert host.status.state=='RUNNING',host.status.last_error
            await host.transmit.update({'mode':'manual'})
            request={'request_id':str(uuid.uuid4())}
            await host.transmit.fire(request); await host.transmit.fire(request)
            for _ in range(300):
                if host.transmit.linked_pings==1: break
                await asyncio.sleep(.01)
            assert device.fires==1 and host.transmit.linked_pings==1
        finally:
            await host.stop();await device.close()
        rows=[json.loads(line) for line in (host._session_path/'derived/transmit.jsonl').read_text().splitlines()]
        assert any(r['event']=='verified_raw_ping_link' for r in rows)
        assert host._archived_total==1
    asyncio.run(run())


def test_measured_1764ms_arm_ack_is_accepted_but_device_lease_stays_two_seconds(tmp_path):
    import asyncio, struct
    from types import SimpleNamespace
    from no5_sonard_openecho.transmit import TransmitController
    async def run():
        service=SimpleNamespace(config=SimpleNamespace(storage_root=tmp_path),_session_path=None,
                                status=SimpleNamespace(segment_id=None))
        tx=TransmitController(service,None)
        lease_bodies=[]
        class Client:
            async def send(self,kind,body):
                request=struct.unpack_from('<I',body)[0]
                lease_bodies.append(body[4:])
                async def delayed():
                    await asyncio.sleep(1.764)
                    tx._waiters[request].set_result({'result':0,'state':1,'mode':4,'lease_until_us':2000000})
                asyncio.create_task(delayed())
        tx.client=Client()
        result=await tx._command(0x41,struct.pack('<II',4,2000000))
        assert result['result']==0
        assert lease_bodies==[struct.pack('<II',4,2000000)]
    asyncio.run(run())
