"""Short-lived browser sessions for the locally proxied CamillaGUI.

The browser proves possession of the already provisioned No5 gateway key once
to a loopback-only endpoint.  The gateway does not retain that key: it checks
the submitted value against the same SHA-256 verifier that protects AUDIO and
then creates an HMAC-signed, short-lived cookie.  The cookie is invalid after
a gateway restart because the in-memory server-session id is part of its MAC.
"""

from __future__ import annotations

import base64
import hashlib
import hmac
import secrets
import time
from collections.abc import Callable


class CamillaGuiAuthError(ValueError):
    """A session secret or configuration cannot safely issue browser sessions."""


def _b64_encode(value: bytes) -> str:
    return base64.urlsafe_b64encode(value).rstrip(b"=").decode("ascii")


def _b64_decode(value: str) -> bytes:
    if not value or any(char not in "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_" for char in value):
        raise CamillaGuiAuthError("invalid URL-safe token component")
    try:
        return base64.urlsafe_b64decode((value + "=" * (-len(value) % 4)).encode("ascii"))
    except (UnicodeEncodeError, ValueError) as exc:
        raise CamillaGuiAuthError("invalid URL-safe token component") from exc


def verifies_gateway_key(candidate: str, expected_sha256: str | None) -> bool:
    """Compare one bounded ASCII browser submission to the stored verifier."""

    if expected_sha256 is None or not isinstance(candidate, str) or not 16 <= len(candidate) <= 512:
        return False
    try:
        encoded = candidate.encode("ascii")
    except UnicodeEncodeError:
        return False
    if any(byte < 0x21 or byte > 0x7E for byte in encoded):
        return False
    actual = hashlib.sha256(encoded).hexdigest()
    return hmac.compare_digest(actual, expected_sha256)


class CamillaGuiSessionAuth:
    """Issue and verify stateless cookie values without persisting user keys."""

    def __init__(self, secret: str, server_session_id: str, max_age_s: int,
                 *, clock: Callable[[], float] = time.time) -> None:
        if not 32 <= len(secret) <= 512:
            raise CamillaGuiAuthError("CamillaGUI session secret length is invalid")
        try:
            secret_bytes = secret.encode("ascii")
        except UnicodeEncodeError as exc:
            raise CamillaGuiAuthError("CamillaGUI session secret must be ASCII") from exc
        if any(byte < 0x21 or byte > 0x7E for byte in secret_bytes):
            raise CamillaGuiAuthError("CamillaGUI session secret must be printable ASCII")
        if not server_session_id or len(server_session_id) > 200:
            raise CamillaGuiAuthError("gateway server session id is invalid")
        self._secret = secret_bytes
        self._server_session_id = server_session_id
        self._max_age_s = max_age_s
        self._clock = clock

    def _signature(self, expires_at_s: int, nonce: str) -> str:
        try:
            message = f"v1|{expires_at_s}|{nonce}|{self._server_session_id}".encode("ascii")
        except UnicodeEncodeError as exc:
            raise CamillaGuiAuthError("gateway server session id is not ASCII") from exc
        return _b64_encode(hmac.new(self._secret, message, hashlib.sha256).digest())

    def issue(self) -> str:
        expires_at_s = int(self._clock()) + self._max_age_s
        nonce = _b64_encode(secrets.token_bytes(18))
        return f"v1.{expires_at_s}.{nonce}.{self._signature(expires_at_s, nonce)}"

    def verify(self, token: str | None) -> bool:
        if not isinstance(token, str) or not 40 <= len(token) <= 300:
            return False
        version, separator, tail = token.partition(".")
        if version != "v1" or not separator:
            return False
        expires_text, separator, tail = tail.partition(".")
        if not separator or not expires_text.isascii() or not expires_text.isdecimal():
            return False
        nonce, separator, signature = tail.partition(".")
        if not separator:
            return False
        try:
            expires_at_s = int(expires_text)
            if len(_b64_decode(nonce)) != 18:
                return False
            supplied = _b64_decode(signature)
        except (CamillaGuiAuthError, ValueError):
            return False
        now_s = int(self._clock())
        if expires_at_s <= now_s or expires_at_s > now_s + self._max_age_s:
            return False
        expected = self._signature(expires_at_s, nonce)
        try:
            return len(supplied) == 32 and hmac.compare_digest(supplied, _b64_decode(expected))
        except CamillaGuiAuthError:
            return False
