package com.no5.helm.chart

enum class PresentationMode { DAY, RED_NIGHT, GRAY_COMPARISON }
enum class ChartSymbolSet(val id: String, val title: String) {
    DEFAULT("default", "Standard"), BIG("big", "Groß"), NO5("no5", "No5"), R04("r04", "R04 · Original")
}
data class ChartStyle(val id: String, val title: String, val mode: PresentationMode,
    val symbolSets: Set<ChartSymbolSet> = setOf(ChartSymbolSet.DEFAULT, ChartSymbolSet.BIG, ChartSymbolSet.NO5),
    val allowsLandRelief: Boolean = mode == PresentationMode.DAY)
object ChartStyles {
    val all = listOf(
        ChartStyle("default", "Default / S52", PresentationMode.DAY),
        ChartStyle("glacier", "Glacier", PresentationMode.DAY),
        ChartStyle("signal", "Signal", PresentationMode.DAY),
        ChartStyle("paper", "Paper", PresentationMode.DAY),
        ChartStyle("contour", "Contour", PresentationMode.DAY),
        ChartStyle("lagoon", "Lagoon", PresentationMode.DAY),
        ChartStyle("cream", "Cream", PresentationMode.DAY),
        ChartStyle("sculpt-flat", "Sculpt · flach", PresentationMode.DAY),
        ChartStyle("sculpt", "Sculpt · plastisch", PresentationMode.DAY),
        ChartStyle("lagoon-sculpt", "S01 · R04 Lagoon Sculpt", PresentationMode.DAY, setOf(ChartSymbolSet.R04), false),
        ChartStyle("prism-flat", "Prism · flach", PresentationMode.DAY),
        ChartStyle("prism", "Prism · plastisch", PresentationMode.DAY),
        ChartStyle("redwatch", "Redwatch · initial", PresentationMode.RED_NIGHT),
        ChartStyle("redline", "Redline", PresentationMode.RED_NIGHT),
        ChartStyle("redshore", "Redshore · frühere Landbetonung", PresentationMode.RED_NIGHT),
        ChartStyle("redwater", "Redwater", PresentationMode.RED_NIGHT),
        ChartStyle("redwater-focus", "Redwater Focus", PresentationMode.RED_NIGHT),
        ChartStyle("redwater-gray", "Redwater · Grauvergleich", PresentationMode.GRAY_COMPARISON),
        ChartStyle("redwater-focus-gray", "Redwater Focus · Grauvergleich", PresentationMode.GRAY_COMPARISON)
    )
    fun require(id: String): ChartStyle = all.firstOrNull { it.id == id } ?: error("Unknown chart style: $id")
}
data class ChartPresentation(val styleId: String = "default", val symbols: ChartSymbolSet = ChartSymbolSet.DEFAULT, val landRelief: Boolean = false) {
    val mode: PresentationMode get() = ChartStyles.require(styleId).mode
    fun validate() {
        val style = ChartStyles.require(styleId)
        require(symbols in style.symbolSets) { "Symbol set does not belong to this chart style" }
        require(!landRelief || style.allowsLandRelief) { "This profile does not support additional land relief" }
    }
}
// A gray comparison has its own slot and cannot replace the saved red night profile.
data class PresentationProfiles(val day: ChartPresentation = ChartPresentation(),
    val night: ChartPresentation = ChartPresentation("redwater-focus"),
    val gray: ChartPresentation = ChartPresentation("redwater-gray")) {
    fun forMode(mode: PresentationMode) = when (mode) { PresentationMode.DAY -> day; PresentationMode.RED_NIGHT -> night; PresentationMode.GRAY_COMPARISON -> gray }
    fun confirmed(value: ChartPresentation): PresentationProfiles { value.validate(); return when (value.mode) {
        PresentationMode.DAY -> copy(day = value); PresentationMode.RED_NIGHT -> copy(night = value); PresentationMode.GRAY_COMPARISON -> copy(gray = value)
    } }
}
