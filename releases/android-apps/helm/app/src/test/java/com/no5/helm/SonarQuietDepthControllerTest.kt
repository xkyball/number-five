package com.no5.helm

import com.no5.domain.*
import org.junit.Assert.*
import org.junit.Test

class SonarQuietDepthControllerTest {
    private class Input {
        var now=1_000_000L
        val base=DeterministicMockSonarSource(now={now})
        val selection=SonarDisplaySelection(SonarPingSource.OWN,1)
        val state=MutableSourceFeed(SonarSourceStatus(base.identity,1,true,true,
            serverSessionId="depth-test",serverGeneration=1,displaySelection=selection))
        val batches=MutableSourceFeed<List<SonarSourceFrame>?>(null)
        val port=object:SonarSourcePort by base {
            override val status:SourceFeed<SonarSourceStatus> get()=state
            override val capabilities:SourceFeed<SonarSourceCapabilities> = MutableSourceFeed(SonarSourceCapabilities(base.identity,bottomDetection=true))
            override val frameBatches:SourceFeed<List<SonarSourceFrame>?> get()=batches
        }
        val controller=SonarNativeController(port){now}
        fun packets(first:Int,n:Int)= (first until first+n).map { i ->
            now+=50
            SonarSourceFrame(base.identity,1,SonarFrame(ShortArray(1024){i.toShort()},i.toLong(),now),
                bottom=if(i%7==0)SonarBottom()else SonarBottom(100.0+i,.99),
                serverSessionId="depth-test",geometry=SonarGeometry(3.072,SonarTxReference.HOST_VERIFIED),
                displaySelection=selection,pingSource=SonarPingSource.OWN,amplitude=SonarAmplitude(4095,12,true))
        }
        fun send(packets:List<SonarSourceFrame>){batches.emit(packets);batches.emit(null)}
    }
    @Test fun batchAndIndividualAdoptionHaveSameDepthAndKeepEveryRawPing() {
        val a=Input();val b=Input()
        try {
            a.send(a.packets(1,100))
            repeat(100){b.send(b.packets(it+1,1))}
            assertEquals(a.controller.model.depthDisplay().valueM!!,b.controller.model.depthDisplay().valueM!!,1e-9)
            assertEquals(100,a.controller.model.ring.count);assertEquals(100,b.controller.model.ring.count)
            assertEquals(1,a.controller.model.ring.sampleAt(99,0));assertEquals(100,a.controller.model.latest!!.sample(0))
            assertEquals(sonarPanelPresentation(a.controller,DepthUnit.METERS).depthLabel,
                sonarPanelPresentation(b.controller,DepthUnit.METERS).depthLabel)
            assertEquals(a.controller.model.bottom,b.controller.model.bottom)
        }finally{a.controller.dispose();b.controller.dispose()}
    }
    @Test fun preferencesRestoreVersionFourWithoutRestoringMeasurementsOrChangingAnotherController() {
        val a=Input();val b=Input()
        try {
            a.controller.depthProcessing(SonarDepthMode.DIRECT);a.controller.depthWindowSeconds(12)
            a.send(a.packets(1,1));val saved=a.controller.exportPresentationState()
            assertEquals(4,saved.version);b.controller.restorePresentationState(saved)
            assertEquals(SonarDepthSettings(SonarDepthMode.DIRECT,12),b.controller.model.depthSettings)
            assertNull(b.controller.model.depthDisplay().valueM)
            b.controller.depthProcessing(SonarDepthMode.SMOOTHED)
            assertEquals(SonarDepthMode.DIRECT,a.controller.model.depthSettings.mode)
        }finally{a.controller.dispose();b.controller.dispose()}
    }
    @Test fun autoIntentCanBeChosenWithoutBottomAndSurvivesUnavailableData() {
        val h=Input();val c=h.controller
        try {
            c.autoRange(true);assertEquals(SonarRangeMode.AUTO,c.requestedRangeMode)
            h.send(h.packets(1,1));assertEquals(SonarRangeMode.AUTO,c.snapshot.display.rangeMode)
            c.model.setBottom(SonarBottom(),true);c.refresh()
            assertEquals(SonarRangeMode.AUTO,c.requestedRangeMode)
            assertEquals(SonarRangeMode.AUTO,c.exportPresentationState().display.rangeMode)
            c.autoRange(false);assertEquals(SonarRangeMode.MANUAL,c.requestedRangeMode)
        }finally{c.dispose()}
    }
}
