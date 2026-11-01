"""Safe, local display overlays for HELM/weather and SONAR source selection.

There is intentionally no local SONAR waveform generator in this module.  The
only simulated SONAR source is the separately deployed, contract-tested M0
daemon.  M1 means the physical Pico producer.  Audio is not represented here:
CamillaDSP and the Spotify receiver are always passed through unchanged.
"""

from __future__ import annotations

from collections import OrderedDict
import copy
from dataclasses import replace
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import re
import stat
import time
import uuid

from .configuration import GatewayConfig
from .protocol import ProtocolError
from .voyage import DEFAULT_VOYAGE_SETTINGS, VoyageEngine, validate_voyage_settings


class SimulationControlError(ProtocolError):
    """An operator request is incomplete, unsafe, or not provisioned."""


HELM_NUMERIC_RANGES: dict[str, tuple[float, float]] = {
    "portRpm": (0, 100_000), "starboardRpm": (0, 100_000), "portTrim": (0, 100), "starboardTrim": (0, 100),
    "portFuel": (0, 100), "starboardFuel": (0, 100), "heading": (0, 359.999999999), "speed": (0, 200),
    "depth": (0, 12_000), "waterTemp": (-5, 60), "portVoltage": (0, 60), "starboardVoltage": (0, 60),
    "houseVoltage": (0, 60), "portCoolantC": (-50, 200), "starboardCoolantC": (-50, 200),
    "portOilBar": (0, 100), "starboardOilBar": (0, 100), "portFuelFlowLph": (0, 10_000),
    "starboardFuelFlowLph": (0, 10_000), "totalFuelFlowLph": (0, 20_000), "latitudeDegrees": (-90, 90),
    "longitudeDegrees": (-180, 180), "fuelRemainingLiters": (0, 1_000_000),
    "courseOverGroundDegrees": (0, 359.999999999), "tripDistanceNm": (0, 1_000_000),
    "tripDurationSeconds": (0, 31_536_000_000), "fuelUsedLiters": (0, 1_000_000),
    "efficiencyNmPerLiter": (0, 1_000_000),
}
HELM_VALUE_FIELDS = (
    "portRpm", "starboardRpm", "portTrim", "starboardTrim", "portFuel", "starboardFuel", "heading", "speed",
    "depth", "waterTemp", "portVoltage", "starboardVoltage", "houseVoltage", "portCoolantC", "starboardCoolantC",
    "portOilBar", "starboardOilBar", "portFuelFlowLph", "starboardFuelFlowLph", "totalFuelFlowLph", "portGear",
    "starboardGear", "latitudeDegrees", "longitudeDegrees", "fuelRemainingLiters", "courseOverGroundDegrees",
)
HELM_EXTRA_FIELDS = ("tripDistanceNm", "tripDurationSeconds", "fuelUsedLiters", "efficiencyNmPerLiter")
WEATHER_FIELDS = (
    "airTemperatureC", "windSpeedKn", "windDirectionDegrees", "relativeHumidityPercent", "pressureHpa",
    "latitudeDegrees", "longitudeDegrees", "cloudCoverPercent", "visibilityMeters",
)
HELM_UNITS = {
    "speed": "kn", "heading": "deg_true", "courseOverGroundDegrees": "deg_true", "depth": "m", "waterTemp": "degC",
    "rpm": "rpm", "trim": "percent", "voltage": "V", "fuel": "percent", "coolant": "degC", "oil": "bar",
    "fuelFlow": "L/h", "position": "deg", "fuelRemainingLiters": "L",
}
WEATHER_UNITS = {
    "airTemperatureC": "degC", "windSpeedKn": "kn", "windDirectionDegrees": "deg_true_from",
    "relativeHumidityPercent": "percent", "pressureHpa": "hPa", "latitudeDegrees": "deg", "longitudeDegrees": "deg",
    "cloudCoverPercent": "percent", "visibilityMeters": "m",
}
_REQUEST_ID = re.compile(r"[A-Za-z0-9_.-]{8,100}\Z")
_STATE_VERSION = 2
_MAX_STATE_BYTES = 65_536


