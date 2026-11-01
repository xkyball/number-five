"""Owned CamillaDSP 4.1.3 master EQ; never a general configuration patch API.

The five peaking responses use Q=1. The sum of positive gains is a conservative
bound on their combined *nominal frequency response*, not a guarantee for all
transients. A preceding owned Gain attenuates by that bound in the same config.
Camilla's GetConfigJson proves active controller configuration, not the first
processed audio block. See upstream v4.1.3 socketserver.rs/bin.rs/processing.rs.
"""
from __future__ import annotations

import asyncio
import copy
import hashlib
import json
import math
import os
from pathlib import Path
import re
import stat
import tempfile
import time

from .protocol import strict_json_object

FREQUENCIES = (63, 250, 1000, 4000, 12000)
HEADROOM = "no5_eq_master_headroom"
BANDS = tuple(f"no5_eq_master_{frequency}" for frequency in FREQUENCIES)
NAMES = (HEADROOM, *BANDS)
GENERATION = re.compile(r"[0-9a-f]{64}\Z")
VERIFICATION = "ACTIVE_CONFIG_READBACK"
STATE_SCHEMA = "no5.master-equalizer-state/1"


class EqualizerError(ValueError):
    """No EQ effect was requested, or the owned topology is unavailable."""


class EqualizerUnknown(EqualizerError):
    """A sent configuration/durability outcome must be reconciled, never replayed."""


def gains(values: object) -> tuple[float, ...]:
    if not isinstance(values, list) or len(values) != 5:
        raise EqualizerError("equalizer needs exactly five gains")
    result = []
    for value in values:
        if (isinstance(value, bool) or not isinstance(value, (int, float)) or
                not -6 <= value <= 6 or not math.isfinite(value) or value != round(value)):
            raise EqualizerError("equalizer gains must be finite integer dB within -6..6")
        result.append(float(value))
    return tuple(result)


def validate_command(command: dict) -> None:
    if (set(command) != {"type", "bankId", "configGeneration", "gainsDb"} or
            command.get("type") != "equalizer" or command.get("bankId") != "master" or
            not isinstance(command.get("configGeneration"), str) or
            GENERATION.fullmatch(command["configGeneration"]) is None):
        raise EqualizerError("equalizer command fields or config generation are invalid")
    gains(command["gainsDb"])


def _nodes(values: tuple[float, ...]) -> dict:
    headroom = sum(max(0.0, value) for value in values)
    nodes = {HEADROOM: {"type": "Gain", "description": "No5 master EQ headroom v1",
                       "parameters": {"gain": -headroom, "inverted": False, "mute": False, "scale": "dB"}}}
    for name, frequency, value in zip(BANDS, FREQUENCIES, values):
        nodes[name] = {"type": "Biquad", "description": "No5 master EQ band v1",
                       "parameters": {"type": "Peaking", "freq": frequency, "q": 1.0, "gain": value}}
    return nodes


def _stage() -> dict:
    return {"type": "Filter", "channels": [0, 1], "names": list(NAMES),
            "description": "No5 master EQ v1", "bypassed": False}


def _first_stereo_mixer(config: dict) -> int:
    pipeline = config.get("pipeline")
    if not isinstance(pipeline, list) or not pipeline:
        raise EqualizerError("equalizer requires an existing stereo mixer pipeline")
    capture = config.get("devices", {}).get("capture", {})
    if capture.get("channels") != 2:
        raise EqualizerError("equalizer requires two capture input channels")
    for index, step in enumerate(pipeline):
        if not isinstance(step, dict):
            raise EqualizerError("invalid Camilla pipeline step")
        if step.get("type") == "Mixer":
            mixer = (config.get("mixers") or {}).get(step.get("name"), {})
            if mixer.get("channels", {}).get("in") != 2 or step.get("bypassed") not in (None, False):
                raise EqualizerError("the first active mixer is not a stereo input mixer")
            return index
    raise EqualizerError("equalizer requires an existing stereo mixer")


def _owned_values(config: dict) -> tuple[float, ...]:
    filters = config.get("filters")
    if not isinstance(filters, dict) or any(name not in filters for name in NAMES):
        raise EqualizerError("owned master equalizer nodes have not been provisioned")
    if any(name.startswith("no5_eq_master_") and name not in NAMES for name in filters):
        raise EqualizerError("unknown reserved equalizer node")
    try:
        values = gains([filters[name]["parameters"]["gain"] for name in BANDS])
    except (KeyError, TypeError) as exc:
        raise EqualizerError("invalid owned equalizer nodes") from exc
    expected = _nodes(values)
    if any(filters[name] != expected[name] for name in NAMES):
        raise EqualizerError("owned equalizer structure or headroom is inconsistent")
    mixer_index = _first_stereo_mixer(config)
    if mixer_index < 1 or config["pipeline"][mixer_index - 1] != _stage():
        raise EqualizerError("owned equalizer must immediately precede the stereo mixer")
    references = []
    for index, step in enumerate(config["pipeline"]):
        names = step.get("names", [])
        if not isinstance(names, list):
            raise EqualizerError("invalid filter chain")
        references += [(index, name) for name in names if name in NAMES]
    if references != [(mixer_index - 1, name) for name in NAMES]:
        raise EqualizerError("owned equalizer nodes must appear exactly once")
    return values


