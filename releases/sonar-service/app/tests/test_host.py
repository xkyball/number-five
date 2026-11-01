from __future__ import annotations
import asyncio
from dataclasses import asdict
import json
import math
from pathlib import Path
import os
import signal
import socket
import subprocess
import sys
import time
import urllib.request
import importlib.util

import numpy as np
import pytest
from fastapi.testclient import TestClient
from no5_sonard.app import create_app
from no5_sonard.config import ServiceConfig
from no5_sonard.protocol import unpack_echogram_frame
from no5_sonard_openecho.__main__ import parse_args
from no5_sonard_openecho.service import HostService
from no5_sonard_openecho.wire import ObservedTransport, VerifiedClient
import prototype as proto

ROOT = Path(__file__).resolve().parents[1]


def fixture_inputs():
    pings = proto.archived_pings(proto.DEFAULT_SCOPE_ROOT / proto.ARCHIVE)
    return [], pings


def config(path, root):
    # Historical archived-PING fixture only, not the TS272 hardware threshold.
    return ServiceConfig(device=path, storage_root=root, sample_count=10000,
        pretrigger_samples=256, trigger_threshold=3946, trigger_hysteresis=8,
        clock_initial_exchanges=4, zarr_chunk_pings=4,
        origin_operator_selected="recorded_pty_unverified", tx_reference_verified=False)


async def wait_for(predicate, timeout=8):
    deadline = time.monotonic() + timeout
    while not predicate():
        if time.monotonic() >= deadline:
            raise TimeoutError("test condition did not arrive")
        await asyncio.sleep(.01)


async def write_chunks(fd, data, sizes=(1, 7, 31, 97)):
    """Write a byte stream over deliberately irregular PTY boundaries."""
    offset = index = 0
    while offset < len(data):
        end = min(len(data), offset + sizes[index % len(sizes)])
        pending = memoryview(data)[offset:end]
        while pending:
            try:
                pending = pending[os.write(fd, pending):]
            except BlockingIOError:
                await asyncio.sleep(.0005)
        offset, index = end, index + 1


def status_packet(sequence):
    return proto.m1.Packet(proto.m1.MessageType.STATUS, sequence, 0,
                           proto.m1.pack_status(proto.m1.PicoStatus(
                               1_000_000, 0, 0, 0, 0, 0, 0, 0)))


def corrupt_packet(packet):
    encoded = proto.m1.encode_packet(packet)
    raw = bytearray(proto.m1.cobs_decode(encoded[:-1]))
    raw[16] ^= 1  # Preserve CRC so this remains a transport-layer CRC error.
    return proto.m1.cobs_encode(raw) + b"\x00"


def assert_closed_archive_matches_wire(final):
    """Compare every completed raw PING field with the durable archive."""
    connection = final["connection_history"][0]
    encoded = Path(connection["raw_rx"]).read_bytes().split(b"\0")
    packets = [proto.m1.decode_packet(frame) for frame in encoded[:-1] if frame]
    wire = [proto.m1.parse_ping_frame(packet.payload) for packet in packets
            if packet.type is proto.m1.MessageType.PING_FRAME]
    archive = Path(final["connection_history"][0]["raw_rx"]).parents[2] / "raw" / (
        final["session"]["segment_id"] + ".zarr")
    archived = proto.archived_pings(archive)
    fields = ("ping_id", "trigger_device_time_us", "sample_period_ns",
              "tx_zero_sample_index", "ping_flags", "adc_min", "adc_max")
    assert len(wire) == len(archived) == final["session"]["archived_pings_total"]
    for expected, actual in zip(wire, archived):
        assert all(getattr(expected, key) == getattr(actual, key) for key in fields)
        np.testing.assert_array_equal(expected.samples, actual.samples)
    return wire, archived


