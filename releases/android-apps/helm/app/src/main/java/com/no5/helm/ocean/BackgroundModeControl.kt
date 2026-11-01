package com.no5.helm.ocean

import com.no5.ui.No5Text
import com.no5.ui.no5Clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Edits a SETTINGS draft only. Parent owns Apply, Cancel and persistence failure handling. */
@Composable
fun BackgroundModeControl(selected: BackgroundMode, effectiveDescription: String,
    onSelect: (BackgroundMode) -> Unit, enabled: Boolean = true, scale: Float = 1f,
    font: FontFamily = FontFamily.Default) {
    ProvideTextStyle(TextStyle(fontFamily = font, fontSize = (30f * scale).coerceAtLeast(14f).sp,
        color = MaterialTheme.colorScheme.onSurface)) {
        Column(verticalArrangement = Arrangement.spacedBy((10f * scale).coerceAtLeast(5f).dp)) {
            Text(com.no5.ui.No5Text.ui("HELM BACKGROUND"), fontWeight = FontWeight.ExtraBold,
                fontSize = (34f * scale).coerceAtLeast(16f).sp)
            Row(Modifier.fillMaxWidth().selectableGroup()) {
                BackgroundMode.entries.forEach { mode ->
                    Row(Modifier.weight(1f).heightIn(min = (90f * scale).coerceAtLeast(48f).dp)
                        .no5Clickable(enabled = enabled, role = Role.RadioButton,
                            onClick = { onSelect(mode) })
                        .testTag("background-mode-${mode.name}")
                        .semantics { this.selected = selected == mode; contentDescription = No5Text.ui("Background %s", No5Text.ui(mode.label)) }
                        .padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = selected == mode, onClick = null, enabled = enabled)
                        Text(No5Text.ui(mode.label))
                    }
                }
            }
            Text(No5Text.ui(effectiveDescription), modifier = Modifier.testTag("background-effective"))
            Text(com.no5.ui.No5Text.ui("Nachts immer aus. Animation: dekorative Landschaft, keine Navigation."))
        }
    }
}
