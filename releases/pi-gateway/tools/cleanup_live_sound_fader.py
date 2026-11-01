#!/usr/bin/env python3
"""One guarded restore of the documented probe's last verified AUX fader.

Never a service change or a general reset. Requires the exact private failed
probe evidence, current complete fader vector and unchanged native source.
Preparation is inert; authorization is supplied by the coordinated owner.
"""
from __future__ import annotations
import argparse
import asyncio
import copy
import json
from pathlib import Path
import secrets
import time
import aiohttp
from no5_pi_gateway.audio import CamillaDspClient
from no5_pi_gateway.configuration import load_config
from no5_pi_gateway.sound import CamillaSoundWire
from probe_live_sound import require, private_json, helm_authorization, ProbeFailure


def expected_cleanup(baseline, report, limits):
    outcomes = report.get("faderCommands", [])
    require([item.get("status") for item in outcomes] == ["VERIFIED", "VERIFIED", "VERIFIED", "REJECTED"],
            "EXACT_FAILED_PROBE_TRAJECTORY_REQUIRED")
    require(report.get("testKeyRevoked") is True and report.get("existingRegistrationRowsPreserved") is True,
            "PREVIOUS_TEST_KEY_CLEANUP_REQUIRED")
    initial = baseline["audio"]["dsp"]["faders"]
    chosen = next((channel for channel in ("AUX3", "REAR", "SUB", "AUX4") if channel in limits and
                   initial[channel]["db"] - limits[channel].step_db >= limits[channel].min_db), None)
    require(chosen is not None, "KNOWN_OWN_FADER_REQUIRED")
    expected = copy.deepcopy(initial)
    expected[chosen]["db"] -= limits[chosen].step_db
    return chosen, expected


def guard_cleanup(baseline, observed_audio, observed_native, expected):
    checks = {
        "exactOwnFaderVector": observed_audio["dsp"]["faders"] == expected,
        "gatewaySourceUnchanged": observed_audio["serverSessionId"] == baseline["audio"]["serverSessionId"],
        "nativeSourceUnchanged": observed_native["sourceGeneration"] == baseline["native"]["sourceGeneration"],
        "nativeConfigurationUnchanged": observed_native["configGeneration"] == baseline["native"]["configGeneration"],
        "noPendingPreview": observed_native["previewLease"] is None and observed_native["preparedRevision"] == observed_native["appliedRevision"],
        "presetAlreadyRestored": observed_native["preset"] == baseline["native"]["preset"],
        "calibrationAlreadyRestored": observed_native["calibration"] == baseline["native"]["calibration"],
    }
    require(all(checks.values()), "AMBIGUOUS_CURRENT_STATE_NO_WRITE")
    return checks


async def execute(args):
    require(str(args.probe_dir).startswith("/var/lib/no5-rebuild-private/"), "PRIVATE_EVIDENCE_REQUIRED")
    baseline = json.loads((args.probe_dir / "baseline.json").read_text())
    previous = json.loads((args.probe_dir / "report.json").read_text())
    config = load_config(args.config)
    require(config.bind_host in {"127.0.0.1", "::1"}, "EXACT_LOOPBACK_REQUIRED")
    chosen, expected = expected_cleanup(baseline, previous, config.audio.limits)
    origin = "http://" + ("[::1]" if config.bind_host == "::1" else "127.0.0.1") + ":" + str(config.port)
    output = args.probe_dir / ("own-cleanup-" + secrets.token_hex(6)); output.mkdir(mode=0o700)
    result = {"schema": "no5.live-sound-own-fader-cleanup/1", "restored": False, "commandsSent": 0,
              "servicesChanged": False, "spotifyCommandsSent": 0}
    wire = CamillaSoundWire(CamillaDspClient(config.audio.camilla, persistent=True))
    try:
        async with aiohttp.ClientSession(timeout=aiohttp.ClientTimeout(total=3)) as session:
            async with helm_authorization(config, session, origin, args.bootstrap_key, result,
                                          identity_path=output / "test-public-identity.json") as headers:
                async def get():
                    async with session.get(origin + "/v1/audio", headers=headers) as response:
                        require(response.status == 200, "AUDIO_READ_FAILED")
                        return await response.json()
                observed = await get(); native = await wire.read()
                private_json(output / "before.json", {"audio": observed, "native": native})
                result["guards"] = guard_cleanup(baseline, observed, native, expected)
                async with session.ws_connect(origin + "/v1/audio/stream", headers=headers,
                                              protocols=["no5.audio.fader.v1"]) as socket:
                    state = await socket.receive_json(timeout=2)
                    require(state.get("kind") == "audio", "INITIAL_AUDIO_STATE_REQUIRED")
                    guard_cleanup(baseline, state, await wire.read(), expected)
                    producer = "own-cleanup-" + secrets.token_hex(8)
                    request = {"version": 1, "kind": "audio-command", "sessionId": producer,
                               "requestId": producer + ":1", "serverSessionId": state["serverSessionId"],
                               "expectedRevision": state["revision"], "command": {"type": "fader", "channel": chosen,
                               "valueDb": baseline["audio"]["dsp"]["faders"][chosen]["db"]}}
                    started = time.monotonic(); await socket.send_json(request); result["commandsSent"] = 1
                    while True:
                        ack = await socket.receive_json(timeout=1)
                        if ack.get("kind") == "audio-ack": break
                    private_json(output / "ack.json", ack)
                    result["ackStatus"] = ack.get("status"); result["ackMs"] = (time.monotonic() - started) * 1000
                    require(ack.get("requestId") == request["requestId"] and ack.get("sessionId") == producer and
                            ack.get("status") == "VERIFIED", "OWN_CLEANUP_ACK_NOT_VERIFIED_NO_RETRY")
                after = await get(); native_after = await wire.read()
                private_json(output / "after.json", {"audio": after, "native": native_after})
                require(after["serverSessionId"] == baseline["audio"]["serverSessionId"] and
                        after["dsp"]["faders"] == baseline["audio"]["dsp"]["faders"] and
                        native_after["sourceGeneration"] == native["sourceGeneration"], "OWN_CLEANUP_READBACK_MISMATCH")
                result["restored"] = True; result["allFadersAndMutesRestored"] = True
    finally:
        await wire.close(); private_json(output / "report.json", result)
    return result


def main():
    parser = argparse.ArgumentParser(); parser.add_argument("--config", type=Path, required=True)
    parser.add_argument("--bootstrap-key", type=Path, required=True); parser.add_argument("--probe-dir", type=Path, required=True)
    parser.add_argument("--execute", action="store_true"); args = parser.parse_args()
    if not args.execute:
        print(json.dumps({"prepared": True, "requestsSent": 0})); return
    try: print(json.dumps(asyncio.run(execute(args))))
    except ProbeFailure as exc: print(json.dumps({"restored": False, "failure": str(exc)})); raise SystemExit(1)

if __name__ == "__main__": main()
