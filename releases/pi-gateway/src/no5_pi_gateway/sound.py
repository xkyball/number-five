"""Bounded, revisioned sound editing; no general Camilla configuration access.

Sound has a separate socket and lock from the fader authority. A controller ACK
is not an audio-block ACK: writes are verified only after appliedRevision has
advanced in the patched processing thread. Unknown writes are never repeated.
"""
from __future__ import annotations

import asyncio
import base64
import copy
import hashlib
import json
import math
import os
from pathlib import Path
import re
import secrets
import stat
import struct
import tempfile
import time
from collections import deque

from .protocol import strict_json_object

def native_sound_value_matches(observed, requested):
    """Compare validated sound model readback, never authority or revisions.

    The installed DSP JSON f64 reader can return the immediate IEEE-754
    neighbour (observed on Lumina: 1.9816084653139114 -> ...112). Preserve the
    exact requested saved model; allow only that one representable step at the
    native boundary. No epsilon, rounding, reordering, mutation or retry.
    """
    if isinstance(requested, bool) or isinstance(observed, bool):
        return type(observed) is type(requested) and observed == requested
    if isinstance(requested, (int, float)) and isinstance(observed, (int, float)):
        if not math.isfinite(observed) or not math.isfinite(requested): return False
        if observed == requested: return True
        if observed == 0 or requested == 0: return False
        return math.nextafter(float(requested), float(observed)) == observed
    if type(observed) is not type(requested): return False
    if isinstance(requested, dict):
        return observed.keys() == requested.keys() and all(native_sound_value_matches(observed[k], v) for k, v in requested.items())
    if isinstance(requested, list):
        return len(observed) == len(requested) and all(native_sound_value_matches(a, b) for a, b in zip(observed, requested))
    return observed == requested


SCHEMA = "no5.sound-state/3"
PREVIOUS_SCHEMA = "no5.sound-state/2"
LEGACY_SCHEMA = "no5.sound-state/1"
DSP_SCHEMA = "no5.camilla-sound/2"
EQ_POLICY = {"minReserveDb": 6, "maxReserveDb": 18, "defaultReserveDb": 12,
             "positiveBandsMustNotOverlap": True}
SOUND_REJECTION_CODES = frozenset({"EQ_POSITIVE_BANDS_OVERLAP", "EQ_VISIBLE_BANDS_OVERLAP", "EQ_COMBINED_GAIN_LIMIT"})
SPECTRUM_SCHEMA = "no5.audio-spectrum/1"
SOUND_TYPES = {"soundPreview", "soundKeepalive", "soundSave", "soundDiscard", "soundSaveAs", "soundLoadPreset", "soundDeletePreset"}
CHANNELS = ("FRONT_LEFT", "FRONT_RIGHT", "REAR_LEFT", "REAR_RIGHT", "SUB")
ROUTINGS = {"front_stereo_fullrange", "stereo_4_1", "immersive_4_1"}
FALLBACK_IDS = {"no5-neutral-" + routing for routing in ROUTINGS}
_ID = re.compile(r"[A-Za-z0-9_-]{1,100}\Z")


class SoundError(ValueError):
    """No effect was sent; the request/model is invalid or superseded."""


class SoundRejected(SoundError):
    """A conforming local receiver explicitly rejected before applying a target."""


class SoundUnknown(SoundError):
    """A send/durable write has an uncertain outcome; reconcile, do not replay."""


def _finite(value, low, high, name):
    if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value) or not low <= value <= high:
        raise SoundError(f"invalid {name}")
    return float(value)


def _integer(value, name):
    if isinstance(value, bool) or not isinstance(value, int) or not 0 <= value <= 9_007_199_254_740_991:
        raise SoundError(f"invalid {name}")
    return value


def _id(value, name="id"):
    if not isinstance(value, str) or not _ID.fullmatch(value):
        raise SoundError(f"invalid {name}")
    return value


def _generation(value):
    if not isinstance(value, str) or not 1 <= len(value) <= 128 or any(ord(c) < 33 or ord(c) > 126 for c in value):
        raise SoundError("invalid configuration generation")
    return value


def _validate_preset_name(name):
    if not isinstance(name, str) or not name.strip() or len(name) > 100 or len(name.encode("utf-8")) > 128 or any(ord(c) < 32 or 127 <= ord(c) <= 159 for c in name):
        raise SoundError("invalid sound preset name")
    return name


def validate_preset(model, *, project=False):
    if not isinstance(model, dict) or set(model) != {"id", "name", "routingTemplate", "eqPoints", "room", "eqEnabled", "reserveDb"}:
        raise SoundError("invalid sound preset fields")
    _id(model["id"])
    if len(model["id"]) > 64: raise SoundError("preset ID exceeds 64 bytes")
    _validate_preset_name(model["name"])
    if model["routingTemplate"] not in ROUTINGS:
        raise SoundError("unapproved routing template")
    if type(model["eqEnabled"]) is not bool:
        raise SoundError("eqEnabled must be boolean")
    _finite(model["reserveDb"], 6, 18, "EQ reserve")
    points = model["eqPoints"]
    if not isinstance(points, list) or len(points) > 16:
        raise SoundError("EQ supports at most 16 points")
    seen = set()
    for point in points:
        if not isinstance(point, dict) or set(point) != {"id", "frequencyHz", "gainDb", "q", "widthLocked"}:
            raise SoundError("invalid EQ point fields")
        identifier = _id(point["id"])
        if len(identifier) > 64: raise SoundError("point ID exceeds 64 bytes")
        if identifier in seen:
            raise SoundError("duplicate EQ point ID")
        seen.add(identifier)
        _finite(point["frequencyHz"], 20, 20000, "frequency")
        _finite(point["gainDb"], -12, 21, "gain")
        _finite(point["q"], .3, 10, "Q")
        if not isinstance(point["widthLocked"], bool):
            raise SoundError("widthLocked must be boolean")
    if model["eqEnabled"] and not project:
        bands = sorted((max(20., point["frequencyHz"] * (math.sqrt(4 * point["q"] ** 2 + 1) - 1) / (2 * point["q"])),
                        min(20000., point["frequencyHz"] * (math.sqrt(4 * point["q"] ** 2 + 1) + 1) / (2 * point["q"])))
                       for point in points if point["gainDb"] > 0)
        for index, first in enumerate(bands):
            if any(max(first[0], second[0]) < min(first[1], second[1]) - 1e-9 for second in bands[index + 1:]):
                raise SoundError("EQ_POSITIVE_BANDS_OVERLAP")
        # Exact combined-response approval belongs to native preparation, not
        # the event loop's short JSON admission path. Its known no-effect
        # EQ_COMBINED_GAIN_LIMIT rejection is forwarded unchanged.
    room = model["room"]
    if not isinstance(room, dict) or set(room) != {"amount", "size"}:
        raise SoundError("invalid room fields")
    _finite(room["amount"], 0, 1, "room amount")
    _finite(room["size"], 0, 1, "room size")
    if model["routingTemplate"] == "front_stereo_fullrange" and room["amount"] != 0:
        raise SoundError("front-only routing has no rear room capability")
    return copy.deepcopy(model)


def _position(value):
    if value is None:
        return None
    if not isinstance(value, dict) or set(value) != {"x", "y", "z"}:
        raise SoundError("position must contain x, y, z")
    _finite(value["x"], -50, 50, "position x")
    _finite(value["y"], -50, 50, "position y")
    if value["z"] is not None:
        _finite(value["z"], -50, 50, "position z")
    return copy.deepcopy(value)


def default_calibration():
    return {"geometryEnabled": False, "listenerM": None, "speakers": [
        {"id": channel, "positionM": None, "manualDelayMs": 0.0, "manualGainDb": 0.0} for channel in CHANNELS]}


