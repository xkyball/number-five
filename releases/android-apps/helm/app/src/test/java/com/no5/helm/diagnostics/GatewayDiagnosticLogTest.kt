package com.no5.helm.diagnostics

import org.json.JSONObject
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GatewayDiagnosticLogTest {
    @Test fun ringKeepsAtMostFiveHundredEntriesAndEvictsTheOldest() {
        var clock = 10L
        val log = GatewayDiagnosticLog(now = { clock++ })
        repeat(501) { index ->
            log.record(service = GatewayDiagnosticService.HELM, phase = GatewayDiagnosticPhase.TEST_RESULT,
                outcome = GatewayDiagnosticOutcome.SUCCESS, durationMs = index.toLong())
        }

        val snapshot = log.snapshot()
        assertEquals(500, snapshot.entries.size)
        assertEquals(1, snapshot.droppedEntries)
        assertEquals(2L, snapshot.entries.first().attemptId)
        assertEquals(501L, snapshot.entries.last().attemptId)
    }

    @Test fun byteBudgetEvictsOldEntriesBeforeItExceedsConfiguredBound() {
        val log = GatewayDiagnosticLog(now = { 1L }, maxEntries = 500, maxBytes = 900)
        repeat(20) {
            log.record(service = GatewayDiagnosticService.SONAR_WS, phase = GatewayDiagnosticPhase.TEST_RESULT,
                outcome = GatewayDiagnosticOutcome.FAILURE, durationMs = 123L,
                error = GatewayDiagnosticError.TLS_HANDSHAKE, httpStatus = 403,
                note = GatewayDiagnosticNote.READ_ONLY)
        }

        val json = log.export(GatewayDiagnosticExportFormat.JSON)
        assertTrue(json.size <= 900)
        assertTrue(log.snapshot().droppedEntries > 0)
    }

    @Test fun hugeUnicodeInputIsReplacedByFixedMarkerAndCannotEscapeToEitherExport() {
        val raw = "秘密🚤".repeat(10_000)
        val log = GatewayDiagnosticLog(now = { 1L })
        log.recordUntrustedInput(raw)

        val json = log.export(GatewayDiagnosticExportFormat.JSON).toString(Charsets.UTF_8)
        val csv = log.export(GatewayDiagnosticExportFormat.CSV).toString(Charsets.UTF_8)
        assertTrue(json.toByteArray(Charsets.UTF_8).size <= GatewayDiagnosticLog.MAX_BYTES)
        assertTrue(csv.toByteArray(Charsets.UTF_8).size <= GatewayDiagnosticLog.MAX_BYTES)
        assertFalse(json.contains(raw.take(16)))
        assertFalse(csv.contains(raw.take(16)))
        assertTrue(json.contains("REDACTED_INPUT"))
        assertTrue(csv.contains("REDACTED_INPUT"))
    }

    @Test fun repeatedJsonAndCsvExportsStayBoundedAndStructurallyValid() {
        var clock = 1L
        val log = GatewayDiagnosticLog(now = { clock++ })
        repeat(500) {
            log.record(service = GatewayDiagnosticService.AUDIO, phase = GatewayDiagnosticPhase.TEST_RESULT,
                outcome = GatewayDiagnosticOutcome.SUCCESS, durationMs = 1L,
                note = GatewayDiagnosticNote.READ_ONLY)
        }

        repeat(8) {
            val json = log.export(GatewayDiagnosticExportFormat.JSON)
            val csv = log.export(GatewayDiagnosticExportFormat.CSV)
            assertTrue(json.size <= GatewayDiagnosticLog.MAX_BYTES)
            assertTrue(csv.size <= GatewayDiagnosticLog.MAX_BYTES)
            val parsed = JSONObject(json.toString(Charsets.UTF_8))
            assertEquals("no5-gateway-diagnostics/v1", parsed.getString("format"))
            assertEquals(500, parsed.getJSONArray("entries").length())
            val lines = csv.toString(Charsets.UTF_8).lineSequence().toList()
            assertEquals("timestampEpochMs,attemptId,service,phase,outcome,durationMs,error,httpStatus,note", lines[3])
            assertEquals(505, lines.size) // Three metadata lines, header, 500 entries and terminal empty line.
        }
    }
}
