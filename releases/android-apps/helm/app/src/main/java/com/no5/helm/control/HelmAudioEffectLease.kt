package com.no5.helm.control

/** The original knob action's local lease travels through the existing delayed AUDIO queue.
 * No new AudioCommand/transport. A successful local check is not an OS-global effect CAS.
 */
internal class HelmAudioEffectLease(
    val expiresUptime: Long,
    val sourceGeneration: Long,
    val sourceSession: String,
    capturedUptime: Long,
    private val uptime: () -> Long,
    private val scopeCurrent: () -> Boolean,
) {
    enum class State { CREATED, QUEUED, SEND_STARTED, LOCAL_DENIED, COMPLETION_UNKNOWN, VERIFIED_RECEIPT }
    private var highwater = capturedUptime
    private var ownerAlive: (() -> Boolean)? = null
    private var cancelled = false
    private var phase = State.CREATED
    val state: State get() = synchronized(this) { phase }
    init { require(sourceGeneration > 0 && sourceSession.isNotEmpty() && capturedUptime >= 0 && expiresUptime > capturedUptime) }
    @Synchronized fun bindOwner(generation: Long, session: String, alive: () -> Boolean): Boolean {
        if (phase != State.CREATED || ownerAlive != null || generation != sourceGeneration || session != sourceSession) return false
        ownerAlive = alive
        return valid(session)
    }
    @Synchronized fun claimQueue(session: String): Boolean {
        if (phase != State.CREATED || !valid(session)) { denyUnsent(); return false }
        phase = State.QUEUED; return true
    }
    @Synchronized fun dropQueuedNow(session: String): Boolean {
        if (phase == State.LOCAL_DENIED) return true
        if (phase != State.QUEUED || valid(session)) return false
        phase = State.LOCAL_DENIED; return true
    }
    @Synchronized fun beginSend(session: String): Boolean {
        if (phase != State.QUEUED || !valid(session)) { denyUnsent(); return false }
        phase = State.SEND_STARTED; return true
    }
    @Synchronized fun cancel() { cancelled = true; denyUnsent() }
    @Synchronized fun completionUnknown() { if (phase == State.SEND_STARTED) phase = State.COMPLETION_UNKNOWN }
    @Synchronized fun verifiedReceipt() {
        if (phase == State.SEND_STARTED || phase == State.COMPLETION_UNKNOWN) phase = State.VERIFIED_RECEIPT
    }
    private fun denyUnsent() { if (phase == State.CREATED || phase == State.QUEUED) phase = State.LOCAL_DENIED }
    private fun valid(session: String): Boolean {
        if (cancelled || session != sourceSession) return false
        val alive = ownerAlive ?: return false
        return try {
            val before = now()
            if (before < 0 || before >= expiresUptime || !alive() || !scopeCurrent()) false
            else {
                val stillAlive = alive() && !cancelled
                val after = now()
                stillAlive && after >= before && after < expiresUptime && !cancelled
            }
        } catch (_: RuntimeException) { false }
    }
    private fun now(): Long {
        val value = uptime()
        if (value < 0 || value < highwater) return -1
        highwater = value; return value
    }
}

/** Synchronous handoff only; the original queue stores the lease before this scope ends. */
internal object HelmAudioEffectContext {
    private val local = ThreadLocal<HelmAudioEffectLease>()
    fun current(): HelmAudioEffectLease? = local.get()
    fun <T> withLease(lease: HelmAudioEffectLease, block: () -> T): T {
        check(local.get() == null) { "Nested knob audio handoff" }
        local.set(lease)
        return try { block() } finally { local.remove() }
    }
}
