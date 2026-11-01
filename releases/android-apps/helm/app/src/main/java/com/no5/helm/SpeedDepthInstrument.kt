package com.no5.helm

import com.no5.ui.No5Text
import android.content.Context
import android.graphics.*
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.asAndroidPath
import com.no5.domain.*
import com.no5.helm.numeric.RotorNumericField
import kotlin.math.*
import java.util.Locale

/** Native Rotor 01 from the user-approved HTML. No face fill, demo values or draw-time statistics. */
class SpeedDepthInstrument(context:Context,private val night:Boolean) {
    private val hero=context.resources.getFont(R.font.barlow_condensed_extrabold)
    // Android's bundled condensed sans avoids a downloaded font dependency.
    internal val numeralTypeface=Typeface.create("sans-serif-condensed",Typeface.BOLD)
    internal val speedNumerals=RotorNumericField(numeralTypeface,118f,3,290f,4f,4f)
    internal val depthNumerals=RotorNumericField(numeralTypeface,46f,5,160f,2.4f,2f)
    internal val extremaNumerals=RotorNumericField(hero,17f,3,55f,1.4f,1f)
    private val label=context.resources.getFont(R.font.barlow_condensed_semibold)
    private val foreground=Color.parseColor(if(night)"#db180d" else "#ffffff")
    private val secondary=Color.parseColor(if(night)"#bb1c12" else "#dbe7ea")
    private val outline=Color.parseColor(if(night)"#000000" else "#05090b")
    private val rail=Color.parseColor(if(night)"#460904" else "#02070a")
    private val keyline=Color.parseColor(if(night)"#9e1b10" else "#edf6f9")
    private val accent=Color.parseColor(if(night)"#e32111" else "#ff790b")
    private val stroke=Paint(Paint.ANTI_ALIAS_FLAG).apply{style=Paint.Style.STROKE;strokeJoin=Paint.Join.ROUND}
    private val ink=Paint(Paint.ANTI_ALIAS_FLAG)
    private val words=Paint(Paint.ANTI_ALIAS_FLAG)
    private fun path(s:String)=PathParser().parsePathString(s).toPath().asAndroidPath()
    private val contour=path("M61 158 C103 75 217 44 310 44 C414 44 517 79 558 154 C602 234 522 339 417 410 C356 451 268 448 206 411 C111 355 20 249 61 158 Z")
    internal val flanks=path("M107 287 C139 341 192 389 242 411 M516 282 C482 342 430 391 379 416")
    private fun point(radiusX:Float,radiusY:Float,degrees:Float):PointF {
        val a=Math.toRadians(degrees.toDouble());return PointF(310+radiusX*cos(a).toFloat(),250+radiusY*sin(a).toFloat())
    }
    private fun arc(rx:Float,ry:Float)=Path().apply {
        for(i in 0..120){val p=point(rx,ry,197+146*i/120f);if(i==0)moveTo(p.x,p.y)else lineTo(p.x,p.y)}
    }
    private val rails=listOf(arc(223f,149f),arc(189f,115f))
    private val segments=List(120){i->
        val angle=197+146/120f*(i+.5f);val half=146/120f*.36f
        val points=listOf(point(219.5f,145.5f,angle-half),point(219.5f,145.5f,angle+half),point(192.5f,118.5f,angle+half),point(192.5f,118.5f,angle-half))
        Path().apply{moveTo(points[0].x,points[0].y);points.drop(1).forEach{lineTo(it.x,it.y)};close()}
    }
    private val ticks=List(51){i->
        val major=i%5==0;val angle=197+146*i/50f
        Triple(point(235f,161f,angle),point(if(major)251f else 243f,if(major)177f else 169f,angle),major)
    }
    private data class ScaleLabel(val value:String,val x:Float,val y:Float)
    private val scaleLabels=SpeedUnit.entries.associateWith { unit ->
        val offsets=listOf(-28f to 9f,-38f to 8f,-17f to -2f,17f to -2f,38f to 8f,28f to 9f)
        List(6) { i ->
            val p=point(170f,96f,197+146*i/5f)
            val value=SpeedDepthInstrumentGeometry.scaleMaximum(unit)*i/5
            ScaleLabel(String.format(Locale.US,if(unit==SpeedUnit.KMH || i==0)"%.0f" else "%.1f",value),
                p.x+offsets[i].first,p.y+6+offsets[i].second)
        }
    }
    internal val textLayouts=InstrumentTextLayouts()
    private val orange=LinearGradient(90f,0f,530f,0f,
        if(night)intArrayOf(0xffb61105.toInt(),0xffe52b08.toInt(),0xffff4a10.toInt())
        else intArrayOf(0xffff3c00.toInt(),0xffff7600.toInt(),0xffffc126.toInt()),floatArrayOf(0f,.5f,1f),Shader.TileMode.CLAMP)
    private val historyGradient=LinearGradient(346f,0f,411f,0f,if(night)intArrayOf(secondary,foreground,accent) else intArrayOf(0xffd5e4e8.toInt(),0xfff6f7f5.toInt(),0xffff830e.toInt()),floatArrayOf(0f,.7f,1f),Shader.TileMode.CLAMP)
    internal val historyBounds=RectF(342f,332f,415f,383f)
    private val graph=RectF(346f,336f,411f,366f)
    private val trace=Path()
    private val historyCache=InstrumentHistoryTraceCache()
    private var traceGeometry:SpeedDepthInstrumentGeometry.Trace?=null

