package com.no5.helm

import com.no5.domain.*
import java.util.Locale
import kotlin.math.abs

internal fun renderValues(raw:HelmState, nowEpochMs:Long=System.currentTimeMillis(), freshness:FreshnessPolicy=QualityEvaluator.SHARED, weather:WeatherSnapshot=WeatherSnapshot.unavailable(), weatherPolicy:FreshnessPolicy=QualityEvaluator.SHARED, units:DisplayUnits=DisplayUnits()):HelmRenderValues{
    val s = raw.copy(quality = raw.quality.copy(status = QualityEvaluator.evaluate(raw.quality, nowEpochMs, freshness)))
    val lost=s.quality.status in setOf(TelemetryStatus.LOST,TelemetryStatus.INVALID,TelemetryStatus.UNAVAILABLE)
    val weatherView = WeatherPresentation.from(weather, nowEpochMs, weatherPolicy)
    val portFlow=s.portFuelFlowLph;val stbdFlow=s.starboardFuelFlowLph
    fun shown(value:Double?,d:Int=1)=if(value == null || !value.isFinite())"—"else format(value,d)
    fun field(name:String,projection:Double?)=raw.displayField(name,projection,nowEpochMs,freshness)
    fun shownField(name:String,projection:Double?,d:Int=1):String {
        val value=field(name,projection)?:return "—"
        return format(value,d)+if(raw.fieldStatus(name,nowEpochMs,freshness)==TelemetryStatus.STALE)"*" else ""
    }
    fun shownScaled(name:String,projection:Double?,convert:(Double)->Double):String {
        val value=field(name,projection)?:return "—"
        return format(convert(value))+if(raw.fieldStatus(name,nowEpochMs,freshness)==TelemetryStatus.STALE)"*" else ""
    }
    fun fresh(name:String,projection:Double?):Double? {
        val observed=if(raw.telemetryFields.isEmpty())projection else raw.telemetryFields[name]?.value
        return field(name,observed)?.takeIf{raw.fieldStatus(name,nowEpochMs,freshness)==TelemetryStatus.LIVE}
    }
    val flowQuality=raw.fieldStatus("totalFuelFlowLph",nowEpochMs,freshness)
    val consumption=units.consumption.instantaneous(fresh("speed",s.speed),fresh("totalFuelFlowLph",s.totalFuelFlowLph),flowQuality)
    val tripConsumption=units.consumption.trip(fresh("tripDistanceNm",s.tripDistanceNm),fresh("fuelUsedLiters",s.fuelUsedLiters),fresh("tripDurationSeconds",s.tripDurationSeconds),TelemetryStatus.LIVE)
    val tripSeconds=field("tripDurationSeconds",s.tripDurationSeconds)
    val heading=field("heading",s.heading)
    val hasStaleField=raw.telemetryFields.keys.any{raw.fieldStatus(it,nowEpochMs,freshness)==TelemetryStatus.STALE}

    fun staleMark(name:String)=if(raw.fieldStatus(name,nowEpochMs,freshness)==TelemetryStatus.STALE)"*" else ""
    fun groupStatus(vararg names:String):String {
        val statuses=names.map{raw.fieldStatus(it,nowEpochMs,freshness)}
        return listOf(TelemetryStatus.INVALID,TelemetryStatus.LOST,TelemetryStatus.UNAVAILABLE,TelemetryStatus.STALE)
            .firstOrNull{it in statuses}?.name ?: "LIVE"
    }
    fun coordinate(value: Double?, latitude: Boolean): String {
        if (value == null || !value.isFinite()) return "—"
        val degrees = abs(value).toInt()
        val direction = if(latitude) { if(value < 0) "S" else "N" } else { if(value < 0) "W" else "E" }
        return "$direction ${degrees}°${format((abs(value)-degrees)*60,3).padStart(6,'0')}′${staleMark(if(latitude)"latitudeDegrees" else "longitudeDegrees")}"
    }
    val sourceLabel = if(s.quality.simulated) "SIM" else "SOURCE"

    val text=mapOf(
        "header-source-label" to if(hasStaleField) "* STALE FIELD" else if(s.quality.simulated) "NETWORK SIMULATION" else "SOURCE TELEMETRY",
        "header-source-detail" to if(lost) s.quality.status.name else if(s.quality.demo) { if(s.running) "SIMULATOR RUNNING" else "SIMULATOR PAUSED" } else "${s.quality.status} · ${if(s.running) s.simulationPhase ?: "RECEIVING" else "PAUSED"}",
        "deck-airTemp" to weatherView.airTemperature,
        "deck-wind" to weatherView.windSpeed,
        "deck-humidity" to weatherView.humidity,
        "deck-pressure" to weatherView.pressure,
        "deck-evidence-environment" to weatherView.evidenceLabel,
        "rpm-port" to shownField("portRpm",s.portRpm,0),"rpm-stbd" to shownField("starboardRpm",s.starboardRpm,0),
        "hud-sog" to shownScaled("speed",s.speed,units.speed::fromKnots),"hud-depth" to shownScaled("depth",s.depth,units.depth::fromMeters),
        "hud-sog-unit" to units.speed.symbol, "hud-depth-unit" to units.depth.symbol,
        "secondary-port-flow" to shownField("portFuelFlowLph",portFlow),"secondary-stbd-flow" to shownField("starboardFuelFlowLph",stbdFlow),
        "secondary-port-gear" to if(field("portRpm",s.portRpm)==null)"—"else(s.portGear ?: "—"),"secondary-stbd-gear" to if(field("starboardRpm",s.starboardRpm)==null)"—"else(s.starboardGear ?: "—"),
        "secondary-port-coolant" to shownField("portCoolantC",s.portCoolantC,0)+"°C","secondary-stbd-coolant" to shownField("starboardCoolantC",s.starboardCoolantC,0)+"°C",
        "secondary-port-oil" to shownField("portOilBar",s.portOilBar)+" bar","secondary-stbd-oil" to shownField("starboardOilBar",s.starboardOilBar)+" bar",
        "deck-latitude" to coordinate(field("latitudeDegrees",s.latitudeDegrees),true),"deck-longitude" to coordinate(field("longitudeDegrees",s.longitudeDegrees),false),
        "secondary-port-trim" to shownField("portTrim",s.portTrim,0)+"%","secondary-stbd-trim" to shownField("starboardTrim",s.starboardTrim,0)+"%",
        "secondary-port-voltage" to shownField("portVoltage",s.portVoltage)+" V","secondary-stbd-voltage" to shownField("starboardVoltage",s.starboardVoltage)+" V",
        "deck-fuelPct" to shownField("portFuel",s.portFuel,0),"deck-remainingL" to shownField("fuelRemainingLiters",s.fuelRemainingLiters,0),
        "deck-totalFlow" to shown(consumption),
        "deck-label-3" to "CONSUMPTION",
        "deck-unit-totalFlow" to units.consumption.symbol,"deck-cog" to shownField("courseOverGroundDegrees",s.courseOverGroundDegrees,0),
        "deck-evidence-position" to "$sourceLabel · ${groupStatus("latitudeDegrees","longitudeDegrees")}",
        "deck-tripDistance" to shownScaled("tripDistanceNm",s.tripDistanceNm,units.speed::distanceFromNm),
        "deck-unit-tripDistance" to units.speed.distanceSymbol,
        "deck-unit-rangeNm" to units.speed.distanceSymbol, "deck-unit-waypointNm" to units.speed.distanceSymbol, "deck-unit-rangeSpeed" to units.speed.symbol,
        "deck-tripDuration" to (tripSeconds?.let { "%02d:%02d:%02d".format(Locale.US,(it/3600).toInt(),(it/60).toInt()%60,it.toInt()%60)+staleMark("tripDurationSeconds") } ?: "—"),
        "deck-fuelUsed" to shownField("fuelUsedLiters",s.fuelUsedLiters),
        "deck-efficiency" to shown(tripConsumption,2),
        "deck-label-26" to "TRIP AVERAGE",
        "deck-unit-efficiency" to units.consumption.symbol,
        "deck-evidence-trip" to "$sourceLabel · ${groupStatus("tripDistanceNm","tripDurationSeconds","fuelUsedLiters")}",
        "deck-fuel-evidence" to if(s.quality.demo) "DEMO · SIMULATED FUEL · NO TANK CAPACITY SET" else if(s.quality.simulated) "SIMULATED VOYAGE · ${s.simulationPhase ?: "FUEL"}" else "SOURCE · ${s.quality.status.name}",
        "deck-flow-quality" to "$sourceLabel · ${if(units.consumption==ConsumptionUnit.LPH) groupStatus("totalFuelFlowLph") else groupStatus("speed","totalFuelFlowLph")}")
    return HelmRenderValues(
        (field("portRpm",s.portRpm)?:0.0).toFloat(),(field("starboardRpm",s.starboardRpm)?:0.0).toFloat(),
        (field("portTrim",s.portTrim)?:0.0).toFloat(),(field("starboardTrim",s.starboardTrim)?:0.0).toFloat(),
        (heading?:0.0).toFloat(),(field("portFuel",s.portFuel)?:0.0).toFloat(),s.running,
        if(s.quality.simulated && s.quality.status==TelemetryStatus.LIVE)"SIMULATED" else s.quality.status.name,
        text,heading!=null,s.quality.simulated,field("portFuel",s.portFuel)!=null)

}

