import asyncio
import hashlib
import json
from types import SimpleNamespace

import numpy as np
import pytest
from fastapi.testclient import TestClient

from no5_sonard.pico_protocol import PingFrame
from no5_sonard.protocol import ECHOGRAM_HEADER_LEN, unpack_echogram_frame
from no5_sonard.processing_m1 import max_pool_resample
from no5_sonard_openecho.noise_reduction import NoiseReduction
from no5_sonard_openecho.__main__ import create_host_app
from test_display_selection import make_service
import prototype as proto


def profile(n=2048, period=2000):
    rng = np.random.default_rng(37)
    x = np.arange(n)*period/2000
    raw = rng.normal(66, 3, n)
    raw += 100*np.exp(-.5*((x-800)/18)**2)
    raw += 12*np.exp(-.5*((x-480)/5)**2)
    return np.rint(np.clip(raw, 0, 4095)).astype(np.uint16)


def process(reducer, raw, **kwargs):
    return reducer.process(raw, ping_id=1, sample_period_ns=2000,
                           tx_zero_sample_index=0, **kwargs)


@pytest.mark.parametrize("n", [2048, 8192, 16384])
def test_background_reduced_before_pooling_without_changing_adc_or_echo_position(n):
    raw = profile(n)
    digest = hashlib.sha256(raw.tobytes()).hexdigest()
    block = SimpleNamespace(start_sample=740, end_sample=860)
    clean, status = process(NoiseReduction(), raw, blocks=[block])
    quiet = slice(350, 425)
    assert status["active"] and status["mode"] == "auto"
    assert np.percentile(clean[quiet], 95) <= 3
    assert clean[780:820].max() >= .9*(raw[780:820].max()-66)
    assert np.argmax(clean[740:860]) == np.argmax(raw[740:860])
    assert clean[470:490].max() >= .8*(raw[470:490].max()-66)
    assert np.percentile(max_pool_resample(clean, start_index=0, bins=1024), 50) < 5
    assert hashlib.sha256(raw.tobytes()).hexdigest() == digest
    assert not np.shares_memory(clean, raw)


def test_local_noise_adapts_and_no_history_cancels_stationary_or_moving_targets():
    rng = np.random.default_rng(4)
    reducer = NoiseReduction()
    for position in [750]*5+[800, 850, 900]:
        raw = np.rint(rng.normal(np.linspace(45, 100, 8192), 4)).astype(np.uint16)
        raw[position:position+12] += 20
        block = SimpleNamespace(start_sample=position, end_sample=position+12)
        clean, status = process(reducer, raw, blocks=[block])
        assert status["active"]
        assert clean[position:position+12].max() >= 15
        assert np.median(clean[1000:]) == 0


@pytest.mark.parametrize("period", [2000, 4000, 8000])
def test_sample_period_and_pretrigger_preserve_geometry(period):
    raw = profile(8192, period)
    raw = np.r_[np.full(100, 4095, dtype=np.uint16), raw]
    peak = 100+int(800*2000/period)
    clean, status = NoiseReduction().process(raw, ping_id=5, sample_period_ns=period,
        tx_zero_sample_index=100, blocks=[SimpleNamespace(start_sample=peak-20, end_sample=peak+20)])
    np.testing.assert_array_equal(clean[:100], raw[:100])
    assert np.argmax(clean[peak-10:peak+10]) == np.argmax(raw[peak-10:peak+10])
    assert status["sample_count"] == len(raw)


def test_no_reference_and_unverified_time_fall_back_to_original_without_fabricated_zeros():
    raw = profile()
    for kwargs, reason in [({"valid_tx": False}, "unverified_time_reference"),
                           ({"blocks": [SimpleNamespace(start_sample=0, end_sample=2048)]},
                            "insufficient_noise_reference")]:
        clean, status = process(NoiseReduction(), raw, **kwargs)
        np.testing.assert_array_equal(raw, clean)
        assert not status["active"] and status["reason"] == reason


def test_policy_api_is_validated_persistent_and_off_idempotent(tmp_path):
    service = make_service(tmp_path)
    api = TestClient(create_host_app(service))
    url = "/api/v1/sonar/noise-reduction"
    assert api.get(url).json()["policy"]["mode"] == "auto"
    for payload in ({"mode": True}, {"mode": []}, {"mode": "magic"}, {"mode": "off", "extra": 0}, {}):
        assert api.put(url, json=payload).status_code == 422
    off = api.put(url, json={"mode": "off"}).json()
    assert off["policy"]["revision"] == 2 and off["clear_client_history"]
    same = api.put(url, json={"mode": "off"}).json()
    assert same["policy"]["revision"] == 2 and not same["clear_client_history"]
    assert make_service(tmp_path).noise_reduction.mode == "off"
    assert api.put(url, json={"mode": "on"}).json()["policy"]["mode"] == "on"


def test_display_changes_only_amplitudes_raw_detection_depth_and_tx_stay_identical(tmp_path):
    async def run():
        captured = []
        for mode in ("off", "auto", "on"):
            service = make_service(tmp_path / mode)
            service.set_noise_reduction_mode(mode)
            service._ensure_session()
            service._open_segment("0123456789ABCDEF", 8192, 2000)
            client = SimpleNamespace(clock=SimpleNamespace(ready=True, pico_us_to_utc_ns=lambda t: t*1000),
                                     hello=SimpleNamespace(device_serial="0123456789ABCDEF"))
            queue = await service.hub.subscribe()
            raw = profile(8192)
            immutable = raw.copy()
            for pid in range(1, 5):
                await service._handle_ping(PingFrame(pid, pid*400000, 2000, 0,
                    int(raw.min()), int(raw.max()), 1, raw), client)
            frames = [queue.get_nowait() for _ in range(queue.qsize())]
            archive = service._writer.path
            service._writer.close()
            np.testing.assert_array_equal(proto.read_zarr_array(archive, "samples"), np.stack([immutable]*4))
            np.testing.assert_array_equal(raw, immutable)
            rows = [json.loads(line) for line in (service._session_path / "derived" /
                service.status.segment_id / "detections.jsonl").read_text().splitlines()]
            assert all(row["noise_reduction"]["mode"] == mode for row in rows)
            captured.append((frames[-1], service.latest_detection(), service._last_presentation))
        off, auto, on = captured
        for result in (auto, on):
            assert off[0][:ECHOGRAM_HEADER_LEN] == result[0][:ECHOGRAM_HEADER_LEN]
            assert off[1:] == result[1:]
            assert off[0][ECHOGRAM_HEADER_LEN:] != result[0][ECHOGRAM_HEADER_LEN:]
        _, amplitudes = unpack_echogram_frame(off[0])
        np.testing.assert_array_equal(amplitudes, max_pool_resample(raw, start_index=0, bins=1024))
    asyncio.run(run())
