"""Exact .19 read approval over the byte-identical .17 DSP/context fixtures."""
import base64
from copy import deepcopy
from dataclasses import replace
import hashlib
import json
import unittest

from no5_pi_gateway.configuration import GatewayConfig
from no5_pi_gateway.protocol import ProtocolError
from no5_pi_gateway.sonar_context import parse_atomic_echogram
from no5_pi_gateway.sonar_contract import (ANALYSIS_VERSIONS, SOURCE_VERSIONS, DEPTH_RATE_VERSIONS,
    PUBLISHED_VERSIONS, DISPLAY_WINDOW_VERSIONS, ATOMIC_CONTEXT_VERSIONS, IMPULSE_VERSIONS,
    package_version, amplitude)
from no5_pi_gateway.sonar_diagnostics import TRANSPORT_LIVENESS_VERSIONS, projection
from no5_pi_gateway.state import GatewayState
from no5_pi_gateway.upstream import upstream_websocket_url
from test_sonar_0417_read_contract import CASES, FIXTURE, FIXTURE_SHA, status_0417
from test_sonar_context import encode, modified_m1
from test_sonar_published import adapter_for

WHEEL_SHA = 'e73b10e2df48f4c376fc218e354558a43c0fc2515613902dbd474ecc8bdc48fc'


def project_case(case):
    status = status_0417(case, '0.4.19')
    adapter = adapter_for(status)
    adapter._host_package_version = '0.4.19'
    envelope = base64.b64decode(case['context_base64'], validate=True)
    parsed = parse_atomic_echogram(envelope)
    frame = adapter._atomic_frame(parsed)
    if frame is None: raise AssertionError('exact .19 atomic frame was discarded')
    frame = replace(frame, host_package_version='0.4.19', analysis_supported=True,
                    amplitude=adapter._amplitude, selection_generation_hint=parsed.publication.display_generation)
    frame = adapter._with_publication(frame)
    state = GatewayState(adapter._config, session_id='87654321-1234-4321-8765-123456789012',
                         now=lambda: frame.observed_at_epoch_ms)
    state.begin_connection(); state.observe_status(status)
    wire = state.accept_ping(frame)
    if wire is None: raise AssertionError('exact .19 publication was not accepted')
    size = int.from_bytes(wire[:4], 'little')
    return state, frame, wire, json.loads(wire[4:4+size]), status


