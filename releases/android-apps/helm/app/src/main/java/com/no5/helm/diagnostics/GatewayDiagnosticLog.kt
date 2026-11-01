package com.no5.helm.diagnostics

import java.nio.charset.StandardCharsets
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicLong

/**
 * Bounded, memory-only connection evidence. Entries intentionally contain only allowlisted
 * enum values and numeric metadata; URLs, headers, response bodies, credentials, certificate
 * bytes and user data cannot enter this schema or an exported file.
 */
enum class GatewayDiagnosticService { SECURITY, SESSION, HELM, WEATHER, AUDIO, SONAR_HTTP, SONAR_WS }
enum class GatewayDiagnosticPhase { INSPECT, RELOAD_STOP, RELOAD_START, TEST_START, TEST_RESULT, IMPORT }
enum class GatewayDiagnosticOutcome { STARTED, SUCCESS, FAILURE, SKIPPED }
enum class GatewayDiagnosticError {
    NONE, DRAFT_NOT_SAVED, PROFILE_MISSING, KEY_MISSING, CA_MISSING, PROFILE_INVALID,
    DNS, MDNS_UNAVAILABLE, MDNS_SERVICE, MDNS_HOST, MDNS_PORT, MDNS_TIMEOUT, CONNECT, TIMEOUT, TLS_HOSTNAME, TLS_CA, TLS_HANDSHAKE, HTTP_401, HTTP_403,
    HTTP_OTHER, SCHEMA, WEBSOCKET, SOURCE_UNAVAILABLE, CANCELLED, INTERNAL, REDACTED_INPUT,
}
enum class GatewayDiagnosticNote { NONE, MATERIAL_READY, MATERIAL_INCOMPLETE, READ_ONLY, ORDERED_RELOAD, REDACTED }
enum class GatewayDiagnosticExportFormat { JSON, CSV }
enum class GatewayDiagnosticFilter { ALL, FAILURES, TESTS, RELOADS }

data class GatewayDiagnosticEntry(
    val timestampEpochMs: Long,
    val attemptId: Long,
    val service: GatewayDiagnosticService,
    val phase: GatewayDiagnosticPhase,
    val outcome: GatewayDiagnosticOutcome,
    val durationMs: Long? = null,
    val error: GatewayDiagnosticError = GatewayDiagnosticError.NONE,
    val httpStatus: Int? = null,
    val note: GatewayDiagnosticNote = GatewayDiagnosticNote.NONE,
) {
    init {
        require(timestampEpochMs >= 0)
        require(attemptId > 0)
        require(durationMs == null || durationMs >= 0)
        require(httpStatus == null || httpStatus in 100..599)
    }
}

data class GatewayDiagnosticSnapshot(
    val entries: List<GatewayDiagnosticEntry>,
    val droppedEntries: Long,
    val lastSuccess: GatewayDiagnosticEntry?,
    val lastFailure: GatewayDiagnosticEntry?,
)

