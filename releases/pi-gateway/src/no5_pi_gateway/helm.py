"""Configuration-driven, read-only Signal K projection for No5 HELM V1.

The gateway intentionally has no built-in Signal K paths.  A vessel must map
each supported No5 field and all source clocks explicitly in its local TOML
configuration.  This prevents a plausible-but-wrong Signal K data path from
being presented as real boat telemetry.
"""

from __future__ import annotations

import asyncio
import contextlib
from dataclasses import dataclass
from datetime import datetime, timezone
import json
import math
import re
import time
from typing import Any

from .configuration import HELM_FIELDS, GatewayConfig, HelmFieldMapping, SignalKConfig
from .protocol import ProtocolError, strict_json_object
from .position_combination import combine


MAX_GATEWAY_JSON_BYTES = 65_536
MAX_POSITION_SOURCES = 16
MAX_POSITION_SOURCES_BYTES = 24_576
_MISSING = object()
_HMS = re.compile(r"(?:[01]\d|2[0-3]):[0-5]\d:[0-5]\d\Z")

# These are No5's wire-level validity limits, not boating operating limits.
_NUMBER_RANGES: dict[str, tuple[float, float]] = {
    "portRpm": (0, 100_000), "starboardRpm": (0, 100_000),
    "portTrim": (0, 100), "starboardTrim": (0, 100),
    "portFuel": (0, 100), "starboardFuel": (0, 100),
    "heading": (0, 359.999999999), "speed": (0, 200), "depth": (0, 12_000),
    "waterTemp": (-5, 60), "portVoltage": (0, 60), "starboardVoltage": (0, 60),
    "houseVoltage": (0, 60), "portCoolantC": (-50, 200),
    "starboardCoolantC": (-50, 200), "portOilBar": (0, 100),
    "starboardOilBar": (0, 100), "portFuelFlowLph": (0, 10_000),
    "starboardFuelFlowLph": (0, 10_000), "totalFuelFlowLph": (0, 20_000),
    "latitudeDegrees": (-90, 90), "longitudeDegrees": (-180, 180),
    "fuelRemainingLiters": (0, 1_000_000), "courseOverGroundDegrees": (0, 359.999999999),
    "tripDistanceNm": (0, 1_000_000), "tripDurationSeconds": (0, 31_536_000_000),
    "fuelUsedLiters": (0, 1_000_000), "efficiencyNmPerLiter": (0, 1_000_000),
}
_VALUE_FIELDS = tuple(field for field in HELM_FIELDS if field in _NUMBER_RANGES or field.endswith("Gear"))
_EXTRA_FIELDS = ("tripDistanceNm", "tripDurationSeconds", "fuelUsedLiters", "efficiencyNmPerLiter")
_MEASUREMENT_FIELDS = tuple(field for field in _NUMBER_RANGES)
_VALUE_MEASUREMENTS = tuple(field for field in _MEASUREMENT_FIELDS if field not in _EXTRA_FIELDS)

_UNITS = {
    "speed": "kn", "heading": "deg_true", "courseOverGroundDegrees": "deg_true",
    "depth": "m", "waterTemp": "degC", "rpm": "rpm", "trim": "percent",
    "voltage": "V", "fuel": "percent", "coolant": "degC", "oil": "bar",
    "fuelFlow": "L/h", "position": "deg", "fuelRemainingLiters": "L",
}


class HelmError(ProtocolError):
    """A configured source did not provide enough valid real telemetry."""


def epoch_ms() -> int:
    return time.time_ns() // 1_000_000


def _pointer_tokens(pointer: str) -> list[str]:
    if pointer == "":
        return []
    if not pointer.startswith("/"):
        raise HelmError("JSON pointer must start with /")
    values: list[str] = []
    for token in pointer[1:].split("/"):
        decoded: list[str] = []
        index = 0
        while index < len(token):
            char = token[index]
            if char != "~":
                decoded.append(char)
                index += 1
            elif index + 1 >= len(token) or token[index + 1] not in {"0", "1"}:
                raise HelmError("invalid RFC6901 escape")
            else:
                decoded.append("~" if token[index + 1] == "0" else "/")
                index += 2
        values.append("".join(decoded))
    return values


def json_pointer(document: object, pointer: str) -> object:
    """Resolve one RFC6901 pointer without guessing a Signal K document shape."""

    current: object = document
    for token in _pointer_tokens(pointer):
        if isinstance(current, dict):
            if token not in current:
                return _MISSING
            current = current[token]
        elif isinstance(current, list):
            if not token.isdecimal() or (len(token) > 1 and token.startswith("0")):
                return _MISSING
            index = int(token)
            if index >= len(current):
                return _MISSING
            current = current[index]
        else:
            return _MISSING
    return current


