"""Strict parser for the generated No5 Nginx gateway-key map."""

from __future__ import annotations

import re
import sys
from typing import NoReturn


MAX_INPUT_BYTES = 16_384
MIN_KEY_BYTES = 16
MAX_KEY_BYTES = 512
MAP_OPEN = "map $http_x_no5_gateway_key $no5_gateway_authorized {"
DEFAULT = "default 0;"
CLOSE = "}"
KEY_LINE = re.compile(r'^[ \t]*"((?:[^"\\\r\n]|\\["\\])*)"[ \t]+1;[ \t]*$')


class NginxAuthMapError(ValueError):
    """The root-owned generated auth map does not meet the narrow contract."""


def _fail(message: str) -> NoReturn:
    raise NginxAuthMapError(message)


def _non_comment_directives(text: str) -> list[str]:
    return [line for line in text.split("\n")
            if line.strip() and not line.lstrip(" \t").startswith("#")]


def _unescape_nginx_quoted(value: str) -> bytes:
    output = bytearray()
    index = 0
    while index < len(value):
        character = value[index]
        if character != "\\":
            output.extend(character.encode("utf-8"))
            index += 1
            continue
        if index + 1 == len(value) or value[index + 1] not in {'"', "\\"}:
            _fail("the key line uses an unsupported Nginx escape")
        output.extend(value[index + 1].encode("ascii"))
        index += 2
    return bytes(output)


def extract(raw: bytes) -> bytes:
    """Recover precisely one raw key or fail closed without returning one."""

    if not 1 <= len(raw) <= MAX_INPUT_BYTES:
        _fail("the auth map size is outside the allowed range")
    if raw.startswith(b"\xef\xbb\xbf"):
        _fail("the auth map must not contain a UTF-8 BOM")
    try:
        text = raw.decode("utf-8")
    except UnicodeDecodeError:
        _fail("the auth map is not valid UTF-8")
    text = text.replace("\r\n", "\n")
    if "\r" in text:
        _fail("the auth map contains an unsupported carriage return")
    directives = _non_comment_directives(text)
    if len(directives) != 4:
        _fail("expected exactly one approved map with one approved key line")
    if (directives[0].strip(" \t") != MAP_OPEN or directives[1].strip(" \t") != DEFAULT or
            directives[3].strip(" \t") != CLOSE):
        _fail("the auth map does not match the approved No5 map syntax")
    match = KEY_LINE.fullmatch(directives[2])
    if match is None:
        _fail("the auth map does not contain exactly one approved quoted key line")
    key = _unescape_nginx_quoted(match.group(1))
    if not MIN_KEY_BYTES <= len(key) <= MAX_KEY_BYTES:
        _fail("the decoded header value length is outside the allowed range")
    if any(byte < 0x21 or byte > 0x7E or byte == ord(":") for byte in key):
        _fail("the decoded header value is not an allowed header value")
    return key


def write_failure(message: str) -> NoReturn:
    """Emit a non-secret diagnostic used by command-line wrappers only."""

    sys.stderr.write(f"No5 gateway key export refused: {message}\n")
    raise SystemExit(65)
