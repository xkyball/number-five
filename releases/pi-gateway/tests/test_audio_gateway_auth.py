"""AUDIO must require the existing gateway header on every backend route."""

from __future__ import annotations

import hashlib
import os
import time
import unittest
from unittest.mock import patch

try:
    from aiohttp.test_utils import TestClient, TestServer
except ModuleNotFoundError:  # Minimal contract test environments omit runtime deps.
    TestClient = TestServer = None  # type: ignore[assignment,misc]

from no5_pi_gateway.audio import CamillaFader, CamillaSnapshot
from no5_pi_gateway.configuration import AudioConfig, AudioFaderLimit, CamillaDspConfig, GatewayConfig
from no5_pi_gateway.service import GatewayService, create_app


FADERS = (("MASTER", 0), ("SUB", 1), ("REAR", 2), ("AUX3", 3), ("AUX4", 4))
HEADER = "X-No5-Gateway-Key"
KEY = "test-header-key-with-sufficient-length"


class AudioGatewayHeaderVerifierTest(unittest.TestCase):
    def test_header_verifier_rejects_missing_invalid_duplicate_and_accepts_existing_key(self) -> None:
        with patch.dict(os.environ, {
            "NO5_TEST_AUDIO_GATEWAY_HEADER_SHA256": hashlib.sha256(KEY.encode("ascii")).hexdigest(),
        }, clear=False):
            service = GatewayService(GatewayConfig(
                audio_gateway_key_hash_env="NO5_TEST_AUDIO_GATEWAY_HEADER_SHA256",
            ))
            self.assertFalse(service.authorizes_audio_gateway_header([]))
            self.assertFalse(service.authorizes_audio_gateway_header(["wrong"]))
            self.assertFalse(service.authorizes_audio_gateway_header([KEY, KEY]))
            self.assertFalse(service.authorizes_audio_gateway_header(["non-ascii-ä"]))
            # No Bearer token enters this verifier: the existing gateway key
            # is the only AUDIO authorization proof.
            self.assertTrue(service.authorizes_audio_gateway_header([KEY]))


@unittest.skipUnless(TestClient is not None, "aiohttp runtime dependency is not installed")
class AudioGatewayHeaderAuthenticationTest(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self) -> None:
        self.environment = patch.dict(
            os.environ,
            {"NO5_TEST_AUDIO_GATEWAY_HEADER_SHA256": hashlib.sha256(KEY.encode("ascii")).hexdigest()},
            clear=False,
        )
        self.environment.start()
        audio = AudioConfig(
            enabled=True,
            # This is only an HTTP authentication test. Absence of a reviewed
            # hardware declaration leaves Camilla writes unavailable.
            hardware_available=None,
            fader_limits=tuple((channel, AudioFaderLimit(-60.0, 0.0, 1.0)) for channel, _index in FADERS),
            camilla=CamillaDspConfig(
                enabled=True,
                websocket_url="ws://127.0.0.1:1234",
                channel_faders=FADERS,
            ),
        )
        app = await create_app(GatewayConfig(
            audio=audio,
            audio_gateway_key_hash_env="NO5_TEST_AUDIO_GATEWAY_HEADER_SHA256",
        ))
        service = app["gateway_service"]
        # Do not contact a Camilla socket while testing HTTP authentication.
        service.audio._stop.set()  # type: ignore[attr-defined]
        now = time.time_ns() // 1_000_000
        service.audio._latest = CamillaSnapshot(  # type: ignore[attr-defined]
            version="4.1.3", state="RUNNING", raw_state="Running",
            faders={channel: CamillaFader(-12.0, False) for channel, _index in FADERS},
            config_title="No 5 - Test", clipped_samples=0, observed_at_epoch_ms=now,
        )
        self.client = TestClient(TestServer(app))
        await self.client.start_server()

    async def asyncTearDown(self) -> None:
        await self.client.close()
        self.environment.stop()

    async def test_audio_get_rejects_missing_invalid_and_duplicate_gateway_headers(self) -> None:
        missing = await self.client.get("/v1/audio")
        self.assertEqual(missing.status, 401)

        invalid = await self.client.get("/v1/audio", headers={HEADER: "wrong"})
        self.assertEqual(invalid.status, 401)

        duplicate = await self.client.get("/v1/audio", headers=[(HEADER, KEY), (HEADER, KEY)])
        self.assertEqual(duplicate.status, 401)

        accepted = await self.client.get("/v1/audio", headers={HEADER: KEY})
        self.assertEqual(accepted.status, 200)
        payload = await accepted.json()
        self.assertEqual(payload["dsp"]["faderControls"][0]["limit"], {
            "minDb": -60.0, "maxDb": 0.0, "stepDb": 1.0,
        })

    async def test_audio_post_needs_no_bearer_after_valid_gateway_header(self) -> None:
        snapshot = await self.client.get("/v1/audio", headers={HEADER: KEY})
        self.assertEqual(snapshot.status, 200)
        state = await snapshot.json()
        command = {
            "version": 1,
            "kind": "audio-command",
            "sessionId": "gateway_auth_test",
            "requestId": "gateway_auth_test:1",
            "serverSessionId": state["serverSessionId"],
            "expectedRevision": state["revision"],
            # Writes remain disabled in this test. A verified Camilla write is
            # neither needed nor permitted to prove HTTP authentication.
            "command": {"type": "fader", "channel": "MASTER", "valueDb": -12.0},
        }
        rejected_without_key = await self.client.post("/v1/audio/command", json=command)
        self.assertEqual(rejected_without_key.status, 401)

        accepted_without_bearer = await self.client.post(
            "/v1/audio/command", headers={HEADER: KEY}, json=command,
        )
        self.assertEqual(accepted_without_bearer.status, 200)
        acknowledgement = await accepted_without_bearer.json()
        self.assertEqual(acknowledgement["status"], "REJECTED")
        self.assertIn("write capability", acknowledgement["message"])

    async def test_invalid_bearer_never_falls_back_to_a_valid_legacy_key(self) -> None:
        for token in ["Bearer N5T2.invalid.signature", "Bearer unknown", "invalid"]:
            response = await self.client.get("/v1/audio", headers={HEADER: KEY, "Authorization": token})
            self.assertEqual(response.status, 401)
        response = await self.client.get("/v1/audio", headers=[(HEADER, KEY),
            ("Authorization", "Bearer first"), ("Authorization", "Bearer second")])
        self.assertEqual(response.status, 401)
