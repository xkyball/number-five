package com.no5.preparation.ui

import androidx.compose.foundation.layout.*
import com.no5.ui.No5Text
import com.no5.ui.No5ActionFeedback
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/** Copies input; callers must use immutable element types. No mutation surface is exposed. */
@Immutable
class FrozenList<out T> private constructor(private val items: List<T>) : List<T> by items {
    companion object { fun <T> copyOf(items: Iterable<T>): FrozenList<T> = FrozenList(java.util.Collections.unmodifiableList(items.toList())) }
    override fun equals(other: Any?) = other is List<*> && items == other
    override fun hashCode() = items.hashCode()
}
fun <T> frozenListOf(vararg items: T) = FrozenList.copyOf(items.asIterable())

@Composable fun No5Action(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val shape = RoundedCornerShape(8.dp)
    No5ActionFeedback(enabled = enabled, shape = shape) { feedback ->
        Button(onClick = onClick, enabled = feedback.enabled, shape = shape,
            interactionSource = feedback.interactionSource,
            modifier = modifier.heightIn(min = 56.dp).then(feedback.modifier)) { Text(No5Text.ui(label)) }
    }
}
/** Fixed title/actions, with one bounded body, inside the calibrated knob corridor.
 * A caller that already owns a scroll container sets bodyScrollable=false so
 * that container receives a finite height instead of nesting unbounded scrolls.
 */
@Composable fun No5PanelDialog(onDismissRequest: () -> Unit, confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier, dismissButton: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null, text: (@Composable () -> Unit)? = null,
    bodyScrollable: Boolean = true) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(9.dp)
    var visiblePanel by remember { mutableStateOf<Rect?>(null) }
    No5KnobSafeDialog(onDismissRequest = onDismissRequest, outsideTapBounds = visiblePanel) {
        BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Surface(modifier.widthIn(max = 720.dp).fillMaxWidth().heightIn(max = maxHeight)
                .onGloballyPositioned { visiblePanel = it.boundsInRoot() },
                shape = shape, border = BorderStroke(1.dp, colors.outline),
                color = colors.surface, contentColor = colors.onSurface, tonalElevation = 0.dp) {
                Column(Modifier.fillMaxWidth()) {
                    title?.let {
                        Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp)) {
                            ProvideTextStyle(MaterialTheme.typography.headlineSmall, content = it)
                        }
                        HorizontalDivider(color = colors.outlineVariant)
                    }
                    text?.let {
                        val body = Modifier.fillMaxWidth().weight(1f, fill = false)
                        Box((if (bodyScrollable) body.verticalScroll(rememberScrollState()) else body)
                            .padding(horizontal = 20.dp, vertical = 16.dp)) {
                            ProvideTextStyle(MaterialTheme.typography.bodyMedium, content = it)
                        }
                    }
                    HorizontalDivider(color = colors.outlineVariant)
                    FlowRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                        verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        dismissButton?.invoke()
                        confirmButton()
                    }
                }
            }
        }
    }
}
/** A full D03 workspace already owns its heading and background. */
val LocalNo5FlatPanel = staticCompositionLocalOf { false }
@Composable fun No5Panel(title: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val scroll = rememberScrollState()
    val thumb = MaterialTheme.colorScheme.primary
    if(LocalNo5FlatPanel.current){
        Column(modifier.verticalScroll(scroll),verticalArrangement=Arrangement.spacedBy(12.dp),content=content)
        return
    }
    Surface(modifier, tonalElevation = 0.dp) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Text(No5Text.ui(title), style = MaterialTheme.typography.headlineMedium)
                HorizontalDivider()
                content()
            }
            if (scroll.maxValue > 0) Canvas(Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(5.dp).padding(vertical = 8.dp)) {
                val visibleFraction = size.height / (size.height + scroll.maxValue)
                val thumbHeight = (size.height * visibleFraction).coerceAtLeast(24.dp.toPx())
                val top = (size.height - thumbHeight) * scroll.value / scroll.maxValue.toFloat()
                drawRect(thumb.copy(alpha = .7f), topLeft = Offset(0f, top), size = Size(size.width, thumbHeight))
            }
        }
    }
}
@Composable fun No5Metric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(No5Text.ui(label), style = MaterialTheme.typography.labelMedium)
        Text(value, style = MaterialTheme.typography.headlineSmall, fontFamily = FontFamily.Monospace, maxLines = 1)
    }
}

enum class Availability { LOADING, READY, EMPTY, OFFLINE, STALE, ERROR }
@Immutable data class DataStatus(val availability: Availability = Availability.EMPTY, val detail: String = "Keine Daten")
@Composable fun StatusNotice(status: DataStatus) {
    Text("${No5Text.ui(status.availability.name)} · ${No5Text.ui(status.detail)}", color = MaterialTheme.colorScheme.primary)
}
