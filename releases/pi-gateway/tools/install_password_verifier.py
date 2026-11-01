#!/usr/bin/env python3
"""Install only the operator's offline verifier. Never accepts a password.

Default is a read-only plan. --apply changes only the verifier and restarts
its dedicated loopback service. The existing gateway/audio processes continue.
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

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'src'))
from no5_pi_gateway.password_auth import HEX, ITERATIONS, save_record
from no5_pi_gateway.nginx_auth_map import extract


def validate_descriptor(value):
    assert isinstance(value, dict) and set(value) == {'format','iterations','clientSalt','serverSalt','verifier'}
    assert value['format'] == 'NO5-PASSWORD-VERIFIER-V1' and value['iterations'] == ITERATIONS
    assert all(isinstance(value[k], str) and HEX.fullmatch(value[k]) for k in ('clientSalt','serverSalt','verifier'))
    assert len({value[k] for k in ('clientSalt','serverSalt','verifier')}) == 3
    return value


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('descriptor', type=Path)
    parser.add_argument('--apply', action='store_true')
    parser.add_argument('--lab', action='store_true')
    args = parser.parse_args()
    assert os.geteuid() == 0
    host = Path('/etc/hostname').read_text().strip()
    if args.lab:
        assert host == 'no5-marineos-lab' and subprocess.check_output(['findmnt','-no','SOURCE','/'],text=True).strip() == '/dev/vda2'
    else:
        assert host == 'lysmarine' and 'Raspberry Pi 5' in Path('/proc/device-tree/model').read_text()
    assert args.descriptor.stat().st_size <= 2048
    def pairs(items):
        result = {}
        for k,v in items:
            assert k not in result
            result[k] = v
        return result
    descriptor = validate_descriptor(json.loads(args.descriptor.read_text(), object_pairs_hook=pairs))
    record_path = Path('/var/lib/no5-password-auth/record.json')
    before = json.loads(record_path.read_text())
    key_path = Path('/etc/no5-password-auth/key.json')
    key = extract(Path('/etc/nginx/conf.d/no5-gateway-auth.conf').read_bytes())
    old_keys = json.loads(key_path.read_text())
    assert old_keys['rawSha256'] == hashlib.sha256(key).hexdigest()
    record = {'version':1, 'clientSalt':descriptor['clientSalt'], 'iterations':ITERATIONS,
              'serverSalt':descriptor['serverSalt'], 'verifier':descriptor['verifier'], 'generation':before['generation']+1}
    derived_key = hashlib.pbkdf2_hmac('sha256', key, bytes.fromhex(record['clientSalt']), ITERATIONS)
    keys = {'rawSha256':old_keys['rawSha256'], 'prehashSha256':hashlib.sha256(derived_key).hexdigest()}
    print(json.dumps({'hostname':host,'apply':args.apply,'plaintext_received':False,'existing_key_preserved':True,
                      'gateway_restart':False,'password_previously_provisioned':before['verifier'] is not None}))
    if not args.apply: return
    backup = Path('/var/backups/no5-password-verifier-' + datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%SZ'))
    backup.mkdir(mode=0o700)
    for path in (record_path,key_path):
        shutil.copy2(path, backup / path.name); (backup/path.name).chmod(0o600)
    account = pwd.getpwnam('no5auth')
    def key_write(data):
        tmp = key_path.with_name('key.staged.json'); tmp.write_text(json.dumps(data)+'\n')
        tmp.chmod(0o640); os.chown(tmp,0,account.pw_gid); os.replace(tmp,key_path)
    try:
        save_record(record_path,record); os.chown(record_path,account.pw_uid,account.pw_gid)
        key_write(keys)
        subprocess.run(['systemctl','restart','no5-password-auth.service'],check=True,capture_output=True)
        subprocess.run(['systemctl','is-active','--quiet','no5-password-auth.service'],check=True)
    except Exception:
        save_record(record_path,before); os.chown(record_path,account.pw_uid,account.pw_gid)
        key_write(old_keys)
        subprocess.run(['systemctl','restart','no5-password-auth.service'],capture_output=True)
        raise
    print(json.dumps({'installed':True,'backup':str(backup),'plaintext_received':False}))


if __name__ == '__main__': main()
