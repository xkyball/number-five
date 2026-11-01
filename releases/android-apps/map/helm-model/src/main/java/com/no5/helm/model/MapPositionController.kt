package com.no5.helm.model

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable

@Serializable enum class PositionStatus { LIVE, STALE, LOST, INVALID, UNAVAILABLE }
@Serializable data class GnssObservation(val value: String?, val sourceRef: String, val observedAtEpochMs: Long?, val status: PositionStatus)
@Serializable data class PositionCandidate(val sourceId: String, val sourceRef: String, val label: String,
    val status: PositionStatus, val observedAtEpochMs: Long?, val position: GeoPoint?, val altitudeMetres: Double? = null,
    val gnss: Map<String, GnssObservation> = emptyMap())
@Serializable data class CombinationContributor(val sourceId: String, val observedAtEpochMs: Long)
@Serializable data class PositionCombination(val version: Int = 1, val method: String = "EQUAL_WEIGHT_SPHERICAL_V1",
    val mode: String = "NONE", val status: PositionStatus = PositionStatus.UNAVAILABLE,
    val reason: String = "NO_USABLE_SOURCE", val observedAtEpochMs: Long? = null,
    val position: GeoPoint? = null, val contributors: List<CombinationContributor> = emptyList())
@Serializable data class PositionSources(val version: Int = 1, val primarySourceId: String? = null,
    val total: Int = 0, val omitted: Int = 0, val sources: List<PositionCandidate> = emptyList(),
    val liveForMs: Long = 5000, val lostAfterMs: Long = 30000,
    val combinationAvailable: Boolean = false, val combinationReason: String = "FUSION_NOT_IMPLEMENTED",
    val valid: Boolean = true, val sourceKind: String = "REAL", val simulated: Boolean = false,
    val combination: PositionCombination? = null)
enum class PositionMode { OFF, CENTERED, FOLLOW }
@Serializable enum class PositionChoice { AUTOMATIC, MANUAL, COMBINED }
@Serializable data class PositionIntent(val version: Int = 1, val choice: PositionChoice = PositionChoice.AUTOMATIC,
    val sourceId: String? = null, val follow: Boolean = false)

data class MapPositionState(val intent: PositionIntent = PositionIntent(), val sources: PositionSources? = null,
    val selectedSourceId: String? = null, val selectedLabel: String? = null,
    val status: PositionStatus = PositionStatus.UNAVAILABLE, val ownship: Ownship? = null,
    val observedAtEpochMs: Long? = null, val generation: Long = 0, val legacy: Boolean = false,
    val awaitingFreshFix: Boolean = true, val followActive: Boolean = false,
    val mode: PositionMode = if (intent.follow) PositionMode.FOLLOW else PositionMode.OFF, val touchActive: Boolean = false,
    val outsideChartBounds: Boolean = false, val chartBoundsKnown: Boolean = true)

/** Delegates only to the existing camera owner; no camera math, network, or second acknowledgement path. */
interface PositionCamera {
    fun navigationBoundsKnown(): Boolean = true
    fun covers(latitude: Double, longitude: Double): Boolean = true
    fun center(latitude: Double, longitude: Double, sequence: Long, physicalUptimeMs: Long): Long
    /** Default rejects instead of silently discarding a captured engine fence. */
    fun center(latitude: Double, longitude: Double, sequence: Long, physicalUptimeMs: Long,
        expectedChartSource: Long, expectedSurface: Long): Long = 0
    fun cancelPending()
}

