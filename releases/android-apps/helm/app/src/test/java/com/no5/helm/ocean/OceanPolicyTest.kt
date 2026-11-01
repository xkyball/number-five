package com.no5.helm.ocean

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI

class OceanPolicyTest {
    @Test fun northCrossingAndTurnSign() {
        assertEquals(1.0,OceanPresentation.shortestDelta(359.0,0.0),0.0)
        assertEquals(-1.0,OceanPresentation.shortestDelta(0.0,359.0),0.0)
        assertTrue(OceanPresentation.landmarkScreenAngle(20.0,10.0) < OceanPresentation.landmarkScreenAngle(20.0,0.0))
        for (h in listOf(0.0,90.0,180.0,270.0)) assertEquals(OceanPresentation.landmarkScreenAngle(50.0,h),OceanPresentation.landmarkScreenAngle(50.0,h+360),0.0)
    }
    @Test fun invalidAndStaleHeadingFreezeActualOrientation() {
        val p=OceanPresentation("a")
        p.accept(OceanInputs("a",359.0,OceanInputQuality.LIVE))
        p.accept(OceanInputs("a",0.0,OceanInputQuality.LIVE))
        val before=p.frame(0f,.05f,OceanQuality.HIGH)
        p.accept(OceanInputs("a",180.0,OceanInputQuality.STALE))
        repeat(40) { assertEquals(before.headingRadians,p.frame(0f,.05f,OceanQuality.HIGH).headingRadians,0f) }
        p.accept(OceanInputs("a",Double.NaN,OceanInputQuality.LIVE))
        assertEquals(before.headingRadians,p.frame(0f,.05f,OceanQuality.HIGH).headingRadians,0f)
    }
    @Test fun sessionRejectsLateDataAndNewSessionReacquiresWithoutSpin() {
        val p=OceanPresentation("new")
        assertFalse(p.accept(OceanInputs("old",180.0,OceanInputQuality.LIVE,50.0,OceanInputQuality.LIVE,true)))
        assertEquals(0f,p.frame(0f,0f,OceanQuality.HIGH).headingRadians,0f)
        p.accept(OceanInputs("new",270.0,OceanInputQuality.LIVE))
        assertEquals((PI*1.5).toFloat(),p.frame(0f,0f,OceanQuality.HIGH).headingRadians,0f)
    }
    @Test fun zeroWindIsValidMissingWindNeutralAndStalePolicyIndependent() {
        val p=OceanPresentation("s")
        p.accept(OceanInputs("s",windSpeedKn=0.0,weatherQuality=OceanInputQuality.LIVE,windCapability=true))
        repeat(500) { p.frame(0f,.05f,OceanQuality.HIGH) }
        assertEquals(.08f,p.frame(0f,.05f,OceanQuality.HIGH).waveHeight,.001f)
        p.accept(OceanInputs("s",windSpeedKn=40.0,weatherQuality=OceanInputQuality.STALE,windCapability=true,staleWeatherUsable=true))
        repeat(500) { p.frame(0f,.05f,OceanQuality.HIGH) }
        assertEquals(.18f,p.frame(0f,.05f,OceanQuality.HIGH).waveHeight,.001f)
        p.accept(OceanInputs("s",windSpeedKn=Double.NaN,weatherQuality=OceanInputQuality.LIVE,windCapability=true))
        repeat(500) { p.frame(0f,.05f,OceanQuality.HIGH) }
        assertEquals(.18f,p.frame(0f,.05f,OceanQuality.HIGH).waveHeight,.001f)
    }
    @Test fun monotonicClockCapsStallsAndDoesNotAccumulatePause() {
        val c=OceanClock()
        assertEquals(0f,c.tick(1_000_000L,.35f).second,0f)
        assertEquals(.05f,c.tick(1_000_000_000_000L,.35f).second,0f)
        c.pause(); val held=c.tick(Long.MAX_VALUE/2,.35f).first
        assertEquals(held,c.tick(1L,.35f).first,0f)
        repeat(100_000) { c.tick(it*50_000_000L,.35f) }
        val frame=c.tick(100_001L*50_000_000L,.35f)
        assertTrue(frame.first in 0f..(8*PI).toFloat())
    }
    @Test fun allVisibilityAndRuntimeFallbackGates() {
        val base=OceanVisibility(true,true,false,false,BackgroundMode.ANIMATION)
        assertTrue(base.runs(OceanQuality.HIGH,true))
        for(v in listOf(base.copy(helmVisible=false),base.copy(resumed=false),base.copy(night=true),base.copy(reducedMotion=true),base.copy(mode=BackgroundMode.IMAGE),base.copy(mode=BackgroundMode.NONE))) assertFalse(v.runs(OceanQuality.HIGH,true))
        assertFalse(base.runs(OceanQuality.STATIC,true));assertFalse(base.runs(OceanQuality.HIGH,false))
    }
    @Test fun adaptiveQualityRequiresSustainedPressureAndSlowRecovery() {
        val g=OceanQualityGovernor()
        repeat(5){g.observe(1f,true)};assertEquals(OceanQuality.HIGH,g.quality)
        g.observe(1f,true);assertEquals(OceanQuality.LOW,g.quality)
        repeat(10){g.observe(1f,true,severePressure=true)};assertEquals(OceanQuality.STATIC,g.quality)
        repeat(29){g.observe(1f,false)};assertEquals(OceanQuality.STATIC,g.quality)
        g.observe(1f,false);assertEquals(OceanQuality.LOW,g.quality)
        g.observe(1f,false,severeThermal=true);assertEquals(OceanQuality.STATIC,g.quality)
    }
    @Test fun nonFiniteFrameInputCannotReachUniforms() {
        val p=OceanPresentation("s")
        val f=p.frame(Float.NaN,Float.POSITIVE_INFINITY,OceanQuality.HIGH)
        assertTrue(f.timeSeconds.isFinite() && f.waveHeight.isFinite() && f.headingRadians.isFinite())
    }
    @Test fun firstAnimationAndVisibilityResumeAcquireLatestLiveHeadingImmediately() {
        val mapper = OceanPresentation("s")
        mapper.accept(OceanInputs("s", 10.0, OceanInputQuality.LIVE))
        mapper.setAnimationActive(false)
        mapper.accept(OceanInputs("s", 150.0, OceanInputQuality.LIVE))
        assertTrue(mapper.setAnimationActive(true))
        assertEquals(Math.toRadians(150.0).toFloat(), mapper.frame(5f, 0f, OceanQuality.HIGH).headingRadians, 0f)
        // Page/lifecycle, reduced-motion and STATIC all pass the same false -> true gate.
        repeat(3) {
            mapper.setAnimationActive(false)
            val heading = listOf(270.0, 359.0, 0.0)[it]
            mapper.accept(OceanInputs("s", heading, OceanInputQuality.LIVE))
            assertTrue(mapper.setAnimationActive(true))
            assertEquals(Math.toRadians(heading).toFloat(), mapper.frame(5f, 0f, OceanQuality.LOW).headingRadians, 0f)
        }
    }
    @Test fun activationNeverAdoptsStaleLostOrInvalidHeading() {
        for (quality in listOf(OceanInputQuality.STALE, OceanInputQuality.LOST,
            OceanInputQuality.INVALID, OceanInputQuality.UNAVAILABLE)) {
            val mapper = OceanPresentation("s")
            mapper.accept(OceanInputs("s", 10.0, OceanInputQuality.LIVE))
            mapper.setAnimationActive(true)
            val displayed = mapper.frame(1f, 0f, OceanQuality.HIGH).headingRadians
            mapper.setAnimationActive(false)
            mapper.accept(OceanInputs("s", 150.0, OceanInputQuality.LIVE))
            mapper.accept(OceanInputs("s", 180.0, quality))
            mapper.setAnimationActive(true)
            assertEquals(displayed, mapper.frame(1f, 0f, OceanQuality.HIGH).headingRadians, 0f)
        }
    }
    @Test fun ordinaryFramesAndQualityChangesKeepSmoothingWhileResumePreservesPhase() {
        val mapper = OceanPresentation("s")
        val clock = OceanClock()
        mapper.accept(OceanInputs("s", 10.0, OceanInputQuality.LIVE))
        mapper.setAnimationActive(true)
        clock.tick(0L, .35f)
        val (phase, dt) = clock.tick(50_000_000L, .35f)
        mapper.accept(OceanInputs("s", 150.0, OceanInputQuality.LIVE))
        assertFalse(mapper.setAnimationActive(true))
        val before = mapper.frame(phase, dt, OceanQuality.HIGH)
        assertTrue(before.headingRadians > Math.toRadians(10.0).toFloat())
        assertTrue(before.headingRadians < Math.toRadians(150.0).toFloat())
        assertFalse(mapper.setAnimationActive(true))
        assertEquals(before.headingRadians, mapper.frame(phase, 0f, OceanQuality.LOW).headingRadians, 0f)
        mapper.setAnimationActive(false)
        clock.pause()
        mapper.accept(OceanInputs("s", 270.0, OceanInputQuality.LIVE))
        mapper.setAnimationActive(true)
        val (resumedPhase, resumedDt) = clock.tick(9_000_000_000L, .35f)
        val resumed = mapper.frame(resumedPhase, resumedDt, OceanQuality.LOW)
        assertEquals(phase, resumed.timeSeconds, 0f)
        assertEquals(0f, resumedDt, 0f)
        assertEquals(Math.toRadians(270.0).toFloat(), resumed.headingRadians, 0f)
    }
}
