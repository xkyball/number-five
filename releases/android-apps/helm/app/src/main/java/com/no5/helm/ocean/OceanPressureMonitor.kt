package com.no5.helm.ocean

import android.app.Activity
import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.os.Handler
import android.os.Build
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.FrameMetrics
import android.view.Window
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

/**
 * Call from the persistent host, with eligibility for HELM / DAY / ANIMATION, hardware rendering,
 * allowed motion. Eligibility must not depend on the current quality: STATIC
 * still samples pressure once a second so it can recover without starting an ocean frame loop.
 */
@Composable
fun rememberOceanQuality(eligible: Boolean): State<OceanQuality> {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val window = remember(context) { context.oceanActivity()?.window }
    val governor = remember { OceanQualityGovernor() }
    val result = remember { mutableStateOf(governor.quality) }
    DisposableEffect(eligible, lifecycle, window) {
        var monitor: OceanPressureMonitor? = null
        fun updateMonitoring() {
            val active = eligible && window != null &&
                lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            if (active && monitor == null) {
                monitor = OceanPressureMonitor(context.applicationContext, window!!, governor) {
                    if (result.value != it) result.value = it
                }.also { it.start() }
            } else if (!active) {
                monitor?.close()
                monitor = null
            }
        }
        val observer = LifecycleEventObserver { _, _ -> updateMonitoring() }
        lifecycle.addObserver(observer)
        updateMonitoring()
        onDispose {
            lifecycle.removeObserver(observer)
            monitor?.close()
            monitor = null
        }
    }
    return result
}

private fun Context.oceanActivity(): Activity? {
    var current = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        val next = current.baseContext
        if (next === current) return null
        current = next
    }
    return current as? Activity
}

/** Fixed-size, thread-safe accumulation; no Compose state or allocation per frame. */
internal class OceanFramePressureAccumulator {
    private var frameCount = 0L
    private var durationNanos = 0.0
    private var deadlineNanos = 0.0
    private var durationToDeadline = 0.0
    private var lateFrames = 0L
    private var severeFrames = 0L
    private var firstDrawSeen = false

    @Synchronized
    fun record(totalDurationNanos: Long, frameBudgetNanos: Long, firstDraw: Boolean = false,
        frameDeadlineNanos: Long = -1L) {
        if (firstDraw) { firstDrawSeen = true; return }
        val deadline = if (frameDeadlineNanos > 0L) frameDeadlineNanos else frameBudgetNanos
        if (totalDurationNanos < 0L || deadline <= 0L) return
        frameCount++
        durationNanos += totalDurationNanos.toDouble()
        deadlineNanos += deadline.toDouble()
        durationToDeadline += totalDurationNanos.toDouble() / deadline
        // Small boundary jitter is not a reason to disable a decorative animation.
        if (totalDurationNanos > deadline + max(2_000_000.0, deadline * .20)) lateFrames++
        if (totalDurationNanos > deadline * 2.0) severeFrames++
    }

    @Synchronized
    fun drain(): OceanFramePressure {
        val sample = OceanFramePressure(frameCount, lateFrames, severeFrames, durationNanos,
            deadlineNanos, durationToDeadline, firstDrawSeen)
        frameCount = 0L
        durationNanos = 0.0
        deadlineNanos = 0.0
        durationToDeadline = 0.0
        lateFrames = 0L
        severeFrames = 0L
        firstDrawSeen = false
        return sample
    }
}

internal data class OceanFramePressure(
    val frameCount: Long = 0,
    val lateFrames: Long = 0,
    val severeFrames: Long = 0,
    val durationNanos: Double = 0.0,
    val deadlineNanos: Double = 0.0,
    val durationToDeadline: Double = 0.0,
    val firstDrawSeen: Boolean = false,
) {
    private val divisor get() = frameCount.coerceAtLeast(1).toDouble()
    val lateFraction get() = lateFrames / divisor
    val severeFraction get() = severeFrames / divisor
    val meanRatio get() = durationToDeadline / divisor
    // Eight ordinary frames or three genuinely long stalls are enough for a one-second sample.
    // A lone startup/navigation stall cannot be converted into sustained rendering pressure.
    val sufficientPressureEvidence get() = frameCount >= 8 ||
        (severeFrames >= 3 && durationNanos >= 500_000_000.0)
    val overBudget get() = sufficientPressureEvidence && lateFraction >= .30 && meanRatio >= 1.25
    val severePressure get() = overBudget && severeFraction >= .50 && meanRatio >= 2.0
    // Sparse, actually drawn healthy frames can establish recovery over thirty measured seconds.
    val healthy get() = frameCount > 0 && lateFraction <= .10 && severeFrames == 0L && meanRatio <= 1.05
    fun diagnostic() = String.format(Locale.ROOT,
        "frames=%d late=%.0f%% severe=%.0f%% mean=%.1fms deadline=%.1fms ratio=%.2f",
        frameCount, lateFraction * 100, severeFraction * 100,
        durationNanos / divisor / 1e6, deadlineNanos / divisor / 1e6, meanRatio)
}

