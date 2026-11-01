"""Real adapter/WS framing and durable restart tests; no DSP/audio-block ACK claim."""
import asyncio
import copy
from dataclasses import replace
import json
from pathlib import Path
import tempfile
import time
import unittest
from unittest.mock import patch

from no5_pi_gateway.audio import AudioError, AudioOutcomeUnknown, CamillaDspAdapter, CamillaDspClient, validate_audio_command
from no5_pi_gateway.configuration import GatewayConfig, ConfigurationError
from no5_pi_gateway.equalizer import (BANDS, HEADROOM, NAMES, EqualizerController, EqualizerError,
    EqualizerStore, EqualizerUnknown, provision_equalizer, snapshot, validate_command)
from test_audio import camilla_config, writable_audio_config


def base_config():
    return {"title": "Existing stereo", "devices": {"samplerate": 48000, "chunksize": 1024,
        "capture": {"channels": 2}, "playback": {"channels": 6}},
        "filters": {"foreign-volume": {"type": "Volume", "parameters": {"ramp_time": 300, "fader": "Aux1"}}},
        "mixers": {"stereo": {"channels": {"in": 2, "out": 6},
                              "mapping": [{"dest": 0, "sources": [{"channel": 0, "gain": -3}]}]}},
        "pipeline": [{"type": "Mixer", "name": "stereo"},
                     {"type": "Filter", "channels": [0], "names": ["foreign-volume"]}]}


def with_gains(config, values):
    result = copy.deepcopy(config)
    for name, value in zip(BANDS, values):result["filters"][name]["parameters"]["gain"] = value
    result["filters"][HEADROOM]["parameters"]["gain"] = -sum(max(0, value) for value in values)
    return result


def eq_command(config, values, path="/etc/camilladsp/preset_stereo.yml"):
    return {"type": "equalizer", "bankId": "master", "configGeneration": snapshot(config, path, 1)["configGeneration"],
            "gainsDb": list(values)}


class CamillaSocket:
    def __init__(self):
        self.config = provision_equalizer(base_config());self.path = "/etc/camilladsp/preset_stereo.yml"
        self.state = "Running";self.sent = [];self.reply = None;self.patch_count = 0
        self.pending = None;self.reads_until_apply = 0;self.lose_patch_reply = False
        self.patch_gate = None
        self.before_patch = None;self.after_read = None
        self.faders = [{"volume": -23, "mute": False}] + [{"volume": -index, "mute": False} for index in range(1, 5)]

    async def send_str(self, raw):
        value = json.loads(raw);self.sent.append(value)
        command, argument = (value, None) if isinstance(value, str) else next(iter(value.items()))
        if command == "PatchConfig":
            if self.patch_gate is not None:await self.patch_gate.wait()
            if self.before_patch:self.before_patch()
            self.patch_count += 1
            updated = copy.deepcopy(self.config)
            for name, record in argument["filters"].items():
                updated["filters"][name]["parameters"].update(record["parameters"])
            self.pending = updated
            if self.lose_patch_reply:
                self.config = updated;self.pending = None
                raise asyncio.TimeoutError("sent, reply lost")
            response = {"result": "Ok"}
        else:
            if command == "GetConfigJson":
                if self.pending is not None:
                    if self.reads_until_apply > 0:self.reads_until_apply -= 1
                    else:self.config = self.pending;self.pending = None
                result = json.dumps(self.config)
            elif command == "GetConfigFilePath":result = self.path
            elif command == "GetState":
                result = self.state
                if self.after_read:self.after_read()
            elif command == "GetVersion":result = "4.1.3"
            elif command == "GetFaders":result = self.faders
            elif command == "GetClippedSamples":result = 0
            elif command == "GetConfigTitle":result = self.config["title"]
            else:raise AssertionError("Unexpected Camilla command: " + command)
            response = {"result": "Ok", "value": result}
        self.reply = type("Reply", (), {"type": "TEXT", "data": json.dumps({command: response})})()

    async def receive(self, *, timeout):return self.reply