    private inline fun positioned(c:Canvas,draw:()->Unit) {
        c.save();c.clipRect(690f,214f,1310f,697f);c.translate(690f,214f)
        try { draw() } finally { c.restore() }
    }
    fun drawStatic(c:Canvas,units:DisplayUnits)=positioned(c) {
        // Exact reference contour and two distinct thin rails; the center stays transparent.
        stroked(c,contour,8f,if(night)keyline else 0xade1edf2.toInt());stroked(c,contour,4.5f,rail)
        stroke.strokeCap=Paint.Cap.ROUND
        stroked(c,flanks,5f,if(night)keyline else 0xb3d9e8ee.toInt());stroked(c,flanks,2.8f,rail)
        stroke.strokeCap=Paint.Cap.BUTT
        ticks.forEach{(a,b,major)->
            line(c,a.x,a.y,b.x,b.y,rail,if(major)4.3f else 3.2f)
            line(c,a.x,a.y,b.x,b.y,foreground,if(major)1.8f else 1.2f)
        }
        text(c,No5Text.ui("SPEED"),310f,165f,19f,label,secondary)
        text(c,units.speed.symbol,310f,291f,24f,label,foreground,spacing=1.5f)
        text(c,"MIN",193f,313f,14f,label,secondary,Paint.Align.LEFT,spacing=.8f)
        text(c,"MAX",331f,313f,14f,label,secondary,Paint.Align.LEFT,spacing=.8f)
        line(c,175f,329f,445f,329f,outline,5f);line(c,175f,329f,445f,329f,secondary,.6f)
        text(c,"TIEFE · ${units.depth.symbol}",260f,350f,14f,label,foreground)
        for(i in 0..2){val y=graph.top+i*graph.height()/2;line(c,graph.left,y,graph.right,y,outline,3f);line(c,graph.left,y,graph.right,y,secondary,.7f)}
        text(c,"−60 s",346f,379f,12f,label,secondary,Paint.Align.LEFT,spacing=.25f)
        text(c,No5Text.ui("JETZT"),411f,379f,12f,label,secondary,Paint.Align.RIGHT,spacing=.25f)
    }
    fun drawStaticOverlay(c:Canvas,units:DisplayUnits)=positioned(c) {
        rails.forEach{stroked(c,it,6.4f,keyline);stroked(c,it,3.6f,rail)}
        for (entry in scaleLabels.getValue(units.speed)) {
            text(c,entry.value,entry.x,entry.y,19f,hero,foreground,spacing=.2f)
        }
    }
    fun drawBand(c:Canvas,reading:SpeedDepthInstrumentGeometry.Reading)=positioned(c) {
        val active=reading.fraction?.let{ceil(it*segments.size).toInt()}?:0
        for(i in segments.indices) {
            ink.shader=if(i<active && reading.speedStatus==TelemetryStatus.LIVE)orange else null
            ink.color=if(i<active)secondary else if(night)0x30390804 else 0x2e14252c
            c.drawPath(segments[i],ink);stroked(c,segments[i],.35f,if(night)outline else 0xff081116.toInt())
        }
        ink.shader=null
    }
    fun draw(c:Canvas,state:HelmState,history:DepthHistorySnapshot,units:DisplayUnits,quality:String,
        nowEpochMs:Long=System.currentTimeMillis(),policy:FreshnessPolicy=QualityEvaluator.SHARED,
        extrema:SpeedExtremaSnapshot=SpeedExtremaSnapshot()) {
        val reading=SpeedDepthInstrumentGeometry.reading(state,units,quality,nowEpochMs,policy)
        drawStatic(c,units);drawBand(c,reading);drawStaticOverlay(c,units);drawDynamic(c,state,history,reading,units,extrema)
    }
    fun drawDynamic(c:Canvas,state:HelmState,history:DepthHistorySnapshot,
        reading:SpeedDepthInstrumentGeometry.Reading,units:DisplayUnits,extrema:SpeedExtremaSnapshot) {
        drawSpeed(c,reading);drawTelemetry(c,reading,units,extrema,state.quality.simulated)
        drawHistoryLayer(c,history,reading.depthStatus,history.endEpochMs)
    }
    fun drawSpeed(c:Canvas,reading:SpeedDepthInstrumentGeometry.Reading)=positioned(c) {
        speedNumerals.draw(c,reading.speed,310f,265f,foreground,outline)
        val speedFlag=instrumentSpeedFlag(reading)
        speedFlag?.let{text(c,it,415f,290f,12f,label,secondary,spacing=.3f)}
    }
    fun drawTelemetry(c:Canvas,reading:SpeedDepthInstrumentGeometry.Reading,units:DisplayUnits,
        extrema:SpeedExtremaSnapshot,simulated:Boolean)=positioned(c) {
        val extremaUsable=reading.speed!=null
        extreme(c,extrema.minimumKnots?.takeIf{extremaUsable}?.let(units.speed::fromKnots),232f)
        extreme(c,extrema.maximumKnots?.takeIf{extremaUsable}?.let(units.speed::fromKnots),373f)
        depthNumerals.draw(c,reading.depth,260f,390f,foreground,outline)
        val provenance=if(simulated)No5Text.ui("SIMULATED") else No5Text.ui("SOURCE")
        val status=if(reading.speedStatus==TelemetryStatus.LIVE&&reading.depthStatus==TelemetryStatus.LIVE)"$provenance · MIN/MAX THIS SESSION"
            else "SPEED ${reading.speedStatus} · DEPTH ${reading.depthStatus}"
        text(c,status,310f,466f,13f,label,secondary,spacing=.3f)
    }
    fun drawHistoryLayer(c:Canvas,history:DepthHistorySnapshot,status:TelemetryStatus,now:Long)=positioned(c) {
        drawHistory(c,history,status,now)
    }

