package com.no5.helm

import android.graphics.Bitmap
import android.graphics.Canvas
import com.no5.domain.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Actual native text pixels; no GPU/device performance assertion. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29],qualifiers="de-rDE-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SonarDepthReadoutRenderTest {
    internal class Input {
        var now=1_000_000L
        var id=0L
        val base=DeterministicMockSonarSource(now={now})
        val selection=SonarDisplaySelection(SonarPingSource.OWN,1)
        val state=MutableSourceFeed(SonarSourceStatus(base.identity,1,true,true,
            serverSessionId="depth-readout",serverGeneration=1,displaySelection=selection))
        val packets=MutableSourceFeed<SonarSourceFrame?>(null)
        val source=object:SonarSourcePort by base {
            override val status:SourceFeed<SonarSourceStatus> get()=state
            override val frames:SourceFeed<SonarSourceFrame?> get()=packets
            override val capabilities:SourceFeed<SonarSourceCapabilities> = MutableSourceFeed(SonarSourceCapabilities(base.identity,bottomDetection=true))
        }
        val controller=SonarNativeController(source){now}
        fun send(valid:Boolean) {
            now+=50;id++
            packets.emit(SonarSourceFrame(base.identity,1,SonarFrame(ShortArray(1024),id,now),
                bottom=if(valid)SonarBottom(400.0,.99)else SonarBottom(),serverSessionId="depth-readout",
                geometry=SonarGeometry(3.072,SonarTxReference.HOST_VERIFIED),displaySelection=selection,
                pingSource=SonarPingSource.OWN,amplitude=SonarAmplitude(4095,12,true)))
        }
    }
    @Test fun shortGapKeepsTheDepthValueTextIdenticalForAllSonarConsumers() {
        val h=Input();val c=h.controller
        try {
            h.send(true);val current=sonarPanelPresentation(c,DepthUnit.METERS)
            val currentUi=com.no5.helm.gpu.sonarSceneUi(c,DepthUnit.METERS,null)
            h.send(false);val held=sonarPanelPresentation(c,DepthUnit.METERS)
            assertEquals(SonarDepthState.HELD,c.depthDisplay.state)
            assertNull("Raw bottom remains missing",c.model.depth())
            assertEquals("Value text must not acquire a moving status suffix",current.depthLabel,held.depthLabel)
            assertNotEquals(current.depthStatusLabel,held.depthStatusLabel)
            assertNotEquals("Native scene must repaint changed independent status without a new buffer",currentUi,com.no5.helm.gpu.sonarSceneUi(c,DepthUnit.METERS,null))
            c.freeze();h.send(true)
            assertEquals(held.depthLabel,sonarPanelPresentation(c,DepthUnit.METERS).depthLabel)
        } finally {c.dispose()}
    }
    @Test fun topDepthDigitsKeepTheSameNativePixelsDuringAShortGapDayAndNight() {
        val h=Input();val c=h.controller
        val renderer=SonarNativeRenderer(RuntimeEnvironment.getApplication(),externalPlot=true,externalData=true)
        fun render(night:Boolean):Bitmap=Bitmap.createBitmap(2000,1200,Bitmap.Config.ARGB_8888).also{
            Canvas(it).also {canvas->renderer.drawBackdrop(canvas,night);renderer.draw(canvas,c,night)}
        }
        fun valuePixels(image:Bitmap)=IntArray(280*40).also{image.getPixels(it,0,280,1540,132,280,40)}
        try {
            h.send(true);val day=render(false);val night=render(true)
            h.send(false);val heldDay=render(false);val heldNight=render(true)
            for((label,pair) in listOf("day" to (day to heldDay),"night" to (night to heldNight))) {
                assertArrayEquals("Numeric field moves on $label gap",valuePixels(pair.first),valuePixels(pair.second))
                val out=File(System.getProperty("no5.renderOutputDir"),"quiet-depth-readouts").apply{mkdirs()}
                for((state,image) in listOf("current" to pair.first,"held" to pair.second))
                    File(out,"sonar-$label-$state.png").outputStream().use{image.compress(Bitmap.CompressFormat.PNG,100,it)}
            }
            listOf(day,night,heldDay,heldNight).forEach{it.recycle()}
        } finally {renderer.dispose();c.dispose()}
    }
}
