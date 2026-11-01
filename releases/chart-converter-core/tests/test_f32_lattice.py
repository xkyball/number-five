"""Independent float/Fraction oracle plus exact large-count/seam cases."""
import math
import random
import struct
import sys
import unittest
from fractions import Fraction as F
from no_chart_converter.rx5.f32_lattice import (MODES, MAX_STEPS, add_bits, units, trajectory, at,
                         joint_chunks, family_bounds, linear_support, normal_or_zero_witness)
from no_chart_converter.rx5.f32_lattice import family_support_2d, exact_bits, round_units


def fvalue(bits):
    return struct.unpack('<f', struct.pack('<I', bits))[0]


def bits(value):
    return struct.unpack('<I', struct.pack('<f', value))[0]


def neighbor(raw, direction):
    if raw & 0x7fffffff == 0:
        return 1 if direction > 0 else 0x80000001
    return raw+(1 if (raw >> 31 == 0) == (direction > 0) else -1)


def oracle(a, b, mode):
    # Python's binary64 sum of two binary32 values suffices for RN32: if the
    # exponents are close enough for a half-ULP32 issue, the sum is exact in
    # binary64; a more distant operand cannot reach a half-ULP32 boundary.
    left, right = fvalue(a), fvalue(b)
    exact = F(left)+F(right)
    raw = bits(left+right)
    need_finite = math.isfinite(fvalue(raw))
    if not need_finite:
        raise OverflowError('oracle nonfinite')
    actual = F(fvalue(raw))
    if mode == 'rtp' and actual < exact:
        raw = neighbor(raw, 1)
    elif mode == 'rtn' and actual > exact:
        raw = neighbor(raw, -1)
    elif mode == 'rtz' and ((exact > 0 and actual > exact) or (exact < 0 and actual < exact)):
        raw = neighbor(raw, -1 if exact > 0 else 1)
    if exact == 0:
        raw = 0x80000000 if a == b == 0x80000000 or mode == 'rtn' else 0
    return raw


