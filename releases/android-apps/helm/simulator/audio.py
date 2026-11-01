"""Deterministic metadata/DSP simulator. No audio output, media URLs or hardware writes."""
import copy
import math
import re
import threading
import time
import uuid

SOURCE = 'NO5_PI_SIMULATOR'
PRESET = 'No 5 - Stereo 4.1'
PRESET_ID = 'sim-stereo-4-1'
CHANNELS = ('MASTER', 'SUB', 'REAR', 'AUX3', 'AUX4')
TRACKS = (
    {'trackId': 'track-01', 'title': 'Tidal Lines', 'artists': ['No5 Studio'], 'album': 'Imaginary Coast', 'durationMs': 247000, 'artworkId': 'tidal-lines'},
    {'trackId': 'track-02', 'title': 'Harbor Lights', 'artists': ['No5 Studio'], 'album': 'Imaginary Coast', 'durationMs': 213000, 'artworkId': 'harbor-lights'},
    {'trackId': 'track-03', 'title': 'Orange Current', 'artists': ['No5 Studio'], 'album': 'Imaginary Coast', 'durationMs': 286000, 'artworkId': 'orange-current'},
)


class AudioSimulator:
    """One producer publishes timestamped snapshots; GET never refreshes stale data.

    Commands name the server generation and expected state revision. Per-client
    monotonic request counters refuse duplicate/replayed commands, including after
    a failed write. The bounded session registry refuses new clients when full.
    """
    def __init__(self, monotonic=time.monotonic, wall_epoch_ms=lambda: time.time_ns() // 1_000_000,
                 session_id_factory=lambda: uuid.uuid4().hex):
        self.monotonic, self.wall_epoch_ms = monotonic, wall_epoch_ms
        self.lock = threading.RLock()
        self.server_session_id = session_id_factory()
        self.revision = 0
        self.last_tick = monotonic()
        self.track_index, self.position_ms = 0, 0.0
        self.playing, self.shuffle, self.repeat = True, False, False
        self.offline = set()
        self.fault = 'none'
        self.fail_write = False
        self.last_requests = {}
        self.faders = {c: {'db': [-24., -6., -9., -12., -12.][i], 'muted': False} for i, c in enumerate(CHANNELS)}
        self.front = {'db': 0., 'muted': False}
        self.cached = self._snapshot()

    def _sync(self):
        current = self.monotonic()
        elapsed_ms = max(0.0, current - self.last_tick) * 1000
        self.last_tick = current
        if not self.playing or 'SOURCE' in self.offline:
            return
        self.position_ms += elapsed_ms
        duration = TRACKS[self.track_index]['durationMs']
        if self.repeat:
            if self.position_ms >= duration:
                self.position_ms %= duration
                self.revision += 1
            return
        # Removing complete playlist cycles keeps long sleep recovery bounded.
        cycle_ms = sum(t['durationMs'] for t in TRACKS)
        if self.position_ms >= cycle_ms:
            self.position_ms %= cycle_ms
            self.revision += 1
        while self.position_ms >= TRACKS[self.track_index]['durationMs']:
            self.position_ms -= TRACKS[self.track_index]['durationMs']
            self.track_index = (self.track_index + (2 if self.shuffle else 1)) % len(TRACKS)
            self.revision += 1

    def _snapshot(self):
        stamp = int(self.wall_epoch_ms())
        source_up, dsp_up, usb_up = (target not in self.offline for target in ('SOURCE', 'DSP', 'USB'))
        quality = lambda live: {'status': 'LIVE' if live else 'LOST', 'timestampEpochMs': stamp, 'source': SOURCE, 'simulated': True}
        media = dict(TRACKS[self.track_index], state='PLAYING' if self.playing and source_up else 'PAUSED',
                     positionMs=int(self.position_ms), shuffle=self.shuffle, repeat=self.repeat)
        levels = []
        for i in range(5):
            channel = 'FRONT' if i < 2 else 'REAR' if i < 4 else 'SUB'
            fader = self.front if channel == 'FRONT' else self.faders[channel]
            base = fader['db'] + self.faders['MASTER']['db']
            peak = max(-160., min(0., base - 3 + 2 * math.sin(self.position_ms / 1700 + i))) if self.playing and source_up and dsp_up and usb_up else -160.
            if fader['muted'] or self.faders['MASTER']['muted']:
                peak = -160.
            levels.append({'index': i, 'peakDb': peak, 'rmsDb': max(-160., peak - 7), 'peakHoldDb': min(0., peak + 1), 'clip': False})
        return {
            'version': 1, 'kind': 'audio', 'source': SOURCE, 'sourceKind': 'SIMULATED', 'simulated': True,
            'serverSessionId': self.server_session_id, 'timestampEpochMs': stamp, 'revision': self.revision,
            'units': {'faders': 'dB', 'meters': 'dBFS', 'position': 'ms'},
            'audioSource': {'connected': source_up, 'active': source_up, 'name': 'NO5 DEMO LIBRARY', 'quality': quality(source_up)},
            'media': media,
            'dsp': {'state': 'RUNNING' if dsp_up else 'ERROR', 'preset': PRESET, 'faders': copy.deepcopy(self.faders),
                    # This mirrors the real gateway extension: labels/order are
                    # catalog data, not inferred zone names.
                    'faderControls': [
                        {'id': channel, 'label': 'Master' if index == 0 else f'Aux {index}', 'index': index,
                         'db': self.faders[channel]['db'], 'muted': self.faders[channel]['muted'],
                         'limit': {'minDb': -60. if channel == 'MASTER' else -18.,
                                   'maxDb': -6. if channel == 'MASTER' else 0., 'stepDb': 1.}}
                        for index, channel in enumerate(CHANNELS)
                    ],
                    'presets': [{'id': PRESET_ID, 'name': PRESET, 'active': True}],
                    'zoneControls': {'FRONT': dict(self.front, limit={'minDb':-18.,'maxDb':0.,'stepDb':1.})},
                    'quality': quality(dsp_up), 'hardwareAvailable': usb_up, 'clippedSamples': 0,
                    'meters': {'channels': levels, 'quality': quality(source_up and dsp_up and usb_up), 'routingVerified': False,
                               'groups': {'MASTER':[0,1,2,3,4], 'FRONT':[0,1], 'REAR':[2,3], 'SUB':[4]}}},
            'capabilities': {'playPause': source_up, 'nextPrevious': source_up, 'seek': source_up,
                             'shuffleRepeat': source_up, 'faderWrite': dsp_up and usb_up, 'mute': dsp_up and usb_up, 'presetWrite': dsp_up and usb_up},
            'authority': 'CAMILLA_DSP_MASTER',
            'limits': {c: {'minDb': -60. if c == 'MASTER' else -18., 'maxDb': -6. if c == 'MASTER' else 0., 'stepDb': 1.} for c in CHANNELS},
            'notice': 'SIMULATED METADATA AND DSP ONLY; NO SOUND OUTPUT OR HARDWARE ROUTING',
        }

    def sample(self):
        with self.lock:
            self._sync()
            if self.fault == 'none':
                self.cached = self._snapshot()

    def get(self):
        with self.lock:
            if self.fault == 'failed':
                return 503, {'error': 'audio simulation fault', 'simulated': True}
            result = copy.deepcopy(self.cached)
            if self.fault == 'stale':
                for quality in (result['audioSource']['quality'], result['dsp']['quality'], result['dsp']['meters']['quality']):
                    quality['status'] = 'STALE'
            return 200, result

    def set_fault(self, value):
        if value not in ('none', 'stale', 'failed'):
            raise ValueError('audio fault must be none, stale or failed')
        with self.lock:
            self.fault = value
            self.sample()

    def set_offline(self, target, offline=True):
        if target not in ('SOURCE', 'DSP', 'USB') or not isinstance(offline, bool):
            raise ValueError('invalid audio offline target/state')
        with self.lock:
            self._sync()
            self.offline.add(target) if offline else self.offline.discard(target)
            self.revision += 1
            self.cached = self._snapshot()

    def fail_next_write(self):
        with self.lock:
            self.fail_write = True

    @staticmethod
    def validate_envelope(payload):
        if not isinstance(payload, dict) or set(payload) != {'version', 'kind', 'sessionId', 'requestId', 'serverSessionId', 'expectedRevision', 'command'}:
            raise ValueError('invalid audio command envelope')
        session, request = payload['sessionId'], payload['requestId']
        if payload['version'] != 1 or isinstance(payload['version'], bool) or payload['kind'] != 'audio-command':
            raise ValueError('unsupported audio command version/kind')
        if not isinstance(session, str) or not re.fullmatch(r'[A-Za-z0-9_-]{1,100}', session):
            raise ValueError('invalid client session ID')
        if not isinstance(request, str) or not request.startswith(session + ':') or not re.fullmatch(r'[1-9][0-9]{0,15}', request[len(session) + 1:]):
            raise ValueError('request ID must contain session and monotonic counter')
        sequence = int(request[len(session) + 1:])
        expected = payload['expectedRevision']
        if isinstance(expected, bool) or not isinstance(expected, int) or expected < 0:
            raise ValueError('invalid expected revision')
        command = payload['command']
        AudioSimulator._validate(command)
        return session, request, sequence, expected, command

    def command(self, payload):
        session, request, sequence, expected, command = self.validate_envelope(payload)
        with self.lock:
            def reply(status, message):
                state = copy.deepcopy(self.cached)
                if self.fault != 'none':
                    for quality in (state['audioSource']['quality'], state['dsp']['quality'], state['dsp']['meters']['quality']):
                        quality['status'] = 'STALE' if self.fault == 'stale' else 'LOST'
                return {'version': 1, 'kind': 'audio-ack', 'source': SOURCE, 'sourceKind': 'SIMULATED', 'simulated': True,
                        'sessionId': session, 'requestId': request, 'serverSessionId': self.server_session_id,
                        'revision': state['revision'], 'status': status, 'message': message, 'state': state}
            if payload['serverSessionId'] != self.server_session_id:
                return reply('REJECTED', 'Server session changed; refresh before another command')
            if session not in self.last_requests and len(self.last_requests) >= 64:
                return reply('REJECTED', 'Simulator client session limit reached; restart simulator')
            if sequence <= self.last_requests.get(session, 0):
                return reply('REJECTED', 'Duplicate or out-of-order command; no replay')
            self.last_requests[session] = sequence
            if self.fault != 'none':
                return reply('REJECTED', 'Audio is offline or stale; no command applied')
            self._sync()
            self.cached = self._snapshot()
            if expected != self.revision:
                return reply('REJECTED', 'State revision changed; refresh before another command')
            affected = {'SOURCE'} if command['type'] == 'transport' else {'DSP', 'USB'}
            if self.offline & affected:
                return reply('REJECTED', 'Capability offline; no command applied')
            if self.fail_write:
                self.fail_write = False
                return reply('REJECTED', 'Injected simulated write failure; no command applied')
            kind = command['type']
            if kind == 'fader':
                channel = command['channel']
                low, high = (-60., -6.) if channel == 'MASTER' else (-18., 0.)
                (self.front if channel == 'FRONT' else self.faders[channel])['db'] = max(low, min(high, math.floor(command['valueDb'] + .5)))
            elif kind == 'mute':
                (self.front if command['channel'] == 'FRONT' else self.faders[command['channel']])['muted'] = command['muted']
            elif kind == 'transport':
                action = command['action']
                if action in ('PLAY', 'PAUSE'):
                    self.playing = action == 'PLAY'
                elif action in ('NEXT', 'PREVIOUS'):
                    direction = (2 if self.shuffle else 1) * (1 if action == 'NEXT' else -1)
                    self.track_index = (self.track_index + direction) % len(TRACKS)
                    self.position_ms = 0
                elif action == 'SEEK':
                    self.position_ms = max(0, min(TRACKS[self.track_index]['durationMs'], command['positionMs']))
                elif action == 'SHUFFLE':
                    self.shuffle = not self.shuffle
                elif action == 'REPEAT':
                    self.repeat = not self.repeat
            # Preset is an explicitly validated simulator no-op, still acknowledged.
            self.revision += 1
            self.cached = self._snapshot()
            return reply('VERIFIED', 'SIMULATED command verified; no hardware write')

    @staticmethod
    def _validate(command):
        if not isinstance(command, dict):
            raise ValueError('command must be an object')
        kind = command.get('type')
        fields = {'fader': {'type', 'channel', 'valueDb'}, 'mute': {'type', 'channel', 'muted'},
                  'preset': {'type', 'id'}, 'transport': {'type', 'action'}}
        expected = fields.get(kind)
        if expected is None:
            raise ValueError('unsupported audio command')
        if kind == 'transport' and command.get('action') == 'SEEK':
            expected = expected | {'positionMs'}
        if set(command) != expected:
            raise ValueError('unexpected/missing audio command parameter')
        if kind in ('fader', 'mute') and command['channel'] not in CHANNELS + ('FRONT',):
            raise ValueError('unknown fader')
        if kind == 'fader' and (isinstance(command['valueDb'], bool) or not isinstance(command['valueDb'], (int, float)) or not math.isfinite(command['valueDb'])):
            raise ValueError('fader must be finite')
        if kind == 'mute' and not isinstance(command['muted'], bool):
            raise ValueError('mute must be boolean')
        if kind == 'preset' and command['id'] != PRESET_ID:
            raise ValueError('unvalidated preset')
        if kind == 'transport':
            if command['action'] not in ('PLAY', 'PAUSE', 'NEXT', 'PREVIOUS', 'SEEK', 'SHUFFLE', 'REPEAT'):
                raise ValueError('unknown transport action')
            if command['action'] == 'SEEK' and (isinstance(command['positionMs'], bool) or not isinstance(command['positionMs'], int) or abs(command['positionMs']) > 2**53):
                raise ValueError('invalid seek position')
