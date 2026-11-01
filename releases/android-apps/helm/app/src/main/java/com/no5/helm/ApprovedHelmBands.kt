package com.no5.helm

import android.graphics.*
import com.no5.ui.No5Text
import com.no5.domain.DisplayUnits
import com.no5.helm.numeric.NumericFormats

/** Live native reflow of the approved 34 lower HELM fields. No fixture values or UI bitmap. */
internal class ApprovedHelmBands(private val scene:HelmScene) {
    private val night=scene.night
    private val ink=Color.parseColor(if(night)"#d40000"else"#07121b")
    private val secondary=Color.parseColor(if(night)"#b00000"else"#344b59")
    private val line=Color.parseColor(if(night)"#700000"else"#90a0a7")
    private val surface=Color.parseColor(if(night)"#090000"else"#f6f8f7")
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private val fuelPaint=Paint(Paint.ANTI_ALIAS_FLAG).apply { shader=LinearGradient(376f,0f,856f,0f,
        intArrayOf(Color.parseColor(if(night)"#700000"else"#cc261a"),Color.parseColor(if(night)"#d40000"else"#dda500")),null,Shader.TileMode.CLAMP) }
    private fun t(c:Canvas,s:String,x:Float,y:Float,size:Float=20f,bold:Boolean=false,color:Int=secondary,max:Float=Float.MAX_VALUE) =
        scene.text(c,No5Text.ui(s),x,y,size,if(bold)"hero"else"label",color,maxWidth=max)
    private fun rule(c:Canvas,x:Float,y:Float,x2:Float,y2:Float){paint.color=line;paint.strokeWidth=1.5f;c.drawLine(x,y,x2,y2,paint)}
    private fun box(c:Canvas,l:Float,t:Float,r:Float,b:Float){paint.color=surface;c.drawRect(l,t,r,b,paint)}
    private fun icon(c:Canvas,name:String,x:Float,y:Float,scale:Float=.42f)=ApprovedSymbols.draw(c,name,x,y,scale,secondary)
    fun static(c:Canvas,units:DisplayUnits) {
        box(c,24f,762f,1976f,846f);rule(c,24f,846f,1976f,846f)
        for(i in 0..2)rule(c,1000f+i*976f/3,775f,1000f+i*976f/3,826f)
        icon(c,"fuel",65f,800f,.65f);t(c,"FUEL",100f,782f)
        t(c,"E",351f,811f,22f);t(c,"F",882f,811f,22f)
        for((i,name) in listOf("CONSUMPTION","RANGE","NEXT WAYPOINT").withIndex()) {
            val x=1076f+i*976f/3;icon(c,listOf("fuel","range","flag")[i],x-30f,801f,.5f);t(c,name,x,782f)
        }
        box(c,24f,850f,1976f,904f);rule(c,24f,910f,1976f,910f);rule(c,1000f,858f,1000f,896f)
        t(c,"ENVIRONMENT",44f,870f,color=ink);t(c,"VESSEL STATUS",1020f,870f,color=ink)
        listOf("AIR TEMP","WIND","HUMIDITY","PRESSURE").forEachIndexed { i,s ->val x=220f+i*190;icon(c,listOf("air","wind","humidity","gauge")[i],x+10,863f);t(c,s,x+30,870f,19f) }
        listOf("ENGINES","N2K / CAN","GPS","ALARMS").forEachIndexed { i,s ->val x=1196f+i*190;icon(c,listOf("engine","bus","gps","alarm")[i],x+10,863f);t(c,s,x+30,870f,19f) }
        box(c,344f,918f,1656f,1046f);rule(c,344f,1048f,1656f,1048f);rule(c,1000f,930f,1000f,1034f)
        t(c,"NAVIGATION",364f,946f,26f,color=ink);t(c,"TRIP",1020f,946f,26f,color=ink)
        icon(c,"course",380f,985f,.5f);icon(c,"flag",698f,985f,.5f)
        t(c,"COG",406f,998f,22f);t(c,"BTW",724f,998f,22f);t(c,"POSITION",364f,1040f,18f)
        listOf("TRIP DISTANCE","FUEL USED","TRIP TIME","TRIP AVERAGE").forEachIndexed { i,s ->val x=1020f+i*154;icon(c,listOf("gauge","fuel","clock","average")[i],x+10,973f);t(c,s,x+30,980f,19f,max=120f) }
    }
    fun slow(c:Canvas,v:HelmRenderValues) {
        fun value(key:String,x:Float,y:Float,size:Float=28f,suffix:String="",max:Float=180f) {
            scene.text(c,(v.text[key]?:"—")+suffix,x,y,size,"hero",ink,maxWidth=max,numericSlots=NumericFormats.helm(key))
        }
        fun status(key:String,x:Float,y:Float,size:Float=14f,max:Float=500f)=t(c,v.text[key]?:"UNBOUND",x,y,size,max=max)
        value("deck-fuelPct",100f,820f,32f," %",110f);value("deck-remainingL",220f,820f,28f," L",125f)
        paint.color=Color.parseColor(if(night)"#260000"else"#adc0cb");c.drawRoundRect(RectF(376f,793f,856f,811f),9f,9f,paint)
        if(v.fuelAvailable) {
            val fraction=v.fuel.coerceIn(0f,100f)/100f
            c.drawRoundRect(RectF(376f,793f,376f+480*fraction,811f),9f,9f,fuelPaint)
            paint.color=surface;c.drawCircle(376f+480*fraction,802f,11f,paint)
            paint.style=Paint.Style.STROKE;paint.color=ink;paint.strokeWidth=2f;c.drawCircle(376f+480*fraction,802f,11f,paint);paint.style=Paint.Style.FILL
        }
        status("deck-fuel-evidence",376f,843f,max=605f)
        value("deck-totalFlow",1076f,820f,32f," "+(v.text["deck-unit-totalFlow"]?:"L/h"),235f);status("deck-flow-quality",1076f,843f,max=230f)
        value("deck-rangeNm",1401.33f,820f,32f," "+(v.text["deck-unit-rangeNm"]?:"NM"),95f)
        t(c,"@ "+(v.text["deck-rangeSpeed"]?:"—")+" "+(v.text["deck-unit-rangeSpeed"]?:"kn"),1504.33f,820f,22f,max=132f)
        t(c,"ROUTE / RANGE UNBOUND",1401.33f,843f,14f,max=230f)
        value("deck-waypointName",1726.67f,820f,32f,max=104f);value("deck-waypointNm",1840.67f,820f,28f," "+(v.text["deck-unit-waypointNm"]?:"NM"),130f)
        t(c,"ETA "+(v.text["deck-waypointEta"]?:"—"),1726.67f,843f,16f,max=172f);t(c,"TTG "+(v.text["deck-waypointTtg"]?:"—"),1906.67f,843f,16f,max=66f)
        status("deck-evidence-environment",44f,899f,18f,186f)
        listOf("deck-airTemp","deck-wind","deck-humidity","deck-pressure").forEachIndexed { i,key ->
            val suffix=listOf(" °C"," kn"," %"," hPa")[i];value(key,250f+i*190,899f,24f,suffix,158f)
        }
        status("deck-evidence-vessel",1020f,899f,18f,169f)
        listOf("deck-enginesStatus","deck-n2kStatus","deck-gpsStatus","deck-alarmsStatus").forEachIndexed { i,key ->value(key,1226f+i*190,899f,24f,max=155f) }
        scene.text(c,v.text["deck-evidence-position"]?:"UNAVAILABLE",980f,946f,18f,"label",secondary,"right",maxWidth=330f)
        value("deck-btw",785f,1006f,44f,"°",193f)
        value("deck-latitude",467f,1040f,22f,max=215f);value("deck-longitude",785f,1040f,22f,max=196f)
        scene.text(c,v.text["deck-evidence-trip"]?:"UNAVAILABLE",1636f,946f,18f,"label",secondary,"right",maxWidth=380f)
        listOf("deck-tripDistance","deck-fuelUsed","deck-tripDuration","deck-efficiency").forEachIndexed { i,key ->
            value(key,1050f+i*154,1020f,28f,when(i){0->" "+(v.text["deck-unit-tripDistance"]?:"NM");1->" L";3->" "+(v.text["deck-unit-efficiency"]?:"L/h");else->""},129f)
        }
    }
    fun motion(c:Canvas,v:HelmRenderValues) = scene.text(c,(v.text["deck-cog"]?:"—")+"°",467f,1006f,44f,"hero",ink,maxWidth=215f,numericSlots=NumericFormats.helm("deck-cog"))
}
