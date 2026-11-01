package com.no5.helm

import android.content.Context
import android.os.SystemClock
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.platform.app.InstrumentationRegistry
import com.no5.helm.chart.*
import com.no5.helm.model.HelmState
import com.no5.helm.ui.*
import com.no5.preparation.ui.No5ControlTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Actual native MAP navigation, synthetic camera owner only. No chart/native library/source or audio port. */
class MapKnobContextDeviceTest {
    @get:Rule val ui=createComposeRule()
    private class Camera:ChartEngine {
        override val status=MutableStateFlow(ChartEngineStatus(initialized=true,touchOutlinesEnabled=true))
        var controls=0;var outlinesDisabled=false
        var failStyle=false
        private var next=0L
        fun ready(){val v=ChartViewport(42.36,-71.04,.15);status.value=status.value.copy(chartLoaded=true,surfaceAttached=true,viewport=v,
            confirmedFrame=ChartFrame(1,"synthetic-ui-camera",ChartSurfaceGeometry.fit(2000,1070),v,ChartScale(120.0,500.0)))}
        override fun submitCameraControl(control:ChartCameraControl,amount:Double,sequence:Long,physicalUptimeMs:Long,latitude:Double,longitude:Double,homeScale:Double):Long {controls++;return ++next}
        override fun attachSurface(surface:Surface,width:Int,height:Int)=Unit
        override fun detachSurface()=Unit
        override fun setViewport(viewport:ChartViewport)=Unit
        override fun setS52Settings(settings:S52Settings)=Unit
        override suspend fun applyPresentationProfile(value:ChartPresentation):EngineResult {
            if(failStyle)return EngineResult(false,"SYNTHETIC_STYLE_FAILURE")
            status.value=status.value.copy(presentation=value);return EngineResult(true)
        }
        override fun setTouchOutlinesEnabled(enabled:Boolean):Boolean {outlinesDisabled=!enabled;status.value=status.value.copy(touchOutlinesEnabled=enabled);return true}
        override suspend fun initialize(config:ChartEngineConfig)=EngineResult(true)
        override suspend fun loadChartSet(id:String,paths:List<String>)=EngineResult(true)
        override suspend fun queryFeature(point:ScreenPoint)=emptyList<ChartFeature>()
        override fun setOwnship(state:OwnshipProjection)=Unit
        override fun setAisTargets(targets:List<AisProjection>)=Unit
        override fun setRouteProjection(route:RouteProjection?)=Unit
        override fun setWaypointProjection(waypoints:List<WaypointProjection>)=Unit
        override fun close()=Unit
    }
    private fun descendants(view:View):List<View> = listOf(view)+if(view is ViewGroup)(0 until view.childCount).flatMap{descendants(view.getChildAt(it))}else emptyList()
    private fun nativeTap(tag:String){ui.runOnIdle{
        val target=WindowInspector.getGlobalWindowViews().flatMap{descendants(it)}.single{it.tag==tag}
        assertTrue(target.isEnabled);target.performClick()
    };ui.waitForIdle()}
    private fun navigate(tag:String){
        if(ui.onAllNodesWithTag("map-dock").fetchSemanticsNodes().isEmpty()){
            val context=InstrumentationRegistry.getInstrumentation().targetContext
            val diagnostics=java.io.File(context.getExternalFilesDir(null),"map-knob-context").apply{mkdirs()}
            fun record(name:String){
                java.io.File(diagnostics,"$name.txt").writeText(ui.onAllNodes(isRoot()).fetchSemanticsNodes().joinToString("\n") { it.toString() })
                ui.runOnIdle{
                    val root=WindowInspector.getGlobalWindowViews().last{it.hasWindowFocus()}
                    val bitmap=android.graphics.Bitmap.createBitmap(root.width,root.height,android.graphics.Bitmap.Config.ARGB_8888)
                    try{root.draw(android.graphics.Canvas(bitmap));java.io.File(diagnostics,"$name.png").outputStream().use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}}finally{bitmap.recycle()}
                }
            }
            record("before-$tag-touch")
            ui.onNodeWithTag("map-dock-toggle").assertIsEnabled()
            ui.onNodeWithTag("map-dock-toggle").performTouchInput{click()};ui.waitForIdle();record("after-$tag-touch")
            ui.waitUntil(3000){ui.onAllNodesWithTag("map-dock").fetchSemanticsNodes().isNotEmpty()}
        }
        ui.onNodeWithTag(tag).performTouchInput{click()};ui.waitForIdle()
    }
    @Test fun unavailableMenuAndDialogKeepAllCameraClaimsAndCannotReachAnotherController(){
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName=="com.no5.map.unificationproof"){"Run only in the isolated UI proof package"}
        context.getSharedPreferences("native-screens-v1",Context.MODE_PRIVATE).edit().clear().putString("lastDestination","CHART").putBoolean("panelExpanded",false).putBoolean("touchOutlinesEnabled",true).commit()
        val camera=Camera();val backend=MutableStateFlow(HelmState())
        var windowFocused=false;var lifecycle=""
        var buffered=emptyMap<ChartKnobInput,ChartBufferedKnobAction>()
        var legacy=emptyMap<ChartKnobInput,(ChartKnobRequest)->Unit>()
        ui.setContent{val window=LocalWindowInfo.current;val owner=LocalLifecycleOwner.current;SideEffect{windowFocused=window.isWindowFocused;lifecycle=owner.lifecycle.currentState.name};No5ControlTheme(false){NativeHelmScreen(camera,backend,"SYNTHETIC_NO_SOURCE","SYNTHETIC UI",ChartViewport(),
            onBufferedKnobBindings={buffered=it},onKnobBindings={legacy=it},onConnect={error("No source connects in this proof")})}}
        ui.waitUntil(3000){var focused=false;InstrumentationRegistry.getInstrumentation().runOnMainSync{
            focused=WindowInspector.getGlobalWindowViews().any{it.hasWindowFocus()}
        };focused};ui.waitForIdle()
        fun rejected(){ui.runOnIdle{
            assertEquals(ChartKnobInput.entries.toSet(),buffered.keys);assertEquals(ChartKnobInput.entries.toSet(),legacy.keys)
            val before=camera.controls
            ChartKnobInput.entries.forEachIndexed{index,input ->
                assertEquals(0L,buffered.getValue(input).submit(index+1L,SystemClock.uptimeMillis()))
                var result:Boolean?=null;legacy.getValue(input)(ChartKnobRequest(SystemClock.uptimeMillis()+60,{true},{result=it}));assertEquals(false,result)
            }
            assertEquals("Covered MAP must not submit hidden camera work",before,camera.controls)
        }}
        rejected()
        ui.runOnIdle{assertTrue(camera.outlinesDisabled);assertFalse(context.getSharedPreferences("native-screens-v1",Context.MODE_PRIVATE).contains("touchOutlinesEnabled"));camera.ready()}
        ui.waitForIdle()
        ui.runOnIdle{assertEquals("Native window focus $windowFocused / $lifecycle",true,windowFocused);assertEquals("RESUMED",lifecycle)}
        lateinit var retired:ChartBufferedKnobAction
        ui.runOnIdle{retired=buffered.getValue(ChartKnobInput.RIGHT_CW);assertTrue(retired.submit(30,SystemClock.uptimeMillis())>0);assertEquals(1,camera.controls)}
        navigate("nav-SETTINGS");rejected()
        ui.runOnIdle{assertEquals(0L,retired.submit(31,SystemClock.uptimeMillis()));assertEquals(1,camera.controls)}
        nativeTap("no5-row-map-settings-position");rejected()
        ui.onNodeWithTag("position-close").performClick();ui.waitForIdle()
        navigate("nav-CHART");ui.runOnIdle{assertTrue(buffered.getValue(ChartKnobInput.RIGHT_CCW).submit(40,SystemClock.uptimeMillis())>0);assertEquals(2,camera.controls)}
        ui.runOnIdle{camera.status.value=camera.status.value.copy(sourcePresentationUnsupported=true)};ui.waitForIdle();rejected()
    }
    @Test fun actualStyleFailureDialogRetainsClaimsAndBlocksItsCoveredCameraUntilDismissed(){
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName=="com.no5.map.unificationproof")
        context.getSharedPreferences("native-screens-v1",Context.MODE_PRIVATE).edit().clear().putString("lastDestination","CHART").putBoolean("panelExpanded",false).commit()
        val camera=Camera().apply{failStyle=true;ready()}
        var bindings=emptyMap<ChartKnobInput,ChartBufferedKnobAction>()
        ui.setContent{No5ControlTheme(false){NativeHelmScreen(camera,MutableStateFlow(HelmState()),"SYNTHETIC_NO_SOURCE","SYNTHETIC UI",ChartViewport(),
            onBufferedKnobBindings={bindings=it},onConnect={error("No source connects in this proof")})}}
        ui.waitUntil(3000){ui.onAllNodesWithTag("map-message").fetchSemanticsNodes().isNotEmpty()}
        ui.runOnIdle{
            assertEquals(ChartKnobInput.entries.toSet(),bindings.keys)
            for(input in ChartKnobInput.entries)assertEquals(0L,bindings.getValue(input).submit(1,SystemClock.uptimeMillis()))
            assertEquals(0,camera.controls)
        }
        ui.onNodeWithTag("Schließen").performClick();ui.waitForIdle()
        ui.waitUntil(3000){var focused=false;InstrumentationRegistry.getInstrumentation().runOnMainSync{focused=WindowInspector.getGlobalWindowViews().any{it.hasWindowFocus()}};focused}
        ui.runOnIdle{assertTrue(bindings.getValue(ChartKnobInput.RIGHT_CW).submit(2,SystemClock.uptimeMillis())>0);assertEquals(1,camera.controls)}
    }
}
