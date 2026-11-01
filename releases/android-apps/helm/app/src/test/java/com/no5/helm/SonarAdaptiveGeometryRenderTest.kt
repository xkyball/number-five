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
import kotlin.math.roundToInt

/** Synthetic factor-eight captures. Native host pixels are not a physical QF/GPU proof. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SonarAdaptiveGeometryRenderTest {
    private var now=1_000_000L
    private class SyntheticSource:SonarSourcePort {
        override val identity=SourceIdentity("synthetic-geometry",SourceMode.PI,"SYNTHETIC RANGE TEST")
        override val status=MutableSourceFeed(SonarSourceStatus(identity,connected=true,running=true))
        override val capabilities=MutableSourceFeed(SonarSourceCapabilities(identity,bottomDetection=true))
        override val frames=MutableSourceFeed<SonarSourceFrame?>(null)
        override fun start(request:SonarAcquisitionRequest)=SonarAcquisitionOutcome.Rejected(identity.sessionId,request.requestId,SonarRejection.UNSUPPORTED,"UNSUPPORTED")
        override fun stop(request:SonarAcquisitionRequest)=start(request)
        override fun tick()=Unit
        override fun close()=Unit
    }
    private fun controller()=SonarNativeController(SyntheticSource()){now}.also {
        it.model.connect("SYNTHETIC RANGE TEST");it.model.acquisition(true)
        it.model.setDisplay(it.model.display.copy(historySeconds=10.0))
    }
    private fun append(c:SonarNativeController,id:Long,maximum:Double,raw:ShortArray,bottom:Boolean=false) {
        c.model.setAnalysis(SonarGeometry(maximum,SonarTxReference.HOST_VERIFIED),
            if(bottom)SonarBottom(1.536/maximum*1023,.9)else SonarBottom(),null,true,
            amplitude=SonarAmplitude(4095,12,true))
        c.model.frame(SonarFrame(raw,id,now));c.refresh()
    }
    private fun render(c:SonarNativeController,night:Boolean=false)=Bitmap.createBitmap(2000,1200,Bitmap.Config.ARGB_8888).also {
        SonarNativeRenderer(RuntimeEnvironment.getApplication()).draw(Canvas(it),c,night)
    }
    private fun save(image:Bitmap,name:String) {
        val file=File(System.getProperty("no5.renderOutputDir"),"adaptive-geometry/$name.png")
        file.parentFile.mkdirs();file.outputStream().use{image.compress(Bitmap.CompressFormat.PNG,100,it)}
    }
    @Test fun shortColumnsKeepTheirPhysicalCoverageAndBottomDepthInBothThemesAndScopeLayouts() {
        for(night in listOf(false,true))for(scope in listOf(false,true)) {
            val c=controller()
            try {
                val short=ShortArray(1024){4095};append(c,1,3.072,short,true)
                now+=1000;append(c,2,24.576,ShortArray(1024),true)
                c.display{it.copy(aScope=scope,overlayBottom=true)}
                assertTrue("Fixture enables the overlay through its real controller",c.model.viewDisplay.overlayBottom)
                assertEquals(2,c.model.ring.count)
                val image=render(c,night)
                val right=SonarLayout.plotRight(scope);val width=SonarLayout.plotWidth(scope)
                val x=(right-width/10).roundToInt()
                val expected=SonarNativeMath.palette(1.0,c.model.viewDisplay.palette,night)
                assertEquals("Short capture must still paint its measured upper range",expected,image.getPixel(x,243))
                val stage=if(night)0xff030000.toInt()else 0xff061323.toInt()
                assertEquals("Below the short capture is unmeasured, not a stretched echo",stage,image.getPixel(x,500))
                val bottomY=(SonarLayout.TOP+SonarLayout.HEIGHT/16).roundToInt()
                val accent=if(night)0xffff1a0a.toInt()else 0xff005daf.toInt()
                assertTrue("Historical bottom must stay at the same physical 1.536m",(bottomY-2..bottomY+2).any{image.getPixel(x,it)==accent})
                assertArrayEquals(short,c.model.ring.atAge(1)!!.samples)
                c.model.setFreeze(true);c.refresh();val frozen=render(c,night)
                now+=1000;append(c,3,3.072,ShortArray(1024){3000},true)
                val continued=render(c,night)
                val a=IntArray((right-SonarLayout.PLOT_LEFT).toInt()*640);val b=IntArray(a.size)
                val cropWidth=(right-SonarLayout.PLOT_LEFT).toInt()
                frozen.getPixels(a,0,cropWidth,SonarLayout.PLOT_LEFT.toInt(),220,cropWidth,640)
                continued.getPixels(b,0,cropWidth,SonarLayout.PLOT_LEFT.toInt(),220,cropWidth,640)
                assertArrayEquals("Live range changes must not relabel frozen history",a,b)
                save(image,"short-on-long-${if(scope)"scope"else"wide"}-${if(night)"night"else"day"}")
                image.recycle();frozen.recycle();continued.recycle()
            } finally {c.dispose()}
        }
    }
    @Test fun longColumnsAreCroppedToCurrentDepthRatherThanCompressedIntoItsAxis() {
        val c=controller()
        try {
            append(c,1,24.576,ShortArray(1024){if(it in 32..96)4095 else 0})
            now+=1000;append(c,2,3.072,ShortArray(1024))
            val image=render(c)
            val x=(SonarLayout.plotRight(true)-SonarLayout.plotWidth(true)/10).roundToInt()
            assertEquals(SonarNativeMath.palette(1.0,c.model.viewDisplay.palette,false),image.getPixel(x,(SonarLayout.TOP+SonarLayout.HEIGHT*.55f).roundToInt()))
            assertEquals(0xff061323.toInt(),image.getPixel(x,243))
            save(image,"long-on-short-cropped");image.recycle()
        }finally{c.dispose()}
    }
    @Test fun firstAndNewestColumnIsVisibleInsideThePlotBoundary() {
        for(scope in listOf(false,true)) {
            val c=controller()
            try {
                append(c,1,3.072,ShortArray(1024){4095})
                c.model.setDisplay(c.model.display.copy(aScope=scope));c.refresh()
                val image=render(c)
                assertEquals("One ping must paint inside the plot before its separator",SonarNativeMath.palette(1.0,c.model.viewDisplay.palette,false),image.getPixel(SonarLayout.plotRight(scope).toInt()-3,500))
                save(image,"first-column-${if(scope)"scope"else"wide"}");image.recycle()
            }finally{c.dispose()}
        }
    }
}
