package com.no5.helm

import android.graphics.*
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.asAndroidPath

/** Vector vocabulary from the approved 0.2.3 source, rendered natively. */
internal object ApprovedSymbols {
    private val paths=mapOf(
        "HELM" to "M-19 0H19M0 -19V19M-13 0A13 13 0 1 0 13 0A13 13 0 1 0 -13 0",
        "AUDIO" to "M-20 -8H-10L0 -18V18L-10 8H-20ZM8 -10Q23 0 8 10M16 -18Q39 0 16 18",
        "SONAR" to "M-22 -10L0 -22L22 -10M-22 2L0 -10L22 2M-22 14L0 2L22 14",
        "DASHBOARD" to "M-22 10A22 22 0 1 1 22 10ZM0 3L13 -12",
        "SETTINGS" to "M-15 0A15 15 0 1 0 15 0A15 15 0 1 0 -15 0M0 -15V-23M0 15V23M-15 0H-23M15 0H23M11 -11L17 -17M-11 -11L-17 -17M11 11L17 17M-11 11L-17 17",
        "KARTE" to "M-22 -14L-8 -22L8 -14L22 -22V14L8 22L-8 14L-22 22ZM-8 -22V14M8 -14V22",
        "ROUTEN" to "M-15 15H5L-2 -14H16M-19 15A4 4 0 1 0 -11 15A4 4 0 1 0 -19 15M12 -14A4 4 0 1 0 20 -14A4 4 0 1 0 12 -14",
        "AIS" to "M0 -21L-15 15L0 9L15 15ZM-22 19L-11 23L0 19L11 23L22 19",
        "EBENEN" to "M-22 -8L0 -22L22 -8L0 6ZM-22 3L0 17L22 3M-22 13L0 27L22 13",
        "fuel" to "M-13 18V-18H8V18ZM-9 -13H4V-3H-9ZM8 -11L17 -2V14Q17 19 21 19V-3L14 -13M-17 18H12",
        "range" to "M-20 -5L0 -20L20 -5M-20 4Q-15 -3-10 4T0 4T10 4T20 4M-20 14Q-15 7-10 14T0 14T10 14T20 14M-20 24Q-15 17-10 24T0 24T10 24T20 24",
        "flag" to "M-14 23V-23H17L11 -10L17 3H-14M-4 -20L5 0M5 -20L-4 0",
        "air" to "M-10 0A10 10 0 1 0 10 0A10 10 0 1 0 -10 0M0 -16V-23M0 16V23M-16 0H-23M16 0H23M-12 -12L-17 -17M12 -12L17 -17M-12 12L-17 17M12 12L17 17",
        "wind" to "M-21 -10H7Q22 -10 18 -20Q13 -27 6 -21M-21 0H17Q31 0 25 11Q21 18 13 14M-21 11H-2Q10 11 7 22Q3 27-3 22",
        "humidity" to "M0 -24Q-19 1-16 10Q-12 24 0 24Q12 24 16 10Q19 1 0 -24Z",
        "gauge" to "M-20 14A24 24 0 1 1 20 14M-18 -10L-13 -6M0 -22V-15M18 -10L13 -6M0 4L15 -12M-4 4A4 4 0 1 0 4 4A4 4 0 1 0 -4 4",
        "engine" to "M-21 17V-11H-10V-17H10V-11H21V17ZM-10 -23H10M-21 -3H-28V9H-21M21 -3H28V9H21",
        "gps" to "M-17 -3A17 17 0 1 0 17 -3A17 17 0 1 0 -17 -3M-17 -3H17M-14 -12H14M0 14V26M-9 26H9",
        "alarm" to "M-18 15Q-9 4-9 -11A9 9 0 0 1 9 -11Q9 4 18 15ZM-5 22Q0 28 5 22M0 -21V-27",
        "course" to "M0 -26L-17 21L0 12L17 21Z",
        "clock" to "M-22 0A22 22 0 1 0 22 0A22 22 0 1 0 -22 0M0 -14V0L11 8",
        "average" to "M-20 -20V20H20M-15 11L-3 -4L6 3L19 -15M10 -15H19V-6",
        "bus" to "M-22 -8L0 -22L22 -8L0 6ZM-22 3L0 17L22 3M-22 13L0 27L22 13")
    private val cache=HashMap<String,Path>()
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply { style=Paint.Style.STROKE;strokeCap=Paint.Cap.ROUND;strokeJoin=Paint.Join.ROUND }
    fun draw(c:Canvas,name:String,x:Float,y:Float,scale:Float,color:Int,width:Float=2.5f) {
        val p=cache.getOrPut(name){PathParser().parsePathString(paths.getValue(name)).toPath().asAndroidPath()}
        paint.color=color;paint.strokeWidth=width
        c.save();c.translate(x,y);c.scale(scale,scale);c.drawPath(p,paint);c.restore()
    }
}
