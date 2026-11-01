package com.no5.helm

import com.no5.domain.*
import com.no5.helm.gpu.SonarGpuCompletion
import org.junit.Assert.*
import org.junit.Test

class SonarGpuDisplayedSliceTest {
    private fun controller():SonarNativeController {
        val c=SonarNativeController(DeterministicMockSonarSource(now={1_000_000L})){1_000_000L}
        c.model.resetAcquisition();c.model.connect("PRIVATE SHOWN SLICE");c.model.acquisition(true)
        c.model.updateContext(SonarEpoch("slice",1,1),SonarPingSource.OWN)
        c.model.setDisplay(SonarDisplay(historySeconds=10.0))
        for(id in 0..10){c.model.setAnalysis(SonarGeometry(3.069,SonarTxReference.HOST_VERIFIED),SonarBottom(),null,false,amplitude=SonarAmplitude(4095,12,true))
            c.model.frame(SonarFrame(ShortArray(1024){(id*100+it).toShort()},id.toLong(),999_000L+id*50),pingSource=SonarPingSource.OWN)}
        c.refresh();return c
    }
    private fun stamp(c:SonarNativeController)=SonarGpuCompletion(c.gpuSourceContext(),c.model.viewRing.lifecycleGeneration,1,1600,900,5,10,999_250,
        displayRevision=c.displayRevision,display=c.model.viewDisplay,top=100.0,bottom=500.0,metersPerBin=.003,newestSlot=c.model.viewRing.storageSlotAt(5))
    @Test fun cursorUsesCommittedPingAndGeometryWhileNewerInputsAreQueued(){
        val c=controller();try{c.gpuPresented(stamp(c));c.inspect(1f,.25f)
            assertEquals(5L,c.cursor!!.pingId);assertEquals(200,c.cursor!!.bin);assertEquals(700,c.cursor!!.amplitude)
            assertEquals(999_250L,c.cursor!!.timestampEpochMs);assertEquals(5L,c.displayedGpuRawFrame()!!.pingId)
        }finally{c.dispose()}
    }
    @Test fun oldFrameCannotAcknowledgeNewDisplayControlRevision(){
        val c=controller();try{val s=stamp(c);c.gpuPresented(s);c.display{it.copy(gainDb=3.0)};assertTrue(c.displayRevision>s.displayRevision)
            c.gpuPresented(s);assertEquals(s.displayRevision,c.presentedDisplayRevision)
        }finally{c.dispose()}
    }
    @Test fun changedEpochCannotInspectUnrelatedNewPayloadAsOldDisplayedPixels(){
        val c=controller();try{c.gpuPresented(stamp(c));c.model.updateContext(SonarEpoch("new",2,1),SonarPingSource.OWN);c.refresh()
            c.inspect(1f,.25f);assertNull(c.cursor);assertNull(c.displayedGpuRawFrame())
        }finally{c.dispose()}
    }
    @Test fun actualBufferedTextureCanSupplyOverlayButCannotAcknowledgePresentation(){
        val c=controller();try{val old=c.presentedDisplayRevision;val value=stamp(c);c.gpuBuffered(value)
            assertSame(value,c.displayedGpuSlice());assertEquals(old,c.presentedDisplayRevision);assertNull(c.lastGpuPresentation)
            c.gpuPresented(value);assertSame(value,c.lastGpuPresentation)
        }finally{c.dispose()}
    }
    @Test fun lateClosedViewCannotClearNewerBufferedSlice(){
        val c=controller();try{val old=stamp(c);val next=old.copy(viewGeneration=2);c.gpuBuffered(next)
            c.gpuBufferDiscarded(old);assertSame(next,c.displayedGpuSlice());c.gpuBufferDiscarded(next);assertNull(c.displayedGpuSlice())
        }finally{c.dispose()}
    }

}
