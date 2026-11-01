package com.no5.helm

import com.no5.domain.*
import org.junit.Assert.*
import org.junit.Test
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class DashboardPaintedTextTest {
    private val now=1_000_000L
    private val quality=TelemetryQuality(TelemetryStatus.LIVE,now,"actual-vessel")
    private val raw=HelmState.unavailable().copy(quality=quality,depth=12.0,portRpm=1000.0,
        telemetryFields=mapOf("depth" to TelemetryValue(12.0,quality),"portRpm" to TelemetryValue(1000.0,quality),
            "portVoltage" to TelemetryValue(12.5,quality)))
    private fun panel(raw:HelmState=this.raw,at:Long=now):HelmPanelPresentation {
        val units=DisplayUnits();val weather=WeatherSnapshot.unavailable()
        val slow=slowPresentation(raw,at,QualityEvaluator.SHARED,weather,QualityEvaluator.SHARED,units)
        return helmPanelPresentation(raw,at,QualityEvaluator.SHARED,slow,motionPresentation(raw,at,QualityEvaluator.SHARED,units),
            SpeedDepthInstrumentGeometry.reading(raw,units,slow.values.quality,at),WeatherPresentation.from(weather,at))
    }
    private fun clock()=DashboardTimeFormatter(Locale.US,TimeZone.getTimeZone("UTC"))
    @Test fun originalTimesIncludingZeroFormatExactlyAndCacheIsBounded() {
        val clock=clock();val reference=DateFormat.getTimeInstance(DateFormat.MEDIUM,Locale.US).apply{timeZone=TimeZone.getTimeZone("UTC")}
        assertEquals("—",clock.format(null))
        for(t in listOf(0L,1L,now,now-1001,now+86_400_000))assertEquals(reference.format(Date(t)),clock.format(t))
        repeat(80){clock.format(now+it)}
        assertEquals(32,clock.cachedTimes)
        assertEquals(reference.format(Date(now)),clock.format(now))
    }
    @Test fun unrelatedMotionAndPacketClockDoNotInvalidateSlowPaintedLabels() {
        val clock=clock();val a=panel()
        val b=panel(raw.copy(portRpm=2000.0,speed=8.0,quality=quality.copy(timestampEpochMs=now+1)),now+1)
        assertEquals(dashboardSlowText(a,clock),dashboardSlowText(b,clock))
        assertNotEquals(dashboardMotionText(a,clock),dashboardMotionText(b,clock))
    }
    @Test fun changedOriginalFieldTimeChangesTheShownEvidenceWithoutMutatingRaw() {
        val clock=clock();val a=panel()
        val moved=raw.copy(telemetryFields=raw.telemetryFields+("depth" to TelemetryValue(12.0,quality.copy(timestampEpochMs=now-1000))))
        assertNotEquals(dashboardSlowText(a,clock).depthEvidence,dashboardSlowText(panel(moved),clock).depthEvidence)
        assertEquals(now,raw.telemetryFields.getValue("depth").quality.timestampEpochMs)
        assertEquals(now-1000,moved.telemetryFields.getValue("depth").quality.timestampEpochMs)
    }
    @Test fun originalFieldExpiresWithoutAnyNewPacketOrTimestampRefresh() {
        val clock=clock();val live=dashboardSlowText(panel(),clock);val expired=dashboardSlowText(panel(at=now+6000),clock)
        assertEquals(TelemetryStatus.LIVE,live.depthStatus)
        assertEquals(TelemetryStatus.STALE,expired.depthStatus)
        assertNotEquals(live.depthEvidence,expired.depthEvidence)
        assertTrue(expired.depthEvidence.endsWith(clock.format(now)))
        assertEquals(now,raw.telemetryFields.getValue("depth").quality.timestampEpochMs)
    }
}