def provision_equalizer(config: dict) -> dict:
    """Return a safe neutral topology, preserving a complete valid existing EQ.

    This pure helper is shared with the privileged rebuild provisioner. The
    unprivileged gateway never edits a preset file or installs missing nodes.
    """
    result = copy.deepcopy(config)
    filters = result.get("filters")
    if filters is None:
        filters = result["filters"] = {}
    if not isinstance(filters, dict):
        raise EqualizerError("Camilla filters must be an object")
    own = [name for name in filters if name.startswith("no5_eq_master_")]
    referenced = any(name in NAMES for step in result.get("pipeline") or [] for name in step.get("names", []))
    if own or referenced:
        _owned_values(result)
        return result
    index = _first_stereo_mixer(result)
    filters.update(_nodes((0.0,) * 5))
    result["pipeline"].insert(index, _stage())
    _owned_values(result)
    return result


def snapshot(config: dict, profile_path: str | None, observed_ms: int) -> dict:
    values = _owned_values(config)
    basis = copy.deepcopy(config)
    for name in NAMES:
        basis["filters"][name]["parameters"]["gain"] = 0.0
    encoded = json.dumps({"profilePath": profile_path, "config": basis}, sort_keys=True,
                         separators=(",", ":"), allow_nan=False).encode()
    return {"bankId": "master", "configGeneration": hashlib.sha256(encoded).hexdigest(),
            "frequenciesHz": list(FREQUENCIES), "gainsDb": list(values), "minDb": -6,
            "maxDb": 6, "stepDb": 1, "headroomDb": sum(max(0.0, value) for value in values),
            "writable": True, "observedAtEpochMs": observed_ms, "verification": VERIFICATION}


class EqualizerStore:
    """Atomic profile-bound state, with a durable no-restore marker before sends."""
    def __init__(self, path: str):
        self.path = Path(path)
        self.state = {"schema": STATE_SCHEMA, "profiles": {}, "blockedGenerations": []}
        try:
            metadata = self.path.lstat()
        except FileNotFoundError:
            return
        if not stat.S_ISREG(metadata.st_mode) or not 0 < metadata.st_size <= 65_536:
            raise EqualizerError("equalizer state must be a bounded regular file")
        descriptor = os.open(self.path, os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0))
        with os.fdopen(descriptor, "rb") as handle:
            value = strict_json_object(handle.read(65_537))
        if (set(value) != {"schema", "profiles", "blockedGenerations"} or value["schema"] != STATE_SCHEMA or
                not isinstance(value["profiles"], dict) or len(value["profiles"]) > 64 or
                not isinstance(value["blockedGenerations"], list) or len(value["blockedGenerations"]) > 64):
            raise EqualizerError("equalizer state schema is invalid")
        for generation, record in value["profiles"].items():
            if not GENERATION.fullmatch(generation) or not isinstance(record, dict) or set(record) != {"gainsDb"}:
                raise EqualizerError("equalizer saved profile is invalid")
            gains(record["gainsDb"])
        if any(not isinstance(item, str) or not GENERATION.fullmatch(item) for item in value["blockedGenerations"]):
            raise EqualizerError("equalizer restore marker is invalid")
        self.state = value

    def _replace(self, value: dict) -> None:
        if len(value["profiles"]) > 64 or len(value["blockedGenerations"]) > 64:
            raise EqualizerError("equalizer durable profile capacity reached")
        encoded = json.dumps(value, sort_keys=True, separators=(",", ":"), allow_nan=False).encode()
        if len(encoded) > 65_536:
            raise EqualizerError("equalizer durable state is too large")
        metadata = self.path.parent.lstat()
        if not stat.S_ISDIR(metadata.st_mode):
            raise EqualizerError("equalizer state directory is unsafe")
        temporary = None
        try:
            descriptor, temporary = tempfile.mkstemp(prefix=".master-equalizer-", dir=self.path.parent)
            with os.fdopen(descriptor, "wb") as handle:
                os.fchmod(handle.fileno(), 0o600)
                handle.write(encoded);handle.flush();os.fsync(handle.fileno())
            os.replace(temporary, self.path)
            directory = os.open(self.path.parent, os.O_RDONLY | getattr(os, "O_DIRECTORY", 0))
            try:
                os.fsync(directory)
            finally:
                os.close(directory)
            self.state = value
        finally:
            if temporary is not None:
                try:os.unlink(temporary)
                except FileNotFoundError:pass

    def stage(self, generation: str) -> None:
        value = copy.deepcopy(self.state)
        if generation not in value["blockedGenerations"]:
            value["blockedGenerations"].append(generation)
        self._replace(value)

    def commit(self, generation: str, values: tuple[float, ...]) -> None:
        value = copy.deepcopy(self.state)
        value["profiles"][generation] = {"gainsDb": list(values)}
        value["blockedGenerations"] = [item for item in value["blockedGenerations"] if item != generation]
        self._replace(value)

    def restore(self, generation: str) -> tuple[float, ...] | None:
        if generation in self.state["blockedGenerations"]:
            return None
        saved = self.state["profiles"].get(generation)
        return None if saved is None else gains(saved["gainsDb"])


