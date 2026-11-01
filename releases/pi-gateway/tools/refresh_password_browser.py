#!/usr/bin/env python3
"""Refresh already extended public browser assets; preserve all auth/audio state."""
import argparse
import datetime
import hashlib
import json
import os
from pathlib import Path

from enable_password_proxy import atomic, browser_changes, protected_state, run


def auth_state():
    paths = ('/var/lib/no5-password-auth/record.json', '/etc/no5-password-auth/key.json')
    return {'files': {p: hashlib.sha256(Path(p).read_bytes()).hexdigest() for p in paths},
            'unit': run('systemctl', 'show', 'no5-password-auth', '-p', 'MainPID', '-p', 'NRestarts',
                        '-p', 'ActiveState', '-p', 'ExecMainStartTimestampMonotonic')}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--lab', action='store_true')
    parser.add_argument('--apply', action='store_true')
    args = parser.parse_args()
    assert os.geteuid() == 0
    host = run('hostname')
    if args.lab:
        assert host == 'no5-marineos-lab' and run('findmnt', '-no', 'SOURCE', '/') == '/dev/vda2'
    else:
        assert host == 'lysmarine' and 'Raspberry Pi 5' in Path('/proc/device-tree/model').read_text()
    assert run('systemctl', 'is-active', 'nginx') == 'active'
    assert run('systemctl', 'is-active', 'no5-password-auth') == 'active'
    # This is only an asset update after password provisioning, never enrollment.
    assert 'no5-password-locations.conf' in Path('/etc/nginx/sites-available/no5-gateway').read_text()
    changes = {p: value for p, value in browser_changes(args.lab).items()
               if Path(p).read_bytes() != value[0]}
    print(json.dumps({'hostname': host, 'planned_files': list(changes), 'apply': args.apply,
                      'audio_restart': False, 'auth_restart': False, 'nginx_reload': False}))
    if not args.apply or not changes:
        return
    backup = Path('/var/backups/no5-password-browser-' +
                  datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%SZ'))
    backup.mkdir(mode=0o700)
    receipt = {'hostname': host, 'files': [], 'preserved': protected_state(), 'auth': auth_state()}
    for index, (path, (data, _)) in enumerate(changes.items()):
        target = Path(path)
        stat = target.stat()
        before = str(index) + '.before'
        previous = target.read_bytes()
        (backup / before).write_bytes(previous)
        (backup / before).chmod(0o600)
        receipt['files'].append({'path': path, 'before': before, 'mode': stat.st_mode & 0o777,
                                'uid': stat.st_uid, 'gid': stat.st_gid,
                                'beforeSha256': hashlib.sha256(previous).hexdigest(),
                                'afterSha256': hashlib.sha256(data).hexdigest()})
    (backup / 'receipt.json').write_text(json.dumps(receipt, indent=2))
    (backup / 'receipt.json').chmod(0o600)
    installed = []
    try:
        for item in receipt['files']:
            assert hashlib.sha256(Path(item['path']).read_bytes()).hexdigest() == item['beforeSha256']
            atomic(item['path'], changes[item['path']][0], item['mode'], item['uid'], item['gid'])
            installed.append(item)
        run('/usr/sbin/nginx', '-t', *(['-c', '/etc/no5-lab/nginx.conf'] if args.lab else []))
        assert protected_state() == receipt['preserved'], 'Protected gateway/audio state changed'
        assert auth_state() == receipt['auth'], 'Auth service or credentials changed'
    except Exception:
        for item in reversed(installed):
            assert hashlib.sha256(Path(item['path']).read_bytes()).hexdigest() == item['afterSha256']
            atomic(item['path'], (backup / item['before']).read_bytes(), item['mode'], item['uid'], item['gid'])
        raise
    print(json.dumps({'passed': True, 'backup': str(backup), 'files': list(changes),
                      'auth_preserved': True, 'audio_and_gateway_preserved': True}))


if __name__ == '__main__':
    main()
