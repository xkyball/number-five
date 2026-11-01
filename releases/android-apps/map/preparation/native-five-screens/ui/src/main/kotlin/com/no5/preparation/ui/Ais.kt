package com.no5.preparation.ui

import com.no5.ui.No5Text
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.util.Locale

@Immutable data class AisPosition(val latitudeDegrees: Double, val longitudeDegrees: Double) {
    init { require(latitudeDegrees.isFinite() && latitudeDegrees in -90.0..90.0); require(longitudeDegrees.isFinite() && longitudeDegrees in -180.0..180.0) }
}
enum class AisQuality { VERIFIED, DEGRADED, UNKNOWN }
/** Freshness and approach assessments belong to the adapter's explicit policy, never to UI thresholds. */
enum class AisFreshness { CURRENT, STALE, LOST, UNKNOWN }
enum class AisFilter(val label: String) { ALL("Alle"), APPROACHES("Annäherungen"), WATCHED("Beobachtet"), STALE_LOST("Alt / verloren") }
@Immutable data class AisTarget(
    val mmsi: String,
    val name: String? = null,
    val position: AisPosition? = null,
    val speedKnots: Double? = null,
    val courseDegrees: Double? = null,
    val rangeNauticalMiles: Double? = null,
    val cpaNauticalMiles: Double? = null,
    val tcpaMinutes: Double? = null,
    val measurementEpochMillis: Long? = null,
    val receptionEpochMillis: Long? = null,
    val quality: AisQuality = AisQuality.UNKNOWN,
    val freshness: AisFreshness = AisFreshness.UNKNOWN,
    val approaching: Boolean? = null,
    val watched: Boolean = false,
    /** Adapter-estimated measurement age at receipt, not wall-clock subtraction in the UI. */
    val measurementAgeAtReceiptMillis: Long? = null,
    /** Same monotonic clock domain and boot/session as AisState.nowMonotonicMillis. */
    val receptionMonotonicMillis: Long? = null,
)
@Immutable data class AisState(
    val targets: FrozenList<AisTarget> = frozenListOf(),
    val status: DataStatus = DataStatus(),
    val query: String = "",
    val filter: AisFilter = AisFilter.ALL,
    val selectedMmsi: String? = null,
    val nowEpochMillis: Long? = null,
    val nowMonotonicMillis: Long? = null,
    val distanceUnit: DistanceUnit = DistanceUnit.NAUTICAL_MILES,
    val speedUnit: SpeedUnit = SpeedUnit.KNOTS,
    val detailsExpanded: Boolean = false,
)
sealed interface AisEvent {
    data class Search(val query: String) : AisEvent
    data class SetFilter(val filter: AisFilter) : AisEvent
    data class Select(val mmsi: String) : AisEvent
    data object ShowList : AisEvent
    data class ToggleWatch(val mmsi: String) : AisEvent
    /** Consumer handles the deliberate view-only map request. This never starts navigation. */
    data class ShowOnMap(val mmsi: String, val position: AisPosition) : AisEvent
}
fun visibleAisTargets(state: AisState): List<AisTarget> = state.targets.filter { target ->
    val query = state.query.trim()
    (query.isEmpty() || target.mmsi.contains(query, ignoreCase = true) || target.name.orEmpty().contains(query, ignoreCase = true)) &&
        when (state.filter) {
            AisFilter.ALL -> true
            AisFilter.APPROACHES -> target.approaching == true
            AisFilter.WATCHED -> target.watched
            AisFilter.STALE_LOST -> target.freshness == AisFreshness.STALE || target.freshness == AisFreshness.LOST
        }
}
fun selectedAisTarget(state: AisState): AisTarget? = visibleAisTargets(state).firstOrNull { it.mmsi == state.selectedMmsi }
/** Pure local UI reducer; production watch persistence is the host's explicit responsibility. */
fun reduceAis(state: AisState, event: AisEvent): AisState {
    val changed = when (event) {
        is AisEvent.Search -> state.copy(query = event.query)
        is AisEvent.SetFilter -> state.copy(filter = event.filter)
        is AisEvent.Select -> state.copy(selectedMmsi = event.mmsi, detailsExpanded = true)
        AisEvent.ShowList -> state.copy(detailsExpanded = false)
        is AisEvent.ToggleWatch -> state.copy(targets = FrozenList.copyOf(state.targets.map { if (it.mmsi == event.mmsi) it.copy(watched = !it.watched) else it }))
        is AisEvent.ShowOnMap -> state
    }
    return if (changed.selectedMmsi != null && selectedAisTarget(changed) == null) changed.copy(selectedMmsi = null, detailsExpanded = false) else changed
}
/** Age is measurement age, not reception age. Future/missing times do not become zero. */
fun aisAgeSeconds(nowEpochMillis: Long?, measurementEpochMillis: Long?): Long? {
    if (nowEpochMillis == null || measurementEpochMillis == null || measurementEpochMillis > nowEpochMillis) return null
    return try { Math.subtractExact(nowEpochMillis, measurementEpochMillis) / 1000L } catch (_: ArithmeticException) { null }
}
/** No UTC fallback: missing timing provenance, reboot/rollback or overflow means unknown. */
fun aisMonotonicAgeSeconds(nowMonotonicMillis: Long?, receptionMonotonicMillis: Long?, measurementAgeAtReceiptMillis: Long?): Long? {
    if (nowMonotonicMillis == null || receptionMonotonicMillis == null || measurementAgeAtReceiptMillis == null ||
        receptionMonotonicMillis < 0L || measurementAgeAtReceiptMillis < 0L || nowMonotonicMillis < receptionMonotonicMillis) return null
    return try {
        Math.addExact(measurementAgeAtReceiptMillis, Math.subtractExact(nowMonotonicMillis, receptionMonotonicMillis)) / 1000L
    } catch (_: ArithmeticException) { null }
}
fun aisDisplayedAgeSeconds(state: AisState, target: AisTarget): Long? = aisMonotonicAgeSeconds(state.nowMonotonicMillis, target.receptionMonotonicMillis, target.measurementAgeAtReceiptMillis)
fun aisDistanceValue(nauticalMiles: Double?, unit: DistanceUnit): Double? = nauticalMiles?.takeIf { it.isFinite() }?.let { if (unit == DistanceUnit.KILOMETRES) it * 1.852 else it }?.takeIf { it.isFinite() }
fun aisSpeedValue(knots: Double?, unit: SpeedUnit): Double? = knots?.takeIf { it.isFinite() }?.let { if (unit == SpeedUnit.KILOMETRES_PER_HOUR) it * 1.852 else it }?.takeIf { it.isFinite() }
private fun distance(value: Double?, state: AisState): String = number(aisDistanceValue(value, state.distanceUnit), if (state.distanceUnit == DistanceUnit.NAUTICAL_MILES) "sm" else "km")
private fun speed(value: Double?, state: AisState): String = number(aisSpeedValue(value, state.speedUnit), if (state.speedUnit == SpeedUnit.KNOTS) "kn" else "km/h")
private fun number(value: Double?, unit: String): String = value?.takeIf { it.isFinite() }?.let { String.format(No5Text.locale(com.no5.ui.UiLanguage.current()), "%.1f %s", it, unit) } ?: No5Text.ui("Unbekannt")
private fun timestamp(value: Long?): String = value?.let { Instant.ofEpochMilli(it).toString() } ?: No5Text.ui("Unbekannt")
private fun freshness(value: AisFreshness) = when(value) { AisFreshness.CURRENT -> "Aktuell"; AisFreshness.STALE -> "Veraltet"; AisFreshness.LOST -> "Verloren"; AisFreshness.UNKNOWN -> "Unbekannt" }
private fun quality(value: AisQuality) = when(value) { AisQuality.VERIFIED -> "Verifiziert"; AisQuality.DEGRADED -> "Eingeschränkt"; AisQuality.UNKNOWN -> "Unbekannt" }