class ExactTests(unittest.TestCase):
    def test_explicit_environment_required(self):
        for mode in ('unknown', None):
            with self.assertRaisesRegex(ValueError, 'FPSCR'):
                trajectory(0, 0, 10, mode=mode, gradual_underflow=True)
        with self.assertRaisesRegex(ValueError, 'FPSCR'):
            trajectory(0, 0, 10, mode='rne', gradual_underflow=False)

    def test_raw_input_types_and_zero_sign_are_explicit(self):
        for value in (True, 1.0, '1', -1, 1 << 32):
            with self.assertRaisesRegex(ValueError, 'RAW_U32'):
                units(value)
        with self.assertRaisesRegex(ValueError, 'ZERO_SIGN'):
            exact_bits(0, negative_zero=1)
        with self.assertRaisesRegex(ValueError, 'ROUNDING_MODE'):
            round_units(0, True)
        with self.assertRaisesRegex(ValueError, 'S32'):
            trajectory(0, 0, True, mode='rne', gradual_underflow=True)

    def test_directed_overflow_clamps_only_in_the_correct_direction(self):
        for mode in ('rtz', 'rtn'):
            self.assertEqual(add_bits(0x7f7fffff, 0x7f7fffff, mode), 0x7f7fffff)
        for mode in ('rtz', 'rtp'):
            self.assertEqual(add_bits(0xff7fffff, 0xff7fffff, mode), 0xff7fffff)
        for left, mode in ((0x7f7fffff, 'rtp'), (0xff7fffff, 'rtn')):
            with self.assertRaisesRegex(ValueError, 'NONFINITE_RESULT'):
                add_bits(left, left, mode)

    def test_single_add_against_independent_oracle(self):
        self.assertEqual(sys.float_info.rounds, 1)
        rng = random.Random(213)
        inputs = [(rng.randrange(0, 0x7f000000) | (rng.randrange(2) << 31),
                   rng.randrange(0, 0x7f000000) | (rng.randrange(2) << 31)) for _ in range(2048)]
        inputs += [(a, b) for a in (0, 0x80000000, 1, 0x80000001, 0x7fffff, 0x800000, 0x3f800000,
                                    0x3f800001, 0xbf800000, 0xbf800001)
                   for b in (0, 0x80000000, 1, 0x80000001, 0x33800000, 0xb3800000)]
        for mode in MODES:
            for a, b in inputs:
                self.assertEqual(add_bits(a, b, mode), oracle(a, b, mode), (mode, hex(a), hex(b)))

    def test_every_state_against_independent_iteration(self):
        rng = random.Random(214)
        pairs = [(bits(rng.uniform(-1e7, 1e7)), bits(rng.uniform(-40, 40))) for _ in range(40)]
        pairs += [(bits(x), bits(d)) for x in (0, 1, -1, 2, -2, 2**24, -(2**24))
                  for d in (1, -1, 2**-24, -(2**-24))]
        for mode in MODES:
            for start, step in pairs:
                result = trajectory(start, step, 131, mode=mode, gradual_underflow=True)
                expected = start
                for k in range(132):
                    self.assertEqual(at(result, k), int(F(fvalue(expected))*(1 << 149)),
                                     (mode, start, step, k))
                    if k < 131:
                        expected = oracle(expected, step, mode)
                self.assertEqual(result['final_bits'], expected)

    def test_large_count_stagnation_exact(self):
        for mode in ('rne', 'rtz', 'rtn'):
            result = trajectory(0, bits(1), MAX_STEPS, mode=mode, gradual_underflow=True)
            self.assertEqual(result['final_bits'], bits(2**24))
            self.assertLess(len(result['phases']), 64)
            self.assertEqual(at(result, 2**24-1), units(bits(2**24-1)))
            self.assertEqual(at(result, 2**24), units(bits(2**24)))
            self.assertEqual(at(result, MAX_STEPS), units(bits(2**24)))

    def test_half_tie_odd_then_even_stagnation(self):
        result = trajectory(0x3f800001, 0x33800000, MAX_STEPS,
                            mode='rne', gradual_underflow=True)
        self.assertEqual(result['final_bits'], 0x3f800002)
        self.assertEqual(len(result['phases']), 2)

    def test_asymmetric_binade_seam(self):
        result = trajectory(bits(1), bits(-(2**-24)), 128, mode='rne', gradual_underflow=True)
        expected = bits(1)
        for k in range(129):
            self.assertEqual(at(result, k), units(expected))
            if k < 128:
                expected = oracle(expected, bits(-(2**-24)), 'rne')
        self.assertNotEqual(result['phases'][0].method, 'EXACT_UNIFORM_LATTICE_JUMP')

    def test_signed_zero_and_subnormal_crossing(self):
        for mode in MODES:
            for start, step in [(1, 0x80000001), (0x80000001, 1), (0x80000000, 0),
                                (0, 0x80000000), (0x80000000, 0x80000000)]:
                result = trajectory(start, step, 4, mode=mode, gradual_underflow=True)
                expected = start
                for _ in range(4):
                    expected = oracle(expected, step, mode)
                self.assertEqual(result['final_bits'], expected)

    def test_joint_halfplane_support_covers_all_same_k_states(self):
        x = trajectory(bits(-100), bits(.37), 1024, mode='rne', gradual_underflow=True)
        y = trajectory(bits(75), bits(-.51), 1024, mode='rne', gradual_underflow=True)
        chunks = joint_chunks([x, y])
        lower, upper = linear_support(chunks, [17, -29])
        values = [17*at(x, k)-29*at(y, k) for k in range(1025)]
        self.assertEqual((lower, upper), (min(values), max(values)))

    def test_interval_family_covers_every_lattice_member_and_step(self):
        start_min, start_max = bits(1), bits(1)+4
        step_min, step_max = bits(.25), bits(.25)+4
        for mode in MODES:
            family = family_bounds(start_min, start_max, step_min, step_max, 47,
                                   mode=mode, gradual_underflow=True)
            for start in range(start_min, start_max+1):
                for step in range(step_min, step_max+1):
                    candidate = trajectory(start, step, 47, mode=mode, gradual_underflow=True)
                    for k in range(48):
                        self.assertLessEqual(at(family['low'], k), at(candidate, k))
                        self.assertLessEqual(at(candidate, k), at(family['high'], k))

    def test_zero_count_has_point_support(self):
        a = trajectory(bits(3), bits(7), 0, mode='rne', gradual_underflow=True)
        b = trajectory(bits(-2), bits(9), 0, mode='rne', gradual_underflow=True)
        self.assertEqual(linear_support(joint_chunks([a, b]), [1, 1]), (units(bits(1)), units(bits(1))))

    def test_nonfinite_and_count_caps_fail_closed(self):
        with self.assertRaisesRegex(ValueError, 'NONFINITE'):
            trajectory(0x7f800000, 0, 1, mode='rne', gradual_underflow=True)
        with self.assertRaisesRegex(ValueError, 'NONFINITE'):
            trajectory(0x7f7fffff, 0x7f7fffff, 1, mode='rne', gradual_underflow=True)
        with self.assertRaisesRegex(ValueError, 'S32'):
            trajectory(0, 0, MAX_STEPS+1, mode='rne', gradual_underflow=True)

    def test_ftz_irrelevance_requires_complete_non_subnormal_witness(self):
        finite = trajectory(bits(-5000), bits(.125), 100000, mode='rne', gradual_underflow=True)
        self.assertTrue(normal_or_zero_witness(finite)['qualified'])
        subnormal = trajectory(0x800000, 0x80000001, 4, mode='rne', gradual_underflow=True)
        self.assertFalse(normal_or_zero_witness(subnormal)['qualified'])
        # Normal endpoints alone are insufficient: a subnormal can occur at
        # an exact interior cancellation before the sequence changes sign.
        interior = trajectory(0x800000, 0x80800001, 2, mode='rne', gradual_underflow=True)
        self.assertFalse(normal_or_zero_witness(interior)['qualified'])

    def test_every_binade_seam_both_signs_all_modes(self):
        for exponent in range(1, 254):
            base = exponent << 23
            step = max(1, base-24*0x800000)
            for sign in (0, 0x80000000):
                for mode in MODES:
                    for offset in (-1, 0, 1):
                        start = (base+offset) | sign
                        delta = step | (sign ^ 0x80000000)
                        result = trajectory(start, delta, 7, mode=mode, gradual_underflow=True)
                        expected = start
                        for k in range(8):
                            self.assertEqual(at(result, k), units(expected))
                            if k < 7:
                                expected = oracle(expected, delta, mode)

    def test_family_halfplane_is_synchronized_and_contains_all_members(self):
        xf = family_bounds(bits(-10)+2, bits(-10), bits(.4), bits(.4)+2, 31,
                           mode='rne', gradual_underflow=True)
        yf = family_bounds(bits(7), bits(7)+2, bits(-.3)+2, bits(-.3), 31,
                           mode='rne', gradual_underflow=True)
        support = family_support_2d(xf, yf, [11, -13])
        for sx in range(bits(-10), bits(-10)+3):
            for sy in range(bits(7), bits(7)+3):
                x = trajectory(sx, bits(.4)+1, 31, mode='rne', gradual_underflow=True)
                y = trajectory(sy, bits(-.3)+1, 31, mode='rne', gradual_underflow=True)
                for k in range(32):
                    value = 11*at(x, k)-13*at(y, k)
                    self.assertLessEqual(support['minimum_units'], value)
                    self.assertLessEqual(value, support['maximum_units'])


if __name__ == '__main__':
    unittest.main()
