package com.no5.helm.model

import kotlinx.serialization.Serializable

@Serializable data class GeoPoint(val latitude: Double, val longitude: Double)
@Serializable data class Ownship(val position: GeoPoint, val headingRadians: Double?, val cogRadians: Double?, val sogMetresPerSecond: Double?, val depthMetres: Double?)
@Serializable data class AisTarget(val mmsi: Long, val name: String, val position: GeoPoint, val headingRadians: Double, val cogRadians: Double, val sogMetresPerSecond: Double)
@Serializable data class Waypoint(val id: String, val name: String, val position: GeoPoint)
@Serializable data class Route(val id: String, val name: String, val waypointIds: List<String>)
@Serializable data class HelmProjection(val ownship: Ownship?, val ais: List<AisTarget>, val routes: List<Route>, val waypoints: List<Waypoint>, val activeRouteId: String? = null)
/** Aggregate HELM provenance; it never overrides the independently selected GPS catalogue. */
@Serializable data class GatewayTelemetryProvenance(val source: String, val simulated: Boolean)
@Serializable data class Snapshot(val schemaVersion: Int = 1, val serverId: String, val revision: Long, val generatedAt: String, val state: HelmProjection, val gatewayV1: Boolean = false, val sourceDetail: String? = null, val fixObservedAtEpochMs: Long? = null, val positionSources: PositionSources? = null, val gatewayTelemetryProvenance: GatewayTelemetryProvenance? = null)
@Serializable data class Delta(val type: String = "delta", val schemaVersion: Int = 1, val serverId: String, val baseRevision: Long, val revision: Long, val generatedAt: String, val ownship: Ownship? = null, val ais: List<AisTarget>? = null, val routes: List<Route>? = null, val waypoints: List<Waypoint>? = null)
enum class ConnectionState { DISCONNECTED, CONNECTING, SYNCING, LIVE, DEGRADED }
data class HelmState(val connection: ConnectionState = ConnectionState.DISCONNECTED, val snapshot: Snapshot? = null, val issue: String? = null)
sealed interface Reduction {
    data class Applied(val state: HelmState) : Reduction
    data class Resync(val state: HelmState) : Reduction
    data class Ignored(val state: HelmState) : Reduction
}

/** Immutable reducer: gaps never partially change the last known projection. */
object HelmReducer {
    fun snapshot(current: HelmState, incoming: Snapshot): HelmState {
        require(incoming.schemaVersion == 1 && incoming.revision >= 0)
        incoming.state.ownship?.let {
            require(it.position.latitude in -85.0..85.0 && it.position.longitude in -180.0..180.0)
        }
        val previous = current.snapshot
        if (previous != null && previous.serverId == incoming.serverId && incoming.revision < previous.revision) return current
        return HelmState(ConnectionState.LIVE, incoming)
    }
    fun delta(current: HelmState, incoming: Delta): Reduction {
        val previous = current.snapshot
        if (incoming.schemaVersion != 1 || previous == null || incoming.serverId != previous.serverId ||
            incoming.baseRevision != previous.revision || incoming.revision != incoming.baseRevision + 1) {
            // Duplicate old deltas are safe to ignore, while a missing base requires a snapshot.
            if (previous != null && incoming.schemaVersion == 1 && incoming.serverId == previous.serverId && incoming.revision <= previous.revision)
                return Reduction.Ignored(current)
            return Reduction.Resync(current.copy(connection = ConnectionState.SYNCING, issue = "Revision gap"))
        }
        if (current.connection != ConnectionState.LIVE) return Reduction.Resync(current)
        val state = previous.state.copy(ownship = incoming.ownship ?: previous.state.ownship,
            ais = incoming.ais ?: previous.state.ais, routes = incoming.routes ?: previous.state.routes,
            waypoints = incoming.waypoints ?: previous.state.waypoints)
        return Reduction.Applied(HelmState(ConnectionState.LIVE, previous.copy(revision = incoming.revision, generatedAt = incoming.generatedAt, state = state)))
    }
    fun lost(current: HelmState, issue: String) = current.copy(connection = ConnectionState.DEGRADED, issue = issue)
}