def epoch_ms() -> int:
    return time.time_ns() // 1_000_000


def _strict(value: object, label: str, keys: set[str]) -> dict[str, object]:
    if not isinstance(value, dict) or set(value) != keys:
        raise SimulationControlError(f"{label} must contain exactly: {', '.join(sorted(keys))}")
    return value


def _selection(value: object, label: str, allowed: tuple[str, ...]) -> list[str]:
    if not isinstance(value, list) or len(value) > len(allowed) or any(not isinstance(item, str) for item in value):
        raise SimulationControlError(f"{label} must be a bounded field-name list")
    if len(value) != len(set(value)) or any(item not in allowed for item in value):
        raise SimulationControlError(f"{label} contains an unsupported or duplicate field")
    return list(value)


def default_settings() -> dict[str, object]:
    return {"voyage": copy.deepcopy(DEFAULT_VOYAGE_SETTINGS), "helm": {"overlayFields": []},
            "weather": {"overlayFields": []}, "sonar": {"mode": "INHERIT"}}


def validate_settings(value: object) -> dict[str, object]:
    root = _strict(value, "settings", {"voyage", "helm", "weather", "sonar"})
    try:
        voyage = validate_voyage_settings(root["voyage"])
    except ValueError as exc:
        raise SimulationControlError(str(exc)) from exc
    helm = _strict(root["helm"], "settings.helm", {"overlayFields"})
    weather = _strict(root["weather"], "settings.weather", {"overlayFields"})
    helm_fields = _selection(helm["overlayFields"], "settings.helm.overlayFields", HELM_VALUE_FIELDS + HELM_EXTRA_FIELDS)
    weather_fields = _selection(weather["overlayFields"], "settings.weather.overlayFields", WEATHER_FIELDS)
    for label, fields in (("HELM", helm_fields), ("weather", weather_fields)):
        coordinates = {"latitudeDegrees", "longitudeDegrees"} & set(fields)
        if coordinates and coordinates != {"latitudeDegrees", "longitudeDegrees"}:
            raise SimulationControlError(f"{label} position must select latitudeDegrees and longitudeDegrees together")
    sonar = _strict(root["sonar"], "settings.sonar", {"mode"})
    if sonar["mode"] not in {"INHERIT", "M0", "M1"}:
        raise SimulationControlError("settings.sonar.mode must be INHERIT, M0 or M1")
    return {"voyage": voyage, "helm": {"overlayFields": helm_fields},
            "weather": {"overlayFields": weather_fields}, "sonar": {"mode": sonar["mode"]}}


