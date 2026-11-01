"""Bounded, versioned same-PING envelope; never rewrites the embedded M1."""
from __future__ import annotations

import json
import math
import re
import struct
import uuid

from no5_sonard.protocol import ECHOGRAM_HEADER_LEN, unpack_echogram_frame

MAGIC = b"N5EC"
VERSION = 1
PREFIX = struct.Struct("<4sHHII")
SCHEMA = "no5.sonard-echogram-context/1"
MAX_METADATA_BYTES = 4096
MAX_M1_BYTES = 40 + 1024 * 2
MAX_ENVELOPE_BYTES = PREFIX.size + MAX_METADATA_BYTES + MAX_M1_BYTES
UINT64_MAX = (1 << 64) - 1
UINT32_MAX = (1 << 32) - 1
_UNSIGNED = re.compile(r"(?:0|[1-9][0-9]*)\Z")
_SIGNED = re.compile(r"(?:0|-?[1-9][0-9]*)\Z")
_TOP = {"schema", "selected_source", "publication_sequence", "published_ping"}
_ROW = {"ping_id", "time_utc_ns", "session_id", "segment_id", "actual_source",
        "source_evidence", "valid_tx_reference", "display_generation",
        "selection_generation", "sample_count", "sample_period_ns", "noise_revision",
        "noise_active", "display_window"}
_WINDOW = {"schema", "ping_id", "capture_sample_count", "display_sample_count",
           "display_start_sample", "display_end_sample_exclusive", "capture_range_m",
           "display_range_m", "tx_reference_verified", "reason", "raw_samples_modified",
           "detector_uses_full_capture"}
_EVIDENCE = {"own": "physical_own_marker_raw_match",
             "native": "qualified_native_marker_raw_match", None: "unconfirmed"}


def _keys(value, expected):
    if type(value) is not dict or set(value) != expected:
        raise ValueError("unexpected context object fields")


def _integer(value, low, high):
    if type(value) is not int or not low <= value <= high:
        raise ValueError("context integer outside declared range")
    return value


def _decimal(value, low, high, *, signed=False):
    pattern = _SIGNED if signed else _UNSIGNED
    if type(value) is not str or len(value) > 20 or pattern.fullmatch(value) is None:
        raise ValueError("context requires canonical decimal string")
    parsed = int(value)
    if not low <= parsed <= high:
        raise ValueError("context decimal outside declared range")
    return parsed


def _boolean(value):
    if type(value) is not bool:
        raise ValueError("context requires boolean")


def _uuid(value):
    if type(value) is not str:
        raise ValueError("context requires UUID string")
    try:
        canonical = str(uuid.UUID(value))
    except (ValueError, AttributeError) as exc:
        raise ValueError("invalid context UUID") from exc
    if value != canonical:
        raise ValueError("context UUID must be canonical")


def _m1(frame):
    if type(frame) is not bytes or not 42 <= len(frame) <= MAX_M1_BYTES:
        raise ValueError("context M1 size outside declared range")
    header, _ = unpack_echogram_frame(frame)
    if (header.header_len != ECHOGRAM_HEADER_LEN or header.header_len != 40
            or header.message_type != 1 or not 1 <= header.bin_count <= 1024
            or struct.unpack_from("<H", frame, 38)[0] != 0):
        raise ValueError("unsupported context M1 header")
    return header