class EqualizerController:
    """Serialized by the existing adapter operation lock, sharing its Camilla WS."""
    def __init__(self, state_file: str):
        self.store = EqualizerStore(state_file)
        self.current: dict | None = None
        self.restore_checked: dict[str, None] = {}

    def _remember_restore(self, generation: str) -> None:
        self.restore_checked[generation] = None
        while len(self.restore_checked) > 64:
            self.restore_checked.pop(next(iter(self.restore_checked)))

    @staticmethod
    async def _read(client, socket) -> tuple[dict, str | None, dict]:
        raw = await client._request(socket, "GetConfigJson")
        if not isinstance(raw, str) or len(raw.encode()) > 65_536:
            raise EqualizerError("active equalizer config is absent or exceeds the WS bound")
        config = strict_json_object(raw.encode())
        path = await client._request(socket, "GetConfigFilePath")
        if path is not None and (not isinstance(path, str) or not path.startswith("/") or len(path) > 1024):
            raise EqualizerError("active equalizer profile path is invalid")
        state = await client._request(socket, "GetState")
        if state not in {"Running", "Paused"}:
            raise EqualizerError("equalizer needs a running or paused Camilla pipeline")
        return config, path, snapshot(config, path, time.time_ns() // 1_000_000)

    async def observe(self, client, session) -> dict:
        async with client._socket(session) as socket:
            config, path, current = await self._read(client, socket)
            generation = current["configGeneration"]
            self.current = current
            if generation not in self.restore_checked:
                self._remember_restore(generation)
                saved = self.store.restore(generation)
                if saved is not None and not any(current["gainsDb"]) and any(saved):
                    current = await self._apply(client, socket, generation, saved, lambda: True)
            self.current = current
            return current

    async def apply(self, client, session, command: dict, authorized) -> dict:
        validate_command(command)
        generation = command["configGeneration"]
        self._remember_restore(generation)
        async with client._socket(session) as socket:
            return await self._apply(client, socket, generation, gains(command["gainsDb"]), authorized)

    async def _apply(self, client, socket, generation: str, values: tuple[float, ...], authorized) -> dict:
        # Persist a no-restore marker first. A crash/unknown write can never replay
        # either this unconfirmed target or an older saved boost on neutral reset.
        try:
            await asyncio.to_thread(self.store.stage, generation)
        except Exception as exc:
            raise EqualizerError("equalizer durable admission failed; no command was sent") from exc
        attempted = False
        try:
            config, path, before = await self._read(client, socket)
            if before["configGeneration"] != generation:
                self.current = before
                raise EqualizerError("equalizer profile generation changed; no command was sent")
            if not authorized():
                raise EqualizerError("equalizer authorization expired; no command was sent")
            if tuple(before["gainsDb"]) != values:
                patch = {"filters": {name: {"parameters": {"gain": node["parameters"]["gain"]}}
                                     for name, node in _nodes(values).items()}}
                attempted = True
                await client._request(socket, "PatchConfig", patch, has_argument=True, needs_value=False)
                deadline = time.monotonic() + 1.5
                while True:
                    _config, _path, observed = await self._read(client, socket)
                    if observed["configGeneration"] != generation:
                        self.current = observed
                        raise EqualizerUnknown("equalizer profile changed after send; do not replay")
                    if tuple(observed["gainsDb"]) == values:
                        break
                    if time.monotonic() >= deadline:
                        raise EqualizerUnknown("active equalizer readback was not confirmed; do not replay")
                    await asyncio.sleep(0.02) # Observation retry only, never a repeated write or processing ACK.
            else:
                observed = before
            await asyncio.to_thread(self.store.commit, generation, values)
            self.current = observed
            return observed
        except EqualizerUnknown:
            raise
        except EqualizerError:
            if attempted:
                raise EqualizerUnknown("equalizer readback became invalid after send; do not replay")
            raise
        except Exception as exc:
            if attempted:
                raise EqualizerUnknown("equalizer completion/durability is unknown; do not replay") from exc
            raise EqualizerError("equalizer could not be confirmed; no command was sent") from exc
