package com.no5.preparation.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas as NativeCanvas
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.mandatorySystemGestures
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.unit.dp
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Production native shell + historical S52 image in a test-only renderer slot.
 * Actual GLES, import and benchmark lifetimes are tested separately with RecordingEngine.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29,35],qualifiers="en-rUS-w2000dp-h1200dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ApprovedMapShellTest {
    @Test @Config(sdk=[29],qualifiers="w1000dp-h600dp-land-xhdpi")
    fun designDensityNeverLeaksIntoRendererPanelsOrTheirDialogs() {
        // A platform Dialog has its own AndroidComposeView and uses the real Android
        // configuration. Set host-only accessibility fontScale, never device settings.
        val config=android.content.res.Configuration(ui.activity.resources.configuration).apply { fontScale=1.25f }
        @Suppress("DEPRECATION")
        ui.activity.resources.updateConfiguration(config,ui.activity.resources.displayMetrics)
        var rendererDensity: androidx.compose.ui.unit.Density?=null
        var panelDensity: androidx.compose.ui.unit.Density?=null
        var showDialog by mutableStateOf(false)
        var dialogDensity: androidx.compose.ui.unit.Density?=null
        ui.setContent {
            val androidDensity=androidx.compose.ui.platform.LocalDensity.current
            CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides
                androidx.compose.ui.unit.Density(androidDensity.density,1.25f)) {
                No5Shell(ShellState(destination=Destination.SETTINGS,chart=ChartUiState(status=DataStatus(Availability.READY,"TEST"))),{},
                    rendererSlot={modifier->rendererDensity=androidx.compose.ui.platform.LocalDensity.current;Box(modifier)},
                    panelContent={_,modifier->
                        panelDensity=androidx.compose.ui.platform.LocalDensity.current
                        Box(modifier){No5Action("DIALOG",{showDialog=true},Modifier.testTag("native-panel-action"))}
                        if(showDialog)androidx.compose.material3.AlertDialog(onDismissRequest={showDialog=false},title={androidx.compose.material3.Text("Native dialog")},
                            confirmButton={dialogDensity=androidx.compose.ui.platform.LocalDensity.current;No5Action("CLOSE",{},Modifier.testTag("native-dialog-action"))})
                    })
            }
        }
        ui.onNodeWithTag("native-panel-action").performClick()
        ui.onNodeWithTag("native-dialog-action").assertIsDisplayed()
        ui.runOnIdle {
            for((label,density) in listOf("renderer" to rendererDensity,"panel" to panelDensity,"dialog" to dialogDensity)) {
                assertEquals(label,2f,density!!.density,0f);assertEquals(label,1.25f,density.fontScale,0f)
            }
        }
        assertTrue(ui.onNodeWithTag("native-dialog-action").fetchSemanticsNode().boundsInRoot.height>=112f)
        val map=ui.onNodeWithTag("renderer-slot").fetchSemanticsNode().boundsInRoot
        assertEquals(2000f,map.width,.1f);assertEquals(1070f,map.height,.1f)
    }
    // This library has no launcher. Register the host-only ComponentActivity before
    // Compose's ActivityScenario rule; no uncached test-manifest dependency is needed.
    @get:Rule(order=0) val hostManifest=org.junit.rules.TestRule { base,_ -> object:org.junit.runners.model.Statement() {
        override fun evaluate() {
            val app=org.robolectric.RuntimeEnvironment.getApplication()
            org.robolectric.Shadows.shadowOf(app.packageManager).addOrUpdateActivity(android.content.pm.ActivityInfo().apply {
                packageName=app.packageName;name=androidx.activity.ComponentActivity::class.java.name
                enabled=true;exported=true;applicationInfo=app.applicationInfo;theme=android.R.style.Theme_Material_NoActionBar
            })
            base.evaluate()
        }
    } }
    @get:Rule(order=1) val ui=createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private fun syntheticInsets(enabled:Boolean) {
        // Foundation's test hook retains artificial insets on hosts with hardware keys.
        val holder=Class.forName("androidx.compose.foundation.layout.WindowInsetsHolder")
        val companion=holder.getField("Companion").get(null)
        companion.javaClass.getMethod("setUseTestInsets",Boolean::class.javaPrimitiveType).invoke(companion,enabled)
    }
    @After fun restoreRealInsetHardware(){syntheticInsets(false)}
    @Before fun immersiveWindowLikeProduct() {
        ui.activity.window.apply {
            clearFlags(android.view.WindowManager.LayoutParams.FLAG_FORCE_NOT_FULLSCREEN)
            addFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN)
            androidx.core.view.WindowCompat.setDecorFitsSystemWindows(this,false)
            if(android.os.Build.VERSION.SDK_INT<30)decorView.systemUiVisibility=decorView.systemUiVisibility or
                android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE or android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or android.view.View.SYSTEM_UI_FLAG_FULLSCREEN or
                android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            androidx.core.view.WindowCompat.getInsetsController(this,decorView).hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        }
    }
    private fun openDock() {
        ui.onNodeWithTag("no5-shell").performTouchInput {
            down(androidx.compose.ui.geometry.Offset(center.x,height-2f))
            moveBy(androidx.compose.ui.geometry.Offset(0f,-90f));up()
        }
        ui.onNodeWithTag("map-dock").assertIsDisplayed()
    }
    private val mapRoot get()=generateSequence(File(".").canonicalFile){it.parentFile}.map{if(File(it,"studies/chart-styles").exists())it else File(it,"map")}.first{File(it,"studies/chart-styles").exists()}
    @Test fun dockOpenerAcceptsPhysicalTapAndRestoresOpenAndClosedState() {
        var state by mutableStateOf(ShellState(chart=ChartUiState(status=DataStatus(Availability.READY,"TEST"))))
        var blocked=emptyList<androidx.compose.ui.geometry.Rect>()
        var gestureBlocked=emptyList<androidx.compose.ui.geometry.Rect>()
        val restore=StateRestorationTester(ui)
        restore.setContent { No5Shell(state,{},rendererSlot={Box(it)},
            onBlockedRegions={blocked=it},onGestureBlockedRegions={gestureBlocked=it}) }
        val renderer=ui.onNodeWithTag("renderer-slot").fetchSemanticsNode().boundsInRoot
        val opener=ui.onNodeWithTag("map-dock-toggle").assertIsDisplayed().assertHasClickAction()
            .assertContentDescriptionEquals("Kartenmenü öffnen").fetchSemanticsNode().boundsInRoot
        val point=opener.center-renderer.topLeft
        assertEquals("Opener centered on map",renderer.center.x,opener.center.x,.1f)
        assertEquals("Opener at bottom with 8 design-pixel margin",renderer.bottom-8f,opener.bottom,.1f)
        assertTrue(blocked.any{it.contains(point)})
        assertFalse(gestureBlocked.any{it.contains(point)})
        ui.runOnIdle{state=state.copy(chart=state.chart.copy(touchActive=true))}
        ui.onNodeWithTag("map-dock-toggle").assertDoesNotExist()
        assertFalse(blocked.any{it.contains(point)})
        ui.runOnIdle{state=state.copy(chart=state.chart.copy(touchActive=false))}
        ui.onNodeWithTag("map-dock-toggle").performTouchInput{down(center)}
        ui.onNodeWithTag("map-dock-toggle").assertIsDisplayed().performTouchInput{up()}
        ui.onNodeWithTag("map-dock").assertIsDisplayed()
        ui.onNodeWithTag("map-dock-toggle").assertDoesNotExist()
        restore.emulateSavedInstanceStateRestore()
        ui.onNodeWithTag("map-dock").assertIsDisplayed()
        ui.onNodeWithTag("dock-close").performTouchInput{click()}
        restore.emulateSavedInstanceStateRestore()
        ui.onNodeWithTag("map-dock").assertDoesNotExist()
        ui.onNodeWithTag("map-dock-toggle").assertIsDisplayed()
        assertEquals(renderer,ui.onNodeWithTag("renderer-slot").fetchSemanticsNode().boundsInRoot)
    }
    @Test @Config(sdk=[29],qualifiers="w1000dp-h464dp-land-xhdpi")
    fun dockOpenerKeepsFortyEightAndroidDpInTheSmallerOemWindow() {
        ui.setContent { No5Shell(ShellState(chart=ChartUiState(status=DataStatus(Availability.READY,"TEST"))),{},rendererSlot={Box(it)}) }
        val bounds=ui.onNodeWithTag("map-dock-toggle").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val density=ui.activity.resources.displayMetrics.density
        assertTrue("Dock opener touch width >= 48 Android dp: $bounds",bounds.width/density>=48f-.01f)
        assertTrue("Dock opener touch height >= 48 Android dp: $bounds",bounds.height/density>=48f-.01f)
        val renderer=ui.onNodeWithTag("renderer-slot").fetchSemanticsNode().boundsInRoot
        assertEquals("Pixel rounding in scaled OEM window",renderer.center.x,bounds.center.x,1f)
        assertTrue(bounds.bottom<=renderer.bottom)
        ui.onNodeWithTag("map-dock-toggle").performTouchInput{click()}
        ui.onNodeWithTag("map-dock").assertIsDisplayed()
    }
    @Test fun collapsedPanelsRestoreMapControlsAndTheirTouchExclusions() {
        var state by mutableStateOf(ShellState(destination=Destination.SETTINGS,
            chart=ChartUiState(status=DataStatus(Availability.READY,"TEST"),zoomInAvailable=true,
                viewport=ViewportSnapshot(1,2000,1070,"TEST"))))
        var blocked=emptyList<androidx.compose.ui.geometry.Rect>()
        ui.setContent { No5Shell(state,{},rendererSlot={Box(it)},onBlockedRegions={blocked=it}) }
        val renderer=ui.onNodeWithTag("renderer-slot").fetchSemanticsNode().boundsInRoot
        for(destination in Destination.entries.filter{it!=Destination.CHART}) {
            ui.runOnIdle { state=state.copy(destination=destination,panelExpanded=true) }
            ui.onNodeWithTag("screen-panel").assertIsDisplayed()
            ui.onNodeWithTag("zoom-in").assertDoesNotExist()
            ui.runOnIdle { state=state.copy(panelExpanded=false) }
            ui.onNodeWithTag("screen-panel").assertDoesNotExist()
            for(tag in listOf("north-up","zoom-in","zoom-out","position-cycle"))
                ui.onNodeWithTag(tag).assertIsDisplayed()
            val zoom=ui.onNodeWithTag("zoom-in").fetchSemanticsNode().boundsInRoot.center-renderer.topLeft
            assertTrue("Visible map controls reserve input",blocked.any{it.contains(zoom)})
            ui.runOnIdle { state=state.copy(chart=state.chart.copy(touchActive=true)) }
            ui.onNodeWithTag("zoom-in").assertDoesNotExist()
            assertFalse("Gesture has no stale control hit area",blocked.any{it.contains(zoom)})
            ui.runOnIdle { state=state.copy(chart=state.chart.copy(touchActive=false)) }
            ui.onNodeWithTag("zoom-in").assertIsDisplayed()
            assertEquals(renderer,ui.onNodeWithTag("renderer-slot").fetchSemanticsNode().boundsInRoot)
        }
    }
    @Test fun mapDockNavigationBackAndRestorationDoNotResizeOrRemountRenderer() {
        var state by mutableStateOf(ShellState(chart=ChartUiState(status=DataStatus(Availability.READY,"TEST"),coverage="TEST",zoomInAvailable=true,viewport=ViewportSnapshot(1,2000,1070,"TEST"))))
        var mounts=0;var releases=0;var blocked=emptyList<androidx.compose.ui.geometry.Rect>();val events=mutableListOf<ShellEvent>()
        val restore=StateRestorationTester(ui)
        restore.setContent {
            Box(Modifier.size(2000.dp,1200.dp)){No5Shell(state,{event->events+=event;if(event is ShellEvent.Navigate)state=state.copy(destination=event.destination)},
                rendererSlot={modifier->Canvas(modifier){drawRect(androidx.compose.ui.graphics.Color.Blue)};DisposableEffect(Unit){mounts++;onDispose{releases++}}},
                onBlockedRegions={blocked=it},chartActions={No5DockAction("OBJEKTINFO",{})})}
        }
        fun bounds()=ui.onNodeWithTag("renderer-slot").fetchSemanticsNode().boundsInRoot
        val original=bounds();assertEquals(2000f,original.width,.1f);assertEquals(130f,original.top,.1f);assertEquals(1070f,original.height,.1f)
        ui.onNodeWithTag("map-dock").assertDoesNotExist();ui.onNodeWithTag("nav-CHART").assertDoesNotExist()
        openDock();ui.onNodeWithTag("nav-CHART").assertIsDisplayed()
        for(d in Destination.entries){ui.onNodeWithTag("nav-${d.name}").performClick();assertEquals(original,bounds())}
        ui.runOnIdle{assertEquals(1,mounts);assertEquals(0,releases);assertTrue(blocked.any{it.contains(androidx.compose.ui.geometry.Offset(49f,1027.4f))})}
        ui.onNodeWithTag("nav-CHART").performClick()
        ui.onNodeWithTag("dock-close").performClick();ui.onNodeWithTag("map-dock").assertDoesNotExist()
        openDock();restore.emulateSavedInstanceStateRestore();ui.onNodeWithTag("map-dock").assertIsDisplayed()
        assertEquals(original,bounds())
        ui.onNodeWithTag("north-up").performClick();ui.runOnIdle{assertTrue(ShellEvent.NorthUp in events)}
        ui.onNodeWithTag("position-cycle").assertIsNotEnabled()
        ui.runOnIdle{ui.activity.onBackPressedDispatcher.onBackPressed()}
        ui.onNodeWithTag("map-dock").assertDoesNotExist();assertEquals(original,bounds())
    }
    @Test fun nativePixelsProveInnerGrowthAndTwoMillimetreMapUnderlapDayAndNight() {
        var state by mutableStateOf(ShellState(chart=ChartUiState(status=DataStatus(Availability.READY,"TEST"),coverage="NOAA Boston",zoomInAvailable=true,selectedTarget="TESTZIEL",targetSummary="Auswahl bleibt erhalten")))
        var view:android.view.View?=null
        val images=listOf("revision-05/reference-native-day.png","revision-04/reference-redwatch.png").map{BitmapFactory.decodeFile(File(mapRoot,"studies/chart-styles/$it").path)}
        ui.setContent { view=LocalView.current
            Box(Modifier.size(2000.dp,1200.dp)){No5Shell(state,{if(it is ShellEvent.SetNight)state=state.copy(redNight=it.enabled)},rendererSlot={modifier->Canvas(modifier){
                drawIntoCanvas{canvas->canvas.nativeCanvas.drawBitmap(images[if(state.redNight)1 else 0],android.graphics.Rect(200,40,2200,1110),android.graphics.RectF(0f,0f,size.width,size.height),null)}
            }},chartActions={No5DockAction("OBJEKTINFO · AUSWAHL PER TOUCH",{},Modifier.fillMaxWidth())})}
        }
        val output=File("build/ui-evidence/dock-button/api${android.os.Build.VERSION.SDK_INT}").apply{mkdirs()}
        for(night in listOf(false,true)) {
            ui.onNodeWithTag(if(night)"palette-toggle"else"palette-day").performClick()
            for(open in listOf(false,true)) {
                if(open)openDock()
                ui.runOnIdle {
                    val image=Bitmap.createBitmap(2000,1200,Bitmap.Config.ARGB_8888);view!!.draw(NativeCanvas(image))
                    // The top body edge is y948.4. At y960, map extends 11.6px beneath it.
                    assertEquals("Map underlap retains source pixels",images[if(night)1 else 0].getPixel(249,870),image.getPixel(49,960))
                    assertEquals("Solid mask begins after the 16.88px underlap",if(night)0xff090000.toInt()else 0xfff6f8f7.toInt(),image.getPixel(49,973))
                    for(x in listOf(49+115+17,1943-112-17))assertEquals(0xff030303.toInt(),image.getPixel(x,1117))
                    File(output,"map-${if(open)"open"else"closed"}-${if(night)"night"else"day"}.png").outputStream().use{image.compress(Bitmap.CompressFormat.PNG,100,it)};image.recycle()
                }
                ui.runOnIdle{state=state.copy(chart=state.chart.copy(touchActive=true))}
                for(tag in listOf("map-dock","map-dock-toggle","zoom-in","zoom-out","north-up","position-cycle","target-overlay"))ui.onNodeWithTag(tag).assertDoesNotExist()
                ui.runOnIdle {
                    val image=Bitmap.createBitmap(2000,1200,Bitmap.Config.ARGB_8888);view!!.draw(NativeCanvas(image))
                    for((x,y) in listOf(1920 to 250,1576 to 998,1000 to 1060,1000 to 760))
                        assertEquals("Former button pixels show the map during touch",images[if(night)1 else 0].getPixel(x+200,y-90),image.getPixel(x,y))
                    File(output,"map-touch-${if(open)"open"else"closed"}-${if(night)"night"else"day"}.png").outputStream().use{image.compress(Bitmap.CompressFormat.PNG,100,it)};image.recycle()
                }
                ui.runOnIdle{state=state.copy(chart=state.chart.copy(touchActive=false))}
                ui.onNodeWithTag("zoom-in").assertIsDisplayed();ui.onNodeWithTag("target-overlay").assertIsDisplayed()
                ui.onNodeWithText("TESTZIEL").assertExists()
                if(open)ui.onNodeWithTag("dock-close").performClick()
            }
        };images.forEach{it.recycle()}
    }
    @Test fun reservedSystemBottomIsExcludedAndSpecialKeyboardDisablesDockSwipe() {
        syntheticInsets(true)
        var claims=0;var host:android.view.View?=null;var observedBottom=0
        ui.setContent { host=LocalView.current
            val d=androidx.compose.ui.platform.LocalDensity.current
            observedBottom=maxOf(WindowInsets.mandatorySystemGestures.getBottom(d),WindowInsets.navigationBars.getBottom(d),WindowInsets.displayCutout.getBottom(d))
            No5Shell(ShellState(chart=ChartUiState(status=DataStatus(Availability.READY,"TEST"))),{},
            rendererSlot={Box(it)},onDockGestureClaimed={claims++}) }
        ui.runOnIdle {
            val insets=androidx.core.view.WindowInsetsCompat.Builder()
                .setInsets(androidx.core.view.WindowInsetsCompat.Type.mandatorySystemGestures(),androidx.core.graphics.Insets.of(0,0,0,48))
                // Legacy compat infers IME from a synthetic nav bar without root stable
                // insets. API29 models the product's immersive hidden nav bar instead.
                .setInsets(androidx.core.view.WindowInsetsCompat.Type.navigationBars(),if(android.os.Build.VERSION.SDK_INT<30)androidx.core.graphics.Insets.NONE else androidx.core.graphics.Insets.of(0,0,0,24))
                .setInsets(androidx.core.view.WindowInsetsCompat.Type.displayCutout(),androidx.core.graphics.Insets.of(0,0,0,12)).build()
            assertEquals(48,insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.mandatorySystemGestures()).bottom)
            if(android.os.Build.VERSION.SDK_INT<30) {
                // This Robolectric host's compat animation never delivers typed settled
                // insets. Supply the settled Foundation state explicitly on API29.
                val type=Class.forName("androidx.compose.foundation.layout.WindowInsetsHolder")
                val companion=type.getField("Companion").get(null)
                val lookup=companion.javaClass.getDeclaredMethod("getOrCreateFor",android.view.View::class.java).apply{isAccessible=true}
                val holder=lookup.invoke(companion,requireNotNull(host))
                holder.javaClass.getMethod("update",androidx.core.view.WindowInsetsCompat::class.java,Int::class.javaPrimitiveType).invoke(holder,insets,0)
            } else androidx.core.view.ViewCompat.dispatchApplyWindowInsets(requireNotNull(host),insets)
        };ui.mainClock.advanceTimeBy(400);ui.waitForIdle()
        ui.runOnIdle{assertEquals("Compose must receive the synthetic settled insets",48,observedBottom)}
        fun swipe(reserved:Float) { ui.onNodeWithTag("no5-shell").performTouchInput {
            down(androidx.compose.ui.geometry.Offset(center.x,height-reserved-2f));moveBy(androidx.compose.ui.geometry.Offset(0f,-90f));up()
        } }
        swipe(0f);ui.onNodeWithTag("map-dock").assertDoesNotExist();assertEquals(0,claims)
        swipe(48f);ui.onNodeWithTag("map-dock").assertIsDisplayed();assertEquals(1,claims)
        ui.onNodeWithTag("dock-close").performClick()
        ui.runOnIdle { com.no5.keyboard.SpecialKeyboardGeometry.publish(ui.activity,ui.activity.packageName,android.graphics.Rect(353,720,1656,1200)) }
        ui.waitForIdle();swipe(48f);ui.onNodeWithTag("map-dock").assertDoesNotExist();assertEquals(1,claims)
        ui.runOnIdle { com.no5.keyboard.SpecialKeyboardGeometry.publish(ui.activity,ui.activity.packageName,null) }
        ui.waitForIdle();swipe(48f);ui.onNodeWithTag("map-dock").assertIsDisplayed();assertEquals(2,claims)
    }
    @Test fun physicalGeometryAndClickableRegionsUseSeparatePolicies() {
        assertEquals(25.316456f,KnobGeometry.INNER_GROWTH,.0001f);assertEquals(16.877638f,KnobGeometry.MAP_UNDERLAP,.0001f)
        assertEquals(115f,KnobGeometry.knobs[0].innerRx,0f);assertEquals(209f,KnobGeometry.knobs[1].ry,0f)
        assertTrue(KnobGeometry.blocked(49f,949f));assertTrue(KnobGeometry.blocked(1943f,1157f))
        for((x,y,w,h) in listOf(listOf(1516f,950f,120f,96f),listOf(1864f,150f,112f,384f),listOf(353f,938f,1303f,254f)))
            assertTrue(KnobGeometry.safe(x,y,x+w,y+h))
    }
}
