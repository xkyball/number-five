from __future__ import annotations

from dataclasses import dataclass
from collections import deque
import math
import time

import numpy as np


@dataclass(slots=True, frozen=True)
class SyncSample:
    pico_mid_ns: int
    host_mid_ns: int
    rtt_ns: int


class PicoClockMapper:
    """Maps Pico monotonic microseconds to host monotonic nanoseconds."""

    def __init__(self, *, window: int = 16, max_rtt_ms: float = 20.0) -> None:
        self.samples: deque[SyncSample] = deque(maxlen=window)
        self.max_rtt_ns = int(max_rtt_ms * 1_000_000)
        self.slope = 1.0
        self.intercept_ns = 0.0
        self.ready = False
        self.last_rtt_ns: int | None = None

    def add_exchange(self, *, host_t1_ns: int, host_t4_ns: int, pico_rx_us: int, pico_tx_us: int) -> bool:
        device_proc_ns = max(0, (pico_tx_us - pico_rx_us) * 1000)
        rtt_ns = max(0, (host_t4_ns - host_t1_ns) - device_proc_ns)
        self.last_rtt_ns = rtt_ns
        if rtt_ns > self.max_rtt_ns:
            return False
        pico_mid_ns = ((pico_rx_us + pico_tx_us) * 1000) // 2
        host_mid_ns = (host_t1_ns + host_t4_ns) // 2
        self.samples.append(SyncSample(pico_mid_ns, host_mid_ns, rtt_ns))
        self._fit()
        return True

    def _fit(self) -> None:
        if not self.samples:
            self.ready = False
            return
        # Prefer lower-RTT exchanges because USB scheduling jitter is asymmetric.
        selected = sorted(self.samples, key=lambda s: s.rtt_ns)[: max(2, min(8, len(self.samples)))]
        if len(selected) == 1:
            s = selected[0]
            self.slope = 1.0
            self.intercept_ns = float(s.host_mid_ns - s.pico_mid_ns)
            self.ready = True
            return
        x = np.array([s.pico_mid_ns for s in selected], dtype=np.float64)
        y = np.array([s.host_mid_ns for s in selected], dtype=np.float64)
        x0 = x[0]
        y0 = y[0]
        dx = x - x0
        dy = y - y0
        denom = float(np.dot(dx, dx))
        slope = 1.0 if denom == 0.0 else float(np.dot(dx, dy) / denom)
        # Reject impossible oscillator fits and fall back to unit slope.
        if not (0.999 <= slope <= 1.001):
            slope = 1.0
        intercept = float(np.mean(y - slope * x))
        self.slope = slope
        self.intercept_ns = intercept
        self.ready = True

    def pico_us_to_host_monotonic_ns(self, pico_us: int) -> int:
        if not self.ready:
            raise RuntimeError("Pico clock mapping is not ready")
        return int(round(self.slope * (pico_us * 1000.0) + self.intercept_ns))

    def pico_us_to_utc_ns(self, pico_us: int) -> int:
        host_mono = self.pico_us_to_host_monotonic_ns(pico_us)
        # Refresh wall/monotonic relationship at conversion time so host NTP
        # corrections are reflected without perturbing the Pico drift fit.
        wall_minus_mono = time.time_ns() - time.monotonic_ns()
        return host_mono + wall_minus_mono

    def snapshot(self) -> dict:
        return {
            "ready": self.ready,
            "samples": len(self.samples),
            "slope": self.slope,
            "drift_ppm": (self.slope - 1.0) * 1_000_000.0,
            "last_rtt_ms": None if self.last_rtt_ns is None else self.last_rtt_ns / 1_000_000.0,
        }
