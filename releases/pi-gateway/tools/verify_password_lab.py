#!/usr/bin/env python3
"""Exercise an offline synthetic verifier on the virtual Pi, then restore it.

No operator password is read. No audio or simulator command is submitted.
The real Pi is explicitly excluded. Only the dedicated auth service restarts.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile

from create_password_verifier import descriptor


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--backup', type=Path, required=True)
    args = parser.parse_args()
    assert os.geteuid() == 0
    assert Path('/etc/hostname').read_text().strip() == 'no5-marineos-lab'
    assert subprocess.check_output(['findmnt','-no','SOURCE','/'],text=True).strip() == '/dev/vda2'
    paths = (Path('/var/lib/no5-password-auth/record.json'), Path('/etc/no5-password-auth/key.json'))
    originals = [(p.read_bytes(), p.stat()) for p in paths]
    assert json.loads(originals[0][0])['verifier'] is None, 'Never replace an operator verifier'
    tools = Path(__file__).resolve().parent
    synthetic = 'kurz'  # Deliberately fewer than 16 characters; never an operator credential.
    value = descriptor(synthetic)
    installed = False
    def run(name, *arguments):
        subprocess.run(['/usr/bin/python3',str(tools / name),*map(str,arguments)],check=True)
    try:
        with tempfile.TemporaryDirectory(prefix='no5-synthetic-password-') as directory:
            record = Path(directory) / 'descriptor.json'
            record.write_text(json.dumps(value)); record.chmod(0o600)
            prehash = Path(directory) / 'prehash'
            prehash.write_text(hashlib.pbkdf2_hmac('sha256',synthetic.encode(),
                bytes.fromhex(value['clientSalt']),600000).hex()); prehash.chmod(0o600)
            installed = True
            run('install_password_verifier.py',record,'--lab','--apply')
            run('verify_password_proxy.py','--lab','--backup',args.backup,'--synthetic-prehash-file',prehash)
    finally:
        if installed:
            current = json.loads(paths[0].read_bytes())
            assert current['verifier'] == value['verifier'] or paths[0].read_bytes() == originals[0][0], 'Concurrent verifier change; do not overwrite'
            for path,(data,stat) in zip(paths,originals):
                temp = path.with_name(path.name + '.restore')
                temp.write_bytes(data); temp.chmod(stat.st_mode & 0o777)
                os.chown(temp,stat.st_uid,stat.st_gid); os.replace(temp,path)
            subprocess.run(['systemctl','restart','no5-password-auth.service'],check=True)
            assert all(p.read_bytes() == before[0] for p,before in zip(paths,originals))
            print(json.dumps({'synthetic_verifier_removed':True,'original_auth_files_restored':True}))
    run('verify_password_proxy.py','--lab','--backup',args.backup)


if __name__ == '__main__': main()
