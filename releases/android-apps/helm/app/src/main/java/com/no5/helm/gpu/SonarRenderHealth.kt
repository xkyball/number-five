package com.no5.helm.gpu

/** Local UI policy; it never requests data, repairs a component or relaxes freshness. */
internal object SonarRenderHealth {
    fun warning(active:Boolean,backpressured:Boolean,oldestQueuedNanos:Long?,
        missingPresentation:Boolean,sourceStale:Boolean):String? = when {
        !active->null
        oldestQueuedNanos?.let{it>2_000_000_000L}==true||missingPresentation->"System überlastet · Bild nicht aktuell"
        sourceStale->"Keine aktuellen Sonardaten · letztes Bild"
        else->null
    }
    fun presentationOverdue(submitted:Long,presented:Long,firstSubmitted:Long,now:Long):Boolean {
        val baseline=if(presented>0L)presented else firstSubmitted
        return submitted>presented&&baseline>0L&&now>=baseline&&now-baseline>2_000_000_000L
    }
}
