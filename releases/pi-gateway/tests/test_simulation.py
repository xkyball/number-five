from __future__ import annotations

import json
from pathlib import Path
import tempfile
import unittest

from no5_pi_gateway.configuration import GatewayConfig, SimulationConfig
from no5_pi_gateway.simulation import SimulationControlError, SimulationController, default_settings, validate_settings


NOW = 1_800_000_000_000


class Clock:
    def __init__(self) -> None:
        self.epoch = NOW
        self.monotonic_seconds = 100.0

    def now(self) -> int:
        return self.epoch

    def monotonic(self) -> float:
        return self.monotonic_seconds


class SimulationControllerTest(unittest.TestCase):
    def configured(self, state_file: str, clock: Clock, **sources: str) -> SimulationController:
        return SimulationController(
            GatewayConfig(simulation=SimulationConfig(enabled=True, state_file=state_file, **sources)),
            "gateway-session", now=clock.now, monotonic=clock.monotonic,
        )

    @staticmethod
    def command(controller: SimulationController, settings: dict[str, object], request_id: str = "simulation-001") -> dict[str, object]:
        return controller.apply({"version": 1, "serverSessionId": "gateway-session", "expectedRevision": controller.control_state()["revision"],
            "requestId": request_id, "action": "apply", "settings": settings})

    def test_selected_helm_field_is_dynamic_and_unselected_real_or_missing_is_preserved(self) -> None:
        clock = Clock()
        real = {"values": {"speed": 6.0, "heading": 90.0}, "extras": {"tripDistanceNm": None},
                "fieldQuality": {"speed": {"status": "LIVE", "timestampEpochMs": NOW}, "heading": {"status": "STALE", "timestampEpochMs": NOW - 20}}}
        with tempfile.TemporaryDirectory() as directory:
            controller = self.configured(str(Path(directory) / "state.json"), clock)
            settings = default_settings(); settings["helm"]["overlayFields"] = ["speed"]  # type: ignore[index]
            self.command(controller, settings)
            payload = controller.helm_payload(real)
            assert payload is not None
            self.assertEqual(payload["sourceKind"], "SIMULATED")
            self.assertNotEqual(payload["values"]["speed"], 6.0)
            self.assertEqual(payload["values"]["heading"], 90.0)
            self.assertEqual(payload["fieldQuality"]["heading"]["status"], "STALE")
            self.assertEqual(payload["extras"]["tripDistanceNm"], None)
            for _ in range(4):
                clock.monotonic_seconds += 5
                controller.advance()
            self.assertNotEqual(controller.helm_payload(real)["values"]["speed"], payload["values"]["speed"])  # type: ignore[index]

    def test_missing_real_fields_stay_missing_when_not_selected(self) -> None:
        clock = Clock()
        with tempfile.TemporaryDirectory() as directory:
            controller = self.configured(str(Path(directory) / "state.json"), clock)
            settings = default_settings(); settings["helm"]["overlayFields"] = ["depth"]  # type: ignore[index]
            self.command(controller, settings)
            payload = controller.helm_payload(None)
            assert payload is not None
            self.assertIsNotNone(payload["values"]["depth"])
            self.assertIsNone(payload["values"]["speed"])
            self.assertEqual(payload["fieldQuality"]["speed"]["status"], "UNAVAILABLE")

    def test_weather_is_field_mixed_and_not_a_static_scene(self) -> None:
        clock = Clock()
        real = {"values": {"airTemperatureC": 19.0, "windSpeedKn": 4.0, "windDirectionDegrees": 5.0, "pressureHpa": None},
                "source": "weather", "sourceKind": "REAL", "simulated": False, "capabilities": {}}
        with tempfile.TemporaryDirectory() as directory:
            controller = self.configured(str(Path(directory) / "state.json"), clock)
            settings = default_settings(); settings["weather"]["overlayFields"] = ["windSpeedKn", "windDirectionDegrees"]  # type: ignore[index]
            self.command(controller, settings)
            payload = controller.weather_payload(real)
            assert payload is not None
            self.assertEqual(payload["values"]["airTemperatureC"], 19.0)
            self.assertIsNone(payload["values"]["pressureHpa"])
            self.assertNotEqual(payload["values"]["windSpeedKn"], 4.0)
            clock.monotonic_seconds += 120
            self.assertNotEqual(controller.weather_payload(real)["values"]["windSpeedKn"], payload["values"]["windSpeedKn"])

    def test_sonar_is_only_explicit_m0_or_m1_daemon_selection(self) -> None:
        clock = Clock()
        with tempfile.TemporaryDirectory() as directory:
            controller = self.configured(str(Path(directory) / "state.json"), clock,
                sonar_m0_upstream_base_url="http://127.0.0.1:8090", sonar_m1_upstream_base_url="http://127.0.0.1:8091")
            settings = default_settings(); settings["sonar"]["mode"] = "M0"  # type: ignore[index]
            self.command(controller, settings)
            self.assertEqual(controller.source_config().producer, "m0")
            self.assertEqual(controller.source_config().upstream_base_url, "http://127.0.0.1:8090")
            self.assertNotIn("audio", controller.control_state()["settings"])
            self.assertTrue(controller.control_state()["sonar"]["sourceIsDaemon"])

    def test_unprovisioned_sonar_mode_is_rejected_and_settings_persist(self) -> None:
        clock = Clock()
        with tempfile.TemporaryDirectory() as directory:
            state_file = str(Path(directory) / "state.json")
            controller = self.configured(state_file, clock)
            invalid = default_settings(); invalid["sonar"]["mode"] = "M0"  # type: ignore[index]
            with self.assertRaises(SimulationControlError): self.command(controller, invalid)
            selected = default_settings(); selected["helm"]["overlayFields"] = ["speed"]  # type: ignore[index]
            self.command(controller, selected)
            persisted = json.loads(Path(state_file).read_text())
            self.assertEqual(persisted["version"], 2)
            self.assertEqual(self.configured(state_file, clock).control_state()["settings"]["helm"]["overlayFields"], ["speed"])

    def test_coordinate_pair_and_idempotent_replay(self) -> None:
        invalid = default_settings(); invalid["helm"]["overlayFields"] = ["latitudeDegrees"]  # type: ignore[index]
        with self.assertRaises(SimulationControlError): validate_settings(invalid)
        clock = Clock()
        with tempfile.TemporaryDirectory() as directory:
            controller = self.configured(str(Path(directory) / "state.json"), clock)
            accepted = default_settings(); accepted["helm"]["overlayFields"] = ["speed"]  # type: ignore[index]
            first = self.command(controller, accepted, "simulation-002")
            replay = controller.apply({"version": 1, "serverSessionId": "gateway-session", "expectedRevision": 0,
                "requestId": "simulation-002", "action": "apply", "settings": accepted})
            self.assertFalse(first["replayed"]); self.assertTrue(replay["replayed"])
