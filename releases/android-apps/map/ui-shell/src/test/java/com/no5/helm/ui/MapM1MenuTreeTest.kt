package com.no5.helm.ui

import android.graphics.*
import android.view.*
import android.widget.ScrollView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.dp
import com.no5.header.artwork.*
import com.no5.helm.chart.*
import com.no5.preparation.ui.*
import com.no5.ui.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="de-w1000dp-h600dp-land-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MapM1MenuTreeTest {
 @get:Rule val ui=createEmptyComposeRule()
 private fun all(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap{all(v.getChildAt(it))}else emptyList()
 @After fun reset(){No5WorkspaceDiagnostics.observe=null;RuntimeEnvironment.setFontScale(1f);UiLanguage.acceptConfirmed(RuntimeEnvironment.getApplication(),"de")}
 @Test fun settingsAndContentHaveOnlyTaskEntriesAndAllStaticMenusFitWithoutScrolling(){
  var epoch by mutableIntStateOf(0);var night by mutableStateOf(false);var styles=0;var positions=0;var info=0
  val fits=mutableListOf<No5WorkspaceTextFit>();No5WorkspaceDiagnostics.observe={fits+=it}
  Robolectric.buildActivity(ComponentActivity::class.java).apply{get().setTheme(android.R.style.Theme_Material_NoActionBar)}.setup().visible().windowFocusChanged(true).use{h->
   h.get().setContent{key(epoch){No5ControlTheme(night){Box(Modifier.fillMaxWidth().height(404.dp)){
    MapSettingsWorkspace(SettingsState(),{},night,{}, {positions++},{styles++},{error("No renderer entry")},{error("No connection diagnostics entry")},{info++},
     library={back->No5Workspace("Library fixture","",emptyList(),night,back)},
     layers={back->MapContentWorkspace(ChartObjectFilterState(true),mapOf("BOYLAT" to "Lateral buoy"),false,null,night,back,{}){close->MapLayersWorkspace(actualLayers(),{},night,close,objectContent={})}},
     displayContent={},diagnosticsContent={error("No diagnostic page")})
   }}}}
   fun target(key:String)=all(h.get().window.decorView).single{it.tag==key}
   fun verify(){ui.runOnIdle{
    val view=target("no5-workspace");val image=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888);fits.clear();view.draw(Canvas(image));image.recycle()
    val rows=all(view).filter{it.tag?.toString()?.startsWith("no5-row-")==true};assertTrue(rows.size<=5)
    val scroll=all(view).filterIsInstance<ScrollView>().single();assertTrue("Menu must not scroll",scroll.getChildAt(0).height<=scroll.height)
    fits.filterNot{it.dynamic}.forEach{assertTrue("Static menu label clips: $it",it.width<=it.available+.01f)}
    assertFalse(all(view).any{it.tag?.toString()?.contains("diagnostic")==true})
   }}
   fun tap(key:String){ui.runOnIdle{assertTrue(target(key).isEnabled);target(key).performClick()};ui.waitForIdle();verify()}
   for(font in listOf(1f,1.25f))for(language in listOf("de","en","af"))for(n in listOf(false,true)){
    ui.runOnIdle{RuntimeEnvironment.setFontScale(font);UiLanguage.acceptConfirmed(h.get(),language);night=n;epoch++};ui.waitForIdle();verify()
    tap("no5-row-map-settings-position");tap("no5-row-map-settings-appearance");tap("no5-row-map-appearance-style")
    tap("no5-row-map-settings-layers");tap("no5-row-m1-content-labels");tap("no5-workspace-back");tap("no5-row-m1-content-groups");tap("no5-row-m1-content-aids");tap("no5-workspace-back");tap("no5-workspace-back");tap("no5-workspace-back")
    tap("no5-workspace-back");tap("no5-row-map-settings-display");tap("no5-row-map-display-distance");tap("no5-workspace-back");tap("no5-row-map-display-speed");tap("no5-workspace-back");tap("no5-row-map-display-dimming");tap("no5-workspace-back");tap("no5-workspace-back")
    tap("no5-row-map-settings-about");tap("no5-row-map-about-build");tap("no5-row-map-about-changes")
   }
   assertEquals(12,styles);assertEquals(12,positions);assertEquals(24,info)
  }
 }
}
