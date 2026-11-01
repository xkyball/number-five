package com.no5.helm

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.Canvas as ComposeCanvas
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.no5.domain.*
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29],qualifiers="en-rUS-w2000dp-h1200dp-land-mdpi",shadows=[NavigationComposeRenderTest.HostPowerManager::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SonarStatusUiTest {
    @get:Rule val ui=createComposeRule()
    private fun capture(name:String,dialog:Boolean=false) {
        ui.waitForIdle()
        ui.runOnUiThread {
            val views=android.view.inspector.WindowInspector.getGlobalWindowViews()
            val view=if(dialog)views.last() else (ui as androidx.compose.ui.test.junit4.AndroidComposeTestRule<*,*>).activity.window.decorView
            val image=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)
            view.draw(Canvas(image))
            if(name=="sonar-night.png")assertEquals("No day surface may remain behind the night status symbol",android.graphics.Color.BLACK,image.getPixel(1238,977))
            val folder=File(System.getProperty("no5.renderOutputDir"),"quiet-status203").apply{mkdirs()}
            File(folder,name).outputStream().use{image.compress(Bitmap.CompressFormat.PNG,100,it)};image.recycle()
        }
    }
    @Test fun fullViewKeepsStatusOutsidePlotAndDetailsStableAsObservationsChange(){
        var now=1_000_000L
        val raw=DeterministicMockSonarSource(now={now})
        val states=MutableSourceFeed(raw.status.value)
        val subscription=raw.status.subscribe{states.emit(it)}
        val port=object:SonarSourcePort by raw{override val status:SourceFeed<SonarSourceStatus> get()=states}
        val c=SonarNativeController(port){now};val night=mutableStateOf(false)
        raw.loadFixture(SonarFixture.CALIBRATED,SonarAcquisitionRequest(raw.identity.sessionId,100))
        val renderer=SonarNativeRenderer(org.robolectric.RuntimeEnvironment.getApplication())
        ui.setContent{MaterialTheme{Box(Modifier.size(2000.dp,1200.dp)){
            ComposeCanvas(Modifier.fillMaxSize()){drawIntoCanvas{renderer.draw(it.nativeCanvas,c,night.value)}}
            SonarScreen(1f,night.value,c,externalScene=true)
        }}}
        try {
            val button=ui.onNodeWithTag("Sonar status").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertTrue(button.top>=SonarLayout.BOTTOM);assertTrue(button.width>=96);assertTrue(button.height>=96)
            assertTrue(KnobGeometry.safe(button.left,button.top,button.right,button.bottom))
            ui.onNodeWithTag("Sonar render overload").assertDoesNotExist()
            val count=c.snapshot.ringCount;val display=c.exportPresentationState()
            for(theme in listOf(false,true)) {
                ui.runOnIdle{night.value=theme}
                capture("sonar-${if(theme)"night"else"day"}.png")
                ui.onNodeWithTag("Sonar status").performClick()
                val frame=ui.onNodeWithTag("sonar-status-details-stage").fetchSemanticsNode().boundsInRoot
                val field=ui.onNodeWithTag("Sonar fact Aktueller Zustand").fetchSemanticsNode().boundsInRoot
                repeat(8){i->ui.runOnIdle{states.emit(states.value.copy(stale=i%2==0,error=if(i%3==0)"Observed source fault"else null));now+=50;c.tick()}
                    assertEquals(frame,ui.onNodeWithTag("sonar-status-details-stage").fetchSemanticsNode().boundsInRoot)
                    assertEquals(field,ui.onNodeWithTag("Sonar fact Aktueller Zustand").fetchSemanticsNode().boundsInRoot)}
                capture("details-${if(theme)"night"else"day"}.png",true)
                ui.onNodeWithTag("Sonar fact Cursorbindung").performScrollTo()
                val cursorBounds=ui.onNodeWithTag("Sonar fact Cursorbindung").fetchSemanticsNode().boundsInRoot
                ui.runOnIdle{c.tick()}
                assertEquals(cursorBounds,ui.onNodeWithTag("Sonar fact Cursorbindung").fetchSemanticsNode().boundsInRoot)
                ui.runOnIdle{c.closeStatusDetails()}
                assertEquals(button,ui.onNodeWithTag("Sonar status").fetchSemanticsNode().boundsInRoot)
            }
            ui.runOnIdle{assertTrue(c.snapshot.ringCount>=count);val after=c.exportPresentationState();assertEquals(display.depthSettings,after.depthSettings);assertEquals(display.knobMode,after.knobMode);assertEquals(display.display.palette,after.display.palette);assertEquals(display.display.historySeconds,after.display.historySeconds,0.0)}
        }finally{ui.runOnIdle{c.dispose();subscription.close()}}
    }
    @Test fun dashboardOpensSameStatusWithoutNavigatingOrChangingSonar(){
        val now=1_000_000L;val raw=DeterministicMockSonarSource(now={now});val c=SonarNativeController(raw){now}
        val audio=AudioNativeController(Dispatchers.Unconfined,now={now},automaticTicker=false,source=MockAudioSource(now={now}))
        var page="DASHBOARD"
        raw.loadFixture(SonarFixture.CALIBRATED,SonarAcquisitionRequest(raw.identity.sessionId,100))
        ui.setContent{MaterialTheme{Box(Modifier.size(2000.dp,1200.dp)){
            DashboardScreen(1f,false,HelmState.unavailable(),WeatherSnapshot.unavailable(),DepthHistorySnapshot.empty(),audio,c,now,
                reducedMotion=true,externalSonarScene=true,onOpenPage={page=it})}}}
        try {
            val b=ui.onNodeWithTag("Sonar status").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertTrue(b.bottom<=DashboardGeometry.echogram.y);assertTrue(b.width>=96);assertTrue(b.height>=96)
            capture("dashboard-day.png")
            ui.onNodeWithTag("Sonar status").performTouchInput{click()}
            ui.onNodeWithTag("sonar-status-details-stage").assertExists()
            ui.runOnIdle{assertEquals("DASHBOARD",page);assertTrue(c.statusDetailsOpen);c.closeStatusDetails()}
        }finally{ui.runOnIdle{audio.dispose();c.dispose()}}
    }
}
