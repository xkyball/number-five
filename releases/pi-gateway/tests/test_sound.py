"""Sound ownership, digital geometry and real HTTP/WS boundary checks.

The fake processing owner models PREPARED separately from an applied audio block;
these tests never claim to measure acoustic alignment or native DSP performance.
"""
import asyncio
import copy
import hashlib
import json
import math
from pathlib import Path
import tempfile
import time
import unittest

from no5_pi_gateway.sound import (SoundController, SoundError, SoundRejected, SoundUnknown, SoundStore,
    default_calibration, effective_calibration, eq_response, validate_calibration,
    validate_dsp_state, validate_preset, validate_sound_command, validate_spectrum, EQ_POLICY, native_sound_value_matches)


def preset(gain=0, *, routing="stereo_4_1", enabled=None, reserve=12.):
    return {"id": "stereo", "name": "Stereo", "routingTemplate": routing,
            "eqEnabled": gain != 0 if enabled is None else enabled, "reserveDb": reserve,
            "eqPoints": [{"id": "bass", "frequencyHz": 1000, "gainDb": gain, "q": 1., "widthLocked": False}],
            "room": {"amount": 0., "size": .5}}


def dsp_state():
    return {"schema": "no5.camilla-sound/2", "eqPolicy":dict(EQ_POLICY), "configGeneration": "config-1", "sourceGeneration": "source-1",
            "appliedRevision": 0, "preparedRevision": 0, "sampleRateHz": 48000, "routingTemplate": "stereo_4_1",
            "preset": preset(), "calibration": {"delaysMs": [0.] * 6, "gainsDb": [0.] * 6}, "headroomDb": 0.,
            "verification": "PROCESSED_BLOCK", "transitionMs": 50, "previewLease": None, "lastRollback": None}


def spectrum(sequence=1):
    return {"schema": "no5.audio-spectrum/1", "sequence": sequence, "sourceGeneration": "source-1",
            "samplePosition": 8192, "sampleTimeNs": 10_000_000_000_000_000, "timebase": "CAPTURE_MONOTONIC_NS",
            "sampleRateHz": 48000, "fftSize": 8192, "window": "HANN", "tap": "POST_EQ_PRE_ROOM",
            "frequenciesHz": [20., 1000., 20000.], "binsDbfs": [-80., -12., -90.], "peaksDbfs": [-6., -6.], "overRangeSamples": 0}


class Wire:
    def __init__(self):
        self.state = dsp_state(); self.calls = []; self.mode = "apply"; self.frame = spectrum(); self.enables = []
        self.block = None; self.lease_calls = []; self.lease_mode = "apply"
    async def read(self): return validate_dsp_state(copy.deepcopy(self.state))
    async def apply(self, argument):
        self.calls.append(copy.deepcopy(argument))
        if argument["expectedConfigGeneration"] != self.state["configGeneration"] or argument["expectedAppliedRevision"] != self.state["appliedRevision"]:
            raise SoundError("CAS rejected")
        if argument["expectedSourceGeneration"] != self.state["sourceGeneration"]: raise SoundError("source changed")
        assert 1 <= argument["validForMs"] <= 5000
        if self.mode == "reject_budget": raise SoundRejected("EQ_COMBINED_GAIN_LIMIT")
        lease = copy.deepcopy(argument["previewLease"])
        if lease is not None: lease.pop("validForMs")
        self.state["previewLease"] = lease
        self.state["preparedRevision"] = argument["revision"]
        if self.block: await self.block.wait()
        if self.mode != "prepare": self.complete(argument)
        if self.mode == "unknown": raise ConnectionError("lost reply")
        return {"status": "PREPARED", "revision": argument["revision"], "headroomDb": self.state["headroomDb"]}
    def complete(self, argument=None):
        argument = argument or self.calls[-1]
        self.state.update(appliedRevision=argument["revision"], preset=copy.deepcopy(argument["preset"]),
                          calibration=copy.deepcopy(argument["calibration"]),
                          headroomDb=argument["preset"]["reserveDb"] if argument["preset"]["eqEnabled"] else 0.)
    async def lease(self, argument):
        self.lease_calls.append(copy.deepcopy(argument))
        current = self.state["previewLease"]
        if (current is None or current["id"] != argument["id"] or self.state["configGeneration"] != argument["expectedConfigGeneration"] or
                self.state["sourceGeneration"] != argument["expectedSourceGeneration"] or self.state["appliedRevision"] != argument["expectedAppliedRevision"] or
                self.state["preparedRevision"] != self.state["appliedRevision"]):
            raise SoundError("lease CAS rejected")
        if self.lease_mode == "drop_before": raise ConnectionError("lease send outcome unknown")
        release = argument["validForMs"] == 0
        self.state["previewLease"] = None if release else {key: copy.deepcopy(argument[key]) for key in ("id", "fallbackPreset", "fallbackCalibration")}
        if self.lease_mode == "drop_after": raise ConnectionError("lease acknowledgment lost")
        return {"status": "RELEASED" if release else "RENEWED", "id": argument["id"], "appliedRevision": self.state["appliedRevision"]}
    def expire(self):
        lease = self.state["previewLease"]
        assert lease is not None
        before = self.state["preparedRevision"]; target = before + 1
        self.state.update(preset=copy.deepcopy(lease["fallbackPreset"]), calibration=copy.deepcopy(lease["fallbackCalibration"]),
                          preparedRevision=target, appliedRevision=target, previewLease=None,
                          headroomDb=lease["fallbackPreset"]["reserveDb"] if lease["fallbackPreset"]["eqEnabled"] else 0.,
                          lastRollback={"leaseId": lease["id"], "fromRevision": before, "toRevision": target})
    async def spectrum(self): return validate_spectrum(copy.deepcopy(self.frame))
    async def spectrum_enabled(self, enabled): self.enables.append(enabled); return {"enabled": enabled}
    async def close(self): pass


