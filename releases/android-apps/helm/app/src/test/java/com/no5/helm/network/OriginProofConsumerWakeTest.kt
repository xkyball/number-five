package com.no5.helm.network

import org.junit.Assert.*
import org.junit.Test

class OriginProofConsumerWakeTest {
    private class Dispatcher : OriginProofConsumerDispatcher {
        val queue = ArrayDeque<Runnable>()
        var posts = 0
        var accept = true
        override fun post(task: Runnable): Boolean { posts++; if (accept) queue.add(task); return accept }
        override fun remove(task: Runnable) { queue.removeAll { it === task } }
        fun drain() { while (queue.isNotEmpty()) queue.removeFirst().run() }
    }
    @Test fun signalsCoalesceAndOnlyRunOnConsumer() {
        val d=Dispatcher();var calls=0;val w=OriginProofConsumerWake(d){calls++}
        repeat(100){w.ready()};assertEquals(1,d.posts);assertEquals(1,d.queue.size);assertEquals(0,calls)
        d.drain();assertEquals(1,calls);w.ready();d.drain();assertEquals(2,calls);w.close()
    }
    @Test fun periodicConsumptionCancelsQueuedDuplicate() {
        val d=Dispatcher();val w=OriginProofConsumerWake(d){fail("already consumed")}
        w.ready();w.cancel();d.drain();assertTrue(d.queue.isEmpty());w.close()
    }
    @Test fun closeRemovesQueuedWorkAndReleasesConsumer() {
        val d=Dispatcher();val w=OriginProofConsumerWake(d){fail("closed")}
        w.ready();w.close();w.ready();d.drain();assertEquals(1,d.posts);assertTrue(d.queue.isEmpty())
    }
    @Test fun rejectedPostIsStickyDiagnosticAndDoesNotLoop() {
        val d=Dispatcher().apply{accept=false};val w=OriginProofConsumerWake(d){fail("not dispatched")}
        w.ready();assertEquals(1,w.failures);assertEquals(1,d.posts);assertTrue(d.queue.isEmpty());w.close()
    }
}