def parse_epoch_ms(value: object) -> int:
    """Parse an unambiguous source timestamp; seconds are intentionally rejected."""

    if isinstance(value, bool):
        raise HelmError("timestamp must be epoch milliseconds or timezone-aware RFC3339")
    if isinstance(value, int):
        result = value
    elif isinstance(value, float) and math.isfinite(value) and value.is_integer():
        result = int(value)
    elif isinstance(value, str):
        try:
            parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
        except ValueError as exc:
            raise HelmError("timestamp is not RFC3339") from exc
        if parsed.tzinfo is None:
            raise HelmError("RFC3339 timestamp must include a timezone")
        result = int(parsed.timestamp() * 1000)
    else:
        raise HelmError("timestamp must be epoch milliseconds or timezone-aware RFC3339")
    if not 1 <= result <= (1 << 63) - 1:
        raise HelmError("timestamp is outside signed epoch-millisecond range")
    return result


def _vessel_time(value: object, format_name: str) -> str:
    if format_name == "hms":
        if not isinstance(value, str) or not _HMS.fullmatch(value):
            raise HelmError("vessel time must be HH:mm:ss")
        return value
    if format_name == "rfc3339_utc":
        if not isinstance(value, str):
            raise HelmError("vessel time must be RFC3339")
        try:
            parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
        except ValueError as exc:
            raise HelmError("vessel time is not RFC3339") from exc
        if parsed.tzinfo is None:
            raise HelmError("vessel time RFC3339 value must include a timezone")
        return parsed.astimezone(timezone.utc).strftime("%H:%M:%S")
    raise HelmError("unsupported vessel time format")


def _status_at(timestamp: int, now: int) -> str:
    age = now - timestamp
    if age < -5_000:
        return "INVALID"
    if age <= 5_000:
        return "LIVE"
    if age <= 30_000:
        return "STALE"
    return "LOST"


def _transform(value: object, mapping: HelmFieldMapping) -> object:
    if mapping.name.endswith("Gear"):
        if value not in {"F", "N", "R"}:
            raise HelmError(f"{mapping.name} must be F, N or R")
        return value
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise HelmError(f"{mapping.name} must be numeric")
    result = float(value)
    if mapping.transform == "radians_to_degrees":
        result = math.degrees(result) % 360.0
    elif mapping.transform == "metres_per_second_to_knots":
        result *= 1.9438444924406
    if not math.isfinite(result):
        raise HelmError(f"{mapping.name} must be finite")
    lower, upper = _NUMBER_RANGES[mapping.name]
    if not lower <= result <= upper:
        raise HelmError(f"{mapping.name} is outside the No5 wire range")
    return result


def _source_ref(value: object) -> str | None:
    # References are opaque: never remove a sentence/PGN suffix or guess that
    # two independently named sources represent the same physical receiver.
    if not isinstance(value, str) or not value.strip() or len(value) > 200:
        return None
    if not value.isprintable():
        return None
    return value


def _observation_time(record: dict[str, object], now: int) -> tuple[int | None, str]:
    try:
        observed = parse_epoch_ms(record.get("timestamp"))
    except (HelmError, ValueError, OverflowError):
        return None, "INVALID"
    # A source timestamp is never replaced with the poll/receipt time. This
    # extension is deliberately stricter about future fixes than the legacy
    # envelope's clock-skew allowance.
    if observed > now:
        return observed, "INVALID"
    return observed, _status_at(observed, now)


def _finite_number(value: object, lower: float, upper: float) -> float | None:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        return None
    try:
        result = float(value)
    except OverflowError:
        return None
    return result if math.isfinite(result) and lower <= result <= upper else None


_GNSS_ENUMS = {
    "type": {"Undefined", "GPS", "GLONASS", "Combined GPS/GLONASS", "Loran-C", "Chayka",
             "Integrated navigation system", "Surveyed", "Galileo"},
    "methodQuality": {"no GPS", "GNSS Fix", "DGNSS fix", "Precise GNSS", "RTK fixed integer",
                      "RTK Fixed Integer", "RTK float", "Estimated (DR) mode", "Manual input", "Simulator mode", "Error"},
    "integrity": {"no Integrity checking", "Safe", "Caution", "Unsafe"},
}
_GNSS_NUMBERS = ("satellites", "horizontalDilution", "positionDilution")


