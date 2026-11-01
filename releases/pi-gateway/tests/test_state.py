from __future__ import annotations

import json
import struct
import unittest

from no5_pi_gateway.configuration import Calibration, GatewayConfig
from no5_pi_gateway.protocol import UPSTREAM_HEADER, parse_echogram_frame
from no5_pi_gateway.state import GatewayState


def frame(ping_id: int, observed_ms: int) -> object:
    samples = struct.pack("<" + "H" * 1024, *([258] * 1024))
    raw = UPSTREAM_HEADER.pack(1, 1, 40, ping_id, observed_ms * 1_000_000,
                               30.0, float("nan"), 0.0, 0, 1024, 0) + samples
    return parse_echogram_frame(raw)


class GatewayStateTest(unittest.TestCase):
    def setUp(self) -> None:
        self.clock = 1_700_000_000_000

    def now(self) -> int:
        return self.clock

    def configured(self, producer: str) -> GatewayConfig:
        return GatewayConfig(
            producer=producer,
            upstream_base_url="http://127.0.0.1:8787",
            source_name=f"no5-sonard-{producer}",
            calibration=Calibration(),
        )

    def test_m0_is_always_simulated_and_calibration_is_invalid(self) -> None:
        state = GatewayState(self.configured("m0"), session_id="gateway-test", now=self.now)
        state.begin_connection()
        state.observe_status({"source": "synthetic", "state": "SIMULATING", "rates": {"ping_hz": 8}})
        binary = state.accept_ping(frame(4, self.clock))
        snapshot = state.snapshot()
        self.assertEqual(snapshot["sourceKind"], "SIMULATED")
        self.assertTrue(snapshot["simulated"])
        self.assertFalse(snapshot["calibration"]["valid"])
        self.assertEqual(snapshot["status"], "LIVE")
        self.assertIsNotNone(binary)
        metadata_length = int.from_bytes(binary[:4], "little")
        metadata = json.loads(binary[4:4 + metadata_length])
        self.assertEqual(metadata["sourceKind"], "SIMULATED")
        self.assertEqual(binary[-2048:], struct.pack("<" + "H" * 1024, *([258] * 1024)))

    def test_m1_raw_transport_does_not_make_up_calibration(self) -> None:
        state = GatewayState(self.configured("m1"), session_id="gateway-test", now=self.now)
        state.begin_connection()
        state.observe_status({"source": "pico", "state": "RUNNING", "rates": {"ping_hz": 10}})
        state.accept_ping(frame(5, self.clock))
        snapshot = state.snapshot()
        self.assertEqual(snapshot["sourceKind"], "REAL")
        self.assertFalse(snapshot["simulated"])
        self.assertIsNone(snapshot["calibration"]["samplePeriodUs"])
        self.assertFalse(snapshot["calibration"]["valid"])

    def test_source_clock_regression_starts_explicit_local_generation(self) -> None:
        state = GatewayState(self.configured("m0"), session_id="gateway-test", now=self.now)
        state.begin_connection()
        state.observe_status({"source": "synthetic", "state": "SIMULATING"})
        state.accept_ping(frame(10, self.clock))
        first_generation = state.generation
        self.clock += 10
        state.accept_ping(frame(1, self.clock - 20))
        self.assertEqual(state.generation, first_generation + 1)

    def test_commands_are_revision_checked_readback_rejections_without_writes(self) -> None:
        state = GatewayState(self.configured("m1"), session_id="gateway-test", now=self.now)
        state.begin_connection()
        request = {
            "version": 1,
            "serverSessionId": "gateway-test",
            "generation": state.generation,
            "expectedRevision": state.revision,
            "clientId": "android-session",
            "requestId": 1,
            "command": "start",
        }
        ack = state.reject_command(request).as_contract()
        self.assertEqual(ack["outcome"], "REJECTED")
        self.assertEqual(ack["reason"], "UPSTREAM_COMMAND_UNAVAILABLE")
        self.assertEqual(ack["state"]["capabilities"], {"canStart": False, "canStop": False, "bottomDetection": True,
                                                               "filterControl": False, "confirmedTracks": False,
                                                               "sourceSelection": False, "diagnostics": False})
        replay = state.reject_command(request).as_contract()
        self.assertEqual(replay["reason"], "STALE_REQUEST")

