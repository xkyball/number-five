package com.no5.helm

import com.no5.domain.*

/** Exact evaluator boundaries (> stale/lost, not >=); no periodic or VSync freshness polling. */
internal object HelmFreshnessDeadline {
    private fun candidates(stamp:Long?,policy:FreshnessPolicy,now:Long):List<Long> {
        if(stamp==null)return emptyList()
        return listOf(-5000L,policy.staleAfterMs+1,policy.lostAfterMs+1).mapNotNull { offset ->
            if(offset>0 && stamp>Long.MAX_VALUE-offset || offset<0 && stamp<Long.MIN_VALUE-offset)null
            else (stamp+offset).takeIf{it>now}
        }
    }
    fun next(raw:HelmState,weather:WeatherSnapshot,policy:FreshnessPolicy,weatherPolicy:FreshnessPolicy,now:Long):Long? {
        val deadlines=ArrayList<Long>()
        fun add(q:TelemetryQuality,forceTimestamp:Boolean=false) {
            val quality=if(forceTimestamp)q.copy(demo=false)else q
            if(quality.demo)return
            val before=QualityEvaluator.evaluate(quality,now,policy)
            candidates(quality.timestampEpochMs,policy,now).filterTo(deadlines){QualityEvaluator.evaluate(quality,it,policy)!=before}
        }
        add(raw.quality)
        raw.telemetryFields.values.forEach{add(it.quality)}
        // History deliberately never accepts the legacy demo freshness bypass.
        add(raw.fieldObservation("depth",raw.depth).quality,true)
        val before=WeatherFreshness.evaluate(weather,now,weatherPolicy)
        (candidates(weather.timestampEpochMs,weatherPolicy,now)+candidates(weather.observedAtEpochMs,weatherPolicy,now))
            .filterTo(deadlines){WeatherFreshness.evaluate(weather,it,weatherPolicy)!=before}
        return deadlines.minOrNull()
    }
}
