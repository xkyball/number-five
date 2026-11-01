package com.no5.helm.network

import android.os.Handler
import android.os.Looper

/** A completion event, never a periodic source/HTTP poll. */
interface OriginProofConsumerDispatcher {
    fun post(task: Runnable): Boolean
    fun remove(task: Runnable)
}

/** Product source feeds are consumed on the app-root Main dispatcher. */
class MainOriginProofConsumerDispatcher : OriginProofConsumerDispatcher {
    @Volatile private var handler: Handler? = null
    override fun post(task: Runnable): Boolean {
        val selected = handler ?: synchronized(this) {
            handler ?: Handler(Looper.getMainLooper()).also { handler = it }
        }
        return selected.post(task)
    }
    override fun remove(task: Runnable) { handler?.removeCallbacks(task) }
}

/** At most one queued ready signal; no proof, frame or callback history. The
 * consumer still drains source/epoch events before checking the original bound. */
internal class OriginProofConsumerWake(private val dispatcher: OriginProofConsumerDispatcher,
    consume: () -> Unit) : AutoCloseable {
    private val lock = Any()
    private var consumer: (() -> Unit)? = consume
    private var queued = false
    private var closed = false
    @Volatile var failures = 0; private set
    private val task = Runnable {
        val target = synchronized(lock) {
            if (closed || !queued) null else { queued = false; consumer }
        }
        target?.invoke()
    }
    fun ready() = synchronized(lock) {
        if (closed || queued) return@synchronized
        queued = true
        if (!dispatcher.post(task)) { queued = false; if (failures < Int.MAX_VALUE) failures++ }
    }
    fun cancel() = synchronized(lock) { queued = false; dispatcher.remove(task) }
    override fun close() = synchronized(lock) {
        closed = true; queued = false; consumer = null; dispatcher.remove(task)
    }
}
