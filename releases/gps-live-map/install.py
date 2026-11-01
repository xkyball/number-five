#!/usr/bin/env python3
"""Pi-local installer. Preserves previous releases; never restarts other services."""
import argparse
import hashlib
import ipaddress
import json
import os
from pathlib import Path
import shutil
import socket
import subprocess
import time

ROOT = Path(__file__).resolve().parent


def run(*args, **kwargs):
    return subprocess.run(args, check=True, capture_output=True, **kwargs).stdout


def digest(root):
    return hashlib.sha256(b''.join(p.relative_to(root).as_posix().encode()+b'\0'+p.read_bytes()
                                  for p in sorted(root.rglob('*')) if p.is_file() and '__pycache__' not in p.parts and p.suffix != '.pyc')).hexdigest()


def install(interface):
    if os.geteuid() != 0 or socket.gethostname().split('.')[0] != 'lysmarine':
        raise ValueError('Run as root on the physical lysmarine Pi only')
    from server import lan_interface
    address, network = lan_interface(interface)
    base = Path('/opt/no5-gps-map')
    current = base / 'current'
    conf = Path('/etc/no5-gps-map')
    unit = Path('/etc/systemd/system/no5-gps-map.service')
    # Narrow update ownership: preserve unexpected pre-existing paths.
    if current.exists() and not current.is_symlink():
        raise ValueError('Unexpected current path')
    if unit.exists() and 'No5 GPS live source comparison' not in unit.read_text():
        raise ValueError('Foreign service retained')
    if not current.exists():
        with socket.socket() as probe:
            probe.bind((address, 8798))
    active = run('systemctl', 'show', 'no5-pi-gateway', '-p', 'ExecStart', '--value').decode()
    import re
    python = re.search(r'/opt/no5-pi-gateway/[A-Za-z0-9._-]+/venv/bin/python', active).group(0)
    if not python.startswith('/opt/no5-pi-gateway/') or not python.endswith('/venv/bin/python'):
        raise ValueError('Unknown installed gateway runtime')
    # Reuse the gateway strict parser, with bytes kept inside this root process.
    key = run(python, '-c', "from pathlib import Path; from no5_pi_gateway.nginx_auth_map import extract; import sys; sys.stdout.buffer.write(extract(Path('/etc/nginx/conf.d/no5-gateway-auth.conf').read_bytes()))",
              env={**os.environ, 'PYTHONPATH': str(Path(python).parents[2] / 'src'), 'PYTHONDONTWRITEBYTECODE':'1'})
    if not 16 <= len(key) <= 512:
        raise ValueError('No valid gateway credential')
    revision = digest(ROOT)
    release = base / 'releases' / revision[:16]
    base.mkdir(parents=True, exist_ok=True)
    base.chmod(0o755)
    release.parent.mkdir(exist_ok=True)
    if release.exists():
        if digest(release) != revision:
            raise ValueError('Release hash conflict')
    else:
        shutil.copytree(ROOT, release, ignore=shutil.ignore_patterns('__pycache__','*.pyc'))
        for p in release.rglob('*'):
            p.chmod(0o755 if p.is_dir() else 0o644)
        release.chmod(0o755)
    conf.mkdir(mode=0o700, exist_ok=True)
    conf.chmod(0o700)
    if (conf/'gateway-key').exists() and (conf/'gateway-key').read_bytes() != key:
        raise ValueError('Credential changed; review rotation before update')
    (conf/'gateway-key').write_bytes(key)
    (conf/'gateway-key').chmod(0o600)
    (conf/'settings').write_text('GPS_MAP_INTERFACE='+interface+'\n')
    (conf/'settings').chmod(0o600)
    prior = str(current.resolve()) if current.exists() else None
    previous_unit = unit.read_text() if unit.exists() else None
    # Save the scoped rollback record without coordinates or key values.
    receipt = {'revision': revision, 'previousRelease': prior, 'previousUnit': previous_unit,
               'interface': interface, 'port': 8798, 'installedAtEpochMs': int(time.time()*1000)}
    (conf/('install-'+str(receipt['installedAtEpochMs'])+'.json')).write_text(json.dumps(receipt,indent=2))
    link = base / 'next'
    if link.exists() or link.is_symlink():
        raise ValueError('Unexpected pending installation link')
    link.symlink_to(release)
    link.replace(current)
    unit.write_bytes((ROOT/'no5-gps-map.service').read_bytes())
    unit.chmod(0o644)
    try:
        run('systemctl','daemon-reload')
        run('systemctl','enable','no5-gps-map.service')
        run('systemctl','restart','no5-gps-map.service')
        # Public, read-only health check; no coordinates returned to the operator.
        from urllib.request import urlopen, build_opener, ProxyHandler
        opener = build_opener(ProxyHandler({}))
        for attempt in range(12):
            try:
                with opener.open('http://'+address+':8798/api/positions', timeout=2) as r:
                    data=json.load(r)
                if data.get('upstreamAvailable'):
                    break
            except OSError:
                pass
            time.sleep(1)
        else:
            raise ValueError('New service did not read GPS data')
    except Exception:
        run('systemctl','stop','no5-gps-map.service')
        if prior:
            link.symlink_to(prior); link.replace(current)
            unit.write_text(previous_unit)
            run('systemctl','daemon-reload'); run('systemctl','start','no5-gps-map.service')
        else:
            run('systemctl','disable','no5-gps-map.service')
            current.unlink(); unit.unlink(); run('systemctl','daemon-reload')
        raise
    print(json.dumps({'status':'PASS','revision':revision,'url':'http://lysmarine.local:8798/',
                      'sources':len(data['sources']), 'enabled':True}))


if __name__=='__main__':
    p=argparse.ArgumentParser(); p.add_argument('--interface',default='wlan0')
    args=p.parse_args()
    if not args.interface.replace('_','').isalnum():
        raise ValueError('Invalid interface')
    install(args.interface)
