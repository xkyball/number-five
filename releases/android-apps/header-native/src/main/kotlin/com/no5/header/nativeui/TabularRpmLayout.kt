package com.no5.header.nativeui

/** Native RPMs retain Barlow's outlines, but never Barlow's proportional advances. */
object TabularRpmLayout {
    const val DIGITS = 4
    const val UNAVAILABLE = "—"
    const val OVER_RANGE = ">999999"

    enum class Anchor { LEFT, RIGHT }
    data class Glyph(val advance: Float, val left: Float, val right: Float) {
        init { require(advance > 0f && advance.isFinite() && left.isFinite() && right > left) }
        val extent get() = maxOf(advance, right - left)
    }
    data class Cell(val character: Char, val left: Float, val right: Float, val origin: Float)
    data class Run(val shown: String, val cells: List<Cell>, val baseline: Float,
        val glyphScale: Float, val advance: Float, val stale: Boolean, val markerCenter: Float) {
        val width get() = cells.size * advance
    }

    /** Four ordinary digits always use this one font scale and one cell advance. */
    class Font(private val glyphs: Map<Char, Glyph>, val slotWidth: Float) {
        init { require(slotWidth > 0f && slotWidth.isFinite()) }
        val numberWidth = slotWidth * .93f // The inner-edge stale marker always owns the remaining 7%.
        private val widestDigit = ('0'..'9').maxOf { glyphs.getValue(it).extent }
        val scale = minOf(1f, numberWidth / (DIGITS * widestDigit))
        val advance = widestDigit * scale
        private val overflowExtent = OVER_RANGE.maxOf { glyphs.getValue(it).extent }
        private val sixDigitScale = minOf(scale, numberWidth / (6 * widestDigit))
        private val overflowScale = minOf(scale, numberWidth / (OVER_RANGE.length * overflowExtent))

        fun place(raw: String, outerX: Float, baseline: Float, anchor: Anchor): Run {
            val stale = raw.endsWith('*')
            val shown = display(raw.removeSuffix("*"))
            val marker = shown == UNAVAILABLE
            val overrange = shown == OVER_RANGE
            val drawScale = when {
                overrange -> overflowScale
                marker -> minOf(scale, advance / glyphs.getValue('—').extent)
                shown.length > DIGITS -> sixDigitScale
                else -> scale
            }
            val pitch = when {
                overrange -> overflowExtent * drawScale
                marker -> advance
                else -> widestDigit * drawScale
            }
            // Reserve place values on BOTH engines: 9 -> 10 -> 1000 never moves the ones cell.
            val reserved = when { overrange -> OVER_RANGE.length; shown.length > DIGITS -> 6; else -> DIGITS }
            val left = if (anchor == Anchor.RIGHT) outerX - shown.length * pitch
                else outerX + (reserved - shown.length) * pitch
            val cells = shown.mapIndexed { index, character ->
                val glyph = glyphs.getValue(character)
                val cellLeft = left + index * pitch
                // Center the visible glyph, including its side bearings, within its cell.
                Cell(character, cellLeft, cellLeft + pitch,
                    cellLeft + pitch / 2f - (glyph.left + glyph.right) * drawScale / 2f)
            }
            val markerCenter = outerX + (numberWidth + slotWidth) / 2f *
                if (anchor == Anchor.RIGHT) -1f else 1f
            return Run(shown, cells, baseline, drawScale, pitch, stale, markerCenter)
        }
    }

    /** Gateway RPMs through 100000 remain exact. Extremely long input gets an honest marker. */
    fun display(raw: String): String {
        if (raw.isEmpty() || raw.any { it !in '0'..'9' }) return UNAVAILABLE
        val canonical = raw.trimStart('0').ifEmpty { "0" }
        return if (canonical.length > 6) OVER_RANGE else canonical
    }
}
