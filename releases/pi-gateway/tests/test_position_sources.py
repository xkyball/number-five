"""Synthetic Signal K fixtures: no private vessel positions or source identities."""

from __future__ import annotations

import asyncio
from copy import deepcopy
from dataclasses import replace
import json
from pathlib import Path
import tempfile
import unittest

from no5_pi_gateway.configuration import ConfigurationError, GatewayConfig, HelmFieldMapping, SignalKConfig, SimulationConfig, load_config
from no5_pi_gateway.helm import HelmAdapter, HelmProjector, MAX_GATEWAY_JSON_BYTES, MAX_POSITION_SOURCES_BYTES
from no5_pi_gateway.simulation import SimulationController, default_settings


NOW = 1_800_000_000_000
SOURCE = "synthetic.gps.GP"


def config(**changes: object) -> SignalKConfig:
    return replace(SignalKConfig(enabled=True, snapshot_url="http://127.0.0.1:3000/confirmed/snapshot",
        published_at_pointer="/published", vessel_time_pointer="/vesselTime",
        fields=(HelmFieldMapping("speed", "/speed/value", "/speed/timestamp"),),
        position_pointer="/navigation/position", gnss_pointer="/navigation/gnss"), **changes)


def observation(value: object, stamp: object = NOW, source: str | None = SOURCE) -> dict[str, object]:
    record = {"value": value, "timestamp": stamp}
    if source is not None:
        record["$source"] = source
    return record


def document() -> dict[str, object]:
    return {"published": NOW, "vesselTime": "12:00:00", "speed": observation(0.0),
            "navigation": {"position": observation({"latitude": 0.0, "longitude": 0.0}),
                           "gnss": {"methodQuality": observation("GNSS Fix"),
                                    "horizontalDilution": observation(0.8)}}}


def project(data: dict[str, object], configuration: SignalKConfig | None = None) -> dict[str, object]:
    return HelmProjector(configuration or config(), "synthetic-session", now=lambda: NOW).project(data).payload