class NativeSoundReadbackTest(unittest.TestCase):
    def test_only_one_finite_representable_step_with_exact_structure(self):
        value = 1.9816084653139114; adjacent = math.nextafter(value, 0.)
        self.assertEqual(adjacent, 1.9816084653139112)
        self.assertTrue(native_sound_value_matches(adjacent, value))
        self.assertTrue(native_sound_value_matches(value, adjacent))
        for observed, requested in [(math.nextafter(adjacent,0.),value),(0.,math.nextafter(0.,1.)),
                (math.inf,math.inf),(math.nan,math.nan),(True,1),([1.,2.],[2.,1.]),({"x":1.},{"y":1.}),
                ({"x":1.,"extra":2.},{"x":1.}),({"id":"other","gainDb":adjacent},{"id":"p","gainDb":value})]:
            self.assertFalse(native_sound_value_matches(observed,requested))
        self.assertTrue(native_sound_value_matches({"id":"p","gains":[adjacent]}, {"id":"p","gains":[value]}))


class SoundModelsTest(unittest.TestCase):
    def test_explicit_enable_and_finite_chosen_reserve_are_required(self):
        for key in ("eqEnabled", "reserveDb"):
            value=preset(2);del value[key]
            with self.assertRaises(SoundError):validate_preset(value)
        for key,bad in [("eqEnabled",1),("eqEnabled",None),("reserveDb",True),
                        ("reserveDb",5.99),("reserveDb",18.01),("reserveDb",float("nan"))]:
            value=preset(2);value[key]=bad
            with self.assertRaises(SoundError):validate_preset(value)
        for reserve in (6.,12.,18.):self.assertEqual(validate_preset(preset(2,reserve=reserve))["reserveDb"],reserve)

    def test_only_positive_visible_bands_must_not_overlap_when_enabled(self):
        value=preset(4);first=value["eqPoints"][0]
        value["eqPoints"].append({**first,"id":"second","frequencyHz":1100.})
        with self.assertRaisesRegex(SoundError,"EQ_POSITIVE_BANDS_OVERLAP"):validate_preset(value)
        value["eqEnabled"]=False
        self.assertEqual(validate_preset(value)["eqPoints"],value["eqPoints"])
        value["eqEnabled"]=True;value["eqPoints"][1]["gainDb"]=-12.
        validate_preset(value)
        # Geometric Q edges may touch exactly; only an actual overlap rejects.
        value["eqPoints"][1].update(gainDb=4.,frequencyHz=1000*(3+math.sqrt(5))/2)
        validate_preset(value)
        value["eqPoints"][1]["frequencyHz"]-=.01
        with self.assertRaises(SoundError):validate_preset(value)

    def test_dsp_policy_and_fixed_reserve_readback_cannot_be_guessed(self):
        for mutation in [lambda v:v.update(schema="no5.camilla-sound/1"),
                         lambda v:v.pop("eqPolicy"),
                         lambda v:v["eqPolicy"].update(positiveBandsMustNotOverlap=1),
                         lambda v:v["eqPolicy"].update(maxReserveDb=24),
                         lambda v:v.update(headroomDb=12.)]:
            value=dsp_state();mutation(value)
            with self.assertRaises(SoundError):validate_dsp_state(value)

    def test_strict_bounded_points_and_front_only_route(self):
        value = preset(12); validate_preset(value)
        value["eqPoints"] *= 17
        with self.assertRaises(SoundError): validate_preset(value)
        value = preset(); value["eqPoints"][0]["gainDb"] = float("nan")
        with self.assertRaises(SoundError): validate_preset(value)
        value = preset(routing="front_stereo_fullrange"); value["room"]["amount"] = .1
        with self.assertRaises(SoundError): validate_preset(value)
        value = preset(); value["arbitraryYaml"] = "devices"
        with self.assertRaises(SoundError): validate_preset(value)
    def test_geometry_has_no_invented_measurements_and_pairs_sub(self):
        value = default_calibration(); result = effective_calibration(value)
        self.assertEqual(result["delaysMs"], [0.] * 6); self.assertIsNone(result["dimensions"])
        value["geometryEnabled"] = True
        with self.assertRaises(SoundError): validate_calibration(value)
        value["listenerM"] = {"x": 0., "y": 0., "z": None}
        for index, speaker in enumerate(value["speakers"]): speaker["positionM"] = {"x": index + 1., "y": 0., "z": None}
        validate_calibration(value); result = effective_calibration(value)
        self.assertTrue(result["approximate"]); self.assertEqual(result["dimensions"], 2)
        self.assertAlmostEqual(result["delaysMs"][0], 4 / 343 * 1000)
        self.assertAlmostEqual(result["gainsDb"][0], 20 * math.log10(1 / 5))
        self.assertEqual(result["delaysMs"][2], result["delaysMs"][3]); self.assertEqual(result["gainsDb"][2], result["gainsDb"][3])
        value["speakers"][0]["manualDelayMs"] = 50
        with self.assertRaises(SoundError): validate_calibration(value)
    def test_response_uses_peaking_transfer_and_combined_headroom(self):
        point = preset(6)["eqPoints"][0]
        response, reserve = eq_response([point, {**point, "id": "second"}])
        self.assertAlmostEqual(reserve, 12., places=4)
        index = min(range(len(response["frequenciesHz"])), key=lambda i: abs(response["frequenciesHz"][i] - 1000))
        self.assertGreater(response["gainsDb"][index], 11.99)
        self.assertLess(response["gainsDb"][0], .01)
        self.assertEqual(eq_response(preset()["eqPoints"])[1], 0)
    def test_dsp_requires_processed_block_verification_and_exact_chosen_reserve(self):
        state = dsp_state(); state["preset"] = preset(2,reserve=18);state["headroomDb"] = 18.
        validate_dsp_state(state)
        state["headroomDb"] = 18.05
        with self.assertRaises(SoundError): validate_dsp_state(state)
        state["headroomDb"] = 18.
        del state["verification"]
        with self.assertRaises(SoundError): validate_dsp_state(state)

    def test_spectrum_keeps_original_clock_and_rejects_wrong_tap_or_unbounded_data(self):
        value = spectrum(); self.assertEqual(validate_spectrum(value)["sampleTimeNs"], value["sampleTimeNs"])
        value["tap"] = "OUTPUT"
        with self.assertRaises(SoundError): validate_spectrum(value)
        value = spectrum(); value["frequenciesHz"] = [20.] * 257
        with self.assertRaises(SoundError): validate_spectrum(value)


