"""An opt-in compatibility check against the separately owned HELM checkout.

The test never writes to that checkout.  CI can point ``NO5_HELM_CLIENT_ROOT``
at a pinned HELM source export; the developer workspace runs it against the
current application contracts.
"""

from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
import tempfile
import time
import unittest

from no5_pi_gateway.audio import CamillaDspAdapter, CamillaFader, CamillaSnapshot, RaspotifyMediaSnapshot
from no5_pi_gateway.configuration import AudioConfig, CamillaDspConfig, GatewayConfig, HelmFieldMapping, RaspotifyConfig, SignalKConfig
from no5_pi_gateway.helm import HelmProjector


HELM_SCHEMA_SHA256 = "02932f458f14e47d2ac635e7f5a5918e8f87feded532493bdb0a235c16cf5d58"
HELM_FIXTURE_SHA256 = "a9462450fb168950c39fb6330c9f6052653abe4d839348b36407d0095ca4d914"


class AndroidClientContractTest(unittest.TestCase):
    @property
    def client_root(self) -> Path:
        supplied = os.environ.get("NO5_HELM_CLIENT_ROOT")
        if not supplied:
            self.skipTest("set NO5_HELM_CLIENT_ROOT to run the separately-owned HELM client contract check")
        root = Path(supplied)
        if not root.is_dir():
            self.skipTest("NO5_HELM_CLIENT_ROOT does not point at a HELM client checkout")
        return root

    def _contract_files(self) -> tuple[Path, Path]:
        root = self.client_root
        return (root / "contracts/no5-gateway-v1.schema.json",
                root / "contracts/fixtures/real-helm-v1.json")

    def test_current_helm_client_uses_polling_v1_without_auth_or_revision_stream(self) -> None:
        root = self.client_root
        sources = (root / "app/src/main/java/com/no5/helm/network/PiNetworkSources.kt").read_text()
        transport = (root / "app/src/main/java/com/no5/helm/network/PiHttpTransport.kt").read_text()
        self.assertIn('path = "/v1/helm"', sources)
        self.assertNotIn("/no5/helm/v1/snapshot", sources)
        self.assertNotIn("helm/v1/snapshot", sources)
        self.assertNotIn("WebSocket", sources)
        self.assertIn('path == "/v1/helm"', transport)
        self.assertNotIn("Authorization", transport)
        # The client orders poll snapshots by the publisher session and source
        # timestamp; HELM V1 has no separate revision field or revision stream.
        self.assertIn("sourceSessionId", sources)
        self.assertIn("acceptedTimestamp", sources)
        schema_path, fixture_path = self._contract_files()
        schema_bytes = schema_path.read_bytes()
        fixture_bytes = fixture_path.read_bytes()
        self.assertEqual(hashlib.sha256(schema_bytes).hexdigest(), HELM_SCHEMA_SHA256)
        self.assertEqual(hashlib.sha256(fixture_bytes).hexdigest(), HELM_FIXTURE_SHA256)
        fixture = json.loads(fixture_bytes)
        self.assertNotIn("revision", fixture)

    def test_real_client_fixture_and_gateway_projection_validate_against_same_schema(self) -> None:
        try:
            from jsonschema import Draft202012Validator
        except ImportError:
            self.skipTest("install the test extra to validate the Android JSON schema")
        schema_path, fixture_path = self._contract_files()
        schema = json.loads(schema_path.read_bytes())
        fixture = json.loads(fixture_path.read_bytes())
        validator = Draft202012Validator(schema)
        validator.validate(fixture)

        now = 1_800_000_000_000
        config = SignalKConfig(
            enabled=True, snapshot_url="http://127.0.0.1:3000/recorded-snapshot", source_name="signalk-main",
            published_at_pointer="/published", vessel_time_pointer="/vesselTime",
            fields=(HelmFieldMapping("speed", "/speed/value", "/speed/observed"),),
        )
        payload = HelmProjector(config, "gateway-session", now=lambda: now).project({
            "published": now,
            "vesselTime": "00:00:00",
            "speed": {"value": 2.0, "observed": now},
        }).payload
        validator.validate(payload)
        self.assertEqual(payload["kind"], "helm")
        self.assertEqual(payload["serverSessionId"], "gateway-session")
        self.assertNotIn("revision", payload)

    def test_audio_fixture_and_display_only_gateway_state_validate_against_current_audio_schema(self) -> None:
        try:
            from jsonschema import Draft202012Validator
        except ImportError:
            self.skipTest("install the test extra to validate the Android JSON schema")
        root = self.client_root
        schema = json.loads((root / "contracts/no5-audio-v1.schema.json").read_bytes())
        fixture = json.loads((root / "contracts/fixtures/real-audio-v1.json").read_bytes())
        validator = Draft202012Validator(schema)
        validator.validate(fixture)
        with tempfile.TemporaryDirectory() as directory:
            audio = AudioConfig(
                enabled=True,
                media_provider="raspotify_onevent_file",
                camilla=CamillaDspConfig(
                    enabled=True, websocket_url="ws://127.0.0.1:1234",
                    channel_faders=(("MASTER", 0), ("SUB", 1), ("REAR", 2), ("AUX3", 3), ("AUX4", 4)),
                ),
                raspotify=RaspotifyConfig(enabled=True, state_file=str(Path(directory) / "media.json")),
            )
            adapter = CamillaDspAdapter(GatewayConfig(audio=audio), "gateway-session")
            now = time.time_ns() // 1_000_000
            adapter._latest = CamillaSnapshot(  # type: ignore[assignment]
                version="4.1.3", state="RUNNING", raw_state="Running",
                faders={name: CamillaFader(-12.0, False) for name in ("MASTER", "SUB", "REAR", "AUX3", "AUX4")},
                config_title="No 5 - Stereo 4.1", clipped_samples=0, observed_at_epoch_ms=now,
            )
            adapter._media = RaspotifyMediaSnapshot(  # type: ignore[assignment]
                connected=True, active=True, state="PLAYING", track_id="spotify:track:1", title="Wake",
                artists=("Artist A",), album="Sea", duration_ms=1000, position_ms=12,
                shuffle=False, repeat=False, observed_at_epoch_ms=now,
            )
            payload = adapter.payload
            assert payload is not None
            validator.validate(payload)
            self.assertEqual(payload["authority"], "CAMILLA_DSP_MASTER")
            self.assertFalse(payload["capabilities"]["playPause"])  # type: ignore[index]
