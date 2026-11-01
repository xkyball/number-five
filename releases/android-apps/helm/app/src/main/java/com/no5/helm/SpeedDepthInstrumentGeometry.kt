package com.no5.helm

import com.no5.domain.DepthHistorySnapshot
import com.no5.domain.DisplayUnits
import com.no5.domain.SpeedUnit
import com.no5.domain.HelmState
import com.no5.domain.TelemetryStatus
import com.no5.domain.TelemetryValue
import com.no5.domain.QualityEvaluator
import com.no5.domain.FreshnessPolicy
import kotlin.math.max

/** Pure geometry and value policy; fixed scales are preferences, not vessel speed limits. */
object SpeedDepthInstrumentGeometry {
    const val LEFT = 690f
    const val TOP = 214f
    const val RIGHT = 1310f
    const val BOTTOM = 697f
    const val RAIL_WIDTH = 446f
    const val RAIL_HEIGHT = 298f
    const val SCALE_MAXIMUM_KMH = 115.0
    const val SCALE_MAXIMUM_KNOTS = SCALE_MAXIMUM_KMH / 1.852
    fun scaleMaximum(unit:SpeedUnit)=unit.fromKnots(SCALE_MAXIMUM_KNOTS)
    data class Point(val x: Float, val y: Float)
    /** Exact Rotor 01 elliptical track, local center (310,250), angles 197°..343°. */
    fun pointAt(fraction: Float,inset: Float=0f): Point {
        val a=Math.toRadians((197+146*fraction.coerceIn(0f,1f)).toDouble())
        return Point(LEFT+310+(223-inset)*kotlin.math.cos(a).toFloat(),
            TOP+250+(149-inset)*kotlin.math.sin(a).toFloat())
    }

    data class Reading(val speed: Double?, val depth: Double?, val fraction: Float?, val aboveScale: Boolean,
        val speedStatus: TelemetryStatus, val depthStatus: TelemetryStatus)
    data class Tick(val value: Double, val fraction: Float, val major: Boolean)
    data class TracePoint(val x: Float, val y: Float, val breakBefore: Boolean)
    data class Trace(val minimumMeters: Double, val maximumMeters: Double, val points: List<TracePoint>)

    fun reading(state: HelmState, units: DisplayUnits, status: String,
        nowEpochMs: Long = System.currentTimeMillis(), policy: FreshnessPolicy = QualityEvaluator.SHARED): Reading {
        val legacy = state.telemetryFields.isEmpty()
        fun fieldStatus(field: TelemetryValue?): TelemetryStatus {
            if (state.quality.status == TelemetryStatus.LOST || state.quality.status == TelemetryStatus.INVALID ||
                state.quality.status == TelemetryStatus.UNAVAILABLE)
                return state.quality.status
            if (legacy) return when(status) {
                "LIVE", "SIMULATED", "DEMO" -> TelemetryStatus.LIVE
                "STALE" -> TelemetryStatus.STALE
                else -> TelemetryStatus.UNAVAILABLE
            }
            val value=field?.value ?: return TelemetryStatus.UNAVAILABLE
            if (!value.isFinite() || value < 0.0) return TelemetryStatus.INVALID
            return QualityEvaluator.evaluate(field.quality,nowEpochMs,policy)
        }
        val speedField=state.telemetryFields["speed"]
        val depthField=state.telemetryFields["depth"]
        val speedStatus=fieldStatus(speedField)
        val depthStatus=fieldStatus(depthField)
        fun visible(quality: TelemetryStatus)=quality==TelemetryStatus.LIVE || quality==TelemetryStatus.STALE
        // Field evidence remains raw. Only a valid LIVE speed may use the frame-clock motion.
        val speedRaw=if(legacy || speedStatus==TelemetryStatus.LIVE)state.speed else speedField?.value
        val depthRaw=if(legacy)state.depth else depthField?.value
        val canonicalSpeed=speedRaw?.takeIf { visible(speedStatus) && it.isFinite() && it in 0.0..200.0 }
        val speed=canonicalSpeed?.let(units.speed::fromKnots)
        val depth=depthRaw?.takeIf { visible(depthStatus) && it.isFinite() && it in 0.0..12_000.0 }?.let(units.depth::fromMeters)
        return Reading(speed,depth,canonicalSpeed?.let { (it/SCALE_MAXIMUM_KNOTS).coerceIn(0.0,1.0).toFloat() },
            canonicalSpeed!=null && canonicalSpeed>SCALE_MAXIMUM_KNOTS,speedStatus,depthStatus)
    }

