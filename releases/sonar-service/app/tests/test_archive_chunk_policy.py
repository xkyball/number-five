import json,os,zlib
import numpy as np
from no5_sonard.storage.raw_zarr import RawPingRecord,RawZarrV2Writer
from no5_sonard_openecho.geometry_archive import GeometryArchive


def test_large_chunks_keep_each_ping_durable_and_recoverable_before_any_chunk_commit(tmp_path,monkeypatch):
    root=tmp_path/'segment.zarr'
    w=GeometryArchive(RawZarrV2Writer(root,sample_count=8192,sample_period_ns=2000,chunk_pings=256),eager_sample_counts=(2048,16384))
    assert set(w.writers)=={(8192,2000),(2048,2000),(16384,2000)}
    calls=[];original_sync=os.fsync
    def sync(fd):original_sync(fd);calls.append(fd)
    monkeypatch.setattr(os,'fsync',sync)
    expected=[]
    for i in range(40):
        a=np.full(2048,i,dtype=np.uint16);expected.append(a.copy());before=len(calls)
        w.append(RawPingRecord(i,1000000+i,2000000+i,2000,0,1,int(a.min()),int(a.max()),a))
        assert len(calls)>before  # Original WAL is durable before append returns.
    writer=w.writers[(2048,2000)]
    assert writer.count==0 and writer.pending_count==40
    # Re-open exactly as after process death; no published PING depends on a RAM buffer.
    recovered=RawZarrV2Writer(writer.path,sample_count=2048,sample_period_ns=2000,chunk_pings=256)
    assert recovered.pending_count==40
    assert [r.ping_id for r in recovered._buffer]==list(range(40))
    assert all(np.array_equal(r.samples,a)for r,a in zip(recovered._buffer,expected))
    recovered.close()
    chunk=np.frombuffer(zlib.decompress((writer.path/'samples/0.0').read_bytes()),dtype='<u2').reshape(256,2048)
    assert np.array_equal(chunk[:40],np.array(expected))
    assert json.loads((writer.path/'samples/.zarray').read_text())['shape']==[40,2048]
