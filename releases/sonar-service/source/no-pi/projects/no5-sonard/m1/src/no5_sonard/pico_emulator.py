from __future__ import annotations

import argparse
import asyncio
import math
import os
import pty
import random
import struct
import time
import tty

import numpy as np

from .pico_protocol import (
    ConfigAck, Hello, MessageType, Packet, PicoStatus, PingFrame, TimeSyncResponse,
    cobs_decode, decode_packet, encode_packet, pack_config_ack, pack_hello, pack_ping_frame,
    pack_status, pack_time_sync_resp, parse_config_set, parse_time_sync_req,
)


class PtyPicoEmulator:
    def __init__(self, *, ping_hz: float = 20.0, serial: str = "NO5EMU000000001") -> None:
        self.master_fd, self.slave_fd = pty.openpty()
        tty.setraw(self.master_fd)
        tty.setraw(self.slave_fd)
        self.slave_path = os.ttyname(self.slave_fd)
        os.set_blocking(self.master_fd, False)
        self.ping_hz = ping_hz
        self.serial = serial[:16]
        self._rx = bytearray()
        self._seq = 0
        self._ping_id = 0
        self._start_ns = time.monotonic_ns()
        self._configured = False
        self._running = False
        self.sample_period_ns = 4000
        self.sample_count = 4096
        self.pretrigger = 128
        self.trigger_mode = 2
        self.signal_mode = 1
        self._task: asyncio.Task[None] | None = None
        self._write_lock = asyncio.Lock()
        self.crc_corrupt_every = 0

    def device_us(self) -> int:
        return (time.monotonic_ns() - self._start_ns) // 1000

    async def start(self) -> None:
        loop = asyncio.get_running_loop()
        loop.add_reader(self.master_fd, self._on_readable)
        self._task = asyncio.create_task(self._run(), name="pico-emulator")

    async def stop(self) -> None:
        if self._task:
            self._task.cancel()
            try:
                await self._task
            except asyncio.CancelledError:
                pass
        loop = asyncio.get_running_loop()
        loop.remove_reader(self.master_fd)
        for fd in (self.master_fd, self.slave_fd):
            try:
                os.close(fd)
            except OSError:
                pass

    def _next_seq(self) -> int:
        v = self._seq
        self._seq = (self._seq + 1) & 0xFFFFFFFF
        return v

    async def _send(self, kind: MessageType, payload: bytes = b"") -> None:
        data = encode_packet(Packet(kind, self._next_seq(), 0, payload))
        async with self._write_lock:
            view = memoryview(data)
            while view:
                try:
                    n = os.write(self.master_fd, view)
                    view = view[n:]
                except BlockingIOError:
                    await asyncio.sleep(0.0005)

    def _on_readable(self) -> None:
        try:
            while True:
                chunk = os.read(self.master_fd, 65536)
                if not chunk:
                    return
                self._rx.extend(chunk)
                while 0 in self._rx:
                    idx = self._rx.index(0)
                    frame = bytes(self._rx[:idx])
                    del self._rx[: idx + 1]
                    if frame:
                        asyncio.create_task(self._handle(frame))
                if len(chunk) < 65536:
                    return
        except BlockingIOError:
            return

    async def _handle(self, encoded: bytes) -> None:
        try:
            pkt = decode_packet(encoded)
        except ValueError:
            return
        if pkt.type is MessageType.HELLO_ACK:
            return
        if pkt.type is MessageType.CONFIG_SET:
            cfg = parse_config_set(pkt.payload)
            status = 0
            if cfg.sample_count <= 0 or cfg.sample_count > 65536 or cfg.sample_period_ns < 2000:
                status = 1
            else:
                self.sample_period_ns = cfg.sample_period_ns
                self.sample_count = cfg.sample_count
                self.pretrigger = min(cfg.pretrigger_samples, cfg.sample_count // 4)
                self.trigger_mode = cfg.trigger_mode
                self.signal_mode = cfg.signal_mode
                self._configured = True
            await self._send(MessageType.CONFIG_ACK, pack_config_ack(ConfigAck(
                cfg.revision, status, self.sample_period_ns, self.sample_count)))
        elif pkt.type is MessageType.TIME_SYNC_REQ:
            token = parse_time_sync_req(pkt.payload)
            rx = self.device_us()
            # emulate a small, varying firmware scheduling delay
            await asyncio.sleep(random.uniform(0.0001, 0.0008))
            tx = self.device_us()
            await self._send(MessageType.TIME_SYNC_RESP, pack_time_sync_resp(TimeSyncResponse(token, rx, tx)))
        elif pkt.type is MessageType.START:
            self._running = True
        elif pkt.type is MessageType.STOP:
            self._running = False

    def _samples(self) -> tuple[np.ndarray, int]:
        tx0 = self.pretrigger
        m_per_sample = self.sample_period_ns * 1e-9 * 1500.0 / 2.0
        axis = (np.arange(self.sample_count, dtype=np.float32) - tx0) * m_per_sample
        t = self.device_us() / 1_000_000.0
        bottom = 11.5 + 1.8 * math.sin(t / 7.0) + 0.2 * math.sin(t / 1.7)
        rng = np.random.default_rng(self._ping_id + 123)
        s = rng.normal(550.0, 90.0, self.sample_count).astype(np.float32)
        s += 7000.0 * np.exp(-0.5 * ((axis - 0.15) / 0.15) ** 2)
        s += 43000.0 * np.exp(-0.5 * ((axis - bottom) / 0.22) ** 2)
        fish = 6.3 + 0.6 * math.sin(t / 2.0)
        s += 12000.0 * np.exp(-0.5 * ((axis - fish) / 0.09) ** 2)
        return np.clip(s, 0, 65535).astype(np.uint16), tx0

    async def _run(self) -> None:
        last_hello = 0.0
        last_status = 0.0
        next_ping = time.monotonic()
        while True:
            now = time.monotonic()
            if now - last_hello >= 1.0 and not self._configured:
                hello = Hello(1, 0, 0, 0b101111, 500_000, 65_536, 12, 3300, self.serial)
                await self._send(MessageType.HELLO, pack_hello(hello))
                last_hello = now
            if now - last_status >= 1.0:
                st = PicoStatus(self.device_us(), self._ping_id, 0, 0, 0, 0, 0, 26000)
                await self._send(MessageType.STATUS, pack_status(st))
                last_status = now
            if self._running and self._configured and now >= next_ping:
                self._ping_id += 1
                samples, tx0 = self._samples()
                trigger_us = self.device_us()
                pf = PingFrame(self._ping_id, trigger_us, self.sample_period_ns, tx0,
                               int(samples.min()), int(samples.max()), 1, samples)
                await self._send(MessageType.PING_FRAME, pack_ping_frame(pf))
                next_ping += 1.0 / self.ping_hz
                if next_ping < now - 0.25:
                    next_ping = now
            await asyncio.sleep(0.001)


async def _main(args: argparse.Namespace) -> None:
    emu = PtyPicoEmulator(ping_hz=args.ping_hz)
    await emu.start()
    print(emu.slave_path, flush=True)
    try:
        while True:
            await asyncio.sleep(3600)
    finally:
        await emu.stop()


def main() -> None:
    p = argparse.ArgumentParser(description="No 5 PTY Pico emulator")
    p.add_argument("--ping-hz", type=float, default=20.0)
    args = p.parse_args()
    asyncio.run(_main(args))


if __name__ == "__main__":
    main()
