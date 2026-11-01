package com.no5.preparation.ui

import androidx.compose.ui.platform.LocalContext
import com.no5.ui.No5Text
import com.no5.ui.No5DetailsDialog
import com.no5.ui.No5FormDialog
import com.no5.ui.No5FormField
import com.no5.header.artwork.No5WorkspaceView
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import java.util.UUID
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontFamily

@Immutable data class RoutePoint(val latitude: Double, val longitude: Double, val id: String = "", val name: String = "") {
    val valid: Boolean get() = latitude.isFinite() && longitude.isFinite() && latitude in -85.0..85.0 && longitude in -180.0..180.0
}
@Immutable data class RouteDocument(val id: String, val name: String, val points: FrozenList<RoutePoint> = frozenListOf(), val revision: Long = 0, val external: Boolean = false, val source: String? = null, val signalKToken: String? = null, val signalKResource: String? = null,
    /** Local recovery metadata only; never sent as part of the Signal K resource. */
    val offlineCopyPending: Boolean = false)
@Immutable data class CoordinateEntry(val index: Int? = null, val latitude: String = "", val longitude: String = "", val error: String? = null, val name: String = "",
    val originalLatitude: String = latitude, val originalLongitude: String = longitude, val originalName: String = name) {
    val dirty: Boolean get() = latitude != originalLatitude || longitude != originalLongitude || name != originalName
}
@Immutable data class RouteSaveRequest(val token: Long, val snapshot: RouteDocument, val remoteConfirmed: Boolean = false)
/** entryLeg is zero-based: 0 means point 1 → point 2, never an implicit nearest-leg choice. */
@Immutable data class RouteStartRequest(val token: Long, val snapshot: RouteDocument, val entryLeg: Int, val coverage: RouteCoverage = RouteCoverage.UNKNOWN)
@Immutable data class RouteStopRequest(val token: Long, val snapshot: RouteDocument)
@Immutable data class RouteLengthEstimate(val routeId: String, val revision: Long, val meters: Double?)
enum class RouteCoverage { UNKNOWN, PARTIAL, AVAILABLE }
enum class RouteNavigationMode { UNKNOWN, MANUAL_GUIDANCE }
/** Backend owns data/status/fix freshness/mode. Pending requests are commands: dispatch once per token;
 * acknowledge using matching tokens only. Save ack MUST follow durable persistence. Start is guidance only,
 * never autopilot. Adapter must revalidate fix/mode, document revision and selected leg on command
 * acceptance and report rejection. Stop also requires a matching ack; do not clear active optimistically.
 * Projected map taps call AddPoint with real WGS84 values; pixels are never accepted as coordinates. */
