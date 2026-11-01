package com.no5.helm.ui

import com.no5.helm.chart.ChartFrame
import com.no5.preparation.ui.ViewportSnapshot

/** Query authority only. Visible-buffer scale has a separate measured projection. */
internal fun confirmedChartViewport(frame: ChartFrame?): ViewportSnapshot? = frame?.let {
    ViewportSnapshot(it.revision, it.geometry.surfaceWidth, it.geometry.surfaceHeight, it.cameraToken)
}