def validate_calibration(model):
    if not isinstance(model, dict) or set(model) != {"geometryEnabled", "listenerM", "speakers"}:
        raise SoundError("invalid speaker calibration fields")
    if not isinstance(model["geometryEnabled"], bool):
        raise SoundError("geometryEnabled must be boolean")
    _position(model["listenerM"])
    speakers = model["speakers"]
    if not isinstance(speakers, list) or len(speakers) != 5:
        raise SoundError("calibration needs five logical speakers")
    seen = set()
    for speaker in speakers:
        if not isinstance(speaker, dict) or set(speaker) != {"id", "positionM", "manualDelayMs", "manualGainDb"} or speaker["id"] not in CHANNELS:
            raise SoundError("invalid speaker fields")
        if speaker["id"] in seen:
            raise SoundError("duplicate speaker")
        seen.add(speaker["id"])
        _position(speaker["positionM"])
        _finite(speaker["manualDelayMs"], -50, 50, "manual delay")
        _finite(speaker["manualGainDb"], -24, 24, "manual gain")
    if model["geometryEnabled"] and (model["listenerM"] is None or any(item["positionM"] is None for item in speakers)):
        raise SoundError("geometric calibration needs measured positions")
    effective_calibration(model)
    return copy.deepcopy(model)


def effective_calibration(model):
    """One attenuation-only basis for five speakers; physical Sub is duplicated."""
    speakers = {item["id"]: item for item in model["speakers"]}
    geometric = model["geometryEnabled"]
    listener = model["listenerM"]
    dimensions = 3 if geometric and listener["z"] is not None and all(speakers[k]["positionM"]["z"] is not None for k in CHANNELS) else 2
    distances = {}
    if geometric:
        for channel in CHANNELS:
            pos = speakers[channel]["positionM"]
            distances[channel] = math.sqrt(sum((pos[axis] - listener[axis]) ** 2 for axis in ("x", "y", "z")[:dimensions]))
        if min(distances.values()) < .05:
            raise SoundError("listener must be at least five centimetres from each speaker")
    farthest = max(distances.values(), default=0)
    delays = {channel: ((farthest - distances[channel]) / 343 * 1000 if geometric else 0) + speakers[channel]["manualDelayMs"] for channel in CHANNELS}
    # A negative manual offset shifts the common zero instead of inventing negative delay.
    offset = min(0, min(delays.values()))
    delays = {channel: value - offset for channel, value in delays.items()}
    if max(delays.values()) > 50:
        raise SoundError("effective speaker delays exceed 50 ms")
    gains = {channel: (20 * math.log10(distances[channel] / farthest) if geometric else 0) + speakers[channel]["manualGainDb"] for channel in CHANNELS}
    offset = max(0, max(gains.values()))
    gains = {channel: value - offset for channel, value in gains.items()}
    order = ("FRONT_LEFT", "FRONT_RIGHT", "SUB", "SUB", "REAR_LEFT", "REAR_RIGHT")
    return {"delaysMs": [delays[channel] for channel in order], "gainsDb": [gains[channel] for channel in order],
            "dimensions": dimensions if geometric else None, "approximate": geometric,
            "speakers": [{"id": channel, "distanceM": distances.get(channel), "delayMs": delays[channel], "gainDb": gains[channel]} for channel in CHANNELS]}


def eq_response(points, sample_rate=48000, count=257, *, dense_reserve=True):
    """Camilla Peaking coefficients (RBJ Q definition), including combined reserve."""
    frequencies = [20 * (1000 ** (i / (count - 1))) for i in range(count)]
    if not points or all(point["gainDb"] == 0 for point in points):
        return {"frequenciesHz": frequencies, "gainsDb": [0.0] * count}, 0.0
    coefficients = []
    for point in points:
        a = 10 ** (point["gainDb"] / 40)
        omega = 2 * math.pi * point["frequencyHz"] / sample_rate
        alpha = math.sin(omega) / (2 * point["q"])
        c = math.cos(omega)
        coefficients.append(((1 + alpha * a, -2 * c, 1 - alpha * a), (1 + alpha / a, -2 * c, 1 - alpha / a)))
    def response_at(frequency):
        z = complex(math.cos(-2 * math.pi * frequency / sample_rate), math.sin(-2 * math.pi * frequency / sample_rate))
        value = 1.0
        for numerator, denominator in coefficients:
            value *= abs(sum(coef * z ** index for index, coef in enumerate(numerator)) / sum(coef * z ** index for index, coef in enumerate(denominator)))
        return 20 * math.log10(max(value, 1e-15))
    # Dense grid plus centre frequencies avoids missing narrow peaks between displayed bins.
    maxima = [response_at(20 * (1000 ** (i / 8192))) for i in range(8193)] if dense_reserve else []
    maxima.extend(response_at(point["frequencyHz"]) for point in points)
    reserve = max(0.0, max(maxima, default=0))
    return {"frequenciesHz": frequencies, "gainsDb": [response_at(f) for f in frequencies]}, reserve


def validate_eq_editing(value):
    if not isinstance(value, dict) or set(value) != {"schema", "experimental", "previousPoint", "editedPointId"} or value["schema"] != "no5.eq-editing/1" or type(value["experimental"]) is not bool:
        raise SoundError("invalid EQ editing intent")
    if value["previousPoint"] is not None:
        validate_preset({"id":"intent", "name":"Intent", "routingTemplate":"stereo_4_1", "eqPoints":[value["previousPoint"]], "room":{"amount":0.,"size":.5}, "eqEnabled":False,"reserveDb":18.})
    if value["editedPointId"] is not None:
        _id(value["editedPointId"], "edited point ID")
        if value["previousPoint"] is None: raise SoundError("EQ point intent needs previous preset")
    return copy.deepcopy(value)


def _native_eq_editing(value):
    if value is None: return False
    if not isinstance(value, dict) or set(value) not in ({"schema", "previewProjection", "maxExperimentalBoostDb", "experimental", "normalPreset"}, {"schema", "previewProjection", "maxExperimentalBoostDb", "experimental", "normalPreset", "validationPolicy"}) or value["schema"] != "no5.eq-editing/1" or value["previewProjection"] is not True or type(value["experimental"]) is not bool or value["maxExperimentalBoostDb"] != 3:
        raise SoundError("unsupported native EQ editing capability")
    if "validationPolicy" in value:
        policy=value["validationPolicy"]
        if not isinstance(policy,dict) or set(policy)!={"schema","kernelSha256","sampleRateHz","gridPoints","minReserveDb","maxReserveDb","experimentalBoostDb"} or policy["schema"]!="no5.eq-safety/1" or not isinstance(policy["kernelSha256"],str) or not re.fullmatch(r"[0-9a-f]{64}",policy["kernelSha256"]) or any(policy[k]!=v or type(policy[k])is not int for k,v in {"sampleRateHz":48000,"gridPoints":65536,"minReserveDb":6,"maxReserveDb":18,"experimentalBoostDb":3}.items()):
            raise SoundError("unsupported native EQ validation policy")
    normal = value["normalPreset"]
    if value["experimental"]:
        validate_preset(normal)
        if any(p["gainDb"]>normal["reserveDb"] for p in normal["eqPoints"]): raise SoundError("invalid normal EQ fallback")
    elif normal is not None: raise SoundError("unexpected normal projection")
    return True


def validate_sound_command(command):
    kind = command.get("type")
    common = {"type", "previewId", "configGeneration"}
    if kind == "soundKeepalive":
        if set(command) not in (common, common | {"experimental"}) or ("experimental" in command and type(command["experimental"]) is not bool):
            raise SoundError("invalid preview heartbeat fields")
    elif kind in SOUND_TYPES:
        required = common | {"scope", "expectedPresetRevision", "expectedCalibrationRevision"}
        if kind == "soundPreview":
            required.add("model")
            if "eqEditing" in command:
                if command.get("scope") != "preset": raise SoundError("EQ intent is preset-only")
                validate_eq_editing(command["eqEditing"])
                required.add("eqEditing")
        elif kind == "soundSaveAs":
            required.add("name")
        elif kind in {"soundLoadPreset", "soundDeletePreset"}:
            required.add("presetId")
        if set(command) != required or command.get("scope") not in {"preset", "calibration"}:
            raise SoundError("invalid scoped sound command fields")
        _integer(command["expectedPresetRevision"], "preset revision")
        _integer(command["expectedCalibrationRevision"], "calibration revision")
        if kind == "soundPreview":
            if command["scope"] == "preset": validate_preset(command["model"], project=bool(command.get("eqEditing", {}).get("editedPointId")))
            else: validate_calibration(command["model"])
        if kind in {"soundSaveAs", "soundLoadPreset", "soundDeletePreset"}:
            if command["scope"] != "preset": raise SoundError("preset library only accepts preset scope")
            if kind == "soundSaveAs":
                _validate_preset_name(command["name"])
            else:
                _id(command["presetId"], "saved preset ID")
                if len(command["presetId"]) > 64: raise SoundError("saved preset ID exceeds 64 bytes")
    else:
        raise SoundError("invalid sound command")
    _id(command["previewId"], "preview ID")
    _generation(command["configGeneration"])


