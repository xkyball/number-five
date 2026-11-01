"""Real Open-Meteo forecast adapter for the existing No5 Gateway V1 contract.

Weather is deliberately independent of HELM publication: it may be unavailable
while other gateway paths are healthy.  Its only location input is an explicit,
fresh Signal K latitude/longitude mapping.  There is no stored fallback,
handset position, simulator value or write endpoint.
"""

from __future__ import annotations

import asyncio
import contextlib
import copy
import math
import time
from typing import Any
from urllib.parse import urlencode

from .configuration import GatewayConfig, WeatherConfig
from .helm import HelmError, json_pointer, parse_epoch_ms
from .protocol import ProtocolError, strict_json_object


MAX_JSON_BYTES = 65_536
OPEN_METEO_URL = "https://api.open-meteo.com/v1/forecast"
_UNITS = {
    "airTemperatureC": "degC", "windSpeedKn": "kn", "windDirectionDegrees": "deg_true_from",
    "relativeHumidityPercent": "percent", "pressureHpa": "hPa",
    "latitudeDegrees": "deg", "longitudeDegrees": "deg",
}


class WeatherError(ProtocolError):
    """No fresh, validated position or provider response is available."""


def epoch_ms() -> int:
    return time.time_ns() // 1_000_000


def _number(value: object, label: str, lower: float, upper: float) -> float:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise WeatherError(f"{label} must be numeric")
    result = float(value)
    if not math.isfinite(result) or not lower <= result <= upper:
        raise WeatherError(f"{label} is outside its accepted range")
    return result


def _optional_number(table: dict[str, object], key: str, lower: float, upper: float) -> float | None:
    if key not in table or table[key] is None:
        return None
    return _number(table[key], key, lower, upper)


def _object(value: object, label: str) -> dict[str, object]:
    if not isinstance(value, dict):
        raise WeatherError(f"{label} must be an object")
    return value


