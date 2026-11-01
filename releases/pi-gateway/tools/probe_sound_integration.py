#!/usr/bin/env python3
"""Native Camilla + actual gateway owner/HTTP/WS, file audio only; no Lab/Pi.

Outputs bind the exact binary and gateway source identities. Synthetic stereo
samples are explicitly a digital proof, not boat acoustics or live ALSA latency.
"""
import argparse
import asyncio
import copy
import hashlib
import importlib.util
import json
import math
import os
from pathlib import Path
import socket
import struct
import tempfile
import time

from aiohttp import ClientSession
from aiohttp.test_utils import TestClient, TestServer
from no5_pi_gateway.audio import CamillaDspClient
from no5_pi_gateway.configuration import AudioConfig, AudioFaderLimit, CamillaDspConfig, GatewayConfig
from no5_pi_gateway.service import AUDIO_SUBPROTOCOL, AUDIO_SPECTRUM_SUBPROTOCOL, create_app
from no5_pi_gateway.sound import CamillaSoundWire, SoundController, SoundRejected, default_calibration

RATE, BLOCK = 48000, 1024
FADERS = (("MASTER", 0), ("SUB", 1), ("REAR", 2), ("AUX3", 3), ("AUX4", 4))


def sha(path): return hashlib.sha256(path.read_bytes()).hexdigest()


class ObservedWire(CamillaSoundWire):
    def __init__(self, client): super().__init__(client); self.prepared = []
    async def apply(self, argument):
        started = time.monotonic()
        result = await super().apply(argument)
        self.prepared.append({"status": result["status"], "revision": result["revision"], "prepareMs": (time.monotonic() - started) * 1000})
        return result


