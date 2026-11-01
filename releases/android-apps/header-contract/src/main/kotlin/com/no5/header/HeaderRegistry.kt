package com.no5.header

/** Only the privileged transport adapter may create this from independently checked caller identity. */
data class AuthenticatedHeaderProducer(val packageName: String, val producerSession: String)

/** Trusted focus/page/session hooks activate a scope before any snapshot can be displayed. */
data class HeaderScope(
    val packageName: String,
    val producerSession: String,
    val foregroundEpoch: Long,
    val pageId: HeaderPage,
    val pageGeneration: Long,
    val sourceSessionId: String?,
)

enum class HeaderClearReason {
    NONE, NO_FOREGROUND_SCOPE, NO_PUBLICATION, PRODUCER_DEAD, NOT_ALLOWED,
    CALLER_MISMATCH, WRONG_FOREGROUND, WRONG_PAGE, WRONG_SOURCE_SESSION,
    OUT_OF_ORDER, EXPIRED, INVALID_ENVELOPE, INVALID_PROFILE, INVALID_DATA,
}

data class HeaderPublishResult(val accepted: Boolean, val reason: HeaderClearReason)

/** Captured under the registry monitor at the actual transition, before any async delivery.
 * snapshot is the accepted ORIGINAL, never current().at(...) or a new measurement. */
data class HeaderRegistryOrigin(
    val revision: Long,
    val scope: HeaderScope?,
    val snapshot: HeaderSnapshotV1?,
    val reason: HeaderClearReason,
)

/** Brand and system navigation survive when snapshot/context/extras disappear. */
data class HeaderConsumerState(val snapshot: HeaderSnapshotV1?, val reason: HeaderClearReason) {
    val brand: String get() = "NO 5"
    val subtitle: String get() = "STELLENBOSCH"
    val profile: HeaderProfile get() = snapshot?.profile ?: HeaderProfile.BASE_SYSTEM
    val extras: HeaderExtras get() = snapshot?.extras ?: HeaderExtras()
    fun navigation(control: SystemNavigationAction): Int = control.keyCode
}

object HeaderValidator {
    const val MAX_LEASE_MS = 5_000L

    fun validate(snapshot: HeaderSnapshotV1, nowElapsedMs: Long): HeaderClearReason {
        val p = snapshot.publication
        if (!HeaderApps.allowed(p.packageName)) return HeaderClearReason.NOT_ALLOWED
        if (snapshot.schemaVersion != 1 || !id(p.producerSession) || p.foregroundEpoch < 0 ||
            p.pageGeneration < 0 || p.sequence < 0 || nowElapsedMs < 0 || p.publishedAtElapsedMs < 0 ||
            p.expiresAtElapsedMs <= p.publishedAtElapsedMs || p.publishedAtElapsedMs > nowElapsedMs ||
            p.expiresAtElapsedMs - p.publishedAtElapsedMs > MAX_LEASE_MS ||
            (p.sourceSessionId != null && !id(p.sourceSessionId))) return HeaderClearReason.INVALID_ENVELOPE
        if (nowElapsedMs >= p.expiresAtElapsedMs) return HeaderClearReason.EXPIRED
        if (HeaderApps.profile(p.packageName, snapshot.pageId) != snapshot.profile) return HeaderClearReason.INVALID_PROFILE
        if (snapshot.profile != HeaderProfile.MARINE_SAFETY && !snapshot.extras.isEmpty()) return HeaderClearReason.INVALID_PROFILE
        if (!text(snapshot.context.title, 128) || !text(snapshot.context.subtitle, 256) ||
            !text(snapshot.context.pageStatus, 512) || !text(snapshot.source.label, 128) ||
            !text(snapshot.source.detail, 512) || !text(snapshot.source.sourceId, 256) ||
            !text(snapshot.source.backendSessionId, 256) ||
            (snapshot.sourceObservedAtEpochMs != null && snapshot.sourceObservedAtEpochMs < 0) ||
            !policy(snapshot.source.freshness)) return HeaderClearReason.INVALID_DATA
        val e = snapshot.extras
        if (e.portRpm?.unit?.let { it != HeaderUnit.RPM } == true ||
            e.starboardRpm?.unit?.let { it != HeaderUnit.RPM } == true || e.sog?.unit == HeaderUnit.RPM)
            return HeaderClearReason.INVALID_DATA
        if (!measurement(e.portRpm) || !measurement(e.starboardRpm) || !measurement(e.sog))
            return HeaderClearReason.INVALID_DATA
        return HeaderClearReason.NONE
    }

