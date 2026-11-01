package com.no5.helm.network

import com.no5.domain.SourceSubscription
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Injected in tests; production owns a different executor per feature. */
fun interface PiPollScheduler {
    fun schedule(intervalMs: Long, poll: () -> Unit): SourceSubscription
}

/** Optional asynchronous wake on the subscription's EXISTING single executor.
 * True includes coalescing with a pending wake. Never polls inline or renews a deadline.
 */
interface WakeablePollSubscription : SourceSubscription { fun requestNow(): Boolean }

/** One admitted read on this subscription's EXISTING worker. Unlike requestNow,
 * this never runs the feature poll or advances commands/selection/diagnostics. */
interface ReadTaskPollSubscription : SourceSubscription { fun requestRead(task: Runnable): Boolean }

/** Compensate for request work, but skip missed slots instead of issuing catch-up bursts. */
internal fun nextPollDelayNanos(intervalNanos: Long, startedAt: Long, finishedAt: Long): Long {
    require(intervalNanos > 0)
    val elapsed = (finishedAt - startedAt).coerceAtLeast(0)
    return if (elapsed >= intervalNanos) intervalNanos else intervalNanos - elapsed
}

class BackgroundPiPollScheduler(private val feature: String) : PiPollScheduler {
    override fun schedule(intervalMs: Long, poll: () -> Unit): SourceSubscription {
        require(intervalMs > 0)
        val closed = AtomicBoolean(false)
        val intervalNanos = TimeUnit.MILLISECONDS.toNanos(intervalMs)
        val executor = ScheduledThreadPoolExecutor(1) { task ->
            Thread(task, "no5-$feature-poll").apply { isDaemon = true }
        }.apply { removeOnCancelPolicy = true }
        val task = object : Runnable {
            override fun run() {
                if (closed.get()) return
                val startedAt = System.nanoTime()
                try { poll() } catch (_: RuntimeException) {
                    // A failed observer must not permanently terminate this feature's polling.
                } finally {
                    if (!closed.get()) {
                        val delay = nextPollDelayNanos(intervalNanos, startedAt, System.nanoTime())
                        try { executor.schedule(this, delay, TimeUnit.NANOSECONDS) }
                        catch (_: java.util.concurrent.RejectedExecutionException) { /* close raced reschedule */ }
                    }
                }
            }
        }
        try { executor.execute(task) } catch (failure: RuntimeException) {
            executor.shutdownNow()
            throw failure
        }
        val wakePending = AtomicBoolean(false)
        val wake = Runnable {
            // One running job plus at most one queued wake, all on this SAME single executor.
            // Releasing at entry lets a new command during completion retain its own wake.
            wakePending.set(false)
            if (!closed.get()) {
                try { poll() } catch (_: RuntimeException) { /* original periodic loop still owns recovery */ }
            }
            // This one-shot wake NEVER reschedules itself or changes the periodic cadence.
        }
        val queuedRead = AtomicReference<Runnable?>(null)
        val read = Runnable { queuedRead.getAndSet(null)?.let { if (!closed.get()) it.run() } }
        return object : WakeablePollSubscription, ReadTaskPollSubscription {
            override fun requestRead(task: Runnable): Boolean {
                if (closed.get()) return false
                if (!queuedRead.compareAndSet(null, task)) return !closed.get() && queuedRead.get() === task
                return try {
                    executor.execute(read)
                    !closed.get()
                } catch (_: java.util.concurrent.RejectedExecutionException) {
                    queuedRead.compareAndSet(task, null); false
                }
            }
            override fun requestNow(): Boolean {
                if (closed.get()) return false
                if (!wakePending.compareAndSet(false, true)) return !closed.get()
                return try {
                    executor.execute(wake)
                    !closed.get()
                } catch (_: java.util.concurrent.RejectedExecutionException) {
                    wakePending.set(false); false
                }
            }
            override fun close() {
                if (closed.compareAndSet(false, true)) { queuedRead.set(null); wakePending.set(false); executor.shutdownNow() }
            }
        }
    }
}
