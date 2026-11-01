#!/usr/bin/env python3
"""Targeted native preview expiry, stopped owner/producer and lost save ACK proof.

Only synthetic file PCM, private loopback ports and a temporary state document.
No Lab/Pi, ALSA, device credentials or production services are used.
"""
import argparse
import asyncio
import copy
import hashlib
import importlib.util
import json
from pathlib import Path
import socket
import time

from no5_pi_gateway.audio import CamillaDspClient
from no5_pi_gateway.configuration import CamillaDspConfig
from no5_pi_gateway.sound import CamillaSoundWire, SoundController, SoundUnknown


class LostAckWire(CamillaSoundWire):
    def __init__(self, client):
        super().__init__(client); self.targets = []; self.lease_calls = 0; self.drop_next_lease_ack = False
    async def apply(self, argument):
        result = await super().apply(argument)
        self.targets.append(copy.deepcopy(argument))
        return result
    async def lease(self, argument):
        self.lease_calls += 1
        result = await super().lease(argument)
        if self.drop_next_lease_ack:
            self.drop_next_lease_ack = False
            raise ConnectionError("injected loss after native lease acknowledgment")
        return result


async def stop_observer(controller):
    if controller._task is not None:
        controller._task.cancel()
        try: await controller._task
        except asyncio.CancelledError: pass
        controller._task = None


