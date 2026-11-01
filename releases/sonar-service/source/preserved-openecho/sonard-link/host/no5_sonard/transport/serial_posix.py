from __future__ import annotations

import asyncio
import errno
import fcntl
import os
import struct
from pathlib import Path
import termios
import tty
from typing import Final

from ..pico_protocol import MAX_DECODED_PACKET, Packet, decode_packet, encode_packet

MAX_ENCODED_FRAME: Final[int] = MAX_DECODED_PACKET + (MAX_DECODED_PACKET // 254) + 32


class SerialDisconnected(ConnectionError):
    pass


class PosixSerialTransport:
    """Binary USB CDC/TTY transport with no pyserial dependency.

    Linux USB CDC ignores the configured baud rate, but we still put the TTY in
    raw mode so no line discipline can modify binary COBS frames.
    """

    def __init__(self, device: str, *, queue_size: int = 256) -> None:
        self.device = device
        self.fd: int | None = None
        self._rx = bytearray()
        self._packets: asyncio.Queue[Packet | BaseException] = asyncio.Queue(maxsize=queue_size)
        self.frame_errors = 0
        self.crc_errors = 0
        self.bytes_rx = 0
        self.bytes_tx = 0
        self._loop: asyncio.AbstractEventLoop | None = None
        self._closed = True

    async def open(self) -> None:
        if self.fd is not None:
            return
        fd = os.open(self.device, os.O_RDWR | os.O_NOCTTY | os.O_NONBLOCK)
        try:
            tty.setraw(fd, when=termios.TCSANOW)
            attrs = termios.tcgetattr(fd)
            attrs[2] |= termios.CLOCAL | termios.CREAD
            attrs[2] &= ~termios.HUPCL
            termios.tcsetattr(fd, termios.TCSANOW, attrs)
            if hasattr(termios, "TIOCEXCL"):
                fcntl.ioctl(fd, termios.TIOCEXCL)
            termios.tcflush(fd, termios.TCIOFLUSH)
            # TinyUSB CDC uses the host DTR signal for tud_cdc_connected().
            # Linux usually asserts it on open, but set it explicitly where supported.
            try:
                modem_bits = termios.TIOCM_DTR | termios.TIOCM_RTS
                fcntl.ioctl(fd, termios.TIOCMBIS, struct.pack("I", modem_bits))
            except (OSError, AttributeError):
                pass
        except Exception:
            os.close(fd)
            raise
        self.fd = fd
        self._loop = asyncio.get_running_loop()
        self._loop.add_reader(fd, self._on_readable)
        self._closed = False

    async def close(self) -> None:
        if self.fd is None:
            return
        fd, self.fd = self.fd, None
        self._closed = True
        if self._loop is not None:
            try:
                self._loop.remove_reader(fd)
            except Exception:
                pass
            try:
                self._loop.remove_writer(fd)
            except Exception:
                pass
        try:
            os.close(fd)
        except OSError:
            pass
        self._offer_exception(SerialDisconnected("serial transport closed"))

    @property
    def is_open(self) -> bool:
        return self.fd is not None and not self._closed

    def _offer_exception(self, exc: BaseException) -> None:
        if self._packets.full():
            try:
                self._packets.get_nowait()
            except asyncio.QueueEmpty:
                pass
        try:
            self._packets.put_nowait(exc)
        except asyncio.QueueFull:
            pass

    def _on_readable(self) -> None:
        if self.fd is None:
            return
        try:
            while True:
                chunk = os.read(self.fd, 65536)
                if not chunk:
                    self._offer_exception(SerialDisconnected("serial EOF"))
                    return
                self.bytes_rx += len(chunk)
                self._rx.extend(chunk)
                if len(self._rx) > MAX_ENCODED_FRAME * 2:
                    self._rx.clear()
                    self.frame_errors += 1
                self._drain_frames()
                if len(chunk) < 65536:
                    return
        except BlockingIOError:
            return
        except OSError as exc:
            if exc.errno in (errno.EAGAIN, errno.EWOULDBLOCK):
                return
            self._offer_exception(SerialDisconnected(str(exc)))

    def _drain_frames(self) -> None:
        while True:
            try:
                idx = self._rx.index(0)
            except ValueError:
                return
            frame = bytes(self._rx[:idx])
            del self._rx[: idx + 1]
            if not frame:
                continue
            if len(frame) > MAX_ENCODED_FRAME:
                self.frame_errors += 1
                continue
            try:
                packet = decode_packet(frame)
            except ValueError as exc:
                self.frame_errors += 1
                if "CRC32" in str(exc):
                    self.crc_errors += 1
                continue
            if self._packets.full():
                # Acquisition must notice overflow; dropping an old packet is
                # preferable to unbounded process memory.
                try:
                    self._packets.get_nowait()
                except asyncio.QueueEmpty:
                    pass
                self.frame_errors += 1
            try:
                self._packets.put_nowait(packet)
            except asyncio.QueueFull:
                self.frame_errors += 1

    async def recv_packet(self, timeout: float | None = None) -> Packet:
        try:
            item = await asyncio.wait_for(self._packets.get(), timeout) if timeout is not None else await self._packets.get()
        except asyncio.TimeoutError:
            raise
        if isinstance(item, BaseException):
            raise item
        return item

    async def send_packet(self, packet: Packet) -> None:
        if self.fd is None:
            raise SerialDisconnected("serial not open")
        data = encode_packet(packet)
        view = memoryview(data)
        while view:
            try:
                n = os.write(self.fd, view)
                if n <= 0:
                    raise SerialDisconnected("serial write returned zero")
                self.bytes_tx += n
                view = view[n:]
            except BlockingIOError:
                await self._wait_writable()
            except OSError as exc:
                raise SerialDisconnected(str(exc)) from exc

    async def _wait_writable(self) -> None:
        if self.fd is None or self._loop is None:
            raise SerialDisconnected("serial not open")
        fut: asyncio.Future[None] = self._loop.create_future()
        fd = self.fd

        def ready() -> None:
            if not fut.done():
                fut.set_result(None)
            if self._loop is not None:
                self._loop.remove_writer(fd)

        self._loop.add_writer(fd, ready)
        try:
            await fut
        finally:
            if self._loop is not None:
                self._loop.remove_writer(fd)