class StopBoundaryReplayDevice(proto.ReplayDevice):
    """PTY peer that places selected PINGs between STOP and stopped STATUS."""

    def __init__(self, *args, late_pings, send_stopped_status=True, **kwargs):
        super().__init__(*args, **kwargs)
        self.late_pings = late_pings
        self.send_stopped_status = send_stopped_status

    async def _handle(self, packet):
        # The first STOP precedes CONFIG.  Every later STOP has a config and
        # is the shutdown boundary under test.
        if int(packet.type) == int(proto.m1.MessageType.STOP) and self.config is not None:
            self.running = False
            self.trace.append("STOP")
            for _ in range(self.late_pings):
                await self._data()
            if self.send_stopped_status:
                await self._status()
            return
        await super()._handle(packet)

    async def _run(self):
        # Send one ordinary PING after START; remaining PINGs are deliberately
        # emitted only by the STOP handler above.
        while True:
            try:
                packet = await asyncio.wait_for(self.commands.get(), timeout=0.005)
                await self._handle(packet)
            except asyncio.TimeoutError:
                if self.running and self._cursor < 1:
                    await self._data()


@pytest.mark.parametrize("late_pings", [1, 3])
def test_stop_drains_ping_frames_received_before_stopped_status(tmp_path, late_pings):
    async def run():
        device = StopBoundaryReplayDevice(*fixture_inputs(), late_pings=late_pings)
        await device.start()
        service = HostService(config(device.slave_path, tmp_path), archive_enabled=True)
        try:
            await service.start()
            await wait_for(lambda: service.status.archived_pings >= 1)
            await service.stop()
        finally:
            await device.close()
        final = json.loads(service.final_status_path.read_text())
        wire, archived = assert_closed_archive_matches_wire(final)
        assert len(wire) == len(archived) == 1 + late_pings
        assert final["shutdown"]["stop_status_confirmed"]
        assert final["shutdown"]["reason"] == "stopped status confirmed"
        # Depending on event-loop scheduling, some of the deliberately late
        # PINGs can be handled while stop_acquisition waits for STATUS.  The
        # remainder must be drained, and every one must be in the archive.
        assert 0 <= final["shutdown"]["drained_received_pings"] <= late_pings
        assert final["shutdown"]["remaining_received_pings"] == 0
        assert final["shutdown"]["supervisor_settled"]
        controls = [json.loads(line) for line in Path(final["connection_history"][0]["control_trace"]).read_text().splitlines()]
        final_stop = max(i for i, row in enumerate(controls)
                         if row["event"] == "send_result" and row["type"] == 0x13)
        last_ping = max(i for i, row in enumerate(controls)
                        if row["event"] == "receive_packet" and row["type"] == 0x20)
        stopped = next(i for i, row in enumerate(controls[final_stop + 1:], final_stop + 1)
                       if row["event"] == "receive_packet" and row["type"] == 0x21
                       and row.get("running") is False)
        drained = next(i for i, row in enumerate(controls)
                       if row["event"] == "shutdown_drain_complete")
        closed = next(i for i, row in enumerate(controls) if row["event"] == "close")
        assert final_stop < last_ping < stopped < drained < closed
    asyncio.run(run())


def test_stop_without_pending_ping_closes_in_order(tmp_path):
    async def run():
        device = StopBoundaryReplayDevice(*fixture_inputs(), late_pings=0)
        await device.start()
        service = HostService(config(device.slave_path, tmp_path), archive_enabled=True)
        try:
            await service.start()
            await wait_for(lambda: service.status.archived_pings == 1)
            await service.stop()
        finally:
            await device.close()
        final = json.loads(service.final_status_path.read_text())
        wire, archived = assert_closed_archive_matches_wire(final)
        assert len(wire) == len(archived) == 1
        assert final["shutdown"]["stop_status_confirmed"]
        assert final["shutdown"]["drained_received_pings"] == 0
        assert final["shutdown"]["remaining_received_pings"] == 0
        assert final["archive_closed"] and final["port_closed"]
    asyncio.run(run())


