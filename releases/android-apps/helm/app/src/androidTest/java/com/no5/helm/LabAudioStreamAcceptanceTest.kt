package com.no5.helm

import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.PixelCopy
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.no5.domain.*
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

/** Explicit live-lab acceptance only. Does not write HOME, access or source settings. */
class LabAudioStreamAcceptanceTest {
    @get:Rule val ui = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private lateinit var activity: MainActivity
    private lateinit var audio: AudioSourcePort
    private val evidence = JSONObject()

    private fun await(message: String, timeout: Long = 20_000, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeout
        while (!condition() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50)
        assertTrue(message, condition())
    }

    private fun save() {
        val dir = File(activity.getExternalFilesDir(null), "audio-stream-20261009").apply { mkdirs() }
        File(dir, "acceptance.json").writeText(evidence.toString(2))
    }

    private fun pixels(name: String, night: Boolean = false) {
        val bitmap = Bitmap.createBitmap(activity.window.decorView.width,
            activity.window.decorView.height, Bitmap.Config.ARGB_8888)
        val expectedSurface = android.graphics.Color.parseColor(if (night) "#090000" else "#f6f8f7")
        var settled = false
        repeat(20) {
            if (settled) return@repeat
            // Compose semantics can settle before Android submits its next draw.
            // Verify real body pixels rather than saving a mixed theme transition.
            ui.mainClock.advanceTimeBy(64)
            ui.waitForIdle()
            SystemClock.sleep(32)
            val done = CountDownLatch(1)
            var result = -1
            instrumentation.runOnMainSync {
                PixelCopy.request(activity.window, bitmap, { result = it; done.countDown() }, Handler(Looper.getMainLooper()))
            }
            assertTrue("Actual window pixels did not arrive", done.await(5, TimeUnit.SECONDS))
            assertEquals(PixelCopy.SUCCESS, result)
            settled = bitmap.getPixel(bitmap.width * 1600 / 2000, bitmap.height * 550 / 1200) == expectedSurface
        }
        assertTrue("Actual AUDIO theme pixels did not settle", settled)
        val dir = File(activity.getExternalFilesDir(null), "audio-stream-20261009").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private fun actualDb() = audio.state.value.dsp.faders.getValue(AudioChannel.MASTER).db!!

    private fun inject(action: Int, down: Long, x: Float, y: Float) {
        val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        try { assertTrue("Android did not accept the input event", instrumentation.uiAutomation.injectInputEvent(event, true)) }
        finally { event.recycle() }
    }

    @Test fun confirmedValuesReachCamillaBeforeFingerRelease() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Live laboratory test needs explicit opt-in", args.getString("no5LabAudioStream") == "true")
        assertEquals("Only the normal laboratory AVD is allowed", "HeaderFixture_API29_ARM64", args.getString("no5TargetAvd"))
        assertEquals(29, Build.VERSION.SDK_INT)
        assertTrue("Only an emulator is allowed", Build.HARDWARE.contains("ranchu") || Build.FINGERPRINT.contains("generic"))
        // Live telemetry and the production tickers never become permanently idle.
        // Advance render frames explicitly while transport keeps real wall time.
        ui.mainClock.autoAdvance = false
        // Launch after the opt-in guard; ordinary instrumentation runs cannot contact the lab.
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity {
            activity = it
            assertEquals("Preserve the saved logical TLS origin", "https://no5-marineos-lab.local:9443", it.sourceSessions.config.endpoint)
            audio = it.sourceSessions.session.audio
        }
        evidence.put("endpoint", "https://no5-marineos-lab.local:9443").put("api", Build.VERSION.SDK_INT)
            .put("avd", args.getString("no5TargetAvd")).put("sourceMode", audio.identity.mode.name)
            .put("productionSecurityProfile", true).put("settingsChanged", false)
        var original: Double? = null
        var sliderLabel: String? = null
        try {
            assertEquals(SourceMode.PI, audio.identity.mode)
            ui.mainClock.advanceTimeBy(64)
            ui.onNodeWithContentDescription("AUDIO").performClick()
            ui.mainClock.advanceTimeBy(64)
            await("Actual DSP telemetry did not become live") {
                audio.state.value.mode == OperatingMode.LIVE && audio.state.value.dsp.state == DspState.RUNNING
            }
            evidence.put("streamBefore", audio.faderStream?.status)
            save()
            await("Authenticated WSS did not become ready") { audio.faderStream?.status?.startsWith("WSS READY") == true }
            ui.mainClock.advanceTimeBy(64)
            assertTrue("Lab Main must remain muted during the fader test", audio.state.value.dsp.faders.getValue(AudioChannel.MASTER).muted)
            original = actualDb()
            val node = ui.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress)).fetchSemanticsNodes().maxBy { it.boundsInRoot.center.x }
            sliderLabel = node.config[SemanticsProperties.ContentDescription].single()
            val slider = ui.onNodeWithContentDescription(sliderLabel)
            val progress = node.config[SemanticsProperties.ProgressBarRangeInfo]
            val bounds = node.boundsInRoot
            evidence.put("sliderLabel", sliderLabel).put("sliderBounds", JSONObject()
                .put("left", bounds.left).put("top", bounds.top).put("right", bounds.right).put("bottom", bounds.bottom))
                .put("windowWidth", activity.window.decorView.width).put("windowHeight", activity.window.decorView.height)
            val rangeWidth = progress.range.endInclusive - progress.range.start
            val target = if (original - 6 >= progress.range.start) original - 6 else original + 6
            val fraction = (original.toFloat() - progress.range.start) / rangeWidth
            val inset = 20f / 388f
            val x = bounds.center.x
            val y = bounds.bottom - bounds.height * (inset + fraction * (1 - 2 * inset))
            val distance = -(target - original).toFloat() / rangeWidth * bounds.height * (1 - 2 * inset)
            ui.onNodeWithContentDescription("Day theme").performClick()
            ui.mainClock.advanceTimeBy(64)
            ui.waitForIdle()
            pixels("day-before")
            val samples = JSONArray()
            val distinct = mutableSetOf<Double>()
            val down = SystemClock.uptimeMillis()
            evidence.put("originalDb", original).put("targetDb", target).put("gestureStartedEpochMs", System.currentTimeMillis())
            inject(MotionEvent.ACTION_DOWN, down, x, y)
            try {
                for (step in 1..80) {
                    inject(MotionEvent.ACTION_MOVE, down, x, y + distance * step / 80)
                    SystemClock.sleep(25)
                    ui.mainClock.advanceTimeBy(32)
                    val value = actualDb()
                    distinct.add(value)
                    val state = audio.state.value
                    val semantic = ui.onNodeWithContentDescription(sliderLabel).fetchSemanticsNode().config
                    samples.put(JSONObject().put("elapsedMs", SystemClock.uptimeMillis() - down)
                        .put("confirmedDb", value).put("stream", audio.faderStream?.status)
                        .put("streamActive", audio.faderStream?.active)
                        .put("streamAvailable", audio.faderStream?.available(AudioChannel.MASTER, target))
                        .put("faderWrite", state.capabilities.faderWrite).put("dspState", state.dsp.state.name)
                        .put("dspAgeMs", state.dsp.quality.timestampEpochMs?.let { System.currentTimeMillis() - it })
                        .put("dspFreshness", QualityEvaluator.evaluate(state.dsp.quality, System.currentTimeMillis(), audio.freshnessPolicy).name)
                        .put("sliderDisabled", semantic.contains(SemanticsProperties.Disabled))
                        .put("previewDb", semantic.getOrNull(SemanticsProperties.ProgressBarRangeInfo)?.current)
                        .put("outcomeStatus", audio.outcomes.value?.status?.name)
                        .put("feedback", state.feedback))
                }
            } finally { inject(MotionEvent.ACTION_UP, down, x, y + distance) }
            evidence.put("releasedEpochMs", System.currentTimeMillis()).put("samplesBeforeRelease", samples)
            await("Final fader command was not verified") {
                audio.state.value.pending == null && audio.outcomes.value?.status == AudioCommandStatus.VERIFIED && abs(actualDb() - target) <= 1.0
            }
            val outcome = audio.outcomes.value!!
            evidence.put("terminal", JSONObject().put("requestId", outcome.requestId)
                .put("expectedRevision", outcome.expectedGatewayRevision).put("ackRevision", outcome.acknowledgedGatewayRevision)
                .put("confirmedDb", outcome.confirmedValueDb).put("status", outcome.status.name))
                .put("streamAfter", audio.faderStream?.status).put("confirmedDbBeforeRestore", actualDb())
            save()
            assertTrue("At least two changed DSP values must be confirmed before release", distinct.filter { abs(it - original) > .01 }.size >= 2)
            assertTrue("Actual terminal acknowledgement must carry a newer gateway revision",
                outcome.acknowledgedGatewayRevision!! > outcome.expectedGatewayRevision!!)
            pixels("day-after")
            ui.onNodeWithContentDescription("Night theme").performClick()
            ui.mainClock.advanceTimeBy(64)
            ui.waitForIdle()
            pixels("night-after", night = true)
            evidence.put("nightPixelsVerified", true)
            slider.performSemanticsAction(SemanticsActions.SetProgress) { it(original.toFloat()) }
            ui.mainClock.advanceTimeBy(64)
            await("Original Main dB did not restore through the app") { abs(actualDb() - original) <= .01 && audio.state.value.pending == null }
            assertTrue(audio.state.value.dsp.faders.getValue(AudioChannel.MASTER).muted)
            evidence.put("restoredDb", actualDb()).put("mainMuted", true).put("passed", true)
        } catch (failure: Throwable) {
            evidence.put("passed", false).put("failureClass", failure.javaClass.simpleName)
                .put("streamAtFailure", audio.faderStream?.status).put("dspState", audio.state.value.dsp.state.name)
            // A fresh ready stream can restore a known muted Main after a failed test.
            if (original != null && sliderLabel != null && audio.faderStream?.status?.startsWith("WSS READY") == true) {
                runCatching {
                    ui.onNodeWithContentDescription(sliderLabel).performSemanticsAction(SemanticsActions.SetProgress) { it(original.toFloat()) }
                    ui.mainClock.advanceTimeBy(64)
                    await("Restore", 5_000) { abs(actualDb() - original) <= .01 && audio.state.value.pending == null }
                    evidence.put("restoredAfterFailure", actualDb())
                }
            }
            throw failure
        } finally {
            save()
            scenario.close()
        }
    }
}
