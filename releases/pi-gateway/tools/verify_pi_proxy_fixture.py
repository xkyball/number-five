#!/usr/bin/env python3
"""Exercise the real nginx binary with isolated sockets and synthetic secrets.

Never reads production files or contacts any existing daemon. Suitable for an
unprivileged Pi SSH session while the privileged deployment is still pending.
"""
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import json
import re
import shutil
import socket
import ssl
import subprocess
import tempfile
import threading
import time

from migrate_pi_browser_proxy import browser_assets

KEY = 'fixture-gateway-key-only-0123456789'
BEARER = 'fixture-internal-token-only-0123456789'


class Upstream(BaseHTTPRequestHandler):
    received = []

    def log_message(self, *args):
        pass

    def do_GET(self):
        self.answer()

    def do_POST(self):
        self.answer()

    def answer(self):
        self.rfile.read(int(self.headers.get('Content-Length', '0')))
        self.received.append((self.command, self.path, dict(self.headers)))
        if self.path == '/admin/v1/simulation':
            assert self.headers.get('Authorization') == 'Bearer ' + BEARER
            assert not self.headers.get('X-No5-Gateway-Key')
            assert not self.headers.get('Cookie')
            status = 200 if self.command == 'GET' else 400
            body = b'{"fixture":true,"settings":{},"revision":0}'
        else:
            status, body = 200, b'{"fixture":true}'
        self.send_response(status)
        self.send_header('Content-Length', str(len(body)))
        self.end_headers()
        self.wfile.write(body)


