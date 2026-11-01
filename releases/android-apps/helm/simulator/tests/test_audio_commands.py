import threading
import unittest

from simulator.audio import AudioSimulator
from simulator.audio_commands import AudioCommands


class AudioCommandTests(unittest.TestCase):
    def setUp(self):
        self.audio = AudioSimulator()
        self.actor = AudioCommands(self.audio)
        self.counter = 0

    def request(self, command):
        self.counter += 1
        return dict(version=1, kind='audio-command', sessionId='ring-test', requestId=f'ring-test:{self.counter}',
                    serverSessionId=self.audio.server_session_id, expectedRevision=self.audio.revision, command=command)

    def test_latest_fader_preserves_discrete_fifo_and_verifies_owned_revision_chain(self):
        a = self.actor.submit(self.request(dict(type='fader', channel='MASTER', valueDb=-20)))
        b = self.actor.submit(self.request(dict(type='fader', channel='MASTER', valueDb=-18)))
        mute = self.actor.submit(self.request(dict(type='mute', channel='MASTER', muted=True)))
        barrier = self.actor.submit(self.request(dict(type='transport', action='NEXT')))
        c = self.actor.submit(self.request(dict(type='fader', channel='MASTER', valueDb=-30)))
        self.assertEqual('SUPERSEDED', self.actor.read(a['commandId'])['status'])
        self.assertEqual(b['commandId'], self.actor.read(a['commandId'])['replacementId'])
        self.assertEqual(-24, self.audio.get()[1]['dsp']['faders']['MASTER']['db'])
        for item in (b, mute, barrier, c):
            self.assertTrue(self.actor.process_one())
            self.assertEqual('APPLIED', self.actor.read(item['commandId'])['status'])
        state = self.audio.get()[1]
        self.assertEqual(-30, state['dsp']['faders']['MASTER']['db'])
        self.assertTrue(state['dsp']['faders']['MASTER']['muted'])
        self.assertEqual('track-02', state['media']['trackId'])

    def test_full_discrete_queue_is_bounded_and_retries_cannot_apply_twice(self):
        request = self.request(dict(type='transport', action='NEXT'))
        admitted = self.actor.submit(request)
        for _ in range(63):
            self.actor.submit(self.request(dict(type='transport', action='NEXT')))
        busy = self.actor.submit(self.request(dict(type='transport', action='NEXT')))
        self.assertEqual('BUSY', busy['reason'])
        self.assertEqual(64, self.actor.size)
        self.assertEqual(admitted, self.actor.submit(request))
        while self.actor.process_one():
            pass
        self.assertEqual(64, self.audio.revision)
        self.assertEqual('APPLIED', self.actor.submit(request)['status'])
        self.assertEqual(64, self.audio.revision)

    def test_blocked_execution_does_not_block_admission_and_unknown_never_replays(self):
        entered, release = threading.Event(), threading.Event()
        calls = []
        def blocked(request):
            calls.append(request)
            entered.set()
            release.wait(2)
            raise TimeoutError('Unclear completion')
        self.audio.command = blocked
        request = self.request(dict(type='transport', action='NEXT'))
        first = self.actor.submit(request)
        worker = threading.Thread(target=self.actor.process_one)
        worker.start()
        try:
            self.assertTrue(entered.wait(1))
            following = self.actor.submit(self.request(dict(type='fader', channel='MASTER', valueDb=-30)))
            self.assertEqual('ACCEPTED', following['status'])
        finally:
            release.set()
            worker.join(2)
        self.assertEqual('UNKNOWN', self.actor.submit(request)['status'])
        self.assertEqual(1, len(calls))

    def test_evicted_receipts_never_reenable_old_requests_and_close_cancels_pending(self):
        first = self.request(dict(type='transport', action='NEXT'))
        for i in range(260):
            self.actor.submit(first if i == 0 else self.request(dict(type='transport', action='NEXT')))
            self.actor.process_one()
        self.assertEqual(256, len(self.actor.results))
        with self.assertRaises(ValueError):
            self.actor.submit(first)
        last = self.actor.submit(self.request(dict(type='transport', action='NEXT')))
        self.actor.close()
        self.assertEqual('CANCELLED', self.actor.read(last['commandId'])['status'])

    def test_foreign_revision_change_is_rejected_and_does_not_override_newer_value(self):
        receipt = self.actor.submit(self.request(dict(type='fader', channel='MASTER', valueDb=-30)))
        external = dict(self.request(dict(type='fader', channel='MASTER', valueDb=-15)), sessionId='external', requestId='external:1')
        self.audio.command(external)
        self.actor.process_one()
        self.assertEqual('REJECTED', self.actor.read(receipt['commandId'])['status'])
        self.assertEqual(-15, self.audio.get()[1]['dsp']['faders']['MASTER']['db'])
