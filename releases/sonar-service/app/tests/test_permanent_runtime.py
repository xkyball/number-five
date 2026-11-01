import importlib.util
import json
from pathlib import Path
import time
import uuid
import pytest
from types import SimpleNamespace

TOOLS=Path(__file__).resolve().parents[1]/'tools'
spec=importlib.util.spec_from_file_location('permanent_runtime',TOOLS/'permanent_runtime.py')
runtime=importlib.util.module_from_spec(spec);spec.loader.exec_module(runtime)


def fixture(tmp_path):
    runtime.save(tmp_path/'runtime.private.json',{'schema':'no5.sonar-runtime/1',
        'targets':{'emulator':{'ssh':[]},'pi':{'ssh':[]}}})
    runtime.save(tmp_path/'desired.json',{'target':'emulator','start_request':None})
    return runtime.Runtime(tmp_path)


def test_blocked_pi_selection_preserves_saved_and_active_emulator(tmp_path,monkeypatch):
    r=fixture(tmp_path);r.target='emulator';r.owner=str(uuid.uuid4())
    def local_source(*_):return {'conflict':'local physical source configured'}
    monkeypatch.setattr(runtime,'remote',local_source)
    with pytest.raises(RuntimeError,match='local physical'):r.command({'command':'target','target':'pi'})
    assert r.desired['target']=='emulator'and r.target=='emulator'
    assert runtime.load(tmp_path/'desired.json')['target']=='emulator'


def test_unreachable_old_target_blocks_activation_until_lease_expiry(tmp_path,monkeypatch):
    r=fixture(tmp_path);r.target='emulator';r.owner=str(uuid.uuid4())
    r.desired['target']='pi';r.last_lease_deadline=time.monotonic()+60
    requests=[]
    def offline(profile,request):
        requests.append(request['method']);raise OSError('old target offline')
    monkeypatch.setattr(runtime,'remote',offline)
    r.route_tick()
    assert requests==['DELETE']and r.target=='emulator'
    assert 'expire'in r.route_reason


def test_stop_while_host_is_absent_persists_revocation_before_next_boot(tmp_path):
    r=fixture(tmp_path)
    path=tmp_path/'control/transmit-recovery.json'
    runtime.save(path,{'schema':'no5.transmit-recovery-policy/1','mode':'auto','desired':{'intent':'old'}})
    r.command({'command':'stop'})
    assert runtime.load(path)['desired']is None
    assert (tmp_path/'control/operator-stop.json').exists()
    assert runtime.load(tmp_path/'desired.json')['start_request']is None


def test_target_off_only_changes_forwarding_selection(tmp_path):
    r=fixture(tmp_path);r.desired['start_request']='an-explicit-start'
    r.command({'command':'target','target':'off'})
    assert r.desired=={'target':'off','start_request':'an-explicit-start'}


def test_start_does_not_bypass_an_archive_hold(tmp_path):
    r=fixture(tmp_path);runtime.save(tmp_path/'control/archive-hold.json',{'reason':'untransferred raw'})
    with pytest.raises(RuntimeError,match='HOLD'):r.command({'command':'start'})
    assert r.desired['start_request']is None


def test_mac_python_exec_identity_is_settled_before_durable_adoption_record(tmp_path,monkeypatch):
    r=fixture(tmp_path)
    child=SimpleNamespace(pid=123,poll=lambda:None)
    identities=iter(['venv-launcher','real-interpreter','real-interpreter','real-interpreter','real-interpreter'])
    monkeypatch.setattr(runtime.subprocess,'Popen',lambda *a,**k:child)
    monkeypatch.setattr(runtime,'process_identity',lambda _:next(identities))
    monkeypatch.setattr(runtime.time,'sleep',lambda _:None)
    assert r.spawn(['python','owned.py'],'capture')is child
    assert runtime.load(tmp_path/'owned-capture.json')['identity']=='real-interpreter'


