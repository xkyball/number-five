package com.no5.helm.network

import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class PiPollSchedulerTest {
    @Test fun admittedReadUsesSameWorkerWithoutRunningOrReschedulingFeaturePoll() {
        val initial=CountDownLatch(1);val release=CountDownLatch(1);val done=CountDownLatch(1)
        val polls=AtomicInteger();val reads=AtomicInteger();val threads=java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()
        val sub=BackgroundPiPollScheduler("proof-read").schedule(10_000) {
            threads.add(Thread.currentThread().id);polls.incrementAndGet();initial.countDown();release.await(2,TimeUnit.SECONDS)
        } as ReadTaskPollSubscription
        val read=Runnable {threads.add(Thread.currentThread().id);reads.incrementAndGet();done.countDown()}
        try {
            assertTrue(initial.await(2,TimeUnit.SECONDS))
            repeat(100){assertTrue(sub.requestRead(read))}
            assertFalse(sub.requestRead(Runnable{fail("foreign queued read")}))
            release.countDown();assertTrue(done.await(2,TimeUnit.SECONDS))
        } finally {release.countDown();sub.close()}
        assertEquals(1,polls.get());assertEquals(1,reads.get());assertEquals(1,threads.size)
        assertFalse(sub.requestRead(read))
    }
    @Test fun closeRemovesQueuedReadAndReleasesItsOwnerWithoutExecution() {
        val initial=CountDownLatch(1);val release=CountDownLatch(1);val exited=CountDownLatch(1)
        val sub=BackgroundPiPollScheduler("proof-close").schedule(10_000) {
            initial.countDown();try {release.await(2,TimeUnit.SECONDS)}catch(_:InterruptedException){}finally{exited.countDown()}
        } as ReadTaskPollSubscription
        assertTrue(initial.await(2,TimeUnit.SECONDS));assertTrue(sub.requestRead(Runnable{fail("closed read")}))
        sub.close();release.countDown();assertTrue(exited.await(2,TimeUnit.SECONDS))
        assertFalse(sub.requestRead(Runnable{}))
    }
    @Test fun wakeIsCoalescedAndSerializedOnTheExistingWorkerWithoutPeriodicFanout() {
        val initial = CountDownLatch(1); val release = CountDownLatch(1); val woke = CountDownLatch(1)
        val calls = AtomicInteger(); val active = AtomicInteger(); val maximum = AtomicInteger()
        val threads = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()
        val subscription = BackgroundPiPollScheduler("wake-test").schedule(10_000) {
            threads.add(Thread.currentThread().id)
            maximum.updateAndGet { maxOf(it, active.incrementAndGet()) }
            try {
                if (calls.incrementAndGet() == 1) { initial.countDown(); check(release.await(2, TimeUnit.SECONDS)) }
                else woke.countDown()
            } finally { active.decrementAndGet() }
        } as WakeablePollSubscription
        try {
            assertTrue(initial.await(2, TimeUnit.SECONDS))
            repeat(100) { assertTrue(subscription.requestNow()) }
            release.countDown(); assertTrue(woke.await(2, TimeUnit.SECONDS))
        } finally { release.countDown(); subscription.close() }
        assertEquals(2, calls.get()); assertEquals(1, maximum.get()); assertEquals(1, threads.size)
        assertFalse(subscription.requestNow()); Thread.sleep(30); assertEquals(2, calls.get())
    }

    @Test fun wakeRuntimeFailureDoesNotRescheduleTheWakeOrDisableOriginalLoop() {
        val first = CountDownLatch(1); val release = CountDownLatch(1)
        val waking = CountDownLatch(1); val recovered = CountDownLatch(1)
        val calls = AtomicInteger()
        val subscription = BackgroundPiPollScheduler("wake-failure").schedule(100) {
            when (calls.incrementAndGet()) {
                1 -> { first.countDown(); check(release.await(2, TimeUnit.SECONDS)) }
                2 -> { waking.countDown(); throw IllegalStateException("Synthetic wake failure") }
                else -> recovered.countDown()
            }
        } as WakeablePollSubscription
        try {
            assertTrue(first.await(2, TimeUnit.SECONDS)); assertTrue(subscription.requestNow())
            release.countDown()
            assertTrue(waking.await(2, TimeUnit.SECONDS)); assertTrue(recovered.await(2, TimeUnit.SECONDS))
        } finally { release.countDown(); subscription.close() }
        assertFalse(subscription.requestNow())
    }

    @Test fun requestDurationIsCompensatedAndMissedSlotsAreNeverReplayed() {
        val ms = 1_000_000L
        assertEquals(48 * ms, nextPollDelayNanos(50 * ms, 100 * ms, 102 * ms))
        assertEquals(1 * ms, nextPollDelayNanos(50 * ms, 100 * ms, 149 * ms))
        assertEquals(50 * ms, nextPollDelayNanos(50 * ms, 100 * ms, 150 * ms))
        assertEquals(50 * ms, nextPollDelayNanos(50 * ms, 100 * ms, 3_100 * ms))
    }

    @Test fun slowRequestsRemainSerializedAndCloseStopsNewRequests() {
        val active = AtomicInteger()
        val maximumActive = AtomicInteger()
        val calls = AtomicInteger()
        val completions = mutableListOf<Long>()
        val starts = mutableListOf<Long>()
        val reached = CountDownLatch(3)
        val subscription = BackgroundPiPollScheduler("slow-test").schedule(50) {
            maximumActive.updateAndGet { maxOf(it, active.incrementAndGet()) }
            synchronized(starts) { starts += System.nanoTime() }
            calls.incrementAndGet()
            try { Thread.sleep(90) } finally {
                active.decrementAndGet()
                synchronized(completions) { completions += System.nanoTime() }
                reached.countDown()
            }
        }
        try { assertTrue(reached.await(3, TimeUnit.SECONDS)) } finally { subscription.close() }
        assertEquals(1, maximumActive.get())
        val before = calls.get()
        Thread.sleep(100)
        assertEquals(before, calls.get())
        synchronized(starts) {
            synchronized(completions) {
                for (i in 1 until minOf(3, starts.size))
                    assertTrue(starts[i] - completions[i - 1] >= TimeUnit.MILLISECONDS.toNanos(40), "Overrun must leave a polling interval")
            }
        }
    }
}
