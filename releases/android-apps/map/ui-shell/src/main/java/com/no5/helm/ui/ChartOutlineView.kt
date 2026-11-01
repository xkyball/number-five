package com.no5.helm.ui
import android.content.Context
import android.graphics.*
import android.view.View
import androidx.compose.ui.geometry.Rect
import com.no5.helm.chart.*
import kotlin.math.*

/** Normal child View invalidates itself; cached drawLines, never JNI/file I/O/feature traversal. */
internal class ChartOutlineView(context:Context):View(context) {
    var outlines:ChartOutlines?=null
    var preview:ChartTouchPreview?=null
        set(value){field=value;visibility=if(value?.showing==true)VISIBLE else INVISIBLE;invalidate()}
    init { visibility=INVISIBLE }
    var geometry:ChartSurfaceGeometry?=null
    var blocked:List<Rect> = emptyList()
    private val coast=Paint().apply { color=Color.WHITE;style=Paint.Style.STROKE }
    private val label=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.YELLOW;textSize=14*resources.displayMetrics.scaledDensity }
    override fun onDraw(canvas:Canvas) {
        val state=preview?.takeIf{it.showing}?:return;val cache=outlines?:return;val fit=geometry?:return
        val camera=state.camera;val scale=camera.metresToPixels*fit.scale;val angle=camera.rotationRadians
        val radius=6378137.0*.9996
        fun north(lat:Double)=ln(tan(PI/4+Math.toRadians(lat)/2))*radius
        val delta=((cache.origin.longitude-camera.longitude+180)%360+360)%360-180
        val east=Math.toRadians(delta)*radius*camera.metresToPixels;val n=(north(cache.origin.latitude)-north(camera.latitude))*camera.metresToPixels
        val center=fit.toSurface(ScreenPoint(camera.width/2.0+east*cos(angle)+n*sin(angle),camera.height/2.0-n*cos(angle)+east*sin(angle)))
        canvas.drawColor(Color.rgb(37,51,58));coast.strokeWidth=(2/scale).toFloat()
        val save=canvas.save();val m=Matrix().apply{setValues(floatArrayOf((scale*cos(angle)).toFloat(),(scale*sin(angle)).toFloat(),center.x.toFloat(),(scale*sin(angle)).toFloat(),(-scale*cos(angle)).toFloat(),center.y.toFloat(),0f,0f,1f))};canvas.concat(m)
        canvas.drawLines(cache.coast,coast);canvas.restoreToCount(save)
        val first="VORSCHAU · grob ${cache.toleranceMetres.toInt()} m · keine Tiefen/AIS"
        val second="${if(state.held) "Touch" else "Vollrender läuft"} · Küstenlinie · Geometrie ${cache.fingerprint.take(8)}"
        val textWidth=max(label.measureText(first),label.measureText(second));val height=label.textSize*2+16
        val candidates=sequence{for(y in 16..(this@ChartOutlineView.height-height).toInt() step (height+16).toInt())for(x in listOf(width*.2f,16f,width-textWidth-16))yield(Rect(x,y.toFloat(),x+textWidth,y+height))}
        candidates.firstOrNull{it.left>=0&&it.right<=width&&blocked.none{r->r.overlaps(it)}}?.let { area ->
            canvas.drawText(first,area.left,area.top+label.textSize,label);canvas.drawText(second,area.left,area.top+label.textSize*2+8,label)
        }
    }
}
