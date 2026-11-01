package com.no5.helm.engine

import com.no5.helm.chart.*
import kotlinx.serialization.json.*

internal fun decodeObjectQuery(raw: String, sourceGeneration: Long): ChartObjectQueryResult {
    val root = Json.parseToJsonElement(raw).jsonObject
    val objects = root.getValue("objects").jsonArray.map { element ->
        val obj = element.jsonObject
        ChartObject(ChartObjectReference(obj.getValue("sourceKey").jsonPrimitive.content,
            obj.getValue("featureIndex").jsonPrimitive.int, sourceGeneration), obj.getValue("id").jsonPrimitive.content,
            obj.getValue("objectClass").jsonPrimitive.content, obj.getValue("primitive").jsonPrimitive.content,
            obj.getValue("cellName").jsonPrimitive.content,
            GeoProjection(obj.getValue("latitude").jsonPrimitive.double, obj.getValue("longitude").jsonPrimitive.double),
            obj.getValue("distancePixels").jsonPrimitive.double,
            obj.getValue("attributes").jsonObject.mapValues { it.value.jsonPrimitive.content })
    }
    val total = root.getValue("total").jsonPrimitive.int
    val next = root["nextOffset"]?.jsonPrimitive?.intOrNull
    require(total >= objects.size && (next == null || next in 1 until total))
    return ChartObjectQueryResult(if (total == 0) ChartObjectQueryState.EMPTY else ChartObjectQueryState.READY, objects, total, next)
}
internal fun decodeObjectHighlight(value: JsonElement?): ChartObjectHighlight {
    if (value == null) return ChartObjectHighlight()
    val obj = value.jsonObject
    return ChartObjectHighlight(obj.getValue("sourceKey").jsonPrimitive.content, obj.getValue("featureIndex").jsonPrimitive.int,
        obj.getValue("visible").jsonPrimitive.boolean, obj.getValue("segments").jsonPrimitive.int)
}
