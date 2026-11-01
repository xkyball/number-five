package com.no5.helm.engine

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class NormalRenderAbortReportTest {
    private fun report(active: Boolean = true, enabled: Boolean = true, original: Boolean = false) = buildJsonObject {
        put("schema", "no5.normal-render-abort/1"); put("runId", "owned-run"); put("available", true)
        put("active", active); put("enabled", enabled); put("originalEnabled", original)
        put("defaultEnabled", false); put("maxCameraAborts", 2); put("eventLimit", 64)
        put("events", buildJsonArray {})
    }
    private fun change(base: JsonObject, key: String, value: JsonElement?) = JsonObject(base.toMutableMap().also {
        if (value == null) it.remove(key) else it[key] = value
    })

    @Test fun restoreRequiresTheMatchingRunAndExplicitInactiveOriginalState() {
        val restored = normalRenderAbortReport(report(false, false).toString(), "owned-run")
        assertTrue(normalRenderAbortRestored(restored))
        assertFalse(normalRenderAbortRestored(report()))
        assertFalse(normalRenderAbortRestored(report(false, true)))
        assertFalse(normalRenderAbortRestored(change(restored, "enabled", null)))
        assertFalse(normalRenderAbortRestored(change(restored, "originalEnabled", null)))
        assertFalse(normalRenderAbortRestored(change(restored, "available", JsonPrimitive(false))))
        assertThrows(IllegalArgumentException::class.java) { normalRenderAbortReport(restored.toString(), "stale-run") }
    }

    @Test fun missingUnavailableOversizedOrChangedDiagnosticBoundsAreRejected() {
        val valid = report()
        for ((key, value) in listOf("available" to JsonPrimitive(false), "enabled" to null,
            "defaultEnabled" to JsonPrimitive(true), "maxCameraAborts" to JsonPrimitive(3),
            "eventLimit" to JsonPrimitive(65), "events" to buildJsonArray { repeat(65) { add(JsonNull) } })) {
            assertThrows(RuntimeException::class.java) {
                normalRenderAbortReport(change(valid, key, value).toString(), "owned-run")
            }
        }
        assertThrows(IllegalArgumentException::class.java) { normalRenderAbortReport(" ".repeat(32 * 1024 + 1), "owned-run") }
        assertThrows(RuntimeException::class.java) { normalRenderAbortReport("{", "owned-run") }
    }

    @Test fun nativePinnedCompletionRetainsItsOldExpectedEpochAndActualNewerEpoch() {
        val native = change(report(), "events", buildJsonArray { add(buildJsonObject {
            put("expectedEpoch", 4); put("latestEpoch", 9); put("reason", "FULL_COMPLETE")
            put("full", true); put("cameraPinned", true)
        }) })
        val decoded = normalRenderAbortReport(native.toString(), "owned-run")
        assertEquals(native, decoded)
        val event = decoded.getValue("events").jsonArray.single().jsonObject
        assertEquals(4L, event.getValue("expectedEpoch").jsonPrimitive.long)
        assertEquals(9L, event.getValue("latestEpoch").jsonPrimitive.long)
    }
}
