import asyncio
from dataclasses import replace
import struct
from types import SimpleNamespace

import pytest
from fastapi.testclient import TestClient
from no5_sonard.pico_protocol import MessageType, Packet
from no5_sonard_openecho.__main__ import create_host_app
from no5_sonard_openecho.interval_signature import PATTERN, PATTERN_NAME, interval_symbol
from no5_sonard_openecho.transmit import EVENT
from test_depth_rate import fixture


def test_code_contains_both_safe_spacings_and_has_an_unambiguous_cycle():
    assert len(PATTERN)==7 and set(PATTERN)=={0,1}
    assert {interval_symbol(i,24300)['requested_spacing_us'] for i in range(7)}=={24300,27000}
    assert all(interval_symbol(i,27000)['requested_spacing_us']>=27000 for i in range(14))
    assert all(tuple(PATTERN[i:]+PATTERN[:i]) != PATTERN for i in range(1,7))


def test_pattern_cannot_change_armed_or_run_continuously_or_on_an_unqualified_floor(tmp_path):
    s,tx,_=fixture(tmp_path);api=TestClient(create_host_app(s));url='/api/v1/transmit'
    assert api.put(url,json={'interval_pattern':PATTERN_NAME}).status_code==422
    tx.profile=replace(tx.profile,qualified_min_spacing_us=24300,qualified_max_own_per_native=8)
    assert api.put(url,json={'interval_pattern':PATTERN_NAME,'duration_s':2}).status_code==200
    tx.mode='periodic'
    assert api.put(url,json={'interval_pattern':'off'}).status_code==409
    tx.mode='off'
    assert api.put(url,json={'interval_pattern':PATTERN_NAME,'duration_s':0}).status_code==422
    assert tx.policy['duration_s']==2
    assert not tx.snapshot()['interval_signature']['acoustic_code_confirmed']


def test_native_events_request_safe_revisions_and_applied_labels_are_physical_not_assumed(tmp_path):
    _,tx,commands=fixture(tmp_path)
    tx.profile=replace(tx.profile,qualified_min_spacing_us=24300,qualified_max_own_per_native=8)
    tx.depth_policy.snapshot=lambda:{'depth_m':1.1,'depth_age_s':.01,'sample_count':2048,
        'range_reserve_limited':False,'reason':'depth_locked','valid_us':900000}
    async def run():
        await tx.update({'mode':'periodic','duration_s':2,'interval_pattern':PATTERN_NAME})
        applied=[]
        for native_stable in range(3,17):
            stamp=1000000+native_stable*386400
            tx.on_packet(Packet(MessageType.TX_EVENT,1,0,EVENT.pack(8,11,stamp,stamp,386400,native_stable<<32)))
            await tx._interval_task
            payload=[p for k,p in commands if k==0x47][-1]
            request,revision,count,spacing,floor,valid,quota=struct.unpack('<IIIIIII',payload)
            assert spacing==interval_symbol(native_stable,24300)['requested_spacing_us']
            assert floor==24300 and quota==8 and count==2048
            tx.on_packet(Packet(MessageType.TX_EVENT,1,0,EVENT.pack(9,revision,count,stamp+174000,spacing,native_stable)))
            label=tx.applied_window['interval_signature']
            assert label['native_stable']==native_stable and label['revision']==revision
            applied.append(spacing)
        assert set(applied)=={24300,27000}
        await tx.abort()
        before=len(commands)
        tx.on_packet(Packet(MessageType.TX_EVENT,1,0,EVENT.pack(8,11,9000000,9000000,386400,17<<32)))
        await asyncio.sleep(0)
        assert len(commands)==before and tx._interval_task is None
    asyncio.run(run())


def test_expired_depth_never_preserves_a_fast_code_interval(tmp_path):
    _,tx,commands=fixture(tmp_path)
    tx.profile=replace(tx.profile,qualified_min_spacing_us=24300,qualified_max_own_per_native=8)
    tx.policy['interval_pattern']=PATTERN_NAME
    async def run():
        await tx._push_depth_plan()
    asyncio.run(run())
    _,_,count,spacing,floor,_,_=struct.unpack('<IIIIIII',commands[-1][1])
    assert count==16384 and spacing>=count*2+3000 and floor==24300
    assert not tx._last_interval_label['distinguishable']


def test_abort_cancels_an_inflight_cycle_plan_before_usb_stop_command(tmp_path):
    _,tx,commands=fixture(tmp_path)
    tx.profile=replace(tx.profile,qualified_min_spacing_us=24300,qualified_max_own_per_native=8)
    async def run():
        await tx.update({'mode':'periodic','duration_s':2,'interval_pattern':PATTERN_NAME})
        entered=asyncio.Event();never=asyncio.Event();original=tx.client.send
        async def delayed_plan(kind,payload):
            if int(kind)==0x47:
                entered.set();await never.wait()
            await original(kind,payload)
        tx.client.send=delayed_plan
        tx.on_packet(Packet(MessageType.TX_EVENT,1,0,EVENT.pack(8,11,2000000,2000000,386400,4<<32)))
        await asyncio.wait_for(entered.wait(),.5)
        task=tx._interval_task
        await asyncio.wait_for(tx.abort(),.5)
        assert task.cancelled() and tx._interval_task is None and tx._renewal is None
        assert not tx._waiters and tx.mode=='off' and commands[-1][0]==0x43
        before=len(commands)
        tx.on_packet(Packet(MessageType.TX_EVENT,1,0,EVENT.pack(8,11,2386400,2386400,386400,5<<32)))
        await asyncio.sleep(0)
        assert len(commands)==before
    asyncio.run(run())
