package com.no5.preparation.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.StateFlow

@Immutable data class Wgs84(val latitude: Double, val longitude: Double) {
    init { require(latitude.isFinite() && latitude in -90.0..90.0); require(longitude.isFinite() && longitude in -180.0..180.0) }
}
@Immutable data class ScreenPixel(val x: Float, val y: Float)
/** Coordinates are LOCAL pixels of the measured slot, never full-display design coordinates. */
@Immutable data class ViewportSnapshot(val revision: Long, val widthPx: Int, val heightPx: Int, val cameraToken: String) {
    init { require(widthPx > 0 && heightPx > 0) }
}
@Immutable data class ProjectedPoint(val id: String, val point: ScreenPixel)
@Immutable data class ProjectionBatch(val viewport: ViewportSnapshot, val points: FrozenList<ProjectedPoint>)
@Immutable data class GeoObject(val id: String, val position: Wgs84)
@Immutable data class ScaleBar(val lengthPx: Float, val label: String, val viewport: ViewportSnapshot)
sealed interface RendererEvent {
    data class FrameConfirmed(val viewport: ViewportSnapshot, val scale: ScaleBar?) : RendererEvent
    data class GeoTapped(val position: Wgs84, val viewport: ViewportSnapshot) : RendererEvent
    data object GestureStarted : RendererEvent
    data object PanStarted : RendererEvent
    data class TouchActivityChanged(val active:Boolean) : RendererEvent
    data class Failed(val message: String) : RendererEvent
}
sealed interface RendererCommand {
    data class Zoom(val factor: Double, val anchor: ScreenPixel, val viewport: ViewportSnapshot) : RendererCommand
    data class Center(val position: Wgs84, val follow: Boolean) : RendererCommand
}
/** Adapter owns one renderer context, lifecycle, gestures and serialized commands. UI never projects. */
interface ChartRendererAdapter {
    @Composable fun Content(modifier: Modifier, onEvent: (RendererEvent) -> Unit)
    suspend fun execute(command: RendererCommand)
    suspend fun project(objects: FrozenList<GeoObject>, viewport: ViewportSnapshot): ProjectionBatch?
    suspend fun unproject(point: ScreenPixel, viewport: ViewportSnapshot): Wgs84?
}
@Immutable data class BackendSnapshot(
    val sourceId: String, val simulated: Boolean, val sequence: Long,
    val measuredAtEpochMillis: Long?, val receivedAtMonotonicMillis: Long,
    val ownPosition: Wgs84?, val status: DataStatus,
)
/** Read-only normalization boundary for SignalK or another gateway; no vessel-control operation. */
interface VesselBackend { val snapshots: StateFlow<BackendSnapshot> }

fun ProjectionBatch.matches(frame: ViewportSnapshot?) = frame != null && viewport == frame
