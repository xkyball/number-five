#!/usr/bin/env python3
"""Add password/CA support to the current proxy without changing the audio gateway.

Default: read-only plan. Apply creates an empty verifier, never an operator
password. Root-private backups retain all credential-bearing proxy material.
"""
import argparse
import datetime
import hashlib
import json
import os
from pathlib import Path
import pwd
import re
import shutil
import subprocess
import sys
import time
import urllib.request

PROJECT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(PROJECT / 'src'))
try:
    from no5_pi_gateway.password_auth import initial_record, save_record
except ModuleNotFoundError:
    sys.path.insert(0, str(Path(__file__).resolve().parent))
    from password_auth import initial_record, save_record


def run(*args):
    result = subprocess.run(args, capture_output=True, text=True)
    if result.returncode:
        diagnostic = re.sub(r'[A-Za-z0-9_-]{32,}', '[redacted]', result.stderr.strip())
        raise RuntimeError('Command failed: ' + args[0] + ': ' + diagnostic[:2000])
    return result.stdout.strip()


def prepare_site(site):
    # Read the actual currently active site, not a newer media release template.
    assert '8790' not in site and 'no5-password-locations.conf' not in site, 'Already extended'
    assert site.count('ssl_certificate /etc/no5-gateway-proxy/tls/server.crt;') == 2
    site = site.replace('ssl_certificate /etc/no5-gateway-proxy/tls/server.crt;',
                        'ssl_certificate /etc/no5-gateway-proxy/tls/server-with-ca.crt;')
    marker = 'server_name '
    lines = site.splitlines(keepends=True)
    inserted = 0
    for i, line in enumerate(lines):
        if line.strip().startswith(marker):
            lines[i] += '    include /etc/no5-gateway-proxy/no5-password-locations.conf;\n'
            inserted += 1
    assert inserted == 2
    site = ''.join(lines)
    # Only the :9443 API location has this check; simulator lives in an include.
    old = 'if ($no5_gateway_credential_present = 0) { return 401; }'
    assert site.count(old) == 1
    site = site.replace(old, 'error_page 418 = @no5_lumina_token;\n'
                        '        if ($no5_lumina_bearer_candidate = 1) { return 418; }\n'
                        '        auth_request /__no5_gateway_auth;\n'
                        '        include /etc/no5-gateway-proxy/nginx-password-upstream-key.conf;\n'
                        '        proxy_set_header X-No5-Gateway-Password "";')
    token_location = '''    # The candidate map recognizes syntax only. The loopback gateway validates
    # the signature, registry, expiry, origin and endpoint scope.
    location @no5_lumina_token {
        proxy_pass http://127.0.0.1:8789;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header Authorization $http_authorization;
        proxy_set_header X-No5-Gateway-Key "";
        proxy_set_header X-No5-Gateway-Password "";
        proxy_set_header X-Forwarded-Proto https;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection $connection_upgrade;
        proxy_buffering off;
        proxy_read_timeout 3600s;
        proxy_send_timeout 30s;
    }

'''
    marker='\n}\n\n# Existing CamillaGUI'
    assert site.count(marker)==1
    site=site.replace(marker,'\n'+token_location+'}\n\n# Existing CamillaGUI')
    # Optional browser password session: proof in a header, original key in a
    # root-private body include sent solely to the unchanged loopback GUI login.
    marker = '    location = /camilladsp/logout {'
    assert marker in site
    password_session = '''    location = /camilladsp/password-session {
        limit_except POST { deny all; }
        if ($no5_camillagui_unsafe_origin_ok = 0) { return 403; }
        auth_request /__no5_gateway_auth;
        proxy_pass http://127.0.0.1:8789/internal/camillagui/login;
        include /etc/no5-gateway-proxy/nginx-password-camilla-body.conf;
        proxy_set_header Content-Type application/json;
        proxy_set_header Origin $http_origin;
        proxy_set_header X-No5-Gateway-Key "";
        proxy_set_header X-No5-Gateway-Password "";
        proxy_set_header Cookie "";
        proxy_buffering off;
    }

'''
    return site.replace(marker, password_session + marker)


