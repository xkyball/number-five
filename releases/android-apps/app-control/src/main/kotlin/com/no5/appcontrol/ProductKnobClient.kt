package com.no5.appcontrol

import android.content.Context
import android.os.Looper
import com.no5.control.*

/**
 * Common product-facing lease API.
 *
 * A map is an explicit, finite lease: only its keys are claimed by this
 * visible screen. Every other key remains with Lumina's direct audio default.
 * The underlying client tears the lease down on pause/focus loss and binder
 * death; callers do not need a separate foreground poller.
 */
class ProductKnobClient(context: Context) : AutoCloseable {
    private var actions = emptyMap<NormalizedKnobInput, () -> ClientDisposition>()
    private var asynchronous = emptyMap<NormalizedKnobInput, (KnobActionTicket) -> Unit>()
    @Volatile private var buffered = emptyMap<NormalizedKnobInput, BufferedKnobAction>()
    @Volatile private var resultProvider:BufferedKnobAction? = null
    private val client = LuminaKnobClient(context,
        buffered = BufferedKnobSubmit { input, sequence, time -> buffered[input]?.submit(sequence, time) ?: -1L },
        query = { id, reply -> resultProvider?.writeResult(id, reply) ?: false },
        dispatch = { input, ticket -> asynchronous[input]?.invoke(ticket) ?: ticket.complete(actions[input]?.invoke() ?: ClientDisposition.AUDIO_DEFAULT) })
    fun requestLease(next: Map<NormalizedKnobInput, () -> ClientDisposition>) {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (buffered.isNotEmpty()) client.update(emptySet())
        clearBufferedBindings(); actions = next.toMap()
        asynchronous = emptyMap()
        client.update(actions.keys)
    }
    fun requestLeaseAsync(next: Map<NormalizedKnobInput, (KnobActionTicket) -> Unit>) {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (buffered.isNotEmpty()) client.update(emptySet())
        clearBufferedBindings(); actions = emptyMap(); asynchronous = next.toMap(); client.update(asynchronous.keys)
    }
    fun requestLeaseBuffered(next: Map<NormalizedKnobInput, BufferedKnobAction>) {
        check(Looper.myLooper() == Looper.getMainLooper())
        val replacement = next.toMap()
        if (buffered.keys != replacement.keys || buffered.any { (input, action) -> replacement[input] !== action }) {
            // Replacing an owner, even for the same keys, revokes admission
            // before cancelling that owner's outstanding work.
            if (buffered.isNotEmpty()) client.update(emptySet())
            clearBufferedBindings()
        }
        buffered = replacement; resultProvider = replacement.values.firstOrNull()
        actions = emptyMap(); asynchronous = emptyMap(); client.update(buffered.keys)
    }
    private fun cancelBufferedWork() { buffered.values.forEach { it.cancel() } }
    private fun clearBufferedBindings() {
        val previous = buffered
        buffered = emptyMap(); resultProvider = null
        previous.values.forEach { it.cancel() }
    }
    val leaseReason: String? get() = client.reason
    /** Returns one or all currently claimed buttons to Lumina immediately. */
    fun releaseLease(buttons: Set<NormalizedKnobInput>? = null) {
        check(Looper.myLooper() == Looper.getMainLooper())
        client.update(emptySet())
        val retainedBuffered = if (buttons == null) emptyMap() else buffered.filterKeys { it !in buttons }
        clearBufferedBindings()
        buffered = retainedBuffered; resultProvider = retainedBuffered.values.firstOrNull()
        actions = if (buttons == null) emptyMap() else actions.filterKeys { it !in buttons }
        asynchronous = if (buttons == null) emptyMap() else asynchronous.filterKeys { it !in buttons }
        client.update(actions.keys + asynchronous.keys + buffered.keys)
    }
    /** Existing screens use this spelling; it retains the exact same lease semantics. */
    fun replaceBindings(next: Map<NormalizedKnobInput, () -> ClientDisposition>) = requestLease(next)
    fun onResume() = client.onResume()
    // Visibility loss cancels effects, not the screen's registered mapping.
    // Compose need not republish unchanged bindings when the Activity returns.
    fun onPause() { client.onPause(); cancelBufferedWork() }
    fun onWindowFocusChanged(hasFocus: Boolean) {
        client.onWindowFocusChanged(hasFocus)
        if (!hasFocus) cancelBufferedWork()
    }
    override fun close() { client.close(); clearBufferedBindings(); actions = emptyMap(); asynchronous = emptyMap() }
}

/** Admission is a bounded in-memory operation. Completion is read separately by the controller. */
interface BufferedKnobAction {
    fun submit(sequence: Long, physicalUptimeMs: Long): Long
    fun writeResult(commandId: Long, reply: android.os.Parcel): Boolean
    fun cancel()
}
