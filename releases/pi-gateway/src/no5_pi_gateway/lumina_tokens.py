"""Canonical Lumina N5T2 token admission using the OpenSSL-backed cryptography backend.

Only registered public SEC1 P-256 keys are available here. Private signing keys
remain in Android Keystore; malformed or noncanonical claims fail closed.
"""
from __future__ import annotations

import base64
import hashlib
import json
import re
import time
from dataclasses import dataclass
from pathlib import Path

from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.asymmetric import ec

HEX = re.compile(r"[0-9a-f]{32}\Z")
DECIMAL = re.compile(r"(?:0|[1-9][0-9]{0,18})\Z")
FIELDS = ("v", "kid", "aud", "role", "scope", "iat", "exp", "jti")
ROLE_SCOPES = {
    "LUMINA_AUDIO": ("audio.read", "audio.command"),
    "HELM": ("audio.read", "audio.command", "sonar.read", "sonar.command"),
    "MAP": ("map.read", "map.write"),
    "HOME": ("configuration.read",),
}


def _b64(value: str) -> bytes:
    if not isinstance(value, str) or not value or len(value) > 16384 or not re.fullmatch(r"[A-Za-z0-9_-]+", value):
        raise ValueError("invalid base64url")
    raw = base64.urlsafe_b64decode(value + "=" * ((4 - len(value) % 4) % 4))
    if base64.urlsafe_b64encode(raw).decode("ascii").rstrip("=") != value:
        raise ValueError("noncanonical base64url")
    return raw


def _point(raw: bytes) -> ec.EllipticCurvePublicKey:
    if len(raw) != 65 or raw[0] != 4:
        raise ValueError("invalid public key")
    return ec.EllipticCurvePublicKey.from_encoded_point(ec.SECP256R1(), raw)


def _verify(public: bytes, payload: bytes, signature: bytes) -> bool:
    try:
        _point(public).verify(signature, payload, ec.ECDSA(hashes.SHA256()))
        return True
    except (ValueError, InvalidSignature):
        return False


@dataclass(frozen=True)
class TokenClaims:
    key_id: str
    role: str
    scopes: tuple[str, ...]
    expires_ms: int


def valid_key(key_id: str, public_key: str) -> None:
    if not isinstance(key_id, str) or not HEX.fullmatch(key_id):
        raise ValueError("invalid key id")
    raw = _b64(public_key)
    _point(raw)
    if hashlib.sha256(raw).hexdigest()[:32] != key_id:
        raise ValueError("key id mismatch")


class LuminaTokenRegistry:
    def __init__(self, path: str | Path, audience: str, clock=time.time):
        self.path = Path(path)
        self.audience = audience
        self.clock = clock

    def _read(self):
        try:
            raw = json.loads(self.path.read_text())
        except FileNotFoundError:
            return {"version": 1, "keys": {}}
        except (OSError, ValueError) as error:
            raise ValueError("unreadable lumina registry") from error
        if set(raw) != {"version", "keys"} or raw["version"] != 1 or not isinstance(raw["keys"], dict):
            raise ValueError("invalid lumina registry")
        return raw

    def verify(self, authorization: str, scope: str):
        try:
            if not isinstance(authorization, str) or len(authorization) > 20480 or not authorization.startswith("Bearer N5T2."):
                return None
            marker, payload64, signature64 = authorization[7:].split(".")
            if marker != "N5T2":
                return None
            payload, signature = _b64(payload64), _b64(signature64)
            text = payload.decode("ascii")
            lines = text.split("\n")
            if len(lines) != 9 or lines[-1] != "":
                return None
            entries = [line.split("=", 1) for line in lines[:-1]]
            if any(len(entry) != 2 for entry in entries) or tuple(entry[0] for entry in entries) != FIELDS:
                return None
            row = dict(entries)
            if row["v"] != "no5-gateway-token-v2" or row["aud"] != self.audience or not HEX.fullmatch(row["kid"]):
                return None
            if len(_b64(row["jti"])) != 16 or not DECIMAL.fullmatch(row["iat"]) or not DECIMAL.fullmatch(row["exp"]):
                return None
            scopes = tuple(row["scope"].split(","))
            if ROLE_SCOPES.get(row["role"]) != scopes or scope not in scopes:
                return None
            issued, expires = int(row["iat"]), int(row["exp"])
            now = int(self.clock() * 1000)
            if expires - issued != 900000 or issued > now + 30000 or expires <= now:
                return None
            key = self._read()["keys"].get(row["kid"])
            if not isinstance(key, dict) or key.get("revoked"):
                return None
            valid_key(row["kid"], key["publicKey"])
            if not _verify(_b64(key["publicKey"]), payload, signature):
                return None
            return TokenClaims(row["kid"], row["role"], scopes, expires)
        except (ValueError, UnicodeError, KeyError, TypeError, OverflowError):
            return None
