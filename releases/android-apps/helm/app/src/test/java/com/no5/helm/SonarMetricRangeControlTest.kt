package com.no5.helm

import android.os.Looper
import android.os.SystemClock
import com.no5.control.NormalizedKnobInput.LEFT_CW
import com.no5.domain.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.lumina.control.commands.v1.CommandRing
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** Synthetic, source-owned captures: no host/Pico writes and no assertion about acoustic depth. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
@LooperMode(LooperMode.Mode.PAUSED)
class SonarMetricRangeControlTest {
    private class CaptureSource : SonarSourcePort {
        override val identity = SourceIdentity("metric-range-fixture", SourceMode.PI, "SYNTHETIC RANGE TEST")
        override val status = MutableSourceFeed(SonarSourceStatus(identity, 1, connected = true, running = true))
        override val capabilities = MutableSourceFeed(SonarSourceCapabilities(identity))
        override val frames = MutableSourceFeed<SonarSourceFrame?>(null)
        private var ping = 0L
        var acquisitionCommands = 0
        fun capture(maximum: Double?) {
            val id = ++ping
            frames.emit(SonarSourceFrame(identity, status.value.generation,
                SonarFrame(ShortArray(1024) { ((it + id) % 4096).toShort() }, id, 1_000_000L + id * 100),
                geometry = if (maximum == null) SonarGeometry(null, SonarTxReference.UNVERIFIED)
                    else SonarGeometry(maximum, SonarTxReference.HOST_VERIFIED),
                amplitude = SonarAmplitude(4095, 12, true), pingSource = SonarPingSource.OWN))
        }
        fun nextGeneration() { status.emit(status.value.copy(generation = status.value.generation + 1)) }
        override fun start(request: SonarAcquisitionRequest): SonarAcquisitionOutcome {
            acquisitionCommands++
            return SonarAcquisitionOutcome.Rejected(identity.sessionId, request.requestId, SonarRejection.UNSUPPORTED, "TEST ONLY")
        }
        override fun stop(request: SonarAcquisitionRequest) = start(request)
        override fun tick() = Unit
        override fun close() { status.close(); capabilities.close(); frames.close() }
    }

    private fun assertMeters(c: SonarNativeController, desired: Double, effective: Double = desired) {
        assertEquals(desired, c.exportPresentationState().display.rangeM!!, 1e-10)
        assertEquals(effective, c.snapshot.display.rangeM!!, 1e-10)
        assertEquals(0.0, c.snapshot.display.cropStart, 0.0)
        assertEquals(effective / c.model.viewMetersPerBin!!, c.snapshot.display.cropEnd, 1e-8)
        assertEquals(SonarRangeMode.MANUAL, c.snapshot.display.rangeMode)
    }

    private fun result(controls: SonarOwnedControls, age: Int = 0): CommandRing.Entry =
        CommandRing.Entry().also { assertTrue(controls.diagnostic(age, it)) }

    @Test fun touchFullOriginCropAbsoluteRangeAndRelativeKnobUseTheSameConfirmedMeterTarget() {
        val touchSource = CaptureSource(); val touch = SonarNativeController(touchSource) { 1_001_000L }
        val knobSource = CaptureSource(); val knob = SonarNativeController(knobSource) { 1_001_000L }
        val actor = SonarControlActor(knob)
        try {
            touchSource.capture(3.072); knobSource.capture(3.072)
            val target = 3.072 * .8
            val raw = knob.model.latest!!.samples
            touch.crop(0.0, 1023.0 * .8)
            actor.observe()
            val id = actor.action(LEFT_CW).submit(1, SystemClock.uptimeMillis())
            assertTrue(id > 0); assertNull(knob.snapshot.display.rangeM)
            shadowOf(Looper.getMainLooper()).idle()
            assertMeters(touch, target); assertMeters(knob, target)
            assertEquals(touch.snapshot.display, knob.snapshot.display)
            assertEquals(CommandRing.State.APPLIED, result(knob.controls).state)
            touch.range(target); assertMeters(touch, target)
            assertArrayEquals(raw, knob.model.latest!!.samples)
            assertEquals(0, touchSource.acquisitionCommands + knobSource.acquisitionCommands)
        } finally { actor.close(); touch.dispose(); knob.dispose() }
    }

