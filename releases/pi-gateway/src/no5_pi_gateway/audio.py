"""CamillaDSP and documented librespot onevent adapters for AUDIO V1.

Raspotify/librespot's ``--onevent`` hook is an observation source, not a
control API.  Its small state file is deliberately local, bounded and
read-only from the gateway; it never invents a media clock or a transport
command channel.
"""

from __future__ import annotations

import asyncio
import contextlib
from collections import deque
from dataclasses import dataclass, replace
import json
import hashlib
import math
import os
from pathlib import Path
import re
import stat
import time
from typing import Any

from .configuration import AudioConfig, CamillaDspConfig, GatewayConfig
from .protocol import ProtocolError, strict_json_object
from .equalizer import EqualizerController, EqualizerError, EqualizerUnknown, validate_command as validate_equalizer_command
from .sound import SOUND_TYPES, SoundController, CamillaSoundWire, SoundError, SoundUnknown, validate_sound_command


class AudioError(ProtocolError):
    """A CamillaDSP reply or requested write cannot be safely accepted."""


class AudioOutcomeUnknown(AudioError):
    """A local receiver accepted an action but did not yield safe readback."""


_SESSION_ID = re.compile(r"[A-Za-z0-9_-]{1,100}\Z")
_REQUEST_ID = re.compile(r"([A-Za-z0-9_-]{1,100}):([1-9][0-9]*)\Z")


def epoch_ms() -> int:
    return time.time_ns() // 1_000_000


@dataclass(frozen=True, slots=True)
class CamillaFader:
    db: float
    muted: bool


@dataclass(frozen=True, slots=True)
class CamillaSnapshot:
    version: str
    state: str
    raw_state: str
    faders: dict[str, CamillaFader]
    config_title: str | None
    clipped_samples: int | None
    observed_at_epoch_ms: int
    config_path: str | None = None


@dataclass(frozen=True, slots=True)
class CamillaPreset:
    """A file candidate that CamillaDSP has read successfully, not a guess."""

    identifier: str
    title: str
    path: str
    sound_routing: str | None = None
    config_sha256: str | None = None


@dataclass(frozen=True, slots=True)
class RaspotifyMediaSnapshot:
    """A validated immutable observation from librespot's onevent process."""

    connected: bool
    active: bool
    state: str
    track_id: str | None
    title: str | None
    artists: tuple[str, ...]
    album: str | None
    duration_ms: int | None
    position_ms: int | None
    shuffle: bool | None
    repeat: bool | None
    observed_at_epoch_ms: int


_EVENT_STATE_VERSION = 1
_MAX_EVENT_BYTES = 16_384
_VISIBLE_TEXT = re.compile(r"[^\x00-\x1f\x7f]")
_PRESET_TITLE = re.compile(r"(?m)^title:\s*([^#\r\n]+)$")


def _bounded_text(value: object, label: str, *, nullable: bool = True) -> str | None:
    if value is None and nullable:
        return None
    if not isinstance(value, str):
        raise AudioError(f"{label} must be a string or null")
    result = value.strip()
    if not result or len(result) > 200 or not all(_VISIBLE_TEXT.fullmatch(char) for char in result):
        raise AudioError(f"{label} must contain 1..200 visible characters")
    return result


def _bounded_optional_int(value: object, label: str, *, minimum: int, maximum: int) -> int | None:
    if value is None:
        return None
    if isinstance(value, bool) or not isinstance(value, int) or not minimum <= value <= maximum:
        raise AudioError(f"{label} is out of range")
    return value


def _audio_state_file_payload(snapshot: RaspotifyMediaSnapshot) -> dict[str, object]:
    return {
        "version": _EVENT_STATE_VERSION,
        "kind": "raspotify-onevent-state",
        "connected": snapshot.connected,
        "active": snapshot.active,
        "state": snapshot.state,
        "trackId": snapshot.track_id,
        "title": snapshot.title,
        "artists": list(snapshot.artists),
        "album": snapshot.album,
        "durationMs": snapshot.duration_ms,
        "positionMs": snapshot.position_ms,
        "shuffle": snapshot.shuffle,
        "repeat": snapshot.repeat,
        "observedAtEpochMs": snapshot.observed_at_epoch_ms,
    }


def _parse_raspotify_state(payload: dict[str, object], *, expected_kind: str = "raspotify-onevent-state") -> RaspotifyMediaSnapshot:
    expected = {
        "version", "kind", "connected", "active", "state", "trackId", "title", "artists", "album",
        "durationMs", "positionMs", "shuffle", "repeat", "observedAtEpochMs",
    }
    if set(payload) != expected or payload.get("version") != _EVENT_STATE_VERSION or payload.get("kind") != expected_kind:
        raise AudioError("Spotify receiver state has an unsupported schema")
    connected = payload["connected"]
    active = payload["active"]
    state = payload["state"]
    if not isinstance(connected, bool) or not isinstance(active, bool) or state not in {"STOPPED", "PAUSED", "PLAYING"}:
        raise AudioError("Raspotify onevent state is invalid")
    artists_value = payload["artists"]
    if not isinstance(artists_value, list) or len(artists_value) > 10:
        raise AudioError("Raspotify artists must contain at most ten values")
    artists = tuple(_bounded_text(value, "Raspotify artist", nullable=False) for value in artists_value)
    shuffle = payload["shuffle"]
    repeat = payload["repeat"]
    if shuffle is not None and not isinstance(shuffle, bool):
        raise AudioError("Raspotify shuffle must be boolean or null")
    if repeat is not None and not isinstance(repeat, bool):
        raise AudioError("Raspotify repeat must be boolean or null")
    observed = _bounded_optional_int(payload["observedAtEpochMs"], "Raspotify observedAtEpochMs", minimum=1,
                                     maximum=9_007_199_254_740_991)
    assert observed is not None
    duration = _bounded_optional_int(payload["durationMs"], "Raspotify durationMs", minimum=1, maximum=86_400_000)
    position = _bounded_optional_int(payload["positionMs"], "Raspotify positionMs", minimum=0, maximum=86_400_000)
    if duration is not None and position is not None and position > duration:
        raise AudioError("Raspotify position exceeds track duration")
    return RaspotifyMediaSnapshot(
        connected=connected, active=active, state=state,
        track_id=_bounded_text(payload["trackId"], "Raspotify trackId"),
        title=_bounded_text(payload["title"], "Raspotify title"), artists=artists,
        album=_bounded_text(payload["album"], "Raspotify album"), duration_ms=duration,
        position_ms=position, shuffle=shuffle, repeat=repeat, observed_at_epoch_ms=observed,
    )


def _read_strict_state_file(path: Path, *, expected_kind: str = "raspotify-onevent-state") -> RaspotifyMediaSnapshot:
    # lstat rather than Path.read_bytes avoids accepting a privileged symlink as
    # a media source.  The onevent bridge atomically replaces a regular file.
    metadata = path.lstat()
    if not stat.S_ISREG(metadata.st_mode):
        raise AudioError("Raspotify state path is not a regular file")
    if metadata.st_size <= 0 or metadata.st_size > _MAX_EVENT_BYTES:
        raise AudioError("Raspotify state file has an unsafe size")
    flags = os.O_RDONLY | getattr(os, "O_CLOEXEC", 0) | getattr(os, "O_NOFOLLOW", 0)
    try:
        descriptor = os.open(path, flags)
    except OSError as exc:
        raise AudioError("Raspotify state file cannot be opened safely") from exc
    try:
        with os.fdopen(descriptor, "rb", closefd=False) as handle:
            content = handle.read(_MAX_EVENT_BYTES + 1)
    finally:
        os.close(descriptor)
    if not content or len(content) > _MAX_EVENT_BYTES:
        raise AudioError("Raspotify state file has an unsafe size")
    return _parse_raspotify_state(strict_json_object(content), expected_kind=expected_kind)


