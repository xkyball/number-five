package com.no5.helm.ui

import com.no5.helm.chart.ChartViewport
import com.no5.helm.chart.ScreenPoint
import kotlin.math.*

/** Transform the viewport, keeping the geographic point under the fingers fixed. */
internal fun transformChartViewport(
    view: ChartViewport,
    anchor: ScreenPoint = ScreenPoint(view.width / 2.0, view.height / 2.0),
    pan: ScreenPoint = ScreenPoint(0.0, 0.0),
    zoom: Double = 1.0,
    rotation: Double = 0.0,
): ChartViewport = com.no5.helm.chart.transformChartCamera(view, anchor, pan, zoom, rotation)
