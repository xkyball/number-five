from __future__ import annotations

import json
import os
from pathlib import Path
import time
import unittest
from unittest.mock import patch

from no5_pi_gateway.configuration import GatewayConfig, WeatherConfig
from no5_pi_gateway.weather import WeatherAdapter, WeatherError


NOW = 1_800_000_000_000


def weather_config() -> WeatherConfig:
    return WeatherConfig(
        enabled=True, source_name="no5-weather", snapshot_url="http://127.0.0.1:3000/signalk/v1/api/vessels/self",
        latitude_value_pointer="/navigation/position/value/latitude",
        latitude_timestamp_pointer="/navigation/position/timestamp",
        longitude_value_pointer="/navigation/position/value/longitude",
        longitude_timestamp_pointer="/navigation/position/timestamp",
    )


def provider_response() -> dict[str, object]:
    return {
        "utc_offset_seconds": 0,
        "latitude": -34.25,
        "longitude": 18.625,
        "current_units": {
            "time": "unixtime", "interval": "seconds", "temperature_2m": "°C",
            "relative_humidity_2m": "%", "pressure_msl": "hPa", "wind_speed_10m": "kn",
            "wind_direction_10m": "°", "wind_gusts_10m": "kn", "cloud_cover": "%", "visibility": "m",
        },
        "current": {
            "time": NOW // 1_000, "interval": 900, "temperature_2m": 0.0,
            "relative_humidity_2m": 68.0, "pressure_msl": 1015.0, "wind_speed_10m": 0.0,
            "wind_direction_10m": 360.0, "wind_gusts_10m": 13.0, "cloud_cover": 12.0, "visibility": 15_000.0,
            "weather_code": 1,
        },
    }


class WeatherAdapterTest(unittest.TestCase):
    def setUp(self) -> None:
        self.adapter = WeatherAdapter(GatewayConfig(weather=weather_config()), "gateway-1")

    def test_explicit_position_and_provider_grid_are_kept_distinct(self) -> None:
        document = {
            "navigation": {"position": {
                "value": {"latitude": -34.3, "longitude": 18.6},
                "timestamp": "2027-01-15T08:00:00Z",
            }},
        }
        with patch("no5_pi_gateway.weather.epoch_ms", return_value=NOW):
            self.assertEqual((-34.3, 18.6), self.adapter._position(document))
            payload = self.adapter._decode(provider_response(), -34.3, 18.6)
        self.assertFalse(payload["simulated"])
        self.assertEqual("FORECAST", payload["provider"]["kind"])  # type: ignore[index]
        self.assertEqual(-34.3, payload["provenance"]["requestedLatitude"])  # type: ignore[index]
        self.assertEqual(-34.25, payload["values"]["latitudeDegrees"])  # type: ignore[index]
        self.assertEqual(0.0, payload["values"]["airTemperatureC"])  # type: ignore[index]
        self.assertEqual(0.0, payload["values"]["windDirectionDegrees"])  # type: ignore[index]
        self.assertEqual(NOW, payload["timestampEpochMs"])
        self.assertEqual(NOW, payload["observedAtEpochMs"])

    def test_stale_position_never_selects_a_forecast_location(self) -> None:
        document = {
            "navigation": {"position": {
                "value": {"latitude": -34.3, "longitude": 18.6},
                "timestamp": "2027-01-15T07:59:00Z",
            }},
        }
        with patch("no5_pi_gateway.weather.epoch_ms", return_value=NOW):
            with self.assertRaises(WeatherError):
                self.adapter._position(document)

    def test_current_gateway_schema_accepts_real_forecast_payload(self) -> None:
        try:
            from jsonschema import Draft202012Validator
        except ImportError:
            self.skipTest("jsonschema is optional")
        supplied = os.environ.get("NO5_HELM_CLIENT_ROOT")
        if not supplied:
            self.skipTest("set NO5_HELM_CLIENT_ROOT to validate the separately-owned Android schema")
        client_root = Path(supplied)
        schema = json.loads((client_root / "contracts" / "no5-gateway-v1.schema.json").read_text())
        with patch("no5_pi_gateway.weather.epoch_ms", return_value=NOW):
            payload = self.adapter._decode(provider_response(), -34.3, 18.6)
        Draft202012Validator(schema).validate(payload)

    def test_provider_failure_leaves_no_fabricated_initial_payload(self) -> None:
        self.assertIsNone(self.adapter.payload)
        self.assertEqual("UNAVAILABLE", self.adapter._payload_status())
