package com.no5.helm

import android.graphics.*
import com.no5.domain.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.roundToInt

/** Host Canvas pixels and shared GPU requests; physical GPU presentation is checked separately. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SonarStartDepthRenderTest {
    @Test fun croppedEchoesAndCursorKeepPhysicalDepthInBothPanelSizesAndThroughFreeze() {
        val source=DeterministicMockSonarSource(now={1000000L})
        val c=SonarNativeController(source){1000000L}
        val renderer=SonarNativeRenderer(RuntimeEnvironment.getApplication())
        fun append(id:Long,maximum:Double,raw:ShortArray) {
            c.model.setAnalysis(SonarGeometry(maximum,SonarTxReference.HOST_VERIFIED),SonarBottom(),null,true,
                amplitude=SonarAmplitude(4095,12,true))
            c.model.frame(SonarFrame(raw,id,1000000L+id*1000));c.refresh()
        }
        fun pixel(start:Double,y:Float,preview:Boolean):Int {
            c.startDepth(start)
            val image=Bitmap.createBitmap(2000,1200,Bitmap.Config.ARGB_8888)
            try {
                if(preview)renderer.drawPreview(Canvas(image),c,false,RectF(0f,0f,1000f,600f))
                else renderer.draw(Canvas(image),c,false)
                val x=if(preview)997 else SonarLayout.plotRight(c.model.viewDisplay.aScope).toInt()-3
                val yy=if(preview)y*600 else SonarLayout.TOP+y*SonarLayout.HEIGHT
                return image.getPixel(x,yy.roundToInt())
            }finally{image.recycle()}
        }
        try {
            c.model.connect("Start depth render fixture");c.model.acquisition(true)
            c.display{it.copy(gainDb=0.0,gamma=1.0,threshold=0.0,historySeconds=10.0)}
            append(1,30.69,ShortArray(1024){if(it in 0..5 || it in 720..780)4095 else 0})
            val full=SonarNativeMath.palette(1.0,c.model.viewDisplay.palette,false)
            val empty=0xff061323.toInt()
            for(preview in listOf(false,true)) {
                assertEquals("Surface echo exists before cropping",full,pixel(0.0,.003f,preview))
                assertEquals("No surface echo is substituted below the chosen start",empty,pixel(.3,.003f,preview))
                val deepFraction=((22.5-20.0)/(30.69-20.0)).toFloat()
                assertEquals("22.5m echo keeps its depth after cropping",full,pixel(20.0,deepFraction,preview))
                assertEquals("Out-of-capture range has no echoes",empty,pixel(40.0,.37f,preview))
            }
            c.startDepth(20.0);c.inspect(1f,((22.5-20)/(30.69-20)).toFloat())
            assertEquals(22.5,c.cursor!!.depthM!!,.031)
            c.freeze();val heldPing=c.model.viewRing.pingIdAt(0)
            append(2,3.069,ShortArray(1024){4095})
            c.startDepth(.3);assertEquals(heldPing,c.model.viewRing.pingIdAt(0))
            assertEquals(.3,c.bounds().start*c.model.viewMetersPerBin!!,1e-9)
            c.freeze();assertEquals(.3,c.snapshot.display.startDepthM,0.0)
            assertEquals(.3,c.bounds().start*c.model.viewMetersPerBin!!,1e-9)
            c.startDepth(20.0);assertEquals(SonarViewportState.OUTSIDE_CAPTURE,c.bounds().state)
            append(3,30.69,ShortArray(1024))
            assertEquals(20.0,c.snapshot.display.startDepthM,0.0);assertTrue(c.bounds().available)
            assertEquals(20.0,c.bounds().start*c.model.viewMetersPerBin!!,1e-9)
        }finally{renderer.dispose();c.dispose();source.close()}
    }
}
