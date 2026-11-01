package com.no5.helm

import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.PixelCopy
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.no5.domain.*
import com.no5.ui.No5Text
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/** Explicit live receiver acceptance; no access, HOME or source-setting changes. */
class LabSpotifyAcceptanceTest {
    @get:Rule val ui = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private lateinit var activity: MainActivity
    private lateinit var audio: AudioSourcePort
    private val evidence = JSONObject()
    private val commands = JSONArray()

    private fun await(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (!condition() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(25)
        assertTrue(message, condition())
        ui.mainClock.advanceTimeBy(64)
    }

    private fun verified(command: AudioCommand, previousId: String?) {
        await("Actual command was not verified") {
            audio.state.value.pending == null && audio.outcomes.value?.let {
                it.requestId != previousId && it.command == command && it.status == AudioCommandStatus.VERIFIED
            } == true
        }
        val outcome = audio.outcomes.value!!
        assertTrue(outcome.acknowledgedGatewayRevision!! > outcome.expectedGatewayRevision!!)
        commands.put(JSONObject().put("command", when (command) {
            is AudioCommand.Transport -> command.action.name
            is AudioCommand.Fader -> "MASTER_FADER"
            else -> "OTHER"
        }).put("requestId", outcome.requestId).put("status", outcome.status.name)
            .put("expectedRevision", outcome.expectedGatewayRevision)
            .put("ackRevision", outcome.acknowledgedGatewayRevision)
            .put("confirmedDb", outcome.confirmedValueDb))
    }

    private fun nightPixels() {
        ui.onNodeWithContentDescription("Night theme").performClick()
        val bitmap = Bitmap.createBitmap(activity.window.decorView.width, activity.window.decorView.height, Bitmap.Config.ARGB_8888)
        try {
            var settled = false
            repeat(30) {
                if (settled) return@repeat
                ui.mainClock.advanceTimeBy(64); ui.waitForIdle(); SystemClock.sleep(32)
                val done = CountDownLatch(1); var result = -1
                instrumentation.runOnMainSync {
                    PixelCopy.request(activity.window, bitmap, { result = it; done.countDown() }, Handler(Looper.getMainLooper()))
                }
                assertTrue(done.await(5, TimeUnit.SECONDS)); assertEquals(PixelCopy.SUCCESS, result)
                settled = bitmap.getPixel(bitmap.width * 1600 / 2000, bitmap.height * 550 / 1200) == android.graphics.Color.parseColor("#090000")
            }
            assertTrue("Actual night AUDIO pixels did not settle", settled)
            val dir = File(activity.getExternalFilesDir(null), "audio-spotify-20261009").apply { mkdirs() }
            File(dir, "night-playing.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            evidence.put("nightPixelsVerified", true)
        } finally { bitmap.recycle() }
    }

    @Test fun pausedContextResumesAndAppFaderKeepsOneMainAuthority() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Live Spotify acceptance needs explicit opt-in", args.getString("no5LabSpotify") == "true")
        assertEquals("HeaderFixture_API29_ARM64", args.getString("no5TargetAvd"))
        assertEquals(29, Build.VERSION.SDK_INT)
        assertTrue(Build.HARDWARE.contains("ranchu") || Build.FINGERPRINT.contains("generic"))
        ui.mainClock.autoAdvance = false
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity {
            activity = it
            assertEquals("https://no5-marineos-lab.local:9443", it.sourceSessions.config.endpoint)
            audio = it.sourceSessions.session.audio
        }
        var originalDb: Double? = null
        var originalMute: Boolean? = null
        var masterLabel: String? = null
        var volumeChanged = false
        try {
            assertEquals(SourceMode.PI, audio.identity.mode)
            ui.mainClock.advanceTimeBy(64); ui.onNodeWithContentDescription("AUDIO").performClick()
            await("Real selected Spotify client did not become playable") {
                audio.state.value.source.connected && audio.state.value.source.active &&
                    audio.state.value.media.state == MediaState.PLAYING && audio.state.value.media.trackId != null &&
                    audio.available(AudioCommand.Transport(TransportAction.PAUSE))
            }
            evidence.put("endpoint", "https://no5-marineos-lab.local:9443").put("avd", "HeaderFixture_API29_ARM64")
                .put("settingsChanged", false).put("productionSecurityProfile", true)
            val track = audio.state.value.media.trackId
            var previous = audio.outcomes.value?.requestId
            ui.onNodeWithContentDescription(No5Text.ui("Pause")).assertIsEnabled().performClick()
            verified(AudioCommand.Transport(TransportAction.PAUSE), previous)
            assertEquals(MediaState.PAUSED, audio.state.value.media.state)
            assertEquals(track, audio.state.value.media.trackId)
            assertTrue(audio.state.value.source.connected && audio.state.value.source.active)
            ui.onNodeWithContentDescription(No5Text.ui("Play")).assertIsEnabled()
            evidence.put("pausedContextRetained", true)
            previous = audio.outcomes.value?.requestId
            ui.onNodeWithContentDescription(No5Text.ui("Play")).performClick()
            verified(AudioCommand.Transport(TransportAction.PLAY), previous)
            assertEquals(MediaState.PLAYING, audio.state.value.media.state)
            await("Authenticated fader stream not ready") { audio.faderStream?.status?.startsWith("WSS READY") == true }
            val master = audio.state.value.dsp.faderControls.single { it.gatewayId == AudioChannel.MASTER }
            originalDb = master.db!!; originalMute = master.muted
            val target = originalDb - master.limit!!.stepDb
            assertTrue(target >= master.limit!!.minDb)
            masterLabel = ui.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress)).fetchSemanticsNodes()
                .maxBy { it.boundsInRoot.center.x }.config[SemanticsProperties.ContentDescription].single()
            previous = audio.outcomes.value?.requestId
            ui.onNodeWithContentDescription(masterLabel).performSemanticsAction(SemanticsActions.SetProgress) { it(target.toFloat()) }
            volumeChanged = true
            verified(AudioCommand.Fader(AudioChannel.MASTER, target), previous)
            assertEquals(target, audio.state.value.dsp.faders.getValue(AudioChannel.MASTER).db!!, .01)
            assertEquals(originalMute, audio.state.value.dsp.faders.getValue(AudioChannel.MASTER).muted)
            previous = audio.outcomes.value?.requestId
            ui.onNodeWithContentDescription(masterLabel).performSemanticsAction(SemanticsActions.SetProgress) { it(originalDb.toFloat()) }
            verified(AudioCommand.Fader(AudioChannel.MASTER, originalDb), previous)
            volumeChanged = false
            evidence.put("originalDb", originalDb).put("restoredDb", originalDb).put("mutePreserved", true)
            nightPixels()
            evidence.put("passed", true)
        } catch (failure: Throwable) {
            evidence.put("passed", false).put("failureClass", failure.javaClass.simpleName)
            throw failure
        } finally {
            // Restore only known confirmed local test changes, never replay an unknown command.
            if (audio.state.value.media.state == MediaState.PAUSED && audio.available(AudioCommand.Transport(TransportAction.PLAY))) {
                runCatching { ui.onNodeWithContentDescription(No5Text.ui("Play")).performClick(); await("Restore playback") { audio.state.value.media.state == MediaState.PLAYING } }
            }
            if (volumeChanged && originalDb != null && masterLabel != null && audio.state.value.pending == null &&
                audio.outcomes.value?.status == AudioCommandStatus.VERIFIED) {
                runCatching { ui.onNodeWithContentDescription(masterLabel).performSemanticsAction(SemanticsActions.SetProgress) { it(originalDb.toFloat()) } }
            }
            evidence.put("commands", commands).put("phoneVolumeAcceptance", false)
            val dir = File(activity.getExternalFilesDir(null), "audio-spotify-20261009").apply { mkdirs() }
            File(dir, "acceptance.json").writeText(evidence.toString(2))
            scenario.close()
        }
    }
}
