package com.no5.helm.engine

import org.junit.Assert.*
import org.junit.Test
import kotlinx.serialization.json.*

class ChartResidencyDecoderTest {
    private fun native() = javaClass.getResourceAsStream("/native-residency-status-v1.json")!!.bufferedReader().use { it.readText() }

    @Test fun readsRealNativeLifecycleStatusIncludingLoadingReservationAndMissingCoverage() {
        val ready = ChartResidencyDecoder.decode(native())
        assertTrue(ready.enabled && ready.complete)
        assertEquals(2, ready.resident)
        assertEquals(2, ready.peakCells)
        assertEquals(0, ready.missing)
        assertTrue(ready.peakChargedBytes > ready.chargedBytes)
        assertTrue(ready.geometryBytes > 0)
        val loading = ChartResidencyDecoder.decode(native().replace("\"complete\":true", "\"complete\":false")
            .replace("\"loading\":0", "\"loading\":1").replace("\"missing\":0", "\"missing\":1"))
        assertFalse(loading.complete)
        assertEquals(1, loading.loading)
        assertEquals(1, loading.missing)
    }

    @Test fun rejectsFalseCompletionImpossibleCountsNegativeBytesAndDuplicateSources() {
        val ready = native()
        for (invalid in listOf(ready.replace("\"loading\":0", "\"loading\":1"),
            ready.replace("\"missing\":0", "\"missing\":1"),
            ready.replace("\"pinned\":0", "\"pinned\":3"),
            ready.replace(Regex("\"geometryBytes\":\\d+"), "\"geometryBytes\":-1"),
            ready.replace(ChartResidencyDecoder.decode(ready).sources.last(),
                ChartResidencyDecoder.decode(ready).sources.first()))) {
            assertThrows(IllegalArgumentException::class.java) { ChartResidencyDecoder.decode(invalid) }
        }
    }
    @Test fun exportsAllHeldGeometryAndRejectsCounterOrBoundContradictions() {
        val original = Json.parseToJsonElement(native()).jsonObject
        val status = ChartResidencyDecoder.decode(native())
        val half = status.chargedBytes / 2
        val entries = status.sources.mapIndexed { i, key -> buildJsonObject {
            put("geometryId", key); put("sourceKey", key)
            put("chargedBytes", if (i == 0) half else status.chargedBytes - half)
            put("pinned", false); put("reusableForCatalog", true)
            put("reservationBoundBytes", if (i == 0) JsonPrimitive(status.chargedBytes) else JsonNull)
        } }
        fun encoded(values: List<JsonObject>) = JsonObject(original + ("geometries" to JsonArray(values))).toString()
        val decoded = ChartResidencyDecoder.decode(encoded(entries))
        assertEquals(2, decoded.geometries.size)
        assertNull(decoded.geometries.last().reservationBoundBytes)
        assertEquals(status.chargedBytes, decoded.geometries.sumOf { it.chargedBytes })
        assertThrows(IllegalArgumentException::class.java) { ChartResidencyDecoder.decode(encoded(entries.take(1))) }
        assertThrows(IllegalArgumentException::class.java) { ChartResidencyDecoder.decode(encoded(listOf(entries.first(), entries.first()))) }
        val badBound = JsonObject(entries.first() + ("reservationBoundBytes" to JsonPrimitive(1)))
        assertThrows(IllegalArgumentException::class.java) { ChartResidencyDecoder.decode(encoded(listOf(badBound, entries.last()))) }
        val falsePin = JsonObject(entries.first() + ("pinned" to JsonPrimitive(true)))
        assertThrows(IllegalArgumentException::class.java) { ChartResidencyDecoder.decode(encoded(listOf(falsePin, entries.last()))) }
    }
}
