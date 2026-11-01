package com.no5.domain

import org.junit.Test
import kotlin.test.*
import kotlin.math.abs

class AudioSoundTest {
    @Test fun legacySeparationIsAtomicAndHonoursLocksCutsAndUnrelatedBands(){
        val a=SoundEqPoint("a",1000.0,4.0,1.0);val b=SoundEqPoint("b",1500.0,5.0,1.0)
        val cut=SoundEqPoint("cut",1000.0,-2.0);val far=SoundEqPoint("far",15000.0,3.0,3.0)
        val input=listOf(a,b,cut,far);val output=SoundEqBands.separateLegacyBands(input)!!
        assertFalse(SoundEqBands.overlaps(output));assertEquals(input.map{it.copy(q=1.0)},output.map{it.copy(q=1.0)})
        assertEquals(cut,output[2]);assertEquals(far,output[3]);assertEquals(listOf(a,b,cut,far),input)
        val locked=a.copy(widthLocked=true)
        assertEquals(locked,SoundEqBands.separateLegacyBands(listOf(locked,b.copy(frequencyHz=2000.0)))!![0])
        assertNull(SoundEqBands.separateLegacyBands(listOf(locked,b.copy(widthLocked=true))))
        assertNull(SoundEqBands.separateLegacyBands(listOf(a,a.copy(id="same-center"))))
        assertEquals(output,SoundEqBands.separateLegacyBands(output))
    }
    @Test fun absolutePointRangeSupportsTheNewReserveAndExperimentalCeilings(){
        for(gain in listOf(-12.0,6.0,9.0,12.0,15.0,18.0,21.0))assertEquals(gain,SoundEqPoint("range",1000.0,gain).gainDb)
        assertFailsWith<IllegalArgumentException>{SoundEqPoint("range",1000.0,21.001)}
        assertFailsWith<IllegalArgumentException>{SoundEqPoint("range",1000.0,-12.001)}
        assertEquals(21.0,SoundEqPoint("range",1000.0).move(1000.0,30.0).gainDb)
    }

