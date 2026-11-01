package com.no5.helm.ui

import android.net.Uri
import android.view.*
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.dp
import com.no5.helm.library.*
import com.no5.preparation.ui.No5ControlTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="de-w1000dp-h600dp-land-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MapM1LibraryFlowTest {
 @get:Rule val ui=createEmptyComposeRule()
 private fun all(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap{all(v.getChildAt(it))}else emptyList()
 @Test fun failedActivationRetainsTaskAndOnlyDurableSuccessResumesRequestedNavigation(){
  val stored=ChartPackage("p","Fixture",0,(0..1).map{ChartCell("c$it",1,0,"$it.000","20261024",emptyMap(),provider="wsv-s57")},source="WSV")
  var submitted:ChartSelection?=null;var navigations=0
  val actions=object:ChartLibraryActions{
   override val state=MutableStateFlow(ChartLibraryUiState(ChartLibrarySnapshot(listOf(stored),ChartSelection("p",listOf("c0")))))
   override val supportsResidentSelection=true;override val supportsLibraryManagement=true
   override fun importPackage(uri:Uri){};override fun cancelImport(){}
   override fun activate(selection:ChartSelection){submitted=selection;state.value=state.value.copy(busy=true)}
  }
  val gate=MapTaskExitGate()
  Robolectric.buildActivity(ComponentActivity::class.java).apply{get().setTheme(android.R.style.Theme_Material_NoActionBar)}.setup().visible().windowFocusChanged(true).use{h->
   h.get().setContent{No5ControlTheme(false){CompositionLocalProvider(LocalMapTaskExit provides gate){Box(Modifier.fillMaxWidth().height(404.dp)){ChartLibraryPanel(actions,false,workspace=true)}}}}
   fun target(key:String)=all(h.get().window.decorView).single{it.tag==key}
   fun tap(key:String){ui.runOnIdle{assertTrue(target(key).isEnabled);target(key).performClick()};ui.waitForIdle()}
   tap("no5-row-chart-package-select-p");tap("no5-row-chart-package-cells");tap("no5-row-chart-cell-c1")
   ui.runOnIdle{gate.leave{navigations++}};focusHostInputWindow(h.get(),ui)
   ui.onNodeWithTag("unsaved-changes-save").performClick();ui.waitForIdle()
   assertEquals(setOf("c0","c1"),submitted!!.cells.toSet());assertEquals(0,navigations)
   ui.runOnIdle{actions.state.value=actions.state.value.copy(busy=false,message="Fixture activation failed")};ui.waitForIdle()
   ui.onNodeWithTag("unsaved-changes-error").assertTextContains("Fixture activation failed",substring=true)
   ui.onNodeWithTag("unsaved-changes-continue").performClick();focusHostInputWindow(h.get(),ui)
   ui.runOnIdle{assertTrue(target("no5-row-chart-cell-c1").isSelected);assertEquals(0,navigations);gate.leave{navigations++}}
   focusHostInputWindow(h.get(),ui);ui.onNodeWithTag("unsaved-changes-save").performClick();ui.waitForIdle()
   ui.runOnIdle{actions.state.value=actions.state.value.copy(busy=false,activationRevision=1,snapshot=ChartLibrarySnapshot(listOf(stored),submitted),message=null)};ui.waitForIdle()
   assertEquals(1,navigations)
   ui.runOnIdle{assertTrue(target("no5-row-chart-package-cells").isEnabled)}
  }
 }
 @Test fun packageSearchAndPageSurviveEnteringAndLeavingManagement(){
  val packages=(0..11).map{ChartPackage("p$it","Pack $it",0,emptyList(),source="WSV")}
  val actions=object:ChartLibraryActions{
   override val state=MutableStateFlow(ChartLibraryUiState(ChartLibrarySnapshot(packages)))
   override val supportsLibraryManagement=true
   override fun importPackage(uri:Uri){};override fun cancelImport(){};override fun activate(selection:ChartSelection){}
  }
  Robolectric.buildActivity(ComponentActivity::class.java).apply{get().setTheme(android.R.style.Theme_Material_NoActionBar)}.setup().visible().windowFocusChanged(true).use{h->
   h.get().setContent{No5ControlTheme(false){Box(Modifier.fillMaxWidth().height(404.dp)){ChartLibraryPanel(actions,false,workspace=true)}}}
   fun target(key:String)=all(h.get().window.decorView).single{it.tag==key}
   fun tap(key:String){ui.runOnIdle{assertTrue(target(key).isEnabled);target(key).performClick()};ui.waitForIdle()}
   ui.runOnIdle{(target("no5-field-m1-search") as android.widget.EditText).setText("Pack")};ui.waitForIdle()
   tap("no5-action-m1-page-next");tap("no5-row-chart-package-select-p4");tap("no5-workspace-back")
   ui.runOnIdle{assertEquals("Pack",(target("no5-field-m1-search") as android.widget.EditText).text.toString());assertTrue(target("no5-row-chart-package-select-p4").isEnabled)}
  }
 }

}
