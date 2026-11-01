package com.no5.helm

import com.no5.domain.*
import org.junit.Assert.*
import org.junit.Test

class SonarImageContinuityControllerTest {
    @Test fun observedQueueLossDisconnectAndStaleStatusSplitOnlyImageContinuity() {
        var now=1_000_000L
        val base=DeterministicMockSonarSource(now={now})
        val selection=SonarDisplaySelection(SonarPingSource.OWN,1)
        val statuses=MutableSourceFeed(SonarSourceStatus(base.identity,1,true,true,serverSessionId="synthetic-stream",serverGeneration=1,displaySelection=selection))
        val packets=MutableSourceFeed<SonarSourceFrame?>(null)
        val source=object:SonarSourcePort by base {
            override val status:SourceFeed<SonarSourceStatus> get()=statuses
            override val frames:SourceFeed<SonarSourceFrame?> get()=packets
        }
        val c=SonarNativeController(source){now}
        try {
            fun frame(id:Long) {
                packets.emit(SonarSourceFrame(base.identity,1,SonarFrame(ShortArray(1024){(id*10).toShort()},id,now),
                    serverSessionId="synthetic-stream",geometry=SonarGeometry(3.072,SonarTxReference.HOST_VERIFIED),
                    displaySelection=selection,pingSource=SonarPingSource.OWN,amplitude=SonarAmplitude(4095,12,true)))
                now+=100
            }
            frame(1);val first=c.model.ring.imageSegmentAt(0)
            statuses.emit(statuses.value.copy(queueDrops=1));frame(2)
            val dropped=c.model.ring.imageSegmentAt(0);assertNotEquals(first,dropped)
            statuses.emit(statuses.value.copy(connected=false,running=false))
            statuses.emit(statuses.value.copy(connected=true,running=true));frame(3)
            val reconnected=c.model.ring.imageSegmentAt(0);assertNotEquals(dropped,reconnected)
            statuses.emit(statuses.value.copy(stale=true));statuses.emit(statuses.value.copy(stale=false));frame(4)
            assertNotEquals(reconnected,c.model.ring.imageSegmentAt(0))
            assertEquals(4,c.model.ring.count)
            now+=500 // Allow the unchanged 2-Hz rate display cache to publish the new count.
            assertEquals(4,c.model.measuredRate().eventCount)
            assertEquals(40,c.model.ring.sampleAt(0,100));assertEquals(10,c.model.ring.sampleAt(3,100))
            val stable=c.model.ring.imageSegmentAt(0)
            statuses.emit(statuses.value);frame(5)
            assertEquals("Repeated identical status is not a new gap",stable,c.model.ring.imageSegmentAt(0))
        }finally{c.dispose()}
    }
}
