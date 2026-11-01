package com.no5.domain

import org.junit.Test
import org.junit.Assert.*

class SpeedExtremaTest {
    private fun source(value:Double,stamp:Long,status:TelemetryStatus=TelemetryStatus.LIVE,run:String="run")=HelmState(
        speed=99.0,simulationRunId=run,quality=TelemetryQuality(TelemetryStatus.LIVE,stamp,"GATEWAY",simulated=true),
        telemetryFields=mapOf("speed" to TelemetryValue(value,TelemetryQuality(status,stamp,"GPS",simulated=true))))
    @Test fun acceptsOnlyNewFreshRawSpeedAndNeverInterpolatedDisplaySpeed() {
        val e=SpeedExtrema();e.accept(source(0.0,1000),1000,"session");e.accept(source(12.0,1100),1100,"session")
        assertEquals(SpeedExtremaSnapshot(0.0,12.0),e.snapshot())
        e.accept(source(100.0,1100),1100,"session")
        e.accept(source(150.0,1200,TelemetryStatus.STALE),1200,"session")
        e.accept(source(180.0,1300),50000,"session")
        e.accept(source(Double.NaN,1400),1400,"session")
        assertEquals(SpeedExtremaSnapshot(0.0,12.0),e.snapshot())
    }
    @Test fun sessionsAndSimulatorRunsResetButUnitConversionsDoNotMutateCanonicalData() {
        val e=SpeedExtrema();e.accept(source(10.0,1000),1000,"a");e.accept(source(30.0,1100),1100,"a")
        assertEquals(55.56,SpeedUnit.KMH.fromKnots(e.snapshot().maximumKnots!!),.001)
        e.accept(source(5.0,1200),1200,"b");assertEquals(SpeedExtremaSnapshot(5.0,5.0),e.snapshot())
        e.accept(source(8.0,1300,run="new"),1300,"b");assertEquals(SpeedExtremaSnapshot(8.0,8.0),e.snapshot())
        e.accept(source(2.0,1400,run="new").copy(simulationElapsedSeconds=20.0),1400,"b")
        e.accept(source(7.0,1500,run="new").copy(simulationElapsedSeconds=0.0),1500,"b")
        assertEquals(SpeedExtremaSnapshot(7.0,7.0),e.snapshot())
    }
    @Test fun missingEvidenceDoesNotBecomeZeroOrExtrema() {
        val e=SpeedExtrema();e.accept(HelmState.unavailable(),1000,"a");assertEquals(SpeedExtremaSnapshot(),e.snapshot())
        e.accept(HelmState(),1000,"a");assertEquals(SpeedExtremaSnapshot(),e.snapshot())
    }
}