/** Single retained owner of source choice, freshness, ownship and follow intent. Results never enable follow. */
class MapPositionController(private val camera: PositionCamera,
    initial: PositionIntent = PositionIntent(), private val save: (PositionIntent) -> Unit = {}) {
    private var intent = initial.takeIf { it.version == 1 } ?: PositionIntent()
    private val mutable = MutableStateFlow(MapPositionState(intent = intent))
    val state: StateFlow<MapPositionState> = mutable.asStateFlow()
    private var backend = HelmState()
    private var connected = false
    private var serverId: String? = null
    private var transportFence = Long.MIN_VALUE
    private var generation = 0L
    private var selected: String? = null
    private var combinationIdentity: List<String> = emptyList()
    private var followObserved: Long? = null
    private var followPosition: GeoPoint? = null
    private var previouslyUsable = false
    private var centered = false // Runtime-only one-shot selection; never replayed from preferences.
    private var touchActive = false

    @Synchronized fun update(value: HelmState, nowEpochMs: Long) {
        val live = value.connection == ConnectionState.LIVE && value.snapshot?.gatewayV1 == true
        val nextServer = value.snapshot?.serverId
        if (live && (!connected || nextServer != serverId)) {
            // A repeated HTTP publication after reconnect cannot revive a pre-disconnect fix.
            transportFence = nowEpochMs
            centered = false
            retireCamera(); generation++
        } else if (!live && connected) { centered = false; retireCamera(); generation++ }
        connected = live
        if (live) serverId = nextServer
        backend = value
        project(nowEpochMs)
    }
    @Synchronized fun tick(nowEpochMs: Long) = project(nowEpochMs)
    @Synchronized fun select(choice: PositionChoice, sourceId: String? = null, nowEpochMs: Long) {
        val normalized = if (choice == PositionChoice.MANUAL) sourceId?.takeIf { it.isNotBlank() && it.length <= 200 } else null
        if (choice == PositionChoice.MANUAL && normalized == null) return
        if (intent.choice == choice && intent.sourceId == normalized) return
        intent = intent.copy(choice = choice, sourceId = normalized)
        centered = false; generation++; selected = null; retireCamera(); save(intent); project(nowEpochMs)
    }
    /** One shared touch/hardware cycle. -1 is a completed OFF intent, not a camera ID. */
    @Synchronized fun cyclePosition(nowEpochMs: Long, sequence: Long, physicalUptimeMs: Long, expectedGeneration: Long = -1,
        expectedChartSource: Long = -1, expectedSurface: Long = -1): Long {
        project(nowEpochMs)
        if (expectedGeneration >= 0 && expectedGeneration != generation) return 0
        if (intent.follow) {
            centered = false; intent = intent.copy(follow = false)
            retireCamera(); save(intent); project(nowEpochMs)
            return -1
        }
        val id = center(nowEpochMs, sequence, physicalUptimeMs, expectedChartSource, expectedSurface)
        if (id <= 0) return 0
        if (centered) {
            centered = false; intent = intent.copy(follow = true); save(intent)
            followObserved = mutable.value.observedAtEpochMs; followPosition = mutable.value.ownship?.position
        } else centered = true
        project(nowEpochMs)
        return id
    }
    /** All touch pauses camera following; only explicit pan intent turns it off. */
    @Synchronized fun touchActivity(active: Boolean, nowEpochMs: Long) {
        if (touchActive == active) return
        touchActive = active
        if (active) retireCamera() else { followObserved = null; followPosition = null }
        project(nowEpochMs)
    }
    @Synchronized fun toggleFollow(nowEpochMs: Long): Boolean {
        project(nowEpochMs)
        if (!intent.follow && (mutable.value.ownship == null || !mutable.value.chartBoundsKnown || mutable.value.outsideChartBounds)) return false
        centered = false; intent = intent.copy(follow = !intent.follow)
        retireCamera(); save(intent); project(nowEpochMs); return true
    }
    /** Explicit pan intent turns positioning OFF. Zoom/rotation must not call this entry. */
    @Synchronized fun gestureStarted(nowEpochMs: Long) {
        centered = false; retireCamera(); generation++
        if (intent.follow) { intent = intent.copy(follow = false); save(intent) }
        project(nowEpochMs)
    }
    @Synchronized fun center(nowEpochMs: Long, sequence: Long, physicalUptimeMs: Long,
        expectedChartSource: Long = -1, expectedSurface: Long = -1): Long {
        val s = mutable.value
        val stamp = s.observedAtEpochMs ?: return 0
        if (s.ownship == null || !connected || stamp > nowEpochMs || nowEpochMs - stamp > (s.sources?.liveForMs ?: 5000)) return 0
        if (!camera.navigationBoundsKnown() || !camera.covers(s.ownship.position.latitude, s.ownship.position.longitude)) return 0
        if (expectedChartSource >= 0 || expectedSurface >= 0) {
            if (expectedChartSource < 0 || expectedSurface < 0) return 0
            return camera.center(s.ownship.position.latitude, s.ownship.position.longitude, sequence, physicalUptimeMs, expectedChartSource, expectedSurface)
        }
        return camera.center(s.ownship.position.latitude, s.ownship.position.longitude, sequence, physicalUptimeMs)
    }
    @Synchronized fun follow(nowEpochMs: Long, physicalUptimeMs: Long): Long {
        val s = mutable.value
        if (!s.followActive || (followObserved == s.observedAtEpochMs && followPosition == s.ownship?.position)) return 0
        val id = center(nowEpochMs, 0, physicalUptimeMs)
        if (id > 0) { followObserved = s.observedAtEpochMs; followPosition = s.ownship?.position }
        return id
    }
    private fun retireCamera() { camera.cancelPending(); followObserved = null; followPosition = null }
    private fun project(now: Long) {
        val snapshot = backend.snapshot
        val block = snapshot?.positionSources
        val legacy = block == null && snapshot?.gatewayV1 == true
        val candidates = block?.sources.orEmpty()
        fun valid(c: PositionCandidate): Boolean = connected && c.observedAtEpochMs != null && c.observedAtEpochMs >= transportFence && effectiveStatus(c, now, block) == PositionStatus.LIVE
        val combined = block?.combination?.takeIf { block.valid && block.combinationAvailable }
        val identity = if (intent.choice == PositionChoice.COMBINED && combined != null)
            listOf(combined.mode, combined.status.name) + combined.contributors.map { it.sourceId }.sorted() else emptyList()
        if (identity != combinationIdentity) { combinationIdentity = identity; centered = false; generation++; retireCamera() }
        val combinedCandidate = combined?.let { PositionCandidate("@combined", "@combined", "GPS kombiniert",
            it.status, it.observedAtEpochMs, it.position) }
        val candidate = when (intent.choice) {
            PositionChoice.COMBINED -> combinedCandidate
            PositionChoice.MANUAL -> candidates.firstOrNull { it.sourceId == intent.sourceId }
            PositionChoice.AUTOMATIC -> candidates.firstOrNull { it.sourceId == block?.primarySourceId && valid(it) }
                ?: candidates.firstOrNull { it.sourceId == selected && valid(it) }
                ?: candidates.firstOrNull(::valid)
                ?: candidates.firstOrNull { it.sourceId == block?.primarySourceId }
        }
        val id = candidate?.sourceId
        if (selected != id) { selected = id; centered = false; generation++; retireCamera() }
        val observed = if (legacy && intent.choice == PositionChoice.AUTOMATIC) snapshot?.fixObservedAtEpochMs else candidate?.observedAtEpochMs
        val rawShip = if (legacy && intent.choice == PositionChoice.AUTOMATIC) snapshot?.state?.ownship else
            candidate?.position?.let { Ownship(it, null, null, null, null) }
        val status = when {
            block?.valid == false -> PositionStatus.UNAVAILABLE
            !connected -> PositionStatus.LOST
            legacy && intent.choice == PositionChoice.AUTOMATIC -> ageStatus(observed, now, 5000, 30000, rawShip?.position)
            candidate != null -> effectiveStatus(candidate, now, block)
            else -> PositionStatus.UNAVAILABLE
        }
        val fenced = observed == null || observed < transportFence
        val ship = rawShip?.takeIf { status == PositionStatus.LIVE && !fenced }
        val known = camera.navigationBoundsKnown()
        val outside = known && ship != null && !camera.covers(ship.position.latitude, ship.position.longitude)
        if (outside != mutable.value.outsideChartBounds || known != mutable.value.chartBoundsKnown) retireCamera()
        if (previouslyUsable && ship == null) { centered = false; retireCamera() }
        previouslyUsable = ship != null
        val displayedBlock = block?.let { currentBlock -> currentBlock.copy(sources = candidates.map { c -> c.copy(
            status = if (connected) effectiveStatus(c, now, block) else PositionStatus.LOST,
            gnss = c.gnss.mapValues { (_, g) ->
                val age = g.observedAtEpochMs?.let { now - it }
                g.copy(status = when {
                    g.status == PositionStatus.INVALID || g.status == PositionStatus.UNAVAILABLE || g.status == PositionStatus.LOST -> g.status
                    !connected -> PositionStatus.LOST
                    age == null -> PositionStatus.UNAVAILABLE
                    age < 0 -> PositionStatus.INVALID
                    age > currentBlock.lostAfterMs -> PositionStatus.LOST
                    age > currentBlock.liveForMs -> PositionStatus.STALE
                    else -> g.status
                })
            }) }) }
        mutable.value = MapPositionState(intent, displayedBlock, id, candidate?.label, status, ship, observed,
            generation, legacy, fenced, intent.follow && ship != null && !touchActive && known && !outside,
            if (intent.follow) PositionMode.FOLLOW else if (centered) PositionMode.CENTERED else PositionMode.OFF, touchActive, outside, known)
    }
    private fun effectiveStatus(c: PositionCandidate, now: Long, block: PositionSources?): PositionStatus {
        if (c.status == PositionStatus.INVALID || c.status == PositionStatus.UNAVAILABLE || c.status == PositionStatus.LOST) return c.status
        val age = ageStatus(c.observedAtEpochMs, now, block?.liveForMs ?: 5000, block?.lostAfterMs ?: 30000, c.position)
        return if (c.status == PositionStatus.STALE && age == PositionStatus.LIVE) PositionStatus.STALE else age
    }
    private fun ageStatus(stamp: Long?, now: Long, liveFor: Long, lostAfter: Long, point: GeoPoint?): PositionStatus {
        if (point == null || !point.latitude.isFinite() || !point.longitude.isFinite() || point.latitude !in -85.0..85.0 || point.longitude !in -180.0..180.0) return PositionStatus.INVALID
        if (stamp == null) return PositionStatus.UNAVAILABLE
        if (stamp < 0 || stamp > now) return PositionStatus.INVALID
        return when { now - stamp <= liveFor -> PositionStatus.LIVE; now - stamp <= lostAfter -> PositionStatus.STALE; else -> PositionStatus.LOST }
    }
}
