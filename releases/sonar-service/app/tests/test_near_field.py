import asyncio
import base64
import hashlib
import json
from pathlib import Path
from types import SimpleNamespace

import numpy as np
import pytest
from fastapi.testclient import TestClient

from no5_sonard.pico_protocol import PingFrame
from no5_sonard.protocol import unpack_echogram_frame
from no5_sonard_openecho.__main__ import create_host_app
from no5_sonard_openecho.detection import DetectionPipeline
from no5_sonard_openecho.near_field import boundary
from no5_sonard_openecho.noise_reduction import NoiseReduction
from no5_sonard_openecho.echogram_context import unpack_context
from test_display_selection import make_service
from test_impulse_reduction import fixture
import prototype as proto


def process(reducer, raw, *, tx=0, period=2000, valid=True):
    return reducer.process(raw, ping_id=1, sample_period_ns=period,
        tx_zero_sample_index=tx, valid_tx=valid, acquisition_source='own')


@pytest.mark.parametrize('period,tx,expected', [(2000,0,334),(2000,77,411),(4000,0,167),(8000,23,107)])
def test_single_physical_boundary_uses_roundtrip_and_original_indices(period,tx,expected):
    a=boundary(sample_count=8192,sample_period_ns=period,tx_zero_sample_index=tx,
               sound_speed_mps=1500,near_field_m=.5)
    assert a['analysis_start_sample']==expected
    assert a['active'] and not a['raw_samples_modified']


@pytest.mark.parametrize('n',[2048,8192,16384])
@pytest.mark.parametrize('mode',['off','auto'])
def test_burst_cannot_change_any_valid_filtered_sample_and_off_still_masks(n,mode):
    raw,_=fixture(n=n);cut=334
    raw[:cut]=4095
    other=raw.copy();other[:cut]=np.arange(cut)%93
    digest=hashlib.sha256(raw.tobytes()).hexdigest();raw.setflags(write=False)
    a,sa=process(NoiseReduction(mode,near_field_m=.5),raw)
    b,sb=process(NoiseReduction(mode,near_field_m=.5),other)
    np.testing.assert_array_equal(a,b)
    assert not a[:cut].any()
    assert sa==sb
    if mode=='auto':assert sa['impulse_reduction']['active']
    else:np.testing.assert_array_equal(a[cut:],raw[cut:])
    assert hashlib.sha256(raw.tobytes()).hexdigest()==digest


def test_only_valid_region_clipping_can_disable_impulse_separator():
    raw,_=fixture();raw[:334]=4095
    _,legacy=process(NoiseReduction(),raw)
    _,current=process(NoiseReduction(near_field_m=.5),raw)
    assert legacy['impulse_reduction']['reason']=='clipped_profile'
    assert current['impulse_reduction']['active']
    raw[1500]=4095
    _,clipped=process(NoiseReduction(near_field_m=.5),raw)
    assert clipped['impulse_reduction']['reason']=='clipped_profile'


def test_exclusion_does_not_rephase_existing_local_noise_reference_cells():
    rng=np.random.default_rng(2)
    raw=np.rint(rng.normal(np.linspace(45,100,8192),4)).astype(np.uint16)
    a,sa=process(NoiseReduction(impulse_mode='off'),raw)
    b,sb=process(NoiseReduction(impulse_mode='off',near_field_m=.5),raw)
    np.testing.assert_array_equal(a[335:],b[335:])


@pytest.mark.parametrize('n,tx,period',[(2048,0,2000),(8192,77,2000),(4096,31,4000),(16384,0,2000)])
def test_all_detector_and_template_steps_exclude_prefix_and_return_original_geometry(n,tx,period):
    rng=np.random.default_rng(7);x=np.arange(n)
    step=period*1e-9*1500/2;cut=tx+int(np.ceil(.5/step));peak=tx+round(.85/step)
    raw=np.rint(rng.normal(55,2,n)+160*np.exp(-.5*((x-peak)/(30*2000/period))**2)).astype(np.uint16)
    a=raw.copy();a[:cut]=4095;b=raw.copy();b[:cut]=rng.integers(0,4000,cut)
    one,two=DetectionPipeline(),DetectionPipeline()
    for pid in range(1,9):
        args=dict(ping_id=pid,sample_period_ns=period,tx_zero_sample_index=tx,
                  time_utc_ns=pid*100_000_000,valid_tx=True,acquisition_source='own',near_field_m=.5)
        aa=one.process(samples=a,**args);bb=two.process(samples=b,**args)
        assert aa.public()==bb.public()
        assert all(block.start_sample>=cut and block.depth_m>=.5 for block in aa.blocks)
    assert any(abs(block.depth_m-.85)<.04 for block in aa.blocks)
    assert aa.tx_zero_sample_index==tx


