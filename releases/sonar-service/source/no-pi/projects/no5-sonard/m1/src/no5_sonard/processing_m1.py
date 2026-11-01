from __future__ import annotations

from dataclasses import dataclass
import math

import numpy as np


@dataclass(slots=True, frozen=True)
class BottomDetectionM1:
    depth_m: float
    confidence: float
    sample_index: int
    snr_db: float


def depth_axis_m(sample_count: int, tx_zero: int, sample_period_ns: int, sound_speed_mps: float) -> np.ndarray:
    idx = np.arange(sample_count, dtype=np.float64) - float(tx_zero)
    return idx * (sample_period_ns * 1e-9) * sound_speed_mps / 2.0


def detect_bottom_m1(samples: np.ndarray, *, tx_zero_sample_index: int, sample_period_ns: int,
                     sound_speed_mps: float = 1500.0, blanking_m: float = 0.5) -> BottomDetectionM1:
    raw = np.asarray(samples, dtype=np.float32)
    if raw.ndim != 1 or raw.size < 32:
        raise ValueError("invalid sample vector")
    meters_per_sample = sample_period_ns * 1e-9 * sound_speed_mps / 2.0
    if meters_per_sample <= 0:
        raise ValueError("invalid sample period")
    first = max(0, tx_zero_sample_index + int(math.ceil(blanking_m / meters_per_sample)))
    if first >= raw.size - 16:
        raise ValueError("blanking leaves no bottom search range")
    kernel = np.ones(5, dtype=np.float32) / 5.0
    smooth = np.convolve(raw, kernel, mode="same")
    # Noise region: first useful 5% of post-blanking range, at least 32 samples.
    n0 = first
    n1 = min(raw.size, n0 + max(32, (raw.size - n0) // 20))
    noise_region = smooth[n0:n1]
    noise = float(np.median(noise_region))
    mad = float(np.median(np.abs(noise_region - noise))) + 1.0
    search = smooth.copy()
    search[:first] = 0.0
    idx = int(np.argmax(search))
    peak = float(search[idx])
    signal = max(1.0, peak - noise)
    snr_db = 20.0 * math.log10(signal / max(1.0, 1.4826 * mad))
    confidence = float(np.clip((snr_db - 6.0) / 24.0, 0.0, 1.0))
    depth = (idx - tx_zero_sample_index) * meters_per_sample
    return BottomDetectionM1(float(max(0.0, depth)), confidence, idx, snr_db)


def max_pool_resample(samples: np.ndarray, *, start_index: int, bins: int) -> np.ndarray:
    src = np.asarray(samples, dtype=np.uint16)[max(0, start_index):]
    if src.size == 0:
        return np.zeros(bins, dtype=np.uint16)
    if src.size <= bins:
        x_old = np.linspace(0.0, 1.0, src.size)
        x_new = np.linspace(0.0, 1.0, bins)
        return np.interp(x_new, x_old, src).astype(np.uint16)
    edges = np.linspace(0, src.size, bins + 1, dtype=np.int64)
    out = np.empty(bins, dtype=np.uint16)
    for i in range(bins):
        a, b = int(edges[i]), int(edges[i + 1])
        out[i] = int(src[a:max(a + 1, b)].max())
    return out