def _gnss_observations(gnss: object, source: str, now: int) -> dict[str, object]:
    if not isinstance(gnss, dict):
        return {}
    result: dict[str, object] = {}
    for name in (*_GNSS_ENUMS, *_GNSS_NUMBERS):
        leaf = gnss.get(name)
        if not isinstance(leaf, dict):
            continue
        # Every GNSS field has its own source and original observation time.
        # Do not attach the primary receiver's quality to another candidate.
        if _source_ref(leaf.get("$source")) == source:
            record: object = leaf
        else:
            alternatives = leaf.get("values")
            record = alternatives.get(source) if isinstance(alternatives, dict) else None
        if not isinstance(record, dict):
            continue
        observed, status = _observation_time(record, now)
        raw = record.get("value")
        if raw is None:
            value: object = None
            status = "UNAVAILABLE"
        elif name in _GNSS_ENUMS:
            value = raw if isinstance(raw, str) and raw in _GNSS_ENUMS[name] else None
        else:
            # DOP is dimensionless. No conversion to an invented metre accuracy.
            value = _finite_number(raw, 0.0, 1_000_000.0)
            if name == "satellites" and value is not None and not value.is_integer():
                value = None
        if raw is not None and value is None:
            status = "INVALID"
        if "$source" in record and _source_ref(record["$source"]) != source:
            status = "INVALID"
        result[name] = {"value": value if status in {"LIVE", "STALE"} else None,
                        "sourceRef": source, "observedAtEpochMs": observed, "status": status}
    return result


def _position_candidate(source: str, record: object, gnss: object, now: int) -> dict[str, object]:
    observed, status = _observation_time(record, now) if isinstance(record, dict) else (None, "INVALID")
    position = None
    if isinstance(record, dict):
        value = record.get("value")
        if value is None:
            status = "UNAVAILABLE"
        elif isinstance(value, dict):
            lat = _finite_number(value.get("latitude"), -90.0, 90.0)
            lon = _finite_number(value.get("longitude"), -180.0, 180.0)
            if lat is None or lon is None:
                status = "INVALID"
            elif status in {"LIVE", "STALE"}:
                position = {"latitudeDegrees": lat, "longitudeDegrees": lon,
                            "altitudeMetres": _finite_number(value.get("altitude"), -100_000.0, 100_000.0)}
        else:
            status = "INVALID"
        if "$source" in record and _source_ref(record["$source"]) != source:
            status = "INVALID"
    quality = _gnss_observations(gnss, source, now)
    # Only a quality observation from this exact fix can invalidate that fix.
    # Independently timed quality remains visible and must not be advertised as
    # simultaneous evidence for a different position observation.
    for name, invalid_values in (("methodQuality", {"no GPS", "Error", "Manual input", "Simulator mode"}),
                                 ("integrity", {"Unsafe"})):
        observation = quality.get(name)
        if isinstance(observation, dict) and observation["status"] == "LIVE" and observation["observedAtEpochMs"] == observed and observation["value"] in invalid_values:
            status = "INVALID"
    if status not in {"LIVE", "STALE"}:
        position = None
    return {"sourceId": source, "sourceRef": source, "label": source,
            "status": status, "observedAtEpochMs": observed, "position": position, "gnss": quality}


def _position_sources(document: dict[str, object], config: SignalKConfig, now: int) -> dict[str, object]:
    assert config.position_pointer is not None
    leaf = json_pointer(document, config.position_pointer)
    gnss = json_pointer(document, config.gnss_pointer) if config.gnss_pointer is not None else None
    records: dict[str, object] = {}
    primary = _source_ref(leaf.get("$source")) if isinstance(leaf, dict) else None
    if primary is not None:
        records[primary] = leaf
    alternatives = leaf.get("values") if isinstance(leaf, dict) else None
    if isinstance(alternatives, dict):
        for reference, record in alternatives.items():
            source = _source_ref(reference)
            if source is not None:
                # The primary leaf and values[the same exact reference] are
                # two views of one source. The primary observation is canonical.
                records.setdefault(source, record)
    ordered = sorted(records, key=lambda source: (source != primary, source))
    sources = [_position_candidate(source, records[source], gnss, now)
               for source in ordered[:MAX_POSITION_SOURCES]]
    catalog = {"version": 1, "sourceKind": "REAL", "simulated": False,
               "primarySourceId": primary, "total": len(records),
               "omitted": len(records) - len(sources),
               "freshness": {"liveForMs": 5_000, "lostAfterMs": 30_000}, "sources": sources,
               "combination": combine(sources, config, now, truncated=any(
                   ref in records and ref not in {s["sourceId"] for s in sources} for ref in config.combination_sources))}
    # Bound the actual standard JSON representation, including escaped Unicode.
    # A huge optional catalogue must not make existing HELM telemetry unusable.
    while len(json.dumps(catalog).encode("utf-8")) > MAX_POSITION_SOURCES_BYTES:
        sources.pop()
        catalog["omitted"] = len(records) - len(sources)
        catalog["combination"] = combine(sources, config, now, truncated=any(
            ref in records and ref not in {s["sourceId"] for s in sources} for ref in config.combination_sources))
    return catalog


