import hashlib
import http.client
import importlib.util
import json
from pathlib import Path
import tempfile
import threading
import unittest

from no5_pi_gateway.password_auth import PasswordVerifier, initial_record, save_record, server


class PasswordAuthTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.path = Path(self.temp.name) / 'record.json'
        save_record(self.path, initial_record())
        self.now = 100.0
        self.key = 'synthetic-gateway-key-for-tests'
        self.auth = PasswordVerifier(self.path, hashlib.sha256(self.key.encode()).hexdigest(), lambda: self.now)
        self.credential = hashlib.pbkdf2_hmac('sha256', b'synthetic password only',
            bytes.fromhex(self.auth.parameters()['salt']), 600000).hex()
    def tearDown(self): self.temp.cleanup()
    def test_empty_record_allows_key_but_never_anonymous_enrollment(self):
        self.assertTrue(self.auth.authorize([self.key], []))
        self.assertFalse(self.auth.authorize([], [self.credential]))
        self.assertFalse(self.auth.authorize([], []))
        self.assertFalse(self.auth.parameters()['registered'])
    def test_disk_never_contains_password_or_reusable_client_credential(self):
        self.auth.set_password(self.credential)
        raw = self.path.read_text()
        self.assertNotIn(self.credential, raw)
        self.assertNotIn('synthetic password only', raw)
        self.assertEqual(self.path.stat().st_mode & 0o777, 0o600)
        self.assertTrue(self.auth.authorize([], [self.credential]))
        self.assertTrue(self.auth.authorize([self.key], []))
    def test_dual_and_duplicate_headers_fail_without_fallback(self):
        self.auth.set_password(self.credential)
        for keys, passwords in [([self.key], ['0'*64]), ([self.key, self.key], []),
                                ([], [self.credential, self.credential]), ([], ['A'*64])]:
            self.assertFalse(self.auth.authorize(keys, passwords))
    def test_stored_verifier_is_not_a_login_credential(self):
        self.auth.set_password(self.credential)
        self.assertFalse(self.auth.authorize([], [json.loads(self.path.read_text())['verifier']]))
    def test_change_invalidates_cached_old_password_and_keeps_key(self):
        self.auth.set_password(self.credential)
        self.assertTrue(self.auth.authorize([], [self.credential]))
        self.auth.set_password('1'*64)
        self.assertFalse(self.auth.authorize([], [self.credential]))
        self.assertTrue(self.auth.authorize([], ['1'*64]))
        self.assertTrue(self.auth.authorize([self.key], []))
    def test_brute_force_limit_bounds_expensive_work_and_resets(self):
        self.auth.set_password(self.credential)
        for _ in range(5): self.assertFalse(self.auth.authorize([], ['0'*64], '192.0.2.1'))
        self.assertFalse(self.auth.authorize([], [self.credential], '192.0.2.1'))
        self.now += 61
        self.assertTrue(self.auth.authorize([], [self.credential], '192.0.2.1'))
        for _ in range(100): self.assertTrue(self.auth.authorize([], [self.credential]))
    def test_loopback_http_contract_has_no_enrollment_and_no_secret_errors(self):
        httpd = server(self.auth, b'synthetic public certificate', 0)
        worker = threading.Thread(target=httpd.serve_forever, daemon=True); worker.start()
        def request(method, path, payload=None, headers=None):
            c = http.client.HTTPConnection('127.0.0.1', httpd.server_port, timeout=5)
            try:
                c.request(method, path, body=payload, headers=headers or {})
                response = c.getresponse(); return response.status, response.read()
            finally: c.close()
        try:
            self.assertEqual(request('GET', '/ca'), (200, b'synthetic public certificate'))
            body = json.dumps({'credential': self.credential})
            self.assertEqual(request('POST', '/password', body, {'Content-Type':'application/json'})[0], 404)
            self.assertEqual(request('POST', '/password', body, {'Content-Type':'application/json', 'X-No5-Gateway-Key':self.key})[0], 404)
            self.auth.set_password(self.credential)
            self.assertEqual(request('GET', '/check', headers={'X-No5-Gateway-Password':self.credential})[0], 204)
            duplicate = '{"credential":"' + self.credential + '","credential":"' + self.credential + '"}'
            self.assertEqual(request('POST', '/password', duplicate, {'Content-Type':'application/json', 'X-No5-Gateway-Key':self.key})[0], 404)
            self.assertEqual(request('GET', '/unknown')[0], 404)
        finally: httpd.shutdown(); httpd.server_close(); worker.join()
    def test_proxy_extension_preserves_admin_and_audio_boundary(self):
        project = Path(__file__).resolve().parents[1]
        spec = importlib.util.spec_from_file_location('enable_password_proxy', project / 'tools/enable_password_proxy.py')
        module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
        site = module.prepare_site((project / 'deploy/nginx/no5-gateway.conf').read_text())
        self.assertIn('location ^~ /admin/', site)
        self.assertIn('location ^~ /internal/', site)
        self.assertIn('include /etc/no5-gateway-proxy/nginx-password-upstream-key.conf;', site)
        self.assertIn('auth_request /__no5_gateway_auth;', site)
        self.assertNotIn('if ($no5_gateway_authorized = 0)', site)
    def test_entered_key_is_also_locally_hashed_without_password_enrollment(self):
        prehash = hashlib.pbkdf2_hmac('sha256', self.key.encode(), bytes.fromhex(self.auth.parameters()['salt']), 600000)
        self.auth.prehashed_key_hash = hashlib.sha256(prehash).hexdigest()
        self.assertTrue(self.auth.authorize([], [prehash.hex()]))
        self.assertFalse(self.auth.parameters()['registered'])
    def test_offline_descriptor_contains_only_verifier_and_matches_client_hash(self):
        project = Path(__file__).resolve().parents[1]
        spec = importlib.util.spec_from_file_location('create_password_verifier', project / 'tools/create_password_verifier.py')
        module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
        value = module.descriptor('synthetic password only', self.auth.parameters()['salt'])
        self.assertNotIn(self.credential, json.dumps(value))
        record = {**self.auth.record, 'serverSalt':value['serverSalt'], 'verifier':value['verifier'], 'generation':1}
        save_record(self.path, record)
        offline = PasswordVerifier(self.path, self.auth.key_hash)
        self.assertTrue(offline.authorize([], [self.credential]))
        self.assertFalse(offline.authorize([], [value['verifier']]))


if __name__ == '__main__': unittest.main()
