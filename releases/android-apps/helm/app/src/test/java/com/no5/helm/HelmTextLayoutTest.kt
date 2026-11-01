package com.no5.helm

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Actual bundled-font measurements exercise the same geometry used by the Canvas renderer. */
class HelmTextLayoutTest {
    // Android's unit-test compile classpath excludes java.desktop. The test JVM still
    // provides it: reflect only at this test boundary, never substitute synthetic widths.
    private val fontClass=Class.forName("java.awt.Font")
    private val contextClass=Class.forName("java.awt.font.FontRenderContext")
    private val glyphVectorClass=Class.forName("java.awt.font.GlyphVector")
    private val rectangleClass=Class.forName("java.awt.geom.Rectangle2D")
    private val lineMetricsClass=Class.forName("java.awt.font.LineMetrics")
    private val fontContext=contextClass.getConstructor(Class.forName("java.awt.geom.AffineTransform"),
        Boolean::class.javaPrimitiveType,Boolean::class.javaPrimitiveType).newInstance(null,true,true)
    private val fonts=mutableMapOf<Pair<String,Float>,Any>()
    private fun font(family:String,size:Float):Any=fonts.getOrPut(family to size){
        val relative="src/main/res/font/barlow_condensed_$family.ttf"
        val file=File(relative).takeIf{it.isFile}?:File("app/$relative")
        val loaded=fontClass.getMethod("createFont",Int::class.javaPrimitiveType,File::class.java)
            .invoke(null,fontClass.getField("TRUETYPE_FONT").getInt(null),file)
        fontClass.getMethod("deriveFont",Float::class.javaPrimitiveType).invoke(loaded,size)
    }
    private fun number(type:Class<*>,receiver:Any,method:String)=
        (type.getMethod(method).invoke(receiver) as Number).toFloat()
    private fun measure(value:String,family:String,size:Float,scaleX:Float=1f):HelmGlyphMetrics {
        val font=font(family,size)
        val glyphs=fontClass.getMethod("createGlyphVector",contextClass,String::class.java)
            .invoke(font,fontContext,value)
        val ink=glyphVectorClass.getMethod("getVisualBounds").invoke(glyphs)
        val logical=fontClass.getMethod("getStringBounds",String::class.java,contextClass)
            .invoke(font,value,fontContext)
        val line=fontClass.getMethod("getLineMetrics",String::class.java,contextClass)
            .invoke(font,value,fontContext)
        return HelmGlyphMetrics(minOf(0f,number(rectangleClass,ink,"getMinX"))*scaleX,
            minOf(-number(lineMetricsClass,line,"getAscent"),number(rectangleClass,ink,"getMinY")),
            maxOf(number(rectangleClass,logical,"getWidth"),number(rectangleClass,ink,"getMaxX"))*scaleX,
            maxOf(number(lineMetricsClass,line,"getDescent"),number(rectangleClass,ink,"getMaxY")))
    }
    private fun verifyPair(id:String,value:String,unit:String,weather:Boolean=false):HelmTextLayout.ValueUnit {
        val box=checkNotNull(NativeHelmLayout.valueUnitBox(id))
        val placed=HelmTextLayout.valueUnit(box,
            measure(value,if(weather)"extrabold" else "semibold",30f,if(weather)1f else 1.1f),
            measure(unit,"semibold",if(weather)24.6f else 30f,if(weather)1f else 1.1f))
        assertTrue("$id $value value outside $box: ${placed.value.bounds}",box.contains(placed.value.bounds))
        assertTrue("$id $unit unit outside $box: ${placed.unit.bounds}",box.contains(placed.unit.bounds))
        assertEquals("$id shared baseline",placed.value.baseline,placed.unit.baseline,.001f)
        assertEquals("$id reserved gap",6f,placed.unit.bounds.left-placed.value.bounds.right,.001f)
        return placed
    }

    @Test fun engineCellsFitNumbersUnitsAndUnavailableOnBothSides(){
        // Stress strings are layout examples, not invented hardware limits.
        val samples=mapOf("coolant" to ("°C" to listOf("—","0","82","199","-40")),
            "oil" to ("bar" to listOf("—","0.0","4.2","25.0")),
            "voltage" to ("V" to listOf("—","0.0","13.8","99.9")))
        for(side in listOf("port","stbd"))for((field,data) in samples)for(value in data.second){
            val pair=verifyPair("secondary-$side-$field",value,data.first)
            assertTrue("$side $field $value stays legible",pair.value.scale>=.9f)
            assertTrue("$side $field unit stays legible",pair.unit.scale>=.9f)
        }
    }

