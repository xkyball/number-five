package com.no5.helm

import com.no5.domain.*
import com.no5.ui.No5Text
import kotlin.math.roundToInt
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState

/** The instrument and its dashboard consumer use one formatter, including unknown/negative values. */
internal fun instrumentNumber(value: Double?): String = value?.takeIf { it.isFinite() && it >= 0.0 }
    ?.let { format(it, 1) } ?: "—"

internal fun instrumentSpeedFlag(reading:SpeedDepthInstrumentGeometry.Reading):String? = when {
    reading.aboveScale&&reading.speedStatus==TelemetryStatus.STALE -> No5Text.ui("STALE · ABOVE SCALE")
    reading.aboveScale -> No5Text.ui("ABOVE SCALE")
    reading.speedStatus==TelemetryStatus.STALE -> No5Text.ui("STALE")
    else -> null
}

internal data class HelmPanelPresentation(
    val raw: HelmState,
    val nowEpochMs: Long,
    val policy: FreshnessPolicy,
    val slow: SlowHelmPresentation,
    val motion: MotionPresentation,
    val instrument: SpeedDepthInstrumentGeometry.Reading,
    val weather: WeatherPresentation,
) {
    val speedText get() = instrumentNumber(motion.speed)
    val depthText get() = instrumentNumber(instrument.depth)
    fun text(key: String): String = motion.values.text[key] ?: slow.values.text[key] ?: "—"
    fun fieldEvidence(name:String):String {
        val observation=raw.fieldObservation(name,null)
        return (if(observation.quality.simulated)"SIMULATED" else "SOURCE")+" · "+raw.fieldStatus(name,nowEpochMs,policy).name
    }
    /** Local safety rails use the same rendered numbers and original field evidence as the body. */
    val headerValues:HelmRenderValues=slow.values.copy(
        portRpm=motion.values.portRpm,stbdRpm=motion.values.stbdRpm,
        portTrim=motion.values.portTrim,stbdTrim=motion.values.stbdTrim,heading=motion.values.heading,
        headingAvailable=motion.values.headingAvailable,
        text=slow.values.text+motion.values.text+mapOf(
            "hud-sog" to (speedText+if(motion.speed!=null&&motion.speedStatus==TelemetryStatus.STALE)"*" else ""),
            "hud-depth" to (depthText+if(instrument.depth!=null&&instrument.depthStatus==TelemetryStatus.STALE)"*" else ""),
            "header-quality-portRpm" to fieldEvidence("portRpm"),
            "header-quality-starboardRpm" to fieldEvidence("starboardRpm"),
            "header-quality-speed" to fieldEvidence("speed"),
        ))
}

/** Callers inject the exact detail-panel objects; this projection does not interpolate or read sources. */
internal fun helmPanelPresentation(raw:HelmState,nowEpochMs:Long,policy:FreshnessPolicy,
    slow: SlowHelmPresentation, motion: MotionPresentation,
    instrument: SpeedDepthInstrumentGeometry.Reading, weather: WeatherPresentation) =
    HelmPanelPresentation(raw,nowEpochMs,policy,slow,motion,instrument,weather)

private data class HelmPanelInputs(val raw:HelmState,val now:Long,val policy:FreshnessPolicy,
    val slow:SlowHelmPresentation,val instrument:SpeedDepthInstrumentGeometry.Reading,val weather:WeatherPresentation)

/** Keep the observer identity across raw updates. Replacing this State on each
 * input discards the dashboard's derived equality and invalidates every widget.
 * Current original raw evidence and motion are still read on every real change.
 */
@Composable internal fun rememberHelmPanelPresentation(raw:HelmState,now:Long,policy:FreshnessPolicy,
    slow:SlowHelmPresentation,motion:State<MotionPresentation>,instrument:SpeedDepthInstrumentGeometry.Reading,
    weather:WeatherPresentation):State<HelmPanelPresentation> {
    val inputs=rememberUpdatedState(HelmPanelInputs(raw,now,policy,slow,instrument,weather))
    return remember(motion) { derivedStateOf {
        val latest=inputs.value
        helmPanelPresentation(latest.raw,latest.now,latest.policy,latest.slow,motion.value,latest.instrument,latest.weather)
    } }
}

