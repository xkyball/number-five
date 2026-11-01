package com.no5.helm.library

import com.no5.helm.chart.*
import kotlinx.serialization.json.*

/** Decode the explicit native metadata contract; no camera or loaded-chart access. */
object ChartCellMetadataDecoder {
    fun decode(path: String, text: String, provider: String = "noaa-s57"): ValidatedChartCell {
        val root = Json.parseToJsonElement(text).jsonObject
        fun JsonObject.int(key: String) = getValue(key).jsonPrimitive.int
        fun JsonObject.string(key: String) = getValue(key).jsonPrimitive.content
        fun JsonObject.double(key: String) = getValue(key).jsonPrimitive.double
        fun nullableInt(key: String) = root.getValue(key).let { if (it == JsonNull) null else it.jsonPrimitive.int }
        fun rings(key: String) = root.getValue(key).jsonArray.map { ring -> ring.jsonArray.map { point ->
            val p = point.jsonArray; require(p.size == 2)
            ChartCellCoordinate(p[0].jsonPrimitive.double, p[1].jsonPrimitive.double)
        } }
        val bounds = root.getValue("bounds").jsonObject
        val caps = root.getValue("capabilities").jsonObject
        val result = ValidatedChartCell(path, root.int("metadataVersion"), root.string("parserVersion"),
            root.string("parserIdentity"), root.string("preparedSourceKey"), root.int("coverageVersion"),
            nullableInt("nativeScale"), nullableInt("usage"),
            ChartCellBounds(bounds.double("south"), bounds.double("west"), bounds.double("north"), bounds.double("east")),
            rings("coverage"), rings("exclusions"), ChartCoverageCapabilities(
                caps.getValue("exteriorRingsOnly").jsonPrimitive.boolean,
                caps.getValue("polygonHoles").jsonPrimitive.boolean,
                caps.getValue("multiPolygons").jsonPrimitive.boolean,
                caps.getValue("wrappedDateline").jsonPrimitive.boolean,
                caps.double("latitudeLimit"), caps.string("coordinatePrecision")))
        require(ChartMetadataContract.usable(result, provider)) { "Unsupported native cell metadata" }
        return result
    }
}
