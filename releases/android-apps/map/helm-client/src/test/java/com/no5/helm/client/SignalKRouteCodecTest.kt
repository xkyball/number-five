package com.no5.helm.client
import com.no5.helm.model.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
class SignalKRouteCodecTest {
    private val id = "11111111-1111-4111-8111-111111111111"
    @Test fun longitudeLatitudePointOrderAndForeignAttributesSurviveEdit() {
        val original = Json.parseToJsonElement("""{"name":"A","description":"foreign","feature":{"type":"Feature","geometry":{"type":"LineString","coordinates":[[18.1234567890123,-33.8,9],[18.2,-33.9]]},"properties":{"foreign":true,"coordinatesMeta":[{"name":"P1","extra":5},{"name":"P2"}]}}}""").jsonObject
        val resource = SignalKRouteCodec.decode(id, buildJsonObject { put("token", "a".repeat(64)); put("route", original) })
        assertEquals(-33.8, resource.points[0].latitude, 0.0); assertEquals(18.1234567890123, resource.points[0].longitude, 0.0)
        val encoded = SignalKRouteCodec.encode(id, "B", resource.points, resource.resourceJson)
        assertEquals(original["description"], encoded["description"])
        val feature = encoded.getValue("feature").jsonObject
        assertEquals(9, feature.getValue("geometry").jsonObject.getValue("coordinates").jsonArray[0].jsonArray[2].jsonPrimitive.int)
        assertEquals(5, feature.getValue("properties").jsonObject.getValue("coordinatesMeta").jsonArray[0].jsonObject.getValue("extra").jsonPrimitive.int)
        assertTrue(feature.getValue("properties").jsonObject.getValue("foreign").jsonPrimitive.boolean)
    }
    @Test fun invalidGeometryIsReportedRatherThanSkippedPointShortcut() {
        val value = Json.parseToJsonElement("""{"token":"${"a".repeat(64)}","route":{"name":"Bad","feature":{"type":"Feature","geometry":{"type":"LineString","coordinates":[[18,-33],[200,0],[19,-34]]}}}}""").jsonObject
        try { SignalKRouteCodec.decode(id, value); fail("Invalid point must reject complete route") } catch (_: IllegalArgumentException) {}
    }
}
