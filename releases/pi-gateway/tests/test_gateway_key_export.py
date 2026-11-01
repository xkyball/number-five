from __future__ import annotations

from pathlib import Path
import contextlib
import io
import hashlib
import subprocess
import sys
import tempfile
import unittest

from no5_pi_gateway.gateway_header_hash import sync


TOOL = Path(__file__).resolve().parents[1] / "tools" / "extract_gateway_header_key_from_nginx_map.py"


def generated_map(key_line: bytes) -> bytes:
    return b"\n".join((
        b"# dummy fixture only",
        b"map $http_x_no5_gateway_key $no5_gateway_authorized {",
        b"    default 0;",
        b"    \"" + key_line + b"\" 1;",
        b"}",
        b"",
    ))


class GatewayKeyExportTest(unittest.TestCase):
    def run_tool(self, document: bytes) -> subprocess.CompletedProcess[bytes]:
        return subprocess.run(
            [sys.executable, str(TOOL), "--stdin-auth-map"], input=document,
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=False,
        )

    def test_exact_map_emits_only_raw_key_without_newline(self) -> None:
        dummy = b"0123456789abcdef0123456789abcdef"
        result = self.run_tool(generated_map(dummy))
        self.assertEqual(result.returncode, 0, result.stderr.decode("utf-8", "replace"))
        self.assertEqual(result.stdout, dummy)
        self.assertNotIn(b"\n", result.stdout)

    def test_nginx_quote_and_backslash_escapes_are_decoded_without_leaking_to_stderr(self) -> None:
        encoded_dummy = b'abc\\"def\\\\0123456789abcdef'
        raw_dummy = b'abc"def\\0123456789abcdef'
        result = self.run_tool(generated_map(encoded_dummy))
        self.assertEqual(result.returncode, 0, result.stderr.decode("utf-8", "replace"))
        self.assertEqual(result.stdout, raw_dummy)

    def test_wrong_map_variable_is_rejected_without_echoing_fixture_key(self) -> None:
        dummy = b"do-not-echo-this-dummy-key-0123456789"
        document = generated_map(dummy).replace(b"$http_x_no5_gateway_key", b"$http_x_other_key")
        result = self.run_tool(document)
        self.assertNotEqual(result.returncode, 0)
        self.assertNotIn(dummy, result.stderr)
        self.assertEqual(result.stdout, b"")

    def test_multiple_key_lines_are_rejected_as_ambiguous(self) -> None:
        dummy = b"0123456789abcdef0123456789abcdef"
        document = generated_map(dummy).replace(b"}\n", b'    "abcdef0123456789abcdef0123456789" 1;\n}\n')
        result = self.run_tool(document)
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(result.stdout, b"")

    def test_unapproved_escape_and_oversized_input_are_rejected(self) -> None:
        bad_escape = generated_map(b"0123456789abc\\ndef0123456789")
        self.assertNotEqual(self.run_tool(bad_escape).returncode, 0)
        self.assertNotEqual(self.run_tool(b"#" * 16_385).returncode, 0)


class GatewayHeaderHashSyncTest(unittest.TestCase):
    def test_sync_keeps_existing_environment_and_writes_only_a_verifier(self) -> None:
        dummy = b"0123456789abcdef0123456789abcdef"
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            auth_map = root / "auth.conf"
            environment = root / "environment"
            auth_map.write_bytes(generated_map(dummy))
            environment.write_bytes(b"NO5_GATEWAY_SIMULATION_TOKEN=retained-value\n")
            sync(auth_map, environment)
            result = environment.read_bytes()
            self.assertIn(b"NO5_GATEWAY_SIMULATION_TOKEN=retained-value\n", result)
            self.assertIn(
                b"NO5_GATEWAY_HEADER_KEY_SHA256=" + hashlib.sha256(dummy).hexdigest().encode("ascii") + b"\n",
                result,
            )
            self.assertNotIn(dummy, result)
            self.assertEqual(result.count(b"NO5_GATEWAY_HEADER_KEY_SHA256="), 1)
            self.assertEqual(environment.stat().st_mode & 0o777, 0o600)

    def test_bad_map_does_not_replace_an_existing_environment(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            auth_map = root / "auth.conf"
            environment = root / "environment"
            auth_map.write_bytes(b"not an auth map\n")
            original = b"NO5_GATEWAY_SIMULATION_TOKEN=retained-value\n"
            environment.write_bytes(original)
            with contextlib.redirect_stderr(io.StringIO()):
                with self.assertRaises(SystemExit):
                    sync(auth_map, environment)
            self.assertEqual(environment.read_bytes(), original)
