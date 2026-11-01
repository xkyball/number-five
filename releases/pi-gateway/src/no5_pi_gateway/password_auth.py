"""Loopback-only password verifier for the existing TLS proxy.

The client sends a PBKDF2 prehash over verified TLS. Disk contains a separately
salted PBKDF2 verifier, never the password or the reusable client prehash.
This is password authentication over TLS, not a PAKE protocol.
"""
import argparse
from collections import deque
import hashlib
import hmac
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import ipaddress
import json
import os
from pathlib import Path
import re
import secrets
import tempfile
import threading
import time

ITERATIONS = 600_000
PASSWORD_HEADER = "X-No5-Gateway-Password"
HEX = re.compile(r"[0-9a-f]{64}\Z")


def initial_record():
    return {"version": 1, "clientSalt": secrets.token_hex(32), "iterations": ITERATIONS,
            "serverSalt": None, "verifier": None, "generation": 0}


def save_record(path, record):
    path = Path(path)
    with tempfile.NamedTemporaryFile(dir=path.parent, mode="w", delete=False) as f:
        temporary = Path(f.name)
        os.chmod(f.name, 0o600)
        json.dump(record, f)
        f.write("\n")
        f.flush()
        os.fsync(f.fileno())
    try:
        os.replace(temporary, path)
        directory = os.open(path.parent, os.O_RDONLY | os.O_DIRECTORY)
        try: os.fsync(directory)
        finally: os.close(directory)
    finally:
        temporary.unlink(missing_ok=True)


class PasswordVerifier:
    def __init__(self, path, key_hash, clock=time.monotonic, prehashed_key_hash=None):
        self.path = Path(path)
        self.key_hash = key_hash
        assert HEX.fullmatch(key_hash)
        self.prehashed_key_hash = prehashed_key_hash
        assert prehashed_key_hash is None or HEX.fullmatch(prehashed_key_hash)
        self.record = json.loads(self.path.read_text())
        assert set(self.record) == set(initial_record())
        assert self.record["version"] == 1 and self.record["iterations"] == ITERATIONS
        assert HEX.fullmatch(self.record["clientSalt"])
        assert isinstance(self.record["generation"], int) and self.record["generation"] >= 0
        assert (self.record["verifier"] is None and self.record["serverSalt"] is None) or (
            HEX.fullmatch(self.record["verifier"]) and HEX.fullmatch(self.record["serverSalt"]))
        self.clock = clock
        self.lock = threading.RLock()
        self.workers = threading.BoundedSemaphore(2)
        self.cached = None
        self.failures = deque()

    def parameters(self):
        with self.lock:
            return {"version": 1, "algorithm": "PBKDF2-HMAC-SHA256", "iterations": ITERATIONS,
                    "salt": self.record["clientSalt"], "registered": self.record["verifier"] is not None}

    def authorize(self, keys, passwords, peer="127.0.0.1"):
        # A supplied password must never silently fall back to the key mode.
        if keys and passwords or len(keys) > 1 or len(passwords) > 1:
            return False
        if keys:
            try: candidate = hashlib.sha256(keys[0].encode("ascii")).hexdigest()
            except (UnicodeError, ValueError): return False
            return hmac.compare_digest(candidate, self.key_hash)
        if len(passwords) != 1 or not HEX.fullmatch(passwords[0]):
            return False
        credential = bytes.fromhex(passwords[0])
        digest = hashlib.sha256(credential).digest()
        if self.prehashed_key_hash is not None and hmac.compare_digest(digest.hex(), self.prehashed_key_hash):
            return True
        now = self.clock()
        with self.lock:
            record = dict(self.record)
            if record["verifier"] is None: return False
            if self.cached and self.cached[1] > now and hmac.compare_digest(digest, self.cached[0]):
                return True
            while self.failures and self.failures[0][0] <= now - 60:
                self.failures.popleft()
            # Bound CPU even for rotating addresses and rotating wrong prehashes.
            if len(self.failures) >= 20 or sum(p == peer for _, p in self.failures) >= 5:
                return False
            self.failures.append((now, peer))
        if not self.workers.acquire(blocking=False): return False
        try:
            computed = hashlib.pbkdf2_hmac("sha256", credential, bytes.fromhex(record["serverSalt"]), ITERATIONS).hex()
            valid = hmac.compare_digest(computed, record["verifier"])
            with self.lock:
                if self.record["generation"] != record["generation"]: return False
                if valid: self.cached = (digest, self.clock() + 900)
            return valid
        finally:
            self.workers.release()

    def set_password(self, credential):
        if not isinstance(credential, str) or not HEX.fullmatch(credential):
            raise ValueError("invalid credential format")
        if not self.workers.acquire(blocking=False):
            raise RuntimeError("busy")
        try:
            salt = secrets.token_hex(32)
            value = hashlib.pbkdf2_hmac("sha256", bytes.fromhex(credential), bytes.fromhex(salt), ITERATIONS).hex()
            with self.lock:
                record = {**self.record, "serverSalt": salt, "verifier": value,
                          "generation": self.record["generation"] + 1}
                save_record(self.path, record)
                self.record = record
                self.cached = None
                self.failures.clear()
        finally:
            self.workers.release()


