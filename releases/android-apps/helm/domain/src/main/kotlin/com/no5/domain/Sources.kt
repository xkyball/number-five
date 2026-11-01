package com.no5.domain

/** Selecting PI configures an adapter target; it does not establish a connection. */
enum class SourceMode { DEMO, PI }

/** A new adapter instance must use a new session ID so late replies can be rejected. */
data class SourceIdentity(val sessionId: String, val mode: SourceMode, val label: String) {
    init {
        require(sessionId.isNotBlank()) { "A source session ID is required" }
        require(label.isNotBlank()) { "A source label is required" }
    }
}

fun interface SourceSubscription { fun close() }

/** Read-only observation boundary. An open feed emits its current value on subscription. */
interface SourceFeed<T> {
    val value: T
    fun subscribe(listener: (T) -> Unit): SourceSubscription
}

/**
 * Small synchronous feed for adapters and deterministic mocks. Adapter ownership is required
 * for emit/close; screens receive only SourceFeed. Callbacks are serialized, including an
 * initial callback. Removing a listener or closing during a callback prevents later delivery.
 * Reentrant emissions are queued so every observer sees values in the same order.
 */
class MutableSourceFeed<T>(initial: T) : SourceFeed<T> {
    private class Observer<T>(val callback: (T) -> Unit)
    private class Emission<T>(val value: T)
    private val lock = Any()
    private val observers = linkedSetOf<Observer<T>>()
    private val pending = ArrayDeque<Emission<T>>()
    private var current = initial
    private var closed = false
    private var delivering = false

    override val value: T get() = synchronized(lock) { current }

    override fun subscribe(listener: (T) -> Unit): SourceSubscription = synchronized(lock) {
        if (closed) return@synchronized SourceSubscription { }
        val observer = Observer(listener)
        observers += observer
        try {
            listener(current)
        } catch (failure: RuntimeException) {
            observers -= observer
            throw failure
        }
        SourceSubscription { synchronized(lock) { observers -= observer } }
    }

    /** Returns false after close. Closed sources cannot change the final snapshot. */
    fun emit(value: T): Boolean = synchronized(lock) {
        if (closed) return@synchronized false
        pending += Emission(value)
        if (delivering) return@synchronized true
        delivering = true
        var firstFailure: RuntimeException? = null
        try {
            while (!closed && pending.isNotEmpty()) {
                current = pending.removeFirst().value
                val snapshot = current
                for (observer in observers.toList()) {
                    if (closed) break
                    if (observer !in observers) continue
                    try {
                        observer.callback(snapshot)
                    } catch (failure: RuntimeException) {
                        observers -= observer
                        if (firstFailure == null) firstFailure = failure
                    }
                }
            }
        } finally {
            delivering = false
        }
        firstFailure?.let { throw it }
        true
    }

    /** Idempotent. New subscriptions are inert after close. */
    fun close() = synchronized(lock) {
        closed = true
        observers.clear()
        pending.clear()
    }
}
