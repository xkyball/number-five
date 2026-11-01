from __future__ import annotations

from dataclasses import dataclass
import struct
from typing import Final

import numpy as np

ECHOGRAM_VERSION: Final[int] = 1
ECHOGRAM_MESSAGE_PING: Final[int] = 1
ECHOGRAM_HEADER = struct.Struct("<BBHQqfffIHH")
ECHOGRAM_HEADER_LEN: Final[int] = ECHOGRAM_HEADER.size


@dataclass(slots=True, frozen=True)
class EchogramHeader:
    version: int
    message_type: int
    header_len: int
    ping_id: int
    time_utc_ns: int
    displayed_max_depth_m: float
    bottom_depth_m: float
    bottom_confidence: float
    flags: int
    bin_count: int


def pack_echogram_frame(
    *,
    ping_id: int,
    time_utc_ns: int,
    max_depth_m: float,
    bottom_depth_m: float,
    bottom_confidence: float,
    flags: int,
    amplitudes: np.ndarray,
) -> bytes:
    samples = np.asarray(amplitudes, dtype="<u2")
    if samples.ndim != 1:
        raise ValueError("amplitudes must be a one-dimensional array")
    if samples.size > 65535:
        raise ValueError("echogram bin count exceeds uint16")
    header = ECHOGRAM_HEADER.pack(
        ECHOGRAM_VERSION,
        ECHOGRAM_MESSAGE_PING,
        ECHOGRAM_HEADER_LEN,
        int(ping_id),
        int(time_utc_ns),
        float(max_depth_m),
        float(bottom_depth_m),
        float(bottom_confidence),
        int(flags),
        int(samples.size),
        0,
    )
    return header + samples.tobytes(order="C")


def unpack_echogram_frame(data: bytes) -> tuple[EchogramHeader, np.ndarray]:
    if len(data) < ECHOGRAM_HEADER_LEN:
        raise ValueError("frame shorter than header")
    values = ECHOGRAM_HEADER.unpack_from(data)
    header = EchogramHeader(
        version=values[0],
        message_type=values[1],
        header_len=values[2],
        ping_id=values[3],
        time_utc_ns=values[4],
        displayed_max_depth_m=values[5],
        bottom_depth_m=values[6],
        bottom_confidence=values[7],
        flags=values[8],
        bin_count=values[9],
    )
    if header.version != ECHOGRAM_VERSION:
        raise ValueError(f"unsupported echogram version {header.version}")
    expected = header.header_len + header.bin_count * 2
    if len(data) != expected:
        raise ValueError(f"invalid frame length: got {len(data)}, expected {expected}")
    amplitudes = np.frombuffer(data, dtype="<u2", count=header.bin_count, offset=header.header_len)
    return header, amplitudes
