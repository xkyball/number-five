package com.no5.helm.ui

import com.no5.helm.chart.ChartCameraAffine
import com.no5.helm.chart.ChartSurfaceGeometry
import com.no5.helm.chart.ChartViewport
import com.no5.helm.chart.chartCameraAffine

/** Existing UI names remain compatible; camera math is shared with worker coverage decisions. */
internal typealias ChartBufferAffine = ChartCameraAffine

internal fun chartBufferAffine(base: ChartViewport, target: ChartViewport,
    geometry: ChartSurfaceGeometry): ChartBufferAffine? = chartCameraAffine(base, target, geometry)
