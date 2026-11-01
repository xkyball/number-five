#!/usr/bin/env python3
"""Reconcile physical-Pi browser listeners without changing TLS or credentials."""
import fcntl
import ipaddress
import json
import os
from pathlib import Path
import socket
import ssl
import subprocess
import tempfile
import time

CONFIG = Path('/etc/no5-gateway-proxy')


def command(*args):
    result = subprocess.run(args, capture_output=True, text=True, timeout=12)
    if result.returncode:
        raise RuntimeError(args[0] + ' failed; private diagnostics suppressed')
    return result.stdout


def atomic_write(path, data, mode=0o644, uid=0, gid=0):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, temporary = tempfile.mkstemp(prefix='.' + path.name + '.', dir=path.parent)
    try:
        os.fchmod(fd, mode)
        os.fchown(fd, uid, gid)
        with os.fdopen(fd, 'wb') as output:
            output.write(data if isinstance(data, bytes) else data.encode())
            output.flush()
            os.fsync(output.fileno())
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def selected_addresses(devices, macs):
    addresses = []
    for device in devices:
        if device.get('address', '').lower() not in macs or 'UP' not in device.get('flags', []):
            continue
        for entry in device.get('addr_info', []):
            flags = set(entry.get('flags', []))
            if (entry.get('scope') != 'global' or entry.get('tentative') or entry.get('dadfailed')
                    or flags.intersection({'tentative', 'dadfailed'})):
                continue
            address = ipaddress.ip_address(entry['local'])
            if not (address.is_loopback or address.is_link_local or address.is_multicast or address.is_unspecified):
                addresses.append(str(address))
    return sorted(set(addresses), key=lambda x: (ipaddress.ip_address(x).version, x))


def listener_config(addresses):
    lines = ['# Generated from the selected physical-Pi NICs; DHCP addresses are not identities.',
             'listen 127.0.0.1:443 ssl;']
    for value in addresses:
        address = ipaddress.ip_address(value)
        endpoint = '[' + value + ']' if address.version == 6 else value
        lines.append('listen ' + endpoint + ':443 ssl;')
    return '\n'.join(lines) + '\n'


def verify_tls(addresses):
    context = ssl.create_default_context(cafile=str(CONFIG / 'tls/ca.crt'))
    for address in ['127.0.0.1', *addresses]:
        with socket.create_connection((address, 443), timeout=.7) as connection:
            with context.wrap_socket(connection, server_hostname='lysmarine.local'):
                pass


def refresh():
    assert os.geteuid() == 0
    assert Path('/etc/hostname').read_text().strip() == 'lysmarine'
    assert 'Raspberry Pi 5' in Path('/proc/device-tree/model').read_text()
    policy = json.loads((CONFIG / 'browser-network.json').read_text())
    assert policy['hostname'] == 'lysmarine.local'
    devices = json.loads(command('/usr/sbin/ip', '-j', 'address', 'show'))
    addresses = selected_addresses(devices, policy['macs'])
    path = CONFIG / 'nginx-browser-listeners.conf'
    previous = path.read_bytes() if path.exists() else None
    desired = listener_config(addresses).encode()
    if previous == desired:
        return {'listener_changed': False, 'nginx_reloaded': False, 'addresses': addresses}
    atomic_write(path, desired)
    active = subprocess.run(['systemctl', 'is-active', '--quiet', 'nginx'], capture_output=True).returncode == 0
    reload_attempted = False
    try:
        command('/usr/sbin/nginx', '-t', '-q')
        if active:
            reload_attempted = True
            command('/usr/sbin/nginx', '-s', 'reload')
            deadline = time.monotonic() + 4
            while True:
                try:
                    verify_tls(addresses)
                    break
                except (OSError, ssl.SSLError):
                    if time.monotonic() >= deadline:
                        raise RuntimeError('New browser listeners failed strict TLS readback')
                    time.sleep(.1)
    except BaseException:
        if previous is None:
            path.unlink(missing_ok=True)
        else:
            atomic_write(path, previous)
        if reload_attempted:
            # Restore the old running configuration as well as its disk copy.
            command('/usr/sbin/nginx', '-t', '-q')
            command('/usr/sbin/nginx', '-s', 'reload')
        raise
    return {'listener_changed': True, 'nginx_reloaded': active, 'addresses': addresses}


def main():
    with Path('/run/no5-browser-network.lock').open('a') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        print(json.dumps(refresh()))


if __name__ == '__main__':
    main()
