import asyncio
import json
from types import SimpleNamespace

import numpy as np
import pytest
from fastapi.testclient import TestClient

from no5_sonard.config import ServiceConfig
from no5_sonard.pico_protocol import Packet, MessageType, PingFrame
from no5_sonard.protocol import unpack_echogram_frame
from no5_sonard_openecho.__main__ import create_host_app
from no5_sonard_openecho.clip_endurance import ClipProfile
from no5_sonard_openecho.service import HostService
from no5_sonard_openecho.transmit import EVENT
import prototype as proto


def make_service(root, source="all"):
    return HostService(ServiceConfig(device="/no-device", storage_root=root,
        sample_count=8192, sample_period_ns=2000, pretrigger_samples=0,
        trigger_threshold=150, trigger_hysteresis=8, tx_marker_requested=True,
        tx_reference_verified=True, tx_reference_basis="hardware_gp16_transducer"),
        transmit_profile=ClipProfile("0123456789ABCDEF", "a"*64, ()),
        display_ping_source=source, archive_enabled=True)


@pytest.mark.parametrize("source,expected", [("all", [1, 2, 3, 4]), ("own", [2, 4]), ("native", [1, 3])])
def test_selection_archives_all_raw_pings_but_publishes_same_ping_analysis(tmp_path, source, expected):
    async def run():
        service = make_service(tmp_path, source)
        service._ensure_session()
        service._open_segment("0123456789ABCDEF", 8192, 2000)
        client = SimpleNamespace(clock=SimpleNamespace(ready=True,
            pico_us_to_utc_ns=lambda us: us*1000),
            hello=SimpleNamespace(device_serial="0123456789ABCDEF"))
        queue = await service.hub.subscribe()
        raw = []
        visible = []
        for ping_id in range(1, 5):
            trigger = ping_id*100_000
            own = ping_id % 2 == 0
            if own:
                for event in (1, 2):
                    service.transmit.on_packet(Packet(MessageType.TX_EVENT, 1, 0,
                        EVENT.pack(event, 0, 100+ping_id, trigger-3, trigger, 0)))
            samples = np.full(8192, 40, dtype=np.uint16)
            samples[750 if own else 950:780 if own else 980] = 200
            before = samples.copy()
            ping = PingFrame(ping_id, trigger, 2000, 0, 40, 200, 1, samples)
            await service._handle_ping(ping, client)
            np.testing.assert_array_equal(samples, before)
            raw.append(before)
            if not queue.empty():
                header, _ = unpack_echogram_frame(queue.get_nowait())
                assert header.ping_id == ping_id
                meta = service.published_pings()["pings"][-1]
                assert meta["ping_id"] == header.ping_id
                assert meta["time_utc_ns"] == header.time_utc_ns
                assert meta["actual_source"] == ("own" if own else None)
                assert meta["source_evidence"] == ("physical_own_marker_raw_match" if own else "unconfirmed")
                assert meta["sample_count"] == len(samples)
                assert meta["valid_tx_reference"]
                assert service.latest_detection()["ping_id"] == ping_id
                assert service.presentation()["ping_id"] == ping_id
                visible.append(ping_id)
            elif own is False and source == "own" and ping_id == 3:
                # A hidden native ping cannot replace the canonical own depth.
                assert service.latest_detection()["ping_id"] == 2
                assert service.presentation()["acquisition_source"] == "own"
        assert visible == expected
        assert [r["ping_id"] for r in service.published_pings()["pings"]] == expected
        # A later hidden/native analysis cannot overwrite published evidence.
        service._last_detection = None
        assert [r["ping_id"] for r in service.published_pings(2)["pings"]] == [p for p in expected if p > 2]
        summary = service.display_selection()
        assert summary["counts"] == {"received": 4, "own": 2, "native": 2,
            "published": len(expected), "hidden": 4-len(expected)}
        archive = service._writer.path
        service._writer.close()
        np.testing.assert_array_equal(proto.read_zarr_array(archive, "ping_id"), [1, 2, 3, 4])
        np.testing.assert_array_equal(proto.read_zarr_array(archive, "samples"), np.stack(raw))
        rows = [json.loads(line) for line in (service._session_path / "derived" /
            service.status.segment_id / "detections.jsonl").read_text().splitlines()]
        assert [r["presentation"]["ping_id"] for r in rows] == [1, 2, 3, 4]
        assert [r["acquisition_source"] for r in rows] == ["native", "own", "native", "own"]
    asyncio.run(run())


