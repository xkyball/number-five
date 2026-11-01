package com.no5.helm.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class MapRouteNoticeTest {
    private val failure = "Speichern fehlgeschlagen: Signal-K-Routen nicht verfügbar (HTTP 503); Gateway-Service und Ressourcenanbieter prüfen."

    @Test fun serviceFailureAndSavePrefixUseTheSameSelectedLanguage() {
        assertEquals(failure, mapRouteNotice(failure, "de"))
        assertEquals("Save failed: Signal K routes unavailable (HTTP 503); check the gateway service and resource provider.", mapRouteNotice(failure, "en"))
        assertEquals("Stoor het misluk: Signal K-roetes is nie beskikbaar nie (HTTP 503); kontroleer die poortdiens en hulpbronverskaffer.", mapRouteNotice(failure, "af"))
    }

    @Test fun arbitraryProviderDetailsAndUserNamesRemainUnchanged() {
        val detail = "Route Chris / test 503 · abc://original"
        assertEquals(detail, mapRouteNotice(detail, "af"))
        assertEquals("Save failed: $detail", mapRouteNotice("Speichern fehlgeschlagen: $detail", "en"))
    }
}
