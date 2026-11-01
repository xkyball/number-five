package com.no5.helm.ui

import android.content.SharedPreferences
import com.no5.helm.chart.ChartCameraControl
import com.no5.helm.chart.ChartEngine
import com.no5.helm.model.*
import org.json.JSONObject

/** Only user intent is persisted, never fixes, results or pending camera commands. */
class MapPositionPreferences(private val preferences: SharedPreferences) {
    fun load(): PositionIntent = runCatching {
        val raw = preferences.getString("positionIntentV1", null) ?: return PositionIntent()
        require(raw.length <= 1024)
        val o = JSONObject(raw); require(o.getInt("version") == 1)
        val choice = PositionChoice.valueOf(o.getString("choice"))
        val source = if (o.isNull("sourceId")) null else o.getString("sourceId").also {
            require(it.isNotBlank() && it.length <= 200 && it.none(Char::isISOControl))
        }
        require(choice != PositionChoice.MANUAL || source != null)
        PositionIntent(choice = choice, sourceId = if (choice == PositionChoice.MANUAL) source else null, follow = o.getBoolean("follow"))
    }.getOrDefault(PositionIntent())
    fun save(intent: PositionIntent) {
        val value = JSONObject().put("version", 1).put("choice", intent.choice.name)
            .put("sourceId", intent.sourceId ?: JSONObject.NULL).put("follow", intent.follow)
        preferences.edit().putString("positionIntentV1", value.toString()).apply()
    }
}

class ChartPositionCamera(private val engine: ChartEngine): PositionCamera {
    private val bounds = com.no5.helm.chart.ChartCameraBoundsCache()
    override fun navigationBoundsKnown(): Boolean {
        val state=engine.status.value
        return !state.residentCatalogActive || bounds.get(state.navigationBounds)!=null
    }
    override fun covers(latitude: Double, longitude: Double): Boolean {
        val state = engine.status.value
        return bounds.get(state.navigationBounds)?.contains(latitude, longitude) ?: !state.residentCatalogActive
    }
    override fun center(latitude: Double, longitude: Double, sequence: Long, physicalUptimeMs: Long) =
        engine.submitCameraControl(ChartCameraControl.CENTER, 1.0, sequence, physicalUptimeMs, latitude, longitude)
    override fun center(latitude: Double, longitude: Double, sequence: Long, physicalUptimeMs: Long,
        expectedChartSource: Long, expectedSurface: Long) =
        engine.submitCameraControl(ChartCameraControl.CENTER, 1.0, sequence, physicalUptimeMs, latitude, longitude,
            Double.NaN, expectedChartSource, expectedSurface)
    override fun cancelPending() = engine.cancelCameraControl()
}
