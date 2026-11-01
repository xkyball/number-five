package com.no5.domain

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.pow

/** Published presentation precision, not raw/detection changes or device performance evidence. */
class SonarAutoDisplayPublicationTest {
    private class Harness {
        var now=1_200_000L
        var id=0L
        val model=SonarModel{now}.also{
            it.connect("SYNTHETIC AUTO PRECISION");it.acquisition(true)
            it.updateContext(SonarEpoch("synthetic-auto-precision",1,1),SonarPingSource.OWN)
        }
        fun next(level:Double=1.0,clipped:Boolean=false):SonarFrame {
            now+=100;id++
            val frame=SonarFrame(ShortArray(1024){bin->
                (if(clipped)4095 else (((if(bin in 400..500)400.0 else 35.0+bin%7)*level).toInt()).coerceIn(0,4095)).toShort()
            },id,now)
            model.setAnalysis(SonarGeometry(3.072,SonarTxReference.HOST_VERIFIED),SonarBottom(),null,false,
                amplitude=SonarAmplitude(4095,12,true))
            model.frame(frame,pingSource=SonarPingSource.OWN)
            return frame
        }
    }
    private fun transfer(raw:Int,d:SonarDisplay):Double =
        ((raw/4095.0*10.0.pow(d.gainDb/20)-d.threshold)/(1-d.threshold)).coerceIn(0.0,1.0).pow(1/d.gamma)

    @Test fun bothGainHysteresisFlanksHoldInsideAndPublishAtTheBoundary(){
        assertEquals(24.0,SonarAutoDisplayPrecision.gain(24.187499,24.0),0.0)
        assertEquals(24.25,SonarAutoDisplayPrecision.gain(24.1875,24.0),0.0)
        assertEquals(24.0,SonarAutoDisplayPrecision.gain(23.812501,24.0),0.0)
        assertEquals(23.75,SonarAutoDisplayPrecision.gain(23.8125,24.0),0.0)
        assertEquals(24.25,SonarAutoDisplayPrecision.gain(24.125001,24.25),0.0)
        assertEquals(24.0,SonarAutoDisplayPrecision.gain(24.0625,24.25),0.0)
    }

    @Test fun substepNoiseDoesNotChangeAnyPublishedTransferKey(){
        var gain=24.0;var gamma=1.2;var threshold=.1
        repeat(1000){i->
            val sign=if(i%2==0)1 else -1
            gain=SonarAutoDisplayPrecision.gain(24.125+sign*.0001,gain)
            gamma=SonarAutoDisplayPrecision.gamma(1.205+sign*.00001,gamma)
            threshold=SonarAutoDisplayPrecision.threshold(.1005+sign*.000001,threshold)
            assertEquals(24.0,gain,0.0);assertEquals(1.2,gamma,0.0);assertEquals(.1,threshold,0.0)
        }
    }

    @Test fun gammaThresholdFlanksAndOriginalBoundsRemainExplicit(){
        assertEquals(1.2,SonarAutoDisplayPrecision.gamma(1.207499,1.2),0.0)
        assertEquals(1.21,SonarAutoDisplayPrecision.gamma(1.207501,1.2),1e-15)
        assertEquals(1.19,SonarAutoDisplayPrecision.gamma(1.192499,1.2),1e-15)
        assertEquals(.1,SonarAutoDisplayPrecision.threshold(.1007499,.1),0.0)
        assertEquals(.101,SonarAutoDisplayPrecision.threshold(.1007501,.1),1e-15)
        assertEquals(.099,SonarAutoDisplayPrecision.threshold(.0992499,.1),1e-15)
        assertEquals(-24.0,SonarAutoDisplayPrecision.gain(-100.0,0.0),0.0)
        assertEquals(36.0,SonarAutoDisplayPrecision.gain(100.0,0.0),0.0)
        assertEquals(.2,SonarAutoDisplayPrecision.gamma(-1.0,1.0),0.0)
        assertEquals(3.0,SonarAutoDisplayPrecision.gamma(9.0,1.0),0.0)
        assertEquals(0.0,SonarAutoDisplayPrecision.threshold(-1.0,.1),0.0)
        assertEquals(.95,SonarAutoDisplayPrecision.threshold(2.0,.1),0.0)
    }

