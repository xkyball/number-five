package com.no5.home

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import kotlin.math.cos
import kotlin.math.sin

/** Thin native footer glyphs share the instrument navigation's stroke language. */
internal class HomeGlyph(private val kind:Int,color:Int) : Drawable() {
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply {this.color=color;style=Paint.Style.STROKE;strokeWidth=2.8f;strokeCap=Paint.Cap.ROUND}
    override fun draw(canvas:Canvas){
        canvas.save();canvas.translate(bounds.left.toFloat(),bounds.top.toFloat());canvas.scale(bounds.width()/56f,bounds.height()/56f)
        when(kind){
            0 -> for(x in listOf(6f,32f))for(y in listOf(6f,32f))canvas.drawRoundRect(RectF(x,y,x+18,y+18),2f,2f,paint)
            1 -> {
                canvas.drawCircle(28f,28f,15f,paint);canvas.drawCircle(28f,28f,5f,paint)
                for(i in 0..7){val a=i*Math.PI/4;canvas.drawLine(28+cos(a).toFloat()*15,28+sin(a).toFloat()*15,28+cos(a).toFloat()*23,28+sin(a).toFloat()*23,paint)}
            }
            else -> {canvas.drawLine(12f,45f,37f,20f,paint);canvas.drawCircle(12f,45f,4f,paint)
                canvas.drawArc(RectF(28f,4f,52f,28f),15f,265f,false,paint)}
        }
        canvas.restore()
    }
    override fun getIntrinsicWidth()=44
    override fun getIntrinsicHeight()=44
    override fun setAlpha(value:Int){paint.alpha=value;invalidateSelf()}
    override fun setColorFilter(filter:ColorFilter?){paint.colorFilter=filter;invalidateSelf()}
    @Deprecated("Deprecated in Android") override fun getOpacity()=PixelFormat.TRANSLUCENT
}
