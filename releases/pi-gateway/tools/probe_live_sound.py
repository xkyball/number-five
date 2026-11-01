#!/usr/bin/env python3
"""Bounded acceptance against the installed gateway and real ALSA runtime.

Preparation is inert. Execution requires the coordinated activation owner to
invoke --execute. No fake server, source injection, preset save or Spotify
command exists here. Bootstrap and short-lived bearer material stay in memory.
"""
from __future__ import annotations
import argparse
import asyncio
import base64
from contextlib import asynccontextmanager
import copy
from collections import deque
import hashlib
import json
import math
import os
from pathlib import Path
import secrets
import stat
import sys
import time

import aiohttp
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from no5_pi_gateway.audio import CamillaDspClient
from no5_pi_gateway.configuration import load_config
from no5_pi_gateway.lumina_tokens import LuminaTokenRegistry
from no5_pi_gateway.sound import CamillaSoundWire, validate_spectrum, validate_preset, validate_calibration


class ProbeFailure(RuntimeError): pass

def require(passed, code):
    if not passed: raise ProbeFailure(code)


def private_json(path, value):
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, "O_NOFOLLOW", 0), 0o600)
    with os.fdopen(descriptor, "w") as handle:
        json.dump(value, handle, separators=(",", ":"), allow_nan=False); handle.write("\n"); handle.flush(); os.fsync(handle.fileno())


def read_bootstrap(path):
    descriptor = os.open(path, os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0))
    with os.fdopen(descriptor, "r") as handle:
        metadata = os.fstat(handle.fileno())
        require(stat.S_ISREG(metadata.st_mode) and not metadata.st_mode & 0o077 and 1 <= metadata.st_size <= 1024, "UNSAFE_BOOTSTRAP_INPUT")
        value = handle.read(1025).strip()
    require(32 <= len(value) <= 512 and all(0x21 <= ord(char) <= 0x7e for char in value), "INVALID_BOOTSTRAP_INPUT")
    return value


def b64(raw): return base64.urlsafe_b64encode(raw).decode().rstrip("=")


@asynccontextmanager
async def helm_authorization(config, session, origin, bootstrap_path, report, *, identity_path=None):
    """Fresh canonical-audience HELM token, real enroll and finally revoke.

    Existing registered keys are compared before/after; never removed or
    re-enrolled. The one new public test key remains revoked in the registry.
    The private signing key/bearer are never persisted or printed.
    """
    require(config.lumina_tokens.enabled, "LUMINA_BEARER_REQUIRED_FOR_PROBE")
    registry = LuminaTokenRegistry(config.lumina_tokens.registry_file, config.lumina_tokens.audience)
    before = copy.deepcopy(registry._read()["keys"])
    bootstrap = read_bootstrap(bootstrap_path)
    key = ec.generate_private_key(ec.SECP256R1())
    public = key.public_key().public_bytes(serialization.Encoding.X962, serialization.PublicFormat.UncompressedPoint)
    key_id = hashlib.sha256(public).hexdigest()[:32]
    require(key_id not in before, "TEST_KEY_COLLISION")
    if identity_path is not None:
        # Public identity only, privately retained for exact revoke cleanup if
        # the process/network fails. No private key or bearer is serialized.
        private_json(identity_path, {"kind": "ephemeral-test-key", "keyId": key_id, "publicKey": b64(public)})
    async def provision(path, body):
        async with session.post(origin + path, headers={"X-No5-Gateway-Key": bootstrap}, json=body) as response:
            require(response.status == 204, "AUTH_PROVISION_HTTP_" + str(response.status))
    enrolled = False
    try:
        await provision("/v2/lumina/enroll", {"keyId": key_id, "publicKey": b64(public)}); enrolled = True
        issued = time.time_ns() // 1_000_000
        payload = (f"v=no5-gateway-token-v2\nkid={key_id}\naud={config.lumina_tokens.audience}\nrole=HELM\n"
                   f"scope=audio.read,audio.command,sonar.read,sonar.command\niat={issued}\nexp={issued + 900000}\njti={b64(os.urandom(16))}\n").encode()
        bearer = "Bearer N5T2." + b64(payload) + "." + b64(key.sign(payload, ec.ECDSA(hashes.SHA256())))
        require(registry.verify(bearer, "audio.read") is not None and registry.verify(bearer, "audio.command") is not None, "FRESH_CONFIG_TOKEN_REJECTED")
        yield {"Authorization": bearer}
    finally:
        # Enrollment may have succeeded even if its reply was lost. Observe
        # this new test identity before cleanup; never leave an active key.
        observed_keys = registry._read()["keys"]
        if enrolled or key_id in observed_keys:
            await provision("/v2/lumina/revoke", {"keyId": key_id})
        after = registry._read()["keys"]
        report["testKeyRevoked"] = key_id not in after or after[key_id].get("revoked") is True
        report["temporaryTestKeyOnly"] = True
        report["existingRegistrationRowsPreserved"] = all(after.get(identifier) == value for identifier, value in before.items())
        require(report["testKeyRevoked"] and report["existingRegistrationRowsPreserved"], "AUTH_CLEANUP_NOT_CONFIRMED")