async def main():
    parser = argparse.ArgumentParser(); parser.add_argument("--binary", required=True, type=Path)
    parser.add_argument("--fixture-helper", required=True, type=Path); parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args(); output = args.output.resolve(); output.mkdir(parents=True, exist_ok=True)
    binary = args.binary.resolve()
    binary_sha256 = sha(binary)
    spec = importlib.util.spec_from_file_location("native_file_fixture", args.fixture_helper)
    fixture = importlib.util.module_from_spec(spec); spec.loader.exec_module(fixture)
    with tempfile.TemporaryDirectory(prefix="no5-sound-file-") as temporary:
        directory = Path(temporary); pcm = directory / "output.pcm"; cfg = directory / "config.json"
        configuration = fixture.configuration(pcm); cfg.write_text(json.dumps(configuration))
        with socket.socket() as reservation:
            reservation.bind(("127.0.0.1", 0)); port = reservation.getsockname()[1]
        log = (output / "camilla.log").open("wb")
        process = await asyncio.create_subprocess_exec(str(binary), "-a", "127.0.0.1", "-p", str(port), "-l", "warn", str(cfg),
            stdin=asyncio.subprocess.PIPE, stdout=asyncio.subprocess.DEVNULL, stderr=log)
        feeding = True
        async def feed():
            block = 0
            while feeding:
                values = list(struct.unpack("<" + "d" * (BLOCK * 2), fixture.signal(block)))
                # Opposite-phase 1k/4k remain, while a low common-mode pilot
                # makes the mono Sub pair nonzero and therefore testable.
                for index in range(BLOCK):
                    pilot = .01 * math.sin(2 * math.pi * 300 * (block * BLOCK + index) / RATE)
                    values[index * 2] += pilot; values[index * 2 + 1] += pilot
                process.stdin.write(struct.pack("<" + "d" * len(values), *values)); await process.stdin.drain(); block += 1
                await asyncio.sleep(.02)
        producer = asyncio.create_task(feed())
        client = None; key = "file-proof-only-not-a-device-key"; env_name = "NO5_SOUND_FILE_PROOF_HASH"
        old_env = os.environ.get(env_name); os.environ[env_name] = hashlib.sha256(key.encode()).hexdigest()
        try:
            url = f"ws://127.0.0.1:{port}"
            camilla = CamillaDspConfig(enabled=True, websocket_url=url, channel_faders=FADERS)
            wire = ObservedWire(CamillaDspClient(camilla, persistent=True))
            controller = SoundController(wire, directory / "sound.json")
            for _ in range(100):
                try: await controller.observe(); break
                except Exception: await asyncio.sleep(.02)
            else: raise AssertionError("native No5 stage unavailable")
            config = GatewayConfig(audio_gateway_key_hash_env=env_name, audio=AudioConfig(enabled=True, hardware_available=True,
                camilla=camilla, fader_limits=tuple((channel, AudioFaderLimit(-60, 0, 1)) for channel, _ in FADERS)))
            app = await create_app(config); service = app["gateway_service"]; service.audio.sound = controller
            client = TestClient(TestServer(app)); await client.start_server(); headers = {"X-No5-Gateway-Key": key}
            for _ in range(100):
                audio = service.audio.payload
                if audio is not None and audio["capabilities"]["faderWrite"]: break
                await asyncio.sleep(.02)
            else: raise AssertionError("real Camilla fader authority unavailable")
            async def state():
                response = await client.get("/v1/audio/sound", headers=headers)
                assert response.status == 200, await response.text()
                return await response.json()
            number = 0
            async def command(kind, scope="preset", model=None, *, expected_status="APPLIED", expected_reason=None):
                nonlocal number
                current = await state(); number += 1
                request = dict(type=kind, previewId="native-proof", configGeneration=current["configGeneration"])
                if kind != "soundKeepalive":
                    request.update(scope=scope, expectedPresetRevision=current["presetRevision"], expectedCalibrationRevision=current["calibrationRevision"])
                if model is not None: request["model"] = model
                audio = service.audio.payload
                envelope = dict(version=1, kind="audio-command", sessionId="native-proof", requestId=f"native-proof:{number}",
                    serverSessionId=audio["serverSessionId"], expectedRevision=0, command=request)
                started = time.monotonic(); response = await client.post("/v1/audio/commands", headers=headers, json=envelope)
                assert response.status == 202, await response.text()
                accepted = await response.json()
                for _ in range(150):
                    result = await (await client.get("/v1/audio/commands/" + accepted["commandId"], headers=headers)).json()
                    if result["status"] not in {"ACCEPTED", "RUNNING"}: break
                    await asyncio.sleep(.01)
                assert result["status"] == expected_status, result
                if expected_status == "APPLIED": assert result["verification"] == "AUDIO_BLOCK_READBACK", result
                if expected_reason is not None: assert result["reason"] == expected_reason, result
                return {"status": result["status"], "reason": result["reason"], "verification": result.get("verification"), "elapsedMs": (time.monotonic() - started) * 1000}
            spectrum = await client.ws_connect("/v1/audio/spectrum", headers=headers, protocols=[AUDIO_SPECTRUM_SUBPROTOCOL])
            async def measured():
                for _ in range(30):
                    frame = await spectrum.receive_json(timeout=2)
                    if frame.get("schema") == "no5.audio-spectrum/1": return frame
                raise AssertionError("native spectrum unavailable")
            baseline = await measured()
            initial = await state(); target = copy.deepcopy(initial["preset"])
            assert initial["version"] == 2 and initial["eqPolicy"]["defaultReserveDb"] == 12
            assert target["eqEnabled"] is False and initial["headroomDb"] == 0
            target.update(eqEnabled=True, reserveDb=12.)
            target["eqPoints"] = [dict(id="one", frequencyHz=1000., gainDb=12., q=1., widthLocked=False)]
            eq_receipt = await command("soundPreview", model=target)
            current = await state(); assert current["headroomDb"] == 12., current
            # Wait for an analysis window consisting solely of the new processed signal.
            await asyncio.sleep(.4)
            while True:
                edited_frame = await measured()
                if edited_frame["samplePosition"] > baseline["samplePosition"] + 16384: break
            nearest = lambda frame, hz: min(range(len(frame["frequenciesHz"])), key=lambda index: abs(frame["frequenciesHz"][index] - hz))
            delta = edited_frame["binsDbfs"][nearest(edited_frame, 4000)] - baseline["binsDbfs"][nearest(baseline, 4000)]
            assert delta < -8., (delta, edited_frame, baseline)
            curve = current["response"]
            nearest_curve = min(range(len(curve["frequenciesHz"])), key=lambda index: abs(curve["frequenciesHz"][index] - 4000))
            predicted_delta = curve["gainsDb"][nearest_curve] - current["headroomDb"]
            assert abs(delta - predicted_delta) < .2, (delta, predicted_delta)
            assert edited_frame["binsDbfs"][nearest(edited_frame, 1000)] > -28., "antiphase channels disappeared"
            fixed = copy.deepcopy(target); fixed["reserveDb"] = 9.; fixed["eqPoints"][0]["gainDb"] = 6.
            await command("soundPreview", model=fixed)
            assert (await state())["headroomDb"] == 9.
            fixed["eqPoints"][0]["gainDb"] = 4.
            await command("soundPreview", model=fixed)
            assert (await state())["headroomDb"] == 9., "point movement changed reserve"
            fixed["eqEnabled"] = False
            await command("soundPreview", model=fixed)
            disabled = await state()
            assert disabled["headroomDb"] == 0. and disabled["preset"] == fixed
            assert disabled["calibration"] == initial["calibration"], "EQ bypass changed separate calibration"
            off_anchor = await wire.spectrum()
            assert off_anchor is not None
            await asyncio.sleep(.25)
            while True:
                off_frame = await measured()
                if off_frame["samplePosition"] > off_anchor["samplePosition"] + 16384: break
            off_delta = off_frame["binsDbfs"][nearest(off_frame,4000)]-baseline["binsDbfs"][nearest(baseline,4000)]
            assert abs(off_delta) < .2, ("disabled EQ did not bypass filters and reserve", off_delta)
            over_budget = copy.deepcopy(fixed);over_budget.update(eqEnabled=True,reserveDb=6.)
            over_budget["eqPoints"][0]["gainDb"] = 8.
            before_reject = await wire.read()
            budget_rejection = await command("soundPreview",model=over_budget,expected_status="REJECTED",expected_reason="EQ_COMBINED_GAIN_LIMIT")
            assert (await wire.read()) == before_reject, "known rejected budget changed native target"
            await command("soundPreview",model=target)
            calibration = default_calibration(); calibration["speakers"][4].update(manualDelayMs=3., manualGainDb=-4.)
            calibration_receipt = await command("soundPreview", "calibration", calibration)
            native = await wire.read(); assert native["calibration"]["delaysMs"][2:4] == [3., 3.]
            assert native["calibration"]["gainsDb"][2:4] == [-4., -4.]
            await asyncio.sleep(.15)
            raw = pcm.read_bytes(); usable = len(raw) // 48 * 48
            samples = struct.unpack("<" + "d" * (usable // 8), raw[:usable])
            assert any(abs(samples[index + 2]) > 1e-5 for index in range(0, len(samples), 6)), "Sub pilot absent"
            assert all(samples[index + 2] == samples[index + 3] for index in range(0, len(samples), 6)), "physical Sub PCM differs"
            await command("soundSave"); saved = await state()
            assert saved["presetRevision"] == 1 and saved["calibrationRevision"] == 0 and saved["preview"]["calibrationDirty"]
            await command("soundDiscard", "calibration")
            native = await wire.read(); assert native["preset"] == target and native["calibration"]["delaysMs"] == [0.] * 6
            # Existing fader WSS while a sound preview is active.
            room = copy.deepcopy(target); room["room"] = dict(amount=.6, size=.8)
            await command("soundPreview", model=room)
            faders = await client.ws_connect("/v1/audio/stream", headers=headers, protocols=[AUDIO_SUBPROTOCOL])
            audio = await faders.receive_json(); started = time.monotonic()
            await faders.send_json(dict(version=1, kind="audio-command", sessionId="fader-proof", requestId="fader-proof:1",
                serverSessionId=audio["serverSessionId"], expectedRevision=audio["revision"], command=dict(type="fader", channel="AUX3", valueDb=-4)))
            while True:
                ack = await faders.receive_json(timeout=2)
                if ack["kind"] == "audio-ack": break
            assert ack["status"] == "VERIFIED", ack
            fader_ms = (time.monotonic() - started) * 1000; await faders.close()
            # A short, specific lease expiry test is part of this feature.
            began = time.monotonic()
            for _ in range(70):
                current = await state()
                if current["preview"] is None and current["preset"] == target: break
                await asyncio.sleep(.1)
            else: raise AssertionError("five-second preview did not return to saved preset")
            expiry_ms = (time.monotonic() - began) * 1000
            assert 4500 <= expiry_ms <= 6500, expiry_ms
            native = await wire.read()
            rejected = False
            try:
                await wire.apply(dict(expectedConfigGeneration=native["configGeneration"], expectedAppliedRevision=99999,
                    expectedSourceGeneration=native["sourceGeneration"], validForMs=5000, previewLease=None,
                    revision=100000, preset=target, calibration=native["calibration"], transitionMs=50))
            except SoundRejected: rejected = True
            assert rejected, "explicit native CAS rejection was not distinguished from UNKNOWN"
            await spectrum.close()
            state_document = json.loads((directory / "sound.json").read_text())
            assert state_document["journal"] is None
            assert sha(binary) == binary_sha256, "binary changed during proof"
            result = dict(schema="no5.gateway-native-sound-proof/1", binarySha256=binary_sha256,
                gatewaySources={name: sha(Path(__file__).resolve().parents[1] / "src/no5_pi_gateway" / name)
                    for name in ("sound.py", "audio.py", "audio_commands.py", "service.py", "configuration.py")},
                eq=eq_receipt, calibration=calibration_receipt, prepared=wire.prepared,
                eqPolicyV2=dict(publicVersion=2,nativeSchema="no5.camilla-sound/2",initialDisabled=True,
                    chosenReserveDb=9.,reserveStableAcrossGainTargets=True,disabledCurvePreserved=True,
                    disabled4000DeltaDb=off_delta,budgetRejection=budget_rejection),
                spectrum=dict(framesMeasured=True, originalSampleTimePreserved=edited_frame["sampleTimeNs"],
                    sourceGeneration=edited_frame["sourceGeneration"], tap=edited_frame["tap"],
                    fftSize=edited_frame["fftSize"], headroomDb=current["headroomDb"], eq4000DeltaDb=delta,
                    responsePredicted4000DeltaDb=predicted_delta,
                    antiphaseStereoEnergyPreserved=True),
                independentScopeSaveDiscard=True, pairedSubCalibration=True, nonzeroSubPcmPairIdentical=True, faderWssMs=fader_ms,
                previewExpiryRollbackMs=expiry_ms, explicitCasRejected=True,
                scope="MAC_FILE_PCM_AND_REAL_LOOPBACK_GATEWAY; not ALSA, acoustic, Lab/Pi or Lumina acceptance")
            (output / "result.json").write_text(json.dumps(result, indent=2) + "\n")
            print(json.dumps(result, indent=2))
        finally:
            if client is not None: await client.close()
            feeding = False
            try: await producer
            except (BrokenPipeError, ConnectionError): pass
            if process.stdin and not process.stdin.is_closing(): process.stdin.close()
            try: await asyncio.wait_for(process.wait(), 3)
            except asyncio.TimeoutError: process.terminate(); await process.wait()
            log.close()
            if old_env is None: os.environ.pop(env_name, None)
            else: os.environ[env_name] = old_env


if __name__ == "__main__": asyncio.run(main())
