package com.no5.helm

import com.no5.domain.*
import org.junit.Assert.*
import org.junit.Test

class SonarQuietControlsTest {
    private class Input {
        var now=1000000L
        val base=DeterministicMockSonarSource(now={now})
        val selection=SonarDisplaySelection(SonarPingSource.OWN,1)
        val status=MutableSourceFeed(SonarSourceStatus(base.identity,1,true,true,serverSessionId="quiet-test",serverGeneration=1,displaySelection=selection))
        val batches=MutableSourceFeed<List<SonarSourceFrame>?>(null)
        val port=object:SonarSourcePort by base {
            override val status:SourceFeed<SonarSourceStatus> get()=this@Input.status
            override val frameBatches:SourceFeed<List<SonarSourceFrame>?> get()=batches
        }
        val controller=SonarNativeController(port){now}
        fun send(first:Int,n:Int) {
            val packets=(first until first+n).map{i -> now+=50;SonarSourceFrame(base.identity,1,SonarFrame(ShortArray(1024){if(it in 400..500)400 else 40},i.toLong(),now),
                serverSessionId="quiet-test",geometry=SonarGeometry(3.072,SonarTxReference.HOST_VERIFIED),displaySelection=selection,pingSource=SonarPingSource.OWN,amplitude=SonarAmplitude(4095,12,true))}
            batches.emit(packets);batches.emit(null)
        }
    }
    @Test fun batchAcceptsEveryPingInOrderAndGainKnobRetainsAuto() {
        val h=Input();val c=h.controller
        try {
            c.imageAuto(true);h.send(1,100)
            assertEquals(100,c.model.ring.count);assertEquals(1,c.model.ring.pingIdAt(99));assertEquals(100L,c.snapshot.pingId)
            val before=c.model.ring.imageValuesAt(50,c.model.viewDisplay)
            c.gain(2.0)
            assertEquals(SonarImageMode.AUTO,c.snapshot.display.gainMode);assertEquals(2.0,c.snapshot.display.autoGainOffsetDb,0.0)
            assertEquals(before.gainDb+2,c.model.ring.imageValuesAt(50,c.model.viewDisplay).gainDb,.000001)
            h.send(101,100);assertEquals(200,c.model.ring.count)
            assertEquals(sonarGainLabel(c.model.viewDisplay),sonarPanelPresentation(c,DepthUnit.METERS).gainLabel)
        }finally{c.dispose()}
    }
    @Test fun stateV2PreservesOffsetAndManualValuesWithoutClaimingRetainedData() {
        val a=Input();val b=Input()
        try {
            a.controller.imageAuto(true);a.controller.gain(4.0)
            val saved=a.controller.exportPresentationState()
            assertEquals(4,saved.version);b.controller.restorePresentationState(saved)
            assertEquals(4.0,b.controller.snapshot.display.autoGainOffsetDb,0.0)
            assertEquals(SonarImageMode.AUTO,b.controller.snapshot.display.gainMode)
            assertEquals(0,b.controller.snapshot.ringCount)
            b.controller.imageMode(SonarImageField.GAIN,SonarImageMode.MANUAL);b.controller.setGain(7.0)
            assertEquals(7.0,b.controller.snapshot.display.gainDb,0.0)
            assertEquals(4.0,a.controller.snapshot.display.autoGainOffsetDb,0.0)
        }finally{a.controller.dispose();b.controller.dispose()}
    }
}
