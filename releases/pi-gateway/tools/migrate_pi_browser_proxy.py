#!/usr/bin/env python3
"""Reviewed physical-Pi browser migration. Default is a read-only preflight.

Apply only this proxy/configuration transaction to the pinned audio release.
All credential reads, generated secrets and recovery copies stay on the Pi.
"""
import argparse
import datetime
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import subprocess
import sys
import time
import tomllib
import urllib.request

from refresh_pi_browser_network import atomic_write, command, listener_config, selected_addresses

PROJECT = Path(__file__).resolve().parents[1]
PROXY = Path('/etc/no5-gateway-proxy')
GATEWAY = Path('/etc/no5-pi-gateway')
PINNED_RELEASE = Path('/opt/no5-pi-gateway/releases/0.1.0-cf6e792-audio')
RUNTIME = '/opt/no5-pi-gateway/releases/0.1.0-e88e3f0/.venv/bin/python'
UI_SHA256 = '186aafbd87983ed2dbb903f23a0558da8ba132a8bf288cbb2eae9cfffb0ddd25'
GUI_ORIGINAL = Path('/opt/camillagui_backend/_internal/config/camillagui.yml')
PRESERVE_UNITS = ('camilladsp', 'no5-spotify-connect', 'no5-sonard', 'no5-sonard-m0', 'signalk', 'avahi-daemon')


def browser_assets(source):
    css = re.search(r'<style>(.*?)</style>', source, re.S)
    js = re.search(r'<script>(.*?)</script>', source, re.S)
    assert css and js and source.count('<style>') == source.count('<script>') == 1
    html = source.replace(css[0], '<link rel="stylesheet" href="/simulator/app.css">')
    html = html.replace(js[0], '<script src="/simulator/app.js" defer></script>')
    for old, new in {
        'Nur Loopback + Bearer-Token.': 'TLS-Zugang mit demselben Gateway-Key wie HELM und Camilla.',
        '<h2>Lokaler Zugriff</h2>': '<h2>Simulator-Zugang</h2>',
        'Bearer-Token <input': 'Gateway-Key <input',
        '<title>No5 lokale Simulation</title>': '<title>No5 Pi · Simulator</title>',
    }.items():
        assert html.count(old) == 1
        html = html.replace(old, new)
    script = js[1]
    old = "headers.set('Authorization','Bearer '+token)"
    assert script.count(old) == 1 and script.count('/admin/v1/simulation') == 2
    script = script.replace(old, "headers.set('X-No5-Gateway-Key',token)")
    script = script.replace('/admin/v1/simulation', '/simulator/api')
    script = script.replace('Bearer-Token fehlt.', 'Gateway-Key fehlt.')
    script = script.replace("token=$('token').value;", "token=$('token').value.trim();")
    assert not any(x in script for x in ('localStorage', 'sessionStorage', 'Bearer '))
    return {'index.html': html, 'app.js': script, 'app.css': css[1]}


def set_table(text, table, values):
    """Surgical TOML changes with a parsed equality check for everything else."""
    before = tomllib.loads(text)
    pattern = r'(?ms)^\[' + re.escape(table) + r'\][^\n]*\n.*?(?=^\[|\Z)'
    match = re.search(pattern, text)
    block = match[0] if match else '[' + table + ']\n'
    for name, value in values.items():
        encoded = json.dumps(value) if not isinstance(value, bool) else str(value).lower()
        setting = name + ' = ' + encoded + '\n'
        field = r'(?m)^' + re.escape(name) + r'\s*=.*\n?'
        if re.search(field, block):
            block, count = re.subn(field, lambda _: setting, block)
            assert count == 1
        else:
            block += '\n' if not block.endswith('\n') else ''
            block += setting
    updated = text[:match.start()] + block + text[match.end():] if match else text.rstrip() + '\n\n' + block
    expected = dict(before)
    expected[table] = {**before.get(table, {}), **values}
    assert tomllib.loads(updated) == expected, 'Unrelated TOML change refused'
    return updated


def environment(text):
    values = {}
    for line in text.splitlines():
        if not line.strip() or line.lstrip().startswith('#'):
            continue
        key, value = line.split('=', 1)
        assert key not in values, 'Duplicate environment field refused'
        values[key] = value
    return values


def units(names):
    return {name: dict(line.split('=', 1) for line in command('systemctl', 'show', name,
        '-p', 'ActiveState', '-p', 'MainPID', '-p', 'ExecMainStartTimestampMonotonic', '-p', 'NRestarts').splitlines()) for name in names}


