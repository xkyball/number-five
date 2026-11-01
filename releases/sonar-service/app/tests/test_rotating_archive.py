import json,threading,zlib
import numpy as np
import pytest
from no5_sonard.storage.raw_zarr import RawPingRecord,RawZarrV2Writer
from no5_sonard_openecho.rotating_archive import RotatingGeometryArchive


def record(i,n=2048):
    a=np.full(n,i,dtype=np.uint16)
    return RawPingRecord(i,1000000+i,2000000+i,2000,0,1,i,i,a)


def test_blocked_chunk_close_does_not_block_the_next_durable_raw_wal(tmp_path,monkeypatch):
    primary=RawZarrV2Writer(tmp_path/'segment.zarr',sample_count=2048,sample_period_ns=2000,chunk_pings=4)
    a=RotatingGeometryArchive(primary,eager_sample_counts=())
    gate=threading.Event();entered=threading.Event();original=RawZarrV2Writer.close
    def close(self):
        if self is primary:entered.set();gate.wait(3)
        return original(self)
    monkeypatch.setattr(RawZarrV2Writer,'close',close)
    try:
        for i in range(8):a.append(record(i))
        assert entered.wait(1) and not gate.is_set()
        assert a.count+a.pending_count==8
        # Every announced record exists in an original CRC-protected WAL,
        # although the old part's Zarr commit remains deliberately blocked.
        persisted=[]
        for entry in a._catalog:
            reader=RawZarrV2Writer.__new__(RawZarrV2Writer)
            reader.path=tmp_path/entry['path'];reader.sample_count=2048
            persisted.extend(reader._read_pending())
        assert [r.ping_id for r in persisted]==list(range(8))
        assert all(r.samples.tobytes()==record(r.ping_id).samples.tobytes()for r in persisted)
        gate.set();a._preparing[(2048,2000)].result(timeout=3)
        a.append(record(8));a.close()
        found=[]
        for entry in json.loads((primary.path/'geometry-index.json').read_text())['geometries']:
            p=tmp_path/entry['path'];rows=json.loads((p/'manifest.json').read_text())['committed_rows']
            if not rows:continue
            ids=np.frombuffer(zlib.decompress((p/'ping_id/0').read_bytes()),dtype='<u8')[:rows]
            values=np.frombuffer(zlib.decompress((p/'samples/0.0').read_bytes()),dtype='<u2').reshape(4,2048)[:rows]
            for i,v in zip(ids,values):assert v.tobytes()==record(int(i)).samples.tobytes()
            found.extend(map(int,ids))
        assert found==list(range(9)) and a.count==9 and a.pending_count==0
    finally:gate.set();a.close()


def test_unprepared_depth_geometry_is_built_offline_before_capture_acceptance(tmp_path,monkeypatch):
    a=RotatingGeometryArchive(RawZarrV2Writer(tmp_path/'s.zarr',sample_count=8192,sample_period_ns=2000,chunk_pings=4))
    gate=threading.Event();entered=threading.Event();original=a._new_writer
    def prepare(n,p):
        if n==3072:entered.set();gate.wait(3)
        return original(n,p)
    monkeypatch.setattr(a,'_new_writer',prepare)
    try:
        assert a.ensure_geometry(3072,2000) is False and entered.wait(1)
        with pytest.raises(OSError,match='unprepared'):a.append(record(1,3072))
        assert a.count+a.pending_count==0
        gate.set();a._geometry_futures[(3072,2000)].result(timeout=3)
        assert a.ensure_geometry(3072,2000)
        a.append(record(1,3072));a.close();assert a.count==1
    finally:gate.set();a.close()


def test_sealing_failure_is_latched_as_storage_fault(tmp_path,monkeypatch):
    p=RawZarrV2Writer(tmp_path/'s.zarr',sample_count=2048,sample_period_ns=2000,chunk_pings=2)
    a=RotatingGeometryArchive(p,eager_sample_counts=());original=RawZarrV2Writer.close
    def fail(self):
        if self is p:raise OSError('disk failure during seal')
        return original(self)
    monkeypatch.setattr(RawZarrV2Writer,'close',fail)
    for i in range(3):a.append(record(i))
    import time
    deadline=time.monotonic()+3
    while not a.snapshot()['failed'] and time.monotonic()<deadline:time.sleep(.005)
    assert 'disk failure during seal' in a.snapshot()['error']
    with pytest.raises(OSError,match='sealing failed: disk failure during seal'):a.check_archive()
    with pytest.raises(OSError):a.close()
    assert (p.path/'_pending.bin').exists()
