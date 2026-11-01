package com.no5.home

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import kotlin.math.min

/** Native Views and their hit/accessibility rectangles share a 2000×1200 stage. */
internal class HomeStage(context: Context, private val night: Boolean,
                         private val widthScaleOnly: Boolean = true) : ViewGroup(context) {
    class Position(val x: Float,val y: Float,val w: Int,val h: Int) : LayoutParams(w,h)
    private var stageScale=1f
    private var stageLeft=0f
    private var stageTop=0f
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private val font=context.resources.getFont(R.font.barlow_condensed_semibold)
    private val body=listOf(RectF(49f-205,1157.4f-209,49f+205,1157.4f+209),
        RectF(1943f-208,1157.4f-209,1943f+208,1157.4f+209))
    fun place(view: View,x: Float,y: Float,w: Int,h: Int) { addView(view,Position(x,y,w,h)) }
    override fun onMeasure(widthSpec: Int,heightSpec: Int) {
        val w=MeasureSpec.getSize(widthSpec);val h=MeasureSpec.getSize(heightSpec)
        setMeasuredDimension(w,h)
        stageScale=if(widthScaleOnly)w/2000f else min(w/2000f,h/1200f)
        stageLeft=(w-2000*stageScale)/2;stageTop=if(widthScaleOnly)0f else(h-1200*stageScale)/2
        for(i in 0 until childCount){val child=getChildAt(i);val p=child.layoutParams as Position
            val ch=if(child is com.no5.header.artwork.No5WorkspaceView)
                min(p.h,(h/stageScale-272f).toInt().coerceAtLeast(252)) else p.h
            child.measure(MeasureSpec.makeMeasureSpec(p.w,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(ch,MeasureSpec.EXACTLY))}
    }
    override fun onLayout(changed: Boolean,l: Int,t: Int,r: Int,b: Int) {
        for(i in 0 until childCount){val child=getChildAt(i);val p=child.layoutParams as Position
            val yInStage=if(widthScaleOnly&&p.y>=990f)p.y+(height/stageScale-1200f)else p.y
            val x=(stageLeft+p.x*stageScale).toInt();val y=(stageTop+yInStage*stageScale).toInt()
            child.pivotX=0f;child.pivotY=0f;child.scaleX=stageScale;child.scaleY=stageScale
            child.layout(x,y,x+p.w,y+child.measuredHeight)}
    }
    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        canvas.save();canvas.translate(stageLeft,stageTop);canvas.scale(stageScale,stageScale)
        paint.color=if(night)Color.BLACK else Color.rgb(9,32,42)
        for(rect in body){val safe=RectF(rect);safe.inset(-CLEARANCE_PX,-CLEARANCE_PX);canvas.drawOval(safe,paint)}
        paint.color=Color.BLACK
        // Approved inner-fill correction only; physical geometry and clearance stay unchanged.
        canvas.drawOval(RectF(-66f,1042.4f,164f,1272.4f).apply {
            inset(-INNER_FILL_EXPANSION_PX,-INNER_FILL_EXPANSION_PX)
        },paint)
        canvas.drawOval(RectF(1831f,1047.4f,2055f,1267.4f).apply {
            inset(-INNER_FILL_EXPANSION_PX,-INNER_FILL_EXPANSION_PX)
        },paint)
        paint.color=if(night)Color.rgb(212,0,0) else Color.rgb(246,248,247)
        paint.typeface=font;paint.textAlign=Paint.Align.CENTER
        fun status(value:String,x:Float,y:Float,size:Float){paint.textSize=size
            canvas.drawText(value,x,y-(paint.ascent()+paint.descent())/2,paint)}
        status(com.no5.ui.No5Text.text(context,"Modus").uppercase(java.util.Locale.ROOT),49f,1094.15f,24f)
        status(com.no5.ui.No5Text.text(context,if(night)"RED" else "DAY"),49f,1157.4f,36f);status("—",112.25f,1157.4f,24f)
        status("HOME",1943f,1096.9f,24f);status("—",1881.4f,1157.4f,24f);status("—",1943f,1157.4f,36f)
        canvas.restore()
    }
    companion object {
        const val SAFE_COLUMN_LEFT = 353f
        const val SAFE_COLUMN_WIDTH = 1303
        // 2000 / 237 mm × 5 mm, rounded upward to preserve the minimum distance.
        const val CLEARANCE_PX=43f
        const val INNER_FILL_EXPANSION_PX=2000f/237f*3f
        fun intersectsSafety(rect: RectF): Boolean = listOf(floatArrayOf(49f,1157.4f,248f,252f),floatArrayOf(1943f,1157.4f,251f,252f)).any { e ->
            val x=e[0].coerceIn(rect.left,rect.right);val y=e[1].coerceIn(rect.top,rect.bottom)
            val nx=(x-e[0])/e[2];val ny=(y-e[1])/e[3];nx*nx+ny*ny<=1f
        }
    }
}
