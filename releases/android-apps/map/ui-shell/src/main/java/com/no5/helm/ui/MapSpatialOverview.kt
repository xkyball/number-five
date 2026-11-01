package com.no5.helm.ui

import com.no5.helm.library.ChartSelectionExtent
import kotlin.math.*

internal data class M1Rect(val left:Float,val top:Float,val right:Float,val bottom:Float){
 fun contains(x:Float,y:Float)=x>=left&&x<=right&&y>=top&&y<=bottom
}
/** UI-only Mercator envelope projection; never claims to resolve exact ENC coverage. */
internal class MapSpatialOverview(extents:List<ChartSelectionExtent>,private val bounds:M1Rect){
 private val valid=extents.filter{it.valid};private val center:Double
 private val west:Double;private val east:Double;private val south:Double;private val north:Double
 private val factor:Double
 init{
  center=if(valid.isEmpty())0.0 else Math.toDegrees(atan2(valid.sumOf{sin(Math.toRadians(it.centerLongitude))},valid.sumOf{cos(Math.toRadians(it.centerLongitude))}))
  val ranges=valid.map{longitude(it.centerLongitude)-it.longitudeSpan/2 to longitude(it.centerLongitude)+it.longitudeSpan/2}
  west=ranges.minOfOrNull{it.first}?:-1.0;east=(ranges.maxOfOrNull{it.second}?:1.0).coerceAtLeast(west+.000001)
  south=valid.minOfOrNull{merc(it.south)}?:-1.0;north=(valid.maxOfOrNull{merc(it.north)}?:1.0).coerceAtLeast(south+.000001)
  factor=min((bounds.right-bounds.left-100)/(east-west),(bounds.bottom-bounds.top-110)/(north-south))
 }
 private fun longitude(x:Double)=center+ChartSelectionExtent.normalizeLongitude(x-center)
 private fun merc(x:Double)=Math.toDegrees(ln(tan(Math.PI/4+Math.toRadians(x.coerceIn(-85.0,85.0))/2)))
 fun rect(e:ChartSelectionExtent,panX:Float=0f,panY:Float=0f):M1Rect? {
  if(!e.valid)return null
  val midX=(bounds.left+bounds.right)/2.0+panX;val midY=(bounds.top+bounds.bottom)/2.0+panY+20
  val cx=(west+east)/2;val cy=(south+north)/2;val ec=longitude(e.centerLongitude)
  val l=(midX+(ec-e.longitudeSpan/2-cx)*factor).toFloat();val r=(midX+(ec+e.longitudeSpan/2-cx)*factor).toFloat()
  val t=(midY-(merc(e.north)-cy)*factor).toFloat();val b=(midY-(merc(e.south)-cy)*factor).toFloat()
  return M1Rect(l,t,max(l+3,r),max(t+3,b))
 }
}