def test_stop_status_timeout_drains_received_pings_and_records_failure(tmp_path):
    async def run():
        device = StopBoundaryReplayDevice(*fixture_inputs(), late_pings=2,
                                          send_stopped_status=False)
        await device.start()
        service = HostService(config(device.slave_path, tmp_path), archive_enabled=True)
        service.stop_confirmation_timeout_s = .05
        service.shutdown_settle_timeout_s = .5
        try:
            await service.start()
            await wait_for(lambda: service.status.archived_pings == 1)
            started = time.monotonic()
            await service.stop()
            assert time.monotonic() - started < 1
        finally:
            await device.close()
        final = json.loads(service.final_status_path.read_text())
        wire, archived = assert_closed_archive_matches_wire(final)
        assert len(wire) == len(archived) == 3
        assert not final["shutdown"]["stop_status_confirmed"]
        assert final["shutdown"]["reason"] == "stop confirmation failed"
        assert "STOP has no confirmed stopped STATUS" in final["shutdown"]["error"]
        assert 0 <= final["shutdown"]["drained_received_pings"] <= 2
        assert final["shutdown"]["remaining_received_pings"] == 0
        assert final["archive_closed"] and final["port_closed"]
        assert not final["all_connections_stop_confirmed"]
        controls = Path(final["connection_history"][0]["control_trace"]).read_text()
        assert "stop_confirmation_failed" in controls
        assert "shutdown_drain_complete" in controls
    asyncio.run(run())


def test_startup_cobs_synchronization_discards_only_the_initial_fragment(tmp_path):
    async def run():
        device = proto.ReplayDevice(*fixture_inputs()); await device.start()
        transport = ObservedTransport(device.slave_path, audit_root=tmp_path / "wire")
        valid = status_packet(41)
        encoded = proto.m1.encode_packet(valid)
        # A connection can begin at any byte within an existing COBS packet.
        # The tail and its delimiter are not a packet and must not count as a
        # post-synchronization frame error.
        fragment = encoded[11:]
        try:
            await transport.open()
            await write_chunks(device.master_fd, fragment + encoded)
            received = await transport.recv_packet(timeout=.5)
            assert received.type is proto.m1.MessageType.STATUS
            assert received.seq == 41
            await transport.wait_startup_synchronization(.1)
            snapshot = transport.startup_sync_snapshot()
            assert snapshot["state"] == "synchronized"
            assert snapshot["discarded_frames"] == 1
            assert snapshot["discarded_bytes"] == len(fragment) - 1
            assert snapshot["first_valid_packet"]["sequence"] == 41
            assert transport.frame_errors == transport.crc_errors == 0
        finally:
            await transport.close(); await device.close()
        controls = [json.loads(line) for line in (tmp_path / "wire" / "control.jsonl").read_text().splitlines()]
        assert [row["event"] for row in controls].count("startup_frame_discarded") == 1
        assert [row["event"] for row in controls].count("startup_synchronized") == 1
    asyncio.run(run())


def test_post_sync_corruption_remains_a_visible_transport_error(tmp_path):
    async def run():
        device = proto.ReplayDevice(*fixture_inputs()); await device.start()
        transport = ObservedTransport(device.slave_path, audit_root=tmp_path / "wire")
        first, second = status_packet(5), status_packet(6)
        try:
            await transport.open()
            await write_chunks(device.master_fd, proto.m1.encode_packet(first))
            assert (await transport.recv_packet(timeout=.5)).seq == 5
            await write_chunks(device.master_fd, corrupt_packet(status_packet(99)) +
                               proto.m1.encode_packet(second))
            assert (await transport.recv_packet(timeout=.5)).seq == 6
            assert transport.startup_sync_snapshot()["discarded_frames"] == 0
            assert transport.frame_errors == transport.crc_errors == 1
        finally:
            await transport.close(); await device.close()
    asyncio.run(run())


def test_startup_cobs_synchronization_times_out_without_a_valid_boundary(tmp_path):
    async def run():
        device = proto.ReplayDevice(*fixture_inputs()); await device.start()
        transport = ObservedTransport(device.slave_path, audit_root=tmp_path / "wire")
        try:
            await transport.open()
            await write_chunks(device.master_fd, b"\x10\x20\x30\x00")
            with pytest.raises(TimeoutError, match="startup COBS synchronization timed out"):
                await transport.wait_startup_synchronization(.03)
            snapshot = transport.startup_sync_snapshot()
            assert snapshot["state"] == "waiting"
            assert snapshot["discarded_frames"] == 1
            assert transport.frame_errors == transport.crc_errors == 0
        finally:
            await transport.close(); await device.close()
    asyncio.run(run())


