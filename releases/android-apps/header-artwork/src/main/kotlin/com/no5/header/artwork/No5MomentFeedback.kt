package com.no5.header.artwork

import android.os.SystemClock

/** Process-local presentation only. Neither Binder admission nor network I/O calls this owner.
 * One current message replaces repetitions; there is no notification/history queue. */
object No5MomentFeedback {
    enum class Phase { ACCEPTED, EXECUTED, FAILED, UNKNOWN }
    data class Moment(val ticket: Long, val action: String, val phase: Phase,
        val expiresAt: Long)
    private var nextTicket = 0L
    private var owner: Any? = null
    private var current: Moment? = null
    private var ownerGeneration = 0L
    private val observers = linkedSetOf<(Moment?) -> Unit>()

    /** Call after admission on the UI owner. Retired tickets cannot replace newer messages. */
    fun begin(owner: Any, action: String, generation: Long = 0L): Long {
        this.owner = owner; ownerGeneration = generation
        val ticket = ++nextTicket
        publish(Moment(ticket, action, Phase.ACCEPTED, SystemClock.uptimeMillis() + 2_500))
        return ticket
    }
    fun complete(ticket: Long, phase: Phase) {
        val message = current?.takeIf { it.ticket == ticket } ?: return
        publish(message.copy(phase = phase, expiresAt = SystemClock.uptimeMillis() +
            if (phase == Phase.FAILED || phase == Phase.UNKNOWN) 5_000 else 2_500))
    }
    fun invalidate(owner: Any, retiredBefore: Long = Long.MAX_VALUE) {
        if (this.owner === owner && ownerGeneration < retiredBefore) clear()
    }
    fun clear() { owner = null; publish(null) }
    fun current(): Moment? = current?.takeIf { it.expiresAt > SystemClock.uptimeMillis() }
    fun observe(observer: (Moment?) -> Unit): AutoCloseable {
        observers.add(observer); observer(current())
        return AutoCloseable { observers.remove(observer) }
    }
    private fun publish(value: Moment?) {
        current = value
        observers.toList().forEach { it(value) }
    }
}
