package com.no5.helm.ocean

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import com.no5.helm.HelmScene
import com.no5.helm.NativeHelmLayout

/**
 * A retained raster layer isolates expensive media from the telemetry Canvas. A graphicsLayer
 * without Offscreen isolates recording only; its shader commands may still execute on every
 * parent frame. Offscreen retains the raster until this layer is invalidated.
 *
 * Read OceanFrame only in this draw block. Telemetry, depth history and source-age ticks are
 * deliberately absent, so the instrument clock cannot invalidate the background.
 * https://developer.android.com/develop/ui/compose/graphics/draw/modifiers#compositing-strategy
 */
@Composable
fun OceanBackgroundLayer(scene: HelmScene, mode: BackgroundMode, reducedMotion: Boolean,
    frame: State<OceanFrame>, modifier: Modifier = Modifier, externalOcean: Boolean = false) {
    Canvas(modifier.graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)) {
        val currentFrame = if (externalOcean) OceanFrame() else frame.value
        drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas
            native.save()
            try {
                native.scale(size.width / NativeHelmLayout.WIDTH, size.height / NativeHelmLayout.HEIGHT)
                native.clipRect(0f, 0f, NativeHelmLayout.WIDTH, NativeHelmLayout.HEIGHT)
                scene.drawBackground(native, mode, reducedMotion, currentFrame, externalOcean)
            } finally {
                native.restore()
            }
        }
    }
}
