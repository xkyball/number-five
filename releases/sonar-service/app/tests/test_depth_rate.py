import asyncio
import json
from pathlib import Path
import struct
import time
from types import SimpleNamespace

import numpy as np
import pytest
from fastapi.testclient import TestClient
from no5_sonard.config import ServiceConfig
from no5_sonard.pico_protocol import MessageType, Packet, PingFrame
from no5_sonard_openecho.__main__ import create_host_app
from no5_sonard_openecho.depth_rate import DepthPolicy, DepthRateProfile, PingRates, capture_geometry
from no5_sonard_openecho.service import HostService
from no5_sonard_openecho.transmit import ACK, EVENT


@pytest.mark.parametrize('depth,expected,limited', [(None,16384,False),(1.1,2048,False),
    (3,3072,False),(10,10240,False),(20,16384,True)])
def test_geometry_has_echo_reserve_and_never_exceeds_adc_buffer(depth,expected,limited):
    assert capture_geometry(depth)==(expected,limited)


def test_stable_shrink_deeper_immediate_conflict_and_stale_search():
    now=[0.]
    p=DepthPolicy(clock=lambda:now[0])
    for n in range(5):
        now[0]=n*.1;p.observe(1.1,valid=True)
    assert p.snapshot()['sample_count']==16384
    now[0]=1.1;p.observe(1.1,valid=True)
    assert p.snapshot()['sample_count']==2048
    now[0]=1.2;p.observe(10,valid=True)
    assert p.snapshot()['sample_count']>=10240
    p.observe(None,valid=False)
    assert p.snapshot()['sample_count']==16384
    for n in range(12):
        now[0]=2+n*.1;p.observe(1.1,valid=True)
    assert p.snapshot()['sample_count']==2048
    now[0]+=1.01
    assert p.snapshot()['sample_count']==16384 and p.snapshot()['reason']=='depth_stale'


@pytest.mark.parametrize('value',[0,-1,float('inf'),float('nan')])
def test_bad_depth_never_accelerates(value):
    p=DepthPolicy();p.observe(value,valid=True)
    assert p.snapshot()['sample_count']==16384


def test_one_missing_candidate_holds_only_a_fresh_previously_applied_window():
    now=[0.];p=DepthPolicy(clock=lambda:now[0])
    for k in range(12):
        now[0]=k*.1;p.observe(1.1,valid=True)
    assert p.snapshot()['sample_count']==2048
    now[0]=1.2;p.observe(None,valid=False)
    held=p.snapshot()
    assert held['sample_count']==2048 and held['reason']=='depth_held_fresh'
    assert held['depth_age_s']==pytest.approx(.1) and p.hits==0
    now[0]=1.8;p.observe(None,valid=False)
    assert p.last_at==pytest.approx(1.1)  # missing candidates never renew depth
    now[0]=2.01
    assert p.snapshot()['sample_count']==16384
    p.observe(1.1,valid=True)
    assert p.snapshot()['sample_count']==16384  # no instant shrink after stale
    for k in range(1,12):
        now[0]=2.01+k*.1;p.observe(1.1,valid=True)
    assert p.snapshot()['sample_count']==2048
    p.observe(4.,valid=False)
    assert p.snapshot()['sample_count']==16384  # contradiction expands now


def test_missing_candidate_never_shortens_an_unsettled_window_or_holds_old_input():
    now=[0.];p=DepthPolicy(clock=lambda:now[0])
    p.observe(1.1,valid=True);now[0]=.1;p.observe(None,valid=False)
    assert p.snapshot()['sample_count']==16384
    for k in range(12):
        now[0]=.2+k*.1;p.observe(1.1,valid=True)
    assert p.snapshot()['sample_count']==2048
    p.observe(None,valid=False,age_s=1.1)
    assert p.snapshot()['sample_count']==16384


@pytest.mark.parametrize('bad',[0,-1,float('inf'),float('nan')])
def test_malformed_depth_immediately_expands_a_previously_locked_window(bad):
    now=[0.];p=DepthPolicy(clock=lambda:now[0])
    for k in range(12):
        now[0]=k*.1;p.observe(1.1,valid=True)
    assert p.snapshot()['sample_count']==2048
    p.observe(bad,valid=False)
    assert p.snapshot()['sample_count']==16384


