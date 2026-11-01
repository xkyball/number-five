package com.no5.helm.ui

import com.no5.ui.No5Text
import com.no5.ui.No5ActionFeedback
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.no5.helm.library.ChartSelectionExtent
import com.no5.preparation.ui.No5CompactAction
import kotlin.math.abs
import kotlin.math.max

internal fun chartRegionLabel(region: ChartRegion): String = region.key?.let { key ->
    fun latitude(value: Int) = "${abs(value)}° ${if (value < 0) "S" else "N"}"
    fun longitude(value: Int) = "${abs(value)}° ${if (value < 0) "W" else "E"}"
    "${latitude(key.latitude)} · ${longitude(key.longitude)}"
} ?: No5Text.ui("Ohne räumliche Zuordnung")

@Composable internal fun ChartRegionGrid(regions: List<ChartRegion>, selected: Set<String>, openId: String?, enabled: Boolean,
    onOpen: (ChartRegion) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = if (maxWidth >= 720.dp) 3 else if (maxWidth >= 420.dp) 2 else 1
        val tileWidth = (maxWidth - 8.dp * (columns - 1)) / columns
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            regions.forEach { region ->
                val colors = MaterialTheme.colorScheme
                val count = region.selectable.count { it in selected }
                No5ActionFeedback(enabled = enabled, shape = RoundedCornerShape(4.dp)) { feedback ->
                    Surface(onClick = { onOpen(region) }, enabled = feedback.enabled,
                        interactionSource = feedback.interactionSource,
                        modifier = Modifier.width(tileWidth).heightIn(min = 88.dp)
                            .testTag("chart-zone-${region.id}").then(feedback.modifier), shape = RoundedCornerShape(4.dp),
                        color = colors.surfaceContainerLowest,
                        border = BorderStroke(if (openId == region.id) 2.dp else 1.dp, if (openId == region.id) colors.primary else colors.outline)) {
                        Row(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            ChartRegionFootprint(region, selected, Modifier.size(72.dp))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(chartRegionLabel(region), style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(No5Text.ui("%d Zellen · %d gewählt", region.cells.size, count), style = MaterialTheme.typography.bodySmall)
                                Text(if (openId == region.id) com.no5.ui.No5Text.ui("Details geöffnet") else com.no5.ui.No5Text.ui("Zone öffnen"), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun ChartRegionFootprint(region: ChartRegion, selected: Set<String>, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    Canvas(modifier.semantics { contentDescription = if (region.key == null) "Keine räumlichen Koordinaten verfügbar" else
        "1-Grad-Zone ${chartRegionLabel(region)}, Zellumrisse und ausgewählte Zellen" }) {
        drawRect(colors.surfaceVariant)
        for (fraction in listOf(.25f, .5f, .75f)) {
            drawLine(colors.outlineVariant.copy(alpha = .5f), Offset(size.width * fraction, 0f), Offset(size.width * fraction, size.height))
            drawLine(colors.outlineVariant.copy(alpha = .5f), Offset(0f, size.height * fraction), Offset(size.width, size.height * fraction))
        }
        val key = region.key
        if (key == null) {
            drawLine(colors.outline, Offset(size.width * .35f, size.height * .35f), Offset(size.width * .65f, size.height * .65f), 2.dp.toPx())
            drawLine(colors.outline, Offset(size.width * .65f, size.height * .35f), Offset(size.width * .35f, size.height * .65f), 2.dp.toPx())
        } else clipRect {
            // Fixed per-zone view; remote islands are never joined into a giant bounding rectangle.
            region.cells.sortedByDescending { it.selectionExtent?.longitudeSpan }.forEach { cell ->
                val extent = cell.selectionExtent ?: return@forEach
                val relativeWest = ChartSelectionExtent.normalizeLongitude(extent.west - (key.longitude + .5)) + .5
                val left = (relativeWest * size.width).toFloat()
                val right = ((relativeWest + extent.longitudeSpan) * size.width).toFloat()
                val top = ((key.latitude + 1.0 - extent.north) * size.height).toFloat()
                val bottom = ((key.latitude + 1.0 - extent.south) * size.height).toFloat()
                val rect = Size(max(2.dp.toPx(), right - left), max(2.dp.toPx(), bottom - top))
                val chosen = cell.name in selected
                drawRect((if (chosen) colors.primary else colors.secondary).copy(alpha = if (chosen) .32f else .12f), Offset(left, top), rect)
                drawRect(if (chosen) colors.primary else colors.onSurfaceVariant.copy(alpha = .6f), Offset(left, top), rect, style = Stroke(1.dp.toPx()))
            }
        }
        drawRect(colors.outline, style = Stroke(1.dp.toPx()))
    }
}

@Composable internal fun RegionPageControls(page: Int, count: Int, enabled: Boolean, onPage: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        No5CompactAction(No5Text.ui("Zurück"), { onPage(page - 1) }, enabled = enabled && page > 0)
        Text("${page + 1}/$count", style = MaterialTheme.typography.bodySmall)
        No5CompactAction(No5Text.ui("Weiter"), { onPage(page + 1) }, enabled = enabled && page + 1 < count)
    }
}

@Composable internal fun ChartRegionDetails(region: ChartRegion, selected: Set<String>, enabled: Boolean, selectionLimit: Int,
    onToggle: (String) -> Unit) {
    var page by rememberSaveable(region.id) { mutableIntStateOf(0) }
    val pageCount = (region.cells.size + 11) / 12
    val currentPage = page.coerceIn(0, pageCount - 1)
    HorizontalDivider()
    Text(chartRegionLabel(region), style = MaterialTheme.typography.titleMedium)
    if (region.key == null) Text(com.no5.ui.No5Text.ui("Für diese Zellen fehlen lokale Geometrie-Koordinaten. Kennungen bleiben einzeln auswählbar; keine Nachbarschaft wird geraten."), style = MaterialTheme.typography.bodySmall)
    else Text(No5Text.ui("1°-Rasterfeld ab %s · Vorschau auf diesen Ausschnitt begrenzt.", chartRegionLabel(region)), style = MaterialTheme.typography.bodySmall)
    if ((selected + region.selectable).size > selectionLimit) Text(No5Text.ui("Diese ganze Zone überschreitet %d Zellen. Einzelne Zellen wählen.", selectionLimit), style = MaterialTheme.typography.bodySmall)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = if (maxWidth >= 640.dp) 3 else if (maxWidth >= 400.dp) 2 else 1
        val tileWidth = (maxWidth - 8.dp * (columns - 1)) / columns
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            region.cells.drop(currentPage * 12).take(12).forEach { cell ->
                Surface(Modifier.width(tileWidth), color = MaterialTheme.colorScheme.surfaceContainerLowest,
                    shape = RoundedCornerShape(4.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                    Row(Modifier.padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        No5ActionFeedback(enabled = enabled && !cell.cancelled && (cell.name in selected || selected.size < selectionLimit),
                            shape = androidx.compose.foundation.shape.CircleShape) { feedback ->
                            Checkbox(cell.name in selected, { onToggle(cell.name) },
                                Modifier.testTag("chart-cell-${cell.name}").then(feedback.modifier),
                                enabled = feedback.enabled, interactionSource = feedback.interactionSource)
                        }
                        Column(Modifier.weight(1f).padding(vertical = 5.dp)) {
                            Text(cell.name, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                            Text(if (cell.cancelled) com.no5.ui.No5Text.ui("Abgekündigt") else No5Text.ui("Ed. %d · Update %d", cell.edition, cell.update), style = MaterialTheme.typography.bodySmall)
                            if (!cell.cancelled) Text(No5Text.ui("Ausgabe %s", cell.issueDate), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
    if (pageCount > 1) RegionPageControls(currentPage, pageCount, enabled) { page = it }
}