async def _spotify_control_request(path: Path, request_id: str, action: dict[str, object], *, timeout_s: float) -> dict[str, object] | None:
    """Send one bounded command to the local No5 receiver Unix socket.

    The socket is an acknowledgement channel only.  A receiver ``ACCEPTED``
    reply confirms delivery to its live Spirc handle, while this gateway still
    waits for a separate, freshly written player state before publishing a
    verified application ACK.
    """

    try:
        metadata = path.lstat()
    except OSError as exc:
        raise AudioError("Spotify Connect control socket is unavailable") from exc
    if not stat.S_ISSOCK(metadata.st_mode):
        raise AudioError("Spotify Connect control path is not a Unix socket")
    request = {
        "version": 1,
        "kind": "spotify-control",
        "requestId": request_id,
        "action": action,
    }
    encoded = json.dumps(request, separators=(",", ":"), allow_nan=False).encode("utf-8") + b"\n"
    if len(encoded) > 4096:
        raise AudioError("Spotify Connect control request exceeds its local bound")
    writer: asyncio.StreamWriter | None = None
    delivery_attempted = False
    try:
        reader, writer = await asyncio.wait_for(
            asyncio.open_unix_connection(str(path), limit=4097), timeout=timeout_s,
        )
        writer.write(encoded)
        await asyncio.wait_for(writer.drain(), timeout=timeout_s)
        delivery_attempted = True
        response = await asyncio.wait_for(reader.readline(), timeout=timeout_s)
    except (OSError, asyncio.TimeoutError) as exc:
        if delivery_attempted:
            raise AudioOutcomeUnknown(
                "Spotify receiver command delivery is unconfirmed; refresh AUDIO state and do not replay"
            ) from exc
        raise AudioError("Spotify Connect receiver did not acknowledge the local command") from exc
    finally:
        if writer is not None:
            writer.close()
            with contextlib.suppress(Exception):
                await writer.wait_closed()
    if not response or len(response) > 4096 or not response.endswith(b"\n"):
        if delivery_attempted:
            raise AudioOutcomeUnknown(
                "Spotify receiver reply is incomplete; refresh AUDIO state and do not replay"
            )
        raise AudioError("Spotify Connect receiver reply is unsafe")
    try:
        payload = strict_json_object(response[:-1])
    except ProtocolError as exc:
        if delivery_attempted:
            raise AudioOutcomeUnknown(
                "Spotify receiver reply is invalid; refresh AUDIO state and do not replay"
            ) from exc
        raise AudioError("Spotify Connect receiver reply is not strict JSON") from exc
    expected = {"version", "kind", "requestId", "status", "message"}
    main_action = str(action.get("type", "")).startswith("MASTER_")
    verified_main = main_action and payload.get("status") == "VERIFIED"
    if (set(payload) != (expected | {"confirmation"} if verified_main else expected) or
            payload.get("version") != 1 or payload.get("kind") != "spotify-control-ack" or
            payload.get("requestId") != request_id or not isinstance(payload.get("message"), str) or
            len(payload["message"]) > 200 or payload.get("status") not in {"ACCEPTED", "REJECTED", "UNKNOWN", "VERIFIED"}):
        raise AudioOutcomeUnknown("Receiver reply is unsupported; refresh AUDIO state and do not replay")
    if payload["status"] == "UNKNOWN":
        raise AudioOutcomeUnknown("Main owner lost command readback; refresh AUDIO state and do not replay")
    if payload["status"] == "REJECTED":
        raise AudioError("Spotify Connect receiver rejected the local command")
    if verified_main:
        confirmation = payload["confirmation"]
        if (not isinstance(confirmation, dict) or
                set(confirmation) != {"sessionId", "revision", "valueDb", "muted", "observedAtEpochMs"} or
                not isinstance(confirmation["sessionId"], str) or not _SESSION_ID.fullmatch(confirmation["sessionId"]) or
                isinstance(confirmation["revision"], bool) or not isinstance(confirmation["revision"], int) or
                confirmation["revision"] < 1 or not isinstance(confirmation["muted"], bool) or
                isinstance(confirmation["observedAtEpochMs"], bool) or not isinstance(confirmation["observedAtEpochMs"], int) or
                confirmation["observedAtEpochMs"] < 1):
            raise AudioOutcomeUnknown("Main owner confirmation is invalid; do not replay")
        _finite_db(confirmation["valueDb"], "confirmed Main value")
        return confirmation
    if main_action or payload["status"] != "ACCEPTED":
        raise AudioOutcomeUnknown("Queue admission is not Main execution confirmation; do not replay")
    return None


def _event_string(environ: dict[str, str], key: str) -> str | None:
    value = environ.get(key)
    if value is None:
        return None
    try:
        return _bounded_text(value, key)
    except AudioError:
        return None


def _event_integer(environ: dict[str, str], key: str, *, minimum: int, maximum: int) -> int | None:
    value = environ.get(key)
    if value is None or not re.fullmatch(r"[0-9]+", value):
        return None
    result = int(value)
    return result if minimum <= result <= maximum else None


def _event_boolean(environ: dict[str, str], key: str) -> bool | None:
    value = environ.get(key)
    if value == "true":
        return True
    if value == "false":
        return False
    return None


def _artists(environ: dict[str, str]) -> tuple[str, ...]:
    raw = environ.get("ARTISTS")
    if raw is None:
        return ()
    values: list[str] = []
    for value in raw.split("\n")[:10]:
        parsed = _event_string({"value": value}, "value")
        if parsed is not None:
            values.append(parsed)
    return tuple(values)


def event_snapshot(event: str, environ: dict[str, str], previous: RaspotifyMediaSnapshot | None,
                   *, now: callable = epoch_ms) -> RaspotifyMediaSnapshot:
    """Apply a documented librespot PlayerEvent without inferring missing data."""

    base = previous or RaspotifyMediaSnapshot(
        connected=False, active=False, state="STOPPED", track_id=None, title=None, artists=(), album=None,
        duration_ms=None, position_ms=None, shuffle=None, repeat=None, observed_at_epoch_ms=now(),
    )
    stamp = now()
    if event == "session_connected":
        return RaspotifyMediaSnapshot(True, base.active, base.state, base.track_id, base.title, base.artists,
                                      base.album, base.duration_ms, base.position_ms, base.shuffle, base.repeat, stamp)
    if event == "session_disconnected":
        return RaspotifyMediaSnapshot(False, False, "STOPPED", base.track_id, base.title, base.artists,
                                      base.album, base.duration_ms, None, base.shuffle, base.repeat, stamp)
    if event == "track_changed":
        return RaspotifyMediaSnapshot(
            base.connected, base.active, base.state, _event_string(environ, "TRACK_ID"),
            _event_string(environ, "NAME"), _artists(environ), _event_string(environ, "ALBUM"),
            _event_integer(environ, "DURATION_MS", minimum=1, maximum=86_400_000), None,
            base.shuffle, base.repeat, stamp,
        )
    if event in {"playing", "paused", "position_changed", "position_correction", "seeked"}:
        position = _event_integer(environ, "POSITION_MS", minimum=0, maximum=86_400_000)
        state = "PLAYING" if event in {"playing", "position_changed", "position_correction", "seeked"} else "PAUSED"
        return RaspotifyMediaSnapshot(base.connected, True, state,
                                      base.track_id, base.title, base.artists, base.album, base.duration_ms, position,
                                      base.shuffle, base.repeat, stamp)
    if event in {"stopped", "unavailable"}:
        return RaspotifyMediaSnapshot(base.connected, False, "STOPPED", base.track_id, base.title, base.artists,
                                      base.album, base.duration_ms, None, base.shuffle, base.repeat, stamp)
    if event == "shuffle_changed":
        return RaspotifyMediaSnapshot(base.connected, base.active, base.state, base.track_id, base.title, base.artists,
                                      base.album, base.duration_ms, base.position_ms, _event_boolean(environ, "SHUFFLE"),
                                      base.repeat, stamp)
    if event == "repeat_changed":
        context = _event_boolean(environ, "REPEAT")
        track = _event_boolean(environ, "REPEAT_TRACK")
        repeat = None if context is None and track is None else bool(context) or bool(track)
        return RaspotifyMediaSnapshot(base.connected, base.active, base.state, base.track_id, base.title, base.artists,
                                      base.album, base.duration_ms, base.position_ms, base.shuffle, repeat, stamp)
    # Loading/preloading and unrecognised future events contain no V1 state
    # transition.  Recording their observation would falsely refresh media.
    return base


def write_event_state(path: Path, event: str, environ: dict[str, str], *, now: callable = epoch_ms) -> None:
    """Serialize one librespot callback into an atomic, group-readable state file."""

    import fcntl
    path.parent.mkdir(mode=0o750, parents=True, exist_ok=True)
    lock_path = path.with_name(f".{path.name}.lock")
    descriptor = os.open(lock_path, os.O_RDWR | os.O_CREAT | getattr(os, "O_CLOEXEC", 0), 0o640)
    try:
        os.fchmod(descriptor, 0o640)
        fcntl.flock(descriptor, fcntl.LOCK_EX)
        try:
            previous = _read_strict_state_file(path)
        except (FileNotFoundError, AudioError):
            previous = None
        snapshot = event_snapshot(event, environ, previous, now=now)
        data = json.dumps(_audio_state_file_payload(snapshot), separators=(",", ":"), allow_nan=False).encode("utf-8")
        temporary = path.with_name(f".{path.name}.{os.getpid()}.tmp")
        temp_descriptor = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_TRUNC | getattr(os, "O_CLOEXEC", 0), 0o640)
        try:
            os.fchmod(temp_descriptor, 0o640)
            os.write(temp_descriptor, data)
            os.fsync(temp_descriptor)
        finally:
            os.close(temp_descriptor)
        os.replace(temporary, path)
    finally:
        os.close(descriptor)


