#!/usr/bin/env python3
"""Read-only live proof: CA, imported/entered key, headers and WS admission.

Optionally checks an already provisioned synthetic lab prehash. Never sets a
password and never submits AUDIO, SONAR or simulator commands. All credential
reads and uses stay on the target host; stdout contains only status metadata.
"""
import argparse
import base64
import hashlib
import http.client
import json
import os
from pathlib import Path
import secrets
import socket
import ssl
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'src'))
from no5_pi_gateway.nginx_auth_map import extract
from enable_password_proxy import protected_state


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--backup', type=Path, required=True)
    parser.add_argument('--connect-address', default='127.0.0.1')
    parser.add_argument('--lab', action='store_true')
    parser.add_argument('--synthetic-prehash-file', type=Path)
    args = parser.parse_args()
    assert os.geteuid() == 0
    host = Path('/etc/hostname').read_text().strip()
    assert host == ('no5-marineos-lab' if args.lab else 'lysmarine')
    assert args.synthetic_prehash_file is None or args.lab
    receipt = json.loads((args.backup / 'receipt.json').read_text())
    assert receipt['hostname'] == host
    assert protected_state() == receipt['preserved']
    key = extract(Path('/etc/nginx/conf.d/no5-gateway-auth.conf').read_bytes()).decode('ascii')
    record = json.loads(Path('/var/lib/no5-password-auth/record.json').read_text())
    entered_key = hashlib.pbkdf2_hmac('sha256', key.encode(), bytes.fromhex(record['clientSalt']),600000).hex()
    typed_headers = {'X-No5-Gateway-Password':entered_key}
    ca = Path('/etc/no5-gateway-proxy/tls/ca.crt').read_bytes()
    context = ssl.create_default_context(cafile='/etc/no5-gateway-proxy/tls/ca.crt')
    name = host + '.local'
    checks = {}
    class Connection(http.client.HTTPSConnection):
        def connect(self):
            self.sock = socket.create_connection((args.connect_address,self.port),self.timeout)
            self.sock = self._context.wrap_socket(self.sock,server_hostname=name)
    def request(path, headers=None, body=None, browser=False, websocket=False):
        c = Connection(name,443 if browser else 9443,context=context,timeout=7)
        headers = dict(headers or {})
        if websocket:
            headers.update({'Upgrade':'websocket','Connection':'Upgrade','Sec-WebSocket-Version':'13',
                            'Sec-WebSocket-Key':base64.b64encode(secrets.token_bytes(16)).decode(),
                            'Sec-WebSocket-Protocol':'no5.sonar.v1' if path == '/v1/sonar/stream' else 'no5.audio.fader.v1'})
        try:
            c.request('POST' if body is not None else 'GET',path,body=body,headers=headers)
            response = c.getresponse()
            payload = b'' if websocket else response.read(65537)
            values = dict(response.headers)
            if websocket and response.status == 101:
                expected = base64.b64encode(hashlib.sha1((headers['Sec-WebSocket-Key'] +
                    '258EAFA5-E914-47DA-95CA-C5AB0DC85B11').encode()).digest()).decode()
                assert response.getheader('Sec-WebSocket-Accept') == expected
                assert response.getheader('Sec-WebSocket-Protocol') == headers['Sec-WebSocket-Protocol']
                # Normal masked WebSocket close, no feature command or control frame.
                mask = secrets.token_bytes(4); code = b'\x03\xe8'
                c.sock.sendall(b'\x88\x82'+mask+bytes(v^mask[i%4] for i,v in enumerate(code)))
            return response.status,payload,values
        finally: c.close()
    def check(label, expected, *args, **kwargs):
        status,body,headers = request(*args,**kwargs)
        assert status == expected,(label,status,expected)
        checks[label]=status
        return body,headers
    body,_ = check('public_ca',200,'/auth/v1/ca'); assert body == ca
    body,_ = check('parameters',200,'/auth/v1/parameters'); assert json.loads(body)['salt'] == record['clientSalt']
    check('anonymous_check',401,'/auth/v1/check')
    check('imported_key',204,'/auth/v1/check',{'X-No5-Gateway-Key':key})
    check('entered_key',204,'/auth/v1/check',typed_headers)
    check('entered_key_status',200,'/v1/status',typed_headers)
    check('entered_key_audio',200,'/v1/audio',typed_headers)
    check('entered_key_sonar_ws',101,'/v1/sonar/stream',typed_headers,websocket=True)
    check('entered_key_fader_ws',101,'/v1/audio/stream',typed_headers,websocket=True)
    check('entered_key_simulator',200,'/simulator/api',typed_headers,browser=True)
    body,_ = check('camilla_login_page',200,'/camilladsp/login',browser=True)
    assert b'Gateway-Key oder Passwort' in body and b'minlength=' not in body
    body,_ = check('camilla_password_script',200,'/camilladsp/login.js',browser=True)
    assert b'no5PasswordPrehash' in body and b'/camilladsp/password-session' in body
    body,_ = check('simulator_password_script',200,'/simulator/app.js',browser=True)
    assert b'no5PasswordPrehash' in body and b"if(value){token=await" in body
    _,headers = check('entered_key_camilla_login',200,'/camilladsp/password-session',
        {**typed_headers,'Origin':'https://'+name,'Content-Type':'application/json'},body=b'{}',browser=True)
    cookie = next(v for k,v in headers.items() if k.lower()=='set-cookie').split(';',1)[0]
    _,headers = check('entered_key_camilla_redirect',302,'/camilladsp/',{'Cookie':cookie},browser=True)
    assert headers.get('Location') == 'https://'+name+'/camilladsp/gui/index.html'
    check('entered_key_camilla_page',200,'/camilladsp/gui/index.html',{'Cookie':cookie},browser=True)
    check('camilla_logout',204,'/camilladsp/logout',{'Origin':'https://'+name,'Cookie':cookie},body=b'{}',browser=True)
    check('wrong_credential',401,'/auth/v1/check',{'X-No5-Gateway-Password':'0'*64})
    check('dual_headers',401,'/v1/status',{**typed_headers,'X-No5-Gateway-Key':key})
    check('enrollment_closed',404,'/auth/v1/password',{'X-No5-Gateway-Key':key},body=b'{}')
    check('admin_closed',404,'/admin/v1/simulation',typed_headers)
    # Physical proxy blocks private routes; lab's unchanged cookie guard returns 401.
    check('internal_closed',401 if args.lab else 404,'/internal/camillagui/auth',typed_headers)
    if args.synthetic_prehash_file:
        password = args.synthetic_prehash_file.read_text().strip()
        assert len(password)==64
        headers={'X-No5-Gateway-Password':password}
        check('synthetic_password_http',200,'/v1/audio',headers)
        check('synthetic_password_sonar_ws',101,'/v1/sonar/stream',headers,websocket=True)
        check('synthetic_password_fader_ws',101,'/v1/audio/stream',headers,websocket=True)
        check('synthetic_password_simulator',200,'/simulator/api',headers,browser=True)
        _,session_headers = check('synthetic_password_camilla_login',200,'/camilladsp/password-session',
            {**headers,'Origin':'https://'+name,'Content-Type':'application/json'},body=b'{}',browser=True)
        cookie = next(v for k,v in session_headers.items() if k.lower()=='set-cookie').split(';',1)[0]
        _,page_headers = check('synthetic_password_camilla_redirect',302,'/camilladsp/',{'Cookie':cookie},browser=True)
        assert page_headers.get('Location') == 'https://'+name+'/camilladsp/gui/index.html'
        check('synthetic_password_camilla_page',200,'/camilladsp/gui/index.html',{'Cookie':cookie},browser=True)
        check('synthetic_password_camilla_logout',204,'/camilladsp/logout',
            {'Origin':'https://'+name,'Cookie':cookie},body=b'{}',browser=True)
        check('stored_verifier_cannot_login',401,'/auth/v1/check',{'X-No5-Gateway-Password':record['verifier']})
    assert protected_state() == receipt['preserved']
    print(json.dumps({'passed':True,'hostname':host,'checks':checks,'preserved_units_and_audio_files':True,
        'password_provisioned':record['verifier'] is not None,'plaintext_received':False}))


if __name__ == '__main__': main()
