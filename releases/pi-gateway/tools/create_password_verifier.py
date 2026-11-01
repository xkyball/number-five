#!/usr/bin/env python3
"""Run interactively on YOUR computer. Outputs only a non-login verifier.

There is no password argument, network call, telemetry or plaintext file.
Do not give the password to Codex. Give only the resulting JSON descriptor.
"""
import getpass
import hashlib
import json
import secrets
import sys

ITERATIONS = 600_000


def descriptor(password, client_salt=None, server_salt=None):
    client_salt = client_salt or secrets.token_hex(32)
    server_salt = server_salt or secrets.token_hex(32)
    prehash = hashlib.pbkdf2_hmac('sha256', password.encode('utf-8'), bytes.fromhex(client_salt), ITERATIONS)
    verifier = hashlib.pbkdf2_hmac('sha256', prehash, bytes.fromhex(server_salt), ITERATIONS).hex()
    return {'format': 'NO5-PASSWORD-VERIFIER-V1', 'iterations': ITERATIONS,
            'clientSalt': client_salt, 'serverSalt': server_salt, 'verifier': verifier}


def main():
    if not sys.stdin.isatty():
        raise SystemExit('Run this command yourself in an interactive terminal; no password via pipe or argument.')
    password = getpass.getpass('Dein Passwort (bleibt nur auf diesem Rechner): ')
    confirmation = getpass.getpass('Dein Passwort noch einmal: ')
    if password != confirmation: raise SystemExit('Eingaben stimmen nicht überein.')
    if not password: raise SystemExit('Bitte ein Passwort eingeben.')
    if len(password) > 512: raise SystemExit('Die App unterstützt höchstens 512 Zeichen.')
    value = descriptor(password)
    del password, confirmation
    print(json.dumps(value, indent=2))


if __name__ == '__main__': main()
