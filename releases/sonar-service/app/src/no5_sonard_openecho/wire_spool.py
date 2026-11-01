"""Durable bounded SSD wire journal with idempotent, byte-exact WD transfer.

Only the spool worker touches the SSD; USB callbacks enqueue bounded bytes.
Each record includes its stream offset and CRC. A failed transfer retains the
original journal, and recovery compares existing destination bytes before append.
"""
import hashlib
import json
import os
from pathlib import Path
import queue
import struct
import threading
import time
import uuid
import zlib

from .archive_fault import ArchiveFault, JournalBudget

HEADER = struct.Struct('<BQII')
STREAMS = {'rx': 1, 'tx': 2, 'control': 3}
NAMES = {1: 'rx.cobs', 2: 'tx.cobs', 3: 'control.jsonl'}


class FragmentQueue(queue.Queue):
    """Coalesce adjacent stream fragments under the existing queue mutex.

    A USB callback can return one byte. Its byte budget must not silently
    become a 16384-byte limit because each fragment occupied a queue row.
    Removed buffers belong exclusively to the writer; producers never mutate
    a buffer being encoded or written. Normal and completion rows stay bounded.
    """
    def put_fragment(self, key, data, *, emergency=False):
        with self.not_full:
            if self.queue and self.queue[-1] is not None:
                last_key, last_data = self.queue[-1]
                if last_key == key and len(last_data)+len(data) <= 65536:
                    last_data.extend(data)
                    return
            if len(self.queue) >= self.maxsize+(256 if emergency else 0):
                raise queue.Full
            self._put((key, bytearray(data)))
            self.unfinished_tasks += 1
            self.not_empty.notify()


def sync_directory(path):
    fd = os.open(path, os.O_RDONLY)
    try:
        os.fsync(fd)
    finally:
        os.close(fd)


def publish_wire_part(part, destination, *, names=None, create_destination=False):
    """Replay records once; an interrupted append may only match exact bytes."""
    part, destination = Path(part), Path(destination)
    names = NAMES if names is None else names
    if not names or any(type(key) is not int or key not in NAMES or
                        not isinstance(name, str) or not name or
                        Path(name).name != name or name in {'.', '..'}
                        for key, name in names.items()):
        raise ArchiveFault('invalid journal destination names; originals retained')
    grouped = {}
    with part.open('rb') as source:
        while header := source.read(HEADER.size):
            if len(header) != HEADER.size:
                raise ArchiveFault('incomplete wire journal header retained for review')
            stream, offset, length, crc = HEADER.unpack(header)
            if stream not in names or not 0 < length <= 1024 * 1024:
                raise ArchiveFault('invalid wire journal record retained for review')
            data = source.read(length)
            if len(data) != length or zlib.crc32(data) & 0xffffffff != crc:
                raise ArchiveFault('wire journal CRC mismatch; original retained')
            if stream not in grouped:
                grouped[stream] = (offset, bytearray())
            first, combined = grouped[stream]
            if offset != first + len(combined):
                raise ArchiveFault('wire journal offsets are not contiguous')
            combined.extend(data)
    for stream, (offset, data) in grouped.items():
            length = len(data)
            path = destination / names[stream]
            if create_destination:
                destination.mkdir(parents=True, exist_ok=True)
                with path.open('ab'):
                    pass
            with path.open('r+b', buffering=0) as target:
                size = os.fstat(target.fileno()).st_size
                if size < offset:
                    raise ArchiveFault('wire destination has a gap; original journal retained')
                target.seek(offset)
                existing = target.read(min(length, size - offset))
                if existing != data[:len(existing)]:
                    raise ArchiveFault('wire destination differs; both originals retained')
                target.seek(offset + len(existing))
                view = memoryview(data)[len(existing):]
                while view:
                    count = target.write(view)
                    if not count:
                        raise ArchiveFault('wire destination short write')
                    view = view[count:]
                os.fsync(target.fileno())
                target.seek(offset)
                if hashlib.sha256(target.read(length)).digest() != hashlib.sha256(data).digest():
                    raise ArchiveFault('wire archive transfer hash mismatch')
    sync_directory(destination)
    part.unlink()
    sync_directory(part.parent)