    private fun drawHistory(c:Canvas,history:DepthHistorySnapshot,status:TelemetryStatus,now:Long) {
        val frame=instrumentHistoryFrame(historyCache,history,now)
        val geometry=frame.trace
        if(traceGeometry !== geometry){
            traceGeometry=geometry
            trace.reset();geometry.points.forEachIndexed{i,p->
                val x=graph.left+p.x*graph.width();val y=graph.top+p.y*graph.height()
                if(i==0||p.breakBefore)trace.moveTo(x,y)else trace.lineTo(x,y)
            }
        }
        c.save();c.clipRect(graph.left-4,graph.top-4,graph.right+4,graph.bottom+4)
        c.translate(-frame.scrollFraction*graph.width(),0f)
        stroke.strokeCap=Paint.Cap.ROUND
        stroked(c,trace,7f,outline);stroke.shader=if(status==TelemetryStatus.LIVE)historyGradient else null
        stroked(c,trace,2.8f,secondary);stroke.shader=null
        geometry.points.forEachIndexed{i,p->if(i==geometry.points.lastIndex || i==0 || p.breakBefore){
            val x=graph.left+p.x*graph.width();val y=graph.top+p.y*graph.height()
            ink.color=outline;c.drawCircle(x,y,4f,ink);ink.color=foreground;c.drawCircle(x,y,2.5f,ink)
        }}
        c.restore();stroke.strokeCap=Paint.Cap.BUTT
    }
    private fun extreme(c:Canvas,value:Double?,x:Float) {
        extremaNumerals.draw(c,value,x+27.5f,313f,foreground,outline)
    }
    private fun text(c:Canvas,value:String,x:Float,y:Float,size:Float,font:Typeface,color:Int,align:Paint.Align=Paint.Align.CENTER,spacing:Float=1.2f){
        words.typeface=font;words.textSize=size;words.textAlign=Paint.Align.LEFT;words.strokeJoin=Paint.Join.ROUND;words.strokeWidth=2.6f
        val layout=textLayouts.get(value,font,size,spacing,words)
        val width=layout.width
        var dx=when(align){Paint.Align.LEFT->x;Paint.Align.RIGHT->x-width;else->x-width/2}
        for(i in layout.glyphs.indices){outlinedText(c,layout.glyphs[i],dx,y,color);dx+=layout.advances[i]}
    }
    private fun outlinedText(c:Canvas,value:String,x:Float,y:Float,color:Int){words.style=Paint.Style.STROKE;words.color=outline;c.drawText(value,x,y,words);words.style=Paint.Style.FILL;words.color=color;c.drawText(value,x,y,words)}
    private fun stroked(c:Canvas,p:Path,width:Float,color:Int){stroke.strokeWidth=width;stroke.color=color;c.drawPath(p,stroke)}
    private fun line(c:Canvas,x:Float,y:Float,x2:Float,y2:Float,color:Int,width:Float){stroke.strokeWidth=width;stroke.color=color;c.drawLine(x,y,x2,y2,stroke)}
}

/** UI-thread-owned, bounded label cache. Paint geometry only; color/alignment remain draw-time. */
internal class InstrumentTextLayouts(private val capacity:Int=64) {
    init { require(capacity>0) }
    data class Layout(val value:String,val font:Typeface,val size:Float,val spacing:Float,
        val width:Float,val glyphs:Array<String>,val advances:FloatArray)
    private val entries=ArrayList<Layout>(capacity)
    internal val size get()=entries.size
    fun get(value:String,font:Typeface,size:Float,spacing:Float,paint:Paint):Layout {
        for(entry in entries) if(entry.value==value && entry.font==font && entry.size==size && entry.spacing==spacing) return entry
        paint.typeface=font;paint.textSize=size
        val glyphs=Array(value.length){value[it].toString()}
        val result=Layout(value,font,size,spacing,paint.measureText(value)+spacing*(value.length-1),
            glyphs,FloatArray(glyphs.size){paint.measureText(glyphs[it])+spacing})
        if(entries.size==capacity)entries.removeAt(0)
        entries.add(result)
        return result
    }
}
