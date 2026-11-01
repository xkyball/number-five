#!/usr/bin/env python3
"""Read-only live acceptance, plus rejected writes and a temporary GUI session.

Run on the physical Pi after the approved migration. No valid simulation,
DSP, audio, SONAR or transport command is submitted. Credentials never leave
this process except to the same Pi's authenticated HTTPS routes.
"""
import argparse
import hashlib
import http.client
import http.cookiejar
import json
import os
from pathlib import Path
import ssl
import socket
import tomllib
import urllib.error
import urllib.request

from migrate_pi_browser_proxy import GATEWAY, PRESERVE_UNITS, PROXY, dsp_state, environment, simulation_state, units


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--backup', type=Path, required=True)
    parser.add_argument('--connect-address', default='127.0.0.1')
    args = parser.parse_args()
    assert os.geteuid() == 0
    assert Path('/etc/hostname').read_text().strip() == 'lysmarine'
    assert 'Raspberry Pi 5' in Path('/proc/device-tree/model').read_text()
    receipt = json.loads((args.backup / 'receipt.json').read_text())
    import sys
    sys.path.insert(0, '/opt/no5-pi-gateway/current/src')
    from no5_pi_gateway.nginx_auth_map import extract
    key = extract(Path('/etc/nginx/conf.d/no5-gateway-auth.conf').read_bytes()).decode('ascii')
    cfg = tomllib.loads((GATEWAY / 'config.toml').read_text())
    env = environment((GATEWAY / 'environment').read_text())
    baseline = simulation_state(cfg, env)
    context = ssl.create_default_context(cafile=str(PROXY / 'tls/ca.crt'))
    cookies = http.cookiejar.CookieJar()

    class LocalPiConnection(http.client.HTTPSConnection):
        def connect(self):
            # The Pi's /etc/hosts maps its own name to 127.0.1.1. Connect to
            # the explicitly selected owned listener while retaining the real
            # hostname for SNI, certificate validation, Host and cookie scope.
            assert self.host == 'lysmarine.local'
            self.sock = socket.create_connection((args.connect_address, self.port), self.timeout)
            self.sock = self._context.wrap_socket(self.sock, server_hostname=self.host)

    class LocalPiHandler(urllib.request.HTTPSHandler):
        def https_open(self, req):
            return self.do_open(LocalPiConnection, req, context=self._context,
                                check_hostname=self._check_hostname)

    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}),
        LocalPiHandler(context=context), urllib.request.HTTPCookieProcessor(cookies))
    origin = 'https://lysmarine.local'
    results = {}

    def request(path, auth=False, method='GET', request_origin=None, body=None, gateway=False, wrong_key=False):
        headers = {}
        if auth:
            headers['X-No5-Gateway-Key'] = 'intentionally-invalid-key' if wrong_key else key
        if request_origin:
            headers['Origin'] = request_origin
        data = json.dumps(body).encode() if body is not None else None
        if data:
            headers['Content-Type'] = 'application/json'
        url = (origin + ':9443' if gateway else origin) + path
        req = urllib.request.Request(url, headers=headers, data=data, method=method)
        try:
            with opener.open(req, timeout=6) as response:
                return response.status, response.read(), response.headers
        except urllib.error.HTTPError as error:
            return error.code, error.read(), error.headers

    for path in ('/simulator/', '/simulator/app.js', '/simulator/app.css'):
        status, body, headers = request(path)
        assert status == 200 and headers.get('Cache-Control') == 'no-store'
        assert key.encode() not in body and env[cfg['simulation']['token_env']].encode() not in body
        results[path] = status
    cases = [
        ('missing_key', '/simulator/api', {}, 401),
        ('wrong_key', '/simulator/api', {'auth': True, 'wrong_key': True}, 401),
        ('authenticated_simulator', '/simulator/api', {'auth': True}, 200),
        ('missing_origin', '/simulator/api', {'auth': True, 'method': 'POST', 'body': {}}, 403),
        ('wrong_origin', '/simulator/api', {'auth': True, 'method': 'POST', 'body': {}, 'request_origin': 'https://example.invalid'}, 403),
        ('invalid_payload', '/simulator/api', {'auth': True, 'method': 'POST', 'body': {}, 'request_origin': origin}, 400),
        ('unsupported_method', '/simulator/api', {'auth': True, 'method': 'DELETE'}, 405),
        ('browser_admin_hidden', '/admin/v1/simulation', {'auth': True}, 404),
        ('browser_internal_hidden', '/internal/camillagui/auth', {'auth': True}, 404),
        ('gateway_admin_hidden', '/admin/v1/simulation', {'auth': True, 'gateway': True}, 404),
        ('gateway_internal_hidden', '/internal/camillagui/auth', {'auth': True, 'gateway': True}, 404),
        ('gateway_key_still_works', '/v1/status', {'auth': True, 'gateway': True}, 200),
        ('browser_has_no_general_api', '/v1/status', {'auth': True}, 404),
    ]
    for name, path, options, expected in cases:
        status, _, _ = request(path, **options)
        assert status == expected, name + ' failed'
        results[name] = status
    status, _, _ = request('/camilladsp/session', method='POST', request_origin=origin, body={'gatewayKey': key})
    assert status == 200, 'GUI session login failed'
    saved = list(cookies)
    assert len(saved) == 1 and saved[0].secure and saved[0].path == '/camilladsp/'
    assert saved[0].has_nonstandard_attr('HttpOnly')
    status, body, _ = request('/camilladsp/')
    assert status == 200 and b'gateway-key' not in body and key.encode() not in body
    assert b'<html' in body.lower(), 'Authenticated original GUI HTML required'
    results['authenticated_camillagui'] = status
    status, _, _ = request('/camilladsp/logout', method='POST', request_origin=origin)
    assert status == 204 and not list(cookies)
    results['gui_logout'] = status
    assert simulation_state(cfg, env) == baseline, 'Verification changed simulator selection'
    if receipt['simulation_before'] is not None:
        assert baseline == receipt['simulation_before']
    current = units(PRESERVE_UNITS)
    assert current == receipt['preserved_units'], 'A protected daemon restarted'
    assert dsp_state() == receipt['dsp_before'], 'DSP state changed'
    assert all(hashlib.sha256(Path(p).read_bytes()).hexdigest() == digest for p, digest in receipt['protected_files'].items())
    assert (PROXY / 'nginx-simulator-upstream-auth.conf').stat().st_mode & 0o777 == 0o600
    print(json.dumps({'verified': True, 'strict_tls': True, 'connect_address': args.connect_address, 'checks': results,
                      'selection_and_audio_preserved': True, 'audio_daemons_restarted': False,
                      'existing_credentials_and_tls_preserved': True}, indent=2))


if __name__ == '__main__':
    main()
