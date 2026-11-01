package com.no5.helm

import com.no5.helm.diagnostics.*
import org.junit.Test
import kotlin.test.*

class GatewayDiagnosticPresentationTest {
    private fun entry(attempt: Long, phase: GatewayDiagnosticPhase, outcome: GatewayDiagnosticOutcome,
        error: GatewayDiagnosticError = GatewayDiagnosticError.NONE, status: Int? = null) =
        GatewayDiagnosticEntry(1000, attempt, GatewayDiagnosticService.HELM, phase, outcome, 120, error, status)

    @Test fun retryNeverKeepsOldSuccessfulServiceResultsAndIgnoresUnrelatedEvents() {
        val old = entry(1, GatewayDiagnosticPhase.TEST_RESULT, GatewayDiagnosticOutcome.SUCCESS, status = 200)
        val pending = entry(2, GatewayDiagnosticPhase.TEST_START, GatewayDiagnosticOutcome.STARTED)
        val unrelated = entry(3, GatewayDiagnosticPhase.RELOAD_START, GatewayDiagnosticOutcome.SUCCESS)
        assertEquals(listOf(pending), latestGatewayTest(listOf(old, pending, unrelated)))
        assertTrue(latestGatewayTest(listOf(unrelated)).isEmpty())
    }

    @Test fun unavailableServiceIsDistinctFromAuthenticationResolutionAndInvalidData() {
        val unavailable = entry(1, GatewayDiagnosticPhase.TEST_RESULT, GatewayDiagnosticOutcome.FAILURE, GatewayDiagnosticError.HTTP_OTHER, 503)
        assertEquals("Gateway antwortet · Dienst derzeit nicht verfügbar", gatewayResultLabel(unavailable))
        assertEquals("HTTP 503 · 120 ms · HTTP_OTHER", gatewayResultDetail(unavailable))
        assertTrue(gatewayErrorExplanation(GatewayDiagnosticError.HTTP_401,401).contains("Zugangsschlüssel"))
        assertTrue(gatewayErrorExplanation(GatewayDiagnosticError.DNS,null).contains("aufgelöst"))
        assertTrue(gatewayErrorExplanation(GatewayDiagnosticError.TLS_HOSTNAME,null).contains("Hostnamen"))
        val invalid = unavailable.copy(error = GatewayDiagnosticError.SCHEMA, httpStatus = 200)
        assertTrue(gatewayResultDetail(invalid).startsWith("HTTP 200"))
        assertTrue(gatewayResultLabel(invalid).contains("Antwortformat"))
    }
}
