package com.no5.helm

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import com.no5.domain.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Native host Canvas pixel equality; no reference images or production measurement values change. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SonarTransferNativeParityTest {
    private class OriginalTransfer:SonarTransferLut() {
        private val model=SonarModel()
        override fun fill(colors:IntArray,fullScale:Int,display:SonarDisplay,night:Boolean) {
            for(raw in colors.indices)colors[raw]=SonarNativeMath.palette(model.displayAmplitude(raw,fullScale,display),display.palette,night)
        }
    }
    private var now=1_000_000L
    private fun render(r:SonarNativeRenderer,c:SonarNativeController,night:Boolean)=Bitmap.createBitmap(1000,512,Bitmap.Config.ARGB_8888).also{r.drawPreview(Canvas(it),c,night,RectF(0f,0f,1000f,512f))}
    private fun pixels(b:Bitmap)=IntArray(b.width*b.height).also{b.getPixels(it,0,b.width,0,0,b.width,b.height)}
    @Test fun rawAndAggregatedNativeImagesMatchForPalettesThemesAndUnexpectedUint16Values() {
        var combinations=0
        for(aggregate in listOf(false,true))for(palette in SonarPalette.entries)for(night in listOf(false,true)) {
            val c=SonarNativeController(DeterministicMockSonarSource(now={now})){now}
            val expected=SonarNativeRenderer(RuntimeEnvironment.getApplication(),OriginalTransfer())
            val actual=SonarNativeRenderer(RuntimeEnvironment.getApplication())
            try {
                c.model.connect("SYNTHETIC PIXEL PARITY");c.model.acquisition(true)
                c.model.updateContext(SonarEpoch("synthetic-transfer",1,1),SonarPingSource.OWN)
                c.model.setAnalysis(SonarGeometry(3.072,SonarTxReference.HOST_VERIFIED),SonarBottom(),null,false,amplitude=SonarAmplitude(4095,12,true))
                val d=SonarDisplay(palette=palette,gainDb=if(night)-24.0 else 12.0,gamma=if(night).2 else 3.0,threshold=if(night)0.0 else .3,historySeconds=if(aggregate)600.0 else 10.0)
                c.model.setDisplay(d)
                val count=if(aggregate)2050 else 12
                for(id in 0 until count) {
                    now+=50
                    c.model.frame(SonarFrame(ShortArray(1024){bin->((bin*67+id*997)and 65535).toShort()},id.toLong(),now),pingSource=SonarPingSource.OWN)
                }
                c.refresh();c.interpolation(true)
                val before=render(expected,c,night);val after=render(actual,c,night)
                assertArrayEquals("Native ${if(aggregate)"aggregated"else"raw"} $palette night=$night",pixels(before),pixels(after))
                if(palette==SonarPalette.MONO_AMBER)for((name,b)in listOf("reference" to before,"optimized" to after)) {
                    val f=File(System.getProperty("no5.renderOutputDir"),"transfer-native/${if(aggregate)"aggregated"else"raw"}-${if(night)"negative-night"else"positive-day"}-$name.png")
                    f.parentFile!!.mkdirs();f.outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)}
                }
                before.recycle();after.recycle();combinations++
            }finally{expected.dispose();actual.dispose();c.dispose()}
        }
        assertEquals(16,combinations)
    }
}
