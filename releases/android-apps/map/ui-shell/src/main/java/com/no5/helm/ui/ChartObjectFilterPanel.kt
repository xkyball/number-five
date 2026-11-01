package com.no5.helm.ui

import com.no5.ui.No5Text
import com.no5.ui.No5ActionFeedback
import android.content.SharedPreferences
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.no5.helm.chart.*
import com.no5.preparation.ui.No5CompactAction
import com.no5.preparation.ui.No5Selection
import org.json.JSONArray
import org.json.JSONObject

internal class ObjectFilterPreferences(private val preferences: SharedPreferences) {
    fun read(): ChartObjectFilters? = preferences.getString("s52Filters", null)?.let { encoded ->
        val j = JSONObject(encoded); require(j.getInt("schema") == 1)
        val hidden = j.getJSONArray("hidden")
        ChartObjectFilters(S52DisplayCategory.valueOf(j.getString("category")),
            (0 until hidden.length()).map { hidden.getString(it) }.toSet(), j.getBoolean("soundings"),
            j.getBoolean("text"), j.getBoolean("important"), j.getBoolean("aton"), j.getBoolean("light"), j.getBoolean("meta"))
    }
    fun confirmed(value: ChartObjectFilters) {
        val j = JSONObject().put("schema", 1).put("category", value.category.name).put("hidden", JSONArray(value.hiddenClasses.sorted()))
            .put("soundings", value.soundings).put("text", value.text).put("important", value.importantTextOnly)
            .put("aton", value.atonText).put("light", value.lightText).put("meta", value.metaObjects)
        check(preferences.edit().putString("s52Filters", j.toString()).commit()) { "Filterpräferenz konnte nicht gespeichert werden" }
    }
}

// Codes are matched to the renderer's bundled dictionary before a group can be used.
internal val objectFilterGroups = linkedMapOf(
    "Tonnen / Baken" to setOf("BOYCAR", "BOYINB", "BOYISD", "BOYLAT", "BOYSAW", "BOYSPP", "BCNCAR", "BCNISD", "BCNLAT", "BCNSAW", "BCNSPP", "TOPMAR"),
    "Lichter" to setOf("LIGHTS", "LITFLT", "LITVES"),
    "Wracks / Hindernisse" to setOf("WRECKS", "OBSTRN", "UWTROC"),
    "Schutz- / Sperrgebiete" to setOf("RESARE", "MIPARE"),
    "Warn- / Übungsgebiete" to setOf("CTNARE", "EXEZNE", "MARCUL"),
    "Tiefenlinien" to setOf("DEPCNT"),
    "Land / Anlagen" to setOf("LNDARE", "BUAARE", "LNDRGN", "LNDMRK", "SLCONS"))

internal fun filterDictionary(csv: String): Map<String, String> = csv.lineSequence().drop(1).mapNotNull { line ->
    val fields = mutableListOf<String>(); val value = StringBuilder(); var quoted = false; var i = 0
    while (i < line.length) {
        val c = line[i++]
        if (c == '"') { if (quoted && i < line.length && line[i] == '"') { value.append('"'); i++ } else quoted = !quoted }
        else if (c == ',' && !quoted) { fields += value.toString(); value.clear() } else value.append(c)
    }
    fields += value.toString()
    fields.getOrNull(2)?.takeIf { it.length == 6 && it != "SOUNDG" }?.let { it to fields[1] }
}.toMap().toSortedMap()

private fun categoryLabel(category:S52DisplayCategory)=No5Text.ui(when(category){
    S52DisplayCategory.BASE->"Basis";S52DisplayCategory.STANDARD->"Standard"
    S52DisplayCategory.ALL->"Alle";S52DisplayCategory.CUSTOM->"Benutzerdefiniert"
})

@Composable internal fun ChartObjectFilterPanel(state: ChartObjectFilterState, dictionary: Map<String, String>,
    busy: Boolean, notice: String?, apply: (ChartObjectFilters) -> Unit) {
    if (!state.supported) return
    val value = state.effective
    var search by remember { mutableStateOf("") }
    HorizontalDivider()
    Text(No5Text.ui("Kartenobjekte · S52"), style = MaterialTheme.typography.titleLarge, modifier = Modifier.testTag("s52-filter-panel"))
    Text(No5Text.ui("Bestätigt: %s · %d ausgeblendete Klassen", categoryLabel(value.category), value.hiddenClasses.size))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        S52DisplayCategory.entries.forEach { category ->
            No5Selection(categoryLabel(category),
                value.category == category, { apply(value.copy(category = category)) }, Modifier.testTag("s52-category-${category.name}"), !busy)
        }
    }
    Text(com.no5.ui.No5Text.ui("Maßstabsgrenzen (SCAMIN), Datum und Kartenabdeckung bleiben wirksam. Basisobjekte und vom S52-Kern hochgestufte Gefahren bleiben auch bei ausgeblendeter Klasse sichtbar."))
    @Composable fun toggle(title: String, tag: String, checked: Boolean, change: (Boolean) -> ChartObjectFilters) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text(No5Text.ui(title), Modifier.weight(1f))
            No5ActionFeedback(enabled = !busy, shape = androidx.compose.foundation.shape.CircleShape) { feedback ->
                Switch(checked, { apply(change(it)) }, Modifier.testTag(tag).then(feedback.modifier),
                    enabled = feedback.enabled, interactionSource = feedback.interactionSource)
            }
        }
    }
    toggle(No5Text.ui("Tiefenzahlen"), "s52-soundings", value.soundings) { value.copy(soundings = it) }
    toggle(No5Text.ui("Beschriftungen"), "s52-text", value.text) { value.copy(text = it) }
    toggle(No5Text.ui("Nur wichtige Texte"), "s52-important", value.importantTextOnly) { value.copy(importantTextOnly = it) }
    toggle(No5Text.ui("Seezeichen-Texte"), "s52-aton", value.atonText) { value.copy(atonText = it) }
    toggle(No5Text.ui("Licht-Texte"), "s52-light", value.lightText) { value.copy(lightText = it) }
    if (value.category == S52DisplayCategory.CUSTOM) {
        objectFilterGroups.forEach { (title, codes) ->
            val known = codes.intersect(dictionary.keys)
            if (known.isNotEmpty()) {
                val shown = known.count { it !in value.hiddenClasses }
                No5CompactAction(No5Text.ui("%s · %d/%d · %s", No5Text.ui(title), shown, known.size, No5Text.ui(if (shown == known.size) "Ausblenden" else "Einblenden")),
                    { apply(value.copy(hiddenClasses = if (shown == known.size) value.hiddenClasses + known else value.hiddenClasses - known)) },
                    Modifier.fillMaxWidth().testTag("s52-group-${known.sorted().first()}"), !busy)
            }
        }
        OutlinedTextField(search, { search = it }, Modifier.fillMaxWidth().testTag("s52-search"), label = { Text(No5Text.ui("Klasse suchen · Name oder Kürzel")) }, singleLine = true)
        if (search.isNotBlank()) dictionary.filter { (code, name) -> code.contains(search, true) || name.contains(search, true) }.forEach { (code, name) ->
            toggle("$code · $name", "s52-class-$code", code !in value.hiddenClasses) {
                value.copy(hiddenClasses = if (it) value.hiddenClasses - code else value.hiddenClasses + code)
            }
        }
    }
    No5CompactAction(No5Text.ui("Auslieferungszustand wiederherstellen"), { apply(ChartObjectFilters()) }, Modifier.testTag("s52-reset"), !busy)
    notice?.let { Text(No5Text.ui(it), Modifier.testTag("s52-filter-notice")) }
}
