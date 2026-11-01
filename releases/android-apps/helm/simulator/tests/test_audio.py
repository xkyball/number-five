import copy
import json
from pathlib import Path
import threading
import unittest
from urllib.error import HTTPError
from urllib.request import Request, urlopen

from simulator.audio import AudioSimulator, TRACKS, PRESET, PRESET_ID
from simulator.server import SimulationServer, Simulation
from simulator.tests.test_simulator import DisabledWeather


class Clock:
    seconds = 0.
    def monotonic(self): return self.seconds
    def wall(self): return 1770000000000 + round(self.seconds * 1000)


class AudioTests(unittest.TestCase):
    def setUp(self):
        self.clock = Clock()
        self.audio = AudioSimulator(self.clock.monotonic, self.clock.wall, lambda: 'audio-fixture-server')
        self.sequence = 0

    def command(self, command, **overrides):
        self.sequence += 1
        payload = {'version': 1, 'kind': 'audio-command', 'sessionId': 'client', 'requestId': 'client:' + str(self.sequence),
                   'serverSessionId': self.audio.server_session_id, 'expectedRevision': self.audio.revision, 'command': command}
        payload.update(overrides)
        return self.audio.command(payload)

    def test_wire_fixture_and_no_hardware_or_external_artwork(self):
        wire = self.audio.get()[1]
        self.assertEqual(json.loads((Path(__file__).parent / 'fixtures/audio-v1.json').read_text()), wire)
        self.assertEqual('SIMULATED', wire['sourceKind'])
        self.assertFalse(wire['dsp']['meters']['routingVerified'])
        self.assertEqual({'tidal-lines', 'harbor-lights', 'orange-current'}, {t['artworkId'] for t in TRACKS})
        self.assertNotIn('https:', json.dumps(wire))

    def test_front_controls_and_explicit_meter_groups_use_source_samples(self):
        state = self.audio.get()[1]
        self.assertEqual([0, 1], state['dsp']['meters']['groups']['FRONT'])
        self.assertEqual({'MASTER', 'SUB', 'REAR', 'AUX3', 'AUX4'}, set(state['dsp']['faders']))
        self.assertEqual('VERIFIED', self.command({'type':'fader','channel':'FRONT','valueDb':-3})['status'])
        self.assertEqual(-3, self.audio.get()[1]['dsp']['zoneControls']['FRONT']['db'])
        self.assertEqual('VERIFIED', self.command({'type':'mute','channel':'FRONT','muted':True})['status'])
        state=self.audio.get()[1]
        self.assertTrue(all(m['peakDb']==-160 for m in state['dsp']['meters']['channels'][:2]))
        self.assertTrue(all(m['peakDb']>-160 for m in state['dsp']['meters']['channels'][2:]))
        self.audio.set_offline('DSP', True)
        self.assertEqual('REJECTED',self.command({'type':'fader','channel':'FRONT','valueDb':0})['status'])

    def test_reads_do_not_advance_or_refresh_and_producer_crosses_track_boundary(self):
        before = self.audio.get()[1]
        self.clock.seconds = 250.
        self.assertEqual(before, self.audio.get()[1])
        self.audio.sample()
        after = self.audio.get()[1]
        self.assertEqual('track-02', after['media']['trackId'])
        self.assertEqual(3000, after['media']['positionMs'])
        self.assertGreater(after['revision'], before['revision'])
        self.assertGreater(after['timestampEpochMs'], before['timestampEpochMs'])

    def test_play_pause_seek_next_previous_shuffle_repeat(self):
        self.assertEqual('VERIFIED', self.command({'type': 'transport', 'action': 'PAUSE'})['status'])
        self.clock.seconds = 5; self.audio.sample()
        self.assertEqual(0, self.audio.get()[1]['media']['positionMs'])
        self.command({'type': 'transport', 'action': 'SEEK', 'positionMs': 12000})
        self.assertEqual(12000, self.audio.get()[1]['media']['positionMs'])
        self.command({'type': 'transport', 'action': 'NEXT'})
        self.assertEqual('track-02', self.audio.get()[1]['media']['trackId'])
        self.command({'type': 'transport', 'action': 'PREVIOUS'})
        self.assertEqual('track-01', self.audio.get()[1]['media']['trackId'])
        self.command({'type': 'transport', 'action': 'SHUFFLE'})
        self.command({'type': 'transport', 'action': 'NEXT'})
        self.assertEqual('track-03', self.audio.get()[1]['media']['trackId'])
        self.command({'type': 'transport', 'action': 'REPEAT'})
        self.command({'type': 'transport', 'action': 'PLAY'})
        self.clock.seconds += 287; self.audio.sample()
        self.assertEqual('track-03', self.audio.get()[1]['media']['trackId'])
        self.assertEqual(1000, self.audio.get()[1]['media']['positionMs'])

    def test_all_faders_mute_limits_and_preset(self):
        for channel in ('MASTER', 'SUB', 'REAR', 'AUX3', 'AUX4'):
            self.assertEqual('VERIFIED', self.command({'type': 'fader', 'channel': channel, 'valueDb': 200})['status'])
            self.assertEqual(-6 if channel == 'MASTER' else 0, self.audio.get()[1]['dsp']['faders'][channel]['db'])
            self.command({'type': 'mute', 'channel': channel, 'muted': True})
            self.assertTrue(self.audio.get()[1]['dsp']['faders'][channel]['muted'])
        self.assertEqual('VERIFIED', self.command({'type': 'preset', 'id': PRESET_ID})['status'])

    def test_replay_wrong_generation_and_stale_revision_never_apply(self):
        first = self.command({'type': 'transport', 'action': 'NEXT'})
        self.assertEqual('VERIFIED', first['status'])
        replay = self.command({'type': 'transport', 'action': 'NEXT'}, requestId='client:1')
        self.assertEqual('REJECTED', replay['status'])
        wrong = self.command({'type': 'transport', 'action': 'NEXT'}, serverSessionId='old-server')
        self.assertEqual('REJECTED', wrong['status'])
        old = self.command({'type': 'transport', 'action': 'NEXT'}, expectedRevision=0)
        self.assertEqual('REJECTED', old['status'])
        self.assertEqual('track-02', self.audio.get()[1]['media']['trackId'])

    def test_faults_offline_failure_and_recovery_do_not_replay(self):
        for target, command in [('SOURCE', {'type': 'transport', 'action': 'NEXT'}), ('DSP', {'type': 'fader', 'channel': 'MASTER', 'valueDb': -30}), ('USB', {'type': 'mute', 'channel': 'SUB', 'muted': True})]:
            self.audio.set_offline(target)
            self.assertEqual('REJECTED', self.command(command)['status'])
            self.audio.set_offline(target, False)
        self.audio.fail_next_write()
        self.assertEqual('REJECTED', self.command({'type': 'transport', 'action': 'NEXT'})['status'])
        self.audio.set_fault('stale'); self.clock.seconds = 20; self.audio.sample()
        stale = self.audio.get()[1]
        self.assertEqual('STALE', stale['audioSource']['quality']['status'])
        self.assertNotEqual(self.clock.wall(), stale['timestampEpochMs'])
        self.assertEqual('REJECTED', self.command({'type': 'transport', 'action': 'NEXT'})['status'])
        self.audio.set_fault('failed'); self.assertEqual(503, self.audio.get()[0])
        self.audio.set_fault('none')
        self.assertEqual('track-01', self.audio.get()[1]['media']['trackId'])
        self.assertEqual(-24, self.audio.get()[1]['dsp']['faders']['MASTER']['db'])
        self.assertFalse(self.audio.get()[1]['dsp']['faders']['SUB']['muted'])

    def test_invalid_commands_are_atomic(self):
        before = self.audio.get()[1]
        for command in ({'type': 'source'}, {'type': 'preset', 'id': 'unknown'}, {'type': 'fader', 'channel': 'MASTER', 'valueDb': True}, {'type': 'transport', 'action': 'SEEK', 'positionMs': 1.5}, {'type': 'transport', 'action': 'PLAY', 'surprise': 1}):
            with self.assertRaises(ValueError): self.command(command)
            self.assertEqual(before, self.audio.get()[1])

    def test_long_sleep_deterministic_and_bounded(self):
        self.clock.seconds = 10**9
        self.audio.sample()
        snapshot = self.audio.get()[1]
        self.assertLess(snapshot['media']['positionMs'], snapshot['media']['durationMs'])


