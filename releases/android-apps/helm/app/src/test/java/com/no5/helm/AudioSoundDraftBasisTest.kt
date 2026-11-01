package com.no5.helm

import android.content.Context
import com.no5.domain.*
import com.no5.helm.network.AudioSoundCodec
import com.no5.helm.network.soundFixture
import kotlinx.coroutines.Dispatchers
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.*

private class DraftBasisPort(initial:AudioSoundState=soundFixture()):AudioSoundPort {
    override val state=MutableSourceFeed(initial)
    override val spectrum=MutableSourceFeed<AudioSpectrumFrame?>(null)
    override val results=MutableSourceFeed<AudioSoundResult?>(null)
    val commands=mutableListOf<AudioSoundCommand>()
    var refreshes=0
    override fun submit(command:AudioSoundCommand):Long {commands+=command;return commands.size.toLong()}
    override fun setEditorVisible(visible:Boolean,eqVisible:Boolean)=Unit
    override fun refresh(){refreshes++}
    override fun close()=Unit
}

/** Process restoration and first-write CAS, with no network or synthetic live values. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class AudioSoundDraftBasisTest {
    private val context get()=RuntimeEnvironment.getApplication()
    private val storage get()=context.getSharedPreferences("no5-sound-drafts-v1",Context.MODE_PRIVATE)
    @Before fun clearBefore(){storage.edit().clear().commit()}
    @After fun clearAfter(){storage.edit().clear().commit()}
    private fun controller(port:DraftBasisPort)=AudioSoundUiController(port,Dispatchers.Unconfined).also{it.attachStorage(context);it.openPreset()}
    private fun boosted(state:AudioSoundState,gain:Double=6.0)=requireNotNull(state.preset).let{it.copy(eqPoints=it.eqPoints.map{p->p.copy(gainDb=gain)})}
    private fun rememberPreset(initial:AudioSoundState=soundFixture(),gain:Double=6.0):SoundPreset {
        val port=DraftBasisPort(initial);val c=controller(port);val draft=boosted(initial,gain);c.changePreset(draft);c.close();return draft
    }

    @Test fun sameOriginalPresetBasisRestoresAndAllowsFirstPreviewDespiteOtherRevisionsChanging(){
        val initial=soundFixture();val draft=rememberPreset(initial)
        val saved=JSONObject(storage.getString("draft",null)!!)
        assertEquals(2,saved.getInt("version"));assertEquals(3,saved.getJSONObject("presetBasis").getLong("presetRevision"))
        val port=DraftBasisPort(initial.copy(calibrationRevision=99,configurationRevision=2000))
        val c=controller(port);assertEquals(draft,c.preset);assertTrue(c.editable);assertFalse(c.conflicted)
        c.updatePoint(c.preset!!.eqPoints.first().copy(gainDb=7.0))
        assertEquals(1,port.commands.filterIsInstance<AudioSoundCommand.Preview>().size)
        val command=port.commands.single() as AudioSoundCommand.Preview
        assertEquals(3,command.basis!!.presetRevision);assertEquals(7.0,command.preset!!.eqPoints.first().gainDb)
        c.close()
    }

    @Test fun stalePresetRevisionPreservesBoostButBlocksFirstEditSaveAndRefreshRelabelling(){
        val initial=soundFixture();val draft=rememberPreset(initial,9.0)
        val port=DraftBasisPort(initial.copy(presetRevision=4));val c=controller(port)
        assertEquals(draft,c.preset);assertTrue(c.presetDirty);assertFalse(c.editable)
        c.updatePoint(c.preset!!.eqPoints.first().copy(gainDb=10.0));c.save(SoundScope.PRESET);c.refresh()
        port.state.emit(port.state.value)
        assertEquals(draft,c.preset);assertEquals(0,port.commands.filterIsInstance<AudioSoundCommand.Preview>().size);assertTrue(port.commands.isEmpty())
        c.close()
        assertEquals(3,JSONObject(storage.getString("draft",null)!!).getJSONObject("presetBasis").getLong("presetRevision"))
        val restored=controller(port);assertFalse(restored.editable);assertEquals(draft,restored.preset)
        var closed=false;restored.requestClose(SoundScope.PRESET){closed=true};assertEquals(SoundScope.PRESET,restored.closeDecision)
        restored.discard();assertTrue(closed);assertTrue(port.commands.isEmpty());assertEquals(initial.preset,restored.preset);assertTrue(restored.editable)
        restored.updatePoint(restored.preset!!.eqPoints.first().copy(gainDb=1.0))
        assertEquals(1,port.commands.size);assertEquals(4,(port.commands.single() as AudioSoundCommand.Preview).basis!!.presetRevision)
        restored.close()
    }

    @Test fun staleCalibrationBlocksOnlyCalibrationWhileAnUnchangedPresetRemainsEditable(){
        val initial=soundFixture();val firstPort=DraftBasisPort(initial);val first=controller(firstPort);first.openSpeakers()
        val draft=first.calibration!!.copy(speakers=first.calibration!!.speakers.map{if(it.id==SoundSpeakerId.SUB)it.copy(manualGainDb=-3.0)else it})
        first.changeCalibration(draft);first.close()
        val port=DraftBasisPort(initial.copy(calibrationRevision=8,presetRevision=40,configurationRevision=500));val c=controller(port)
        assertTrue(c.conflicted);assertTrue(c.editable,"A stale calibration must not disable the Preset editor")
        c.openSpeakers();assertFalse(c.editable);c.changeCalibration(draft.copy(geometryEnabled=false));c.save(SoundScope.CALIBRATION)
        assertEquals(draft,c.calibration);assertTrue(port.commands.isEmpty())
        c.openPreset();assertTrue(c.editable);c.updatePoint(c.preset!!.eqPoints.first().copy(gainDb=2.0))
        val preview=port.commands.single() as AudioSoundCommand.Preview
        assertEquals(SoundScope.PRESET,preview.scope);assertEquals(40,preview.basis!!.presetRevision)
        c.close()
        assertEquals(7,JSONObject(storage.getString("draft",null)!!).getJSONObject("calibrationBasis").getLong("calibrationRevision"))
    }

    @Test fun stalePresetDoesNotBlockCurrentCalibrationOrSendItsOldBoost(){
        val initial=soundFixture();rememberPreset(initial,8.0)
        val port=DraftBasisPort(initial.copy(presetRevision=4));val c=controller(port);c.openSpeakers();assertTrue(c.editable)
        c.changeCalibration(c.calibration!!.copy(speakers=c.calibration!!.speakers.map{if(it.id==SoundSpeakerId.SUB)it.copy(manualDelayMs=.1)else it}))
        val preview=port.commands.single() as AudioSoundCommand.Preview
        assertEquals(SoundScope.CALIBRATION,preview.scope);assertNull(preview.preset);assertEquals(7,preview.basis!!.calibrationRevision)
        c.openPreset();assertFalse(c.editable);assertEquals(8.0,c.preset!!.eqPoints.first().gainDb);c.close()
    }

    @Test fun versionOneDraftSurvivesMigrationWithUnknownBasisAndCannotBePreviewed(){
        val initial=soundFixture();val draft=boosted(initial,11.0)
        storage.edit().putString("draft",JSONObject().put("version",1).put("configGeneration",initial.configGeneration).put("presetId",initial.preset!!.id).put("preset",AudioSoundCodec.preset(draft)).toString()).commit()
        val port=DraftBasisPort(initial);val c=controller(port)
        assertEquals(draft,c.preset);assertTrue(c.presetDirty);assertFalse(c.editable);assertTrue(c.status!!.contains("ohne bestätigte Basis"))
        c.neutral();c.save(SoundScope.PRESET);c.refresh();assertEquals(draft,c.preset);assertTrue(port.commands.isEmpty());c.close()
        val migrated=JSONObject(storage.getString("draft",null)!!);assertEquals(2,migrated.getInt("version"));assertTrue(migrated.isNull("presetBasis"))
        val again=controller(port);assertEquals(draft,again.preset);assertFalse(again.editable);assertTrue(port.commands.isEmpty())
        again.requestClose(SoundScope.PRESET){};again.discard();assertEquals(initial.preset,again.preset);assertTrue(port.commands.isEmpty());again.close()
    }

    @Test fun newConfigurationOrDifferentActivePresetDoesNotLoseOrRebaseTheOldDraft(){
        val initial=soundFixture();val draft=rememberPreset(initial)
        val newState=initial.copy(configGeneration="b".repeat(64),preset=initial.preset!!.copy(id="different"))
        val port=DraftBasisPort(newState);val c=controller(port)
        assertEquals(draft,c.preset);assertFalse(c.editable);c.addPoint(750.0);c.save(SoundScope.PRESET);assertTrue(port.commands.isEmpty());c.close()
        assertEquals(initial.configGeneration,JSONObject(storage.getString("draft",null)!!).getJSONObject("presetBasis").getString("configGeneration"))
    }

    @Test fun changedPresetIdentityAloneRequiresReconciliationEvenWithIdenticalConfigurationAndRevision(){
        val initial=soundFixture();val draft=rememberPreset(initial)
        val port=DraftBasisPort(initial.copy(preset=initial.preset!!.copy(id="another_preset")));val c=controller(port)
        assertEquals(draft,c.preset);assertFalse(c.editable);c.neutral();assertTrue(port.commands.isEmpty());c.close()
    }

    @Test fun readableLegacyPresetStillCannotPreviewWhenAnotherStoredSectionIsMalformed(){
        val initial=soundFixture();val draft=boosted(initial,10.0)
        storage.edit().putString("draft",JSONObject().put("version",1).put("preset",AudioSoundCodec.preset(draft)).put("calibration",JSONObject()).toString()).commit()
        val port=DraftBasisPort(initial);val c=controller(port)
        assertEquals(draft,c.preset);assertFalse(c.editable);c.neutral();assertTrue(port.commands.isEmpty());c.close()
    }

    @Test fun unchangedCalibrationBasisIgnoresPresetAndFaderRevisionsButRetainsItsOwnOriginalRevision(){
        val initial=soundFixture();val p=DraftBasisPort(initial);val c=controller(p);c.openSpeakers()
        c.changeCalibration(c.calibration!!.copy(speakers=c.calibration!!.speakers.map{if(it.id==SoundSpeakerId.SUB)it.copy(manualGainDb=-2.0)else it}));c.close()
        val port=DraftBasisPort(initial.copy(presetRevision=900,configurationRevision=901));val restored=controller(port);restored.openSpeakers()
        assertTrue(restored.editable);assertFalse(restored.conflicted)
        restored.changeCalibration(restored.calibration!!.copy(speakers=restored.calibration!!.speakers.map{if(it.id==SoundSpeakerId.SUB)it.copy(manualGainDb=-2.1)else it}))
        val preview=port.commands.single() as AudioSoundCommand.Preview;assertEquals(SoundScope.CALIBRATION,preview.scope);assertEquals(7,preview.basis!!.calibrationRevision)
        restored.close()
    }
    @Test fun incompleteExactCoordinatesSurvivePageAndProcessLossWithTheirOriginalBasis(){
        val initial=soundFixture().copy(calibration=SoundCalibration(
            SoundSpeakerId.entries.mapIndexed{i,id->SoundSpeaker(id,SoundPosition(i*.2,i*.7,.4))},SoundPosition(.5,1.7,1.1),true))
        val firstPort=DraftBasisPort(initial);val first=controller(firstPort);first.openSpeakers();first.changeSection("positions")
        first.editCoordinate("FRONT_LEFT","x","pending")
        assertTrue(first.hasInvalidGeometry);assertEquals(initial.calibration,first.calibration);assertTrue(firstPort.commands.isEmpty())
        first.changeSection("delay");first.changeSection("positions")
        assertEquals("pending",first.coordinateInput("FRONT_LEFT").x)
        first.close()
        val retained=JSONObject(storage.getString("draft",null)!!)
        assertEquals(initial.calibrationRevision,retained.getJSONObject("calibrationBasis").getLong("calibrationRevision"))
        val port=DraftBasisPort(initial);val restored=controller(port);restored.openSpeakers()
        assertTrue(restored.hasInvalidGeometry);assertTrue(restored.calibrationDirty)
        assertEquals("pending",restored.coordinateInput("FRONT_LEFT").x)
        assertTrue(port.commands.isEmpty(),"Restoring a local input must not replay an audio operation")
        restored.editCoordinate("FRONT_LEFT","x","0,3")
        assertFalse(restored.hasInvalidGeometry)
        assertEquals(SoundPosition(.3,0.0,.4),restored.calibration!!.speakers.first().positionM)
        assertEquals(initial.calibration!!.listenerM,restored.calibration!!.listenerM)
        assertEquals(initial.calibration!!.speakers.last(),restored.calibration!!.speakers.last())
        restored.close()
    }

    @Test fun foreignCalibrationBlocksRestoredExactInputAndDiscardDoesNotOverwriteIt(){
        val initial=soundFixture();val first=controller(DraftBasisPort(initial));first.openSpeakers()
        first.editCoordinate("SUB","x","-");first.close()
        val current=initial.copy(calibrationRevision=initial.calibrationRevision+1)
        val port=DraftBasisPort(current);val restored=controller(port);restored.openSpeakers()
        assertTrue(restored.conflicted);assertFalse(restored.editable)
        restored.editCoordinate("SUB","x","2");restored.save(SoundScope.CALIBRATION)
        assertEquals("-",restored.coordinateInput("SUB").x);assertTrue(port.commands.isEmpty())
        var closed=false;restored.requestClose(SoundScope.CALIBRATION){closed=true};restored.discard()
        assertTrue(closed);assertFalse(restored.hasInvalidGeometry);assertEquals(current.calibration,restored.calibration)
        assertTrue(port.commands.isEmpty());restored.close()
    }

}
