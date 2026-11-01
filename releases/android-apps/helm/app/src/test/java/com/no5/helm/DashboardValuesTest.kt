package com.no5.helm

import com.no5.domain.*
import org.junit.Assert.*
import org.junit.Test

class DashboardValuesTest {
    private val now=100_000L
    private val live=TelemetryQuality(TelemetryStatus.LIVE,now,"actual-vessel")
    @Test fun dashboardUsesEachFieldsOriginalTimestampAndQualityIncludingRealZero() {
        val raw=HelmState.unavailable().copy(speed=0.0,depth=12.0,quality=live,
            telemetryFields=mapOf("speed" to TelemetryValue(0.0,live),"depth" to TelemetryValue(12.0,live.copy(timestampEpochMs=now-6000))))
        val speed=dashboardField(raw,"speed",raw.speed,now,QualityEvaluator.SHARED)
        val depth=dashboardField(raw,"depth",raw.depth,now,QualityEvaluator.SHARED)
        assertEquals(0.0,speed.value!!,0.0);assertEquals(TelemetryStatus.LIVE,speed.quality)
        assertEquals(TelemetryStatus.STALE,depth.quality);assertEquals(now-6000,depth.observedAtEpochMs)
        assertEquals(12.0,depth.value!!,0.0)
        val missing=dashboardField(raw,"courseOverGroundDegrees",300.0,now,QualityEvaluator.SHARED)
        assertNull(missing.value);assertEquals(TelemetryStatus.UNAVAILABLE,missing.quality)
    }
    @Test fun newPublicationsDoNotRefreshAnExpiredFieldOrInventAValue() {
        val raw=HelmState.unavailable().copy(quality=live,telemetryFields=mapOf("depth" to TelemetryValue(12.0,live.copy(timestampEpochMs=now-30_001))))
        val depth=dashboardField(raw,"depth",raw.depth,now,QualityEvaluator.SHARED)
        assertNull(depth.value);assertEquals(TelemetryStatus.LOST,depth.quality)
        assertEquals(now-30_001,depth.observedAtEpochMs)
    }
    @Test fun allDashboardPanelsStayOutsidePhysicalKnobsHeaderAndNavigation() {
        for(bounds in DashboardGeometry.content) {
            assertTrue(bounds.y>=130f);assertTrue(bounds.bottom<1058f)
            assertTrue(KnobGeometry.safe(bounds.x,bounds.y,bounds.right,bounds.bottom))
        }
        for((index,bounds)in DashboardGeometry.content.withIndex())
            for(other in DashboardGeometry.content.drop(index+1))assertFalse(bounds.overlaps(other))
    }
    @Test fun dashboardProjectionsIgnoreOnlyUnpaintedInterpolationAndPreserveOriginalEvidence() {
        val raw=HelmState.unavailable().copy(quality=live,speed=6.51,portRpm=1012.1,
            telemetryFields=mapOf("speed" to TelemetryValue(6.51,live),"portRpm" to TelemetryValue(1012.1,live)))
        val units=DisplayUnits();val weather=WeatherSnapshot.unavailable()
        val slow=slowPresentation(raw,now,QualityEvaluator.SHARED,weather,QualityEvaluator.SHARED,units)
        val reading=SpeedDepthInstrumentGeometry.reading(raw,units,slow.values.quality,now)
        val p=helmPanelPresentation(raw,now,QualityEvaluator.SHARED,slow,
            motionPresentation(raw,now,QualityEvaluator.SHARED,units),reading,WeatherPresentation.from(weather,now))
        val subpixel=p.copy(motion=p.motion.copy(speed=6.52,fraction=.123456f,
            values=p.motion.values.copy(portRpm=1012.2f,portTrim=.01f)))
        assertEquals(dashboardSlowPresentation(p),dashboardSlowPresentation(subpixel))
        assertEquals(dashboardMotionLabels(p),dashboardMotionLabels(subpixel))
        val visible=subpixel.copy(motion=subpixel.motion.copy(values=subpixel.motion.values.copy(
            text=subpixel.motion.values.text+mapOf("rpm-port" to "1013"))))
        assertNotEquals(dashboardMotionLabels(p),dashboardMotionLabels(visible))
        assertEquals(dashboardSlowPresentation(p),dashboardSlowPresentation(visible))
        val originalTime=p.copy(raw=raw.copy(telemetryFields=raw.telemetryFields+
            mapOf("portRpm" to TelemetryValue(1012.1,live.copy(timestampEpochMs=now-1)))))
        assertNotEquals(dashboardMotionLabels(p),dashboardMotionLabels(originalTime))
        assertEquals(now-1,dashboardMotionLabels(originalTime).port.observedAtEpochMs)
    }

}