    private fun measurement(m: HeaderMeasurement?) = m == null ||
        (text(m.sourceId, 256) && (m.sourceObservedAtEpochMs == null || m.sourceObservedAtEpochMs >= 0) && policy(m.freshness))
    private fun policy(p: HeaderFreshness) = p.staleAfterMs > 0 && p.lostAfterMs >= p.staleAfterMs
    private fun text(s: String?, max: Int) = s == null || s.length <= max
    internal fun id(s: String) = s.isNotBlank() && s.length <= 256
}

/**
 * Native/JVM state machine, no Android I/O. All transitions share one monitor.
 * Liveness/focus/page identity are trusted HOST inputs, never facts inferred from a payload.
 */
class HeaderRegistry(private val onOrigin: (HeaderRegistryOrigin) -> Unit = {}) {
    private val alive = mutableMapOf<String, String>()
    private val retired = mutableSetOf<AuthenticatedHeaderProducer>()
    private var scope: HeaderScope? = null
    private var lastScope: HeaderScope? = null
    private var latest: HeaderSnapshotV1? = null
    private var lastAcceptedSequence = -1L
    private var reason = HeaderClearReason.NO_FOREGROUND_SCOPE
    private var originRevision = 0L
    private var exportedScope: HeaderScope? = null
    private var exportedSnapshot: HeaderSnapshotV1? = null
    private var exportedReason = HeaderClearReason.NO_FOREGROUND_SCOPE

    @Synchronized
    fun producerStarted(producer: AuthenticatedHeaderProducer): Boolean {
        if (!HeaderApps.allowed(producer.packageName) || !HeaderValidator.id(producer.producerSession) || producer in retired) return false
        val previous = alive.put(producer.packageName, producer.producerSession)
        if (previous != null && previous != producer.producerSession) {
            retired.add(AuthenticatedHeaderProducer(producer.packageName, previous))
            if (scope?.packageName == producer.packageName) clear(HeaderClearReason.PRODUCER_DEAD)
        }
        return true
    }

    @Synchronized
    fun producerDied(producer: AuthenticatedHeaderProducer) {
        if (!HeaderApps.allowed(producer.packageName) || !HeaderValidator.id(producer.producerSession)) return
        retired.add(producer)
        if (alive[producer.packageName] == producer.producerSession) alive.remove(producer.packageName)
        if (scope?.packageName == producer.packageName && scope?.producerSession == producer.producerSession)
            clear(HeaderClearReason.PRODUCER_DEAD)
    }

    /** Call also on internal page/source switches; a fresh pageGeneration invalidates old callbacks. */
    @Synchronized
    fun onForegroundChanged(next: HeaderScope?) {
        if (next == scope) return
        if (next != null) {
            if (!HeaderApps.allowed(next.packageName) || !HeaderValidator.id(next.producerSession) ||
                next.foregroundEpoch < 0 || next.pageGeneration < 0 ||
                HeaderApps.profile(next.packageName, next.pageId) == null ||
                (next.sourceSessionId != null && !HeaderValidator.id(next.sourceSessionId))) {
                scope = null
                clear(HeaderClearReason.INVALID_ENVELOPE)
                return
            }
            val previous = lastScope
            if (previous != null && next.foregroundEpoch < previous.foregroundEpoch) {
                scope = null
                clear(HeaderClearReason.WRONG_FOREGROUND)
                return
            }
            if (previous != null && next.foregroundEpoch == previous.foregroundEpoch &&
                next.packageName == previous.packageName && next.producerSession == previous.producerSession &&
                (next.pageGeneration < previous.pageGeneration ||
                    (next.pageGeneration == previous.pageGeneration &&
                        (next.pageId != previous.pageId || next.sourceSessionId != previous.sourceSessionId || scope == null)))) {
                scope = null
                clear(HeaderClearReason.WRONG_PAGE)
                return
            }
            lastScope = next
        }
        scope = next
        lastAcceptedSequence = -1L
        clear(if (next == null) HeaderClearReason.NO_FOREGROUND_SCOPE else HeaderClearReason.NO_PUBLICATION)
    }

