from __future__ import annotations

import asyncio
from collections import deque
from dataclasses import dataclass, field
import json
import math
from pathlib import Path
import time
import uuid

from .config import ServiceConfig
from .hub import LatestFrameHub
from .pico.client import PicoClient
from .pico_protocol import ConfigSet, PingFrame
from .processing_m1 import detect_bottom_m1, max_pool_resample
from .protocol import pack_echogram_frame
from .storage.raw_zarr import RawPingRecord, RawZarrV2Writer
from .transport.serial_posix import PosixSerialTransport, SerialDisconnected


@dataclass(slots=True)
class RuntimeStatus:
    state: str = "BOOT"
    started_monotonic: float = field(default_factory=time.monotonic)
    last_ping_id: int = 0
    last_ping_utc_ns: int | None = None
    last_bottom_depth_m: float | None = None
    last_bottom_confidence: float = 0.0
    unverified_pings: int = 0
    last_tx_reference_valid: bool = False
    last_sample_count: int = 0
    last_sample_period_ns: int = 0
    ping_timestamps: deque[float] = field(default_factory=lambda: deque(maxlen=200))
    session_id: str | None = None
    segment_id: str | None = None
    archived_pings: int = 0
    reconnects: int = 0
    last_error: str | None = None


class SonarService:
    def __init__(self, config: ServiceConfig | None = None) -> None:
        self.config = config or ServiceConfig()
        self.hub = LatestFrameHub(queue_size=4)
        self.status = RuntimeStatus()
        self._task: asyncio.Task[None] | None = None
        self._running = False
        self._client: PicoClient | None = None
        self._writer: RawZarrV2Writer | None = None
        self._session_path: Path | None = None

    async def start(self) -> None:
        if self._task and not self._task.done():
            return
        self._running = True
        self._ensure_session()
        self._task = asyncio.create_task(self._supervisor(), name="no5-sonard-supervisor")

    async def stop(self) -> None:
        self._running = False
        if self._client:
            try:
                await self._client.stop_acquisition()
            except Exception:
                pass
        if self._task:
            self._task.cancel()
            try:
                await self._task
            except asyncio.CancelledError:
                pass
        self._task = None
        if self._client:
            await self._client.close()
            self._client = None
        if self._writer:
            self._writer.close()
            self._writer = None
        self.status.state = "IDLE"

    def _ensure_session(self) -> None:
        if self.status.session_id:
            return
        sid = str(uuid.uuid4())
        self.status.session_id = sid
        self._session_path = self.config.storage_root / "sessions" / sid
        self._session_path.mkdir(parents=True, exist_ok=True)
        session = {
            "schema_version": "no5.session.m1/1.0",
            "session_id": sid,
            "start_time_utc": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
            "source": self.config.source,
            "origin_operator_selected": self.config.origin_operator_selected,
            "tx_reference_verified": self.config.tx_reference_verified,
        }
        (self._session_path / "session.json").write_text(json.dumps(session, indent=2) + "\n")

    async def _supervisor(self) -> None:
        if self.config.source != "pico":
            raise RuntimeError("M1 package currently expects --source pico; M0 remains available separately")
        while self._running:
            try:
                await self._run_pico_connection()
            except asyncio.CancelledError:
                raise
            except Exception as exc:
                self.status.last_error = f"{type(exc).__name__}: {exc}"
                self.status.state = "WAIT_PICO"
            finally:
                if self._writer:
                    self._writer.close()
                    self._writer = None
                if self._client:
                    await self._client.close()
                    self._client = None
            if self._running:
                self.status.reconnects += 1
                await asyncio.sleep(1.0)

    async def _run_pico_connection(self) -> None:
        self.status.state = "WAIT_PICO"
        transport = PosixSerialTransport(self.config.device)
        client = PicoClient(transport)
        self._client = client
        await client.start()
        await client.stop_acquisition()
        await client.acknowledge_hello()
        self.status.state = "HANDSHAKE"
        hello = await client.wait_hello(timeout=5.0)
        await client.acknowledge_hello()
        await client.synchronize(self.config.clock_initial_exchanges)
        caps = hello.capabilities
        if self.config.trigger_mode == 1 and not (caps & (1 << 0)):
            raise RuntimeError("Pico does not support external GPIO trigger")
        if self.config.trigger_mode == 2 and not (caps & (1 << 1)):
            raise RuntimeError("Pico does not support analog-threshold trigger")
        if self.config.signal_mode == 1 and not (caps & (1 << 3)):
            raise RuntimeError("Pico does not support envelope signal mode")
        if self.config.signal_mode == 2 and not (caps & (1 << 4)):
            raise RuntimeError("Pico does not support carrier signal mode")
        pretrigger = self.config.pretrigger_samples if (caps & (1 << 2)) else 0
        cfg = ConfigSet(
            revision=int(time.time()) & 0xFFFFFFFF,
            sample_period_ns=self.config.sample_period_ns,
            sample_count=min(self.config.sample_count, hello.max_samples),
            pretrigger_samples=pretrigger,
            trigger_mode=self.config.trigger_mode,
            signal_mode=self.config.signal_mode,
            trigger_threshold=self.config.trigger_threshold,
            trigger_hysteresis=self.config.trigger_hysteresis,
            capture_timeout_us=self.config.capture_timeout_us,
        )
        ack = await client.configure(cfg)
        self._open_segment(hello.device_serial, ack.actual_sample_count, ack.actual_sample_period_ns)
        await client.start_acquisition()
        self.status.state = "RUNNING"
        sync_task = asyncio.create_task(self._periodic_sync(client), name="pico-clock-sync")
        disconnect_task = asyncio.create_task(client.wait_disconnected(), name="pico-disconnect")
        try:
            while self._running:
                ping_task = asyncio.create_task(client.pings.get())
                done, _ = await asyncio.wait({ping_task, disconnect_task}, return_when=asyncio.FIRST_COMPLETED)
                if disconnect_task in done:
                    ping_task.cancel()
                    raise SerialDisconnected("Pico disconnected")
                ping = ping_task.result()
                await self._handle_ping(ping, client)
        finally:
            sync_task.cancel()
            disconnect_task.cancel()
            for task in (sync_task, disconnect_task):
                try:
                    await task
                except (asyncio.CancelledError, Exception):
                    pass

    async def _periodic_sync(self, client: PicoClient) -> None:
        while True:
            await asyncio.sleep(self.config.clock_sync_interval_s)
            try:
                await client.sync_once(timeout=1.0)
            except Exception:
                # Clock mapping remains usable from prior samples; status exposes staleness via RTT/sample count.
                pass

    def _open_segment(self, device_serial: str, sample_count: int, sample_period_ns: int) -> None:
        assert self._session_path is not None
        seg = str(uuid.uuid4())
        self.status.segment_id = seg
        path = self._session_path / "raw" / f"{seg}.zarr"
        attrs = {
            "schema": "no5.raw-ping/1.0",
            "protocol_version": 1,
            "device_serial": device_serial,
            "sample_period_ns": sample_period_ns,
            "sample_count": sample_count,
            "signal_mode": self.config.signal_mode,
            "trigger_mode": self.config.trigger_mode,
            "origin_operator_selected": self.config.origin_operator_selected,
            "tx_reference_verified": self.config.tx_reference_verified,
            "created_utc": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        }
        self._writer = RawZarrV2Writer(path, sample_count=sample_count,
                                       sample_period_ns=sample_period_ns,
                                       chunk_pings=self.config.zarr_chunk_pings,
                                       attrs=attrs)
        self.status.archived_pings = self._writer.count

    async def _handle_ping(self, ping: PingFrame, client: PicoClient) -> None:
        if not client.clock.ready:
            return
        time_utc_ns = client.clock.pico_us_to_utc_ns(ping.trigger_device_time_us)
        writer = self._writer
        if writer is None:
            raise RuntimeError("raw writer not open")
        record = RawPingRecord(
            ping_id=ping.ping_id,
            time_utc_ns=time_utc_ns,
            trigger_device_time_us=ping.trigger_device_time_us,
            sample_period_ns=ping.sample_period_ns,
            tx_zero_sample_index=ping.tx_zero_sample_index,
            ping_flags=ping.ping_flags,
            adc_min=ping.adc_min,
            adc_max=ping.adc_max,
            samples=ping.samples,
        )
        writer.append(record)
        self.status.archived_pings = writer.count + writer.pending_count  # operational count incl. WAL

        # Archive every valid packet. Depth needs a separately verified TX origin.
        invalid_flags = (1 << 2) | (1 << 4) | (1 << 5) | (1 << 6)
        valid_tx = (self.config.tx_reference_verified and bool(ping.ping_flags & 1)
                    and not (ping.ping_flags & invalid_flags)
                    and 0 <= ping.tx_zero_sample_index < ping.samples.size)
        start_index = max(0, ping.tx_zero_sample_index) if valid_tx else 0
        depth, confidence, max_depth = math.nan, 0.0, math.nan
        if valid_tx:
            det = detect_bottom_m1(
                ping.samples,
                tx_zero_sample_index=ping.tx_zero_sample_index,
                sample_period_ns=ping.sample_period_ns,
                sound_speed_mps=self.config.sound_speed_mps,
                blanking_m=self.config.blanking_m,
            )
            depth, confidence = det.depth_m, det.confidence
            post_samples = ping.samples.size - start_index
            max_depth = post_samples * ping.sample_period_ns * 1e-9 * self.config.sound_speed_mps / 2.0
        else:
            self.status.unverified_pings += 1
        display = max_pool_resample(ping.samples, start_index=start_index, bins=self.config.display_bins)
        frame = pack_echogram_frame(
            ping_id=ping.ping_id,
            time_utc_ns=time_utc_ns,
            max_depth_m=max_depth,
            bottom_depth_m=depth if confidence > 0.1 else math.nan,
            bottom_confidence=confidence,
            flags=ping.ping_flags if valid_tx else (ping.ping_flags & ~1) | (1 << 5),
            amplitudes=display,
        )
        await self.hub.publish(frame)
        now = time.monotonic()
        self.status.last_ping_id = ping.ping_id
        self.status.last_ping_utc_ns = time_utc_ns
        self.status.last_bottom_depth_m = depth if valid_tx else None
        self.status.last_bottom_confidence = confidence
        self.status.last_tx_reference_valid = valid_tx
        self.status.last_sample_count = ping.samples.size
        self.status.last_sample_period_ns = ping.sample_period_ns
        self.status.ping_timestamps.append(now)

    def snapshot(self) -> dict:
        ts = list(self.status.ping_timestamps)
        ping_hz = 0.0
        if len(ts) >= 2 and ts[-1] > ts[0]:
            ping_hz = (len(ts) - 1) / (ts[-1] - ts[0])
        client = self._client
        transport = client.transport if client else None
        return {
            "version": "0.2.0",
            "milestone": "M1",
            "state": self.status.state,
            "source": self.config.source,
            "uptime_s": round(time.monotonic() - self.status.started_monotonic, 3),
            "session": {"id": self.status.session_id, "segment_id": self.status.segment_id,
                        "archived_pings": self.status.archived_pings},
            "pico": {
                "connected": bool(transport and transport.is_open),
                "device": self.config.device,
                "serial": None if not client or not client.hello else client.hello.device_serial,
                "packet_seq_gaps": 0 if not client else client.packet_seq_gaps,
                "ping_id_gaps": 0 if not client else client.ping_id_gaps,
                "queue_drops": 0 if not client else client.missed_ping_queue,
                "frame_errors": 0 if not transport else transport.frame_errors,
                "crc_errors": 0 if not transport else transport.crc_errors,
                "bytes_rx": 0 if not transport else transport.bytes_rx,
                "bytes_tx": 0 if not transport else transport.bytes_tx,
            },
            "clock": {} if not client else client.clock.snapshot(),
            "rates": {"ping_hz": round(ping_hz, 3)},
            "bottom": {
                "depth_m": None if self.status.last_bottom_depth_m is None else round(self.status.last_bottom_depth_m, 3),
                "confidence": round(self.status.last_bottom_confidence, 4),
            },
            "acquisition": {
                "origin_operator_selected": self.config.origin_operator_selected,
                "tx_reference_verified": self.config.tx_reference_verified,
                "last_tx_reference_valid": self.status.last_tx_reference_valid,
                "unverified_pings": self.status.unverified_pings,
                "sample_count": self.status.last_sample_count,
                "sample_period_ns": self.status.last_sample_period_ns,
            },
            "ping_id": self.status.last_ping_id,
            "echogram_clients": self.hub.client_count,
            "reconnects": self.status.reconnects,
            "last_error": self.status.last_error,
        }