def main():
    nginx = shutil.which('nginx') or '/usr/sbin/nginx'
    project = Path(__file__).resolve().parents[1]
    with tempfile.TemporaryDirectory(prefix='no5-proxy-fixture-') as directory:
        root = Path(directory)
        cert, key = root / 'cert.crt', root / 'cert.key'
        subprocess.run(['openssl', 'req', '-x509', '-newkey', 'rsa:2048', '-nodes',
                        '-days', '1', '-subj', '/CN=localhost', '-addext', 'subjectAltName=DNS:localhost',
                        '-keyout', str(key), '-out', str(cert)], check=True, capture_output=True)
        static = root / 'static'
        (static / 'simulator').mkdir(parents=True)
        source = (project / 'src/no5_pi_gateway/simulation_ui.html').read_text()
        for name, value in browser_assets(source).items():
            (static / 'simulator' / name).write_text(value)
        for name in ('camillagui-login.html', 'camillagui-login.js', 'camillagui-login.css'):
            shutil.copyfile(project / 'deploy/nginx' / name, static / name)
        site = (project / 'deploy/nginx/no5-gateway.conf').read_text()
        site = site.replace('include /etc/no5-gateway-proxy/nginx-browser-listeners.conf;', 'listen 127.0.0.1:443 ssl;')
        simulator = (project / 'deploy/nginx/no5-simulator-locations.conf').read_text()
        simulator = simulator.replace('include /etc/no5-gateway-proxy/nginx-simulator-upstream-auth.conf;',
                                      'proxy_set_header Authorization "Bearer ' + BEARER + '";')
        site = site.replace('include /etc/no5-gateway-proxy/nginx-simulator-locations.conf;', simulator)
        site = site.replace('/etc/no5-gateway-proxy/tls/server.crt', str(cert))
        site = site.replace('/etc/no5-gateway-proxy/tls/server.key', str(key))
        site = site.replace('/usr/local/share/no5-gateway', str(static))
        upstream = ThreadingHTTPServer(('127.0.0.1', 0), Upstream)
        thread = threading.Thread(target=upstream.serve_forever, daemon=True)
        thread.start()
        site = site.replace('127.0.0.1:8789', '127.0.0.1:' + str(upstream.server_port))
        sockets = []

        def isolate(match):
            path = root / ('socket' + str(len(sockets)))
            sockets.append(path)
            return match[1] + 'listen unix:' + str(path) + match[2] + ';'

        site = re.sub(r'(?m)^(\s*)listen\s+\S+(.*?);$', isolate, site)
        maps = (project / 'deploy/nginx/01-no5-camillagui-map.conf').read_text()
        maps += (project / 'deploy/nginx/no5-simulator-maps.conf').read_text()
        config = root / 'nginx.conf'
        config.write_text('pid ' + str(root / 'nginx.pid') + ';\nerror_log ' + str(root / 'error.log') + ';\n'
                          'events {}\nhttp {\naccess_log off;\nmap_hash_bucket_size 128;\n'
                          'client_body_temp_path ' + str(root / 'body') + ';\n'
                          'proxy_temp_path ' + str(root / 'proxy') + ';\n'
                          'map $http_x_no5_gateway_key $no5_gateway_authorized {default 0; "' + KEY + '" 1;}\n'
                          "map $http_upgrade $connection_upgrade {default upgrade; '' close;}\n" + maps + site + '\n}\n')
        subprocess.run([nginx, '-t', '-q', '-p', str(root) + '/', '-c', str(config)], check=True, capture_output=True)
        process = subprocess.Popen([nginx, '-p', str(root) + '/', '-c', str(config), '-g', 'daemon off;'],
                                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        checks = {}
        context = ssl.create_default_context(cafile=str(cert))

        def request(path, auth=False, method='GET', origin=None, browser=True, host='lysmarine.local'):
            headers = {'Host': host, 'Connection': 'close', 'Cookie': 'untrusted-client-cookie'}
            if auth:
                headers['X-No5-Gateway-Key'] = KEY
            if origin:
                headers['Origin'] = origin
            data = b'{}' if method == 'POST' else b''
            if data:
                headers['Content-Type'] = 'application/json'
                headers['Content-Length'] = str(len(data))
            raw = method + ' ' + path + ' HTTP/1.1\r\n' + ''.join(k + ': ' + v + '\r\n' for k, v in headers.items()) + '\r\n'
            with socket.socket(socket.AF_UNIX) as connection:
                connection.settimeout(3)
                connection.connect(str(sockets[-1] if browser else sockets[0]))
                with context.wrap_socket(connection, server_hostname='localhost') as secure:
                    secure.sendall(raw.encode() + data)
                    chunks = []
                    while True:
                        chunk = secure.recv(65536)
                        if not chunk:
                            break
                        chunks.append(chunk)
            response = b''.join(chunks)
            status = int(response.split(b' ', 2)[1])
            return status, response

        try:
            deadline = time.monotonic() + 5
            while not all(path.exists() for path in sockets):
                assert process.poll() is None, 'Fixture nginx startup failed'
                assert time.monotonic() < deadline
                time.sleep(.05)
            for path in ('/simulator/', '/simulator/app.js', '/simulator/app.css', '/camilladsp/login'):
                status, response = request(path)
                assert status == 200, (path, status)
                assert KEY.encode() not in response and BEARER.encode() not in response
                checks[path] = status
            cases = [
                ('missing_key', '/simulator/api', {}, 401),
                ('authenticated_read', '/simulator/api', {'auth': True}, 200),
                ('missing_origin', '/simulator/api', {'auth': True, 'method': 'POST'}, 403),
                ('wrong_origin', '/simulator/api', {'auth': True, 'method': 'POST', 'origin': 'https://example.invalid'}, 403),
                ('validated_write_route', '/simulator/api', {'auth': True, 'method': 'POST', 'origin': 'https://lysmarine.local'}, 400),
                ('unsupported_method', '/simulator/api', {'auth': True, 'method': 'DELETE'}, 405),
                ('wrong_host', '/simulator/', {'host': 'example.invalid'}, 421),
                ('admin_browser', '/admin/v1/simulation', {'auth': True}, 404),
                ('internal_browser', '/internal/camillagui/auth', {'auth': True}, 404),
                ('admin_gateway', '/admin/v1/simulation', {'auth': True, 'browser': False}, 404),
                ('internal_gateway', '/internal/camillagui/auth', {'auth': True, 'browser': False}, 404),
                ('api_without_key', '/v1/status', {'browser': False}, 401),
                ('api_existing_key', '/v1/status', {'browser': False, 'auth': True}, 200),
                ('unlisted_simulator_path', '/simulator/admin/v1/simulation', {'auth': True}, 404),
            ]
            for name, path, options, expected in cases:
                status, _ = request(path, **options)
                assert status == expected, (name, status, expected)
                checks[name] = status
            print(json.dumps({'fixture_passed': True, 'checks': checks,
                              'upstream_calls': len(Upstream.received), 'production_inputs_used': False}, indent=2))
        finally:
            process.terminate()
            process.wait(timeout=5)
            upstream.shutdown()
            upstream.server_close()


if __name__ == '__main__':
    main()