def test_unverifiable_crash_journal_latches_hold_before_new_usb_owner(tmp_path,monkeypatch):
    r=fixture(tmp_path);r.config['recover_journals']=['recover']
    (tmp_path/'journal/orphan').mkdir(parents=True)
    monkeypatch.setattr(runtime.subprocess,'run',lambda *a,**k:SimpleNamespace(returncode=1,stderr='CRC mismatch; original retained'))
    r.host_tick()
    assert r.host is None
    assert 'CRC mismatch'in runtime.load(tmp_path/'control/archive-hold.json')['reason']
    # Restart remains blocked even if the next recovery invocation would pass.
    monkeypatch.setattr(runtime.subprocess,'run',lambda *a,**k:pytest.fail('HOLD bypassed'))
    r.next_host=0;r.host_tick()
    assert r.host is None


def test_explicit_lab_address_survives_additional_interfaces_without_guessing():
    profile={'lan_address':'192.168.68.70'}
    assert runtime.relay_destination(profile,['192.168.68.70','192.168.20.1'])=='192.168.68.70'
    with pytest.raises(RuntimeError,match='no longer assigned'):
        runtime.relay_destination(profile,['192.168.20.1'])
    with pytest.raises(RuntimeError,match='ambiguous'):
        runtime.relay_destination({},['192.168.68.70','192.168.20.1'])


def test_owned_emulator_ssh_endpoint_survives_missing_dhcp_without_changing_pi():
    profile={'relay_transport':'ssh_loopback','lan_address':'192.168.68.70'}
    assert runtime.relay_destination(profile,[])=='10.0.2.2'
    assert runtime.tunnel_forwards(profile,'emulator')==[
        '-R','127.0.0.1:18788:127.0.0.1:8787','-L','127.0.0.1:19443:127.0.0.1:9443']
    assert runtime.tunnel_forwards(profile,'pi')==['-R','127.0.0.1:18788:127.0.0.1:8787']
    assert runtime.tunnel_forwards({},'emulator')==['-R','127.0.0.1:18788:127.0.0.1:8787']


def test_live_command_has_no_archive_or_data_volume_dependency(tmp_path):
    config={'python':'python','wheel_launcher':'launch.py','volume_launcher':'must-not-run.py',
        'host_arguments':['--device','/dev/test','--storage-root','state',
                          '--archive-chunk-pings','256','--expected-serial','test']}
    args=runtime.live_host_arguments(config,tmp_path,'host.whl',tmp_path/'pid.json')
    assert 'must-not-run.py' not in args and '--capture-journal-root' not in args
    assert '--test-archive' not in args
    assert args[args.index('--storage-root')+1]==str(tmp_path/'live-state')


def test_live_archive_policy_is_separate_from_optional_recorder_quality():
    import importlib.util
    spec=importlib.util.spec_from_file_location('archive_policy',TOOLS/'verify_permanent_chain.py')
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
    status={'archive_health':{'state':'DISABLED','enabled':False},
            'test_recorder':{'state':'FAILED','dropped_entries':123}}
    assert module.archive_policy_error(status,'independent_optional_recorder') is None
    assert module.archive_policy_error(status,'required_legacy_archive')
    status['archive_health']={'state':'HOLD','enabled':True}
    assert module.archive_policy_error(status,'independent_optional_recorder')
    assert module.archive_policy_error(status,'unknown')


def test_direct_host_resource_identity_does_not_guess_wrapper_children(monkeypatch):
    import importlib.util,sys
    spec=importlib.util.spec_from_file_location('host_resource_policy',TOOLS/'verify_permanent_chain.py')
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
    identity={'pid':123,'start_abstime':456,'executable_uuid':'own'}
    monkeypatch.setitem(sys.modules,'macos_memory',SimpleNamespace(process_memory=lambda p:identity,
        process_arguments=lambda p:['python','checked-wheel','--control-root','own']))
    config={'host_process_identity':dict(identity),'host_kernel_argv':['python','checked-wheel','--control-root','own']}
    assert module.direct_live_host_pid({'pid':123},config)=='123'
    config['host_process_identity']['start_abstime']=789
    with pytest.raises(RuntimeError,match='identity changed'):module.direct_live_host_pid({'pid':123},config)
