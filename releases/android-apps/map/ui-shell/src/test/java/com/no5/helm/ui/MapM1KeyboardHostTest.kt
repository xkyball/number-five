package com.no5.helm.ui

import android.graphics.*
import android.view.*
import android.widget.EditText
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import com.no5.helm.library.*
import com.no5.preparation.ui.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.*

/** Actual shell integration with reported keyboard geometry; no physical keyboard claim. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29,35],qualifiers="de-w1000dp-h600dp-land-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MapM1KeyboardHostTest {
 @get:Rule val ui=createEmptyComposeRule()
 private fun all(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap{all(v.getChildAt(it))}else emptyList()
 @Test fun keyboardReflowsOnlyWorkAreaAndRetainsDraftSelectionAndFixedDock(){
  val cells=(0..11).map{ChartCell("cell-$it",1,0,"$it.000","20261024",emptyMap())};val stored=ChartPackage("p","Package",0,cells)
  var query by mutableStateOf("");var selected by mutableStateOf(setOf("cell-0"))
  Robolectric.buildActivity(No5ImmersiveActivity::class.java).apply{get().setTheme(android.R.style.Theme_Material_NoActionBar)}.setup().visible().windowFocusChanged(true).use{h->
   h.get().setContent{No5ControlTheme(false){No5Shell(ShellState(destination=Destination.SETTINGS),{},workspacePanels=true,rendererSlot={Box(it)},panelContent={_,_->
    MapRegionWorkspace(stored,chartRegions(cells.filter{it.name.contains(query)}),selected,emptySet(),query,{query=it},false,false,true,false,100,false,false,{}, {},"",null,{}, {},null,{selected=it},{selected=selected+it},emptySet())
   })}}
   ui.onNodeWithTag("map-dock-toggle").performClick()
   val dock=ui.onNodeWithTag("map-dock").fetchSemanticsNode().boundsInRoot
   val renderer=ui.onNodeWithTag("renderer-slot").fetchSemanticsNode().boundsInRoot
   fun target(key:String)=all(h.get().window.decorView).single{it.tag==key}
   var field:EditText?=null
   ui.runOnIdle{field=target("no5-field-chart-region-filter") as EditText;field!!.setText("cell-")};ui.waitForIdle()
   val normal=ui.onNodeWithTag("screen-panel").fetchSemanticsNode().boundsInRoot
   repeat(2){
    ui.runOnIdle{com.no5.keyboard.SpecialKeyboardGeometry.publish(h.get(),h.get().packageName,Rect(0,772,2000,1200))};ui.waitForIdle()
    assertEquals(dock,ui.onNodeWithTag("map-dock").fetchSemanticsNode().boundsInRoot)
    assertEquals(renderer,ui.onNodeWithTag("renderer-slot").fetchSemanticsNode().boundsInRoot)
    ui.runOnIdle{
     val view=target("map-m1-workspace");assertEquals(642,view.height)
     assertSame(field,target("no5-field-chart-region-filter"));assertEquals("cell-",field!!.text.toString())
     val footer=target("no5-action-chart-package-activate");val origin=IntArray(2);footer.getLocationOnScreen(origin);assertTrue(origin[1]+footer.height<=772)
     assertTrue(target("no5-row-chart-cell-cell-0").isSelected)
    }
    ui.runOnIdle{com.no5.keyboard.SpecialKeyboardGeometry.publish(h.get(),h.get().packageName,null)};ui.waitForIdle()
    assertEquals(normal,ui.onNodeWithTag("screen-panel").fetchSemanticsNode().boundsInRoot)
    assertEquals(setOf("cell-0"),selected);assertEquals("cell-",query)
   }
  }
 }
}