def changed_paths(before, after, path=""):
    """Values remain private; public diagnostics contain field names only."""
    if isinstance(before, dict) and isinstance(after, dict):
        result = []
        for key in sorted(set(before) | set(after)):
            result.extend(changed_paths(before.get(key), after.get(key), (path + "." + key).lstrip(".")))
        return result
    return [] if before == after else [path]


def control_projection(state):
    """Observable command fields; not a claim to expose hidden owner CAS."""
    dsp = copy.deepcopy(state["dsp"])
    dsp.pop("clippedSamples", None); dsp.pop("meters", None); dsp.pop("sound", None)
    for key in ("quality", "equalizer"):
        if isinstance(dsp.get(key), dict):
            dsp[key].pop("timestampEpochMs", None); dsp[key].pop("observedAtEpochMs", None)
    media = copy.deepcopy(state.get("media")); source = copy.deepcopy(state.get("audioSource"))
    if isinstance(media, dict): media.pop("positionMs", None)
    if isinstance(source, dict) and isinstance(source.get("quality"), dict): source["quality"].pop("timestampEpochMs", None)
    capabilities = copy.deepcopy(state.get("capabilities", {})); capabilities.pop("sound", None)
    return {"dsp": dsp, "media": media, "audioSource": source, "capabilities": capabilities,
            "authority": state.get("authority"), "limits": state.get("limits")}


def retryable_fader_conflict(ack, sent_state):
    # This exact backend rejection occurs before any write. A lost reply,
    # malformed ACK, UNKNOWN or any other rejection is never permission to retry.
    state = ack.get("state", {})
    return (ack.get("status") == "REJECTED" and ack.get("message") == "AUDIO state changed; command was not sent" and
            state.get("serverSessionId") == sent_state.get("serverSessionId") and
            state.get("revision", -1) > sent_state.get("revision", -1) and
            state.get("dsp", {}).get("faders") == sent_state.get("dsp", {}).get("faders") and
            state.get("dsp", {}).get("state") == sent_state.get("dsp", {}).get("state") and
            state.get("dsp", {}).get("preset") == sent_state.get("dsp", {}).get("preset") and
            state.get("capabilities", {}).get("faderWrite") is True)


