package com.no5.helm

import android.app.Application
import android.util.AtomicFile
import androidx.lifecycle.AndroidViewModel
import com.no5.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.json.JSONObject
import java.io.File

/** Retains only UI controllers/history in RAM; durable state contains no readings or commands. */
internal class HelmPresentationViewModel(application: Application) : AndroidViewModel(application) {
    data class Controllers(val audio: AudioNativeController, val sonar: SonarNativeController,
        val depthHistory: DepthHistory = DepthHistory(), val speedExtrema: SpeedExtrema = SpeedExtrema())
    private var identity: No5SourceSession? = null
    private var controllers: Controllers? = null
    private val file = AtomicFile(File(application.filesDir, "helm-view-v1.json"))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val saves = Channel<String>(Channel.CONFLATED)
    private var saved = runCatching { file.openRead().bufferedReader().use { JSONObject(it.readText()) }.also {
        require(it.getInt("version") in 1..4)
    } }.getOrElse { JSONObject().put("version", 4) }
    val page: String get() = saved.optString("page", "HELM").takeIf {
        it in setOf("DASHBOARD", "HELM", "AUDIO", "SONAR", "SETTINGS")
    } ?: "HELM"
    private val writer = scope.launch {
            for (value in saves) {
                try {
                    val output = file.startWrite()
                    try { output.write(value.toByteArray(Charsets.UTF_8)); file.finishWrite(output) }
                    catch (failure: Exception) { file.failWrite(output); throw failure }
                } catch (failure: Exception) {
                    android.util.Log.w("No5ViewState", "View state could not be saved")
                }
            }
    }
    fun acquire(session: No5SourceSession, presentation: AudioPresentationSettings): Controllers {
        if (identity === session) return checkNotNull(controllers)
        controllers?.let {
            // A source replacement can precede Compose's state-saving effect.
            // Capture the outgoing owners before disposal, rather than
            // restoring an older selection/display and then persisting it.
            save(page, it.audio.exportPresentationState(), it.sonar.exportPresentationState())
            try{it.audio.dispose()}finally{it.sonar.dispose()}
        }
        // Drop the outgoing owners before constructing replacements. Compose may still
        // retain its old controller briefly; resetAcquisition has released its payload pages.
        controllers=null;identity=null
        val next = Controllers(AudioNativeController(source = session.audio, initialPresentation = presentation), SonarNativeController(session.sonar))
        next.audio.restorePresentationState(AudioViewState(saved.optInt("selectedFader", 0)))
        saved.optJSONObject("sonar")?.let { json -> runCatching {
            val display = SonarDisplay(
                palette = SonarPalette.valueOf(json.getString("palette")), gainDb = json.getDouble("gain"),
                gamma = json.getDouble("gamma"), threshold = json.getDouble("threshold"),
                rangeMode = SonarRangeMode.valueOf(json.getString("rangeMode")), rangeM = if (json.isNull("range")) null else json.getDouble("range"),
                cropStart = json.getDouble("cropStart"), cropEnd = json.getDouble("cropEnd"), zoom = json.getBoolean("zoom"),
                scrollSpeed = json.getDouble("scrollSpeed"), historySeconds = json.getDouble("historySeconds"),
                aScope = json.getBoolean("aScope"), freeze = false,
                overlayBottom = json.getBoolean("overlayBottom"), overlayTargets = json.getBoolean("overlayTargets"),
                gainMode = SonarImageMode.valueOf(json.optString("gainMode", "MANUAL")),
                gammaMode = SonarImageMode.valueOf(json.optString("gammaMode", "MANUAL")),
                thresholdMode = SonarImageMode.valueOf(json.optString("thresholdMode", "MANUAL")),
                manualGainDb = json.optDouble("manualGain").takeIf{it.isFinite()},
                manualGamma = json.optDouble("manualGamma").takeIf{it.isFinite()},
                manualThreshold = json.optDouble("manualThreshold").takeIf{it.isFinite()},
                exactPingProfiles = json.optBoolean("exactPingProfiles",false),
                autoGainOffsetDb = json.optDouble("autoGainOffsetDb",0.0).coerceIn(-24.0,24.0),
                startDepthM = if(saved.getInt("version") >= 4) json.optDouble("startDepthM",0.0)
                    .takeIf { it.isFinite() && it >= 0.0 } ?: 0.0 else 0.0)
            next.sonar.restorePresentationState(SonarViewState(display, runCatching{SonarKnobMode.valueOf(json.optString("knobMode","SONAR"))}.getOrDefault(SonarKnobMode.SONAR), json.getBoolean("interpolation"), depthSettings = json.optJSONObject("depthProcessing").let { depth ->
                SonarDepthSettings(runCatching { SonarDepthMode.valueOf(depth?.optString("mode") ?: "SMOOTHED") }.getOrDefault(SonarDepthMode.SMOOTHED),
                    (depth?.optInt("windowSeconds",5) ?: 5).coerceIn(1,30))
            }))
        } }
        identity = session; controllers = next
        return next
    }
    fun save(page: String, audio: AudioViewState, sonar: SonarViewState) {
        require(page in setOf("DASHBOARD", "HELM", "AUDIO", "SONAR", "SETTINGS"))
        val d = sonar.display
        val value = JSONObject().put("version", 4).put("page", page).put("selectedFader", audio.selectedPhysicalFader)
            .put("sonar", JSONObject().put("palette", d.palette.name).put("gain", d.gainDb).put("gamma", d.gamma)
                .put("threshold", d.threshold).put("rangeMode", d.rangeMode.name).put("range", d.rangeM ?: JSONObject.NULL)
                .put("cropStart", d.cropStart).put("cropEnd", d.cropEnd).put("zoom", d.zoom).put("scrollSpeed", d.scrollSpeed)
                .put("historySeconds", d.historySeconds).put("aScope", d.aScope).put("overlayBottom", d.overlayBottom)
                .put("overlayTargets", d.overlayTargets).put("gainMode",d.gainMode.name).put("gammaMode",d.gammaMode.name)
                .put("thresholdMode",d.thresholdMode.name).put("manualGain",d.manualGainDb?:JSONObject.NULL)
                .put("manualGamma",d.manualGamma?:JSONObject.NULL).put("manualThreshold",d.manualThreshold?:JSONObject.NULL)
                .put("exactPingProfiles",d.exactPingProfiles).put("autoGainOffsetDb",d.autoGainOffsetDb).put("startDepthM",d.startDepthM)
                .put("knobMode", sonar.knobMode.name).put("interpolation", sonar.linearInterpolation)
                .put("depthProcessing", JSONObject().put("mode",sonar.depthSettings.mode.name).put("windowSeconds",sonar.depthSettings.windowSeconds)))
        saved = value
        saves.trySend(value.toString())
    }
    override fun onCleared() {
        controllers?.let { it.audio.dispose(); it.sonar.dispose() }; controllers = null; identity = null
        saves.close(); writer.invokeOnCompletion { scope.cancel() }
        super.onCleared()
    }
}
