"""Exact synthetic 0.4.14 wheel/fixture contract; no acquisition or TX commands."""
import base64
import asyncio
from copy import deepcopy
from dataclasses import replace
import hashlib
import json
import math
from pathlib import Path
import struct
import unittest

import aiohttp
from aiohttp import web
from aiohttp.test_utils import TestServer

from no5_pi_gateway.configuration import GatewayConfig
from no5_pi_gateway.protocol import ProtocolError, UPSTREAM_HEADER, parse_echogram_frame
from no5_pi_gateway.sonar_contract import amplitude, display_selection, package_version
from no5_pi_gateway.sonar_diagnostics import projection
from no5_pi_gateway.sonar_presentation import geometry, project
from no5_pi_gateway.sonar_published import PublishedPingCache, host_context, parse_page
from no5_pi_gateway.state import GatewayState
from test_sonar_published import host_status, adapter_for, row, page

FIXTURE = Path(__file__).parent / 'fixtures/sonar-0414/stripe-interface.json'
FIXTURE_SHA = '89114aac6059689254eb003176ff213442149a6a4e9c7a8cab4f80627ad9f4dc'
CASES = json.loads(FIXTURE.read_text())['cases']


def publication_page(value):
    return {'schema': 'no5.sonard-published-pings/1', 'capacity': 512,
        'session_id': value['session_id'], 'segment_id': value['segment_id'],
        'oldest_ping_id': value['ping_id'], 'latest_ping_id': value['ping_id'],
        'next_after_ping_id': value['ping_id'], 'has_more': False, 'truncated': False, 'pings': [value]}


def case_state(case):
    value = case['published_ping']; result = host_status(display=value['display_generation'],
        selection=value['selection_generation'], ping=value['ping_id'], noise=value['noise_active'])
    result['host_package']['version'] = '0.4.14'
    result['session'] = {'id': value['session_id'], 'segment_id': value['segment_id']}
    result['noise_reduction'] = deepcopy(case['noise_reduction'])
    result['acquisition']['amplitude']['noise_reduction'] = deepcopy(case['noise_reduction'])
    result['display_window'] = deepcopy(value['display_window'])
    return result


def case_frame(case, state=None):
    raw = base64.b64decode(case['frame_base64'], validate=True)
    result = parse_echogram_frame(raw)
    value = state or case_state(case)
    return replace(result, host_package_version='0.4.14', analysis_supported=True,
        amplitude=amplitude(value, scale_only=True), host_context_hint=host_context(value),
        selection_generation_hint=value['display_selection']['generation'])


def analysis(case):
    row = case['published_ping']
    return {'detection': {'schema': 'no5.sonard-detection/2', 'ping_id': row['ping_id'],
        'time_utc_ns': row['time_utc_ns'], 'sample_period_ns': row['sample_period_ns'],
        'acquisition_source': row['actual_source'], 'valid_tx': row['valid_tx_reference'],
        'tracks': deepcopy(case['presentation']['tracks']), 'blocks': []},
        'presentation': deepcopy(case['presentation'])}


