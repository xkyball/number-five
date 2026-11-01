package com.no5.helm

import kotlin.math.min

/** Uniform external scaling only. Pointer coordinates use the exact inverse transform. */
data class DesignViewport(val width:Float,val height:Float) {
    init { require(width.isFinite() && height.isFinite() && width>0 && height>0) }
    val scale:Float=min(width/NativeHelmLayout.WIDTH,height/NativeHelmLayout.HEIGHT)
    val left:Float=(width-NativeHelmLayout.WIDTH*scale)/2
    val top:Float=(height-NativeHelmLayout.HEIGHT*scale)/2
    fun toDesign(x:Float,y:Float):Pair<Float, Float> = (x-left)/scale to (y-top)/scale
    fun toScreen(x:Float,y:Float):Pair<Float, Float> = (left+x*scale) to (top+y*scale)
    fun contains(x:Float,y:Float)=x in left..(left+NativeHelmLayout.WIDTH*scale) && y in top..(top+NativeHelmLayout.HEIGHT*scale)
}

/**
 * Native-only 2000 × 1200 reflow. The extracted 2000 × 1280 scene stays immutable.
 * Source shapes/icons stay immutable. Native text boxes reserve explicit space for
 * changing telemetry, units and evidence labels instead of relying on source sample widths.
 */
object NativeHelmLayout {
    const val ADAPTATION_VERSION="native-2000x1200-v4"
    const val WIDTH=2000f
    const val HEIGHT=1200f
    const val SOURCE_HEIGHT=1280f
    const val HEADER_BOTTOM=130f
    const val DECK_TOP=883f
    const val DECK_BOTTOM=1040f
    const val FOOTER_TOP=1058f
    const val SOURCE_FOOTER_TOP=1138f
    const val FOOTER_HEIGHT=142f
    const val FOOTER_SHIFT=FOOTER_TOP-SOURCE_FOOTER_TOP
    // Titles/evidence finish near y921. The body has a common optical center and
    // a 100px group envelope, leaving breathing room above the 1040px panel edge.
    const val DECK_CONTENT_CENTER=978f
    const val DECK_GROUP_HEIGHT=100f
    const val DECK_GROUP_TOP=DECK_CONTENT_CENTER-DECK_GROUP_HEIGHT/2f
    private fun deckY(offset:Float)=DECK_GROUP_TOP+offset

    private val panelIds=setOf("environment-deck","vessel-deck","position-deck","trip-deck")
    fun panelHeight(id:String,sourceHeight:Float):Float =
        if(id in panelIds)DECK_BOTTOM-DECK_TOP else sourceHeight

    fun panelTop(id:String,sourceTop:Float):Float = if(id in panelIds)DECK_TOP else sourceTop

