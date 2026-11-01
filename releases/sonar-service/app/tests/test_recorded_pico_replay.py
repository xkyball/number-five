"""Replay validation is independent of any physical GPIO or serial device."""
import asyncio
import hashlib
import json
from pathlib import Path
import sys
import struct

import numpy as np
import pytest

sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'tools'))
from recorded_pico_replay import (EVENT,RecordedPico,export_recording,records,
    recording_metadata,remap_payload,sample_digest_update)
from no5_sonard import pico_protocol as c


def fixture(tmp_path):
    packets=[]
    def event(kind,tid,start,marker=0,pid=0,detail=0):
        packets.append(c.Packet(c.MessageType(0x45),len(packets),0,
            EVENT.pack(kind,detail,tid,start,marker,pid)))
    event(9,2048,999900,24300,1,detail=1)
    event(1,77,1000000)
    event(2,77,1000000,1000003)
    ping=c.PingFrame(5,1000003,2000,0,0,4095,1,np.arange(2048,dtype=np.uint16))
    packets.append(c.Packet(c.MessageType(0x20),len(packets),0,c.pack_ping_frame(ping)))
    event(6,77,1000000,1000003,5)
    event(7,77,1000000,1000003)
    wire=tmp_path/'rx.cobs';wire.write_bytes(b''.join(c.encode_packet(p) for p in packets))
    control=tmp_path/'control.jsonl';control.write_text(''.join(json.dumps({
        'event':'receive_packet','sequence':p.seq,'host_monotonic_ns':9000000000+p.seq*1000000})+'\n' for p in packets))
    result=tmp_path/'source.json';result.write_text(json.dumps({'run':{
        'state':'COMPLETE','verified':True,'all_associated':True,'own_starts':1},
        'last_transmissions':[{'tx_id':77}]}))
    archive=tmp_path/'replay.zip';export_recording(wire,control,result,archive)
    return archive,ping,(wire,control,result)


def test_export_preserves_adc_bytes_and_arrival_spacing(tmp_path):
    path,ping,_=fixture(tmp_path);meta=recording_metadata(path)
    data=list(records(path));raw=next(p for _,kind,_,_,p in data if kind==0x20)
    assert c.parse_ping_frame(raw).samples.tobytes()==ping.samples.tobytes()
    digest=hashlib.sha256();sample_digest_update(digest,ping)
    assert meta['samples_sha256']==digest.hexdigest()
    assert [r[0] for r in data]==[n*1000000 for n in range(6)]
    assert meta['sample_counts']=={'2048':1} and not meta['new_hardware_evidence']


def test_export_rejects_incomplete_source_and_torn_wire(tmp_path):
    _,_,(wire,control,result)=fixture(tmp_path)
    row=json.loads(result.read_text());row['run']['verified']=False;result.write_text(json.dumps(row))
    with pytest.raises(ValueError,match='completed'):export_recording(wire,control,result,tmp_path/'bad.zip')
    row['run']['verified']=True;result.write_text(json.dumps(row));wire.write_bytes(wire.read_bytes()[:-1])
    with pytest.raises(ValueError,match='truncated'):export_recording(wire,control,result,tmp_path/'torn.zip')


def test_acceleration_changes_only_time_not_sample_geometry(tmp_path):
    _,original,_=fixture(tmp_path)
    raw=remap_payload(0x20,c.pack_ping_frame(original),999900,2000000,2)
    mapped=c.parse_ping_frame(raw)
    assert mapped.trigger_device_time_us==2000052
    assert mapped.samples.tobytes()==original.samples.tobytes()
    assert mapped.sample_period_ns==2000 and mapped.tx_zero_sample_index==0
    event=EVENT.unpack(remap_payload(0x45,EVENT.pack(9,1,16384,1000000,35768,4),999900,2000000,2))
    assert event==(9,1,16384,2000050,17884,4)


@pytest.mark.parametrize('command',[0x40,0x41,0x42,0x46,0x47])
def test_replay_rejects_every_output_command(tmp_path,command):
    path,_,_=fixture(tmp_path)
    async def run():
        device=RecordedPico(path,1,tmp_path);await device.start()
        try:
            device.commands.put_nowait(c.Packet(c.MessageType(command),1,0,b''))
            await asyncio.wait_for(device.complete.wait(),1)
            assert 'forbidden' in device.error and device.frames_sent==0
        finally:await device.close()
    asyncio.run(run())


def test_owned_pty_serves_real_handshake_clock_ping_and_stop(tmp_path):
    from no5_sonard.pico.client import PicoClient
    from no5_sonard.transport.serial_posix import PosixSerialTransport
    path,original,_=fixture(tmp_path)
    async def run():
        device=RecordedPico(path,1,tmp_path);await device.start()
        client=PicoClient(PosixSerialTransport(device.device));await client.start()
        try:
            await client.acknowledge_hello();hello=await client.wait_hello(1)
            assert hello.device_serial=='REPLAY0711TEST'
            await client.stop_acquisition();await client.synchronize(2)
            await client.configure(c.ConfigSet(1,2000,2048,0,2,1,150,8,1000000,1))
            await client.start_acquisition()
            received=await asyncio.wait_for(client.pings.get(),1)
            assert received.samples.tobytes()==original.samples.tobytes()
            await asyncio.wait_for(device.complete.wait(),1)
            await client.stop_acquisition();await asyncio.sleep(.01)
            assert device.stop_count==2 and not device.running and device.error is None
            assert client.packet_seq_gaps==client.ping_id_gaps==0
        finally:await client.close();await device.close()
    asyncio.run(run())


def test_benchmark_archive_check_rejects_a_rewritten_sample(tmp_path):
    from benchmark_replay import verify_archive
    from no5_sonard.storage.raw_zarr import RawZarrV2Writer,RawPingRecord
    archive,ping,(wire,_,_)=fixture(tmp_path)
    session=tmp_path/'output/state/sessions/test'
    target=session/'wire/connection/rx.cobs';target.parent.mkdir(parents=True)
    target.write_bytes(wire.read_bytes())
    path=session/'raw/one.zarr'
    def write(samples):
        writer=RawZarrV2Writer(path,sample_count=2048,sample_period_ns=2000,chunk_pings=1,attrs={})
        writer.append(RawPingRecord(ping_id=ping.ping_id,trigger_device_time_us=ping.trigger_device_time_us,
            time_utc_ns=0,sample_period_ns=2000,tx_zero_sample_index=0,adc_min=0,adc_max=4095,ping_flags=1,samples=samples))
        writer.close()
    write(ping.samples)
    result=verify_archive(tmp_path/'output',archive)
    assert result['all_pings_archived'] and result['matches_source_hash']
    assert result['wire_archive_timestamps_equal']
    import shutil
    shutil.rmtree(path);changed=ping.samples.copy();changed[123]+=1;write(changed)
    with pytest.raises(ValueError,match='raw PING mismatch'):verify_archive(tmp_path/'output',archive)
