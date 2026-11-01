package com.no5.helm.ui
import com.no5.ui.No5Text
import com.no5.ui.No5ActionFeedback
import com.no5.ui.no5Clickable
import android.content.SharedPreferences
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.no5.helm.chart.*
import com.no5.preparation.ui.No5CompactAction
import com.no5.preparation.ui.No5Selection
import com.no5.preparation.ui.No5KnobSafeDialog
import com.no5.preparation.ui.No5UnsavedChangesDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class SavedChartProfiles(private val prefs: SharedPreferences) {
    var profiles: PresentationProfiles = PresentationProfiles()
        private set
    var activeMode: PresentationMode = runCatching { PresentationMode.valueOf(prefs.getString("activeMode", "DAY")!!) }.getOrDefault(PresentationMode.DAY)
        private set
    init {
        PresentationMode.entries.forEach { mode ->
            val initial = profiles.forMode(mode)
            val candidate = runCatching {
                ChartPresentation(prefs.getString("${mode.name}.style", initial.styleId)!!,
                    ChartSymbolSet.valueOf(prefs.getString("${mode.name}.symbols", initial.symbols.name)!!),
                    prefs.getBoolean("${mode.name}.land", false)).also { it.validate(); require(it.mode == mode) }
            }.getOrDefault(initial)
            profiles = profiles.confirmed(candidate)
        }
    }
    suspend fun confirmed(value: ChartPresentation) {
        withContext(Dispatchers.IO) {
            check(prefs.edit().putString("${value.mode.name}.style", value.styleId)
                .putString("${value.mode.name}.symbols", value.symbols.name)
                .putBoolean("${value.mode.name}.land", value.landRelief)
                .putString("activeMode", value.mode.name).commit()) { "Kartenstil konnte nicht gespeichert werden" }
        }
        profiles = profiles.confirmed(value); activeMode = value.mode
    }
}
@Composable internal fun ChartStyleSettings(active: ChartPresentation, saved: PresentationProfiles, busy: Boolean,
    onDismiss: () -> Unit, onApply: (ChartPresentation) -> Unit,
    onApplyConfirmed: ((ChartPresentation, (Boolean) -> Unit) -> Unit)? = null,workspace:Boolean=false) {
    val candidateSaver = remember {
        listSaver<ChartPresentation, String>(
            save = { listOf(it.styleId, it.symbols.name, it.landRelief.toString()) },
            restore = { parts -> runCatching { ChartPresentation(parts[0], ChartSymbolSet.valueOf(parts[1]), parts[2].toBooleanStrict()).also { it.validate() } }.getOrNull() },
        )
    }
    var candidate by rememberSaveable(stateSaver = candidateSaver) { mutableStateOf(active) }
    val exitGate=LocalMapTaskExit.current
    val exitOwner=remember{Any()}
    fun finishClose(){if(exitGate!=null)exitGate.complete(exitOwner,onDismiss)else onDismiss()}
    var closeRequested by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var applySerial by remember { mutableLongStateOf(0L) }
    var saveError by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    var mounted by remember { mutableStateOf(true) }
    DisposableEffect(Unit) { onDispose { mounted = false } }
    val latestDismiss by rememberUpdatedState(::finishClose)
    val working = busy || saving
    fun requestClose() {
        if (busy || saving) return
        if (candidate == active) finishClose() else closeRequested = true
    }
    fun save(closeAfterSave: Boolean) {
        if (busy || saving) return
        if (candidate == active) { if (closeAfterSave) finishClose(); return }
        saveError = null
        if (onApplyConfirmed == null) {
            // The legacy embedding callback cannot establish durable success.
            // Dispatch it, but never dismiss a dirty editor on that basis.
            onApply(candidate)
            return
        }
        saving = true
        val serial = ++applySerial
        try {
            onApplyConfirmed(candidate) { success ->
                if (!mounted || serial != applySerial || !saving) return@onApplyConfirmed
                saving = false
                if (success) { closeRequested = false; if (closeAfterSave||exitGate?.isPending(exitOwner)==true) latestDismiss() }
                else saveError = com.no5.ui.No5Text.text(context, "Speichern fehlgeschlagen. Die Änderungen bleiben erhalten.")
            }
        } catch (_: RuntimeException) {
            saving = false
            saveError = com.no5.ui.No5Text.text(context, "Speichern fehlgeschlagen. Die Änderungen bleiben erhalten.")
        }
    }
    RegisterMapTaskExit(exitOwner,::requestClose)
    // The app's actual day/night chrome is inherited. Selecting a chart palette
    // is only a draft and must neither force day controls nor change the shell.
    val controls = MaterialTheme.colorScheme
    if(workspace)MapStyleWorkspace(candidate,active,working,saveError,{candidate=it},{candidate=saved.forMode(it)},::requestClose,{save(false)})
    else {
    No5KnobSafeDialog(onDismissRequest = ::requestClose) {
        Surface(Modifier.fillMaxSize()
            .testTag("chart-style-dialog"), shape = RoundedCornerShape(16.dp), color = controls.background) {
            Column(Modifier.fillMaxSize()) {
                ChartStyleHeader(active)
                FlowRow(Modifier.fillMaxWidth().selectableGroup().padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PresentationMode.entries.forEach { mode ->
                        No5Selection(No5Text.text(LocalContext.current, chartModeTitle(mode)), candidate.mode == mode,
                            { candidate = saved.forMode(mode) },
                            Modifier.testTag("chart-style-mode-${mode.name}"), enabled = !working)
                    }
                }
                Text(No5Text.text(LocalContext.current, "Tag, Rotnacht und Grau werden getrennt gespeichert."),
                    Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                    style = MaterialTheme.typography.bodySmall, color = controls.onSurfaceVariant)
                HorizontalDivider(color = controls.outlineVariant)
                BoxWithConstraints(Modifier.weight(1f).padding(16.dp)) {
                    val styles = ChartStyles.all.filter { it.mode == candidate.mode }
                    if (maxWidth >= 600.dp) {
                        val detailWidth = (maxWidth * .38f).coerceIn(220.dp, 290.dp)
                        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            ChartStyleGallery(styles, candidate, active, working, { candidate = candidate.withStyle(it) },
                                Modifier.weight(1f).fillMaxHeight())
                            ChartStyleDetail(candidate, working, { candidate = it },
                                Modifier.width(detailWidth).fillMaxHeight().verticalScroll(rememberScrollState()))
                        }
                    } else {
                        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(18.dp)) {
                            ChartStyleGallery(styles, candidate, active, working, { candidate = candidate.withStyle(it) },
                                Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 300.dp))
                            ChartStyleDetail(candidate, working, { candidate = it }, Modifier.fillMaxWidth())
                        }
                    }
                }
                HorizontalDivider(color = controls.outlineVariant)
                saveError?.let { Text(it, Modifier.padding(horizontal = 16.dp).testTag("chart-style-error"), color = controls.error) }
                ChartStyleFooter(candidate, busy || saving, ::requestClose, { save(false) })
            }
        }
    }
    }
    if (closeRequested) MapTaskDecision(
        saveLabel=m1("Anwenden","Apply","Pas toe"),onSave = { save(true) },
        onDiscard = { candidate = active; closeRequested = false; finishClose() },
        onContinue = { closeRequested = false; exitGate?.cancel(exitOwner) }, saving = busy || saving,
        error = saveError,
    )
}

