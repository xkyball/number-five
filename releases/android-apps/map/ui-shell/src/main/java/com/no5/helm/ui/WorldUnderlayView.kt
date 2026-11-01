package com.no5.helm.ui

import android.content.Context
import android.graphics.*
import android.view.View
import com.no5.helm.chart.ChartViewport
import java.io.DataInputStream
import java.security.MessageDigest
import kotlin.math.*

/** Approved W1 geometry. A single retained display list; no Surface, EGL, bitmap or chart authority. */
internal class WorldUnderlayView(context: Context): View(context) {
    private val path=Path().apply { fillType=Path.FillType.EVEN_ODD }
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private val node=RenderNode("No5WorldLand110m").apply { setPosition(0,0,4096,4096);setClipToBounds(false) }
    private val matrix=Matrix()
    var camera=ChartViewport(-32.14670140048012,20.0,.00019,0.0,2000,1070)
        set(value){if(field!=value){field=value;invalidate()}}
    var landColor=Color.rgb(200,199,178)
        set(value){if(field!=value){field=value;node.discardDisplayList();invalidate()}}
    var waterColor=Color.rgb(17,98,130)
        set(value){if(field!=value){field=value;invalidate()}}
    init {
        setWillNotDraw(false)
        isClickable=false;importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO
        val raw=context.assets.open("world/land-110m-v1.bin").use { it.readBytes() }
        require(MessageDigest.getInstance("SHA-256").digest(raw).joinToString(""){"%02x".format(it)}==
            "fb8bbf815be2f8ca9521918c79a865820e35c25122fbc8ac77da65b11561fc73")
        DataInputStream(raw.inputStream()).use { input ->
            val magic=ByteArray(8);input.readFully(magic);require(String(magic,Charsets.US_ASCII)=="NE110W1\u0000")
            val count=input.readInt();require(count==128)
            repeat(count){val size=input.readInt();require(size in 4..10000)
                repeat(size){i->val x=input.readFloat();val y=input.readFloat();require(x.isFinite()&&y.isFinite())
                    if(i==0)path.moveTo(x,y) else path.lineTo(x,y)};path.close()}
            require(input.available()==0)
        }
    }
    private val palettes by lazy { org.json.JSONObject(context.assets.open("world/colors-v1.json").bufferedReader().use{it.readText()}) }
    private var style: String?=null
    fun configurePalette(value: String) {
        if(style==value)return
        val roles=palettes.getJSONObject(value)
        fun color(name:String):Int {val a=roles.getJSONArray(name);return Color.rgb(a.getInt(0),a.getInt(1),a.getInt(2))}
        landColor=color("land");waterColor=color("water");style=value
    }
    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(waterColor)
        paint.color=landColor
        if(canvas.isHardwareAccelerated&&!node.hasDisplayList()) {
            val recording=node.beginRecording();recording.drawPath(path,paint);node.endRecording()
        }
        val c=camera;val factor=min(width.toDouble()/c.width,height.toDouble()/c.height)
        val scale=2*PI*(6378137.0*.9996)*c.metresToPixels/4096*factor
        val x=(c.longitude+180)/360*4096
        val y=(PI-ln(tan(PI/4+Math.toRadians(c.latitude)/2)))/(2*PI)*4096
        val cosine=cos(c.rotationRadians);val sine=sin(c.rotationRadians)
        val a=scale*cosine;val b= -scale*sine;val d=scale*sine;val e=scale*cosine
        for(turn in -1..1) {
            val save=canvas.save()
            matrix.setValues(floatArrayOf(a.toFloat(),b.toFloat(),(width/2.0-a*x-b*y+turn*4096*a).toFloat(),
                d.toFloat(),e.toFloat(),(height/2.0-d*x-e*y+turn*4096*d).toFloat(),0f,0f,1f))
            canvas.concat(matrix)
            if(!canvas.quickReject(0f,0f,4096f,4096f,Canvas.EdgeType.AA)) {
                if(canvas.isHardwareAccelerated)canvas.drawRenderNode(node)else canvas.drawPath(path,paint)
            }
            canvas.restoreToCount(save)
        }
    }
    override fun onDetachedFromWindow(){node.discardDisplayList();super.onDetachedFromWindow()}
}
