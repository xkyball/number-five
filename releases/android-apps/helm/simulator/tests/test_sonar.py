import base64
import http.client
import json
import socket
import struct
import threading
import unittest

from simulator.server import Simulation, SimulationServer
from simulator.tests.test_simulator import issue, DisabledWeather
from simulator.sonar import SonarSimulator, decode_ping, encode_ping
from simulator.sonar_transport import client_control, frame


class Clock:
    def __init__(self): self.t = 0.0
    def mono(self): return self.t
    def wall(self): return 1700000000000 + round(self.t * 1000)


class SonarTest(unittest.TestCase):
    def setUp(self):
        self.clock = Clock()
        self.sim = Simulation(monotonic=self.clock.mono, wall_epoch_ms=self.clock.wall, running=False)
        self.sonar = SonarSimulator(self.sim, session_factory=lambda: "sonar-session")
    def command(self, name, request=1):
        s = self.sonar.state()
        return dict(version=1, serverSessionId=s['serverSessionId'], generation=s['generation'],
                    expectedRevision=s['revision'], clientId='test', requestId=request, command=name)
    def test_depth_and_binary_roundtrip(self):
        self.sonar.sample()
        h, samples = decode_ping(self.sonar.latest)
        self.assertEqual(len(samples), 1024)
        self.assertEqual(h['sourceKind'], 'SIMULATED')
        self.assertAlmostEqual(h['bottom']['bin'] * .03, self.sim.engine.helm_values()['depth'])
        self.assertLessEqual(abs(max(range(1024), key=samples.__getitem__) - h['bottom']['bin']), 1)
        self.assertEqual(encode_ping(h, list(samples)), self.sonar.latest)
    def test_reads_do_not_publish_and_pause_keeps_acquiring(self):
        self.sonar.sample(); before = self.sonar.latest
        self.clock.t += .2
        self.sonar.state(); self.sonar.stream_snapshot()
        self.assertIs(before, self.sonar.latest)
        self.sonar.sample()
        h, _ = decode_ping(self.sonar.latest)
        self.assertEqual(h['simulation']['elapsedSeconds'], 0)
        self.assertEqual(h['observedAtEpochMs'], self.clock.wall())
        self.assertGreater(h['pingId'], 0)
    def test_ack_stale_and_no_replay(self):
        cmd = self.command('stop')
        self.assertEqual(self.sonar.command(cmd)['outcome'], 'VERIFIED')
        self.assertEqual(self.sonar.command(cmd)['reason'], 'STALE_REQUEST')
        self.assertFalse(self.sonar.state()['running'])
        stale = self.command('start', 2); stale['expectedRevision'] -= 1
        self.assertEqual(self.sonar.command(stale)['reason'], 'STALE_REVISION')
        stale['expectedRevision'] += 1
        self.assertEqual(self.sonar.command(stale)['reason'], 'STALE_REQUEST')
    def test_config_validates_atomically_and_rejects_bools(self):
        before = self.sonar.state()
        with self.assertRaises(ValueError): self.sonar.configure({'targets': True, 'cadenceHz': True})
        self.assertEqual(before, self.sonar.state())
        cmd = self.command('configure'); cmd['settings'] = {'calibrated': False}
        ack = self.sonar.configure_command(cmd)
        self.assertEqual(ack['outcome'], 'VERIFIED')
        self.assertGreater(ack['generation'], before['generation'])
        self.assertFalse(ack['state']['calibration']['valid'])
    def test_generation_reset_and_fault(self):
        self.sonar.sample(); old = self.sonar.state()['generation']
        issue(self.sim,'reset')
        self.sonar.sample()
        self.assertGreater(self.sonar.state()['generation'], old)
        before = self.sonar.latest
        self.sonar.configure({'fault': 'stale'})
        self.clock.t += 20; self.sonar.sample()
        self.assertIs(self.sonar.latest, before)
        self.assertEqual(self.sonar.state()['status'], 'STALE')
    def test_stop_suppresses_acquisition(self):
        self.sonar.sample(); before = self.sonar.latest
        self.sonar.command(self.command('stop'))
        self.clock.t += 2; self.sonar.sample()
        self.assertIs(self.sonar.latest, before)
    def test_malformed_lengths(self):
        for data in (b'', b'\xff'*4, struct.pack('<I', 4097) + b' '*5000):
            with self.assertRaises(ValueError): decode_ping(data)
    def test_masked_control_frames(self):
        mask = b'abcd'; payload = b'ping'
        wire = b'\x89\x84' + mask + bytes(x ^ mask[i % 4] for i,x in enumerate(payload))
        self.assertEqual(client_control(wire), (9, payload, 10))
        self.assertIsNone(client_control(wire[:8]))
        for bad in (b'\x89\x00', b'\x81\x80', b'\x09\x80', b'\xc9\x80', b'\x89\xfe'):
            with self.assertRaises(ValueError): client_control(bad)


class StreamTest(unittest.TestCase):
    def setUp(self):
        self.server = SimulationServer(('127.0.0.1', 0),Simulation(weather=DisabledWeather()))
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
    def tearDown(self):
        self.server.shutdown(); self.server.server_close(); self.thread.join(3)
    def connect(self, origin=None):
        sock = socket.create_connection(self.server.server_address, timeout=2)
        port = self.server.server_address[1]
        request = (f'GET /v1/sonar/stream HTTP/1.1\r\nHost: 127.0.0.1:{port}\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Version: 13\r\nSec-WebSocket-Key: {base64.b64encode(bytes(range(16))).decode()}\r\nSec-WebSocket-Protocol: no5.sonar.v1\r\n')
        if origin: request += 'Origin: ' + origin + '\r\n'
        sock.sendall((request+'\r\n').encode())
        response = b''
        while not response.endswith(b'\r\n\r\n'): response += sock.recv(1)
        return sock, response
    @staticmethod
    def read_frame(sock):
        def read(n):
            result=b''
            while len(result)<n:
                chunk=sock.recv(n-len(result))
                if not chunk: raise EOFError()
                result+=chunk
            return result
        a,b=read(2); n=b&127
        if n==126: n=struct.unpack('!H', read(2))[0]
        return a&15,read(n)
    def test_wire_hello_before_ping_and_http_ack(self):
        sock,response=self.connect()
        try:
            self.assertIn(b'101',response)
            opcode,payload=self.read_frame(sock)
            self.assertEqual(opcode,1)
            state=json.loads(payload)
            self.assertEqual(state['kind'],'sonar.state')
            opcode,payload=self.read_frame(sock)
            self.assertEqual(opcode,2)
            h,samples=decode_ping(payload)
            self.assertEqual(h['serverSessionId'],state['serverSessionId'])
            conn=http.client.HTTPConnection(*self.server.server_address,timeout=2)
            command=dict(version=1,serverSessionId=state['serverSessionId'],generation=state['generation'],expectedRevision=state['revision'],clientId='wire',requestId=1,command='stop')
            conn.request('POST','/v1/sonar/command',json.dumps(command),{'Content-Type':'application/json'})
            ack=json.loads(conn.getresponse().read());conn.close()
            self.assertEqual(ack['outcome'],'VERIFIED')
            self.assertFalse(ack['state']['running'])
        finally: sock.close()
    def test_cross_origin_rejected(self):
        sock,response=self.connect('https://evil.example')
        try: self.assertIn(b'403',response)
        finally:sock.close()

if __name__=='__main__':unittest.main()
