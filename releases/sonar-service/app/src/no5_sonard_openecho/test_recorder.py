"""Optional lossy test recorder. Never owns capture, TX or live-test health.

One writer thread consumes a byte-bounded overwrite ring. An entry removed from
that ring is immutable; an I/O failure disables this recorder, never the source.
The disk budget is conservative and includes WAL/chunk construction headroom.
"""
from collections import deque
from dataclasses import replace
from pathlib import Path
import json
import threading
import time

from .archive_fault import JournalBudget


class TestRecorder:
    __test__ = False
    INPUT_BYTES = 3 * 1024 * 1024  # active <= 64 KiB; total input stays < 4 MiB
    INPUT_ENTRIES = 4096
    MAX_WRITERS = 4
    MAX_ENTRY_BYTES = 64 * 1024

    def __init__(self, root, *, writer=None):
        self.root = Path(root)
        self.budget = JournalBudget(self.root)
        self.condition = threading.Condition()
        self.ring = deque()
        self.pending_bytes = self.maximum_bytes = 0
        self.active_bytes = 0
        self.dropped_entries = self.dropped_bytes = 0
        self.dropped_raw = self.written_raw = self.offered_raw = 0
        self.written_entries = 0
        self.error = None
        self.closing = self.closed = False
        self._writers = {}
        self._part = 0
        self._handles = {}
        self._write_entry = writer or self._write
        self.thread = threading.Thread(target=self._run, name='sonard-test-recorder', daemon=True)
        self.thread.start()

    def _drop(self, kind, size):
        self.dropped_entries += 1
        self.dropped_bytes += size
        self.dropped_raw += kind == 'raw'

    def offer(self, kind, value, size):
        # A true overwrite ring: no await, no disk I/O, never backpressure RX.
        with self.condition:
            if kind == 'raw': self.offered_raw += 1
            if self.closing or self.error or size > self.MAX_ENTRY_BYTES:
                self._drop(kind, size)
                return
            while self.ring and (self.pending_bytes + size > self.INPUT_BYTES
                                  or len(self.ring) >= self.INPUT_ENTRIES):
                old_kind, _, old_size = self.ring.popleft()
                self.pending_bytes -= old_size
                self._drop(old_kind, old_size)
            self.ring.append((kind, value, size))
            self.pending_bytes += size
            self.maximum_bytes = max(self.maximum_bytes, self.pending_bytes + self.active_bytes)
            self.condition.notify()

    def _producer_error(self, kind, size, exc):
        with self.condition:
            self.error = self.error or f'{type(exc).__name__}: {exc}'
            self._drop(kind, size)
            self.closing = True
            self.condition.notify()

    def raw(self, record):
        try:
            if (not 32 <= record.samples.size <= 16384
                    or record.sample_period_ns not in (2000,4000,8000)):
                raise ValueError('unsupported test archive geometry')
            # Never alias later processing buffers or transform original ADC.
            record = replace(record, samples=record.samples.copy())
            self.offer('raw', record, record.samples.nbytes + 512)
        except Exception as exc:
            self._producer_error('raw', 0, exc)

    def wire(self, key, data):
        try:
            for start in range(0, len(data), self.MAX_ENTRY_BYTES - 512):
                part = bytes(data[start:start+self.MAX_ENTRY_BYTES-512])
                self.offer('wire:'+key, part, len(part)+512)
        except Exception as exc:
            self._producer_error('wire:'+key, 0, exc)

    def evidence(self, key, row):
        try:
            data = (json.dumps(row, sort_keys=True, separators=(',', ':'))+'\n').encode()
            self.offer('evidence:'+key, data, len(data)+512)
        except Exception as exc:
            self._producer_error('evidence:'+key, 0, exc)

    def snapshot(self):
        with self.condition:
            return {'enabled': True, 'mode': 'test_only_lossy_ring',
                'state': 'FAILED' if self.error else ('DEGRADED' if self.dropped_entries else 'OK'),
                'affects_live_health': False, 'error': self.error,
                'input_limit_bytes': self.INPUT_BYTES, 'input_entry_limit': self.INPUT_ENTRIES,
                'pending_entries': len(self.ring), 'pending_bytes': self.pending_bytes,
                'active_bytes': self.active_bytes, 'maximum_input_bytes': self.maximum_bytes,
                'dropped_entries': self.dropped_entries, 'dropped_bytes': self.dropped_bytes,
                'dropped_raw_pings': self.dropped_raw, 'offered_raw_pings': self.offered_raw,
                'written_raw_pings': self.written_raw, 'written_entries': self.written_entries,
                'disk_hard_limit_bytes': self.budget.LIMIT, 'disk_bound_bytes': self.budget.last_used,
                'closed': self.closed, 'close_pending': self.closing and not self.closed}

    def _write(self, kind, value, size):
        self.root.mkdir(parents=True, exist_ok=True)
        extra = size + 4096
        if kind == 'raw':
            key = (value.samples.size, value.sample_period_ns)
            existing = self._writers.get(key)
            if existing is None:
                extra += 65536
                if len(self._writers) >= self.MAX_WRITERS:
                    retired = next(iter(self._writers.values()))
                    extra += retired.sample_count * retired.pending_count * 4 + 65536
            if existing is not None and existing.pending_count >= 63:
                extra += 2*64*value.samples.nbytes + 65536
        with self.budget.reserve(extra):
            if kind == 'raw':
                from no5_sonard.storage.raw_zarr import RawZarrV2Writer
                key = (value.samples.size, value.sample_period_ns)
                writer = self._writers.get(key)
                if writer is None:
                    if len(self._writers) >= self.MAX_WRITERS:
                        old_key = next(iter(self._writers))
                        self._writers[old_key].close()
                        del self._writers[old_key]
                    self._part += 1
                    writer = RawZarrV2Writer(self.root/'raw'/f'n{key[0]}-p{key[1]}-part{self._part:06d}.zarr',
                        sample_count=key[0], sample_period_ns=key[1], chunk_pings=64,
                        attrs={'source': 'optional_test_recorder', 'losses': 'recorder-status.json'})
                    self._writers[key] = writer
                writer.append(value)
            else:
                key = kind.replace(':', '-')
                handle = self._handles.get(key)
                if handle is None:
                    handle = (self.root/(key+('.cobs' if kind.startswith('wire:') else '.jsonl'))).open('xb')
                    self._handles[key] = handle
                handle.write(value)
                handle.flush()

    def _run(self):
        try:
            while True:
                with self.condition:
                    self.condition.wait_for(lambda: self.ring or self.closing)
                    if not self.ring: break
                    kind, value, size = self.ring.popleft()
                    self.pending_bytes -= size
                    self.active_bytes = size
                self._write_entry(kind, value, size)
                with self.condition:
                    self.active_bytes = 0
                    self.written_entries += 1
                    self.written_raw += kind == 'raw'
        except Exception as exc:
            with self.condition:
                self.error = f'{type(exc).__name__}: {exc}'
                self._drop(kind, size)
                self.active_bytes = 0
                while self.ring:
                    k, _, n = self.ring.popleft(); self._drop(k, n)
                self.pending_bytes = 0
        finally:
            try:
                # Finish existing originals using only the reserved tail.
                for writer in self._writers.values():
                    extra = writer.sample_count * writer.pending_count * 4 + 65536
                    with self.budget.reserve(extra, emergency=True): writer.close()
                import os
                for handle in self._handles.values():
                    handle.flush(); os.fsync(handle.fileno()); handle.close()
                with self.condition: self.closed = True
                if self.root.exists():
                    with self.budget.reserve(65536, emergency=True):
                        (self.root/'recorder-status.json').write_text(json.dumps(self.snapshot(), indent=2)+'\n')
            except Exception as exc:
                self.error = self.error or f'{type(exc).__name__}: {exc}'
            with self.condition:
                self.closed = True
                self.condition.notify_all()

    def close(self, timeout=10):
        with self.condition:
            self.closing = True
            self.condition.notify()
        self.thread.join(timeout)
        # A blocked recorder remains bounded and reported, never blocks STOP.
        return self.snapshot()