internal data class InstrumentHistoryFrame(val trace: SpeedDepthInstrumentGeometry.Trace, val scrollFraction: Float)
internal fun instrumentHistoryFrame(cache: InstrumentHistoryTraceCache, history: DepthHistorySnapshot, now: Long): InstrumentHistoryFrame {
    val trace=cache.update(history,now)
    return InstrumentHistoryFrame(trace,((now.toDouble()-cache.anchorEndMs)/60_000.0).toFloat())
}

/** Source provenance and freshness are separate, even for a simulated source. */
internal fun audioPanelSourceLabel(controller: AudioNativeController): String {
    val quality=No5Text.ui(controller.state.source.quality.status.name)
    return if(controller.isSimulation)No5Text.ui("SIMULATED")+" · "+quality else quality
}

internal data class SonarPanelPresentation(
    val sourceLabel: String,
    val qualityLabel: String,
    val interpretationLabel: String,
    val depthLabel: String,
    val rangeLabel: String,
    val gainLabel: String,
    val viewPingId: Long?,
    val viewTimestampEpochMs: Long?,
    val frozen: Boolean,
    val depthStatusLabel: String,
)

/** Label policy shared by SONAR's native fascia and DASHBOARD. The image evidence belongs
 * to viewRing, which may be frozen while the live acquisition/depth continue independently. */
internal fun sonarPanelPresentation(controller: SonarNativeController, unit: DepthUnit): SonarPanelPresentation {
    val s=controller.snapshot
    val m=controller.model
    val frozen=s.display.freeze
    val ring=m.viewRing
    val hasView=ring.count>0
    val geometry=if(frozen&&hasView)ring.geometryAt(0) else m.geometry
    val presentation=m.viewPresentation
    val meters=m.viewMetersPerBin
    val calibration=m.viewCalibration
    val validBottom=if(frozen)m.viewBottomValid else m.bottomValid
    val quality=when(s.quality) {
        SonarQuality.OFFLINE -> if(s.ringCount>0)No5Text.ui("OFFLINE · HISTORY RETAINED") else No5Text.ui("SOURCE UNAVAILABLE")
        SonarQuality.DEGRADED -> if(controller.sourceStatus.stale)No5Text.ui("STALE · HISTORY RETAINED") else No5Text.ui(if(m.selectedPingSource==SonarPingSource.OWN)"Keine eigenen Pings" else "Keine Pings")
        SonarQuality.IDLE -> if(s.connected&&s.running)No5Text.ui(if(m.selectedPingSource==SonarPingSource.OWN)"Keine eigenen Pings" else "Keine Pings") else if(s.connected)No5Text.ui("ACQUISITION STOPPED") else No5Text.ui("UNAVAILABLE")
        SonarQuality.ERROR -> No5Text.ui("ACQUISITION ERROR")
        else -> if(frozen)No5Text.ui("VIEW FROZEN · %s",No5Text.ui(if(s.running)"Messbetrieb läuft" else "STOPPED")) else No5Text.ui("Messbetrieb läuft")
    }
    val fresh=controller.liveEvidence
    val interpretation=if(!fresh) No5Text.ui("Letzter bestätigter Befund") else if(geometry==null) {
        if(meters==null)No5Text.ui("CALIBRATION REQUIRED")
        else if(!validBottom)No5Text.ui("BOTTOM UNVALIDATED")
        else No5Text.ui("CALIBRATED · %s",calibration.depthReference.name)
    } else if(meters==null)No5Text.ui("TX REFERENCE UNVERIFIED")
    else sonarAnalysisQualityLabel(presentation?.quality)+" · "+No5Text.ui(if(geometry.txReference==SonarTxReference.EMPIRICAL_FALLBACK)"Empirischer Sendebezug" else "Host-Sendebezug")
    val bounds=controller.bounds()
    val range=if(!bounds.available)"${No5Text.ui("Anzeige ab")} ${sonarNumber(unit.fromMeters(s.display.startDepthM),2)} ${unit.symbol} · —"
        else if(meters!=null)"${sonarNumber(unit.fromMeters(bounds.start*meters))}–${sonarNumber(unit.fromMeters(bounds.end*meters))} ${unit.symbol}"
        else "${bounds.start.roundToInt()}–${bounds.end.roundToInt()} BINS"
    return SonarPanelPresentation(
        if(s.simulated||s.demo)No5Text.ui("SIMULATED") else controller.sourceLabel(),
        quality,interpretation,
        controller.depthDisplay.valueM?.let { No5Text.ui("DEPTH %s %s",sonarNumber(unit.fromMeters(it),2),unit.symbol) } ?: No5Text.ui("DEPTH —"),
        range,sonarGainLabel(s.display),
        if(hasView)ring.pingIdAt(0) else null,
        if(hasView)ring.timestampAt(0) else null,
        frozen,
        No5Text.ui(when(controller.depthDisplay.state) {
            SonarDepthState.CURRENT -> "Aktuell"
            SonarDepthState.HELD -> "Überbrückt"
            SonarDepthState.UNAVAILABLE -> "Keine Tiefe"
        }),
    )
}