class Sonar0419ReadContractTest(unittest.TestCase):
    def test_exact19_gates_and_atomic_route_keep20_suffix_and_wrong_producer_unconfirmed(self):
        for versions in (ANALYSIS_VERSIONS, SOURCE_VERSIONS, DEPTH_RATE_VERSIONS, PUBLISHED_VERSIONS,
                         DISPLAY_WINDOW_VERSIONS, ATOMIC_CONTEXT_VERSIONS, IMPULSE_VERSIONS,
                         TRANSPORT_LIVENESS_VERSIONS):
            self.assertIn('0.4.19', versions)
            for value in ('0.4.32', '0.4.19-dev', '0.4.19+local', '0.4.190', ' 0.4.19'):
                self.assertNotIn(value, versions)
        self.assertTrue(upstream_websocket_url('http://localhost', '0.4.19').endswith('/ws/v1/echogram-context'))
        for value in ('0.4.32', '0.4.19-dev', '0.4.19+local', '0.4.190', ' 0.4.19', 18, None):
            status = status_0417(CASES[0], value)
            self.assertIsNone(package_version(status)); self.assertIsNone(amplitude(status))
        status = status_0417(CASES[0], '0.4.19'); status['host_package']['name'] = 'another-producer'
        self.assertIsNone(package_version(status)); self.assertIsNone(amplitude(status))

    def test_nine_unchanged_host_fixtures_keep_original_m1_samples_ids_context_and_crop(self):
        self.assertEqual(hashlib.sha256(FIXTURE.read_bytes()).hexdigest(), FIXTURE_SHA)
        self.assertEqual(len(CASES), 9)
        for case in CASES:
            with self.subTest(name=case['name']):
                state, frame, wire, metadata, _ = project_case(case)
                original = base64.b64decode(case['frame_base64'], validate=True)
                envelope = base64.b64decode(case['context_base64'], validate=True)
                self.assertEqual(parse_atomic_echogram(envelope).original_m1, original)
                self.assertEqual(hashlib.sha256(original).hexdigest(), case['frame_sha256'])
                size = int.from_bytes(wire[:4], 'little')
                self.assertLessEqual(size, 4096); self.assertLessEqual(len(envelope), 6200)
                self.assertEqual(frame.samples, original[40:]); self.assertEqual(wire[4+size:], original[40:])
                self.assertEqual(metadata['hostPackageVersion'], '0.4.19')
                published = case['published_ping']
                self.assertEqual(metadata['pingId'], published['ping_id'])
                self.assertEqual(metadata['observedAtEpochMs'], published['time_utc_ns']//1_000_000)
                self.assertEqual(metadata['pingSource'], published['actual_source'])
                self.assertEqual(metadata['displaySelection']['generation'], published['display_generation'])
                self.assertEqual(metadata['displaySelection']['selectionGeneration'], published['selection_generation'])
                self.assertEqual(metadata['geometry']['displayedMaxDepthM'], published['display_window']['display_range_m'])
                self.assertEqual(metadata['amplitude']['fullScaleCode'], 4095)
                self.assertEqual(metadata['amplitude']['noiseRevision'], published['noise_revision'])
                self.assertEqual(metadata['amplitude']['noiseActive'], published['noise_active'])
                self.assertEqual(metadata['publication']['sequence'], '1')
                self.assertFalse(state.snapshot()['capabilities']['canStart'])
                self.assertFalse(state.snapshot()['capabilities']['canStop'])

    def test_exact19_requires_explicit_scale_and_does_not_turn_impulse_activity_into_noise_active(self):
        for key, bad in (('fullscale_code', 65535), ('significant_bits', 16),
                         ('normalization_source', 'guessed'), ('display_transform', 'impulse_only')):
            status = status_0417(CASES[0], '0.4.19'); status['acquisition']['amplitude'][key] = bad
            self.assertEqual(amplitude(status)['normalizationSource'], 'UNCONFIRMED')
        absent = status_0417(CASES[0], '0.4.19'); del absent['acquisition']['amplitude']
        self.assertEqual(amplitude(absent)['normalizationSource'], 'UNCONFIRMED')
        disabled = next(case for case in CASES if case['name'] == 'impulse_off')
        self.assertFalse(disabled['noise_reduction']['latest']['impulse_reduction']['active'])
        self.assertTrue(project_case(disabled)[3]['amplitude']['noiseActive'])

    def test_exact19_diagnostics_reuse_existing_bounded_optional_liveness_and_impulse_paths(self):
        from test_sonar_0415_read_contract import liveness
        state, frame, _, _, status = project_case(CASES[1])
        status['pico'] = {'transport_liveness': liveness()}
        status['noise_reduction']['latest']['impulse_reduction']['reason'] = '🟠' * 100000
        result = projection(state, status, {}, status_request_started_at=frame.observed_at_epoch_ms,
                            status_request_elapsed_s=.01)
        fields = {(item['group'], item['name']): item for item in result['fields']}
        self.assertIn(('pico', 'transport_liveness.poll_read_bytes'), fields)
        self.assertEqual(fields['noise_reduction', 'policy.impulse_mode']['value'], 'auto')
        self.assertIn(('host_package', 'version'), fields)
        self.assertLessEqual(len(result['fields']), 128)
        self.assertLessEqual(len(json.dumps(result, ensure_ascii=False).encode()), 32768)
        missing = status_0417(CASES[0], '0.4.19')
        missing['noise_reduction']['latest'].pop('impulse_reduction')
        missing['noise_reduction']['policy'].pop('impulse_mode')
        missing['noise_reduction']['latest'].pop('impulse_mode')
        self.assertFalse(any('impulse' in row['name'] for row in projection(state, missing, {})['fields']))

    def test_same_atomic_guards_keep_corrupt_join_window_tx_and_trailer_rejected(self):
        case = CASES[0]; root = deepcopy(case['context_metadata'])
        raw = base64.b64decode(case['frame_base64'])
        for mutate in ('time', 'tx', 'window', 'source'):
            changed = deepcopy(root)
            if mutate == 'time': changed['published_ping']['time_utc_ns'] = str(int(changed['published_ping']['time_utc_ns'])+1)
            elif mutate == 'tx': changed['published_ping']['valid_tx_reference'] = False
            elif mutate == 'window': changed['published_ping']['display_window']['display_range_m'] += .01
            else: changed['published_ping']['actual_source'] = 'native'
            with self.subTest(mutate=mutate), self.assertRaises(ProtocolError):
                parse_atomic_echogram(encode(changed, raw))
        with self.assertRaises(ProtocolError): parse_atomic_echogram(base64.b64decode(case['context_base64'])+b'x')
        with self.assertRaises(ProtocolError): parse_atomic_echogram(encode(root, modified_m1(raw, bins=512)))