@Immutable data class RoutesState(
    val routes: FrozenList<RouteDocument> = frozenListOf(),
    val status: DataStatus = DataStatus(),
    val draft: RouteDocument? = null,
    val shown: RouteDocument? = null,
    val insertAt: Int? = null,
    val replaceAt: Int? = null,
    val deleteCandidate: RouteDocument? = null,
    val dirty: Boolean = false,
    val coordinate: CoordinateEntry? = null,
    val coordinateCloseDialog: Boolean = false,
    val undoPoints: FrozenList<RoutePoint>? = null,
    val undoDocument: RouteDocument? = null,
    val nextPointId: Long = 1,
    val routeLength: RouteLengthEstimate? = null,
    val discardDialog: Boolean = false,
    val review: RouteDocument? = null,
    val reviewEntryLeg: Int? = null,
    val coverage: RouteCoverage = RouteCoverage.UNKNOWN,
    val stopDialog: Boolean = false,
    val stopRequest: RouteStopRequest? = null,
    val activeEntryLeg: Int? = null,
    val saveRequest: RouteSaveRequest? = null,
    /** Remote ACK is retained when only the local offline-copy commit failed. */
    val cachePending: RouteDocument? = null,
    val startRequest: RouteStartRequest? = null,
    val active: RouteDocument? = null,
    val activeStatusLabel: String = "Aktive Routenführung",
    val freshFix: Boolean = false,
    val mode: RouteNavigationMode = RouteNavigationMode.UNKNOWN,
    val nextToken: Long = 1,
    val message: String? = null,
    val saveSupported: Boolean = true,
    val startSupported: Boolean = true,
    val stopSupported: Boolean = true,
    val writeCapabilityDetail: String? = null,
)
sealed interface RoutesEvent {
    data object New : RoutesEvent
    data class Open(val id: String) : RoutesEvent
    data object EditShown : RoutesEvent
    data class InsertBefore(val index: Int) : RoutesEvent
    data class ReplaceOnMap(val index: Int) : RoutesEvent
    data class PlacePoint(val index: Int, val point: RoutePoint) : RoutesEvent
    data object Hide : RoutesEvent
    data object ReviewDelete : RoutesEvent
    data object CancelDelete : RoutesEvent
    data object Deleted : RoutesEvent
    data class Recover(val document: RouteDocument) : RoutesEvent
    data class Rename(val name: String) : RoutesEvent
    data class EditPoint(val index: Int? = null) : RoutesEvent
    /** Renderer adapter supplies actual projected WGS84 coordinates. */
    data class AddPoint(val point: RoutePoint) : RoutesEvent
    data class Latitude(val value: String) : RoutesEvent
    data class Longitude(val value: String) : RoutesEvent
    data class PointName(val value: String) : RoutesEvent
    data object ConfirmCoordinate : RoutesEvent
    data object CancelCoordinate : RoutesEvent
    data object DiscardCoordinate : RoutesEvent
    data object KeepCoordinate : RoutesEvent
    data class MovePoint(val index: Int, val delta: Int) : RoutesEvent
    data class RemovePoint(val index: Int) : RoutesEvent
    data object Undo : RoutesEvent
    data object CancelEdit : RoutesEvent
    data object KeepEditing : RoutesEvent
    data object Discard : RoutesEvent
    data object Save : RoutesEvent
    data class Saved(val token: Long, val document: RouteDocument? = null) : RoutesEvent
    data class SaveFailed(val token: Long, val reason: String) : RoutesEvent
    data class SaveCacheFailed(val token: Long, val document: RouteDocument, val reason: String) : RoutesEvent
    data object ReviewStart : RoutesEvent
    data object CancelStart : RoutesEvent
    data class SelectEntryLeg(val index: Int) : RoutesEvent
    data object ReviewStop : RoutesEvent
    data object CancelStop : RoutesEvent
    data object ConfirmStop : RoutesEvent
    data class Stopped(val token: Long) : RoutesEvent
    data class StopFailed(val token: Long, val reason: String) : RoutesEvent
    data object ConfirmStart : RoutesEvent
    data class Started(val token: Long) : RoutesEvent
    data class StartFailed(val token: Long, val reason: String) : RoutesEvent
    data class NavigationReadiness(val freshFix: Boolean, val mode: RouteNavigationMode) : RoutesEvent
}
// WGS84 aliases at the antimeridian and poles represent the same position.
private fun RoutePoint.samePosition(other: RoutePoint) = latitude == other.latitude &&
    (longitude == other.longitude || kotlin.math.abs(longitude - other.longitude) == 360.0 || kotlin.math.abs(latitude) == 90.0)
