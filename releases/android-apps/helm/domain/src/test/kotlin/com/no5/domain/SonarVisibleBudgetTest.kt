package com.no5.domain

import org.junit.Assert.*
import org.junit.Test
import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference

class SonarVisibleBudgetTest {
    private fun frame(id: Long, time: Long = id * 50) = SonarFrame(ShortArray(1024) { (id + it).toShort() }, id, time)
    @Test fun horizonBudgetAndUint16Originals() {
        assertEquals(4800, sonarVisiblePingBudget(240.0))
        assertEquals(12000, sonarVisiblePingBudget(600.0))
        val ring = SonarRingBuffer(4800)
        repeat(9601) { ring.push(frame(it.toLong())) }
        assertEquals(4800, ring.count)
        assertEquals(9600L, ring.pingIdAt(0)); assertEquals(4801L, ring.pingIdAt(4799))
        assertEquals(4800L * 2048, ring.allocatedSampleBytes)
        assertEquals((9600 + 1023) and 65535, ring.sampleAt(0,1023))
    }
    @Test fun sparseOldColumnsAreRemovedAndHorizonShrinkReleasesOldOwner() {
        val old = SonarRingBuffer(4800)
        repeat(200) { old.push(frame(it.toLong(), it * 2000L)) }
        old.evictBefore(388000.0)
        assertEquals(6, old.count)
        val next = old.moveToCapacity(200)
        assertEquals(0, old.count); assertEquals(0L, old.allocatedSampleBytes)
        assertEquals(6, next.count); assertEquals(199L, next.pingIdAt(0))
        next.clear(); assertEquals(0L, next.allocatedSampleBytes)
    }
    @Test fun latestAndHistoryShareOneImmutableFrameAndOverwriteDropsIt() {
        val ring = SonarRingBuffer(1)
        val original = frame(1); ring.push(original)
        assertSame(original, ring.atAge(0))
        val external = original.samples; external.fill(0)
        assertEquals(1, ring.sampleAt(0, 0))
        ring.push(frame(2)); assertEquals(2, ring.sampleAt(0,0))
    }
    @Test fun overwrittenFrameIsCollectableViaReferenceQueue() {
        val queue = ReferenceQueue<SonarFrame>()
        val ring = SonarRingBuffer(1)
        fun seed(): WeakReference<SonarFrame> {
            val old = frame(1); ring.push(old); return WeakReference(old, queue)
        }
        val reference = seed(); ring.push(frame(2))
        // Test-only GC; production never calls GC or relies on GC to enforce its count cap.
        repeat(20) { if (reference.get() != null) { System.gc(); Thread.sleep(10) } }
        assertNull(reference.get()); assertSame(reference, queue.remove(1000))
    }
    @Test fun freezeKeepsSameRawOwnerAndDoesNotAppendLiveHistory() {
        var now = 100000L
        val m = SonarModel { now }.also { it.connect("TEST ONLY"); it.acquisition(true) }
        m.setDisplay(m.display.copy(historySeconds=10.0))
        repeat(300) { now += 50; m.frame(frame(it.toLong(),now)) }
        val owner=m.ring; val latest=m.ring.pingIdAt(0)
        m.setFreeze(true)
        repeat(300) { now += 50; m.frame(frame((it+300).toLong(),now)) }
        assertSame(owner,m.viewRing); assertEquals(200,m.ring.count)
        assertEquals(latest,m.viewRing.pingIdAt(0)); assertEquals(599L,m.snapshot().pingId)
        m.setFreeze(false); now += 50; m.frame(frame(600,now))
        assertEquals(1,m.ring.count); assertEquals(600L,m.ring.pingIdAt(0))
        assertFalse(m.imageHistoryAggregated)
    }
}