@Composable private fun ChartStyleHeader(active: ChartPresentation) {
    val controls = MaterialTheme.colorScheme
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        val headerWidth = maxWidth
        val title: @Composable () -> Unit = {
            Column {
                Text(No5Text.text(LocalContext.current, "Kartenstil"), style = MaterialTheme.typography.headlineLarge,
                    modifier = Modifier.semantics { heading() })
                Text(No5Text.text(LocalContext.current, "Darstellung wählen, dann anwenden."), style = MaterialTheme.typography.bodyMedium,
                    color = controls.onSurfaceVariant)
            }
        }
        val current: @Composable (Modifier) -> Unit = { modifier ->
            Text(No5Text.text(LocalContext.current, "Aktuell: %s", No5Text.text(LocalContext.current, ChartStyles.require(active.styleId).title)),
                style = MaterialTheme.typography.labelMedium, color = controls.onSurfaceVariant,
                modifier = modifier.testTag("chart-style-active"), maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (headerWidth >= 600.dp) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(Modifier.weight(1f)) { title() }
            current(Modifier.widthIn(max = headerWidth * .4f))
        } else Column(verticalArrangement = Arrangement.spacedBy(4.dp)) { title(); current(Modifier.fillMaxWidth()) }
    }
}

/** Fixed actions stay in the safe dialog's footer; only the body can scroll. */
@Composable private fun ChartStyleFooter(candidate: ChartPresentation, busy: Boolean,
    onDismiss: () -> Unit, onApply: () -> Unit) {
    val controls = MaterialTheme.colorScheme
    BoxWithConstraints(Modifier.fillMaxWidth().background(controls.surface).padding(horizontal = 16.dp, vertical = 8.dp)
        .testTag("chart-style-footer")) {
        val selection: @Composable (Modifier) -> Unit = { modifier ->
            Text(if (busy) No5Text.text(LocalContext.current, "Darstellung wird bestätigt …") else No5Text.text(LocalContext.current, "Auswahl: %s", No5Text.text(LocalContext.current, ChartStyles.require(candidate.styleId).title)),
                modifier.testTag("chart-style-selection"), style = MaterialTheme.typography.bodyMedium,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        val actions: @Composable () -> Unit = {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                No5CompactAction(No5Text.text(LocalContext.current, "Schließen"), onDismiss, Modifier.testTag("chart-style-close"), enabled = !busy)
                No5CompactAction(No5Text.text(LocalContext.current, if (busy) "Wird angewendet …" else "Anwenden"), onApply,
                    Modifier.testTag("chart-style-apply"), enabled = !busy)
            }
        }
        if (maxWidth >= 520.dp) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            selection(Modifier.weight(1f)); actions()
        } else Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            selection(Modifier.fillMaxWidth())
            Box(Modifier.align(Alignment.End)) { actions() }
        }
    }
}

