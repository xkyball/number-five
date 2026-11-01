package com.no5.helm

import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Contracts are checked against both real generated scene assets, not copied test fixtures. */
class NativeHelmLayoutTest {
    private fun scene(theme:String):JSONObject {
        val relative="src/main/assets/helm/scene_$theme.json"
        val file=File(relative).takeIf{it.isFile}?:File("app/$relative")
        return JSONObject(file.readText())
    }
    private fun nodes(root:JSONObject):List<JSONObject> {
        val result=mutableListOf<JSONObject>()
        fun visit(node:JSONObject){
            result+=node
            val children=node.optJSONArray("children")?:return
            for(i in 0 until children.length())visit(children.getJSONObject(i))
        }
        val layers=root.getJSONArray("layers")
        for(i in 0 until layers.length()){
            val children=layers.getJSONObject(i).getJSONArray("nodes")
            for(j in 0 until children.length())visit(children.getJSONObject(j))
        }
        return result
    }
    private fun JSONObject.f(key:String)=get(key).toString().removeSuffix("px").toFloat()

    @Test fun everyRealLowerDeckTextHasBottomPaddingAndUniformGlyphScaling(){
        for(theme in listOf("day","night")){
            val texts=scene(theme).getJSONArray("texts")
            var count=0
            for(i in 0 until texts.length()){
                val source=texts.getJSONObject(i)
                if(!source.getString("id").startsWith("deck-") || source.f("y")<891f)continue
                val id=source.getString("id")
                val placed=NativeHelmLayout.placeText(id,source.f("y"),source.f("fontSize"),
                    source.f("lineHeight"),source.f("scaleX"))
                assertEquals("$theme $id line-height scales with font",source.f("lineHeight")/source.f("fontSize"),
                    placed.lineHeight/placed.fontSize,.00001f)
                assertEquals("$theme $id no new horizontal squeezing",source.f("scaleX"),placed.scaleX,0f)
                assertTrue("$theme $id top=${placed.top}",placed.top>=NativeHelmLayout.DECK_TOP+12f)
                assertTrue("$theme $id bottom=${placed.top+placed.lineHeight}",
                    placed.top+placed.lineHeight<=NativeHelmLayout.DECK_BOTTOM-5f)
                count++
            }
            assertEquals("all actual lower-deck labels/values/units/statuses inspected",47,count)
        }
    }

    @Test fun fourActualCardsHaveEighteenPixelsOfFooterSeparation(){
        val panelIds=setOf("environment-deck","vessel-deck","position-deck","trip-deck")
        for(theme in listOf("day","night")){
            val panels=nodes(scene(theme)).filter{it.optString("id") in panelIds}
            assertEquals(panelIds,panels.map{it.getString("id")}.toSet())
            for(panel in panels){
                val attrs=panel.getJSONObject("attrs");val id=panel.getString("id")
                assertEquals(891f,attrs.f("y"),0f)
                assertEquals(245f,attrs.f("height"),0f)
                assertEquals(883f,NativeHelmLayout.panelTop(id,attrs.f("y")),0f)
                assertEquals(157f,NativeHelmLayout.panelHeight(id,attrs.f("height")),0f)
            }
        }
        assertEquals(18f,NativeHelmLayout.FOOTER_TOP-NativeHelmLayout.DECK_BOTTOM,0f)
        assertEquals(-80f,NativeHelmLayout.FOOTER_SHIFT,0f)
        assertEquals(1200f,NativeHelmLayout.FOOTER_TOP+NativeHelmLayout.FOOTER_HEIGHT,0f)
    }

    @Test fun weatherIconsValuesAndLabelsShareRealColumnAxes(){
        for(theme in listOf("day","night")){
            val actualIds=nodes(scene(theme)).map{it.optString("id")}.toSet()
            for(column in NativeHelmLayout.weatherColumns){
                assertTrue("$theme ${column.iconId} exists in generated asset",column.iconId in actualIds)
                val icon=checkNotNull(NativeHelmLayout.iconPlacement(column.iconId))
                val value=checkNotNull(NativeHelmLayout.valueUnitBox(column.valueId))
                val label=checkNotNull(NativeHelmLayout.weatherLabelBox(column.labelId))
                assertEquals(column.center,icon.x+24f*icon.scale,0f)
                assertEquals(column.center,(value.left+value.right)/2,0f)
                assertEquals(column.center,(label.left+label.right)/2,0f)
                assertTrue("icon/value breathing room",icon.y+48f*icon.scale+5f<=value.top)
                assertTrue("label bottom padding",label.bottom<=NativeHelmLayout.DECK_BOTTOM-7f)
            }
        }
        assertTrue("pressure keeps its authored extra width",NativeHelmLayout.valueUnitBox("deck-pressure")!!.width>
            NativeHelmLayout.valueUnitBox("deck-wind")!!.width)
    }

