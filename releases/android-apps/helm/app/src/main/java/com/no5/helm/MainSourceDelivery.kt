package com.no5.helm

import android.os.Handler
import android.os.Looper

/** Latest UI observation for one feed. Raw SONAR packets never pass through this mailbox. */
internal class MainSourceDelivery : AutoCloseable {
    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()
    private var pending: (() -> Unit)? = null
    private var scheduled = false
    private var closed = false
    private val drain = Runnable {
        val action = synchronized(lock) {
            scheduled = false
            pending.also { pending = null }
        }
        action?.invoke()
    }
    fun deliver(action: () -> Unit) {
        if (Looper.myLooper() == main.looper) {
            synchronized(lock) {
                if (closed) return
                pending = null
                main.removeCallbacks(drain)
                scheduled = false
            }
            action()
        } else synchronized(lock) {
            if (closed) return
            pending = action
            if (!scheduled) {
                scheduled = true
                main.post(drain)
            }
        }
    }
    override fun close() = synchronized(lock) {
        closed = true
        pending = null
        main.removeCallbacks(drain)
        scheduled = false
    }
}
