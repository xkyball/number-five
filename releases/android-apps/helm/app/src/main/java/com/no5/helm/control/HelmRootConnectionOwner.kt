package com.no5.helm.control

import com.no5.control.wire.BridgeWire

/** Main-owned finite retry of fresh one-shot channels. No packets, gestures or publications replay.
 * The underlying process-wide HelmRootChannel worker still refuses a second live reader.
 */
internal class HelmRootConnectionOwner(
    private val main: Main,
    private val elapsed: () -> Long,
    private val factory: (Callbacks) -> Channel,
    private val onConnected: () -> Unit,
    private val onPacket: (BridgeWire.Packet) -> Boolean,
    private val onDisconnected: (String) -> Unit,
    private val budgetStartedElapsed: Long? = null,
) : AutoCloseable {
    interface Main {
        fun check()
        fun postDelayed(task: Runnable, delayMs: Long): Boolean
        fun remove(task: Runnable)
    }
    interface Channel : AutoCloseable { fun start() }
    data class Callbacks(val connected: () -> Unit, val packet: (BridgeWire.Packet) -> Unit, val disconnected: (String) -> Unit)
    private class Attempt { var channel: Channel? = null }
    private var active: Attempt? = null
    private var started = false
    private var closed = false
    private var deadline = 0L
    private var highwater = 0L
    private var failures = 0
    private val retry = Runnable { attempt() }
    private val expire = Runnable { closeWithReason("candidate_deadline") }
    fun start() {
        main.check(); if (started || closed) return
        started = true; highwater = elapsed()
        if (highwater < 0 || highwater > Long.MAX_VALUE - LIFETIME_MS) { closeWithReason("clock_unknown"); return }
        val start = budgetStartedElapsed ?: highwater
        if (start < 0 || start > highwater || start > Long.MAX_VALUE - LIFETIME_MS || highwater >= start + LIFETIME_MS) {
            closeWithReason("candidate_deadline"); return
        }
        deadline = start + LIFETIME_MS
        if (!main.postDelayed(expire, deadline - highwater)) { closeWithReason("main_unavailable"); return }
        attempt()
    }
    private fun live(): Boolean {
        val now = elapsed()
        if (closed || now < highwater || now >= deadline) return false
        highwater = now; return true
    }
    private fun attempt() {
        main.check()
        if (!live()) { closeWithReason("candidate_deadline"); return }
        if (active != null) return
        val own = Attempt(); active = own
        try {
            val channel = factory(Callbacks(
                connected = { main.check(); if (active === own && live()) onConnected() },
                packet = { packet ->
                    main.check()
                    if (active === own) {
                        if (!live()) closeWithReason("candidate_deadline")
                        else if (!onPacket(packet)) failed(own, "admission_retired")
                    }
                },
                disconnected = { reason -> main.check(); if (active === own) failed(own, reason) }))
            own.channel = channel
            if (active !== own || closed) channel.close() else channel.start()
        } catch (failure: Throwable) {
            if (active === own) failed(own, "channel_start_failed")
            if (failure !is RuntimeException) throw failure
        }
    }
    private fun failed(own: Attempt, reason: String) {
        active = null // Fence all old callbacks before fallible close or consumer notification.
        var failure: Throwable? = null
        try { onDisconnected(reason) } catch (t: Throwable) { failure = t }
        try { own.channel?.close() } catch (t: Throwable) { if (failure == null) failure = t else failure!!.addSuppressed(t) }
        if (failure != null) { closeWithReason("cleanup_unknown"); throw failure!! }
        if (!live()) { closeWithReason("candidate_deadline"); return }
        val delay = BACKOFF[minOf(failures++, BACKOFF.lastIndex)].coerceAtMost(deadline - highwater)
        if (!main.postDelayed(retry, delay)) closeWithReason("main_unavailable")
    }
    override fun close() { main.check(); closeWithReason("owner_closed") }
    private fun closeWithReason(reason: String) {
        if (closed) return
        closed = true; main.remove(retry); main.remove(expire)
        val own = active; active = null
        var failure: Throwable? = null
        try { onDisconnected(reason) } catch (t: Throwable) { failure = t }
        try { own?.channel?.close() } catch (t: Throwable) { if (failure == null) failure = t else failure!!.addSuppressed(t) }
        failure?.let { throw it }
    }
    companion object {
        const val LIFETIME_MS = 90_000L
        private val BACKOFF = longArrayOf(100, 250, 500, 1_000)
    }
}
