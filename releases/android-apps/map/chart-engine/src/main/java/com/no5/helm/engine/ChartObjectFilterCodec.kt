package com.no5.helm.engine

import com.no5.helm.chart.*
import kotlinx.serialization.json.*

internal fun encodeObjectFilters(value: ChartObjectFilters) = buildJsonObject {
    put("category", value.category.name); put("hiddenClasses", JsonArray(value.hiddenClasses.sorted().map(::JsonPrimitive)))
    put("soundings", value.soundings); put("text", value.text); put("importantTextOnly", value.importantTextOnly)
    put("atonText", value.atonText); put("lightText", value.lightText); put("metaObjects", value.metaObjects)
}
internal fun decodeObjectFilters(value: JsonObject) = ChartObjectFilters(S52DisplayCategory.valueOf(value.getValue("category").jsonPrimitive.content),
    value.getValue("hiddenClasses").jsonArray.map { it.jsonPrimitive.content }.toSet(),
    value.getValue("soundings").jsonPrimitive.boolean, value.getValue("text").jsonPrimitive.boolean,
    value.getValue("importantTextOnly").jsonPrimitive.boolean, value.getValue("atonText").jsonPrimitive.boolean,
    value.getValue("lightText").jsonPrimitive.boolean, value.getValue("metaObjects").jsonPrimitive.boolean)