private fun chartModeTitle(mode: PresentationMode) = when (mode) {
    PresentationMode.DAY -> "Tag"
    PresentationMode.RED_NIGHT -> "Rotnacht"
    PresentationMode.GRAY_COMPARISON -> "Grauvergleich"
}

@Composable private fun ChartStyleGallery(styles: List<ChartStyle>, candidate: ChartPresentation,
    active: ChartPresentation, busy: Boolean, onSelect: (ChartStyle) -> Unit, modifier: Modifier) {
    LazyVerticalGrid(columns = GridCells.Adaptive(160.dp), modifier = modifier.selectableGroup().testTag("chart-style-gallery"),
        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(styles, key = { it.id }) { style ->
            val selected = candidate.styleId == style.id
            val colors = MaterialTheme.colorScheme
            Surface(Modifier.semantics { this.selected = selected }
                .no5Clickable(enabled = !busy, role = Role.RadioButton, onClick = { onSelect(style) })
                .testTag("chart-style-${style.id}"), shape = RoundedCornerShape(4.dp),
                border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) colors.primary else colors.outlineVariant),
                color = if (selected) colors.secondaryContainer else colors.surfaceContainerLowest,
                contentColor = if (selected) colors.onSecondaryContainer else colors.onSurface) {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    ChartStylePalettePreview(style.id, Modifier.fillMaxWidth().height(64.dp).clip(RoundedCornerShape(5.dp)))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(No5Text.text(LocalContext.current, style.title), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, maxLines = 2)
                        if (style.id == active.styleId) Text(No5Text.text(LocalContext.current, "Aktiv"), style = MaterialTheme.typography.labelSmall, color = colors.primary)
                    }
                }
            }
        }
    }
}