def fixture(root, **config):
    profile=DepthRateProfile.load(Path(__file__).resolve().parents[1]/'deployment/clip-depth-0711.json')
    s=HostService(ServiceConfig(device='/no-device',storage_root=root,trigger_threshold=150,
        trigger_hysteresis=8,tx_marker_requested=True,**config),transmit_profile=profile, archive_enabled=True)
    s.status.state='RUNNING';tx=s.transmit
    c=SimpleNamespace(hello=SimpleNamespace(fw_major=0,fw_minor=7,fw_patch=11,capabilities=0xC0001FEE,
        device_serial=profile.serial),accepted_config={'requested':{'reserved':1}},packet_seq_gaps=0,
        ping_id_gaps=0,missed_ping_queue=0,transport=SimpleNamespace(transmit_handler=None,crc_errors=0,frame_errors=0))
    commands=[]
    async def send(kind,payload):
        commands.append((int(kind),payload))
        tx.on_packet(Packet(MessageType.TX_ACK,1,0,ACK.pack(struct.unpack_from('<I',payload)[0],0,1,4,2000000)))
    c.send=send;tx.bind(c)
    return s,tx,commands


def test_api_policy_validation_and_unarmed_changes(tmp_path):
    s,tx,_=fixture(tmp_path);api=TestClient(create_host_app(s))
    assert api.get('/api/v1/transmit').json()['policy']['rate_policy']=='depth_auto'
    assert api.put('/api/v1/transmit',json={'rate_policy':'arbitrary'}).status_code==422
    assert api.put('/api/v1/transmit',json={'rate_policy':'fixed_qualified'}).status_code==200
    tx.mode='periodic'
    assert api.put('/api/v1/transmit',json={'rate_policy':'depth_auto'}).status_code==409
    assert api.put('/api/v1/transmit',json={'mode':'off','rate_policy':'depth_auto'}).status_code==409
    assert tx.policy['rate_policy']=='fixed_qualified'
    assert tx.snapshot()['rate_control']['sample_count']==8192
    assert tx.snapshot()['rate_control']['max_own_per_native']==7


def test_trial_cannot_become_a_normal_or_unqualified_endurance_rate(tmp_path):
    _,tx,_=fixture(tmp_path);tx.trial_spacing_us=24300
    tx.short_verified=True
    async def run():
        with pytest.raises(RuntimeError,match='receipts'):
            await tx.update({'mode':'periodic','duration_s':600})
    asyncio.run(run())
    assert tx.snapshot()['rate_control']['experimental_trial']
    assert tx.profile.qualified_min_spacing_us==27000
    tx.policy['rate_policy']='fixed_qualified'
    fixed=tx.snapshot()['rate_control']
    assert fixed['max_own_per_native']==7 and fixed['periodic_full_search'] is None


def test_variable_ping_raw_samples_and_window_revision_are_kept(tmp_path):
    _,tx,commands=fixture(tmp_path)
    async def run():
        await tx.update({'mode':'periodic','duration_s':2})
        plan=next(p for k,p in commands if k==0x47)
        assert struct.unpack_from('<IIIIII',plan,4)==(1,16384,35768,27000,1000000,7)
        for k,count in enumerate([16384,2048,8192]):
            start=1000000+k*50000;txid=100+k
            tx.on_packet(Packet(MessageType.TX_EVENT,1,0,EVENT.pack(9,k+1,count,start,27000,1)))
            for event in [1,2,3]:
                tx.on_packet(Packet(MessageType.TX_EVENT,1,0,EVENT.pack(event,0,txid,start,start+3,0)))
            samples=np.arange(count,dtype=np.uint16)%3000;before=samples.tobytes()
            tx.on_ping(PingFrame(k+1,start+3,2000,0,0,3000,1,samples))
            tx.on_packet(Packet(MessageType.TX_EVENT,1,0,EVENT.pack(6,0,txid,start,start+3,k+1)))
            tx.on_detection(SimpleNamespace(acquisition_source='own',valid_tx=True,ping_id=k+1,time_utc_ns=time.time_ns()),
                {'quality':'LOCKED','bottom':{'depth_m':1.1,'confidence':.99}})
            assert samples.tobytes()==before
            assert tx._ledger[txid]['capture_window']['revision']==k+1
        assert tx.linked_pings==3 and tx.geometry_errors==0
        await tx.abort()
    asyncio.run(run())


def test_profile_rejects_fabricated_fast_qualification(tmp_path):
    base=Path(__file__).resolve().parents[1]/'deployment/clip-depth-0711.json'
    row=json.loads(base.read_text());row['baseline']['path']=str(base.parent/row['baseline']['path'])
    row['qualified_min_spacing_us']=6000
    p=tmp_path/'bad.json';p.write_text(json.dumps(row))
    with pytest.raises(ValueError,match='physical rate'):
        DepthRateProfile.load(p)


