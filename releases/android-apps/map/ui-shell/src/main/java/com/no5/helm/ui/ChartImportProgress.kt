package com.no5.helm.ui

import com.no5.ui.No5Text
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import com.no5.helm.library.*
import com.no5.preparation.ui.No5CompactAction
import kotlinx.coroutines.delay
import java.util.Locale

@Composable internal fun ChartImportProgress(progress: ImportProgress, busy: Boolean, cancel: () -> Unit) {
    var now by remember(progress.phaseStartedNanos) { mutableLongStateOf(System.nanoTime()) }
    LaunchedEffect(busy, progress.phaseStartedNanos) {
        while (busy) { now = System.nanoTime(); delay(1000) }
    }
    val cancelling = progress.activity == ImportActivity.CANCELLING
    val title = when (progress.activity) {
        ImportActivity.RUNNING -> No5Text.ui(progress.phase.title)
        ImportActivity.CANCELLING -> No5Text.ui("Abbruch angefordert")
        ImportActivity.CANCELLED -> No5Text.ui("Import abgebrochen")
        ImportActivity.FAILED -> No5Text.ui("Import fehlgeschlagen · %s", No5Text.ui(progress.phase.title))
        ImportActivity.COMPLETED -> No5Text.ui("Import abgeschlossen")
    }
    Column(Modifier.fillMaxWidth().testTag("chart-import-progress")) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("chart-import-phase"))
        if (busy) {
            val fraction = progress.byteFraction
            if (fraction != null && !cancelling) LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
            else LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(No5Text.ui("Diese Phase läuft seit %d s · Gesamtfortschritt unbekannt", ((now - progress.phaseStartedNanos).coerceAtLeast(0) / 1_000_000_000)))
        }
        Text(No5Text.ui(progress.detail))
        progress.currentFile?.let { Text(No5Text.ui("Datei: %s", it), maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("chart-import-file")) }
        progress.processedFiles?.let { done ->
            if (progress.phase == ImportPhase.INDEXING) {
                if (progress.currentFile != null && progress.totalFiles != null)
                    Text(No5Text.ui("Zelle %d / %d wird geprüft und indexiert", done + 1, progress.totalFiles))
                Text(No5Text.ui("%s Zellen abgeschlossen", count(done, progress.totalFiles)))
            } else Text(No5Text.ui("%s Dateien verarbeitet", count(done, progress.totalFiles)))
        }
        progress.processedBytes?.let { Text(No5Text.ui("%s verarbeitet · diese Phase", byteCount(it, progress.totalBytes))) }
        progress.currentFileBytes?.let { Text(No5Text.ui("Aktuelle Datei: %s", byteCount(it, progress.currentFileTotalBytes))) }
        if (busy && cancelling) Text(com.no5.ui.No5Text.ui("Warte auf die laufende Datei/Operation und die Bereinigung. Ein nativer Dateiaufruf kann erst nach seiner Rückkehr abgebrochen werden."))
        progress.error?.let { Text(No5Text.ui(it), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("chart-import-error")) }
        if (busy && progress.canCancel) No5CompactAction("Import abbrechen", cancel, Modifier.testTag("chart-import-cancel"))
    }
}

private fun count(value: Int, total: Int?) = if (total == null) "$value" else "$value / $total"
private fun byteCount(value: Long, total: Long?) = if (total == null) No5Text.ui("%s · Gesamtgröße unbekannt", bytes(value)) else "${bytes(value)} / ${bytes(total)}"
private fun bytes(value: Long): String = when {
    value < 1024 -> "$value B"
    value < 1024 * 1024 -> String.format(No5Text.locale(com.no5.ui.UiLanguage.current()), "%.1f KiB", value / 1024.0)
    else -> String.format(No5Text.locale(com.no5.ui.UiLanguage.current()), "%.1f MiB", value / (1024.0 * 1024))
}
