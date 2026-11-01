"""Replay timing facts from attempt 5; samples are synthetic, never a soak proof."""
from copy import deepcopy
from dataclasses import replace
import json
import unittest

from no5_pi_gateway.configuration import GatewayConfig
from no5_pi_gateway.sonar_context import parse_atomic_echogram
from no5_pi_gateway.state import GatewayState
from test_sonar_context import context_case, encode, modified_m1, atomic_status
from test_state import frame as legacy_frame


class ExplicitClockContextTest(unittest.TestCase):
    def setup_source(self):
        self.root, self.raw = context_case()
        value = atomic_status(self.root)
        value['host_package']['version'] = '0.4.25'
        self.state = GatewayState(GatewayConfig(producer='m1', upstream_base_url='http://127.0.0.1:8787'),
                                  now=lambda: 1792417381400)
        self.state.begin_connection()
        self.state.observe_status(value)
        self.status = value

    def ping(self, ident, stamp_ms, sequence):
        root = deepcopy(self.root)
        row = root['published_ping']
        row['ping_id'] = row['display_window']['ping_id'] = str(ident)
        row['time_utc_ns'] = str(stamp_ms * 1_000_000)
        root['publication_sequence'] = str(sequence)
        envelope = parse_atomic_echogram(encode(root, modified_m1(self.raw, ping=ident, utc=stamp_ms*1_000_000)))
        return replace(envelope.frame, host_package_version='0.4.25', publication=envelope.publication,
                       publication_sequence=sequence, display_selection=self.state.display_selection)

    def test_recorded_37ms_regression_preserves_explicit_epoch_and_original_bytes(self):
        self.setup_source()
        self.state.accept_ping(self.ping(433769, 1792417381335, 1))
        generation = self.state.generation
        regressed = self.ping(433770, 1792417381298, 2)
        wire = self.state.accept_ping(regressed)
        n = int.from_bytes(wire[:4], 'little')
        metadata = json.loads(wire[4:4+n])
        self.assertEqual(metadata['generation'], generation)
        self.assertEqual(metadata['observedAtEpochMs'], 1792417381298)
        self.assertEqual(metadata['pingId'], 433770)
        self.assertEqual(wire[4+n:], regressed.samples)
        self.state.accept_ping(self.ping(433771, 1792417381400, 3))
        self.assertEqual(self.state.generation, generation)

    def test_real_segment_change_still_retires_evidence(self):
        self.setup_source()
        self.state.accept_ping(self.ping(433769, 1792417381335, 1))
        generation = self.state.generation
        self.status['session']['segment_id'] = '22222222-2222-4222-8222-222222222222'
        self.state.observe_status(self.status)
        self.assertGreater(self.state.generation, generation)
        self.assertIsNone(self.state._last_ping)

    def test_large_regression_stays_stale_instead_of_retiming(self):
        self.setup_source()
        self.state.accept_ping(self.ping(433769, 1792417381335, 1))
        generation = self.state.generation
        self.state.accept_ping(self.ping(433770, 1792417371000, 2))
        self.assertEqual(self.state.generation, generation)
        self.assertEqual(self.state.snapshot()['status'], 'STALE')

    def test_legacy_timestamp_only_regression_still_starts_generation(self):
        self.setup_source()
        self.state = GatewayState(GatewayConfig(producer='m1', upstream_base_url='http://127.0.0.1:8787'))
        self.state.begin_connection()
        self.state.observe_status({'source': 'pico', 'state': 'RUNNING'})
        self.state.accept_ping(legacy_frame(433769, 1792417381335))
        generation = self.state.generation
        self.state.accept_ping(legacy_frame(433770, 1792417381298))
        self.assertEqual(self.state.generation, generation + 1)