def dsp_state():
    code = '''import asyncio,hashlib,json,aiohttp
async def main():
 async with aiohttp.ClientSession() as c:
  async with c.ws_connect('ws://127.0.0.1:1234') as ws:
   async def query(name):
    await ws.send_json(name)
    reply=(await asyncio.wait_for(ws.receive_json(),3))[name]
    assert reply['result']=='Ok'
    return reply.get('value')
   config=await query('GetConfigJson')
   config=json.loads(config) if isinstance(config,str) else config
   print(json.dumps({'faders':await query('GetFaders'),'state':await query('GetState'),
    'configSha256':hashlib.sha256(json.dumps(config,sort_keys=True).encode()).hexdigest()}))
asyncio.run(main())'''
    return json.loads(command(RUNTIME, '-c', code))


def simulation_state(config, env):
    if not config.get('simulation', {}).get('enabled'):
        return None
    token = env.get(config['simulation'].get('token_env', 'NO5_GATEWAY_SIMULATION_TOKEN'))
    assert token, 'Enabled simulation must already have its own local token'
    request = urllib.request.Request('http://127.0.0.1:8789/admin/v1/simulation', headers={'Authorization': 'Bearer ' + token})
    with urllib.request.urlopen(request, timeout=6) as response:
        data = json.load(response)
    return {key: data[key] for key in ('settings', 'revision')}


def snapshot(path):
    assert not path.is_symlink(), 'Refuse replacement of an unexpected symlink'
    if not path.exists():
        return None
    info = path.stat()
    return {'data': path.read_bytes(), 'mode': info.st_mode & 0o777, 'uid': info.st_uid, 'gid': info.st_gid}


def restore(originals):
    for name, old in reversed(list(originals.items())):
        path = Path(name)
        if old is None:
            path.unlink(missing_ok=True)
        else:
            atomic_write(path, old['data'], old['mode'], old['uid'], old['gid'])


def preflight():
    assert os.geteuid() == 0
    assert Path('/etc/hostname').read_text().strip() == 'lysmarine'
    assert 'Raspberry Pi 5' in Path('/proc/device-tree/model').read_text()
    assert Path('/opt/no5-pi-gateway/current').resolve() == PINNED_RELEASE
    assert GUI_ORIGINAL.is_file()
    source = PINNED_RELEASE / 'src/no5_pi_gateway/simulation_ui.html'
    assert hashlib.sha256(source.read_bytes()).hexdigest() == UI_SHA256
    command('/usr/sbin/nginx', '-t', '-q')
    config_text = (GATEWAY / 'config.toml').read_text()
    config = tomllib.loads(config_text)
    assert config['gateway']['bind_host'] == '127.0.0.1'
    assert config['gateway']['port'] == 8789
    env_text = (GATEWAY / 'environment').read_text()
    env = environment(env_text)
    # Use the already installed strict key-map parser solely inside this Pi.
    sys.path.insert(0, str(PINNED_RELEASE / 'src'))
    from no5_pi_gateway.nginx_auth_map import extract
    key = extract(Path('/etc/nginx/conf.d/no5-gateway-auth.conf').read_bytes())
    assert hashlib.sha256(key).hexdigest() == env['NO5_GATEWAY_HEADER_KEY_SHA256']
    current_units = units((*PRESERVE_UNITS, 'nginx', 'no5-pi-gateway', 'camillagui'))
    for name in ('camilladsp', 'no5-spotify-connect', 'nginx', 'no5-pi-gateway'):
        assert current_units[name]['ActiveState'] == 'active', name + ' must be active'
    dsp = dsp_state()
    assert dsp['state'] == 'Running'
    # This proxy migration submits no DSP or playback command. Preserve the
    # operator's current mute/volume rather than impose the older audio baseline.
    devices = json.loads(command('/usr/sbin/ip', '-j', 'address', 'show'))
    macs = sorted(d['address'].lower() for d in devices if d['ifname'] in ('wlan0', 'eth0'))
    assert macs == ['88:a2:9e:f1:6d:cb', '88:a2:9e:f1:6d:cc'], 'Unexpected physical-Pi NIC identity'
    return {'config_text': config_text, 'config': config, 'env_text': env_text, 'env': env,
            'key': key, 'units': current_units, 'dsp': dsp, 'macs': macs, 'devices': devices,
            'simulation': simulation_state(config, env), 'source': source,
            'protected': {str(p): hashlib.sha256(p.read_bytes()).hexdigest() for p in (
                Path('/etc/nginx/conf.d/no5-gateway-auth.conf'), PROXY / 'tls/ca.crt',
                PROXY / 'tls/ca.key', PROXY / 'tls/server.crt', PROXY / 'tls/server.key',
                Path('/etc/camilladsp/preset_no5_front_stereo_fullrange.yml'))}}


