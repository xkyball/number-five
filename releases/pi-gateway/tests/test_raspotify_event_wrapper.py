from __future__ import annotations

import os
from pathlib import Path
import subprocess
import tempfile
import unittest


WRAPPER = Path(__file__).resolve().parents[1] / "deploy" / "libexec" / "no5-raspotify-event"
BRIDGE = "/opt/no5-pi-gateway/current/.venv/bin/no5-raspotify-event-bridge"


class RaspotifyEventWrapperTest(unittest.TestCase):
    def run_wrapper(self, *, player_event: str | None, arguments: list[str] | None = None) -> subprocess.CompletedProcess[str]:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            recorder = root / "bridge"
            output = root / "arguments"
            recorder.write_text("#!/bin/sh\nprintf '%s\\n' \"$@\" > \"$NO5_TEST_OUTPUT\"\n", encoding="utf-8")
            recorder.chmod(0o755)
            wrapper = root / "wrapper"
            wrapper.write_text(WRAPPER.read_text(encoding="utf-8").replace(BRIDGE, str(recorder)), encoding="utf-8")
            wrapper.chmod(0o755)
            environment = os.environ | {"NO5_TEST_OUTPUT": str(output)}
            if player_event is None:
                environment.pop("PLAYER_EVENT", None)
            else:
                environment["PLAYER_EVENT"] = player_event
            result = subprocess.run(
                [str(wrapper), *(arguments or [])], env=environment, text=True,
                stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=False,
            )
            result.recorded_arguments = output.read_text(encoding="utf-8").splitlines() if output.exists() else []  # type: ignore[attr-defined]
            return result

    def test_uses_librespot_player_event_environment_variable(self) -> None:
        result = self.run_wrapper(player_event="playing")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.recorded_arguments, [  # type: ignore[attr-defined]
            "--state-file", "/var/lib/no5-media/raspotify-event.json", "playing",
        ])

    def test_fails_closed_without_event_or_with_positional_input(self) -> None:
        absent = self.run_wrapper(player_event=None)
        positional = self.run_wrapper(player_event="playing", arguments=["untrusted"])
        self.assertEqual(absent.returncode, 64)
        self.assertEqual(positional.returncode, 64)
        self.assertEqual(absent.recorded_arguments, [])  # type: ignore[attr-defined]
        self.assertEqual(positional.recorded_arguments, [])  # type: ignore[attr-defined]