class SoundStore:
    """Single atomically replaced private document; no root configuration writes."""
    LIMIT = 262144
    def __init__(self, path):
        self.path = Path(path)
        self.state = {"schema": SCHEMA, "presetRevision": 0, "calibrationRevision": 0, "presets": {}, "fallbacks": {}, "bindings": {}, "calibration": default_calibration(), "journal": None}
        try:
            descriptor = os.open(self.path, os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0))
        except FileNotFoundError:
            return
        with os.fdopen(descriptor, "rb") as handle:
            metadata = os.fstat(handle.fileno())
            if not stat.S_ISREG(metadata.st_mode) or not 0 < metadata.st_size <= self.LIMIT:
                raise SoundError("sound state must be a bounded regular file")
            value = strict_json_object(handle.read(self.LIMIT + 1))
        value = self.migrate(value)
        self.validate(value)
        self.state = value

    @staticmethod
    def migrate(value):
        """Read old owned documents losslessly, without rewriting on startup.

        An old curve has no explicit enable intent or chosen fixed reserve.
        Keep every point/ID/room/calibration/revision/binding and default only
        this versioned local document to Off/12. Network/native writers never
        receive that default. Schema2 adds only an empty fallback table when
        read as schema3. No migration rewrites the document on startup.
        """
        if not isinstance(value, dict) or value.get("schema") not in {LEGACY_SCHEMA, PREVIOUS_SCHEMA}:
            return value
        if set(value) != {"schema", "presetRevision", "calibrationRevision", "presets", "bindings", "calibration", "journal"}:
            raise SoundError("invalid historical sound state fields")
        upgraded = copy.deepcopy(value)
        if not isinstance(upgraded.get("presets"), dict):
            raise SoundError("invalid legacy sound presets")
        presets = list(upgraded["presets"].values()) if value["schema"] == LEGACY_SCHEMA else []
        journal = upgraded.get("journal")
        if journal is not None:
            if not isinstance(journal, dict) or "preset" not in journal:
                raise SoundError("invalid legacy recovery journal")
            if value["schema"] == LEGACY_SCHEMA: presets.append(journal["preset"])
        for model in presets:
            if not isinstance(model, dict) or set(model) != {"id", "name", "routingTemplate", "eqPoints", "room"}:
                raise SoundError("invalid legacy preset fields")
            model.update(eqEnabled=False, reserveDb=12.)
        upgraded["schema"] = SCHEMA
        upgraded["fallbacks"] = {}
        return upgraded

    @staticmethod
    def validate(value):
        if not isinstance(value, dict) or set(value) != {"schema", "presetRevision", "calibrationRevision", "presets", "fallbacks", "bindings", "calibration", "journal"} or value["schema"] != SCHEMA:
            raise SoundError("invalid durable sound schema")
        for field in ("presetRevision", "calibrationRevision"):
            _integer(value[field], field)
        if not isinstance(value["presets"], dict) or len(value["presets"]) > 64:
            raise SoundError("too many sound presets")
        for identifier, model in value["presets"].items():
            if identifier != validate_preset(model)["id"] or identifier in FALLBACK_IDS:
                raise SoundError("sound preset ID mismatch")
        if not isinstance(value["fallbacks"], dict) or not set(value["fallbacks"]) <= ROUTINGS:
            raise SoundError("invalid neutral fallback table")
        fallback_ids = set()
        for routing, model in value["fallbacks"].items():
            validate_preset(model)
            if (model["routingTemplate"] != routing or model["id"] != "no5-neutral-" + routing or
                    model["eqPoints"] or model["room"] != {"amount": 0., "size": .5}):
                raise SoundError("fallback must be a neutral model of its routing")
            fallback_ids.add(model["id"])
        if not isinstance(value["bindings"], dict) or len(value["bindings"]) > 64:
            raise SoundError("invalid configuration bindings")
        for generation, identifier in value["bindings"].items():
            _generation(generation)
            if identifier not in value["presets"] and identifier not in fallback_ids: raise SoundError("unknown bound preset")
        validate_calibration(value["calibration"])
        journal = value["journal"]
        if journal is not None:
            required = {"configGeneration", "sourceGeneration", "targetRevision", "preset", "calibration"}
            if not isinstance(journal, dict) or not required <= set(journal) or set(journal) - required - {"leaseId", "leaseSync"}:
                raise SoundError("invalid sound recovery journal")
            _generation(journal["configGeneration"]); _generation(journal["sourceGeneration"])
            _integer(journal["targetRevision"], "recovery revision")
            validate_preset(journal["preset"]); validate_calibration(journal["calibration"])
            if journal.get("leaseId") is not None: _id(journal["leaseId"], "native lease ID")
            if journal.get("leaseSync") not in (None, "RENEWED", "RELEASED"):
                raise SoundError("invalid durable lease synchronization intent")

    def replace(self, state):
        self.validate(state)
        data = json.dumps(state, sort_keys=True, separators=(",", ":"), allow_nan=False).encode()
        if len(data) > self.LIMIT or not stat.S_ISDIR(self.path.parent.lstat().st_mode):
            raise SoundError("unsafe sound state location/size")
        temporary = None
        try:
            descriptor, temporary = tempfile.mkstemp(prefix=".sound-", dir=self.path.parent)
            with os.fdopen(descriptor, "wb") as handle:
                os.fchmod(handle.fileno(), 0o600); handle.write(data); handle.flush(); os.fsync(handle.fileno())
            os.replace(temporary, self.path)
            directory = os.open(self.path.parent, os.O_RDONLY | getattr(os, "O_DIRECTORY", 0))
            try: os.fsync(directory)
            finally: os.close(directory)
            self.state = copy.deepcopy(state)
        finally:
            if temporary is not None:
                try: os.unlink(temporary)
                except FileNotFoundError: pass


def _validate_physical_calibration(calibration):
    if not isinstance(calibration, dict) or set(calibration) != {"delaysMs", "gainsDb"}:
        raise SoundError("invalid native physical calibration")
    for key, minimum, maximum in (("delaysMs", 0, 50), ("gainsDb", -120, 0)):
        values = calibration[key]
        if not isinstance(values, list) or len(values) != 6: raise SoundError("native calibration needs six outputs")
        for number in values: _finite(number, minimum, maximum, key)
        if values[2] != values[3]: raise SoundError("native Sub outputs must match")


