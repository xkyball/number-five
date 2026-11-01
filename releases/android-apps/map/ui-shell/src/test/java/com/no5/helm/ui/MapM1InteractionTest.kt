package com.no5.helm.ui

import android.view.*
import android.widget.EditText
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.dp
import com.no5.helm.chart.*
import com.no5.helm.library.*
import com.no5.preparation.ui.*
import com.no5.ui.UiLanguage
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="de-w1000dp-h600dp-land-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MapM1InteractionTest {
 @get:Rule val ui=createEmptyComposeRule()
 private fun all(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap{all(v.getChildAt(it))}else emptyList()
 private fun host()=Robolectric.buildActivity(ComponentActivity::class.java).apply{get().setTheme(android.R.style.Theme_Material_NoActionBar)}.setup().visible().windowFocusChanged(true)
 private fun target(root:View,key:String)=all(root).single{it.tag==key}
 private fun tap(root:View,key:String){ui.runOnIdle{val v=target(root,key);assertTrue("Disabled: $key",v.isEnabled);assertTrue(v.performClick())};ui.waitForIdle()}
 @After fun reset(){UiLanguage.acceptConfirmed(RuntimeEnvironment.getApplication(),"de")}
 @Test fun cellSelectionSurvivesPagesSearchToolsAndMapPanDoesNotSelect(){
  val cells=(0..7).map{ChartCell("c$it",1,0,"c$it.000","20261024",emptyMap(),selectionExtent=if(it==7)null else ChartSelectionExtent(42.0,43.0,-72.0,-71.0))}
  val stored=ChartPackage("p","Package",0,cells);var query by mutableStateOf("");var selected by mutableStateOf(emptySet<String>());var mutations=0
  host().use{h->h.get().setContent{No5ControlTheme(false){Box(Modifier.fillMaxWidth().height(404.dp)){
   MapRegionWorkspace(stored,chartRegions(cells.filter{it.name.contains(query)}),selected,emptySet(),query,{query=it},false,false,true,false,100,false,false,{}, {},"",null,{}, {},null,{mutations++;selected=it},{mutations++;selected=selected+it},emptySet())
  }}}
   val root=h.get().window.decorView
   tap(root,"no5-row-chart-cell-c0");assertEquals(setOf("c0"),selected)
   tap(root,"no5-action-chart-next");tap(root,"no5-row-chart-cell-c3");tap(root,"no5-action-chart-prev")
   ui.runOnIdle{assertTrue(target(root,"no5-row-chart-cell-c0").isSelected)
    val v=target(root,"map-m1-workspace");val now=android.os.SystemClock.uptimeMillis()
    listOf(Triple(MotionEvent.ACTION_DOWN,500f,360f),Triple(MotionEvent.ACTION_MOVE,580f,400f),Triple(MotionEvent.ACTION_UP,580f,400f)).forEachIndexed{i,e->val event=MotionEvent.obtain(now,now+i*20,e.first,e.second,e.third,0);v.dispatchTouchEvent(event);event.recycle()}
   };assertEquals(2,mutations)
   ui.runOnIdle{
    val v=target(root,"map-m1-workspace");val now=android.os.SystemClock.uptimeMillis()
    for(action in listOf(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_UP)){val event=MotionEvent.obtain(now,now+20,action,732f,424f,0);v.dispatchTouchEvent(event);event.recycle()}
   };ui.waitForIdle()
   tap(root,"no5-row-chart-hit-c1");assertEquals(2,mutations)
   tap(root,"no5-workspace-back");ui.runOnIdle{assertTrue(target(root,"no5-row-chart-hit-c0").isEnabled)}
   tap(root,"no5-workspace-back")
   ui.runOnIdle{(target(root,"no5-field-chart-region-filter") as EditText).setText("c7")};ui.waitForIdle()
   tap(root,"no5-row-chart-cell-c7");assertEquals(setOf("c0","c3","c7"),selected)
   tap(root,"no5-action-chart-region-tools");tap(root,"no5-row-chart-select-none");assertTrue(selected.isEmpty())
  }
 }
 @Test fun classChangesRequireConfirmedCustomAndDoNotDisplayUnconfirmedIntent(){
  var state by mutableStateOf(ChartObjectFilterState(true));var sent:ChartObjectFilters?=null;var categories=0
  host().use{h->h.get().setContent{No5ControlTheme(false){Box(Modifier.fillMaxWidth().height(404.dp)){
   MapIndividualClasses(state,linkedMapOf("BOYLAT" to "Buoy, lateral","LIGHTS" to "Light"),false,null,false,{}, {categories++},{sent=it})
  }}}
   val root=h.get().window.decorView
   ui.runOnIdle{assertFalse(target(root,"no5-row-s52-class-BOYLAT").isEnabled)}
   tap(root,"no5-row-s52-scope");assertEquals(1,categories);assertNull(sent)
   ui.runOnIdle{state=state.copy(effective=state.effective.copy(category=S52DisplayCategory.CUSTOM),revision=1)}
   tap(root,"no5-row-s52-class-BOYLAT");assertTrue("BOYLAT" in sent!!.hiddenClasses)
   ui.runOnIdle{assertTrue(target(root,"no5-row-s52-class-BOYLAT").isSelected);state=state.copy(effective=sent!!,revision=2)}
   ui.runOnIdle{assertFalse(target(root,"no5-row-s52-class-BOYLAT").isSelected)}
  }
 }
 @Test fun offlineRouteCoordinatesStayEditableAndChartActionsStayBlocked(){
  var state by mutableStateOf(RoutesState(draft=RouteDocument("d","Draft",frozenListOf(RoutePoint(1.0,1.0,name="A"),RoutePoint(2.0,2.0,name="B"))),dirty=true,saveSupported=false))
  host().use{h->h.get().setContent{No5ControlTheme(false){Box(Modifier.fillMaxWidth().height(404.dp)){
   MapRoutesWorkspace(state,false,{state=reduceRoutes(state,it)},{},false,{}, {}, {})
  }}}
   val root=h.get().window.decorView
   ui.runOnIdle{(target(root,"no5-field-route-name") as EditText).setText("New name");assertFalse(target(root,"no5-action-route-save").isEnabled)}
   assertEquals("New name",state.draft!!.name)
   tap(root,"no5-row-route-point-0");ui.runOnIdle{assertFalse(target(root,"no5-row-route-point-map").isEnabled)}
   tap(root,"no5-row-route-point-edit");assertNotNull(state.coordinate)
  }
 }
 @Test fun routeOrderSelectionMovesAcrossPagesAsOneUndoableAction(){
  val document=RouteDocument("d","Draft",FrozenList.copyOf((0..3).map{RoutePoint(it.toDouble(),it.toDouble(),"p$it","P$it")}))
  var state by mutableStateOf(RoutesState(draft=document,dirty=true,saveSupported=false))
  host().use{h->h.get().setContent{No5ControlTheme(false){Box(Modifier.fillMaxWidth().height(404.dp)){
   MapRoutesWorkspace(state,false,{state=reduceRoutes(state,it)},{},false,{}, {}, {})
  }}}
   val root=h.get().window.decorView
   tap(root,"no5-action-route-next");tap(root,"no5-row-route-point-3");tap(root,"no5-row-route-point-order");tap(root,"no5-row-route-order-0")
   assertEquals(listOf("p3","p0","p1","p2"),state.draft!!.points.map{it.id})
   tap(root,"no5-workspace-back");tap(root,"no5-action-route-undo");assertEquals(document,state.draft)
  }
 }

}