def raspotify_event_main() -> None:
    """librespot ``--onevent`` program: no CLI input other than its event name."""

    import argparse

    parser = argparse.ArgumentParser(description="Persist a documented librespot onevent observation")
    parser.add_argument("--state-file", required=True, type=Path)
    parser.add_argument("event")
    arguments = parser.parse_args()
    if not arguments.state_file.is_absolute():
        parser.error("--state-file must be absolute")
    # The documented hook has no authenticated caller identity.  Limiting the
    # accepted event vocabulary makes arbitrary environment values harmless.
    # All known librespot 0.8 PlayerEvent names are accepted. Events without a
    # V1 media-state transition deliberately retain the prior observation
    # timestamp, so a connect/client/volume notification cannot masquerade as
    # fresh playback. Position events are carried when a receiver enables
    # PlayerConfig.position_update_interval.
    allowed = {
        "session_connected", "session_disconnected", "session_client_changed", "track_changed",
        "playing", "paused", "stopped", "unavailable", "shuffle_changed", "repeat_changed",
        "loading", "preloading", "preload_next", "end_of_track", "play_request_id_changed",
        "volume_changed", "seeked", "position_correction", "position_changed", "autoplay_changed",
        "filter_explicit_content_changed",
    }
    if arguments.event not in allowed:
        parser.error("unsupported librespot event")
    write_event_state(arguments.state_file, arguments.event, dict(os.environ))


def _finite_db(value: object, label: str) -> float:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise AudioError(f"{label} must be a numeric dB value")
    result = float(value)
    if not math.isfinite(result) or not -150.0 <= result <= 24.0:
        raise AudioError(f"{label} is outside CamillaDSP's documented dB range")
    return result


def _preset_title(value: object) -> str:
    """Extract only a simple, visible YAML title from Camilla's validation reply.

    We deliberately do not parse or expose the rest of a DSP configuration.
    Quoted/complex YAML titles are rejected instead of being interpreted by a
    second YAML implementation that could disagree with CamillaDSP.
    """

    if not isinstance(value, str) or len(value.encode("utf-8")) > 65_536:
        raise AudioError("CamillaDSP preset validation returned unsafe content")
    match = _PRESET_TITLE.search(value)
    if match is None:
        raise AudioError("CamillaDSP preset has no simple title")
    title = match.group(1).strip()
    if (not title or len(title) > 200 or title.startswith(("'", '"')) or
            not all(_VISIBLE_TEXT.fullmatch(char) for char in title)):
        raise AudioError("CamillaDSP preset title is unsafe")
    return title


def _preset_sound_routing(value: object) -> str | None:
    """Exact native No5Sound activation marker from DSP-validated YAML.

    No filename/title inference or general YAML parser. Ambiguous, quoted,
    nested or absent descriptions cannot advertise automatic routing.
    """
    if not isinstance(value, str) or len(value.encode("utf-8")) > 65_536:
        return None
    descriptions = re.findall(r"^description: (.*)$", value, re.MULTILINE)
    if len(descriptions) != 1:
        return None
    marker = re.fullmatch(r"No5 sound v1 / (front_stereo_fullrange|stereo_4_1|immersive_4_1)", descriptions[0])
    return marker.group(1) if marker else None


def _preset_identifier(filename: str) -> str:
    stem = Path(filename).stem.lower()
    value = re.sub(r"[^a-z0-9_-]", "-", stem).strip("-")
    if not value or len(value) > 90:
        raise AudioError("CamillaDSP preset filename cannot form a stable identifier")
    return f"camilla-{value}"


def validate_audio_command(payload: dict[str, object]) -> None:
    """Validate the complete V1 envelope before any future write is considered."""

    required = {"version", "kind", "sessionId", "requestId", "serverSessionId", "expectedRevision", "command"}
    if set(payload) != required:
        raise AudioError("audio command must contain exactly the V1 envelope fields")
    if payload["version"] != 1 or payload["kind"] != "audio-command":
        raise AudioError("unsupported audio command envelope")
    session = payload["sessionId"]
    request = payload["requestId"]
    if not isinstance(session, str) or not _SESSION_ID.fullmatch(session):
        raise AudioError("audio command sessionId is invalid")
    if not isinstance(request, str):
        raise AudioError("audio command requestId is invalid")
    request_match = _REQUEST_ID.fullmatch(request)
    if request_match is None or request_match.group(1) != session:
        raise AudioError("audio command requestId must be <sessionId>:<positive counter>")
    if (not isinstance(payload["serverSessionId"], str) or not payload["serverSessionId"] or
            isinstance(payload["expectedRevision"], bool) or not isinstance(payload["expectedRevision"], int) or
            payload["expectedRevision"] < 0 or not isinstance(payload["command"], dict)):
        raise AudioError("audio command session, revision or command is invalid")
    command = payload["command"]
    command_type = command.get("type")
    if command_type == "fader":
        if set(command) != {"type", "channel", "valueDb"}:
            raise AudioError("audio fader command is invalid")
        if command.get("channel") not in {"MASTER", "SUB", "REAR", "AUX3", "AUX4"}:
            raise AudioError("audio fader channel is invalid")
        _finite_db(command.get("valueDb"), "audio fader value")
    elif command_type == "mute":
        if set(command) != {"type", "channel", "muted"}:
            raise AudioError("audio mute command is invalid")
        if command.get("channel") not in {"MASTER", "SUB", "REAR", "AUX3", "AUX4"} or not isinstance(command.get("muted"), bool):
            raise AudioError("audio mute command is invalid")
    elif command_type == "transport":
        # A media bridge, not CamillaDSP, must implement this branch.
        action = command.get("action")
        allowed = {"PLAY", "PAUSE", "NEXT", "PREVIOUS", "SEEK", "SHUFFLE", "REPEAT"}
        if not isinstance(action, str) or action not in allowed:
            raise AudioError("audio transport action is invalid")
        expected = {"type", "action", "positionMs"} if action == "SEEK" else {"type", "action"}
        if set(command) != expected:
            raise AudioError("audio transport command fields are invalid")
        if action == "SEEK" and (isinstance(command.get("positionMs"), bool) or
                                  not isinstance(command.get("positionMs"), int) or command["positionMs"] < 0):
            raise AudioError("audio seek position is invalid")
    elif command_type == "preset":
        preset_id = command.get("id")
        if set(command) != {"type", "id"} or not isinstance(preset_id, str) or not _SESSION_ID.fullmatch(preset_id):
            raise AudioError("audio preset command is invalid")
    elif command_type in SOUND_TYPES:
        try:
            validate_sound_command(command)
        except SoundError as exc:
            raise AudioError(str(exc)) from exc
    elif command_type == "equalizer":
        try:
            validate_equalizer_command(command)
        except EqualizerError as exc:
            raise AudioError(str(exc)) from exc
    else:
        raise AudioError("audio command type is invalid")


def _camilla_state(value: object) -> tuple[str, str]:
    if not isinstance(value, str):
        raise AudioError("CamillaDSP state must be a string")
    mapped = {
        "Running": "RUNNING",
        # Camilla's paused processing still has an active DSP pipeline.  The
        # public V1 enum has no PAUSED state, so RUNNING is the only truthful
        # non-error processing category; raw_state retains the source detail.
        "Paused": "RUNNING",
        "Inactive": "STOPPED",
        "Starting": "APPLYING_PRESET",
        "Stalled": "ERROR",
    }.get(value)
    if mapped is None:
        raise AudioError("unknown CamillaDSP state")
    return mapped, value


def _value_reply(command: str, raw: str, *, needs_value: bool = True) -> object | None:
    try:
        payload = strict_json_object(raw.encode("utf-8"))
    except ProtocolError as exc:
        raise AudioError("CamillaDSP reply is not strict JSON") from exc
    if set(payload) != {command} or not isinstance(payload[command], dict):
        raise AudioError(f"CamillaDSP reply does not match {command}")
    result = payload[command]
    if result.get("result") != "Ok":
        raise AudioError(f"CamillaDSP rejected {command}")
    if needs_value:
        if set(result) != {"result", "value"}:
            raise AudioError(f"CamillaDSP {command} reply lacks an exact value")
        return result["value"]
    if set(result) != {"result"}:
        raise AudioError(f"CamillaDSP {command} write reply has unexpected fields")
    return None