def validate_dsp_state(value):
    required = {"schema", "configGeneration", "sourceGeneration", "appliedRevision", "preparedRevision", "sampleRateHz", "routingTemplate", "preset", "calibration", "headroomDb", "verification", "transitionMs", "previewLease", "lastRollback", "eqPolicy"}
    if not isinstance(value, dict) or set(value) not in (required, required | {"eqEditing"}) or value["schema"] != DSP_SCHEMA:
        raise SoundError("Camilla No5 sound extension unavailable or incompatible")
    if value["verification"] != "PROCESSED_BLOCK" or value["transitionMs"] != 50:
        raise SoundError("DSP application verification unsupported")
    if value["eqPolicy"] != EQ_POLICY or value["eqPolicy"].get("positiveBandsMustNotOverlap") is not True:
        raise SoundError("DSP EQ policy unsupported")
    _generation(value["configGeneration"]); _generation(value["sourceGeneration"])
    _integer(value["appliedRevision"], "DSP revision"); _integer(value["preparedRevision"], "prepared revision")
    if value["sampleRateHz"] != 48000 or value["routingTemplate"] not in ROUTINGS:
        raise SoundError("unsupported No5 sound topology")
    editing = value.get("eqEditing")
    _native_eq_editing(editing)
    preset = validate_preset(value["preset"])
    if editing and editing["experimental"]:
        normal = editing["normalPreset"]
        if {k:v for k,v in normal.items() if k!="eqPoints"} != {k:v for k,v in preset.items() if k!="eqPoints"} or len(normal["eqPoints"])!=len(preset["eqPoints"]):
            raise SoundError("normal EQ fallback differs from active preset")
        scale = None
        for a,b in zip(normal["eqPoints"],preset["eqPoints"]):
            if {k:v for k,v in a.items() if k!="gainDb"}!={k:v for k,v in b.items() if k!="gainDb"}:
                raise SoundError("normal EQ fallback changed point geometry")
            if b["gainDb"]<=0:
                if a["gainDb"]!=b["gainDb"]: raise SoundError("normal EQ fallback changed cut")
            else:
                if not 0<=a["gainDb"]<=b["gainDb"]: raise SoundError("normal EQ fallback increased gain")
                factor=a["gainDb"]/b["gainDb"]
                if scale is not None and abs(factor-scale)>1e-12: raise SoundError("normal EQ fallback is not proportional")
                scale=factor
    if editing and editing["experimental"] and value.get("previewLease") is None:
        raise SoundError("experimental DSP target has no lease")
    if preset["routingTemplate"] != value["routingTemplate"]:
        raise SoundError("DSP preset routing mismatch")
    calibration = value["calibration"]
    if not isinstance(calibration, dict) or set(calibration) != {"delaysMs", "gainsDb"}:
        raise SoundError("invalid DSP calibration")
    for key, minimum, maximum in (("delaysMs", 0, 50), ("gainsDb", -120, 0)):
        values = calibration[key]
        if not isinstance(values, list) or len(values) != 6:
            raise SoundError("DSP needs six physical calibration outputs")
        for number in values: _finite(number, minimum, maximum, key)
        if values[2] != values[3]: raise SoundError("Sub outputs must share calibration")
    _finite(value["headroomDb"], 0, 18, "DSP headroom")
    expected_reserve = preset["reserveDb"] if preset["eqEnabled"] else 0.
    if not math.isclose(value["headroomDb"], expected_reserve, rel_tol=0, abs_tol=1e-8):
        raise SoundError("DSP EQ reserve does not match enabled preset")
    lease = value.get("previewLease")
    if lease is not None:
        if not isinstance(lease, dict) or set(lease) != {"id", "fallbackPreset", "fallbackCalibration"}:
            raise SoundError("invalid native preview lease")
        _id(lease["id"], "native lease ID"); validate_preset(lease["fallbackPreset"])
        _validate_physical_calibration(lease["fallbackCalibration"])
    rollback = value.get("lastRollback")
    if rollback is not None:
        if not isinstance(rollback, dict) or set(rollback) != {"leaseId", "fromRevision", "toRevision"}:
            raise SoundError("invalid native rollback observation")
        _id(rollback["leaseId"], "native rollback lease ID")
        _integer(rollback["fromRevision"], "rollback origin"); _integer(rollback["toRevision"], "rollback target")
        if rollback["toRevision"] <= rollback["fromRevision"]: raise SoundError("rollback revision must advance")
    return copy.deepcopy(value)


def validate_spectrum(frame):
    required = {"schema", "sequence", "sourceGeneration", "samplePosition", "sampleRateHz", "fftSize", "window", "tap", "frequenciesHz", "binsDbfs", "peaksDbfs", "overRangeSamples", "sampleTimeNs", "timebase"}
    if not isinstance(frame, dict) or set(frame) != required or frame["schema"] != SPECTRUM_SCHEMA:
        raise SoundError("invalid spectrum schema")
    for field in ("sequence", "samplePosition", "overRangeSamples"): _integer(frame[field], field)
    if "sampleTimeNs" in frame:
        value = frame["sampleTimeNs"]
        if isinstance(value, bool) or not isinstance(value, int) or not 0 <= value <= 9_223_372_036_854_775_807:
            raise SoundError("invalid original sample time")
    if frame.get("timebase", "CAPTURE_MONOTONIC_NS") != "CAPTURE_MONOTONIC_NS": raise SoundError("unknown spectrum timebase")
    _generation(frame["sourceGeneration"])
    if frame["sampleRateHz"] != 48000 or frame["fftSize"] != 8192 or frame["window"] != "HANN" or frame["tap"] != "POST_EQ_PRE_ROOM":
        raise SoundError("invalid spectrum analysis")
    frequencies, bins = frame["frequenciesHz"], frame["binsDbfs"]
    if not isinstance(frequencies, list) or not isinstance(bins, list) or not 2 <= len(frequencies) <= 256 or len(bins) != len(frequencies):
        raise SoundError("spectrum exceeds bounded bins")
    previous = 0
    for frequency, amplitude in zip(frequencies, bins):
        _finite(frequency, 20, 20000, "spectrum frequency"); _finite(amplitude, -240, 24, "spectrum amplitude")
        if frequency <= previous: raise SoundError("spectrum frequencies must increase")
        previous = frequency
    if not isinstance(frame["peaksDbfs"], list) or len(frame["peaksDbfs"]) != 2:
        raise SoundError("spectrum needs stereo peaks")
    for peak in frame["peaksDbfs"]: _finite(peak, -240, 24, "peak")
    if len(json.dumps(frame, separators=(",", ":"), allow_nan=False).encode()) > 32768:
        raise SoundError("spectrum frame exceeds wire bound")
    return copy.deepcopy(frame)


class CamillaSoundWire:
    """Independent local socket, never taking the fader operation lock."""
    def __init__(self, client):
        self.client = client
        self.lock = asyncio.Lock()

    async def request(self, command, argument=None):
        import aiohttp
        async with self.lock:
            async with aiohttp.ClientSession(timeout=aiohttp.ClientTimeout(total=3)) as session:
                async with self.client._socket(session) as socket:
                    if command in {"SetNo5Sound", "SetNo5SoundLease"}:
                        await socket.send_str(json.dumps({command: argument}, separators=(",", ":"), allow_nan=False))
                        reply = await socket.receive(timeout=3)
                        if getattr(reply.type, "name", reply.type) != "TEXT" or not isinstance(reply.data, str):
                            raise SoundUnknown("sound command reply missing")
                        root = strict_json_object(reply.data.encode())
                        value = root.get(command)
                        if set(root) != {command} or not isinstance(value, dict):
                            raise SoundUnknown("sound reply envelope mismatch")
                        result = value.get("result")
                        if result == "Error" or (isinstance(result, dict) and set(result) == {"InvalidValueError"} and
                                isinstance(result["InvalidValueError"], str) and len(result["InvalidValueError"]) <= 128):
                            code = result.get("InvalidValueError") if isinstance(result, dict) else None
                            raise SoundRejected(code if code in SOUND_REJECTION_CODES else "DSP rejected sound CAS/target")
                        if set(value) != {"result", "value"} or value["result"] != "Ok":
                            raise SoundUnknown("sound reply not a conforming acknowledgment")
                        return value["value"]
                    return await self.client._request(socket, command, argument, has_argument=argument is not None)

    async def read(self): return validate_dsp_state(await self.request("GetNo5Sound"))
    async def apply(self, argument): return await self.request("SetNo5Sound", argument)
    async def lease(self, argument): return await self.request("SetNo5SoundLease", argument)
    async def spectrum(self):
        value = await self.request("GetNo5Spectrum")
        return None if value is None else validate_spectrum(value)
    async def spectrum_enabled(self, enabled):
        return await self.request("SetNo5Spectrum", {"enabled": enabled})
    async def close(self): await self.client.close()