class Session:
    def __init__(self, socket):self.socket = socket
    async def __aenter__(self):return self
    async def __aexit__(self, *unused):pass
    def ws_connect(self, *args, **kwargs):return SocketContext(self.socket)


class SocketContext:
    def __init__(self, socket):self.socket = socket
    async def __aenter__(self):return self.socket
    async def __aexit__(self, *unused):pass


class EqualizerTopologyTest(unittest.TestCase):
    def test_neutral_provision_is_pure_and_only_adds_owned_stereo_stage(self):
        original = base_config();before = copy.deepcopy(original)
        configured = provision_equalizer(original)
        self.assertEqual(original, before)
        self.assertEqual(configured["devices"], original["devices"])
        self.assertEqual(configured["mixers"], original["mixers"])
        self.assertEqual(configured["filters"]["foreign-volume"], original["filters"]["foreign-volume"])
        self.assertEqual(configured["pipeline"][1:], original["pipeline"])
        self.assertEqual(configured["pipeline"][0]["channels"], [0, 1])
        self.assertEqual(configured["pipeline"][0]["names"], list(NAMES))
        self.assertEqual(snapshot(configured, "profile", 1)["gainsDb"], [0] * 5)
        boosted = with_gains(configured, [4, -6, 2, 0, -1])
        self.assertEqual(provision_equalizer(boosted), boosted)
        self.assertEqual(snapshot(boosted, "profile", 1)["headroomDb"], 6)

    def test_structure_and_foreign_changes_invalidate_generation_own_gains_do_not(self):
        neutral = provision_equalizer(base_config());first = snapshot(neutral, "profile", 1)
        boosted = with_gains(neutral, [4, -6, 2, 0, -1])
        self.assertEqual(snapshot(boosted, "profile", 3)["configGeneration"], first["configGeneration"])
        for changed in ("path", "foreign", "pipeline"):
            candidate = copy.deepcopy(neutral);path = "profile"
            if changed == "path":path = "different-profile"
            if changed == "foreign":candidate["filters"]["foreign-volume"]["parameters"]["ramp_time"] = 301
            if changed == "pipeline":candidate["pipeline"].append({"type": "Filter", "names": ["foreign-volume"], "channels": [1]})
            self.assertNotEqual(snapshot(candidate, path, 1)["configGeneration"], first["configGeneration"])
        bad = copy.deepcopy(neutral);bad["filters"][BANDS[0]]["parameters"]["q"] = 2
        with self.assertRaises(EqualizerError):provision_equalizer(bad)
        bad = copy.deepcopy(neutral);bad["filters"].pop(BANDS[-1])
        with self.assertRaises(EqualizerError):provision_equalizer(bad)
        bad = with_gains(neutral, [4, -6, 0, 0, 0]);bad["filters"][HEADROOM]["parameters"]["gain"] = 0
        with self.assertRaises(EqualizerError):snapshot(bad, "profile", 1)

    def test_only_five_finite_integer_gains_and_exact_typed_command_are_accepted(self):
        good = eq_command(provision_equalizer(base_config()), [0] * 5)
        validate_command(good)
        for invalid in ([0] * 4, [0] * 6, [True, 0, 0, 0, 0], [0.5, 0, 0, 0, 0],
                        [float("nan"), 0, 0, 0, 0], [float("inf"), 0, 0, 0, 0], [7, 0, 0, 0, 0], [10**1000, 0, 0, 0, 0]):
            with self.subTest(invalid=invalid), self.assertRaises(EqualizerError):validate_command({**good, "gainsDb": invalid})
        for invalid in ({**good, "extra": 1}, {**good, "bankId": "arbitrary"}, {**good, "configGeneration": "0" * 63}):
            with self.assertRaises(EqualizerError):validate_command(invalid)
        with self.assertRaises(ConfigurationError):replace(camilla_config(), equalizer_state_file="/etc/camilladsp/root-owned.yml")


