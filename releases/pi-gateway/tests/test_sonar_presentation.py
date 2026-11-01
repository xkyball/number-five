from __future__ import annotations

from copy import deepcopy
from dataclasses import replace
import json
import math
import struct
import unittest

from no5_pi_gateway.configuration import GatewayConfig
from no5_pi_gateway.protocol import ProtocolError, UPSTREAM_HEADER, parse_echogram_frame
from no5_pi_gateway.sonar_presentation import geometry, project
from no5_pi_gateway.state import GatewayState

UTC_NS = 1_700_000_000_000_000_000


def frame(*, ping_id=7, maximum=27.0, flags=129):
    samples = struct.pack('<1024H', *range(1024))
    raw = UPSTREAM_HEADER.pack(1, 1, 40, ping_id, UTC_NS, maximum, 15.0, .95, flags, 1024, 0) + samples
    return replace(parse_echogram_frame(raw), analysis_supported=True, host_package_version='0.3.5')


def analysis(*, ping_id=7, quality='LOCKED', source='direct_envelope_track_rules_v1', confidence=.55):
    return {'detection': {'schema': 'no5.sonard-detection/2', 'ping_id': ping_id,
                         'time_utc_ns': UTC_NS, 'valid_tx': quality != 'UNVERIFIED_TX',
                         'sample_period_ns': 4000, 'tx_zero_sample_index': 1000},
            'presentation': {'schema': 'no5.sonard-presentation/1', 'ping_id': ping_id,
                             'quality': quality, 'reason': 'confirmed source selection',
                             'bottom': {'source': source, 'display_bin': 12 / 30 * 1023,
                                        'depth_m': 12.0, 'confidence': confidence, 'strength': .6,
                                        'track_id': None if source == 'm1_raw' else 4,
                                        'classification': 'unknown' if source == 'm1_raw' else 'bottom_like',
                                        'classification_source': None if source == 'm1_raw' else 'rules_v1'} if quality == 'LOCKED' else None,
                             'tracks': [{'track_id': 4, 'state': 'confirmed', 'depth_m': 12.0, 'confidence': confidence}] if source != 'm1_raw' and quality == 'LOCKED' else [],
                             'filter': {'requested_mode': 'auto', 'effective_mode': 'auto_filter', 'active': True},
                             'recommendations': {'range': 'FOLLOW_BOTTOM' if quality == 'LOCKED' else 'HOLD',
                                                 'range_end_bin': 12 / 30 * 1023 * 1.3 if quality == 'LOCKED' else None,
                                                 'freeze': quality == 'UNSTABLE'}}}


