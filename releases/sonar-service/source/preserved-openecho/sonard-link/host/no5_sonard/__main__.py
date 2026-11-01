from __future__ import annotations

import argparse
from pathlib import Path

import uvicorn

from .app import create_app
from .config import ServiceConfig
from .service import SonarService


def main() -> None:
    p = argparse.ArgumentParser(description="No 5 sonar daemon M1")
    p.add_argument("--host", default="0.0.0.0")
    p.add_argument("--port", type=int, default=8787)
    p.add_argument("--log-level", default="info")
    p.add_argument("--device", default="/dev/no5-sonar-pico")
    p.add_argument("--storage-root", default="/var/lib/no5-sonar")
    p.add_argument("--sample-period-us", type=float, default=4.0)
    p.add_argument("--sample-count", type=int, default=10000)
    p.add_argument("--pretrigger", type=int, default=256)
    p.add_argument("--zarr-chunk-pings", type=int, default=16)
    p.add_argument("--trigger-threshold", type=int, default=3946)
    p.add_argument("--trigger-hysteresis", type=int, default=8)
    p.add_argument("--capture-timeout-us", type=int, default=1000000)
    p.add_argument("--origin", choices=["unknown", "venterior_demo", "venterior_normal"], default="unknown")
    args = p.parse_args()
    cfg = ServiceConfig(
        device=args.device,
        storage_root=Path(args.storage_root),
        sample_period_ns=int(round(args.sample_period_us * 1000.0)),
        sample_count=args.sample_count,
        pretrigger_samples=args.pretrigger,
        zarr_chunk_pings=args.zarr_chunk_pings,
        trigger_threshold=args.trigger_threshold,
        trigger_hysteresis=args.trigger_hysteresis,
        capture_timeout_us=args.capture_timeout_us,
        origin_operator_selected=args.origin,
        tx_reference_verified=False,
    )
    app = create_app(SonarService(cfg))
    uvicorn.run(app, host=args.host, port=args.port, log_level=args.log_level)


if __name__ == "__main__":
    main()