    @Synchronized
    fun publish(caller: AuthenticatedHeaderProducer, snapshot: HeaderSnapshotV1, nowElapsedMs: Long): HeaderPublishResult {
        val p = snapshot.publication
        val active = scope
        val rejected = when {
            !HeaderApps.allowed(caller.packageName) || !HeaderApps.allowed(p.packageName) -> HeaderClearReason.NOT_ALLOWED
            p.packageName != caller.packageName || p.producerSession != caller.producerSession -> HeaderClearReason.CALLER_MISMATCH
            alive[caller.packageName] != caller.producerSession -> HeaderClearReason.PRODUCER_DEAD
            active == null -> HeaderClearReason.NO_FOREGROUND_SCOPE
            active.packageName != p.packageName || active.producerSession != p.producerSession ||
                active.foregroundEpoch != p.foregroundEpoch -> HeaderClearReason.WRONG_FOREGROUND
            active.pageId != snapshot.pageId || active.pageGeneration != p.pageGeneration -> HeaderClearReason.WRONG_PAGE
            active.sourceSessionId != p.sourceSessionId -> HeaderClearReason.WRONG_SOURCE_SESSION
            p.sequence <= lastAcceptedSequence -> HeaderClearReason.OUT_OF_ORDER
            else -> HeaderValidator.validate(snapshot, nowElapsedMs)
        }
        if (rejected != HeaderClearReason.NONE) {
            // An invalid message from the current owner fails closed. Background callers cannot
            // erase the valid foreground owner's data; they never become the displayed context.
            if (active?.packageName == caller.packageName && active.producerSession == caller.producerSession) clear(rejected)
            return HeaderPublishResult(false, rejected)
        }
        latest = snapshot
        lastAcceptedSequence = p.sequence
        reason = HeaderClearReason.NONE
        emitOrigin()
        return HeaderPublishResult(true, reason)
    }

    @Synchronized
    fun current(nowElapsedMs: Long, nowEpochMs: Long): HeaderConsumerState {
        val active = scope
        val value = latest
        if (active == null || !HeaderApps.allowed(active.packageName)) {
            latest = null
            if (reason == HeaderClearReason.NONE) reason = HeaderClearReason.NO_FOREGROUND_SCOPE
            emitOrigin()
        }
        else if (alive[active.packageName] != active.producerSession) clear(HeaderClearReason.PRODUCER_DEAD)
        else if (value != null) {
            val invalid = HeaderValidator.validate(value, nowElapsedMs)
            if (invalid != HeaderClearReason.NONE) clear(invalid)
        }
        return HeaderConsumerState(latest?.at(nowEpochMs), reason)
    }

    private fun clear(why: HeaderClearReason) {
        latest = null
        reason = why
        emitOrigin()
        // Rejection/expiry/death does NOT reset sequence: no accepted old payload can reappear.
    }

    /** Optional read-only sink must remain bounded/nonblocking; its failure cannot alter ACLs. */
    private fun emitOrigin() {
        if (scope == exportedScope && latest === exportedSnapshot && reason == exportedReason) return
        if (originRevision == Long.MAX_VALUE) return // The exporter must never reuse a revision.
        exportedScope = scope; exportedSnapshot = latest; exportedReason = reason
        val origin = HeaderRegistryOrigin(++originRevision, scope, latest, reason)
        try { onOrigin(origin) } catch (_: RuntimeException) { /* Export failure is not producer admission. */ }
    }
}