/** Startup, resumed windows and first draws of new layouts receive a fresh settling interval. */
internal class OceanPressureSamplingGate {
    private var readyAtNanos = Long.MAX_VALUE
    fun reset(nowNanos: Long) { readyAtNanos = nowNanos + 3_000_000_000L }
    fun ready(nowNanos: Long) = nowNanos >= readyAtNanos
}

/** An idle window is not measured evidence of health, and breaks sustained sample intervals. */
internal fun OceanQualityGovernor.observeMeasuredPressure(
    elapsedSeconds: Float,
    pressure: OceanFramePressure,
    severeThermal: Boolean = false,
    lowMemory: Boolean = false,
) {
    if (severeThermal || lowMemory || (!pressure.firstDrawSeen &&
            (pressure.sufficientPressureEvidence || pressure.healthy))) {
        observe(elapsedSeconds, pressure.overBudget, severeThermal, lowMemory,
            pressure.severePressure, pressure.healthy)
    } else {
        pause()
    }
}

/** Lifecycle owns this instance; close is idempotent and queued callbacks cannot publish later. */
private class OceanPressureMonitor(
    private val context: Context,
    private val window: Window,
    private val governor: OceanQualityGovernor,
    private val publish: (OceanQuality) -> Unit,
) : AutoCloseable {
    private val handler = Handler(Looper.getMainLooper())
    private val active = AtomicBoolean(false)
    private val frames = OceanFramePressureAccumulator()
    private val samplingGate = OceanPressureSamplingGate()
    private val memorySignal = AtomicBoolean(false)
    private val thermalStatus = AtomicInteger(0)
    private val activityManager by lazy { context.getSystemService(ActivityManager::class.java) }
    private val powerManager by lazy { context.getSystemService(PowerManager::class.java) }
    private val memoryInfo = ActivityManager.MemoryInfo()
    private var frameListenerRegistered = false
    private var memoryCallbackRegistered = false
    private var thermalListener: PowerManager.OnThermalStatusChangedListener? = null
    private var lastSampleNanos = 0L
    private var lowRamDevice = false
    private var lastPublishedQuality = governor.quality
    @Volatile private var frameBudgetNanos = 16_666_667L

    private val frameListener = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
        if (active.get()) {
            // Copy scalar values immediately; Android reuses the FrameMetrics object after return.
            frames.record(metrics.getMetric(FrameMetrics.TOTAL_DURATION), frameBudgetNanos,
                metrics.getMetric(FrameMetrics.FIRST_DRAW_FRAME) == 1L,
                if (Build.VERSION.SDK_INT >= 31) metrics.getMetric(FrameMetrics.DEADLINE) else -1L)
        }
    }

    @Suppress("DEPRECATION")
    private val memoryCallbacks = object : ComponentCallbacks2 {
        override fun onConfigurationChanged(newConfig: Configuration) = Unit
        override fun onLowMemory() { if (active.get()) memorySignal.set(true) }
        override fun onTrimMemory(level: Int) {
            // UI_HIDDEN is a visibility change, not proof of memory pressure.
            if (active.get() && ((level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW &&
                    level < ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) ||
                    level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND)) memorySignal.set(true)
        }
    }

    private val sampler = object : Runnable {
        override fun run() {
            if (!active.get()) return
            try {
                val now = SystemClock.elapsedRealtimeNanos()
                val elapsed = ((now - lastSampleNanos).coerceAtLeast(0L) / 1_000_000_000.0).toFloat()
                lastSampleNanos = now
                val pressure = frames.drain()
                updateFrameBudget()
                if (pressure.firstDrawSeen) samplingGate.reset(now)
                // Modern Android no longer sends all trim levels. Poll the OS low-memory flag too.
                activityManager?.getMemoryInfo(memoryInfo)
                val lowMemory = memorySignal.getAndSet(false) || memoryInfo.lowMemory || lowRamDevice
                val severeThermal = thermalStatus.get() >= PowerManager.THERMAL_STATUS_SEVERE
                val before = governor.quality
                if (severeThermal || lowMemory || samplingGate.ready(now)) {
                    governor.observeMeasuredPressure(elapsed, pressure, severeThermal, lowMemory)
                } else governor.pause()
                val reason = when {
                    lowRamDevice -> "low_ram_device"
                    lowMemory -> "low_memory"
                    severeThermal -> "severe_thermal"
                    governor.quality.ordinal < before.ordinal -> "measured_recovery"
                    pressure.severePressure -> "sustained_severe_deadline_misses"
                    else -> "sustained_deadline_misses"
                }
                publishQuality(governor.quality, reason, pressure)
                // Give a changed detail/cadence level time to settle before judging it again.
                if (before != governor.quality) samplingGate.reset(now)
                // No Choreographer, frame request, draw invalidation or ocean animation starts here.
                if (active.get()) handler.postDelayed(this, SAMPLE_PERIOD_MS)
            } catch (failure: RuntimeException) {
                failClosed(failure)
            }
        }
    }

    fun start() {
        if (!active.compareAndSet(false, true)) return
        try {
            lastSampleNanos = SystemClock.elapsedRealtimeNanos()
            samplingGate.reset(lastSampleNanos)
            updateFrameBudget()
            lowRamDevice = activityManager?.isLowRamDevice == true
            if (lowRamDevice) {
                governor.observe(1f, false, lowMemory = true)
                publishQuality(governor.quality, "low_ram_device")
            }
            // A new lifecycle may recover from a previous monitor error.
            publish(governor.quality)
            window.addOnFrameMetricsAvailableListener(frameListener, handler)
            frameListenerRegistered = true
            context.registerComponentCallbacks(memoryCallbacks)
            memoryCallbackRegistered = true
            val thermalManager = powerManager
            if (thermalManager != null) {
                thermalStatus.set(thermalManager.currentThermalStatus)
                val listener = PowerManager.OnThermalStatusChangedListener { status ->
                    if (active.get()) thermalStatus.set(status)
                }
                thermalManager.addThermalStatusListener(listener)
                thermalListener = listener
            }
            handler.postDelayed(sampler, SAMPLE_PERIOD_MS)
        } catch (failure: RuntimeException) {
            failClosed(failure)
        }
    }

    private fun failClosed(failure: RuntimeException) {
        close()
        // Stay static until a new eligible lifecycle creates fresh observers. Never retry on draw.
        publish(OceanQuality.STATIC)
        Log.w("No5Ocean", "quality=$lastPublishedQuality->STATIC reason=pressure_monitor_error " +
            "${failure.javaClass.simpleName}: ${failure.message}", failure)
        lastPublishedQuality = OceanQuality.STATIC
    }

    private fun publishQuality(quality: OceanQuality, reason: String, pressure: OceanFramePressure? = null) {
        if (quality != lastPublishedQuality) {
            Log.i("No5Ocean", "quality=$lastPublishedQuality->$quality reason=$reason " +
                (pressure?.diagnostic() ?: ""))
            lastPublishedQuality = quality
        }
        publish(quality)
    }

    private fun updateFrameBudget() {
        val refreshRate = window.decorView.display?.refreshRate ?: 60f
        val rate = if (refreshRate.isFinite() && refreshRate > 0f) refreshRate else 60f
        val nextBudget = (1_000_000_000.0 / rate).toLong().coerceAtLeast(1L)
        if (abs(nextBudget.toDouble() / frameBudgetNanos - 1.0) > .10) {
            samplingGate.reset(SystemClock.elapsedRealtimeNanos())
            governor.pause()
        }
        frameBudgetNanos = nextBudget
    }

    override fun close() {
        if (!active.compareAndSet(true, false)) return
        handler.removeCallbacks(sampler)
        // Each cleanup runs independently, including thermal unregister if another removal fails.
        if (frameListenerRegistered) release("frame metrics") {
            window.removeOnFrameMetricsAvailableListener(frameListener)
        }
        frameListenerRegistered = false
        if (memoryCallbackRegistered) release("memory callback") {
            context.unregisterComponentCallbacks(memoryCallbacks)
        }
        memoryCallbackRegistered = false
        val listener = thermalListener
        thermalListener = null
        if (listener != null) release("thermal listener") {
            powerManager?.removeThermalStatusListener(listener)
        }
        frames.drain()
        memorySignal.set(false)
        governor.pause()
    }

    private inline fun release(name: String, cleanup: () -> Unit) {
        try { cleanup() } catch (failure: RuntimeException) {
            Log.w("No5Ocean", "Could not unregister pressure $name", failure)
        }
    }

    companion object { private const val SAMPLE_PERIOD_MS = 1_000L }
}
