package com.no5.helm.ui

import org.json.JSONObject
import java.util.UUID

/** Frozen before opening the document picker, even if an external command starts another run. */
internal data class RendererReportExport(val fileName: String, val json: String)

internal fun rendererReportExport(json: String): RendererReportExport {
    val report = JSONObject(json)
    val schema = report.optString("schema")
    val fileName = if (schema == "no5-resident-capacity/1" && report.optJSONObject("metadata")?.optString("inputRecipe") == "CURRENT_VIEW_REUSE") {
        val metadata = report.getJSONObject("metadata")
        val id = metadata.getString("runId")
        require(UUID.fromString(id).toString() == id) { "Ungültige Reportkennung" }
        val variant = com.no5.helm.chart.RendererViewportReuseVariant.valueOf(metadata.getString("viewportReuse"))
        val parity = metadata.optBoolean("parity", false)
        require(!parity || (!metadata.getBoolean("detailed") && variant in setOf(
            com.no5.helm.chart.RendererViewportReuseVariant.OFF, com.no5.helm.chart.RendererViewportReuseVariant.ALL)))
        val kind = if (parity) "parity" else if (metadata.getBoolean("detailed")) "detail" else "normal"
        "current-map-${variant.name.lowercase(java.util.Locale.ROOT)}-$kind-$id.json"
    } else if (schema == "no5-renderer-style-benchmark/1" || schema == "no5-renderer-live-input/1" || (schema == "no5-renderer-ab/1" && report.has("runId"))) {
        val id = report.getString("runId")
        require(UUID.fromString(id).toString() == id) { "Ungültige Reportkennung" }
        val recipe = when (schema) { "no5-renderer-ab/1" -> "standard"; "no5-renderer-live-input/1" -> "t0-live"; else -> "s01-abba" }
        "boston-$recipe-$id.json"
    } else "no5-renderer-profile.json"
    return RendererReportExport(fileName, json)
}
