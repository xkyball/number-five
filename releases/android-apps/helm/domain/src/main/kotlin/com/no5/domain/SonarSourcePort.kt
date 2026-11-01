package com.no5.domain

import java.util.concurrent.atomic.AtomicLong

/**
 * Acquisition only. Callbacks are serialized by an adapter on its consumer's dispatcher.
 * Publish status for a generation before publishing its frames; never reuse a session ID.
 */
interface SonarSourcePort {
    val identity: SourceIdentity
    val status: SourceFeed<SonarSourceStatus>
    val capabilities: SourceFeed<SonarSourceCapabilities>
    val frames: SourceFeed<SonarSourceFrame?>
    /** Transient ordered deliveries. A batch contains every accepted queued ping;
     * observers must consume it before returning and must not retain RAW history.
     * Null means this adapter only implements the legacy single-frame feed. */
    val frameBatches: SourceFeed<List<SonarSourceFrame>?>? get() = null
    fun receiveDiagnostics(): SonarReceiveDiagnostics? = null
    val outcomes: SourceFeed<SonarAcquisitionOutcome?> get() = EmptySonarOutcomes
    val filterState: SourceFeed<SonarFilterState> get() = MutableSourceFeed(SonarFilterState(identity))
    val filterOutcomes: SourceFeed<SonarFilterOutcome?> get() = MutableSourceFeed(null)
    fun filter(request: SonarFilterRequest): SonarFilterOutcome = SonarFilterOutcome(identity.sessionId, request.requestId,
        null, request.mode, SonarFilterCommandStatus.REJECTED, "UNSUPPORTED")
    fun filterResult(commandId: Long): SonarFilterOutcome? = null
    val sourceState: SourceFeed<SonarSourceSelectionState> get() = MutableSourceFeed(SonarSourceSelectionState(identity))
    val sourceOutcomes: SourceFeed<SonarSourceSelectionOutcome?> get() = MutableSourceFeed(null)
    val diagnostics: SourceFeed<SonarDiagnostics?> get() = MutableSourceFeed(null)
    fun requestOriginClockProof(request: SonarOriginClockRequest, callback: (SonarOriginClockProof?) -> Unit): Boolean = false
    /** Recovery needs fresh input from the same source, independently of a stalled display.
     * Drain already received measurements in order, then choose an OWN ping no older
     * than minimum. The gateway certifies a same-context OWN publication at its
     * next regular nonce-bearing read. The monotonic bracket starts BEFORE that drain. This
     * never certifies the displayed image or replaces the exact-ping proof API. */
    fun requestRecoveryOriginClockProof(minimum: SonarOriginClockRequest, callback: (SonarOriginClockProof?) -> Unit): Boolean = false
    fun originClockDiagnostics(): SonarOriginClockDiagnostics? = null
    /** Read-side transport health only; no acquisition/source command. Unknown is -1. */
    fun readReceiveSilenceNanos(): Long = -1L
    fun reconnectReadTransport(): Boolean = false
    fun setDiagnosticsVisible(visible: Boolean) = Unit
    /** Display-only subscription demand; never an acquisition or TX command. */
    fun setProfileDemand(enabled: Boolean) = Unit
    fun selectSource(request: SonarSourceSelectionRequest): SonarSourceSelectionOutcome =
        SonarSourceSelectionOutcome(identity.sessionId, request.requestId, null, request.source,
            SonarFilterCommandStatus.REJECTED, "UNSUPPORTED")
    fun sourceResult(commandId: Long): SonarSourceSelectionOutcome? = null
    fun start(request: SonarAcquisitionRequest): SonarAcquisitionOutcome
    fun stop(request: SonarAcquisitionRequest): SonarAcquisitionOutcome
    fun tick()
    fun close()
}

data class SonarReceiveDiagnostics(
    val pendingEvents: Int,
    val capacity: Int,
    val backpressured: Boolean,
    val backpressureEvents: Long,
    val deliveredBatches: Long,
    val largestBatch: Int,
    val oldestPendingAgeNanos: Long?,
)

data class SonarSourceStatus(
    val identity: SourceIdentity,
    val generation: Long = 0,
    val connected: Boolean = false,
    val running: Boolean = false,
    val error: String? = null,
    val source: String? = null,
    val simulated: Boolean = false,
    val serverSessionId: String? = null,
    val serverGeneration: Long? = null,
    val publishedAtEpochMs: Long? = null,
    val queueDrops: Long = 0,
    val rejectedFrames: Long = 0,
    val stale: Boolean = false,
    val displaySelection: SonarDisplaySelection? = null,
    val amplitude: SonarAmplitude? = null,
    val observationReason: SonarObservationReason? = null,
) {
    init { require(generation >= 0); require(!running || connected); require(queueDrops >= 0 && rejectedFrames >= 0); require(serverGeneration == null || serverGeneration >= 0) }
}

