"""Source-package import never compiles, loads or starts the own C component."""
import json
import subprocess
import sys


def test_host_sources_are_resources_without_native_load_or_process_start(tmp_path):
    # Install the guards before importing product code. The same test is run
    # against a separate wheel installation, not just the source checkout.
    script = r'''
import json, sys
blocked = {'subprocess.Popen', 'os.system', 'os.exec', 'os.fork',
           'os.posix_spawn', 'ctypes.dlopen'}
attempted = []
def guard(event, args):
    if event in blocked:
        attempted.append(event)
        raise AssertionError('Automatic native/process action: ' + event)
sys.addaudithook(guard)
import no_chart_converter
import no_chart_converter.host_runtime
from importlib.resources import files
base = files('no_chart_converter').joinpath('host_runtime')
sources = {name: base.joinpath(name).read_text(encoding='utf8')
           for name in ('no5_mutex.c', 'no5_mutex.h', 'README.txt',
                        'cray_mutex.cpp', 'cray_mutex.h')}
assert all(sources.values())
assert attempted == []
# Prove the audit guard is live without invoking a compiler, loader or process.
for event in ('subprocess.Popen', 'ctypes.dlopen'):
    try:
        sys.audit(event, 'INVENTED_GUARD_CONTROL')
    except AssertionError:
        pass
    else:
        raise AssertionError('Audit guard inactive')
print(json.dumps({'resources': sorted(sources), 'automaticActions': 0,
                  'guardControls': attempted}))
'''
    result = subprocess.run([sys.executable,'-B','-c',script],cwd=tmp_path,
                            capture_output=True,text=True,timeout=10)
    assert result.returncode == 0, result.stdout + result.stderr
    value = json.loads(result.stdout)
    assert value['resources'] == ['README.txt','cray_mutex.cpp','cray_mutex.h',
                                 'no5_mutex.c','no5_mutex.h']
    assert value['automaticActions'] == 0
    assert value['guardControls'] == ['subprocess.Popen','ctypes.dlopen']
