package com.no5.helm

import com.no5.domain.*
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Display formatting only: every result uses its original observation time.
 * One formatter and at most32 rendered timestamps replace per-ping formatter
 * construction. Raw timestamps and freshness evidence remain in the source model.
 */
internal class DashboardTimeFormatter(locale:Locale,zone:TimeZone) {
    private val formatter=DateFormat.getTimeInstance(DateFormat.MEDIUM,locale).apply { timeZone=zone }
    private val cache=object:LinkedHashMap<Long,String>(32,.75f,true) {
        override fun removeEldestEntry(eldest:MutableMap.MutableEntry<Long,String>?):Boolean=size>32
    }
    @Synchronized fun format(originalEpochMs:Long?):String {
        if(originalEpochMs==null)return "—"
        return cache[originalEpochMs]?:formatter.format(Date(originalEpochMs)).also{cache[originalEpochMs]=it}
    }
    internal val cachedTimes:Int get()=cache.size
}

private fun evidence(field:DashboardField,clock:DashboardTimeFormatter,flag:String?=null):String =
    "${if(field.simulated)"SIM · "else""}${field.quality.name} · ${clock.format(field.observedAtEpochMs)}"+
        (flag?.let{" · $it"}?:"")

internal data class DashboardMotionText(val portRpm:String,val starboardRpm:String,val course:String,
    val heading:String,val speed:String,val portEvidence:String,val starboardEvidence:String,
    val courseEvidence:String,val headingEvidence:String,val speedEvidence:String)
internal fun dashboardMotionText(panel:HelmPanelPresentation,clock:DashboardTimeFormatter):DashboardMotionText {
    val p=dashboardMotionLabels(panel)
    return DashboardMotionText(p.portRpm,p.starboardRpm,p.course,p.heading,p.speed,evidence(p.port,clock),
        evidence(p.starboard,clock),evidence(p.courseField,clock),evidence(p.headingField,clock),evidence(p.speedField,clock,p.speedFlag))
}

/** Equality follows this panel's painted fields, not unrelated RPM/heading/raw
 * packet clocks. Current per-field status is still evaluated from the untouched
 * original observations, including expiry with no new packets.
 */
internal data class DashboardSlowText(val texts:Map<String,String>,val fieldEvidence:Map<String,String>,
    val depthText:String,val depthEvidence:String,val depthStatus:TelemetryStatus,val weather:WeatherPresentation) {
    fun text(key:String)=texts[key]?:"—"
}
internal fun dashboardSlowText(p:HelmPanelPresentation,clock:DashboardTimeFormatter):DashboardSlowText {
    val keys=listOf("secondary-port-voltage","secondary-port-oil","secondary-port-coolant",
        "secondary-stbd-voltage","secondary-stbd-oil","secondary-stbd-coolant")
    val fields=listOf("portVoltage","portOilBar","portCoolantC","starboardVoltage","starboardOilBar","starboardCoolantC")
    fun field(name:String,value:Double?)=dashboardField(p.raw,name,value,p.nowEpochMs,p.policy)
    return DashboardSlowText(keys.associateWith{p.slow.values.text[it]?:"—"},
        fields.associateWith{evidence(field(it,p.raw.telemetryFields[it]?.value),clock)},
        p.depthText,evidence(field("depth",p.raw.depth).copy(quality=p.instrument.depthStatus),clock),
        p.instrument.depthStatus,p.weather)
}
