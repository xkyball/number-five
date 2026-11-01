"""Gateway contract/authority tests; numeric projection is exercised in native Rust tests."""
import copy
import unittest
import test_sound as fixture
from no5_pi_gateway.sound import SoundError, SoundUnknown, SoundStore, validate_eq_editing, validate_sound_command, validate_dsp_state


def editing(experimental=False, previous=None, point=None):
    return {"schema":"no5.eq-editing/1", "experimental":experimental,
            "previousPoint":previous, "editedPointId":point}


class ProjectionWire(fixture.Wire):
    def __init__(self):
        super().__init__()
        self.state["eqEditing"]={"schema":"no5.eq-editing/1","previewProjection":True,
                                "maxExperimentalBoostDb":3.0,"experimental":False,"normalPreset":None}
    async def apply(self, argument):
        bounded=copy.deepcopy(argument); intent=argument.get("eqEditing")
        if intent:
            # Fixed one-band receipt fixture, not a second DSP response implementation.
            maximum=bounded["preset"]["reserveDb"]+(3 if intent["experimental"] else 0)
            for point in bounded["preset"]["eqPoints"]: point["gainDb"]=min(point["gainDb"],maximum)
        result=await super().apply(bounded)
        if intent: result.update(preset=bounded["preset"],experimental=intent["experimental"],
                                 constrained=argument["preset"]!=bounded["preset"])
        return result
    def complete(self, argument=None):
        argument=argument or self.calls[-1]
        super().complete(argument)
        active=argument.get("eqEditing",{}).get("experimental",False)
        self.state["eqEditing"]["experimental"]=active
        normal=copy.deepcopy(argument["preset"]) if active else None
        if normal:
            for point in normal["eqPoints"]:point["gainDb"]=min(point["gainDb"],normal["reserveDb"])
        self.state["eqEditing"]["normalPreset"]=normal
    def expire(self):
        super().expire();self.state["eqEditing"].update(experimental=False,normalPreset=None)


class EqEditingContractTest(unittest.IsolatedAsyncioTestCase):
    asyncSetUp=fixture.SoundControllerTest.asyncSetUp
    asyncTearDown=fixture.SoundControllerTest.asyncTearDown
    command=fixture.SoundControllerTest.command
    send=fixture.SoundControllerTest.send
    async def enable_wire(self):
        self.wire=ProjectionWire();self.controller.wire=self.wire
        await self.controller.observe()
    async def test_projection_capability_is_required_before_any_dsp_send_or_journal_write(self):
        before=copy.deepcopy(self.controller.store.state)
        with self.assertRaisesRegex(SoundError,"unsupported"):
            await self.send(model=fixture.preset(9,reserve=6),eqEditing=editing())
        self.assertEqual(self.wire.calls,[]);self.assertEqual(self.controller.store.state,before)
    async def test_confirmed_snapshot_uses_the_projected_target_and_preserves_eq_enabled(self):
        await self.enable_wire()
        value=await self.send(model=fixture.preset(9,reserve=6),eqEditing=editing())
        self.assertEqual(value["preset"]["eqPoints"][0]["gainDb"],6)
        self.assertTrue(value["preset"]["eqEnabled"])
        self.assertTrue(value["eqEditing"]["constrained"])
        self.assertFalse(value["eqEditing"]["experimental"])
        self.assertTrue(value["capabilities"]["experimentalEq"])
    async def test_experiment_cannot_save_or_copy_even_when_client_bypasses_ui(self):
        await self.enable_wire();before=copy.deepcopy(self.controller.store.state["presets"])
        value=await self.send(model=fixture.preset(9,reserve=6),eqEditing=editing(True))
        self.assertTrue(value["eqEditing"]["experimental"])
        for kind,kwargs in [("soundSave",{}),("soundSaveAs",{"name":"Forbidden"})]:
            with self.assertRaisesRegex(SoundError,"EXPERIMENTAL_PREVIEW_ONLY"):await self.send(kind,**kwargs)
        self.assertEqual(self.controller.store.state["presets"],before)
        self.assertEqual(SoundStore(self.path).state["presets"],before)
        self.assertEqual(self.wire.lease_calls,[])
        self.assertFalse(self.controller.store.state["journal"]["preset"]["eqEnabled"])
    async def test_only_explicit_visible_experiment_keepalive_renews_native_lease(self):
        await self.enable_wire();await self.send(model=fixture.preset(9,reserve=6),eqEditing=editing(True))
        with self.assertRaisesRegex(SoundError,"EXPERIMENTAL_PREVIEW_ONLY"):await self.send("soundKeepalive")
        self.assertEqual(self.wire.lease_calls,[])
        await self.send("soundKeepalive",experimental=True)
        self.assertTrue(self.wire.lease_calls[-1]["experimental"])
        self.assertFalse(self.wire.lease_calls[-1]["fallbackPreset"]["eqEnabled"])
    async def test_normalized_exit_can_then_be_saved_without_the_experimental_flag(self):
        await self.enable_wire();await self.send(model=fixture.preset(9,reserve=6),eqEditing=editing(True))
        value=await self.send(model=fixture.preset(9,reserve=6),eqEditing=editing(False))
        self.assertFalse(value["eqEditing"]["experimental"])
        self.assertEqual(value["preset"]["eqPoints"][0]["gainDb"],6)
        await self.send("soundSave")
        saved=SoundStore(self.path).state["presets"]["stereo"]
        self.assertEqual(saved["eqPoints"][0]["gainDb"],6)
        self.assertNotIn("eqEditing",saved)
    async def test_unobserved_experiment_is_unknown_not_reported_as_applied(self):
        await self.enable_wire();self.wire.mode="prepare"
        with self.assertRaises(SoundUnknown):await self.send(model=fixture.preset(9,reserve=6),eqEditing=editing(True))
        self.assertFalse(self.controller.current["eqEditing"]["experimental"])
        self.assertEqual(len(self.wire.calls),1)
    async def test_edit_intent_fields_are_strict_and_not_allowed_for_calibration(self):
        for key,bad in [("schema","future"),("experimental",1),("editedPointId","p")]:
            value=editing();value[key]=bad
            with self.assertRaises(SoundError):validate_eq_editing(value)
        value=self.command(scope="calibration",model=fixture.default_calibration(),eqEditing=editing())
        with self.assertRaises(SoundError):validate_sound_command(value)

    async def test_native_normal_fallback_must_preserve_identity_geometry_cuts_and_common_scale(self):
        await self.enable_wire();await self.send(model=fixture.preset(9,reserve=6),eqEditing=editing(True))
        baseline=copy.deepcopy(self.wire.state)
        for change in (lambda p:p.update(id="foreign"),lambda p:p["room"].update(size=.1),
                       lambda p:p["eqPoints"][0].update(frequencyHz=2000),lambda p:p["eqPoints"][0].update(gainDb=-1)):
            value=copy.deepcopy(baseline);change(value["eqEditing"]["normalPreset"])
            with self.assertRaises(SoundError):validate_dsp_state(value)
        validate_dsp_state(baseline)
