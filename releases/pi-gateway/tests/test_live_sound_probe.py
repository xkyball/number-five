"""Bounded operator probe logic; never reaches Lab/Pi or provisions a key."""
import asyncio
import copy
from pathlib import Path
import sys
import tempfile
from types import SimpleNamespace
import unittest
import aiohttp

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'tools'))
from probe_live_sound import ObservedFaderStream, ProbeFailure, retryable_fader_conflict, control_projection, changed_paths
from cleanup_live_sound_fader import guard_cleanup


def state(revision=1):
    return {'kind': 'audio', 'serverSessionId': 'test-server', 'revision': revision,
            'dsp': {'state': 'RUNNING', 'preset': 'fixture', 'quality': {'timestampEpochMs': 1, 'status': 'LIVE'},
                    'faders': {'MASTER': {'db': -10, 'muted': False}, 'AUX3': {'db': -5, 'muted': False}}},
            'capabilities': {'faderWrite': True}, 'media': {'title': 'fixture', 'positionMs': 0}}


class Socket:
    def __init__(self): self.incoming = asyncio.Queue(); self.sent = []; self.closed = False
    def __aiter__(self): return self
    async def __anext__(self):
        value = await self.incoming.get()
        if value is None: raise StopAsyncIteration
        return SimpleNamespace(type=aiohttp.WSMsgType.TEXT, data=__import__('json').dumps(value))
    async def send_json(self, value): self.sent.append(value)
    async def close(self): self.closed = True


class ProbeStreamTest(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.directory = tempfile.TemporaryDirectory(); self.socket = Socket()
        self.stream = ObservedFaderStream(self.socket, Path(self.directory.name))
        await self.socket.incoming.put(state()); await self.stream.state()
    async def asyncTearDown(self): await self.stream.close(); self.directory.cleanup()
    async def test_idle_publications_update_revision_and_verified_receipt_persists(self):
        await self.socket.incoming.put(state(2)); await asyncio.sleep(0)
        self.assertEqual((await self.stream.state())['revision'], 2)
        request = {'sessionId': 'test', 'requestId': 'test:1', 'serverSessionId': 'test-server',
                   'command': {'channel': 'AUX3', 'valueDb': -6}}
        task = asyncio.create_task(self.stream.write(request)); await asyncio.sleep(0)
        confirmed = state(3); confirmed['dsp']['faders']['AUX3']['db'] = -6
        await self.socket.incoming.put({'kind': 'audio-ack', 'sessionId': 'test', 'requestId': 'test:1',
            'serverSessionId': 'test-server', 'status': 'VERIFIED', 'state': confirmed})
        ack, elapsed = await task
        self.assertEqual(ack['status'], 'VERIFIED'); self.assertLess(elapsed, 250)
        self.assertEqual(self.stream.last_verified, confirmed)
        self.assertTrue((Path(self.directory.name) / 'fader-verified-1.json').is_file())
        self.assertEqual(len(self.socket.sent), 1)
        # A stale periodic message queued before the ACK never rolls it back.
        await self.socket.incoming.put(state(2)); await asyncio.sleep(0)
        self.assertEqual((await self.stream.state())['revision'], 3)
    async def test_malformed_or_lost_ack_never_replays(self):
        request = {'sessionId': 'test', 'requestId': 'test:1', 'serverSessionId': 'test-server',
                   'command': {'channel': 'AUX3', 'valueDb': -6}}
        task = asyncio.create_task(self.stream.write(request)); await asyncio.sleep(0)
        await self.socket.incoming.put({'kind': 'audio-ack', 'sessionId': 'wrong', 'requestId': 'test:1'})
        with self.assertRaises(ProbeFailure): await task
        self.assertEqual(len(self.socket.sent), 1); self.assertIsNone(self.stream.last_verified)
    def test_only_exact_known_no_effect_conflict_allows_new_cas(self):
        before = state(); after = state(2)
        ack = {'status': 'REJECTED', 'message': 'AUDIO state changed; command was not sent', 'state': after}
        self.assertTrue(retryable_fader_conflict(ack, before))
        for changed in ({**ack, 'status': 'UNKNOWN'}, {**ack, 'message': 'Unknown completion'}):
            self.assertFalse(retryable_fader_conflict(changed, before))
        after['dsp']['faders']['AUX3']['db'] = -6
        self.assertFalse(retryable_fader_conflict(ack, before))
    def test_projection_names_changed_control_fields_without_values(self):
        before = state(); after = copy.deepcopy(before); after['dsp']['quality']['timestampEpochMs'] = 2
        self.assertEqual(changed_paths(control_projection(before), control_projection(after)), [])
        after['media']['title'] = 'changed'
        self.assertEqual(changed_paths(control_projection(before), control_projection(after)), ['media.title'])


class OwnCleanupGuardTest(unittest.TestCase):
    def test_global_revision_is_not_foreign_fader_evidence_but_full_vector_is_guarded(self):
        original = state(); observed = state(30); expected = copy.deepcopy(original['dsp']['faders'])
        expected['AUX3']['db'] = -6; observed['dsp']['faders'] = copy.deepcopy(expected)
        native = {'sourceGeneration': 'source', 'configGeneration': 'config', 'previewLease': None,
                  'preparedRevision': 4, 'appliedRevision': 4, 'preset': {'value': 1}, 'calibration': {'value': 2}}
        baseline = {'audio': original, 'native': copy.deepcopy(native)}
        self.assertTrue(all(guard_cleanup(baseline, observed, native, expected).values()))
        observed['dsp']['faders']['MASTER']['db'] = -11
        with self.assertRaises(ProbeFailure): guard_cleanup(baseline, observed, native, expected)
