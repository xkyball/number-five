"""Small, source-bound ngspice batch runner; never opens a plot or app window."""
import argparse
import hashlib
import json
import math
import os
from pathlib import Path
import re
import shutil
import tempfile
import time

from native_tools import run


SCHEMA = 'iot.spice-plan/1'
COMPATIBILITY_MODES = ('lt', 'lta', 'ps', 'psa', 'ltps', 'ltpsa')


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def write(path, value):
    path.write_text(json.dumps(value, indent=2, sort_keys=True) + '\n')


def local(root, name):
    p = Path(name)
    if not name or p.is_absolute() or '..' in p.parts or p == Path('.'):
        raise ValueError('Expected a relative source path: ' + str(name))
    target = root / p
    if target.resolve() != target.absolute() or not target.is_file():
        raise ValueError('Missing file or symlink in source path: ' + name)
    return target


def number(value):
    if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value):
        raise ValueError('Expected a finite number')
    return value


def inputs(plan, root):
    files = {}
    entries = [{'path': plan['deck'], 'sha256': plan['deck_sha256']}]
    entries += plan.get('models', []) + plan.get('dependencies', [])
    for entry in entries:
        p = local(root, entry['path'])
        if sha(p) != entry['sha256']:
            raise ValueError('Source hash mismatch: ' + entry['path'])
        if entry['path'] in files:
            raise ValueError('Duplicate source binding: ' + entry['path'])
        files[entry['path']] = entry['sha256']
    visited = set()

    def scan(name):
        if name in visited:
            return
        visited.add(name)
        in_control = False
        for raw in local(root, name).read_text().splitlines()[1 if name == plan['deck'] else 0:]:
            line = raw.strip()
            if line.startswith('*#'):
                raise ValueError('Embedded script commands are not allowed')
            if not line or line.startswith('*'):
                continue
            if '`' in line or '$' in line:
                raise ValueError('Command expansion is not allowed')
            token = line.split()[0].lower()
            if token == '.control':
                in_control = True
                continue
            if token == '.endc':
                in_control = False
                continue
            if in_control:
                if token not in ('run', 'meas', 'measure', 'print', 'let', 'reset', 'set'):
                    raise ValueError('Unsupported control command: ' + token)
                if token == 'set' and not re.fullmatch(r'set\s+(num_threads\s*=\s*1|wr_singlescale|wr_vecnames|noaskquit)', line, re.I):
                    raise ValueError('Unsupported set command')
            if token in ('.include', '.inc', '.lib', '.incpslt'):
                match = re.match(r'^\.\w+\s+(?:"([^"]+)"|\x27([^\x27]+)\x27|(\S+))(.*)$', line)
                if not match:
                    raise ValueError('Malformed library/include')
                ref = next(x for x in match.groups()[:3] if x is not None)
                tail = match.group(4).strip()
                # One-word .lib SECTION is a section opener inside a bound model.
                if token == '.lib' and not tail and '/' not in ref and '.' not in ref and name != plan['deck']:
                    continue
                rel = Path(name).parent / ref
                if Path(ref).is_absolute() or '..' in rel.parts:
                    raise ValueError('Include escapes source directory: ' + ref)
                target = rel.as_posix()
                if target not in files:
                    raise ValueError('Unbound include/library dependency: ' + target)
                scan(target)
            if token in ('.hdl', '.osdi', '.load', '.spiceinit'):
                raise ValueError('External code loading is not allowed')
    scan(plan['deck'])
    model_paths = {m['path'] for m in plan.get('models', [])}
    for name in visited:
        if name == plan['deck']:
            continue
        if re.search(r'^\s*\.(?:model|subckt)\s', local(root, name).read_text(), re.M | re.I) and name not in model_paths:
            raise ValueError('Model definitions require an explicit model binding: ' + name)
    return files


