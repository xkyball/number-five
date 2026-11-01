"""Bounded RFC 6455 SONAR-only stream for the local development gateway.

No application writes over WebSocket. Client frames are control frames only;
all acquisition commands use the revision-checked HTTP command endpoint.
"""
import base64
import hashlib
import json
import select
import socket
import struct
import time
from urllib.parse import urlsplit

SUBPROTOCOL = "no5.sonar.v1"
MAX_MESSAGE = 6148
GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"


def frame(opcode, payload=b""):
    if len(payload) > MAX_MESSAGE:
        raise ValueError("outbound message exceeds bound")
    prefix = bytes([0x80 | opcode])
    return prefix + (bytes([len(payload)]) if len(payload) < 126 else b"\x7e" + struct.pack("!H", len(payload))) + payload


def client_control(data):
    """Return (opcode,payload,consumed) or None for a partial bounded frame."""
    if len(data) < 2:
        return None
    first, second = data[:2]
    if first & 0x70 or not first & 0x80 or first & 15 not in (8, 9, 10) or not second & 0x80:
        raise ValueError("masked, final control frames required")
    length = second & 127
    if length > 125:
        raise ValueError("control payload exceeds 125 bytes")
    if len(data) < 6 + length:
        return None
    mask = data[2:6]
    payload = bytes(value ^ mask[i % 4] for i, value in enumerate(data[6:6 + length]))
    if first & 15 == 8:
        if length == 1:
            raise ValueError("invalid close payload")
        if length >= 2:
            code = struct.unpack("!H", payload[:2])[0]
            if code < 1000 or code in (1004, 1005, 1006, 1015) or 1016 <= code < 3000 or code >= 5000:
                raise ValueError("invalid close code")
            payload[2:].decode("utf-8", errors="strict")
    return first & 15, payload, 6 + length


def serve_sonar_stream(handler, sonar, stop):
    """Upgrade and serve at most the newest ping; socket write deadline is 1s.

    Handler must set rbufsize=0 before setup, avoiding read-ahead across upgrade.
    WS workers share the gateway's existing bounded connection admission.
    """
    headers = handler.headers
    try:
        if headers.get("Upgrade", "").lower() != "websocket" or "upgrade" not in {x.strip().lower() for x in headers.get("Connection", "").split(",")}:
            raise ValueError("WebSocket upgrade required")
        if headers.get("Sec-WebSocket-Version") != "13":
            raise ValueError("WebSocket version 13 required")
        key = headers.get("Sec-WebSocket-Key", "")
        if len(base64.b64decode(key, validate=True)) != 16:
            raise ValueError("invalid WebSocket key")
        if SUBPROTOCOL not in [x.strip() for x in headers.get("Sec-WebSocket-Protocol", "").split(",")]:
            raise ValueError("no5.sonar.v1 subprotocol required")
        origin = headers.get("Origin")
        if origin and (urlsplit(origin).scheme not in ("http", "https") or urlsplit(origin).netloc != headers.get("Host")):
            handler._response(403, {"error": "cross-origin sonar stream rejected"})
            return
        if headers.get("Transfer-Encoding") or headers.get("Content-Length", "0") != "0":
            raise ValueError("WebSocket upgrade body prohibited")
    except (ValueError, UnicodeError):
        handler._response(400, {"error": "invalid SONAR WebSocket handshake"})
        return
    handler.send_response(101)
    handler.send_header("Upgrade", "websocket")
    handler.send_header("Connection", "Upgrade")
    handler.send_header("Sec-WebSocket-Accept", base64.b64encode(hashlib.sha1((key + GUID).encode("ascii")).digest()).decode("ascii"))
    handler.send_header("Sec-WebSocket-Protocol", SUBPROTOCOL)
    handler.end_headers()
    handler.wfile.flush()
    handler.close_connection = True
    sock = handler.connection
    sock.settimeout(1.0)
    received = bytearray()
    last_revision = None
    last_ping = None
    next_state = 0.0
    next_ping = time.monotonic() + 5
    outstanding = None
    ping_sent = 0.0
    fragment_since = None
    try:
        while not stop.is_set():
            now = time.monotonic()
            state, packet = sonar.stream_snapshot()
            revision = (state["serverSessionId"], state["generation"], state["revision"], state["status"])
            if revision != last_revision or now >= next_state:
                sock.sendall(frame(1, json.dumps(state, allow_nan=False, separators=(",", ":")).encode("utf-8")))
                last_revision, next_state = revision, now + 1
            if state["status"] == "UNAVAILABLE":
                sock.sendall(frame(8, struct.pack("!H", 1013) + b"Sonar source unavailable"))
                return
            if packet is not None and state["running"] and packet is not last_ping:
                sock.sendall(frame(2, packet))
                last_ping = packet
            if outstanding is not None and now - ping_sent > 10:
                return
            if now >= next_ping and outstanding is None:
                outstanding = struct.pack("!d", now)
                sock.sendall(frame(9, outstanding))
                ping_sent, next_ping = now, now + 5
            if fragment_since is not None and now - fragment_since > 2:
                return
            readable, _, _ = select.select([sock], [], [], .025)
            if not readable:
                continue
            chunk = sock.recv(1024)
            if not chunk:
                return
            received.extend(chunk)
            if len(received) > 2048:
                raise ValueError("control receive buffer exceeded")
            while received:
                message = client_control(received)
                if message is None:
                    if fragment_since is None:
                        fragment_since = time.monotonic()
                    break
                fragment_since = None
                opcode, payload, consumed = message
                del received[:consumed]
                if opcode == 8:
                    sock.sendall(frame(8, payload))
                    return
                if opcode == 9:
                    sock.sendall(frame(10, payload))
                elif opcode == 10 and payload == outstanding:
                    outstanding = None
    except (ValueError, UnicodeError):
        try:
            sock.sendall(frame(8, struct.pack("!H", 1002) + b"Invalid control frame"))
        except OSError:
            pass
    except (OSError, TimeoutError):
        pass
