from __future__ import annotations

import math
import struct
import unittest

from no5_pi_gateway.protocol import (
    APP_SAMPLE_BYTES,
    ProtocolError,
    UPSTREAM_HEADER,
    encode_android_ping,
    parse_echogram_frame,
    strict_json_object,
)


def upstream_frame(*, ping_id: int = 7, utc_ns: int = 1_700_000_000_000_000_000,
                   maximum_range: float = 30.0, bottom: float = 15.0,
                   confidence: float = 0.8, bins: int = 1024) -> bytes:
    samples = struct.pack("<" + "H" * bins, *range(bins))
    return UPSTREAM_HEADER.pack(1, 1, 40, ping_id, utc_ns, maximum_range,
                                bottom, confidence, 0, bins, 0) + samples


class M0M1ProtocolTest(unittest.TestCase):
    def test_parses_exact_header_and_preserves_raw_uint16_bins(self) -> None:
        frame = parse_echogram_frame(upstream_frame())
        self.assertEqual(frame.ping_id, 7)
        self.assertEqual(frame.samples, struct.pack("<" + "H" * 1024, *range(1024)))
        self.assertEqual(frame.range_at_bin(512), 512 * 30.0 / 1023)
        self.assertEqual(frame.bottom()["bin"], 15.0 * 1023 / 30.0)

    def test_nan_bottom_is_unknown_not_zero(self) -> None:
        frame = parse_echogram_frame(upstream_frame(bottom=math.nan))
        self.assertEqual(frame.bottom(), {"bin": None, "confidence": None, "strength": None})

    def test_rejects_contract_mismatches(self) -> None:
        with self.assertRaises(ProtocolError):
            parse_echogram_frame(upstream_frame(bins=1000))
        malformed = bytearray(upstream_frame())
        malformed[2:4] = (39).to_bytes(2, "little")
        with self.assertRaises(ProtocolError):
            parse_echogram_frame(bytes(malformed))

    def test_android_binary_message_is_bounded_and_raw(self) -> None:
        result = encode_android_ping({"kind": "sonar.ping", "version": 1}, b"\0" * APP_SAMPLE_BYTES)
        metadata_length = int.from_bytes(result[:4], "little")
        self.assertEqual(result[4 + metadata_length:], b"\0" * APP_SAMPLE_BYTES)

    def test_rejects_trailing_byte_after_full_sonar_frame(self) -> None:
        with self.assertRaises(ProtocolError):
            parse_echogram_frame(upstream_frame() + b"\0")

    def test_strict_json_rejects_duplicate_fields(self) -> None:
        with self.assertRaises(ProtocolError):
            strict_json_object(b'{"version":1,"version":1}')
