"""Durable original RAW WALs with independent, bounded Zarr part sealing.

A part receives at most chunk_pings records. Its original fsync'd WAL is the
publication boundary; closing a sealed part never stalls another part's WAL.
Every output remains an ordinary original fixed-geometry Zarr directory.
"""
from concurrent.futures import ThreadPoolExecutor, wait
import json
import os
from pathlib import Path
import time
from contextlib import nullcontext
import threading
from no5_sonard.storage.raw_zarr import RawZarrV2Writer


class RotatingGeometryArchive:
    MAX_PARTS = 16384
    MAX_SEAL_PARTS = 32
    METADATA_RESERVATION = 64 * 1024

    def __init__(self, primary, *, eager_sample_counts=(2048,16384), journal_root=None, volume_guard=None, close_timeout_s=10):
        self.primary=primary;self.path=primary.path
        self.sample_count=primary.sample_count;self.sample_period_ns=primary.sample_period_ns
        self.attrs=json.loads((self.path/'.zattrs').read_text())
        self.volume_guard=volume_guard;self.close_timeout_s=close_timeout_s
        self.journal_root=None if journal_root is None else Path(journal_root)
        self.journal_path=None
        self._destinations={}
        self.lock=threading.RLock();self.index_lock=threading.Lock()
        self.writers={(self.sample_count,self.sample_period_ns):primary}
        self._standby={};self._preparing={};self._geometry_futures={};self._ordinal={}
        self._catalog=[self._entry(primary)]
        self._accepted=primary.count+primary.pending_count;self._committed=primary.count
        self._error=None;self._closed=False
        self._sealing=set();self._seal_slots=threading.BoundedSemaphore(self.MAX_SEAL_PARTS)
        self._index_future=None;self._archive_stage=None;self._archive_stage_at=None
        self._failed_record=None;self._failed_writer=None;self._fault_drain=False
        self._tail_writers={};self._retired_tail=[]
        self._prepare_executor=ThreadPoolExecutor(max_workers=1,thread_name_prefix='sonard-raw-prepare')
        self._executor=ThreadPoolExecutor(max_workers=1,thread_name_prefix='sonard-zarr-seal')
        if self.journal_root is not None:
            self._check_volume()
            self.journal_root.mkdir(parents=True,exist_ok=True)
            if self.journal_root.stat().st_dev==self.volume_guard['device']:
                raise ValueError('capture journal must use the internal filesystem')
            self._check_journal_capacity()
            self.journal_path=self.journal_root/self.path.stem
            self.journal_path.mkdir(exist_ok=False)
            from .archive_fault import JournalBudget
            with JournalBudget(self.journal_root).reserve(4096):
                with (self.journal_path/'OWNER.json').open('x') as owner:
                    json.dump({'schema':'no5.capture-journal-owner/1','pid':os.getpid(),
                        'segment':self.path.stem},owner)
                    owner.flush();os.fsync(owner.fileno())
            # The original destination group is only the geometry index. Raw
            # records use prepared standard journals until a sealed transfer.
            self._catalog=[];self.writers={}
            local=self._new_writer(self.sample_count,self.sample_period_ns)
            self.writers[(self.sample_count,self.sample_period_ns)]=local
        for n in set(eager_sample_counts)|{self.sample_count}:
            key=(n,self.sample_period_ns)
            if key not in self.writers:self.writers[key]=self._new_writer(*key)
            self._standby[key]=self._new_writer(*key)
        self._write_index()

    @staticmethod
    def _entry(writer):
        return {'path':writer.path.name,'sample_count':writer.sample_count,
                'sample_period_ns':writer.sample_period_ns}

    @property
    def archived_count(self):
        with self.lock:return self._accepted

    @property
    def count(self):
        with self.lock:return self._committed

    @property
    def pending_count(self):
        with self.lock:return self._accepted-self._committed

    def _check_volume(self):
        if self.journal_root is None:return
        if not self.volume_guard:raise OSError('local capture journal requires an identified mounted data volume')
        mount=Path(self.volume_guard['mount'])
        if not mount.is_mount() or mount.stat().st_dev!=self.volume_guard['device']:
            raise OSError('required data volume unavailable; no journal fallback')

    def _check_journal_capacity(self,extra=0):
        if self.journal_root is None:return
        from .archive_fault import JournalBudget
        with JournalBudget(self.journal_root).reserve(extra):pass

    def check_archive(self):
        # The already identified WD is checked by publication, never by an
        # SSD admission. A slow mount stat must not block original USB data.
        with self.lock:
            if self._error:raise OSError('RAW part sealing failed: '+str(self._error)) from self._error

    def _new_writer(self,n,p,*,emergency=False):
        if not 2048<=n<=16384 or n%256 or p!=2000:
            raise ValueError('unapproved adaptive raw geometry')
        key=(n,p)
        with self.lock:
            if len(self._catalog)>=self.MAX_PARTS:
                raise OSError('RAW part catalog capacity reached')
            ordinal=self._ordinal.get(key,0)
            if key==(self.sample_count,self.sample_period_ns) and ordinal==0:ordinal=1
            self._ordinal[key]=ordinal+1
        destination=self.path.with_name(f'{self.path.stem}.n{n}.p{p}.part{ordinal:06d}.zarr')
        path=destination if self.journal_path is None else self.journal_path/destination.name
        from .archive_fault import JournalBudget
        reservation=(nullcontext() if self.journal_root is None else
            JournalBudget(self.journal_root).reserve(self.METADATA_RESERVATION,emergency=emergency))
        with reservation:
            writer=RawZarrV2Writer(path,sample_count=n,sample_period_ns=p,
                chunk_pings=self.primary.chunk_pings,attrs={**self.attrs,'sample_count':n,
                    'sample_period_ns':p,'geometry_parent_segment':self.path.stem,
                    'raw_part_ordinal':ordinal,
                    **({'archive_destination':str(destination.absolute()),
                        'archive_volume_uuid':self.volume_guard.get('uuid')} if self.journal_path is not None else {})})
        with self.lock:
            entry=self._entry(writer)
            if self.journal_path is not None:
                entry.update(journal_path=str(path),committed_to_volume=False)
                self._destinations[path]=destination
            self._catalog.append(entry)
        return writer

    def _write_index(self):
        with self.index_lock:
            with self.lock:entries=list(self._catalog)
            self.primary._write_json(self.path/'geometry-index.json',{
                'schema':'no5.raw-geometry-index/1','raw_samples_modified':False,
                'segment_id':self.path.stem,'independent_parts':True,
                'geometries':entries})

    def _latch_error(self,exc):
        with self.lock:
            if self._error is None:self._error=exc

    def _stage(self,name):
        with self.lock:
            self._archive_stage=name;self._archive_stage_at=time.monotonic() if name else None

    def _seal_and_publish(self,sealed):
        try:
            self._stage('local_seal')
            before=sealed.count
            from .archive_fault import JournalBudget
            # Closing temporarily creates Zarr chunks alongside the WAL.
            # Include uncompressed chunk bounds and metadata in the SSD cap.
            size=sealed.chunk_pings*(sealed.sample_count*2+40)+self.METADATA_RESERVATION
            reservation=(nullcontext() if self.journal_root is None else
                JournalBudget(self.journal_root).reserve(size,emergency=True))
            with reservation:sealed.close()
            self._stage('volume_transfer')
            self._publish(sealed)
            with self.lock:self._committed+=sealed.count-before
        except BaseException as exc:
            self._latch_error(exc)
            raise
        finally:self._stage(None)

    def _sealed_done(self,future):
        with self.lock:self._sealing.discard(future)
        self._seal_slots.release()

    def _prepare_part(self,key):
        try:return self._new_writer(*key)
        except BaseException as exc:
            self._latch_error(exc);raise

    def _schedule_index(self):
        # A coalesced index job is separate from the SSD factory as well.
        with self.lock:
            if self._index_future is None or self._index_future.done():
                self._index_future=self._executor.submit(self._background_index)

    def _background_index(self):
        try:
            self._stage('volume_index');self._check_volume();self._write_index()
        except BaseException as exc:
            self._latch_error(exc);raise
        finally:self._stage(None)

    def _publish(self,writer):
        if self.journal_path is None:return
        self._check_volume()
        from .journal_transfer import publish_part
        publish_part(writer.path,self._destinations[writer.path])
        with self.lock:
            for entry in self._catalog:
                if entry['path']==writer.path.name:
                    entry.pop('journal_path',None);entry['committed_to_volume']=True
                    break
            self._destinations.pop(writer.path,None)
        self._write_index()

    def _prepare_geometry(self,key):
        try:
            active=self._new_writer(*key);standby=self._new_writer(*key)
            with self.lock:
                self.writers[key]=active;self._standby[key]=standby
            self._schedule_index()
        except BaseException as exc:
            with self.lock:self._error=exc
            raise

    def ensure_geometry(self,n,p):
        self.check_archive()
        key=(n,p)
        with self.lock:
            if self._closed:raise OSError('RAW archive closed')
            if key in self.writers:return True
            if key not in self._geometry_futures:
                self._geometry_futures[key]=self._prepare_executor.submit(self._prepare_geometry,key)
            return False

    @staticmethod
    def _same_record(a,b):
        return (a.ping_id==b.ping_id and a.time_utc_ns==b.time_utc_ns and
            a.trigger_device_time_us==b.trigger_device_time_us and
            a.sample_period_ns==b.sample_period_ns and a.tx_zero_sample_index==b.tx_zero_sample_index and
            a.ping_flags==b.ping_flags and a.adc_min==b.adc_min and a.adc_max==b.adc_max and
            a.samples.tobytes()==b.samples.tobytes())

    def _append_wal(self,writer,record,*,emergency=False):
        if writer._closed or record.sample_period_ns!=writer.sample_period_ns:
            raise ValueError('RAW writer geometry/ownership mismatch')
        from .archive_fault import JournalBudget
        reservation=(nullcontext() if self.journal_root is None else
            JournalBudget(self.journal_root).reserve(record.samples.nbytes+512,emergency=emergency))
        with reservation:writer._append_pending(record)
        writer._buffer.append(record)
        with self.lock:self._accepted+=1

    def append(self,record):
        attempted=None
        try:
            if self._closed:raise OSError('RAW archive closed')
            if self._fault_drain:
                return self._append_tail(record)
            self.check_archive()
            key=(record.samples.size,record.sample_period_ns)
            if key not in self.writers:
                raise OSError('unprepared RAW geometry; acquisition must retain full search until ready')
            writer=self.writers[key]
            if writer.pending_count>=writer.chunk_pings:
                standby=self._standby.get(key)
                future=self._preparing.get(key)
                if standby is None:
                    if future is None or not future.done():
                        raise OSError('RAW sealing backlog; no prepared part available')
                    standby=future.result()
                if not self._seal_slots.acquire(blocking=False):
                    raise OSError('RAW seal queue capacity reached; retain originals and HOLD')
                # Consume a completed preparation only after all admission
                # checks. Never lose a pending future on the error branch.
                self._standby.pop(key,None);self._preparing.pop(key,None)
                self.writers[key]=standby
                self._preparing[key]=self._prepare_executor.submit(self._prepare_part,key)
                sealing=self._executor.submit(self._seal_and_publish,writer)
                with self.lock:self._sealing.add(sealing)
                sealing.add_done_callback(self._sealed_done)
                writer=standby
            attempted=writer
            self._append_wal(writer,record)
            if self._failed_record is not None and self._same_record(self._failed_record,record):
                self._failed_record=None;self._failed_writer=None
        except OSError:
            # Preserve the already dequeued original, including the exact UTC
            # computed by the original clock mapper, until STOP/freeze drains it.
            if self._failed_record is None:
                self._failed_record=record;self._failed_writer=attempted
            raise

    def _append_tail(self,record):
        key=(record.samples.size,record.sample_period_ns)
        writer=self._tail_writers.get(key)
        if writer is None or writer.pending_count>=writer.chunk_pings:
            if writer is not None:self._retired_tail.append(writer)
            writer=self._new_writer(*key,emergency=True)
            self._tail_writers[key]=writer
        self._append_wal(writer,record,emergency=True)

    def begin_fault_drain(self):
        """Called only after TX/acquisition STOP and the RX freeze boundary.

        This does not clear a fault or publish another frame. Standard original
        WALs use only the bounded completion reserve, even if WD I/O is stuck.
        """
        self._fault_drain=True
        record=self._failed_record;writer=self._failed_writer
        if record is None:return 0
        if writer is not None:
            # An fsync may fail after a complete record write. Do not duplicate
            # it in another part; verify the full original WAL, then retry fsync.
            from no5_sonard.storage.raw_zarr import _PENDING_LEN,_PENDING_CRC
            import zlib
            data=writer._pending_path.read_bytes() if writer._pending_path.exists() else b''
            offset=0
            while offset<len(data):
                if offset+4>len(data):raise OSError('partial failed RAW WAL; retain original for recovery')
                size=_PENDING_LEN.unpack_from(data,offset)[0];offset+=4
                if size<4 or offset+size>len(data):raise OSError('partial failed RAW WAL; retain original for recovery')
                body=data[offset:offset+size];offset+=size
                if zlib.crc32(body[:-4])&0xffffffff!=_PENDING_CRC.unpack(body[-4:])[0]:
                    raise OSError('failed RAW WAL CRC; retain original for recovery')
            rows=writer._read_pending()
            if rows and self._same_record(rows[-1],record):
                with writer._pending_path.open('rb') as handle:os.fsync(handle.fileno())
                if not writer._buffer or not self._same_record(writer._buffer[-1],record):
                    writer._buffer.append(record)
                    with self.lock:self._accepted+=1
            else:self._append_tail(record)
        else:self._append_tail(record)
        self._failed_record=None;self._failed_writer=None
        return 1

    def snapshot(self):
        with self.lock:
            return {'pending_seal_parts':len(self._sealing),'seal_queue_limit':self.MAX_SEAL_PARTS,
                'preparing_parts':sum(not f.done() for f in self._preparing.values()),
                'ready_parts':len(self._standby)+sum(f.done() and f.exception() is None for f in self._preparing.values()),
                'archive_stage':self._archive_stage,
                'archive_stage_age_s':None if self._archive_stage_at is None else max(0.,time.monotonic()-self._archive_stage_at),
                'failed_original_records':int(self._failed_record is not None),'fault_drain':self._fault_drain,
                'accepted_rows':self._accepted,'committed_rows':self._committed,'failed':self._error is not None,
                'error':None if self._error is None else f'{type(self._error).__name__}: {self._error}'}

    def flush(self):
        # A partial flush seals the archive; appending behind a partial original
        # Zarr chunk is forbidden. Service teardown is the only caller.
        self.close()

    def close(self):
        if self._closed:
            self.check_archive();return
        self._closed=True
        deadline=time.monotonic()+self.close_timeout_s
        preparation=[*self._preparing.values(),*self._geometry_futures.values()]
        self._prepare_executor.shutdown(wait=False)
        _,pending=wait(preparation,timeout=max(0.,deadline-time.monotonic()))
        if pending:
            self._executor.shutdown(wait=False)
            self._latch_error(OSError('RAW preparation close timed out; original SSD journals retained'))
            self.check_archive()
        ready=list(self._standby.values())
        for future in self._preparing.values():
            if not future.cancelled() and future.exception() is None:ready.append(future.result())
        # All WD work stays on its own worker, including partial and empty
        # parts at teardown. A stuck volume cannot hide status or STOP completion.
        completion=[]
        for writer in [*self.writers.values(),*ready,*self._tail_writers.values(),*self._retired_tail]:
            completion.append(self._executor.submit(self._seal_and_publish,writer))
        completion.append(self._executor.submit(self._background_index))
        self._executor.shutdown(wait=False)
        _,pending=wait(completion,timeout=max(0.,deadline-time.monotonic()))
        if pending:self._latch_error(OSError('RAW archive close timed out; original SSD journals retained'))
        self.check_archive()
        if self.journal_path is not None:
            if {p.name for p in self.journal_path.iterdir()}!={'OWNER.json'}:
                raise OSError('unretired RAW parts retained with owner for recovery')
            (self.journal_path/'OWNER.json').unlink()
            self.journal_path.rmdir()