class SonarDisplayWindowTest(unittest.TestCase):
    def test_bound_fixture_three_full_binary_roundtrips(self):
        self.assertEqual(hashlib.sha256(FIXTURE.read_bytes()).hexdigest(), FIXTURE_SHA)
        for case in CASES:
            with self.subTest(stripe=case['stripe_mode'], verified=case['published_ping']['valid_tx_reference']):
                raw = base64.b64decode(case['frame_base64'], validate=True)
                self.assertEqual(len(raw), 2088)
                self.assertEqual(hashlib.sha256(raw).hexdigest(), case['frame_sha256'])
                self.assertEqual(UPSTREAM_HEADER.pack(*UPSTREAM_HEADER.unpack_from(raw)) + raw[40:], raw)
                page = parse_page(publication_page(case['published_ping']), after_ping_id=0, host_version='0.4.14')
                cache = PublishedPingCache(); cache.set_context(page.context); cache.accept(page)
                self.assertEqual(cache.match(case_frame(case)), page.rows[0])
                self.assertEqual(page.rows[0].sample_count, 16384)  # Original capture, NOT 2048 display samples.
                self.assertEqual(case_frame(case).samples, raw[40:])

    def test_0414_requires_complete_window_but_0413_remains_unchanged(self):
        legacy = page([row()])
        self.assertIsNone(parse_page(legacy, after_ping_id=0, host_version='0.4.13').rows[0].display_window)
        with self.assertRaises(ProtocolError): parse_page(legacy, after_ping_id=0, host_version='0.4.14')
        self.assertEqual(package_version(case_state(CASES[0])), '0.4.14')
        future = case_state(CASES[0]); future['host_package']['version'] = '0.4.32'
        self.assertIsNone(package_version(future))

    def test_window_bounds_and_identity_fail_closed(self):
        base = CASES[1]['published_ping']
        invalid = [
            {'schema': 'future'}, {'ping_id': base['ping_id'] + 1}, {'ping_id': True},
            {'capture_sample_count': 2048}, {'display_sample_count': True},
            {'display_sample_count': 2047}, {'display_start_sample': 2048},
            {'display_end_sample_exclusive': 16385}, {'display_start_sample': -1},
            {'tx_reference_verified': False}, {'raw_samples_modified': True},
            {'detector_uses_full_capture': False}, {'capture_range_m': math.nan},
            {'capture_range_m': -1}, {'display_range_m': math.inf},
            {'display_range_m': 3.072}, {'display_range_m': 24.576000213623047},
            {'reason': 'full_capture'}, {'reason': []},
        ]
        for change in invalid:
            with self.subTest(change=change), self.assertRaises(ProtocolError):
                value = deepcopy(base); value['display_window'].update(change)
                parse_page(publication_page(value), after_ping_id=0, host_version='0.4.14')
        native = deepcopy(base); native.update(actual_source='native', source_evidence='qualified_native_marker_raw_match')
        with self.assertRaises(ProtocolError):
            parse_page(publication_page(native), after_ping_id=0, host_version='0.4.14')

    def test_header_window_match_is_exact_binary32_not_tolerated(self):
        case = CASES[1]; parsed = parse_page(publication_page(case['published_ping']), after_ping_id=0, host_version='0.4.14')
        cache = PublishedPingCache(); cache.set_context(parsed.context); cache.accept(parsed)
        original = case_frame(case)
        bits = struct.unpack('<I', struct.pack('<f', original.displayed_max_depth_m))[0]
        next_value = struct.unpack('<f', struct.pack('<I', bits + 1))[0]
        self.assertIsNone(cache.match(replace(original, displayed_max_depth_m=next_value)))
        self.assertIsNone(cache.match(replace(original, displayed_max_depth_m=math.nan)))
        self.assertIsNone(cache.match(replace(original, time_utc_ns=original.time_utc_ns + 1)))

    def test_bottom_and_range_recommendation_use_the_actual_short_or_full_window(self):
        for case in CASES[:2]:
            with self.subTest(stripe=case['stripe_mode']):
                observed = case_frame(case); result = project(observed, analysis(case))
                bottom = case['presentation']['bottom']
                self.assertAlmostEqual(result['bottom']['bin'], bottom['depth_m'] / observed.displayed_max_depth_m * 1023)
                self.assertAlmostEqual(result['bottom']['bin'], bottom['display_bin'])
                self.assertAlmostEqual(result['recommendations']['rangeEndBin'],
                    case['presentation']['recommendations']['range_end_bin'])

    def test_unverified_pretrigger_keeps_all_samples_and_never_invents_meters(self):
        case = CASES[2]; parsed = parse_page(publication_page(case['published_ping']), after_ping_id=0, host_version='0.4.14')
        window = parsed.rows[0].display_window
        self.assertEqual((window.display_start_sample, window.display_sample_count), (0, 16384))
        self.assertIsNone(window.capture_range_m); self.assertIsNone(window.display_range_m)
        raw = case_frame(case); self.assertTrue(math.isnan(raw.displayed_max_depth_m))
        self.assertIsNone(geometry(raw)['displayedMaxDepthM']); self.assertEqual(geometry(raw)['txReference'], 'UNVERIFIED')
        result = project(raw, analysis(case))
        self.assertEqual(result['quality'], 'UNVERIFIED_TX'); self.assertIsNone(result['bottom']['bin'])
        cache = PublishedPingCache(); cache.set_context(parsed.context); cache.accept(parsed)
        self.assertIsNone(cache.match(replace(raw, displayed_max_depth_m=24.576)))
        for change in ({'display_start_sample': 1000}, {'capture_range_m': 24.576},
                       {'display_range_m': 24.576}, {'reason': 'full_capture'}):
            value = deepcopy(case['published_ping']); value['display_window'].update(change)
            with self.assertRaises(ProtocolError): parse_page(publication_page(value), after_ping_id=0, host_version='0.4.14')

    def test_outside_15m_track_and_bottom_are_not_clamped_into_3m_window(self):
        case = CASES[1]; payload = analysis(case)
        extra = deepcopy(payload['presentation']['tracks'][0]); extra.update(track_id=2, depth_m=15.)
        payload['detection']['tracks'].append(deepcopy(extra)); payload['presentation']['tracks'].append(extra)
        original_detection = deepcopy(payload['detection'])
        projected = project(case_frame(case), payload)
        self.assertEqual((projected['trackTotal'], projected['trackOmitted']), (2, 1))
        self.assertEqual([item['trackId'] for item in projected['tracks']], [1])
        self.assertEqual(payload['detection'], original_detection)
        payload['presentation']['bottom']['depth_m'] = 15.
        projected = project(case_frame(case), payload)
        self.assertIsNone(projected['bottom']['bin']); self.assertIsNone(projected['recommendations']['rangeEndBin'])
        value = case_state(case)
        state = GatewayState(GatewayConfig(producer='m1', upstream_base_url='http://127.0.0.1:8787'))
        state.begin_connection(); state.observe_status(value)
        diagnostics = projection(state, value, payload)
        self.assertEqual(diagnostics['trackTotal'], 2)
        fields = {(item['group'], item['name']): item['value'] for item in diagnostics['fields']}
        self.assertEqual(fields['track:2', 'depth_m'], '15.0')

    def test_diagnostics_read_fields_are_bounded_and_0414_only(self):
        value = case_state(CASES[1]); state = GatewayState(GatewayConfig(producer='m1', upstream_base_url='http://127.0.0.1:8787'))
        state.begin_connection(); state.observe_status(value)
        result = projection(state, value, {})
        fields = {(item['group'], item['name']): item['value'] for item in result['fields']}
        self.assertEqual(fields['display_window', 'display_sample_count'], '2048')
        self.assertEqual(fields['display_window', 'capture_sample_count'], '16384')
        self.assertEqual(fields['display_window', 'reason'], 'qualified_depth_window')
        self.assertEqual(fields['noise_reduction', 'policy.stripe_mode'], 'auto')
        self.assertLessEqual(len(result['fields']), 128); self.assertLessEqual(len(json.dumps(result).encode()), 32768)
        value['host_package']['version'] = '0.4.13'; state.observe_status(value)
        old = projection(state, value, {})
        self.assertFalse(any(item['group'] == 'display_window' for item in old['fields']))

    def test_adaptive_window_does_not_advance_source_or_policy_epoch(self):
        case = CASES[1]; value = case_state(case); adapter = adapter_for(value)
        adapter._host_package_version = '0.4.14'
        state = GatewayState(GatewayConfig(producer='m1', upstream_base_url='http://127.0.0.1:8787'),
                             now=lambda: case['published_ping']['time_utc_ns'] // 1_000_000)
        state.begin_connection(); state.observe_status(value)
        before = state.generation
        first = parse_page(publication_page(case['published_ping']), after_ping_id=0, host_version='0.4.14')
        adapter._published.accept(first)
        self.assertIsNotNone(state.accept_ping(adapter._with_publication(case_frame(case))))
        # A later search ping keeps the SAME display/policy epoch but uses full coverage.
        later = deepcopy(case['published_ping']); later.update(ping_id=10, time_utc_ns=later['time_utc_ns'] + 400_000_000)
        later['display_window'] = deepcopy(CASES[0]['published_ping']['display_window'])
        later['display_window']['ping_id'] = 10
        adapter._published.accept(parse_page(publication_page(later), after_ping_id=8, host_version='0.4.14'))
        later_frame = replace(case_frame(CASES[0], value), ping_id=10, time_utc_ns=later['time_utc_ns'],
            observed_at_epoch_ms=later['time_utc_ns'] // 1_000_000)
        wire = state.accept_ping(adapter._with_publication(later_frame))
        self.assertIsNotNone(wire); self.assertEqual(state.generation, before)
        count = int.from_bytes(wire[:4], 'little'); self.assertLessEqual(count, 4096)
        metadata = json.loads(wire[4:4 + count])
        self.assertEqual(metadata['pingSource'], 'own'); self.assertEqual(metadata['amplitude']['fullScaleCode'], 4095)
        self.assertEqual(wire[-2048:], later_frame.samples)


class SonarDisplayWindowNetworkTest(unittest.IsolatedAsyncioTestCase):
    async def test_0414_fetch_requires_window_and_retains_legacy_bounded_worker(self):
        case = CASES[1]; value = case_state(case)
        for missing in (False, True):
            body = publication_page(deepcopy(case['published_ping']))
            if missing: del body['pings'][0]['display_window']
            app = web.Application()
            async def published(request):
                self.assertEqual(request.query.get('limit'), '64')
                return web.json_response(body)
            app.router.add_get('/api/v1/sonar/published-pings', published)
            async with TestServer(app) as server, aiohttp.ClientSession() as session:
                adapter = adapter_for(value); adapter._host_package_version = '0.4.14'
                adapter._config = GatewayConfig(producer='m1', upstream_base_url=str(server.make_url('')).rstrip('/'))
                await asyncio.wait_for(adapter._fetch_published(session, []), .5)
                self.assertEqual(len(adapter._published), 0 if missing else 1)
                if not missing:
                    self.assertIsNotNone(adapter._published.match(case_frame(case)))
