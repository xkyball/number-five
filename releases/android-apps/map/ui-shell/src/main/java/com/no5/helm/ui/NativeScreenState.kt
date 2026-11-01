package com.no5.helm.ui

import com.no5.helm.chart.*
import com.no5.helm.model.ConnectionState
import com.no5.helm.model.GeoPoint
import com.no5.helm.model.HelmState
import com.no5.helm.model.Snapshot
import com.no5.preparation.ui.*

internal const val ROUTE_CAPABILITY = "Speichern in Signal K über den direkten Lumina-Gateway-Zugang. Lokale Entwürfe sichern Offline-Bearbeitung; Routenführung ist noch nicht angeschlossen."
internal data class NativeScreenState(
    val shell: ShellState = ShellState(),
    val routes: RoutesState = RoutesState(saveSupported = false, startSupported = false, stopSupported = false, writeCapabilityDetail = ROUTE_CAPABILITY,
        activeStatusLabel = "Backendmarkierung · aktive Route"),
    val ais: AisState = AisState(),
    val settings: SettingsState = SettingsState(),
    val pendingDestination: Destination? = null,
) {
    fun navigate(destination: Destination): NativeScreenState = if (destination == shell.destination) copy(shell = shell.copy(panelExpanded = true)) else if (routes.dirty) {
        copy(routes = reduceRoutes(routes, RoutesEvent.CancelEdit), pendingDestination = destination)
    } else copy(shell = shell.copy(destination = destination, panelExpanded = true))
    fun route(event: RoutesEvent): NativeScreenState {
        val next = reduceRoutes(routes, event)
        return when (event) {
            RoutesEvent.Discard -> if (next.dirty) copy(routes = next) else copy(routes = next, pendingDestination = null,
                shell = shell.copy(destination = pendingDestination ?: shell.destination, panelExpanded = true))
            is RoutesEvent.Saved -> if (routes.saveRequest?.token == event.token && !next.dirty && !next.discardDialog)
                copy(routes = next, pendingDestination = null,
                    shell = shell.copy(destination = pendingDestination ?: shell.destination, panelExpanded = true))
                else copy(routes = next)
            RoutesEvent.KeepEditing -> copy(routes = next, pendingDestination = if (next.discardDialog) pendingDestination else null)
            else -> copy(routes = next)
        }
    }
}
internal fun GeoPoint.valid() = latitude.isFinite() && longitude.isFinite() && latitude in -85.0..85.0 && longitude in -180.0..180.0
internal fun GeoPoint.projection() = GeoProjection(latitude, longitude)
internal fun sourceStatus(helm: HelmState) = DataStatus(when (helm.connection) {
    ConnectionState.LIVE -> Availability.READY
    ConnectionState.CONNECTING, ConnectionState.SYNCING -> Availability.LOADING
    ConnectionState.DEGRADED -> if (helm.snapshot == null) Availability.OFFLINE else Availability.STALE
    ConnectionState.DISCONNECTED -> Availability.OFFLINE
}, helm.issue ?: when (helm.connection) {
    ConnectionState.LIVE -> helm.snapshot?.sourceDetail ?: "Helm-Transport live · Messalter, Fixqualität und Simulationsherkunft unbekannt"
    ConnectionState.SYNCING -> "Snapshot-Abgleich läuft"
    ConnectionState.CONNECTING -> "Verbindung wird aufgebaut"
    else -> "Helm-Gateway nicht verbunden"
})
internal fun normalizeAis(snapshot: Snapshot?, watched: Set<String>): FrozenList<AisTarget> = FrozenList.copyOf(
    snapshot?.state?.ais.orEmpty().filter { it.mmsi in 100000000L..999999999L }.distinctBy { it.mmsi }.map { target ->
        AisTarget(mmsi = target.mmsi.toString(), name = target.name.takeIf { it.isNotBlank() },
            position = target.position.takeIf { it.valid() }?.let { AisPosition(it.latitude, it.longitude) },
            speedKnots = target.sogMetresPerSecond.takeIf { it.isFinite() && it >= 0 }?.times(1.9438444924406),
            courseDegrees = target.cogRadians.takeIf { it.isFinite() && it >= 0 && it < Math.PI * 2 }?.let { Math.toDegrees(it) },
            watched = target.mmsi.toString() in watched)
    })
