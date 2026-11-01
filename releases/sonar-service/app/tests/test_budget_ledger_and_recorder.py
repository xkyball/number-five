import asyncio
import threading
from pathlib import Path
import numpy as np
import pytest
from no5_sonard.config import ServiceConfig
from no5_sonard.storage.raw_zarr import RawPingRecord
from no5_sonard_openecho.archive_fault import JournalBudget, ArchiveFault
from no5_sonard_openecho.service import HostService
from no5_sonard_openecho.test_recorder import TestRecorder


def test_small_admissions_reuse_conservative_bound_and_reclaim_only_by_scan(tmp_path, monkeypatch):
    b=JournalBudget(tmp_path);original=b.used;calls=[]
    monkeypatch.setattr(b,'used',lambda:(calls.append(1),original())[1])
    for n in range(1000):
        with b.reserve(4096):
            (tmp_path/'stream').write_bytes(b'x'*4096)
    assert len(calls)==1 and b.last_used==4096000
    monkeypatch.setattr(b,'LIMIT',4096000+16384);monkeypatch.setattr(b,'RESERVE',16384)
    with b.reserve(4096):pass
    assert len(calls)==2 and b.last_used==8192


def test_cached_bound_counts_unwritten_admissions_and_no_negative_credit(tmp_path, monkeypatch):
    b=JournalBudget(tmp_path);monkeypatch.setattr(b,'LIMIT',100);monkeypatch.setattr(b,'RESERVE',20)
    with b.reserve(70):
        with pytest.raises(ArchiveFault):
            with b.reserve(31,emergency=True):pass
        with b.reserve(30,emergency=True):pass
    assert b.last_used==100


def test_default_session_creates_no_archive_or_writer(tmp_path):
    root=tmp_path/'never-created'
    h=HostService(ServiceConfig(storage_root=root))
    h._ensure_session();h._open_segment('test',2048,2000)
    assert not root.exists() and h._writer is None and h._session_path is None
    assert h.snapshot()['archive_health']['state']=='DISABLED'
    assert h.snapshot()['test_recorder']['enabled'] is False


def test_default_live_selection_is_readable_without_enabling_archive(tmp_path):
    import sys
    sys.path.insert(0,str(Path(__file__).resolve().parents[2]/'no5-pi-gateway/src'))
    from no5_pi_gateway.sonar_contract import display_selection
    root=tmp_path/'never-created'
    host=HostService(ServiceConfig(storage_root=root))
    host._ensure_session();host._open_segment('test',2048,2000)
    value=host.display_selection()
    assert display_selection(value)['rawArchiveSource']=='all'
    assert value['raw_archive_enabled'] is False and value['test_archive_enabled'] is False
    assert host.snapshot()['archive_health']['state']=='DISABLED'
    assert not root.exists() and host._writer is None


def test_ring_overwrites_oldest_queued_entries_without_waiting_for_io(tmp_path):
    active=threading.Event();release=threading.Event();seen=[]
    def write(kind,value,size):
        active.set();assert release.wait(2);seen.append(value)
    r=TestRecorder(tmp_path/'archive',writer=write);r.INPUT_BYTES=12;r.INPUT_ENTRIES=3
    r.offer('test',0,4);assert active.wait(1)
    for n in range(1,11):r.offer('test',n,4)
    s=r.snapshot();assert s['dropped_entries']==7 and s['pending_entries']==3
    assert s['affects_live_health'] is False and seen==[]
    release.set();r.close();assert seen==[0,8,9,10]


def test_recorder_failure_is_visible_and_never_escapes_offer_or_close(tmp_path):
    def failed(*_):raise OSError('disk unavailable')
    r=TestRecorder(tmp_path/'archive',writer=failed)
    r.offer('wire:rx',b'x',1);r.close()
    s=r.snapshot();assert s['state']=='FAILED' and 'disk unavailable' in s['error']
    r.offer('wire:rx',b'y',1)
    assert r.snapshot()['dropped_entries']==2


def test_recorder_raw_copy_preserves_original_bytes_and_exact_timestamp(tmp_path):
    done=threading.Event();release=threading.Event();rows=[]
    def write(kind,value,size):
        done.set();assert release.wait(2);rows.append(value)
    r=TestRecorder(tmp_path/'archive',writer=write)
    values=np.arange(2048,dtype=np.uint16)
    record=RawPingRecord(7,1234567890,555,2000,0,1,0,4095,values)
    r.raw(record);assert done.wait(1);values[:]=0;release.set();r.close()
    assert rows[0].time_utc_ns==1234567890
    assert rows[0].samples.tobytes()==np.arange(2048,dtype=np.uint16).tobytes()


def test_recorder_encoding_error_never_escapes_to_live_source(tmp_path):
    r=TestRecorder(tmp_path/'archive')
    r.evidence('unserializable', {'unexpected':object()})
    r.close()
    assert r.snapshot()['state']=='FAILED'
    assert r.snapshot()['affects_live_health'] is False


def test_disk_hard_bound_preserves_original_and_does_not_hold_service(tmp_path, monkeypatch):
    monkeypatch.setattr(JournalBudget,'LIMIT',128*1024)
    monkeypatch.setattr(JournalBudget,'RESERVE',32*1024)
    root=tmp_path/'test-archive';root.mkdir();original=b'x'*(128*1024)
    (root/'original').write_bytes(original)
    r=TestRecorder(root);h=HostService(ServiceConfig(storage_root=tmp_path/'unused'))
    h.test_recorder=r
    r.wire('rx',b'new');r.close()
    assert r.snapshot()['state']=='FAILED'
    assert (root/'original').read_bytes()==original
    assert h.snapshot()['archive_health']['state']=='DISABLED'
    assert not h._storage_fault and h.status.state!='HOLD'


def test_saved_raw_adc_and_utc_are_byte_exact(tmp_path):
    import sys
    sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'tools'))
    from verify_clip_endurance_archive import array
    values=np.arange(2048,dtype=np.uint16)
    r=TestRecorder(tmp_path/'archive')
    r.raw(RawPingRecord(7,1234567890,555,2000,0,1,0,4095,values));r.close()
    assert r.snapshot()['dropped_raw_pings']==0 and r.snapshot()['written_raw_pings']==1
    group=next((tmp_path/'archive/raw').glob('*.zarr'))
    np.testing.assert_array_equal(next(array(group/'samples'))[0],values)
    assert int(next(array(group/'time_utc_ns'))[0])==1234567890


def test_recorder_accepts_cli_geometry_and_bounds_active_writer_buffers(tmp_path):
    r=TestRecorder(tmp_path/'archive')
    for n in [32,256,10000,16384,8192,10000]:
        r.raw(RawPingRecord(n,1234567890+n,555,4000,0,1,0,4095,np.arange(n,dtype=np.uint16)))
    r.close()
    assert r.snapshot()['state']=='OK' and r.snapshot()['written_raw_pings']==6
    assert len(r._writers)<=r.MAX_WRITERS