def test_mid_frame_open_synchronizes_before_stop_and_preserves_command_order(tmp_path):
    class MidFrameOpenDevice(proto.ReplayDevice):
        def __init__(self, *args, **kwargs):
            super().__init__(*args, **kwargs)
            self.injected = False

        async def _handle(self, packet):
            if int(packet.type) == int(proto.m1.MessageType.HELLO_ACK) and not self.injected:
                self.injected = True
                # Emit the end of an old packet before the HELLO elicited by the
                # prompt.  This models opening the port during active STREAM.
                stale = proto.m1.encode_packet(status_packet(77))[9:]
                await write_chunks(self.master_fd, stale)
            await super()._handle(packet)

    async def run():
        device = MidFrameOpenDevice(*fixture_inputs()); await device.start()
        service = HostService(config(device.slave_path, tmp_path), archive_enabled=True)
        try:
            await service.start()
            await wait_for(lambda: service.status.archived_pings == len(device.pings))
        finally:
            await service.stop(); await device.close()
        final = json.loads(service.final_status_path.read_text())
        assert device.injected
        sync = final["pico"]["startup_sync"]
        assert sync["state"] == "synchronized"
        assert sync["discarded_frames"] == 1
        assert final["pico"]["frame_errors"] == final["pico"]["crc_errors"] == 0
        connection = final["connection_history"][0]
        assert connection["startup_sync"] == sync
        controls = [json.loads(line) for line in Path(connection["control_trace"]).read_text().splitlines()]
        events = [row["event"] for row in controls]
        first_stop = next(i for i, row in enumerate(controls)
                          if row["event"] == "send_result"
                          and row["type"] == int(proto.m1.MessageType.STOP))
        assert events.index("startup_synchronized") < first_stop < events.index("stop_confirmed")
        assert device.trace == ["STOP", "CONFIG:ping", "START:ping", "STOP"]
    asyncio.run(run())


@pytest.mark.parametrize("corrupt", [0, 2])
def test_saved_pings_archive_display_stop_and_durable_counters(tmp_path, corrupt):
    async def run():
        inputs = fixture_inputs()
        device = proto.ReplayDevice(*inputs, corrupt_data_number=corrupt)
        await device.start()
        service = HostService(config(device.slave_path, tmp_path), archive_enabled=True)
        queue = await service.hub.subscribe()
        expected = [p for i, p in enumerate(inputs[1], 1) if i != corrupt]
        try:
            await service.start()
            await wait_for(lambda: service.status.archived_pings == len(expected))
            snapshot = service.snapshot()
            assert snapshot["state"] == "RUNNING", snapshot
            assert snapshot["accepted_config"]["ack"]["actual_sample_count"] == 10000
            assert snapshot["device_status"] is not None
            assert snapshot["bottom"]["depth_m"] is None
            assert snapshot["acquisition"]["last_tx_reference_valid"] is False
            header, amplitudes = unpack_echogram_frame(await queue.get())
            assert math.isnan(header.bottom_depth_m)
            assert math.isnan(header.displayed_max_depth_m)
            assert not (header.flags & 1) and (header.flags & 32)
            assert len(amplitudes) == service.config.display_bins
            segment = service._writer.path
        finally:
            await service.stop()
            await service.hub.unsubscribe(queue)
            await device.close()
        final = json.loads(service.final_status_path.read_text())
        assert final["state"] == "IDLE"
        assert final["port_closed"] and final["archive_closed"]
        assert final["all_connections_stop_confirmed"]
        assert final["pico"]["crc_errors"] == int(bool(corrupt))
        assert final["pico"]["packet_seq_gaps"] == int(bool(corrupt))
        assert final["pico"]["ping_id_gaps"] == int(bool(corrupt))
        assert final["pico"]["queue_drops"] == 0
        assert final["session"]["archived_pings_total"] == len(expected)
        assert final["acquisition"]["clipped_pings_total"] == len(expected)
        assert final["clock"]["ready"]
        assert final["state_before_shutdown"] == "RUNNING"
        np.testing.assert_array_equal(proto.read_zarr_array(segment, "samples"),
                                      np.stack([p.samples for p in expected]))
        assert not (segment / "_pending.bin").exists()
        connection = final["connection_history"][0]
        raw = Path(connection["raw_rx"]).read_bytes()
        decoder = proto.FrameDecoder(); packets = decoder.feed(raw)
        assert decoder.errors == int(bool(corrupt))
        assert sum(p.type == proto.m1.MessageType.PING_FRAME for p in packets) == len(expected)
        controls = [json.loads(line) for line in Path(connection["control_trace"]).read_text().splitlines()]
        events = [r["event"] for r in controls]
        assert events.index("open") < events.index("stop_confirmed") < events.index("config_accepted")
        starts = [i for i, r in enumerate(controls) if r["event"] == "send_result" and r["type"] == 0x12]
        assert len(starts) == 1 and starts[0] > events.index("config_accepted")
        assert events[-1] == "close"
        assert device.trace == ["STOP", "CONFIG:ping", "START:ping", "STOP"]
    asyncio.run(run())


