package com.no5.helm.client

import com.no5.helm.model.*
import kotlinx.serialization.json.*
import java.util.UUID

internal object SignalKRouteCodec {
    fun id(value: String): String = UUID.fromString(value).toString().also { require(it == value) }
    fun decode(id: String, entry: JsonObject): SignalKRouteResource {
        id(id)
        val token = entry.getValue("token").jsonPrimitive.content
        require(token.length == 64 && token.all { it in "0123456789abcdef" })
        val resource = entry.getValue("route").jsonObject
        val feature = resource.getValue("feature").jsonObject
        require(feature.getValue("type").jsonPrimitive.content == "Feature")
        val geometry = feature.getValue("geometry").jsonObject
        require(geometry.getValue("type").jsonPrimitive.content == "LineString")
        val positions = geometry.getValue("coordinates").jsonArray
        require(positions.size in 2..5000)
        val metadata = feature["properties"]?.jsonObject?.get("coordinatesMeta")?.jsonArray.orEmpty()
        val points = positions.mapIndexed { index, position ->
            val p = position.jsonArray; require(p.size in 2..3)
            val lon = p[0].jsonPrimitive.double; val lat = p[1].jsonPrimitive.double
            require(lat.isFinite() && lat in -85.0..85.0 && lon.isFinite() && lon in -180.0..180.0)
            val meta = metadata.getOrNull(index)?.jsonObject
            SignalKRoutePoint(meta?.get("no5PointId")?.jsonPrimitive?.content ?: "$id-$index",
                meta?.get("name")?.jsonPrimitive?.content.orEmpty(), lat, lon)
        }
        val name = resource["name"]?.jsonPrimitive?.content.orEmpty()
        require(name.isNotBlank() && name.length <= 200 && points.all { it.id.isNotBlank() && it.id.length <= 150 && it.name.length <= 200 } && points.map { it.id }.distinct().size == points.size)
        return SignalKRouteResource(id, name, points, token, resource.toString())
    }
    fun encode(id: String, name: String, points: List<SignalKRoutePoint>, original: String?): JsonObject {
        id(id)
        require(name.isNotBlank() && name.length <= 200 && points.size in 2..5000 && points.all { it.id.isNotBlank() && it.id.length <= 150 && it.name.length <= 200 && it.latitude.isFinite() && it.longitude.isFinite() && it.latitude in -85.0..85.0 && it.longitude in -180.0..180.0 })
        require(points.map { it.id }.distinct().size == points.size)
        val source = original?.let { Json.parseToJsonElement(it).jsonObject } ?: buildJsonObject {}
        val feature = source["feature"]?.jsonObject ?: buildJsonObject {}
        val properties = feature["properties"]?.jsonObject ?: buildJsonObject {}
        val oldMeta = properties["coordinatesMeta"]?.jsonArray.orEmpty().mapIndexed { index, meta ->
            (meta.jsonObject["no5PointId"]?.jsonPrimitive?.content ?: "$id-$index") to meta.jsonObject
        }.toMap()
        val oldPoints = feature["geometry"]?.jsonObject?.get("coordinates")?.jsonArray.orEmpty()
        val oldCoordinates = oldPoints.mapIndexed { index, coordinate ->
            (properties["coordinatesMeta"]?.jsonArray?.getOrNull(index)?.jsonObject?.get("no5PointId")?.jsonPrimitive?.content ?: "$id-$index") to coordinate.jsonArray
        }.toMap()
        // Retain foreign resource/feature properties, replace only the edited geometry and point metadata.
        return buildJsonObject {
            source.filterKeys { it !in setOf("name", "feature", "timestamp", "$" + "source", "distance") }.forEach { (k, v) -> put(k, v) }
            put("name", name)
            put("feature", buildJsonObject {
                feature.filterKeys { it !in setOf("type", "geometry", "properties") }.forEach { (k, v) -> put(k, v) }
                put("type", "Feature")
                put("geometry", buildJsonObject { put("type", "LineString"); put("coordinates", JsonArray(points.map { p ->
                    val old = oldCoordinates[p.id]
                    if (old != null && old.size == 3 && old[0].jsonPrimitive.double == p.longitude && old[1].jsonPrimitive.double == p.latitude) old
                    else JsonArray(listOf(JsonPrimitive(p.longitude), JsonPrimitive(p.latitude)))
                })) })
                put("properties", buildJsonObject {
                    properties.filterKeys { it != "coordinatesMeta" }.forEach { (k, v) -> put(k, v) }
                    put("coordinatesMeta", JsonArray(points.map { p -> buildJsonObject {
                        oldMeta[p.id]?.filterKeys { it !in setOf("name", "no5PointId") }?.forEach { (k, v) -> put(k, v) }
                        put("name", p.name); put("no5PointId", p.id)
                    } }))
                })
            })
        }
    }
}
