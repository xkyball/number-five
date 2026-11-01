package com.no5.helm

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** Transient, drawing-only A/B hook. It owns no model, input or persistence. */
internal val LocalSoundHeaderCacheEnabled=staticCompositionLocalOf { true }

/** Retain the small opaque header/footer while editing sound. Current telemetry
 * invalidates its header; contours, media and controls retain their final target.
 */
@Composable
internal fun SoundHeaderLayer(
    scene:HelmScene,night:Boolean,scale:Float,values:State<HelmRenderValues>,
    status:()->String,description:String,cached:Boolean,
) {
    val modifier=Modifier.fillMaxSize().semantics { contentDescription=description }
    if(!cached) {
        Canvas(modifier.graphicsLayer()) {
            drawIntoCanvas { canvas ->
                val c=canvas.nativeCanvas
                c.save();c.scale(size.width/2000f,size.height/1200f);c.clipRect(0f,0f,2000f,1200f)
                c.drawColor(if(night)android.graphics.Color.BLACK else android.graphics.Color.rgb(246,249,250))
                scene.drawChrome(c,"AUDIO",values.value,status())
                c.restore()
            }
        }
    } else {
        Canvas(modifier) { drawRect(if(night)Color.Black else Color(0xfff6f9fa)) }
        Canvas(Modifier.fillMaxWidth().height((130f*scale).dp).graphicsLayer {
            compositingStrategy=CompositingStrategy.Offscreen
        }) {
            drawIntoCanvas { canvas ->
                val c=canvas.nativeCanvas
                c.save();val unit=size.width/2000f;c.scale(unit,unit)
                scene.drawChromeRetainedHeader(c,values.value,status())
                c.restore()
            }
        }
        Canvas(Modifier.fillMaxWidth().offset(y=(1058f*scale).dp).height((142f*scale).dp).graphicsLayer {
            compositingStrategy=CompositingStrategy.Offscreen
        }) {
            drawIntoCanvas { canvas ->
                val c=canvas.nativeCanvas
                c.save();val unit=size.width/2000f;c.scale(unit,unit);c.translate(0f,-1058f)
                c.clipRect(0f,1058f,2000f,1200f)
                c.drawColor(if(night)android.graphics.Color.BLACK else android.graphics.Color.rgb(246,249,250))
                scene.drawChromeRetainedFooter(c)
                c.restore()
            }
        }
        Canvas(Modifier.fillMaxSize().graphicsLayer()) {
            drawIntoCanvas { canvas ->
                val c=canvas.nativeCanvas
                c.save();c.scale(size.width/2000f,size.height/1200f);c.clipRect(0f,0f,2000f,1200f)
                scene.drawChromeOutline(c)
                c.restore()
            }
        }
    }
}