def test_echo_just_after_boundary_survives_and_pretrigger_is_not_rebased():
    tx=100;n=2048;x=np.arange(n);peak=tx+380
    raw=np.rint(55+180*np.exp(-.5*((x-peak)/9)**2)).astype(np.uint16);raw[:tx+334]=4095
    out,status=process(NoiseReduction(near_field_m=.5),raw,tx=tx)
    np.testing.assert_array_equal(out[:tx],raw[:tx])
    assert not out[tx:tx+334].any()
    assert np.argmax(out[tx+334:])+tx+334==peak
    assert out[peak]>150 and status['near_field']['analysis_start_sample']==tx+334
    r=DetectionPipeline().process(ping_id=1,samples=raw,tx_zero_sample_index=tx,
        sample_period_ns=2000,time_utc_ns=1,valid_tx=True,near_field_m=.5)
    assert any(abs(b.depth_m-.57)<.015 for b in r.blocks)


def test_unknown_reference_is_untouched_and_excluded_whole_capture_is_explicit():
    raw,_=fixture()
    out,status=process(NoiseReduction(near_field_m=.5),raw,valid=False)
    np.testing.assert_array_equal(raw,out)
    assert status['near_field']['reason']=='unverified_time_reference'
    out,status=process(NoiseReduction(near_field_m=100),raw)
    assert not out.any() and status['reason']=='no_usable_samples'
    r=DetectionPipeline().process(ping_id=1,samples=raw,tx_zero_sample_index=0,
        sample_period_ns=2000,time_utc_ns=1,valid_tx=True,near_field_m=100)
    assert not r.blocks and not r.selected


def test_near_field_api_persists_resets_only_on_changes_and_rejects_invalid_values(tmp_path):
    s=make_service(tmp_path);api=TestClient(create_host_app(s));url='/api/v1/sonar/noise-reduction'
    assert api.get(url).json()['policy']['near_field_m']==0
    old=s.detection
    changed=api.put(url,json={'near_field_m':.5}).json()
    assert changed['clear_client_history'] and s.detection is not old
    assert changed['policy']['near_field_m']==.5
    current=s.detection
    assert not api.put(url,json={'near_field_m':.5}).json()['clear_client_history']
    assert s.detection is current
    assert make_service(tmp_path).noise_reduction.near_field_m==.5
    for bad in [None,True,False,-.01,'0.5',[],{},10**1000]:
        assert api.put(url,json={'near_field_m':bad}).status_code==422
    for value in [float('inf'),float('-inf'),float('nan')]:
        with pytest.raises(ValueError):NoiseReduction(near_field_m=value)
    assert api.put(url,json={'near_field_m':0}).json()['policy']['near_field_m']==0


def test_public_synthetic_three_stage_vectors_reproduce_actual_wheel_and_same_raw():
    fixture=json.loads((Path(__file__).parent/'fixtures/near-field-interface-0420.json').read_text())
    digests=set()
    for case in fixture['cases']:
        raw=np.frombuffer(base64.b64decode(case['raw_adc_base64']),dtype='<u2')
        digests.add(hashlib.sha256(raw.tobytes()).hexdigest())
        recorded=case['noise_reduction']['latest']
        values,status=NoiseReduction.from_recorded(recorded).process(raw,
            ping_id=case['published_ping']['ping_id'],sample_period_ns=2000,tx_zero_sample_index=0,
            acquisition_source='own',blocks=[SimpleNamespace(**b) for b in case['blocks']])
        assert status==recorded
        if case['display_mask_only_m']:values[:334]=0
        assert values.tobytes()==base64.b64decode(case['derived_adc_base64'])
        meta,frame=unpack_context(base64.b64decode(case['context_base64']))
        assert meta==case['context_metadata'] and frame==base64.b64decode(case['frame_base64'])
        _,bins=unpack_echogram_frame(frame)
        if case['name']!='reference':assert not bins[:167].any()
    assert len(digests)==1


@pytest.mark.parametrize('mode',['off','auto'])
def test_wire_has_no_burst_and_keeps_original_adc_archive_range_and_ids(tmp_path,mode):
    async def run():
        s=make_service(tmp_path);s.set_noise_reduction_mode(mode,near_field_m=.5)
        s._ensure_session();s._open_segment('0123456789ABCDEF',8192,2000)
        client=SimpleNamespace(clock=SimpleNamespace(ready=True,pico_us_to_utc_ns=lambda t:t*1000),
            hello=SimpleNamespace(device_serial='0123456789ABCDEF'))
        q=await s.hub.subscribe();raw,_=fixture(n=8192);raw[:334]=4095;before=raw.copy()
        for pid in range(1,5):
            await s._handle_ping(PingFrame(pid,pid*400000,2000,0,0,4095,1,raw),client)
        frames=[q.get_nowait() for _ in range(q.qsize())]
        header,values=unpack_echogram_frame(frames[-1])
        assert header.ping_id==4 and len(values)==1024
        assert header.displayed_max_depth_m==pytest.approx(12.288)
        assert not values[np.arange(1024)*header.displayed_max_depth_m/1023<.5].any()
        assert values[100:].max()>0
        path=s._writer.path;s._writer.close()
        np.testing.assert_array_equal(proto.read_zarr_array(path,'samples'),np.stack([before]*4))
        np.testing.assert_array_equal(raw,before)
        rows=[json.loads(line) for line in next((s._session_path/'derived').glob('*/detections.jsonl')).read_text().splitlines()]
        assert all(r['noise_reduction']['near_field_m']==.5 for r in rows)
    asyncio.run(run())