def plan(before):
    config = before['config']
    updated = set_table(before['config_text'], 'camilla_gui', {
        'enabled': True, 'public_origin': 'https://lysmarine.local',
        'session_secret_env': 'NO5_CAMILLAGUI_SESSION_SECRET', 'cookie_name': 'no5_camillagui', 'session_max_age_s': 900})
    sim = config.get('simulation', {})
    values = {'enabled': True}
    if not sim.get('state_file'):
        values['state_file'] = '/var/lib/no5-pi-gateway/simulation-state.json'
    if not sim.get('token_env'):
        values['token_env'] = 'NO5_GATEWAY_SIMULATION_TOKEN'
    updated = set_table(updated, 'simulation', values)
    new_config = tomllib.loads(updated)
    env = dict(before['env'])
    for name in ('NO5_CAMILLAGUI_SESSION_SECRET', new_config['simulation']['token_env']):
        env.setdefault(name, secrets.token_urlsafe(48))
        assert re.fullmatch(r'[A-Za-z0-9_-]{32,}', env[name]), 'Unexpected private token format'
    env_text = before['env_text'].rstrip() + '\n'
    for name in sorted(set(env) - set(before['env'])):
        env_text += name + '=' + env[name] + '\n'
    assert all(environment(env_text)[name] == value for name, value in before['env'].items())
    config_meta = (GATEWAY / 'config.toml').stat()
    env_meta = (GATEWAY / 'environment').stat()
    changes = {
        GATEWAY / 'config.toml': (updated, config_meta.st_mode & 0o777, config_meta.st_uid, config_meta.st_gid),
        GATEWAY / 'environment': (env_text, env_meta.st_mode & 0o777, env_meta.st_uid, env_meta.st_gid),
        Path('/etc/nginx/sites-available/no5-gateway'): ((PROJECT / 'deploy/nginx/no5-gateway.conf').read_text(), 0o644, 0, 0),
        PROXY / 'browser-network.json': (json.dumps({'hostname': 'lysmarine.local', 'macs': before['macs']}) + '\n', 0o644, 0, 0),
        PROXY / 'nginx-browser-listeners.conf': (listener_config(selected_addresses(before['devices'], before['macs'])), 0o644, 0, 0),
        PROXY / 'nginx-simulator-locations.conf': ((PROJECT / 'deploy/nginx/no5-simulator-locations.conf').read_text(), 0o644, 0, 0),
        PROXY / 'nginx-simulator-upstream-auth.conf': ('proxy_set_header Authorization "Bearer ' + env[new_config['simulation']['token_env']] + '";\n', 0o600, 0, 0),
        Path('/usr/local/libexec/no5-refresh-browser-network.py'): ((PROJECT / 'tools/refresh_pi_browser_network.py').read_text(), 0o755, 0, 0),
        Path('/etc/NetworkManager/dispatcher.d/90-no5-browser-network'): ((PROJECT / 'deploy/no5-browser-network-dispatcher.sh').read_text(), 0o755, 0, 0),
        Path('/etc/systemd/system/nginx.service.d/no5-browser-network.conf'): ('[Unit]\nWants=no5-browser-network.service\nAfter=no5-browser-network.service\n', 0o644, 0, 0),
        Path('/etc/systemd/system/camillagui.service.d/no5-browser.conf'): ('[Service]\nExecStart=\nExecStart=/opt/camillagui_backend/camillagui_backend -c /etc/camillagui/no5.yml\n', 0o644, 0, 0),
    }
    for name in ('01-no5-camillagui-map.conf', 'no5-simulator-maps.conf'):
        changes[Path('/etc/nginx/conf.d') / name] = ((PROJECT / 'deploy/nginx' / name).read_text(), 0o644, 0, 0)
    for name in ('no5-browser-network.service', 'no5-browser-network.timer'):
        changes[Path('/etc/systemd/system') / name] = ((PROJECT / 'deploy/systemd' / name).read_text(), 0o644, 0, 0)
    for name in ('camillagui-login.html', 'camillagui-login.js', 'camillagui-login.css'):
        changes[Path('/usr/local/share/no5-gateway') / name] = ((PROJECT / 'deploy/nginx' / name).read_text(), 0o644, 0, 0)
    for name, value in browser_assets(before['source'].read_text()).items():
        changes[Path('/usr/local/share/no5-gateway/simulator') / name] = (value, 0o644, 0, 0)
    gui = GUI_ORIGINAL.read_text()
    gui, count = re.subn(r'(?m)^bind_address:.*$', 'bind_address: "127.0.0.1"', gui)
    assert count == 1 and re.search(r'(?m)^port:\s*5005\s*$', gui)
    assert re.search(r'(?m)^on_set_active_config:\s*null\s*$', gui)
    assert re.search(r'(?m)^on_get_active_config:\s*null\s*$', gui)
    changes[Path('/etc/camillagui/no5.yml')] = (gui, 0o644, 0, 0)
    return changes