internal fun sonarAnalysisQualityLabel(quality:SonarAnalysisQuality?):String=No5Text.ui(when(quality) {
    SonarAnalysisQuality.LOCKED->"Tiefenauswahl bestätigt"
    SonarAnalysisQuality.WAITING->"Analyse wartet"
    SonarAnalysisQuality.SEARCHING->"Tiefensuche"
    SonarAnalysisQuality.UNSTABLE->"Tiefe instabil"
    SonarAnalysisQuality.UNVERIFIED_TX->"Sendebezug unbestätigt"
    null->"Analyse ausstehend"
})

/** Dashboard text equality contains painted strings and original evidence only.
 * Subpixel engine-band motion stays in Canvas; it cannot invalidate unrelated
 * dashboard text. No source values, clocks or freshness thresholds are changed. */
internal data class DashboardMotionLabels(val portRpm:String,val starboardRpm:String,val course:String,
    val heading:String,val speed:String,val port:DashboardField,val starboard:DashboardField,
    val courseField:DashboardField,val headingField:DashboardField,val speedField:DashboardField,val speedFlag:String?)
internal fun dashboardMotionLabels(p:HelmPanelPresentation):DashboardMotionLabels {
    val raw=p.raw
    fun field(name:String,value:Double?)=dashboardField(raw,name,value,p.nowEpochMs,p.policy)
    return DashboardMotionLabels(p.text("rpm-port"),p.text("rpm-stbd"),p.text("deck-cog")+"°",
        if(p.motion.values.headingAvailable)p.motion.values.heading.roundToInt().toString()+"°"else"—°",p.speedText,
        field("portRpm",raw.portRpm),field("starboardRpm",raw.starboardRpm),field("courseOverGroundDegrees",raw.courseOverGroundDegrees),
        field("heading",raw.heading),field("speed",raw.speed).copy(quality=p.motion.speedStatus),instrumentSpeedFlag(p.motion.reading()))
}
internal data class DashboardSlowPresentation(val raw:HelmState,val nowEpochMs:Long,val policy:FreshnessPolicy,
    val slow:SlowHelmPresentation,val instrument:SpeedDepthInstrumentGeometry.Reading,val weather:WeatherPresentation) {
    fun text(key:String)=slow.values.text[key]?:"—"
    val depthText get()=instrumentNumber(instrument.depth)
}
internal fun dashboardSlowPresentation(p:HelmPanelPresentation)=DashboardSlowPresentation(p.raw,p.nowEpochMs,p.policy,p.slow,p.instrument,p.weather)

internal fun sonarGainLabel(d: com.no5.domain.SonarDisplay): String =
    if(d.gainMode==com.no5.domain.SonarImageMode.AUTO) "AUTO · %+.0f dB".format(java.util.Locale.ROOT,d.autoGainOffsetDb)
    else "${sonarNumber(d.gainDb,0)} dB"

/** Only fields actually consumed by drawSharedHeader participate in chrome invalidation.
 * Body-only motion/trim/weather fields retain their owners and remain fully available elsewhere.
 */
internal fun headerChromeValues(values:HelmRenderValues)=HelmRenderValues(
    quality=values.quality,demo=values.demo,text=values.text.filterKeys{it in headerChromeKeys})
private val headerChromeKeys=setOf("rpm-port","rpm-stbd","hud-sog","hud-sog-unit",
    "header-quality-portRpm","header-quality-starboardRpm","header-quality-speed","header-source-label")
