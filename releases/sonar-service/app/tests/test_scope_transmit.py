import asyncio
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import struct
import sys
from types import SimpleNamespace
import numpy as np
from no5_sonard.pico_protocol import MessageType
from no5_sonard_openecho.transmit import ACK, EVENT
from test_transmit import TXReplay,SERIAL
import prototype as proto

class ScopeTXReplay(TXReplay):
    async def _handle(self,packet):
        if int(packet.type)!=0x42:return await super()._handle(packet)
        request,tx_id=struct.unpack('<IQ',packet.payload)
        await self._send(MessageType.TX_ACK,ACK.pack(request,0,1,1,self.device_us()+2000000))
        start=self.device_us();marker=start+2
        await self._send(MessageType.TX_EVENT,EVENT.pack(1,0,tx_id,start,0,0))
        await self._send(MessageType.TX_EVENT,EVENT.pack(2,0,tx_id,start,marker,0))
        await self._send(MessageType.TX_EVENT,EVENT.pack(3,0,tx_id,start,marker,0))
        self.samples=np.arange(4096,dtype=np.uint16)%500+50
        payload=bytearray(proto.pack_stream(self.samples,0,marker-200,2000))
        struct.pack_into('<I',payload,32,256|(100<<16))
        await self._send(MessageType.STREAM_FRAME,payload)

def test_single_owner_scope_control_records_original_samples_and_marker(tmp_path):
    tools=Path(__file__).resolve().parents[1]/'tools';sys.path.insert(0,str(tools))
    spec=importlib.util.spec_from_file_location('scope_tx_tool',tools/'capture_owned_tx_scope.py')
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
    async def run():
        receipt=tmp_path/'receipt.json'
        receipt.write_text(json.dumps({'schema':'no5.hardware-check/1','pico_serial':SERIAL,
            'checks':{k:{'passed':True,'basis':'operator_measurement','fixture_only':True} for k in ['setup_isolated','output_idle']}}))
        profile=tmp_path/'profile.json';profile.write_text(json.dumps({'schema':'no5.transmit-profile/1','qualified':True,
            'kind':'pico_loopback','serial':SERIAL,'frequency_hz':197000,'max_high_ns':1000,'max_cycles':8,
            'min_period_us':200000,'max_high_ns_per_second':40000,
            'evidence':[{'check':k,'path':receipt.name,'sha256':hashlib.sha256(receipt.read_bytes()).hexdigest()} for k in ['setup_isolated','output_idle']]}))
        device=ScopeTXReplay();await device.start()
        args=SimpleNamespace(profile=profile,output=tmp_path/'capture',device=device.slave_path,source='GPIO22_reference',
             cycles=1,high_ns=1000,period_us=1000000,sample_period_us=2,mode='manual',fires=1,seconds=.15,
             wheel_sha256=hashlib.sha256(Path(os.environ['SONARD_TEST_WHEEL']).read_bytes()).hexdigest())
        try:report=await module.capture(args)
        finally:await device.close()
        assert report['stop_confirmed'] and not report['acoustic_echo_verified']
        assert report['frames']==1 and len(report['markers'])==1
        packets=list(proto.recorded_packets(args.output/'capture.oescope'))
        raw=next(p.payload for p in packets if int(p.type)==0x22)
        # Decode the packed12bit sample stream independently of the UI helper.
        b=np.frombuffer(raw[40:],dtype=np.uint8).reshape(-1,3).astype(np.uint16)
        values=np.empty(4096,dtype=np.uint16);values[::2]=b[:,0]|((b[:,1]&15)<<8);values[1::2]=(b[:,1]>>4)|(b[:,2]<<4)
        np.testing.assert_array_equal(values,device.samples)
    asyncio.run(run())
