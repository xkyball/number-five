package com.no5.header

/** Implement with SystemClock.elapsedRealtime/currentTimeMillis in Android hooks, not in sensor adapters. */
interface HeaderClock {
    fun elapsedRealtimeMs(): Long
    fun epochMs(): Long
}

/** Native transport boundary only. Root must supply IPC, caller authentication and death monitoring. */
fun interface NativeHeaderTransport {
    fun publish(snapshot: HeaderSnapshotV1): HeaderPublishResult
}

/** One process/session owns publication sequence. This opens no connection and contains no HTML. */
class NativeHeaderPublisher(
    private val packageName: String,
    private val producerSession: String,
    private val clock: HeaderClock,
    private val transport: NativeHeaderTransport,
    private val leaseMs: Long = 1_500,
) : AutoCloseable {
    private var sequence = -1L
    private var closed = false

    init {
        require(HeaderApps.allowed(packageName))
        require(HeaderValidator.id(producerSession))
        require(leaseMs in 1..HeaderValidator.MAX_LEASE_MS)
    }

    @Synchronized
    fun nextPublication(foregroundEpoch: Long, pageGeneration: Long, sourceSessionId: String?): HeaderPublication {
        check(!closed) { "Header publisher is closed" }
        require(foregroundEpoch >= 0 && pageGeneration >= 0)
        require(sourceSessionId == null || HeaderValidator.id(sourceSessionId))
        val now = clock.elapsedRealtimeMs()
        require(now >= 0)
        val next = Math.addExact(sequence, 1)
        val expires = Math.addExact(now, leaseMs)
        sequence = next
        return HeaderPublication(packageName, producerSession, foregroundEpoch, pageGeneration, sourceSessionId, next, now, expires)
    }

    @Synchronized
    fun publish(snapshot: HeaderSnapshotV1): HeaderPublishResult {
        if (closed) return HeaderPublishResult(false, HeaderClearReason.PRODUCER_DEAD)
        if (snapshot.publication.packageName != packageName || snapshot.publication.producerSession != producerSession)
            return HeaderPublishResult(false, HeaderClearReason.CALLER_MISMATCH)
        val invalid = HeaderValidator.validate(snapshot, clock.elapsedRealtimeMs())
        return if (invalid == HeaderClearReason.NONE) transport.publish(snapshot) else HeaderPublishResult(false, invalid)
    }

    override fun close() = synchronized(this) { closed = true }
}