class EqualizerExecutionTest(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.directory = tempfile.TemporaryDirectory();self.path = str(Path(self.directory.name) / "master-equalizer.json")
        self.socket = CamillaSocket();self.session = Session(self.socket)
        self.client = CamillaDspClient(camilla_config())
        self.controller = EqualizerController(self.path)
    async def asyncTearDown(self):self.directory.cleanup()

    async def test_one_atomic_owned_patch_then_active_readback_preserves_every_foreign_field(self):
        self.socket.reads_until_apply = 2
        before = copy.deepcopy(self.socket.config);faders = copy.deepcopy(self.socket.faders)
        command = eq_command(before, [4, -6, 2, 0, -1])
        observed = await self.controller.apply(self.client, self.session, command, lambda: True)
        self.assertEqual(observed["verification"], "ACTIVE_CONFIG_READBACK")
        self.assertEqual(observed["gainsDb"], command["gainsDb"]);self.assertEqual(observed["headroomDb"], 6)
        self.assertEqual(self.socket.patch_count, 1);self.assertEqual(self.socket.faders, faders)
        expected = with_gains(before, command["gainsDb"])
        self.assertEqual(self.socket.config, expected)
        sent = next(value["PatchConfig"] for value in self.socket.sent if isinstance(value, dict))
        self.assertEqual(set(sent), {"filters"})
        self.assertTrue(all(set(node) == {"parameters"} and set(node["parameters"]) == {"gain"} for node in sent["filters"].values()))
        self.assertEqual(EqualizerStore(self.path).restore(command["configGeneration"]), tuple(command["gainsDb"]))

    async def test_stale_profile_and_revocation_are_rejected_before_any_effect(self):
        command = eq_command(self.socket.config, [3, 0, 0, 0, 0])
        self.socket.path = "/etc/camilladsp/another.yml"
        with self.assertRaisesRegex(EqualizerError, "generation changed"):
            await self.controller.apply(self.client, self.session, command, lambda: True)
        self.assertEqual(self.socket.patch_count, 0)
        self.socket.path = "/etc/camilladsp/preset_stereo.yml"
        authorized = [True];self.socket.after_read = lambda: authorized.__setitem__(0, False)
        with self.assertRaisesRegex(EqualizerError, "authorization expired"):
            await self.controller.apply(self.client, self.session, command, lambda: authorized[0])
        self.assertEqual(self.socket.patch_count, 0)

    async def test_restart_restores_only_matching_neutral_owned_profile_and_retains_nonzero_live_change(self):
        command = eq_command(self.socket.config, [2, -1, 0, 0, 0]);neutral = copy.deepcopy(self.socket.config)
        await self.controller.apply(self.client, self.session, command, lambda: True)
        self.socket.config = neutral
        rebooted = EqualizerController(self.path)
        restored = await rebooted.observe(self.client, self.session)
        self.assertEqual(restored["gainsDb"], command["gainsDb"]);self.assertEqual(self.socket.patch_count, 2)
        self.socket.config = with_gains(neutral, [-2, 0, 0, 0, 0])
        another = EqualizerController(self.path)
        self.assertEqual((await another.observe(self.client, self.session))["gainsDb"], [-2, 0, 0, 0, 0])
        self.assertEqual(self.socket.patch_count, 2)
        self.socket.path = "/etc/camilladsp/different.yml";self.socket.config = neutral
        self.assertEqual((await another.observe(self.client, self.session))["gainsDb"], [0] * 5)
        self.assertEqual(self.socket.patch_count, 2)

    async def test_unknown_neutral_reset_never_replays_or_restores_previous_boost_after_restart(self):
        boosted = eq_command(self.socket.config, [3, 0, 0, 0, 0])
        await self.controller.apply(self.client, self.session, boosted, lambda: True)
        self.socket.lose_patch_reply = True
        with self.assertRaises(EqualizerUnknown):
            await self.controller.apply(self.client, self.session, {**boosted, "gainsDb": [0] * 5}, lambda: True)
        self.assertEqual(self.socket.patch_count, 2)
        rebooted = EqualizerController(self.path)
        self.socket.lose_patch_reply = False
        self.assertEqual((await rebooted.observe(self.client, self.session))["gainsDb"], [0] * 5)
        self.assertEqual(self.socket.patch_count, 2)
        self.assertIsNone(EqualizerStore(self.path).restore(boosted["configGeneration"]))

    async def test_atomic_stage_failure_has_no_effect_and_commit_failure_stays_restore_blocked(self):
        command = eq_command(self.socket.config, [3, 0, 0, 0, 0])
        with patch("no5_pi_gateway.equalizer.os.replace", side_effect=OSError("disk full")):
            with self.assertRaisesRegex(EqualizerError, "durable admission"):
                await self.controller.apply(self.client, self.session, command, lambda: True)
        self.assertEqual(self.socket.patch_count, 0)
        real_replace = __import__("os").replace;calls = []
        def fail_second(*args):
            calls.append(args)
            if len(calls) == 2:raise OSError("commit disk full")
            return real_replace(*args)
        with patch("no5_pi_gateway.equalizer.os.replace", side_effect=fail_second):
            with self.assertRaises(EqualizerUnknown):await self.controller.apply(self.client, self.session, command, lambda: True)
        self.assertEqual(self.socket.patch_count, 1)
        self.assertIsNone(EqualizerStore(self.path).restore(command["configGeneration"]))
        self.assertEqual(list(Path(self.directory.name).glob(".master-equalizer-*")), [])

    async def test_adapter_capability_revision_cache_and_unknown_use_existing_audio_path(self):
        audio = writable_audio_config(str(Path(self.directory.name) / "unused-media.json"))
        audio = replace(audio, camilla=replace(audio.camilla,
            equalizer_state_file="/var/lib/no5-pi-gateway/master-equalizer.json"))
        adapter = CamillaDspAdapter(GatewayConfig(audio=audio), "gateway-test")
        adapter._client = self.client;adapter._session_factory = lambda: self.session;adapter._equalizer = self.controller
        adapter._latest = await self.client.read_snapshot(self.session)
        self.assertFalse(adapter.payload["capabilities"]["equalizer"])
        await adapter._observe_equalizer(self.session)
        state = adapter.payload
        self.assertTrue(state["capabilities"]["equalizer"])
        body = {"version": 1, "kind": "audio-command", "sessionId": "test", "requestId": "test:1",
                "serverSessionId": "gateway-test", "expectedRevision": state["revision"],
                "command": eq_command(self.socket.config, [2, 0, 0, 0, 0])}
        validate_audio_command(body)
        ack = await adapter.command_authorized(body, lambda: True)
        self.assertEqual(ack["status"], "VERIFIED");self.assertGreater(ack["revision"], body["expectedRevision"])
        self.assertEqual(ack["verification"], "ACTIVE_CONFIG_READBACK")
        self.assertEqual(ack["state"]["dsp"]["equalizer"]["gainsDb"], [2, 0, 0, 0, 0])
        self.assertEqual(await adapter.command(body), ack);self.assertEqual(self.socket.patch_count, 1)
        self.socket.lose_patch_reply = True
        unknown = {**body, "requestId": "test:2", "expectedRevision": adapter.payload["revision"],
                   "command": {**body["command"], "gainsDb": [0] * 5}}
        for _ in range(2):
            with self.assertRaises(AudioOutcomeUnknown):await adapter.command_authorized(unknown, lambda: True)
        self.assertEqual(self.socket.patch_count, 2)
        self.assertTrue(adapter.payload["capabilities"]["faderWrite"])
        self.assertFalse(adapter.payload["capabilities"]["equalizer"])
