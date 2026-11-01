package com.no5.helm

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import com.no5.domain.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SonarDashboardPreviewTest {
    @Test fun previewUsesTheSameActualEchogramTransferAndDoesNotAlterAcquisition() {
        val now=1_000_000L
        val source=DeterministicMockSonarSource(now={now})
        val controller=SonarNativeController(source,now={now})
        source.loadFixture(SonarFixture.CALIBRATED,SonarAcquisitionRequest(source.identity.sessionId,100))
        val samples=controller.model.latest!!.samples.copyOf()
        val ping=controller.snapshot.pingId
        val count=controller.snapshot.ringCount
        val target=RectF(SonarLayout.PLOT_LEFT,SonarLayout.TOP,SonarLayout.plotRight(true),SonarLayout.BOTTOM)
        for(night in listOf(false,true)) {
            val full=Bitmap.createBitmap(2000,1200,Bitmap.Config.ARGB_8888)
            val preview=Bitmap.createBitmap(2000,1200,Bitmap.Config.ARGB_8888)
            val renderer=SonarNativeRenderer(RuntimeEnvironment.getApplication())
            val marker=Color.rgb(11,17,23)
            Canvas(preview).apply {drawColor(marker);renderer.drawPreview(this,controller,night,target)}
            renderer.draw(Canvas(full),controller,night)
            assertEquals(marker,preview.getPixel(10,10))
            assertEquals(marker,preview.getPixel(1900,1000))
            // Neither axis labels nor the different preview grid pass through these samples.
            for(x in listOf(167,421,743,1163,1523))for(y in listOf(227,361,607,773))
                assertEquals("Same raw echo transfer at $x,$y night=$night",full.getPixel(x,y),preview.getPixel(x,y))
            full.recycle();preview.recycle()
        }
        assertEquals(ping,controller.snapshot.pingId);assertEquals(count,controller.snapshot.ringCount)
        assertArrayEquals(samples,controller.model.latest!!.samples)
        assertEquals(controller.displayRevision,controller.presentedDisplayRevision)
        controller.dispose()
    }
}
