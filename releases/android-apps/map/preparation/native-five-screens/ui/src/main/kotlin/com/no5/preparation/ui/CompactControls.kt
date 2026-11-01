package com.no5.preparation.ui

import androidx.compose.foundation.Canvas
import com.no5.ui.No5Text
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import com.no5.ui.no5Clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** The outer bounds own every click, including the transparent space around the face. */
@Composable private fun CompactControl(label: String, onClick: () -> Unit, modifier: Modifier,
    enabled: Boolean, selected: Boolean?, role: Role, fullFace: Boolean = false) {
    val style = MaterialTheme.typography.labelLarge
    val localizedLabel = No5Text.ui(label)
    val labelWidth = rememberTextMeasurer().measure(localizedLabel, style = style, maxLines = 1, softWrap = false).size.width
    val faceWidth = with(LocalDensity.current) { labelWidth.toDp() + 20.dp }.coerceAtLeast(32.dp)
    val colors = MaterialTheme.colorScheme
    val chosen = selected == true
    val face = if (chosen) colors.secondaryContainer else colors.surfaceContainerLowest
    val ink = if (chosen) colors.onSecondaryContainer else colors.onSurface
    Box(modifier.heightIn(min = 48.dp).widthIn(min = 48.dp)
        .semantics(mergeDescendants = true) { if (selected != null) this.selected = selected }
        .no5Clickable(feedbackColor = colors.primary, enabled = enabled, role = role, onClick = onClick),
        contentAlignment = Alignment.Center) {
        Box((if (fullFace) Modifier.fillMaxWidth() else Modifier.width(faceWidth))
            .heightIn(min = 32.dp).testTag("compact-control-face")
            .graphicsLayer { alpha = if (enabled) 1f else .45f }
            .background(face, RoundedCornerShape(4.dp)).border(1.dp, colors.outline, RoundedCornerShape(4.dp))) {
            Text(localizedLabel, Modifier.align(Alignment.Center).fillMaxWidth().padding(horizontal = 9.dp, vertical = 7.dp), color = ink,
                style = style, textAlign = TextAlign.Center, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
            if (chosen) Canvas(Modifier.matchParentSize()) {
                drawRect(colors.primary, Offset(7.dp.toPx(), size.height - 2.dp.toPx()),
                    Size((size.width - 14.dp.toPx()).coerceAtLeast(0f), 2.dp.toPx()))
            }
        }
    }
}

@Composable fun No5CompactAction(label: String, onClick: () -> Unit, modifier: Modifier = Modifier,
    enabled: Boolean = true, fullFace: Boolean = false) =
    CompactControl(label, onClick, modifier, enabled, null, Role.Button, fullFace)

@Composable fun No5Selection(label: String, selected: Boolean, onClick: () -> Unit,
    modifier: Modifier = Modifier, enabled: Boolean = true) =
    CompactControl(label, onClick, modifier, enabled, selected, Role.RadioButton)

@Composable fun No5Tab(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) =
    CompactControl(label, onClick, modifier, true, selected, Role.Tab)

/** A data row remains a continuous, full-width target rather than a small action face. */
@Composable fun No5DataRow(selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit) {
    val colors = MaterialTheme.colorScheme
    androidx.compose.material3.Surface(modifier.fillMaxWidth().heightIn(min = 60.dp)
        .semantics(mergeDescendants = true) { this.selected = selected }
        .no5Clickable(feedbackColor = colors.primary, role = Role.Button, onClick = onClick),
        color = if (selected) colors.secondaryContainer else colors.surfaceContainerLowest,
        contentColor = if (selected) colors.onSecondaryContainer else colors.onSurface,
        shape = RoundedCornerShape(4.dp), border = androidx.compose.foundation.BorderStroke(1.dp, colors.outline)) {
        Box {
            Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 7.dp), content = content)
            if (selected) Box(Modifier.align(Alignment.CenterStart).width(2.dp).height(40.dp).background(colors.primary))
        }
    }
}
