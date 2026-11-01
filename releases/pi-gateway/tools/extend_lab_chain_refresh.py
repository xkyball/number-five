#!/usr/bin/env python3
"""Preserve the existing lab certificate renewal with the added public CA chain."""
import argparse
import ast
import datetime
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
from enable_password_proxy import atomic, lab_chain_refresh, protected_state


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--apply', action='store_true')
    args = parser.parse_args()
    assert os.geteuid() == 0 and Path('/etc/hostname').read_text().strip() == 'no5-marineos-lab'
    assert subprocess.check_output(['findmnt','-no','SOURCE','/'],text=True).strip() == '/dev/vda2'
    path = Path('/opt/no5-lab/bin/refresh-lab-network.py')
    before = path.read_bytes(); stat = path.stat()
    after = lab_chain_refresh(before.decode()).encode(); ast.parse(after)
    state = protected_state()
    print(json.dumps({'lab_only':True,'apply':args.apply,'certificate_rotation':False,'service_restart':False}))
    if not args.apply: return
    backup = Path('/var/backups/no5-lab-chain-refresh-' + datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%SZ'))
    backup.mkdir(mode=0o700); shutil.copy2(path,backup/'refresh.before.py'); (backup/'refresh.before.py').chmod(0o600)
    assert path.read_bytes() == before
    atomic(path,after,stat.st_mode & 0o777,stat.st_uid,stat.st_gid)
    assert protected_state() == state
    print(json.dumps({'applied':True,'backup':str(backup),'sha256':hashlib.sha256(after).hexdigest(),'protected_state_preserved':True}))


if __name__ == '__main__': main()
