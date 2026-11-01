package com.no5.home

import android.content.Intent
import android.content.pm.ResolveInfo
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.RectF
import android.os.Looper
import android.os.SystemClock
import org.robolectric.Shadows.shadowOf
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import com.no5.navigation.AppNavigation
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 35], qualifiers = "en-rUS-w1000dp-h600dp-land-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeRenderTest {
    private fun all(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { all(view.getChildAt(it)) } else emptyList()
    private fun layout(view: View, height: Int) {
        view.measure(View.MeasureSpec.makeMeasureSpec(2000, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, 2000, height)
    }
    @Test fun nativeLayoutsKeepDayNightGeometryAndVisibleTouchTargets() {
        val dir = File(System.getProperty("no5.renderOutputDir")).apply { mkdirs() }
        assertEquals(320, RuntimeEnvironment.getApplication().resources.displayMetrics.densityDpi)
        for (height in listOf(1200, 928)) {
            var dayBounds: List<Rect>? = null
            for (night in listOf(false, true)) {
                Robolectric.buildActivity(HomeActivity::class.java,
                    Intent().putExtra(AppNavigation.NIGHT, night)).setup().visible().windowFocusChanged(true).use { host ->
                    val view = host.get().findViewById<ViewGroup>(android.R.id.content)
                    host.get().updateGatewayNotice("Gateway · TLS und Status bestätigt", true)
                    layout(view, height)
                    val buttons = all(view).filterIsInstance<Button>().filter { it.isShown }
                    assertEquals(5, buttons.size)
                    assertEquals(View.GONE, all(view).first { it.tag == "home-gateway-notice" }.visibility)
                    val bounds = buttons.map { button ->
                        val visible = Rect()
                        assertTrue(button.getGlobalVisibleRect(visible))
                        val scale=1f
                        assertTrue("Ancestor clips transformed width: ${button.text}",kotlin.math.abs(visible.width()-button.width*scale)<=1.1f)
                        assertTrue("Ancestor clips transformed height: ${button.text}",kotlin.math.abs(visible.height()-button.height*scale)<=1.1f)
                        val origin=IntArray(2);view.getLocationOnScreen(origin)
                        visible.apply {offset(-origin[0],-origin[1])}
                    }
                    bounds.forEach { rect ->
                        assertTrue("Touch target below 48dp: $rect", rect.width() >= 96 && rect.height() >= 96)
                        assertTrue("Clipped target: $rect", Rect(0, 0, 2000, height).contains(rect))
                    }
                    if(height==1200)bounds.forEach {rect ->
                        assertFalse("Button intersects the physical 5-mm template: $rect",HomeStage.intersectsSafety(RectF(rect)))
                    }
                    if(height==1200)all(view).filterIsInstance<TextView>().filter {it.text.isNotEmpty() && it.isShown}.forEach {label ->
                        val rect=Rect();assertTrue(label.getGlobalVisibleRect(rect))
                        val origin=IntArray(2);view.getLocationOnScreen(origin);rect.offset(-origin[0],-origin[1])
                        assertFalse("Important text intersects knob template: ${label.text}",HomeStage.intersectsSafety(RectF(rect)))
                    }
                    bounds.forEachIndexed { i, a -> bounds.drop(i + 1).forEach { b -> assertFalse(Rect.intersects(a, b)) } }
                    if (!night) dayBounds = bounds else assertEquals(dayBounds, bounds)
                    val bitmap = Bitmap.createBitmap(2000, height, Bitmap.Config.ARGB_8888)
                    view.draw(Canvas(bitmap))
                    if (height == 1200) {
                        assertEquals(25.316456f, HomeStage.INNER_FILL_EXPANSION_PX, .001f)
                        assertEquals(Color.BLACK, bitmap.getPixel(180, 1157))
                        assertEquals(Color.BLACK, bitmap.getPixel(1815, 1157))
                        assertEquals(43f, HomeStage.CLEARANCE_PX, 0f)
                    }
                    File(dir, "home-photo-api${RuntimeEnvironment.getApiLevel()}-${if(night) "night" else "day"}-2000x$height.png")
                        .outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    if (!night && height == 1200) {
                        val skyColors = HashSet<Int>()
                        for (y in 4 until 108 step 3) for (x in 800 until 1200 step 3) skyColors += bitmap.getPixel(x, y)
                        assertTrue("HOME must paint the original cloud crop", skyColors.size > 100)
                    }
                    if (night) {
                        var colored = 0
                        for (y in 0 until height step 3) for (x in 0 until 2000 step 3) {
                            val color = bitmap.getPixel(x,y)
                            assertTrue("Night contains non-red surface", Color.green(color) <= 4 && Color.blue(color) <= 4)
                            if (Color.red(color) > 30) colored++
                        }
                        assertTrue(colored > 500)
                    }
                    bitmap.recycle()
                    // Dispatch through the actual hierarchy at the measured center, not performClick.
                    val mapIndex = buttons.indexOfFirst { it.text.startsWith("No5 MAP") }
                    val target = bounds[mapIndex]
                    for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                        val event = MotionEvent.obtain(SystemClock.uptimeMillis(), SystemClock.uptimeMillis(), action, target.exactCenterX(), target.exactCenterY(), 0)
                        assertTrue(view.dispatchTouchEvent(event)); event.recycle()
                    }
                    shadowOf(Looper.getMainLooper()).idle()
                    assertTrue(all(view).filterIsInstance<TextView>().any { it.text.startsWith("App nicht installiert") })
                }
            }
        }
    }
    @Test fun secondaryAppsRenderAndBackRetainsTheme() {
        val app = RuntimeEnvironment.getApplication()
        val candidates = listOf("com.no5.knobdiagnostic" to "Knob Diagnose", "com.no5.knobmeasurement" to "Knob Messung")
            .map { (pkg, title) -> ResolveInfo().apply {
                nonLocalizedLabel = title
                activityInfo = ActivityInfo().apply {
                    packageName = pkg; name = "$pkg.MainActivity"; enabled = true; exported = true
                    applicationInfo = ApplicationInfo().apply { packageName = pkg; enabled = true }
                }
            } }
        shadowOf(app.packageManager).addResolveInfoForIntent(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), candidates)
        for (night in listOf(false, true)) {
            Robolectric.buildActivity(HomeActivity::class.java,
                Intent().putExtra(AppNavigation.NIGHT, night)).setup().visible().windowFocusChanged(true).use { host ->
                all(host.get().window.decorView).filterIsInstance<Button>().first { it.text == "Alle Apps" }.performClick()
                val view = host.get().findViewById<ViewGroup>(android.R.id.content)
                layout(view, 1200)
                val buttons = all(view).filterIsInstance<Button>()
                val targets = all(view).filter {it.tag?.toString()?.startsWith("no5-row-home-app-")==true} + buttons.filter { it.text == "Zurück" }
                assertEquals(3, targets.size)
                for (button in targets) {
                    val visible = Rect()
                    assertTrue(button.getLocalVisibleRect(visible))
                    assertEquals(Rect(0, 0, button.width, button.height), visible)
                    assertTrue(button.height >= 96)
                }
                val bitmap = Bitmap.createBitmap(2000, 1200, Bitmap.Config.ARGB_8888)
                view.draw(Canvas(bitmap))
                File(File(System.getProperty("no5.renderOutputDir")).apply{mkdirs()},
                    "home-apps-list-fixture-api${RuntimeEnvironment.getApiLevel()}-${if(night) "night" else "day"}.png")
                    .outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
                buttons.first { it.text == "Zurück" }.performClick()
                val header = all(host.get().window.decorView)
                assertEquals(!night, header.single { it.tag == "header-day" }.isSelected)
                assertEquals(night, header.single { it.tag == "header-night" }.isSelected)
            }
        }
    }

}
