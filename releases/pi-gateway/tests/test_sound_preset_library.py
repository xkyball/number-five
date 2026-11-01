"""Discrete owned preset operations through the existing queue/lease protocol."""
import copy
import json
import unittest
import asyncio

import test_sound as fixture
preset = fixture.preset
from no5_pi_gateway.sound import SoundError, SoundStore, SoundUnknown, default_calibration, validate_sound_command


class SoundPresetLibraryTest(unittest.IsolatedAsyncioTestCase):
    asyncSetUp = fixture.SoundControllerTest.asyncSetUp
    asyncTearDown = fixture.SoundControllerTest.asyncTearDown
    command = fixture.SoundControllerTest.command
    send = fixture.SoundControllerTest.send
    async def test_save_as_persists_named_copy_and_loading_preserves_calibration(self):
        await self.send(model=preset(3))
        await self.send("soundSave")
        original = copy.deepcopy(SoundStore(self.path).state["presets"])
        calibration = copy.deepcopy(self.wire.state["calibration"])
        result = await self.send("soundSaveAs", name="Boot · Abend")
        new_id = result["preset"]["id"]
        self.assertNotIn(new_id, original)
        self.assertEqual(result["preset"]["name"], "Boot · Abend")
        self.assertEqual(result["preset"]["eqPoints"], preset(3)["eqPoints"])
        self.assertEqual(result["presetRevision"], 2)
        self.assertEqual(result["calibrationRevision"], 0)
        self.assertIsNone(result["preview"])
        restored = SoundStore(self.path).state
        for identifier, model in original.items(): self.assertEqual(restored["presets"][identifier], model)
        self.assertEqual(restored["presets"][new_id], result["preset"])
        await self.send("soundLoadPreset", presetId="stereo")
        self.assertEqual(self.wire.state["preset"], original["stereo"])
        self.assertEqual(self.wire.state["calibration"], calibration)
        self.assertEqual(self.controller.store.state["bindings"]["config-1"], "stereo")

    async def test_new_copy_without_editor_preview_and_equal_names_keep_distinct_ids(self):
        first = await self.send("soundSaveAs", name="My sound")
        second = await self.send("soundSaveAs", name="My sound")
        self.assertNotEqual(first["preset"]["id"], second["preset"]["id"])
        self.assertEqual(len(second["presetCatalog"]), 2)
        self.assertTrue(second["capabilities"]["presetLibrary"])

    async def test_loading_cannot_discard_dirty_preset_or_change_routing(self):
        await self.send("soundSaveAs", name="Saved")
        identifier = self.wire.state["preset"]["id"]
        await self.send(model=preset(4))
        before = copy.deepcopy(self.wire.state)
        with self.assertRaisesRegex(SoundError,"save or discard"):
            await self.send("soundLoadPreset", presetId=identifier)
        self.assertEqual(self.wire.state, before)
        await self.send("soundDiscard")
        model = copy.deepcopy(self.controller.store.state["presets"][identifier])
        model.update(id="front",routingTemplate="front_stereo_fullrange",room={"amount":0.,"size":.5})
        self.controller.store.state["presets"]["front"] = model
        before = copy.deepcopy(self.wire.state)
        with self.assertRaisesRegex(SoundError,"another routing"):
            await self.send("soundLoadPreset", presetId="front")
        self.assertEqual(self.wire.state, before)

    async def test_full_catalog_and_bad_names_are_rejected_before_audio_apply(self):
        for i in range(64):
            model=preset(); model["id"]=f"saved_{i}"
            self.controller.store.state["presets"][model["id"]]=model
        before=copy.deepcopy(self.wire.state)
        with self.assertRaisesRegex(SoundError,"catalog is full"):
            await self.send("soundSaveAs",name="Overflow")
        self.assertEqual(before,self.wire.state)
        self.assertLess(len(json.dumps(self.controller.snapshot()).encode()),65536)
        for name in ("", " ", "bad\nname", "bad\x85name", "x"*101, "ä"*65):
            with self.assertRaises(SoundError):validate_sound_command(self.command("soundSaveAs",name=name))

    async def test_foreign_revision_and_unknown_id_have_no_effect(self):
        before=copy.deepcopy(self.wire.state)
        with self.assertRaises(SoundError):await self.send("soundSaveAs",name="Stale",expectedPresetRevision=999)
        with self.assertRaises(SoundError):await self.send("soundLoadPreset",presetId="missing")
        self.assertEqual(before,self.wire.state)

    async def test_saving_a_copy_keeps_unsaved_speaker_adjustments_out_of_the_preset(self):
        calibration=default_calibration();calibration["speakers"][4]["manualDelayMs"]=3.
        await self.send(scope="calibration",model=calibration)
        result=await self.send("soundSaveAs",name="Copied sound")
        self.assertEqual(result["calibrationRevision"],0)
        self.assertTrue(result["preview"]["calibrationDirty"])
        self.assertFalse(result["preview"]["presetDirty"])
        self.assertEqual(SoundStore(self.path).state["calibration"],default_calibration())
        self.assertEqual(self.wire.state["calibration"]["delaysMs"][2],3.)
        await self.send("soundDiscard",scope="calibration")
        self.assertEqual(self.wire.state["preset"]["name"],"Copied sound")

    async def test_lost_save_release_does_not_create_a_second_copy_during_reconciliation(self):
        self.wire.lease_mode="drop_after"
        with self.assertRaises(SoundUnknown):await self.send("soundSaveAs",name="Unknown reply")
        self.assertEqual(len(SoundStore(self.path).state["presets"]),1)
        identifier=next(iter(self.controller.store.state["presets"]))
        await self.controller.observe();await self.controller._reconcile_lease_sync()
        self.assertEqual(list(self.controller.store.state["presets"]),[identifier])
        self.assertEqual(len(self.wire.calls),1)


class SoundPresetLibraryRingTest(unittest.IsolatedAsyncioTestCase):
    async def test_named_create_and_select_are_fifo_barriers_and_same_request_is_idempotent(self):
        from no5_pi_gateway.audio_commands import AudioCommands
        from test_audio_commands import Adapter, request
        class SoundAdapter(Adapter):
            async def sound_command_authorized(self, owner, request, valid, *, admitted_monotonic=None):
                self.writes.append(request["command"])
                return {"status":"VERIFIED","state":dict(self.payload),"verification":"AUDIO_BLOCK_READBACK"}
        adapter=SoundAdapter();ring=AudioCommands(adapter)
        common={"previewId":"edit","scope":"preset","configGeneration":"config-1","expectedPresetRevision":0,"expectedCalibrationRevision":0}
        commands=[{**common,"type":"soundSaveAs","name":"A"},
                  {**common,"type":"soundLoadPreset","presetId":"existing"},
                  {**common,"type":"soundSaveAs","name":"B"}]
        try:
            receipts=[ring.submit("owner",request(i+1,c,revision=0),lambda:True) for i,c in enumerate(commands)]
            repeat=ring.submit("owner",request(1,commands[0],revision=0),lambda:True)
            self.assertEqual(receipts[0]["commandId"],repeat["commandId"])
            ring.start()
            for _ in range(100):
                if ring.read("owner",receipts[-1]["commandId"])["status"]=="APPLIED":break
                await asyncio.sleep(.001)
            self.assertEqual(adapter.writes,commands)
            self.assertEqual(ring.read("owner",receipts[-1]["commandId"])["verification"],"AUDIO_BLOCK_READBACK")
        finally:await ring.stop()