class SimulationController:
    """Revisioned overlay settings; it has no adapter write path."""

    def __init__(self, config: GatewayConfig, server_session_id: str, *, now: callable = epoch_ms,
                 monotonic: callable = time.monotonic) -> None:
        self._gateway, self._config = config, config.simulation
        self._server_session_id, self._now, self._monotonic = server_session_id, now, monotonic
        self._instance_id = str(uuid.uuid4())
        self._settings = default_settings()
        self._revision = 0
        self._last_error: str | None = None
        self._receipts: OrderedDict[str, tuple[str, dict[str, object]]] = OrderedDict()
        self._engine = VoyageEngine(self._settings["voyage"])  # type: ignore[arg-type]
        self._last_advance = monotonic()
        if self._config.enabled:
            self._load_state()
            self._engine.configure(self._settings["voyage"])  # type: ignore[arg-type]

    @property
    def configured(self) -> bool: return self._config.enabled

    @property
    def last_error(self) -> str | None: return self._last_error

    @property
    def helm_active(self) -> bool: return bool(self._settings["helm"]["overlayFields"])  # type: ignore[index]

    @property
    def weather_active(self) -> bool: return bool(self._settings["weather"]["overlayFields"])  # type: ignore[index]

    @property
    def sonar_mode(self) -> str: return self._settings["sonar"]["mode"]  # type: ignore[index,return-value]

    @property
    def any_active(self) -> bool: return self.helm_active or self.weather_active or self.sonar_mode != "INHERIT"

    def _load_state(self) -> None:
        assert self._config.state_file is not None
        path = Path(self._config.state_file)
        try:
            metadata = path.lstat()
            if not stat.S_ISREG(metadata.st_mode) or not 0 < metadata.st_size <= _MAX_STATE_BYTES: raise SimulationControlError("unsafe state file")
            fd = os.open(path, os.O_RDONLY | getattr(os, "O_CLOEXEC", 0) | getattr(os, "O_NOFOLLOW", 0))
            try: raw = os.read(fd, _MAX_STATE_BYTES + 1)
            finally: os.close(fd)
            if len(raw) > _MAX_STATE_BYTES: raise SimulationControlError("state too large")
            document = json.loads(raw.decode("utf-8")); envelope = _strict(document, "persisted state", {"version", "revision", "settings"})
            if envelope["version"] != _STATE_VERSION or isinstance(envelope["revision"], bool) or not isinstance(envelope["revision"], int) or envelope["revision"] < 0:
                raise SimulationControlError("state version/revision invalid")
            self._settings, self._revision = validate_settings(envelope["settings"]), envelope["revision"]
            # A persisted source switch must not become an implicit fallback
            # when its fixed daemon configuration was removed.
            self.source_config(self.sonar_mode)
        except FileNotFoundError: return
        except (OSError, UnicodeDecodeError, json.JSONDecodeError, SimulationControlError):
            self._settings, self._revision = default_settings(), 0; self._last_error = "simulation state ignored: validation failed"

    def _persist(self) -> None:
        assert self._config.state_file is not None
        path = Path(self._config.state_file); path.parent.mkdir(mode=0o750, parents=True, exist_ok=True)
        encoded = json.dumps({"version": _STATE_VERSION, "revision": self._revision, "settings": self._settings}, separators=(",", ":"), allow_nan=False).encode()
        temp = path.with_name(f".{path.name}.{os.getpid()}.tmp")
        fd = os.open(temp, os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, "O_CLOEXEC", 0), 0o600)
        try: os.write(fd, encoded); os.fsync(fd)
        finally: os.close(fd)
        try: os.replace(temp, path)
        except Exception:
            with __import__("contextlib").suppress(FileNotFoundError): temp.unlink()
            raise

    def _advance(self) -> None:
        current = self._monotonic(); elapsed = max(0.0, min(5.0, current - self._last_advance)); self._last_advance = current
        voyage = self._settings["voyage"]  # type: ignore[index]
        if voyage["running"]: self._engine.advance(elapsed * float(voyage["timeScale"]))  # type: ignore[index]

    def advance(self) -> None:
        """Advance only the deterministic overlay clock; never emits SONAR."""
        self._advance()

    def source_config(self, mode: str | None = None) -> GatewayConfig:
        selected = mode or self.sonar_mode
        if selected == "INHERIT": return self._gateway
        producer = selected.lower(); configured = getattr(self._config, f"sonar_{producer}_upstream_base_url")
        if configured is None and self._gateway.producer == producer: configured = self._gateway.upstream_base_url
        if configured is None: raise SimulationControlError(f"SONAR {selected} is not provisioned in the service configuration")
        return replace(self._gateway, producer=producer, upstream_base_url=configured)

    def _metadata(self, now: int) -> dict[str, object]:
        result = self._engine.metadata(); result.update({"observedAtEpochMs": now, "runId": self._instance_id, "revision": self._revision}); return result

    def control_state(self) -> dict[str, object]:
        self._advance(); helm_fields = self._settings["helm"]["overlayFields"]  # type: ignore[index]
        weather_fields = self._settings["weather"]["overlayFields"]  # type: ignore[index]
        availability: dict[str, bool] = {"INHERIT": True}
        for mode in ("M0", "M1"):
            try: self.source_config(mode); availability[mode] = True
            except SimulationControlError: availability[mode] = False
        return {"version": 2, "kind": "simulation.control", "serverSessionId": self._server_session_id, "instanceId": self._instance_id,
            "revision": self._revision, "enabled": self.configured, "lastError": self._last_error, "settings": copy.deepcopy(self._settings),
            "active": {"helm": self.helm_active, "weather": self.weather_active, "sonar": self.sonar_mode != "INHERIT"},
            "fieldSources": {"helm": {field: ("SIMULATED" if field in helm_fields else "REAL_OR_UNAVAILABLE") for field in HELM_VALUE_FIELDS + HELM_EXTRA_FIELDS},
                "weather": {field: ("SIMULATED" if field in weather_fields else "REAL_OR_UNAVAILABLE") for field in WEATHER_FIELDS}, "audio": "REAL_OR_UNAVAILABLE"},
            "dynamic": {"voyage": self._metadata(self._now()), "helm": self._engine.helm_values(), "weather": self._engine.weather_values()},
            "sonar": {"selected": self.sonar_mode, "availableModes": availability, "sourceIsDaemon": True}, "allowedActions": {"apply": self.configured},
            "safety": {"hardwareWrites": False, "camillaWrites": False, "signalkWrites": False, "sonarUpstreamWrites": False,
                "audioSimulation": False, "mixedHelmEnvelopeMarkedSimulated": True}}

    def apply(self, command: dict[str, object]) -> dict[str, object]:
        if not self.configured: raise SimulationControlError("simulation control is not configured")
        if set(command) != {"version", "serverSessionId", "expectedRevision", "requestId", "action", "settings"} or command.get("version") != 1 or command.get("action") != "apply":
            raise SimulationControlError("simulation command must be a complete version 1 apply request")
        request_id, expected = command.get("requestId"), command.get("expectedRevision")
        if not isinstance(request_id, str) or _REQUEST_ID.fullmatch(request_id) is None: raise SimulationControlError("simulation requestId must be 8..100 safe characters")
        if isinstance(expected, bool) or not isinstance(expected, int) or expected < 0: raise SimulationControlError("simulation expectedRevision must be a non-negative integer")
        candidate = validate_settings(command.get("settings")); self.source_config(candidate["sonar"]["mode"])  # type: ignore[index]
        fingerprint = json.dumps(command, sort_keys=True, separators=(",", ":"), allow_nan=False); prior = self._receipts.get(request_id)
        if prior is not None:
            if prior[0] != fingerprint: raise SimulationControlError("simulation requestId was already used with different content")
            replay = copy.deepcopy(prior[1]); replay["replayed"] = True; replay["state"] = self.control_state(); return replay
        if command.get("serverSessionId") != self._server_session_id: raise SimulationControlError("simulation server session changed; reload state")
        if expected != self._revision: raise SimulationControlError("simulation revision conflict; reload state")
        old_settings, old_revision = self._settings, self._revision; self._settings, self._revision = candidate, self._revision + 1
        self._engine.configure(candidate["voyage"])  # type: ignore[arg-type]
        try: self._persist(); self._last_error = None
        except OSError as exc:
            self._settings, self._revision = old_settings, old_revision; self._engine.configure(old_settings["voyage"])  # type: ignore[arg-type]
            raise SimulationControlError(f"simulation state could not be persisted: {type(exc).__name__}") from exc
        ack = {"version": 1, "kind": "simulation.ack", "requestId": request_id, "serverSessionId": self._server_session_id,
               "revision": self._revision, "outcome": "APPLIED", "replayed": False, "state": self.control_state()}
        self._receipts[request_id] = (fingerprint, copy.deepcopy(ack))
        while len(self._receipts) > 128: self._receipts.popitem(last=False)
        return ack

    @staticmethod
    def _base_helm(now: int, session: str) -> dict[str, object]:
        return {"version": 1, "kind": "helm", "source": "unavailable", "sourceKind": "REAL", "simulated": False,
            "timestampEpochMs": now, "publishedAtEpochMs": now, "serverSessionId": session, "status": "UNAVAILABLE", "simulation": None, "running": False,
            "vesselTime": datetime.fromtimestamp(now / 1000, timezone.utc).strftime("%H:%M:%S"), "units": copy.deepcopy(HELM_UNITS),
            "capabilities": {"telemetry": True, "position": False, "depth": False, "waterTemperature": False, "commands": False},
            "values": {field: None for field in HELM_VALUE_FIELDS}, "extras": {field: None for field in HELM_EXTRA_FIELDS},
            "fieldQuality": {field: {"status": "UNAVAILABLE", "timestampEpochMs": None} for field in HELM_NUMERIC_RANGES}}

    def helm_payload(self, real_payload: dict[str, object] | None) -> dict[str, object] | None:
        if not self.helm_active: return real_payload
        self._advance(); now = self._now(); base = copy.deepcopy(real_payload) if real_payload else self._base_helm(now, self._server_session_id)
        if not isinstance(base.get("values"), dict) or not isinstance(base.get("extras"), dict) or not isinstance(base.get("fieldQuality"), dict): base = self._base_helm(now, self._server_session_id)
        values, extras, quality = base["values"], base["extras"], base["fieldQuality"]; generated = self._engine.helm_values() | self._engine.extras()
        selected = self._settings["helm"]["overlayFields"]  # type: ignore[index]
        for field in selected:
            if field in values: values[field] = generated[field]
            else: extras[field] = generated[field]
            if field in quality: quality[field] = {"status": "LIVE", "timestampEpochMs": now}
        stamps = [entry.get("timestampEpochMs") for field, entry in quality.items() if field in generated and isinstance(entry, dict) and entry.get("status") in {"LIVE", "STALE"} and isinstance(entry.get("timestampEpochMs"), int)]
        position = values.get("latitudeDegrees") is not None and values.get("longitudeDegrees") is not None
        base.update({"source": self._config.source_name, "sourceKind": "SIMULATED", "simulated": True, "timestampEpochMs": min(stamps) if stamps else now,
            "publishedAtEpochMs": now, "serverSessionId": self._server_session_id, "status": "LIVE", "simulation": self._metadata(now), "running": True,
            "vesselTime": datetime.fromtimestamp(now / 1000, timezone.utc).strftime("%H:%M:%S"), "capabilities": {"telemetry": True, "position": position,
                "depth": values.get("depth") is not None, "waterTemperature": values.get("waterTemp") is not None, "commands": False}})
        return base

    def weather_payload(self, real_payload: dict[str, object] | None) -> dict[str, object] | None:
        if not self.weather_active: return real_payload
        self._advance(); now = self._now(); selected = self._settings["weather"]["overlayFields"]  # type: ignore[index]
        base = copy.deepcopy(real_payload) if real_payload else {"version": 1, "kind": "weather", "source": "unavailable", "sourceKind": "REAL", "simulated": False,
            "serverSessionId": self._server_session_id, "timestampEpochMs": now, "publishedAtEpochMs": now, "status": "UNAVAILABLE", "units": copy.deepcopy(WEATHER_UNITS),
            "provider": {"id": "UNAVAILABLE", "label": "unavailable", "kind": "REAL"}, "delivery": {"gatewayId": self._config.source_name, "transport": "HTTP"},
            "capabilities": {}, "observedAtEpochMs": None, "pressureReference": "UNSPECIFIED", "values": {field: None for field in WEATHER_FIELDS}, "simulation": None}
        if not isinstance(base.get("values"), dict): base["values"] = {field: None for field in WEATHER_FIELDS}
        generated = self._engine.weather_values()
        for field in selected: base["values"][field] = generated[field]
        values = base["values"]
        base.update({"source": self._config.source_name, "sourceKind": "SIMULATED", "simulated": True, "serverSessionId": self._server_session_id,
            "timestampEpochMs": now, "publishedAtEpochMs": now, "status": "LIVE", "observedAtEpochMs": now,
            "provider": {"id": "NO5_VOYAGE_OVERLAY", "label": "SIMULATED · local voyage overlay", "kind": "SIMULATED"}, "simulation": self._metadata(now),
            "capabilities": {"airTemperature": values.get("airTemperatureC") is not None,
                "wind": values.get("windSpeedKn") is not None and values.get("windDirectionDegrees") is not None,
                "humidity": values.get("relativeHumidityPercent") is not None, "pressure": values.get("pressureHpa") is not None,
                "observedLocation": values.get("latitudeDegrees") is not None and values.get("longitudeDegrees") is not None,
                "cloudCover": values.get("cloudCoverPercent") is not None, "visibility": values.get("visibilityMeters") is not None}})
        return base