fun RouteDocument.valid() = name.isNotBlank() && points.size >= 2 && points.all { it.valid } && points.zipWithNext().all { (a, b) -> !a.samePosition(b) }
private fun RoutesState.canStart() = startSupported && freshFix && mode == RouteNavigationMode.MANUAL_GUIDANCE && startRequest == null && stopRequest == null && active == null
private fun RoutesState.changed(points: List<RoutePoint>): RoutesState = copy(draft = draft?.copy(points = FrozenList.copyOf(points), offlineCopyPending = false), dirty = true, undoPoints = draft?.points, undoDocument = draft, review = null, message = null)
private fun RoutesState.identify(point: RoutePoint): Pair<RoutePoint, Long> {
    if (point.id.isNotBlank()) return point to nextPointId
    return point.copy(id = "point-${UUID.randomUUID()}") to nextPointId + 1
}
fun reduceRoutes(state: RoutesState, event: RoutesEvent): RoutesState {
    val s = state
    val d = s.draft
    // Persistence snapshots must not change during their outstanding request.
    val editLocked = s.saveRequest != null || s.startRequest != null || s.stopRequest != null
    return when (event) {
        RoutesEvent.New -> if (editLocked || s.dirty) s.copy(message = "Bearbeitung zuerst speichern oder abbrechen.") else {
            var token = s.nextToken
            while (s.routes.any { it.id == "local-$token" }) token++
            s.copy(draft = RouteDocument("local-${UUID.randomUUID()}", "Neue Route"), nextToken = token + 1, dirty = true, insertAt = null, replaceAt = null, undoPoints = null, undoDocument = null, review = null, message = null)
        }
        is RoutesEvent.Open -> if (editLocked || s.dirty) s.copy(message = "Bearbeitung zuerst speichern oder abbrechen.") else s.copy(shown = s.routes.find { it.id == event.id }, draft = null, insertAt = null, replaceAt = null, undoPoints = null, undoDocument = null, review = null, message = null)
        RoutesEvent.EditShown -> if (editLocked || s.dirty || s.shown == null) s else {
            val original = s.shown
            val editable = if (original.external) original.copy(id = "local-${UUID.randomUUID()}", revision = 0, external = false,
                points = FrozenList.copyOf(original.points.map { it.copy(id = "point-${UUID.randomUUID()}") }), name = "${original.name} · Kopie", signalKToken = null, signalKResource = null, source = null) else original
            s.copy(draft = editable, dirty = original.external, insertAt = null, replaceAt = null, message = null)
        }
        is RoutesEvent.InsertBefore -> if (editLocked || d == null || event.index !in d.points.indices) s else s.copy(insertAt = event.index, replaceAt = null, message = "Neuen Wegpunkt auf der Karte antippen.")
        is RoutesEvent.ReplaceOnMap -> if (editLocked || d == null || event.index !in d.points.indices) s else s.copy(replaceAt = event.index, insertAt = null, message = "Neue Position antippen oder den Punkt auf der Karte ziehen.")
        is RoutesEvent.PlacePoint -> if (editLocked || d == null || event.index !in d.points.indices || !event.point.valid) s else {
            val points = d.points.toMutableList(); points[event.index] = points[event.index].copy(latitude = event.point.latitude, longitude = event.point.longitude)
            s.changed(points).copy(insertAt = null, replaceAt = null)
        }
        RoutesEvent.Hide -> if (editLocked || s.dirty) s else s.copy(shown = null, draft = null)
        RoutesEvent.ReviewDelete -> if (editLocked || s.dirty || s.shown?.external != false) s else s.copy(deleteCandidate = s.shown)
        RoutesEvent.CancelDelete -> s.copy(deleteCandidate = null)
        RoutesEvent.Deleted -> s.copy(routes = FrozenList.copyOf(s.routes.filterNot { it.id == s.deleteCandidate?.id }), shown = null, deleteCandidate = null, draft = null)
        is RoutesEvent.Recover -> if (editLocked || s.dirty) s else s.copy(draft = event.document, cachePending = event.document.takeIf { it.offlineCopyPending }, dirty = true, insertAt = null, replaceAt = null,
            message = if (event.document.offlineCopyPending) "Signal K bestätigt; Offline-Kopie steht noch aus." else "Wiederhergestellter Entwurf · noch nicht gespeichert.")
        is RoutesEvent.Rename -> if (editLocked || d == null) s else s.copy(draft = d.copy(name = event.name, offlineCopyPending = false), dirty = true, undoDocument = d, undoPoints = d.points, review = null)
        is RoutesEvent.EditPoint -> if (editLocked || d == null) s else {
            val point = event.index?.let { d.points.getOrNull(it) }
            if (event.index != null && point == null) s else s.copy(coordinate = CoordinateEntry(event.index, point?.latitude?.toString() ?: "", point?.longitude?.toString() ?: "", name = point?.name ?: ""))
        }
        is RoutesEvent.AddPoint -> if (editLocked || d == null) s else if (!event.point.valid) s.copy(message = "Ungültige WGS84-Koordinate vom Renderer.") else {
            val (point, nextId) = s.identify(event.point)
            if (d.points.any { it.id.isNotBlank() && it.id == point.id }) s.copy(message = "Wegpunkt-ID bereits vorhanden.")
            else if (s.replaceAt != null) reduceRoutes(s, RoutesEvent.PlacePoint(s.replaceAt, point))
            else { val points = d.points.toMutableList(); points.add(s.insertAt?.coerceIn(0, points.size) ?: points.size, point)
                s.changed(points).copy(nextPointId = nextId, insertAt = null, replaceAt = null) }
        }
        is RoutesEvent.Latitude -> s.copy(coordinate = s.coordinate?.copy(latitude = event.value, error = null))
        is RoutesEvent.Longitude -> s.copy(coordinate = s.coordinate?.copy(longitude = event.value, error = null))
        is RoutesEvent.PointName -> s.copy(coordinate = s.coordinate?.copy(name = event.value))
        RoutesEvent.CancelCoordinate -> if (editLocked) s else if (s.coordinate?.dirty == true) s.copy(coordinateCloseDialog = true) else s.copy(coordinate = null, coordinateCloseDialog = false)
        RoutesEvent.DiscardCoordinate -> if (editLocked) s else s.copy(coordinate = null, coordinateCloseDialog = false)
        RoutesEvent.KeepCoordinate -> s.copy(coordinateCloseDialog = false)
        RoutesEvent.ConfirmCoordinate -> {
            val c = s.coordinate
            val lat = c?.latitude?.trim()?.replace(',', '.')?.toDoubleOrNull()
            val lon = c?.longitude?.trim()?.replace(',', '.')?.toDoubleOrNull()
            if (editLocked || d == null || c == null) s
            else if (lat == null || lon == null || !RoutePoint(lat, lon).valid) s.copy(coordinate = c.copy(error = "WGS84: Breite −85…85°, Länge −180…180°; Dezimalgrad."))
            else {
                val points = d.points.toMutableList()
                val existing = c.index?.let { points.getOrNull(it) }
                if (c.index != null && existing == null) return s
                val (point, nextId) = s.identify(RoutePoint(lat, lon, existing?.id ?: "", c.name))
                if (c.index == null) points.add(point) else points[c.index] = point
                s.changed(points).copy(coordinate = null, coordinateCloseDialog = false, nextPointId = nextId)
            }
        }
        is RoutesEvent.MovePoint -> if (editLocked || d == null || event.index !in d.points.indices || event.delta == 0 || event.delta !in -event.index..(d.points.lastIndex - event.index)) s else {
            val points = d.points.toMutableList(); val point = points.removeAt(event.index); points.add(event.index + event.delta, point); s.changed(points)
        }
        is RoutesEvent.RemovePoint -> if (editLocked || d == null || event.index !in d.points.indices) s else s.changed(d.points.filterIndexed { i, _ -> i != event.index })
        RoutesEvent.Undo -> if (editLocked || d == null || s.undoDocument == null) s else s.copy(draft = s.undoDocument, dirty = true, undoDocument = null, undoPoints = null, coordinate = null, review = null, message = "Letzte Änderung rückgängig gemacht.")
        RoutesEvent.CancelEdit -> if (editLocked) s else if (s.dirty) s.copy(discardDialog = true) else s.copy(draft = null, review = null, coordinate = null, insertAt = null, replaceAt = null)
        RoutesEvent.KeepEditing -> if (editLocked) s else s.copy(discardDialog = false)
        RoutesEvent.Discard -> if (editLocked) s else s.copy(draft = null, dirty = false, discardDialog = false, coordinate = null, coordinateCloseDialog = false, cachePending = null, insertAt = null, replaceAt = null, undoPoints = null, undoDocument = null, review = null)
        RoutesEvent.Save -> if (!s.saveSupported) s.copy(message = s.writeCapabilityDetail ?: "Kein Routen-Schreibvertrag verfügbar.") else if (editLocked || d == null || !s.dirty) s else if (!d.valid()) s.copy(message = "Name und mindestens zwei gültige Wegpunkte ohne identische Nachbarpunkte erforderlich.") else {
            val cached = s.cachePending?.takeIf { it == d } ?: d.takeIf { it.offlineCopyPending }
            val snapshot = cached ?: d.copy(revision = maxOf(d.revision, s.routes.find { it.id == d.id }?.revision ?: 0) + 1)
            s.copy(saveRequest = RouteSaveRequest(s.nextToken, snapshot, remoteConfirmed = cached != null), nextToken = s.nextToken + 1, message = "Speicherbestätigung ausstehend.")
        }
        is RoutesEvent.Saved -> if (s.saveRequest?.token != event.token) s else {
            val saved = event.document?.takeIf { it.id == s.saveRequest.snapshot.id && it.revision == s.saveRequest.snapshot.revision } ?: s.saveRequest.snapshot
            s.copy(routes = FrozenList.copyOf(s.routes.filterNot { it.id == saved.id } + saved), draft = null, shown = saved, dirty = false, saveRequest = null, cachePending = null, discardDialog = false, coordinate = null, coordinateCloseDialog = false, undoPoints = null, undoDocument = null, message = "Route gespeichert. Navigation wurde nicht gestartet.")
        }
        is RoutesEvent.SaveFailed -> if (s.saveRequest?.token != event.token) s else s.copy(saveRequest = null, message = "Speichern fehlgeschlagen: ${event.reason}")
        is RoutesEvent.SaveCacheFailed -> if (s.saveRequest?.token != event.token || event.document.id != s.saveRequest.snapshot.id || event.document.revision != s.saveRequest.snapshot.revision) s else s.copy(
            routes = FrozenList.copyOf(s.routes.filterNot { it.id == event.document.id } + event.document),
            draft = event.document.copy(offlineCopyPending = true), cachePending = event.document.copy(offlineCopyPending = true), dirty = true, saveRequest = null,
            message = "Signal K bestätigt; Offline-Kopie konnte nicht gespeichert werden: ${event.reason}")
        RoutesEvent.ReviewStart -> if (editLocked || d == null || s.dirty || !d.valid() || s.routes.none { it == d } || !s.canStart()) s.copy(message = "Start gesperrt: gespeicherte Route, frische Position und bestätigter Führungsmodus erforderlich; keine laufende Navigation.") else s.copy(review = d, reviewEntryLeg = null)
        RoutesEvent.CancelStart -> s.copy(review = null, reviewEntryLeg = null)
        is RoutesEvent.SelectEntryLeg -> if (s.review == null || event.index !in 0 until s.review.points.lastIndex) s else s.copy(reviewEntryLeg = event.index)
        RoutesEvent.ReviewStop -> if (!s.stopSupported || s.active == null || editLocked) s else s.copy(stopDialog = true)
        RoutesEvent.CancelStop -> s.copy(stopDialog = false)
        RoutesEvent.ConfirmStop -> if (!s.stopSupported || !s.stopDialog || s.active == null || editLocked) s else s.copy(stopRequest = RouteStopRequest(s.nextToken, s.active), nextToken = s.nextToken + 1, stopDialog = false, message = "Stoppbestätigung ausstehend.")
        is RoutesEvent.Stopped -> if (s.stopRequest?.token != event.token) s else s.copy(stopRequest = null, active = null, activeEntryLeg = null, message = "Routenführung beendet. Gespeicherte Route bleibt erhalten.")
        is RoutesEvent.StopFailed -> if (s.stopRequest?.token != event.token) s else s.copy(stopRequest = null, message = "Stopp fehlgeschlagen: ${event.reason}")
        RoutesEvent.ConfirmStart -> if (s.review == null || !s.review.valid() || s.dirty || !s.canStart() || s.review != d || s.routes.none { it == s.review } || s.reviewEntryLeg == null || s.reviewEntryLeg !in 0 until s.review.points.lastIndex) s.copy(review = null, message = "Start gesperrt. Voraussetzungen erneut prüfen.") else s.copy(startRequest = RouteStartRequest(s.nextToken, s.review, s.reviewEntryLeg, s.coverage), nextToken = s.nextToken + 1, review = null, message = "Startbestätigung ausstehend.")
        is RoutesEvent.Started -> if (s.startRequest?.token != event.token) s else s.copy(active = s.startRequest.snapshot, activeEntryLeg = s.startRequest.entryLeg, startRequest = null, message = "Routenführung aktiv. Keine Autopilotsteuerung.")
        is RoutesEvent.StartFailed -> if (s.startRequest?.token != event.token) s else s.copy(startRequest = null, message = "Start abgelehnt: ${event.reason}")
        is RoutesEvent.NavigationReadiness -> s.copy(freshFix = event.freshFix, mode = event.mode, review = if (event.freshFix && event.mode == RouteNavigationMode.MANUAL_GUIDANCE) s.review else null)
    }
}

