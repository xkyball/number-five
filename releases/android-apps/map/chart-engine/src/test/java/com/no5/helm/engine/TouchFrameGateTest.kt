package com.no5.helm.engine
import org.junit.Test
import org.junit.Assert.*
import java.util.concurrent.*
import com.no5.helm.chart.*
class TouchFrameGateTest {
    @Test fun heldTouchDoesNotStartFramesAndLastUpCanStartImmediately() {
        val gate=TouchFrameGate();gate.held.set(true);assertNull(gate.begin(1,100))
        gate.held.set(false);assertNotNull(gate.begin(1,101));assertNull(gate.begin(2,102))
    }
    @Test fun runningNativeWorkFinishesButRetouchAndLifecycleCannotAcceptItsBuffer() {
        val gate=TouchFrameGate();val b=gate.begin(1,100)!!;val latch=CountDownLatch(1);val finish=CountDownLatch(1);val worker=Executors.newSingleThreadExecutor()
        try { val job=worker.submit { latch.countDown();assertTrue(finish.await(5,TimeUnit.SECONDS));gate.swapped(b,null) };assertTrue(latch.await(1,TimeUnit.SECONDS))
            gate.invalidate();gate.held.set(true);repeat(1000){assertNull(gate.begin(it.toLong(),101))};assertFalse(job.isDone)
            finish.countDown();job.get(1,TimeUnit.SECONDS);assertNull(gate.consume(100));assertFalse(gate.valid(b,1))
            gate.held.set(false);val latest=gate.begin(1000,102)!!;gate.swapped(latest,null);assertSame(latest,gate.consume(102));assertTrue(gate.valid(latest,1000));gate.invalidate();assertFalse(gate.valid(latest,1000))
        } finally { finish.countDown();worker.shutdownNow() }
    }
    @Test fun producerCompletionExactTimestampRevisionAndSingleAckAreRequired() {
        val g=TouchFrameGate();val b=g.begin(9,100)!!;assertNull(g.consume(100));assertNull(g.consume(99))
        val frame=ChartFrame(1,"completed:9",ChartSurfaceGeometry.fit(2000,1200),ChartViewport(42.36,-71.04,.15),null)
        g.swapped(b,frame)
        assertNull(g.consume(99));val consumed=g.consume(100);assertSame(b,consumed);assertSame(frame,consumed!!.frame)
        assertNull(g.consume(100));assertTrue(g.valid(b,9));assertFalse(g.valid(b,10));g.held.set(true);assertFalse(g.valid(b,9))
    }
}
