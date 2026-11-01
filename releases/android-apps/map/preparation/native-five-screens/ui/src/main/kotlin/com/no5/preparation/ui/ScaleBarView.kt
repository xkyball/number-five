package com.no5.preparation.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.log10
import java.math.BigDecimal

/** Reuses the measured reference distance; choosing a nice label never stretches its pixels. */
fun scaleBarForUnit(lengthPx: Float, distanceMetres: Double, unit: DistanceUnit, viewport: ViewportSnapshot): ScaleBar? {
    if (!lengthPx.isFinite() || lengthPx <= 0 || !distanceMetres.isFinite() || distanceMetres <= 0) return null
    val divisor = when {
        unit == DistanceUnit.NAUTICAL_MILES -> 1852.0
        distanceMetres < 1000 -> 1.0
        else -> 1000.0
    }
    val suffix = if (unit == DistanceUnit.NAUTICAL_MILES) "sm" else if (divisor == 1.0) "m" else "km"
    val measuredUnits = distanceMetres / divisor
    if (!measuredUnits.isFinite() || measuredUnits <= 0) return null
    val exponent = floor(log10(measuredUnits)).toInt()
    val choices = listOf(5L, 2L, 1L).map { BigDecimal.valueOf(it).scaleByPowerOfTen(exponent) } +
        BigDecimal.valueOf(5L).scaleByPowerOfTen(exponent - 1)
    val nice = choices.firstOrNull { it.toDouble().isFinite() && it.toDouble() > 0 && it.toDouble() <= measuredUnits } ?: return null
    val projectedLength = (lengthPx.toDouble() * (nice.toDouble() / measuredUnits)).toFloat()
    if (!projectedLength.isFinite() || projectedLength <= 0 || projectedLength > lengthPx) return null
    val number = nice.stripTrailingZeros().toPlainString().replace('.', ',')
    return ScaleBar(projectedLength, "$number $suffix", viewport)
}

/** lengthPx is the adapter's physical slot projection, not a design-raster length. */
internal fun scaleBarLengthDp(scale: ScaleBar?, viewport: ViewportSnapshot?, slotWidthPx: Float,
    slotHeightPx: Float, density: Float, availableWidthPx: Float): Float? {
    if (scale == null || viewport == null || scale.viewport != viewport || scale.label.isBlank()) return null
    if (!slotWidthPx.isFinite() || !slotHeightPx.isFinite() || slotWidthPx <= 0 || slotHeightPx <= 0 ||
        abs(viewport.widthPx - slotWidthPx) > 2f || abs(viewport.heightPx - slotHeightPx) > 2f) return null
    if (!density.isFinite() || density <= 0 || !availableWidthPx.isFinite() || availableWidthPx <= 0 ||
        !scale.lengthPx.isFinite() || scale.lengthPx <= 0 || scale.lengthPx > availableWidthPx) return null
    return scale.lengthPx / density
}

/** Five ticks show the exact labelled projected length. Invalid authority draws no bar. */
@Composable internal fun ScaleBarView(scale: ScaleBar?, viewport: ViewportSnapshot?, slotWidthPx: Float,
    slotHeightPx: Float, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier) {
        val currentScale = scale ?: return@BoxWithConstraints
        val density = LocalDensity.current
        val padding = 6.dp
        val length = scaleBarLengthDp(currentScale, viewport, slotWidthPx, slotHeightPx, density.density,
            with(density) { (maxWidth - padding * 2).toPx() }) ?: return@BoxWithConstraints
        val color = MaterialTheme.colorScheme.onSurface
        Column(Modifier.padding(horizontal = padding), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text("0 · ${currentScale.label}", fontSize = 22.sp, maxLines = 1, modifier = Modifier.testTag("chart-scale-label"))
            Canvas(Modifier.width(length.dp).height(24.dp).testTag("chart-scale-drawing")
                .semantics { contentDescription = "Maßstabsbalken von 0 bis ${currentScale.label}, fünf Teilstriche" }) {
                val baseline = size.height * .6f
                val stroke = 2.dp.toPx()
                drawLine(color, Offset(0f, baseline), Offset(size.width, baseline), stroke)
                for (index in 0..4) {
                    val x = size.width * index / 4f
                    val height = if (index % 2 == 0) 16.dp.toPx() else 9.dp.toPx()
                    drawLine(color, Offset(x, baseline - height / 2), Offset(x, baseline + height / 2), stroke)
                }
            }
        }
    }
}