@Composable fun AisScreen(state: AisState, onEvent: (AisEvent) -> Unit, modifier: Modifier = Modifier) {
    val visible = visibleAisTargets(state)
    val target = selectedAisTarget(state)
    if (state.detailsExpanded && target != null) {
        Column(modifier.fillMaxSize()) {
            No5CompactAction(No5Text.ui("‹ Zurück zur Liste"), { onEvent(AisEvent.ShowList) },
                Modifier.padding(horizontal = 16.dp).testTag("ais-list-back"))
            No5Panel(No5Text.ui("ZIELDETAILS"), Modifier.weight(1f).fillMaxWidth()) {
                Text(target.name?.takeIf { it.isNotBlank() } ?: No5Text.ui("Name unbekannt"), style = MaterialTheme.typography.headlineSmall)
                Text("MMSI ${target.mmsi}", fontFamily = FontFamily.Monospace)
                Text(No5Text.ui("Datenstand: %s · Qualität: %s",No5Text.ui(freshness(target.freshness)),No5Text.ui(quality(target.quality))))
                Text(when (target.approaching) { true -> No5Text.ui("Annäherung gemeldet"); false -> No5Text.ui("Keine Annäherung gemeldet"); null -> No5Text.ui("Risiko unbekannt") })
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    No5Metric(No5Text.ui("Geschwindigkeit"), speed(target.speedKnots, state), Modifier.weight(1f))
                    No5Metric(No5Text.ui("Kurs über Grund"), number(target.courseDegrees, "°"), Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    No5Metric("CPA", distance(target.cpaNauticalMiles, state), Modifier.weight(1f))
                    No5Metric("TCPA", number(target.tcpaMinutes, "min"), Modifier.weight(1f))
                }
                Text(No5Text.ui("CPA/TCPA unbekannt bedeutet keine bestätigte Entwarnung."))
                No5Metric(No5Text.ui("Messalter"), aisDisplayedAgeSeconds(state, target)?.let { "$it s" } ?: No5Text.ui("Unbekannt"))
                Text(No5Text.ui("Messzeit (UTC): %s",timestamp(target.measurementEpochMillis)), fontFamily = FontFamily.Monospace)
                Text(No5Text.ui("Empfang (UTC): %s",timestamp(target.receptionEpochMillis)), fontFamily = FontFamily.Monospace)
                Text(target.position?.let { String.format(Locale.ROOT, No5Text.ui("Letzte Position: %.5f° Breite / %.5f° Länge"), it.latitudeDegrees, it.longitudeDegrees) } ?: No5Text.ui("Letzte Position: unbekannt"), fontFamily = FontFamily.Monospace)
                No5CompactAction(if (target.watched) No5Text.ui("Beobachtung beenden") else No5Text.ui("Ziel beobachten"), { onEvent(AisEvent.ToggleWatch(target.mmsi)) }, Modifier.fillMaxWidth(), fullFace = true)
                No5CompactAction(No5Text.ui("Letzte Position auf Karte"), { target.position?.let { onEvent(AisEvent.ShowOnMap(target.mmsi, it)) } }, Modifier.fillMaxWidth(), enabled = target.position != null, fullFace = true)
            }
        }
    } else {
        Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text(No5Text.ui("AIS · %d / %d sichtbar",visible.size,state.targets.size), style = MaterialTheme.typography.headlineMedium)
            StatusNotice(state.status)
            OutlinedTextField(value = state.query, onValueChange = { onEvent(AisEvent.Search(it)) },
                label = { Text(No5Text.ui("Schiffsname oder MMSI")) }, singleLine = true,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("ais-search"))
            LazyRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                items(AisFilter.entries) { filter ->
                    No5Selection(filter.label, state.filter == filter, { onEvent(AisEvent.SetFilter(filter)) },
                        Modifier.testTag("ais-filter-${filter.name}"))
                }
            }
            if (visible.isEmpty()) Text(if (state.targets.isEmpty()) No5Text.ui("Keine AIS-Ziele verfügbar.") else No5Text.ui("Keine Ziele für diese Suche / diesen Filter."))
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                items(visible, key = { it.mmsi }) { row ->
                    No5DataRow(row.mmsi == state.selectedMmsi, { onEvent(AisEvent.Select(row.mmsi)) }, Modifier.testTag("ais-target-${row.mmsi}")) {
                        Text("${if (row.watched) "★ " else ""}${row.name?.takeIf { it.isNotBlank() } ?: "Name unbekannt"}",
                            style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("MMSI ${row.mmsi} · ${distance(row.rangeNauticalMiles, state)}", fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("CPA ${distance(row.cpaNauticalMiles, state)} · ${No5Text.ui(freshness(row.freshness))} · ${No5Text.ui(quality(row.quality))}",
                            style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}