def check_rollback(backup):
    receipt = json.loads((backup / 'receipt.json').read_text())
    for name, item in receipt['files'].items():
        path = Path(name)
        if path == PROXY / 'nginx-browser-listeners.conf':
            policy = json.loads((PROXY / 'browser-network.json').read_text())
            devices = json.loads(command('/usr/sbin/ip', '-j', 'address', 'show'))
            generated = listener_config(selected_addresses(devices, policy['macs'])).encode()
            assert path.read_bytes() == generated, 'Unexpected browser listener modification'
            continue
        assert path.is_file() and hashlib.sha256(path.read_bytes()).hexdigest() == item['after_sha256'], 'Later file change prevents stale rollback'
    originals = {name: ({**item['before'], 'data': (backup / item['backup_name']).read_bytes()} if item['before'] else None)
                 for name, item in receipt['files'].items()}
    return receipt, originals


def rollback(backup):
    receipt, originals = check_rollback(backup)
    command('systemctl', 'disable', '--now', 'no5-browser-network.timer')
    command('systemctl', 'stop', 'no5-browser-network.service')
    command('systemctl', 'disable', 'no5-browser-network.service')
    restore(originals)
    command('systemctl', 'daemon-reload')
    if receipt['gateway_restarted']:
        command('systemctl', 'restart', 'no5-pi-gateway')
    if receipt['camillagui_before_active']:
        command('systemctl', 'restart', 'camillagui')
    else:
        command('systemctl', 'stop', 'camillagui')
    command('/usr/sbin/nginx', '-t', '-q')
    command('/usr/sbin/nginx', '-s', 'reload')
    print(json.dumps({'rolled_back': True, 'audio_daemons_restarted': False}))


