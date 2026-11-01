package com.no5.helm.chart

enum class S52DisplayCategory { BASE, STANDARD, ALL, CUSTOM }
data class ChartObjectFilters(val category: S52DisplayCategory = S52DisplayCategory.ALL,
    val hiddenClasses: Set<String> = emptySet(), val soundings: Boolean = true, val text: Boolean = true,
    val importantTextOnly: Boolean = false, val atonText: Boolean = true, val lightText: Boolean = true,
    val metaObjects: Boolean = false) {
    init { require(hiddenClasses.size <= 300 && hiddenClasses.all { it.length == 6 && it.all { c -> c.isLetterOrDigit() || c in "_$" } }) }
}
data class ChartObjectFilterState(val supported: Boolean = false, val effective: ChartObjectFilters = ChartObjectFilters(), val revision: Long = 0)
enum class ChartRouteRole { SAVED, DRAFT, ACTIVE }
data class ChartOverlayObject(val id: String, val position: GeoProjection, val label: String = "", val headingRadians: Double? = null)