class ObservedFaderStream:
    """Continuously consume publications, correlate one in-flight ACK, never replay.

    The optional second CAS request is decided by the caller only after a strict
    known-no-effect revision rejection. This stream never retries bytes itself.
    Private evidence is bounded to 64 received events plus per-attempt receipts.
    """
    def __init__(self, socket, directory):
        self.socket = socket; self.directory = directory; self.latest = None
        self.last_verified = None; self.history = deque(maxlen=64)
        self.pending = None; self.failure = None; self.ready = asyncio.Event()
        self.counter = 0; self.closed = False; self.receiver = asyncio.create_task(self._observe())
    def accept(self, state):
        require(state.get("kind") == "audio", "MALFORMED_FADER_STATE")
        if self.latest is None or state["serverSessionId"] != self.latest["serverSessionId"] or state["revision"] >= self.latest["revision"]:
            self.latest = copy.deepcopy(state); self.ready.set()
    async def _observe(self):
        try:
            async for message in self.socket:
                require(message.type == aiohttp.WSMsgType.TEXT, "FADER_CONNECTION_UNCERTAIN_NO_RETRY")
                value = json.loads(message.data); self.history.append(copy.deepcopy(value))
                if value.get("kind") == "audio": self.accept(value)
                elif value.get("kind") == "audio-ack":
                    pending = self.pending
                    require(pending is not None and value.get("requestId") == pending[0]["requestId"] and
                            value.get("sessionId") == pending[0]["sessionId"] and
                            value.get("serverSessionId") == pending[0]["serverSessionId"], "FADER_ACK_IDENTITY_MISMATCH")
                    received = time.monotonic(); request, future = pending
                    self.accept(value["state"])
                    private_json(self.directory / f"fader-attempt-{self.counter}.json", {"request": request, "ack": value,
                                 "receivedMonotonic": received})
                    if value.get("status") == "VERIFIED":
                        channel = request["command"]["channel"]
                        require(value["state"]["dsp"]["faders"][channel]["db"] == request["command"]["valueDb"], "FADER_VERIFIED_VALUE_MISMATCH")
                        self.last_verified = copy.deepcopy(value["state"])
                        # Durable own trajectory exists before cleanup, even if a
                        # following request is rejected or its reply is lost.
                        private_json(self.directory / f"fader-verified-{self.counter}.json", self.last_verified)
                    self.pending = None
                    if not future.done(): future.set_result((value, received))
                else: raise ProbeFailure("UNEXPECTED_FADER_MESSAGE")
            raise ProbeFailure("FADER_CONNECTION_UNCERTAIN_NO_RETRY")
        except asyncio.CancelledError: raise
        except Exception as exc:
            self.failure = exc; self.ready.set()
            if self.pending is not None and not self.pending[1].done(): self.pending[1].set_exception(exc)
    async def state(self):
        await asyncio.wait_for(self.ready.wait(), 2)
        if self.failure is not None: raise self.failure
        require(self.latest is not None, "FADER_STATE_NOT_OBSERVED")
        return copy.deepcopy(self.latest)
    async def write(self, request):
        require(self.pending is None and self.failure is None, "FADER_FLIGHT_UNCERTAIN_NO_RETRY")
        self.counter += 1; future = asyncio.get_running_loop().create_future(); self.pending = (copy.deepcopy(request), future)
        began = time.monotonic()
        await self.socket.send_json(request)
        ack, received = await asyncio.wait_for(asyncio.shield(future), 1)
        return ack, (received - began) * 1000
    async def close(self):
        if self.closed: return
        self.closed = True
        self.receiver.cancel()
        try: await self.receiver
        except asyncio.CancelledError: pass
        private_json(self.directory / "fader-received-history.json", list(self.history))
        await self.socket.close()


