package com.no5.helm

import android.content.Context
import com.no5.ui.No5Text
import android.graphics.*
import kotlin.math.roundToInt

/** Approved central footer: design coordinates shared by painting and hit regions. */
object BottomNavigation {
    val pages = listOf("DASHBOARD", "HELM", "AUDIO", "SONAR", "SETTINGS")
    const val left=344f
    const val cellWidth=262.4f
    const val top=1070f
    const val height=114f
    const val hitWidth=245f
    fun cellLeft(index:Int)=left+10f+index*cellWidth
    fun ink(night:Boolean,selected:Boolean)=if(night)if(selected)"#ff1a0a"else"#ed0000" else if(selected)"#07121b"else"#344b59"
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    fun drawIndicator(canvas:Canvas,page:String,night:Boolean) {
        val index=pages.indexOf(page);if(index<0)return
        paint.color=if(night)Color.rgb(184,71,18)else Color.rgb(255,91,0)
        val x=(cellLeft(index)+17f).roundToInt().toFloat()
        canvas.drawRect(x,1171f,x+206f,1175f,paint)
    }
    internal fun draw(canvas:Canvas,page:String,night:Boolean,scene:HelmScene) {
        val surface=Color.parseColor(if(night)"#090000"else"#f6f8f7")
        val line=Color.parseColor(if(night)"#700000"else"#90a0a7")
        paint.style=Paint.Style.FILL;paint.color=surface
        canvas.drawRoundRect(RectF(left,1060f,1656f,1192f),28f,28f,paint)
        paint.style=Paint.Style.STROKE;paint.strokeWidth=1.5f;paint.color=line
        canvas.drawRoundRect(RectF(left,1060f,1656f,1192f),28f,28f,paint)
        pages.forEachIndexed { i,name ->
            val x=cellLeft(i);val color=Color.parseColor(ink(night,name==page))
            ApprovedSymbols.draw(canvas,name,x+46f,1117f,1f,color,3f)
            scene.text(canvas,No5Text.ui(name),x+83f,1127f,27f,"label",color,maxWidth=157f)
            if(i>0){paint.color=line;canvas.drawLine(x-7f,1085f,x-7f,1164f,paint)}
        }
        paint.style=Paint.Style.FILL;drawIndicator(canvas,page,night)
    }
}
