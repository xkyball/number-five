"""Real pipes exercise bounded per-request framing without an Android GUI."""
from pathlib import Path
import base64
import json
import subprocess
import sys
import time
from types import SimpleNamespace

import pytest

sys.path.insert(0,str(Path(__file__).parents[1]/'tools'))
import android_preferences as m


@pytest.fixture
def channel(tmp_path,monkeypatch):
    script=tmp_path/'guest.py'
    script.write_text('''import sys,json,base64,time
mode=sys.argv[1]
print('NO5_PREF_READY 42',flush=True)
for line in sys.stdin:
 if line.strip()=='QUIT':break
 nonce=line.split()[1]
 if mode=='timeout':time.sleep(2.2)
 data=json.dumps({'version':1,'page':'SONAR','sonar':{'gainMode':'AUTO','gain':25.5}}).encode()
 if mode=='corrupt':data=b'{bad'
 if mode=='schema':data=b'{"version":2}'
 if mode=='size':data=b'x'*65537
 if mode=='nonce':nonce='0'*32
 if mode=='missing':print('NO5_PREF_ERROR missing',flush=True);break
 print('NO5_PREF_COMMITTED '+nonce+' 1 1:2:'+str(len(data))+':3:4',flush=True)
 print(base64.b64encode(data).decode(),flush=True)
''')
    original=subprocess.Popen
    monkeypatch.setattr(m.subprocess,'run',lambda *a,**k:SimpleNamespace(stdout=b'abc  /owned.dex\n'))
    def make(mode):
        monkeypatch.setattr(m.subprocess,'Popen',lambda *a,**k:original([sys.executable,'-u',str(script),mode],**k))
        return m.PreferencesReader(['adb'],'/owned.dex','abc')
    return make


def test_nonce_and_committed_values_preserved_single_process(channel):
    with channel('valid')as c:
        value,proof=c.read();value2,proof2=c.read()
        assert value==value2 and value['sonar']['gain']==25.5
        assert proof['response_nonce_verified']
        assert proof['request_nonce']!=proof2['request_nonce']
        assert c.read_count==2
    assert c.cleanup_verified


@pytest.mark.parametrize('mode,detail',[('nonce','receipt'),('corrupt','invalid JSON'),('schema','schema'),('size','length'),('missing','receipt')])
def test_stable_errors_are_fatal_without_fallback_or_retry(channel,mode,detail):
    with pytest.raises((RuntimeError,ValueError),match=detail):
        with channel(mode)as c:c.read()
    assert c.read_count==0


def test_query_timeout_remains_two_seconds(channel):
    with channel('timeout')as c:
        t=time.monotonic()
        with pytest.raises(TimeoutError):c.read()
        assert 1.9<=time.monotonic()-t<2.6
        assert c.read_count==0


def test_dex_mismatch_never_starts_a_guest_process(monkeypatch):
    monkeypatch.setattr(m.subprocess,'run',lambda *a,**k:SimpleNamespace(stdout=b'wrong  /x\n'))
    def forbidden(*a,**k):raise AssertionError('must not start')
    monkeypatch.setattr(m.subprocess,'Popen',forbidden)
    with pytest.raises(RuntimeError,match='hash changed'):
        with m.PreferencesReader(['adb'],'/x','expected'):pass
