"""Safety checks for the physical-Pi proxy migration; no live Pi access."""
import sys
from pathlib import Path
import tomllib
import unittest

TOOLS = Path(__file__).resolve().parents[1] / 'tools'
sys.path.insert(0, str(TOOLS))
from migrate_pi_browser_proxy import browser_assets, environment, set_table
from refresh_pi_browser_network import listener_config, selected_addresses


class PiBrowserProxyTests(unittest.TestCase):
    def test_toml_changes_preserve_audio_presets_and_simulation_state_path(self):
        source = '''# Operator's existing physical profile
[gateway]
bind_host = "127.0.0.1"
port = 8789
[simulation]
enabled = false # keep other settings
state_file = "/var/lib/no5-pi-gateway/existing-selection.json"
sonar_m0_upstream_base_url = "http://127.0.0.1:8788"
[audio.camilla]
preset_files = ["original.yml", "preset_no5_front_stereo_fullrange.yml"]
[audio.camilla.channel_faders]
MASTER = 0
AUX3 = 1
'''
        expected = tomllib.loads(source)
        expected['simulation']['enabled'] = True
        updated = set_table(source, 'simulation', {'enabled': True})
        self.assertEqual(tomllib.loads(updated), expected)
        updated = set_table(updated, 'camilla_gui', {'enabled': True, 'public_origin': 'https://lysmarine.local'})
        self.assertEqual(tomllib.loads(updated)['audio'], expected['audio'])
        self.assertIn('# Operator', updated)

    def test_toml_section_without_trailing_newline(self):
        updated = set_table('[simulation]\nenabled = false', 'simulation', {'enabled': True, 'state_file': '/state'})
        self.assertEqual(tomllib.loads(updated)['simulation'], {'enabled': True, 'state_file': '/state'})

    def test_only_selected_live_nic_addresses_are_exposed(self):
        entries = [
            {'address': 'aa:bb', 'flags': ['UP'], 'addr_info': [
                {'local': '192.168.4.5', 'scope': 'global'},
                {'local': 'fd00::10', 'scope': 'global'},
                {'local': 'fe80::10', 'scope': 'link'},
                {'local': 'fd00::11', 'scope': 'global', 'flags': ['tentative']} ]},
            {'address': 'cc:dd', 'flags': ['UP'], 'addr_info': [{'local': '10.0.2.15', 'scope': 'global'}]},
            {'address': 'ee:ff', 'flags': [], 'addr_info': [{'local': '192.168.9.9', 'scope': 'global'}]},
        ]
        self.assertEqual(selected_addresses(entries, ['aa:bb', 'ee:ff']), ['192.168.4.5', 'fd00::10'])
        text = listener_config(selected_addresses(entries, ['aa:bb']))
        self.assertIn('listen [fd00::10]:443 ssl;', text)
        self.assertNotIn('10.0.2.15', text)
        self.assertNotIn('0.0.0.0', text)
        self.assertEqual(listener_config([]).count('listen'), 1)

    def test_public_browser_assets_never_embed_backend_bearer(self):
        source = (Path(__file__).resolve().parents[1] / 'src/no5_pi_gateway/simulation_ui.html').read_text()
        assets = browser_assets(source)
        self.assertIn('Gateway-Key', assets['index.html'])
        self.assertNotIn('<script>', assets['index.html'])
        self.assertIn("headers.set('X-No5-Gateway-Key',token)", assets['app.js'])
        self.assertNotIn('/admin/', assets['app.js'])
        self.assertFalse(any(word in assets['app.js'] for word in ('localStorage', 'sessionStorage', 'Bearer ')))

    def test_ambiguous_environment_is_rejected(self):
        self.assertEqual(environment('# comment\nKEY=original\n'), {'KEY': 'original'})
        with self.assertRaises(AssertionError):
            environment('KEY=one\nKEY=two\n')


if __name__ == '__main__':
    unittest.main()
