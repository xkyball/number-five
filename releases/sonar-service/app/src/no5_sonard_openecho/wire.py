from __future__ import annotations

import asyncio
from dataclasses import asdict
import errno
import json
import os
import sys
from pathlib import Path
import time
from .archive_fault import ArchiveFault

from no5_sonard.pico.client import PicoClient
from no5_sonard.pico_protocol import (
    MessageType, decode_packet, encode_packet, parse_status,
)
from no5_sonard.transport.serial_posix import (
    MAX_ENCODED_FRAME, PosixSerialTransport, SerialDisconnected,
)


class ObservedTransport(PosixSerialTransport):
    """Existing exclusive transport plus an auditable COBS start boundary.

    Opening a USB CDC endpoint can happen while its peer is part-way through a
    COBS frame.  A delimiter observed after that open is only a boundary, not
    evidence that the preceding bytes form a packet.  Before the first valid
    packet this transport therefore records rejected candidates separately;
    once a valid packet establishes the boundary, ordinary framing/CRC errors
    use the inherited counters again.
    """

    dispatches_packet_handler = True

    def __init__(self, device, *, audit_root: Path, expected_identity=None,
                 journal_root=None, volume_guard=None, recorder=None, **kwargs):
        super().__init__(device, **kwargs)
        self.recorder = recorder
        self.audit_root = Path(audit_root) if audit_root is not None else None
        self.expected_identity = expected_identity
        self.status_generation = 0
        self.status_running = None
        self.status_updated = asyncio.Event()
        self.last_device_status = None
        self._audit = {}
        self._wire_journal = None
        self.journal_root, self.volume_guard = journal_root, volume_guard
        self.archive_fault_handler = None
        self._startup_synchronized = False
        self._startup_sync_event = asyncio.Event()
        self._startup_discarded_frames = 0
        self._startup_discarded_bytes = 0
        self._startup_first_packet = None
        self.transmit_handler = None
        self.packet_handler = None
        self._read_poll_interval_s = .01 if sys.platform == "darwin" else .1
        self._packet_silence_limit_s = 3.
        self._read_poll_handle = None
        self._last_byte_at = self._last_packet_at = None
        self._silence_faulted = False
        self._poll_read_bytes = 0
        self._send_lock = asyncio.Lock()
        self._archive_fault_settling = False
        self._fault_read_fragment = None
        self._settlement_audit_errors = []

    def liveness_snapshot(self):
        now = time.monotonic()
        byte_age = None if self._last_byte_at is None else max(0., now-self._last_byte_at)
        packet_age = None if self._last_packet_at is None else max(0., now-self._last_packet_at)
        return {"state": "silent" if self._silence_faulted else "open" if self.is_open else "closed",
                "last_byte_age_s": byte_age, "last_packet_age_s": packet_age,
                "read_poll_interval_s": self._read_poll_interval_s,
                "packet_silence_limit_s": self._packet_silence_limit_s,
                "poll_read_bytes": self._poll_read_bytes, "raw_samples_modified": False}

    def _poll_receive(self):
        if self.fd is None or self._closed:
            return
        # Same event loop, same exclusive FD and framing path. A missed TTY
        # readiness notification must not leave an already buffered USB frame
        # undrained. No second reader, packet fabrication or sample conversion.
        before = self.bytes_rx
        self._on_readable()
        if self._silence_faulted:
            return
        self._poll_read_bytes += self.bytes_rx-before
        if (not self._silence_faulted and self._last_packet_at is not None
                and time.monotonic()-self._last_packet_at > self._packet_silence_limit_s):
            self._silence_faulted = True
            try:
                self.trace("serial_packet_silence", **self.liveness_snapshot())
            except ArchiveFault as exc:
                self._offer_exception(exc)
            else:
                self._offer_exception(SerialDisconnected(
                    f"Pico USB packet silence exceeded {self._packet_silence_limit_s:g}s; rebind unarmed"))
        self._read_poll_handle = self._loop.call_later(self._read_poll_interval_s, self._poll_receive)

    def trace(self, event, **fields):
        if self._audit or self.recorder is not None:
            row = {"event": event, "host_monotonic_ns": time.monotonic_ns(),
                   "host_utc_ns": time.time_ns(), **fields}
            self._write_audit("control", (json.dumps(row) + "\n").encode())

    @property
    def startup_synchronized(self):
        return self._startup_synchronized

    def startup_sync_snapshot(self):
        return {"state": "synchronized" if self._startup_synchronized else "waiting",
                "discarded_frames": self._startup_discarded_frames,
                "discarded_bytes": self._startup_discarded_bytes,
                "first_valid_packet": self._startup_first_packet}

    async def wait_startup_synchronization(self, timeout):
        try:
            await asyncio.wait_for(self._startup_sync_event.wait(), timeout)
        except asyncio.TimeoutError as exc:
            snapshot = self.startup_sync_snapshot()
            raise TimeoutError("startup COBS synchronization timed out: "
                               f"discarded_frames={snapshot['discarded_frames']} "
                               f"discarded_bytes={snapshot['discarded_bytes']}") from exc

    def _discard_startup_frame(self, frame, reason):
        self._startup_discarded_frames += 1
        self._startup_discarded_bytes += len(frame)
        self.trace("startup_frame_discarded", encoded_bytes=len(frame), reason=reason,
                   discarded_frames=self._startup_discarded_frames,
                   discarded_bytes=self._startup_discarded_bytes)

    def _accept_packet(self, packet):
        if self._packets.full():
            # This is an operational loss after a real packet boundary; retain
            # the existing transport's visible error behaviour.
            try:
                self._packets.get_nowait()
            except asyncio.QueueEmpty:
                pass
            self.frame_errors += 1
        try:
            self._packets.put_nowait(packet)
        except asyncio.QueueFull:
            self.frame_errors += 1

    def check_archive(self):
        if self._wire_journal is not None:
            try:
                self._wire_journal.check()
            except OSError as exc:
                if self.archive_fault_handler: self.archive_fault_handler(exc)
                raise ArchiveFault(str(exc)) from exc

    def _write_audit(self, key, data):
        if not self._audit and self.recorder is None:
            return
        if self.recorder is not None:
            self.recorder.wire(key, data)
            return
        if self._wire_journal is not None:
            try:
                self._wire_journal.append(key, data)
            except OSError as exc:
                if self.archive_fault_handler: self.archive_fault_handler(exc)
                raise ArchiveFault(str(exc)) from exc
        else:
            self._audit[key].write(data)

    def _close_audit(self):
        if not self._audit:
            return
        try:
            if self._wire_journal is not None:
                self._wire_journal.close()
            else:
                for handle in self._audit.values():
                    handle.flush()
                    os.fsync(handle.fileno())
                    handle.close()
        finally:
            self._audit = {}

    async def open(self):
        try:
            if self.audit_root is not None:
                self.audit_root.mkdir(parents=True, exist_ok=False)
                for key, name in [("rx", "rx.cobs"), ("tx", "tx.cobs"),
                                  ("control", "control.jsonl")]:
                    self._audit[key] = (self.audit_root / name).open("xb", buffering=0)
                if self.journal_root is None:
                    from .wire_journal import WireJournal
                    self._wire_journal = WireJournal(self._audit)
                else:
                    from .wire_spool import SpoolingWireJournal
                    self._wire_journal = SpoolingWireJournal(self._audit, root=self.journal_root,
                        destination=self.audit_root, volume_guard=self.volume_guard)
            self.trace("open_attempt", device=self.device,
                       note="Host arrival times are not acoustic TX times")
            await super().open()
            self._last_byte_at = self._last_packet_at = time.monotonic()
            self._read_poll_handle = self._loop.call_later(self._read_poll_interval_s, self._poll_receive)
            self.trace("open", exclusive=True)
        except BaseException as exc:
            try:self.trace("open_failed", error=f"{type(exc).__name__}: {exc}")
            except OSError:pass
            try:self._close_audit()
            except OSError:pass
            raise

    def _on_readable(self):
        if self.fd is None:
            return
        try:
            # USB reads commonly return short fragments while more bytes are
            # already buffered. Drain until EAGAIN (bounded for event-loop
            # fairness), so the silence check sees complete queued packets
            # before declaring a disconnect. Never grant time to a silent peer.
            drained = 0
            while drained < MAX_ENCODED_FRAME * 2:
                chunk = os.read(self.fd, 65536)
                if not chunk:
                    self._offer_exception(SerialDisconnected("serial EOF"))
                    return
                # Save bytes before framing/CRC rejection, including bad packets.
                try:self._write_audit("rx", chunk)
                except ArchiveFault as exc:
                    self._fault_read_fragment = (chunk, getattr(exc.__cause__, 'wire_original_enqueued',
                        getattr(exc, 'wire_original_enqueued', False)))
                    raise
                drained += len(chunk)
                self._last_byte_at = time.monotonic()
                self.bytes_rx += len(chunk)
                self._rx.extend(chunk)
                if len(self._rx) > MAX_ENCODED_FRAME * 2:
                    if not self._startup_synchronized:
                        self._discard_startup_frame(self._rx, "startup_buffer_limit")
                    else:
                        self.frame_errors += 1
                    self._rx.clear()
                prior = (self.frame_errors, self.crc_errors)
                self._drain_frames()
                if prior != (self.frame_errors, self.crc_errors):
                    self.trace("packet_rejection", frame_errors=self.frame_errors,
                               crc_errors=self.crc_errors)
        except BlockingIOError:
            return
        except ArchiveFault as exc:
            self._silence_faulted = True
            if self._loop is not None and self.fd is not None:
                self._loop.remove_reader(self.fd)
            self._offer_exception(exc)
        except OSError as exc:
            if exc.errno in (errno.EAGAIN, errno.EWOULDBLOCK):
                return
            self._offer_exception(SerialDisconnected(str(exc)))

    def _drain_frames(self):
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
                if self._startup_synchronized:
                    self.frame_errors += 1
                else:
                    self._discard_startup_frame(frame, "frame_limit")
                continue
            try:
                packet = decode_packet(frame)
            except ValueError as exc:
                if self._startup_synchronized:
                    self.frame_errors += 1
                    if "CRC32" in str(exc):
                        self.crc_errors += 1
                else:
                    self._discard_startup_frame(frame, str(exc))
                continue
            if not self._startup_synchronized:
                self._startup_synchronized = True
                self._startup_first_packet = {"type": int(packet.type), "sequence": packet.seq,
                                              "flags": packet.flags, "payload_bytes": len(packet.payload)}
                self.trace("startup_synchronized", **self.startup_sync_snapshot())
                self._startup_sync_event.set()
            self._accept_packet(packet)
            self._last_packet_at = time.monotonic()

    async def recv_packet(self, timeout=None):
        while True:
            try:packet = await super().recv_packet(timeout)
            except ArchiveFault:
                if not self._archive_fault_settling:raise
                # HOLD is already latched. The bounded settlement reader only
                # reconciles originals and a stopped STATUS; it cannot rearm.
                continue
            break
        fields = {"type": int(packet.type), "sequence": packet.seq,
                  "flags": packet.flags, "payload_bytes": len(packet.payload)}
        if packet.type == MessageType.STATUS:
            status = parse_status(packet.payload)
            self.last_device_status = asdict(status)
            self.status_running = bool(packet.flags & 1)
            self.status_generation += 1
            self.status_updated.set()
            fields.update(status=self.last_device_status, running=self.status_running)
        self.trace("receive_packet", **fields)
        if int(packet.type) in (0x44, 0x45) and self.transmit_handler:
            self.transmit_handler(packet)
        if self.packet_handler:
            self.packet_handler(packet)
        return packet

    async def send_packet(self, packet):
        async with self._send_lock:
            await self._send_one(packet)

    async def _send_one(self, packet):
        if self._archive_fault_settling and int(packet.type) not in (0x13, 0x43):
            raise ArchiveFault('archive settlement permits only STOP/TX_ABORT')
        def audit(function, *args, **kwargs):
            try:function(*args, **kwargs)
            except ArchiveFault as exc:
                if not self._archive_fault_settling:raise
                # A full/unavailable journal must never prevent a safe STOP.
                # Its audit failure stays visible and HOLD remains latched.
                if len(self._settlement_audit_errors) < 16:
                    self._settlement_audit_errors.append(str(exc))
        encoded = encode_packet(packet)
        prior = self.bytes_tx
        audit(self.trace, "send_attempt", type=int(packet.type), sequence=packet.seq)
        try:
            await super().send_packet(packet)
        finally:
            sent = self.bytes_tx - prior
            if self._audit or self.recorder is not None:
                audit(self._write_audit, "tx", encoded[:sent])
            audit(self.trace, "send_result", type=int(packet.type), sequence=packet.seq,
                       transmitted_bytes=sent, intended_bytes=len(encoded),
                       complete=sent == len(encoded))

    async def close(self):
        if self._read_poll_handle is not None:
            self._read_poll_handle.cancel()
            self._read_poll_handle = None
        try:
            self.trace("close", bytes_rx=self.bytes_rx, bytes_tx=self.bytes_tx,
                       frame_errors=self.frame_errors, crc_errors=self.crc_errors)
        except OSError:
            pass  # Close still releases the FD; journal.close reports the fault.
        try:
            await super().close()
        finally:
            await asyncio.to_thread(self._close_audit)

    async def freeze_receive(self):
        """One bounded receive boundary before draining already accepted PINGs."""
        deadline=time.monotonic()+.15
        while (self._last_byte_at is not None and time.monotonic()-self._last_byte_at<.02
               and time.monotonic()<deadline):
            await asyncio.sleep(.005)
        self._on_readable()
        if self._read_poll_handle is not None:
            self._read_poll_handle.cancel();self._read_poll_handle=None
        if self.fd is not None and self._loop is not None:self._loop.remove_reader(self.fd)
        # Let the single RX consumer finish packets already in its queue.
        await asyncio.sleep(0)

    def begin_archive_fault_settle(self):
        if self._wire_journal is None or not hasattr(self._wire_journal, 'begin_fault_drain'):
            return
        self._wire_journal.begin_fault_drain()
        self._archive_fault_settling = True
        if self._fault_read_fragment is not None:
            chunk, saved = self._fault_read_fragment
            if not saved:self._write_audit('rx', chunk)
            self._fault_read_fragment = None
            self.bytes_rx += len(chunk)
            self._rx.extend(chunk)
            self._drain_frames()
        if self.fd is not None and self._loop is not None:
            self._silence_faulted = False
            self._loop.add_reader(self.fd, self._on_readable)
            if self._read_poll_handle is not None:self._read_poll_handle.cancel()
            self._read_poll_handle = self._loop.call_later(self._read_poll_interval_s, self._poll_receive)