def simulator_password_js(js, kdf):
    old = "headers.set('X-No5-Gateway-Key',token)"
    new = "headers.set('X-No5-Gateway-Password',token)"
    if old in js:
        assert js.count(old) == 1 and new not in js
        js = kdf + '\n' + js.replace(old, new)
    else:
        assert js.startswith(kdf) and js.count(new) == 1, 'Unexpected password browser script'
    # Reuse the in-memory derivation on refresh; the input is cleared at login.
    # An explicitly entered new credential replaces it without trimming passwords.
    expression = re.compile(r"async function load\(\)\{[^\n]*?render\(await request\('/simulator/api'\)\)\}")
    js, count = expression.subn("async function load(){const value=$('token').value;"
        "if(value){token=await no5PasswordPrehash(value);$('token').value='';}"
        "if(!token)throw Error('Gateway-Key oder Passwort fehlt.');"
        "render(await request('/simulator/api'))}", js)
    assert count == 1, 'Unexpected simulator login function'
    return js


def camilla_password_html(html):
    assert html.count('<form id="login"') == 1
    html = html.replace('Mit dem vorhandenen No5-Gateway-Schlüssel anmelden.', 'Mit Gateway-Schlüssel oder Passwort anmelden.')
    html = html.replace('<label for="gateway-key">Gateway-Schlüssel</label>',
        '<label for="gateway-key">Gateway-Key oder Passwort</label>')
    html, count = re.subn(r'(<input\b[^>]*\bid="gateway-key"[^>]*)(>)',
        lambda m: re.sub(r'\s+minlength="[0-9]+"', '', m[1]) + m[2], html)
    assert count == 1, 'Unexpected Camilla credential input'
    return html


def browser_changes(lab=False):
    root = Path('/usr/local/share/no5-gateway')
    simulator_root = Path('/usr/local/share/no5-lab') if lab else root
    kdf = (PROJECT / 'deploy/nginx/password-kdf.js').read_text()
    html_path = simulator_root / 'simulator/index.html'
    html = html_path.read_text()
    assert html.count('id="token"') == 1 and 'id="auth-mode"' not in html
    html = html.replace('Gateway-Key <input', 'Gateway-Key oder Passwort <input')
    html = html.replace('Gateway-Schlüssel <input', 'Gateway-Key oder Passwort <input')
    js_path = simulator_root / 'simulator/app.js'
    js = js_path.read_text()
    js = simulator_password_js(js, kdf)
    gui_html_path = root / 'camillagui-login.html'
    gui_html = camilla_password_html(gui_html_path.read_text())
    gui_js = kdf + '''
(() => {
  "use strict";
  const form=document.getElementById("login"), input=document.getElementById("gateway-key"), status=document.getElementById("status");
  let busy=false;
  form.addEventListener("submit", async event => {
    event.preventDefault(); if(busy || !input.value)return; busy=true;
    let value=input.value; input.value=""; status.textContent="Anmeldung wird geprüft …";
    try {
      const headers={"Content-Type":"application/json"};
      headers["X-No5-Gateway-Password"]=await no5PasswordPrehash(value);
      const response=await fetch("/camilladsp/password-session", {
        method:"POST", credentials:"same-origin", redirect:"error", headers,
        body:"{}"});
      if(!response.ok)throw Error("Anmeldung abgelehnt.");
      window.location.replace("/camilladsp/");
    } catch (_) {status.textContent="Anmeldung fehlgeschlagen. Bitte Zugang prüfen.";}
    finally {value="";busy=false;}
  });
})();
'''
    return {str(html_path): (html.encode(), 0o644), str(js_path): (js.encode(), 0o644),
            str(gui_html_path): (gui_html.encode(), 0o644), str(root / 'camillagui-login.js'): (gui_js.encode(), 0o644)}


