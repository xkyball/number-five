package com.no5.helm

import java.io.PrintWriter
import java.io.StringWriter
import org.junit.Assert.*
import org.junit.Test

class SonarPaintDiagnosticsTest {
    @Test fun ringKeepsOnlyLatest64DurationsAndAggregatesOnlyOnDump() {
        val ring=SonarPaintDurationRing()
        ring.record(-1)
        for(value in 1L..80L)ring.record(value)
        val text=StringWriter().also{ring.dump("test",PrintWriter(it))}.toString()
        assertTrue(text.contains("totalSamples=80"));assertTrue(text.contains("windowSamples=64"))
        assertTrue(text.contains("sumNs=3104"));assertTrue(text.contains("lastNs=80"));assertTrue(text.contains("maxNs=80"))
        assertTrue(text.contains("sumOverflow=false"))
    }
    @Test fun phaseDurationsAreBoundedAndClearlySeparateCpuWallTimeFromGpuTime() {
        SonarPaintDiagnostics.recordPhases(false,11,22,33)
        SonarPaintDiagnostics.recordPhases(true,0,44,55)
        val text=StringWriter().also{SonarPaintDiagnostics.dump(PrintWriter(it))}.toString()
        assertTrue(text.contains("full.lut"));assertTrue(text.contains("full.recolorUpload"));assertTrue(text.contains("full.drawRemainder"))
        assertTrue(text.contains("preview.lut"));assertTrue(text.contains("windowLimit=64"));assertTrue(text.contains("gpuTime=UNKNOWN"))
        assertTrue(text.contains("zeros=CACHED_TRANSFER"))
    }
    @Test fun costBreakdownKeeps64AlignedPrimitiveSamplesAndHonestUnits() {
        val costs=SonarPaintCostBreakdown()
        for(v in 1L..80L)costs.record(v,v*2,v*3,v*4,32,2048,8388608,v,200,false,true)
        val text=StringWriter().also{costs.dump("test",PrintWriter(it))}.toString()
        assertTrue(text.contains("test.pack totalSamples=80 windowSamples=64 windowLimit=64 sumNs=3104"))
        assertTrue(text.contains("test.bitmapCopy totalSamples=80 windowSamples=64 windowLimit=64 sumNs=6208"))
        assertTrue(text.contains("test.pairPrepare totalSamples=80 windowSamples=64 windowLimit=64 sumNs=9312"))
        assertTrue(text.contains("test.uploadCalls totalSamples=80 windowSamples=64 windowLimit=64 sumCount=2048"))
        assertTrue(text.contains("test.aggregated totalSamples=80 windowSamples=64 windowLimit=64 sumCount=0"))
        assertTrue(text.contains("test.bulkFallback totalSamples=80 windowSamples=64 windowLimit=64 sumCount=64"))
    }
}