class CamillaDspClient:
    """A serialized CamillaDSP 4.1.3 WebSocket client.

    The commands and response framing are taken from CamillaDSP's documented
    WebSocket protocol.  This class never performs Android-volume operations.
    """

    def __init__(self, config: CamillaDspConfig, *, now: callable = epoch_ms, persistent: bool = False) -> None:
        self._config = config
        self._now = now
        self._persistent = persistent
        self._session: Any = None
        self._socket_context: Any = None
        self._socket_value: Any = None

    async def close(self) -> None:
        context, session = self._socket_context, self._session
        self._socket_context = self._socket_value = self._session = None
        if context is not None:
            with contextlib.suppress(Exception):
                await context.__aexit__(None, None, None)
        if session is not None:
            await session.close()

    @contextlib.asynccontextmanager
    async def _socket(self, session: Any) -> Any:
        assert self._config.websocket_url is not None
        if not self._persistent:
            async with session.ws_connect(self._config.websocket_url, autoping=True,
                                          max_msg_size=65_536, timeout=3.0) as socket:
                yield socket
            return
        if self._socket_value is None or self._socket_value.closed:
            await self.close()
            import aiohttp
            self._session = aiohttp.ClientSession(timeout=aiohttp.ClientTimeout(total=None, sock_connect=3.0))
            self._socket_context = self._session.ws_connect(self._config.websocket_url,
                autoping=True, heartbeat=5.0, max_msg_size=65_536, timeout=3.0)
            try:
                self._socket_value = await self._socket_context.__aenter__()
            except BaseException:
                await self.close()
                raise
        try:
            yield self._socket_value
        except BaseException:
            # No command retry: a lost reply has an unknown outcome. Recovery
            # opens a new socket only for a subsequent observation/request.
            await self.close()
            raise

    async def _request(self, socket: Any, command: str, argument: object = None,
                       *, has_argument: bool = False, needs_value: bool = True) -> object | None:
        outbound: object = {command: argument} if has_argument else command
        await socket.send_str(json.dumps(outbound, separators=(",", ":"), allow_nan=False))
        message = await socket.receive(timeout=3.0)
        message_type = getattr(message.type, "name", message.type)
        if message_type != "TEXT" or not isinstance(message.data, str):
            raise AudioError("CamillaDSP did not return a text WebSocket reply")
        return _value_reply(command, message.data, needs_value=needs_value)

    async def _read_snapshot(self, socket: Any) -> CamillaSnapshot:
        version = await self._request(socket, "GetVersion")
        if version != self._config.expected_version:
            raise AudioError("CamillaDSP version does not match reviewed 4.1.3 adapter")
        state_value = await self._request(socket, "GetState")
        state, raw_state = _camilla_state(state_value)
        faders_value = await self._request(socket, "GetFaders")
        if not isinstance(faders_value, list):
            raise AudioError("GetFaders must return an array")
        if not faders_value or not isinstance(faders_value[0], dict):
            raise AudioError("CamillaDSP Main fader is absent")
        main_fader = faders_value[0]
        if set(main_fader) != {"volume", "mute"} or not isinstance(main_fader["mute"], bool):
            raise AudioError("CamillaDSP Main fader is malformed")
        # Camilla 4.1.3 GetFaders[0] reads the same target_volume(0)/mute(0)
        # as GetVolume/GetMute. Comparing separate replies races a receiver or
        # phone update between requests. Use this one actual DSP reply for Main
        # and the other faders; never retry a write to repair an observation.
        faders: dict[str, CamillaFader] = {"MASTER": CamillaFader(
            _finite_db(main_fader["volume"], "Main.volume"), main_fader["mute"])}
        for channel, index in self._config.fader_indexes.items():
            if channel == "MASTER":
                continue
            if index >= len(faders_value) or not isinstance(faders_value[index], dict):
                raise AudioError(f"configured CamillaDSP fader {channel} is absent")
            value = faders_value[index]
            if set(value) != {"volume", "mute"} or not isinstance(value["mute"], bool):
                raise AudioError(f"configured CamillaDSP fader {channel} is malformed")
            faders[channel] = CamillaFader(_finite_db(value["volume"], f"{channel}.volume"), value["mute"])
        clipped = await self._request(socket, "GetClippedSamples")
        if isinstance(clipped, bool) or not isinstance(clipped, int) or clipped < 0:
            raise AudioError("GetClippedSamples must be a non-negative integer")
        title = await self._request(socket, "GetConfigTitle")
        if title is not None and (not isinstance(title, str) or not title.strip() or len(title) > 200):
            raise AudioError("GetConfigTitle must be a short string or null")
        config_path = await self._request(socket, "GetConfigFilePath")
        if config_path is not None and (not isinstance(config_path, str) or not config_path.startswith("/") or
                                        len(config_path) > 1024 or "\x00" in config_path):
            raise AudioError("GetConfigFilePath must be an absolute path or null")
        return CamillaSnapshot(
            version=version, state=state, raw_state=raw_state, faders=faders,
            config_title=title, clipped_samples=clipped, observed_at_epoch_ms=self._now(), config_path=config_path,
        )

    async def _read_presets(self, socket: Any) -> tuple[CamillaPreset, ...]:
        """Read configured candidates through CamillaDSP's validation API.

        CamillaDSP exposes only the active config path; it has no command to
        enumerate presets.  Candidate discovery stays confined to the
        administrator-approved local directory and every candidate is rejected
        unless ``ReadConfigFile`` succeeds in the DSP itself.
        """

        candidates = self._config.preset_paths()
        presets: list[CamillaPreset] = []
        identifiers: set[str] = set()
        for candidate in candidates:
            identifier = _preset_identifier(candidate.name)
            if identifier in identifiers:
                continue
            try:
                validated = await self._request(socket, "ReadConfigFile", str(candidate), has_argument=True)
                title = _preset_title(validated)
            except AudioError:
                continue
            routing = _preset_sound_routing(validated)
            digest = hashlib.sha256(validated.encode("utf-8")).hexdigest() if routing else None
            presets.append(CamillaPreset(identifier, title, str(candidate), routing, digest))
            identifiers.add(identifier)
        return tuple(presets)

    async def read_presets(self, session: Any) -> tuple[CamillaPreset, ...]:
        """Validate the catalog on the serialized Camilla connection."""

        assert self._config.websocket_url is not None
        async with self._socket(session) as socket:
            return await self._read_presets(socket)

    async def read_snapshot(self, session: Any) -> CamillaSnapshot:
        assert self._config.websocket_url is not None
        async with self._socket(session) as socket:
            return await self._read_snapshot(socket)

    async def _write_and_readback(self, session: Any, command: str, argument: object,
                                  verify: callable) -> CamillaSnapshot:
        assert self._config.websocket_url is not None
        async with self._socket(session) as socket:
            await self._request(socket, command, argument, has_argument=True, needs_value=False)
            snapshot = await self._read_snapshot(socket)
        if not verify(snapshot):
            raise AudioError("CamillaDSP write did not pass post-action readback")
        return snapshot

    async def set_fader_db(self, session: Any, channel: str, value_db: object) -> CamillaSnapshot:
        if channel not in self._config.fader_indexes:
            raise AudioError("unknown No5 audio channel")
        requested = _finite_db(value_db, "requested fader value")
        if channel == "MASTER":
            return await self._write_and_readback(
                session, "SetVolume", requested,
                lambda snapshot: math.isclose(snapshot.faders["MASTER"].db, requested, abs_tol=0.01),
            )
        return await self._write_and_readback(
            session, "SetFaderVolume", [self._config.fader_indexes[channel], requested],
            lambda snapshot: math.isclose(snapshot.faders[channel].db, requested, abs_tol=0.01),
        )

    async def set_fader_mute(self, session: Any, channel: str, muted: object) -> CamillaSnapshot:
        if channel not in self._config.fader_indexes or not isinstance(muted, bool):
            raise AudioError("fader mute needs a configured channel and boolean value")
        if channel == "MASTER":
            return await self._write_and_readback(
                session, "SetMute", muted,
                lambda snapshot: snapshot.faders["MASTER"].muted is muted,
            )
        return await self._write_and_readback(
            session, "SetFaderMute", [self._config.fader_indexes[channel], muted],
            lambda snapshot: snapshot.faders[channel].muted is muted,
        )

    async def apply_preset(self, session: Any, preset: CamillaPreset,
                           previous_config_path: str | None) -> CamillaSnapshot:
        """Apply one allowlisted, Camilla-validated file with readback/rollback.

        ``SetConfigFilePath`` alone only changes CamillaDSP's pending path.  A
        successful command therefore requires the candidate to pass
        ``ReadConfigFile``, an explicit ``Reload`` and a post-reload snapshot
        naming the same file and title.  If anything after the path selection
        fails, best-effort restoration uses the previously read active file.
        """

        assert self._config.websocket_url is not None
        async with self._socket(session) as socket:
            checked = await self._request(socket, "ReadConfigFile", preset.path, has_argument=True)
            if _preset_title(checked) != preset.title:
                raise AudioError("CamillaDSP preset title changed after catalog validation")
            if preset.sound_routing is not None and (
                    _preset_sound_routing(checked) != preset.sound_routing or
                    hashlib.sha256(checked.encode("utf-8")).hexdigest() != preset.config_sha256):
                raise AudioError("Validated sound routing changed before activation")
            changed = False
            try:
                await self._request(socket, "SetConfigFilePath", preset.path, has_argument=True, needs_value=False)
                changed = True
                await self._request(socket, "Reload", needs_value=False)
                snapshot = await self._read_snapshot(socket)
                if snapshot.config_path != preset.path or snapshot.config_title != preset.title:
                    raise AudioError("CamillaDSP preset reload did not pass post-action readback")
                return snapshot
            except Exception as exc:
                if changed and previous_config_path is not None:
                    with contextlib.suppress(Exception):
                        await self._request(socket, "SetConfigFilePath", previous_config_path,
                                            has_argument=True, needs_value=False)
                        await self._request(socket, "Reload", needs_value=False)
                        restored = await self._read_snapshot(socket)
                        if restored.config_path != previous_config_path:
                            raise AudioError("CamillaDSP rollback readback did not match its previous config")
                if isinstance(exc, AudioError):
                    raise
                raise AudioError("CamillaDSP preset apply failed") from exc


