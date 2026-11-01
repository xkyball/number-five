"""Console entry point for the Pi systemd service."""

from __future__ import annotations

import argparse
import json
from dataclasses import replace

from .configuration import ConfigurationError, load_config


def main() -> None:
    parser = argparse.ArgumentParser(description="No5 Pi SONAR gateway")
    parser.add_argument("--config", required=True, help="TOML configuration path")
    parser.add_argument("--check-config", action="store_true",
                        help="validate the configuration and exit without opening listeners or upstreams")
    parser.add_argument("--camilla-main-authority", action="store_true",
                        help="route Main writes through the reviewed No5 Connect/Camilla volume bridge")
    args = parser.parse_args()
    try:
        config = load_config(args.config)
        if args.camilla_main_authority:
            config = replace(config, audio=replace(config.audio,
                spotify_connect=replace(config.audio.spotify_connect, master_volume_bridge=True)))
    except (ConfigurationError, OSError) as exc:
        parser.error(str(exc))
    if args.check_config:
        print("configuration valid")
        if args.camilla_main_authority:
            main = config.audio.limits["MASTER"]
            print("camilla-main-authority " + json.dumps({
                "minDb": main.min_db, "maxDb": main.max_db, "stepDb": main.step_db,
            }, sort_keys=True))
        return
    try:
        from aiohttp import web
        from .service import create_app
    except ImportError as exc:
        parser.error(f"runtime dependency missing: {exc}")
    app = __import__("asyncio").run(create_app(config))
    web.run_app(app, host=config.bind_host, port=config.port, handle_signals=True)


if __name__ == "__main__":
    main()