class PositionSourcesTest(unittest.TestCase):
    def catalog(self, data: dict[str, object], configuration: SignalKConfig | None = None) -> dict[str, object]:
        return project(data, configuration)["positionSources"]

    def test_opt_in_preserves_every_existing_wire_field_and_unit(self) -> None:
        data = document()
        legacy = project(data, config(position_pointer=None, gnss_pointer=None))
        enriched = project(data)
        self.assertNotIn("positionSources", legacy)
        del enriched["positionSources"]
        self.assertEqual(enriched, legacy)

    def test_single_complete_leaf_is_one_real_source_with_zero_coordinates(self) -> None:
        result = self.catalog(document())
        self.assertEqual((result["version"], result["total"], result["omitted"]), (1, 1, 0))
        self.assertEqual(result["primarySourceId"], SOURCE)
        self.assertEqual((result["sourceKind"], result["simulated"]), ("REAL", False))
        self.assertEqual(result["freshness"], {"liveForMs": 5000, "lostAfterMs": 30000})
        candidate = result["sources"][0]
        self.assertEqual(candidate["sourceId"], SOURCE)
        self.assertEqual(candidate["label"], SOURCE)
        self.assertEqual(candidate["sourceRef"], SOURCE)
        self.assertEqual(candidate["position"], {"latitudeDegrees": 0.0, "longitudeDegrees": 0.0, "altitudeMetres": None})
        self.assertEqual(candidate["status"], "LIVE")
        self.assertEqual(candidate["observedAtEpochMs"], NOW)
        self.assertEqual(set(candidate["gnss"]), {"methodQuality", "horizontalDilution"})
        self.assertNotIn("accuracyMetres", candidate)
        self.assertNotIn("motion", candidate)
        self.assertEqual(result["combination"], {"available": False, "reason": "COMBINATION_NOT_CONFIGURED"})

    def test_latitude_and_longitude_are_never_taken_from_different_observations(self) -> None:
        data = document()
        data["navigation"]["position"] = observation({"latitude": 1.0})
        data["navigation"]["position"]["values"] = {
            "second.gps": observation({"longitude": 2.0}, source=None)}
        result = self.catalog(data)
        self.assertEqual(result["total"], 2)
        for source in result["sources"]:
            self.assertEqual(source["status"], "INVALID")
            self.assertIsNone(source["position"])

    def test_primary_and_same_exact_values_reference_count_once(self) -> None:
        data = document()
        data["navigation"]["position"]["values"] = {
            SOURCE: observation({"latitude": 9.0, "longitude": 9.0}, NOW - 100, source=None),
            SOURCE + ".RMC": observation({"latitude": 2.0, "longitude": 3.0}, source=None)}
        result = self.catalog(data)
        self.assertEqual(result["total"], 2)
        self.assertEqual(result["sources"][0]["position"]["latitudeDegrees"], 0.0)
        self.assertEqual({s["sourceId"] for s in result["sources"]}, {SOURCE, SOURCE + ".RMC"})

    def test_each_quality_keeps_its_original_source_and_time(self) -> None:
        data = document()
        data["navigation"]["position"]["values"] = {
            "second.gps": observation({"latitude": 2.0, "longitude": 3.0}, source=None)}
        data["navigation"]["gnss"]["methodQuality"]["values"] = {
            "second.gps": observation("DGNSS fix", NOW - 100, source=None)}
        result = self.catalog(data)
        primary, second = result["sources"]
        self.assertEqual(primary["gnss"]["methodQuality"]["observedAtEpochMs"], NOW)
        self.assertEqual(second["gnss"]["methodQuality"], {
            "value": "DGNSS fix", "sourceRef": "second.gps", "observedAtEpochMs": NOW - 100, "status": "LIVE"})
        self.assertNotIn("horizontalDilution", second["gnss"])

    def test_future_and_stale_fix_boundaries(self) -> None:
        for age, expected in ((-1, "INVALID"), (0, "LIVE"), (5000, "LIVE"),
                              (5001, "STALE"), (30000, "STALE"), (30001, "LOST")):
            with self.subTest(age=age):
                data = document()
                data["navigation"]["position"]["timestamp"] = NOW - age
                source = self.catalog(data)["sources"][0]
                self.assertEqual(source["status"], expected)
                self.assertEqual(source["observedAtEpochMs"], NOW - age)
                self.assertEqual(source["position"] is not None, expected in {"LIVE", "STALE"})

    def test_other_sensor_publication_cannot_expire_or_freshen_a_position(self) -> None:
        data = document()
        data["published"] = NOW - 31_000
        data["speed"]["timestamp"] = NOW - 31_000
        payload = project(data)
        self.assertEqual(payload["status"], "LOST")
        source = payload["positionSources"]["sources"][0]
        self.assertEqual(source["status"], "LIVE")
        self.assertEqual(source["observedAtEpochMs"], NOW)
        self.assertIsNotNone(source["position"])
        data = document()
        data["navigation"]["position"]["timestamp"] = NOW - 31_000
        source = self.catalog(data)["sources"][0]
        self.assertEqual(source["status"], "LOST")
        self.assertEqual(source["observedAtEpochMs"], NOW - 31_000)
        self.assertIsNone(source["position"])

    def test_bad_timestamp_and_nonfinite_or_incomplete_position_are_not_fresh(self) -> None:
        invalid = (observation({"latitude": 0.0, "longitude": 0.0}, True),
                   observation({"latitude": float("nan"), "longitude": 0.0}),
                   observation({"latitude": 0.0, "longitude": 181.0}),
                   observation({"latitude": False, "longitude": 0.0}), observation("not a position"))
        for record in invalid:
            with self.subTest(record=record):
                data = document()
                data["navigation"]["position"] = record
                result = self.catalog(data)["sources"][0]
                self.assertEqual(result["status"], "INVALID")
                self.assertIsNone(result["position"])

    def test_missing_source_is_not_invented_and_values_sources_still_work(self) -> None:
        data = document()
        del data["navigation"]["position"]["$source"]
        result = self.catalog(data)
        self.assertEqual(result["sources"], [])
        self.assertIsNone(result["primarySourceId"])
        data["navigation"]["position"]["values"] = {
            SOURCE: observation({"latitude": 0.0, "longitude": 0.0}, source=None)}
        self.assertEqual(self.catalog(data)["total"], 1)

    def test_invalid_source_references_are_excluded_and_contradictions_invalid(self) -> None:
        data = document()
        data["navigation"]["position"]["values"] = {
            "": {}, "   ": {}, "newline\nsource": {}, "x" * 201: {},
            "second.gps": observation({"latitude": 2.0, "longitude": 3.0}, source="different.gps")}
        result = self.catalog(data)
        self.assertEqual(result["total"], 2)
        second = result["sources"][1]
        self.assertEqual(second["status"], "INVALID")
        self.assertIsNone(second["position"])

    def test_quality_is_not_fabricated_and_invalid_optional_quality_does_not_hide_position(self) -> None:
        data = document()
        data["navigation"]["gnss"] = {"satellites": observation(-1),
                                      "methodQuality": observation("invented-fix")}
        source = self.catalog(data)["sources"][0]
        self.assertEqual(source["status"], "LIVE")
        self.assertEqual(source["gnss"]["satellites"]["status"], "INVALID")
        self.assertIsNone(source["gnss"]["methodQuality"]["value"])
        self.assertNotIn("integrity", source["gnss"])

    def test_same_fix_no_fix_and_unsafe_evidence_disable_position(self) -> None:
        for name, value in (("methodQuality", "no GPS"), ("methodQuality", "Error"),
                            ("methodQuality", "Simulator mode"), ("integrity", "Unsafe")):
            with self.subTest(name=name, value=value):
                data = document()
                data["navigation"]["gnss"][name] = observation(value)
                source = self.catalog(data)["sources"][0]
                self.assertEqual(source["status"], "INVALID")
                self.assertIsNone(source["position"])
                self.assertEqual(source["gnss"][name]["value"], value)
        data["navigation"]["gnss"][name]["timestamp"] = NOW - 1
        self.assertEqual(self.catalog(data)["sources"][0]["status"], "LIVE")

    def test_source_limit_retains_primary_and_accounts_for_omitted_sources(self) -> None:
        data = document()
        data["navigation"]["position"]["values"] = {f"receiver.{n:02d}":
            observation({"latitude": 0.0, "longitude": 0.0}, source=None) for n in range(20)}
        result = self.catalog(data)
        self.assertEqual((result["total"], len(result["sources"]), result["omitted"]), (21, 16, 5))
        self.assertEqual(result["sources"][0]["sourceId"], SOURCE)

    def test_actual_serialized_unicode_block_and_envelope_are_bounded(self) -> None:
        data = document()
        references = ["é" * 197 + f"{n:03d}" for n in range(16)]
        data["navigation"]["position"] = observation({"latitude": 0.0, "longitude": 0.0}, source=references[0])
        data["navigation"]["position"]["values"] = {source:
            observation({"latitude": 0.0, "longitude": 0.0}, source=None) for source in references}
        data["navigation"]["gnss"] = {
            name: {"values": {source: observation(1.0, source=None) for source in references}}
            for name in ("satellites", "horizontalDilution", "positionDilution")}
        payload = project(data)
        result = payload["positionSources"]
        self.assertLessEqual(len(json.dumps(result).encode()), MAX_POSITION_SOURCES_BYTES)
        self.assertLessEqual(len(json.dumps(payload).encode()), MAX_GATEWAY_JSON_BYTES)
        self.assertGreater(result["omitted"], 0)
        self.assertEqual(result["omitted"], result["total"] - len(result["sources"]))
        self.assertEqual(result["sources"][0]["sourceId"], references[0])

    def test_explicit_root_pointer_uses_the_same_document(self) -> None:
        data = document()
        leaf = data["navigation"]["position"]
        leaf.update({key: data[key] for key in ("published", "vesselTime", "speed")})
        result = self.catalog(leaf, config(position_pointer="", gnss_pointer=None))
        self.assertEqual(result["total"], 1)
        self.assertEqual(result["sources"][0]["gnss"], {})

    def test_missing_optional_leaves_do_not_break_other_telemetry(self) -> None:
        data = document()
        del data["navigation"]
        payload = project(data)
        self.assertEqual(payload["values"]["speed"], 0.0)
        self.assertEqual(payload["positionSources"]["total"], 0)

    def test_adapter_obtains_position_and_quality_with_its_single_existing_fetch(self) -> None:
        class Client:
            calls = 0
            async def fetch(self, session: object) -> dict[str, object]:
                self.calls += 1
                return deepcopy(document())
        client = Client()
        adapter = HelmAdapter(GatewayConfig(helm=config()), "synthetic-session")
        adapter._client = client
        adapter._projector = HelmProjector(config(), "synthetic-session", now=lambda: NOW)
        asyncio.run(adapter.refresh(object()))
        self.assertEqual(client.calls, 1)
        self.assertEqual(adapter.snapshot["positionSources"]["total"], 1)

    def test_engine_simulation_preserves_independently_timed_real_position_provenance(self) -> None:
        real = project(document())
        with tempfile.TemporaryDirectory() as directory:
            controller = SimulationController(GatewayConfig(simulation=SimulationConfig(
                enabled=True, state_file=str(Path(directory) / "state.json"))),
                "synthetic-session", now=lambda: NOW + 1000, monotonic=lambda: 1.0)
            settings = default_settings()
            settings["helm"]["overlayFields"] = ["portRpm"]
            controller.apply({"version": 1, "serverSessionId": "synthetic-session",
                "expectedRevision": controller.control_state()["revision"], "requestId": "synthetic-overlay",
                "action": "apply", "settings": settings})
            combined = controller.helm_payload(real)
        self.assertTrue(combined["simulated"])
        self.assertEqual(combined["positionSources"], real["positionSources"])
        self.assertFalse(combined["positionSources"]["simulated"])
        self.assertEqual(combined["positionSources"]["sources"][0]["observedAtEpochMs"], NOW)


class PositionSourcesConfigurationTest(unittest.TestCase):
    def test_optional_pointers_are_explicit_bounded_and_validated(self) -> None:
        for pointer in ("not/a/pointer", "/invalid~2escape", "/" + "x" * 2000):
            with self.subTest(pointer=pointer), self.assertRaises(ConfigurationError):
                config(position_pointer=pointer)
        with self.assertRaises(ConfigurationError):
            config(position_pointer=None)
        self.assertEqual(config(position_pointer="/a~1b/~0c").position_pointer, "/a~1b/~0c")

    def test_toml_loader_preserves_optional_pointers_without_new_network_configuration(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "config.toml"
            path.write_text('[helm]\nposition_pointer="/navigation/position"\ngnss_pointer="/navigation/gnss"\n')
            loaded = load_config(path).helm
        self.assertEqual(loaded.position_pointer, "/navigation/position")
        self.assertEqual(loaded.gnss_pointer, "/navigation/gnss")
        self.assertIsNone(loaded.snapshot_url)
        self.assertFalse(loaded.enabled)
