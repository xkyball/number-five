package com.no5.helm.engine

import com.no5.helm.chart.*
import kotlinx.serialization.json.*
import java.security.MessageDigest
import kotlin.math.*

/** Only called once per activated chart set on the chart worker. Never on UI/input/draw. */
internal object OutlinePreparation {
    fun prepare(raw:String):ChartOutlines {
        val json=Json.parseToJsonElement(raw).jsonObject
        val origin=json.getValue("origin").jsonArray.let { GeoProjection(it[0].jsonPrimitive.double,it[1].jsonPrimitive.double) }
        val radius=6378137.0*.9996
        fun north(lat:Double):Double { require(lat.isFinite()&&lat in -85.0..85.0);return ln(tan(PI/4+Math.toRadians(lat)/2))*radius }
        val baseNorth=north(origin.latitude)
        fun segments(key:String):FloatArray {
            val result=ArrayList<Float>()
            for (line in json.getValue(key).jsonArray) {
                val points=line.jsonArray.map { v -> val p=v.jsonArray;val lon=p[1].jsonPrimitive.double;require(lon.isFinite()&&abs(lon-origin.longitude)<=180)
                    ScreenPoint(Math.toRadians(lon-origin.longitude)*radius,north(p[0].jsonPrimitive.double)-baseNorth) }
                if(points.size<2)continue
                val keep=BooleanArray(points.size);keep[0]=true;keep[points.lastIndex]=true
                val ranges=java.util.ArrayDeque<Pair<Int,Int>>();ranges.add(0 to points.lastIndex)
                while(ranges.isNotEmpty()) {
                    val (a,b)=ranges.removeLast();val first=points[a];val last=points[b];val dx=last.x-first.x;val dy=last.y-first.y;val length=dx*dx+dy*dy
                    var far=-1;var distance=400.0
                    for(i in a+1 until b){val p=points[i];val t=if(length==0.0)0.0 else (((p.x-first.x)*dx+(p.y-first.y)*dy)/length).coerceIn(0.0,1.0)
                        val x=p.x-first.x-t*dx;val y=p.y-first.y-t*dy;val d=x*x+y*y;if(d>distance){distance=d;far=i}}
                    if(far>=0){keep[far]=true;ranges.add(a to far);ranges.add(far to b)}
                }
                var previous=points.first()
                for(i in 1..points.lastIndex)if(keep[i]) { val p=points[i];result.add(previous.x.toFloat());result.add(previous.y.toFloat());result.add(p.x.toFloat());result.add(p.y.toFloat());previous=p
                    require(result.size<=200_000) }
            }
            return result.toFloatArray()
        }
        val coast=segments("coast")
        require(coast.isNotEmpty())
        val fingerprint=MessageDigest.getInstance("SHA-256").digest(raw.toByteArray()).joinToString(""){"%02x".format(it)}
        return ChartOutlines(origin,coast,20.0,fingerprint)
    }
}
