package com.no5.control

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.FutureTask
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** One process-wide daemon and three queue slots, including across host re-creations. */
internal class BoundedControlIpc : AutoCloseable {
    private val executor = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(3), { task -> Thread(task, "no5-control-ipc").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy())

    fun execute(task: FutureTask<*>): Boolean = try { executor.execute(task); true }
        catch (_: RejectedExecutionException) { false }

    fun cancel(task: FutureTask<*>) { executor.remove(task); task.cancel(false) }

    override fun close() { executor.shutdownNow().filterIsInstance<FutureTask<*>>().forEach { it.cancel(false) } }
}

internal object ProcessControlIpc { val worker = BoundedControlIpc() }
