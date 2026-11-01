"""Invented headers and view inputs only; no actual chart fixture."""
import copy
import math
import struct
import unittest

from no_chart_converter.navionics.native_level_policy import derive_native_band, predict_native_state, range_accepts


def fhex(value):
    return struct.pack("<f", value).hex()


def invented_header(base=1.0, limit=20, header_maximum=8):
    raw = bytearray(48)
    struct.pack_into("<h", raw, 4, limit)
    struct.pack_into("<ii", raw, 24, 1024 << 14, 1024 << 14)
    struct.pack_into("<H", raw, 34, header_maximum)
    struct.pack_into("<f", raw, 44, base)
    return raw.hex()


def request(low=.25, high=1., base=.025):
    return {"format": "no5-native-view-band-policy/1", "mapId": "invented-map-A",
            "nativeHeader48LEHex": invented_header(base),
            "environment": {"gEnvLim0": 6, "gEnvLim1": 14},
            "nativeViewRangeF32LEHex": {"minimumInclusive": fhex(low), "maximumInclusive": fhex(high)}}


class PolicyTests(unittest.TestCase):
    def test_narrow_native_band(self):
        out = derive_native_band(request())
        self.assertEqual(out["native_level_range"], {"min_level": 1, "max_level": 3})
        self.assertEqual(out["packedByte"], 0x13)
        self.assertEqual([r["mapZoomI32"] for r in out["modeledStates"]], [15, 16, 17])
        self.assertFalse(out["nativeExecuted"])
        self.assertFalse(out["sourceSCAMINEncoded"])

    def test_single_declared_view_is_explicit_22(self):
        self.assertEqual(derive_native_band(request(.5, .5))["packedByte"], 0x22)

    def test_exact_nibble_endpoints_and_filter_bypass(self):
        for step in (1, 2, 3):
            self.assertTrue(range_accepts(1, 3, chart_step=step, map_zoom=16,
                                         environment_threshold=14, header_maximum=0))
        for step in (0, 4):
            self.assertFalse(range_accepts(1, 3, chart_step=step, map_zoom=16,
                                          environment_threshold=14, header_maximum=0))
        self.assertTrue(range_accepts(1, 3, chart_step=99, map_zoom=16,
                                     environment_threshold=14, header_maximum=0,
                                     range_filter_enabled=False))

    def test_underzoom_is_different_and_not_silently_generalized(self):
        self.assertFalse(range_accepts(2, 3, chart_step=2, map_zoom=13,
                                      environment_threshold=14, header_maximum=1))
        self.assertTrue(range_accepts(2, 3, chart_step=-1, map_zoom=13,
                                     environment_threshold=14, header_maximum=8))
        with self.assertRaisesRegex(ValueError, "OWN_POLICY_SCOPE_UNDERZOOM"):
            derive_native_band(request(.0625, .5))

    def test_original_zoom_rejections_are_not_clamped(self):
        r = request()
        with self.assertRaisesRegex(ValueError, "ABOVE_LIMIT"):
            predict_native_state(r["nativeHeader48LEHex"], r["environment"], fhex(1024))
        with self.assertRaisesRegex(ValueError, "BELOW_ZERO"):
            predict_native_state(r["nativeHeader48LEHex"], r["environment"], fhex(1e-10))

    def test_header_limit_precedes_environment_limit(self):
        r = request()
        r["nativeHeader48LEHex"] = invented_header(.025, limit=15)
        with self.assertRaisesRegex(ValueError, "ABOVE_LIMIT"):
            derive_native_band(r)

    def test_positive_f32_boundary_neighbours(self):
        r = request()
        # In this invented native header, nearest F32s on either side of the
        # model half-log boundary select consecutive zooms. Not a native test.
        base = struct.unpack("<f", bytes.fromhex(r["nativeHeader48LEHex"])[44:48])[0]
        boundary = (base / 3704.54) * math.exp((16.5) * .69314718)
        bits = struct.unpack("<I", struct.pack("<f", boundary))[0]
        states = [predict_native_state(r["nativeHeader48LEHex"], r["environment"],
                  struct.pack("<I", bits + d).hex()) for d in (-1, 1)]
        self.assertEqual([s["mapZoomI32"] for s in states], [16, 17])

    def test_invalid_inputs_no_implicit_default(self):
        r = request()
        for field in list(r):
            bad = copy.deepcopy(r); del bad[field]
            with self.subTest(missing=field), self.assertRaises(ValueError):
                derive_native_band(bad)
        for value in (0., -1., float("inf"), float("nan")):
            bad = request(); bad["nativeViewRangeF32LEHex"]["minimumInclusive"] = fhex(value)
            with self.subTest(value=value), self.assertRaises(ValueError):
                derive_native_band(bad)
        bad = request(1., .25)
        with self.assertRaises(ValueError): derive_native_band(bad)
        bad = request(); bad["environment"]["gEnvLim0"] = True
        with self.assertRaises(ValueError): derive_native_band(bad)

    def test_explicit_00_is_not_all_levels(self):
        out = derive_native_band(request(.125, .125))
        self.assertEqual(out["packedByte"], 0)
        self.assertEqual(out["modeledStates"][0]["chartStepI32"], 0)
        self.assertFalse(range_accepts(0, 0, chart_step=2, map_zoom=16,
                                      environment_threshold=14, header_maximum=0))


if __name__ == "__main__":
    unittest.main()