class SpoolingWireJournal:
    def __init__(self, handles, *, root, destination, volume_guard,
                 limit_bytes=8*1024*1024, part_bytes=256*1024, close_timeout_s=10,
                 evidence_name=None):
        self.handles = handles
        self.destination = Path(destination).absolute()
        self.guard = volume_guard
        self.budget = JournalBudget(root)
        if evidence_name is not None and (not isinstance(evidence_name, str) or
                not evidence_name or Path(evidence_name).name != evidence_name or
                evidence_name in {'.', '..'}):
            raise ArchiveFault('invalid evidence destination name')
        self.names = NAMES if evidence_name is None else {3: evidence_name}
        self.evidence = evidence_name is not None
        self.path = Path(root) / (('evidence-' if self.evidence else 'wire-') + uuid.uuid4().hex)
        self.path.mkdir(parents=True)
        self.limit = limit_bytes
        self.part_bytes = part_bytes
        self.close_timeout_s = close_timeout_s
        self.rows = FragmentQueue(16384)
        self.lock = threading.Lock()
        self.pending = self.maximum = 0
        self.fault_drain = False
        self.fault_snapshot = None
        self.stage = None
        self.stage_started = None
        self.maximum_sync_s = 0.
        self.sync_count = 0
        self.error = None
        self.closed = False
        self.producer_done = threading.Event()
        self.changed = threading.Event()
        self.offsets = {key: 0 for key in STREAMS}
        self.ordinal = 0
        self._check_volume()
        with self.budget.reserve(4096):
            with (self.path / 'OWNER.json').open('x') as handle:
                owner = {'schema': 'no5.evidence-spool-owner/1' if self.evidence else 'no5.wire-spool-owner/1',
                         'pid': os.getpid(), 'destination': str(self.destination), 'volume': self.guard}
                if self.evidence:
                    owner['stream_names'] = self.names
                json.dump(owner, handle)
                handle.flush(); os.fsync(handle.fileno())
        sync_directory(self.path)
        self.thread = threading.Thread(target=self._run, name='sonard-wire-spool', daemon=True)
        self.transfer_thread = threading.Thread(target=self._transfer, name='sonard-wire-transfer', daemon=True)
        self.thread.start(); self.transfer_thread.start()

    def _check_volume(self):
        mount = Path(self.guard['mount'])
        if not mount.is_mount() or mount.stat().st_dev != self.guard['device']:
            raise ArchiveFault('required wire data volume unavailable; no fallback')

    def check(self):
        if self.error is not None:
            raise ArchiveFault('wire archive failed: ' + str(self.error)) from self.error

    def append(self, key, data):
        data = bytes(data)
        if not data:return
        with self.lock:
            if not self.fault_drain:self.check()
            if key not in STREAMS or STREAMS[key] not in self.names:
                raise ArchiveFault('invalid journal stream')
            if self.closed:
                raise ArchiveFault('wire archive closed')
            error = None
            try:
                if self.pending + len(data) > self.limit:
                    raise ArchiveFault('wire SSD enqueue byte capacity exceeded')
                self.budget.enqueue(len(data), emergency=self.fault_drain)
                try:self.rows.put_fragment(key, data, emergency=self.fault_drain)
                except queue.Full:
                    self.budget.dequeued(len(data))
                    raise ArchiveFault('wire SSD enqueue row capacity exceeded') from None
            except ArchiveFault as exc:
                if self.fault_drain:raise
                self.error = error = exc
                self.fault_snapshot = self.snapshot()
                # One already-read USB fragment must survive a full normal
                # queue. The completion tail is inside the same 4 MiB bound.
                if len(data) > 65536:raise
                self.budget.enqueue(len(data), emergency=True)
                try:self.rows.put_fragment(key, data, emergency=True)
                except queue.Full:
                    self.budget.dequeued(len(data));raise exc
                error.wire_original_enqueued = True
            self.pending += len(data)
            self.maximum = max(self.maximum, self.pending)
            if error is not None:raise error

    def begin_fault_drain(self):
        # This only permits preservation and STOP settlement. It never clears
        # the fault, HOLD, admission limits, or the explicit recovery gate.
        with self.lock:self.fault_drain = True

    def snapshot(self):
        return {'kind': 'durable_ssd_wire', 'pending_bytes': self.pending,
                'maximum_pending_bytes': self.maximum, 'limit_bytes': self.limit,
                'journal_path': str(self.path), 'failed': self.error is not None,
                'pending_rows': self.rows.qsize(), 'shared_queued_bytes': self.budget.queued,
                'fault_drain': self.fault_drain, 'fault_snapshot': self.fault_snapshot,
                'worker_stage': self.stage,
                'worker_stage_age_s': None if self.stage_started is None else time.monotonic()-self.stage_started,
                'maximum_sync_s': self.maximum_sync_s, 'sync_count': self.sync_count,
                'shared_budget_bytes': self.budget.LIMIT}

    def _sync(self, handle):
        started = time.monotonic()
        self.stage, self.stage_started = 'ssd_fsync', started
        try:os.fsync(handle.fileno())
        finally:
            self.maximum_sync_s = max(self.maximum_sync_s, time.monotonic()-started)
            self.sync_count += 1
            self.stage = self.stage_started = None

    def _run(self):
        handle = part = None
        size = 0
        opened = time.monotonic()
        def seal():
            nonlocal handle, part, size
            if handle is None:
                return
            handle.flush(); self._sync(handle); handle.close()
            sealed = part.with_suffix('.sealed')
            part.rename(sealed); sync_directory(self.path)
            handle = part = None; size = 0
            self.changed.set()
        try:
            if self.evidence:
                # Only the SSD worker may inspect an existing archive path.
                # Reopening an evidence stream continues its byte offset.
                target=self.destination/self.names[3]
                try:self.offsets['control']=target.stat().st_size
                except FileNotFoundError:pass
            while True:
                try:
                    item = self.rows.get(timeout=.02)
                except queue.Empty:
                    if handle is not None and time.monotonic() - opened >= 1:
                        seal()
                    continue
                if item is None:
                    break
                batch = [item]
                batch_bytes = len(item[1])
                deadline = time.monotonic()+.02
                done = False
                while len(batch) < 64 and batch_bytes < 256*1024:
                    remaining = deadline-time.monotonic()
                    if remaining <= 0:break
                    try:
                        following = self.rows.get(timeout=remaining)
                    except queue.Empty:
                        break
                    if following is None:
                        done = True; break
                    batch.append(following)
                    batch_bytes += len(following[1])
                encoded = bytearray(); accepted = 0
                for key, data in batch:
                    encoded.extend(HEADER.pack(STREAMS[key], self.offsets[key], len(data), zlib.crc32(data)&0xffffffff))
                    encoded.extend(data); self.offsets[key] += len(data); accepted += len(data)
                def write_encoded(emergency=False):
                    nonlocal handle,part,opened
                    with self.budget.reserve(len(encoded) + 4096,emergency=emergency):
                        if handle is None:
                            self.ordinal += 1
                            part = self.path / f'{self.ordinal:012d}.open'
                            handle = part.open('xb', buffering=0)
                            opened = time.monotonic()
                        view = memoryview(encoded)
                        while view:
                            count = handle.write(view)
                            if not count:
                                raise OSError('wire SSD short write')
                            view = view[count:]
                        self._sync(handle)
                try:write_encoded(emergency=self.fault_drain or self.error is not None)
                except ArchiveFault as exc:
                    # Latch HOLD before using the reserved tail exclusively
                    # to persist bytes already accepted by bounded queues.
                    self.error=exc
                    write_encoded(emergency=True)
                self.budget.dequeued(accepted)
                size += len(encoded)
                with self.lock:
                    self.pending -= accepted
                if size >= self.part_bytes or time.monotonic() - opened >= 1:
                    seal()
                if done:break
            seal()
        except BaseException as exc:
            self.error = exc
            if handle is not None:
                try:
                    handle.flush(); os.fsync(handle.fileno()); handle.close()
                except OSError:
                    pass
        finally:
            self.producer_done.set(); self.changed.set()

    def _transfer(self):
        try:
            while True:
                parts = sorted(self.path.glob('*.sealed'))
                for part in parts:
                    self._check_volume()
                    if self.evidence:
                        publish_wire_part(part, self.destination, names=self.names, create_destination=True)
                    else:
                        publish_wire_part(part, self.destination)
                if self.producer_done.is_set() and not list(self.path.glob('*.sealed')):
                    return
                self.changed.wait(.1); self.changed.clear()
        except BaseException as exc:
            self.error = exc

    def close(self):
        self.closed = True
        deadline = time.monotonic() + self.close_timeout_s
        while self.thread.is_alive() and time.monotonic() < deadline:
            try:
                self.rows.put(None, timeout=.05); break
            except queue.Full:
                pass
        self.thread.join(max(0, deadline-time.monotonic()))
        self.transfer_thread.join(max(0, deadline-time.monotonic()))
        if self.thread.is_alive() or self.transfer_thread.is_alive():
            raise ArchiveFault('wire archive close timed out; original SSD journal retained')
        for handle in self.handles.values():
            handle.close()
        self.check()
        (self.path / 'OWNER.json').unlink()
        self.path.rmdir()
        sync_directory(self.path.parent)
