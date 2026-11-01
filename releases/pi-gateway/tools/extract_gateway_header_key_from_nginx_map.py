#!/usr/bin/env python3
"""Pipe-only wrapper around the packaged strict No5 Nginx-map parser."""

from __future__ import annotations

import os
from pathlib import Path
import sys

# Keep this source-tree utility usable before the package is installed. The
# deployable implementation lives in ``src`` and is also used by the Pi-side
# verifier sync command.
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src"))

from no5_pi_gateway.nginx_auth_map import NginxAuthMapError, extract, write_failure


def main(arguments: list[str]) -> None:
    if arguments != ["--stdin-auth-map"]:
        write_failure("supply exactly --stdin-auth-map and pipe the complete auth map on stdin")
    data = sys.stdin.buffer.read(16_385)
    try:
        key = extract(data)
    except NginxAuthMapError as exc:
        write_failure(str(exc))
    # No print(): the downstream export format requires no trailing newline.
    os.write(sys.stdout.fileno(), key)


if __name__ == "__main__":
    main(sys.argv[1:])
