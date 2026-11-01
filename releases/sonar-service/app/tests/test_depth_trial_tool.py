"""Headless permission changes observation only, never run/abort guards."""
import importlib.util
import json
from pathlib import Path
import pytest

spec=importlib.util.spec_from_file_location('depth_trial',Path(__file__).parents[1]/'tools/depth_rate_trial.py')
trial=importlib.util.module_from_spec(spec);spec.loader.exec_module(trial)


def test_explicit_observation_choice_required_before_contact(tmp_path,monkeypatch):
    monkeypatch.setattr(trial,'call',lambda *a,**k:pytest.fail('must reject before contacting device'))
    for kwargs in [{},{'headless':True,'view_proof':tmp_path/'unused'}]:
        with pytest.raises(ValueError):trial.run(tmp_path/'run',2,**kwargs)


def test_headless_keeps_native_guard_and_aborts_failed_run(tmp_path,monkeypatch):
    calls=[]
    passive={'state':'RUNNING','session':{'id':'actual'},'rates':{'ping_hz':3.0},
             'transmit':{'original_reference':{'observation_fresh':True,
                 'latest':{'meaning':'qualified','native_stable':3}}}}
    def api(method,path,payload=None,**kwargs):
        calls.append((method,path,kwargs['api_base']))
        if path.endswith('/status'):return passive
        if method=='GET':return {'run':{'state':'FAILED','verified':False,'elapsed_s':1},'rate_control':{}}
        return {}
    monkeypatch.setattr(trial,'call',api)
    with pytest.raises(RuntimeError,match='failed'):
        trial.run(tmp_path/'run',2,headless=True,api_base='http://127.0.0.1:8797')
    assert calls[-1][:2]==('POST','/api/v1/transmit/abort')
    assert all(c[2]=='http://127.0.0.1:8797' for c in calls)
    assert json.loads((tmp_path/'run/observation.json').read_text())['headless']
    passive['rates']['ping_hz']=0
    with pytest.raises(RuntimeError,match='native reference'):
        trial.run(tmp_path/'bad',2,headless=True)
    assert not (tmp_path/'bad').exists()