def _validate(metadata, frame):
    header = _m1(frame)
    _keys(metadata, _TOP)
    if metadata["schema"] != SCHEMA or metadata["selected_source"] not in ("all", "own", "native"):
        raise ValueError("unsupported context schema or selection")
    _decimal(metadata["publication_sequence"], 1, UINT64_MAX)
    row = metadata["published_ping"]
    _keys(row, _ROW)
    pid = _decimal(row["ping_id"], 0, UINT64_MAX)
    stamp = _decimal(row["time_utc_ns"], -(1 << 63), (1 << 63)-1, signed=True)
    if pid != header.ping_id or stamp != header.time_utc_ns:
        raise ValueError("context identity differs from original M1")
    _uuid(row["session_id"]); _uuid(row["segment_id"])
    source = row["actual_source"]
    if source not in ("own", "native", None) or row["source_evidence"] != _EVIDENCE[source]:
        raise ValueError("context source evidence mismatch")
    if source is not None and metadata["selected_source"] not in ("all", source):
        raise ValueError("context selection differs from actual source")
    if source == "native" and row["valid_tx_reference"] is not True:
        raise ValueError("confirmed native context requires valid TX reference")
    for name in ("display_generation", "selection_generation", "noise_revision", "sample_period_ns"):
        _integer(row[name], 1, UINT32_MAX)
    count = _integer(row["sample_count"], 1, 16384)
    _boolean(row["valid_tx_reference"]); _boolean(row["noise_active"])
    if bool(header.flags & 1) != row["valid_tx_reference"]:
        raise ValueError("context TX reference differs from M1 flag bit 0")
    window = row["display_window"]
    _keys(window, _WINDOW)
    if window["schema"] != "no5.display-window/1" or _decimal(window["ping_id"], 0, UINT64_MAX) != pid:
        raise ValueError("context window identity mismatch")
    if _integer(window["capture_sample_count"], 1, 16384) != count:
        raise ValueError("context capture count mismatch")
    start = _integer(window["display_start_sample"], 0, count)
    end = _integer(window["display_end_sample_exclusive"], 0, count)
    if not start < end or _integer(window["display_sample_count"], 1, 16384) != end-start:
        raise ValueError("context display window mismatch")
    if end < count and source != "own":
        raise ValueError("context display crop requires confirmed own source")
    _boolean(window["tx_reference_verified"])
    if window["tx_reference_verified"] != row["valid_tx_reference"]:
        raise ValueError("context TX evidence mismatch")
    if window["raw_samples_modified"] is not False or window["detector_uses_full_capture"] is not True:
        raise ValueError("context raw/detector invariant mismatch")
    reason = window["reason"]
    if type(reason) is not str or not 1 <= len(reason.encode("utf-8")) <= 128:
        raise ValueError("context window reason size")
    if row["valid_tx_reference"]:
        for name in ("capture_range_m", "display_range_m"):
            value = window[name]
            try:
                finite = type(value) in (int, float) and math.isfinite(value)
            except OverflowError:
                finite = False
            if not finite or value <= 0:
                raise ValueError("context requires finite positive range")
        if window["display_range_m"] != header.displayed_max_depth_m:
            raise ValueError("context display range differs from M1")
    elif (window["capture_range_m"] is not None or window["display_range_m"] is not None
          or start != 0 or end != count or not math.isnan(header.displayed_max_depth_m)):
        raise ValueError("unverified context must retain unknown full coverage")


def pack_context(frame: bytes, published_ping: dict, *, selected_source: str,
                 publication_sequence: int) -> bytes:
    """Copy only the new wire view. The HTTP row and original frame stay intact."""
    _keys(published_ping, _ROW)
    row = dict(published_ping)
    _keys(row["display_window"], _WINDOW)
    row["display_window"] = dict(row["display_window"])
    row["ping_id"] = str(_integer(row["ping_id"], 0, UINT64_MAX))
    row["time_utc_ns"] = str(_integer(row["time_utc_ns"], -(1 << 63), (1 << 63)-1))
    row["display_window"]["ping_id"] = str(_integer(row["display_window"]["ping_id"], 0, UINT64_MAX))
    metadata = {"schema": SCHEMA, "selected_source": selected_source,
                "publication_sequence": str(_integer(publication_sequence, 1, UINT64_MAX)),
                "published_ping": row}
    _validate(metadata, frame)
    encoded = json.dumps(metadata, ensure_ascii=False, allow_nan=False,
                         sort_keys=True, separators=(",", ":")).encode("utf-8")
    if not 1 <= len(encoded) <= MAX_METADATA_BYTES:
        raise ValueError("context metadata exceeds 4096 bytes")
    return PREFIX.pack(MAGIC, VERSION, 0, len(encoded), len(frame)) + encoded + frame


def _unique_object(pairs):
    result = {}
    for name, value in pairs:
        if name in result:
            raise ValueError("duplicate context JSON field")
        result[name] = value
    return result


def _nonfinite(value):
    raise ValueError("nonfinite context JSON number")


def unpack_context(envelope: bytes) -> tuple[dict, bytes]:
    if type(envelope) is not bytes or not PREFIX.size <= len(envelope) <= MAX_ENVELOPE_BYTES:
        raise ValueError("context envelope size outside declared range")
    magic, version, flags, metadata_len, frame_len = PREFIX.unpack_from(envelope)
    if magic != MAGIC or version != VERSION or flags != 0:
        raise ValueError("unsupported context prefix")
    if not 1 <= metadata_len <= MAX_METADATA_BYTES or not 42 <= frame_len <= MAX_M1_BYTES:
        raise ValueError("context component size outside declared range")
    if len(envelope) != PREFIX.size + metadata_len + frame_len:
        raise ValueError("context envelope length mismatch")
    boundary = PREFIX.size + metadata_len
    try:
        metadata = json.loads(envelope[PREFIX.size:boundary].decode("utf-8"),
                              object_pairs_hook=_unique_object, parse_constant=_nonfinite)
    except RecursionError as exc:
        raise ValueError("context JSON nesting exceeds parser limit") from exc
    frame = envelope[boundary:]
    _validate(metadata, frame)
    return metadata, frame