def qualification(plan, root, files):
    status = plan['model_status']
    if status not in ('builtin', 'behavioral', 'qualified', 'missing'):
        raise ValueError('Unknown model_status')
    if status == 'missing':
        return 'OPEN', ['Required model is missing']
    reasons = []
    models = plan.get('models', [])
    if re.search(r'^\s*\.(?:model|subckt)\s', local(root, plan['deck']).read_text(), re.M | re.I):
        reasons.append('Inline model definitions lack a separately bound qualification')
    if status == 'builtin' and (models or plan.get('dependencies')):
        reasons.append('External dependencies cannot be declared builtin')
    if status == 'behavioral':
        reasons.append('Behavioral approximation is not model qualification')
    if status == 'qualified' and not models:
        reasons.append('Qualified status requires model bindings')
    for model in models:
        if model.get('status') != 'qualified':
            reasons.append('Model not qualified: ' + model['path'])
            continue
        q = model.get('qualification')
        if not q:
            reasons.append('Qualification evidence missing: ' + model['path'])
            continue
        p = local(root, q['path'])
        if sha(p) != q['sha256']:
            raise ValueError('Qualification hash mismatch: ' + q['path'])
        proof = json.loads(p.read_text())
        if (proof.get('schema') != 'iot.spice-model-qualification/1'
                or proof.get('model_sha256') != model['sha256']
                or proof.get('passed') is not True
                or not proof.get('checks')
                or any(x.get('passed') is not True for x in proof['checks'])):
            reasons.append('Qualification evidence incomplete: ' + model['path'])
        files[q['path']] = q['sha256']
    return ('OPEN' if reasons else 'PASS'), reasons


