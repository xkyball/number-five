"""Archive failures are never serial disconnects or operator STOPs."""
from contextlib import contextmanager
from pathlib import Path
import os
import threading


class ArchiveFault(OSError):
    pass


_locks = {}
_queued = {}
_queue_locks = {}
_usage = {}
_reserved = {}
_admitted = {}
_bounds = {}
_guard = threading.Lock()


class JournalBudget:
    LIMIT = 64 * 1024 * 1024
    RESERVE = 16 * 1024 * 1024
    QUEUE_LIMIT = 4 * 1024 * 1024
    QUEUE_TAIL = 1024 * 1024

    def __init__(self, root):
        self.root = Path(root)
        self.key = str(self.root.absolute())
        with _guard:
            self.lock = _locks.setdefault(self.key, threading.RLock())
            self.queue_lock = _queue_locks.setdefault(self.key,threading.Lock())
            _queued.setdefault(self.key,0)
            _usage.setdefault(self.key,0)
            _reserved.setdefault(self.key,0)
            _admitted.setdefault(self.key,0)

    def enqueue(self,size,*,emergency=False):
        with self.queue_lock:
            limit=self.QUEUE_LIMIT if emergency else self.QUEUE_LIMIT-self.QUEUE_TAIL
            if _queued[self.key]+size>limit:
                raise ArchiveFault('shared SSD journal enqueue capacity exceeded')
            _queued[self.key]+=size

    @property
    def queued(self):
        with self.queue_lock:return _queued[self.key]

    def dequeued(self,size):
        with self.queue_lock:_queued[self.key]-=size

    @property
    def last_used(self):
        # Status must not inspect a filesystem or wait on a writer's fsync.
        return _usage[self.key]

    def invalidate(self):
        """After an explicit out-of-band recovery/mutation, rescan before admission."""
        with self.lock:
            _bounds.pop(self.key, None)

    def used(self):
        total = 0
        folders = [self.root]
        while folders:
            try:
                with os.scandir(folders.pop()) as entries:
                    for entry in entries:
                        try:
                            if entry.is_dir():
                                if not entry.is_symlink():folders.append(entry.path)
                            else:total += entry.stat().st_size
                        except FileNotFoundError:
                            pass  # A verified transfer retired this entry.
            except FileNotFoundError:
                pass
        return total

    @contextmanager
    def reserve(self, extra=0, *, emergency=False, refresh=False):
        if extra < 0:
            raise ValueError('journal reservation must not be negative')
        # The journal tree is exclusively owned by these writers. Every new
        # byte (including OWNER metadata) is reserved before it becomes visible.
        # Keep an upper bound; completions/deletions NEVER credit it. Only a
        # checked full scan can reclaim space. This is not a time-based cache.
        limit = self.LIMIT if emergency else self.LIMIT-self.RESERVE
        with self.lock:
            bound = _bounds.get(self.key)
            needs_scan = refresh or bound is None or bound + extra > limit
            inflight = _reserved[self.key]
            watermark = _admitted[self.key]
        if needs_scan:
            used = self.used()  # no filesystem operation owns accounting lock
            with self.lock:
                checked = used + inflight + (_admitted[self.key]-watermark)
                previous = _bounds.get(self.key)
                # Both bounds include every admission since their own scan.
                _bounds[self.key] = checked if previous is None else min(previous, checked)
        with self.lock:
            projected = _bounds[self.key] + extra
            if projected > limit:
                raise ArchiveFault('64 MiB capture journal capacity reached; preserve untransferred RAW/Wire')
            _reserved[self.key] += extra
            _admitted[self.key] += extra
            _bounds[self.key] = projected
            _usage[self.key] = projected
        try:
            # A slow fsync owns only this reservation. It must never hold the
            # shared accounting lock and serialize unrelated RAW/Wire writers.
            yield
        finally:
            with self.lock:
                _reserved[self.key]-=extra
