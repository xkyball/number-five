"""Equivalence, real native selection and unchanged malformed-input contracts."""
import random
import numpy as np
import pytest
from no5_sonard import pico_protocol as wire
from no5_sonard.processing_m1 import max_pool_resample as reference
from no5_sonard_openecho.resample import max_pool_resample
from no5_sonard_openecho import cobs_codec as codec


@pytest.fixture(autouse=True)
def restore_backend():
    yield
    codec.configure('python')


@pytest.mark.parametrize('bins',[0,1,17,1024,1280])
def test_resample_boundary_matrix(bins):
    rng=np.random.default_rng(184)
    for n in (0,1,17,1023,1024,1025,2048,8192,16384):
        original=rng.integers(0,65536,n,dtype=np.uint16)
        for samples in (original,original[::-1]):
            for start in (-1,0,1,37,n,n+1):
                before=samples.tobytes()
                a=reference(samples,start_index=start,bins=bins)
                b=max_pool_resample(samples,start_index=start,bins=bins)
                assert (a.dtype,a.shape,a.tobytes())==(b.dtype,b.shape,b.tobytes())
                assert samples.tobytes()==before


def test_invalid_bins_keep_reference_errors():
    for bins in (-3,-1,1.5):
        with pytest.raises((ValueError,TypeError)):
            reference(np.arange(17),start_index=0,bins=bins)
        with pytest.raises((ValueError,TypeError)):
            max_pool_resample(np.arange(17),start_index=0,bins=bins)


def test_both_service_bindings_use_fast_resampler():
    from no5_sonard import service as base
    from no5_sonard_openecho import service as live
    assert base.max_pool_resample is live.max_pool_resample is max_pool_resample


def outcome(fn,data):
    try:return ('value',fn(data))
    except Exception as e:return ('error',type(e),str(e))


def test_native_and_reference_cobs_exact_outputs_and_errors():
    status=codec.configure('native')
    assert status['selected']=='native' and status['native_version']=='1.2.2'
    rng=random.Random(184)
    for n in (0,1,2,17,253,254,255,256,511,1024,4096,32768):
        for raw in (bytes(n),b'\xff'*n,bytes(rng.randrange(256) for _ in range(n))):
            encoded=wire.cobs_encode(raw)
            assert wire.cobs_decode(encoded)==wire._cobs_decode_reference(encoded)==raw
    malformed=[b'',b'\0',b'\x02\0',b'\x03A',b'\xff'+b'x'*253]
    malformed += [bytes(rng.randrange(256) for _ in range(rng.randrange(50))) for _ in range(2000)]
    for data in malformed:
        for value in (data,bytearray(data),memoryview(data)):
            assert outcome(wire.cobs_decode,value)==outcome(wire._cobs_decode_reference,value)


def test_backend_unavailable_never_masquerades_as_native(monkeypatch):
    def missing():raise ImportError('fixture')
    monkeypatch.setattr(codec,'_load_native',missing)
    assert codec.configure('auto')['selected']=='python'
    assert codec.snapshot()['reason']
    with pytest.raises(RuntimeError,match='native cobs'):
        codec.configure('native')
    assert codec.configure('python')['selected']=='python'


def test_native_crc_and_packet_errors_remain_visible():
    codec.configure('native')
    valid=wire.encode_packet(wire.Packet(wire.MessageType.STATUS,13,0,b'abc'))
    assert wire.decode_packet(valid[:-1]).payload==b'abc'
    raw=bytearray(wire._cobs_decode_reference(valid[:-1]));raw[-1]^=1
    with pytest.raises(ValueError,match='CRC32 mismatch'):
        wire.decode_packet(wire.cobs_encode(raw))


def test_cli_native_mode_is_explicit():
    from no5_sonard_openecho.__main__ import parse_args
    args=parse_args(['--device','/dev/pts/1','--expected-serial','fixture',
        '--storage-root','state','--trigger-threshold','150','--trigger-hysteresis','8',
        '--cobs-decoder','native'])
    assert args.cobs_decoder=='native'
