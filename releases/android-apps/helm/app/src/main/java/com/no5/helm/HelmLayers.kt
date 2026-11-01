package com.no5.helm

import android.graphics.Canvas as NativeCanvas
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import com.no5.domain.*

/** Counters count actual recording callbacks, not recompositions or submitted GPU frames. UI owner only. */
internal object HelmRecordCounters {
    var static=0L; private set
    var slow=0L; private set
    var motion=0L; private set
    var band=0L; private set
    var history=0L; private set
    fun recorded(domain:String) {
        when(domain){"static"->static++;"slow"->slow++;"motion"->motion++;"band"->band++;"history"->history++}
        if(BuildConfig.DEBUG)android.os.Trace.setCounter("helm.$domain.records",when(domain){"static"->static;"slow"->slow;"band"->band;"history"->history;else->motion})
    }
    fun snapshot()=listOf(static,slow,motion)
    fun report()="static=$static slow=$slow motion=$motion band=$band history=$history"
}

/** Bounded GPU backing: at 2000x1200 all five retained planes total <24 MB RGBA, not per bin. */
internal data class HelmLayerBounds(val left:Float=0f,val top:Float=0f,val width:Float=2000f,val height:Float=1200f)
@Composable private fun HelmLayer(domain:String,bounds:HelmLayerBounds=HelmLayerBounds(),draw:(NativeCanvas)->Unit) {
    val language=com.no5.ui.LocalNo5Language.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val dx=maxWidth/2000;val dy=maxHeight/1200
        Canvas(Modifier.offset(dx*bounds.left,dy*bounds.top).size(dx*bounds.width,dy*bounds.height)
            .graphicsLayer(compositingStrategy=if(domain=="motion")CompositingStrategy.Auto else CompositingStrategy.Offscreen)) {
            language // Locale participates in the retained draw callback; state/owners remain intact.
            HelmRecordCounters.recorded(domain)
            drawIntoCanvas { canvas ->
                val c=canvas.nativeCanvas;c.save()
                try {c.scale(size.width/bounds.width,size.height/bounds.height);c.translate(-bounds.left,-bounds.top)
                    c.clipRect(bounds.left,bounds.top,bounds.left+bounds.width,bounds.top+bounds.height);draw(c)}
                finally {c.restore()}
            }
        }
    }
}

@Composable internal fun HelmStaticLayer(scene:HelmScene,units:DisplayUnits) {
    val staticUnits=remember(units.speed,units.depth) { DisplayUnits(speed=units.speed,depth=units.depth) }
    HelmLayer("static") { scene.drawStatic(it,staticUnits) }
}
@Composable internal fun HelmSlowLayer(scene:HelmScene,values:SlowHelmPresentation) {
    val retained=rememberUpdatedState(values)
    HelmLayer("slow") { scene.drawSlow(it,retained.value.values) }
}
@Composable internal fun HelmMotionLayer(scene:HelmScene,motion:State<MotionPresentation>) {
    HelmLayer("motion") { c -> val value=motion.value;scene.drawMotionValues(c,value.values,value.reading()) }
}
private data class InstrumentTelemetry(val reading:SpeedDepthInstrumentGeometry.Reading,val units:DisplayUnits,
    val extrema:SpeedExtremaSnapshot,val simulated:Boolean)
@Composable internal fun HelmInstrumentTelemetryLayer(scene:HelmScene,reading:SpeedDepthInstrumentGeometry.Reading,
    units:DisplayUnits,extrema:SpeedExtremaSnapshot,simulated:Boolean) {
    val retained=rememberUpdatedState(InstrumentTelemetry(reading,units,extrema,simulated))
    HelmLayer("slow",HelmLayerBounds(690f,554f,620f,187f)) { c->val value=retained.value;scene.drawInstrumentTelemetry(c,value.reading,value.units,value.extrema,value.simulated) }
}

@Composable internal fun HelmStaticOverlay(scene:HelmScene,units:DisplayUnits) {
    val scaleUnits=remember(units.speed) { DisplayUnits(speed=units.speed) }
    HelmLayer("static",HelmLayerBounds(100f,294f,1800f,450f)) { scene.drawStaticOverlay(it,scaleUnits) }
}

/** One quantized display list, no 121-entry bitmap table. */
internal data class SpeedBandKey(val active:Int,val status:TelemetryStatus)
@Composable internal fun HelmSpeedBandLayer(scene:HelmScene,motion:State<MotionPresentation>) {
    val band=remember(motion) { derivedStateOf {
        val value=motion.value
        SpeedBandKey(value.fraction?.let{kotlin.math.ceil(it*120).toInt()}?:0,value.speedStatus)
    } }
    HelmLayer("band",HelmLayerBounds(770f,334f,460f,160f)) { c ->
        val value=band.value
        // Midpoint avoids floating-point ceil rounding a reconstructed bin into its neighbor.
        scene.drawBand(c,SpeedDepthInstrumentGeometry.Reading(null,null,
            if(value.active==0)0f else (value.active-.5f)/120f,false,value.status,TelemetryStatus.UNAVAILABLE))
    }
}

@Composable internal fun rememberInstrumentHistoryClock(history:DepthHistorySnapshot,active:Boolean):State<Long> {
    val now=remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(history,active) {
        if(active) {
            now.longValue=System.currentTimeMillis()
            val last=history.points.maxOfOrNull{it.timestampEpochMs}
            while(last!=null && last.toDouble()+60_000>=now.longValue)
                withFrameNanos { now.longValue=System.currentTimeMillis() }
        }
    }
    return now
}

@Composable internal fun HelmHistoryLayer(scene:HelmScene,history:DepthHistorySnapshot,status:TelemetryStatus,
    active:Boolean,scale:Float,clock:State<Long>?=null) {
    val now=clock?:rememberInstrumentHistoryClock(history,active)
    Canvas(Modifier.offset((1032*scale).dp,(590*scale).dp).size((73*scale).dp,(38*scale).dp).graphicsLayer()) {
        HelmRecordCounters.recorded("history")
        drawIntoCanvas { canvas ->
            val c=canvas.nativeCanvas;c.save()
            try {c.scale(size.width/73f,size.height/38f);c.translate(-1032f,-590f)
                scene.drawHistory(c,history,status,now.value)} finally {c.restore()}
        }
    }
}
