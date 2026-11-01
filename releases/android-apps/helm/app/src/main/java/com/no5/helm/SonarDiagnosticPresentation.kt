package com.no5.helm

import com.no5.domain.SonarDiagnosticField

/** Bounded read-only labels. A run average never becomes a window rate or minimum. */
internal data class SonarDiagnosticSummary(val label:String,val field:SonarDiagnosticField)
internal fun sonarDiagnosticSummary(fields:List<SonarDiagnosticField>):List<SonarDiagnosticSummary> {
    val labels=linkedMapOf(
        "transmit.rate_control.planned_hz" to "Planrate · Eigene",
        "transmit.schedule.nominal_own_mean_hz" to "Slotplan · nominelle Rate",
        "transmit.run.own_starts" to "Eigene Starts · Lauf",
        "transmit.run.physical_markers" to "Physische Marker · Lauf",
        "transmit.run.raw_ping_links" to "Rohzuordnungen · Lauf",
        "transmit.run.usable_pings" to "Brauchbare Tiefenpings · Lauf",
        "transmit.run.usable_hz" to "Brauchbare Tiefe · Ø Lauf",
        "transmit.ping_rates.rates.own.hz" to "Hostrate · Eigene",
        "transmit.ping_rates.rates.physical.hz" to "Hostrate · physische Marker",
        "transmit.ping_rates.rates.raw.hz" to "Hostrate · Rohzuordnungen",
        "transmit.ping_rates.rates.usable.hz" to "Hostrate · brauchbare Tiefe",
        "transmit.physical_markers" to "Physische Marker · Host",
        "transmit.linked_pings" to "Rohzuordnungen · Host",
        "transmit.marker_reference.quarantined" to "Referenz in Quarantäne",
        "transmit.reason" to "Gemeldeter Sendegrund",
    )
    val keyed=fields.associateBy {"${it.group}.${it.name}"}
    return labels.mapNotNull {(key,label)->keyed[key]?.let{SonarDiagnosticSummary(label,it)}}.take(12)
}