    @Test fun realWeatherValuesFitFullSizedInFourSeparateColumns(){
        val samples=mapOf("airTemp" to listOf("—","0.0","28.4","-99.9"),
            "wind" to listOf("—","0.0","23.8","99.9"),
            "humidity" to listOf("—","0","100"),
            "pressure" to listOf("—","0","1032","1100"))
        for((field,values) in samples)for(value in values){
            val id="deck-$field"
            val pair=verifyPair(id,value,NativeHelmLayout.weatherUnits.getValue(id),true)
            assertTrue("$id $value should not be crammed",pair.value.scale>=.95f)
            assertTrue("$id unit should not be crammed",pair.unit.scale>=.95f)
        }
        val boxes=samples.keys.map{checkNotNull(NativeHelmLayout.valueUnitBox("deck-$it"))}
        for(i in 0 until boxes.lastIndex)assertTrue(boxes[i].right+12<=boxes[i+1].left)
    }

    @Test fun engineTextBoxesMirrorAndStayClearOfIconsAndDividers(){
        val portRegions=mapOf("coolant" to (77f to 187f),"oil" to (248f to 385f),
            "voltage" to (432f to 564f))
        for((field,region) in portRegions){
            val port=checkNotNull(NativeHelmLayout.valueUnitBox("secondary-port-$field"))
            val stbd=checkNotNull(NativeHelmLayout.valueUnitBox("secondary-stbd-$field"))
            assertEquals(port.mirrored(),stbd)
            assertTrue("$field clears icon",port.left>=region.first+8)
            assertTrue("$field clears divider/hull",port.right<=region.second-10)
            for(side in listOf("port","stbd")){
                val label=checkNotNull(NativeHelmLayout.engineLabelBox("secondary-$side-$field-label"))
                assertTrue(label.top>=port.bottom+3)
            }
        }
    }

    @Test fun allEvidenceTextFitsItsOwnCardWithoutEnteringTitleOrNeighbour(){
        val cards=mapOf("environment" to (240f to 508f),"vessel" to (710f to 1028f),
            "position" to (1170f to 1427f),"trip" to (1515f to 1968f))
        for((field,region) in cards){
            val box=checkNotNull(NativeHelmLayout.evidenceBox("deck-evidence-$field"))
            assertTrue(box.left>=region.first+20)
            assertEquals(region.second-20,box.right,0f)
            for(value in listOf("UNBOUND","SOURCE · UNAVAILABLE","SOURCE · INVALID","DEMO · STALE","OPEN-METEO · LIVE","OPEN-METEO · FORECAST · LIVE")){
                val placed=HelmTextLayout.single(box,measure(value,"semibold",14f),"right")
                assertTrue("$field $value",box.contains(placed.bounds))
                assertTrue("$field evidence remains readable",placed.scale>=.9f)
            }
        }
    }

    @Test fun valuesAndLabelsUseTheSameStableOpticalAxis(){
        for(id in listOf("secondary-port-coolant","secondary-port-oil","secondary-port-voltage",
            "secondary-stbd-coolant","secondary-stbd-oil","secondary-stbd-voltage",
            "deck-airTemp","deck-wind","deck-humidity","deck-pressure")){
            val box=checkNotNull(NativeHelmLayout.valueUnitBox(id))
            for(value in listOf("—","0","13.8","199")){
                val pair=HelmTextLayout.valueUnit(box,measure(value,"semibold",30f),measure("V","semibold",24.6f))
                assertEquals("$id $value group remains centered",(box.left+box.right)/2,
                    (pair.value.bounds.left+pair.unit.bounds.right)/2,.001f)
            }
        }
    }

    @Test fun rpmEndpointUsesFullSizedGlyphWithBezelAndTickClearance(){
        val anchor=NativeHelmLayout.rpmLabelAnchor("rpm-scale-port-label-7",248.556f,141.661f)
        val font=font("semibold",23f)
        val glyphs=fontClass.getMethod("createGlyphVector",contextClass,String::class.java).invoke(font,fontContext,"7")
        val ink=glyphVectorClass.getMethod("getVisualBounds").invoke(glyphs)
        val halfWidth=number(rectangleClass,ink,"getWidth")/2
        assertTrue("below upper bezel",anchor.baseline+number(rectangleClass,ink,"getMinY")>=132f)
        assertTrue("clear of endpoint tick",anchor.x+halfWidth<240f)
        assertTrue("rises above the 6",anchor.baseline<160.287f-10f)
    }

    @Test fun overflowAndNegativeBearingsCannotEscapeExplicitBox(){
        val box=NativeHelmLayout.TextBox(100f,200f,200f,240f)
        for(width in listOf(5f,70f,500f))for(unitWidth in listOf(10f,200f)){
            val pair=HelmTextLayout.valueUnit(box,HelmGlyphMetrics(-3f,-32f,width,6f),
                HelmGlyphMetrics(-2f,-22f,unitWidth,5f))
            assertTrue(box.contains(pair.value.bounds));assertTrue(box.contains(pair.unit.bounds))
            assertTrue(pair.value.bounds.right+5.99f<=pair.unit.bounds.left)
        }
    }
}
