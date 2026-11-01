import asyncio
import hashlib
import json
from types import SimpleNamespace

import numpy as np
import pytest
from fastapi.testclient import TestClient

from no5_sonard.protocol import unpack_echogram_frame
from no5_sonard.pico_protocol import MessageType, Packet, PingFrame
from no5_sonard_openecho.__main__ import create_host_app
from no5_sonard_openecho.display_window import display_window
from no5_sonard_openecho.noise_reduction import NoiseReduction
from no5_sonard_openecho.transmit import EVENT
from test_depth_rate import fixture


def test_far_capture_cannot_change_near_background_estimate_or_weak_echo():
    rng = np.random.default_rng(88)
    raw = np.rint(rng.normal(55, 3, 16384)).astype(np.uint16)
    raw[:2048] += 60
    raw[750:770] += 12  # known weak return, independent of the far pedestal
    block = SimpleNamespace(start_sample=745, end_sample=775)
    original = hashlib.sha256(raw.tobytes()).hexdigest()
    def run(n, stripe):
        return NoiseReduction(stripe_mode=stripe).process(raw[:n], ping_id=1,
            sample_period_ns=2000, tx_zero_sample_index=0, blocks=[block])[0]
    short, long = run(2048, 'auto'), run(16384, 'auto')
    # Physical neighbourhood is identical away from the capture boundary.
    np.testing.assert_array_equal(short[:1500], long[:1500])
    assert long[750:770].max() >= 10
    assert np.median(run(16384, 'off')[350:700]) > np.median(long[350:700])+30
    assert hashlib.sha256(raw.tobytes()).hexdigest() == original


@pytest.mark.parametrize('bad', [None, {'reason':'depth_stale','depth_age_s':2.,'sample_count':2048},
    {'reason':'depth_locked','depth_age_s':1.,'sample_count':2048},
    {'reason':'stabilizing','depth_age_s':.1,'sample_count':2048}])
def test_missing_stale_or_unsettled_depth_keeps_complete_display(bad):
    x = display_window(sample_count=16384, sample_period_ns=2000, tx_zero_sample_index=100,
        sound_speed_mps=1500, ping_id=1, stripe_mode='auto', depth_control=bad,
        valid_tx=True, acquisition_source='own')
    assert x['display_sample_count'] == 16284
    assert x['display_range_m'] == pytest.approx(16284*.0015)


def test_display_window_is_explicit_pretrigger_aware_and_never_invents_coverage():
    args = dict(sample_count=16384, sample_period_ns=2000, tx_zero_sample_index=100,
        sound_speed_mps=1500, ping_id=1, stripe_mode='auto',
        depth_control={'reason':'depth_locked','depth_age_s':.1,'sample_count':2048},
        valid_tx=True, acquisition_source='own')
    x = display_window(**args)
    assert x['display_start_sample'] == 100 and x['display_end_sample_exclusive'] == 2148
    assert x['capture_sample_count'] == 16384 and x['display_range_m'] == pytest.approx(3.072)
    for override in ({'acquisition_source':'native'}, {'stripe_mode':'off'}):
        assert display_window(**(args|override))['display_sample_count'] == 16284
    assert display_window(**(args|{'sample_count':2048}))['display_sample_count'] == 1948


def test_unverified_window_describes_original_index_zero_payload_and_unknown_range():
    for marker_index in [-1,0,1000,16384]:
        x=display_window(sample_count=16384,sample_period_ns=2000,tx_zero_sample_index=marker_index,
            sound_speed_mps=1500,ping_id=1,stripe_mode='auto',valid_tx=False,
            acquisition_source='own',depth_control={'reason':'depth_locked','depth_age_s':.1,'sample_count':2048})
        assert x['display_start_sample']==0 and x['display_sample_count']==16384
        assert x['capture_range_m'] is None and x['display_range_m'] is None
        assert x['reason']=='unverified_time_reference' and not x['tx_reference_verified']


def test_stripe_policy_is_persistent_independent_and_validated(tmp_path):
    s,_,_ = fixture(tmp_path)
    api = TestClient(create_host_app(s));url='/api/v1/sonar/noise-reduction'
    for value in [True, [], 'bad', None]:
        assert api.put(url,json={'stripe_mode':value}).status_code == 422
    assert api.put(url,json={'stripe_mode':'off'}).json()['policy']['algorithm'] == 'robust_adc_background_v1'
    assert api.put(url,json={'mode':'off'}).json()['policy']['stripe_mode'] == 'off'
    assert api.put(url,json={'stripe_mode':'auto'}).json()['policy']['mode'] == 'off'
    restored,_,_ = fixture(tmp_path)
    assert restored.noise_reduction.stripe_mode == 'auto' and restored.noise_reduction.mode == 'off'


def test_search_raw_and_detection_keep_deep_return_with_same_ping_display_geometry(tmp_path):
    s, tx, _ = fixture(tmp_path, tx_reference_verified=True, tx_reference_basis='hardware_gp16_transducer',
                       sample_count=8192, sample_period_ns=2000, pretrigger_samples=0)
    s.set_display_source('own');s._ensure_session();s._open_segment(tx.profile.serial,8192,2000)
    tx.client.clock=SimpleNamespace(ready=True,pico_us_to_utc_ns=lambda us:us*1000)
    # Freeze only this test's wall-clock-dependent depth controller at a fresh
    # qualified window; the real detector still sees every captured sample.
    tx.on_detection=lambda *args:None
    tx.depth_policy.snapshot=lambda:{'reason':'depth_locked','depth_age_s':.1,'sample_count':2048}
    frames=[]
    async def publish(frame):frames.append(frame)
    s.hub.publish=publish
    async def run():
        raw=np.full(16384,80,dtype=np.uint16);raw[750:790]=500;raw[10000:10007]=400
        before=raw.tobytes()
        for event in [9,1,2,3]:
            payload=EVENT.pack(9,1,16384,1000000,35768,2) if event==9 else EVENT.pack(event,0,1,1000000,1000003,0)
            tx.on_packet(Packet(MessageType.TX_EVENT,1,0,payload))
        await s._handle_ping(PingFrame(1,1000003,2000,0,80,500,1,raw),tx.client)
        s._writer.close()
        assert raw.tobytes()==before
        header,amplitudes=unpack_echogram_frame(frames[-1])
        assert header.displayed_max_depth_m == pytest.approx(3.072)
        assert len(amplitudes)==1024
        row=s.published_pings()['pings'][0]
        assert row['sample_count']==row['display_window']['capture_sample_count']==16384
        assert row['display_window']['display_sample_count']==2048
        assert any(b.start_sample > 9000 for b in s._last_detection.blocks)
        archived=json.loads(next((s._session_path/'derived').glob('*/detections.jsonl')).read_text().splitlines()[0])
        assert archived['display_window']==row['display_window']
        assert any(b['start_sample'] > 9000 for b in archived['blocks'])
    asyncio.run(run())