class AudioHttpTests(unittest.TestCase):
    def test_actual_gateway_audio_commands_faults_and_reconnect(self):
        server = SimulationServer(('127.0.0.1', 0),Simulation(weather=DisabledWeather()))
        worker = threading.Thread(target=server.serve_forever, daemon=True); worker.start()
        base = 'http://127.0.0.1:' + str(server.server_address[1])
        def request(path, body=None):
            req = Request(base + path, data=json.dumps(body).encode() if body is not None else None, headers={'Content-Type': 'application/json'})
            try: response = urlopen(req, timeout=2)
            except HTTPError as error: response = error
            with response: return response.status, json.load(response)
        try:
            code, initial = request('/v1/audio'); self.assertEqual(200, code)
            command = {'version': 1, 'kind': 'audio-command', 'sessionId': 'http-test', 'requestId': 'http-test:1', 'serverSessionId': initial['serverSessionId'], 'expectedRevision': initial['revision'], 'command': {'type': 'transport', 'action': 'NEXT'}}
            code, ack = request('/v1/audio/command', command)
            self.assertEqual(200, code); self.assertEqual('VERIFIED', ack['status'])
            self.assertEqual('track-02', request('/v1/audio')[1]['media']['trackId'])
            self.assertEqual('REJECTED', request('/v1/audio/command', command)[1]['status'])
            asynchronous = dict(command, requestId='http-test:2', expectedRevision=ack['state']['revision'])
            code, receipt = request('/v1/audio/commands', asynchronous)
            self.assertEqual(202, code)
            for _ in range(100):
                code, result = request('/v1/audio/commands/' + receipt['commandId'])
                if result['status'] not in ('ACCEPTED', 'RUNNING'):
                    break
                threading.Event().wait(.01)
            self.assertEqual(200, code)
            self.assertEqual('APPLIED', result['status'])
            self.assertEqual('track-03', result['state']['media']['trackId'])
            self.assertEqual('APPLIED', request('/v1/audio/commands', asynchronous)[1]['status'])
            request('/v1/simulation', {'command': 'audioFault', 'value': 'failed'})
            self.assertEqual(503, request('/v1/audio')[0])
            self.assertEqual(200, request('/v1/helm')[0])
            request('/v1/simulation', {'command': 'audioFault', 'value': 'none'})
            self.assertEqual('track-03', request('/v1/audio')[1]['media']['trackId'])
        finally:
            server.shutdown(); server.server_close(); worker.join(timeout=3)
