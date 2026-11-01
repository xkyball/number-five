package com.no5.helm.engine

import com.no5.helm.chart.ChartResidencyStatus
import com.no5.helm.chart.ChartResidentGeometry
import com.no5.helm.chart.ChartResidentBudget
import kotlinx.serialization.json.*

internal object ChartResidencyDecoder {
    fun decode(raw: String): ChartResidencyStatus {
        val value = Json.parseToJsonElement(raw).jsonObject
        fun long(key: String) = value.getValue(key).jsonPrimitive.long.also { require(it >= 0) }
        fun count(key: String) = long(key).also { require(it <= Int.MAX_VALUE) }.toInt()
        fun text(key: String) = value.getValue(key).jsonPrimitive.content
        val sources = value.getValue("sources").jsonArray.map { it.jsonPrimitive.content }
        require(sources.distinct().size == sources.size && sources.all { it.matches(Regex("[a-f0-9]{64}")) })
        val geometries = value["geometries"]?.jsonArray?.map { entry ->
            val g = entry.jsonObject
            fun nonnegative(key: String) = g.getValue(key).jsonPrimitive.long.also { require(it >= 0) }
            ChartResidentGeometry(g.getValue("geometryId").jsonPrimitive.content,
                g.getValue("sourceKey").jsonPrimitive.content, nonnegative("chargedBytes"),
                g.getValue("pinned").jsonPrimitive.boolean, g.getValue("reusableForCatalog").jsonPrimitive.boolean,
                g["reservationBoundBytes"]?.takeIf { it != JsonNull }?.jsonPrimitive?.long?.also { require(it > 0) })
        }.orEmpty()
        require(geometries.map { it.geometryId }.distinct().size == geometries.size &&
            geometries.all { it.geometryId.isNotBlank() && it.sourceKey.matches(Regex("[a-f0-9]{64}")) &&
                (it.reservationBoundBytes?.let { bound -> it.chargedBytes <= bound } ?: true) })
        val budget = value["budget"]?.takeIf { it != JsonNull }?.jsonObject?.let { b ->
            ChartResidentBudget(b.getValue("cells").jsonPrimitive.int, b.getValue("bytes").jsonPrimitive.long,
                b.getValue("loadingBytes").jsonPrimitive.long)
        }
        val status = ChartResidencyStatus(value.getValue("enabled").jsonPrimitive.boolean,
            value.getValue("complete").jsonPrimitive.boolean, text("catalogGeneration"), text("cameraKey"),
            text("policyKey"), long("cameraRevision"), long("contentRevision"), count("resident"), count("loading"),
            count("pinned"), count("required"), count("missing"), long("chargedBytes"), long("geometryBytes"),
            long("loadingBytes"), count("peakCells"), long("peakChargedBytes"), long("loads"), long("retains"),
            long("evictions"), long("failures"), text("message"), sources, geometries, budget)
        require(status.loading in 0..1 && status.pinned <= status.resident && sources.size <= status.resident &&
            status.missing <= status.required && (!status.complete || status.enabled && status.loading == 0 && status.missing == 0))
        if (value["geometries"] != null) require(geometries.size == status.resident &&
            geometries.count { it.pinned } == status.pinned &&
            geometries.filter { it.reusableForCatalog }.map { it.sourceKey }.toSet() == sources.toSet() &&
            geometries.fold(status.loadingBytes) { total, g -> Math.addExact(total, g.chargedBytes) } == status.chargedBytes)
        return status
    }
}