def server(verifier, ca, port=8790):
    class Handler(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.0"
        def log_message(self, *_): pass  # No URLs, request bodies or credential headers in logs.
        def setup(self):
            super().setup()
            self.connection.settimeout(5)
        def reply(self, status, data=b"", content_type="application/json"):
            self.send_response(status)
            self.send_header("Content-Type", content_type)
            self.send_header("Content-Length", str(len(data)))
            self.send_header("Cache-Control", "no-store")
            self.send_header("X-Content-Type-Options", "nosniff")
            self.end_headers()
            self.wfile.write(data)
        def authorized(self):
            peers = self.headers.get_all("X-Real-IP", [])
            try: peer = str(ipaddress.ip_address(peers[0])) if len(peers) == 1 else self.client_address[0]
            except ValueError: return False
            return verifier.authorize(self.headers.get_all("X-No5-Gateway-Key", []),
                                      self.headers.get_all(PASSWORD_HEADER, []), peer)
        def do_GET(self):
            if self.client_address[0] != "127.0.0.1": return self.reply(403)
            if self.path == "/parameters":
                return self.reply(200, json.dumps(verifier.parameters()).encode())
            if self.path == "/ca":
                return self.reply(200, ca, "application/x-pem-file")
            if self.path == "/check":
                return self.reply(204 if self.authorized() else 401)
            return self.reply(404)
        def do_POST(self):
            return self.reply(404)  # No enrollment or password-changing HTTP API.
        def do_PUT(self): self.reply(405)
        def do_DELETE(self): self.reply(405)
    class BoundedServer(ThreadingHTTPServer):
        daemon_threads = True
        request_queue_size = 16
        slots = threading.BoundedSemaphore(16)
        def process_request(self, request, address):
            if not self.slots.acquire(blocking=False):
                self.shutdown_request(request)
                return
            try: super().process_request(request, address)
            except Exception:
                self.slots.release()
                raise
        def process_request_thread(self, request, address):
            try: super().process_request_thread(request, address)
            finally: self.slots.release()
        def handle_error(self, *_): pass
    return BoundedServer(("127.0.0.1", port), Handler)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--record", type=Path, required=True)
    parser.add_argument("--key-hash", type=Path, required=True)
    parser.add_argument("--ca", type=Path, required=True)
    args = parser.parse_args()
    keys = json.loads(args.key_hash.read_text())
    verifier = PasswordVerifier(args.record, keys['rawSha256'], prehashed_key_hash=keys['prehashSha256'])
    ca = args.ca.read_bytes()
    assert 0 < len(ca) <= 65536 and b"PRIVATE KEY" not in ca
    server(verifier, ca).serve_forever()


if __name__ == "__main__": main()
