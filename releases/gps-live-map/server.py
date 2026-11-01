#!/usr/bin/env python3
"""Read-only, LAN-only GPS viewer. Python standard library; no disk telemetry."""
import argparse
import copy
import ipaddress
import json
import math
import os
from pathlib import Path
import signal
import socket
import subprocess
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.request import Request, build_opener, ProxyHandler, HTTPRedirectHandler

ROOT = Path(__file__).resolve().parent
MAX_BYTES = 131072


def position(value):
    if not isinstance(value, dict):
        return None
    lat, lon = value.get('latitudeDegrees'), value.get('longitudeDegrees')
    if any(type(x) not in (int, float) or not math.isfinite(x) for x in (lat, lon)):
        return None
    if not -90 <= lat <= 90 or not -180 <= lon <= 180:
        return None
    return {'latitudeDegrees': lat, 'longitudeDegrees': lon}


def observation(value):
    stamp = value.get('observedAtEpochMs')
    return {'status': value.get('status', 'UNAVAILABLE'),
            'observedAtEpochMs': stamp if type(stamp) is int and stamp > 0 else None,
            'position': position(value.get('position'))}


def normalize(document, now):
    catalog = document.get('positionSources')
    if not isinstance(catalog, dict) or not isinstance(catalog.get('sources'), list):
        raise ValueError('POSITION_CATALOG_MISSING')
    sources, ids = [], set()
    for value in catalog['sources']:
        source = value.get('sourceId')
        if not isinstance(source, str) or not source or source in ids:
            raise ValueError('INVALID_SOURCE_ID')
        ids.add(source)
        sources.append(dict(observation(value), sourceId=source, label=value.get('label', source)))
    combo = catalog.get('combination') or {}
    combination = dict(observation(combo), available=combo.get('available', False),
                       mode=combo.get('mode', 'NONE'), reason=combo.get('reason', 'UNAVAILABLE'),
                       contributors=[{'sourceId': s['sourceId']} for s in combo.get('contributors', [])],
                       excluded=[{'sourceId': s['sourceId'], 'reason': s.get('reason', 'UNKNOWN')}
                                 for s in combo.get('excluded', [])])
    freshness = (catalog.get('freshness') or {}).get('liveForMs', 5000)
    if type(freshness) is not int or not 0 < freshness <= 5000:
        freshness = 5000
    return {'version': 1, 'serverNowEpochMs': now, 'fetchedAtEpochMs': now,
            'upstreamAvailable': True, 'error': None,
            # HELM may simulate unrelated navigation fields while this independent
            # source catalogue remains real. Its own provenance is authoritative.
            'simulated': catalog.get('simulated') is True or catalog.get('sourceKind') == 'SIMULATED',
            'serverSessionId': document.get('serverSessionId'),
            'liveForMs': freshness, 'total': catalog.get('total', len(sources)),
            'omitted': catalog.get('omitted', 0), 'sources': sources, 'combination': combination}


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None


class Collector:
    def __init__(self, key_file, url='http://127.0.0.1:8789/v1/helm'):
        if url != 'http://127.0.0.1:8789/v1/helm':
            raise ValueError('Only the local HELM read endpoint is allowed')
        self.key_file, self.url = Path(key_file), url
        self.lock, self.stop = threading.Lock(), threading.Event()
        self.value = {'version': 1, 'sources': [], 'combination': None,
                      'liveForMs': 5000, 'fetchedAtEpochMs': None,
                      'upstreamAvailable': False, 'error': 'STARTING'}
        self.opener = build_opener(ProxyHandler({}), NoRedirect())

    def poll(self):
        try:
            key = self.key_file.read_text().strip()
            if not key or '\n' in key or '\r' in key:
                raise ValueError('Invalid key')
            request = Request(self.url, headers={'X-No5-Gateway-Key': key, 'Accept': 'application/json'})
            with self.opener.open(request, timeout=2) as response:
                raw = response.read(MAX_BYTES + 1)
            if len(raw) > MAX_BYTES:
                raise ValueError('Oversized response')
            value = normalize(json.loads(raw), int(time.time() * 1000))
            with self.lock:
                self.value = value
        except Exception:
            # Never log response bodies, coordinates, key or URLs with credentials.
            with self.lock:
                self.value.update(upstreamAvailable=False, error='GATEWAY_UNAVAILABLE')

    def snapshot(self):
        with self.lock:
            value = copy.deepcopy(self.value)
        now = int(time.time() * 1000)
        value['serverNowEpochMs'] = now
        if value['fetchedAtEpochMs'] is not None and now - value['fetchedAtEpochMs'] > 3500:
            value.update(upstreamAvailable=False, error='GATEWAY_UNAVAILABLE')
        return value

    def run(self):
        while not self.stop.is_set():
            start = time.monotonic()
            self.poll()
            self.stop.wait(max(0, 1 - (time.monotonic() - start)))