internal fun normalizeRoutes(snapshot: Snapshot?): FrozenList<RouteDocument> {
    val state = snapshot?.state ?: return frozenListOf()
    val waypoints = state.waypoints.associateBy { it.id }
    return FrozenList.copyOf(state.routes.distinctBy { it.id }.mapNotNull { route ->
        // An unresolved point invalidates the entire polyline; never bridge across missing data.
        val points = route.waypointIds.map { id -> waypoints[id]?.takeIf { it.position.valid() }?.let {
            RoutePoint(it.position.latitude, it.position.longitude, it.id, it.name)
        } }
        if (points.any { it == null }) null else RouteDocument("external-${snapshot.serverId}-${route.id}", route.name, FrozenList.copyOf(points.filterNotNull()), snapshot.revision, external = true, source = snapshot.serverId)
    })
}
internal fun overlaySnapshot(snapshot: Snapshot?, draft: RouteDocument?, visibility: Map<String, Boolean>, editing: Boolean = draft != null, ownship: com.no5.helm.model.Ownship? = snapshot?.state?.ownship): ChartOverlays {
    val state = snapshot?.state
    val routes = normalizeRoutes(snapshot)
    val selectedRoute = draft ?: routes.find { it.id == "external-${snapshot?.serverId}-${state?.activeRouteId}" }
    val routePoints = selectedRoute?.points?.takeIf { points -> points.all { it.valid && it.latitude in -85.0..85.0 } }
    return ChartOverlays(
        ownship = if (visibility["ownship"] != false) ownship?.position?.takeIf { it.valid() }?.projection() else null,
        ais = if (visibility["ais"] == true) state?.ais.orEmpty().filter { it.mmsi in 100000000L..999999999L && it.position.valid() }.distinctBy { it.mmsi }.map { it.position.projection() } else emptyList(),
        waypoints = if (visibility["waypoints"] == true) state?.waypoints.orEmpty().filter { it.position.valid() }.map { it.position.projection() } else emptyList(),
        route = if (visibility["route"] == true && routePoints != null) RouteProjection(routePoints.map { GeoProjection(it.latitude, it.longitude) }, selectedRoute.id, selectedRoute.revision,
            if (editing) ChartRouteRole.DRAFT else if (draft == null) ChartRouteRole.ACTIVE else ChartRouteRole.SAVED,
            routePoints.map { it.id }, routePoints.map { it.name }) else null,
        aisObjects = if (visibility["ais"] == true) state?.ais.orEmpty().filter { it.mmsi in 100000000L..999999999L && it.position.valid() }.distinctBy { it.mmsi }
            .map { ChartOverlayObject("ais-${it.mmsi}", it.position.projection(), it.name.ifBlank { "MMSI ${it.mmsi}" }, it.headingRadians.takeIf { v -> v.isFinite() && v >= 0 && v < Math.PI * 2 }) } else emptyList(),
        waypointObjects = if (visibility["waypoints"] == true) state?.waypoints.orEmpty().filter { it.position.valid() }
            .map { ChartOverlayObject(it.id, it.position.projection(), it.name) } else emptyList(),
    )
}
internal fun actualLayers() = LayersState(layers = frozenListOf(
    LayerOption("enc", "Seekarte · S52", true, true, "Direkt per GLES; nautische Darstellung nicht freigegeben.", locked = true),
    LayerOption("ownship", "Bootsposition", true, true, "Letzte belegte Bootsposition."),
    LayerOption("ais", "AIS · letzte gemeldete Position", true, true, "Messalter und Zielqualität im Helm-Vertrag unbekannt."),
    LayerOption("waypoints", "Wegpunkte", true, true, "Positionen aus dem bestätigten Snapshot."),
    LayerOption("route", "Route / Entwurf", true, true, "Anzeige startet keine Routenführung."),
    LayerOption("radar", "Radar", false, false, "Kein Radarvertrag angeschlossen."),
    LayerOption("satellite", "Satellitenkarte", false, false, "Kein Satellitenkartenadapter angeschlossen."),

))

internal fun gatewayFeatureStatus(helm: HelmState, feature: String) = if (helm.snapshot?.gatewayV1 == true) DataStatus(Availability.ERROR, "$feature werden vom No5-Gateway V1 nicht geliefert.") else sourceStatus(helm)
