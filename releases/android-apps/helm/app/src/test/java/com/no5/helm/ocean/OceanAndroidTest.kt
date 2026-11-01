package com.no5.helm.ocean

import android.graphics.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[33,35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OceanAndroidTest {
    private val context get() = RuntimeEnvironment.getApplication()
    @Test fun persistedSelectionRoundTripsAllModes() {
        val store=BackgroundPreferences(context)
        for(mode in BackgroundMode.entries){assertTrue(store.save(mode));assertEquals(mode,BackgroundPreferences(context).load())}
    }
    @Test fun invalidSettingDefaultsToOwnedImage() {
        context.getSharedPreferences("no5_background",0).edit().putString("mode","invalid").commit()
        assertEquals(BackgroundMode.IMAGE,BackgroundPreferences(context).load())
    }
    @Test fun explicitSaveRepairsWrongTypedPreference() {
        context.getSharedPreferences("no5_background",0).edit().putInt("mode",42).commit()
        val store = BackgroundPreferences(context)
        assertEquals(BackgroundMode.IMAGE, store.load())
        assertTrue(store.save(BackgroundMode.NONE))
        assertEquals(BackgroundMode.NONE, BackgroundPreferences(context).load())
    }
    @Test fun failedCommitRestoresProcessCacheAndDoesNotChangeSourcePreferences() {
        val preferences = context.getSharedPreferences("background_failure_test", 0)
        preferences.edit().clear().putString("mode", "IMAGE").commit()
        val failing = object : android.content.SharedPreferences by preferences {
            override fun edit(): android.content.SharedPreferences.Editor {
                val editor = preferences.edit()
                return object : android.content.SharedPreferences.Editor by editor {
                    override fun putString(key: String?, value: String?): android.content.SharedPreferences.Editor {
                        editor.putString(key, value); return this
                    }
                    override fun remove(key: String?): android.content.SharedPreferences.Editor {
                        editor.remove(key); return this
                    }
                    override fun commit(): Boolean { editor.commit(); return false }
                }
            }
        }
        val wrapper = object : android.content.ContextWrapper(context) {
            override fun getSharedPreferences(name: String?, mode: Int) = failing
        }
        val sourcePreferences = context.getSharedPreferences("no5_source_settings", 0)
        val before = sourcePreferences.all.toMap()
        val store = BackgroundPreferences(wrapper)
        assertFalse(store.save(BackgroundMode.ANIMATION))
        assertEquals(BackgroundMode.IMAGE, store.load())
        assertEquals(before, sourcePreferences.all)
    }
    @Test fun shaderCompileFailureIsTerminalAndHasExplicitFallbackLabel() {
        val failures = mutableListOf<String>()
        val renderer = OceanBackgroundRenderer(context, failures::add, "invalid shader program")
        repeat(4) { renderer.prepare(BackgroundMode.ANIMATION) }
        assertFalse(renderer.shaderAvailable)
        assertEquals(1, failures.size)
        assertTrue(failures.single().contains("Shader setup"))
        assertTrue(failures.single().contains("error"))
        assertTrue(renderer.effectiveLabel(BackgroundMode.ANIMATION, false, OceanQuality.HIGH).contains("Shader-Fehler"))
        val fallback = Bitmap.createBitmap(2000,1200,Bitmap.Config.ARGB_8888)
        val image = Bitmap.createBitmap(2000,1200,Bitmap.Config.ARGB_8888)
        renderer.draw(Canvas(fallback),BackgroundMode.ANIMATION,false,false,OceanFrame())
        renderer.draw(Canvas(image),BackgroundMode.IMAGE,false,false,OceanFrame())
        assertTrue(fallback.sameAs(image))
        fallback.recycle(); image.recycle()
    }
    // Robolectric 4.15 API33 native Skia rejects valid AGSL layout(color); device coverage remains required.
    @Test @Config(sdk=[35]) fun agslCompilesAndUniformSetupSucceedsOnHostSkia() {
        val failures=mutableListOf<String>();val renderer=OceanBackgroundRenderer(context,failures::add)
        renderer.prepare(BackgroundMode.ANIMATION)
        assertEquals(emptyList<String>(),failures)
        assertTrue(renderer.shaderAvailable)
    }
    // Robolectric 4.15 API33 native Skia rejects valid AGSL layout(color); device coverage remains required.
    @Test @Config(sdk=[35]) fun allFloatUniformsReachNativeValidation() {
        val shader = RuntimeShader(context.assets.open("ocean/ocean.agsl").bufferedReader().use { it.readText() })
        val values = mapOf(
            "uResolution" to floatArrayOf(1200f,704f),
            "uHorizontalFovRadians" to floatArrayOf(1.35f),
            "uHorizonY" to floatArrayOf(.25f),
            "uLightDirection" to floatArrayOf(-.35f,.82f,-.45f),
            "uTimeSeconds" to floatArrayOf(1.25f),
            "uHeadingRadians" to floatArrayOf(.5f),
            "uWaveHeight" to floatArrayOf(.5f),
            "uFoamIntensity" to floatArrayOf(.15f),
            "uDetail" to floatArrayOf(1f))
        values.forEach { (name, value) -> shader.setFloatUniform(name, value) }
        // Guard against no-op shadows: an unknown uniform must fail in actual native validation.
        assertThrows(IllegalArgumentException::class.java) { shader.setFloatUniform("NO_SUCH_UNIFORM", floatArrayOf(1f)) }
        assertThrows(IllegalArgumentException::class.java) { shader.setFloatUniform("uResolution", floatArrayOf(1f)) }
        assertThrows(IllegalArgumentException::class.java) { shader.setFloatUniform("uTimeSeconds", floatArrayOf(1f,2f)) }
    }
    // Robolectric 4.15 API33 native Skia rejects valid AGSL layout(color); device coverage remains required.
    @Test @Config(sdk=[35]) fun productionFrameUniformsBindOnHardwareRecordingCanvas() {
        val failures=mutableListOf<String>()
        val renderer=OceanBackgroundRenderer(context, failures::add)
        renderer.prepare(BackgroundMode.ANIMATION)
        val node = RenderNode("No5OceanUniformProbe")
        node.setPosition(0,0,2000,1200)
        val canvas = node.beginRecording()
        assertTrue(canvas.isHardwareAccelerated)
        try {
            renderer.draw(canvas,BackgroundMode.ANIMATION,false,false,OceanFrame(timeSeconds=1.25f))
        } finally { node.endRecording() }
        assertEquals(emptyList<String>(), failures)
        assertTrue(renderer.shaderAvailable)
    }
    // Robolectric 4.15 API33 native Skia rejects valid AGSL layout(color); device coverage remains required.
    @Test @Config(sdk=[35]) fun missingFrameUniformReportsItsNameAndFallsBackOnlyOnce() {
        val failures = mutableListOf<String>()
        val program = context.assets.open("ocean/ocean.agsl").bufferedReader().use { it.readText() }
            .replace("uTimeSeconds", "uDifferentTime")
        val renderer = OceanBackgroundRenderer(context, failures::add, program)
        renderer.prepare(BackgroundMode.ANIMATION)
        assertTrue(renderer.shaderAvailable)
        val node = RenderNode("No5OceanFailureProbe")
        node.setPosition(0,0,2000,1200)
        val canvas = node.beginRecording()
        try {
            repeat(3) { renderer.draw(canvas,BackgroundMode.ANIMATION,false,false,OceanFrame(timeSeconds=1f)) }
        } finally { node.endRecording() }
        assertFalse(renderer.shaderAvailable)
        assertEquals(1, failures.size)
        assertTrue(failures.single().contains("Shader draw"))
        assertTrue(failures.single().contains("uTimeSeconds"))
    }
    @Test fun nightAndNoneDrawNothingFallbackHasFade() {
        val renderer=OceanBackgroundRenderer(context);renderer.prepare(BackgroundMode.IMAGE)
        val bitmap=Bitmap.createBitmap(2000,1200,Bitmap.Config.ARGB_8888);val canvas=Canvas(bitmap)
        renderer.draw(canvas,BackgroundMode.IMAGE,true,false,OceanFrame())
        assertEquals(0,bitmap.getPixel(1000,300))
        renderer.draw(canvas,BackgroundMode.NONE,false,false,OceanFrame())
        assertEquals(0,bitmap.getPixel(1000,300))
        renderer.draw(canvas,BackgroundMode.IMAGE,false,false,OceanFrame())
        assertEquals(255,Color.alpha(bitmap.getPixel(1000,300)))
        assertTrue(Color.alpha(bitmap.getPixel(401,300))<10)
        assertEquals(0,bitmap.getPixel(1000,800));bitmap.recycle()
    }
    // Robolectric 4.15 API33 native Skia rejects valid AGSL layout(color); device coverage remains required.
    @Test @Config(sdk=[35]) fun softwareCanvasUsesImageEvenWhenShaderIsAvailable() {
        val renderer=OceanBackgroundRenderer(context)
        renderer.prepare(BackgroundMode.ANIMATION)
        assertTrue(renderer.shaderAvailable)
        val actual=Bitmap.createBitmap(2000,1200,Bitmap.Config.ARGB_8888)
        val expected=Bitmap.createBitmap(2000,1200,Bitmap.Config.ARGB_8888)
        renderer.draw(Canvas(actual),BackgroundMode.ANIMATION,false,false,OceanFrame())
        renderer.draw(Canvas(expected),BackgroundMode.IMAGE,false,false,OceanFrame())
        assertTrue("Unsupported software shader path must select owned image",actual.sameAs(expected))
        actual.recycle();expected.recycle()
    }
}
