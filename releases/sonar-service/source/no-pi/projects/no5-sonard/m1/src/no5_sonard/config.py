from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path


@dataclass(slots=True)
class ServiceConfig:
    source: str = "pico"  # pico | synthetic
    device: str = "/dev/no5-sonar-pico"
    storage_root: Path = Path("/var/lib/no5-sonar")
    sample_period_ns: int = 4000
    sample_count: int = 10000
    pretrigger_samples: int = 256
    trigger_mode: int = 2
    signal_mode: int = 1
    trigger_threshold: int = 2500
    trigger_hysteresis: int = 100
    capture_timeout_us: int = 100000
    clock_initial_exchanges: int = 8
    clock_sync_interval_s: float = 30.0
    zarr_chunk_pings: int = 16
    display_bins: int = 1024
    sound_speed_mps: float = 1500.0
    blanking_m: float = 0.5
