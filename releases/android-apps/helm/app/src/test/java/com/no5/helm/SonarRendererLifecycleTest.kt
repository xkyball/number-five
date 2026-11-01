package com.no5.helm

import android.content.ComponentCallbacks2
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import androidx.compose.runtime.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.no5.domain.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29],qualifiers="en-rUS-w1000dp-h600dp-land-mdpi",shadows=[NavigationComposeRenderTest.HostPowerManager::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SonarRendererLifecycleTest {
    @get:Rule val ui=createComposeRule()
    @Test fun memoryCallbackReleasesTheAtlasAndRemovingItsOwnerDisposesResources() {
        val now=1_000_000L;val context=RuntimeEnvironment.getApplication()
        val c=SonarNativeController(DeterministicMockSonarSource(now={now})){now}
        val r=SonarNativeRenderer(context);var shown by mutableStateOf(true)
        val image=Bitmap.createBitmap(1000,512,Bitmap.Config.ARGB_8888)
        try {
            c.model.connect("SYNTHETIC MEMORY TEST");c.model.acquisition(true)
            c.model.setDisplay(c.model.display.copy(historySeconds=10.0))
            for(i in 0..1) {
                c.model.setAnalysis(SonarGeometry(if(i==0)3.072 else 24.576,SonarTxReference.HOST_VERIFIED),SonarBottom(),null,false,amplitude=SonarAmplitude(4095,12,true))
                c.model.frame(SonarFrame(ShortArray(1024){1000},i.toLong(),now-100+i*100),pingSource=SonarPingSource.OWN)
            }
            c.refresh();c.interpolation(true)
            fun draw(){r.drawPreview(Canvas(image),c,false,RectF(0f,0f,1000f,512f))}
            ui.setContent{if(shown)SonarRendererLifecycle(r)}
            ui.runOnIdle {
                draw();assertTrue(r.temporalAllocated)
                context.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)
                assertFalse(r.temporalAllocated);draw();assertFalse("No automatic allocation retry",r.temporalAllocated)
                c.interpolation(false);draw();c.interpolation(true);draw();assertTrue(r.temporalAllocated)
                shown=false
            }
            ui.runOnIdle{assertFalse(r.temporalAllocated)}
        }finally{image.recycle();r.dispose();c.dispose()}
    }
}