class SoundController:
    PREVIEW_SECONDS = 5.0
    def __init__(self, wire, path, *, monotonic=time.monotonic, now=lambda: time.time_ns() // 1_000_000):
        self.wire = wire
        self.store = SoundStore(path)
        self.monotonic, self.now = monotonic, now
        self.current = None
        self.error = "WAITING"
        self.preview = None
        self.lock = asyncio.Lock()
        self.diagnostics = deque(maxlen=64)
        self._task = None
        self._closed = False
        self._spectrum_consumers = 0
        self._spectrum_generation = None
        self._spectrum_frame = None
        self._last_spectrum_monotonic = 0
        self._last_spectrum_renewal = 0
        self._last_new_spectrum = 0
        self._spectrum_lock = asyncio.Lock()
        self._response_cache = None
        self._restored_sources = deque(maxlen=64)
        self._preset_preview_signature = None
        self._preset_previews = {}
        self.routing_profiles = {}

    def start(self):
        if self._task is None: self._task = asyncio.create_task(self._run(), name="no5-sound-owner")

    async def stop(self):
        self._closed = True
        if self._task:
            self._task.cancel()
            try: await self._task
            except asyncio.CancelledError: pass
            self._task = None
        # This intentionally reconciles only our CAS-bound journal.
        try:
            async with self.lock: await self._recover()
        except Exception: pass
        await self.wire.close()

    def _diagnose(self, reason): self.diagnostics.append({"reason": reason, "atEpochMs": self.now()})

    def _saved_preset(self):
        if self.preview is not None:
            return copy.deepcopy(self.preview["savedPreset"])
        identifier = self.current["preset"]["id"]
        return copy.deepcopy(self.store.state["presets"].get(identifier, self.current["preset"]))

    async def _persist(self, value):
        try:
            await asyncio.to_thread(self.store.replace, value)
            await self._refresh_preset_previews()
        except Exception as exc:
            # os.replace may have completed before directory fsync failed.
            # Observe that document instead of later overwriting a possibly
            # committed save with an older in-memory rollback baseline.
            try:
                observed = await asyncio.to_thread(SoundStore, self.store.path)
                self.store.state = observed.state
            except Exception: pass
            raise SoundUnknown("sound durability unknown; reconcile without replay") from exc

    async def _refresh_preset_previews(self):
        # Derived only from durable saved models. No live FFT, screen capture,
        # DSP writes, new files, or synchronous filter work in snapshot().
        models = copy.deepcopy(self.store.state["presets"])
        signature = json.dumps(models, sort_keys=True, separators=(",", ":"), ensure_ascii=False)
        if signature == self._preset_preview_signature: return
        def prepare():
            previews = {}
            for identifier, model in models.items():
                response, _ = eq_response(model["eqPoints"], count=48, dense_reserve=False)
                canonical = json.dumps(model, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode()
                previews[identifier] = {"sha256b64": base64.b64encode(hashlib.sha256(canonical).digest()).decode("ascii"),
                    # A miniature's ±18 dB plot: 48 log-spaced 20 Hz..20 kHz
                    # samples, signed int8 in quarter-dB steps. The full model
                    # and full-resolution editor response stay unchanged.
                    "db4i8": base64.b64encode(struct.pack("48b", *(round(max(-18., min(18., value)) * 4) for value in response["gainsDb"]))).decode("ascii"),
                    "eqEnabled": model["eqEnabled"], "reserveDb": round(model["reserveDb"], 2)}
            return previews
        previews = await asyncio.to_thread(prepare)
        self._preset_previews, self._preset_preview_signature = previews, signature

    async def observe(self):
        observed = await self.wire.read()
        if self.current is not None and observed["sourceGeneration"] != self.current["sourceGeneration"]:
            self.preview = None
            self._response_cache = None
        points = observed["preset"]["eqPoints"]
        signature = json.dumps(points, sort_keys=True, separators=(",", ":"))
        if self._response_cache is None or self._response_cache[0] != signature:
            response = await asyncio.to_thread(eq_response, points, dense_reserve=False)
            self._response_cache = signature, response
        self.current = observed
        self.error = None
        await self._refresh_preset_previews()
        return observed

    def _remaining_ms(self, deadline):
        remaining = int((deadline - self.monotonic()) * 1000)
        if remaining < 1: raise SoundError("sound activity expired before native submission")
        return min(5000, remaining)

    @staticmethod
    def _physical(calibration):
        effective = effective_calibration(calibration)
        return {key: effective[key] for key in ("delaysMs", "gainsDb")}

    async def _apply(self, preset, calibration, valid=lambda: True, *, activity_deadline=None, preview_lease_id=None, eq_editing=None):
        observed = await self.observe()
        if not valid(): raise SoundError("authorization expired before sound effect")
        if eq_editing is not None:
            validate_eq_editing(eq_editing)
            if not _native_eq_editing(observed.get("eqEditing")): raise SoundError("EQ projection unsupported by DSP")
            if eq_editing["experimental"] and preview_lease_id is None: raise SoundError("experimental preview needs lease")
        if preset["routingTemplate"] != observed["routingTemplate"]:
            raise SoundError("sound editing cannot enable another routing template")
        # A prepared-but-not-applied foreign write must not be overwritten.
        if observed["preparedRevision"] != observed["appliedRevision"]:
            raise SoundError("another DSP transition is pending")
        target = observed["appliedRevision"] + 1
        effective = effective_calibration(calibration)
        argument = {"expectedConfigGeneration": observed["configGeneration"], "expectedSourceGeneration": observed["sourceGeneration"],
                    "expectedAppliedRevision": observed["appliedRevision"], "revision": target, "preset": preset, "calibration": {key: effective[key] for key in ("delaysMs", "gainsDb")}, "transitionMs": 50}
        state = copy.deepcopy(self.store.state)
        previous_journal = copy.deepcopy(state["journal"])
        baseline = previous_journal if previous_journal is not None and previous_journal["configGeneration"] == observed["configGeneration"] and previous_journal["sourceGeneration"] == observed["sourceGeneration"] else None
        state["journal"] = {"configGeneration": observed["configGeneration"], "sourceGeneration": observed["sourceGeneration"],
                            "targetRevision": target, "leaseId": preview_lease_id or (previous_journal or {}).get("leaseId"),
                            "leaseSync": (previous_journal or {}).get("leaseSync"),
                            "preset": copy.deepcopy(baseline["preset"]) if baseline else self._saved_preset(),
                            "calibration": copy.deepcopy(baseline["calibration"] if baseline else self.store.state["calibration"])}
        await self._persist(state)
        if not valid(): raise SoundError("authorization expired before sound effect")
        if activity_deadline is None: activity_deadline = self.monotonic() + self.PREVIEW_SECONDS
        argument["validForMs"] = self._remaining_ms(activity_deadline)
        journal = state["journal"]
        if eq_editing is not None: argument["eqEditing"] = copy.deepcopy(eq_editing)
        argument["previewLease"] = None if preview_lease_id is None else {
            "id": preview_lease_id, "validForMs": argument["validForMs"],
            "fallbackPreset": copy.deepcopy(journal["preset"]), "fallbackCalibration": self._physical(journal["calibration"])}
        try:
            receipt = await self.wire.apply(argument)
            if not isinstance(receipt, dict) or receipt.get("status") != "PREPARED" or receipt.get("revision") != target:
                raise SoundUnknown("DSP acceptance unknown; reconcile without replay")
            applied_preset = preset
            if eq_editing is not None:
                if type(receipt.get("experimental")) is not bool or receipt["experimental"] != eq_editing["experimental"] or type(receipt.get("constrained")) is not bool:
                    raise SoundUnknown("native EQ projection receipt unknown")
                applied_preset = validate_preset(receipt.get("preset"))
                if any(applied_preset[key] != preset[key] for key in ("id", "name", "routingTemplate", "eqEnabled", "reserveDb", "room")):
                    raise SoundUnknown("native EQ projection changed unrelated preset fields")
            deadline = self.monotonic() + .3
            while self.monotonic() < deadline:
                value = await self.observe()
                if value["configGeneration"] != observed["configGeneration"] or value["sourceGeneration"] != observed["sourceGeneration"]:
                    raise SoundUnknown("DSP source changed after sound submission")
                if value["appliedRevision"] == target and native_sound_value_matches(value["preset"], applied_preset) and native_sound_value_matches(value["calibration"], argument["calibration"]):
                    if eq_editing is not None and value.get("eqEditing", {}).get("experimental") is not eq_editing["experimental"]:
                        raise SoundUnknown("native EQ mode not confirmed")
                    return {"revision": target, "preset": copy.deepcopy(applied_preset), "constrained": receipt.get("constrained", False)}
                if value["appliedRevision"] > target:
                    raise SoundUnknown("DSP target was replaced by another writer")
                await asyncio.sleep(.01)
        except SoundUnknown: raise
        except SoundRejected:
            state = copy.deepcopy(self.store.state); state["journal"] = previous_journal
            await self._persist(state)
            raise
        except Exception as exc: raise SoundUnknown("DSP completion unknown; reconcile without replay") from exc
        raise SoundUnknown("DSP audio-block application not observed; reconcile without replay")

    def _check(self, owner, command):
        if self.current is None or self.error: raise SoundError("sound extension unavailable")
        if command["configGeneration"] != self.current["configGeneration"]: raise SoundError("sound configuration changed")
        if command["type"] != "soundKeepalive":
            scope = command["scope"]
            field = "presetRevision" if scope == "preset" else "calibrationRevision"
            expected = "expectedPresetRevision" if scope == "preset" else "expectedCalibrationRevision"
            if command[expected] != self.store.state[field]: raise SoundError("sound model revision changed")
        if self.preview is not None and (self.preview["owner"] != owner or self.preview["id"] != command["previewId"]):
            raise SoundError("another sound preview owns the device")
        journal = self.store.state["journal"]
        if journal is not None and journal.get("leaseSync") is not None:
            raise SoundError("durable sound change needs native lease reconciliation")
        if journal is not None and not self._journal_matches(journal):
            raise SoundError("preview completion uncertain or externally changed; reconcile first")

    async def command(self, owner, command, valid, *, activity_deadline=None):
        validate_sound_command(command)
        if activity_deadline is None: activity_deadline = self.monotonic() + self.PREVIEW_SECONDS
        authority_valid = valid
        valid = lambda: authority_valid() and self.monotonic() < activity_deadline
        async with self.lock:
            await self.observe()
            await self._reconcile_lease_sync()
            if self.preview is not None and self.monotonic() >= self.preview["deadline"]: await self._recover()
            self._check(owner, command)
            if not valid(): raise SoundError("authorization expired")
            kind = command["type"]
            experimental_active = self.current.get("eqEditing", {}).get("experimental", False)
            if experimental_active and kind in {"soundSave", "soundSaveAs", "soundLoadPreset", "soundDeletePreset"}:
                raise SoundError("EXPERIMENTAL_PREVIEW_ONLY")
            if experimental_active and kind == "soundKeepalive" and command.get("experimental") is not True:
                raise SoundError("EXPERIMENTAL_PREVIEW_ONLY")
            # These are discrete commands in the existing bounded FIFO. Prepare
            # the owned model under this same revision/preview lock; the client
            # cannot supply paths, routing documents, or a replacement store.
            persist_library = kind in {"soundSaveAs", "soundLoadPreset", "soundDeletePreset"}
            library_model = None
            deleting = None
            if kind == "soundSaveAs":
                if len(self.store.state["presets"]) >= 64: raise SoundError("saved preset catalog is full")
                library_model = copy.deepcopy(self.preview["preset"] if self.preview is not None else self.current["preset"])
                library_model.update(id="user_" + secrets.token_hex(16), name=command["name"].strip())
            elif kind == "soundLoadPreset":
                if self.preview is not None and self.preview["presetDirty"]:
                    raise SoundError("save or discard the preset draft before selecting another preset")
                library_model = copy.deepcopy(self.store.state["presets"].get(command["presetId"]))
                if library_model is None: raise SoundError("saved preset no longer exists")
                if library_model["routingTemplate"] != self.current["routingTemplate"]:
                    raise SoundError("saved preset requires another routing profile")
            elif kind == "soundDeletePreset":
                deleting = command["presetId"]
                if deleting not in self.store.state["presets"]: raise SoundError("saved preset no longer exists")
                if self.preview is not None and (self.preview["presetDirty"] or self.preview["calibrationDirty"]):
                    raise SoundError("save or discard the sound draft before deleting a preset")
                if deleting != self.current["preset"]["id"]:
                    state = copy.deepcopy(self.store.state)
                    del state["presets"][deleting]
                    state["bindings"] = {key: value for key, value in state["bindings"].items() if value != deleting}
                    state["presetRevision"] += 1
                    await self._persist(state)
                    return self.snapshot(owner)
                candidates = sorted((model for identifier, model in self.store.state["presets"].items()
                                     if identifier != deleting and model["routingTemplate"] == self.current["routingTemplate"]),
                                    key=lambda model: model["id"])
                # Last preset: return to a neutral, unsaved sound. Removing a
                # library entry never deletes an approved root routing file.
                library_model = copy.deepcopy(candidates[0]) if candidates else {
                    "id": "no5-neutral-" + self.current["routingTemplate"], "name": "Neutral",
                    "routingTemplate": self.current["routingTemplate"], "eqPoints": [],
                    "room": {"amount": 0., "size": .5},
                    "eqEnabled": self.current["preset"]["eqEnabled"], "reserveDb": self.current["preset"]["reserveDb"]}
            if library_model is not None: validate_preset(library_model)
            if kind in {"soundPreview", "soundSave"} and command["scope"] == "preset":
                model = command["model"] if kind == "soundPreview" else self.preview["preset"] if self.preview is not None else None
                if model is not None and model["id"] in FALLBACK_IDS:
                    raise SoundError("create a new preset before editing the neutral fallback")
            if self.preview is None:
                if experimental_active: raise SoundError("foreign experimental preview needs reconciliation")
                if kind != "soundPreview" and not persist_library: raise SoundError("sound preview no longer exists")
                expected_calibration = effective_calibration(self.store.state["calibration"])
                if not native_sound_value_matches(self.current["calibration"], {key: expected_calibration[key] for key in ("delaysMs", "gainsDb")}):
                    raise SoundError("external calibration needs reconciliation; no preview was started")
                # Roll back to the actually observed pre-edit preset, including
                # an administrator's prior change, never an older saved value.
                saved_preset = copy.deepcopy(self.current["preset"])
                self.preview = {"owner": owner, "id": command["previewId"], "leaseId": secrets.token_urlsafe(18), "deadline": activity_deadline,
                                "savedPreset": saved_preset, "preset": copy.deepcopy(saved_preset), "calibration": copy.deepcopy(self.store.state["calibration"]),
                                "presetDirty": False, "calibrationDirty": False}
            if kind == "soundKeepalive":
                await self._update_native_lease(self.store.state["journal"], activity_deadline, valid=valid, experimental=command.get("experimental", False))
                self.preview["deadline"] = activity_deadline
                return self.snapshot(owner)
            scope = command["scope"]
            if kind == "soundPreview" or persist_library:
                model = library_model if persist_library else validate_preset(command["model"], project=bool(command.get("eqEditing", {}).get("editedPointId"))) if scope == "preset" else validate_calibration(command["model"])
                if scope == "preset" and not persist_library:
                    old_points = {point["id"]: point for point in self.preview["preset"]["eqPoints"]}
                    for point in model["eqPoints"]:
                        old = old_points.get(point["id"])
                        if old and old["widthLocked"] and point["widthLocked"] and point["frequencyHz"] != old["frequencyHz"]:
                            expected = old["q"] * point["frequencyHz"] / old["frequencyHz"]
                            if not math.isclose(point["q"], expected, rel_tol=1e-6, abs_tol=1e-8):
                                raise SoundError("locked EQ width must remain fixed in Hz")
                preset = model if scope == "preset" else self.preview["preset"]
                calibration = model if scope == "calibration" else self.preview["calibration"]
                editing = command.get("eqEditing") if kind == "soundPreview" else None
                if experimental_active and scope != "preset": raise SoundError("end EQ experiment before calibration")
                applied = await self._apply(preset, calibration, valid, activity_deadline=activity_deadline, preview_lease_id=self.preview["leaseId"], eq_editing=editing)
                if scope == "preset": model = applied["preset"]
                self.preview[scope] = model
                self.preview["eqConstrained"] = applied["constrained"]
                self.preview[scope + "Dirty"] = True
                self.preview["deadline"] = activity_deadline
            if kind == "soundSave" or persist_library:
                # Readback must still belong to this preview; no blind persistence after UNKNOWN.
                journal = self.store.state["journal"]
                if journal is not None and not self._journal_matches(journal):
                    raise SoundError("preview was changed externally; reconcile first")
                if not self.preview[scope + "Dirty"]: return self.snapshot(owner)
                state = copy.deepcopy(self.store.state)
                next_preview = copy.deepcopy(self.preview)
                next_preview["deadline"] = activity_deadline
                if deleting is not None:
                    del state["presets"][deleting]
                    state["bindings"] = {key: value for key, value in state["bindings"].items() if value != deleting}
                if scope == "preset":
                    if self.current.get("eqEditing", {}).get("experimental", False) or any(point["gainDb"]>self.preview["preset"]["reserveDb"] for point in self.preview["preset"]["eqPoints"]):
                        raise SoundError("EXPERIMENTAL_PREVIEW_ONLY")
                    if self.preview["preset"]["id"] in FALLBACK_IDS:
                        state["fallbacks"][self.preview["preset"]["routingTemplate"]] = copy.deepcopy(self.preview["preset"])
                    else:
                        state["presets"][self.preview["preset"]["id"]] = copy.deepcopy(self.preview["preset"])
                    next_preview["savedPreset"] = copy.deepcopy(self.preview["preset"])
                else: state["calibration"] = copy.deepcopy(self.preview["calibration"])
                saved = next_preview["savedPreset"]
                if saved["id"] not in state["presets"] and saved["id"] not in FALLBACK_IDS: state["presets"][saved["id"]] = copy.deepcopy(saved)
                state["bindings"][self.current["configGeneration"]] = saved["id"]
                state["presetRevision" if scope == "preset" else "calibrationRevision"] += 1
                next_preview[scope + "Dirty"] = False
                release = not next_preview["presetDirty"] and not next_preview["calibrationDirty"]
                if journal is not None:
                    state["journal"][scope] = copy.deepcopy(next_preview[scope])
                    state["journal"]["leaseSync"] = "RELEASED" if release else "RENEWED"
                await self._persist(state)
                self.preview = next_preview
                await self._update_native_lease(state["journal"], activity_deadline, release=release, valid=valid)
                state = copy.deepcopy(self.store.state)
                if release: state["journal"] = None
                else: state["journal"]["leaseSync"] = None
                await self._persist(state)
            elif kind == "soundDiscard":
                model = self._saved_preset() if scope == "preset" else copy.deepcopy(self.store.state["calibration"])
                preset = model if scope == "preset" else self.preview["preset"]
                calibration = model if scope == "calibration" else self.preview["calibration"]
                await self._apply(preset, calibration, valid, activity_deadline=activity_deadline, preview_lease_id=self.preview["leaseId"])
                self.preview[scope] = model
                self.preview[scope + "Dirty"] = False
                self.preview["deadline"] = activity_deadline
                release = not self.preview["presetDirty"] and not self.preview["calibrationDirty"]
                await self._update_native_lease(self.store.state["journal"], activity_deadline, release=release, valid=valid)
                state = copy.deepcopy(self.store.state)
                if release: state["journal"] = None
                await self._persist(state)
            if not self.preview["presetDirty"] and not self.preview["calibrationDirty"]:
                self.preview = None
            return self.snapshot(owner)

    async def _update_native_lease(self, journal, activity_deadline, *, release=False, valid=lambda: True, experimental=False):
        if journal is None or journal.get("leaseId") is None:
            raise SoundUnknown("native preview lease unavailable; reconcile")
        observed = await self.observe()
        if not self._journal_matches(journal): raise SoundUnknown("native preview revision changed before lease update")
        if not valid(): raise SoundUnknown("native preview authority expired after effect/durability")
        try: remaining = 0 if release else self._remaining_ms(activity_deadline)
        except SoundError as exc: raise SoundUnknown("native preview activity expired after effect/durability") from exc
        request = {"id": journal["leaseId"], "expectedConfigGeneration": journal["configGeneration"],
                   "expectedSourceGeneration": journal["sourceGeneration"], "expectedAppliedRevision": observed["appliedRevision"],
                   "validForMs": remaining,
                   "fallbackPreset": copy.deepcopy(journal["preset"]), "fallbackCalibration": self._physical(journal["calibration"])}
        if _native_eq_editing(observed.get("eqEditing")):
            request["experimental"] = experimental
        expected = "RELEASED" if release else "RENEWED"
        try:
            receipt = await self.wire.lease(request)
            if receipt != {"status": expected, "id": journal["leaseId"], "appliedRevision": observed["appliedRevision"]}:
                raise SoundUnknown("native lease update acknowledgment unknown")
        except SoundUnknown: raise
        except Exception as exc: raise SoundUnknown("native lease update unknown; reconcile without replay") from exc

    def _owned_auto_rollback(self, journal):
        observed = self.current
        rollback = None if observed is None else observed.get("lastRollback")
        return (observed is not None and rollback is not None and journal.get("leaseId") is not None and
                observed["configGeneration"] == journal["configGeneration"] and observed["sourceGeneration"] == journal["sourceGeneration"] and
                rollback["leaseId"] == journal["leaseId"] and rollback["fromRevision"] == journal["targetRevision"] and
                rollback["toRevision"] == observed["appliedRevision"] and observed["preparedRevision"] == observed["appliedRevision"])

    async def _reconcile_lease_sync(self):
        journal = self.store.state["journal"]
        if journal is None or journal.get("leaseSync") is None or self.current is None: return False
        if not self._journal_matches(journal): return False
        lease = self.current.get("previewLease")
        expected = journal["leaseSync"]
        matching = (lease is None if expected == "RELEASED" else native_sound_value_matches(lease, {
            "id": journal["leaseId"], "fallbackPreset": journal["preset"], "fallbackCalibration": self._physical(journal["calibration"])}))
        if expected == "RELEASED" and (not native_sound_value_matches(self.current["preset"], journal["preset"]) or not native_sound_value_matches(self.current["calibration"], self._physical(journal["calibration"]))):
            matching = False
        if not matching: return False
        state = copy.deepcopy(self.store.state)
        if expected == "RELEASED":
            state["journal"] = None
            self.preview = None
        else:
            state["journal"]["leaseSync"] = None
            if self.preview is not None: self.preview["savedPreset"] = copy.deepcopy(journal["preset"])
        await self._persist(state)
        self._diagnose("NATIVE_LEASE_SYNC_RECONCILED")
        return True

    def _journal_matches(self, journal):
        value = self.current
        return value is not None and value["configGeneration"] == journal["configGeneration"] and value["sourceGeneration"] == journal["sourceGeneration"] and value["appliedRevision"] == journal["targetRevision"] and value["preparedRevision"] == value["appliedRevision"]

    async def _recover(self):
        journal = self.store.state["journal"]
        if journal is not None:
            await self.observe()
            if self._owned_auto_rollback(journal):
                if not native_sound_value_matches(self.current["preset"], journal["preset"]) or not native_sound_value_matches(self.current["calibration"], self._physical(journal["calibration"])):
                    # A save was durable but its lease release/renewal was not
                    # confirmed. Only this explicit owned rollback permits a
                    # new recovery goal for the confirmed durable baseline.
                    await self._apply(journal["preset"], journal["calibration"])
                state = copy.deepcopy(self.store.state); state["journal"] = None
                await self._persist(state); self.preview = None
                self._diagnose("NATIVE_PREVIEW_ROLLBACK_RECONCILED")
                return
            if (self.current["configGeneration"] == journal["configGeneration"] and
                    self.current["sourceGeneration"] == journal["sourceGeneration"] and
                    self.current["preparedRevision"] >= journal["targetRevision"] and
                    self.current["appliedRevision"] < self.current["preparedRevision"]):
                self._diagnose("PREVIEW_AWAITING_AUDIO_BLOCK")
                return
            if self._journal_matches(journal):
                effective = effective_calibration(journal["calibration"])
                target_cal = {key: effective[key] for key in ("delaysMs", "gainsDb")}
                if not native_sound_value_matches(self.current["preset"], journal["preset"]) or not native_sound_value_matches(self.current["calibration"], target_cal):
                    await self._apply(journal["preset"], journal["calibration"])
                elif self.current.get("previewLease") is not None and journal.get("leaseId"):
                    await self._update_native_lease(journal, self.monotonic() + self.PREVIEW_SECONDS, release=True)
                self._diagnose("PREVIEW_ROLLED_BACK")
            else:
                self._diagnose("PREVIEW_FOREIGN_STATE_PRESERVED")
            state = copy.deepcopy(self.store.state); state["journal"] = None
            await self._persist(state)
        self.preview = None

    def _calibration_matches(self):
        if self.current is None: return False
        model = self.preview["calibration"] if self.preview is not None else self.store.state["calibration"]
        effective = effective_calibration(model)
        return native_sound_value_matches(self.current["calibration"], {key: effective[key] for key in ("delaysMs", "gainsDb")})

    def summary(self):
        available = self.current is not None and self.error is None
        pending_sync = self.store.state["journal"] is not None and self.store.state["journal"].get("leaseSync") is not None
        return {"available": available, "writable": available and self._calibration_matches() and not pending_sync,
                "presetRevision": self.store.state["presetRevision"],
                "calibrationRevision": self.store.state["calibrationRevision"],
                "configurationRevision": None if self.current is None else self.current["appliedRevision"],
                "headroomDb": None if self.current is None else self.current["headroomDb"],
                "eqEnabled": None if self.current is None else self.current["preset"]["eqEnabled"],
                "reserveDb": None if self.current is None else self.current["preset"]["reserveDb"],
                "eqPolicy": copy.deepcopy(EQ_POLICY),
                "capabilities": {"presetLibrary": available, "presetManagement": available, "eqProjection": available and bool(self.current.get("eqEditing")), "experimentalEq": available and bool(self.current.get("eqEditing")), "parametricEq": available, "room": available and self.current["routingTemplate"] != "front_stereo_fullrange",
                                 "speakerAlignment": available, "spectrum": available}}

    def snapshot(self, owner=None):
        state = self.store.state
        base = {"version": 2, "kind": "audio-sound", "available": self.current is not None and self.error is None,
                "eqPolicy": copy.deepcopy(EQ_POLICY),
                "writable": self.summary()["writable"],
                "presetRevision": state["presetRevision"], "calibrationRevision": state["calibrationRevision"],
                "capabilities": self.summary()["capabilities"],
                "presetCatalog": [{key: model[key] for key in ("id", "name", "routingTemplate")}
                                  for model in sorted(state["presets"].values(), key=lambda item: item["id"])],
                "presetPreviews": copy.deepcopy(self._preset_previews),
                "routingProfiles": copy.deepcopy(self.routing_profiles),
                "configGeneration": None, "sourceGeneration": None, "configurationRevision": None, "preset": None,
                "calibration": copy.deepcopy(state["calibration"]), "headroomDb": None, "response": None,
                "effectiveCalibration": None, "preview": None, "reason": self.error}
        if self.current is None: return base
        if self.current.get("eqEditing"):
            base["eqEditing"] = copy.deepcopy(self.current["eqEditing"])
            base["eqEditing"]["constrained"] = bool(self.preview and self.preview.get("eqConstrained"))
        if self.store.state["journal"] is not None and self.store.state["journal"].get("leaseSync") is not None and base["reason"] is None:
            base["reason"] = "NATIVE_LEASE_SYNC_PENDING"
        current = self.current
        calibration = self.preview["calibration"] if self.preview is not None else state["calibration"]
        effective = effective_calibration(calibration)
        if not self._calibration_matches():
            effective.update(delaysMs=copy.deepcopy(current["calibration"]["delaysMs"]),
                             gainsDb=copy.deepcopy(current["calibration"]["gainsDb"]), dimensions=None, approximate=False)
            for item, physical in zip(effective["speakers"], (0, 1, 4, 5, 2)):
                item.update(distanceM=None, delayMs=effective["delaysMs"][physical], gainDb=effective["gainsDb"][physical])
            if base["reason"] is None: base["reason"] = "FOREIGN_CALIBRATION"
        base.update(configGeneration=current["configGeneration"], sourceGeneration=current["sourceGeneration"],
                    configurationRevision=current["appliedRevision"], preset=copy.deepcopy(current["preset"]),
                    calibration=copy.deepcopy(calibration), headroomDb=current["headroomDb"],
                    response=None if self._response_cache is None else copy.deepcopy(self._response_cache[1][0]), effectiveCalibration=effective)
        if self.preview is not None and self.preview["owner"] == owner:
            base["preview"] = {"id": self.preview["id"], "expiresAtEpochMs": self.now() + max(0, int((self.preview["deadline"] - self.monotonic()) * 1000)),
                               "presetDirty": self.preview["presetDirty"], "calibrationDirty": self.preview["calibrationDirty"]}
        return base

    async def _restore(self):
        """Restore owned state only into a pristine newly loaded approved stage.

        A nonzero DSP revision belongs to an observed writer and is preserved.
        The global calibration follows every approved routing; preset settings
        are restored only for a configuration previously saved by this owner.
        """
        current = self.current
        if current is None or self.preview is not None or self.store.state["journal"] is not None:
            return
        source = current["sourceGeneration"]
        if source in self._restored_sources: return
        self._restored_sources.append(source)
        if current["preparedRevision"] != 0 or current["appliedRevision"] != 0: return
        identifier = self.store.state["bindings"].get(current["configGeneration"])
        fallbacks = {model["id"]: model for model in self.store.state["fallbacks"].values()}
        preset = self.store.state["presets"].get(identifier, fallbacks.get(identifier, current["preset"]))
        calibration = self.store.state["calibration"]
        effective = effective_calibration(calibration)
        dsp_calibration = {key: effective[key] for key in ("delaysMs", "gainsDb")}
        if native_sound_value_matches(current["preset"], preset) and native_sound_value_matches(current["calibration"], dsp_calibration): return
        await self._apply(copy.deepcopy(preset), copy.deepcopy(calibration))
        state = copy.deepcopy(self.store.state); state["journal"] = None
        await self._persist(state)
        self._diagnose("SAVED_SOUND_RESTORED")

    async def _run(self):
        while not self._closed:
            try:
                async with self.lock:
                    await self.observe()
                    await self._reconcile_lease_sync()
                    if self.store.state["journal"] is not None and (self.preview is None or self.monotonic() >= self.preview["deadline"]): await self._recover()
                    await self._restore()
            except asyncio.CancelledError: raise
            except Exception:
                self.error = "SOUND_UNAVAILABLE"
            await asyncio.sleep(.25)

    async def spectrum_attach(self):
        async with self._spectrum_lock:
            if self._spectrum_consumers == 0:
                await self.wire.spectrum_enabled(True)
                self._spectrum_frame = None
                self._last_spectrum_renewal = self.monotonic()
            self._spectrum_consumers += 1

    async def spectrum_detach(self):
        async with self._spectrum_lock:
            self._spectrum_consumers = max(0, self._spectrum_consumers - 1)
            if self._spectrum_consumers == 0:
                try: await self.wire.spectrum_enabled(False)
                except Exception: self._diagnose("SPECTRUM_DISABLE_UNKNOWN")

    async def spectrum_latest(self):
        async with self._spectrum_lock:
            if self._spectrum_consumers == 0: return None
            now = self.monotonic()
            if now - self._last_spectrum_monotonic < .1:
                frame = self._spectrum_frame
                return frame if frame is not None and self.current is not None and frame["sourceGeneration"] == self.current["sourceGeneration"] and now - self._last_new_spectrum <= .5 else None
            self._last_spectrum_monotonic = now
            if now - self._last_spectrum_renewal >= .75:
                await self.wire.spectrum_enabled(True)
                self._last_spectrum_renewal = self.monotonic()
            frame = await self.wire.spectrum()
            if frame is None: return None
            if self.current is None or frame["sourceGeneration"] != self.current["sourceGeneration"]: return None
            identity = (frame["sourceGeneration"], frame["sequence"])
            if self._spectrum_generation is not None and identity[0] == self._spectrum_generation[0] and identity[1] < self._spectrum_generation[1]:
                return None
            if identity != self._spectrum_generation:
                self._last_new_spectrum = self.monotonic()
            self._spectrum_generation = identity
            self._spectrum_frame = frame
            return frame if self.monotonic() - self._last_new_spectrum <= .5 else None
