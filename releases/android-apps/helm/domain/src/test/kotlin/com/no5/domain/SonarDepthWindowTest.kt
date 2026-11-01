package com.no5.domain

import org.junit.Assert.*
import org.junit.Test

class SonarDepthWindowTest {
    private val settings = SonarDepthSettings()
    private class Input(val window: SonarDepthWindow = SonarDepthWindow()) {
        var mono = 10_000_000_000L
        var time = 1_000_000L
        var id = 0L
        fun send(value: Double?, dt: Long = 1000, received: Long? = null) {
            mono += dt * 1_000_000; time += dt; id++
            window.observe(value, id, time, received ?: mono, mono, SonarDepthSettings())
        }
        fun read(live: Boolean = true, settings: SonarDepthSettings = SonarDepthSettings()) = window.read(settings, mono, live)
    }
    @Test fun firstValueIncludingZeroAppearsImmediatelyAndMeanIgnoresMissingValues() {
        val h=Input();h.send(0.0)
        assertEquals(0.0,h.read().valueM!!,0.0)
        h.send(10.0);h.send(null)
        assertEquals(5.0,h.read().valueM!!,0.0)
        assertEquals(SonarDepthState.HELD,h.read().state)
        h.send(Double.NaN,10);h.send(-1.0,10);h.send(Double.POSITIVE_INFINITY,10);assertEquals(5.0,h.read().valueM!!,0.0)
    }
    @Test fun exactExpiryWithoutNewPingsAndImmediateReappearance() {
        val h=Input();h.send(8.0);h.mono+=4_999_000_000
        assertEquals(8.0,h.read(false).valueM!!,0.0)
        h.mono+=1_000_000;assertNull(h.read().valueM)
        h.send(3.0);assertEquals(3.0,h.read().valueM!!,0.0)
        assertEquals(SonarDepthState.CURRENT,h.read().state)
    }
    @Test fun sourceTimeExpiresOldBatchValuesAndOriginalReceptionPreventsRejuvenation() {
        val h=Input();h.send(2.0);h.send(8.0,6000)
        assertEquals(8.0,h.read().valueM!!,0.0)
        val delayed=h.mono-6_000_000_000
        h.send(99.0,100,delayed)
        assertEquals(8.0,h.read().valueM!!,0.0)
    }
    @Test fun duplicatesAndBackwardEventsDoNotChangeTheWindow() {
        val h=Input();h.send(2.0)
        assertFalse(h.window.observe(100.0,h.id,h.time,h.mono,h.mono,settings))
        assertFalse(h.window.observe(100.0,h.id+1,h.time-1,h.mono,h.mono,settings))
        assertEquals(2.0,h.read().valueM!!,0.0)
    }
    @Test fun rawModeDoesNotBridgeMissingDepthAndSwitchingDoesNotChangeMeasurements() {
        val h=Input();h.send(4.0);h.send(8.0)
        assertEquals(8.0,h.read(settings=settings.copy(mode=SonarDepthMode.DIRECT)).valueM!!,0.0)
        assertEquals(6.0,h.read().valueM!!,0.0)
        h.send(null)
        assertNull(h.read(settings=settings.copy(mode=SonarDepthMode.DIRECT)).valueM)
        assertEquals(6.0,h.read().valueM!!,0.0)
    }
    @Test fun contextResetAndShorterWindowNeverReuseOldEvidence() {
        val h=Input();h.send(1.0);h.send(9.0)
        assertEquals(9.0,h.read(settings=settings.copy(windowSeconds=1)).valueM!!,0.0)
        h.window.clear();assertNull(h.read().valueM)
        h.id=0;h.send(7.0);assertEquals(7.0,h.read().valueM!!,0.0)
    }
    @Test fun overflowIsExplicitlyIncompleteAndStorageIsBounded() {
        val h=Input(SonarDepthWindow(2));h.send(1.0,10);h.send(2.0,10);h.send(3.0,10)
        assertFalse(h.read().complete);assertNull(h.read().valueM)
        assertEquals(48,h.window.allocatedBytes)
        val existing=SonarImageAuto().estimatedBytes + 12000*(12L+8L+8L) + 2*47*256*16L + 256*32L + 512*1024L
        assertTrue("All existing statistics/caches plus quiet scalar depths fit 4 MiB",existing+SonarDepthWindow().allocatedBytes < 4*1024*1024)
        h.mono+=5_000_000_000;assertTrue(h.read().complete)
    }
    @Test fun modelKeepsRawDepthAndFreezesOnlyPresentation() {
        var wall=1_000_000L;var mono=10_000_000_000L;var id=0L
        val model=SonarModel({wall},{mono});model.setDepthSettings(settings)
        model.connect("test");model.acquisition(true)
        fun ping(bin:Double?) {
            wall+=50;mono+=50_000_000;id++
            model.setCalibration(Calibration(true,1.0));model.setBottom(SonarBottom(bin,if(bin==null)null else .99),true)
            model.frame(SonarFrame(ShortArray(1024),id,wall),localReceivedAtNanos=mono)
        }
        ping(100.0);ping(200.0)
        assertEquals(model.metersPerBin!!*200,model.depth()!!,1e-9)
        assertEquals(model.metersPerBin!!*150,model.depthDisplay().valueM!!,1e-9)
        model.setFreeze(true);val frozen=model.depthDisplay();ping(300.0)
        assertEquals(frozen,model.depthDisplay())
        assertEquals(model.metersPerBin!!*200,model.liveDepthDisplay().valueM!!,1e-9)
        mono+=6_000_000_000;assertEquals(frozen,model.depthDisplay());assertNull(model.liveDepthDisplay().valueM)
        model.setFreeze(false);assertNull(model.depthDisplay().valueM)
        ping(400.0);assertNotNull(model.depthDisplay().valueM)
        model.setCalibration(Calibration(true,1.0,depthReference=DepthReference.SURFACE,transducerOffsetM=2.0))
        assertNull(model.depthDisplay().valueM)
        ping(500.0);assertNotNull(model.depthDisplay().valueM)
        model.updateContext(SonarEpoch("new",1));assertNull(model.depthDisplay().valueM)
    }
}
