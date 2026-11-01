package com.no5.helm
import kotlin.math.*
/** Exact canonical HELM heading mapping: 2.5° minor / 15° major / 30° labeled. */
object HeadingGeometry {
    data class Tick(val heading:Double,val x:Float,val y:Float,val major:Boolean,val label:String?,val cardinal:Boolean)
    private val anchors=listOf(-78.0 to 662.0,-60.0 to 744.0,-30.0 to 875.5,0.0 to 1000.0,30.0 to 1123.5,60.0 to 1255.0,78.0 to 1338.0)
    fun ticks(heading:Double):List<Tick>{
        if(!heading.isFinite())return emptyList()
        val normalized=((heading%360)+360)%360
        val result=mutableListOf<Tick>();var raw=ceil((normalized-78)/2.5)*2.5
        while(raw<=normalized+78+1e-9){
            val h=((raw%360)+360)%360;val delta=raw-normalized
            val pair=anchors.zipWithNext().first{delta<=it.second.first}
            val x=(pair.first.second+(pair.second.second-pair.first.second)*((delta-pair.first.first)/(pair.second.first-pair.first.first))).toFloat()
            val y=(139+25*((abs(x-1000)/338).toDouble().pow(2))).toFloat()
            val major=abs(h/15-round(h/15))<1e-8;val labelled=abs(h/30-round(h/30))<1e-8;val cardinal=h%90==0.0
            val label=if(labelled)mapOf(0 to "N",90 to "E",180 to "S",270 to "W")[h.roundToInt()]?:h.roundToInt().toString()else null
            result+=Tick(h,x,y,major,label,cardinal);raw+=2.5
        };return result
    }
}
