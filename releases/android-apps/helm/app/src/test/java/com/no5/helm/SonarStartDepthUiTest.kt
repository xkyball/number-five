package com.no5.helm

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.Canvas as ComposeCanvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.no5.domain.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29],qualifiers="en-rUS-w2000dp-h1200dp-land-mdpi",shadows=[NavigationComposeRenderTest.HostPowerManager::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SonarStartDepthUiTest {
    @get:Rule val ui=createComposeRule()
    private fun capture(name:String){
        ui.waitForIdle();ui.runOnUiThread{
            val view=android.view.inspector.WindowInspector.getGlobalWindowViews().last()
            val image=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888);view.draw(Canvas(image))
            val directory=File(System.getProperty("no5.renderOutputDir"),"start-depth205").apply{mkdirs()}
            File(directory,name).outputStream().use{image.compress(Bitmap.CompressFormat.PNG,100,it)};image.recycle()
        }
    }
    @Test fun rangeDialogAppliesOnlyValidDraftsKeepsItsGeometryAndUsesTheSelectedUnit(){
        val source=DeterministicMockSonarSource(now={1000000L});val c=SonarNativeController(source){1000000L}
        source.loadFixture(SonarFixture.CALIBRATED,SonarAcquisitionRequest(source.identity.sessionId,10))
        val night=mutableStateOf(false);val unit=mutableStateOf(DepthUnit.METERS)
        val renderer=SonarNativeRenderer(RuntimeEnvironment.getApplication())
        ui.setContent{MaterialTheme{Box(Modifier.size(2000.dp,1200.dp)){
            ComposeCanvas(Modifier.fillMaxSize()){drawIntoCanvas{renderer.draw(it.nativeCanvas,c,night.value,unit.value)}}
            SonarScreen(1f,night.value,c,unit.value,externalScene=true)
        }}}
        try{
            ui.runOnIdle{c.open()}
            sonarImagePage(ui)
            sonarWorkspaceTap(ui,"no5-row-sonar-range")
            val frame=ui.onNodeWithTag("sonar-sheet-range-stage").fetchSemanticsNode().boundsInRoot
            val input=ui.onNodeWithTag("sonar-start-depth-input").assertIsDisplayed()
            val inputBounds=input.fetchSemanticsNode().boundsInRoot
            assertTrue(frame.contains(inputBounds.topLeft));assertTrue(frame.contains(inputBounds.bottomRight))
            ui.onNodeWithTag("Sonar apply start depth").assertIsNotEnabled()
            input.performTextReplacement("-1")
            ui.onNodeWithTag("Sonar apply start depth").assertIsNotEnabled()
            input.performTextReplacement("0,30")
            ui.runOnIdle{assertEquals(0.0,c.snapshot.display.startDepthM,0.0)}
            ui.onNodeWithTag("Sonar apply start depth").assertIsEnabled().performClick()
            ui.runOnIdle{assertEquals(.3,c.snapshot.display.startDepthM,1e-12)}
            assertEquals(frame,ui.onNodeWithTag("sonar-sheet-range-stage").fetchSemanticsNode().boundsInRoot)
            assertEquals(inputBounds,ui.onNodeWithTag("sonar-start-depth-input").fetchSemanticsNode().boundsInRoot)
            capture("range-day.png")
            ui.runOnIdle{night.value=true}
            capture("range-night.png")
            ui.onNodeWithTag("sonar-start-depth-input").performTextReplacement("20")
            ui.onNodeWithTag("Sonar apply start depth").performClick()
            ui.runOnIdle{assertEquals(SonarViewportState.OUTSIDE_CAPTURE,c.bounds().state)}
            assertEquals(frame,ui.onNodeWithTag("sonar-sheet-range-stage").fetchSemanticsNode().boundsInRoot)
            capture("range-unavailable.png")
            ui.runOnIdle{unit.value=DepthUnit.FEET}
            ui.onNodeWithTag("sonar-start-depth-input").performTextReplacement("1")
            ui.onNodeWithTag("Sonar apply start depth").performClick()
            ui.runOnIdle{assertEquals(.3048,c.snapshot.display.startDepthM,1e-12)}
            ui.onNodeWithTag("Sonar reset start depth").performClick()
            ui.runOnIdle{assertEquals(0.0,c.snapshot.display.startDepthM,0.0);assertTrue(c.bounds().available)}
        }finally{ui.runOnIdle{c.dispose();source.close();renderer.dispose()}}
    }
}
