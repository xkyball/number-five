package com.no5.domain
import org.junit.Assert.*
import org.junit.Test

class SonarQuietRangeTest {
    private class Input {
        var time=1000000L;var id=0L
        val model=SonarModel{time}.also{it.connect("range test");it.acquisition(true);it.setCalibration(Calibration(true,20.0));it.setBottom(SonarBottom(100.0,.99),true);it.setDisplay(it.display.copy(rangeMode=SonarRangeMode.AUTO,cropEnd=200.0))}
        fun ping(bottom:Double=250.0,valid:Boolean=true,dt:Long=50){
            time+=dt;id++
            model.setBottom(SonarBottom(bottom,if(valid).99 else .1),true)
            model.frame(SonarFrame(ShortArray(1024),id,time))
        }
    }
    @Test fun expansionRequiresThreeConfirmedPingsAndTwentyPercentReserve(){
        val h=Input();h.ping();h.ping();assertEquals(200.0,h.model.display.cropEnd,0.0)
        h.ping();assertEquals(300.0,h.model.display.cropEnd,.000001)
    }
    @Test fun tenPercentHysteresisHoldsSmallChanges(){
        val h=Input();repeat(100){h.ping(175.0)};assertEquals(200.0,h.model.display.cropEnd,0.0)
    }
    @Test fun shrinkRequiresThirtySecondsOfContinuousSmallerNeed(){
        val h=Input();repeat(600){h.ping(100.0)};assertEquals(200.0,h.model.display.cropEnd,0.0)
        h.ping(100.0);assertEquals(120.0,h.model.display.cropEnd,.000001)
    }
    @Test fun invalidBottomAndTransportGapBreakConfirmations(){
        val h=Input();h.ping();h.ping();h.ping(valid=false);h.ping();assertEquals(200.0,h.model.display.cropEnd,0.0)
        h.ping(dt=3000);h.ping();assertEquals(200.0,h.model.display.cropEnd,0.0);h.ping();assertEquals(300.0,h.model.display.cropEnd,.000001)
    }
    @Test fun freezeHoldsRangeAndRawRingWhileSourceContinues(){
        val h=Input();h.ping(100.0);val ring=h.model.ring;val count=ring.count
        h.model.setFreeze(true);val held=h.model.viewDisplay
        repeat(700){h.ping(300.0)}
        assertEquals(held,h.model.viewDisplay);assertSame(ring,h.model.ring);assertEquals(count,ring.count)
        assertEquals(701,h.model.latest!!.pingId)
    }
}