def test_actual_window_rates_include_idle_time_and_missing_usable(monkeypatch):
    now=[0.];monkeypatch.setattr('no5_sonard_openecho.depth_rate.time.monotonic',lambda:now[0])
    rates=PingRates()
    for i in range(101):
        now[0]=i*.05
        rates.add('own',1000000+i*50000);rates.add('physical',1000000+i*50000)
        if i%2==0:rates.add('usable',1000000+i*50000)
    x=rates.snapshot()
    assert x['complete'] and x['rates']['own']['hz']==20
    assert x['rates']['usable']['hz']==10
    assert x['rates']['own']['rolling_1s_min']==20
    now[0]+=6
    assert rates.snapshot()['rates']['own']['hz']==0


def test_raw_archive_keeps_variable_geometries_without_padding(tmp_path):
    import zlib
    from no5_sonard.storage.raw_zarr import RawPingRecord, RawZarrV2Writer
    from no5_sonard_openecho.geometry_archive import GeometryArchive
    archive=GeometryArchive(RawZarrV2Writer(tmp_path/'segment.zarr',sample_count=8192,sample_period_ns=2000))
    expected={}
    for pid,n in enumerate([8192,2048,16384,2048],1):
        samples=(np.arange(n,dtype=np.uint16)+pid)%3500;expected[pid]=samples.tobytes()
        archive.append(RawPingRecord(ping_id=pid,time_utc_ns=pid*1000000,trigger_device_time_us=pid*1000,
            sample_period_ns=2000,tx_zero_sample_index=0,ping_flags=1,adc_min=0,adc_max=3500,samples=samples))
    assert archive.count+archive.pending_count==4
    archive.close();assert archive.count==4
    observed={}
    for p in tmp_path.glob('*.zarr'):
        meta=json.loads((p/'samples/.zarray').read_text());rows=meta['shape'][0];chunk,n=meta['chunks']
        ids=np.frombuffer(zlib.decompress((p/'ping_id/0').read_bytes()),dtype='<u8')[:rows]
        values=np.frombuffer(zlib.decompress((p/'samples/0.0').read_bytes()),dtype='<u2').reshape(chunk,n)[:rows]
        for pid,raw in zip(ids,values):observed[int(pid)]=raw.tobytes()
    assert observed==expected
    index=json.loads((tmp_path/'segment.zarr/geometry-index.json').read_text())
    assert len(index['geometries'])==3 and index['raw_samples_modified'] is False


def test_original_reference_preserves_detail_observed_time_and_latched_fault(tmp_path):
    _,tx,_=fixture(tmp_path)
    tx.on_packet(Packet(MessageType.TX_EVENT,1,0,EVENT.pack(8,4,1700000,1259200,386400,3<<32)))
    x=tx.snapshot()['original_reference'];latest=x['latest']
    assert latest['observation_reason']=='native_reference_deadline_expired'
    assert latest['observed_device_us']==1700000 and latest['observed_device_us']!=latest['native_last_device_us']
    assert latest['observed_utc_ns'] is None and latest['utc_basis'] is None
    assert latest['observation_kind']=='timeout_check' and x['observation_fresh']
    tx.client.clock=SimpleNamespace(ready=True,pico_us_to_utc_ns=lambda us:1000000000000+us*1000)
    tx.on_packet(Packet(MessageType.TX_EVENT,1,0,EVENT.pack(8,11,1800000,1800000,386400,4<<32)))
    x=tx.snapshot()['original_reference']
    assert x['latest']['observed_utc_ns']==1001800000000
    assert x['latest']['meaning']=='qualified' and x['latched_fault']['firmware_detail']==4
    assert tx.mode=='off'  # Observing recovery is not a rearm.


def test_recovery_probe_is_bounded_and_cannot_qualify_echo_endurance(tmp_path):
    _,tx,_=fixture(tmp_path);tx.trial_spacing_us=24300;tx.diagnostic_source='R17_a'
    status=tx.snapshot()['rate_control']
    assert status['sample_count']==2048 and status['depth_m'] is None
    tx.on_ping(PingFrame(1,1000000,2000,0,0,100,1,np.full(8192,100,dtype=np.uint16)))
    assert not tx.profiles and tx._echoes==0
    async def run():
        with pytest.raises(ValueError,match='2s'):
            await tx.update({'mode':'periodic','duration_s':30})
    asyncio.run(run())


