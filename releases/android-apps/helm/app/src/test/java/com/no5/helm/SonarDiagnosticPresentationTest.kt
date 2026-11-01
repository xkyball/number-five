package com.no5.helm

import com.no5.domain.SonarDiagnosticField
import org.junit.Assert.*
import org.junit.Test

class SonarDiagnosticPresentationTest {
    @Test fun actualGatewayKeysRemainDistinctAndUsableHzIsExplicitlyARunAverage() {
        val fields=listOf(
            SonarDiagnosticField("transmit","schedule.nominal_own_mean_hz","18.12","Hz"),
            SonarDiagnosticField("transmit","run.own_starts","10737"),
            SonarDiagnosticField("transmit","run.physical_markers","10737"),
            SonarDiagnosticField("transmit","run.raw_ping_links","10737"),
            SonarDiagnosticField("transmit","run.usable_hz","16.98","Hz"),
            SonarDiagnosticField("transmit","rate_control.planned_hz","18.12","Hz"),
            SonarDiagnosticField("transmit","ping_rates.rates.own.hz","17.4","Hz"))
        val rows=sonarDiagnosticSummary(fields)
        assertEquals(fields.size,rows.size)
        assertEquals("Brauchbare Tiefe · Ø Lauf",rows.single{it.field.name=="run.usable_hz"}.label)
        assertEquals("Slotplan · nominelle Rate",rows.single{it.field.name=="schedule.nominal_own_mean_hz"}.label)
        assertEquals("Hostrate · Eigene",rows.single{it.field.name=="ping_rates.rates.own.hz"}.label)
        assertTrue(rows.all{it.field in fields})
    }
    @Test fun summaryIsBoundedAndNeverSynthesizesAbsentStatistics() {
        assertTrue(sonarDiagnosticSummary(emptyList()).isEmpty())
        assertTrue(sonarDiagnosticSummary(List(128){SonarDiagnosticField("unknown","future_$it","0")}).isEmpty())
    }
}
