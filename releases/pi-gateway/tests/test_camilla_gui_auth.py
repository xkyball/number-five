"""Browser-session tests for the loopback-only CamillaGUI boundary."""

from __future__ import annotations

import hashlib
import os
import unittest
from unittest.mock import patch

try:
    from aiohttp.test_utils import TestClient, TestServer
except ModuleNotFoundError:  # Minimal contract test environments omit runtime deps.
    TestClient = TestServer = None  # type: ignore[assignment,misc]

from no5_pi_gateway.camilla_gui_auth import CamillaGuiSessionAuth, verifies_gateway_key
from no5_pi_gateway.configuration import CamillaGuiAuthConfig, ConfigurationError, GatewayConfig
from no5_pi_gateway.service import create_app


KEY = "test-header-key-with-sufficient-length"
KEY_HASH_ENV = "NO5_TEST_CAMILLAGUI_GATEWAY_HEADER_SHA256"
SESSION_ENV = "NO5_TEST_CAMILLAGUI_SESSION_SECRET"
SESSION_SECRET = "0123456789abcdef" * 4
ORIGIN = "https://lysmarine.local"


class CamillaGuiSessionTokenTest(unittest.TestCase):
    def test_short_lived_token_is_bound_to_gateway_session_and_expiry(self) -> None:
        now = [1_000]
        first = CamillaGuiSessionAuth(SESSION_SECRET, "server-one", 900, clock=lambda: now[0])
        token = first.issue()
        self.assertTrue(first.verify(token))
        self.assertFalse(CamillaGuiSessionAuth(SESSION_SECRET, "server-two", 900, clock=lambda: now[0]).verify(token))
        now[0] = 1_900
        self.assertFalse(first.verify(token))

    def test_existing_gateway_key_is_never_retained_in_the_session_token(self) -> None:
        expected = hashlib.sha256(KEY.encode("ascii")).hexdigest()
        self.assertTrue(verifies_gateway_key(KEY, expected))
        self.assertFalse(verifies_gateway_key("wrong", expected))
        self.assertFalse(verifies_gateway_key(KEY + "\n", expected))
        token = CamillaGuiSessionAuth(SESSION_SECRET, "server", 900, clock=lambda: 1_000).issue()
        self.assertNotIn(KEY, token)

    def test_configuration_rejects_non_https_or_longer_sessions(self) -> None:
        with self.assertRaises(ConfigurationError):
            CamillaGuiAuthConfig(enabled=True, public_origin="http://lysmarine.local")
        with self.assertRaises(ConfigurationError):
            CamillaGuiAuthConfig(enabled=True, session_max_age_s=901)


@unittest.skipUnless(TestClient is not None, "aiohttp runtime dependency is not installed")
class CamillaGuiSessionEndpointTest(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self) -> None:
        self.environment = patch.dict(os.environ, {
            KEY_HASH_ENV: hashlib.sha256(KEY.encode("ascii")).hexdigest(),
            SESSION_ENV: SESSION_SECRET,
        }, clear=False)
        self.environment.start()
        config = GatewayConfig(
            audio_gateway_key_hash_env=KEY_HASH_ENV,
            camilla_gui=CamillaGuiAuthConfig(
                enabled=True, public_origin=ORIGIN, session_secret_env=SESSION_ENV,
            ),
        )
        app = await create_app(config)
        self.client = TestClient(TestServer(app))
        await self.client.start_server()

    async def asyncTearDown(self) -> None:
        await self.client.close()
        self.environment.stop()

    async def login(self) -> str:
        response = await self.client.post(
            "/internal/camillagui/login", headers={"Origin": ORIGIN}, json={"gatewayKey": KEY},
        )
        self.assertEqual(response.status, 200)
        self.assertEqual(response.headers.get("Cache-Control"), "no-store")
        cookie = response.cookies["no5_camillagui"]
        self.assertTrue(cookie["secure"])
        self.assertTrue(cookie["httponly"])
        self.assertEqual(cookie["samesite"], "Strict")
        self.assertEqual(cookie["path"], "/camilladsp/")
        return cookie.value

    async def test_happy_path_denial_logout_and_csrf_origin_are_enforced(self) -> None:
        wrong_origin = await self.client.post(
            "/internal/camillagui/login", headers={"Origin": "https://attacker.invalid"},
            json={"gatewayKey": KEY},
        )
        self.assertEqual(wrong_origin.status, 403)

        wrong_key = await self.client.post(
            "/internal/camillagui/login", headers={"Origin": ORIGIN}, json={"gatewayKey": "wrong"},
        )
        self.assertEqual(wrong_key.status, 401)

        session = await self.login()
        authenticated = await self.client.get(
            "/internal/camillagui/auth", headers={"Cookie": f"no5_camillagui={session}"},
        )
        self.assertEqual(authenticated.status, 204)

        forbidden_logout = await self.client.post(
            "/internal/camillagui/logout", headers={
                "Origin": "https://attacker.invalid", "Cookie": f"no5_camillagui={session}",
            },
        )
        self.assertEqual(forbidden_logout.status, 403)

        logout = await self.client.post(
            "/internal/camillagui/logout", headers={
                "Origin": ORIGIN, "Cookie": f"no5_camillagui={session}",
            },
        )
        self.assertEqual(logout.status, 204)
        self.assertEqual(logout.cookies["no5_camillagui"]["max-age"], "0")

        no_session = await self.client.get("/internal/camillagui/auth")
        self.assertEqual(no_session.status, 401)

    async def test_login_requires_only_one_bounded_gateway_key_field(self) -> None:
        missing = await self.client.post(
            "/internal/camillagui/login", headers={"Origin": ORIGIN}, json={},
        )
        self.assertEqual(missing.status, 401)
        extra = await self.client.post(
            "/internal/camillagui/login", headers={"Origin": ORIGIN},
            json={"gatewayKey": KEY, "other": "no"},
        )
        self.assertEqual(extra.status, 401)
        not_json = await self.client.post(
            "/internal/camillagui/login", headers={"Origin": ORIGIN, "Content-Type": "text/plain"}, data="x",
        )
        self.assertEqual(not_json.status, 415)