@dataclass(frozen=True, slots=True)
class HelmProjection:
    payload: dict[str, object]
    source_timestamp_ms: int


class HelmProjector:
    """Construct a validated No5 HELM V1 payload from one source snapshot."""

    def __init__(self, config: SignalKConfig, server_session_id: str, *, now: callable = epoch_ms) -> None:
        self._config = config
        self._server_session_id = server_session_id
        self._now = now

    def project(self, document: dict[str, object]) -> HelmProjection:
        now = self._now()
        published_value = json_pointer(document, self._config.published_at_pointer or "")
        vessel_value = json_pointer(document, self._config.vessel_time_pointer or "")
        if published_value is _MISSING or vessel_value is _MISSING:
            raise HelmError("configured Signal K publication or vessel-time pointer is absent")
        published = parse_epoch_ms(published_value)
        overall_status = _status_at(published, now)
        vessel_time = _vessel_time(vessel_value, self._config.vessel_time_format)
        mappings = {mapping.name: mapping for mapping in self._config.fields}
        values: dict[str, object | None] = {field: None for field in _VALUE_FIELDS}
        qualities: dict[str, dict[str, object | None]] = {
            field: {"status": "UNAVAILABLE", "timestampEpochMs": None}
            for field in _MEASUREMENT_FIELDS
        }
        observation_times: list[int] = []
        # Retained only for a LOST/INVALID envelope.  It is never made into a
        # current value, but keeps the source-time diagnosis visible rather
        # than turning a valid old producer packet into a timeless HTTP error.
        noncurrent_observation_times: list[int] = []

        for name, mapping in mappings.items():
            raw_value = json_pointer(document, mapping.value_pointer)
            raw_timestamp = json_pointer(document, mapping.timestamp_pointer)
            if raw_value is _MISSING or raw_timestamp is _MISSING or raw_value is None or raw_timestamp is None:
                continue
            try:
                observed = parse_epoch_ms(raw_timestamp)
                if observed > published:
                    raise HelmError("field observation follows source publication")
                transformed = _transform(raw_value, mapping)
                status = _status_at(observed, now)
                if overall_status in {"INVALID", "LOST"}:
                    status = overall_status
                if name in qualities:
                    noncurrent_observation_times.append(observed)
            except HelmError:
                if name in qualities:
                    qualities[name] = {"status": "INVALID", "timestampEpochMs": None}
                continue
            if name in qualities:
                if status in {"LIVE", "STALE"}:
                    values[name] = transformed
                    qualities[name] = {"status": status, "timestampEpochMs": observed}
                    observation_times.append(observed)
                else:
                    qualities[name] = {"status": status, "timestampEpochMs": None}
            elif status in {"LIVE", "STALE"}:
                values[name] = transformed

        # Gear is part of its engine's observation group, but HELM V1 has no
        # standalone gear quality.  Never retain it when the RPM evidence fails.
        for gear, rpm in (("portGear", "portRpm"), ("starboardGear", "starboardRpm")):
            if qualities[rpm]["status"] not in {"LIVE", "STALE"}:
                values[gear] = None

        if values["latitudeDegrees"] is None or values["longitudeDegrees"] is None:
            values["latitudeDegrees"] = None
            values["longitudeDegrees"] = None
            for field in ("latitudeDegrees", "longitudeDegrees"):
                if qualities[field]["status"] in {"LIVE", "STALE"}:
                    qualities[field] = {"status": "UNAVAILABLE", "timestampEpochMs": None}
            observation_times = [
                stamp for field, stamp in ((field, qualities[field]["timestampEpochMs"])
                                             for field in _MEASUREMENT_FIELDS)
                if field not in {"latitudeDegrees", "longitudeDegrees"} and isinstance(stamp, int)
            ]

        timestamp_candidates = observation_times or noncurrent_observation_times
        if not timestamp_candidates:
            raise HelmError("no configured numeric HELM observation is usable")
        timestamp = min(timestamp_candidates)
        capabilities = {
            "telemetry": True,
            "position": all(values[field] is not None for field in ("latitudeDegrees", "longitudeDegrees")),
            "depth": values["depth"] is not None,
            "waterTemperature": values["waterTemp"] is not None,
            "commands": False,
        }
        payload = {
            "version": 1,
            "kind": "helm",
            "source": self._config.source_name,
            "sourceKind": "REAL",
            "simulated": False,
            "timestampEpochMs": timestamp,
            "publishedAtEpochMs": published,
            "serverSessionId": self._server_session_id,
            "status": overall_status,
            "simulation": None,
            "running": overall_status in {"LIVE", "STALE"},
            "vesselTime": vessel_time,
            "units": _UNITS,
            "capabilities": capabilities,
            "values": {field: values[field] for field in _VALUE_MEASUREMENTS + ("portGear", "starboardGear")},
            "extras": {field: values[field] for field in _EXTRA_FIELDS},
            "fieldQuality": qualities,
        }
        if self._config.position_pointer is not None:
            # The legacy publication pointer may itself be another sensor's
            # observation timestamp. It cannot invalidate independently timed
            # candidates or be substituted for their original source clocks.
            payload["positionSources"] = _position_sources(document, self._config, now)
        if len(json.dumps(payload).encode("utf-8")) > MAX_GATEWAY_JSON_BYTES:
            raise HelmError("HELM projection exceeds 65536 bytes")
        return HelmProjection(payload, published)


