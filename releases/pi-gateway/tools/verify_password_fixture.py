#!/usr/bin/env python3
"""Real nginx + real password verifier; synthetic inputs and isolated sockets."""
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import re
import shutil
import socket
import ssl
import subprocess
import sys
import tempfile
import threading
import time

PROJECT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(PROJECT / 'src'))
from no5_pi_gateway.password_auth import PasswordVerifier, initial_record, save_record, server
from enable_password_proxy import prepare_site

KEY = 'synthetic-old-key-0123456789abcdef'


def main():
    with tempfile.TemporaryDirectory(prefix='no5-password-fixture-') as directory:
        root = Path(directory)
        cert, key = root / 'cert.crt', root / 'cert.key'
        subprocess.run(['openssl', 'req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-days', '1',
            '-subj', '/CN=localhost', '-addext', 'subjectAltName=DNS:localhost', '-keyout', str(key), '-out', str(cert)],
            check=True, capture_output=True)
        record = root / 'record.json'; save_record(record, initial_record())
        typed_key = hashlib.pbkdf2_hmac('sha256', KEY.encode(), bytes.fromhex(json.loads(record.read_text())['clientSalt']), 600000)
        auth = PasswordVerifier(record, hashlib.sha256(KEY.encode()).hexdigest(), prehashed_key_hash=hashlib.sha256(typed_key).hexdigest())
        auth_http = server(auth, cert.read_bytes(), 0)
        threading.Thread(target=auth_http.serve_forever, daemon=True).start()
        received = []
        class Upstream(BaseHTTPRequestHandler):
            def log_message(self, *_): pass
            def answer(self):
                body = self.rfile.read(int(self.headers.get('Content-Length', 0)))
                received.append((self.path, dict(self.headers), body))
                self.send_response(200); self.send_header('Content-Length', '2'); self.end_headers(); self.wfile.write(b'{}')
            do_GET = answer
            do_POST = answer
        upstream = ThreadingHTTPServer(('127.0.0.1', 0), Upstream)
        threading.Thread(target=upstream.serve_forever, daemon=True).start()
        site = prepare_site((PROJECT / 'deploy/nginx/no5-gateway.conf').read_text())
        site = site.replace('include /etc/no5-gateway-proxy/nginx-browser-listeners.conf;', 'listen 127.0.0.1:443 ssl;')
        locations = (PROJECT / 'deploy/nginx/no5-password-locations.conf').read_text()
        site = site.replace('include /etc/no5-gateway-proxy/no5-password-locations.conf;', locations)
        site = site.replace('include /etc/no5-gateway-proxy/nginx-password-upstream-key.conf;',
                            'proxy_set_header X-No5-Gateway-Key "' + KEY + '";')
        site = site.replace('include /etc/no5-gateway-proxy/nginx-password-camilla-body.conf;',
                            'proxy_set_body \'{"gatewayKey":"' + KEY + '"}\';')
        simulation = (PROJECT / 'deploy/nginx/no5-simulator-locations.conf').read_text().replace(
            'if ($no5_gateway_authorized = 0) { return 401; }', 'auth_request /__no5_gateway_auth;\nproxy_set_header X-No5-Gateway-Password "";')
        simulation = simulation.replace('include /etc/no5-gateway-proxy/nginx-simulator-upstream-auth.conf;',
                                        'proxy_set_header Authorization "Bearer synthetic-internal-token";')
        site = site.replace('include /etc/no5-gateway-proxy/nginx-simulator-locations.conf;', simulation)
        site = site.replace('/etc/no5-gateway-proxy/tls/server-with-ca.crt', str(cert)).replace('/etc/no5-gateway-proxy/tls/server.key', str(key))
        site = site.replace('127.0.0.1:8790', '127.0.0.1:' + str(auth_http.server_port))
        site = site.replace('127.0.0.1:8789', '127.0.0.1:' + str(upstream.server_port))
        site = site.replace('proxy_set_header X-Real-IP $remote_addr;', 'proxy_set_header X-Real-IP 127.0.0.1;')
        sockets = []
        def isolate(match):
            path = root / ('socket' + str(len(sockets))); sockets.append(path)
            return match[1] + 'listen unix:' + str(path) + match[2] + ';'
        site = re.sub(r'(?m)^(\s*)listen\s+\S+(.*?);$', isolate, site)
        maps = ''.join((PROJECT / 'deploy/nginx' / name).read_text() for name in
            ('01-no5-camillagui-map.conf', 'no5-simulator-maps.conf', 'no5-password-maps.conf'))
        config = root / 'nginx.conf'
        config.write_text('pid ' + str(root / 'pid') + ';\nerror_log ' + str(root / 'error.log') + ';\nevents {}\nhttp {\naccess_log off;\nmap_hash_bucket_size 128;\n'
            'client_body_temp_path ' + str(root / 'body') + ';\nproxy_temp_path ' + str(root / 'proxy') + ';\n'
            'map $http_x_no5_gateway_key $no5_gateway_authorized {default 0;}\n'
            "map $http_upgrade $connection_upgrade {default upgrade; '' close;}\n" + maps + site + '\n}\n')
        nginx = shutil.which('nginx') or '/usr/sbin/nginx'
        subprocess.run([nginx, '-t', '-q', '-p', str(root)+'/', '-c', str(config)], check=True, capture_output=True)
        process = subprocess.Popen([nginx, '-p', str(root)+'/', '-c', str(config), '-g', 'daemon off;'], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        context = ssl.create_default_context(cafile=str(cert))
        checks = {}
        def request(path, headers=None, body=None, browser=False):
            method = 'POST' if body is not None else 'GET'
            headers = {'Host':'lysmarine.local','Connection':'close', **(headers or {})}
            data = json.dumps(body).encode() if body is not None else b''
            if body is not None: headers.update({'Content-Type':'application/json','Content-Length':str(len(data))})
            raw = method + ' ' + path + ' HTTP/1.1\r\n' + ''.join(k+': '+v+'\r\n' for k,v in headers.items()) + '\r\n'
            with socket.socket(socket.AF_UNIX) as connection:
                connection.settimeout(5); connection.connect(str(sockets[-1] if browser else sockets[0]))
                with context.wrap_socket(connection, server_hostname='localhost') as secure:
                    secure.sendall(raw.encode()+data); chunks=[]
                    while True:
                        chunk=secure.recv(65536)
                        if not chunk: break
                        chunks.append(chunk)
            result=b''.join(chunks); head,payload=result.split(b'\r\n\r\n',1)
            return int(head.split(b' ',2)[1]), payload
        def check(name, expected, *args, **kwargs):
            status, _ = request(*args, **kwargs); assert status == expected, (name,status,expected); checks[name]=status
        try:
            for _ in range(60):
                if sockets[0].exists(): break
                if process.poll() is not None: raise RuntimeError('nginx fixture failed')
                time.sleep(.05)
            check('public_ca',200,'/auth/v1/ca')
            check('parameters',200,'/auth/v1/parameters')
            check('anonymous_api',401,'/v1/audio')
            check('legacy_api',200,'/v1/audio',{'X-No5-Gateway-Key':KEY})
            check('anonymous_registration_closed',404,'/auth/v1/password',body={'credential':'a'*64})
            check('key_registration_closed',404,'/auth/v1/password',{'X-No5-Gateway-Key':KEY},body={'credential':'a'*64})
            check('entered_key',200,'/v1/audio',{'X-No5-Gateway-Password':typed_key.hex()})
            # A short chosen password follows the same browser PBKDF2 contract.
            prehash = hashlib.pbkdf2_hmac('sha256', b'kurz',
                bytes.fromhex(auth.parameters()['salt']), 600000).hex()
            auth.set_password(prehash)  # Synthetic offline provisioning only.
            password={'X-No5-Gateway-Password':prehash}
            check('password_http',200,'/v1/audio',password)
            assert received[-1][1].get('X-No5-Gateway-Key') == KEY
            assert 'X-No5-Gateway-Password' not in received[-1][1]
            check('password_simulator',200,'/simulator/api',password,browser=True)
            assert received[-1][1].get('Authorization') == 'Bearer synthetic-internal-token'
            check('password_camilla_session',200,'/camilladsp/password-session',
                  {**password,'Origin':'https://lysmarine.local'},body={},browser=True)
            assert json.loads(received[-1][2]) == {'gatewayKey':KEY}
            check('foreign_origin_camilla',403,'/camilladsp/password-session',
                  {**password,'Origin':'https://foreign.invalid'},body={},browser=True)
            check('foreign_origin_simulator',403,'/simulator/api',
                  {**password,'Origin':'https://foreign.invalid'},body={},browser=True)
            check('wrong_password',401,'/v1/helm',{'X-No5-Gateway-Password':'0'*64})
            check('dual_credentials',401,'/v1/helm',{**password,'X-No5-Gateway-Key':KEY})
            check('foreign_origin_setup_closed',404,'/auth/v1/password',
                  {'X-No5-Gateway-Key':KEY,'Origin':'https://foreign.invalid'},body={'credential':'b'*64})
            check('admin_closed',404,'/admin/v1/simulation',password)
            check('internal_closed',404,'/internal/camillagui/auth',password)
            check('stored_hash_cannot_login',401,'/auth/v1/check',{'X-No5-Gateway-Password':auth.record['verifier']})
            check('legacy_after_password',200,'/v1/status',{'X-No5-Gateway-Key':KEY})
            print(json.dumps({'passed':True,'checks':checks,'production_contacted':False}))
        finally:
            process.terminate(); process.wait(timeout=5)
            upstream.shutdown(); upstream.server_close(); auth_http.shutdown(); auth_http.server_close()


if __name__ == '__main__': main()