    @Test fun storedHistoryRemainsStableWhileAutoGraduallyAdapts(){
        val h=Harness();h.model.setImageAuto(true);repeat(100){h.next()}
        val retained=h.model.ring.imageMappingAt(50)
        repeat(100){h.next(.1)}
        assertEquals(retained,h.model.ring.imageMappingAt(150))
        assertEquals(SonarImageAutoState.ACTIVE,h.model.imageAutoState)
    }
    @Test fun offsetDoesNotBecomeAnEstimatorFeedbackInput(){
        val a=Harness();val b=Harness();a.model.setImageAuto(true);b.model.setImageAuto(true)
        a.model.setAutoGainOffset(2.0)
        repeat(1000){a.next();b.next()}
        assertEquals(a.model.display.gainDb,b.model.display.gainDb,0.0)
        assertEquals(2.0,a.model.ring.imageValuesAt(10,a.model.display).gainDb-b.model.ring.imageValuesAt(10,b.model.display).gainDb,.000001)
        assertEquals(SonarImageMode.AUTO,a.model.display.gainMode)
    }
    @Test fun explicitModeChangesRebaseHistoryInsteadOfRestoringObsoleteTone(){
        val h=Harness();repeat(30){h.next()}
        h.model.setManualImageValue(SonarImageField.GAIN,8.0)
        h.model.setImageMode(SonarImageField.GAIN,SonarImageMode.AUTO)
        assertEquals(8.0,h.model.ring.imageMappingAt(20)!!.gainDb,0.0)
        assertEquals(8.0,h.model.ring.imageValuesAt(20,h.model.display).gainDb,0.0)
    }
    @Test fun preciseManualValuesSurviveAutoTransitionsIndependently(){
        val h=Harness();val gain=4.123456789;val gamma=1.23456789;val threshold=.0123456789
        h.model.setManualImageValue(SonarImageField.GAIN,gain)
        h.model.setManualImageValue(SonarImageField.GAMMA,gamma)
        h.model.setManualImageValue(SonarImageField.THRESHOLD,threshold)
        h.model.setImageMode(SonarImageField.GAIN,SonarImageMode.AUTO);repeat(100){h.next()}
        assertEquals(gamma,h.model.display.gamma,0.0);assertEquals(threshold,h.model.display.threshold,0.0)
        assertEquals(gain,h.model.display.manualGainDb!!,0.0)
        h.model.setImageMode(SonarImageField.GAIN,SonarImageMode.MANUAL)
        assertEquals(gain,h.model.display.gainDb,0.0)
        h.model.setImageMode(SonarImageField.GAMMA,SonarImageMode.AUTO);h.next()
        assertEquals(gain,h.model.display.gainDb,0.0);assertEquals(threshold,h.model.display.threshold,0.0)
        h.model.setImageMode(SonarImageField.GAMMA,SonarImageMode.MANUAL)
        h.model.setImageMode(SonarImageField.THRESHOLD,SonarImageMode.AUTO);h.next()
        assertEquals(gamma,h.model.display.gamma,0.0)
        h.model.setImageMode(SonarImageField.THRESHOLD,SonarImageMode.MANUAL)
        assertEquals(threshold,h.model.display.threshold,0.0)
    }

    @Test fun currentExactManualSeedIsNotRoundedUntilAnAutoChangeExceedsDeadband(){
        val current=4.123456789
        assertEquals(current,SonarAutoDisplayPrecision.gain(current+.0001,current),0.0)
        assertEquals(4.25,SonarAutoDisplayPrecision.gain(current+.2,current),0.0)
    }

    @Test fun freezeHoldsImageAndControlsWhileSourceStatisticsContinue(){
        val h=Harness();h.model.setImageAuto(true);repeat(100){h.next()}
        h.model.setFreeze(true);val held=h.model.viewDisplay
        repeat(30){h.next(.1)}
        assertEquals(held,h.model.viewDisplay)
        assertEquals(held.gainDb,h.model.display.gainDb,0.0)
        assertEquals(130L,h.model.latest!!.pingId)
        assertTrue(h.model.display.freeze);assertTrue(h.model.display.gainMode==SonarImageMode.AUTO)
        h.model.setFreeze(false)
        h.next(.1)
        assertEquals(SonarImageAutoState.ACTIVE,h.model.imageAutoState)
        assertEquals(held.gainDb,h.model.display.gainDb,0.0) // same source second, no new evaluation
        repeat(29){h.next(.1)}
        assertNotEquals(held.gainDb,h.model.display.gainDb)
        assertEquals(h.model.display,h.model.viewDisplay)
    }
    @Test fun actualSourceSwitchAndUnboundPingCannotBorrowCalibration(){
        var now=1000000L;val m=SonarModel{now};m.connect("source context test");m.acquisition(true)
        m.updateContext(SonarEpoch("context",1,1),SonarPingSource.ALL);m.setImageAuto(true)
        fun ping(id:Long,source:SonarPingSource?){now+=50;m.setAnalysis(SonarGeometry(3.072,SonarTxReference.HOST_VERIFIED),SonarBottom(),null,false,amplitude=SonarAmplitude(4095,12,true));m.frame(SonarFrame(ShortArray(1024){if(it in 400..500)400 else 40},id,now),pingSource=source)}
        for(id in 1L..100L)ping(id,SonarPingSource.OWN)
        assertEquals(SonarImageAutoState.ACTIVE,m.imageAutoState)
        ping(101,SonarPingSource.NATIVE);assertEquals(SonarImageAutoState.WARMING,m.imageAutoState)
        ping(102,null);assertEquals(SonarImageAutoState.WAITING,m.imageAutoState)
        assertEquals(102,m.ring.count)
    }

}
