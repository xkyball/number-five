import asyncio
import importlib.util
import json
from pathlib import Path
import struct
import sys
from types import SimpleNamespace
import zlib

import numpy as np
import pytest
from no5_sonard.pico_protocol import MessageType, Hello, pack_hello, parse_config_set
import prototype as proto

TOOLS=Path(__file__).resolve().parents[1]/'tools'
sys.path.insert(0,str(TOOLS))
SPEC=importlib.util.spec_from_file_location('clip_probe',TOOLS/'clip_transmit_probe.py')
probe=importlib.util.module_from_spec(SPEC); SPEC.loader.exec_module(probe)
SERIAL='CLIPPTY-00000001'


def events(count):
    result=[]; markers=[]
    for i in range(count):
        start=1000000+100000*i; marker=start+2; markers.append(marker)
        for event in (1,2,3):
            result.append(dict(event=event,reason=0,id=50+i,output_start_us=start,marker_us=marker if event>1 else 0,ping_id=0))
    result.append(dict(event=7,reason=0,id=50+count-1,output_start_us=start,marker_us=marker,ping_id=0))
    return result,markers


def test_only_physically_marked_raw_events_pass_and_not_as_echo():
    e,m=events(10)
    result=probe.verify_records(e,m,10)
    assert result['own_confirmed_markers']==10
    assert result['actual_output_intervals_us']==[100000]*9
    assert not result['acoustic_echo_verified']
    with pytest.raises(ValueError,match='raw STREAM'): probe.verify_records(e,[],10)
    with pytest.raises(ValueError,match='Missing or duplicate'): probe.verify_records(e[:2]+e[3:],m,10)
    with pytest.raises(ValueError,match='number of unique'): probe.verify_records(e+e[:1],m,10)


def test_wrong_prior_receipt_cannot_authorize_train(tmp_path):
    with pytest.raises(ValueError): probe.check_prior_single(None,1,SERIAL)
    path=tmp_path/'single.json'
    path.write_text(json.dumps({'schema':'no5.clip-transmit-trial/1','count':1,'cycles':1,
        'own_confirmed_markers':1,'expected_serial':SERIAL,'gpio':[18,22], 'stop_confirmed':True,'error':None}))
    probe.check_prior_single(path,1,SERIAL)
    with pytest.raises(ValueError): probe.check_prior_single(path,2,SERIAL)
    with pytest.raises(ValueError): probe.check_prior_single(path,1,'OTHER')
    with pytest.raises(ValueError): probe.check_prior_single(path,1,SERIAL,1)
    receipt=json.loads(path.read_text()); receipt['expected_firmware_version']=[0,7,1]
    path.write_text(json.dumps(receipt))
    probe.check_prior_single(path,1,SERIAL,1)
    with pytest.raises(ValueError): probe.check_prior_single(path,1,SERIAL,0)


class ClipDecoder(proto.FrameDecoder):
    """Same framing checks; the fake device also accepts new command 0x46."""
    def feed(self,data):
        packets=[]
        for byte in data:
            if byte:
                self.pending.append(byte); continue
            frame=bytes(self.pending); self.pending.clear()
            if not frame: continue
            raw=proto.m1.cobs_decode(frame)
            magic,version,kind,seq,length,flags=proto.m1.HEADER.unpack_from(raw)
            assert magic==b'N5' and version==1 and len(raw)==length+20
            assert zlib.crc32(raw[:-4])==struct.unpack_from('<I',raw,len(raw)-4)[0]
            packets.append(proto.WirePacket(kind,seq,flags,raw[16:-4]))
        return packets