@Composable fun RoutesScreen(state: RoutesState, onEvent: (RoutesEvent) -> Unit, modifier: Modifier = Modifier, showDialogs: Boolean = true,
    lengthLabel: String? = null, legLabels: List<String> = emptyList(), onFit: (() -> Unit)? = null) {
    val context = LocalContext.current
    val locked = state.saveRequest != null || state.startRequest != null || state.stopRequest != null
    No5Panel(No5Text.text(LocalContext.current, if (state.draft == null) "ROUTEN" else "ROUTE · ENTWURF"), modifier.fillMaxSize()) {
        lengthLabel?.let { Text(it, Modifier.testTag("route-length")) }
        onFit?.let { No5CompactAction(No5Text.text(LocalContext.current, "Route auf Karte zeigen"), it, Modifier.testTag("route-fit")) }
            StatusNotice(state.status)
            state.writeCapabilityDetail?.let { Text(it) }
            if (state.draft == null) {
                Text(No5Text.text(LocalContext.current, "Speichern und Navigieren sind getrennte Aktionen."))
                No5CompactAction(No5Text.text(LocalContext.current, "+ Neue Route"), { onEvent(RoutesEvent.New) }, Modifier.fillMaxWidth().testTag("route-new"), !locked && !state.dirty)
                if (state.routes.isEmpty()) Text(No5Text.text(LocalContext.current, "Keine gespeicherten Routen"))
                state.routes.forEach { route -> No5CompactAction(No5Text.text(LocalContext.current, "%s · %d Punkte", route.name, route.points.size), { onEvent(RoutesEvent.Open(route.id)) }, Modifier.fillMaxWidth().testTag("route-open-${route.id}"), !locked && !state.dirty) }
            }
            state.active?.let {
                Text(No5Text.text(context, "%s: %s · Version %d", No5Text.text(context, state.activeStatusLabel), it.name, it.revision))
                No5CompactAction(No5Text.text(LocalContext.current, "Routenführung beenden …"), { onEvent(RoutesEvent.ReviewStop) }, Modifier.fillMaxWidth(), !locked && state.stopSupported)
            }
            val draft = state.draft
            state.shown?.takeIf { draft == null }?.let { route ->
                Text(route.name, Modifier.testTag("route-shown-name"))
                Text(No5Text.text(context, "%d Wegpunkte · Version %d · %s", route.points.size, route.revision,
                    No5Text.text(context, if (route.external) "nur lesbare Quelle" else "Signal K / Offline-Kopie")))
                No5CompactAction(No5Text.text(LocalContext.current, if (route.external) "Als lokale Kopie bearbeiten" else "Bearbeiten"), { onEvent(RoutesEvent.EditShown) }, Modifier.testTag("route-edit"), !locked)
                No5CompactAction(No5Text.text(LocalContext.current, "Ausblenden"), { onEvent(RoutesEvent.Hide) }, Modifier.testTag("route-hide"), !locked)
                if (!route.external) No5CompactAction(No5Text.text(LocalContext.current, "Route löschen …"), { onEvent(RoutesEvent.ReviewDelete) }, Modifier.testTag("route-delete"), !locked)
            }
            if (draft == null && state.shown == null) Text(No5Text.text(LocalContext.current, "Route auswählen oder eine neue Route planen.")) else if (draft != null) {
                OutlinedTextField(draft.name, { onEvent(RoutesEvent.Rename(it)) }, label = { Text(No5Text.text(LocalContext.current, "Routenname")) }, enabled = !locked, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("route-name"))
                Text(No5Text.text(LocalContext.current, if (state.dirty) "Ungespeicherte Änderungen" else "Gespeicherter Stand"))
                Text(No5Text.text(LocalContext.current, "Punkte auf der Karte setzen; markierte Punkte ziehen. Kartenbewegung fügt keine Punkte hinzu."), Modifier.testTag("route-edit-mode"))
                draft.points.forEachIndexed { index, point ->
                    legLabels.getOrNull(index - 1)?.let { Text(No5Text.text(LocalContext.current, "Abschnitt %d · %s", index, it)) }
                    Text(No5Text.text(context, "%d. %s · %s° / %s° WGS84", index + 1,
                        point.name.ifBlank { No5Text.text(context, "Wegpunkt") }, point.latitude, point.longitude), fontFamily = FontFamily.Monospace)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(9.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        No5CompactAction(No5Text.text(LocalContext.current, "Position auf Karte"), { onEvent(RoutesEvent.ReplaceOnMap(index)) }, Modifier.testTag("route-replace-$index"), !locked)
                        No5CompactAction(No5Text.text(LocalContext.current, "Davor einfügen"), { onEvent(RoutesEvent.InsertBefore(index)) }, Modifier.testTag("route-insert-$index"), !locked)
                        No5CompactAction(No5Text.text(LocalContext.current, "Ändern"), { onEvent(RoutesEvent.EditPoint(index)) }, enabled = !locked)
                        No5CompactAction("↑", { onEvent(RoutesEvent.MovePoint(index, -1)) }, Modifier.semantics { contentDescription = No5Text.text(context, "Wegpunkt %d nach oben", index + 1) }, enabled = !locked && index > 0)
                        No5CompactAction("↓", { onEvent(RoutesEvent.MovePoint(index, 1)) }, Modifier.semantics { contentDescription = No5Text.text(context, "Wegpunkt %d nach unten", index + 1) }, enabled = !locked && index < draft.points.lastIndex)
                        No5CompactAction("×", { onEvent(RoutesEvent.RemovePoint(index)) }, Modifier.semantics { contentDescription = No5Text.text(context, "Wegpunkt %d entfernen", index + 1) }, enabled = !locked)
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(9.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    No5CompactAction(No5Text.text(LocalContext.current, "Wegpunkt hinzufügen"), { onEvent(RoutesEvent.EditPoint()) }, enabled = !locked)
                    No5CompactAction(No5Text.text(LocalContext.current, "Rückgängig"), { onEvent(RoutesEvent.Undo) }, Modifier.testTag("route-undo"), enabled = !locked && state.undoDocument != null)
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(9.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    No5CompactAction(No5Text.text(LocalContext.current, "In Signal K speichern"), { onEvent(RoutesEvent.Save) }, Modifier.testTag("route-save"), enabled = !locked && state.dirty && state.saveSupported)
                    No5CompactAction(No5Text.text(LocalContext.current, "Abbrechen"), { onEvent(RoutesEvent.CancelEdit) }, enabled = !locked)
                }
                HorizontalDivider()
                Text(No5Text.text(context, "Position: %s · Modus: %s", No5Text.text(context, if (state.freshFix) "frisch" else "fehlt / veraltet"), No5Text.text(context, if(state.mode==RouteNavigationMode.MANUAL_GUIDANCE)"Routenführung"else"Unbekannt")))
                No5CompactAction(No5Text.text(LocalContext.current, "Navigation prüfen …"), { onEvent(RoutesEvent.ReviewStart) }, enabled = !locked && !state.dirty && state.canStart() && draft.valid())
                Text(No5Text.text(LocalContext.current, "Speichern startet keine Navigation. Keine Autopilotsteuerung."))
            }
            state.message?.let { Text(routeUiMessage(context, it), color = MaterialTheme.colorScheme.primary) }
    }
    if (showDialogs) RoutesDialogs(state, onEvent)
}

/** Dialogs stay mounted when the side panel is collapsed or a destination change awaits discard. */
@Composable fun RoutesDialogs(state: RoutesState, onEvent: (RoutesEvent) -> Unit, night:Boolean=false) {
    val context = LocalContext.current
    val locked = state.saveRequest != null || state.startRequest != null || state.stopRequest != null
    state.coordinate?.let { coordinate ->
        val decimal=androidx.compose.foundation.text.KeyboardOptions(keyboardType=androidx.compose.ui.text.input.KeyboardType.Decimal)
        No5FormDialog("Wegpunkt",listOf(
            No5FormField("Name",coordinate.name,"route-point-name",onChange={onEvent(RoutesEvent.PointName(it))}),
            No5FormField("route.latitude",coordinate.latitude,"route-point-latitude",keyboard=decimal,onChange={onEvent(RoutesEvent.Latitude(it))}),
            No5FormField("Länge",coordinate.longitude,"route-point-longitude",keyboard=decimal,onChange={onEvent(RoutesEvent.Longitude(it))})),
            night,{onEvent(RoutesEvent.CancelCoordinate)},tag="route-coordinate",
            notice=coordinate.error?.let{routeUiMessage(context,it)},
            actions=listOf(
                No5WorkspaceView.Action("Zurück",key="route-point-cancel",action={onEvent(RoutesEvent.CancelCoordinate)}),
                No5WorkspaceView.Action("action.apply",enabled=coordinate.latitude.toDoubleOrNull()?.let{it.isFinite()&&it in -85.0..85.0}==true&&coordinate.longitude.toDoubleOrNull()?.let{it.isFinite()&&it in -180.0..180.0}==true,
                    key="route-point-apply",action={onEvent(RoutesEvent.ConfirmCoordinate)})))
    }
    if (state.coordinateCloseDialog) No5UnsavedChangesDialog(
        onSave = { onEvent(RoutesEvent.ConfirmCoordinate) },
        onDiscard = { onEvent(RoutesEvent.DiscardCoordinate) },
        onContinue = { onEvent(RoutesEvent.KeepCoordinate) }, saving = locked,
        error = state.coordinate?.error?.let { routeUiMessage(context, it) },
    )
    if (state.discardDialog) No5UnsavedChangesDialog(
        onSave = { onEvent(RoutesEvent.Save) },
        onDiscard = { onEvent(RoutesEvent.Discard) },
        onContinue = { onEvent(RoutesEvent.KeepEditing) }, saving = locked,
        saveEnabled = state.saveSupported,
        error = state.message?.takeIf { state.saveRequest == null }?.let { routeUiMessage(context, it) },
    )
    if (state.stopDialog) No5DetailsDialog("Routenführung beenden?",night,{onEvent(RoutesEvent.CancelStop)},
        body=No5Text.text(context,"Die aktive Führung wird erst nach Backend-Bestätigung beendet. Die gespeicherte Route bleibt erhalten. Keine Autopilotsteuerung."),
        actions=listOf(
            No5WorkspaceView.Action("Zurück",key="route-stop-cancel",action={onEvent(RoutesEvent.CancelStop)}),
            No5WorkspaceView.Action("Führung beenden",enabled=!locked,key="route-stop-confirm",action={onEvent(RoutesEvent.ConfirmStop)})),tag="route-stop")
    state.review?.let { route ->
        val length = state.routeLength?.takeIf { it.routeId == route.id && it.revision == route.revision }?.meters?.takeIf { it.isFinite() && it >= 0 }
        val facts=listOf(
            No5Text.text(context,"%s · Version %d · %d Wegpunkte",route.name,route.revision,route.points.size),
            No5Text.text(context,"Routenlänge: %s",length?.let{No5Text.text(context,"%s m · laut Adapter",it)}?:No5Text.text(context,"unbekannt · kein bestätigter Adapterwert")),
            No5Text.text(context,"Kartenabdeckung: %s",No5Text.text(context,when(state.coverage){RouteCoverage.UNKNOWN->"UNBEKANNT · nicht geprüft";RouteCoverage.PARTIAL->"TEILWEISE · Lücken vorhanden";RouteCoverage.AVAILABLE->"verfügbar laut Adapter"})),
            No5Text.text(context,"Warnung: Keine Kollisions-, Tiefen- oder Befahrbarkeitsfreigabe. Position und Führungsmodus werden vor Annahme erneut geprüft. Keine Autopilotsteuerung."))
        No5DetailsDialog("Routenführung prüfen",night,{onEvent(RoutesEvent.CancelStart)},body=facts.joinToString("\n"),
            rows=(0 until route.points.lastIndex).map{leg->No5WorkspaceView.Row(
                No5Text.text(context,"Abschnitt %d: WP %d → WP %d",leg+1,leg+1,leg+2),rawLabel=true,
                selected=state.reviewEntryLeg==leg,control="check",key="route-entry-$leg",action={onEvent(RoutesEvent.SelectEntryLeg(leg))})},
            actions=listOf(
                No5WorkspaceView.Action("Zurück",key="route-start-cancel",action={onEvent(RoutesEvent.CancelStart)}),
                No5WorkspaceView.Action("Routenführung starten",enabled=state.canStart()&&state.reviewEntryLeg!=null,key="route-start-confirm",action={onEvent(RoutesEvent.ConfirmStart)})),tag="route-start")
    }
}

/** Local status templates only; opaque backend reasons remain untouched arguments. */
private fun routeUiMessage(context: android.content.Context, message: String): String {
    for (prefix in listOf("Speichern fehlgeschlagen: ", "Stopp fehlgeschlagen: ", "Start abgelehnt: ",
        "Signal K bestätigt; Offline-Kopie konnte nicht gespeichert werden: ")) {
        if (message.startsWith(prefix)) return No5Text.text(context, prefix + "%s", message.removePrefix(prefix))
    }
    return No5Text.text(context, message)
}
