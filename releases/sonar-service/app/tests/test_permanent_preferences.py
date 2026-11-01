"""An AtomicFile write in progress must not masquerade as a broken UI record."""
import base64
import importlib.util
import json
import os
from pathlib import Path

import pytest

path = Path(os.environ.get('SONAR_VERIFY_CANDIDATE', Path(__file__).parents[1]/'tools/verify_permanent_chain.py'))
spec = importlib.util.spec_from_file_location('preferences_chain', path)
m = importlib.util.module_from_spec(spec); spec.loader.exec_module(m)


def receipt(data, attempts=1, size=None):
    size = len(data) if size is None else size
    return ('NO5_PREF_COMMITTED '+str(attempts)+' 1:2:'+str(size)+':3:4\n').encode()+base64.b64encode(data)+b'\n'


def test_committed_record_preserves_page_and_display_values(monkeypatch):
    prefs = {'version':1,'page':'AUDIO','sonar':{'gainMode':'AUTO','gain':25.5}}
    calls = []
    def command(args, timeout):
        calls.append((args, timeout))
        return receipt(json.dumps(prefs).encode(), attempts=3)
    monkeypatch.setattr(m, 'command', command)
    value, proof = m.read_view_preferences(['adb'])
    assert value == prefs  # A committed wrong page is never changed to SONAR.
    assert proof['attempts'] == 3 and proof['method'] == 'committed_atomic_file_inode'
    assert len(calls)==1 and calls[0][1]==2


@pytest.mark.parametrize('raw', [
    receipt(b''), receipt(b'{"version":'), receipt(b'null'),
    receipt(b'{"version":2}'), receipt(b'{"version":1}', size=50),
    receipt(b'{"version":1}', attempts=17),
    b'NO5_PREF_ERROR no_committed_snapshot_within_16_attempts\n',
    b'NO5_PREF_ERROR missing_base\n', b'',
])
def test_invalid_or_missing_committed_record_is_fatal_without_retry(monkeypatch, raw):
    calls=[]
    def command(args, timeout):
        calls.append(args); return raw
    monkeypatch.setattr(m, 'command', command)
    with pytest.raises(RuntimeError): m.read_view_preferences(['adb'])
    assert len(calls)==1  # No retry of a stable invalid JSON response.