def apply(before, changes):
    originals = {}
    for path, (value, mode, uid, gid) in changes.items():
        old = snapshot(path)
        if old and old == {'data': value.encode(), 'mode': mode, 'uid': uid, 'gid': gid}:
            continue
        originals[str(path)] = old
    if not originals:
        print(json.dumps({'changed_files': 0, 'already_installed': True}))
        return
    assert not Path('/etc/systemd/system/no5-browser-network.timer').exists(), 'Use the existing migration receipt for updates'
    backup = Path('/var/backups') / ('no5-browser-proxy-' + datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%SZ'))
    backup.mkdir(mode=0o700)
    receipt = {'files': {}, 'gateway_restarted': False, 'camillagui_before_active': before['units']['camillagui']['ActiveState'] == 'active',
               'preserved_units': {n: before['units'][n] for n in PRESERVE_UNITS},
               'dsp_before': before['dsp'], 'simulation_before': before['simulation'],
               'protected_files': before['protected']}
    for index, (name, old) in enumerate(originals.items()):
        saved_name = str(index) + '.before'
        if old:
            atomic_write(backup / saved_name, old['data'], 0o600)
        value = changes[Path(name)][0].encode()
        receipt['files'][name] = {'before': {key: value for key, value in old.items() if key != 'data'} if old else None,
                                'backup_name': saved_name, 'after_sha256': hashlib.sha256(value).hexdigest()}
    for name in ('migrate_pi_browser_proxy.py', 'refresh_pi_browser_network.py'):
        atomic_write(backup / name, (PROJECT / 'tools' / name).read_bytes(), 0o700)
    gateway_changed = any(str(GATEWAY / name) in originals for name in ('config.toml', 'environment'))
    receipt['gateway_restarted'] = gateway_changed
    atomic_write(backup / 'receipt.json', json.dumps(receipt, indent=2), 0o600)
    try:
        for name in originals:
            atomic_write(Path(name), *changes[Path(name)])
        # Validate with the already approved runtime; no package installation.
        validate = 'import sys;sys.path.insert(0,"' + str(PINNED_RELEASE / 'src') + '");from no5_pi_gateway.configuration import load_config;load_config("/etc/no5-pi-gateway/config.toml")'
        command('/usr/sbin/runuser', '-u', 'no5gateway', '--', RUNTIME, '-c', validate)
        command('/usr/sbin/nginx', '-t', '-q')
        command('systemctl', 'daemon-reload')
        if gateway_changed:
            command('systemctl', 'restart', 'no5-pi-gateway')
        command('systemctl', 'restart' if receipt['camillagui_before_active'] else 'start', 'camillagui')
        command('/usr/sbin/nginx', '-s', 'reload')
        # Allow only normal service startup to settle; do not submit any writes.
        deadline = time.monotonic() + 8
        while True:
            try:
                current = units((*PRESERVE_UNITS, 'camillagui', 'no5-pi-gateway'))
                assert current['camillagui']['ActiveState'] == current['no5-pi-gateway']['ActiveState'] == 'active'
                assert all(current[n] == before['units'][n] for n in PRESERVE_UNITS)
                listeners = command('ss', '-H', '-lnt')
                gui_listeners = [line.split()[3] for line in listeners.splitlines() if line.split()[3].endswith(':5005')]
                assert gui_listeners == ['127.0.0.1:5005'], 'CamillaGUI must remain loopback-only'
                assert dsp_state() == before['dsp']
                assert all(hashlib.sha256(Path(p).read_bytes()).hexdigest() == digest
                           for p, digest in before['protected'].items())
                cfg = tomllib.loads((GATEWAY / 'config.toml').read_text())
                sim = simulation_state(cfg, environment((GATEWAY / 'environment').read_text()))
                if before['simulation'] is not None:
                    assert sim == before['simulation']
                break
            except (AssertionError, OSError):
                if time.monotonic() >= deadline:
                    raise RuntimeError('Service/state preservation readback failed')
                time.sleep(.2)
        command('systemctl', 'enable', '--now', 'no5-browser-network.timer')
        command('systemctl', 'enable', 'no5-browser-network.service')
        assert Path('/opt/no5-pi-gateway/current').resolve() == PINNED_RELEASE
    except BaseException:
        # Do not print exception bodies from private services/configuration.
        subprocess.run(['systemctl', 'disable', '--now', 'no5-browser-network.timer'], capture_output=True)
        subprocess.run(['systemctl', 'stop', 'no5-browser-network.service'], capture_output=True)
        subprocess.run(['systemctl', 'disable', 'no5-browser-network.service'], capture_output=True)
        restore(originals)
        command('systemctl', 'daemon-reload')
        if gateway_changed:
            command('systemctl', 'restart', 'no5-pi-gateway')
        command('systemctl', 'restart' if receipt['camillagui_before_active'] else 'stop', 'camillagui')
        command('/usr/sbin/nginx', '-t', '-q')
        command('/usr/sbin/nginx', '-s', 'reload')
        raise RuntimeError('Migration failed; original files/services restored. Private diagnostics suppressed') from None
    print(json.dumps({'installed': True, 'backup': str(backup), 'changed_files': len(originals),
                      'gateway_restarted': gateway_changed, 'camillagui_started': True,
                      'audio_daemons_restarted': False, 'simulation_selection_preserved': True,
                      'existing_gateway_key_and_ca_preserved': True}, indent=2))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--apply', action='store_true')
    parser.add_argument('--rollback', type=Path)
    parser.add_argument('--check-rollback', type=Path)
    args = parser.parse_args()
    assert os.geteuid() == 0
    assert Path('/etc/hostname').read_text().strip() == 'lysmarine'
    assert 'Raspberry Pi 5' in Path('/proc/device-tree/model').read_text()
    with Path('/run/no5-browser-network.lock').open('a') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        if args.rollback or args.check_rollback:
            assert not args.apply
            assert not (args.rollback and args.check_rollback)
            backup = args.rollback or args.check_rollback
            assert backup.resolve().parent == Path('/var/backups')
            if args.check_rollback:
                receipt, originals = check_rollback(backup)
                print(json.dumps({'rollback_check_passed': True, 'checked_files': len(originals), 'applied': False}))
            else:
                rollback(backup)
            return
        before = preflight()
        changes = plan(before)
        if args.apply:
            apply(before, changes)
        else:
            print(json.dumps({'preflight_passed': True, 'release': str(PINNED_RELEASE),
                              'simulation_configured': before['simulation'] is not None,
                              'services': before['units'], 'dsp': before['dsp'],
                              'planned_files': [str(path) for path in changes],
                              'gateway_restart_needed': any(changes[GATEWAY / name][0] != before[field]
                                  for name, field in (('config.toml', 'config_text'), ('environment', 'env_text')))}, indent=2))


if __name__ == '__main__':
    main()
