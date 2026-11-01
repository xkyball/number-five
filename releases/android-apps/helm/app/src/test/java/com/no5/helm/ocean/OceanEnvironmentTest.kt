package com.no5.helm.ocean

import com.no5.domain.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import kotlin.math.*

class OceanEnvironmentTest {
    private fun epoch(s: String)=Instant.parse(s).toEpochMilli()
    private fun sun(s: String,lat: Double=0.0,lon: Double=0.0)=OceanSolar.direction(epoch(s),lat,lon)!!
    @Test fun equinoxMorningEastNoonUpEveningWest() {
        assertTrue(sun("2026-03-20T06:00:00Z").sunEast>.99)
        assertTrue(sun("2026-03-20T12:00:00Z").sunUp>.998)
        assertTrue(sun("2026-03-20T18:00:00Z").sunEast<-.99)
        assertTrue(sun("2026-03-20T00:00:00Z").sunUp<-.998)
    }
    @Test fun solsticePolesLongitudeAndLeapDayAreFinite() {
        assertTrue(sun("2026-06-21T12:00:00Z",90.0).sunUp>.39)
        assertTrue(sun("2026-12-21T12:00:00Z",90.0).sunUp<-.39)
        assertTrue(sun("2026-03-20T06:00:00Z",0.0,90.0).sunUp>.998)
        for(lat in listOf(-90.0,-34.3,0.0,90.0)) for(lon in listOf(-180.0,0.0,180.0)) {
            val e=sun("2024-02-29T18:03:00Z",lat,lon)
            assertEquals(1f,sqrt(e.sunEast*e.sunEast+e.sunUp*e.sunUp+e.sunNorth*e.sunNorth),.00001f)
        }
        assertNull(OceanSolar.direction(-1,0.0,0.0));assertNull(OceanSolar.direction(0,Double.NaN,0.0))
        assertNull(OceanSolar.direction(0,91.0,0.0))
    }
    @Test fun windFromBecomesWorldPropagationAndHeadingDoesNotRotateTheWorld() {
        for(from in listOf(0.0,90.0,180.0,270.0,360.0)) {
            val p=OceanPresentation("s")
            p.accept(OceanInputs("s",windDirectionFromDegrees=from,windCapability=true,weatherQuality=OceanInputQuality.LIVE))
            val e=p.frame(0f,0f,OceanQuality.HIGH).environment
            assertEquals(sin(Math.toRadians(from+180)),sin(e.windToRadians.toDouble()),.000001)
            p.accept(OceanInputs("s",headingDegrees=200.0,headingQuality=OceanInputQuality.LIVE,windDirectionFromDegrees=from,windCapability=true,weatherQuality=OceanInputQuality.LIVE))
            assertEquals(e,p.frame(0f,0f,OceanQuality.HIGH).environment)
        }
    }
    @Test fun missingAndStaleWeatherSmoothlyReturnToNeutralAndZerosRemainValid() {
        val p=OceanPresentation("s")
        val input=OceanInputs("s",weatherQuality=OceanInputQuality.LIVE,cloudCoverPercent=0.0,visibilityMeters=0.0,windSpeedKn=0.0,windCapability=true)
        p.accept(input);val first=p.frame(0f,0f,OceanQuality.HIGH).environment
        assertEquals(0f,first.cloudCover,0f);assertEquals(.08f,first.fogDensity,.000001f)
        p.accept(input.copy(weatherQuality=OceanInputQuality.STALE));val after=p.frame(0f,.05f,OceanQuality.HIGH).environment
        assertTrue(after.cloudCover in 0f.. .01f);assertTrue(after.fogDensity>.07f)
        repeat(1600){p.frame(0f,.05f,OceanQuality.HIGH)}
        val neutral=p.frame(0f,.05f,OceanQuality.HIGH)
        assertEquals(.18f,neutral.waveHeight,.0001f)
        assertEquals(OceanEnvironment().cloudCover,neutral.environment.cloudCover,.0001f)
        assertEquals(OceanEnvironment().fogDensity,neutral.environment.fogDensity,.000001f)
    }
    @Test fun windCrossesNorthByShortArcAndRejectsLateSessions() {
        val p=OceanPresentation("s")
        val i=OceanInputs("s",windDirectionFromDegrees=179.0,windCapability=true,weatherQuality=OceanInputQuality.LIVE)
        p.accept(i);val before=p.frame(0f,0f,OceanQuality.HIGH).environment.windToRadians
        assertFalse(p.accept(i.copy(sessionId="old",cloudCoverPercent=100.0)))
        p.accept(i.copy(windDirectionFromDegrees=181.0))
        val next=p.frame(0f,.05f,OceanQuality.HIGH).environment.windToRadians
        assertTrue(next>before && next-before<.001f)
    }
    @Test fun staleIndividualPositionAndWeatherGridCannotBecomeSolarPosition() {
        val now=epoch("2026-03-20T12:00:00Z")
        val quality=TelemetryQuality(TelemetryStatus.LIVE,now,"test")
        val raw=HelmState(latitudeDegrees=45.0,longitudeDegrees=12.0,quality=quality,
            telemetryFields=mapOf("latitudeDegrees" to TelemetryValue(45.0,quality),
                "longitudeDegrees" to TelemetryValue(12.0,quality.copy(timestampEpochMs=now-60_000))))
        val weather=WeatherSnapshot.unavailable().copy(values=WeatherValues(latitudeDegrees=50.0,longitudeDegrees=20.0))
        val i=oceanSourceInputs("s",raw,QualityEvaluator.SHARED,weather,WeatherFreshness.FORECAST,now)
        assertNull(i.latitudeDegrees);assertNull(i.longitudeDegrees)
    }
    @Test fun utcLightDoesNotUseAnimationTimeAndRequiresFreshBoatCoordinates() {
        val now=epoch("2026-03-20T12:00:00Z")
        val quality=TelemetryQuality(TelemetryStatus.LIVE,now,"test",simulated=true)
        val raw=HelmState(latitudeDegrees=0.0,longitudeDegrees=0.0,quality=quality)
        val input=oceanSourceInputs("s",raw,QualityEvaluator.SHARED,WeatherSnapshot.unavailable(),WeatherFreshness.FORECAST,now)
        val p=OceanPresentation("s");p.accept(input)
        assertTrue(p.frame(0f,0f,OceanQuality.HIGH).environment.sunUp>.998)
        assertEquals(p.frame(0f,0f,OceanQuality.HIGH).environment,p.frame(20f,0f,OceanQuality.HIGH).environment)
        val stale=oceanSourceInputs("s",raw,QualityEvaluator.SHARED,WeatherSnapshot.unavailable(),WeatherFreshness.FORECAST,now+60_000)
        assertNull(stale.latitudeDegrees);assertNull(stale.longitudeDegrees)
        val noPosition=OceanPresentation("s");noPosition.accept(stale)
        assertEquals(OceanEnvironment(),noPosition.frame(0f,0f,OceanQuality.HIGH).environment)
    }
}
