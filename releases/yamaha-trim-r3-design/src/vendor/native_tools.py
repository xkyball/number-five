"""Shared, serialized CLI entry point for native CAD verification.

macOS Fritzing's signed bundle contains only Cocoa. Its batch export service
can run without foreground activation; this is not a true offscreen QPA host.
Never silently substitute an interactive launch for a failed batch export.
"""
from contextlib import contextmanager
from pathlib import Path
import fcntl
import json
import os
import subprocess
import sys
import time
import uuid


class NativeToolError(RuntimeError):
    pass


def state_root():
    root = Path(os.environ.get('IOT_NATIVE_TOOLS_STATE', str(
        Path.home() / 'Library/Application Support/IoT-Native-Tools')))
    root.mkdir(parents=True, exist_ok=True, mode=0o700)
    return root


def policy(args, inherited=None):
    """Validate the operation, then enforce its noninteractive environment."""
    args = list(map(str, args))
    if not args:
        raise NativeToolError('No command supplied')
    name = Path(args[0]).name.lower()
    env = dict(os.environ if inherited is None else inherited)
    probe = bool({'--help', '-help', '-h', '--version', '-version', '-v'} & set(args[1:]))
    if name == 'fritzing':
        if not probe and not {'-svg', '--svg', '-gerber', '--gerber'} & set(args[1:]):
            raise NativeToolError('Fritzing requires a batch export operation; GUI/server launch refused')
        tool = 'fritzing'
        if sys.platform == 'darwin':
            env['QT_QPA_PLATFORM'] = 'cocoa'
            mode = 'background-cocoa-batch'
        else:
            env['QT_QPA_PLATFORM'] = 'offscreen'
            mode = 'offscreen'
        # Ignore experimental plugins from a caller; preserve the signed bundle.
        env.pop('QT_QPA_PLATFORM_PLUGIN_PATH', None)
    elif name == 'openscad':
        if not probe and '-o' not in args[1:] and not any(a.startswith('--o=') for a in args[1:]):
            raise NativeToolError('OpenSCAD requires an output path; interactive launch refused')
        tool, mode = 'openscad', 'offscreen-cli'
        env['QT_QPA_PLATFORM'] = 'offscreen'
    elif name == 'ngspice':
        if not probe and not ({'-n', '-b'} <= set(args[1:])):
            raise NativeToolError('ngspice requires -n -b; interactive/init-file launch refused')
        tool, mode = 'ngspice', 'headless-batch'
        for key in ('OMP_NUM_THREADS', 'OPENBLAS_NUM_THREADS', 'MKL_NUM_THREADS'):
            env[key] = '1'
    elif name == 'rsvg-convert':
        if not probe and (not {'-o', '--output'} & set(args[1:]) or
                          Path(args[-1]).suffix.lower() != '.svg' or not Path(args[-1]).is_file()):
            raise NativeToolError('SVG rasterization requires an explicit output and local SVG input')
        tool, mode = 'svg-raster', 'offscreen-cli'
    elif name == 'kicad-cli':
        tool, mode = 'kicad-cli', 'cli'
    elif name in ('flash studio', 'orcaslicer', 'orca-slicer', 'prusa-slicer', 'prusaslicer'):
        if not probe and not any(a.startswith(('--slice', '--export-', '--info')) for a in args[1:]):
            raise NativeToolError('Slicer requires an explicit batch operation')
        tool, mode = 'slicer', 'cli'
    elif name == 'blender':
        if '-b' not in args[1:] and '--background' not in args[1:] and not probe:
            raise NativeToolError('Blender requires --background')
        tool, mode = 'blender', 'background'
    else:
        raise NativeToolError('No noninteractive policy for ' + name)
    env['QT_MAC_DISABLE_FOREGROUND_APPLICATION_TRANSFORM'] = '1'
    for key in ('OMP_NUM_THREADS', 'OPENBLAS_NUM_THREADS', 'MKL_NUM_THREADS'):
        env.setdefault(key, '2')
    return tool, mode, env


@contextmanager
def tool_lock(name, timeout=600):
    """All projects using this runner share one process slot per tool family."""
    root = state_root()
    with (root / (name + '.lock')).open('a+') as lock:
        deadline = time.monotonic() + timeout
        while True:
            try:
                fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
                break
            except BlockingIOError:
                if time.monotonic() >= deadline:
                    raise TimeoutError('Waiting for central ' + name + ' process slot')
                time.sleep(.1)
        try:
            yield
        finally:
            fcntl.flock(lock, fcntl.LOCK_UN)


def run(args, *, env=None, lock_timeout=600, **kwargs):
    """subprocess.run-compatible result; no shell, app activation or GUI retry."""
    args = list(map(str, args))
    if kwargs.pop('shell', False):
        raise NativeToolError('Shell execution is not supported')
    tool, mode, effective = policy(args, env)
    kwargs['timeout'] = kwargs.get('timeout') or 300
    root = state_root() / 'runs'
    root.mkdir(exist_ok=True)
    record = dict(tool=tool, mode=mode, command=args, started_unix=time.time(),
                  automatic_GUI_fallback=False, foreground_transform_disabled=True,
                  true_offscreen=mode in ('offscreen', 'offscreen-cli'))
    try:
        with tool_lock(tool, lock_timeout):
            record['acquired_unix'] = time.time()
            result = subprocess.run(args, env=effective, **kwargs)
            record['returncode'] = result.returncode
            return result
    except BaseException as ex:
        record['error'] = type(ex).__name__ + ': ' + str(ex)
        raise
    finally:
        record['finished_unix'] = time.time()
        (root / (uuid.uuid4().hex + '.json')).write_text(json.dumps(record, indent=2) + '\n')


if __name__ == '__main__':
    args = sys.argv[1:]
    if args[:1] == ['--']:
        args = args[1:]
    try:
        raise SystemExit(run(args).returncode)
    except (NativeToolError, TimeoutError) as ex:
        raise SystemExit(str(ex))