    // Absolute native tops. Compact lower-deck rows have intentional, uniform typography;
    // the 2000 × 1280 source and upper engine/instrument geometry are not stretched.
    private val textTops=mapOf(
        "deck-label-9" to 895f,"deck-label-10" to 895f,
        "deck-label-11" to 895f,"deck-label-12" to 895f,
        "deck-label-13" to deckY(79f),"deck-label-14" to deckY(79f),
        "deck-label-15" to deckY(79f),"deck-label-16" to deckY(79f),
        "deck-airTemp" to deckY(44f),"deck-wind" to deckY(44f),
        "deck-humidity" to deckY(44f),"deck-pressure" to deckY(44f),
        "deck-unit-airTemp" to deckY(44f),"deck-unit-wind" to deckY(44f),
        "deck-unit-humidity" to deckY(44f),"deck-unit-pressure" to deckY(44f),
        "deck-label-17" to deckY(1f),"deck-label-18" to deckY(1f),
        "deck-enginesStatus" to deckY(23f),"deck-n2kStatus" to deckY(23f),
        "deck-label-19" to deckY(49f),"deck-label-20" to deckY(49f),
        "deck-gpsStatus" to deckY(71f),"deck-alarmsStatus" to deckY(71f),
        "deck-latitude" to deckY(-2f),"deck-longitude" to deckY(24f),
        "deck-label-21" to deckY(48f),"deck-label-22" to deckY(48f),
        "deck-cog" to deckY(69f),"deck-btw" to deckY(69f),
        "deck-unit-cog" to deckY(72f),"deck-unit-btw" to deckY(72f),
        "deck-label-23" to deckY(1f),"deck-label-24" to deckY(1f),
        "deck-tripDistance" to deckY(22f),"deck-tripDuration" to deckY(22f),
        "deck-unit-tripDistance" to deckY(25f),
        "deck-label-25" to deckY(49f),"deck-label-26" to deckY(49f),
        "deck-fuelUsed" to deckY(70f),"deck-efficiency" to deckY(70f),
        "deck-unit-fuelUsed" to deckY(75f),"deck-unit-efficiency" to deckY(75f),
        "deck-evidence-environment" to 901f,"deck-evidence-vessel" to 901f,
        "deck-evidence-position" to 901f,"deck-evidence-trip" to 901f
    )
    private val compactSizes=mapOf(
        "deck-label-13" to 18f,"deck-label-14" to 18f,
        "deck-label-15" to 18f,"deck-label-16" to 18f,
        "deck-label-17" to 20f,"deck-label-18" to 20f,
        "deck-label-19" to 20f,"deck-label-20" to 20f,
        "deck-label-21" to 19f,"deck-label-22" to 19f,
        "deck-label-23" to 20f,"deck-label-24" to 20f,
        "deck-label-25" to 20f,"deck-label-26" to 20f,
        "deck-latitude" to 25f,"deck-longitude" to 25f,
        "deck-cog" to 29f,"deck-btw" to 29f,
        "deck-unit-cog" to 25f,"deck-unit-btw" to 25f,
        "deck-tripDistance" to 29f,"deck-tripDuration" to 29f,
        "deck-fuelUsed" to 29f,"deck-efficiency" to 29f,
        "deck-unit-tripDistance" to 24f,"deck-unit-fuelUsed" to 24f,
        "deck-unit-efficiency" to 24f
    )
    fun textTop(id:String,sourceTop:Float):Float=textTops[id]?:sourceTop
    data class TextPlacement(val top:Float,val fontSize:Float,val lineHeight:Float,val scaleX:Float)
    fun placeText(id:String,sourceTop:Float,fontSize:Float,lineHeight:Float,scaleX:Float):TextPlacement {
        val size=compactSizes[id]?:fontSize
        return TextPlacement(textTop(id,sourceTop),size,lineHeight*size/fontSize,scaleX)
    }

    data class TextBox(val left:Float,val top:Float,val right:Float,val bottom:Float) {
        init { require(listOf(left,top,right,bottom).all{it.isFinite()} && right>left && bottom>top) }
        val width get()=right-left
        val height get()=bottom-top
        fun mirrored()=TextBox(WIDTH-right,top,WIDTH-left,bottom)
        fun contains(other:TextBox,epsilon:Float=.01f)=other.left>=left-epsilon &&
            other.right<=right+epsilon && other.top>=top-epsilon && other.bottom<=bottom+epsilon
    }

