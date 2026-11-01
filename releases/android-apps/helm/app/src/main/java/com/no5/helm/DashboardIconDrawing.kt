package com.no5.helm

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale

/** The existing marine control vocabulary, sized independently of touch bounds. */
internal object DashboardIconDrawing {
    fun draw(scope:DrawScope,kind:String,color:Color)=with(scope) {
        val width=size.width/48;val height=size.height/48
        scale(width,height,pivot=Offset.Zero) {
            when(kind) {
                "PLAY","NEXT","PREVIOUS" -> {
                    val reverse=kind=="PREVIOUS"
                    val path=Path().apply {if(reverse){moveTo(35f,7f);lineTo(12f,24f);lineTo(35f,41f)}
                        else {moveTo(13f,7f);lineTo(36f,24f);lineTo(13f,41f)};close()}
                    drawPath(path,color)
                    if(kind=="NEXT")drawLine(color,Offset(39f,7f),Offset(39f,41f),4f)
                    if(reverse)drawLine(color,Offset(8f,7f),Offset(8f,41f),4f)
                }
                "PAUSE" -> {drawLine(color,Offset(17f,7f),Offset(17f,41f),8f);drawLine(color,Offset(32f,7f),Offset(32f,41f),8f)}
                "MUTE" -> {
                    val path=Path().apply {moveTo(4f,17f);lineTo(12f,17f);lineTo(23f,7f);lineTo(23f,41f);lineTo(12f,31f);lineTo(4f,31f);close()}
                    drawPath(path,color,style=Stroke(2.5f));drawLine(color,Offset(30f,17f),Offset(43f,31f),2.5f);drawLine(color,Offset(43f,17f),Offset(30f,31f),2.5f)
                }
                "FREEZE" -> for(i in 0..2) {val angle=Math.PI*i/3;val dx=(20*kotlin.math.sin(angle)).toFloat();val dy=(20*kotlin.math.cos(angle)).toFloat()
                    drawLine(color,Offset(24-dx,24-dy),Offset(24+dx,24+dy),2.5f)}
            }
        }
    }
}
