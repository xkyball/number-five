package com.no5.helm

import com.no5.domain.*
import org.junit.Assert.*
import org.junit.Test

class SpeedDepthInstrumentGeometryTest {
    @Test fun rotorScaleUsesExactReferenceEllipseAndIndependentScaleTicks() {
        val g=SpeedDepthInstrumentGeometry
        val top=g.pointAt(.5f)
        assertEquals(1000f,top.x,.01f);assertEquals(315f,top.y,.01f)
        val ticks=g.ticks();assertEquals(51,ticks.size)
        assertEquals(0.0,ticks.first().value,0.0);assertEquals(115/1.852,ticks.last().value,0.0)
        for(tick in ticks){
            val p=g.pointAt(tick.fraction);val opposite=g.pointAt(1-tick.fraction)
            assertEquals(2000f,p.x+opposite.x,.01f);assertEquals(p.y,opposite.y,.01f)
            assertTrue(p.x in g.LEFT..g.RIGHT && p.y in g.TOP..g.BOTTOM)
        }
        assertEquals(342f,g.pointAt(.5f,27f).y,.01f)
    }

    @Test fun depthAndSpeedUseIndependentFieldQualityAndMissingIsNeverZero() {
        val depth=TelemetryValue(7.0,TelemetryQuality(TelemetryStatus.LIVE,1000,"DEPTH"))
        val speed=TelemetryValue(30.0,TelemetryQuality(TelemetryStatus.LIVE,1000,"GPS"))
        val state=HelmState(speed=20.0,depth=99.0,telemetryFields=mapOf("depth" to depth,"speed" to speed))
        val live=SpeedDepthInstrumentGeometry.reading(state,DisplayUnits(),"STALE",1000)
        assertEquals(20.0,live.speed!!,0.0) // The frame-clock motion is permitted by raw field evidence.
        assertEquals(7.0,live.depth!!,0.0)
        val stale=SpeedDepthInstrumentGeometry.reading(state,DisplayUnits(),"STALE",7000)
        assertEquals(30.0,stale.speed!!,0.0) // Stale snaps to the actual last measurement.
        assertEquals(TelemetryStatus.STALE,stale.depthStatus)
        val missing=SpeedDepthInstrumentGeometry.reading(state.copy(telemetryFields=mapOf("depth" to depth)),DisplayUnits(),"LIVE",1000)
        assertNull(missing.speed);assertEquals(7.0,missing.depth!!,0.0)
        val expiredDepth=state.copy(telemetryFields=mapOf("speed" to speed,"depth" to depth.copy(quality=depth.quality.copy(timestampEpochMs=-40000))))
        val independent=SpeedDepthInstrumentGeometry.reading(expiredDepth,DisplayUnits(),"LOST",1000)
        assertEquals(20.0,independent.speed!!,0.0);assertNull(independent.depth)
        val invalidSpeed=state.copy(telemetryFields=mapOf("speed" to speed.copy(value=Double.NaN)))
        assertNull(SpeedDepthInstrumentGeometry.reading(invalidSpeed,DisplayUnits(),"LIVE",1000).speed)
    }

