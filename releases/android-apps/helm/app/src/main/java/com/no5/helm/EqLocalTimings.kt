package com.no5.helm
/** Bounded diagnostics, not Compose state. No model values, credentials or UI invalidations. */
internal data class EqLocalTiming(val token:Long,val queuedNs:Long,val startedNs:Long,val finishedNs:Long,val submittedNs:Long=0)
internal class EqLocalTimings {
 private val rows=linkedMapOf<Long,EqLocalTiming>()
 @Synchronized fun record(row:EqLocalTiming){rows[row.token]=row;while(rows.size>64)rows.remove(rows.keys.first())}
 @Synchronized fun snapshot()=rows.values.toList()
}
