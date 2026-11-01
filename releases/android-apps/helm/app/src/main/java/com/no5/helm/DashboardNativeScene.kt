package com.no5.helm

import android.content.Context
import android.graphics.*
import kotlin.math.max
import kotlin.math.roundToInt

/** Authored fascia only. All observations, labels and controls are supplied by the
 * existing panel owners; the photograph is the existing HELM asset, never a data layer. */
internal class DashboardNativeScene(context:Context,private val image:Bitmap?=null) {
    private val imagePaint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    // One continuous photographic scrim. The transparent part preserves COG/HDG;
    // white values and their evidence remain readable over foam and the white bow.
    private val photoScrim=Paint().apply {
        shader=LinearGradient(0f,328f,0f,796f,
            intArrayOf(Color.TRANSPARENT,0x80000000.toInt(),0xb3000000.toInt()),
            floatArrayOf(0f,.2f,1f),Shader.TileMode.CLAMP)
    }
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply {strokeCap=Paint.Cap.ROUND;strokeJoin=Paint.Join.ROUND}
    private val label=Paint(Paint.ANTI_ALIAS_FLAG).apply {typeface=context.resources.getFont(R.font.barlow_condensed_semibold)}
    private val wing=Path().apply {
        moveTo(24f,144f);cubicTo(230f,144f,350f,146f,382f,276f)
        cubicTo(400f,354f,406f,426f,432f,514f);cubicTo(442f,549f,428f,572f,400f,605f)
        lineTo(286f,748f);quadTo(250f,790f,174f,796f);lineTo(24f,796f);close()
    }
    private val rail=Path().apply {moveTo(182f,212f);cubicTo(318f,236f,332f,364f,350f,440f);quadTo(368f,515f,402f,552f)}
    private val railMeasure=PathMeasure(rail,false)
    private val activeRail=Path()
    private val crest=Path().apply {moveTo(24f,144f);cubicTo(230f,144f,350f,146f,382f,276f);cubicTo(400f,354f,406f,426f,432f,514f)}
    private val imageTarget=RectF(24f,144f,1256f,796f)
    private val imageSource=Rect()
    private data class ScaleMark(val x:Float,val y:Float,val nx:Float,val ny:Float,val major:Boolean,val label:String?)
    private val marks=run {
        val measure=PathMeasure(rail,false);val point=FloatArray(2);val tangent=FloatArray(2)
        List(29) {i ->measure.getPosTan(measure.length*i/28f,point,tangent)
            ScaleMark(point[0],point[1],-tangent[1],tangent[0],i%4==0,if(i%4==0)(7-i/4).toString() else null)}
    }
    private val compass=RectF(488f,216f,820f,390f)
    private val compassMarks=List(21) {i ->
        val angle=Math.toRadians(200.0+i*7);val x=654+166*kotlin.math.cos(angle);val y=303+87*kotlin.math.sin(angle)
        floatArrayOf(x.toFloat(),y.toFloat(),(x+(if(i%5==0)18 else 10)*kotlin.math.cos(angle)).toFloat(),
            (y+(if(i%5==0)18 else 10)*kotlin.math.sin(angle)).toFloat())
    }
    private val factLines=floatArrayOf(464f,570f,676f)
    private val weatherIcons=listOf(66f to "gauge",510f to "air",900f to "wind")
    private val battery=Path().apply {addRect(-17f,-11f,17f,13f,Path.Direction.CW);moveTo(-10f,-11f);lineTo(-10f,-17f);lineTo(-4f,-17f);lineTo(-4f,-11f)
        moveTo(5f,-11f);lineTo(5f,-17f);lineTo(11f,-17f);lineTo(11f,-11f);moveTo(-11f,0f);lineTo(-3f,0f);moveTo(-7f,-4f);lineTo(-7f,4f);moveTo(6f,0f);lineTo(12f,0f)}
    private val oil=Path().apply {moveTo(-18f,-5f);lineTo(-6f,-5f);lineTo(-6f,-11f);lineTo(7f,-11f);lineTo(7f,-5f);lineTo(19f,-5f);lineTo(10f,12f);lineTo(-10f,12f);close()
        moveTo(-17f,-2f);lineTo(-25f,-8f);lineTo(-27f,-1f);lineTo(-19f,6f);moveTo(10f,-8f);lineTo(24f,-13f)}
    private val coolant=Path().apply {moveTo(-5f,8f);lineTo(-5f,-17f);cubicTo(-5f,-25f,5f,-25f,5f,-17f);lineTo(5f,8f)
        cubicTo(19f,20f,-19f,20f,-5f,8f);moveTo(0f,-16f);lineTo(0f,12f);moveTo(8f,-9f);lineTo(14f,-9f);moveTo(8f,-2f);lineTo(14f,-2f)}
    private val factIcons=listOf(504f to battery,610f to oil,716f to coolant)
    init {image?.let {bitmap ->
        val fit=max(imageTarget.width()/bitmap.width,imageTarget.height()/bitmap.height)
        val width=imageTarget.width()/fit;val height=imageTarget.height()/fit
        imageSource.set(((bitmap.width-width)/2).roundToInt(),((bitmap.height-height)/2).roundToInt(),
            ((bitmap.width+width)/2).roundToInt(),((bitmap.height+height)/2).roundToInt())
    }}
    private fun line(c:Canvas,x:Float,y:Float,r:Float,b:Float,color:Int,width:Float=1.5f) {
        paint.style=Paint.Style.STROKE;paint.color=color;paint.strokeWidth=width;c.drawLine(x,y,r,b,paint)
    }
    fun draw(c:Canvas,night:Boolean,externalSonarScene:Boolean=false) {
        val paper=if(night)0xff000000.toInt() else 0xfff6f9fa.toInt()
        val ink=if(night)0xffb00000.toInt() else 0xff344b59.toInt()
        val rule=if(night)0xff700000.toInt() else 0xff90a0a7.toInt()
        c.save();c.clipRect(0f,144f,2000f,1058f)
        if(externalSonarScene)DashboardGeometry.echogram.let { c.clipOutRect(it.x,it.y,it.x+it.width,it.y+it.height) }
        c.drawColor(paper)
        if(!night) {
            paint.style=Paint.Style.FILL;paint.color=0xff144564.toInt();c.drawRect(imageTarget,paint)
            image?.let {c.drawBitmap(it,imageSource,imageTarget,imagePaint)}
            c.drawRect(imageTarget,photoScrim)
        }
        paint.style=Paint.Style.FILL;paint.color=paper;c.drawPath(wing,paint)
        c.save();c.translate(1280f,0f);c.scale(-1f,1f);c.drawPath(wing,paint);c.restore()
        fun edge() {
            paint.style=Paint.Style.STROKE;paint.color=rule;paint.strokeWidth=1.5f;c.drawPath(wing,paint)
            paint.color=ink;paint.strokeWidth=8f;c.drawPath(crest,paint)
            paint.color=rule;paint.strokeWidth=5f;c.drawPath(rail,paint)
            for(mark in marks) {
                line(c,mark.x+mark.nx*10,mark.y+mark.ny*10,mark.x+mark.nx*(if(mark.major)29 else 19),mark.y+mark.ny*(if(mark.major)29 else 19),ink,if(mark.major)2f else 1f)
                mark.label?.let {value ->label.color=ink;label.textSize=18f;label.textAlign=Paint.Align.CENTER
                    c.drawText(value,mark.x+mark.nx*43,mark.y+mark.ny*43+6,label)}
            }
        }
        edge();c.save();c.translate(1280f,0f);c.scale(-1f,1f)
        // Mirror the structure, then restore readable scale numerals in the right wing.
        paint.style=Paint.Style.STROKE;paint.color=rule;paint.strokeWidth=1.5f;c.drawPath(wing,paint)
        paint.color=ink;paint.strokeWidth=8f;c.drawPath(crest,paint)
        paint.color=rule;paint.strokeWidth=5f;c.drawPath(rail,paint);c.restore()
        for(mark in marks) {
            line(c,1280-mark.x-mark.nx*10,mark.y+mark.ny*10,1280-mark.x-mark.nx*(if(mark.major)29 else 19),mark.y+mark.ny*(if(mark.major)29 else 19),ink,if(mark.major)2f else 1f)
            mark.label?.let {value ->label.color=ink;label.textSize=18f;label.textAlign=Paint.Align.CENTER
                c.drawText(value,1280-mark.x-mark.nx*43,mark.y+mark.ny*43+6,label)}
        }
        for(y in factLines) {line(c,48f,y,300f,y,rule);line(c,980f,y,1232f,y,rule)}
        // A shared compass reference, deliberately with no fabricated heading or tick labels.
        paint.style=Paint.Style.STROKE;paint.color=ink;paint.strokeWidth=2f
        c.drawArc(compass,200f,140f,false,paint)
        for(mark in compassMarks)line(c,mark[0],mark[1],mark[2],mark[3],ink)
        ApprovedSymbols.draw(c,"course",654f,204f,.8f,ink)
        for((x,name) in weatherIcons)ApprovedSymbols.draw(c,name,x,838f,.7f,ink)
        for((y,path) in factIcons)for(side in 0..1) {
            c.save();c.translate(if(side==0)68f else 1032f,y);c.scale(.8f,.8f)
            paint.style=Paint.Style.STROKE;paint.color=ink;paint.strokeWidth=2.3f;c.drawPath(path,paint);c.restore()
        }
        line(c,24f,800f,1256f,800f,rule);line(c,1290f,144f,1290f,884f,rule)
        line(c,1304f,224f,1964f,224f,rule);line(c,1932f,232f,1932f,612f,rule)
        for(i in 0..4)line(c,1932f,232f+95*i,1938f,232f+95*i,rule)
        line(c,0f,894f,2000f,894f,rule);line(c,280f,1044f,1696f,1044f,rule)
        line(c,590f,918f,590f,1024f,rule);line(c,958f,918f,958f,1024f,rule)
        ApprovedSymbols.draw(c,"AUDIO",126f,972f,.9f,ink)
        c.restore()
    }
    /** Same fixed 0–7000 reference as HELM's existing native RPM trace. Unknown
     * is distinct from real zero; neither paints a fictitious running band. */
    fun drawEngineBands(c:Canvas,portRpm:Float,starboardRpm:Float,portAvailable:Boolean,starboardAvailable:Boolean,night:Boolean) {
        paint.style=Paint.Style.STROKE;paint.strokeWidth=5f
        paint.color=if(night)0xffe32111.toInt() else 0xffff790b.toInt()
        fun band(value:Float,available:Boolean) {
            if(!available||!value.isFinite()||value<=0f)return
            activeRail.reset()
            railMeasure.getSegment(railMeasure.length*(1-(value/7000f).coerceIn(0f,1f)),railMeasure.length,activeRail,true)
            c.drawPath(activeRail,paint)
        }
        band(portRpm,portAvailable);c.save();c.translate(1280f,0f);c.scale(-1f,1f)
        band(starboardRpm,starboardAvailable);c.restore()
    }
}
