package com.no5.helm.ui

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import com.no5.helm.chart.*
import kotlin.math.min

/** UI-thread injection, explicitly separate from the platform input-dispatch endpoint. */
internal class RendererTouchReplayController(private val view: ChartSurfaceView) {
    private val handler = Handler(Looper.getMainLooper())
    private var sink: RendererLiveInputSink? = null
    private var active = false
    private var delivered = 0
    private var maxLate = 0L
    private var steps = emptyList<RendererTouchReplay.Step>()
    private var base = 0L
    private var down = 0L
    private var index = 0
    private var width = 0
    private var height = 0
    private val deliver = object : Runnable {
        override fun run() {
            if (!active) return
            if (view.width != width || view.height != height || view.inputSink() !== sink) { stop("FAILED_SURFACE_OR_RUN_CHANGED"); return }
            val step = steps[index]
            val at = base + step.offsetMs
            maxLate = maxOf(maxLate, (SystemClock.uptimeMillis() - at).coerceAtLeast(0))
            if (step.action == MotionEvent.ACTION_DOWN) down = at
            val edge = min(width, height)
            val event = MotionEvent.obtain(down, at, step.action, step.pointers.size,
                step.pointers.map { p -> MotionEvent.PointerProperties().apply { id = p.id; toolType = MotionEvent.TOOL_TYPE_FINGER } }.toTypedArray(),
                step.pointers.map { p -> MotionEvent.PointerCoords().apply {
                    x = width / 2f + edge * p.dx / 1000f; y = height / 2f + edge * p.dy / 1000f; pressure = 1f; size = 1f
                } }.toTypedArray(), 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
            val accepted = try { view.inputOrigin = RendererInputOrigin.VIEW_INJECTED; view.dispatchTouchEvent(event) }
                finally { view.inputOrigin = RendererInputOrigin.PLATFORM_DISPATCH; event.recycle() }
            delivered++
            if (!accepted) { stop("FAILED_TOUCH_REJECTED"); return }
            if (++index == steps.size) {
                active = false; sink?.replay("COMPLETED", delivered, maxLate); sink = null
            } else handler.postAtTime(this, base + steps[index].offsetMs)
        }
    }
    fun sync(enabled: Boolean, replay: Boolean) {
        if (!enabled || !replay) { if (active) stop("STOPPED_RUN_END"); return }
        if (active) return
        val target = view.inputSink() ?: return
        if (!target.claimReplay()) return
        sink = target
        try {
            steps = view.context.assets.open(RendererTouchReplay.asset(target.replayRecipe)).bufferedReader().use { RendererTouchReplay.parse(it.readText(), target.replayRecipe) }
            require(view.width > 0 && view.height > 0 && !view.isTouchActive) { "Replay surface/touch unavailable" }
            width = view.width; height = view.height; delivered = 0; index = 0; maxLate = 0
            base = SystemClock.uptimeMillis() + 100; active = true
            target.replay("STARTED")
            handler.postAtTime(deliver, base)
        } catch (failure: Exception) { stop("FAILED_REPLAY_ASSET_OR_TOUCH") }
    }
    fun externalInput() { if (active) stop("FAILED_EXTERNAL_INPUT_INTERFERENCE") }
    fun stop(reason: String = "FAILED_LIFECYCLE_INTERRUPTED") {
        handler.removeCallbacks(deliver)
        if (active) view.cancelReplayGesture()
        sink?.replay(reason, delivered, maxLate)
        active = false; sink = null
    }
}
