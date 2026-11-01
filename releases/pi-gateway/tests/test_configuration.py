from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from no5_pi_gateway.configuration import ConfigurationError, GatewayConfig, SimulationConfig, LuminaTokenConfig, load_config


class ConfigurationTest(unittest.TestCase):
    def test_disabled_source_is_honestly_unavailable(self) -> None:
        config = GatewayConfig()
        self.assertEqual(config.producer, "disabled")

    def test_unverified_m1_requires_a_real_local_url_but_not_calibration(self) -> None:
        config = GatewayConfig(producer="m1", upstream_base_url="http://127.0.0.1:8787")
        self.assertFalse(config.calibration.valid)
        self.assertIsNone(config.calibration.sample_period_us)

    def test_config_rejects_pathful_upstream(self) -> None:
        with self.assertRaises(ConfigurationError):
            GatewayConfig(producer="m0", upstream_base_url="http://127.0.0.1:8787/not-a-base")

    def test_example_config_is_loadable(self) -> None:
        project = Path(__file__).resolve().parents[1]
        config = load_config(project / "config" / "no5-pi-gateway.example.toml")
        self.assertEqual(config.producer, "disabled")
        self.assertIsNone(config.calibration.sample_period_us)

    def test_local_simulation_requires_an_absolute_state_file_only_when_enabled(self) -> None:
        self.assertFalse(SimulationConfig().enabled)
        with self.assertRaises(ConfigurationError):
            SimulationConfig(enabled=True, state_file="simulation-state.json")

    def test_enabled_lumina_tokens_require_reader_and_root_helper_paths(self) -> None:
        with self.assertRaises(ConfigurationError):
            LuminaTokenConfig(enabled=True, audience="https://gateway.example", registry_file="/var/lib/registry.json")
        config=LuminaTokenConfig(enabled=True, audience="https://gateway.example", registry_file="/var/lib/registry.json", registry_socket="/run/registry.sock")
        self.assertEqual(config.registry_socket, "/run/registry.sock")