@Composable private fun ChartStyleDetail(candidate: ChartPresentation, busy: Boolean,
    onChange: (ChartPresentation) -> Unit, modifier: Modifier) {
    val style = ChartStyles.require(candidate.styleId)
    val colors = MaterialTheme.colorScheme
    Column(modifier.testTag("chart-style-detail"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(No5Text.text(LocalContext.current, style.title), style = MaterialTheme.typography.headlineLarge)
        ChartStylePalettePreview(style.id, Modifier.fillMaxWidth().height(112.dp).clip(RoundedCornerShape(8.dp)))
        Text(No5Text.text(LocalContext.current, "Schematische Farbvorschau"), style = MaterialTheme.typography.labelMedium)
        Text(No5Text.text(LocalContext.current, "Originale Stilfarben · keine echte Karte. Symbolformen und Reliefwirkung zeigt die angewendete Karte."),
            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        HorizontalDivider(color = colors.outlineVariant)
        Text(No5Text.text(LocalContext.current, "Tonnen und Toppzeichen"), style = MaterialTheme.typography.titleMedium)
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)) {
            style.symbolSets.forEach { symbols ->
                No5Selection(No5Text.text(LocalContext.current, symbols.title), symbols == candidate.symbols,
                    { onChange(candidate.copy(symbols = symbols)) },
                    Modifier.testTag("chart-style-symbol-${symbols.id}"), enabled = !busy)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            No5ActionFeedback(enabled = !busy && style.allowsLandRelief) { feedback ->
                Checkbox(checked = candidate.landRelief, enabled = feedback.enabled,
                    interactionSource = feedback.interactionSource,
                    onCheckedChange = { onChange(candidate.copy(landRelief = it)) },
                    modifier = feedback.modifier.testTag("chart-style-land-relief"))
            }
            Text(No5Text.text(LocalContext.current, "Zusätzliches ENC-Landrelief"), Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium)
        }
        Text(No5Text.text(LocalContext.current, if (style.id == "lagoon-sculpt") "S01 verwendet R04-Originalsymbole und kein Zusatzrelief. Die Schattierung stellt keine gemessene Unterwasserhöhe dar."
            else if (!style.allowsLandRelief) "Zusätzliches Landrelief ist für diesen Stil nicht verfügbar."
            else "Sculpt und Prism betonen Landkanten. Reliefhöhen und Schattierungen sind schematisch."),
            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
    }
}

/** Small deterministic Canvas: the same invented coast for every palette, no extra chart renderer. */
@Composable internal fun ChartStylePalettePreview(styleId: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val palette = ChartStylePreviewPalettes.require(styleId)
    val coast = remember { Path() }
    Canvas(modifier.semantics { contentDescription = No5Text.text(context, "Schematische Farbvorschau: %s", ChartStyles.require(styleId).title) }
        .testTag("chart-style-preview-$styleId")) {
        val w = size.width
        val h = size.height
        drawRect(Color(palette.deep))
        fun shore(x: Float, fill: Int) {
            coast.reset()
            coast.moveTo(0f, 0f)
            coast.lineTo(w * x, 0f)
            coast.cubicTo(w * (x - .04f), h * .2f, w * (x + .15f), h * .31f, w * (x + .02f), h * .51f)
            coast.cubicTo(w * (x - .16f), h * .7f, w * (x + .03f), h * .75f, w * (x - .06f), h)
            coast.lineTo(0f, h)
            coast.close()
            drawPath(coast, Color(fill))
        }
        shore(.69f, palette.middle)
        shore(.54f, palette.shallow)
        shore(.43f, palette.veryShallow)
        shore(.30f, palette.land)
        drawPath(coast, Color(palette.coast), style = Stroke(palette.coastWidth.coerceIn(1f, 3f) * density))
        val contour = Path().apply {
            moveTo(w * .72f, 0f)
            cubicTo(w * .62f, h * .37f, w * .9f, h * .35f, w * .73f, h * .67f)
            cubicTo(w * .57f, h * .85f, w * .74f, h * .9f, w * .65f, h)
        }
        drawPath(contour, Color(palette.contour), style = Stroke(.8f * density))
        drawCircle(Color(palette.ink), radius = 1.5f * density, center = Offset(w * .78f, h * .47f))
        drawCircle(Color(palette.ink), radius = 1.5f * density, center = Offset(w * .62f, h * .81f))
    }
}
