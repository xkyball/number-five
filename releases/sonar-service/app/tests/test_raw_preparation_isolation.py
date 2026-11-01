"""WD work must not own the factory for the next local original RAW WAL."""
import contextlib
import threading
import time

from no5_sonard.storage.raw_zarr import RawZarrV2Writer
from no5_sonard_openecho.archive_fault import JournalBudget
from no5_sonard_openecho.rotating_archive import RotatingGeometryArchive
from test_rotating_archive import record


def test_wd_publish_stall_does_not_exhaust_prepared_local_parts(tmp_path, monkeypatch):
    monkeypatch.setattr(RotatingGeometryArchive, '_check_volume', lambda self: None)
    destination = tmp_path/'volume/segment.zarr'; destination.parent.mkdir()
    archive = RotatingGeometryArchive(RawZarrV2Writer(destination,
        sample_count=2048, sample_period_ns=2000, chunk_pings=2),
        eager_sample_counts=(), journal_root=tmp_path/'ssd',
        volume_guard={'device': -1, 'uuid': 'test'})
    gate = threading.Event(); entered = threading.Event(); original = archive._publish
    first = archive.writers[(2048, 2000)]
    def publish(writer):
        if writer is first:
            entered.set()
            assert gate.wait(15)
        return original(writer)
    monkeypatch.setattr(archive, '_publish', publish)
    try:
        for i in range(4): archive.append(record(i))
        assert entered.wait(3)
        # The old implementation's future includes the blocked WD publication.
        # The replacement must finish local preparation independently.
        future = archive._preparing[(2048, 2000)]
        deadline = time.monotonic()+3
        while not future.done() and time.monotonic()<deadline: time.sleep(.005)
        archive.append(record(4))
        archive.append(record(5))
        assert not gate.is_set() and archive.archived_count == 6
        assert JournalBudget(tmp_path/'ssd').used() < JournalBudget.LIMIT-JournalBudget.RESERVE
        current = archive.writers[(2048, 2000)]
        assert [row.ping_id for row in current._read_pending()] == [4, 5]
        assert all(row.samples.tobytes() == record(row.ping_id).samples.tobytes()
                   for row in current._read_pending())
        gate.set(); archive.close()
        assert archive.count == 6 and archive.pending_count == 0
    finally:
        gate.set()
        with contextlib.suppress(OSError): archive.close()


def test_failed_rotation_keeps_future_and_original_until_stopped_fault_drain(tmp_path, monkeypatch):
    import pytest
    archive=RotatingGeometryArchive(RawZarrV2Writer(tmp_path/'segment.zarr',
        sample_count=2048,sample_period_ns=2000,chunk_pings=2),eager_sample_counts=())
    gate=threading.Event();entered=threading.Event();original=archive._new_writer
    def blocked(n,p,**kwargs):
        entered.set();assert gate.wait(15)
        return original(n,p,**kwargs)
    monkeypatch.setattr(archive,'_new_writer',blocked)
    try:
        for i in range(4):archive.append(record(i))
        assert entered.wait(3)
        future=archive._preparing[(2048,2000)]
        failed=record(4)
        with pytest.raises(OSError,match='no prepared'):archive.append(failed)
        assert archive._preparing[(2048,2000)] is future
        assert archive._failed_record is failed
        gate.set();future.result(timeout=3)
        assert archive.begin_fault_drain()==1
        archive.append(record(5))
        assert archive.archived_count==6 and archive.snapshot()['failed_original_records']==0
        rows=archive._tail_writers[(2048,2000)]._read_pending()
        assert [(r.ping_id,r.time_utc_ns,r.samples.tobytes()) for r in rows]==[
            (r.ping_id,r.time_utc_ns,r.samples.tobytes()) for r in (failed,record(5))]
        archive.close()
        assert archive.count==6
    finally:
        gate.set();archive.close()