/** Equality contains only visible slow values, never interpolated RPM/heading or source timestamps. */
internal data class SlowHelmPresentation(val values:HelmRenderValues)
internal data class MotionPresentation(val values:HelmRenderValues,val speed:Double?,val fraction:Float?,
    val aboveScale:Boolean,val speedStatus:TelemetryStatus) {
    fun reading()=SpeedDepthInstrumentGeometry.Reading(speed,null,fraction,aboveScale,speedStatus,TelemetryStatus.UNAVAILABLE)
}
private val motionKeys=setOf("rpm-port","rpm-stbd","hud-sog","secondary-port-trim","secondary-stbd-trim","deck-cog")
internal fun slowPresentation(raw:HelmState,now:Long,policy:FreshnessPolicy,weather:WeatherSnapshot,
    weatherPolicy:FreshnessPolicy,units:DisplayUnits):SlowHelmPresentation {
    val v=renderValues(raw,now,policy,weather,weatherPolicy,units)
    return SlowHelmPresentation(v.copy(portRpm=0f,stbdRpm=0f,portTrim=0f,stbdTrim=0f,heading=0f,
        headingAvailable=false,text=v.text.filterKeys{it !in motionKeys}))
}
internal fun motionPresentation(raw:HelmState,now:Long,policy:FreshnessPolicy,units:DisplayUnits):MotionPresentation {
    fun value(name:String,projection:Double?)=raw.displayField(name,projection,now,policy)
    fun shown(name:String,projection:Double?,digits:Int=0):String {
        val number=value(name,projection)?:return "—"
        return format(number,digits)+if(raw.fieldStatus(name,now,policy)==TelemetryStatus.STALE)"*" else ""
    }
    val status=QualityEvaluator.evaluate(raw.quality,now,policy)
    val heading=value("heading",raw.heading)
    val v=HelmRenderValues(
        portRpm=(value("portRpm",raw.portRpm)?:0.0).toFloat(),stbdRpm=(value("starboardRpm",raw.starboardRpm)?:0.0).toFloat(),
        portTrim=(value("portTrim",raw.portTrim)?:0.0).toFloat(),stbdTrim=(value("starboardTrim",raw.starboardTrim)?:0.0).toFloat(),
        heading=(heading?:0.0).toFloat(),headingAvailable=heading!=null,demo=raw.quality.simulated,
        quality=if(raw.quality.simulated&&status==TelemetryStatus.LIVE)"SIMULATED" else status.name,
        text=mapOf("rpm-port" to shown("portRpm",raw.portRpm),"rpm-stbd" to shown("starboardRpm",raw.starboardRpm),
            "secondary-port-trim" to shown("portTrim",raw.portTrim)+"%","secondary-stbd-trim" to shown("starboardTrim",raw.starboardTrim)+"%",
            "deck-cog" to shown("courseOverGroundDegrees",raw.courseOverGroundDegrees)))
    val reading=SpeedDepthInstrumentGeometry.reading(raw,units,v.quality,now,policy)
    return MotionPresentation(v,reading.speed,reading.fraction,reading.aboveScale,reading.speedStatus)
}