class SoundControllerTest(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.path = Path(self.temp.name) / "sound.json"
        self.wire = Wire(); self.controller = SoundController(self.wire, self.path)
        await self.controller.observe()
    async def asyncTearDown(self): self.temp.cleanup()
    def command(self, kind="soundPreview", scope="preset", model=None, **changes):
        result = {"type": kind, "previewId": "edit", "configGeneration": "config-1"}
        if kind != "soundKeepalive":
            result.update(scope=scope, expectedPresetRevision=self.controller.store.state["presetRevision"],
                          expectedCalibrationRevision=self.controller.store.state["calibrationRevision"])
        if kind == "soundPreview": result["model"] = model if model is not None else preset(2)
        result.update(changes); return result
    async def send(self, *args, owner="helm", **kwargs):
        return await self.controller.command(owner, self.command(*args, **kwargs), lambda: True)
    async def test_adjacent_native_float_can_preview_save_and_release_without_rounding_saved_model(self):
        requested = preset(1.9816084653139114)
        complete = self.wire.complete
        def rounded(argument=None):
            complete(argument)
            self.wire.state["preset"]["eqPoints"][0]["gainDb"] = 1.9816084653139112
        self.wire.complete = rounded
        await self.send(model=requested)
        await self.send("soundSave")
        self.assertEqual(self.controller.store.state["presets"][requested["id"]], requested)
        self.assertIsNone(self.controller.store.state["journal"])
        self.assertIsNone(self.wire.state["previewLease"])
        self.assertEqual(len(self.wire.calls), 1)

    async def test_two_step_or_structurally_changed_readback_is_unknown_without_replay(self):
        requested = preset(1.9816084653139114)
        complete = self.wire.complete
        def changed(argument=None):
            complete(argument)
            self.wire.state["preset"]["eqPoints"][0]["gainDb"] = math.nextafter(math.nextafter(requested["eqPoints"][0]["gainDb"], 0.), 0.)
        self.wire.complete = changed
        with self.assertRaises(SoundUnknown): await self.send(model=requested)
        self.assertEqual(len(self.wire.calls), 1)
        self.assertNotEqual(self.controller.store.state["presets"].get(requested["id"]), requested)

    async def test_drag_keeps_reserve_and_off_bypasses_without_erasing_curve_or_room(self):
        value=preset(2,reserve=9.);value["room"]={"amount":.3,"size":.8}
        await self.send(model=value);self.assertEqual(self.wire.state["headroomDb"],9.)
        value["eqPoints"][0]["gainDb"]=4.
        await self.send(model=value);self.assertEqual(self.wire.state["headroomDb"],9.)
        original=copy.deepcopy(value);value["eqEnabled"]=False
        await self.send(model=value)
        self.assertEqual(self.wire.state["headroomDb"],0.)
        self.assertEqual(self.wire.state["preset"]["eqPoints"],original["eqPoints"])
        self.assertEqual(self.wire.state["preset"]["room"],original["room"])
        self.assertEqual(self.wire.state["calibration"],dsp_state()["calibration"])
        await self.send("soundSave")
        saved=SoundStore(self.path).state["presets"][value["id"]]
        self.assertFalse(saved["eqEnabled"]);self.assertEqual(saved["reserveDb"],9.)
        value["eqEnabled"]=True;value["eqPoints"][0]["gainDb"]=0.
        await self.send(model=value)
        self.assertEqual(self.wire.state["headroomDb"],9.,"Enabled neutral curve still uses chosen reserve")

    async def test_native_budget_rejection_is_no_effect_and_preserves_owned_baseline(self):
        initial=copy.deepcopy(self.wire.state);self.wire.mode="reject_budget"
        with self.assertRaisesRegex(SoundRejected,"EQ_COMBINED_GAIN_LIMIT"):
            await self.send(model=preset(8,reserve=6.))
        self.assertEqual(self.wire.state,initial);self.assertIsNone(self.controller.store.state["journal"])
        self.assertEqual(len(self.wire.calls),1)
    async def test_save_directory_fsync_outcome_unknown_preserves_observed_saved_intent(self):
        await self.send(model=preset(2))
        original = self.controller.store.replace
        def replaced_but_ack_lost(value):
            original(value)
            raise OSError("injected directory durability acknowledgment failure")
        self.controller.store.replace = replaced_but_ack_lost
        with self.assertRaises(SoundUnknown): await self.send("soundSave")
        self.controller.store.replace = original
        self.assertEqual(self.controller.store.state["presetRevision"], 1)
        self.assertEqual(self.controller.store.state["journal"]["leaseSync"], "RELEASED")
        self.assertEqual(self.wire.lease_calls, [])
        self.wire.expire(); await self.controller._recover()
        self.assertEqual(self.wire.state["preset"], preset(2))
        self.assertEqual(SoundStore(self.path).state["presetRevision"], 1)

    async def test_native_target_carries_source_deadline_and_atomic_fallback(self):
        await self.send()
        argument = self.wire.calls[0]
        self.assertEqual(argument["expectedSourceGeneration"], "source-1")
        self.assertTrue(1 <= argument["validForMs"] <= 5000)
        self.assertEqual(argument["previewLease"]["fallbackPreset"], preset())
        self.assertEqual(argument["previewLease"]["id"], self.controller.store.state["journal"]["leaseId"])
        await self.send("soundKeepalive")
        self.assertEqual(len(self.wire.calls), 1)
        self.assertEqual(self.wire.lease_calls[-1]["validForMs"] > 0, True)

    async def test_save_lost_native_release_ack_is_unknown_then_readback_reconciled(self):
        await self.send(model=preset(2))
        self.wire.lease_mode = "drop_after"
        with self.assertRaises(SoundUnknown): await self.send("soundSave")
        self.assertEqual(self.controller.store.state["presetRevision"], 1)
        self.assertEqual(self.controller.store.state["journal"]["leaseSync"], "RELEASED")
        self.assertIsNone(self.wire.state["previewLease"])
        await self.controller.observe(); await self.controller._reconcile_lease_sync()
        self.assertIsNone(self.controller.store.state["journal"])
        self.assertIsNone(self.controller.preview)
        self.assertEqual(len(self.wire.calls), 1); self.assertEqual(len(self.wire.lease_calls), 1)

    async def test_durable_save_lease_not_delivered_then_owned_native_rollback_recovers_saved_goal(self):
        await self.send(model=preset(2))
        self.wire.lease_mode = "drop_before"
        with self.assertRaises(SoundUnknown): await self.send("soundSave")
        self.wire.expire()
        self.assertEqual(self.wire.state["preset"], preset())
        self.wire.lease_mode = "apply"
        await self.controller._recover()
        self.assertEqual(self.wire.state["preset"], preset(2))
        self.assertIsNone(self.controller.store.state["journal"])
        self.assertEqual(len(self.wire.calls), 2)
        self.assertIsNone(self.wire.calls[-1]["previewLease"])

    async def test_scoped_save_rebinds_native_fallback_without_saving_other_scope(self):
        await self.send(model=preset(2))
        calibration = default_calibration(); calibration["speakers"][4]["manualDelayMs"] = 3.
        await self.send(scope="calibration", model=calibration)
        await self.send("soundSave", "preset")
        lease = self.wire.state["previewLease"]
        self.assertEqual(lease["fallbackPreset"], preset(2))
        self.assertEqual(lease["fallbackCalibration"]["delaysMs"], [0.] * 6)
        self.wire.expire(); await self.controller._recover()
        self.assertEqual(self.wire.state["preset"], preset(2))
        self.assertEqual(self.wire.state["calibration"]["delaysMs"], [0.] * 6)
        self.assertIsNone(self.controller.store.state["journal"])
        self.assertEqual(len(self.wire.calls), 2)

    async def test_native_expiry_of_unprocessed_target_never_replays_stale_preview(self):
        self.wire.mode = "prepare"
        with self.assertRaises(SoundUnknown): await self.send(model=preset(2))
        self.wire.expire(); self.wire.mode = "apply"
        await self.controller._recover()
        self.assertEqual(self.wire.state["preset"], preset())
        self.assertEqual(len(self.wire.calls), 1)
        self.assertIsNone(self.controller.store.state["journal"])

    async def test_foreign_write_after_native_rollback_is_never_replaced(self):
        await self.send(model=preset(2)); self.wire.lease_mode = "drop_before"
        with self.assertRaises(SoundUnknown): await self.send("soundSave")
        self.wire.expire()
        self.wire.state.update(preset=preset(9), headroomDb=12., appliedRevision=3, preparedRevision=3)
        await self.controller._recover()
        self.assertEqual(self.wire.state["preset"], preset(9)); self.assertEqual(len(self.wire.calls), 1)

    async def test_preview_replace_then_expire_rolls_back_original_not_intermediate(self):
        await self.send(model=preset(1)); await self.send(model=preset(2))
        self.assertEqual(self.wire.state["preset"]["eqPoints"][0]["gainDb"], 2)
        await self.controller._recover()
        self.assertEqual(self.wire.state["preset"], preset())
        self.assertEqual(len(self.wire.calls), 3); self.assertIsNone(self.controller.store.state["journal"])
        self.assertNotIn("helm", self.path.read_text())
        self.assertEqual(self.path.stat().st_mode & 0o777, 0o600)
    async def test_separate_scoped_save_does_not_save_or_undo_other_preview(self):
        await self.send(model=preset(2))
        calibration = default_calibration(); calibration["speakers"][4]["manualDelayMs"] = 3
        await self.send(scope="calibration", model=calibration)
        state = await self.send("soundSave", "preset")
        self.assertEqual(state["presetRevision"], 1); self.assertEqual(state["calibrationRevision"], 0)
        self.assertFalse(state["preview"]["presetDirty"]); self.assertTrue(state["preview"]["calibrationDirty"])
        await self.send("soundDiscard", "calibration")
        self.assertEqual(self.wire.state["preset"], preset(2)); self.assertEqual(self.wire.state["calibration"]["delaysMs"], [0.] * 6)
        durable = SoundStore(self.path)
        self.assertEqual(durable.state["presets"]["stereo"], preset(2)); self.assertEqual(durable.state["calibration"], default_calibration())
    async def test_foreign_preview_owner_revision_and_configuration_are_rejected(self):
        await self.send()
        with self.assertRaises(SoundError): await self.send(owner="other")
        with self.assertRaises(SoundError): await self.send(expectedPresetRevision=99)
        with self.assertRaises(SoundError): await self.send(configGeneration="foreign")
        self.assertEqual(len(self.wire.calls), 1)
        self.wire.state["appliedRevision"] += 1; self.wire.state["preparedRevision"] += 1
        self.wire.state["preset"] = preset(9)
        with self.assertRaises(SoundError): await self.send("soundDiscard")
        await self.controller._recover()
        self.assertEqual(self.wire.state["preset"], preset(9)); self.assertEqual(len(self.wire.calls), 1)
        self.assertEqual(self.controller.diagnostics[-1]["reason"], "PREVIEW_FOREIGN_STATE_PRESERVED")
    async def test_unknown_send_is_never_repeated_and_journal_reconciles_after_restart(self):
        self.wire.mode = "unknown"
        with self.assertRaises(SoundUnknown): await self.send()
        self.assertEqual(len(self.wire.calls), 1)
        recovered = SoundController(self.wire, self.path)
        self.wire.mode = "apply"
        await recovered._recover()
        self.assertEqual(len(self.wire.calls), 2)
        self.assertEqual(self.wire.state["preset"], preset()); self.assertIsNone(recovered.store.state["journal"])
    async def test_preexisting_foreign_preset_is_rollback_basis_not_older_saved_values(self):
        await self.send(model=preset(2)); await self.send("soundSave")
        self.wire.state["preset"] = preset(7); self.wire.state["appliedRevision"] += 1; self.wire.state["preparedRevision"] += 1
        await self.send(model=preset(8)); await self.controller._recover()
        self.assertEqual(self.wire.state["preset"], preset(7))
        self.assertEqual(self.controller.store.state["presets"]["stereo"], preset(2))

    async def test_foreign_calibration_is_observed_and_not_overwritten_by_preset_edit(self):
        self.wire.state["calibration"]["delaysMs"][2:4] = [4., 4.]
        await self.controller.observe()
        state = self.controller.snapshot("helm")
        self.assertFalse(state["writable"])
        self.assertEqual(state["effectiveCalibration"]["delaysMs"][2:4], [4., 4.])
        with self.assertRaises(SoundError): await self.send(model=preset(2))
        self.assertEqual(self.wire.calls, [])

    async def test_delayed_activity_cannot_restart_five_second_preview(self):
        with self.assertRaises(SoundError):
            await self.controller.command("helm", self.command(), lambda: True, activity_deadline=self.controller.monotonic() - .01)
        self.assertEqual(self.wire.calls, [])
        self.assertIsNone(self.controller.preview)

    async def test_lost_rollback_reply_preserves_baseline_and_never_replays_rollback(self):
        await self.send(model=preset(2))
        self.wire.mode = "unknown"
        with self.assertRaises(SoundUnknown): await self.controller._recover()
        self.assertEqual(self.wire.state["preset"], preset())
        self.assertEqual(len(self.wire.calls), 2)
        self.wire.mode = "apply"
        await self.controller._recover()
        self.assertEqual(len(self.wire.calls), 2)
        self.assertIsNone(self.controller.store.state["journal"])

    async def test_pending_audio_block_is_not_claimed_applied_or_rolled_back_blindly(self):
        self.wire.mode = "prepare"
        with self.assertRaises(SoundUnknown): await self.send()
        self.assertEqual(self.wire.state["appliedRevision"], 0)
        await self.controller._recover()
        self.assertIsNotNone(self.controller.store.state["journal"]); self.assertEqual(len(self.wire.calls), 1)
        with self.assertRaises(SoundError): await self.send()
        self.wire.complete(); self.wire.mode = "apply"
        await self.controller._recover()
        self.assertEqual(self.wire.state["preset"], preset()); self.assertEqual(len(self.wire.calls), 2)
    async def test_authorization_is_rechecked_after_atomic_journal_before_effect(self):
        calls = 0
        def valid():
            nonlocal calls
            calls += 1; return calls < 3
        with self.assertRaises(SoundError):
            await self.controller.command("helm", self.command(), valid)
        self.assertEqual(self.wire.calls, [])
        await self.controller._recover(); self.assertIsNone(self.controller.store.state["journal"])
    async def test_width_lock_preserves_hz_on_frequency_move(self):
        first = preset(2); first["eqPoints"][0]["widthLocked"] = True
        await self.send(model=first)
        second = copy.deepcopy(first); second["eqPoints"][0]["frequencyHz"] = 2000
        with self.assertRaises(SoundError): await self.send(model=second)
        second["eqPoints"][0]["q"] = 2
        await self.send(model=second)
        self.assertEqual(self.wire.state["preset"], second)
    async def test_restart_restores_saved_sound_only_into_pristine_approved_stage(self):
        await self.send(model=preset(2)); await self.send("soundSave")
        self.wire.state = dsp_state(); self.wire.state["sourceGeneration"] = "reboot-1"
        recovered = SoundController(self.wire, self.path); await recovered.observe(); await recovered._restore()
        self.assertEqual(self.wire.state["preset"], preset(2))
        self.wire.state["sourceGeneration"] = "foreign-reload"; self.wire.state["appliedRevision"] = 8; self.wire.state["preparedRevision"] = 8
        self.wire.state["preset"] = preset(7)
        await recovered.observe(); await recovered._restore(); self.assertEqual(self.wire.state["preset"], preset(7))
    async def test_stalled_duplicate_spectrum_expires_without_freshness_invention(self):
        clock = [10.]
        self.controller.monotonic = lambda: clock[0]
        await self.controller.spectrum_attach()
        self.assertIsNotNone(await self.controller.spectrum_latest())
        clock[0] += .6
        self.assertIsNone(await self.controller.spectrum_latest())
        self.assertEqual(self.controller._last_new_spectrum, 10.)
        self.wire.frame["sequence"] += 1
        clock[0] += .11
        self.assertIsNotNone(await self.controller.spectrum_latest())
        await self.controller.spectrum_detach()

    async def test_spectrum_is_latest_for_all_consumers_and_generation_gated(self):
        await self.controller.spectrum_attach(); await self.controller.spectrum_attach()
        first = await self.controller.spectrum_latest(); second = await self.controller.spectrum_latest()
        self.assertEqual(first, second); self.assertEqual(self.wire.enables, [True])
        await self.controller.spectrum_detach(); self.assertEqual(self.wire.enables, [True])
        self.controller._last_spectrum_monotonic = -1; self.controller._last_spectrum_renewal = -1
        self.wire.frame["sourceGeneration"] = "old-source"
        self.assertIsNone(await self.controller.spectrum_latest()); self.assertEqual(self.wire.enables, [True, True])
        await self.controller.spectrum_detach(); self.assertEqual(self.wire.enables[-1], False)


class SoundBoundaryTest(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        import test_audio_stream as fixture
        await fixture.AudioStreamTest.asyncSetUp(self)
        self.directory = tempfile.TemporaryDirectory(); self.wire = Wire()
        self.sound = SoundController(self.wire, Path(self.directory.name) / "sound.json")
        await self.sound.observe(); self.audio.sound = self.sound
        self.headers = {"X-No5-Gateway-Key": fixture.KEY}
    async def asyncTearDown(self):
        import test_audio_stream as fixture
        await fixture.AudioStreamTest.asyncTearDown(self); self.directory.cleanup()
    async def test_http_snapshot_auth_and_sound_revision_independent_from_faders(self):
        self.assertEqual((await self.client.get("/v1/audio/sound")).status, 401)
        state = await (await self.client.get("/v1/audio/sound", headers=self.headers)).json()
        self.assertTrue(state["available"]); self.assertEqual(state["presetRevision"], 0)
        self.assertEqual(state["version"],2);self.assertEqual(state["eqPolicy"],EQ_POLICY)
        audio = self.audio.payload
        body = {"version": 1, "kind": "audio-command", "sessionId": "sound", "requestId": "sound:1", "serverSessionId": audio["serverSessionId"], "expectedRevision": 0,
                "command": {"type": "soundPreview", "previewId": "edit", "scope": "preset", "configGeneration": "config-1", "expectedPresetRevision": 0, "expectedCalibrationRevision": 0, "model": preset(2)}}
        response = await self.client.post("/v1/audio/commands", json=body, headers=self.headers)
        self.assertEqual(response.status, 202); receipt = await response.json()
        for _ in range(100):
            result = await (await self.client.get("/v1/audio/commands/" + receipt["commandId"], headers=self.headers)).json()
            if result["status"] not in {"ACCEPTED", "RUNNING"}: break
            await asyncio.sleep(.005)
        self.assertEqual(result["status"], "APPLIED"); self.assertEqual(result["verification"], "AUDIO_BLOCK_READBACK")
        meta=result["soundReadback"];self.assertEqual(meta["schema"],"no5.sound-command-readback/1")
        self.assertEqual(meta["state"]["preset"],(await (await self.client.get("/v1/audio/sound",headers=self.headers)).json())["preset"])
        self.assertTrue(0<meta["remainingLeaseMs"]<=5000)
        self.assertEqual(len(self.wire.calls), 1)
        repeated = await (await self.client.post("/v1/audio/commands", json=body, headers=self.headers)).json()
        self.assertEqual(repeated["commandId"], receipt["commandId"]); self.assertEqual(len(self.wire.calls), 1)
    async def test_native_budget_rejection_keeps_exact_reason_and_old_writer_is_not_submitted(self):
        self.wire.mode="reject_budget";state=self.audio.payload
        body={"version":1,"kind":"audio-command","sessionId":"sound","requestId":"sound:1",
              "serverSessionId":state["serverSessionId"],"expectedRevision":state["revision"],
              "command":{"type":"soundPreview","previewId":"edit","scope":"preset","configGeneration":"config-1",
                         "expectedPresetRevision":0,"expectedCalibrationRevision":0,"model":preset(8,reserve=6.)}}
        legacy=copy.deepcopy(body);del legacy["command"]["model"]["eqEnabled"]
        rejected=await self.client.post("/v1/audio/commands",json=legacy,headers=self.headers)
        self.assertEqual(rejected.status,400);self.assertEqual(self.wire.calls,[])
        response=await self.client.post("/v1/audio/commands",json=body,headers=self.headers)
        self.assertEqual(response.status,202);receipt=await response.json()
        for _ in range(100):
            result=await (await self.client.get("/v1/audio/commands/"+receipt["commandId"],headers=self.headers)).json()
            if result["status"] not in {"ACCEPTED","RUNNING"}:break
            await asyncio.sleep(.002)
        self.assertEqual(result["status"],"REJECTED");self.assertEqual(result["reason"],"EQ_COMBINED_GAIN_LIMIT")
        self.assertEqual(len(self.wire.calls),1);self.assertIsNone(self.sound.store.state["journal"])
    async def test_sound_block_ack_preserves_fader_cas_revision(self):
        # Sound owns its own revisions. Its block receipt must not synthesize
        # a new fader revision and reject an otherwise-current queued fader.
        from no5_pi_gateway.service import AUDIO_SUBPROTOCOL
        ws = await self.client.ws_connect("/v1/audio/stream", protocols=[AUDIO_SUBPROTOCOL], headers=self.headers)
        initial = await ws.receive_json()
        control_before = copy.deepcopy(self.audio._control_signature)
        request = {"version": 1, "kind": "audio-command", "sessionId": "sound", "requestId": "sound:1",
                   "serverSessionId": initial["serverSessionId"], "expectedRevision": initial["revision"],
                   "command": {"type": "soundPreview", "previewId": "edit", "scope": "preset",
                   "configGeneration": "config-1", "expectedPresetRevision": 0, "expectedCalibrationRevision": 0, "model": preset(2)}}
        ack = await self.audio.sound_command_authorized("owner", request, lambda: True)
        self.assertEqual(ack["status"], "VERIFIED")
        self.assertEqual(ack["verification"], "AUDIO_BLOCK_READBACK")
        self.assertEqual(self.audio._control_signature, control_before)
        self.assertEqual(ack["state"]["revision"], initial["revision"])
        await ws.send_json({**request, "sessionId": "fader", "requestId": "fader:1",
                           "command": {"type": "fader", "channel": "AUX3", "valueDb": -1}})
        while True:
            result = await ws.receive_json(timeout=.2)
            if result["kind"] == "audio-ack": break
        self.assertEqual(result["status"], "VERIFIED")
        self.assertEqual(self.fake.writes, [("AUX3", -1)])
        await ws.close()

    async def test_sound_ack_keeps_real_foreign_fader_cas_change(self):
        from dataclasses import replace
        from no5_pi_gateway.audio import CamillaFader
        from no5_pi_gateway.service import AUDIO_SUBPROTOCOL
        ws = await self.client.ws_connect("/v1/audio/stream", protocols=[AUDIO_SUBPROTOCOL], headers=self.headers)
        initial = await ws.receive_json()
        # A real unrelated fader change is a signature change and must still
        # invalidate the old CAS. Separating sound is not a global rebase.
        changed = dict(self.fake.snapshot.faders); changed["AUX4"] = CamillaFader(-5., False)
        self.fake.snapshot = replace(self.fake.snapshot, faders=changed)
        self.audio._latest = self.fake.snapshot
        request = {"version": 1, "kind": "audio-command", "sessionId": "sound", "requestId": "sound:1",
                   "serverSessionId": initial["serverSessionId"], "expectedRevision": initial["revision"],
                   "command": {"type": "soundPreview", "previewId": "edit", "scope": "preset",
                   "configGeneration": "config-1", "expectedPresetRevision": 0, "expectedCalibrationRevision": 0, "model": preset(2)}}
        ack = await self.audio.sound_command_authorized("owner", request, lambda: True)
        self.assertGreater(ack["state"]["revision"], initial["revision"])
        await ws.send_json({**request, "sessionId": "fader", "requestId": "fader:1",
                           "command": {"type": "fader", "channel": "AUX3", "valueDb": -1}})
        while True:
            result = await ws.receive_json(timeout=.2)
            if result["kind"] == "audio-ack": break
        self.assertEqual(result["status"], "REJECTED")
        self.assertEqual(result["message"], "AUDIO state changed; command was not sent")
        self.assertEqual(self.fake.writes, [])
        await ws.close()

    async def test_slow_sound_does_not_block_real_fader_websocket(self):
        from no5_pi_gateway.service import AUDIO_SUBPROTOCOL
        self.wire.block = asyncio.Event()
        pending = asyncio.create_task(self.sound.command("helm", {"type": "soundPreview", "previewId": "edit", "scope": "preset", "configGeneration": "config-1", "expectedPresetRevision": 0, "expectedCalibrationRevision": 0, "model": preset(2)}, lambda: True))
        for _ in range(100):
            if self.wire.calls: break
            await asyncio.sleep(.001)
        ws = await self.client.ws_connect("/v1/audio/stream", protocols=[AUDIO_SUBPROTOCOL], headers=self.headers)
        state = await ws.receive_json()
        await ws.send_json({"version": 1, "kind": "audio-command", "sessionId": "fader", "requestId": "fader:1", "serverSessionId": state["serverSessionId"], "expectedRevision": state["revision"], "command": {"type": "fader", "channel": "AUX3", "valueDb": -4}})
        deadline = time.monotonic() + .2
        while True:
            result = await ws.receive_json(timeout=.2)
            if result["kind"] == "audio-ack": break
        self.assertLess(time.monotonic(), deadline); self.assertEqual(result["status"], "VERIFIED"); self.assertEqual(self.fake.writes, [("AUX3", -4)])
        self.wire.block.set(); await pending; await ws.close()
    async def test_spectrum_protocol_read_only_and_latest_snapshot(self):
        from aiohttp import WSMsgType, WSServerHandshakeError
        from no5_pi_gateway.service import AUDIO_SPECTRUM_SUBPROTOCOL
        with self.assertRaises(WSServerHandshakeError): await self.client.ws_connect("/v1/audio/spectrum", protocols=["wrong"], headers=self.headers)
        ws = await self.client.ws_connect("/v1/audio/spectrum", protocols=[AUDIO_SPECTRUM_SUBPROTOCOL], headers=self.headers)
        frame = await ws.receive_json(); self.assertEqual(frame["schema"], "no5.audio-spectrum/1")
        await ws.send_json({"command": "forbidden"})
        while (await ws.receive()).type == WSMsgType.TEXT: pass
        self.assertEqual(ws.close_code, 1003); await ws.close()
        for _ in range(20):
            if self.wire.enables == [True, False]: break
            await asyncio.sleep(.001)
        self.assertEqual(self.wire.enables, [True, False])

class SoundMigrationTest(unittest.TestCase):
    def test_restoring_old_owned_curve_into_new_pristine_stage_keeps_eq_bypassed(self):
        async def check():
            with tempfile.TemporaryDirectory() as directory:
                path=Path(directory)/"sound.json";old=preset(6)
                del old["eqEnabled"];del old["reserveDb"]
                document=SoundStore(path).state
                document.pop("fallbacks")
                document.update(schema="no5.sound-state/1",presetRevision=3,presets={old["id"]:old},bindings={"config-1":old["id"]})
                path.write_text(json.dumps(document));wire=Wire();wire.state["preset"]["eqPoints"]=[]
                owner=SoundController(wire,path);await owner.observe();await owner._restore()
                self.assertEqual(wire.state["preset"]["eqPoints"],old["eqPoints"])
                self.assertFalse(wire.state["preset"]["eqEnabled"]);self.assertEqual(wire.state["headroomDb"],0.)
                self.assertEqual(wire.state["preset"]["reserveDb"],12.);self.assertEqual(owner.store.state["presetRevision"],3)
        asyncio.run(check())

    def test_old_store_curve_and_journal_migrate_losslessly_off_without_startup_write(self):
        value=preset(12);value["eqPoints"]=[{**value["eqPoints"][0],"id":f"old-{index}"} for index in range(16)]
        del value["eqEnabled"];del value["reserveDb"]
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory)/"sound.json";base=SoundStore(path).state
            base.pop("fallbacks")
            base.update(schema="no5.sound-state/1",presetRevision=3,presets={value["id"]:value},bindings={"config-1":value["id"]},
                        journal={"configGeneration":"old-config","sourceGeneration":"old-source","targetRevision":4,
                                 "preset":copy.deepcopy(value),"calibration":default_calibration()})
            raw=json.dumps(base).encode();path.write_bytes(raw);loaded=SoundStore(path)
            self.assertEqual(path.read_bytes(),raw)
            self.assertEqual(loaded.state["schema"],"no5.sound-state/3");self.assertEqual(loaded.state["fallbacks"],{});self.assertEqual(loaded.state["presetRevision"],3)
            for model in (loaded.state["presets"][value["id"]],loaded.state["journal"]["preset"]):
                self.assertFalse(model["eqEnabled"]);self.assertEqual(model["reserveDb"],12.)
                self.assertEqual({k:v for k,v in model.items() if k not in {"eqEnabled","reserveDb"}},value)
            self.assertEqual(loaded.state["bindings"],base["bindings"])
            loaded.replace(loaded.state)
            self.assertEqual(SoundStore(path).state,loaded.state)

    def test_runtime_values_are_captured_and_newer_store_cannot_be_overwritten(self):
        from no5_pi_gateway.equalizer import provision_equalizer, snapshot
        from no5_pi_gateway.sound_migrate import capture_legacy, import_legacy, state_sha256
        from test_equalizer import base_config
        config = provision_equalizer(base_config())
        path = "/etc/camilladsp/preset_no5_stereo_4_1.yml"
        before = snapshot(config, path, 0)
        manifest = {"schema": "no5.sound-profile-migration/1", "profiles": [
            {"sourcePath": path, "destinationPath": "/etc/camilladsp/preset_no5_sound_stereo_4_1.yml",
             "legacyConfigGeneration": before["configGeneration"], "routing": "stereo_4_1"}]}
        # Capture live values, not the neutral root preset. Headroom must match.
        from no5_pi_gateway.equalizer import _nodes
        config["filters"].update(_nodes((2., 0., -1., 0., 0.)))
        capture = capture_legacy(config, path, manifest)
        bound = copy.deepcopy(manifest)
        row = bound["profiles"][0]
        row.update(sourceSha256="1" * 64, sourceConfigSha256="2" * 64)
        row["activationBinding"] = {"origin": "CAMILLA_VALIDATE_CONFIG_JSON", "sourceSha256": "1" * 64,
            "validationInputSha256": "2" * 64, "normalizedConfigSha256": "3" * 64,
            "legacyConfigGeneration": before["configGeneration"], "camillaBinarySha256": "4" * 64}
        row["legacyConfigGeneration"] = "5" * 64  # Raw YAML identity need not equal normalized Camilla JSON.
        self.assertEqual(capture_legacy(config, path, bound)["preset"], capture["preset"])
        row["activationBinding"]["validationInputSha256"] = "6" * 64
        with self.assertRaises(SoundError): capture_legacy(config, path, bound)
        alias = "/etc/camilladsp/no5.yml"
        with self.assertRaises(SoundError): capture_legacy(config, alias, manifest)
        binding = {"sourcePath": alias, "sourceConfigSha256": hashlib.sha256(json.dumps(config, sort_keys=True, separators=(",", ":")).encode()).hexdigest(), "canonicalSourcePath": path}
        self.assertEqual(capture_legacy(config, alias, manifest, alias_binding=binding)["preset"], capture["preset"])
        binding["sourceConfigSha256"] = "0" * 64
        with self.assertRaises(SoundError): capture_legacy(config, alias, manifest, alias_binding=binding)
        self.assertEqual([p["gainDb"] for p in capture["preset"]["eqPoints"]], [2., 0., -1., 0., 0.])
        observed = dsp_state(); observed["preset"]["eqPoints"] = []
        with tempfile.TemporaryDirectory() as directory:
            store = SoundStore(Path(directory) / "sound.json")
            original = state_sha256(store)
            outcome = import_legacy(store, capture, observed, expected_store_sha256=original)
            self.assertEqual(outcome["presetRevision"], 1)
            with self.assertRaises(SoundError): import_legacy(store, capture, observed, expected_store_sha256=original)
            self.assertEqual(store.state["presets"][capture["preset"]["id"]], capture["preset"])
        bad = copy.deepcopy(config); bad["pipeline"].append({"type": "Filter", "channels": [0], "names": []})
        with self.assertRaises(SoundError): capture_legacy(bad, path, manifest)

class SoundSafetyTest(unittest.TestCase):
    def test_state_symlink_corruption_and_unmanaged_location_are_rejected(self):
        from dataclasses import replace
        from no5_pi_gateway.configuration import CamillaDspConfig, ConfigurationError
        with self.assertRaises(ConfigurationError): replace(CamillaDspConfig(), sound_state_file="/etc/camilladsp/sound.json")
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "state.json"
            path.write_text('{"schema":"unknown"}')
            with self.assertRaises(SoundError): SoundStore(path)
            link = Path(directory) / "link.json"; link.symlink_to(path)
            with self.assertRaises(OSError): SoundStore(link)

class SoundRingTest(unittest.IsolatedAsyncioTestCase):
    async def test_sound_targets_supersede_with_scope_barriers_and_independent_revision(self):
        from no5_pi_gateway.audio_commands import AudioCommands
        from test_audio_commands import Adapter, request
        class SoundAdapter(Adapter):
            async def sound_command_authorized(self, owner, request, valid, *, admitted_monotonic=None):
                self.writes.append(request["command"])
                return {"status": "VERIFIED", "state": dict(self.payload), "verification": "AUDIO_BLOCK_READBACK"}
        adapter = SoundAdapter(); ring = AudioCommands(adapter)
        def target(gain):
            return {"type": "soundPreview", "previewId": "edit", "scope": "preset", "configGeneration": "config-1",
                    "expectedPresetRevision": 0, "expectedCalibrationRevision": 0, "model": preset(gain)}
        try:
            old = ring.submit("owner", request(1, target(1), revision=0), lambda: True)
            latest = ring.submit("owner", request(2, target(2), revision=0), lambda: True)
            save = {"type": "soundSave", "previewId": "edit", "scope": "preset", "configGeneration": "config-1", "expectedPresetRevision": 0, "expectedCalibrationRevision": 0}
            ring.submit("owner", request(3, save, revision=0), lambda: True)
            last = ring.submit("owner", request(4, target(3), revision=0), lambda: True)
            self.assertEqual(ring.read("owner", old["commandId"])["status"], "SUPERSEDED")
            self.assertEqual(ring.read("owner", old["commandId"])["replacementId"], latest["commandId"])
            ring.start()
            for _ in range(100):
                if ring.read("owner", last["commandId"])["status"] == "APPLIED": break
                await asyncio.sleep(.001)
            self.assertEqual(adapter.writes, [target(2), save, target(3)])
            self.assertEqual(ring.read("owner", last["commandId"])["verification"], "AUDIO_BLOCK_READBACK")
            for number in range(5, 300): ring.submit("owner", request(number, target(1), revision=0), lambda: True)
            self.assertEqual(len(ring.diagnostics), 64)
            self.assertLessEqual(ring._size, 64)
        finally: await ring.stop()

    async def test_expired_waiting_sound_request_cannot_create_late_preview(self):
        from no5_pi_gateway.audio_commands import AudioCommands
        from test_audio_commands import Adapter, request
        adapter = Adapter(); ring = AudioCommands(adapter)
        command = {"type": "soundKeepalive", "previewId": "edit", "configGeneration": "config-1"}
        result = ring.submit("owner", request(1, command, revision=0), lambda: True)
        ring._pending[ring._head].admitted_monotonic -= 6
        try:
            ring.start()
            for _ in range(20):
                receipt = ring.read("owner", result["commandId"])
                if receipt["status"] == "CANCELLED": break
                await asyncio.sleep(.001)
            self.assertEqual(receipt["reason"], "SOUND_ACTIVITY_EXPIRED")
            self.assertEqual(adapter.writes, [])
        finally: await ring.stop()

class SharedEqPolicyReadbackTest(unittest.TestCase):
    def test_old_metadata_remains_valid_and_shared_identity_is_strict(self):
        from no5_pi_gateway.sound import _native_eq_editing
        old={"schema":"no5.eq-editing/1","previewProjection":True,"maxExperimentalBoostDb":3,"experimental":False,"normalPreset":None}
        self.assertTrue(_native_eq_editing(old))
        policy={"schema":"no5.eq-safety/1","kernelSha256":"a"*64,"sampleRateHz":48000,"gridPoints":65536,"minReserveDb":6,"maxReserveDb":18,"experimentalBoostDb":3}
        self.assertTrue(_native_eq_editing({**old,"validationPolicy":policy}))
        for key,value in [('kernelSha256','wrong'),('gridPoints',512),('sampleRateHz',44100),('minReserveDb',True),('maxReserveDb',19),('experimentalBoostDb',4)]:
            with self.assertRaises(SoundError):_native_eq_editing({**old,'validationPolicy':{**policy,key:value}})
