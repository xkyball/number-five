package com.no5.helm

import com.no5.domain.*
import org.junit.Assert.*
import org.junit.Test

class HelmRetainedTest {
    private val policy=FreshnessPolicy(5000,30000)
    private val quality=TelemetryQuality(TelemetryStatus.LIVE,100_000,"fixture",false)
    @Test fun freshnessUsesStrictBoundariesAndStopsAfterLost() {
        val state=HelmState(quality=quality)
        val weather=WeatherSnapshot.unavailable()
        assertEquals(105001L,HelmFreshnessDeadline.next(state,weather,policy,policy,100000))
        assertEquals(130001L,HelmFreshnessDeadline.next(state,weather,policy,policy,105001))
        assertNull(HelmFreshnessDeadline.next(state,weather,policy,policy,130001))
    }
    @Test fun fieldsAndFutureStampsHaveTheirOwnDeadlines() {
        val state=HelmState(quality=quality,telemetryFields=mapOf(
            "portOilBar" to TelemetryValue(3.0,quality.copy(timestampEpochMs=98000)),
            "heading" to TelemetryValue(30.0,quality.copy(timestampEpochMs=110000))))
        assertEquals(103001L,HelmFreshnessDeadline.next(state,WeatherSnapshot.unavailable(),policy,policy,100000))
        assertEquals(105000L,HelmFreshnessDeadline.next(state,WeatherSnapshot.unavailable(),policy,policy,103001))
    }
    @Test fun motionValuesMatchLegacyButContainNoWeatherTripOrPosition() {
        for(status in TelemetryStatus.entries)for(units in SpeedUnit.entries) {
            val raw=HelmState(portRpm=3456.5,starboardRpm=1.0,portTrim=13.3,starboardTrim=88.8,
                speed=4.5,heading=359.4,courseOverGroundDegrees=2.5,quality=quality.copy(status=status))
            val displayUnits=DisplayUnits(speed=units)
            val full=renderValues(raw,100000,policy,units=displayUnits)
            val motion=motionPresentation(raw,100000,policy,displayUnits).values
            assertEquals(full.portRpm,motion.portRpm);assertEquals(full.heading,motion.heading)
            assertEquals(full.headingAvailable,motion.headingAvailable);assertEquals(full.quality,motion.quality)
            for((key,value) in motion.text)assertEquals(key,full.text[key],value)
            assertFalse(motion.text.containsKey("deck-airTemp"));assertFalse(motion.text.containsKey("deck-latitude"))
        }
    }
    @Test fun rpmAndHeadingDoNotChangeVisibleSlowPresentation() {
        val raw=HelmState(portRpm=1000.0,starboardRpm=1000.0,heading=20.0,quality=quality)
        fun slow(s:HelmState)=slowPresentation(s,100000,policy,WeatherSnapshot.unavailable(),policy,DisplayUnits())
        assertEquals(slow(raw),slow(raw.copy(portRpm=2222.0,starboardRpm=3333.0,heading=120.0)))
        assertNotEquals(slow(raw),slow(raw.copy(portCoolantC=99.0)))
    }
    @Test fun schedulingHintSettlesWithoutChangingInterpolation() {
        val initial=HelmState(portRpm=1000.0,running=true,quality=quality)
        val model=HelmPresentationModel(initial,0)
        assertFalse(model.isTransitioning())
        model.accept(initial.copy(portRpm=2000.0,quality=quality.copy(timestampEpochMs=100050)),50)
        assertTrue(model.isTransitioning());assertEquals(1500.0,model.render(100).portRpm,0.0)
        model.render(150);assertFalse(model.isTransitioning())
        model.accept(initial.copy(portRpm=3000.0,quality=quality.copy(timestampEpochMs=100100)),160)
        model.render(170,TelemetryStatus.STALE);assertFalse(model.isTransitioning())
    }
}
