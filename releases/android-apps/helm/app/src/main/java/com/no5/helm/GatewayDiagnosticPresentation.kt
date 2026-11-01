package com.no5.helm

import com.no5.helm.diagnostics.*

internal val gatewayTestServices = listOf(GatewayDiagnosticService.HELM, GatewayDiagnosticService.WEATHER,
    GatewayDiagnosticService.AUDIO, GatewayDiagnosticService.SONAR_HTTP, GatewayDiagnosticService.SONAR_WS)

/** A pending retry must never display a successful result from a previous attempt. */
internal fun latestGatewayTest(entries: List<GatewayDiagnosticEntry>): List<GatewayDiagnosticEntry> {
    val tests = entries.filter { it.phase == GatewayDiagnosticPhase.TEST_START || it.phase == GatewayDiagnosticPhase.TEST_RESULT }
    val attempt = tests.lastOrNull()?.attemptId ?: return emptyList()
    return tests.filter { it.attemptId == attempt }
}

internal fun gatewayServiceLabel(service: GatewayDiagnosticService): String = when(service) {
    GatewayDiagnosticService.SECURITY -> "Zugang"
    GatewayDiagnosticService.SESSION -> "Sitzung"
    GatewayDiagnosticService.HELM -> "HELM"
    GatewayDiagnosticService.WEATHER -> "WETTER"
    GatewayDiagnosticService.AUDIO -> "AUDIO"
    GatewayDiagnosticService.SONAR_HTTP -> "SONAR · HTTP"
    GatewayDiagnosticService.SONAR_WS -> "SONAR · STREAM"
}

internal fun gatewayServicePath(service: GatewayDiagnosticService): String = when(service) {
    GatewayDiagnosticService.HELM -> "/v1/helm"
    GatewayDiagnosticService.WEATHER -> "/v1/weather"
    GatewayDiagnosticService.AUDIO -> "/v1/audio"
    GatewayDiagnosticService.SONAR_HTTP -> "/v1/sonar"
    GatewayDiagnosticService.SONAR_WS -> "/v1/sonar/stream"
    else -> "—"
}

internal fun gatewayResultLabel(entry: GatewayDiagnosticEntry): String = when(entry.outcome) {
    GatewayDiagnosticOutcome.STARTED -> "Wird geprüft …"
    GatewayDiagnosticOutcome.SUCCESS -> when(entry.service) {
        GatewayDiagnosticService.SECURITY -> "OK · Zugang geladen"
        GatewayDiagnosticService.SESSION -> "OK · Sitzung übernommen"
        GatewayDiagnosticService.SONAR_WS -> "OK · Handshake bestätigt"
        else -> "OK · Antwort gültig"
    }
    GatewayDiagnosticOutcome.SKIPPED -> "Nicht ausgeführt"
    GatewayDiagnosticOutcome.FAILURE -> gatewayErrorExplanation(entry.error, entry.httpStatus)
}

internal fun gatewayResultDetail(entry: GatewayDiagnosticEntry): String = listOfNotNull(
    entry.httpStatus?.let { "HTTP $it" }, entry.durationMs?.let { "$it ms" },
    entry.error.takeIf { it != GatewayDiagnosticError.NONE }?.name,
).joinToString(" · ").ifEmpty { "—" }

internal fun gatewayErrorExplanation(error: GatewayDiagnosticError, status: Int?): String = when(error) {
    GatewayDiagnosticError.NONE -> "Kein Fehler"
    GatewayDiagnosticError.DRAFT_NOT_SAVED -> "Home-Zugang stimmt nicht mit der aktiven Verbindung überein"
    GatewayDiagnosticError.PROFILE_MISSING -> "Gateway-Zugang in Home fehlt"
    GatewayDiagnosticError.KEY_MISSING -> "Zugangsschlüssel in Home fehlt"
    GatewayDiagnosticError.CA_MISSING -> "CA-Zertifikat in Home fehlt"
    GatewayDiagnosticError.PROFILE_INVALID -> "Gateway-Zugang ist ungültig"
    GatewayDiagnosticError.DNS -> "Hostname konnte nicht aufgelöst werden"
    GatewayDiagnosticError.MDNS_UNAVAILABLE -> "mDNS ist nicht verfügbar"
    GatewayDiagnosticError.MDNS_SERVICE -> "Kein passender mDNS-Dienst gefunden"
    GatewayDiagnosticError.MDNS_HOST -> "mDNS-Hostname stimmt nicht überein"
    GatewayDiagnosticError.MDNS_PORT -> "mDNS-Port stimmt nicht überein"
    GatewayDiagnosticError.MDNS_TIMEOUT -> "mDNS-Suche hat zu lange gedauert"
    GatewayDiagnosticError.CONNECT -> "Gateway-Verbindung wurde abgelehnt oder ist nicht erreichbar"
    GatewayDiagnosticError.TIMEOUT -> "Zeitlimit für die Antwort überschritten"
    GatewayDiagnosticError.TLS_HOSTNAME -> "Zertifikat passt nicht zum Gateway-Hostnamen"
    GatewayDiagnosticError.TLS_CA -> "Zertifikat wird von der gespeicherten CA nicht bestätigt"
    GatewayDiagnosticError.TLS_HANDSHAKE -> "TLS-Verbindung konnte nicht aufgebaut werden"
    GatewayDiagnosticError.HTTP_401 -> "Zugangsschlüssel wurde nicht akzeptiert"
    GatewayDiagnosticError.HTTP_403 -> "Zugriff auf diesen Dienst wurde verweigert"
    GatewayDiagnosticError.HTTP_OTHER -> when(status) {
        503 -> "Gateway antwortet · Dienst derzeit nicht verfügbar"
        404 -> "Gateway antwortet · Dienstpfad nicht gefunden"
        else -> "Gateway antwortet mit einem HTTP-Fehler"
    }
    GatewayDiagnosticError.SCHEMA -> "Gateway antwortet · Antwortformat ist ungültig"
    GatewayDiagnosticError.WEBSOCKET -> "WebSocket-Handshake oder Protokoll wurde nicht bestätigt"
    GatewayDiagnosticError.SOURCE_UNAVAILABLE -> "Dienst konnte nicht gelesen werden"
    GatewayDiagnosticError.CANCELLED -> "Prüfung abgebrochen"
    GatewayDiagnosticError.INTERNAL -> "Interner Prüffehler"
    GatewayDiagnosticError.REDACTED_INPUT -> "Diagnoseeintrag wurde bereinigt"
}
