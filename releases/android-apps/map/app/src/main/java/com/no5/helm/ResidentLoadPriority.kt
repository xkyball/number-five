package com.no5.helm

import com.no5.helm.library.ChartPlanningCatalog

/** Orders admission only. Every conservative viewport candidate remains required.
 * The factor four follows the native quilt heuristic; it is not a nautical detail rule. */
internal object ResidentLoadPriority {
    fun order(required: List<String>, catalog: ChartPlanningCatalog, chartScale: Double?): List<String> {
        if (chartScale == null || !chartScale.isFinite() || chartScale <= 0) return required
        val metadata = catalog.cells.associateBy { it.key.sourceKey }
        fun scale(key: String) = metadata[key]?.metadata?.nativeScale?.takeIf { it > 0 }
        fun group(key: String): Int = scale(key)?.let { if (chartScale <= it.toDouble() * 4) 0 else 1 } ?: 2
        return required.sortedWith(compareBy<String> { group(it) }
            .thenBy { scale(it)?.let { scale -> if (group(it) == 1) -scale.toLong() else scale.toLong() } ?: Long.MAX_VALUE }
            .thenBy { metadata[it]?.order ?: Int.MAX_VALUE })
    }
}
