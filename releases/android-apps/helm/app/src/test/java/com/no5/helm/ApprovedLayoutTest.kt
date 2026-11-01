package com.no5.helm

import android.graphics.*
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.no5.domain.*
import kotlinx.coroutines.Dispatchers
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29,35],qualifiers="en-rUS-w2000dp-h1200dp-land-mdpi",shadows=[NavigationComposeRenderTest.HostPowerManager::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ApprovedLayoutTest {
    @get:Rule val ui=createComposeRule()
    private var now=1_000_000L
    private val source=DeterministicMockSonarSource(now={now})
    private val sonar=SonarNativeController(source,now={now})
    private val audio=AudioNativeController(Dispatchers.Unconfined,now={now},automaticTicker=false,automaticVerification=false,source=MockAudioSource(now={now}))
    @After fun close(){sonar.dispose();audio.dispose()}
    @Test fun opticalGrowthNeverChangesPhysicalOrInteractiveGeometryAndAllAudioFooterControlsAreClear() {
        assertEquals(25.316456f,KnobGeometry.INNER_GROWTH,.0001f)
        assertEquals(16.877638f,KnobGeometry.MAP_UNDERLAP,.0001f)
        assertEquals(205f,KnobGeometry.knobs[0].rx,0f);assertEquals(208f,KnobGeometry.knobs[1].rx,0f)
        BottomNavigation.pages.indices.forEach { i->assertTrue(KnobGeometry.safe(BottomNavigation.cellLeft(i),1070f,BottomNavigation.cellLeft(i)+245f,1184f)) }
        for((x,y,w,h) in listOf(listOf(353f,814f,647f,96f),listOf(353f,854f,1303f,96f),listOf(344f,250f,1312f,680f)))
            assertTrue(KnobGeometry.safe(x,y,x+w,y+h))
        val g=AudioNativeGeometry
        (g.transport+(0..2).map{g.zoneMute(it)}+listOf(g.seek,g.masterSlider,g.masterDown,g.masterUp,g.masterMute,g.settings)).forEach {
            assertTrue("Control over physical knob: $it",KnobGeometry.safe(it.x,it.y,it.right,it.bottom))
            assertTrue("Approved touch minimum: $it",it.width>=96 && it.height>=96)
        }
        for(viewport in listOf(DesignViewport(2000f,1200f),DesignViewport(1880f,1000f),DesignViewport(1000f,600f))) {
            val p=viewport.toScreen(616.4f,1070f);val d=viewport.toDesign(p.first,p.second)
            assertEquals(616.4f,d.first,.001f);assertEquals(1070f,d.second,.001f)
            assertTrue(viewport.top>=0);assertTrue(viewport.left>=0)
        }
    }
    @Test fun nativeModeToggleDirectlySelectsSonarOrAudioWithoutChangingFrozenAcquisition() {
        source.loadFixture(SonarFixture.CALIBRATED,SonarAcquisitionRequest(source.identity.sessionId,100))
        sonar.freeze();sonar.gain(8.0);sonar.range(10.0);sonar.display{it.copy(aScope=false,zoom=true)}
        val held=sonar.model.viewRing.atAge(0)!!.pingId;val display=sonar.snapshot.display
        assertEquals(SonarKnobMode.SONAR,sonar.knobMode)
        ui.setContent { Box(Modifier.size(2000.dp,1200.dp)){SonarScreen(1f,false,sonar,DepthUnit.METERS,audio)} }
        ui.onNodeWithTag("Sonar knob mode").assertIsNotSelected().performClick()
        ui.onNodeWithTag("Sonar knob mode").assertIsSelected().assertIsDisplayed()
        ui.onNodeWithTag("Sonar audio play pause").assertDoesNotExist()
        ui.onNodeWithTag("Close sonar audio controls").assertDoesNotExist()
        ui.onNodeWithTag("Sonar tab DISPLAY").assertDoesNotExist()
        ui.runOnIdle {
            assertEquals(SonarKnobMode.AUDIO,sonar.knobMode);assertEquals(display,sonar.snapshot.display)
            assertNull(sonar.drawer)
            repeat(30){now+=100;sonar.tick()}
            assertTrue(sonar.running);assertTrue(sonar.model.latest!!.pingId>held)
            assertEquals(held,sonar.model.viewRing.atAge(0)!!.pingId)
        }
        ui.onNodeWithTag("Sonar knob mode").performClick().assertIsNotSelected()
        ui.runOnIdle { assertEquals(SonarKnobMode.SONAR,sonar.knobMode);assertEquals(display,sonar.snapshot.display);assertNull(sonar.drawer) }
        assertEquals(2000f,SonarLayout.RIGHT,0f)
    }
    @Test fun exportNativeApprovedLayoutsAndVerifyExpandedBlackPixelsInBothThemes() {
        source.loadFixture(SonarFixture.CALIBRATED,SonarAcquisitionRequest(source.identity.sessionId,100))
        audio.loadFixture(AudioFixture.DEMO);audio.startDemo()
        val context=RuntimeEnvironment.getApplication();val output=File(System.getProperty("no5.renderOutputDir"),"approved-023-api${android.os.Build.VERSION.SDK_INT}").apply{mkdirs()}
        val fixture=HelmState(speed=29.0/1.852,depth=12.9,heading=36.0,quality=TelemetryQuality(TelemetryStatus.LIVE,source="NATIVE_TEST",demo=true))
        val units=DisplayUnits(speed=SpeedUnit.KMH)
        for(night in listOf(false,true))for(page in listOf("HELM","AUDIO","SONAR","SONAR-WIDE","SONAR-AUDIO","SONAR-WIDE-AUDIO")) {
            val image=Bitmap.createBitmap(2000,1200,Bitmap.Config.ARGB_8888);val c=Canvas(image);c.drawColor(if(night)Color.BLACK else Color.rgb(246,249,250))
            val scene=HelmScene(context,night)
            when {
                page=="HELM"->scene.draw(c,renderValues(fixture,now,units=units),fixture,units=units)
                page=="AUDIO"->{AudioNativeScene(context,night).draw(c,audio);scene.drawChrome(c,page,renderValues(fixture,now),audio.statusLabel)}
                else->{sonar.display{it.copy(aScope=!page.contains("WIDE"))};if((sonar.knobMode==SonarKnobMode.AUDIO)!=page.endsWith("AUDIO"))sonar.toggleKnobMode()
                    SonarNativeRenderer(context).draw(c,sonar,night);scene.drawChrome(c,"SONAR",renderValues(fixture,now),sonar.statusText)}
            }
            KnobOverlay(context).draw(c,night,audio,if(page.startsWith("SONAR"))sonar else null)
            KnobGeometry.knobs.forEach { k ->
                val x=(k.cx+if(k.cx<1000)k.innerRx+17f else -k.innerRx-17f).toInt()
                assertEquals("Expanded optical fill must be black",Color.rgb(3,3,3),image.getPixel(x,1117))
            }
            File(output,"${page.lowercase()}-${if(night)"night"else"day"}.png").outputStream().use{image.compress(Bitmap.CompressFormat.PNG,100,it)};image.recycle()
        }
    }
}