@pytest.mark.parametrize('chunk_pings',[16,256])
def test_service_pipeline_preserves_raw_and_frame_geometry_across_window_changes(tmp_path,chunk_pings):
    from no5_sonard.protocol import unpack_echogram_frame
    s,tx,_=fixture(tmp_path,tx_reference_verified=True,tx_reference_basis='hardware_gp16_transducer',
                   sample_count=8192,sample_period_ns=2000,pretrigger_samples=0,zarr_chunk_pings=chunk_pings)
    s.set_display_source('own')
    s._ensure_session();s._open_segment(tx.profile.serial,8192,2000)
    tx.client.clock=SimpleNamespace(ready=True,pico_us_to_utc_ns=lambda us:time.time_ns())
    frames=[]
    async def publish(frame):frames.append(frame)
    s.hub.publish=publish
    async def run():
        for pid,n in enumerate([16384,2048,8192,2048],1):
            start=pid*100000
            tx.on_packet(Packet(MessageType.TX_EVENT,1,0,EVENT.pack(9,pid,n,start,40000,1)))
            for event in [1,2,3]:
                tx.on_packet(Packet(MessageType.TX_EVENT,1,0,EVENT.pack(event,0,pid,start,start+3,0)))
            raw=np.full(n,50,dtype=np.uint16);raw[750:760]=500;before=raw.tobytes()
            await s._handle_ping(PingFrame(pid,start+3,2000,0,50,500,1,raw),tx.client)
            tx.on_packet(Packet(MessageType.TX_EVENT,1,0,EVENT.pack(6,0,pid,start,start+3,pid)))
            assert raw.tobytes()==before
            header,_=unpack_echogram_frame(frames[-1])
            assert abs(header.displayed_max_depth_m-n*.0015)<.00001
        s._writer.close()
        assert s._writer.count==tx.linked_pings==4
    asyncio.run(run())


def test_slow_plan_ack_cannot_hold_the_finite_lease_or_accumulate_refresh_tasks(tmp_path,monkeypatch):
    from no5_sonard_openecho.transmit import TransmitController
    async def run():
        _,tx,_=fixture(tmp_path);tx.mode='periodic'
        gate=asyncio.Event();calls=[]
        async def command(self,kind,body=b''):
            calls.append(kind)
            if kind==0x47:await gate.wait()
            return {'accepted':True}
        monkeypatch.setattr(TransmitController,'_command',command)
        await asyncio.wait_for(tx._command(0x41),.1);await asyncio.sleep(0)
        pending=tx._depth_refresh_task
        assert calls==[0x41,0x47] and not pending.done()
        await asyncio.wait_for(tx._command(0x41),.1)
        assert tx._depth_refresh_task is pending and calls==[0x41,0x47,0x41]
        await tx._cancel_renewal();assert pending.cancelled()
    asyncio.run(run())


def test_unconfirmed_plan_falls_back_on_device_expiry_without_aborting_renewals(tmp_path,monkeypatch):
    from no5_sonard_openecho.transmit import TransmitController,TxCommandTimeout
    async def run():
        _,tx,_=fixture(tmp_path);tx.mode='periodic';calls=[]
        async def command(self,kind,body=b''):
            calls.append(kind)
            if kind==0x47:raise TxCommandTimeout(kind,42,1.)
        monkeypatch.setattr(TransmitController,'_command',command)
        await tx._command(0x41);await asyncio.sleep(0)
        assert tx.mode=='periodic' and tx._depth_refresh_error and 0x42 not in calls and 0x43 not in calls
        await tx._command(0x41);await asyncio.sleep(0)
        assert calls.count(0x41)==2
        await tx._cancel_renewal()
    asyncio.run(run())


def test_unprepared_archive_geometry_keeps_full_search_without_clipping_range(tmp_path):
    async def run():
        service,tx,commands=fixture(tmp_path)
        now=[0.];tx.depth_policy=DepthPolicy(clock=lambda:now[0])
        for k in range(12):
            now[0]=k*.1;tx.depth_policy.observe(10.,valid=True)
        assert tx.depth_policy.snapshot()['sample_count']==10240
        ready=[False];service._writer=SimpleNamespace(ensure_geometry=lambda n,p:ready[0])
        await tx._push_depth_plan()
        first=struct.unpack('<IIIIIII',commands[-1][1])
        assert commands[-1][0]==0x47 and first[2]==16384
        assert tx.snapshot()['rate_control']['archive_geometry_preparing']==10240
        ready[0]=True;await tx._push_depth_plan()
        second=struct.unpack('<IIIIIII',commands[-1][1])
        assert second[2]==10240 and tx.snapshot()['rate_control']['archive_geometry_preparing'] is None
        assert not any(kind==0x42 for kind,_ in commands)
    asyncio.run(run())
