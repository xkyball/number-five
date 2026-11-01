from __future__ import annotations

import asyncio
from collections import defaultdict
import random
import time

from ..clock_sync import PicoClockMapper
from ..pico_protocol import (
    ConfigAck, ConfigSet, Hello, MessageType, Packet, PicoStatus, PingFrame,
    TimeSyncResponse, pack_config_set, pack_hello_ack, pack_time_sync_req,
    parse_config_ack, parse_hello, parse_ping_frame, parse_status, parse_time_sync_resp,
)
from ..transport.serial_posix import PosixSerialTransport, SerialDisconnected


class PicoClient:
    def __init__(self, transport: PosixSerialTransport, *, ping_queue_size: int = 256,
                 clock_window: int = 16, max_rtt_ms: float = 20.0) -> None:
        self.transport = transport
        self.clock = PicoClockMapper(window=clock_window, max_rtt_ms=max_rtt_ms)
        self.pings: asyncio.Queue[PingFrame] = asyncio.Queue(maxsize=ping_queue_size)
        self.status: PicoStatus | None = None
        self.hello: Hello | None = None
        self._seq = random.randrange(0, 2**32)
        self._rx_task: asyncio.Task[None] | None = None
        self._hello_event = asyncio.Event()
        self._disconnect = asyncio.Event()
        self._config_waiters: dict[int, asyncio.Future[ConfigAck]] = {}
        self._sync_waiters: dict[int, tuple[int, asyncio.Future[tuple[TimeSyncResponse, int]]]] = {}
        self.missed_ping_queue = 0
        self.last_packet_seq: int | None = None
        self.packet_seq_gaps = 0
        self.last_ping_id: int | None = None
        self.ping_id_gaps = 0

    def _next_seq(self) -> int:
        value = self._seq
        self._seq = (self._seq + 1) & 0xFFFFFFFF
        return value

    async def start(self) -> None:
        await self.transport.open()
        self._rx_task = asyncio.create_task(self._rx_loop(), name="pico-rx")

    async def close(self) -> None:
        if self._rx_task:
            self._rx_task.cancel()
            try:
                await self._rx_task
            except (asyncio.CancelledError, Exception):
                pass
        await self.transport.close()
        self._disconnect.set()

    async def send(self, msg_type: MessageType, payload: bytes = b"", flags: int = 0) -> None:
        await self.transport.send_packet(Packet(msg_type, self._next_seq(), flags, payload))

    async def wait_hello(self, timeout: float = 5.0) -> Hello:
        await asyncio.wait_for(self._hello_event.wait(), timeout)
        assert self.hello is not None
        return self.hello

    async def acknowledge_hello(self) -> None:
        await self.send(MessageType.HELLO_ACK, pack_hello_ack())

    async def configure(self, config: ConfigSet, timeout: float = 3.0) -> ConfigAck:
        loop = asyncio.get_running_loop()
        fut: asyncio.Future[ConfigAck] = loop.create_future()
        self._config_waiters[config.revision] = fut
        await self.send(MessageType.CONFIG_SET, pack_config_set(config))
        try:
            ack = await asyncio.wait_for(fut, timeout)
        finally:
            self._config_waiters.pop(config.revision, None)
        if ack.status != 0:
            raise RuntimeError(f"Pico rejected config revision {ack.revision}: status={ack.status}")
        return ack

    async def sync_once(self, timeout: float = 1.0) -> bool:
        token = random.randrange(1, 2**32)
        loop = asyncio.get_running_loop()
        fut: asyncio.Future[tuple[TimeSyncResponse, int]] = loop.create_future()
        t1 = time.monotonic_ns()
        self._sync_waiters[token] = (t1, fut)
        await self.send(MessageType.TIME_SYNC_REQ, pack_time_sync_req(token))
        try:
            resp, t4 = await asyncio.wait_for(fut, timeout)
        finally:
            self._sync_waiters.pop(token, None)
        return self.clock.add_exchange(host_t1_ns=t1, host_t4_ns=t4,
                                       pico_rx_us=resp.pico_rx_us, pico_tx_us=resp.pico_tx_us)

    async def synchronize(self, exchanges: int = 8) -> None:
        good = 0
        attempts = 0
        while good < exchanges and attempts < exchanges * 3:
            attempts += 1
            try:
                if await self.sync_once():
                    good += 1
            except asyncio.TimeoutError:
                pass
            await asyncio.sleep(0.01)
        if good < max(2, exchanges // 2) or not self.clock.ready:
            raise RuntimeError(f"insufficient clock sync samples: {good}/{attempts}")

    async def start_acquisition(self) -> None:
        await self.send(MessageType.START)

    async def stop_acquisition(self) -> None:
        await self.send(MessageType.STOP)

    async def wait_disconnected(self) -> None:
        await self._disconnect.wait()

    async def _rx_loop(self) -> None:
        try:
            while True:
                packet = await self.transport.recv_packet()
                self._track_packet_seq(packet.seq)
                t4 = time.monotonic_ns()
                if packet.type is MessageType.HELLO:
                    self.hello = parse_hello(packet.payload)
                    self._hello_event.set()
                elif packet.type is MessageType.CONFIG_ACK:
                    ack = parse_config_ack(packet.payload)
                    fut = self._config_waiters.get(ack.revision)
                    if fut and not fut.done():
                        fut.set_result(ack)
                elif packet.type is MessageType.TIME_SYNC_RESP:
                    resp = parse_time_sync_resp(packet.payload)
                    pending = self._sync_waiters.get(resp.token)
                    if pending:
                        _, fut = pending
                        if not fut.done():
                            fut.set_result((resp, t4))
                elif packet.type is MessageType.STATUS:
                    self.status = parse_status(packet.payload)
                elif packet.type is MessageType.PING_FRAME:
                    ping = parse_ping_frame(packet.payload)
                    self._track_ping_id(ping.ping_id)
                    if self.pings.full():
                        try:
                            self.pings.get_nowait()
                            self.missed_ping_queue += 1
                        except asyncio.QueueEmpty:
                            pass
                    self.pings.put_nowait(ping)
                elif packet.type is MessageType.ERROR:
                    # Device errors are surfaced by status counters; unknown error payloads
                    # are deliberately not fatal to the transport.
                    pass
        except (SerialDisconnected, OSError):
            self._disconnect.set()
        except asyncio.CancelledError:
            raise
        except Exception:
            self._disconnect.set()
            raise

    def _track_packet_seq(self, seq: int) -> None:
        if self.last_packet_seq is not None:
            expected = (self.last_packet_seq + 1) & 0xFFFFFFFF
            if seq != expected:
                self.packet_seq_gaps += (seq - expected) & 0xFFFFFFFF
        self.last_packet_seq = seq

    def _track_ping_id(self, ping_id: int) -> None:
        if self.last_ping_id is not None and ping_id > self.last_ping_id + 1:
            self.ping_id_gaps += ping_id - self.last_ping_id - 1
        self.last_ping_id = ping_id
