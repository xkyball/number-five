"""Synthetic wire fixtures; no chart files, runtime or external dependencies."""
import unittest
from dataclasses import FrozenInstanceError

from no_chart_converter.navionics.display_control_wire import (
    DisplayControlRow, decode_display_control, encode_display_control,
)


class DisplayControlWireTests(unittest.TestCase):
    def setUp(self):
        self.row = DisplayControlRow(0, 0, 0, 0)

    def test_explicit_five_byte_row_no_count_or_padding(self):
        row = DisplayControlRow(18, 0xabcd, -128, 127)
        self.assertEqual(encode_display_control(5, 5, [row]), b'\x12\xcd\xab\x80\x7f')
        self.assertEqual(decode_display_control(b'\x12\xcd\xab\x80\x7f', 5, 5), (row,))

    def test_neutral_all16_rows_are80_bytes(self):
        rows = (self.row,) * 16
        self.assertEqual(encode_display_control(0, 15, rows), b'\x00' * 80)
        self.assertEqual(decode_display_control(b'\x00' * 80, 0, 15), rows)

    def test_all_feature_range_nibble_pairs(self):
        count = 0
        for lo in range(16):
            for hi in range(lo, 16):
                rows = (self.row,) * (hi - lo + 1)
                raw = encode_display_control(lo, hi, rows)
                self.assertEqual(len(raw), 5 * len(rows))
                self.assertEqual(decode_display_control(raw, lo, hi), rows)
                count += 1
        self.assertEqual(count, 136)

    def test_all_style_bytes_and_word_bit_boundaries(self):
        for b in range(255):
            for word in (0, 511, 512, 16383, 16384, 32768, 49152, 65535):
                for x, y in ((-128, 127), (127, -128), (0, 0)):
                    row = DisplayControlRow(b, word, x, y)
                    expected = bytes((b, word & 255, word >> 8, x & 255, y & 255))
                    self.assertEqual(encode_display_control(2, 2, [row]), expected)
                    self.assertEqual(decode_display_control(expected, 2, 2), (row,))

    def test_opaque_word_high_bits_are_not_discarded(self):
        rows = tuple(DisplayControlRow(0, word, 0, 0) for word in (0, 0x4000, 0x8000, 0xc000))
        self.assertEqual(decode_display_control(encode_display_control(0, 3, rows), 0, 3), rows)

    def test_default_preserves_explicit_rows(self):
        self.assertEqual(encode_display_control(0, 1, [self.row, self.row]), b'\0' * 10)

    def test_repeat_previous_is_opt_in(self):
        rows = (self.row,) * 16
        raw = encode_display_control(0, 15, rows, repeat_previous=True)
        self.assertEqual(raw, b'\0' * 5 + b'\xff' * 15)
        self.assertEqual(decode_display_control(raw, 0, 15), rows)

    def test_repeat_compares_every_raw_field(self):
        rows = (self.row, DisplayControlRow(0, 0x4000, 0, 0),
                DisplayControlRow(0, 0x4000, 0, 0), DisplayControlRow(0, 0x4000, 0, 1))
        raw = encode_display_control(3, 6, rows, repeat_previous=True)
        self.assertEqual(len(raw), 16)
        self.assertEqual(raw[10], 255)
        self.assertEqual(decode_display_control(raw, 3, 6), rows)

    def test_repeat_and_explicit_duplicates_have_same_values(self):
        row = DisplayControlRow(254, 65535, -128, 127)
        full = b'\xfe\xff\xff\x80\x7f' * 3
        compressed = b'\xfe\xff\xff\x80\x7f\xff\xff'
        self.assertEqual(decode_display_control(full, 1, 3), decode_display_control(compressed, 1, 3))

    def test_first_repeat_is_refused(self):
        with self.assertRaises(ValueError):
            decode_display_control(b'\xff', 0, 0)

    def test_payload_at_each_truncated_boundary(self):
        raw = b'\x00\xff\xff\x80\x7f'
        for n in range(5):
            with self.subTest(n=n), self.assertRaises(ValueError):
                decode_display_control(raw[:n], 0, 0)

    def test_missing_second_row_is_refused(self):
        with self.assertRaises(ValueError):
            decode_display_control(b'\0' * 5, 0, 1)

    def test_trailing_data_is_refused(self):
        for tail in (b'\0', b'\xff', b'\0' * 5):
            with self.subTest(tail=tail), self.assertRaises(ValueError):
                decode_display_control(b'\0' * 5 + tail, 0, 0)

    def test_encoder_decoder_have_identical_range_rejections(self):
        for lo, hi in ((-1, 0), (0, 16), (1, 0), (True, 1), (0, False),
                       (0.0, 0), (0, 0.0), ('0', 0), (0, None), (None, 0)):
            for api in ('encode', 'decode'):
                with self.subTest(lo=lo, hi=hi, api=api), self.assertRaises(ValueError):
                    if api == 'encode':
                        encode_display_control(lo, hi, [self.row])
                    else:
                        decode_display_control(b'\0' * 5, lo, hi)

    def test_row_integer_fields_refuse_bools(self):
        for field in range(4):
            values = [0, 0, 0, 0]
            values[field] = True
            with self.subTest(field=field), self.assertRaises(ValueError):
                DisplayControlRow(*values)

    def test_row_field_boundaries_are_strict(self):
        for values in ((-1, 0, 0, 0), (255, 0, 0, 0), (0, -1, 0, 0),
                       (0, 65536, 0, 0), (0, 0, -129, 0), (0, 0, 128, 0),
                       (0, 0, 0, -129), (0, 0, 0, 128), (0, 0.0, 0, 0)):
            with self.subTest(values=values), self.assertRaises(ValueError):
                DisplayControlRow(*values)

    def test_exact_row_count_is_required(self):
        for rows in ([], [self.row, self.row]):
            with self.subTest(rows=rows), self.assertRaises(ValueError):
                encode_display_control(0, 0, rows)

    def test_explicit_typed_sequence_is_required(self):
        for rows in ({0: self.row}, (r for r in [self.row]), [dict(style_byte=0, style_word=0, dx=0, dy=0)], [None]):
            with self.assertRaises(ValueError):
                encode_display_control(0, 0, rows)

    def test_decoder_requires_bytes(self):
        for raw in ('\0' * 5, bytearray(5), memoryview(b'\0' * 5), [0] * 5, None):
            with self.assertRaises(ValueError):
                decode_display_control(raw, 0, 0)

    def test_repeat_flag_must_be_explicit_bool(self):
        for value in (0, 1, None, 'yes'):
            with self.assertRaises(ValueError):
                encode_display_control(0, 0, [self.row], repeat_previous=value)

    def test_decoded_rows_are_immutable(self):
        rows = decode_display_control(b'\0' * 5 + b'\xff', 0, 1)
        with self.assertRaises(FrozenInstanceError):
            rows[0].dx = 1
        self.assertEqual(rows[1].dx, 0)


if __name__ == '__main__':
    unittest.main()