def test_mismatched_ack_never_starts_and_preserves_diagnostic(tmp_path):
    class BadAck(proto.ReplayDevice):
        async def _send(self, kind, payload=b"", corrupt=False):
            if kind == proto.m1.MessageType.CONFIG_ACK:
                ack = proto.m1.parse_config_ack(payload)
                payload = proto.m1.pack_config_ack(proto.m1.ConfigAck(
                    ack.revision, ack.status, ack.actual_sample_period_ns, ack.actual_sample_count + 1))
            await super()._send(kind, payload, corrupt)
    async def run():
        device = BadAck(*fixture_inputs()); await device.start()
        service = HostService(config(device.slave_path, tmp_path), archive_enabled=True)
        try:
            await service.start()
            await wait_for(lambda: service.status.state == "ERROR")
            assert "geometry/revision mismatch" in service.status.last_error
            assert service.status.archived_pings == 0
            assert not any(t.startswith("START") for t in device.trace)
        finally:
            await service.stop(); await device.close()
    asyncio.run(run())


def test_wrong_hello_identity_aborts_before_config(tmp_path):
    async def run():
        device = proto.ReplayDevice(*fixture_inputs()); await device.start()
        service = HostService(config(device.slave_path, tmp_path),
                              expected_identity={"device_serial": "WRONG-DEVICE"}, archive_enabled=True)
        try:
            await service.start()
            await wait_for(lambda: service.status.state == "ERROR")
            assert "HELLO identity" in service.status.last_error
            assert not any(t.startswith(("CONFIG", "START")) for t in device.trace)
        finally:
            await service.stop(); await device.close()
    asyncio.run(run())


def test_unconfirmed_stop_blocks_configuration(tmp_path):
    async def run():
        device = proto.ReplayDevice(*fixture_inputs()); await device.start()
        transport = ObservedTransport(device.slave_path, audit_root=tmp_path / "wire")
        client = VerifiedClient(transport)
        try:
            await client.start()
            with pytest.raises(RuntimeError, match="confirmed stopped STATUS"):
                await client.configure(proto.ping_config())
            assert device.trace == []
        finally:
            await client.close(); await device.close()
    asyncio.run(run())


def test_missing_device_api_and_existing_state_preserved(tmp_path):
    sentinel = tmp_path / "existing.txt"; sentinel.write_text("keep\n")
    service = HostService(config(str(tmp_path / "absent-device"), tmp_path), archive_enabled=True)
    with TestClient(create_app(service)) as client:
        deadline = time.monotonic() + 2
        while time.monotonic() < deadline:
            status = client.get("/api/v1/status").json()
            if status["state"] == "WAIT_PICO": break
            time.sleep(.01)
        assert status["state"] == "WAIT_PICO"
        assert status["session"]["archived_pings"] == 0
        assert client.get("/").status_code == 200
        assert client.get("/static/app.js").status_code == 200
        assert client.get("/healthz").status_code == 200
    assert sentinel.read_text() == "keep\n"
    assert json.loads(service.final_status_path.read_text())["port_closed"]


