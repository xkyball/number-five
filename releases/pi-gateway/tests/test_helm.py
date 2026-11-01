from __future__ import annotations

import unittest
import asyncio

from no5_pi_gateway.configuration import GatewayConfig, HelmFieldMapping, SignalKConfig
from no5_pi_gateway.helm import HelmAdapter, HelmError, HelmProjector, SignalKSnapshotClient, json_pointer


NOW = 1_800_000_000_000


def signal_k_config(*fields: HelmFieldMapping) -> SignalKConfig:
    return SignalKConfig(
        enabled=True,
        snapshot_url="http://127.0.0.1:3000/confirmed/snapshot",
        source_name="signalk-main",
        published_at_pointer="/published",
        vessel_time_pointer="/vesselTime",
        fields=fields,
    )


class HelmProjectionTest(unittest.TestCase):
    def test_explicit_pointer_mapping_preserves_source_times_and_units(self) -> None:
        config = signal_k_config(
            HelmFieldMapping("speed", "/navigation/speed/value", "/navigation/speed/observed", "metres_per_second_to_knots"),
            HelmFieldMapping("heading", "/navigation/heading/value", "/navigation/heading/observed", "radians_to_degrees"),
            HelmFieldMapping("latitudeDegrees", "/position/latitude/value", "/position/latitude/observed"),
            HelmFieldMapping("longitudeDegrees", "/position/longitude/value", "/position/longitude/observed"),
            HelmFieldMapping("portRpm", "/engines/port/rpm/value", "/engines/port/rpm/observed"),
            HelmFieldMapping("portGear", "/engines/port/gear/value", "/engines/port/gear/observed"),
        )
        document = {
            "published": NOW - 100,
            "vesselTime": "12:34:56",
            "navigation": {
                "speed": {"value": 10.0, "observed": NOW - 400},
                "heading": {"value": 3.141592653589793, "observed": NOW - 300},
            },
            "position": {
                "latitude": {"value": 54.5, "observed": NOW - 200},
                "longitude": {"value": 12.2, "observed": NOW - 200},
            },
            "engines": {"port": {
                "rpm": {"value": 850.0, "observed": NOW - 500},
                "gear": {"value": "F", "observed": NOW - 500},
            }},
        }

        payload = HelmProjector(config, "session-1", now=lambda: NOW).project(document).payload

        self.assertEqual(payload["sourceKind"], "REAL")
        self.assertFalse(payload["simulated"])
        self.assertEqual(payload["publishedAtEpochMs"], NOW - 100)
        self.assertEqual(payload["timestampEpochMs"], NOW - 500)
        self.assertEqual(payload["vesselTime"], "12:34:56")
        self.assertAlmostEqual(payload["values"]["speed"], 19.438444924406)
        self.assertAlmostEqual(payload["values"]["heading"], 180.0)
        self.assertEqual(payload["values"]["portGear"], "F")
        self.assertEqual(payload["fieldQuality"]["portRpm"], {"status": "LIVE", "timestampEpochMs": NOW - 500})
        self.assertEqual(payload["units"]["speed"], "kn")

    def test_incomplete_position_is_not_fabricated(self) -> None:
        config = signal_k_config(
            HelmFieldMapping("speed", "/speed/value", "/speed/observed"),
            HelmFieldMapping("latitudeDegrees", "/latitude/value", "/latitude/observed"),
        )
        document = {
            "published": NOW,
            "vesselTime": "00:00:00",
            "speed": {"value": 2.0, "observed": NOW},
            "latitude": {"value": 54.5, "observed": NOW},
        }
        payload = HelmProjector(config, "session-1", now=lambda: NOW).project(document).payload
        self.assertIsNone(payload["values"]["latitudeDegrees"])
        self.assertIsNone(payload["values"]["longitudeDegrees"])
        self.assertFalse(payload["capabilities"]["position"])
        self.assertEqual(payload["fieldQuality"]["latitudeDegrees"]["status"], "UNAVAILABLE")

    def test_bad_or_missing_clock_does_not_become_current_data(self) -> None:
        config = signal_k_config(HelmFieldMapping("speed", "/speed/value", "/speed/observed"))
        with self.assertRaises(HelmError):
            HelmProjector(config, "session-1", now=lambda: NOW).project({
                "published": NOW,
                "vesselTime": "00:00:00",
                "speed": {"value": 2.0, "observed": NOW + 1},
            })

    def test_old_but_valid_source_is_reported_lost_with_its_original_time(self) -> None:
        config = signal_k_config(HelmFieldMapping("speed", "/speed/value", "/speed/observed"))
        observed = NOW - 31_000
        payload = HelmProjector(config, "session-1", now=lambda: NOW).project({
            "published": observed,
            "vesselTime": "00:00:00",
            "speed": {"value": 2.0, "observed": observed},
        }).payload
        self.assertEqual(payload["status"], "LOST")
        self.assertEqual(payload["timestampEpochMs"], observed)
        self.assertIsNone(payload["values"]["speed"])
        self.assertEqual(payload["fieldQuality"]["speed"], {"status": "LOST", "timestampEpochMs": None})

    def test_pointer_decoder_rejects_bad_escapes_and_never_guesses_arrays(self) -> None:
        self.assertEqual(json_pointer({"a/b": {"~x": 3}}, "/a~1b/~0x"), 3)
        with self.assertRaises(HelmError):
            json_pointer({}, "/bad~2escape")
        self.assertNotEqual(json_pointer([{"x": 1}], "/01/x"), 1)

    def test_adapter_exposes_payload_not_internal_projection_wrapper(self) -> None:
        config = signal_k_config(HelmFieldMapping("speed", "/speed/value", "/speed/observed"))
        adapter = HelmAdapter(GatewayConfig(helm=config), "session-1")

        class FixtureClient:
            async def fetch(self, unused_session: object) -> dict[str, object]:
                del unused_session
                return {
                    "published": NOW,
                    "vesselTime": "00:00:00",
                    "speed": {"value": 2.0, "observed": NOW},
                }

        adapter._client = FixtureClient()  # type: ignore[assignment]
        adapter._projector = HelmProjector(config, "session-1", now=lambda: NOW)  # type: ignore[assignment]
        asyncio.run(adapter.refresh(object()))
        assert adapter.snapshot is not None
        self.assertEqual(adapter.snapshot["kind"], "helm")

    def test_strict_snapshot_reader_requests_identity_encoding(self) -> None:
        class Content:
            async def read(self, unused_limit: int) -> bytes:
                return b'{"published":1800000000000}'

        class Response:
            status = 200
            headers = {"Content-Type": "application/json", "Content-Encoding": "identity"}
            content = Content()

            async def __aenter__(self) -> "Response":
                return self

            async def __aexit__(self, *unused: object) -> None:
                return None

        class Session:
            headers: dict[str, str] | None = None

            def get(self, unused_url: str, *, allow_redirects: bool, headers: dict[str, str]) -> Response:
                self.headers = headers
                self.assertFalse(allow_redirects)  # type: ignore[attr-defined]
                return Response()

        session = Session()
        # Bind unittest's assertion on the small fake rather than exposing any
        # HTTP implementation details to the production reader.
        session.assertFalse = self.assertFalse  # type: ignore[attr-defined]
        config = signal_k_config(HelmFieldMapping("speed", "/speed/value", "/speed/observed"))
        document = asyncio.run(SignalKSnapshotClient(config).fetch(session))
        self.assertEqual(document, {"published": 1_800_000_000_000})
        self.assertEqual(session.headers, {
            "Accept": "application/json", "Accept-Encoding": "identity", "Cache-Control": "no-cache, no-store",
        })
