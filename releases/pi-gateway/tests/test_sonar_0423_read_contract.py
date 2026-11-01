"""Exact read approval for control/recovery-only host update."""
import base64
import unittest
from test_sonar_0420_read_contract import project_case, CASES
from no5_pi_gateway.sonar_contract import package_version, amplitude
from test_sonar_0417_read_contract import status_0417
class Sonar0423ReadContractTest(unittest.TestCase):
    def test_existing_real_context_keeps_bytes_geometry_marker_and_scale(self):
        for case in CASES:
            _, frame, wire, m, _ = project_case(case, '0.4.23')
            n = int.from_bytes(wire[:4], 'little')
            self.assertEqual(wire[4+n:], base64.b64decode(case['frame_base64'])[40:])
            self.assertEqual(m['hostPackageVersion'], '0.4.23')
            self.assertEqual(m['pingSource'], case['published_ping']['actual_source'])
            self.assertEqual(m['amplitude']['fullScaleCode'], 4095)
            self.assertFalse(m['amplitude']['rawSamplesModified'])
    def test_unreviewed_and_wrong_producer_remain_unconfirmed(self):
        for version in ['0.4.32','0.4.21+local','0.4.210']:
            s=status_0417(CASES[0],version)
            self.assertIsNone(package_version(s)); self.assertIsNone(amplitude(s))
        s=status_0417(CASES[0],'0.4.23');s['host_package']['name']='other'
        self.assertIsNone(package_version(s))