    @Test fun pendingMeterStepsAccumulateWithoutBeingMultipliedByANewCaptureGeometry() {
        val source = CaptureSource(); val c = SonarNativeController(source) { 1_001_000L }
        val actor = SonarControlActor(c)
        try {
            source.capture(3.072); actor.observe()
            val action = actor.action(LEFT_CW)
            val first = action.submit(1, SystemClock.uptimeMillis())
            source.capture(24.576)
            val last = action.submit(2, SystemClock.uptimeMillis())
            assertTrue(last > first); assertNull(c.snapshot.display.rangeM)
            shadowOf(Looper.getMainLooper()).idle()
            assertMeters(c, 3.072 * .8 * .8)
            assertEquals(CommandRing.State.SUPERSEDED, result(c.controls, 1).state)
            assertEquals(CommandRing.State.APPLIED, result(c.controls).state)
        } finally { actor.close(); c.dispose() }
    }

    @Test fun requestedMeterViewportSurvivesFactorEightCaptureChangesAndReturnsFromShortCoverage() {
        for (desired in listOf(1.2676, 10.0)) {
            val source = CaptureSource(); val c = SonarNativeController(source) { 1_001_000L }
            try {
                source.capture(24.576); c.range(desired); assertMeters(c, desired)
                source.capture(3.072); assertMeters(c, desired, minOf(desired, 3.072))
                source.capture(24.576); assertMeters(c, desired)
                source.capture(3.072); assertMeters(c, desired, minOf(desired, 3.072))
                assertNull(c.snapshot.depthM)
                assertEquals(0, source.acquisitionCommands)
            } finally { c.dispose() }
        }
    }

    @Test fun explicitMeterWishBeyondCurrentCaptureIsSavedWhileOnlyItsEffectiveViewIsLimited() {
        val source = CaptureSource(); val c = SonarNativeController(source) { 1_001_000L }
        try {
            source.capture(3.072); c.range(10.0)
            assertMeters(c, 10.0, 3.072)
            source.capture(24.576); assertMeters(c, 10.0)
        } finally { c.dispose() }
    }

    @Test fun pendingUnverifiedBinStepsStayBinsWhenTxGeometryAppearsBeforeTheirEffect() {
        val source = CaptureSource(); val c = SonarNativeController(source) { 1_001_000L }
        val actor = SonarControlActor(c)
        try {
            source.capture(null); actor.observe()
            actor.action(LEFT_CW).submit(1, SystemClock.uptimeMillis())
            actor.action(LEFT_CW).submit(2, SystemClock.uptimeMillis())
            source.capture(24.576)
            shadowOf(Looper.getMainLooper()).idle()
            assertNull(c.snapshot.display.rangeM); assertNull(c.exportPresentationState().display.rangeM)
            assertEquals(1023.0 * .8 * .8, c.snapshot.display.cropEnd, 1e-9)
            assertEquals(CommandRing.State.APPLIED, result(c.controls).state)
        } finally { actor.close(); c.dispose() }
    }

    @Test fun pendingBinGoalIsNotMistakenForMetersWhenANewVerifiedStepSupersedesIt() {
        val source = CaptureSource(); val c = SonarNativeController(source) { 1_001_000L }
        val actor = SonarControlActor(c)
        try {
            source.capture(null); actor.observe()
            actor.action(LEFT_CW).submit(1, SystemClock.uptimeMillis())
            source.capture(3.072)
            actor.action(LEFT_CW).submit(2, SystemClock.uptimeMillis())
            shadowOf(Looper.getMainLooper()).idle()
            assertMeters(c, 3.072 * .8)
            assertEquals(CommandRing.State.SUPERSEDED, result(c.controls, 1).state)
        } finally { actor.close(); c.dispose() }
    }