async def execute(args):
    config = load_config(args.config)
    require(config.bind_host in {"127.0.0.1", "::1"}, "EXACT_LOOPBACK_GATEWAY_REQUIRED")
    origin = "http://" + ("[::1]" if config.bind_host == "::1" else "127.0.0.1") + ":" + str(config.port)
    require(args.private_dir.is_absolute() and str(args.private_dir).startswith("/var/lib/no5-rebuild-private/"), "PRIVATE_REBUILD_DIRECTORY_REQUIRED")
    parent_metadata = args.private_dir.lstat()
    require(stat.S_ISDIR(parent_metadata.st_mode) and not parent_metadata.st_mode & 0o077, "PRIVATE_OUTPUT_PERMISSIONS_REQUIRED")
    directory = args.private_dir / ("probe-" + secrets.token_hex(6)); directory.mkdir(mode=0o700)
    report = {"schema": "no5.live-sound-acceptance/1", "scope": "INSTALLED_GATEWAY_LOOPBACK_HTTP_WS_AND_REAL_ALSA; not HTTPS/Lumina or acoustic acceptance",
              "passed": False, "soundCommands": [], "faderCommands": [], "testKeyRevoked": False,
              "spotifyCommandsSent": 0, "servicesChanged": False}
    wire = CamillaSoundWire(CamillaDspClient(config.audio.camilla, persistent=True))
    sounds = []; spectrum_frames = []; fader_last = None; initial_audio = initial_sound = initial_native = None
    spectrum = spectrum_task = fader_socket = fader_stream = None
    sequence = 0; producer = "sound-probe-" + secrets.token_hex(8); preview = "sound-probe-" + secrets.token_hex(8)
    timeout = aiohttp.ClientTimeout(total=3)
    try:
        async with aiohttp.ClientSession(timeout=timeout) as session:
            async with helm_authorization(config, session, origin, args.bootstrap_key, report, identity_path=directory / "test-public-identity.json") as headers:
                async def get(path):
                    async with session.get(origin + path, headers=headers) as response:
                        require(response.status == 200, "GET_HTTP_" + str(response.status))
                        return await response.json()
                async def native(command): return await wire.request(command)
                initial_sound = await get("/v1/audio/sound"); initial_audio = await get("/v1/audio"); initial_native = await wire.read()
                actual_config = json.loads(await native("GetConfigJson"))
                devices = actual_config.get("devices", {})
                require(devices.get("samplerate") == 48000 and devices.get("capture", {}).get("type") == "Alsa" and
                        devices.get("capture", {}).get("channels") == 2 and devices.get("playback", {}).get("type") == "Alsa" and
                        devices.get("playback", {}).get("channels") == 6, "ACTUAL_ALSA_2_TO_6_REQUIRED")
                require(initial_sound["writable"] and initial_native["previewLease"] is None and initial_native["preparedRevision"] == initial_native["appliedRevision"], "UNOWNED_OR_UNAVAILABLE_SOUND_BASELINE")
                require(await native("GetState") == "Running", "CAMILLA_NOT_RUNNING")
                initial_clips = await native("GetClippedSamples")
                private_json(directory / "baseline.json", {"audio": initial_audio, "sound": initial_sound, "native": initial_native, "config": actual_config})
                report["actualAlsa2To6"] = True
                spectrum = await session.ws_connect(origin + "/v1/audio/spectrum", headers=headers, protocols=["no5.audio.spectrum.v1"])
                async def observe_spectrum():
                    async for message in spectrum:
                        require(message.type == aiohttp.WSMsgType.TEXT, "SPECTRUM_CONNECTION_LOST")
                        value = json.loads(message.data)
                        if value.get("schema") != "no5.audio-spectrum/1": continue
                        value = validate_spectrum(value)
                        require(value["sourceGeneration"] == initial_native["sourceGeneration"], "SPECTRUM_SOURCE_CHANGED")
                        if spectrum_frames:
                            previous = spectrum_frames[-1]
                            require(value["sequence"] > previous["sequence"] and value["samplePosition"] > previous["samplePosition"] and
                                    value["sampleTimeNs"] > previous["sampleTimeNs"], "SPECTRUM_SOURCE_CLOCK_NOT_ADVANCING")
                        spectrum_frames.append(value)
                        if len(spectrum_frames) > 64: del spectrum_frames[0]
                spectrum_task = asyncio.create_task(observe_spectrum())
                async def sound(kind, scope="preset", model=None):
                    nonlocal sequence
                    sequence += 1; state = await get("/v1/audio/sound"); audio = await get("/v1/audio")
                    require(state["configGeneration"] == initial_sound["configGeneration"] and state["sourceGeneration"] == initial_sound["sourceGeneration"], "SOUND_SOURCE_CHANGED")
                    command = {"type": kind, "previewId": preview, "configGeneration": state["configGeneration"]}
                    if kind != "soundKeepalive": command.update(scope=scope, expectedPresetRevision=state["presetRevision"], expectedCalibrationRevision=state["calibrationRevision"])
                    if model is not None: command["model"] = model
                    request = {"version": 1, "kind": "audio-command", "sessionId": producer, "requestId": f"{producer}:{sequence}",
                               "serverSessionId": audio["serverSessionId"], "expectedRevision": audio["revision"], "command": command}
                    began = time.monotonic()
                    async with session.post(origin + "/v1/audio/commands", headers=headers, json=request) as response:
                        require(response.status == 202, "SOUND_ADMISSION_HTTP_" + str(response.status)); receipt = await response.json()
                    admission = (time.monotonic() - began) * 1000
                    while time.monotonic() - began < 1:
                        result = await get("/v1/audio/commands/" + receipt["commandId"])
                        if result["status"] not in {"ACCEPTED", "RUNNING"}: break
                        await asyncio.sleep(.005)
                    elapsed = (time.monotonic() - began) * 1000
                    report["soundCommands"].append({"type": kind, "scope": scope, "status": result["status"], "admissionMs": admission, "appliedMs": elapsed})
                    require(result["status"] == "APPLIED", "SOUND_RESULT_" + result["status"])
                    require(elapsed <= 250, "SOUND_250MS_TARGET_MISSED")
                    sounds.append(kind)
                    return await get("/v1/audio/sound")
                faders = initial_audio["dsp"]["faders"]
                chosen = next((channel for channel in ("AUX3", "REAR", "SUB", "AUX4") if channel in config.audio.limits and
                               faders[channel]["db"] - config.audio.limits[channel].step_db >= config.audio.limits[channel].min_db), None)
                require(chosen is not None, "NO_SAFE_NON_MAIN_FADER_STEP")
                fader_socket = await session.ws_connect(origin + "/v1/audio/stream", headers=headers, protocols=["no5.audio.fader.v1"])
                fader_stream = ObservedFaderStream(fader_socket, directory)
                async def fader_pair_test():
                    nonlocal fader_last
                    original = faders[chosen]["db"]; target = original - config.audio.limits[chosen].step_db
                    expected_faders = copy.deepcopy(faders); attempt_number = 0
                    for value in (target, original, target, original, target, original):
                        await asyncio.sleep(.025); goal_started = time.monotonic()
                        for attempt in range(2):
                            state = await fader_stream.state()
                            require(state["serverSessionId"] == initial_audio["serverSessionId"], "FADER_SOURCE_CHANGED")
                            require(state["dsp"]["faders"] == expected_faders, "FADER_FOREIGN_CONTROL_CHANGE_NO_WRITE")
                            attempt_number += 1
                            request = {"version": 1, "kind": "audio-command", "sessionId": producer + "-fader", "requestId": f"{producer}-fader:{attempt_number}",
                                "serverSessionId": state["serverSessionId"], "expectedRevision": state["revision"], "command": {"type": "fader", "channel": chosen, "valueDb": value}}
                            value_ack, elapsed = await fader_stream.write(request)
                            paths = changed_paths(control_projection(state), control_projection(value_ack["state"]))
                            report["faderCommands"].append({"status": value_ack["status"], "ackMs": elapsed,
                                "reason": value_ack.get("message"), "observableControlChangedFields": paths,
                                "revisionAdvanced": value_ack["state"]["revision"] > state["revision"], "freshCasAttempt": attempt > 0})
                            if value_ack["status"] == "VERIFIED":
                                fader_last = fader_stream.last_verified; expected_faders[chosen]["db"] = value
                                goal_ms = (time.monotonic() - goal_started) * 1000
                                report.setdefault("faderGoalMs", []).append(goal_ms)
                                require(goal_ms <= 250 and
                                        fader_last["dsp"]["faders"]["MASTER"] == faders["MASTER"], "FADER_DEADLINE_OR_MAIN_CHANGED")
                                break
                            require(attempt == 0 and retryable_fader_conflict(value_ack, state), "FADER_RESULT_" + value_ack["status"])
                            # New request only after an exact pre-write rejection
                            # and fresh known fader vector. Unknown never retries.
                            fresh = await get("/v1/audio")
                            require(fresh["serverSessionId"] == initial_audio["serverSessionId"] and
                                    fresh["dsp"]["faders"] == expected_faders, "FADER_REJECTED_BUT_FOREIGN_STATE_NO_RETRY")
                            fader_stream.accept(fresh)
                            await asyncio.sleep(.020)  # preserve the stream's admission rate
                    return fader_last
                fader_task = asyncio.create_task(fader_pair_test())
                probe_point_id = "probe-" + secrets.token_hex(8)
                eq_v2_enabled = getattr(args, "eq_v2_enabled", False)
                async def spectrum_checkpoint(label, expected_preset):
                    observed = await wire.read()
                    require(observed["preset"] == expected_preset and observed["headroomDb"] ==
                            (expected_preset["reserveDb"] if expected_preset["eqEnabled"] else 0.) and
                            observed["preparedRevision"] == observed["appliedRevision"], "EQ_V2_PROCESSING_READBACK_MISMATCH")
                    # A full 8192-sample window plus the 50ms transition must
                    # elapse before recording a definitely later live spectrum.
                    await asyncio.sleep(.32)
                    require(spectrum_frames and not spectrum_task.done(), "EQ_V2_LIVE_SPECTRUM_MISSING")
                    frame = spectrum_frames[-1]
                    report.setdefault("eqV2Checkpoints", []).append({"label": label, "eqEnabled": expected_preset["eqEnabled"],
                        "reserveDb": expected_preset["reserveDb"], "headroomDb": observed["headroomDb"],
                        "spectrumSequence": frame["sequence"], "samplePosition": frame["samplePosition"],
                        "peakDbfs": max(frame["peaksDbfs"]), "nonSilent": max(frame["peaksDbfs"]) > -100})
                try:
                    for index in range(3):
                        model = copy.deepcopy(initial_sound["preset"])
                        if len(model["eqPoints"]) < 16:
                            model["eqPoints"].append({"id": probe_point_id, "frequencyHz": 1000., "gainDb": -.5 * (index + 1), "q": 1., "widthLocked": False})
                        else:
                            point = model["eqPoints"][0]; point["gainDb"] = max(-12., point["gainDb"] - .5 * (index + 1))
                        if initial_sound["capabilities"]["room"]:
                            model["room"]["amount"] = max(0., min(1., model["room"]["amount"] + (.05 if model["room"]["amount"] < .9 else -.05)))
                        if eq_v2_enabled:
                            model["eqEnabled"] = True; model["reserveDb"] = 12.
                        validate_preset(model); await sound("soundPreview", model=model)
                        if eq_v2_enabled: await spectrum_checkpoint("EQ_ON_POINT_" + str(index + 1), model)
                    if eq_v2_enabled:
                        off_model = copy.deepcopy(model); off_model["eqEnabled"] = False
                        await sound("soundPreview", model=off_model)
                        await spectrum_checkpoint("EQ_OFF_CURVE_RETAINED", off_model)
                        report["eqV2ConstantReserveAndBypassReadback"] = True
                    await fader_task
                    calibration = copy.deepcopy(initial_sound["calibration"])
                    sub = next(item for item in calibration["speakers"] if item["id"] == "SUB")
                    if sub["manualGainDb"] > -23.75: sub["manualGainDb"] -= .25
                    validate_calibration(calibration); await sound("soundPreview", "calibration", calibration)
                    current = await wire.read()
                    require(current["calibration"]["delaysMs"][2] == current["calibration"]["delaysMs"][3] and
                            current["calibration"]["gainsDb"][2] == current["calibration"]["gainsDb"][3], "SUB_PAIR_MISMATCH")
                    await sound("soundDiscard", "calibration"); await sound("soundDiscard", "preset")
                finally:
                    original_failure = sys.exception()
                    if original_failure is not None:
                        report["failure"] = str(original_failure) if isinstance(original_failure, ProbeFailure) else type(original_failure).__name__
                    try:
                        if not fader_task.done():
                            try: await asyncio.wait_for(asyncio.shield(fader_task), 2)
                            except (asyncio.TimeoutError, Exception):
                                report["faderCompletionUncertain"] = True
                                fader_task.cancel()
                                try: await fader_task
                                except (asyncio.CancelledError, Exception): pass
                        # Global media/sound revisions are not proof of a foreign
                        # fader write. Require the full known own fader vector and
                        # unchanged source/config, then use a fresh current CAS.
                        observed_audio = await get("/v1/audio"); fader_last = fader_stream.last_verified
                        if observed_audio["dsp"]["faders"] != faders and fader_last is not None:
                            current_native = await wire.read()
                            require(observed_audio["serverSessionId"] == initial_audio["serverSessionId"] and
                                    observed_audio["dsp"]["faders"] == fader_last["dsp"]["faders"] and
                                    current_native["sourceGeneration"] == initial_native["sourceGeneration"] and
                                    current_native["configGeneration"] == initial_native["configGeneration"], "FADER_FOREIGN_CHANGE_REQUIRES_REVIEW")
                            restore_id = producer + "-restore"; await asyncio.sleep(.025)
                            restored, _ = await fader_stream.write({"version": 1, "kind": "audio-command", "sessionId": restore_id,
                                "requestId": restore_id + ":1", "serverSessionId": observed_audio["serverSessionId"],
                                "expectedRevision": observed_audio["revision"],
                                "command": {"type": "fader", "channel": chosen, "valueDb": faders[chosen]["db"]}})
                            require(restored["status"] == "VERIFIED", "OWN_FADER_CLEANUP_NOT_CONFIRMED_NO_RETRY")
                        await fader_stream.close(); fader_stream = None; fader_socket = None
                        # Only backend-owned preview cleanup; its owner/source/lease
                        # CAS preserves any intervening foreign operation.
                        current = await get("/v1/audio/sound")
                        if (current.get("preview") or {}).get("id") == preview:
                            for scope in ("calibration", "preset"):
                                if current["preview"] is not None and current["preview"][scope + "Dirty"]:
                                    current = await sound("soundDiscard", scope)
                    except Exception as cleanup_failure:
                        report["cleanupFailure"] = str(cleanup_failure) if isinstance(cleanup_failure, ProbeFailure) else type(cleanup_failure).__name__
                        if original_failure is None: raise
                for _ in range(20):
                    if len(spectrum_frames) >= 5: break
                    if spectrum_task.done(): await spectrum_task
                    await asyncio.sleep(.05)
                require(len(spectrum_frames) >= 5, "REAL_SPECTRUM_FLOW_NOT_OBSERVED")
                after_sound = await get("/v1/audio/sound"); after_audio = await get("/v1/audio"); after_native = await wire.read()
                report.update(presetRestored=after_native["preset"] == initial_native["preset"], calibrationRestored=after_native["calibration"] == initial_native["calibration"],
                    presetRevisionUnchanged=after_sound["presetRevision"] == initial_sound["presetRevision"],
                    calibrationRevisionUnchanged=after_sound["calibrationRevision"] == initial_sound["calibrationRevision"],
                    allFadersAndMutesRestored=after_audio["dsp"]["faders"] == faders, mainUnchanged=after_audio["dsp"]["faders"]["MASTER"] == faders["MASTER"],
                    sourceGenerationUnchanged=after_native["sourceGeneration"] == initial_native["sourceGeneration"],
                    spectrumFramesObserved=len(spectrum_frames), spectrumClockAdvanced=True,
                    maxSpectrumSampleTimeGapMs=max((second["sampleTimeNs"] - first["sampleTimeNs"]) / 1_000_000
                        for first, second in zip(spectrum_frames, spectrum_frames[1:])),
                    spectrumGapOver500msObserved=any(second["sampleTimeNs"] - first["sampleTimeNs"] > 500_000_000
                        for first, second in zip(spectrum_frames, spectrum_frames[1:])),
                    maxObservedPeakDbfs=max(max(frame["peaksDbfs"]) for frame in spectrum_frames),
                    liveSignalThresholdDbfs=-100,
                    liveNonSilentSignalObserved=any(max(frame["peaksDbfs"]) > -100 for frame in spectrum_frames),
                    liveFrequencyResponseProven=False,
                    frequencyResponseEvidence="SEPARATE_CALIBRATED_NATIVE_FILE_PCM",
                    playbackClipCounterDelta=(await native("GetClippedSamples")) - initial_clips,
                    actualCamillaRunning=await native("GetState") == "Running")
                private_json(directory / "spectrum-frames.json", spectrum_frames)
                private_json(directory / "after.json", {"audio": after_audio, "sound": after_sound, "native": after_native})
                for key in ("presetRestored", "calibrationRestored", "presetRevisionUnchanged", "calibrationRevisionUnchanged", "allFadersAndMutesRestored", "mainUnchanged", "sourceGenerationUnchanged", "actualCamillaRunning"):
                    require(report[key], key.upper() + "_FAILED")
                spectrum_task.cancel()
                try: await spectrum_task
                except asyncio.CancelledError: pass
                await spectrum.close()
                require(not report["spectrumGapOver500msObserved"], "REAL_SPECTRUM_GAP_OVER_500MS")
                report["transportChecksPassed"] = True
        report["passed"] = report.get("transportChecksPassed", False) and report.get("liveNonSilentSignalObserved", False)
        report["status"] = "PASS" if report["passed"] else "LIMITED_SILENT_INPUT"
        report["liveAudioEvidence"] = "NON_SILENT_PCM_OBSERVED" if report.get("liveNonSilentSignalObserved") else "NOT_PASSED_SILENT_INPUT"
    except Exception as exc:
        report.setdefault("failure", str(exc) if isinstance(exc, ProbeFailure) else type(exc).__name__)
        raise
    finally:
        if spectrum_frames: private_json(directory / "last-spectrum-frames.json", spectrum_frames)
        if fader_stream is not None: await fader_stream.close()
        if spectrum_task is not None and not spectrum_task.done():
            spectrum_task.cancel()
            try: await spectrum_task
            except (asyncio.CancelledError, Exception): pass
        if spectrum is not None: await spectrum.close()
        if fader_socket is not None: await fader_socket.close()
        await wire.close()
        private_json(directory / "report.json", report)
    return report


def main():
    parser = argparse.ArgumentParser(); parser.add_argument("--config", type=Path, required=True)
    parser.add_argument("--bootstrap-key", type=Path, required=True); parser.add_argument("--private-dir", type=Path, required=True)
    parser.add_argument("--eq-v2-enabled", action="store_true", help="Explicitly preview EQ On/12 during point changes and Off with the same curve; restore only own preview")
    parser.add_argument("--execute", action="store_true"); args = parser.parse_args()
    if not args.execute:
        print(json.dumps({"prepared": True, "requestsSent": 0, "requiresCoordinatedActiveRuntime": True})); return
    try: print(json.dumps(asyncio.run(execute(args)), indent=2))
    except Exception as exc:
        print(json.dumps({"schema": "no5.live-sound-acceptance/1", "passed": False,
                          "failure": str(exc) if isinstance(exc, ProbeFailure) else type(exc).__name__})); raise SystemExit(1)


if __name__ == "__main__": main()
