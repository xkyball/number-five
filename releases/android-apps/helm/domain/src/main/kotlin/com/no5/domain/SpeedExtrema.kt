package com.no5.domain

/** Observed speed extrema of the current source session/run, in canonical knots. */
data class SpeedExtremaSnapshot(val minimumKnots:Double?=null,val maximumKnots:Double?=null)
class SpeedExtrema {
    private var key:List<Any?>?=null
    private var stamp:Long?=null
    private var elapsed:Double?=null
    private var result=SpeedExtremaSnapshot()
    fun accept(raw:HelmState,nowEpochMs:Long,sessionId:String,policy:FreshnessPolicy=QualityEvaluator.SHARED) {
        val field=raw.telemetryFields["speed"] ?: if(raw.telemetryFields.isEmpty()) TelemetryValue(raw.speed,raw.quality) else null
        val nextKey=listOf(sessionId,raw.sourceSessionId,raw.simulationRunId,field?.quality?.source,raw.quality.simulated)
        val nextElapsed=raw.simulationElapsedSeconds?.takeIf{it.isFinite()}
        if(key!=nextKey || (nextElapsed!=null && elapsed?.let{nextElapsed<it}==true)) {
            result=SpeedExtremaSnapshot();stamp=null
        }
        key=nextKey;elapsed=nextElapsed
        if(raw.quality.status in setOf(TelemetryStatus.INVALID,TelemetryStatus.LOST,TelemetryStatus.UNAVAILABLE))return
        val value=field?.value?.takeIf{it.isFinite()&&it in 0.0..200.0} ?: return
        val q=field.quality
        val observed=q.timestampEpochMs ?: return
        if(observed<0 || q.source.isNullOrBlank() || QualityEvaluator.evaluate(q,nowEpochMs,policy)!=TelemetryStatus.LIVE)return
        if(stamp?.let{observed<=it}==true)return
        stamp=observed
        result=SpeedExtremaSnapshot(minOf(result.minimumKnots?:value,value),maxOf(result.maximumKnots?:value,value))
    }
    fun snapshot()=result
}
