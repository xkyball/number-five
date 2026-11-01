package com.no5.helm.numeric

import android.graphics.*
import com.no5.helm.instrumentNumber
import kotlin.math.max

/** Fixed-size, ink-bounded cells. Font fitting happens once for the field capacity, never per value. */
internal class RotorNumericField(typeface:Typeface, requestedSize:Float, val integerCapacity:Int,
    val maximumWidth:Float, private val outlineWidth:Float, private val gap:Float) {
    data class Glyph(val character:Char,val path:Path,val origin:Float,val inkBounds:RectF)
    data class Run(val glyphs:List<Glyph>,val width:Float)
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.typeface=typeface;textSize=requestedSize;strokeJoin=Paint.Join.ROUND;strokeWidth=outlineWidth
    }
    private data class Shape(val path:Path,val bounds:RectF)
    private fun shapes():Map<Char,Shape> = "0123456789.—".associateWith { ch ->
        val path=Path();paint.getTextPath(ch.toString(),0,1,0f,0f,path)
        val bounds=RectF();path.computeBounds(bounds,true);Shape(path,bounds)
    }
    private fun pitch(shapes:Map<Char,Shape>)=('0'..'9').maxOf{shapes.getValue(it).bounds.width()}+outlineWidth+2+gap
    private fun dotWidth(shapes:Map<Char,Shape>,pitch:Float)=max(pitch*.36f,shapes.getValue('.').bounds.width()+outlineWidth+2+gap)
    private val shapes:Map<Char,Shape>
    val textSize:Float get()=paint.textSize
    val digitPitch:Float
    val decimalWidth:Float
    init {
        var measured=shapes()
        val pitch=pitch(measured)
        val width=(integerCapacity+1)*pitch+dotWidth(measured,pitch)
        if(width>maximumWidth) {
            // Conservatively include constant stroke/gap padding in the fixed capacity fit.
            val padding=(integerCapacity+2)*(outlineWidth+2+gap)
            paint.textSize*=((maximumWidth-padding)/(width-padding)).coerceIn(.1f,1f)
            measured=shapes()
        }
        shapes=measured;digitPitch=pitch(shapes);decimalWidth=dotWidth(shapes,digitPitch)
        check((integerCapacity+1)*digitPitch+decimalWidth<=maximumWidth+.1f)
    }
    fun layout(value:Double?):Run {
        val formatted=instrumentNumber(value)
        val width=formatted.sumOf { (if(it=='.')decimalWidth else digitPitch).toDouble() }.toFloat()
        var left=-width/2
        val glyphs=formatted.map { ch ->
            val shape=shapes.getValue(ch)
            val cell=if(ch=='.')decimalWidth else digitPitch
            val x=left+(cell-shape.bounds.left-shape.bounds.right)/2
            val actual=RectF(shape.bounds).apply{offset(x,0f);inset(-outlineWidth/2-1,-outlineWidth/2-1)}
            left+=cell
            Glyph(ch,shape.path,x,actual)
        }
        return Run(glyphs,width)
    }
    fun draw(canvas:Canvas,value:Double?,centerX:Float,baseline:Float,foreground:Int,outline:Int) {
        val run=layout(value)
        canvas.save();canvas.translate(centerX,baseline)
        // One outline pass followed by one fill pass keeps neighboring outlines off the dot.
        for(style in listOf(Paint.Style.STROKE,Paint.Style.FILL)) {
            paint.style=style;paint.color=if(style==Paint.Style.STROKE)outline else foreground
            for(glyph in run.glyphs){canvas.save();canvas.translate(glyph.origin,0f);canvas.drawPath(glyph.path,paint);canvas.restore()}
        }
        canvas.restore()
    }
}