data class SonarSourceCapabilities(
    val identity: SourceIdentity,
    val canStart: Boolean = false,
    val canStop: Boolean = false,
    val bottomDetection: Boolean = false,
    val filterControl: Boolean = false,
    val confirmedTracks: Boolean = false,
    val sourceSelection: Boolean = false,
    val diagnostics: Boolean = false,
)

enum class SonarFilterMode { OFF, AUTO, ON }
enum class SonarFilterCommandStatus { ACCEPTED, RUNNING, APPLIED, SUPERSEDED, CANCELLED, REJECTED, UNKNOWN }
data class SonarFilterRequest(val sessionId: String, val requestId: Long, val mode: SonarFilterMode)
data class SonarFilterState(val identity: SourceIdentity, val generation: Long = 0, val available: Boolean = false,
    val mode: SonarFilterMode? = null, val effectiveMode: String? = null, val active: Boolean? = null,
    val reason: String? = null)
data class SonarFilterOutcome(val sessionId: String, val requestId: Long, val commandId: Long?,
    val mode: SonarFilterMode, val status: SonarFilterCommandStatus, val reason: String)

/** Metadata travels with the frame it describes; absent calibration never becomes physical depth. */
data class SonarSourceFrame(
    val identity: SourceIdentity,
    val generation: Long,
    val frame: SonarFrame,
    val calibration: Calibration = Calibration(),
    val bottom: SonarBottom = SonarBottom(),
    val serverSessionId: String? = null,
    val publishedAtEpochMs: Long = frame.timestampEpochMs,
    val simulated: Boolean = false,
    val geometry: SonarGeometry? = null,
    val presentation: SonarPresentation? = null,
    val hostPackageVersion: String? = null,
    val displaySelection: SonarDisplaySelection? = null,
    val amplitude: SonarAmplitude? = null,
    val pingSource: SonarPingSource? = null,
    val serverGeneration: Long? = null,
    val localReceivedAtNanos: Long = 0,
) { init { require(generation >= 0 && (serverGeneration == null || serverGeneration >= 0)) } }

data class SonarAcquisitionRequest(val sessionId: String, val requestId: Long) {
    init { require(sessionId.isNotBlank()); require(requestId >= 0) }
}

enum class SonarRejection { UNAVAILABLE, CLOSED, WRONG_SESSION, STALE_REQUEST, UNSUPPORTED, PENDING, TIMEOUT, PROTOCOL, REJECTED }
sealed interface SonarAcquisitionOutcome {
    val sessionId: String
    val requestId: Long
    data class Pending(override val sessionId: String, override val requestId: Long) : SonarAcquisitionOutcome
    data class Acknowledged(override val sessionId: String, override val requestId: Long) : SonarAcquisitionOutcome
    data class Rejected(
        override val sessionId: String,
        override val requestId: Long,
        val reason: SonarRejection,
        val message: String,
    ) : SonarAcquisitionOutcome
}

private object EmptySonarOutcomes : SourceFeed<SonarAcquisitionOutcome?> {
    override val value: SonarAcquisitionOutcome? = null
    override fun subscribe(listener: (SonarAcquisitionOutcome?) -> Unit): SourceSubscription {
        listener(null)
        return SourceSubscription { }
    }
}

private object SonarSourceSessions {
    private val counter = AtomicLong()
    fun next(mode: SourceMode) = SourceIdentity("sonar-${mode.name.lowercase()}-${counter.incrementAndGet()}", mode, "SONAR ${mode.name}")
}

/** Explicit fail-closed Pi placeholder. No decoder, network, fixture data or acquisition command. */
class UnavailablePiSonarSource(
    override val identity: SourceIdentity = SonarSourceSessions.next(SourceMode.PI),
) : SonarSourcePort {
    init { require(identity.mode == SourceMode.PI) }
    private val mutableStatus = MutableSourceFeed(SonarSourceStatus(identity))
    private val mutableCapabilities = MutableSourceFeed(SonarSourceCapabilities(identity))
    private val mutableFrames = MutableSourceFeed<SonarSourceFrame?>(null)
    override val status: SourceFeed<SonarSourceStatus> get() = mutableStatus
    override val capabilities: SourceFeed<SonarSourceCapabilities> get() = mutableCapabilities
    override val frames: SourceFeed<SonarSourceFrame?> get() = mutableFrames
    private var closed = false
    private fun reject(request: SonarAcquisitionRequest) = SonarAcquisitionOutcome.Rejected(
        identity.sessionId, request.requestId,
        if (closed) SonarRejection.CLOSED else if (request.sessionId != identity.sessionId) SonarRejection.WRONG_SESSION else SonarRejection.UNAVAILABLE,
        if (closed) "Sonar source is closed." else "Pi sonar adapter is unavailable; acquisition is locked.")
    override fun start(request: SonarAcquisitionRequest): SonarAcquisitionOutcome = reject(request)
    override fun stop(request: SonarAcquisitionRequest): SonarAcquisitionOutcome = reject(request)
    override fun tick() = Unit
    override fun close() {
        if (closed) return
        closed = true
        try { mutableStatus.close() } finally {
            try { mutableCapabilities.close() } finally { mutableFrames.close() }
        }
    }
}