def test_cli_requires_explicit_current_threshold_and_rejects_bad_geometry(tmp_path):
    common = ["--device", "test-only", "--expected-serial", "PTY-RECORDED-001",
              "--storage-root", str(tmp_path)]
    with pytest.raises(SystemExit): parse_args(common)
    with pytest.raises(SystemExit):
        parse_args(common + ["--trigger-threshold", "150", "--trigger-hysteresis", "200"])
    assert parse_args(common + ["--trigger-threshold", "150", "--trigger-hysteresis", "8"]).host == "127.0.0.1"
    with pytest.raises(ValueError, match="requires a measured marker A-B-A basis"):
        HostService(ServiceConfig(tx_reference_verified=True), archive_enabled=True)


def test_stop_rejects_reused_pid_before_sending_any_signal(tmp_path, monkeypatch):
    spec = importlib.util.spec_from_file_location("candidate_stop", ROOT / "tools/stop.py")
    control = importlib.util.module_from_spec(spec); spec.loader.exec_module(control)
    receipt = tmp_path / "pid.json"
    receipt.write_text(json.dumps({"pid": 90608, "package": "no5-sonard-openecho",
                                  "process_identity": "former candidate instance"}))
    monkeypatch.setattr(control.subprocess, "run", lambda *a, **k:
                        subprocess.CompletedProcess([], 0, "90608 running Scope instance\n"))
    sent = []
    monkeypatch.setattr(control.os, "kill", lambda *args: sent.append(args))
    with pytest.raises(ValueError, match="no longer matches"):
        control.stop(receipt)
    assert sent == []


def test_actual_cli_sigterm_completes_stop_archive_and_port_close(tmp_path):
    async def run():
        device = proto.ReplayDevice(*fixture_inputs()); await device.start()
        with socket.socket() as sock:
            sock.bind(("127.0.0.1", 0)); port = sock.getsockname()[1]
        wheel = Path(os.environ["SONARD_TEST_WHEEL"])
        log = (tmp_path / "cli.log").open("w")
        args = [sys.executable, "-B", str(ROOT / "tools/launch.py"), str(wheel),
                "--host", "127.0.0.1", "--port", str(port),
                "--device", device.slave_path, "--expected-serial", "PTY-RECORDED-001",
                "--storage-root", str(tmp_path / "state"),
                "--pid-file", str(tmp_path / "sonard.pid.json"),
                # Checks serialization/ACK of the proposed live profile.
                # ReplayDevice replays old samples and does not simulate triggers.
                "--trigger-threshold", "150", "--trigger-hysteresis", "8"]
        process = subprocess.Popen(args, stdout=log, stderr=subprocess.STDOUT)
        try:
            await wait_for(lambda: "START:ping" in device.trace, 10)
            def get_status():
                with urllib.request.urlopen(f"http://127.0.0.1:{port}/api/v1/status", timeout=1) as reply:
                    return json.load(reply)
            deadline = time.monotonic() + 5
            while time.monotonic() < deadline:
                status = await asyncio.to_thread(get_status)
                if status["display_selection"]["counts"]["received"] == 35: break
                await asyncio.sleep(.02)
            assert status["display_selection"]["counts"]["received"] == 35
            assert status["accepted_config"]["requested"]["trigger_threshold"] == 150
            stopping = await asyncio.to_thread(subprocess.run,
                [sys.executable, "-B", str(ROOT / "tools/stop.py"),
                 "--pid-file", str(tmp_path / "sonard.pid.json")],
                capture_output=True, text=True, timeout=15)
            assert stopping.returncode == 0, stopping.stdout + stopping.stderr
            assert json.loads(stopping.stdout)["stop_status_confirmed"]
            # Uvicorn can re-raise SIGTERM after its completed lifespan shutdown.
            # The receipts/STOP/archive checks below distinguish graceful closure.
            assert await asyncio.to_thread(process.wait, 8) in (0, -signal.SIGTERM)
            assert status["session"]["archived_pings_total"] == 0
            assert not (tmp_path / "state" / "sessions").exists()
            final_path = tmp_path / "sonard.pid.shutdown.json"
            final = json.loads(final_path.read_text())
            assert final["all_connections_stop_confirmed"]
            assert final["archive_closed"] and final["port_closed"]
            assert device.trace[-1] == "STOP"
        finally:
            if process.poll() is None:
                process.send_signal(signal.SIGTERM)
                await asyncio.to_thread(process.wait, 8)
            log.close(); await device.close()
    asyncio.run(run())