def test_full_seal_queue_holds_but_fault_tail_keeps_dequeued_original(tmp_path,monkeypatch):
    import pytest
    monkeypatch.setattr(RotatingGeometryArchive,'MAX_SEAL_PARTS',1)
    archive=RotatingGeometryArchive(RawZarrV2Writer(tmp_path/'segment.zarr',
        sample_count=2048,sample_period_ns=2000,chunk_pings=2),eager_sample_counts=())
    gate=threading.Event();entered=threading.Event();original=archive._publish
    def blocked(writer):entered.set();assert gate.wait(15);return original(writer)
    monkeypatch.setattr(archive,'_publish',blocked)
    try:
        for i in range(4):archive.append(record(i))
        assert entered.wait(3)
        archive._preparing[(2048,2000)].result(timeout=3)
        with pytest.raises(OSError,match='queue capacity'):archive.append(record(4))
        assert archive.snapshot()['pending_seal_parts']==1
        assert archive.begin_fault_drain()==1
        assert archive.archived_count==5
        gate.set();archive.close();assert archive.count==5
    finally:gate.set();archive.close()


def test_post_write_fsync_error_drain_does_not_duplicate_original(tmp_path,monkeypatch):
    import pytest
    archive=RotatingGeometryArchive(RawZarrV2Writer(tmp_path/'segment.zarr',
        sample_count=2048,sample_period_ns=2000,chunk_pings=2),eager_sample_counts=())
    writer=archive.writers[(2048,2000)];original=writer._append_pending
    def write_then_fail(row):original(row);raise OSError('fsync acknowledgement failed')
    monkeypatch.setattr(writer,'_append_pending',write_then_fail)
    with pytest.raises(OSError,match='acknowledgement'):archive.append(record(1))
    assert archive.archived_count==0 and len(writer._read_pending())==1
    assert archive.begin_fault_drain()==1 and archive.archived_count==1
    assert not archive._tail_writers
    archive.close();assert archive.count==1


def test_ssd_append_does_not_probe_wd_and_fault_tail_obeys_hard_budget(tmp_path,monkeypatch):
    import pytest
    monkeypatch.setattr(RotatingGeometryArchive,'_check_volume',lambda self:None)
    destination=tmp_path/'volume/s.zarr';destination.parent.mkdir()
    archive=RotatingGeometryArchive(RawZarrV2Writer(destination,
        sample_count=2048,sample_period_ns=2000,chunk_pings=2),eager_sample_counts=(),
        journal_root=tmp_path/'ssd',volume_guard={'device':-1,'uuid':'test'})
    def no_wd():raise AssertionError('WD touched by SSD admission')
    monkeypatch.setattr(archive,'_check_volume',no_wd)
    archive.append(record(1))
    assert archive.archived_count==1
    monkeypatch.setattr(archive,'_check_volume',lambda:None)
    filler=tmp_path/'ssd/filler'
    with filler.open('wb') as out:out.truncate(JournalBudget.LIMIT-10000)
    JournalBudget(tmp_path/'ssd').invalidate()  # out-of-band fixture, outside owned writers
    with pytest.raises(OSError,match='64 MiB'):archive.append(record(2))
    # Completion reserve is still a hard limit; it cannot consume beyond64MiB.
    with pytest.raises(OSError,match='64 MiB'):archive.begin_fault_drain()
    assert filler.exists() and archive._failed_record.ping_id==2
    filler.unlink();assert archive.begin_fault_drain()==1
    archive.close();assert archive.count==2


def test_close_timeout_keeps_original_wal_and_owner_without_hiding_status(tmp_path,monkeypatch):
    import pytest
    monkeypatch.setattr(RotatingGeometryArchive,'_check_volume',lambda self:None)
    destination=tmp_path/'volume/s.zarr';destination.parent.mkdir()
    archive=RotatingGeometryArchive(RawZarrV2Writer(destination,
        sample_count=2048,sample_period_ns=2000,chunk_pings=2),eager_sample_counts=(),
        journal_root=tmp_path/'ssd',volume_guard={'device':-1,'uuid':'test'},close_timeout_s=.05)
    gate=threading.Event();entered=threading.Event();original=archive._publish
    def blocked(writer):entered.set();assert gate.wait(15);return original(writer)
    monkeypatch.setattr(archive,'_publish',blocked)
    archive.append(record(1));started=time.monotonic()
    try:
        with pytest.raises(OSError,match='sealing failed'):archive.close()
        assert time.monotonic()-started<.5 and entered.is_set()
        assert (archive.journal_path/'OWNER.json').exists()
        assert archive.snapshot()['failed'] and archive.archived_count==1
    finally:
        gate.set()
        # Join only the deliberately unblocked test worker, not production I/O.
        archive._executor.shutdown(wait=True)
