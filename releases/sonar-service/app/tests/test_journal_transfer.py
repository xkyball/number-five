import json,threading
from pathlib import Path
import pytest
from no5_sonard.storage.raw_zarr import RawZarrV2Writer
from no5_sonard_openecho.rotating_archive import RotatingGeometryArchive
from no5_sonard_openecho.journal_transfer import publish_part,file_hash
from test_rotating_archive import record


def test_blocked_volume_transfer_preserves_new_durable_ssd_pings_and_hashes(tmp_path,monkeypatch):
    monkeypatch.setattr(RotatingGeometryArchive,'_check_volume',lambda self:None)
    destination=tmp_path/'volume/s.zarr';journal=tmp_path/'ssd';destination.parent.mkdir()
    a=RotatingGeometryArchive(RawZarrV2Writer(destination,sample_count=2048,sample_period_ns=2000,chunk_pings=4),
        eager_sample_counts=(),journal_root=journal,volume_guard={'device':-1,'uuid':'test'})
    first=a.writers[(2048,2000)];original=a._publish;gate=threading.Event();entered=threading.Event()
    def blocked(writer):
        if writer is first:entered.set();gate.wait(3)
        return original(writer)
    monkeypatch.setattr(a,'_publish',blocked)
    try:
        for i in range(8):a.append(record(i))
        assert entered.wait(1)
        assert a.archived_count==8 and a._destinations[first.path].exists() is False
        current=a.writers[(2048,2000)]
        assert [r.ping_id for r in current._read_pending()]==[4,5,6,7]
        assert all(r.samples.tobytes()==record(r.ping_id).samples.tobytes()for r in current._read_pending())
        gate.set();a._preparing[(2048,2000)].result(timeout=3);a.append(record(8));a.close()
        assert a.count==9 and a.pending_count==0 and not a.journal_path.exists()
        index=json.loads((destination/'geometry-index.json').read_text())
        assert all(e['committed_to_volume'] and 'journal_path' not in e for e in index['geometries'])
        assert sum(json.loads((destination.parent/e['path']/'manifest.json').read_text())['committed_rows'] for e in index['geometries'])==9
    finally:gate.set();a.close()


def test_transfer_hash_mismatch_never_deletes_original_journal(tmp_path,monkeypatch):
    import shutil
    source=tmp_path/'source';source.mkdir();(source/'raw').write_bytes(b'unchanged raw bytes')
    before=file_hash(source/'raw');copy=shutil.copytree
    def damaged(src,dst):
        result=copy(src,dst);(Path(dst)/'raw').write_bytes(b'bad destination');return result
    monkeypatch.setattr(shutil,'copytree',damaged)
    with pytest.raises(OSError,match='hash mismatch'):publish_part(source,tmp_path/'destination')
    assert file_hash(source/'raw')==before and not (tmp_path/'destination').exists()


def test_journal_has_hard_budget_with_sealing_reserve(tmp_path):
    a=RotatingGeometryArchive.__new__(RotatingGeometryArchive);a.journal_root=tmp_path
    with (tmp_path/'old-durable-raw').open('wb') as f:f.truncate(48*1024*1024)
    with pytest.raises(OSError,match='64 MiB'):a._check_journal_capacity(1)


def test_missing_data_volume_rejects_journal_fallback(tmp_path):
    a=RotatingGeometryArchive.__new__(RotatingGeometryArchive);a.journal_root=tmp_path/'ssd'
    a.volume_guard={'mount':str(tmp_path/'absent'),'device':-1}
    with pytest.raises(OSError,match='no journal fallback'):a._check_volume()


def test_orphaned_original_wal_recovers_with_identical_samples(tmp_path):
    import zlib,numpy as np
    from no5_sonard_openecho.journal_transfer import recover_verified_part
    root=tmp_path/'journal';root.mkdir();volume=tmp_path/'volume';volume.mkdir()
    source=root/'part.zarr';destination=volume/'part.zarr'
    (root/'OWNER.json').write_text(json.dumps({'schema':'no5.capture-journal-owner/1','pid':2147483647}))
    w=RawZarrV2Writer(source,sample_count=2048,sample_period_ns=2000,chunk_pings=4,
        attrs={'archive_destination':str(destination),'archive_volume_uuid':'expected'})
    w.append(record(1));w.append(record(2))  # process ended before ordinary close
    result=recover_verified_part(source,volume,'expected')
    values=np.frombuffer(zlib.decompress((destination/'samples/0.0').read_bytes()),dtype='<u2').reshape(4,2048)
    assert result['raw_rows']==2 and not source.exists()
    assert values[0].tobytes()==record(1).samples.tobytes() and values[1].tobytes()==record(2).samples.tobytes()


def test_live_owner_and_wrong_volume_cannot_be_recovered(tmp_path):
    import os
    from no5_sonard_openecho.journal_transfer import recover_verified_part
    root=tmp_path/'journal';root.mkdir();source=root/'part.zarr';source.mkdir()
    (root/'OWNER.json').write_text(json.dumps({'schema':'no5.capture-journal-owner/1','pid':os.getpid()}))
    with pytest.raises(OSError,match='live owner'):recover_verified_part(source,tmp_path/'volume','id')


def test_retired_part_disappearing_during_quota_scan_is_not_a_storage_fault(tmp_path,monkeypatch):
    import os
    a=RotatingGeometryArchive.__new__(RotatingGeometryArchive);a.journal_root=tmp_path
    def retired(root,onerror):
        onerror(FileNotFoundError('part already durably transferred'))
        return iter(())
    monkeypatch.setattr(os,'walk',retired)
    a._check_journal_capacity(100)