    @Test fun scaleUsesOnePhysicalMaximumAcrossUnitsAndClampsOnlyTheArc() {
        val g=SpeedDepthInstrumentGeometry
        for(kmh in listOf(0.0,114.9,115.0,115.1)) {
            val knots=kmh/1.852
            val readings=SpeedUnit.entries.map { unit ->
                val reading=g.reading(HelmState(speed=knots),DisplayUnits(speed=unit),"LIVE")
                assertEquals(unit.fromKnots(knots),reading.speed!!,1e-10)
                assertEquals((kmh/115).coerceIn(0.0,1.0).toFloat(),reading.fraction!!,1e-7f)
                assertEquals(kmh>115,reading.aboveScale)
                val ticks=g.ticks(unit)
                assertEquals(unit.fromKnots(115/1.852),ticks.last().value,1e-10)
                assertEquals(0f,ticks.first().fraction,0f);assertEquals(1f,ticks.last().fraction,0f)
                reading
            }
            assertEquals(1,readings.map{it.fraction}.distinct().size)
        }
        assertEquals(115.0,g.scaleMaximum(SpeedUnit.KMH),1e-10)
        assertEquals(115/1.609344,g.scaleMaximum(SpeedUnit.MPH),1e-10)
    }
    @Test fun zeroUnknownStaleAndSelectedDepthUnitsStayDistinct() {
        val units=DisplayUnits(depth=DepthUnit.FEET)
        val zero=SpeedDepthInstrumentGeometry.reading(HelmState(speed=0.0,depth=0.0),units,"LIVE")
        assertEquals(0.0,zero.speed!!,0.0);assertEquals(0.0,zero.depth!!,0.0)
        val stale=SpeedDepthInstrumentGeometry.reading(HelmState(depth=10.0),units,"STALE")
        assertEquals(32.80839895013123,stale.depth!!,0.00001)
        for(status in listOf("LOST","INVALID","UNAVAILABLE")){
            val absent=SpeedDepthInstrumentGeometry.reading(HelmState(),units,status)
            assertNull(absent.speed);assertNull(absent.depth);assertNull(absent.fraction)
        }
        assertNull(SpeedDepthInstrumentGeometry.reading(HelmState(depth=null),units,"LIVE").depth)
    }
    @Test fun traceUsesSourceTimeAndPreservesGapsWithoutExtendingLastSample() {
        val history=DepthHistorySnapshot(listOf(DepthHistoryPoint(30_000,5.0,true),DepthHistoryPoint(60_000,6.0,false),DepthHistoryPoint(90_000,7.0,true)),0,120_000,120_000)
        val trace=SpeedDepthInstrumentGeometry.trace(history)
        assertEquals(listOf(.25f,.5f,.75f),trace.points.map{it.x})
        assertEquals(listOf(true,false,true),trace.points.map{it.breakBefore})
        assertTrue(trace.points[0].y<trace.points[1].y)
        assertTrue(trace.points.all{it.x in 0f..1f && it.y in 0f..1f})
    }
    @Test fun emptyAndFlatHistoryAreFiniteAndKeepTwoMinuteWindow() {
        assertTrue(SpeedDepthInstrumentGeometry.trace(DepthHistorySnapshot.empty()).points.isEmpty())
        val trace=SpeedDepthInstrumentGeometry.trace(DepthHistorySnapshot(listOf(DepthHistoryPoint(120_000,0.0,true)),0,120_000,120_000))
        assertEquals(1f,trace.points.single().x,0f)
        assertTrue(trace.points.single().y.isFinite())
        assertTrue(trace.maximumMeters>trace.minimumMeters)
        assertTrue(SpeedDepthInstrumentGeometry.TOP>193f)
        assertTrue(SpeedDepthInstrumentGeometry.BOTTOM<702f)
    }
    @Test fun historyCacheReusesGeometryUntilExpiryButPreservesGapsAndRescalesOnExpiry() {
        val points=listOf(DepthHistoryPoint(1000,100.0,true),DepthHistoryPoint(2000,2.0,true))
        val cache=InstrumentHistoryTraceCache()
        fun snapshot(end:Long)=DepthHistorySnapshot(points,end-120000,end,120000)
        val first=cache.update(snapshot(60000))
        assertSame(first,cache.update(snapshot(61000))) // Inclusive left boundary.
        val expired=cache.update(snapshot(61001))
        assertNotSame(first,expired)
        assertEquals(SpeedDepthInstrumentGeometry.trace(snapshot(61001).copy(startEpochMs=1001,windowMs=60000)),expired)
        assertTrue(expired.points.single().breakBefore)
        assertTrue(expired.maximumMeters<first.maximumMeters)
        assertSame(expired,cache.update(snapshot(61500)))
        assertNotSame(expired,cache.update(snapshot(61499))) // Backwards clock even within the cached anchor.
        assertTrue(cache.update(snapshot(62001)).points.isEmpty())
        assertEquals(2,cache.update(snapshot(60000)).points.size)
        assertTrue(cache.update(DepthHistorySnapshot(emptyList(),0,60000,120000)).points.isEmpty())
    }

    @Test fun sameListFutureObservationsEnterAtTheirTimestampAndInvalidDepthsRemainExcluded() {
        val cache=InstrumentHistoryTraceCache()
        val points=listOf(DepthHistoryPoint(1000,Double.NaN,true),DepthHistoryPoint(1100,4.0,true),DepthHistoryPoint(1200,-1.0,false))
        val first=cache.update(DepthHistorySnapshot(points,0,1000,120000))
        assertTrue(first.points.isEmpty())
        assertSame(first,cache.update(DepthHistorySnapshot(points,0,1099,120000)))
        val visible=cache.update(DepthHistorySnapshot(points,0,1100,120000))
        assertEquals(1,visible.points.size)
        assertTrue(visible.points.single().breakBefore)
    }

    @Test fun traceMatchesPreCacheProjectionExactlyAcrossWindowsAndGaps() {
        fun original(history:DepthHistorySnapshot):SpeedDepthInstrumentGeometry.Trace {
            val valid=history.points.filter { it.depthMeters.isFinite() && it.depthMeters>=0.0 &&
                it.timestampEpochMs>=history.startEpochMs && it.timestampEpochMs<=history.endEpochMs }
            if(valid.isEmpty())return SpeedDepthInstrumentGeometry.Trace(0.0,2.0,emptyList())
            val low=valid.minOf{it.depthMeters};val high=valid.maxOf{it.depthMeters}
            val span=maxOf(2.0,high-low)
            val minimum=maxOf(0.0,(low+high-span)/2-span*.12)
            val maximum=maxOf(minimum+2.0,(low+high+span)/2+span*.12)
            val duration=(history.endEpochMs.toDouble()-history.startEpochMs.toDouble()).coerceAtLeast(1.0)
            return SpeedDepthInstrumentGeometry.Trace(minimum,maximum,valid.map { p ->
                SpeedDepthInstrumentGeometry.TracePoint(((p.timestampEpochMs.toDouble()-history.startEpochMs.toDouble())/duration).toFloat(),
                    ((p.depthMeters-minimum)/(maximum-minimum)).toFloat(),p.breakBefore)
            })
        }
        val points=(0..2400).map { i -> DepthHistoryPoint(i*50L,
            when(i%31){0->Double.NaN;1->-1.0;else->12.0+kotlin.math.sin(i*.7)*10},i%17==0) }
        for(end in listOf(0L,50L,60000L,60001L,120000L,180001L)) {
            val history=DepthHistorySnapshot(points,end-60000,end,60000)
            assertEquals(original(history),SpeedDepthInstrumentGeometry.trace(history))
        }
    }

}