def test_own_selection_waits_without_marker_and_policy_switch_is_explicit(tmp_path):
    service = make_service(tmp_path, "own")
    api = TestClient(create_host_app(service))
    assert api.get("/api/v1/sonar/presentation").json()["quality"] == "WAITING"
    assert api.get("/api/v1/detection/latest").json()["detection"] is None
    for invalid in ({"source": "auto"}, {"source": True}, {"source": []}, {"source": "own", "extra": 1}):
        assert api.put("/api/v1/sonar/display-selection", json=invalid).status_code == 422
    policy = api.put("/api/v1/sonar/display-selection", json={"source": "all"}).json()
    assert policy["source"] == "all" and policy["generation"] == 2
    assert policy["clear_client_history"] and not policy["automatic_native_fallback"]
    assert api.get("/api/v1/sonar/display-selection").json()["raw_archive_source"] == "all"


def test_native_ping_never_becomes_own_from_a_command_ack(tmp_path):
    async def run():
        service = make_service(tmp_path, "own")
        service._ensure_session()
        service._open_segment("0123456789ABCDEF", 8192, 2000)
        queue = await service.hub.subscribe()
        client = SimpleNamespace(clock=SimpleNamespace(ready=True, pico_us_to_utc_ns=lambda t: t*1000),
            hello=SimpleNamespace(device_serial="0123456789ABCDEF"))
        # Output start alone is deliberately insufficient evidence.
        service.transmit.on_packet(Packet(MessageType.TX_EVENT, 1, 0, EVENT.pack(1, 0, 123, 100000, 0, 0)))
        ping = PingFrame(1, 100003, 2000, 0, 40, 40, 1, np.full(8192, 40, dtype=np.uint16))
        await service._handle_ping(ping, client)
        assert queue.empty() and service.latest_detection() is None
        assert service.published_pings()["pings"] == []
        assert service.presentation()["quality"] == "WAITING"
        assert service.display_selection()["counts"]["hidden"] == 1
        service._writer.close()
    asyncio.run(run())


def test_published_metadata_ring_bound_and_cursor_loss_are_explicit(tmp_path):
    service = make_service(tmp_path, "own")
    for i in range(600):
        service._published_pings.append({"ping_id": i+1, "actual_source": "own"})
    api = TestClient(create_host_app(service))
    body = api.get("/api/v1/sonar/published-pings?after_ping_id=1").json()
    assert body["capacity"] == 512 and len(body["pings"]) == 64
    assert body["truncated"] and body["oldest_ping_id"] == 89
    assert api.get("/api/v1/sonar/published-pings?after_ping_id=590").json()["pings"] == [
        {"ping_id": i, "actual_source": "own"} for i in range(591, 601)]
    assert body["has_more"] and body["next_after_ping_id"] == 152
    assert api.get("/api/v1/sonar/published-pings?limit=65").status_code == 422
    assert api.get("/api/v1/sonar/published-pings?after_ping_id=-1").status_code == 422
    original = service.display_selection()["selection_generation"]
    service.set_noise_reduction_mode("off")
    assert service.display_selection()["selection_generation"] == original
    service.set_display_source("native")
    service.set_display_source("own")
    assert service.display_selection()["selection_generation"] == original+2


def test_published_ring_never_exposes_old_segment_under_new_header(tmp_path):
    service = make_service(tmp_path)
    service._ensure_session()
    service._open_segment("0123456789ABCDEF", 8192, 2000)
    first = service.status.segment_id
    service._published_pings.append({"ping_id": 800, "segment_id": first})
    service._writer.close()
    service._open_segment("0123456789ABCDEF", 8192, 2000)
    try:
        response = service.published_pings()
        assert response["segment_id"] != first
        assert response["pings"] == [] and response["latest_ping_id"] is None
        service._published_pings.append({"ping_id": 1, "segment_id": response["segment_id"]})
        assert service.published_pings()["pings"] == [{"ping_id": 1, "segment_id": response["segment_id"]}]
    finally:
        service._writer.close()