class SonardPresentationTest(unittest.TestCase):
    def test_confirmed_track_overlay_is_bounded_and_transformed_into_stream_geometry(self):
        payload = analysis(source='m1_raw', confidence=.8)
        payload['presentation']['tracks'] = [{'track_id': n, 'state': 'confirmed', 'depth_m': float(n),
            'confidence': .4, 'classification': 'fish_like', 'classification_source': 'rules_v1', 'detector': 'cfar'} for n in range(1, 21)]
        projected = project(frame(), payload)
        self.assertEqual(len(projected['tracks']), 8)
        self.assertEqual(projected['trackTotal'], 20)
        self.assertEqual(projected['trackOmitted'], 12)
        self.assertAlmostEqual(projected['tracks'][0]['bin'], 1023/27)
        state = GatewayState(GatewayConfig(producer='m1', upstream_base_url='http://127.0.0.1:8787'), now=lambda: UTC_NS//1_000_000)
        wire = state.accept_ping(replace(frame(), presentation=projected))
        self.assertLessEqual(int.from_bytes(wire[:4], 'little'), 4096)
        self.assertEqual(wire[-2048:], frame().samples)
        payload['presentation']['quality']='UNVERIFIED_TX';payload['presentation']['bottom']=None;payload['detection']['valid_tx']=False
        self.assertEqual(project(frame(), payload)['tracks'], [])

    def test_confirmed_track_below_raw_threshold_is_selected_with_actual_stream_geometry(self):
        projection = project(frame(), analysis())
        self.assertAlmostEqual(projection['bottom']['bin'], 12 / 27 * 1023)
        self.assertEqual(projection['bottom']['confidence'], .55)
        self.assertEqual(projection['bottom']['trackId'], 4)
        self.assertAlmostEqual(projection['recommendations']['rangeEndBin'], 12 * 1.3 / 27 * 1023)
        self.assertEqual(geometry(replace(frame(), presentation=projection))['adcSamplePeriodUs'], 4)

    def test_neither_previous_ping_nor_same_id_from_an_old_session_is_attached(self):
        self.assertIsNone(project(frame(), analysis(ping_id=6)))
        stale = analysis(); stale['detection']['time_utc_ns'] -= 1
        self.assertIsNone(project(frame(), stale))

    def test_raw_selection_still_requires_its_point_75_threshold(self):
        with self.assertRaises(ProtocolError):
            project(frame(), analysis(source='m1_raw'))
        self.assertEqual(project(frame(), analysis(source='m1_raw', confidence=.8))['bottom']['confidence'], .8)

    def test_no_pending_analysis_fallback_to_the_legacy_detector(self):
        state = GatewayState(GatewayConfig(producer='m1', upstream_base_url='http://127.0.0.1:8787'), now=lambda: UTC_NS // 1_000_000)
        metadata = state.ping_metadata(frame())
        self.assertEqual(metadata['bottom'], {'bin': None, 'confidence': None, 'strength': None})
        self.assertIsNone(metadata['presentation'])
        self.assertIsNotNone(metadata['legacyBottom']['bin'])
        self.assertEqual(metadata['hostPackageVersion'], '0.3.5')

    def test_canonical_and_legacy_bottoms_remain_distinct_and_samples_unchanged(self):
        observed = frame(); observed = replace(observed, presentation=project(observed, analysis()))
        state = GatewayState(GatewayConfig(producer='m1', upstream_base_url='http://127.0.0.1:8787'), now=lambda: UTC_NS // 1_000_000)
        wire = state.accept_ping(observed)
        count = int.from_bytes(wire[:4], 'little'); metadata = json.loads(wire[4:4 + count])
        self.assertEqual(wire[-2048:], observed.samples)
        self.assertNotEqual(metadata['bottom']['bin'], metadata['legacyBottom']['bin'])
        self.assertEqual(metadata['geometry']['txReference'], 'EMPIRICAL_FALLBACK')

    def test_unstable_holds_range_and_clears_bottom_without_stopping_the_raw_stream(self):
        projection = project(frame(), analysis(quality='UNSTABLE'))
        self.assertIsNone(projection['bottom']['bin'])
        self.assertTrue(projection['recommendations']['freeze'])
        self.assertEqual(projection['recommendations']['range'], 'HOLD')

    def test_nan_range_and_unverified_reference_keep_samples_but_have_no_meter_axis(self):
        observed = frame(maximum=math.nan, flags=0)
        self.assertEqual(len(observed.samples), 2048)
        self.assertIsNone(geometry(observed)['displayedMaxDepthM'])
        self.assertEqual(geometry(observed)['txReference'], 'UNVERIFIED')
        self.assertIsNone(project(observed, analysis(quality='UNVERIFIED_TX'))['bottom']['bin'])
        self.assertIsNone(geometry(frame(flags=33))['displayedMaxDepthM'])

    def test_malformed_numbers_and_invalid_source_do_not_become_depth(self):
        for key, value in [('source', 'made_up_detector'), ('confidence', math.nan), ('track_id', True), ('depth_m', -1)]:
            malformed = deepcopy(analysis()); malformed['presentation']['bottom'][key] = value
            with self.subTest(key=key), self.assertRaises(ProtocolError):
                project(frame(), malformed)

    def test_out_of_display_range_bottom_is_not_clamped_to_a_fake_depth(self):
        payload = analysis(); payload['presentation']['bottom']['depth_m'] = 40
        payload['presentation']['tracks'][0]['depth_m'] = 40
        projection = project(frame(), payload)
        self.assertIsNone(projection['bottom']['bin'])
        self.assertIsNone(projection['recommendations']['rangeEndBin'])

    def test_unverified_analysis_overrides_a_frame_reference_assertion(self):
        observed = frame(); projection = project(observed, analysis(quality='UNVERIFIED_TX'))
        self.assertIsNone(geometry(replace(observed, presentation=projection))['displayedMaxDepthM'])

    def test_tentative_or_missing_selected_track_cannot_become_a_canonical_bottom(self):
        for tracks in [[], [{'track_id': 4, 'state': 'tentative', 'depth_m': 12.0, 'confidence': .55}]]:
            payload = analysis(); payload['presentation']['tracks'] = tracks
            with self.assertRaises(ProtocolError):
                project(frame(), payload)


if __name__ == '__main__':
    unittest.main()
