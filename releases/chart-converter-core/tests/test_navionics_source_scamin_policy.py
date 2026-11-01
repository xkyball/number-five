"""Invented portable tests; no native image, device or chart fixture."""
import copy
import math
import struct
import unittest

from no_chart_converter.navionics.source_scamin_policy import (POLICY, CQ_REFERENCE, LN2, _f32,
    _native_range, derive_scamin_band, no5_horizontal_calibration,
    scamin_native_cap, source_denominator)


def cell():
    header = bytearray(48)
    struct.pack_into('<h', header, 4, 20)
    struct.pack_into('<ii', header, 24, 65536, 65536)
    struct.pack_into('<f', header, 44, 3704.54/(1 << 10))
    return dict(format='no5-native-view-band-policy/1', mapId='invented-map',
                nativeHeader48LEHex=header.hex(), environment=dict(gEnvLim0=6, gEnvLim1=14),
                nativeViewRangeF32LEHex=dict(minimumInclusive=struct.pack('<f', 16).hex(),
                    maximumInclusive=struct.pack('<f', 128).hex()))


PROFILE = dict(latitudeAdjustment='OFF', zoomSteps=0)


class Controls(unittest.TestCase):
    def test_explicit_named_calibration(self):
        c = no5_horizontal_calibration()
        self.assertEqual(c['policy'], POLICY)
        self.assertEqual(c['pixelsPerMillimetre'], 3)
        self.assertFalse(c['physicalA97DPIClaimed'])
        self.assertAlmostEqual(source_denominator(10, c),
            30000 * (6378137*.9996)*(math.pi/180)/(6378388/57.29577951308))

    def test_changed_display_or_alias_refused(self):
        for key, value in [('pixelsPerMillimetre', 96/25.4),
                           ('nativeProjectedToSourceViewportScale', 1.0)]:
            c = no5_horizontal_calibration(); c[key] = value
            with self.assertRaises(ValueError):
                source_denominator(10, c)

    def test_missing_is_missing_with_separate_cell_policy(self):
        result = derive_scamin_band(source_scamin=None, native_cell_band=cell(),
            calibration=None, native_view_profile=None)
        self.assertIsNone(result['sourceSCAMIN'])
        self.assertEqual(result['native_level_range'], result['cellPolicy']['native_level_range'])
        self.assertEqual(result['native_level_range'], dict(min_level=7, max_level=10))
        self.assertFalse(result['sourceDenominatorInvented'])

    def test_invented_threshold_composes_bounded_feature_band(self):
        # An invented source threshold deliberately inside the CQ R32 bucket.
        c = no5_horizontal_calibration()
        threshold = 700001
        result = derive_scamin_band(source_scamin=threshold, native_cell_band=cell(),
            calibration=c, native_view_profile=PROFILE)
        self.assertEqual(result['SCAMINBucket']['maximumNativeRange'], 32)
        self.assertEqual(result['native_level_range'], dict(min_level=7, max_level=8))
        self.assertEqual(result['cellPolicy']['native_level_range'], dict(min_level=7, max_level=10))
        self.assertFalse(result['queryGeometryAndAttributesChanged'])

    def test_bucket_loss_is_explicit_instead_of_exact_parity(self):
        c = no5_horizontal_calibration()
        cap = scamin_native_cap(700001, c, PROFILE)
        factor = source_denominator(1, c)
        low, high = (700000/factor, 700002/factor)
        self.assertLess(source_denominator(low, c), 700001)
        self.assertGreater(source_denominator(high, c), 700001)
        self.assertEqual(_native_range(low, 0)[0], _native_range(high, 0)[0])
        self.assertGreater(cap['modeledMaximumOverdisplayDenominator'], 0)
        self.assertFalse(cap['exactSCAMINParity'])

    def test_cap_never_hides_selected_finer_control_values(self):
        c = no5_horizontal_calibration()
        for threshold in (120001, 700001, 1500001):
            cap = scamin_native_cap(threshold, c, PROFILE)
            for divisor in (1, 1.25, 2, 3, 8, 16):
                mpp = cap['thresholdNativeProjectedUnitsPerPixel']/divisor
                self.assertLessEqual(_native_range(mpp, 0)[0], cap['maximumNativeRange'])

    def test_native_zoom_profile_is_explicit(self):
        c = no5_horizontal_calibration()
        for p in (None, {}, dict(latitudeAdjustment='ON', zoomSteps=0),
                  dict(latitudeAdjustment='OFF', zoomSteps=5)):
            with self.assertRaises(ValueError):
                scamin_native_cap(700001, c, p)
        a = scamin_native_cap(700001, c, PROFILE)
        b = scamin_native_cap(700001, c, dict(PROFILE, zoomSteps=1))
        self.assertEqual(b['maximumNativeRange'], 2*a['maximumNativeRange'])

    def test_no_silent_denominators_or_zero_defaults(self):
        for value in (False, 0, -1, 1.2, '700001'):
            with self.assertRaises(ValueError):
                scamin_native_cap(value, no5_horizontal_calibration(), PROFILE)

    def test_empty_source_visible_cell_domain_keeps_owner_explicitly(self):
        result = derive_scamin_band(source_scamin=10001, native_cell_band=cell(),
            calibration=no5_horizontal_calibration(), native_view_profile=PROFILE)
        self.assertEqual(result['disposition'], 'NO_SOURCE_VISIBLE_CELL_VIEW_CONSERVATIVE_CELL_KEEP')
        self.assertEqual(result['native_level_range'], result['cellPolicy']['native_level_range'])

    def test_coarsest_saturation_has_no_invented_finite_upper_edge(self):
        c = no5_horizontal_calibration()
        for value, steps in ((1500000001, 0), (50000001, 4)):
            # Large valid source value and a separately coarser zoom profile.
            cap = scamin_native_cap(value, c, dict(PROFILE, zoomSteps=steps))
            self.assertEqual(cap['maximumNativeRange'], 4096)
            if cap['maximumNativeRange'] == 4096:
                self.assertFalse(cap['modeledBucketUpperBoundFinite'])
                self.assertIsNone(cap['modeledBucketUpperNo5Denominator'])
                self.assertIsNone(cap['modeledBucketNextMppF32'])
                self.assertEqual(cap['modeledBucketBoundary'],
                                 'SATURATED_COARSEST_RANGE_NO_FINITE_UPPER_EDGE')
        cap = scamin_native_cap(1500000001, c, PROFILE)
        self.assertEqual(cap['maximumNativeRange'], 4096)
        result = derive_scamin_band(source_scamin=1500000001, native_cell_band=cell(),
            calibration=c, native_view_profile=PROFILE)
        self.assertEqual(result['native_level_range'], result['cellPolicy']['native_level_range'])

    def test_cell_request_unchanged(self):
        request = cell(); before = copy.deepcopy(request)
        derive_scamin_band(source_scamin=700001, native_cell_band=request,
            calibration=no5_horizontal_calibration(), native_view_profile=PROFILE)
        self.assertEqual(request, before)


if __name__ == '__main__':
    unittest.main()