class ClipPeer(proto.ReplayDevice):
    def __init__(self,firmware_patch=0):
        super().__init__([],[])
        self.firmware_patch=firmware_patch
        self.decoder=ClipDecoder(); self.sent_commands=[]; self.fires=0
        self.samples=np.arange(4096,dtype=np.uint16)%500+50

    def _valid_config(self,payload): return parse_config_set(payload)

    async def _run(self):
        # The physical firmware emits STATUS every second even while no
        # acoustic/phase marker is available. Missing phase is not USB silence.
        while True:
            try:
                await self._handle(await asyncio.wait_for(self.commands.get(), timeout=.5))
            except asyncio.TimeoutError:
                await self._status()

    async def _handle(self,p):
        kind=int(p.type); self.sent_commands.append(kind)
        if kind==2:
            await self._send(MessageType.HELLO,pack_hello(Hello(0,7,self.firmware_patch,probe.CAPABILITIES,500000,16384,12,3300,SERIAL)))
        elif kind in (0x46,0x41,0x42,0x43):
            request=struct.unpack_from('<I',p.payload)[0]
            if kind==0x46: assert len(p.payload)==28 and struct.unpack('<7I',p.payload)[-1]==1
            await self._send(MessageType.TX_ACK,probe.ACK.pack(request,0,1,3,self.device_us()+2000000))
            if kind==0x42:
                self.fires+=1; tx_id=struct.unpack_from('<Q',p.payload,4)[0]
                start=self.device_us(); marker=start+2
                for event in (1,2,3):
                    await self._send(MessageType.TX_EVENT,probe.EVENT.pack(event,0,tx_id,start,marker if event>1 else 0,0))
                payload=bytearray(proto.pack_stream(self.samples,0,marker-200,2000))
                struct.pack_into('<I',payload,32,256|(100<<16))
                await self._send(MessageType.STREAM_FRAME,payload)
                await self._send(MessageType.TX_EVENT,probe.EVENT.pack(7,0,tx_id,start,marker,0))
        else: await super()._handle(p)


@pytest.mark.parametrize('firmware_patch',[0,1])
@pytest.mark.parametrize('mismatch',[None,'serial','patch'])
def test_real_transport_and_wheel_pty_capture_or_identity_refusal(tmp_path,firmware_patch,mismatch):
    async def run():
        peer=ClipPeer(firmware_patch); await peer.start()
        args=SimpleNamespace(output=tmp_path/'capture',device=peer.slave_path,
            expected_serial='WRONG' if mismatch=='serial' else SERIAL,wiring_verified=True,
            firmware_patch=1-firmware_patch if mismatch=='patch' else firmware_patch,
            cycles=1,count=1,prior_single=None,source='Q10_2')
        try:
            if mismatch:
                with pytest.raises(RuntimeError,match='identity/capabilities differ'): await probe.capture(args)
                assert set(peer.sent_commands)=={2}
            else:
                report=await probe.capture(args)
                assert peer.fires==1 and report['frames']==1 and report['own_confirmed_markers']==1
                assert report['stop_confirmed'] and not report['acoustic_echo_verified']
                raw=next(p.payload for p in proto.recorded_packets(args.output/'capture.oescope') if int(p.type)==0x22)
                packed=np.frombuffer(raw[40:],dtype=np.uint8).reshape(-1,3).astype(np.uint16)
                values=np.empty(4096,dtype=np.uint16)
                values[::2]=packed[:,0]|((packed[:,1]&15)<<8)
                values[1::2]=(packed[:,1]>>4)|(packed[:,2]<<4)
                np.testing.assert_array_equal(values,peer.samples)
        finally: await peer.close()
    asyncio.run(run())


def test_unverified_wiring_is_rejected_before_creating_transport(tmp_path):
    with pytest.raises(ValueError,match='before any USB access'):
        asyncio.run(probe.capture(SimpleNamespace(wiring_verified=False)))
    assert not list(tmp_path.iterdir())


def test_phase_gate_uses_stable_physical_marks_and_rejects_busy_phase():
    def marks(phase):
        last=200*8192+phase-30000-386000
        return [last-3*386000+i*386000 for i in range(4)]
    assert probe.next_quiet_phase(marks(1800),0) is None
    chosen=probe.next_quiet_phase(marks(6000),0)
    assert chosen['predicted_block_phase_us']==6000
    assert chosen['native_period_us']==386000
    assert not chosen['real_time_guarantee']
    assert probe.next_quiet_phase(marks(6000)[:3],0) is None
    unstable=marks(6000); unstable[-2]+=5000
    assert probe.next_quiet_phase(unstable,0) is None


def test_missing_phase_reference_never_arms_or_fires(tmp_path):
    async def run():
        peer=ClipPeer(); await peer.start()
        args=SimpleNamespace(output=tmp_path/'capture',device=peer.slave_path,
            expected_serial=SERIAL,wiring_verified=True,cycles=2,count=1,
            prior_single=None,source='GPIO22',phase_align=True)
        try:
            with pytest.raises(RuntimeError,match='no TX armed'):
                await probe.capture(args)
            assert peer.fires==0
            assert not set(peer.sent_commands)&{0x46,0x41,0x42}
            report=json.loads((args.output/'trial.json').read_text())
            assert report['stop_confirmed'] and report['host_unarmed_phase_selection']['enabled']
        finally:
            await peer.close()
    asyncio.run(run())
