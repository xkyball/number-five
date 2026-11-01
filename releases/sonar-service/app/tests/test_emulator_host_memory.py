"""A low RSS must not hide a large emulator allocation or permit foreign signals."""
import importlib.util
from pathlib import Path
import signal
import sys
import json
import time
import os
import subprocess

import pytest

TOOLS=Path(__file__).parents[1]/'tools'
sys.path.insert(0,str(TOOLS))
import macos_memory
import run_sonar_emulator as m


def test_physical_charge_not_residency_enforces_ceiling():
    assert m.over_limit({'resident_bytes':600*1024**2,'phys_footprint_bytes':17*1024**3},6*1024**3)
    assert not m.over_limit({'resident_bytes':600*1024**2,'phys_footprint_bytes':3*1024**3},6*1024**3)


def test_hardware_gpu_is_required_even_when_window_is_visible():
    good='GPU Vendor=[Google (Apple)]\nGPU Renderer=[Android Emulator OpenGL ES Translator (Apple M4 Pro)]\nGPU Version=[OpenGL ES 3.0 Metal]'
    assert m.hardware_renderer('available: swiftshader\n'+good)['vendor']=='Google (Apple)'
    with pytest.raises(RuntimeError,match='software fallback'):
        m.hardware_renderer(good.replace('Apple M4 Pro','ANGLE SwiftShader LLVM'))
    assert m.hardware_renderer('Booting') is None


def test_only_standalone_host_graphics_arguments_are_adopted():
    good='/sdk/qemu-system-aarch64 -avd No5_Lumina_API29 -gpu host -feature -Vulkan -no-snapshot'
    m.verify_arguments(good,'No5_Lumina_API29')
    for bad in [good.replace(' -feature -Vulkan',''),good+' -feature Vulkan',good.replace('host','swiftshader'),good+' -qt-hide-window',good.replace('No5_Lumina_API29','Foreign'),'/bin/sleep -avd No5_Lumina_API29 -gpu host']:
        with pytest.raises(RuntimeError):m.verify_arguments(bad,'No5_Lumina_API29')


def test_guest_composition_comparison_never_accepts_software_or_wrong_feature():
    good='/sdk/qemu-system-aarch64 -avd No5_Lumina_API29 -gpu host -feature -Vulkan,-HostComposition'
    m.verify_arguments(good,'No5_Lumina_API29',guest_composition=True)
    for wrong in [good.replace(',-HostComposition',''),good+' -feature HostComposition',
                  good.replace('-gpu host','-gpu swiftshader')]:
        with pytest.raises(RuntimeError):m.verify_arguments(wrong,'No5_Lumina_API29',guest_composition=True)


def test_reused_pid_never_receives_a_signal():
    identity={'start_abstime':1,'executable_uuid':'a'};signals=[]
    with pytest.raises(RuntimeError,match='identity changed'):
        m.terminate_verified(42,identity,lambda _:dict(identity,start_abstime=2),lambda *x:signals.append(x))
    assert not signals
    m.terminate_verified(42,identity,lambda _:identity,lambda *x:signals.append(x),
                         expected_arguments=['owned','-gpu','host'],read_arguments=lambda _:['owned','-gpu','host'])
    assert signals==[(42,signal.SIGTERM)]


def test_changed_or_missing_full_kernel_arguments_never_receive_signal():
    identity={'start_abstime':1,'executable_uuid':'a'};signals=[]
    for expected,actual in [(None,['owned']),(['owned','-gpu','host'],['owned','-gpu host'])]:
        with pytest.raises(RuntimeError,match='full kernel arguments'):
            m.terminate_verified(42,identity,lambda _:identity,lambda *x:signals.append(x),
                                 expected_arguments=expected,read_arguments=lambda _:actual)
    assert not signals


@pytest.mark.skipif(sys.platform!='darwin',reason='real macOS argument ABI')
def test_kernel_arguments_preserve_spaces_empty_values_and_boundaries():
    arguments=[sys.executable,'-c','import time; time.sleep(20)',
               '/SDK with spaces/qemu-system-aarch64','', '-gpu host']
    child=subprocess.Popen(arguments)
    try:
        actual=macos_memory.process_arguments(child.pid)
        assert actual[1:] == arguments[1:]
        good=['/SDK with spaces/qemu-system-aarch64','-avd','No5_Lumina_API29',
              '-gpu','host','-feature','-Vulkan']
        m.verify_arguments(good,'No5_Lumina_API29')
        with pytest.raises(RuntimeError):
            m.verify_arguments(good[:3]+['-gpu host']+good[5:],'No5_Lumina_API29')
    finally:
        child.terminate();child.wait(timeout=5)