def atomic(path, data, mode, uid=0, gid=0):
    path = Path(path); path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(path.name + '.password-staged')
    with temporary.open('wb') as stream:
        stream.write(data); stream.flush(); os.fsync(stream.fileno())
    os.chmod(temporary, mode); os.chown(temporary, uid, gid); os.replace(temporary, path)


def lab_chain_refresh(source):
    marker = '        certificate_changed = hostname_certificate()\n'
    assert source.count(marker) == 1 and "chain = TLS / 'server-with-ca.crt'" not in source
    return source.replace(marker, marker + '''        # Password/CA bootstrap supplies the root alongside the leaf certificate.
        chain = TLS / 'server-with-ca.crt'
        if chain.exists():
            desired_chain = (TLS / 'server.crt').read_text().rstrip() + '\\n' + (TLS / 'ca.crt').read_text()
            if chain.read_text() != desired_chain:
                write(chain, desired_chain)
                certificate_changed = True
''')


def protected_state():
    names = ('no5-pi-gateway', 'camilladsp', 'camillagui', 'no5-spotify-connect', 'no5-sonard', 'no5-sonard-m0', 'signalk', 'avahi-daemon')
    states = {n: run('systemctl', 'show', n, '-p', 'MainPID', '-p', 'NRestarts', '-p', 'ActiveState',
                     '-p', 'ExecMainStartTimestampMonotonic') for n in names}
    paths = [Path('/etc/no5-gateway-proxy/tls') / n for n in ('ca.crt', 'ca.key', 'server.crt', 'server.key')]
    paths += [Path('/etc/nginx/conf.d/no5-gateway-auth.conf')]
    paths += list(Path('/etc/camilladsp').glob('*.yml'))
    paths += [p for directory in ('/etc/no5-pi-gateway', '/etc/no5-lab')
              for p in (Path(directory) / 'config.toml', Path(directory) / 'environment') if p.exists()]
    return {'units':states, 'sha256':{str(p):hashlib.sha256(p.read_bytes()).hexdigest() for p in paths if p.is_file()}}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--apply', action='store_true')
    parser.add_argument('--rollback', type=Path)
    parser.add_argument('--lab', action='store_true')
    args = parser.parse_args()
    assert os.geteuid() == 0
    host = run('hostname')
    if args.lab:
        assert host == 'no5-marineos-lab' and run('findmnt', '-no', 'SOURCE', '/') == '/dev/vda2'
    else:
        assert host == 'lysmarine' and 'Raspberry Pi 5' in Path('/proc/device-tree/model').read_text()
    assert run('systemctl', 'is-active', 'nginx') == 'active'
    nginx_check = ('/usr/sbin/nginx', '-t', '-c', '/etc/no5-lab/nginx.conf') if args.lab else ('/usr/sbin/nginx', '-t')
    if args.rollback:
        receipt = json.loads((args.rollback / 'receipt.json').read_text())
        assert receipt['hostname'] == host
        for item in receipt['files']:
            assert hashlib.sha256(Path(item['path']).read_bytes()).hexdigest() == item['afterSha256']
        run('systemctl', 'disable', '--now', 'no5-password-auth.service')
        for item in reversed(receipt['files']):
            path = Path(item['path'])
            assert hashlib.sha256(path.read_bytes()).hexdigest() == item['afterSha256']
            if item['before'] is None: path.unlink()
            else: atomic(path, (args.rollback / item['before']).read_bytes(), item['mode'], item['uid'], item['gid'])
        run(*nginx_check); run('systemctl', 'reload', 'nginx'); run('systemctl', 'daemon-reload')
        print(json.dumps({'rolled_back': True, 'backup': str(args.rollback)})); return
    site_path = Path('/etc/nginx/sites-available/no5-gateway')
    site = prepare_site(site_path.read_text())
    simulator_path = Path('/etc/no5-gateway-proxy/nginx-simulator-locations.conf')
    if not simulator_path.exists():
        # Lab stores its simulator locations in a different include, identify exactly.
        candidates = [p for p in Path('/etc/no5-lab').glob('*simulator*.conf')
                      if 'location = /simulator/api' in p.read_text()]
        assert len(candidates) == 1
        simulator_path = candidates[0]
    simulation = simulator_path.read_text()
    old = 'if ($no5_gateway_authorized = 0) { return 401; }'
    assert simulation.count(old) == 1
    simulation = simulation.replace(old, 'auth_request /__no5_gateway_auth;\n    proxy_set_header X-No5-Gateway-Password "";')
    sys.path.insert(0, '/opt/no5-pi-gateway/current/src' if not args.lab else '/opt/no5-lab/gateway')
    # Imported from the reviewable staged package, which has the same strict map parser.
    from no5_pi_gateway.nginx_auth_map import extract
    key = extract(Path('/etc/nginx/conf.d/no5-gateway-auth.conf').read_bytes()).decode('ascii')
    assert re.fullmatch('[A-Za-z0-9_-]{16,512}', key)
    ca = Path('/etc/no5-gateway-proxy/tls/ca.crt').read_bytes()
    server = Path('/etc/no5-gateway-proxy/tls/server.crt').read_bytes()
    assert b'PRIVATE KEY' not in ca + server
    changes = {
        str(site_path): (site.encode(), 0o644),
        str(simulator_path): (simulation.encode(), 0o644),
        '/usr/local/libexec/no5-password-auth.py': ((PROJECT / 'src/no5_pi_gateway/password_auth.py').read_bytes(), 0o755),
        '/etc/systemd/system/no5-password-auth.service': ((PROJECT / 'deploy/systemd/no5-password-auth.service').read_bytes(), 0o644),
        '/etc/no5-gateway-proxy/no5-password-locations.conf': ((PROJECT / 'deploy/nginx/no5-password-locations.conf').read_bytes(), 0o644),
        '/etc/nginx/conf.d/no5-password-maps.conf': ((PROJECT / 'deploy/nginx/no5-password-maps.conf').read_text().replace('lysmarine.local', host + '.local').encode(), 0o644),
        '/etc/no5-gateway-proxy/nginx-password-upstream-key.conf': (('proxy_set_header X-No5-Gateway-Key "' + key + '";\n').encode(), 0o600),
        '/etc/no5-gateway-proxy/nginx-password-camilla-body.conf': (('proxy_set_body \'{"gatewayKey":"' + key + '"}\';\n').encode(), 0o600),
        '/etc/no5-gateway-proxy/tls/server-with-ca.crt': (server.rstrip() + b'\n' + ca, 0o644),
        '/usr/local/share/no5-gateway/ca.crt': (ca, 0o644),
    }
    changes.update(browser_changes(args.lab))
    if args.lab:
        main_path = Path('/etc/no5-lab/nginx.conf')
        main_config = main_path.read_text()
        assert 'no5-password-maps.conf' not in main_config
        marker = 'include /etc/nginx/sites-available/no5-gateway;'
        assert main_config.count(marker) == 1
        # nginx initializes map hash defaults when parsing the first map.
        # Keep the existing map tuning ahead of all new map declarations.
        main_config = main_config.replace(marker, 'include /etc/nginx/conf.d/no5-password-maps.conf;\n    ' + marker)
        changes[str(main_path)] = (main_config.encode(), 0o644)
        refresh_path = Path('/opt/no5-lab/bin/refresh-lab-network.py')
        changes[str(refresh_path)] = (lab_chain_refresh(refresh_path.read_text()).encode(), 0o755)
    print(json.dumps({'hostname': host, 'planned_files': list(changes), 'gateway_release_change': False,
                      'audio_restart': False, 'operator_password_set': False, 'apply': args.apply}))
    if not args.apply: return
    backup = Path('/var/backups/no5-password-proxy-' + datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%SZ'))
    backup.mkdir(mode=0o700)
    receipt = {'hostname': host, 'files': [], 'preserved': protected_state()}
    for index, (path, (data, mode)) in enumerate(changes.items()):
        target = Path(path); before = None; uid = gid = 0; before_mode = mode
        if target.exists():
            stat = target.stat(); uid, gid, before_mode = stat.st_uid, stat.st_gid, stat.st_mode & 0o777
            before = str(index) + '.before'; (backup / before).write_bytes(target.read_bytes()); (backup / before).chmod(0o600)
        receipt['files'].append({'path': path, 'before': before, 'mode': before_mode, 'uid': uid, 'gid': gid,
                                 'afterSha256': hashlib.sha256(data).hexdigest()})
    (backup / 'receipt.json').write_text(json.dumps(receipt, indent=2)); (backup / 'receipt.json').chmod(0o600)
    try:
        try: auth_user = pwd.getpwnam('no5auth')
        except KeyError:
            run('useradd', '--system', '--home-dir', '/nonexistent', '--no-create-home', '--shell', '/usr/sbin/nologin', 'no5auth')
            auth_user = pwd.getpwnam('no5auth')
        directory = Path('/var/lib/no5-password-auth'); directory.mkdir(exist_ok=True); directory.chmod(0o700)
        os.chown(directory, auth_user.pw_uid, auth_user.pw_gid)
        record = directory / 'record.json'
        if not record.exists():
            save_record(record, initial_record()); os.chown(record, auth_user.pw_uid, auth_user.pw_gid)
        else:
            assert json.loads(record.read_text())['verifier'] is None, 'Existing operator password must never be replaced'
        secret_directory = Path('/etc/no5-password-auth'); secret_directory.mkdir(exist_ok=True); secret_directory.chmod(0o750)
        os.chown(secret_directory, 0, auth_user.pw_gid)
        client_salt = json.loads(record.read_text())['clientSalt']
        prehash = hashlib.pbkdf2_hmac('sha256', key.encode(), bytes.fromhex(client_salt), 600000)
        key_verifiers = {'rawSha256': hashlib.sha256(key.encode()).hexdigest(),
                         'prehashSha256': hashlib.sha256(prehash).hexdigest()}
        atomic(secret_directory / 'key.json', (json.dumps(key_verifiers) + '\n').encode(), 0o640, 0, auth_user.pw_gid)
        for path, (data, mode) in changes.items(): atomic(path, data, mode)
        run(*nginx_check)
        run('systemctl', 'daemon-reload'); run('systemctl', 'enable', '--now', 'no5-password-auth.service')
        assert run('systemctl', 'is-active', 'no5-password-auth.service') == 'active'
        for attempt in range(60):
            try:
                with urllib.request.urlopen('http://127.0.0.1:8790/parameters', timeout=.5) as response:
                    assert json.load(response)['registered'] is False
                break
            except OSError:
                if attempt == 59: raise
                time.sleep(.05)
        run('systemctl', 'reload', 'nginx')
        assert protected_state() == receipt['preserved'], 'Protected daemon or audio/credential file changed'
    except Exception:
        subprocess.run(['systemctl', 'disable', '--now', 'no5-password-auth.service'], capture_output=True)
        for item in reversed(receipt['files']):
            target = Path(item['path'])
            if item['before'] is None: target.unlink(missing_ok=True)
            else: atomic(target, (backup / item['before']).read_bytes(), item['mode'], item['uid'], item['gid'])
        subprocess.run(nginx_check, capture_output=True)
        subprocess.run(['systemctl', 'reload', 'nginx'], capture_output=True)
        subprocess.run(['systemctl', 'daemon-reload'], capture_output=True)
        raise
    shutil.copy2(__file__, backup / 'enable_password_proxy.py')
    shutil.copy2(PROJECT / 'src/no5_pi_gateway/password_auth.py', backup / 'password_auth.py')
    print(json.dumps({'applied': True, 'backup': str(backup), 'changed_files': len(changes), 'operator_password_set': False}))


if __name__ == '__main__': main()
