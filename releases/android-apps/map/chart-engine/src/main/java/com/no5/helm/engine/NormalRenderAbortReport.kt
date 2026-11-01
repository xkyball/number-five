package com.no5.helm.engine

import kotlinx.serialization.json.*

/** Bounded, run-owned native evidence. Missing restoration fields never count as confirmation. */
internal fun normalRenderAbortReport(raw: String, expectedRunId: String): JsonObject {
    require(raw.toByteArray(Charsets.UTF_8).size <= 32 * 1024) { "Normal-abort report exceeds bounded evidence budget" }
    return Json.parseToJsonElement(raw).jsonObject.also { report ->
        require(report.getValue("schema").jsonPrimitive.content == "no5.normal-render-abort/1" &&
            report.getValue("runId").jsonPrimitive.content == expectedRunId) { "Normal-abort report owner changed" }
        require(report.getValue("available").jsonPrimitive.boolean) { "Normal-abort report unavailable" }
        report.getValue("active").jsonPrimitive.boolean
        report.getValue("enabled").jsonPrimitive.boolean
        report.getValue("originalEnabled").jsonPrimitive.boolean
        require(!report.getValue("defaultEnabled").jsonPrimitive.boolean &&
            report.getValue("maxCameraAborts").jsonPrimitive.int == 2 &&
            report.getValue("eventLimit").jsonPrimitive.int == 64 &&
            report.getValue("events").jsonArray.size <= 64) { "Normal-abort report bounds changed" }
    }
}

internal fun normalRenderAbortRestored(report: JsonObject): Boolean =
    report["available"]?.jsonPrimitive?.booleanOrNull == true &&
        report["active"]?.jsonPrimitive?.booleanOrNull == false &&
        report["enabled"]?.jsonPrimitive?.booleanOrNull?.let { enabled ->
            report["originalEnabled"]?.jsonPrimitive?.booleanOrNull?.let { enabled == it }
        } == true
