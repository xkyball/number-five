from contextlib import redirect_stdout, redirect_stderr
import io
import json
import unittest
from unittest.mock import patch

from no5_pi_gateway.__main__ import main
from no5_pi_gateway.configuration import (
    AudioConfig, AudioFaderLimit, CamillaDspConfig, GatewayConfig, SpotifyConnectConfig,
)


class MainAuthorityPreflightTest(unittest.TestCase):
    def config(self, maximum=0.0):
        return GatewayConfig(audio=AudioConfig(
            enabled=True, media_provider="spotify_connect",
            camilla=CamillaDspConfig(enabled=True, websocket_url="ws://127.0.0.1:1234",
                                    channel_faders=(("MASTER", 0), ("SUB", 1), ("REAR", 2), ("AUX3", 3), ("AUX4", 4))),
            spotify_connect=SpotifyConnectConfig(enabled=True, state_file="/private/state.json",
                                                control_socket="/private/control.sock"),
            fader_limits=(("MASTER", AudioFaderLimit(-60.0, maximum, 1.0)),),
        ))

    def test_authority_preflight_exposes_only_the_validated_public_main_range(self):
        output = io.StringIO()
        with patch("sys.argv", ["gateway", "--config", "unused", "--check-config", "--camilla-main-authority"]), \
                patch("no5_pi_gateway.__main__.load_config", return_value=self.config()), redirect_stdout(output):
            main()
        lines = output.getvalue().splitlines()
        self.assertEqual(lines[0], "configuration valid")
        self.assertEqual(json.loads(lines[1].removeprefix("camilla-main-authority ")),
                         {"minDb": -60.0, "maxDb": 0.0, "stepDb": 1.0})
        self.assertNotIn("/private", output.getvalue())

    def test_unsafe_main_range_is_rejected_before_any_preflight_success(self):
        output = io.StringIO()
        with patch("sys.argv", ["gateway", "--config", "unused", "--check-config", "--camilla-main-authority"]), \
                patch("no5_pi_gateway.__main__.load_config", return_value=self.config(1.0)), \
                redirect_stdout(output), redirect_stderr(io.StringIO()), self.assertRaises(SystemExit) as raised:
            main()
        self.assertEqual(raised.exception.code, 2)
        self.assertEqual(output.getvalue(), "")

    def test_existing_general_preflight_output_is_preserved(self):
        output = io.StringIO()
        with patch("sys.argv", ["gateway", "--config", "unused", "--check-config"]), \
                patch("no5_pi_gateway.__main__.load_config", return_value=GatewayConfig()), redirect_stdout(output):
            main()
        self.assertEqual(output.getvalue(), "configuration valid\n")
