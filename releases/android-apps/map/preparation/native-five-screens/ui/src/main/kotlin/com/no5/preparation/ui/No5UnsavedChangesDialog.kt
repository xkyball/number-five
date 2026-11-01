package com.no5.preparation.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.no5.ui.No5Text

/** The confirmation never treats a second outside tap as permission to discard. */
@Composable fun No5UnsavedChangesDialog(
    onSave: () -> Unit,
    onDiscard: () -> Unit,
    onContinue: () -> Unit,
    saving: Boolean = false,
    saveEnabled: Boolean = true,
    error: String? = null,
) {
    com.no5.ui.No5DecisionDialog(
        title="Änderungen behalten?",
        body="Ungespeicherte Änderungen gehen beim Verwerfen verloren.",
        night=MaterialTheme.colorScheme.surface.red<.15f,
        font=MaterialTheme.typography.bodyLarge.fontFamily?:androidx.compose.ui.text.font.FontFamily.Default,
        onSave=onSave,onDiscard=onDiscard,onContinue=onContinue,
        busy=saving,canSave=saveEnabled,error=error,
        prefix="unsaved-changes",dialogTag="unsaved-changes-dialog",
    )
}
