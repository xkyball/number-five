"""Binary contracts shared by M0/M1 and No5 Android SONAR V1."""

from __future__ import annotations

from dataclasses import dataclass
import json
import math
import struct


UPSTREAM_HEADER = struct.Struct("<BBHQqfffIHH")
UPSTREAM_HEADER_BYTES = UPSTREAM_HEADER.size
APP_BINS = 1024
APP_SAMPLE_BYTES = APP_BINS * 2
APP_MAX_MESSAGE_BYTES = 4 + 4096 + APP_SAMPLE_BYTES


class ProtocolError(ValueError):
    """Bytes or metadata do not satisfy a confirmed transport contract."""


@dataclass(frozen=True, slots=True)
class EchogramFrame:
    ping_id: int
    observed_at_epoch_ms: int
    displayed_max_depth_m: float
    bottom_depth_m: float | None
    bottom_confidence: float | None
    flags: int
    samples: bytes
    time_utc_ns: int | None = None
    analysis_supported: bool = False
    presentation: dict[str, object] | None = None
    host_package_version: str | None = None
    display_selection: dict[str, object] | None = None
    amplitude: dict[str, object] | None = None
    ping_source: str | None = None
    selection_generation_hint: int | None = None
    host_context_hint: tuple[str, str] | None = None
    publication: object | None = None
    publication_sequence: int | None = None
    publication_sequence_gap: int = 0

    def bottom(self) -> dict[str, float | None]:
        if (self.bottom_depth_m is None or self.bottom_confidence is None or
                not math.isfinite(self.displayed_max_depth_m)):
            return {"bin": None, "confidence": None, "strength": None}
        bin_index = self.bottom_depth_m * (APP_BINS - 1) / self.displayed_max_depth_m
        if not 0 <= bin_index <= APP_BINS - 1:
            return {"bin": None, "confidence": None, "strength": None}
        return {
            "bin": bin_index,
            "confidence": self.bottom_confidence,
            # Neither M0 nor M1 provides an independent strength metric.
            "strength": None,
        }

    def range_at_bin(self, bin_index: int) -> float:
        if not 0 <= bin_index < APP_BINS:
            raise ProtocolError("bin index outside 0..1023")
        return bin_index * self.displayed_max_depth_m / (APP_BINS - 1)


def parse_echogram_frame(data: bytes) -> EchogramFrame:
    """Parse the confirmed 40-byte little-endian M0/M1 wire format exactly."""

    if len(data) < UPSTREAM_HEADER_BYTES:
        raise ProtocolError("echogram frame shorter than 40-byte header")
    (version, message_type, header_len, ping_id, utc_ns, max_depth,
     bottom_depth, bottom_confidence, flags, bin_count, _reserved) = UPSTREAM_HEADER.unpack_from(data)
    if version != 1 or message_type != 1:
        raise ProtocolError("unsupported echogram version or message type")
    if header_len != UPSTREAM_HEADER_BYTES:
        raise ProtocolError("unexpected echogram header length")
    if bin_count != APP_BINS:
        raise ProtocolError("No5 Android V1 requires exactly 1024 bins")
    if len(data) != header_len + bin_count * 2:
        raise ProtocolError("echogram payload length does not match bin count")
    if utc_ns <= 0:
        raise ProtocolError("echogram timestamp must be positive UTC nanoseconds")
    # 0.3.5 publishes NaN when the TX reference is unverified. Preserve the
    # display samples; the explicit geometry below must suppress the meter axis.
    if math.isinf(max_depth) or (math.isfinite(max_depth) and max_depth <= 0):
        raise ProtocolError("displayed range must be finite and positive")
    if not math.isfinite(bottom_depth):
        normalized_bottom_depth: float | None = None
        normalized_confidence: float | None = None
    elif not math.isfinite(max_depth) or not 0 <= bottom_depth <= max_depth:
        normalized_bottom_depth = None
        normalized_confidence = None
    elif not math.isfinite(bottom_confidence) or not 0 <= bottom_confidence <= 1:
        normalized_bottom_depth = None
        normalized_confidence = None
    else:
        normalized_bottom_depth = float(bottom_depth)
        normalized_confidence = float(bottom_confidence)
    return EchogramFrame(
        ping_id=ping_id,
        observed_at_epoch_ms=utc_ns // 1_000_000,
        displayed_max_depth_m=float(max_depth),
        bottom_depth_m=normalized_bottom_depth,
        bottom_confidence=normalized_confidence,
        flags=flags,
        samples=data[header_len:],
        time_utc_ns=utc_ns,
    )


def encode_android_ping(metadata: dict[str, object], samples: bytes) -> bytes:
    """Produce the Android framing without resampling, normalization or gamma."""

    if len(samples) != APP_SAMPLE_BYTES:
        raise ProtocolError("Android V1 ping needs exactly 2048 raw sample bytes")
    encoded = json.dumps(metadata, separators=(",", ":"), allow_nan=False).encode("utf-8")
    if not 1 <= len(encoded) <= 4096:
        raise ProtocolError("Android V1 metadata JSON must be 1..4096 bytes")
    result = struct.pack("<I", len(encoded)) + encoded + samples
    if len(result) > APP_MAX_MESSAGE_BYTES:
        raise ProtocolError("Android V1 ping exceeds maximum message size")
    return result


def strict_json_object(raw: bytes) -> dict[str, object]:
    """Decode bounded command JSON while rejecting duplicate object keys."""

    def no_duplicates(pairs: list[tuple[str, object]]) -> dict[str, object]:
        result: dict[str, object] = {}
        for key, value in pairs:
            if key in result:
                raise ProtocolError(f"duplicate JSON key: {key}")
            result[key] = value
        return result

    try:
        value = json.loads(raw.decode("utf-8"), object_pairs_hook=no_duplicates)
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise ProtocolError("command body must be strict UTF-8 JSON") from exc
    if not isinstance(value, dict):
        raise ProtocolError("command body must be a JSON object")
    return value