    /** Icon viewports and separator clearances are excluded from these text boxes. */
    private val portEngineBoxes=mapOf(
        "coolant" to TextBox(85f,495f,177f,531f),
        "oil" to TextBox(257f,495f,373f,531f),
        "voltage" to TextBox(443f,495f,552f,531f)
    )
    // Keep the wider pressure column from the authored scene. All three visual rows
    // in each column share an axis: immutable icon, number/unit pair, and label.
    data class WeatherColumn(val valueId:String,val labelId:String,val iconId:String,
        val center:Float,val left:Float,val right:Float)
    val weatherColumns=listOf(
        WeatherColumn("deck-airTemp","deck-label-13","deck-icon-sun-4",100f,50f,150f),
        WeatherColumn("deck-wind","deck-label-14","deck-icon-wind-5",206.5f,162f,251f),
        WeatherColumn("deck-humidity","deck-label-15","deck-icon-drop-6",314f,266f,362f),
        WeatherColumn("deck-pressure","deck-label-16","deck-icon-pressure-7",436f,378f,494f)
    )
    private val weatherBoxes=weatherColumns.associate{it.valueId to TextBox(it.left,deckY(44f),it.right,deckY(80f))}
    fun weatherLabelBox(id:String):TextBox?=weatherColumns.firstOrNull{it.labelId==id}
        ?.let{TextBox(it.left,deckY(79f),it.right,deckY(101f))}
    fun tripValueUnitBox(id:String):TextBox?=when(id){
        "deck-tripDistance" -> TextBox(1542f,deckY(21f),1687f,deckY(51f))
        "deck-efficiency" -> TextBox(1795f,deckY(70f),1953f,deckY(101f))
        else -> null
    }
    val weatherUnits=mapOf("deck-airTemp" to "°C","deck-wind" to "kn",
        "deck-humidity" to "%","deck-pressure" to "hPa")
    fun valueUnitBox(id:String):TextBox? {
        weatherBoxes[id]?.let{return it}
        if(!id.startsWith("secondary-"))return null
        val side=id.removePrefix("secondary-").substringBefore('-')
        if(side!="port" && side!="stbd")return null
        val box=portEngineBoxes[id.substringAfterLast('-')]?:return null
        return if(side=="port")box else box.mirrored()
    }
    fun engineLabelBox(id:String):TextBox? = if(id.endsWith("-label"))
        valueUnitBox(id.removeSuffix("-label"))?.let{TextBox(it.left,534f,it.right,559.2f)} else null
    fun evidenceBox(id:String):TextBox?=when(id){
        "deck-evidence-environment" -> TextBox(286f,901f,488f,917f)
        "deck-evidence-vessel" -> TextBox(798f,901f,1008f,917f)
        "deck-evidence-position" -> TextBox(1210f,901f,1407f,917f)
        "deck-evidence-trip" -> TextBox(1740f,901f,1948f,917f)
        else -> null
    }
    data class TextAnchor(val x:Float,val baseline:Float)
    /** Every scale digit uses the same 23px baseline drawing, including the endpoint.
     * The endpoint moves along the free inner bezel margin, never into a fit/shrink box. */
    fun rpmLabelAnchor(id:String,sourceX:Float,sourceBaseline:Float):TextAnchor = when(id) {
        "rpm-scale-port-label-7" -> TextAnchor(232f,150f)
        "rpm-scale-stbd-label-7" -> TextAnchor(WIDTH-232f,150f)
        else -> TextAnchor(sourceX,sourceBaseline)
    }

    data class IconPlacement(val x:Float,val y:Float,val scale:Float)
    private val compactIcons=mapOf(
        "deck-icon-sun-4" to IconPlacement(81.5f,deckY(2f),37f/48f),
        "deck-icon-wind-5" to IconPlacement(188f,deckY(2f),37f/48f),
        "deck-icon-drop-6" to IconPlacement(295.5f,deckY(2f),37f/48f),
        "deck-icon-pressure-7" to IconPlacement(417.5f,deckY(2f),37f/48f),
        "deck-icon-engine-8" to IconPlacement(562f,deckY(6f),42f/48f),
        "deck-icon-network-9" to IconPlacement(786f,deckY(6f),42f/48f),
        "deck-icon-gps-10" to IconPlacement(563f,deckY(54f),42f/48f),
        "deck-icon-bell-11" to IconPlacement(786f,deckY(54f),42f/48f),
        "deck-icon-position-12" to IconPlacement(1090f,deckY(2f),48f/48f),
        "deck-icon-distance-13" to IconPlacement(1475f,deckY(6f),42f/48f),
        "deck-icon-clock-14" to IconPlacement(1740f,deckY(6f),42f/48f),
        "deck-icon-fuel-15" to IconPlacement(1475f,deckY(54f),42f/48f),
        "deck-icon-efficiency-16" to IconPlacement(1740f,deckY(54f),42f/48f)
    )
    fun iconPlacement(id:String):IconPlacement?=compactIcons[id]