    @Test fun compactIconsUseImmutablePathsAndUniformScale(){
        for(theme in listOf("day","night")){
            var count=0
            for(node in nodes(scene(theme))){
                val placement=NativeHelmLayout.iconPlacement(node.optString("id"))?:continue
                assertEquals("g",node.getString("tag"))
                assertTrue(node.getJSONObject("attrs").getString("transform").contains("scale("))
                assertTrue(placement.scale>0f && placement.scale<=1f)
                assertTrue(placement.y>=NativeHelmLayout.DECK_TOP+40f)
                assertTrue(placement.y+48f*placement.scale<=NativeHelmLayout.DECK_BOTTOM-10f)
                count++
            }
            assertEquals(13,count)
        }
        for(id in listOf("deck-icon-fuel-0","deck-icon-fuel-1","deck-icon-range-2","deck-icon-flag-3"))
            assertNull(NativeHelmLayout.iconPlacement(id))
    }

    @Test fun frameFollowsAuthoredHullAndUsesActualBezelPalette(){
        for(theme in listOf("day","night")){
            val root=scene(theme);val actual=nodes(root).associateBy{it.optString("id")}
            assertEquals("M0 775 L26 741 Q44 719 69 719 H1931 Q1956 719 1974 741 L2000 775",
                actual.getValue("fuel-strip-upper").getJSONObject("attrs").getString("d"))
            assertEquals("M0 711 C12 703 25 702 42 702 H604 M1396 702 H1958 C1975 702 1988 703 2000 711",
                actual.getValue("main-lower-seam").getJSONObject("attrs").getString("d"))
            val palette=NativeHelmLayout.framePalette(theme=="night")
            val stops=root.getJSONObject("gradients").getJSONObject("rpm-bezel-$theme").getJSONArray("stops")
            for(i in 0..2)assertEquals(stops.getJSONArray(i).getString(1),palette[i])
            assertEquals(actual.getValue("rpm-outer-accent-port").getJSONObject("style").getString("fill"),palette[3])
            val nav=root.getJSONArray("layers").let{layers->(0 until layers.length()).map{layers.getJSONObject(it)}
                .single{it.getString("id")=="frame-nav"}}
            assertEquals("translate(0 1138)",nav.getJSONArray("nodes").getJSONObject(0).getJSONObject("attrs").getString("transform"))
            val hull=nav.getJSONArray("nodes").getJSONObject(0).getJSONArray("children").getJSONObject(0).getJSONObject("attrs").getString("d")
            assertTrue(hull.contains("V47L1967 99Q1940 131 1904 131H97Q61 131 33 99L0 47Z"))
        }
    }

    @Test fun rpmDigitsRetainOneBaselineRenderingContractAndMirror(){
        for(theme in listOf("day","night")){
            val actual=nodes(scene(theme)).associateBy{it.optString("id")}
            for(number in 0..7){
                fun anchor(side:String):NativeHelmLayout.TextAnchor {
                    val id="rpm-scale-$side-label-$number";val node=actual.getValue(id)
                    assertEquals("23px",node.getJSONObject("style").getString("font-size"))
                    assertEquals("middle",node.getJSONObject("style").getString("text-anchor"))
                    val attrs=node.getJSONObject("attrs")
                    return NativeHelmLayout.rpmLabelAnchor(id,attrs.f("x"),attrs.f("y"))
                }
                val port=anchor("port");val stbd=anchor("stbd")
                assertEquals(2000f-port.x,stbd.x,.001f);assertEquals(port.baseline,stbd.baseline,0f)
                if(number==7){
                    assertEquals(232f,port.x,0f);assertEquals(150f,port.baseline,0f)
                    assertTrue("7 continues upward from 6",port.baseline<anchorFor(actual,"rpm-scale-port-label-6").baseline-10f)
                }else assertEquals(anchorFor(actual,"rpm-scale-port-label-$number"),port)
            }
        }
    }
    private fun anchorFor(nodes:Map<String,JSONObject>,id:String):NativeHelmLayout.TextAnchor {
        val attrs=nodes.getValue(id).getJSONObject("attrs")
        return NativeHelmLayout.TextAnchor(attrs.f("x"),attrs.f("y"))
    }
}