async def main():
    parser = argparse.ArgumentParser(); parser.add_argument("--binary", type=Path, required=True)
    parser.add_argument("--fixture-helper", type=Path, required=True); parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args(); args.output.mkdir(parents=True, exist_ok=True)
    binary_sha256 = hashlib.sha256(args.binary.read_bytes()).hexdigest()
    import tempfile
    spec = importlib.util.spec_from_file_location("file_fixture", args.fixture_helper)
    fixture = importlib.util.module_from_spec(spec); spec.loader.exec_module(fixture)
    with tempfile.TemporaryDirectory(prefix="no5-native-preview-") as temporary:
        directory = Path(temporary); configuration = directory / "config.json"
        configuration.write_text(json.dumps(fixture.configuration(directory / "out.pcm")))
        with socket.socket() as reservation:
            reservation.bind(("127.0.0.1", 0)); port = reservation.getsockname()[1]
        log = (args.output / "camilla.log").open("wb")
        process = await asyncio.create_subprocess_exec(str(args.binary), "-a", "127.0.0.1", "-p", str(port), "-l", "warn", str(configuration),
            stdin=asyncio.subprocess.PIPE, stdout=asyncio.subprocess.DEVNULL, stderr=log)
        active = True; gate = asyncio.Event(); gate.set(); written = 0
        async def feed():
            nonlocal written
            while active:
                await gate.wait()
                process.stdin.write(fixture.signal(written)); await process.stdin.drain(); written += 1
                await asyncio.sleep(.021)
        producer = asyncio.create_task(feed())
        wire = LostAckWire(CamillaDspClient(CamillaDspConfig(enabled=True, websocket_url=f"ws://127.0.0.1:{port}",
            channel_faders=(("MASTER", 0), ("SUB", 1), ("REAR", 2), ("AUX3", 3), ("AUX4", 4))), persistent=True))
        controller = SoundController(wire, directory / "sound.json")
        try:
            for _ in range(100):
                try: await controller.observe(); break
                except Exception: await asyncio.sleep(.02)
            else: raise AssertionError("native lease stage unavailable")
            controller.start()
            async def send(kind, model=None):
                state = controller.snapshot("proof")
                command = {"type": kind, "previewId": "proof", "configGeneration": state["configGeneration"]}
                if kind != "soundKeepalive":
                    command.update(scope="preset", expectedPresetRevision=state["presetRevision"], expectedCalibrationRevision=state["calibrationRevision"])
                if model is not None: command["model"] = model
                return await controller.command("proof", command, lambda: True)
            target = copy.deepcopy(controller.current["preset"])
            target.update(eqEnabled=True, reserveDb=12.)
            target["eqPoints"] = [dict(id="one", frequencyHz=1000., gainDb=4., q=1., widthLocked=False)]
            await send("soundPreview", target)
            wire.drop_next_lease_ack = True; unknown = False
            try: await send("soundSave")
            except SoundUnknown: unknown = True
            assert unknown, "lost native save/release ACK incorrectly became success"
            assert controller.store.state["presetRevision"] == 1
            writes = len(wire.targets); leases = wire.lease_calls
            async with controller.lock:
                await controller.observe(); await controller._reconcile_lease_sync()
            assert controller.store.state["journal"] is None and controller.preview is None
            assert len(wire.targets) == writes and wire.lease_calls == leases, "lost save was replayed"
            preview = copy.deepcopy(target); preview["room"] = {"amount": .7, "size": .8}
            await send("soundPreview", preview)
            lease = controller.store.state["journal"]["leaseId"]; before = controller.current["appliedRevision"]
            await stop_observer(controller)
            writes = len(wire.targets); started = time.monotonic()
            for _ in range(70):
                native = await wire.read()
                if native["lastRollback"] is not None and native["lastRollback"]["leaseId"] == lease and native["appliedRevision"] == native["preparedRevision"]: break
                await asyncio.sleep(.1)
            else: raise AssertionError("native preview did not expire without gateway observer")
            elapsed = (time.monotonic() - started) * 1000
            assert 4500 < elapsed < 6500, elapsed
            assert native["preset"] == target and native["previewLease"] is None
            assert native["lastRollback"]["fromRevision"] == before
            await controller._recover()
            assert controller.store.state["journal"] is None and len(wire.targets) == writes
            # Now withhold every new audio block before submitting the preview.
            gate.clear(); await asyncio.sleep(.15)
            before = (await wire.read())["appliedRevision"]
            unknown_pending = False
            try: await send("soundPreview", preview)
            except SoundUnknown: unknown_pending = True
            assert unknown_pending, "unprocessed preparation falsely acknowledged as applied"
            journal = copy.deepcopy(controller.store.state["journal"]); writes = len(wire.targets)
            for _ in range(70):
                native = await wire.read()
                if native["previewLease"] is None and native["preparedRevision"] > journal["targetRevision"]: break
                await asyncio.sleep(.1)
            else: raise AssertionError("native worker did not prepare expiry rollback while producer stopped")
            assert native["appliedRevision"] == before and native["preset"] == target
            gate.set()
            for _ in range(50):
                native = await wire.read()
                if native["lastRollback"] is not None and native["lastRollback"]["leaseId"] == journal["leaseId"] and native["appliedRevision"] == native["preparedRevision"]: break
                await asyncio.sleep(.02)
            else: raise AssertionError("native rollback failed to become processed after producer resume")
            assert native["preset"] == target, "expired draft reached the final applied model"
            await controller._recover()
            assert len(wire.targets) == writes and controller.store.state["journal"] is None
            assert hashlib.sha256(args.binary.read_bytes()).hexdigest() == binary_sha256, "binary changed during proof"
            result = {"schema": "no5.gateway-native-lease-proof/1", "binarySha256": binary_sha256,
                "gatewaySources": {name: hashlib.sha256((Path(__file__).resolve().parents[1] / "src/no5_pi_gateway" / name).read_bytes()).hexdigest()
                    for name in ("sound.py", "audio.py", "audio_commands.py", "service.py", "configuration.py")},
                "lostSaveLeaseAckWasUnknown": unknown, "lostSaveReadbackReconciledWithoutReplay": True,
                "gatewayObserverStoppedNativeRollbackMs": elapsed, "nativeRollbackReconciledWithoutReplay": True,
                "producerStoppedPreviewNotAppliedBeforeExpiry": True, "producerResumeProcessedOwnedRollback": True,
                "scope": "SYNTHETIC_FILE_PCM_NATIVE_WS; no ALSA, Lab/Pi or Lumina acceptance"}
            (args.output / "result.json").write_text(json.dumps(result, indent=2) + "\n")
            print(json.dumps(result, indent=2))
        finally:
            gate.set(); active = False
            try: await producer
            except (BrokenPipeError, ConnectionError): pass
            await controller.stop()
            if process.stdin and not process.stdin.is_closing(): process.stdin.close()
            try: await asyncio.wait_for(process.wait(), 3)
            except asyncio.TimeoutError: process.terminate(); await process.wait()
            log.close()


if __name__ == "__main__": asyncio.run(main())
