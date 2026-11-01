import importlib.util
import sys
import types
import pathlib
import struct
ROOT=pathlib.Path(__file__).resolve().parents[1]
BASE=ROOT.parent/'sonar-parser'
def load(name,path):
    class Array(list):
        def copy(self): return Array(self)
    fake=types.SimpleNamespace(ndarray=Array, frombuffer=lambda raw, dtype, count, offset: Array(range(count)))
    previous=sys.modules.get('numpy'); sys.modules['numpy']=fake
    spec=importlib.util.spec_from_file_location(name,path); module=importlib.util.module_from_spec(spec); sys.modules[name]=module
    try: spec.loader.exec_module(module)
    finally:
        if previous is None: sys.modules.pop('numpy',None)
        else: sys.modules['numpy']=previous
    return module
def test_hello_adds_tx_capability_and_reserved_stays_opt_in():
    base=(ROOT.parent/'shared-pico-extraction-v1/src/base/session.c').read_text(); candidate=(ROOT/'src/base/session.c').read_text()
    needle='hello_t h={ .minor=5,.caps=(1u<<31)|(1u<<30)|(1u<<1)|(1u<<2)|(1u<<3)|(1u<<5),.rate=500000,.count=MAX_PING_SAMPLES,.bits=12,.mv=3300 };'
    assert needle in base
    assert needle.replace(".minor=5,.caps=", ".minor=7,.caps=(1u<<6)|") in candidate
    assert '!(c.reserved&~1u)' in candidate and 'b->acq.marker_enabled=marker' in candidate
def test_exact_sonard_parser_accepts_tx_valid_ping_without_schema_change():
    m1=load('m1',BASE/'sonard-link/host/no5_sonard/pico_protocol.py')
    payload=struct.pack('<QQIIiHHI',7,1_000_000,2000,32,5,100,900,41)+bytes(64)
    ping=m1.parse_ping_frame(payload)
    assert ping.tx_zero_sample_index==5 and ping.ping_flags==41 and len(ping.samples)==32
def test_scope_marker_is_bounded_to_a_4096_sample_stream_block():
    code=(ROOT/'src/modules/scope_stream.c').read_text()
    assert 'prefix.flags|=(1u<<8)|(marker_index<<16)' in code
    assert 'base_take_tx_marker(b,prefix.time_us,COUNT,prefix.period,&marker_index)' in code
def test_ping_uses_measured_marker_as_tx_zero_only_when_present():
    code=(ROOT/'src/modules/sonard_ping.c').read_text()
    assert 'p.zero=(int32_t)marker_index;' in code and 'p.flags|=1u;' in code
