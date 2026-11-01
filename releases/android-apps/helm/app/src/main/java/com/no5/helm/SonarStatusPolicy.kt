package com.no5.helm

/** Presentation only. No acquisition, recovery or freshness deadlines are changed here. */
internal enum class SonarStatusLevel { NORMAL, WARNING, ERROR }
internal enum class SonarStatusReason(val level:SonarStatusLevel,val label:String) {
    LIVE(SonarStatusLevel.NORMAL,"Aktuell"),
    WAITING(SonarStatusLevel.NORMAL,"Wartet auf Sonardaten"),
    PREPARING(SonarStatusLevel.NORMAL,"Ansicht wird vorbereitet"),
    FROZEN(SonarStatusLevel.NORMAL,"Ansicht eingefroren"),
    BACKPRESSURE(SonarStatusLevel.NORMAL,"Empfang wartet"),
    VIEWPORT_UNAVAILABLE(SonarStatusLevel.NORMAL,"Tiefenausschnitt nicht verfügbar"),
    DISCONNECTED(SonarStatusLevel.WARNING,"Sonarverbindung unterbrochen"),
    REFERENCE_LOST(SonarStatusLevel.WARNING,"Originalreferenz verloren"),
    STALE(SonarStatusLevel.WARNING,"Keine aktuellen Sonardaten · letztes Bild"),
    PRESENTATION_LATE(SonarStatusLevel.WARNING,"Bildpräsentation verzögert"),
    QUEUE_LATE(SonarStatusLevel.WARNING,"Empfangsrückstau · Bild nicht aktuell"),
    SOURCE_ERROR(SonarStatusLevel.ERROR,"Quellenfehler"),
    RENDER_ERROR(SonarStatusLevel.ERROR,"Darstellungsfehler")
}
internal data class SonarStatusIncident(val reason:SonarStatusReason,val startedNanos:Long,val endedNanos:Long?=null)
internal data class SonarUiStatus(
    val reason:SonarStatusReason=SonarStatusReason.WAITING,
    val symbolLevel:SonarStatusLevel=SonarStatusLevel.NORMAL,
    val recovering:Boolean=false,
    val currentIncident:SonarStatusIncident?=null,
    val lastIncident:SonarStatusIncident?=null,
) {
    val label:String get()=if(recovering&&reason==SonarStatusReason.LIVE)"Wieder aktuell"else reason.label
}

/** A warning clears after five continuously healthy monotonic seconds, never by acknowledgement. */
internal class SonarStatusPolicy {
    private var context:String?=null
    private var healthySince:Long?=null
    private var status=SonarUiStatus()
    fun update(sourceContext:String,reason:SonarStatusReason,nowNanos:Long):SonarUiStatus {
        if(context!=sourceContext){context=sourceContext;healthySince=null;status=SonarUiStatus()}
        val problem=reason.level!=SonarStatusLevel.NORMAL
        var current=status.currentIncident
        var last=status.lastIncident
        if(current!=null&&current.reason!=reason){last=current.copy(endedNanos=nowNanos);current=null}
        if(problem){
            healthySince=null
            if(current==null)current=SonarStatusIncident(reason,nowNanos)
        }else if(status.symbolLevel!=SonarStatusLevel.NORMAL&&healthySince==null)healthySince=nowNanos
        val recovering=!problem&&healthySince?.let{nowNanos<it||nowNanos-it<5_000_000_000L}==true
        val level=if(problem||recovering)maxOf(status.symbolLevel,reason.level)else SonarStatusLevel.NORMAL
        status=SonarUiStatus(reason,level,recovering,current,last)
        return status
    }
}

/** These exact local subscription-intent markers are not source faults. Raw status is retained. */
internal fun sonarProfileIntentStatus(error:String?):SonarStatusReason? = when(error){
    "PROFILE_DEMAND_PAUSED"->SonarStatusReason.WAITING
    "PROFILE_DEMAND_RESUMING"->SonarStatusReason.PREPARING
    else->null
}