class VerifiedClient(PicoClient):
    """STOP/STATUS and exact CONFIG/ACK before starting the existing PING path."""

    def __init__(self, transport, *, stop_confirmation_timeout_s=3, **kwargs):
        super().__init__(transport, **kwargs)
        self.last_stop_confirmed = False
        self.accepted_config = None
        self._startup_prompt_sent = False
        self.stop_confirmation_timeout_s = stop_confirmation_timeout_s

    def begin_archive_fault_settle(self):
        self.transport.begin_archive_fault_settle()
        if self._rx_task is not None and self._rx_task.done():
            # Replace only the completed reader on this same exclusive FD.
            # Disconnect and the persistent archive HOLD remain latched.
            self._rx_task = asyncio.create_task(self._rx_loop(), name='pico-fault-settle-rx')

    async def stop_acquisition(self):
        # The initial HELLO_ACK is a non-acquisition prompt: it gives an idle
        # Pico a deterministic way to emit a complete packet.  START/STOP and
        # configuration stay blocked until a valid incoming frame has made the
        # COBS boundary explicit.
        if not self.transport.startup_synchronized:
            if not self._startup_prompt_sent:
                await self.acknowledge_hello()
                self._startup_prompt_sent = True
            await self.transport.wait_startup_synchronization(timeout=5)
        self.last_stop_confirmed = False
        generation = self.transport.status_generation
        await super().stop_acquisition()
        deadline = asyncio.get_running_loop().time() + self.stop_confirmation_timeout_s
        while not (self.transport.status_generation > generation
                   and self.transport.status_running is False):
            self.transport.status_updated.clear()
            remaining = deadline - asyncio.get_running_loop().time()
            if remaining <= 0:
                raise TimeoutError("STOP has no confirmed stopped STATUS")
            try:
                await asyncio.wait_for(self.transport.status_updated.wait(), remaining)
            except asyncio.TimeoutError as exc:
                raise TimeoutError("STOP has no confirmed stopped STATUS") from exc
        self.last_stop_confirmed = True
        self.transport.trace("stop_confirmed")

    async def wait_hello(self, timeout=5):
        hello = await super().wait_hello(timeout)
        if hello.adc_bits != 12 or hello.max_samples < 32:
            raise RuntimeError("HELLO ADC/sample geometry is incompatible")
        if (hello.capabilities & 0xA) != 0xA:
            raise RuntimeError("HELLO lacks analog-trigger/envelope capabilities")
        expected = self.transport.expected_identity
        if expected and any(asdict(hello).get(key) != value for key, value in expected.items()):
            raise RuntimeError("HELLO identity/capabilities differ from the selected device")
        self.transport.trace("hello_verified", hello=asdict(hello))
        return hello

    async def configure(self, config, timeout=3):
        self.accepted_config = None
        if not self.last_stop_confirmed or self.transport.status_running is not False:
            raise RuntimeError("CONFIG requires a confirmed stopped STATUS")
        ack = await super().configure(config, timeout)
        if (ack.revision, ack.actual_sample_period_ns, ack.actual_sample_count) != (
                config.revision, config.sample_period_ns, config.sample_count):
            raise RuntimeError("CONFIG_ACK geometry/revision mismatch")
        self.accepted_config = {"requested": asdict(config), "ack": asdict(ack)}
        self.transport.trace("config_accepted", **self.accepted_config)
        return ack

    async def start_acquisition(self):
        if self.accepted_config is None or not self.last_stop_confirmed:
            raise RuntimeError("START requires an accepted CONFIG after confirmed STOP")
        await super().start_acquisition()
        self.last_stop_confirmed = False