/** Each serialization path enforces both the entry and complete UTF-8 byte budgets. */
class GatewayDiagnosticLog(
    private val now: () -> Long = System::currentTimeMillis,
    private val maxEntries: Int = MAX_ENTRIES,
    private val maxBytes: Int = MAX_BYTES,
) {
    private val lock = Any()
    private val sequence = AtomicLong(0)
    private val entries = ArrayDeque<GatewayDiagnosticEntry>()
    private var storedBytes = 0
    private var dropped = 0L

    fun newAttemptId(): Long = sequence.incrementAndGet()

    fun record(
        attemptId: Long = newAttemptId(),
        service: GatewayDiagnosticService,
        phase: GatewayDiagnosticPhase,
        outcome: GatewayDiagnosticOutcome,
        durationMs: Long? = null,
        error: GatewayDiagnosticError = GatewayDiagnosticError.NONE,
        httpStatus: Int? = null,
        note: GatewayDiagnosticNote = GatewayDiagnosticNote.NONE,
    ): GatewayDiagnosticEntry {
        val entry = GatewayDiagnosticEntry(now(), attemptId, service, phase, outcome, durationMs, error, httpStatus, note)
        val bytes = encodedEntry(entry).toByteArray(StandardCharsets.UTF_8).size
        // The typed allowlist makes this unreachable in production, but retain the hard guard
        // so future fields cannot turn the in-memory journal into an unbounded sink.
        val stored = if (bytes <= MAX_ENTRY_BYTES && encodedEntry(entry).length <= MAX_ENTRY_CHARS) entry else
            entry.copy(outcome = GatewayDiagnosticOutcome.FAILURE, error = GatewayDiagnosticError.REDACTED_INPUT,
                note = GatewayDiagnosticNote.REDACTED, durationMs = null, httpStatus = null)
        val storedSize = encodedEntry(stored).toByteArray(StandardCharsets.UTF_8).size
        synchronized(lock) {
            while (entries.isNotEmpty() && (entries.size >= maxEntries || storedBytes + storedSize > maxBytes)) {
                storedBytes -= encodedEntry(entries.removeFirst()).toByteArray(StandardCharsets.UTF_8).size
                dropped++
            }
            // A malformed future schema cannot fit even after eviction; retain a clear bounded
            // marker rather than writing a partial record.
            if (storedSize > maxBytes) {
                dropped++
                return stored.copy(error = GatewayDiagnosticError.REDACTED_INPUT, note = GatewayDiagnosticNote.REDACTED)
            }
            entries.addLast(stored)
            storedBytes += storedSize
        }
        return stored
    }

    /** Untrusted text is never retained; callers can only observe the fixed REDACTED marker. */
    fun recordUntrustedInput(
        @Suppress("UNUSED_PARAMETER") input: CharSequence,
        attemptId: Long = newAttemptId(),
    ): GatewayDiagnosticEntry = record(
        attemptId = attemptId,
        service = GatewayDiagnosticService.SECURITY,
        phase = GatewayDiagnosticPhase.INSPECT,
        outcome = GatewayDiagnosticOutcome.FAILURE,
        error = GatewayDiagnosticError.REDACTED_INPUT,
        note = GatewayDiagnosticNote.REDACTED,
    )

    fun snapshot(filter: GatewayDiagnosticFilter = GatewayDiagnosticFilter.ALL): GatewayDiagnosticSnapshot = synchronized(lock) {
        val selected = entries.filter { entry ->
            when (filter) {
                GatewayDiagnosticFilter.ALL -> true
                GatewayDiagnosticFilter.FAILURES -> entry.outcome == GatewayDiagnosticOutcome.FAILURE
                GatewayDiagnosticFilter.TESTS -> entry.phase == GatewayDiagnosticPhase.TEST_START || entry.phase == GatewayDiagnosticPhase.TEST_RESULT
                GatewayDiagnosticFilter.RELOADS -> entry.phase == GatewayDiagnosticPhase.RELOAD_STOP || entry.phase == GatewayDiagnosticPhase.RELOAD_START
            }
        }
        GatewayDiagnosticSnapshot(
            entries = selected,
            droppedEntries = dropped,
            lastSuccess = entries.lastOrNull { it.outcome == GatewayDiagnosticOutcome.SUCCESS },
            lastFailure = entries.lastOrNull { it.outcome == GatewayDiagnosticOutcome.FAILURE },
        )
    }

    fun clear() = synchronized(lock) { entries.clear(); storedBytes = 0; dropped = 0 }

    fun export(format: GatewayDiagnosticExportFormat): ByteArray {
        val snapshot = snapshot()
        val rendered = when (format) {
            GatewayDiagnosticExportFormat.JSON -> renderJson(snapshot)
            GatewayDiagnosticExportFormat.CSV -> renderCsv(snapshot)
        }
        val bytes = rendered.toByteArray(StandardCharsets.UTF_8)
        check(bytes.size <= maxBytes) { "Diagnostic export exceeds its bounded byte budget" }
        return bytes
    }

    private fun renderJson(snapshot: GatewayDiagnosticSnapshot): String = boundedRender(snapshot) { entries, omitted ->
        buildString {
            append("{\"format\":\"no5-gateway-diagnostics/v1\",\"truncated\":")
            append(snapshot.droppedEntries + omitted > 0)
            append(",\"omittedEntries\":").append(snapshot.droppedEntries + omitted)
            append(",\"entries\":[")
            entries.forEachIndexed { index, entry -> if (index > 0) append(','); append(encodedEntry(entry)) }
            append("]}")
        }
    }

    private fun renderCsv(snapshot: GatewayDiagnosticSnapshot): String = boundedRender(snapshot) { entries, omitted ->
        buildString {
            append("# format=no5-gateway-diagnostics/v1\n")
            append("# truncated=").append(snapshot.droppedEntries + omitted > 0).append('\n')
            append("# omittedEntries=").append(snapshot.droppedEntries + omitted).append('\n')
            append("timestampEpochMs,attemptId,service,phase,outcome,durationMs,error,httpStatus,note\n")
            entries.forEach { entry -> append(csvEntry(entry)).append('\n') }
        }
    }

    private fun boundedRender(snapshot: GatewayDiagnosticSnapshot, render: (List<GatewayDiagnosticEntry>, Int) -> String): String {
        val selected = snapshot.entries.toMutableList()
        var omitted = 0
        while (true) {
            val rendered = render(selected, omitted)
            if (rendered.toByteArray(StandardCharsets.UTF_8).size <= maxBytes) return rendered
            check(selected.isNotEmpty()) { "Diagnostic export metadata exceeds the byte budget" }
            selected.removeAt(0)
            omitted++
        }
    }

    private fun encodedEntry(entry: GatewayDiagnosticEntry): String = buildString {
        append('{')
        append("\"timestampEpochMs\":").append(entry.timestampEpochMs).append(',')
        append("\"attemptId\":").append(entry.attemptId).append(',')
        append("\"service\":\"").append(entry.service.name).append("\",")
        append("\"phase\":\"").append(entry.phase.name).append("\",")
        append("\"outcome\":\"").append(entry.outcome.name).append("\",")
        append("\"durationMs\":").append(entry.durationMs ?: "null").append(',')
        append("\"error\":\"").append(entry.error.name).append("\",")
        append("\"httpStatus\":").append(entry.httpStatus ?: "null").append(',')
        append("\"note\":\"").append(entry.note.name).append("\"}")
    }

    private fun csvEntry(entry: GatewayDiagnosticEntry): String = listOf(
        entry.timestampEpochMs, entry.attemptId, entry.service.name, entry.phase.name, entry.outcome.name,
        entry.durationMs ?: "", entry.error.name, entry.httpStatus ?: "", entry.note.name,
    ).joinToString(",")

    companion object {
        const val MAX_ENTRIES = 500
        const val MAX_BYTES = 256 * 1024
        const val MAX_ENTRY_CHARS = 512
        const val MAX_ENTRY_BYTES = 512
    }
}