    fun ticks(unit:SpeedUnit=SpeedUnit.KNOTS): List<Tick> = (0..50).map { Tick(scaleMaximum(unit)*it/50,it/50f,it%5==0) }

    /** True time on x; deeper water goes down. A gap is never bridged or filled. */
    fun trace(history: DepthHistorySnapshot): Trace {
        fun valid(point: com.no5.domain.DepthHistoryPoint) = point.depthMeters.isFinite() && point.depthMeters >= 0.0 &&
            point.timestampEpochMs >= history.startEpochMs && point.timestampEpochMs <= history.endEpochMs
        var count = 0
        var low = Double.POSITIVE_INFINITY
        var high = Double.NEGATIVE_INFINITY
        for (point in history.points) if (valid(point)) {
            count++
            low = minOf(low, point.depthMeters)
            high = maxOf(high, point.depthMeters)
        }
        if (count == 0) return Trace(0.0, 2.0, emptyList())
        val span = max(2.0, high - low)
        val minimum = max(0.0, (low + high - span) / 2 - span * .12)
        val maximum = max(minimum + 2.0, (low + high + span) / 2 + span * .12)
        val duration = (history.endEpochMs.toDouble() - history.startEpochMs.toDouble()).coerceAtLeast(1.0)
        val projected = ArrayList<TracePoint>(count)
        for (point in history.points) if (valid(point)) {
            projected.add(TracePoint(((point.timestampEpochMs.toDouble() - history.startEpochMs.toDouble()) / duration).toFloat(),
                ((point.depthMeters - minimum) / (maximum - minimum)).toFloat(), point.breakBefore))
        }
        return Trace(minimum, maximum, projected)
    }
}

/** One instrument/session owner; keep scrolling cheap but invalidate as a visible point expires. */
internal class InstrumentHistoryTraceCache {
    private var sourcePoints:List<com.no5.domain.DepthHistoryPoint>?=null
    private var oldestVisibleStamp:Long?=null
    private var nextFutureStamp:Long?=null
    private var lastEndMs=Long.MIN_VALUE
    var anchorEndMs=0L
        private set
    private var geometry=SpeedDepthInstrumentGeometry.Trace(0.0,2.0,emptyList())

    fun update(history:DepthHistorySnapshot,now:Long=history.endEpochMs):SpeedDepthInstrumentGeometry.Trace {
        val start=if(now<Long.MIN_VALUE+60_000L)Long.MIN_VALUE else now-60_000L
        if(sourcePoints !== history.points || now<lastEndMs ||
            oldestVisibleStamp?.let{it<start}==true || nextFutureStamp?.let{it<=now}==true) {
            sourcePoints=history.points
            anchorEndMs=now
            geometry=SpeedDepthInstrumentGeometry.trace(history.copy(startEpochMs=start,endEpochMs=now,windowMs=60_000))
            oldestVisibleStamp=null
            nextFutureStamp=null
            for(point in history.points) {
                if(!point.depthMeters.isFinite() || point.depthMeters<0.0)continue
                val stamp=point.timestampEpochMs
                if(stamp>now) {
                    if(nextFutureStamp==null || stamp<nextFutureStamp!!)nextFutureStamp=stamp
                } else if(stamp>=start && (oldestVisibleStamp==null || stamp<oldestVisibleStamp!!)) {
                    oldestVisibleStamp=stamp
                }
            }
        }
        lastEndMs=now
        return geometry
    }
}