    @Test fun nativeReadbackAllowsOneRepresentableStepButNoShapeIdentityOrZeroChange(){
        val gain=1.9816084653139114;val next=Math.nextAfter(gain,0.0)
        assertEquals(1.9816084653139112,next)
        assertTrue(SoundNativeReadback.number(next,gain));assertTrue(SoundNativeReadback.number(gain,next))
        assertFalse(SoundNativeReadback.number(Math.nextAfter(next,0.0),gain))
        for(pair in listOf(0.0 to Double.MIN_VALUE,Double.MIN_VALUE to 0.0,Double.NaN to Double.NaN,Double.POSITIVE_INFINITY to Double.POSITIVE_INFINITY))assertFalse(SoundNativeReadback.number(pair.first,pair.second))
        val p=SoundPreset("id","Title","stereo_4_1",listOf(SoundEqPoint("p",1000.0,gain),SoundEqPoint("q",3000.0,-2.0)))
        assertTrue(SoundNativeReadback.preset(p.copy(eqPoints=listOf(p.eqPoints[0].copy(gainDb=next),p.eqPoints[1])),p))
        for(bad in listOf(p.copy(id="other"),p.copy(name="Other"),p.copy(eqEnabled=true),p.copy(eqPoints=p.eqPoints.reversed()),p.copy(eqPoints=p.eqPoints.drop(1)),p.copy(eqPoints=listOf(p.eqPoints[0].copy(widthLocked=true),p.eqPoints[1]))))assertFalse(SoundNativeReadback.preset(bad,p))
        assertFalse(SoundNativeReadback.preset(null,null))
    }
    @Test fun peakResponseUsesTheBiquadTransferRatherThanIllustrativeInterpolation(){
        for(f in listOf(20.0,150.0,1000.0,12000.0,20000.0))for(g in listOf(-12.0,0.0,12.0)){
            val p=SoundEqPoint("point",f,g,1.0)
            assertEquals(g,SoundEqResponse.gainDb(listOf(p),f),.000001)
            assertTrue(abs(SoundEqResponse.gainDb(listOf(p),0.0))<.000001)
        }
        val points=listOf(SoundEqPoint("a",1000.0,5.0),SoundEqPoint("b",1000.0,7.0))
        assertEquals(12.0,SoundEqResponse.gainDb(points,1000.0),.000001)
    }
    @Test fun widthLockRetainsHzAndStopsAtQBoundary(){
        val p=SoundEqPoint("point",1000.0,4.0,2.0,true)
        val moved=p.move(2000.0,8.0)
        assertEquals(500.0,moved.widthHz,.000001);assertEquals(4.0,moved.q)
        val limit=p.move(20000.0,12.0)
        assertEquals(5000.0,limit.frequencyHz);assertEquals(10.0,limit.q);assertEquals(500.0,limit.widthHz)
    }
    @Test fun directBandHandlesShareOneQAndLockCannotChangeTheirWidth(){
        val p=SoundEqPoint("point",1200.0,3.0,1.05)
        assertEquals(p.widthHz,p.highHz-p.lowHz,.000001)
        assertEquals(p.frequencyHz*p.frequencyHz,p.lowHz*p.highHz,.000001)
        val wider=p.withBandEdge(p.lowHz*.7,true)
        assertEquals(p.frequencyHz,wider.frequencyHz);assertEquals(p.gainDb,wider.gainDb);assertTrue(wider.widthHz>p.widthHz)
        assertEquals(p.lowHz*.7,wider.lowHz,.000001)
        val fromHigh=p.withBandEdge(p.highHz*1.2,false)
        assertEquals(p.highHz*1.2,fromHigh.highHz,.000001)
        val locked=p.copy(widthLocked=true)
        assertEquals(locked,locked.withBandEdge(20.0,true));assertEquals(locked,locked.withBandEdge(20000.0,false))
        assertEquals(.3,p.withBandEdge(1.0,true).q);assertEquals(10.0,p.withBandEdge(1200.0,true).q)
    }
    @Test fun neutralAffectsGainsOnlyAndUnknownGeometryStaysUnknown(){
        val p=SoundPreset("preset","User title","stereo_4_1",listOf(SoundEqPoint("x",300.0,7.0,3.0,true)),SoundRoom(.7,.8))
        assertEquals(p.room,p.neutral().room);assertEquals(p.eqPoints[0].q,p.neutral().eqPoints[0].q);assertEquals(0.0,p.neutral().eqPoints[0].gainDb)
        assertFalse(SoundCalibration().completeGeometry);assertNull(SoundCalibration().listenerM)
        assertFailsWith<IllegalArgumentException>{p.copy(eqPoints=List(17){SoundEqPoint("p$it",200.0)})}
        assertFailsWith<IllegalArgumentException>{p.copy(eqPoints=listOf(p.eqPoints[0],p.eqPoints[0]))}
    }
    @Test fun exactWireLimitsIncludeUtf8AndRoutingCapabilities(){
        SoundEqPoint("x".repeat(64),20.0)
        assertFailsWith<IllegalArgumentException>{SoundEqPoint("x".repeat(65),20.0)}
        SoundPreset("x".repeat(64),"é".repeat(64),"front_stereo_fullrange")
        assertFailsWith<IllegalArgumentException>{SoundPreset("x","é".repeat(65),"front_stereo_fullrange")}
        assertFailsWith<IllegalArgumentException>{SoundPreset("x","Name","arbitrary_yaml")}
        assertFailsWith<IllegalArgumentException>{SoundPreset("x","Name","front_stereo_fullrange",room=SoundRoom(.1,.5))}
        assertFailsWith<IllegalArgumentException>{SoundCalibration(geometryEnabled=true)}
        assertFailsWith<IllegalArgumentException>{SoundPosition(50.001,0.0)}
        assertEquals(50.0,SoundPosition(50.0,0.0).x)
    }
    @Test fun eqEnableAndChosenReserveBelongToThePresetAndNeutralChangesNeither(){
        val base=SoundPreset("preset","Klang","stereo_4_1",listOf(SoundEqPoint("p",200.0,5.0)),SoundRoom(.7,.3))
        assertFalse(base.eqEnabled);assertEquals(12.0,base.reserveDb)
        val on=base.copy(eqEnabled=true,reserveDb=18.0)
        assertTrue(on.neutral().eqEnabled);assertEquals(18.0,on.neutral().reserveDb);assertEquals(on.room,on.neutral().room)
        assertEquals(base.eqPoints,on.copy(eqEnabled=false).eqPoints)
        assertEquals(6.0,base.copy(reserveDb=6.0).reserveDb)
        for(invalid in listOf(5.999,18.001,Double.NaN))assertFailsWith<IllegalArgumentException>{base.copy(reserveDb=invalid)}
    }
    @Test fun positiveVisibleBandsMayTouchButNotOverlapAndCutsAreUnrestricted(){
        val a=SoundEqPoint("a",1000.0,4.0,2.0)
        val unit=SoundEqPoint("unit",1000.0,4.0,2.0)
        val b=SoundEqPoint("b",a.highHz/(unit.lowHz/unit.frequencyHz),3.0,2.0)
        assertFalse(SoundEqBands.overlap(a,b));assertTrue(SoundEqBands.overlap(a,b.copy(frequencyHz=b.frequencyHz-.0001)))
        assertFalse(SoundEqBands.overlap(a,a.copy(id="cut",gainDb=-6.0)))
    }
    @Test fun movingAcrossANeighbourStopsAtFirstValidBoundaryEvenIfTheEndpointWouldBeFree(){
        val a=SoundEqPoint("a",300.0,3.0,2.0);val b=SoundEqPoint("b",1000.0,4.0,4.0)
        val requested=a.move(5000.0,3.0);assertFalse(SoundEqBands.overlap(requested,b))
        val bounded=SoundEqBands.constrain(a,requested,listOf(a,b))
        assertTrue(bounded.constrained);assertTrue(bounded.point.frequencyHz<1000.0)
        assertTrue(bounded.point.highHz<=b.lowHz+1e-9);assertEquals(3.0,bounded.point.gainDb)
        assertFalse(SoundEqBands.overlaps(listOf(bounded.point,b)))
    }
    @Test fun expansionAndLockedHzMovementStopWithoutChangingOtherBandValues(){
        val a=SoundEqPoint("a",400.0,3.0,5.0);val b=SoundEqPoint("b",700.0,4.0,5.0)
        val expanded=SoundEqBands.constrain(a,a.copy(q=.3),listOf(a,b))
        assertTrue(expanded.constrained);assertTrue(expanded.point.highHz<=b.lowHz+1e-9)
        val locked=SoundEqPoint("locked",250.0,2.0,2.0,true);val far=SoundEqPoint("far",900.0,2.0,4.0)
        val moved=SoundEqBands.constrain(locked,locked.move(2000.0,2.0),listOf(locked,far))
        assertTrue(moved.constrained);assertEquals(locked.widthHz,moved.point.widthHz,1e-7)
        assertTrue(moved.point.highHz<=far.lowHz+1e-9)
    }
    @Test fun liftingACutInsideAPositiveBandStopsAtZeroWhileLegacyBypassedCurvesRemainCorrectable(){
        val cut=SoundEqPoint("cut",1000.0,-2.0);val b=SoundEqPoint("b",1100.0,5.0)
        val lifted=SoundEqBands.constrain(cut,cut.copy(gainDb=4.0),listOf(cut,b))
        assertTrue(lifted.constrained);assertEquals(0.0,lifted.point.gainDb)
        val legacy=cut.copy(gainDb=6.0)
        assertEquals(5.0,SoundEqBands.constrain(legacy,legacy.copy(gainDb=5.0),listOf(legacy,b)).point.gainDb)
        assertEquals(0.0,SoundEqBands.constrain(legacy,legacy.copy(gainDb=0.0),listOf(legacy,b)).point.gainDb)
    }
    @Test fun spectrumRequiresBoundedRealGeometryAndNeverAcceptsNaNsOrWrongFft(){
        val frame=AudioSpectrumFrame(1,"source",8192,48000,8192,listOf(20.0,1000.0,20000.0),listOf(-80.0,-12.0,-90.0),listOf(-6.0,-6.0),0)
        assertEquals(8192,frame.samplePosition)
        assertFailsWith<IllegalArgumentException>{frame.copy(fftSize=1024)}
        assertFailsWith<IllegalArgumentException>{frame.copy(binsDbfs=listOf(Double.NaN,-2.0,-3.0))}
        assertFailsWith<IllegalArgumentException>{frame.copy(frequenciesHz=listOf(20.0,20.0,20000.0))}
    }
    @Test fun widthBoundaryKeepsAnUnchangedFrequencyAndGainBitExact(){
        val a=SoundEqPoint("a",5477.162391840849,4.097563978750259,1.6246926266327137)
        val b=SoundEqPoint("b",13179.202930309702,11.327203120104969,.8605087271267844)
        val bounded=SoundEqBands.constrain(a,a.copy(q=.3),listOf(a,b))
        assertTrue(bounded.constrained);assertEquals(a.frequencyHz.toRawBits(),bounded.point.frequencyHz.toRawBits())
        assertEquals(a.gainDb.toRawBits(),bounded.point.gainDb.toRawBits());assertFalse(SoundEqBands.overlap(bounded.point,b))
    }

}