class CamillaDspAdapter:
    """Background observer plus readback-gated CamillaDSP fader controls."""

    def __init__(self, config: GatewayConfig, server_session_id: str) -> None:
        self._audio: AudioConfig = config.audio
        self._client = CamillaDspClient(config.audio.camilla, persistent=True)
        self._server_session_id = server_session_id
        self._latest: CamillaSnapshot | None = None
        self._media: RaspotifyMediaSnapshot | None = None
        self._last_error: str | None = None
        self._media_error: str | None = None
        self._presets: tuple[CamillaPreset, ...] = ()
        self._presets_checked_at_epoch_ms = 0
        self._payload: dict[str, object] | None = None
        self._payload_signature: object | None = None
        self._control_signature: object | None = None
        self._revision = 0
        self._main_authority: dict[str, object] | None = None
        self._main_query_counter = 0
        self._stop = asyncio.Event()
        # CamillaDSP accepts a single command/reply sequence on each socket.
        # Serialising polls and writes keeps an old poll from overwriting a
        # just-read-back command state.
        self._operation_lock = asyncio.Lock()
        self._command_cache: dict[tuple[str, str], tuple[str, dict[str, object]]] = {}
        self._command_order: deque[tuple[str, str]] = deque()
        # A socket acknowledgement without a matching player observation is
        # deliberately not represented as a successful or rejected command.
        # Remember it so a retry cannot replay a possibly applied transport
        # action; the client must reconcile by GET instead.
        self._unknown_transport: dict[tuple[str, str], str] = {}
        self._unknown_transport_order: deque[tuple[str, str]] = deque()
        self._session_factory: callable | None = None
        self._equalizer: EqualizerController | None = None
        self._equalizer_error: str | None = None
        self.sound: SoundController | None = None
        if self._audio.camilla.sound_state_file is not None:
            sound_client = CamillaDspClient(config.audio.camilla, persistent=True)
            try:
                self.sound = SoundController(CamillaSoundWire(sound_client), self._audio.camilla.sound_state_file)
            except (SoundError, ProtocolError, OSError):
                # A corrupt editor document must not remove otherwise healthy
                # faders, media, or telemetry. Fail closed for sound only.
                self.sound = None

    @property
    def configured(self) -> bool:
        return self._audio.enabled

    @property
    def snapshot(self) -> CamillaSnapshot | None:
        return self._latest

    @property
    def media_snapshot(self) -> RaspotifyMediaSnapshot | None:
        return self._media

    @property
    def last_error(self) -> str | None:
        return self._last_error

    @staticmethod
    def _freshness(observed_at_epoch_ms: int | None) -> str:
        if observed_at_epoch_ms is None:
            return "UNAVAILABLE"
        age = epoch_ms() - observed_at_epoch_ms
        if age < -5_000:
            return "INVALID"
        if age <= 2_500:
            return "LIVE"
        if age <= 30_000:
            return "STALE"
        return "LOST"

    @property
    def dsp_status(self) -> str:
        if not self._audio.enabled:
            return "UNAVAILABLE"
        return self._freshness(None if self._latest is None else self._latest.observed_at_epoch_ms)

    @property
    def media_status(self) -> str:
        if self._audio.media_provider not in {"raspotify_onevent_file", "spotify_connect"}:
            return "UNAVAILABLE"
        return self._freshness(None if self._media is None else self._media.observed_at_epoch_ms)

    def _spotify_control_available(self) -> bool:
        """Whether this exact source observation has a local control endpoint."""

        if (self._audio.media_provider != "spotify_connect" or self._media is None or
                self.media_status != "LIVE" or not self._media.connected):
            return False
        assert self._audio.spotify_connect.control_socket is not None
        try:
            return stat.S_ISSOCK(Path(self._audio.spotify_connect.control_socket).lstat().st_mode)
        except OSError:
            return False

    def _transport_capabilities(self) -> dict[str, bool]:
        media = self._media
        ready = self._spotify_control_available()
        context = bool(ready and media is not None and media.active and media.track_id is not None)
        return {
            "playPause": context,
            "nextPrevious": context,
            "seek": bool(context and media is not None and media.duration_ms is not None),
            "shuffleRepeat": bool(context and media is not None and media.shuffle is not None and media.repeat is not None),
        }

    @property
    def audio_status(self) -> str:
        """The least-fresh input state without treating a valid stale input as live."""

        if not self._audio.enabled:
            return "UNAVAILABLE"
        statuses = (self.dsp_status, self.media_status)
        for status in ("INVALID", "UNAVAILABLE", "LOST", "STALE"):
            if status in statuses:
                return status
        return "LIVE"

    @property
    def availability_reason(self) -> str | None:
        """Reason the DSP portion cannot be published.

        Media freshness is deliberately excluded: it is a separate source in
        the AUDIO contract.  Treating a stopped Spotify event feed as a DSP
        outage made the Camilla controls vanish whenever playback was idle.
        """
        if not self._audio.enabled:
            return "audio adapter is not configured"
        if self._latest is None:
            return self._last_error or "CamillaDSP has not produced a valid snapshot"
        if self.dsp_status not in {"LIVE", "STALE"}:
            return f"CamillaDSP snapshot is {self.dsp_status.lower()}"
        return None

    @property
    def write_available(self) -> bool:
        """A Camilla write is independent of Spotify playback evidence."""

        return (self._audio.hardware_available is True and
                self.availability_reason is None and self.dsp_status == "LIVE" and self._latest is not None and
                self._latest.state == "RUNNING")

    @staticmethod
    def _quality(status: str, timestamp: int | None, source: str) -> dict[str, object]:
        return {"status": status, "timestampEpochMs": timestamp, "source": source, "simulated": False}

    def _build_payload(self) -> dict[str, object] | None:
        if self.availability_reason is not None or self._latest is None:
            self._payload = None
            self._payload_signature = None
            return None
        dsp = self._latest
        media = self._media
        write_available = self.write_available
        transport_capabilities = self._transport_capabilities()
        equalizer = self._equalizer_view()
        signature = (dsp, media, self._presets, self.dsp_status, self.media_status, write_available,
                     tuple(sorted(transport_capabilities.items())), self._main_version(), equalizer)
        if signature == self._payload_signature and self._payload is not None:
            return self._payload
        # This timestamp is a gateway publication time only. The two input
        # qualities retain the original observation times from Camilla/librespot.
        published_at = epoch_ms()
        # A revision guards command-relevant state, not the observer's poll clock.
        # Keep publishing actual observation times, playback progress and clip counts
        # without making every GET->POST race a conflict. Real control, catalog and
        # availability changes still invalidate an older expectedRevision.
        control_signature = (
            replace(dsp, observed_at_epoch_ms=0, clipped_samples=None),
            None if media is None else replace(media, observed_at_epoch_ms=0, position_ms=None),
            self._presets, self.dsp_status, self.media_status, write_available,
            tuple(sorted(transport_capabilities.items())), self._main_version(),
            None if equalizer is None else {key:value for key,value in equalizer.items() if key!="observedAtEpochMs"},
        )
        if control_signature != self._control_signature:
            self._revision += 1
            self._control_signature = control_signature
        self._payload = {
            "version": 1,
            "kind": "audio",
            "source": self._audio.source_name,
            "sourceKind": "REAL",
            "simulated": False,
            "serverSessionId": self._server_session_id,
            "timestampEpochMs": published_at,
            "revision": self._revision,
            "units": {"faders": "dB", "meters": "dBFS", "position": "ms"},
            "audioSource": {
                "connected": False if media is None else media.connected,
                "active": False if media is None else media.active,
                "name": None,
                "quality": self._quality(self.media_status, None if media is None else media.observed_at_epoch_ms,
                                         self._audio.source_name),
            },
            "media": {
                "state": "STOPPED" if media is None else media.state,
                "title": None if media is None else media.title,
                "artists": [] if media is None else list(media.artists),
                "album": None if media is None else media.album,
                "trackId": None if media is None else media.track_id,
                # librespot's documented events carry cover URLs, but No5
                # AUDIO V1 uses stable artwork IDs, not URLs. Do not invent one.
                "artworkId": None,
                "durationMs": None if media is None else media.duration_ms,
                "positionMs": None if media is None else media.position_ms,
                "shuffle": None if media is None else media.shuffle,
                "repeat": None if media is None else media.repeat,
            },
            "dsp": {
                "state": dsp.state,
                "preset": dsp.config_title,
                "faders": {channel: {"db": value.db, "muted": value.muted}
                           for channel, value in dsp.faders.items()},
                "quality": self._quality(self.dsp_status, dsp.observed_at_epoch_ms, self._audio.source_name),
                "hardwareAvailable": self._audio.hardware_available,
                "clippedSamples": dsp.clipped_samples,
                "meters": None,
                # V1 extension: physical Camilla fader indexes, labels and
                # limits.  The legacy ``faders`` object remains for older
                # readers; new clients must render this list instead of
                # presenting semantic names that CamillaDSP never supplied.
                "faderControls": [
                    {
                        "id": channel,
                        "label": self._audio.camilla.fader_labels[channel],
                        "index": index,
                        "db": dsp.faders[channel].db,
                        "muted": dsp.faders[channel].muted,
                        "limit": (None if channel not in self._audio.limits else {
                            "minDb": self._audio.limits[channel].min_db,
                            "maxDb": self._audio.limits[channel].max_db,
                            "stepDb": self._audio.limits[channel].step_db,
                        }),
                    }
                    for channel, index in sorted(self._audio.camilla.fader_indexes.items(), key=lambda item: item[1])
                    if channel in dsp.faders
                ],
                "presets": [
                    {"id": preset.identifier, "name": preset.title,
                     # Equal titles do not prove an active profile. In particular,
                     # the preserved original and editable Sound variant share
                     # a title; only the actual DSP path identifies the variant.
                     "active": dsp.config_path == preset.path}
                    for preset in self._presets
                ],
            },
            "capabilities": {
                **transport_capabilities,
                "faderWrite": write_available, "mute": write_available,
                "presetWrite": write_available and bool(self._presets),
            },
            "authority": "CAMILLA_DSP_MASTER",
            # Range publication is independent of write availability. A client
            # needs the configured from/to dB range to render a truthful
            # read-only control; the capability still prevents writes.
            "limits": ({channel: {"minDb": limit.min_db, "maxDb": limit.max_db, "stepDb": limit.step_db}
                        for channel, limit in self._audio.limits.items()} or None),
        }
        if self._audio.camilla.equalizer_state_file is not None:
            self._payload["capabilities"]["equalizer"] = equalizer is not None and equalizer["writable"]
            if equalizer is not None:
                self._payload["dsp"]["equalizer"] = equalizer
        self._payload_signature = signature
        self._decorate_sound()
        return self._payload

    def _equalizer_view(self) -> dict | None:
        current = None if self._equalizer is None else self._equalizer.current
        if current is None:
            return None
        return {**current, "writable": self.write_available and self._equalizer_error is None and
                self._freshness(current["observedAtEpochMs"]) == "LIVE"}

    async def _observe_equalizer(self, session) -> None:
        path = self._audio.camilla.equalizer_state_file
        if path is None:
            return
        try:
            if self._equalizer is None:
                self._equalizer = await asyncio.to_thread(EqualizerController, path)
            await self._equalizer.observe(self._client, session)
            self._equalizer_error = None
        except asyncio.CancelledError:
            raise
        except Exception as exc:
            # EQ unavailability never disables otherwise healthy faders/audio.
            self._equalizer_error = f"Equalizer input rejected: {type(exc).__name__}"

    def _decorate_sound(self) -> None:
        if self._payload is not None and self.sound is not None:
            sound = self.sound.summary()
            self._payload["capabilities"]["sound"] = sound["writable"] and self.write_available
            self._payload["dsp"]["sound"] = {key: sound[key] for key in
                ("available", "presetRevision", "calibrationRevision", "configurationRevision", "headroomDb", "eqEnabled", "reserveDb", "eqPolicy", "capabilities")}

    @property
    def payload(self) -> dict[str, object] | None:
        payload = self._build_payload()
        self._decorate_sound()
        return payload

    async def sound_command_authorized(self, owner, request, authorization_valid, *, admitted_monotonic=None):
        validate_audio_command(request)
        if self.sound is None or not self.write_available:
            return self._rejected_ack(request, "Owned sound processor write unavailable")
        state = self.payload
        if state is None or request["serverSessionId"] != state["serverSessionId"]:
            return self._rejected_ack(request, "Gateway source changed")
        try:
            sound = await self.sound.command(owner, request["command"], authorization_valid,
                activity_deadline=None if admitted_monotonic is None else admitted_monotonic + self.sound.PREVIEW_SECONDS)
        except SoundUnknown as exc:
            raise AudioOutcomeUnknown(str(exc)) from exc
        except SoundError as exc:
            return self._rejected_ack(request, str(exc))
        # Sound has independent model/DSP revisions. Its verified receipt must
        # not synthesize a fader/media CAS change, otherwise a concurrent WSS
        # fader is rejected despite no conflicting control change.
        ack = self._verified_ack(request, "Sound audio-block readback verified", force_control_revision=False)
        # Inactive deletion is a durable library edit, with no DSP write.
        # Active deletion additionally applies/readbacks its replacement sound.
        ack["verification"] = "SOUND_STATE_COMMIT" if request["command"]["type"] == "soundDeletePreset" else "AUDIO_BLOCK_READBACK"
        ack["sound"] = sound
        return ack

    @staticmethod
    def _normalise_step(value: float, *, min_db: float, step_db: float) -> float:
        return math.floor((value - min_db) / step_db + 0.5) * step_db + min_db

    def _rejected_ack(self, request: dict[str, object], message: str) -> dict[str, object]:
        state = self._build_payload()
        if state is None:
            raise AudioError(self.availability_reason or "AUDIO source is unavailable")
        return {
            "version": 1,
            "kind": "audio-ack",
            "source": state["source"],
            "sourceKind": state["sourceKind"],
            "simulated": state["simulated"],
            "serverSessionId": state["serverSessionId"],
            "timestampEpochMs": state["timestampEpochMs"],
            "revision": state["revision"],
            "sessionId": request["sessionId"],
            "requestId": request["requestId"],
            "status": "REJECTED",
            "message": message,
            "state": state,
        }

    def _verified_ack(self, request: dict[str, object], message: str, *, force_control_revision: bool = True) -> dict[str, object]:
        # Fader/media writes advance their CAS revision even for an intentional
        # no-op. Sound receipts use their own revisions and only observe actual
        # fader/media changes. The request cache never repeats a verified write.
        self._payload_signature = None
        if force_control_revision:
            self._control_signature = None
        state = self._build_payload()
        if state is None:
            raise AudioError("CamillaDSP readback did not produce a complete AUDIO state")
        return {
            "version": 1,
            "kind": "audio-ack",
            "source": state["source"],
            "sourceKind": state["sourceKind"],
            "simulated": state["simulated"],
            "serverSessionId": state["serverSessionId"],
            "timestampEpochMs": state["timestampEpochMs"],
            "revision": state["revision"],
            "sessionId": request["sessionId"],
            "requestId": request["requestId"],
            "status": "VERIFIED",
            "message": message,
            "state": state,
        }

    def _cache_ack(self, request: dict[str, object], fingerprint: str, ack: dict[str, object]) -> None:
        key = (request["sessionId"], request["requestId"])
        assert isinstance(key[0], str) and isinstance(key[1], str)
        self._command_cache[key] = (fingerprint, ack)
        self._command_order.append(key)
        while len(self._command_order) > 128:
            stale = self._command_order.popleft()
            self._command_cache.pop(stale, None)

    def _mark_transport_unknown(self, key: tuple[str, str], fingerprint: str) -> None:
        self._unknown_transport[key] = fingerprint
        self._unknown_transport_order.append(key)
        while len(self._unknown_transport_order) > 128:
            stale = self._unknown_transport_order.popleft()
            self._unknown_transport.pop(stale, None)

    def _media_readback_matches(self, before: RaspotifyMediaSnapshot, after: RaspotifyMediaSnapshot,
                                command: dict[str, object], issued_at_epoch_ms: int) -> bool:
        """Match an observed receiver state without deriving a playback clock."""

        # Event timestamps are millisecond precision. A real state transition
        # observed in the same millisecond is still readback evidence; the
        # action-specific tests below require a changed player state/track.
        if after.observed_at_epoch_ms < before.observed_at_epoch_ms:
            return False
        action = command["action"]
        assert isinstance(action, str)
        if action == "PLAY":
            return after.state == "PLAYING"
        if action == "PAUSE":
            return after.state == "PAUSED"
        if action in {"NEXT", "PREVIOUS"}:
            return before.track_id is not None and after.track_id is not None and after.track_id != before.track_id
        if action == "SEEK":
            requested = command["positionMs"]
            assert isinstance(requested, int)
            if after.position_ms is None:
                return False
            # The source can emit its one-second position event shortly after
            # a seek.  Permit only the elapsed player-time window, never an
            # unbounded approximate UI clock.
            elapsed = max(0, after.observed_at_epoch_ms - issued_at_epoch_ms)
            return requested <= after.position_ms <= requested + elapsed + 250
        if action == "SHUFFLE":
            return after.shuffle is not None and before.shuffle is not None and after.shuffle is not before.shuffle
        if action == "REPEAT":
            return after.repeat is not None and before.repeat is not None and after.repeat is not before.repeat
        raise AudioError("unsupported Spotify transport action")

    def _spotify_action(self, command: dict[str, object]) -> dict[str, object]:
        media = self._media
        capabilities = self._transport_capabilities()
        if media is None or not self._spotify_control_available():
            raise AudioError("Spotify Connect transport is unavailable; command was not sent")
        action = command["action"]
        assert isinstance(action, str)
        if action in {"PLAY", "PAUSE"}:
            if not capabilities["playPause"]:
                raise AudioError("Spotify play/pause capability is unavailable; command was not sent")
            return {"type": action}
        if action in {"NEXT", "PREVIOUS"}:
            if not capabilities["nextPrevious"]:
                raise AudioError("Spotify next/previous needs a live current track; command was not sent")
            return {"type": action}
        if action == "SEEK":
            if not capabilities["seek"]:
                raise AudioError("Spotify seek needs a live track duration; command was not sent")
            position = command["positionMs"]
            assert isinstance(position, int) and media.duration_ms is not None
            if position > media.duration_ms:
                raise AudioError("Spotify seek position exceeds the observed track duration; command was not sent")
            return {"type": "SEEK", "position_ms": position}
        if action == "SHUFFLE":
            if not capabilities["shuffleRepeat"] or media.shuffle is None:
                raise AudioError("Spotify shuffle capability is unavailable; command was not sent")
            return {"type": "SHUFFLE", "enabled": not media.shuffle}
        if action == "REPEAT":
            if not capabilities["shuffleRepeat"] or media.repeat is None:
                raise AudioError("Spotify repeat capability is unavailable; command was not sent")
            return {"type": "REPEAT", "enabled": not media.repeat}
        raise AudioError("unsupported Spotify transport action")

    async def _send_spotify_control(self, request_id: str, action: dict[str, object]) -> None:
        assert self._audio.spotify_connect.control_socket is not None
        await _spotify_control_request(
            Path(self._audio.spotify_connect.control_socket), request_id, action,
            timeout_s=self._audio.spotify_connect.command_timeout_s,
        )

    async def _read_media_snapshot(self) -> RaspotifyMediaSnapshot:
        if self._audio.media_provider == "raspotify_onevent_file":
            assert self._audio.raspotify.state_file is not None
            return _read_strict_state_file(Path(self._audio.raspotify.state_file))
        if self._audio.media_provider == "spotify_connect":
            assert self._audio.spotify_connect.state_file is not None
            return _read_strict_state_file(
                Path(self._audio.spotify_connect.state_file), expected_kind="spotify-connect-state",
            )
        raise AudioError("audio media source is unavailable")

    async def _await_spotify_readback(self, before: RaspotifyMediaSnapshot, command: dict[str, object],
                                      issued_at_epoch_ms: int) -> bool:
        deadline = time.monotonic() + self._audio.spotify_connect.command_timeout_s
        while True:
            try:
                candidate = await self._read_media_snapshot()
                self._media = candidate
                self._media_error = None
                if self._media_readback_matches(before, candidate, command, issued_at_epoch_ms):
                    return True
            except (OSError, AudioError):
                # Preserve the previous verified source state.  A malformed
                # replacement cannot turn a local command into a verified one.
                pass
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                return False
            await asyncio.sleep(min(0.1, remaining))

    async def _command_spotify_transport(self, request: dict[str, object], fingerprint: str,
                                         key: tuple[str, str]) -> dict[str, object]:
        command = request["command"]
        assert isinstance(command, dict) and self._media is not None
        before = self._media
        action = self._spotify_action(command)
        issued_at = epoch_ms()
        await self._send_spotify_control(str(request["requestId"]), action)
        if not await self._await_spotify_readback(before, command, issued_at):
            self._mark_transport_unknown(key, fingerprint)
            raise AudioOutcomeUnknown(
                "Spotify receiver accepted the command but no matching player readback arrived; refresh AUDIO state and do not replay"
            )
        ack = self._verified_ack(request, "Spotify Connect player readback verified")
        self._cache_ack(request, fingerprint, ack)
        return ack

    def _main_version(self) -> tuple[object, object] | None:
        if self._main_authority is None:
            return None
        return self._main_authority["sessionId"], self._main_authority["revision"]

    def _accept_main_confirmation(self, confirmation: dict[str, object]) -> None:
        value = _finite_db(confirmation["valueDb"], "Main owner value")
        limit = self._audio.limits.get("MASTER")
        if (limit is None or self._latest is None or not limit.min_db <= value <= limit.max_db or
                not isinstance(confirmation["muted"], bool)):
            raise AudioError("Main owner state violates the configured contract")
        self._main_authority = confirmation
        self._latest = replace(self._latest,
            faders=self._latest.faders | {"MASTER": CamillaFader(value, confirmation["muted"])})
        # A Main-only observation must not refresh older Aux/config/meter data.

    async def _observe_main_authority(self) -> None:
        if not self._audio.spotify_connect.master_volume_bridge or self._latest is None:
            return
        self._main_query_counter += 1
        confirmation = await _spotify_control_request(Path(self._audio.spotify_connect.control_socket),
            f"{self._server_session_id}:main-read:{self._main_query_counter}", {"type": "MASTER_STATE"}, timeout_s=1.0)
        if confirmation is None:
            raise AudioError("Main owner did not produce an execution-confirmed snapshot")
        self._accept_main_confirmation(confirmation)

    async def command_authorized(self, request: dict[str, object], authorization_valid) -> dict[str, object]:
        return await self.command(request, authorization_valid=authorization_valid)

    async def command(self, request: dict[str, object], *, authorization_valid=None) -> dict[str, object]:
        """Execute a revision-bound Camilla or readback-capable Spotify action."""

        validate_audio_command(request)
        if request["command"]["type"] in SOUND_TYPES:
            raise AudioError("Sound editing requires the asynchronous commands owner")
        fingerprint = json.dumps(request, sort_keys=True, separators=(",", ":"), allow_nan=False)
        key = (request["sessionId"], request["requestId"])
        assert isinstance(key[0], str) and isinstance(key[1], str)
        async with self._operation_lock:
            previous = self._command_cache.get(key)
            if previous is not None:
                if previous[0] != fingerprint:
                    raise AudioError("audio command requestId was already used for a different request")
                return previous[1]
            unknown = self._unknown_transport.get(key)
            if unknown is not None:
                if unknown != fingerprint:
                    raise AudioError("audio command requestId was already used for a different request")
                raise AudioOutcomeUnknown(
                    "Spotify command outcome is already unknown; refresh AUDIO state and do not replay"
                )
            # Observe the final authority under the command lock. Its version
            # detects phone/other-producer changes, including A->B->A.
            await self._observe_main_authority()
            current = self._build_payload()
            if current is None:
                raise AudioError(self.availability_reason or "AUDIO source is unavailable")
            if request["serverSessionId"] != current["serverSessionId"]:
                ack = self._rejected_ack(request, "Gateway session changed; command was not sent")
                self._cache_ack(request, fingerprint, ack)
                return ack
            if request["expectedRevision"] != current["revision"]:
                ack = self._rejected_ack(request, "AUDIO state changed; command was not sent")
                self._cache_ack(request, fingerprint, ack)
                return ack
            command = request["command"]
            assert isinstance(command, dict)
            command_type = command["type"]
            if command_type == "transport":
                if self._audio.media_provider != "spotify_connect":
                    ack = self._rejected_ack(
                        request, "Spotify transport requires the No5 Spotify Connect receiver; command was not sent"
                    )
                    self._cache_ack(request, fingerprint, ack)
                    return ack
                try:
                    return await self._command_spotify_transport(request, fingerprint, key)
                except AudioOutcomeUnknown:
                    raise
                except AudioError as exc:
                    ack = self._rejected_ack(request, str(exc))
                    self._cache_ack(request, fingerprint, ack)
                    return ack
            if not self.write_available:
                ack = self._rejected_ack(request, "CamillaDSP write capability is unavailable; command was not sent")
                self._cache_ack(request, fingerprint, ack)
                return ack
            if command_type == "equalizer":
                available = self._equalizer_view()
                if available is None or not available["writable"] or self._equalizer is None:
                    ack = self._rejected_ack(request, "Owned master equalizer is unavailable; command was not sent")
                    self._cache_ack(request, fingerprint, ack)
                    return ack
                if command["configGeneration"] != available["configGeneration"]:
                    ack = self._rejected_ack(request, "Equalizer profile generation changed; command was not sent")
                    self._cache_ack(request, fingerprint, ack)
                    return ack
                if self._session_factory is None:
                    import aiohttp
                    session_context = aiohttp.ClientSession(timeout=aiohttp.ClientTimeout(total=6.0))
                else:
                    session_context = self._session_factory()
                self._mark_transport_unknown(key, fingerprint)
                try:
                    async with session_context as session:
                        await self._equalizer.apply(self._client, session, command,
                            lambda: self.write_available and (authorization_valid is None or authorization_valid()))
                        self._latest = await self._client.read_snapshot(session)
                    self._equalizer_error = None
                except EqualizerUnknown as exc:
                    self._equalizer_error = "Equalizer completion unknown"
                    raise AudioOutcomeUnknown(str(exc)) from exc
                except EqualizerError as exc:
                    ack = self._rejected_ack(request, str(exc))
                    self._cache_ack(request, fingerprint, ack)
                    return ack
                except Exception as exc:
                    self._equalizer_error = "Equalizer completion unknown"
                    raise AudioOutcomeUnknown("Equalizer post-action observation unknown; do not replay") from exc
                ack = self._verified_ack(request, "Equalizer active configuration readback verified")
                ack["verification"] = "ACTIVE_CONFIG_READBACK"
                self._cache_ack(request, fingerprint, ack)
                return ack
            if command_type == "preset":
                preset_id = command["id"]
                assert isinstance(preset_id, str)
                preset = next((item for item in self._presets if item.identifier == preset_id), None)
                if preset is None or self._latest is None:
                    ack = self._rejected_ack(request, "CamillaDSP preset is no longer in the validated catalog; command was not sent")
                    self._cache_ack(request, fingerprint, ack)
                    return ack
                if self._session_factory is None:
                    import aiohttp
                    session_context = aiohttp.ClientSession(timeout=aiohttp.ClientTimeout(total=8.0))
                else:
                    session_context = self._session_factory()
                async with session_context as session:
                    self._latest = await self._client.apply_preset(session, preset, self._latest.config_path)
                    await self._observe_equalizer(session)
                self._last_error = None
                ack = self._verified_ack(request, "CamillaDSP preset reload verified")
                self._cache_ack(request, fingerprint, ack)
                return ack
            if command_type not in {"fader", "mute"}:
                raise AudioError("unsupported audio command")
            channel = command["channel"]
            assert isinstance(channel, str)
            limit = self._audio.limits.get(channel)
            if limit is None:
                ack = self._rejected_ack(request, "Configured CamillaDSP limit is unavailable; command was not sent")
                self._cache_ack(request, fingerprint, ack)
                return ack
            if command_type == "fader":
                requested = _finite_db(command["valueDb"], "audio fader value")
                rounded = self._normalise_step(requested, min_db=limit.min_db, step_db=limit.step_db)
                if not limit.min_db <= requested <= limit.max_db or not math.isclose(requested, rounded, abs_tol=0.000_001):
                    ack = self._rejected_ack(request, "Fader value is outside the configured CamillaDSP safety limit")
                    self._cache_ack(request, fingerprint, ack)
                    return ack
            if self._session_factory is None:
                import aiohttp
                session_context = aiohttp.ClientSession(timeout=aiohttp.ClientTimeout(total=4.0))
            else:
                session_context = self._session_factory()
            async with session_context as session:
                if channel == "MASTER" and self._audio.spotify_connect.master_volume_bridge:
                    assert self._main_authority is not None
                    expected = {"sessionId": self._main_authority["sessionId"], "revision": self._main_authority["revision"]}
                    action = ({"type": "MASTER_VOLUME", "value_db": command["valueDb"], "expected": expected}
                              if command_type == "fader" else {"type": "MASTER_MUTE", "muted": command["muted"], "expected": expected})
                    assert self._audio.spotify_connect.control_socket is not None
                    self._mark_transport_unknown(key, fingerprint)
                    try:
                        confirmation = await _spotify_control_request(Path(self._audio.spotify_connect.control_socket),
                            str(request["requestId"]), action, timeout_s=1.0)
                    except AudioOutcomeUnknown:
                        raise
                    except AudioError as exc:
                        # A strict receiver rejection proves no write occurred.
                        ack = self._rejected_ack(request, str(exc))
                        self._cache_ack(request, fingerprint, ack)
                        return ack
                    if confirmation is None:
                        raise AudioOutcomeUnknown("Main command lacks an individual owner receipt; do not replay")
                    confirmed = (math.isclose(float(confirmation["valueDb"]), command["valueDb"], abs_tol=0.01)
                                 if command_type == "fader" else confirmation["muted"] is command["muted"])
                    if not confirmed:
                        raise AudioOutcomeUnknown("Main owner receipt did not confirm this command; do not replay")
                    self._accept_main_confirmation(confirmation)
                    # The receipt proves this command's own execution. Read
                    # fresh complete state for publication, even if another
                    # producer has already made a later change. Never wait for
                    # a coincidentally matching global value as execution proof.
                    self._latest = await self._client.read_snapshot(session)
                    await self._observe_main_authority()
                elif command_type == "fader":
                    self._latest = await self._client.set_fader_db(session, channel, command["valueDb"])
                else:
                    self._latest = await self._client.set_fader_mute(session, channel, command["muted"])
            self._last_error = None
            ack = self._verified_ack(request, "CamillaDSP readback verified")
            self._cache_ack(request, fingerprint, ack)
            return ack

    async def _read_media(self) -> None:
        if self._audio.media_provider not in {"raspotify_onevent_file", "spotify_connect"}:
            return
        try:
            self._media = await self._read_media_snapshot()
            self._media_error = None
        except Exception as exc:
            # Preserve a prior source observation long enough to publish its
            # STALE/LOST state; do not turn a file-read receipt into fresh data.
            self._media_error = f"Spotify receiver state rejected: {type(exc).__name__}"

    async def stop(self) -> None:
        self._stop.set()
        if self.sound is not None:
            await self.sound.stop()
        async with self._operation_lock:
            if hasattr(self._client, "close"):
                await self._client.close()

    async def run(self) -> None:
        if self.sound is not None and self._audio.enabled:
            self.sound.start()
        if not self._audio.enabled:
            return
        import aiohttp

        timeout = aiohttp.ClientTimeout(total=3.0)
        intervals = [self._audio.camilla.poll_interval_s]
        if self._audio.raspotify.enabled:
            intervals.append(self._audio.raspotify.poll_interval_s)
        if self._audio.spotify_connect.enabled:
            intervals.append(self._audio.spotify_connect.poll_interval_s)
        poll_interval = min(intervals)
        async with aiohttp.ClientSession(timeout=timeout) as session:
            while not self._stop.is_set():
                try:
                    async with self._operation_lock:
                        self._latest = await self._client.read_snapshot(session)
                        await self._observe_main_authority()
                        await self._observe_equalizer(session)
                        now = epoch_ms()
                        if (now - self._presets_checked_at_epoch_ms >=
                                int(self._audio.camilla.preset_refresh_s * 1_000)):
                            self._presets = await self._client.read_presets(session)
                            if self.sound is not None:
                                routes = {}
                                for preset in self._presets:
                                    if preset.sound_routing is not None:
                                        routes.setdefault(preset.sound_routing, []).append(preset)
                                self.sound.routing_profiles = {routing: values[0].identifier for routing, values in routes.items() if len(values) == 1}
                            self._presets_checked_at_epoch_ms = now
                        self._last_error = None
                except asyncio.CancelledError:
                    raise
                except Exception as exc:
                    # As with the event file, retain a genuine prior observation
                    # so freshness can age explicitly instead of becoming fresh.
                    self._last_error = f"CamillaDSP input rejected: {type(exc).__name__}"
                await self._read_media()
                self._build_payload()
                with contextlib.suppress(asyncio.TimeoutError):
                    await asyncio.wait_for(self._stop.wait(), poll_interval)
