package com.no5.helm

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.PixelCopy
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.platform.app.InstrumentationRegistry
import com.no5.domain.SourceConfig
import com.no5.keyboard.SpecialKeyboardGeometry
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Actual APK composition and PixelCopy pixels on the isolated emulator; no Lumina actions. */
class FooterSelectionEmulatorTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val baseline get() = InstrumentationRegistry.getArguments().getString("footerBaseline") == "true"
    @get:Rule(order=0) val offlineSource = object : org.junit.rules.ExternalResource() {
        override fun before() {
            AndroidSourceSettingsStore(instrumentation.targetContext).save(SourceConfig(connectionEnabled=false))
        }
    }
    @get:Rule(order=1) val ui = createAndroidComposeRule<MainActivity>()
    @Before fun isolatedEmulatorOnly() {
        assertTrue(android.os.Build.FINGERPRINT.contains("generic") || android.os.Build.HARDWARE.contains("ranchu"))
        assertEquals(320,ui.activity.resources.displayMetrics.densityDpi)
    }
    private fun description(page: String) = page + if (page == "DASHBOARD") ", staged screen" else ""
    private fun settle() { ui.mainClock.advanceTimeBy(600); ui.waitForIdle(); instrumentation.waitForIdleSync() }
    private fun pixels(name: String? = null): Bitmap {
        val bitmap = Bitmap.createBitmap(2000,1200,Bitmap.Config.ARGB_8888)
        val done = CountDownLatch(1); var result = -1
        instrumentation.runOnMainSync {
            PixelCopy.request(ui.activity.window,bitmap,{ result=it; done.countDown() },Handler(Looper.getMainLooper()))
        }
        assertTrue(done.await(5,TimeUnit.SECONDS)); assertEquals(PixelCopy.SUCCESS,result)
        if (name != null) {
            val dir = File(ui.activity.getExternalFilesDir(null),"footer-selection").apply { mkdirs() }
            File(dir,"${if(baseline)"before"else"after"}-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
        }
        return bitmap
    }
    private fun singleRoute(page: String, night: Boolean, name: String? = null) {
        ui.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role,Role.Tab) and isSelected()).assertCountEquals(1)
        BottomNavigation.pages.forEach { p ->
            if(p == page) ui.onNodeWithContentDescription(description(p)).assertIsSelected()
            else ui.onNodeWithContentDescription(description(p)).assertIsNotSelected()
        }
        val bitmap = pixels(name)
        try {
            val surface = Color.parseColor(if(night)"#090000"else"#f6f8f7")
            val active = if(night)Color.rgb(184,71,18)else Color.rgb(255,91,0)
            BottomNavigation.pages.forEachIndexed { i,p ->
                val x = (BottomNavigation.cellLeft(i)+100).toInt()
                assertEquals("Only $page has an underline; checked $p",if(p==page)active else surface,bitmap.getPixel(x,1173))
                assertEquals("No retained focus/press surface on $p",surface,bitmap.getPixel(x,1160))
            }
        } finally { bitmap.recycle() }
    }
    @Test fun focusedInactiveTabCannotKeepASecondVisualHighlight() {
        for(night in listOf(false,true)) {
            ui.onNodeWithContentDescription(if(night)"Night theme"else"Day theme").performClick()
            ui.onNodeWithContentDescription("HELM").performClick(); settle()
            // Real key input enters keyboard mode; semantics activates AUDIO without transferring focus.
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_TAB)
            ui.onNodeWithContentDescription("HELM").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
            ui.onNodeWithContentDescription("HELM").assertIsFocused()
            ui.onNodeWithContentDescription("AUDIO").performClick(); settle()
            ui.onNodeWithContentDescription("HELM").assertIsNotSelected()
            ui.onNodeWithContentDescription("HELM").assertIsFocused()
            if(baseline) {
                val bitmap=pixels("focus-${if(night)"night"else"day"}")
                assertNotEquals("Reproduce Material focus shade on inactive HELM",Color.parseColor(if(night)"#090000"else"#f6f8f7"),bitmap.getPixel(716,1160))
                bitmap.recycle()
            } else singleRoute("AUDIO",night,"focus-${if(night)"night"else"day"}")
        }
    }
    @Test fun navigationReselectionQuickSwitchBackHomeAndImeKeepOneActiveRoute() {
        val owner=ui.activity.sourceSessions; val session=owner.session; val config=owner.config
        val nav=ui.onNodeWithContentDescription("HELM").fetchSemanticsNode().boundsInRoot
        for(night in listOf(false,true)) {
            ui.onNodeWithContentDescription(if(night)"Night theme"else"Day theme").performClick(); settle()
            for(page in BottomNavigation.pages) {
                ui.onNodeWithContentDescription(description(page)).performTouchInput { click() }; settle()
                singleRoute(page,night,"$page-${if(night)"night"else"day"}")
                ui.onNodeWithContentDescription(description(page)).performTouchInput { click() }; settle()
                singleRoute(page,night)
            }
            repeat(3) { BottomNavigation.pages.reversed().forEach { page ->
                ui.onNodeWithContentDescription(description(page)).performTouchInput { click() }
            } }
            settle(); singleRoute("DASHBOARD",night)
            ui.onNodeWithContentDescription("SONAR").performClick(); settle()
            repeat(4) { ui.onNodeWithContentDescription("Sonar Audio quick switch").performClick(); settle(); singleRoute("SONAR",night) }
            ui.onNodeWithContentDescription("Sonar advanced").performClick(); settle()
            instrumentation.runOnMainSync { ui.activity.onBackPressedDispatcher.onBackPressed() }; settle()
            singleRoute("SONAR",night)
            // Genuine Home -> singleTask resume on this emulator only.
            val activity=ui.activity
            assertTrue(instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME))
            ui.waitUntil(5_000) { activity.lifecycle.currentState == androidx.lifecycle.Lifecycle.State.CREATED }
            instrumentation.uiAutomation.waitForIdle(250,5_000)
            // Android 10 throttles an app's own background start just after Home. Resume as
            // an external launcher/shell caller and keep the original MAIN/LAUNCHER identity.
            val launch=instrumentation.uiAutomation.executeShellCommand(
                "am start -W -n com.no5.helm/.MainActivity -a android.intent.action.MAIN -c android.intent.category.LAUNCHER")
            android.os.ParcelFileDescriptor.AutoCloseInputStream(launch).bufferedReader().use {
                assertTrue(it.readText().contains("Status: ok"))
            }
            ui.waitUntil(5_000) { activity.lifecycle.currentState == androidx.lifecycle.Lifecycle.State.RESUMED && activity.hasWindowFocus() }
            settle(); singleRoute("SONAR",night,"resume-${if(night)"night"else"day"}")
            assertSame(owner,ui.activity.sourceSessions); assertSame(session,owner.session)
            ui.onNodeWithContentDescription("SETTINGS").performClick(); settle()
            ui.onNodeWithTag("open-gateway-connection").performClick(); settle()
            ui.onNodeWithTag("gateway-endpoint").performTextReplacement("http://unsaved.local:8080")
            instrumentation.runOnMainSync { ui.activity.onBackPressedDispatcher.onBackPressed() }; settle()
            ui.onNodeWithTag("open-gateway-connection").performClick(); settle()
            ui.onNodeWithTag("gateway-endpoint").assertTextContains(config.endpoint)
            ui.onNodeWithTag("gateway-back").performClick(); settle()
            repeat(3) { cycle ->
                instrumentation.runOnMainSync {
                    val b=WindowInsetsCompat.Builder().setSystemWindowInsets(Insets.of(0,0,0,480)).setStableInsets(Insets.NONE).build()
                    ViewCompat.dispatchApplyWindowInsets(ui.activity.window.decorView,b)
                    SpecialKeyboardGeometry.publish(ui.activity,ui.activity.packageName,Rect(353,720,1656,1200))
                }; settle()
                assertEquals(nav,ui.onNodeWithContentDescription("HELM").fetchSemanticsNode().boundsInRoot)
                assertEquals(720f,ui.onNodeWithTag("helm-form-viewport").fetchSemanticsNode().boundsInRoot.bottom,1f)
                singleRoute("SETTINGS",night,if(cycle==0)"ime-${if(night)"night"else"day"}"else null)
                instrumentation.runOnMainSync {
                    ViewCompat.dispatchApplyWindowInsets(ui.activity.window.decorView,WindowInsetsCompat.Builder().setSystemWindowInsets(Insets.NONE).setStableInsets(Insets.NONE).build())
                    SpecialKeyboardGeometry.publish(ui.activity,ui.activity.packageName,null)
                }; settle()
                assertEquals(nav,ui.onNodeWithContentDescription("HELM").fetchSemanticsNode().boundsInRoot)
                singleRoute("SETTINGS",night)
            }
        }
        assertSame(session,owner.session); assertEquals(config,owner.config)
    }
}