def test_abi_field_offsets_match_sdk_rusage_v0():
    assert macos_memory.RUsageV0.phys_footprint.offset==72
    assert macos_memory.RUsageV0.proc_start_abstime.offset==80
    assert macos_memory.RUsageV0.proc_exit_abstime.offset==88


@pytest.mark.skipif(sys.platform!='darwin',reason='real macOS rusage lifecycle')
def test_exited_unreaped_child_is_not_a_running_process():
    child=subprocess.Popen([sys.executable,'-c','pass'])
    try:
        deadline=time.monotonic()+5
        while True:
            try:macos_memory.process_memory(child.pid)
            except ProcessLookupError:break
            if time.monotonic()>deadline:pytest.fail('exited child retained a live memory identity')
            time.sleep(.01)
        signals=[]
        with pytest.raises(ProcessLookupError):
            m.terminate_verified(child.pid,{},send_signal=lambda *args:signals.append(args))
        assert signals==[]
    finally:child.wait(timeout=5)


@pytest.mark.parametrize('fault',['none','stale','replaced','software','no_gpu','large_physical_charge','unguarded'])
def test_acceptance_requires_current_guard_real_identity_gpu_and_full_footprint(tmp_path,monkeypatch,fault):
    spec=importlib.util.spec_from_file_location('host_memory_verifier',TOOLS/'verify_permanent_chain.py')
    v=importlib.util.module_from_spec(spec);spec.loader.exec_module(v)
    identity={'pid':42,'start_abstime':1,'executable_uuid':'a'}
    actual=dict(identity,phys_footprint_bytes=3*1024**3,resident_bytes=500*1024**2)
    guard={'state':'RUNNING','pid':42,'process_identity':identity,'automatic_restart':False,
           'maximum_footprint_bytes':6*1024**3,'monotonic_s':time.clock_gettime(time.CLOCK_MONOTONIC),
           'graphics':{'vendor':'Apple','renderer':'Apple M4 Pro','version':'Metal'}}
    if fault=='stale':guard['monotonic_s']-=11
    if fault=='replaced':actual['start_abstime']=2
    if fault=='software':guard['graphics']['renderer']='SwiftShader'
    if fault=='no_gpu':guard['graphics']={}
    if fault=='large_physical_charge':actual['phys_footprint_bytes']=17*1024**3
    if fault=='unguarded':guard['state']='STOPPED'
    p=tmp_path/'guard.json';p.write_text(json.dumps(guard))
    monkeypatch.setattr(macos_memory,'process_memory',lambda _:actual)
    config={'emulator_host_identity':identity,'emulator_guardian_state':str(p)}
    if fault=='none':
        value=v.emulator_host_memory(config)
        assert value['emulator_phys_footprint_kib']==3*1024**2
    else:
        with pytest.raises(RuntimeError):v.emulator_host_memory(config)


@pytest.mark.parametrize('declared', [True, False])
def test_console_owner_checks_declared_supervisor_and_never_falls_back_on_its_failure(tmp_path, monkeypatch, declared):
    spec = importlib.util.spec_from_file_location('console_memory_verifier', TOOLS/'verify_permanent_chain.py')
    v = importlib.util.module_from_spec(spec); spec.loader.exec_module(v)
    identity = {'pid': 42, 'start_abstime': 1, 'executable_uuid': 'a'}
    actual = dict(identity, phys_footprint_bytes=3*1024**3, resident_bytes=500*1024**2)
    good = {'state': 'RUNNING', 'pid': 42, 'process_identity': identity,
            'automatic_restart': False, 'maximum_footprint_bytes': 6*1024**3,
            'monotonic_s': time.clock_gettime(time.CLOCK_MONOTONIC),
            'graphics': {'vendor': 'Apple', 'renderer': 'Apple M4 Pro', 'version': 'Metal'}}
    default = tmp_path/'emulator-state.json'; default.write_text(json.dumps(good))
    scoped = tmp_path/'scoped-guardian.json'; scoped.write_text(json.dumps(good))
    config = {'runtime_root': str(tmp_path)}
    if declared: config['emulator_guardian_state'] = str(scoped)
    monkeypatch.setattr(macos_memory, 'process_memory', lambda pid: actual)
    v.bind_console_emulator_owner(config, 42)
    assert v.emulator_host_memory(config)['emulator_guardian_verified']
    selected = scoped if declared else default
    selected.write_text(json.dumps(dict(good, state='STOPPED')))
    with pytest.raises(RuntimeError, match='supervisor is not current'):
        v.emulator_host_memory(config)