class WeatherAdapter:
    """A bounded forecast cache with explicit Signal K position provenance."""

    def __init__(self, config: GatewayConfig, server_session_id: str) -> None:
        self._config: WeatherConfig = config.weather
        self._server_session_id = server_session_id
        self._payload: dict[str, object] | None = None
        self._last_error: str | None = None
        self._next_attempt_epoch_ms = 0
        self._stop = asyncio.Event()

    @property
    def configured(self) -> bool:
        return self._config.enabled

    @property
    def last_error(self) -> str | None:
        return self._last_error

    def _payload_status(self) -> str:
        if self._payload is None:
            return "UNAVAILABLE"
        observed = self._payload.get("observedAtEpochMs")
        fetched = self._payload.get("timestampEpochMs")
        if not isinstance(observed, int) or not isinstance(fetched, int):
            return "INVALID"
        now = epoch_ms()
        # Forecasts are model observations.  A re-poll never turns an old
        # model interval back into LIVE merely by receiving the JSON again.
        age = max(now - observed, now - fetched)
        if observed > now + 5_000 or fetched > now + 5_000:
            return "INVALID"
        if age <= 30 * 60_000 and self._last_error is None:
            return "LIVE"
        if age <= 60 * 60_000:
            return "STALE"
        return "LOST"

    @property
    def payload(self) -> dict[str, object] | None:
        if self._payload is None:
            return None
        status = self._payload_status()
        if status not in {"LIVE", "STALE"}:
            return None
        result = copy.deepcopy(self._payload)
        result["status"] = status
        return result

    async def stop(self) -> None:
        self._stop.set()

    async def _read_json(self, session: Any, url: str, *, expected_host: str | None = None) -> dict[str, object]:
        headers = {"Accept": "application/json", "Accept-Encoding": "identity", "Cache-Control": "no-cache, no-store"}
        async with session.get(url, allow_redirects=False, headers=headers) as response:
            if response.status != 200:
                raise WeatherError(f"weather upstream returned HTTP {response.status}")
            if expected_host is not None:
                final = response.url
                if final.scheme != "https" or final.host != expected_host or final.path != "/v1/forecast":
                    raise WeatherError("Open-Meteo response URL is not the approved endpoint")
            content_type = response.headers.get("Content-Type", "").split(";", 1)[0].strip().lower()
            if content_type != "application/json" and not content_type.endswith("+json"):
                raise WeatherError("weather upstream did not declare JSON")
            encoding = response.headers.get("Content-Encoding", "identity").lower()
            if encoding not in {"", "identity"}:
                raise WeatherError("weather upstream used unsupported content encoding")
            raw = await response.content.read(MAX_JSON_BYTES + 1)
        if len(raw) > MAX_JSON_BYTES:
            raise WeatherError("weather upstream response exceeds 65536 bytes")
        try:
            return strict_json_object(raw)
        except ProtocolError as exc:
            raise WeatherError("weather upstream JSON is invalid") from exc

    def _position(self, document: dict[str, object]) -> tuple[float, float]:
        config = self._config
        assert config.latitude_value_pointer and config.longitude_value_pointer
        assert config.latitude_timestamp_pointer and config.longitude_timestamp_pointer
        lat = _number(json_pointer(document, config.latitude_value_pointer), "Signal K latitude", -90.0, 90.0)
        lon = _number(json_pointer(document, config.longitude_value_pointer), "Signal K longitude", -180.0, 180.0)
        try:
            lat_observed = parse_epoch_ms(json_pointer(document, config.latitude_timestamp_pointer))
            lon_observed = parse_epoch_ms(json_pointer(document, config.longitude_timestamp_pointer))
        except HelmError as exc:
            raise WeatherError("Signal K position timestamp is invalid") from exc
        now = epoch_ms()
        if lat_observed > now + 5_000 or lon_observed > now + 5_000:
            raise WeatherError("Signal K position timestamp is in the future")
        if now - min(lat_observed, lon_observed) > int(config.position_stale_after_s * 1_000):
            raise WeatherError("Signal K position is stale")
        return lat, lon

    @staticmethod
    def _request_url(latitude: float, longitude: float) -> str:
        params = {
            "latitude": f"{latitude:.5f}",
            "longitude": f"{longitude:.5f}",
            "current": "temperature_2m,relative_humidity_2m,pressure_msl,wind_speed_10m,wind_direction_10m,wind_gusts_10m,cloud_cover,visibility,weather_code",
            "wind_speed_unit": "kn",
            "temperature_unit": "celsius",
            "timeformat": "unixtime",
            "timezone": "GMT",
            "cell_selection": "sea",
            "forecast_hours": "24",
        }
        return f"{OPEN_METEO_URL}?{urlencode(params)}"

    def _decode(self, response: dict[str, object], latitude: float, longitude: float) -> dict[str, object]:
        if response.get("error") is True:
            raise WeatherError("Open-Meteo reported an error")
        if _number(response.get("utc_offset_seconds"), "Open-Meteo UTC offset", 0.0, 0.0) != 0.0:
            raise WeatherError("Open-Meteo response is not UTC")
        current = _object(response.get("current"), "Open-Meteo current")
        units = _object(response.get("current_units"), "Open-Meteo current_units")
        expected_units = {
            "time": "unixtime", "interval": "seconds", "temperature_2m": "°C",
            "relative_humidity_2m": "%", "pressure_msl": "hPa", "wind_speed_10m": "kn",
            "wind_direction_10m": "°", "wind_gusts_10m": "kn",
        }
        for key, unit in expected_units.items():
            if units.get(key) != unit:
                raise WeatherError(f"Open-Meteo unit for {key} is unsupported")
        observed_seconds = current.get("time")
        if isinstance(observed_seconds, bool) or not isinstance(observed_seconds, int):
            raise WeatherError("Open-Meteo model valid time is invalid")
        observed = observed_seconds * 1_000
        now = epoch_ms()
        if observed <= 0 or observed > now + 5_000 or now - observed > 2 * 60 * 60_000:
            raise WeatherError("Open-Meteo model valid time is outside the accepted window")
        interval = _number(current.get("interval"), "Open-Meteo current interval", 1.0, 3_600.0)
        if not interval.is_integer():
            raise WeatherError("Open-Meteo current interval is not whole seconds")
        values = {
            "airTemperatureC": _optional_number(current, "temperature_2m", -80.0, 80.0),
            "windSpeedKn": _optional_number(current, "wind_speed_10m", 0.0, 250.0),
            "windDirectionDegrees": _optional_number(current, "wind_direction_10m", 0.0, 360.0),
            "relativeHumidityPercent": _optional_number(current, "relative_humidity_2m", 0.0, 100.0),
            "pressureHpa": _optional_number(current, "pressure_msl", 800.0, 1_200.0),
            "latitudeDegrees": _number(response.get("latitude"), "Open-Meteo grid latitude", -90.0, 90.0),
            "longitudeDegrees": _number(response.get("longitude"), "Open-Meteo grid longitude", -180.0, 180.0),
        }
        if values["windDirectionDegrees"] == 360.0:
            values["windDirectionDegrees"] = 0.0
        clouds = _optional_number(current, "cloud_cover", 0.0, 100.0)
        visibility = _optional_number(current, "visibility", 0.0, 1_000_000.0)
        if clouds is not None and units.get("cloud_cover") != "%":
            raise WeatherError("Open-Meteo cloud-cover unit is unsupported")
        if visibility is not None and units.get("visibility") != "m":
            raise WeatherError("Open-Meteo visibility unit is unsupported")
        if all(values[name] is None for name in ("airTemperatureC", "windSpeedKn", "relativeHumidityPercent", "pressureHpa")):
            raise WeatherError("Open-Meteo response has no supported current values")
        # Current Open-Meteo data never supplies onboard observations.  Provider
        # grid coordinates and requested boat coordinates remain distinct.
        capabilities = {
            "airTemperature": values["airTemperatureC"] is not None,
            "wind": values["windSpeedKn"] is not None or values["windDirectionDegrees"] is not None,
            "humidity": values["relativeHumidityPercent"] is not None,
            "pressure": values["pressureHpa"] is not None,
            "observedLocation": True,
            "cloudCover": clouds is not None,
            "visibility": visibility is not None,
        }
        output_values = dict(values)
        output_values["cloudCoverPercent"] = clouds
        output_values["visibilityMeters"] = visibility
        output_units = dict(_UNITS)
        if clouds is not None:
            output_units["cloudCoverPercent"] = "percent"
        if visibility is not None:
            output_units["visibilityMeters"] = "m"
        fetched = epoch_ms()
        return {
            "version": 1,
            "kind": "weather",
            "source": self._config.source_name,
            "sourceKind": "REAL",
            "simulated": False,
            "serverSessionId": self._server_session_id,
            "timestampEpochMs": fetched,
            "publishedAtEpochMs": fetched,
            "status": "LIVE",
            "units": output_units,
            "provider": {"id": "OPEN_METEO", "label": "Open-Meteo · forecast model", "kind": "FORECAST"},
            "delivery": {"gatewayId": self._config.source_name, "transport": "HTTP"},
            "capabilities": capabilities,
            "observedAtEpochMs": observed,
            "pressureReference": "MEAN_SEA_LEVEL",
            "values": output_values,
            "provenance": {
                "requestedLatitude": round(latitude, 5),
                "requestedLongitude": round(longitude, 5),
                "fetchedAtEpochMs": fetched,
                "validAtEpochMs": observed,
                "measurementKind": "MODELLED_CURRENT_WEATHER",
            },
        }

    async def refresh(self, session: Any) -> None:
        if not self._config.enabled:
            return
        now = epoch_ms()
        if now < self._next_attempt_epoch_ms:
            return
        assert self._config.snapshot_url is not None
        try:
            position_document = await self._read_json(session, self._config.snapshot_url)
            latitude, longitude = self._position(position_document)
            forecast = await self._read_json(session, self._request_url(latitude, longitude), expected_host="api.open-meteo.com")
            self._payload = self._decode(forecast, latitude, longitude)
            self._last_error = None
            self._next_attempt_epoch_ms = now + int(self._config.refresh_interval_s * 1_000)
        except Exception as exc:
            self._last_error = f"Weather input rejected: {type(exc).__name__}"
            self._next_attempt_epoch_ms = now + int(self._config.retry_interval_s * 1_000)

    async def run(self) -> None:
        if not self._config.enabled:
            return
        import aiohttp

        timeout = aiohttp.ClientTimeout(total=8.0)
        async with aiohttp.ClientSession(timeout=timeout, auto_decompress=False) as session:
            while not self._stop.is_set():
                try:
                    await self.refresh(session)
                except asyncio.CancelledError:
                    raise
                # refresh handles validation/provider failures as a bounded
                # stale cache state; an unexpected task exception must not
                # take down HELM, SONAR or AUDIO.
                except Exception as exc:
                    self._last_error = f"Weather adapter failed: {type(exc).__name__}"
                with contextlib.suppress(asyncio.TimeoutError):
                    await asyncio.wait_for(self._stop.wait(), 1.0)
