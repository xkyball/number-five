"""Pure own-byte controls; these tests never load or execute a vendor reader."""
import struct
import unittest
from no_chart_converter.navionics.metadata import encode_metadata_record, FORMAT_MARKER


class MetadataTests(unittest.TestCase):
    def make(self, **updates):
        args = dict(declared_file_bytes=300, raw_bounds=(100000, -200000, 300000, 400000))
        args.update(updates)
        return encode_metadata_record(**args)

    def test_fixed_record_and_magic_offsets(self):
        data = self.make()
        self.assertEqual(len(data), 134)
        self.assertEqual(struct.unpack_from('<HHI', data), (0x8050, 130, FORMAT_MARKER))
        self.assertEqual((data[0] & 0x3f, data[0] & 0xc0), (16, 0x40))

    def test_typed_metadata_fields_and_dates(self):
        data = self.make(declared_file_bytes=0x12345678, raw_byte_8=3, raw_word_9=42,
                         date_text_1='20240229', date_text_2='20261017')
        self.assertEqual(struct.unpack_from('<BHI', data, 8), (3, 42, 0x12345678))
        self.assertEqual(data[29:45], b'2024022920261017')
        self.assertEqual(struct.unpack_from('<4i', data, 45), (100000, -200000, 300000, 400000))

    def test_unknown_fields_are_explicit_own_zeros(self):
        data = self.make()
        self.assertEqual(data[15:29], bytes(14))
        self.assertEqual(data[61:134], bytes(73))

    def test_date_guards(self):
        for value in ['20260229', '20261301', '20260100', '2026-1017', '２０２６１０１７', '', 20261017]:
            with self.subTest(value=value), self.assertRaises(ValueError):
                self.make(date_text_1=value)

    def test_total_size_guards(self):
        for value in [0, 133, -1, True, 1 << 32, 134.0]:
            with self.subTest(value=value), self.assertRaises(ValueError):
                self.make(declared_file_bytes=value)

    def test_other_word_guards(self):
        for args in [dict(raw_byte_8=256), dict(raw_word_9=-1), dict(raw_word_9=True),
                     dict(raw_bounds=(1, 2, 3)), dict(raw_bounds=(1, 2, 3, 1 << 31)),
                     dict(raw_bounds=(1, 2, 3, False))]:
            with self.subTest(args=args), self.assertRaises(ValueError):
                self.make(**args)


if __name__ == '__main__':
    unittest.main()