    @Test fun oldBinPreferencesAndNonzeroCropRemainExplicitBinsAcrossGeometryChanges() {
        val source = CaptureSource(); val c = SonarNativeController(source) { 1_001_000L }
        try {
            val legacy = SonarViewState(SonarDisplay(rangeM = null, cropStart = 0.0, cropEnd = 400.0))
            c.restorePresentationState(legacy)
            for (maximum in listOf(null, 3.072, 24.576)) {
                source.capture(maximum)
                assertNull(c.snapshot.display.rangeM); assertNull(c.exportPresentationState().display.rangeM)
                assertEquals(400.0, c.snapshot.display.cropEnd, 0.0)
            }
            val id = c.controls.crop(100.0, 600.0)
            source.capture(3.072); c.controls.execute(id)
            assertNull(c.snapshot.display.rangeM)
            assertEquals(100.0, c.snapshot.display.cropStart, 0.0)
            assertEquals(600.0, c.snapshot.display.cropEnd, 0.0)
            source.capture(24.576)
            assertNull(c.exportPresentationState().display.rangeM)
            assertEquals(100.0, c.snapshot.display.cropStart, 0.0)
            assertEquals(600.0, c.snapshot.display.cropEnd, 0.0)
        } finally { c.dispose() }
    }

    @Test fun unverifiedTouchAndSubThirtyTwoBinExplicitCropDoNotInventUnitsOrRangeFloors() {
        val source = CaptureSource(); val c = SonarNativeController(source) { 1_001_000L }
        try {
            source.capture(null); c.crop(0.0, 10.0)
            assertNull(c.snapshot.display.rangeM); assertEquals(10.0, c.snapshot.display.cropEnd, 0.0)
            c.adjustRange(1)
            assertNull(c.snapshot.display.rangeM); assertEquals(32.0, c.snapshot.display.cropEnd, 0.0)
            source.capture(3.072); c.crop(0.0, 10.0)
            assertMeters(c, 10.0 * 3.072 / 1023)
            assertEquals(10.0, c.snapshot.display.cropEnd, 1e-10)
        } finally { c.dispose() }
    }

    @Test fun sourceGenerationFocusAndDeadlineStillCancelAcceptedMetricCommandsWithoutEffects() {
        val source = CaptureSource(); val c = SonarNativeController(source) { 1_001_000L }
        var uptime = 100L
        val controls = SonarOwnedControls(c) { uptime }
        controls.prepareBindings()
        try {
            source.capture(24.576); controls.observe()
            val before = c.snapshot.display
            val expired = controls.adjustRange(1, 1, true)
            uptime += 2_000; controls.execute(expired)
            assertEquals(CommandRing.State.CANCELLED, result(controls).state)
            assertEquals(before, c.snapshot.display)
            val focus = controls.adjustRange(1, 2, true)
            controls.cancel(); controls.execute(focus)
            assertEquals(CommandRing.State.CANCELLED, result(controls).state)
            assertEquals(CommandRing.Reason.FOCUS_CHANGED, result(controls).reason)
            assertEquals(before, c.snapshot.display)
            val generation = controls.adjustRange(1, 3, true)
            source.nextGeneration(); source.capture(24.576); controls.observe(); controls.execute(generation)
            assertEquals(CommandRing.State.CANCELLED, result(controls).state)
            assertEquals(CommandRing.Reason.SOURCE_CHANGED, result(controls).reason)
            assertNull(c.snapshot.display.rangeM)
            assertNull(c.exportPresentationState().display.rangeM)
        } finally { controls.close(); c.dispose() }
    }

    @Test fun frozenViewKeepsItsConfirmedGeometryWhileAcquisitionMovesAndMeterIntentReturnsOnThaw() {
        val source = CaptureSource(); val c = SonarNativeController(source) { 1_001_000L }
        try {
            source.capture(24.576); c.range(10.0); c.freeze()
            val frozen = c.model.viewRing
            val samples = frozen.atAge(0)!!.samples
            source.capture(3.072)
            assertTrue(c.snapshot.display.freeze); assertSame(frozen, c.model.viewRing)
            assertEquals(24.576 / 1023, c.model.viewMetersPerBin!!, 1e-12)
            c.adjustRange(1); assertMeters(c, 8.0)
            assertTrue(c.snapshot.display.freeze); assertArrayEquals(samples, c.model.viewRing.atAge(0)!!.samples)
            assertEquals(2, c.model.ring.count); assertTrue(c.running)
            c.freeze(); assertFalse(c.snapshot.display.freeze)
            assertMeters(c, 8.0, 3.072)
            source.capture(24.576); assertMeters(c, 8.0)
            assertEquals(0, source.acquisitionCommands)
        } finally { c.dispose() }
    }
}