def handler(collector, network):
    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *_):
            pass

        def do_GET(self):
            self.connection.settimeout(5)
            peer = ipaddress.ip_address(self.client_address[0])
            if not peer.is_loopback and peer not in network:
                self.send_error(403)
                return
            name = self.path.split('?', 1)[0]
            if name == '/api/positions':
                data = json.dumps(collector.snapshot(), allow_nan=False).encode()
                content_type = 'application/json'
            else:
                files = {'/': ('index.html', 'text/html; charset=utf-8'),
                         '/app.js': ('app.js', 'text/javascript'),
                         '/model.js': ('model.js', 'text/javascript'),
                         '/style.css': ('style.css', 'text/css'),
                         '/vendor/leaflet.js': ('vendor/leaflet.js', 'text/javascript'),
                         '/vendor/leaflet.css': ('vendor/leaflet.css', 'text/css')}
                if name not in files:
                    self.send_error(404)
                    return
                file, content_type = files[name]
                data = (ROOT / 'static' / file).read_bytes()
            self.send_response(200)
            self.send_header('Content-Type', content_type)
            self.send_header('Content-Length', str(len(data)))
            self.send_header('Cache-Control', 'no-store')
            self.send_header('X-Content-Type-Options', 'nosniff')
            self.send_header('Referrer-Policy', 'strict-origin-when-cross-origin')
            self.send_header('Content-Security-Policy', "default-src 'none'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data: https://tile.openstreetmap.org; connect-src 'self'; base-uri 'none'; frame-ancestors 'none'")
            self.end_headers()
            self.wfile.write(data)
    return Handler


class Server(ThreadingHTTPServer):
    daemon_threads = True
    request_queue_size = 8
    def __init__(self, *args):
        self.slots = threading.BoundedSemaphore(8)
        super().__init__(*args)

    def process_request(self, request, client_address):
        if not self.slots.acquire(blocking=False):
            self.shutdown_request(request)
            return
        super().process_request(request, client_address)

    def process_request_thread(self, request, client_address):
        try:
            request.settimeout(5)
            super().process_request_thread(request, client_address)
        finally:
            self.slots.release()


def lan_interface(interface):
    rows = json.loads(subprocess.check_output(['/usr/sbin/ip', '-j', '-4', 'addr', 'show', 'dev', interface], timeout=3))
    addresses = [a for row in rows for a in row['addr_info'] if a['scope'] == 'global']
    if len(addresses) != 1 or not ipaddress.ip_address(addresses[0]['local']).is_private:
        raise ValueError('Exactly one private LAN IPv4 address required')
    addr = addresses[0]
    return addr['local'], ipaddress.ip_network(f"{addr['local']}/{addr['prefixlen']}", strict=False)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--interface', default='wlan0')
    parser.add_argument('--port', type=int, default=8798)
    parser.add_argument('--key-file', type=Path)
    args = parser.parse_args()
    key_file = args.key_file or Path(os.environ['CREDENTIALS_DIRECTORY']) / 'gateway-key'
    host, network = lan_interface(args.interface)
    collector = Collector(key_file)
    thread = threading.Thread(target=collector.run, daemon=True)
    server = Server((host, args.port), handler(collector, network))
    for sig in (signal.SIGTERM, signal.SIGINT):
        signal.signal(sig, lambda *_: threading.Thread(target=server.shutdown, daemon=True).start())
    thread.start()
    try:
        server.serve_forever(poll_interval=0.25)
    finally:
        collector.stop.set()
        server.server_close()
        thread.join(3)


if __name__ == '__main__':
    main()
