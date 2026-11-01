package com.no5.helm

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import com.no5.helm.ocean.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29,33,35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AndroidCompatibilityTest {
    @Test fun activityCreationAndFullscreenSetupUseCompatibleApis() {
        AndroidSourceSettingsStore(RuntimeEnvironment.getApplication()).save(
            com.no5.domain.SourceConfig(connectionEnabled=false))
        Robolectric.buildActivity(MainActivity::class.java).create().use { activity ->
            assertNotNull(activity.get().window.decorView)
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        }
    }
    @Test @Config(sdk=[29]) fun api29AnimationPreparationNeverAttemptsAgslAndKeepsImageFallback() {
        val failures=mutableListOf<String>()
        val renderer=OceanBackgroundRenderer(RuntimeEnvironment.getApplication(),failures::add,"invalid AGSL")
        repeat(3) { renderer.prepare(BackgroundMode.ANIMATION) }
        assertFalse(renderer.shaderAvailable)
        assertTrue(failures.isEmpty())
        val bitmap=Bitmap.createBitmap(1200,704,Bitmap.Config.ARGB_8888)
        renderer.draw(Canvas(bitmap),BackgroundMode.ANIMATION,false,false,OceanFrame())
        bitmap.recycle()
    }
}