    // This native bevel closes the exact fuel-strip-upper and main-lower-seam
    // contours, whose source paths are checked against both shipped scene assets.
    const val FUEL_FRAME_PATH="M0 711 C12 703 25 702 42 702 H604 L695 710 H1305 L1396 702 H1958 C1975 702 1988 703 2000 711 L2000 775 L1974 741 Q1956 719 1931 719 H69 Q44 719 26 741 L0 775 Z"
    const val FUEL_FRAME_ACCENT="M4 754 L24 729 Q38 710 60 709 M1940 709 Q1962 710 1976 729 L1996 754"
    // Footer accent follows the authored outer hull in local 2000 × 142 coordinates.
    const val NAV_FRAME_ACCENT="M4 76 L21 103 Q40 129 67 136 M1933 136 Q1960 129 1979 103 L1996 76"
    fun framePalette(night:Boolean)=if(night) listOf("#090000","#2a0000","#100000","#b00000")
        else listOf("#05131e","#233e50","#0b1e2a","#ff5900")

    /** Only structural separators change shape; upper/fuel paths and all icon paths do not. */
    fun structuralPath(id:String,sourcePath:String):String=when(id){
        "environment-dividers" -> "M155 930 V1028 M258 930 V1028 M370 930 V1028"
        "position-divider" -> "M1222 978 V1028"
        "trip-divider" -> "M1703 932 V1028"
        else -> sourcePath
    }
}

/** Measured glyph extents, independent of Android so the packing contract is JVM-testable. */
data class HelmGlyphMetrics(val left:Float,val top:Float,val right:Float,val bottom:Float) {
    init { require(listOf(left,top,right,bottom).all{it.isFinite()} && right>left && bottom>top) }
    val width get()=right-left
    val height get()=bottom-top
}

object HelmTextLayout {
    data class PlacedGlyph(val x:Float,val baseline:Float,val scale:Float,
        val bounds:NativeHelmLayout.TextBox)
    data class ValueUnit(val value:PlacedGlyph,val unit:PlacedGlyph,val gap:Float)

    private fun place(metrics:HelmGlyphMetrics,x:Float,baseline:Float,scale:Float)=PlacedGlyph(
        x,baseline,scale,NativeHelmLayout.TextBox(x+metrics.left*scale,
            baseline+metrics.top*scale,x+metrics.right*scale,baseline+metrics.bottom*scale))

    /** Reserve the unit column first. Both glyphs share a baseline; no horizontal squeezing. */
    fun valueUnit(box:NativeHelmLayout.TextBox,value:HelmGlyphMetrics,unit:HelmGlyphMetrics,
        gap:Float=6f,anchor:String="center"):ValueUnit {
        require(gap>=0 && gap<box.width)
        val unitScale=minOf(1f,box.height/unit.height,(box.width-gap)/unit.width*.45f)
        val valueWidth=box.width-gap-unit.width*unitScale
        var valueScale=minOf(1f,valueWidth/value.width,box.height/value.height)
        var finalUnitScale=unitScale
        val top=minOf(value.top*valueScale,unit.top*unitScale)
        val bottom=maxOf(value.bottom*valueScale,unit.bottom*unitScale)
        val verticalFit=minOf(1f,box.height/(bottom-top))
        valueScale*=verticalFit;finalUnitScale*=verticalFit
        val commonTop=minOf(value.top*valueScale,unit.top*finalUnitScale)
        val commonBottom=maxOf(value.bottom*valueScale,unit.bottom*finalUnitScale)
        val baseline=box.top+(box.height-(commonBottom-commonTop))/2-commonTop
        val totalWidth=value.width*valueScale+gap+unit.width*finalUnitScale
        val groupLeft=when(anchor){"right"->box.right-totalWidth;"left"->box.left
            else->box.left+(box.width-totalWidth)/2}
        val valueX=groupLeft-value.left*valueScale
        val unitX=groupLeft+value.width*valueScale+gap-unit.left*finalUnitScale
        return ValueUnit(place(value,valueX,baseline,valueScale),
            place(unit,unitX,baseline,finalUnitScale),gap)
    }

    fun single(box:NativeHelmLayout.TextBox,metrics:HelmGlyphMetrics,anchor:String):PlacedGlyph {
        val scale=minOf(1f,box.width/metrics.width,box.height/metrics.height)
        val left=when(anchor){"right"->box.right-metrics.width*scale
            "center"->box.left+(box.width-metrics.width*scale)/2;else->box.left}
        val baseline=box.top+(box.height-metrics.height*scale)/2-metrics.top*scale
        return place(metrics,left-metrics.left*scale,baseline,scale)
    }
}
