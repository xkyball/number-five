package com.no5.helm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.no5.domain.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class HelmPresentationViewModelTest {
    private fun model(store: ViewModelStore): HelmPresentationViewModel = ViewModelProvider(store,
        object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST")
                return HelmPresentationViewModel(RuntimeEnvironment.getApplication()) as T
            }
        })[HelmPresentationViewModel::class.java]
    private fun session(id: String): No5SourceSession = object : No5SourceSession {
        override val identity = SourceIdentity(id, SourceMode.PI, "Explicit test fixture")
        override val audio = MockAudioSource(now = { 1_000_000L })
        override val sonar = UnavailablePiSonarSource(identity)
        override val helm: HelmSource get() = error("Presentation owner must not open a source")
        override val weather: WeatherSourcePort get() = error("Presentation owner must not open weather")
        override fun close() { audio.close(); sonar.close() }
    }
    @Test fun theSameSessionRetainsControllersAndHistoryButReplacingItCannotReplayAnAction() {
        val store = ViewModelStore(); val firstSession = session("first"); val nextSession = session("next")
        try {
            val model = model(store)
            val first = model.acquire(firstSession, AudioPresentationSettings.DEFAULT)
            first.audio.restorePresentationState(AudioViewState(3))
            val resumed = model.acquire(firstSession, AudioPresentationSettings.DEFAULT)
            assertSame(first.audio, resumed.audio); assertSame(first.sonar, resumed.sonar)
            assertSame(first.depthHistory, resumed.depthHistory)
            assertEquals(3, resumed.audio.selectedOutput)
            val replacement = model.acquire(nextSession, AudioPresentationSettings.DEFAULT)
            assertNotSame(first.audio, replacement.audio)
            assertEquals("Source replacement retains the current selection before the next Compose save", 3, replacement.audio.selectedOutput)
            assertFalse(first.audio.changeLevel(AudioChannel.MASTER, -10.0))
            assertNull(nextSession.audio.state.value.pending)
            awaitSavedSelection(3)
        } finally { store.clear(); firstSession.close(); nextSession.close() }
    }
    @Test fun processRestartRestoresPageAndDisplayPreferencesWithoutReadingsFreezeOrCommands() {
        val context = RuntimeEnvironment.getApplication()
        File(context.filesDir, "helm-view-v1.json").delete()
        val store = ViewModelStore(); val model = model(store)
        model.save("SONAR", AudioViewState(3), SonarViewState(SonarDisplay(gainDb = 4.0, aScope = false, freeze = true),SonarKnobMode.AUDIO))
        val deadline = System.nanoTime() + 2_000_000_000L
        val file = File(context.filesDir, "helm-view-v1.json")
        while (runCatching { org.json.JSONObject(file.readText()).getString("page") }.getOrNull() != "SONAR" && System.nanoTime() < deadline) Thread.sleep(5)
        assertEquals("atomic state writer completed", "SONAR", org.json.JSONObject(file.readText()).getString("page"))
        store.clear()
        val restartedStore = ViewModelStore(); val source = session("restarted")
        try {
            val restarted = model(restartedStore)
            assertEquals("SONAR", restarted.page)
            val restored = restarted.acquire(source, AudioPresentationSettings.DEFAULT)
            assertEquals(3, restored.audio.selectedOutput)
            assertEquals(4.0, restored.sonar.snapshot.display.gainDb, 0.0)
            assertEquals(SonarKnobMode.AUDIO,restored.sonar.knobMode)
            assertFalse(restored.sonar.snapshot.display.aScope)
            assertFalse(restored.sonar.snapshot.display.freeze)
            assertNull(restored.sonar.snapshot.depthM)
            assertNull(source.audio.state.value.pending)
        } finally { restartedStore.clear(); source.close() }
    }

    @Test fun exactPingProofPreferenceSurvivesSourceReplacementAndProcessRestartWithoutCommands() {
        val file=File(RuntimeEnvironment.getApplication().filesDir,"helm-view-v1.json");file.delete()
        val store=ViewModelStore();val first=session("exact-first");val second=session("exact-replacement")
        try {
            val model=model(store);val controllers=model.acquire(first,AudioPresentationSettings.DEFAULT)
            assertFalse(controllers.sonar.snapshot.display.exactPingProfiles)
            controllers.sonar.exactPingProfiles(true)
            val replaced=model.acquire(second,AudioPresentationSettings.DEFAULT)
            assertTrue(replaced.sonar.snapshot.display.exactPingProfiles)
            assertFalse(replaced.sonar.snapshot.display.freeze);assertNull(second.audio.state.value.pending)
            val deadline=System.nanoTime()+2_000_000_000L
            while(runCatching{org.json.JSONObject(file.readText()).getJSONObject("sonar").getBoolean("exactPingProfiles")}.getOrDefault(false)!=true&&System.nanoTime()<deadline)Thread.sleep(5)
            assertTrue(org.json.JSONObject(file.readText()).getJSONObject("sonar").getBoolean("exactPingProfiles"))
        }finally{store.clear();first.close();second.close()}
        val restartedStore=ViewModelStore();val restarted=session("exact-restarted")
        try {
            val restored=model(restartedStore).acquire(restarted,AudioPresentationSettings.DEFAULT)
            assertTrue(restored.sonar.snapshot.display.exactPingProfiles)
            assertEquals(0,restored.sonar.model.ring.count);assertFalse(restored.sonar.snapshot.display.freeze)
            assertNull(restarted.audio.state.value.pending)
        }finally{restartedStore.clear();restarted.close()}
        // A version1 file written before this additive preference remains default-off.
        val old=org.json.JSONObject(file.readText());old.getJSONObject("sonar").remove("exactPingProfiles");file.writeText(old.toString())
        val legacyStore=ViewModelStore();val legacy=session("exact-legacy")
        try{assertFalse(model(legacyStore).acquire(legacy,AudioPresentationSettings.DEFAULT).sonar.snapshot.display.exactPingProfiles)}
        finally{legacyStore.clear();legacy.close()}
    }

    @Test fun selectedRearSurvivesSourceReplacementBeforeComposeSaveAndOfflineProcessRestart() {
        val context = RuntimeEnvironment.getApplication()
        val file = File(context.filesDir, "helm-view-v1.json")
        file.delete()
        val store = ViewModelStore()
        val firstSession = session("live-before-source-replacement")
        val replacementSession = session("offline-replacement")
        val model = model(store)
        model.save("AUDIO", AudioViewState(0), SonarViewState(SonarDisplay()))
        (firstSession.audio as MockAudioSource).loadFixture(AudioFixture.DEMO)
        val first = model.acquire(firstSession, AudioPresentationSettings.DEFAULT)
        val rear = requireNotNull(first.audio.faderControl(AudioChannel.REAR))
        try {
            first.audio.selectKnobFader(rear)
            first.sonar.restorePresentationState(SonarViewState(SonarDisplay(gainDb = 5.0, aScope = false)))
            // Deliberately omit model.save: source renewal wins the race with the
            // normal Compose effect that observes the user's current selection.
            val replaced = model.acquire(replacementSession, AudioPresentationSettings.DEFAULT)
            assertEquals(rear.physicalIndex, replaced.audio.selectedOutput)
            assertNull("No invented offline catalog", replaced.audio.selectedFader())
            assertEquals(5.0, replaced.sonar.snapshot.display.gainDb, 0.0)
            assertNull(replacementSession.audio.state.value.pending)
            awaitSavedSelection(rear.physicalIndex)
        } finally { store.clear(); firstSession.close(); replacementSession.close() }

        val restartedStore = ViewModelStore()
        val restartedSession = session("new-process-before-catalog")
        try {
            val restarted = model(restartedStore)
            assertEquals("AUDIO", restarted.page)
            val restored = restarted.acquire(restartedSession, AudioPresentationSettings.DEFAULT)
            assertEquals(rear.physicalIndex, restored.audio.selectedOutput)
            assertNull(restored.audio.selectedFader())
            (restartedSession.audio as MockAudioSource).loadFixture(AudioFixture.DEMO)
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            assertEquals(AudioChannel.REAR, restored.audio.selectedFader()?.gatewayId)
            assertEquals(5.0, restored.sonar.snapshot.display.gainDb, 0.0)
            assertFalse(restored.sonar.snapshot.display.aScope)
            assertNull(restartedSession.audio.state.value.pending)
        } finally { restartedStore.clear(); restartedSession.close() }
    }

    @Test fun missingOrInvalidStoredKnobModeUsesSonarWithoutLosingOtherDisplayPreferences() {
        val file=File(RuntimeEnvironment.getApplication().filesDir,"helm-view-v1.json");file.delete()
        val initialStore=ViewModelStore()
        try{model(initialStore).save("SONAR",AudioViewState(0),SonarViewState(SonarDisplay(gainDb=6.0),SonarKnobMode.SONAR));awaitSavedSelection(0)}finally{initialStore.clear()}
        val complete=org.json.JSONObject(file.readText())
        for(invalid in listOf<String?>(null,"NOT_A_MODE")){
            val data=org.json.JSONObject(complete.toString());if(invalid==null)data.getJSONObject("sonar").remove("knobMode")else data.getJSONObject("sonar").put("knobMode",invalid)
            file.writeText(data.toString())
            val store=ViewModelStore();val source=session("fallback-${invalid?:"missing"}")
            try{val restored=model(store).acquire(source,AudioPresentationSettings.DEFAULT);assertEquals(SonarKnobMode.SONAR,restored.sonar.knobMode);assertEquals(6.0,restored.sonar.snapshot.display.gainDb,0.0);assertNull(source.audio.state.value.pending)}finally{store.clear();source.close()}
        }
    }

    @Test fun quietDepthVersionThreePersistsSettingsAndLegacyVersionsDefaultWithoutLosingPreferences() {
        val file=File(RuntimeEnvironment.getApplication().filesDir,"helm-view-v1.json");file.delete()
        val store=ViewModelStore()
        try {
            model(store).save("SONAR",AudioViewState(2),SonarViewState(SonarDisplay(gainDb=7.0,aScope=false),
                depthSettings=SonarDepthSettings(SonarDepthMode.DIRECT,17)))
            awaitSavedSelection(2)
        } finally { store.clear() }
        val saved=org.json.JSONObject(file.readText())
        assertEquals(4,saved.getInt("version"))
        assertEquals("DIRECT",saved.getJSONObject("sonar").getJSONObject("depthProcessing").getString("mode"))
        for(version in listOf(3,2,1)) {
            val data=org.json.JSONObject(saved.toString()).put("version",version)
            if(version<3)data.getJSONObject("sonar").remove("depthProcessing")
            file.writeText(data.toString())
            val restoredStore=ViewModelStore();val input=session("quiet-restored-$version")
            try {
                val c=model(restoredStore).acquire(input,AudioPresentationSettings.DEFAULT)
                assertEquals(if(version==3)SonarDepthSettings(SonarDepthMode.DIRECT,17)else SonarDepthSettings(),c.sonar.depthProcessingSettings)
                assertEquals(7.0,c.sonar.snapshot.display.gainDb,0.0);assertFalse(c.sonar.snapshot.display.aScope)
                assertEquals(2,c.audio.selectedOutput);assertNull(c.sonar.depthDisplay.valueM)
                assertEquals(0,c.sonar.model.ring.count);assertNull(input.audio.state.value.pending)
            } finally {restoredStore.clear();input.close()}
        }
    }

    @Test fun sourceReplacementReleasesOldLiveAndFrozenPayloadBeforeReadingTheNextPorts() {
        val store=ViewModelStore();val firstSession=session("memory-old");val nextSession=session("memory-next")
        try{
            val owner=model(store);val first=owner.acquire(firstSession,AudioPresentationSettings.DEFAULT)
            val old=first.sonar.model;old.connect("synthetic retained memory");old.acquisition(true)
            old.updateContext(SonarEpoch("old-memory",1,1),SonarPingSource.OWN)
            repeat(300){i->old.setAnalysis(SonarGeometry(3.072,SonarTxReference.HOST_VERIFIED),SonarBottom(),null,false,
                amplitude=SonarAmplitude(4095,12,true))
                old.frame(SonarFrame(ShortArray(1024){i.toShort()},i.toLong(),1200000L+i*50),pingSource=SonarPingSource.OWN)}
            old.setFreeze(true);val frozen=old.viewRing;val frozenImage=old.viewTimeImageHistory
            assertTrue(old.ring.allocatedSampleBytes>0);assertTrue(old.imageHistory.allocatedSampleBytes>0)
            var reads=0
            val watched=object:No5SourceSession by nextSession{
                override val audio:AudioSourcePort get(){reads++
                    assertEquals(0L,old.ring.allocatedSampleBytes);assertEquals(0L,old.imageHistory.allocatedSampleBytes)
                    assertEquals(0L,frozen.allocatedSampleBytes);assertEquals(0L,frozenImage.allocatedSampleBytes)
                    return nextSession.audio
                }
            }
            val replacement=owner.acquire(watched,AudioPresentationSettings.DEFAULT)
            assertTrue(reads>0);assertNotSame(first,replacement)
            assertEquals(0L,replacement.sonar.model.ring.allocatedSampleBytes)
            assertEquals(0L,replacement.sonar.model.imageHistory.allocatedSampleBytes)
            assertSame(replacement,owner.acquire(watched,AudioPresentationSettings.DEFAULT))
        }finally{store.clear();firstSession.close();nextSession.close()}
    }

    private fun awaitSavedSelection(expected: Int) {
        val file = File(RuntimeEnvironment.getApplication().filesDir, "helm-view-v1.json")
        val deadline = System.nanoTime() + 2_000_000_000L
        while (runCatching { org.json.JSONObject(file.readText()).getInt("selectedFader") }.getOrNull() != expected && System.nanoTime() < deadline) Thread.sleep(5)
        assertEquals("Atomic state writer retained the current owner preference", expected, org.json.JSONObject(file.readText()).getInt("selectedFader"))
    }
}
