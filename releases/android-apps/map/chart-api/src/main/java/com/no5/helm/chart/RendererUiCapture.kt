package com.no5.helm.chart

/** Independent UI observations. Window spans are wall durations, not thread CPU. */
enum class RendererUiPhase(val key: String) {
    WINDOW_UNKNOWN_DELAY("window.unknownDelay"), WINDOW_INPUT("window.input"),
    WINDOW_ANIMATION("window.animation"), WINDOW_LAYOUT_MEASURE("window.layoutMeasure"),
    WINDOW_DRAW("window.draw"), WINDOW_SYNC("window.sync"),
    WINDOW_COMMAND_ISSUE("window.commandIssue"), WINDOW_SWAP("window.swap"),
    WINDOW_TOTAL("window.total"), WINDOW_GPU("window.gpuApi31"),
    CHART_NODE_MEASURE("chartNode.measure"), CHART_NODE_PLACE("chartNode.place"),
    CHART_NODE_DRAW_DISPATCH("chartNode.drawDispatch"), ANDROID_VIEW_UPDATE("androidView.update"),
    TEXTURE_UPDATE_INTERVAL("textureUpdate.callbackInterval"), CAPTURE_PROBE("captureProbe"),
}
enum class RendererWindowState { NOT_REGISTERED, REGISTERED, CALLBACK_SEEN, UNAVAILABLE }
/** A handle belongs to ONE capture; after stop its calls are ignored. No JNI or UI-thread JSON. */
interface RendererUiSink {
    fun record(phase: RendererUiPhase, wallNanos: Long, cpuNanos: Long = -1)
    fun windowFrame(firstDraw: Boolean, droppedReports: Int)
    fun windowState(state: RendererWindowState)
    fun textureUpdated()
}
