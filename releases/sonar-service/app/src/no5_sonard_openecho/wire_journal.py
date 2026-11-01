"""Bounded, byte-exact wire audit; no filesystem calls in USB callbacks."""
import queue
import threading
import time


class WireJournal:
    def __init__(self, handles, limit_bytes=8*1024*1024, max_rows=16384):
        self.handles = handles
        self.limit = limit_bytes
        self.rows = queue.Queue(max_rows)
        self.lock = threading.Lock()
        self.pending = 0
        self.maximum = 0
        self.error = None
        self.closed = False
        self.thread = threading.Thread(target=self._run, name='sonard-wire-audit', daemon=True)
        self.thread.start()

    def check(self):
        if self.error is not None:
            raise OSError('wire archive failed') from self.error

    def append(self, key, data):
        data = bytes(data)
        with self.lock:
            self.check()
            if self.closed:
                raise OSError('wire archive closed')
            if self.pending+len(data)>self.limit or self.rows.full():
                self.error = OSError('wire archive capacity exceeded')
                raise self.error
            self.pending += len(data)
            self.maximum = max(self.maximum, self.pending)
            self.rows.put_nowait((key, data))

    def _run(self):
        try:
            while True:
                item = self.rows.get()
                if item is None:
                    break
                batch=[item];size=len(item[1]);deadline=time.monotonic()+.02
                while len(batch)<64 and size<256*1024:
                    remaining=deadline-time.monotonic()
                    if remaining<=0:break
                    try:following=self.rows.get(timeout=remaining)
                    except queue.Empty:break
                    if following is None:
                        self.rows.put_nowait(None)
                        break
                    batch.append(following);size+=len(following[1])
                grouped={}
                for key,data in batch:
                    grouped.setdefault(key,bytearray()).extend(data)
                for key,data in grouped.items():
                    view=memoryview(data)
                    while view:
                        written=self.handles[key].write(view)
                        if not written:
                            raise OSError('wire archive short write')
                        view=view[written:]
                with self.lock:
                    self.pending-=size
        except BaseException as exc:
            self.error = exc

    def close(self):
        import os
        with self.lock:
            self.closed = True
        while self.thread.is_alive():
            try:
                self.rows.put(None, timeout=.05)
                break
            except queue.Full:
                pass
        self.thread.join()
        try:
            for handle in self.handles.values():
                handle.flush()
                os.fsync(handle.fileno())
        finally:
            for handle in self.handles.values():
                handle.close()
        self.check()
