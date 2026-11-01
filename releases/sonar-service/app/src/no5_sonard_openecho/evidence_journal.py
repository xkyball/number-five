"""Bounded ordered derived evidence; disk fsync never occupies USB callbacks."""
import os,queue,threading,time
from pathlib import Path

class EvidenceJournal:
    def __init__(self,limit_bytes=8*1024*1024,max_rows=4096,*,root=None,volume_guard=None,
                 close_timeout_s=10):
        self.spools={} if root is not None else None
        self.root=root;self.volume_guard=volume_guard;self.close_timeout_s=close_timeout_s
        self.limit=limit_bytes;self.rows=queue.Queue(max_rows);self.lock=threading.Lock()
        self.pending=0;self.maximum=0;self.error=None;self.closed=False
        if self.spools is not None:
            if not volume_guard:raise ValueError('durable evidence requires the checked volume')
            return
        self.thread=threading.Thread(target=self._run,name='sonard-evidence',daemon=True);self.thread.start()
    def append(self,path,data):
        data=bytes(data)
        if self.spools is not None:
            from .archive_fault import ArchiveFault
            from .wire_spool import SpoolingWireJournal
            path=Path(path).absolute()
            if not path.is_relative_to(Path(self.volume_guard['mount']).absolute()):
                raise ArchiveFault('evidence destination outside checked archive volume')
            with self.lock:
                if self.closed:raise ArchiveFault('evidence journal is closed')
                if path not in self.spools:
                    self.spools[path]=SpoolingWireJournal({},root=self.root,destination=path.parent,
                        volume_guard=self.volume_guard,limit_bytes=self.limit,
                        close_timeout_s=self.close_timeout_s,evidence_name=path.name)
                spool=self.spools[path]
            try:spool.append('control',data)
            except OSError as exc:self.error=exc;raise
            return
        with self.lock:
            if self.error:raise OSError('evidence journal failed') from self.error
            if self.closed:raise OSError('evidence journal is closed')
            if self.pending+len(data)>self.limit or self.rows.full():raise OSError('evidence journal capacity exceeded')
            self.pending+=len(data);self.maximum=max(self.maximum,self.pending)
            self.rows.put_nowait((Path(path),data))
    def snapshot(self):
        if self.spools is not None:
            from .archive_fault import JournalBudget
            with self.lock:spools=list(self.spools.values())
            return {'kind':'durable_ssd_evidence','pending_bytes':sum(s.pending for s in spools),
                'maximum_pending_bytes':sum(s.maximum for s in spools),'limit_bytes':self.limit,
                'shared_ssd_bytes_last_checked':JournalBudget(self.root).last_used,
                'shared_budget_bytes':JournalBudget.LIMIT,
                'journals':[str(s.path) for s in spools],
                'failed':self.error is not None or any(s.error is not None for s in spools)}
        with self.lock:return {'pending_bytes':self.pending,'maximum_pending_bytes':self.maximum,'limit_bytes':self.limit,'failed':self.error is not None}
    def check(self):
        if self.error:raise OSError('evidence journal failed') from self.error
        if self.spools is not None:
            with self.lock:spools=list(self.spools.values())
            for spool in spools:spool.check()
    def _run(self):
        handle=None;current=None
        try:
            while True:
                item=self.rows.get()
                if item is None:break
                batch=[item];size=len(item[1])
                # Group only already queued rows. The original JSONL bytes and
                # ordering are retained; each completed batch is fsync'd.
                deadline=time.monotonic()+.1
                while len(batch)<64 and size<256*1024:
                    remaining=deadline-time.monotonic()
                    if remaining<=0:break
                    try:following=self.rows.get(timeout=remaining)
                    except queue.Empty:break
                    if following is None:self.rows.put_nowait(None);break
                    batch.append(following);size+=len(following[1])
                for path,data in batch:
                    if path!=current:
                        if handle:handle.flush();os.fsync(handle.fileno());handle.close()
                        path.parent.mkdir(parents=True,exist_ok=True);handle=path.open('ab');current=path
                    handle.write(data)
                handle.flush();os.fsync(handle.fileno())
                with self.lock:self.pending-=size
        except BaseException as exc:
            with self.lock:self.error=exc
        finally:
            if handle:
                try:handle.flush();os.fsync(handle.fileno());handle.close()
                except BaseException as exc:
                    with self.lock:self.error=exc
    def close(self):
        with self.lock:self.closed=True
        if self.spools is not None:
            errors=[]
            for spool in list(self.spools.values()):
                try:spool.close()
                except OSError as exc:errors.append(exc)
            if errors:
                self.error=errors[0]
                raise OSError('evidence journal close failed; original SSD journals retained') from errors[0]
            self.check()
            return
        while self.thread.is_alive():
            try:self.rows.put(None, timeout=.05);break
            except queue.Full:continue
        self.thread.join()
        if self.error:raise OSError('evidence journal failed') from self.error