class SignalKSnapshotClient:
    """Strict, bounded HTTP reader for an explicitly configured snapshot URL."""

    def __init__(self, config: SignalKConfig) -> None:
        self._config = config

    async def fetch(self, session: Any) -> dict[str, object]:
        assert self._config.snapshot_url is not None
        async with session.get(self._config.snapshot_url, allow_redirects=False,
                               # Signal K 2.13.5 on lysmarine advertises a
                               # compressed response unless identity is made
                               # explicit. This adapter intentionally rejects
                               # content encodings, so request identity rather
                               # than relying on client-library defaults.
                               headers={"Accept": "application/json", "Accept-Encoding": "identity",
                                        "Cache-Control": "no-cache, no-store"}) as response:
            if response.status != 200:
                raise HelmError(f"Signal K snapshot returned HTTP {response.status}")
            content_type = response.headers.get("Content-Type", "").split(";", 1)[0].strip().lower()
            if content_type != "application/json" and not content_type.endswith("+json"):
                raise HelmError("Signal K snapshot did not declare a JSON media type")
            encoding = response.headers.get("Content-Encoding", "identity").lower()
            if encoding not in {"", "identity"}:
                raise HelmError("Signal K snapshot used unsupported content encoding")
            raw = await response.content.read(MAX_GATEWAY_JSON_BYTES + 1)
        if len(raw) > MAX_GATEWAY_JSON_BYTES:
            raise HelmError("Signal K snapshot exceeds 65536 bytes")
        try:
            return strict_json_object(raw)
        except ProtocolError as exc:
            raise HelmError("Signal K snapshot JSON is invalid") from exc


class HelmAdapter:
    """One serialized polling loop; failed input is never served as fresh data."""

    def __init__(self, config: GatewayConfig, server_session_id: str) -> None:
        self._config = config.helm
        self._projector = HelmProjector(config.helm, server_session_id)
        self._client = SignalKSnapshotClient(config.helm)
        self._snapshot: dict[str, object] | None = None
        self._last_error: str | None = None
        self._stop = asyncio.Event()

    @property
    def configured(self) -> bool:
        return self._config.enabled

    @property
    def snapshot(self) -> dict[str, object] | None:
        return self._snapshot

    @property
    def last_error(self) -> str | None:
        return self._last_error

    async def stop(self) -> None:
        self._stop.set()

    async def refresh(self, session: Any) -> None:
        """Read and atomically replace the app-visible projection once."""

        document = await self._client.fetch(session)
        self._snapshot = self._projector.project(document).payload
        self._last_error = None

    async def run(self) -> None:
        if not self._config.enabled:
            return
        import aiohttp

        timeout = aiohttp.ClientTimeout(total=3.0)
        async with aiohttp.ClientSession(timeout=timeout, auto_decompress=False) as session:
            while not self._stop.is_set():
                try:
                    await self.refresh(session)
                except asyncio.CancelledError:
                    raise
                except Exception as exc:
                    self._snapshot = None
                    self._last_error = f"Signal K input rejected: {type(exc).__name__}"
                with contextlib.suppress(asyncio.TimeoutError):
                    await asyncio.wait_for(self._stop.wait(), self._config.poll_interval_s)
