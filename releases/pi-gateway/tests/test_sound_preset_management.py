"""New/discard is a lease preview, deletion and previews use saved identities."""
import base64
import asyncio
import copy
import hashlib
import json
import struct
import unittest
from unittest.mock import patch

import test_sound as fixture
from no5_pi_gateway.sound import SoundController, SoundError, SoundStore, SoundUnknown, eq_response, validate_sound_command


class SoundPresetManagementTest(unittest.IsolatedAsyncioTestCase):
    asyncSetUp = fixture.SoundControllerTest.asyncSetUp
    asyncTearDown = fixture.SoundControllerTest.asyncTearDown
    command = fixture.SoundControllerTest.command
    send = fixture.SoundControllerTest.send

    async def saved(self, name="Original"):
        return (await self.send("soundSaveAs", name=name))["preset"]

    async def test_blank_preview_discard_restores_original_without_library_entry(self):
        original = await self.saved()
        before = copy.deepcopy(SoundStore(self.path).state)
        blank = {**original, "id": "draft_blank", "name": "New", "eqPoints": [], "room": {"amount": 0., "size": .5}}
        await self.send(model=blank)
        self.assertNotIn("draft_blank", self.controller.store.state["presets"])
        self.assertTrue(self.controller.snapshot("helm")["preview"]["presetDirty"])
        await self.send("soundDiscard")
        after = SoundStore(self.path).state
        self.assertEqual(after, before)
        self.assertEqual(self.wire.state["preset"], original)

    async def test_blank_save_is_first_durable_creation_and_does_not_replace_original(self):
        original = await self.saved()
        blank = {**original, "id": "draft_blank", "name": "New", "eqPoints": [], "room": {"amount": 0., "size": .5}}
        await self.send(model=blank)
        await self.send("soundSave")
        state = SoundStore(self.path).state
        self.assertEqual(state["presets"][original["id"]], original)
        self.assertEqual(state["presets"]["draft_blank"], blank)
        self.assertEqual(state["bindings"]["config-1"], "draft_blank")

    async def test_process_loss_and_native_expiry_do_not_publish_new_preset(self):
        original = await self.saved()
        blank = {**original, "id": "draft_blank", "name": "New", "eqPoints": [], "room": {"amount": 0., "size": .5}}
        await self.send(model=blank)
        self.wire.expire()
        restarted = SoundController(self.wire, self.path)
        await restarted.observe()
        await restarted._recover()
        self.assertEqual(self.wire.state["preset"], original)
        self.assertNotIn("draft_blank", restarted.store.state["presets"])
        self.assertIsNone(restarted.store.state["journal"])

    async def test_delete_inactive_is_storage_only_and_removes_all_its_bindings(self):
        old = await self.saved("Old")
        active = await self.saved("Active")
        self.controller.store.state["bindings"]["old-config"] = old["id"]
        before = copy.deepcopy(self.wire.state)
        count = len(self.wire.calls)
        revision = self.controller.store.state["presetRevision"]
        result = await self.send("soundDeletePreset", presetId=old["id"])
        self.assertEqual(self.wire.state, before)
        self.assertEqual(len(self.wire.calls), count)
        self.assertEqual(result["preset"]["id"], active["id"])
        self.assertEqual(result["presetRevision"], revision + 1)
        state = SoundStore(self.path).state
        self.assertNotIn(old["id"], state["presets"])
        self.assertNotIn(old["id"], state["bindings"].values())
        self.assertNotIn(old["id"], result["presetPreviews"])

    async def test_delete_active_applies_remaining_sound_preserving_calibration(self):
        remaining = await self.saved("Remaining")
        active = await self.saved("Active")
        calibration = copy.deepcopy(self.wire.state["calibration"])
        result = await self.send("soundDeletePreset", presetId=active["id"])
        self.assertEqual(result["preset"], remaining)
        self.assertEqual(self.wire.state["calibration"], calibration)
        self.assertEqual(result["calibrationRevision"], 0)
        self.assertIsNone(result["preview"])
        state = SoundStore(self.path).state
        self.assertNotIn(active["id"], state["presets"])
        self.assertEqual(state["bindings"]["config-1"], remaining["id"])
        self.assertIsNone(state["journal"])

    async def test_delete_last_uses_neutral_sound_without_manufacturing_library_rows(self):
        active = await self.saved()
        result = await self.send("soundDeletePreset", presetId=active["id"])
        self.assertEqual(result["preset"]["eqPoints"], [])
        self.assertEqual(result["preset"]["room"]["amount"], 0)
        self.assertEqual(result["preset"]["eqEnabled"], active["eqEnabled"])
        self.assertEqual(result["preset"]["reserveDb"], active["reserveDb"])
        self.assertEqual(result["presetCatalog"], [])
        self.assertEqual(result["presetPreviews"], {})
        durable = SoundStore(self.path).state
        self.assertEqual(durable["presets"], {})
        self.assertEqual(durable["bindings"]["config-1"], result["preset"]["id"])
        self.assertEqual(durable["fallbacks"]["stereo_4_1"], result["preset"])

    async def test_delete_rejects_stale_unknown_foreign_owner_and_dirty_drafts(self):
        active = await self.saved()
        before = copy.deepcopy(self.wire.state)
        for args in ({"presetId": "missing"}, {"presetId": active["id"], "expectedPresetRevision": 900}):
            with self.assertRaises(SoundError): await self.send("soundDeletePreset", **args)
        self.assertEqual(self.wire.state, before)
        await self.send(model={**active, "room": {"amount": .3, "size": .5}})
        before = copy.deepcopy(self.wire.state)
        with self.assertRaisesRegex(SoundError, "save or discard"):
            await self.send("soundDeletePreset", presetId=active["id"])
        with self.assertRaises(SoundError):
            await self.controller.command("other-owner", self.command("soundDeletePreset", presetId=active["id"]), lambda: True)
        self.assertEqual(self.wire.state, before)
        for scope in ("calibration", "invalid"):
            with self.assertRaises(SoundError): validate_sound_command(self.command("soundDeletePreset", presetId=active["id"], scope=scope))

    async def test_last_deletion_restores_neutral_enable_and_reserve_after_gateway_and_dsp_restart(self):
        await self.send(model=fixture.preset(3, reserve=18))
        await self.send("soundSave")
        result = await self.send("soundDeletePreset", presetId="stereo")
        neutral = copy.deepcopy(result["preset"])
        self.wire.state = fixture.dsp_state()
        self.wire.state["sourceGeneration"] = "restarted-dsp"
        restarted = SoundController(self.wire, self.path)
        await restarted.observe(); await restarted._restore()
        self.assertEqual(self.wire.state["preset"], neutral)
        self.assertTrue(self.wire.state["preset"]["eqEnabled"])
        self.assertEqual(self.wire.state["headroomDb"], 18)
        self.assertEqual(restarted.snapshot("helm")["presetCatalog"], [])
        self.assertEqual(SoundStore(self.path).state["calibrationRevision"], 0)

    async def test_schema_two_migration_is_lossless_without_startup_rewrite_and_fallbacks_are_typed(self):
        await self.saved()
        old = copy.deepcopy(SoundStore(self.path).state)
        old.pop("fallbacks"); old["schema"] = "no5.sound-state/2"
        raw = json.dumps(old).encode()
        self.path.write_bytes(raw)
        loaded = SoundStore(self.path)
        self.assertEqual(self.path.read_bytes(), raw)
        self.assertEqual(loaded.state, {**old, "schema": "no5.sound-state/3", "fallbacks": {}})
        bad = copy.deepcopy(loaded.state)
        bad["fallbacks"]["stereo_4_1"] = fixture.preset(3)
        with self.assertRaises(SoundError): SoundStore.validate(bad)
        bad = copy.deepcopy(loaded.state)
        bad["fallbacks"]["arbitrary"] = fixture.preset()
        with self.assertRaises(SoundError): SoundStore.validate(bad)
        future = copy.deepcopy(loaded.state); future["schema"] = "no5.sound-state/999"
        self.path.write_text(json.dumps(future))
        with self.assertRaises(SoundError): SoundStore(self.path)

    async def test_lost_delete_release_reconciles_without_recreating_deleted_entry(self):
        active = await self.saved()
        self.wire.lease_mode = "drop_after"
        with self.assertRaises(SoundUnknown): await self.send("soundDeletePreset", presetId=active["id"])
        count = len(self.wire.calls)
        restarted = SoundController(self.wire, self.path)
        await restarted.observe()
        self.assertTrue(await restarted._reconcile_lease_sync())
        self.assertNotIn(active["id"], restarted.store.state["presets"])
        self.assertEqual(len(self.wire.calls), count)
        self.assertIsNone(restarted.store.state["journal"])

    async def test_rejected_active_delete_preserves_library_and_unknown_apply_is_not_replayed(self):
        active = await self.saved()
        before = copy.deepcopy(SoundStore(self.path).state)
        self.wire.mode = "reject_budget"
        with self.assertRaises(SoundError): await self.send("soundDeletePreset", presetId=active["id"])
        self.assertEqual(SoundStore(self.path).state, before)
        self.assertEqual(self.wire.state["preset"], active)
        self.wire.mode = "unknown"
        with self.assertRaises(SoundUnknown): await self.send("soundDeletePreset", presetId=active["id"])
        self.assertIn(active["id"], SoundStore(self.path).state["presets"])
        self.wire.expire()
        count = len(self.wire.calls)
        restarted = SoundController(self.wire, self.path)
        await restarted.observe(); await restarted._recover()
        self.assertEqual(len(self.wire.calls), count)
        self.assertEqual(self.wire.state["preset"], active)
        self.assertIn(active["id"], restarted.store.state["presets"])

    async def test_preview_is_saved_curve_and_changes_only_after_save(self):
        await self.send(model=fixture.preset(6))
        await self.send("soundSave")
        snap = self.controller.snapshot("owner")
        item = snap["presetPreviews"]["stereo"]
        raw = struct.unpack("48b", base64.b64decode(item["db4i8"], validate=True))
        expected, _ = eq_response(fixture.preset(6)["eqPoints"], count=48, dense_reserve=False)
        self.assertEqual(list(raw), [round(max(-18, min(18, db)) * 4) for db in expected["gainsDb"]])
        canonical = json.dumps(fixture.preset(6), sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode()
        self.assertEqual(base64.b64decode(item["sha256b64"], validate=True).hex(), hashlib.sha256(canonical).hexdigest())
        await self.send(model=fixture.preset(-3))
        self.assertEqual(self.controller.snapshot("owner")["presetPreviews"]["stereo"], item)
        await self.send("soundSave")
        self.assertNotEqual(self.controller.snapshot("owner")["presetPreviews"]["stereo"], item)
        # Reading the list must never perform filter preparation.
        with patch("no5_pi_gateway.sound.eq_response", side_effect=AssertionError("not in snapshot")):
            self.controller.snapshot("owner")

    async def test_full_catalog_long_unicode_names_and_curves_stays_inside_app_wire_limit(self):
        for index in range(64):
            model = fixture.preset(-12)
            model.update(id=f"p{index:02}" + "x" * 61, name="ä" * 64, reserveDb=17.12345678912345)
            model["room"]["size"] = .12345678912345678
            model["eqPoints"] = [{**model["eqPoints"][0], "id": f"point{n:02}" + "x" * 57, "frequencyHz": 20 * 1000 ** (n / 15)} for n in range(16)]
            self.controller.store.state["presets"][model["id"]] = model
        loaded = copy.deepcopy(model)
        loaded["id"] = "z" * 64
        self.wire.state["preset"] = loaded
        self.wire.state["headroomDb"] = loaded["reserveDb"]
        self.wire.state["configGeneration"] = "c" * 128
        self.wire.state["sourceGeneration"] = "s" * 128
        calibration = self.controller.store.state["calibration"]
        calibration["listenerM"] = {axis: 49.12345678912345 for axis in ("x", "y", "z")}
        for speaker in calibration["speakers"]:
            speaker["positionM"] = {axis: -49.12345678912345 for axis in ("x", "y", "z")}
        await self.controller.observe()
        self.controller.preview = {"owner": "owner", "id": "p"*100,
            "deadline": self.controller.monotonic()+5, "preset": loaded,
            "calibration": copy.deepcopy(calibration), "presetDirty": True, "calibrationDirty": True}
        self.controller.routing_profiles = {routing: str(index)+"x"*99
                                           for index, routing in enumerate(("front_stereo_fullrange", "stereo_4_1", "immersive_4_1"))}
        result = self.controller.snapshot("owner")
        self.assertEqual(len(result["presetPreviews"]), 64)
        self.__class__.max_payload_bytes = len(json.dumps(result).encode())
        self.assertLessEqual(self.max_payload_bytes, 65536)
        self.assertTrue(result["capabilities"]["presetManagement"])


class SoundPresetManagementBoundaryTest(unittest.IsolatedAsyncioTestCase):
    asyncSetUp = fixture.SoundBoundaryTest.asyncSetUp
    asyncTearDown = fixture.SoundBoundaryTest.asyncTearDown

    async def test_authenticated_http_delete_has_distinct_receipt_no_fader_change_and_idempotency(self):
        async def send(sequence, kind, **fields):
            audio = self.audio.payload
            body = {"version": 1, "kind": "audio-command", "sessionId": "manage", "requestId": f"manage:{sequence}",
                    "serverSessionId": audio["serverSessionId"], "expectedRevision": audio["revision"],
                    "command": {"type": kind, "previewId": "edit", "scope": "preset", "configGeneration": "config-1",
                                "expectedPresetRevision": self.sound.store.state["presetRevision"],
                                "expectedCalibrationRevision": self.sound.store.state["calibrationRevision"], **fields}}
            reply = await self.client.post("/v1/audio/commands", json=body, headers=self.headers)
            self.assertEqual(reply.status, 202)
            receipt = await reply.json()
            for _ in range(100):
                result = await (await self.client.get("/v1/audio/commands/" + receipt["commandId"], headers=self.headers)).json()
                if result["status"] not in {"ACCEPTED", "RUNNING"}: break
                await asyncio.sleep(.005)
            self.assertEqual(result["status"], "APPLIED")
            return body, receipt, result
        await send(1, "soundSaveAs", name="First")
        first = self.wire.state["preset"]["id"]
        await send(2, "soundSaveAs", name="Second")
        revision = self.audio.payload["revision"]
        count = len(self.wire.calls)
        body, receipt, result = await send(3, "soundDeletePreset", presetId=first)
        self.assertEqual(result["verification"], "SOUND_STATE_COMMIT")
        self.assertEqual(self.audio.payload["revision"], revision)
        self.assertEqual(len(self.wire.calls), count)
        repeated = await (await self.client.post("/v1/audio/commands", json=body, headers=self.headers)).json()
        self.assertEqual(repeated["commandId"], receipt["commandId"])
        snapshot = await (await self.client.get("/v1/audio/sound", headers=self.headers)).json()
        self.assertNotIn(first, snapshot["presetPreviews"])
        self.assertEqual(len(snapshot["presetCatalog"]), 1)
        self.assertEqual(self.fake.writes, [])
