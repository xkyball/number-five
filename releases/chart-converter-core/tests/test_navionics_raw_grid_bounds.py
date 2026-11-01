"""Small own software checks, not native acceptance or geographic tests."""
import unittest
from no_chart_converter.navionics.raw_grid_bounds import (
    HEADER_X_ABS_LIMIT as X, HEADER_Y_ABS_LIMIT as Y,
    validate_raw_grid_bounds as validate, validate_u16_xy, validate_point40_xy,
)


class RawBatchGuards(unittest.TestCase):
    def test_known_four_grid_arithmetic(self):
        r = validate((100000, 100000, 200000, 200000), (4, 4))
        self.assertEqual(r["normalizedGridSourcePrediction"], [49152, 49152])
        self.assertEqual(r["nativeHeaderLODScaleQualification"], "UNQUALIFIED")

    def test_ordinary_finer_software_example(self):
        b, g = (100000, 100000, 101000, 101000), (1024, 1024)
        r = validate(b, g, bank_bounds=b, bank_grid=g)
        self.assertEqual(r["normalizedGridSourcePrediction"], [16760832] * 2)
        self.assertEqual(r["u16LocalCoordinateMaximum"], [1023] * 2)
        self.assertEqual(r["headerCalcFieldEndI32SourcePrediction"], 101000)
        p = ((0, 0), (1, 1), (511, 512), (1023, 1023))
        self.assertEqual(validate_u16_xy(p, g), p)
        self.assertTrue(r["bankCheckedEqual"])
        self.assertFalse(r["originalExecutionPerformed"])
        self.assertFalse(r["nativeN65536AcceptanceClaim"])

    def test_arithmetic_boundary_does_not_claim_native_acceptance(self):
        r = validate((0, 0, 1000, 1000), (65536, 65536))
        self.assertEqual(r["shiftedSignedGrid"], [1073741824] * 2)
        self.assertEqual(r["normalizedGridSourcePrediction"], [1073725440] * 2)
        self.assertEqual(r["nativeHeaderLODScaleQualification"], "UNQUALIFIED")
        self.assertFalse(r["nativeN65536AcceptanceClaim"])
        self.assertEqual(validate_u16_xy(((65535, 65535),), (65536, 65536)),
                         ((65535, 65535),))

    def test_rectangle_selected_intermediate(self):
        r = validate((0, 0, 1000, 1000), (1024, 512))
        self.assertEqual(r["headerAspectRatioF64"], .5)
        self.assertEqual(r["headerCalcFieldEndI32SourcePrediction"], 2000)
        self.assertEqual(r["nativeHeaderLODScaleQualification"], "UNQUALIFIED")

    def test_header_bound_checks_are_inclusive(self):
        r = validate((-X, -Y, X, Y), (4, 4))
        self.assertEqual(r["rawBounds"], [-X, -Y, X, Y])

    def test_invalid_grid_values(self):
        for n in (-1, 0, 1, 65537, 131072, 2**32, True, 4., "4"):
            with self.subTest(n=n), self.assertRaises(ValueError):
                validate((0, 0, 1000, 1000), (n, 4))

    def test_invalid_bounds_and_shapes(self):
        for b in ((0,0,0,1), (2,0,1,1), (0,1,1,0),
                  (0,0,X+1,1), (0,0,1,Y+1), (-X-1,0,1,1),
                  (0,-Y-1,1,1), (0,0,2**31,1),
                  (False,0,1,1), (0.,0,1,1), (0,0,1), "0011"):
            with self.subTest(b=b), self.assertRaises(ValueError):
                validate(b, (4,4))

    def test_aspect_vcvt_overflow(self):
        with self.assertRaisesRegex(ValueError, "ASPECT_VCVT"):
            validate((0, 0, 1000, Y), (65536, 2))

    def test_aspect_calcfield_envelope(self):
        with self.assertRaisesRegex(ValueError, "CALCFIELD_ENVELOPE"):
            validate((0, 0, 1000, Y), (4, 2))

    def test_aspect_degenerate_truncation(self):
        with self.assertRaisesRegex(ValueError, "ASPECT_ADD"):
            validate((0, 0, 1000, 1), (2, 65536))

    def test_header_bank_mismatch(self):
        for kw in ({"bank_bounds": (0,0,1000,1000)},
                   {"bank_grid": (4,4)},
                   {"bank_bounds": (0,0,1000,1001), "bank_grid": (4,4)},
                   {"bank_bounds": (0,0,1000,1000), "bank_grid": (8,4)}):
            with self.subTest(kw=kw), self.assertRaises(ValueError):
                validate((0,0,1000,1000), (4,4), **kw)

    def test_raw_vector_outside_and_noninteger(self):
        for points in (((-1,0),), ((4,0),), ((0,4),), ((True,1),),
                       ((1.,1),), ((1,),), "11"):
            with self.subTest(points=points), self.assertRaises(ValueError):
                validate_u16_xy(points, (4,4))

    def test_point40_uses_source_units_not_local_grid_units(self):
        self.assertEqual(validate_point40_xy((125000,175000),
                         (100000,100000,200000,200000)), (125000,175000))
        for p in ((3,1), (200001,175000), (125000,False), (2**31,175000)):
            with self.subTest(p=p), self.assertRaises(ValueError):
                validate_point40_xy(p, (100000,100000,200000,200000))


if __name__ == "__main__":
    unittest.main()
