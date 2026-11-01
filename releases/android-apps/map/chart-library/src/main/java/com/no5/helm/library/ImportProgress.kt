package com.no5.helm.library

enum class ImportPhase(val title: String) {
    OPENING("Öffnen"), READING("Einlesen"), EXTRACTING("Entpacken"),
    CHECKING("Prüfen"), INDEXING("Indexieren"), SAVING("Speichern")
}
enum class ImportActivity { RUNNING, CANCELLING, CANCELLED, FAILED, COMPLETED }

/** Counters refer to the current phase. Null means unmeasured, never an estimate. */
data class ImportProgress(
    val phase: ImportPhase = ImportPhase.OPENING,
    val detail: String = "ZIP-Datei wird geöffnet",
    val currentFile: String? = null,
    val processedFiles: Int? = null,
    val totalFiles: Int? = null,
    val processedBytes: Long? = null,
    val totalBytes: Long? = null,
    val currentFileBytes: Long? = null,
    val currentFileTotalBytes: Long? = null,
    val activity: ImportActivity = ImportActivity.RUNNING,
    val phaseStartedNanos: Long = System.nanoTime(),
    val error: String? = null
) {
    val canCancel: Boolean get() = activity == ImportActivity.RUNNING && phase != ImportPhase.SAVING
    // This is a byte fraction of one phase, never a percentage of the whole import.
    val byteFraction: Float? get() = if (totalBytes != null && totalBytes > 0 && processedBytes != null)
        (processedBytes.toDouble() / totalBytes).coerceIn(0.0, 1.0).toFloat() else null
}

/** At most five ordinary updates per second; phase/terminal transitions are immediate. */
internal class ImportProgressReporter(private val emit: (ImportProgress) -> Unit,
    private val nanoTime: () -> Long = System::nanoTime) {
    @Volatile var latest = ImportProgress(phaseStartedNanos = nanoTime())
        private set
    private var lastEmission = Long.MIN_VALUE
    private var lastValue: ImportProgress? = null
    @Synchronized fun report(value: ImportProgress, force: Boolean = false) {
        val now = nanoTime()
        val transition = value.phase != latest.phase || value.activity != latest.activity
        latest = value.copy(phaseStartedNanos = if (value.phase != latest.phase) now else latest.phaseStartedNanos)
        if (force || transition || lastEmission == Long.MIN_VALUE || now - lastEmission >= 200_000_000L) {
            lastEmission = now
            lastValue = latest
            emit(latest)
        }
    }
    // Publish a pending filename even if the next native call has no internal checkpoints.
    @Synchronized fun flushPending() {
        if (latest != lastValue) report(latest)
    }
    fun phase(value: ImportProgress) = report(value, force = true)
}
