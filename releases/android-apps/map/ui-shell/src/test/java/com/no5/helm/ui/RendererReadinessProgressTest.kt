package com.no5.helm.ui

import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.viewinterop.AndroidView
import com.no5.helm.RendererReleaseReadinessGate
import com.no5.helm.rendererReleaseReadinessUi
import com.no5.helm.chart.*
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Actual Compose1.10.3 rule-clock counterexample, not a renderer/model timing benchmark.
 * The View is deliberately plain: these tests create no native surface, JNI, ACK or fake FULL.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "w1000dp-h600dp-land-xhdpi")
class RendererReadinessProgressTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var activity: ActivityController<ComponentActivity>
    private val requested = mutableStateOf(false)
    private val views = AtomicInteger()
    private val frameEffectCompleted = AtomicBoolean()

    @Before fun setup() {
        activity = Robolectric.buildActivity(ComponentActivity::class.java)
        activity.get().setTheme(android.R.style.Theme_Material_NoActionBar)
        activity.setup().visible().windowFocusChanged(true)
        activity.get().setContent {
            if (requested.value) {
                AndroidView(factory = { context -> View(context).also { views.incrementAndGet() } })
                LaunchedEffect(Unit) {
                    withFrameNanos { } // The actual rule's existing frame clock, not a test-made clock.
                    frameEffectCompleted.set(true)
                }
            }
        }
        compose.waitForIdle()
    }

    @After fun close() {
        compose.mainClock.autoAdvance = true
        if (::activity.isInitialized) activity.pause().stop().destroy()
    }

    @Test fun wallSleepStallsPendingViewAndFrameEffectButPublicSynchronizationProgressesBoth() {
        compose.runOnUiThread { requested.value = true }
        val before = compose.mainClock.currentTime
        Thread.sleep(50) // The old readiness loop yields wall time but never pumps the Compose clock.
        assertEquals(before, compose.mainClock.currentTime)
        assertEquals(0, views.get())
        assertFalse(frameEffectCompleted.get())

        // The same public cross-Android1.10.3 synchronization used before the corrected
        // pre-owner wait; this also services Robolectric's paused Android message queue.
        compose.waitForIdle()
        compose.waitUntil(timeoutMillis = 1_000) { views.get() == 1 && frameEffectCompleted.get() }
        assertTrue(compose.mainClock.currentTime > before)
        assertEquals(1, views.get())
        assertTrue(frameEffectCompleted.get())
    }

    @Test fun disabledAutoAdvanceDoesNotManufactureThePendingReadiness() {
        compose.mainClock.autoAdvance = false
        compose.runOnUiThread { requested.value = true }
        val before = compose.mainClock.currentTime
        assertThrows(ComposeTimeoutException::class.java) {
            compose.waitUntil(timeoutMillis = 50) { views.get() == 1 && frameEffectCompleted.get() }
        }
        assertEquals(before, compose.mainClock.currentTime)
        assertEquals(0, views.get())
        assertFalse(frameEffectCompleted.get())
        compose.mainClock.autoAdvance = true // Only restore this test-owned rule, never a product setting.
        compose.waitForIdle()
        compose.waitUntil(timeoutMillis = 1_000) { views.get() == 1 && frameEffectCompleted.get() }
    }

    @Test fun progressingClockCannotSatisfyAStillFalseExternalReadyCondition() {
        compose.runOnUiThread { requested.value = true }
        val actualFull = AtomicBoolean(false) // Independent missing producer evidence stays false.
        val before = compose.mainClock.currentTime
        compose.waitForIdle()
        assertThrows(ComposeTimeoutException::class.java) {
            compose.waitUntil(timeoutMillis = 50) {
                views.get() == 1 && frameEffectCompleted.get() && actualFull.get()
            }
        }
        assertTrue(compose.mainClock.currentTime > before)
        assertTrue(frameEffectCompleted.get())
        assertFalse(actualFull.get())
    }

    private fun engineReadyFixture(): ChartEngineStatus {
        val view = ChartViewport(width = 1000, height = 600)
        return ChartEngineStatus(initialized = true, chartLoaded = true, surfaceAttached = true,
            viewport = view, confirmedFrame = ChartFrame(12, "synthetic-readiness-only",
                ChartSurfaceGeometry.fit(1000,600), view, sourceGeneration = 3), chartSourceGeneration = 3,
            surfaceGeneration = 7, residentCatalogActive = true, residentCatalogGeneration = "synthetic-readiness",
            residentCameraReady = true, residentStartState = ChartResidentStartState.READY)
    }

    @Test fun jointGateWaitsForActuallyDelayedComposeWarningRemovalAfterExternalEngineReady() {
        val engine = AtomicReference(engineReadyFixture())
        val release = CompletableDeferred<Unit>()
        val warning = mutableStateOf(true)
        activity.get().setContent {
            Box {
                AndroidView(factory = { View(it) }, modifier = Modifier.testTag("renderer-slot"))
                if (warning.value) BasicText("Earlier composition is still loading", Modifier.testTag("chart-warning"))
                LaunchedEffect(Unit) {
                    release.await()
                    withFrameNanos { }
                    warning.value = false
                }
            }
        }
        compose.waitForIdle()
        val gate = RendererReleaseReadinessGate(1_000)
        assertTrue(gate.engineReady(engine.get(), 1_001))
        val pendingUi = rendererReleaseReadinessUi(compose) // The actual collector used by Test-APK Support.
        assertTrue("Engine readiness must not hide the actual composed warning", "chart-warning" in pendingUi.blockingTags)
        assertFalse(gate.ready(engine.get(), pendingUi, engine.get(), 1_001))
        val before = compose.mainClock.currentTime
        release.complete(Unit)
        compose.waitUntil(timeoutMillis = 1_000) {
            val first = engine.get()
            gate.ready(first, rendererReleaseReadinessUi(compose), engine.get(), 1_002)
        }
        assertTrue(compose.mainClock.currentTime > before)
        assertFalse(warning.value)
        val actualUi = rendererReleaseReadinessUi(compose)
        assertTrue(actualUi.rendererPresent)
        assertTrue(actualUi.blockingTags.isEmpty())
        assertEquals(61_000L, gate.deadline)
    }

    @Test fun actualComposeFrameCannotExtendDeadlineOrReplaceForeignOwner() {
        for (deadlineExpired in listOf(true, false)) {
            val initial = engineReadyFixture()
            val engine = AtomicReference(initial)
            val now = AtomicLong(1_000)
            val release = CompletableDeferred<Unit>()
            activity.get().setContent {
                AndroidView(factory = { View(it) }, modifier = Modifier.testTag("renderer-slot"))
                LaunchedEffect(release) {
                    release.await(); withFrameNanos { }
                    if (deadlineExpired) now.set(61_001)
                    else engine.set(initial.copy(rendererBenchmark = initial.rendererBenchmark.copy(active = true, runId = "foreign")))
                }
            }
            compose.waitForIdle()
            val gate = RendererReleaseReadinessGate(1_000)
            assertTrue(gate.engineReady(engine.get(), now.get()))
            release.complete(Unit)
            compose.waitForIdle()
            val ui = rendererReleaseReadinessUi(compose) // Same actual collector as Support.ready().
            assertThrows(IllegalStateException::class.java) { gate.ready(initial, ui, engine.get(), now.get()) }
            assertEquals(61_000L, gate.deadline)
            if (deadlineExpired) assertEquals(61_001L, now.get())
            else {
                assertTrue(engine.get().rendererBenchmark.active)
                assertEquals("foreign", engine.get().rendererBenchmark.runId)
            }
        }
    }
}