def execute(plan_path, output, simulator='/opt/homebrew/bin/ngspice'):
    output = Path(output).resolve()
    output.mkdir(parents=True, exist_ok=True)
    result = {'schema': 'iot.spice-result/1', 'status': 'FAIL', 'electrical_status': 'OPEN',
              'qualification_status': 'OPEN', 'physical_acceptance': False, 'cache_hit': False}
    try:
        plan_path = Path(plan_path).resolve()
        root = plan_path.parent
        plan = json.loads(plan_path.read_text())
        if plan['schema'] != SCHEMA:
            raise ValueError('Unsupported plan schema')
        mode = plan.get('compatibility_mode')
        if mode is not None and (type(mode) is not str or mode not in COMPATIBILITY_MODES):
            raise ValueError('Unsupported explicit ngspice compatibility_mode')
        timeout = number(plan.get('timeout_seconds', 30))
        if not 0 < timeout <= 120:
            raise ValueError('timeout_seconds must be >0 and <=120')
        measurements = plan['measurements']
        names = set()
        if not measurements:
            raise ValueError('At least one measurement is required')
        for m in measurements:
            if not re.fullmatch(r'[A-Za-z][A-Za-z0-9_]*', m['name']) or m['name'].lower() in names:
                raise ValueError('Invalid/duplicate measurement name')
            names.add(m['name'].lower())
            if number(m['min']) > number(m['max']) or not isinstance(m['unit'], str) or not m['unit']:
                raise ValueError('Invalid measurement bounds/unit')
        params = plan.get('parameters', {})
        for key, value in params.items():
            if not re.fullmatch(r'[A-Za-z][A-Za-z0-9_]*', key):
                raise ValueError('Invalid parameter name')
            number(value)
        if plan.get('model_status') == 'missing':
            deck_source = local(root, plan['deck'])
            if sha(deck_source) != plan['deck_sha256']:
                raise ValueError('Source hash mismatch: ' + plan['deck'])
            result.update(status='OPEN', reason='Required models missing; simulation not run')
            return result
        files = inputs(plan, root)
        qstatus, reasons = qualification(plan, root, files)
        if any((root / n).resolve() == output / 'result.json' for n in files):
            raise ValueError('Output would overwrite a bound source')
        result.update(qualification_status=qstatus, qualification_reasons=reasons)
        if plan['model_status'] == 'missing':
            result.update(status='OPEN', reason='Required models missing; simulation not run')
            return result
        simulator = str(Path(simulator).resolve(strict=True))
        binary_hash = sha(simulator)
        env = dict(os.environ, OMP_NUM_THREADS='1', OPENBLAS_NUM_THREADS='1', MKL_NUM_THREADS='1')
        version = run([simulator, '-n', '--version'], env=env, capture_output=True, text=True,
                      timeout=min(timeout, 10), lock_timeout=120)
        if version.returncode:
            raise ValueError('Simulator version probe failed')
        version_text = version.stdout.strip()
        key_data = {'plan': plan, 'files': files, 'simulator_sha256': binary_hash,
                    'simulator_version': version_text, 'runner_sha256': sha(__file__)}
        cache_key = hashlib.sha256(json.dumps(key_data, sort_keys=True).encode()).hexdigest()
        cache = output / 'cache' / cache_key
        result.update(cache_key=cache_key, input_hashes=files, simulator_sha256=binary_hash,
                      simulator_version=version_text)
        if cache.exists():
            seal = json.loads((cache / 'seal.json').read_text())
            if set(seal) != {'result.json', 'simulation.log'} or any(sha(cache / n) != h for n, h in seal.items()):
                raise ValueError('Corrupt cache: bound result/log mismatch')
            saved = json.loads((cache / 'result.json').read_text())
            if (saved.get('cache_key') != cache_key or saved.get('schema') != 'iot.spice-result/1'
                    or saved.get('input_hashes') != files or saved.get('simulator_sha256') != binary_hash
                    or saved.get('status') not in ('PASS', 'OPEN', 'FAIL')):
                raise ValueError('Corrupt cache key')
            saved['cache_hit'] = True
            result = saved
            return result
        with tempfile.TemporaryDirectory(prefix='spice-', dir=output) as temp:
            work = Path(temp)
            for name in files:
                dst = work / name
                dst.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(local(root, name), dst)
                if sha(dst) != files[name]:
                    raise ValueError('Source changed during preparation: ' + name)
            deck = work / plan['deck']
            if params:
                text = deck.read_text()
                at = list(re.finditer(r'^\s*\.end\s*$', text, re.M | re.I))
                if not at:
                    raise ValueError('Deck has no .end')
                pos = at[-1].start()
                text = text[:pos] + ''.join('.param %s=%.17g\n' % (k, v) for k, v in params.items()) + text[pos:]
                deck.write_text(text)
            log = work / 'simulation.log'
            started = time.monotonic()
            arguments = [simulator, '-n', '-b', '-D', 'num_threads=1']
            if mode is not None:
                # Native ngspice preprocessing; vendor model bytes stay bound.
                arguments += ['-D', 'ngbehavior=' + mode]
                result['compatibility_mode'] = mode
            completed = run(arguments + ['-o', str(log), str(deck)],
                            env=env, cwd=work, capture_output=True, text=True,
                            timeout=timeout, lock_timeout=120)
            result.update(wall_seconds=time.monotonic() - started, returncode=completed.returncode)
            if sha(simulator) != binary_hash:
                raise ValueError('Simulator changed during execution')
            text = log.read_text() if log.exists() else completed.stdout + completed.stderr
            readings = {}
            for name, raw in re.findall(r'^\s*([A-Za-z][A-Za-z0-9_]*)\s*=\s*([-+0-9.eE]+)', text, re.M):
                if name.lower() in readings:
                    raise ValueError('Ambiguous duplicate measurement: ' + name)
                readings[name.lower()] = float(raw)
            checks = []
            for m in measurements:
                value = readings.get(m['name'].lower())
                checks.append(dict(m, value=value, passed=value is not None and math.isfinite(value)
                                   and m['min'] <= value <= m['max']))
            failed_log = bool(re.search(r'(?im)^\s*(?:error\b|fatal\b)|simulation\(s\) aborted|doAnalyses:.*(?:failed|timestep)', text))
            electrical = 'PASS' if completed.returncode == 0 and not failed_log and all(c['passed'] for c in checks) else 'FAIL'
            result.update(measurements=checks, electrical_status=electrical,
                          status='FAIL' if electrical == 'FAIL' else qstatus)
            cache.mkdir(parents=True)
            (cache / 'simulation.log').write_text(text)
            result['log'] = str(cache / 'simulation.log')
            write(cache / 'result.json', result)
            write(cache / 'seal.json', {n: sha(cache / n) for n in ('result.json', 'simulation.log')})
    except Exception as ex:
        result.update(status='FAIL', error=type(ex).__name__ + ': ' + str(ex))
    finally:
        write(output / 'result.json', result)
    return result


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('plan')
    p.add_argument('--output', required=True)
    args = p.parse_args()
    result = execute(args.plan, args.output)
    print(json.dumps(result, indent=2))
    return {'PASS': 0, 'OPEN': 2, 'FAIL': 1}[result['status']]


if __name__ == '__main__':
    raise SystemExit(main())
