package com.no5.helm.ui

import com.no5.helm.model.ConnectionState
import com.no5.helm.model.HelmState
import com.no5.helm.model.Ownship

enum class ChartKnobInput { LEFT_CW, LEFT_CCW, LEFT_PUSH, LEFT_LONG_PUSH, RIGHT_CW, RIGHT_CCW, RIGHT_PUSH, RIGHT_LONG_PUSH }
class ChartKnobRequest(val deadlineUptimeMs: Long, val active: () -> Boolean, val complete: (Boolean) -> Unit)

/** A publication does not freshen coordinates; only their actual gateway observation can admit following. */
internal fun currentOwnship(state: HelmState, now: Long): Ownship? {
    val snapshot = state.snapshot ?: return null
    val observed = snapshot.fixObservedAtEpochMs ?: return null
    if (state.connection != ConnectionState.LIVE || !snapshot.gatewayV1 || observed > now || now - observed > 5000) return null
    return snapshot.state.ownship?.takeIf { it.position.latitude in -85.0..85.0 && it.position.longitude in -180.0..180.0 }
}

interface ChartBufferedKnobAction {
    fun submit(sequence:Long,physicalUptimeMs:Long):Long
    fun result(id:Long,destination:com.no5.helm.chart.ChartControlResult):Boolean
    fun cancel()
}

/** Physical mapping only. Camera arithmetic, bounds, coalescing and results belong to the engine owner. */
internal fun submitChartKnobControl(engine:com.no5.helm.chart.ChartEngine,input:ChartKnobInput,
    sequence:Long,physicalUptimeMs:Long,latitude:Double,longitude:Double,homeScale:Double,positionControls:MapPositionControls?=null):Long {
    if(input==ChartKnobInput.LEFT_PUSH)return positionControls?.cycle(sequence,physicalUptimeMs)?:0
    val control=when(input) {
        ChartKnobInput.LEFT_CW,ChartKnobInput.LEFT_CCW -> com.no5.helm.chart.ChartCameraControl.ZOOM_STEPS
        ChartKnobInput.RIGHT_CW,ChartKnobInput.RIGHT_CCW -> com.no5.helm.chart.ChartCameraControl.ROTATE_STEPS
        ChartKnobInput.LEFT_PUSH,ChartKnobInput.LEFT_LONG_PUSH -> com.no5.helm.chart.ChartCameraControl.CENTER
        ChartKnobInput.RIGHT_PUSH -> com.no5.helm.chart.ChartCameraControl.NORTH_UP
        ChartKnobInput.RIGHT_LONG_PUSH -> com.no5.helm.chart.ChartCameraControl.RESET
    }
    val amount=if(input==ChartKnobInput.LEFT_CCW||input==ChartKnobInput.RIGHT_CCW)-1.0 else 1.0
    return engine.submitCameraControl(control,amount,sequence,physicalUptimeMs,latitude,longitude,homeScale)
}
