package com.no5.helm

import android.util.AtomicFile
import com.no5.helm.chart.ChartViewport
import com.no5.helm.library.ChartSelection
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** A camera belongs to an exact durable selection, never to a transient native generation. */
internal class ChartCameraStore(directory: File) {
    private val file = AtomicFile(File(directory, "chart-camera-v1.json"))
    @Synchronized fun load(selection: ChartSelection): ChartViewport? = runCatching {
        val json = file.openRead().bufferedReader().use { JSONObject(it.readText()) }
        require(json.getInt("version") == 1 && json.getString("packageId") == selection.packageId)
        require(json.getJSONArray("cells").let { cells -> List(cells.length()) { cells.getString(it) } } == selection.cells.sorted())
        ChartViewport(latitude = json.getDouble("latitude"), longitude = json.getDouble("longitude"),
            metresToPixels = json.getDouble("scale"), rotationRadians = json.getDouble("rotation")).also(::validate)
    }.getOrNull()
    @Synchronized fun save(selection: ChartSelection, camera: ChartViewport) {
        validate(camera)
        val bytes = JSONObject().put("version", 1).put("packageId", selection.packageId)
            .put("cells", JSONArray(selection.cells.sorted())).put("latitude", camera.latitude)
            .put("longitude", camera.longitude).put("scale", camera.metresToPixels)
            .put("rotation", camera.rotationRadians).toString().toByteArray(Charsets.UTF_8)
        val stream = file.startWrite()
        try { stream.write(bytes); file.finishWrite(stream) }
        catch (failure: Throwable) { file.failWrite(stream); throw failure }
    }
    private fun validate(camera: ChartViewport) {
        require(camera.latitude.isFinite() && camera.latitude in -85.0..85.0)
        require(camera.longitude.isFinite() && camera.longitude in -180.0..180.0)
        require(camera.metresToPixels.isFinite() && camera.metresToPixels in .00001..10.0)
        require(camera.rotationRadians.isFinite())
    }
}
