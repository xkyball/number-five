"""Root-only installation of an AUDIO verifier for the existing Nginx key."""

from __future__ import annotations

import hashlib
import os
from pathlib import Path
import stat
import sys
from typing import NoReturn

from .nginx_auth_map import NginxAuthMapError, extract


MAX_ENVIRONMENT_BYTES = 65_536
VARIABLE = b"NO5_GATEWAY_HEADER_KEY_SHA256"


def fail(message: str) -> NoReturn:
    sys.stderr.write(f"No5 gateway header verifier sync refused: {message}\n")
    raise SystemExit(65)


def _environment_without_previous_verifier(path: Path) -> bytes:
    try:
        metadata = path.lstat()
    except FileNotFoundError:
        return b""
    except OSError as exc:
        fail(f"cannot inspect environment file: {exc.strerror or 'unknown error'}")
    if not stat.S_ISREG(metadata.st_mode):
        fail("environment path is not a regular file")
    if metadata.st_size > MAX_ENVIRONMENT_BYTES:
        fail("environment file exceeds the safe size")
    try:
        raw = path.read_bytes()
    except OSError as exc:
        fail(f"cannot read environment file: {exc.strerror or 'unknown error'}")
    if b"\x00" in raw:
        fail("environment file contains a NUL byte")
    retained = [line for line in raw.splitlines(keepends=True)
                if not line.startswith(VARIABLE + b"=")]
    return b"".join(retained)


def sync(auth_map: Path, environment: Path) -> None:
    """Atomically retain the environment and add only the SHA-256 verifier."""

    if not auth_map.is_absolute() or not environment.is_absolute():
        fail("auth-map and environment paths must be absolute")
    try:
        key = extract(auth_map.read_bytes())
    except OSError as exc:
        fail(f"cannot read auth map: {exc.strerror or 'unknown error'}")
    except NginxAuthMapError as exc:
        fail(str(exc))
    verifier = hashlib.sha256(key).hexdigest().encode("ascii")
    retained = _environment_without_previous_verifier(environment)
    if retained and not retained.endswith(b"\n"):
        retained += b"\n"
    output = retained + VARIABLE + b"=" + verifier + b"\n"
    temporary = environment.with_name(f".{environment.name}.no5-header-hash-{os.getpid()}")
    descriptor: int | None = None
    try:
        descriptor = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        os.fchmod(descriptor, 0o600)
        written = 0
        while written < len(output):
            written += os.write(descriptor, output[written:])
        os.fsync(descriptor)
        os.close(descriptor)
        descriptor = None
        os.replace(temporary, environment)
    except OSError as exc:
        fail(f"cannot atomically write environment file: {exc.strerror or 'unknown error'}")
    finally:
        if descriptor is not None:
            os.close(descriptor)
        try:
            temporary.unlink()
        except FileNotFoundError:
            pass


def main(arguments: list[str] | None = None) -> None:
    arguments = sys.argv[1:] if arguments is None else arguments
    if len(arguments) != 4 or arguments[0] != "--auth-map" or arguments[2] != "--environment":
        fail("supply exactly --auth-map <absolute path> --environment <absolute path>")
    sync(Path(arguments[1]), Path(arguments[3]))


if __name__ == "__main__":
    main()
