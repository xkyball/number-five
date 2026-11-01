package com.no5.helm

import com.no5.domain.DepthUnit

/** Parsing an operator draft never changes units, acquisition or saved preferences. */
internal fun sonarStartDepthInput(text: String, unit: DepthUnit): Double? {
    val value = text.trim().replace(',', '.').toDoubleOrNull()
        ?.takeIf { it.isFinite() && it >= 0.0 } ?: return null
    return (value / unit.fromMeters(1.0)).takeIf { it.isFinite() && it >= 0.0 }
}
