package com.no5.preparation.ui

import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.viewinterop.AndroidView
import com.no5.header.artwork.No5HeaderControlsView
import com.no5.header.artwork.No5HeaderMediaView
import com.no5.ui.No5Text
import com.no5.ui.UiLanguage
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Actual native MAP header slots; Android provider checks the pruned, exported tree. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 35], qualifiers = "w1000dp-h600dp-land-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HeaderInteropAccessibilityTest {
    @get:Rule val ui = createEmptyComposeRule()
    private lateinit var host: ActivityController<ComponentActivity>
    private lateinit var composeView: View
    private lateinit var media: No5HeaderMediaView
    private lateinit var controls: No5HeaderControlsView
    private var homeActions = 0
    private var audioActions = 0
    private var infoActions = 0
    private val themeActions = mutableListOf<Boolean>()
    private lateinit var originalLanguage: String

    @Before fun launchVisibleHeaderHost() {
        originalLanguage = UiLanguage.current(RuntimeEnvironment.getApplication())
        host = Robolectric.buildActivity(ComponentActivity::class.java)
        host.get().setTheme(android.R.style.Theme_Material_NoActionBar)
        host.setup().visible().windowFocusChanged(true)
    }
    @After fun close() {
        host.pause().stop().destroy()
        UiLanguage.acceptConfirmed(RuntimeEnvironment.getApplication(), originalLanguage)
    }

    private fun install(night: Boolean, epoch: Int = 0) {
        host.get().setContent {
            composeView = LocalView.current
            No5ControlTheme(night) {
                key(epoch) {
                    Box(Modifier.fillMaxSize()) {
                        No5Header(ShellState(redNight = night, sourceLabel = "SYNTHETIC HEADER SOURCE",
                            sourceStatus = DataStatus(Availability.READY, "Synthetic source status")),
                            { if (it == ShellEvent.OpenHome) homeActions++ else if (it is ShellEvent.SetNight) themeActions += it.enabled },
                            mediaHeaderSlot = { modifier, redNight ->
                                AndroidView(factory = { No5HeaderMediaView(it).also { view ->
                                    media = view; view.onOpenAudio = { audioActions++ }
                                } }, modifier = modifier, update = { it.render("Synthetic title", "Synthetic artist",
                                    "Synthetic media action", redNight, active = true, reducedMotion = true) })
                            }, headerControlsSlot = { modifier, redNight, chooseNight ->
                                AndroidView(factory = { No5HeaderControlsView(it).also { view -> controls = view } },
                                    modifier = modifier, update = { it.render("MAP · TEST", redNight, true,
                                        chooseNight, { infoActions++ }) })
                            })
                    }
                }
            }
        }
        ui.waitForIdle()
        host.windowFocusChanged(true)
        ui.waitForIdle()
    }

    private fun assertExported(tag: String, description: String? = null, clickable: Boolean = false) {
        // Compose's test tree still contains fully covered siblings. Only the Android
        // provider below proves that actual accessibility clients can reach the target.
        val semantics = ui.onNodeWithTag(tag).fetchSemanticsNode()
        ui.runOnIdle {
            val provider = checkNotNull(composeView.accessibilityNodeProvider)
            val node = provider.createAccessibilityNodeInfo(semantics.id)
            try {
                assertNotNull("Android accessibility must export $tag", node)
                if (description != null) assertEquals(description, node?.contentDescription?.toString())
                if (clickable) assertTrue("Android accessibility retains $tag action", node?.isClickable == true)
                val bounds = android.graphics.Rect()
                node?.getBoundsInScreen(bounds)
                assertFalse("Exported target keeps nonempty real bounds: $tag", bounds.isEmpty)
            } finally { node?.recycle() }
        }
    }

    @Test fun nativeControlSlotCannotPruneHomeMediaOrSourceAcrossLanguagesAndNight() {
        var epoch = 0
        for (language in listOf("de", "en", "af")) for (night in listOf(false, true)) {
            UiLanguage.acceptConfirmed(host.get(), language)
            install(night, epoch++)
            assertExported("header-brand", No5Text.text(host.get(), "No5 HOME öffnen"), clickable = true)
            assertExported("header-media")
            assertExported("source-mode", "SYNTHETIC HEADER SOURCE · Synthetic source status")
            ui.runOnIdle {
                assertEquals("Synthetic media action", media.createAccessibilityNodeInfo().contentDescription.toString())
                assertTrue(media.isClickable)
                assertEquals(2000, controls.width)
                assertEquals(130, controls.height)
            }
        }
    }

    private fun stageTap(x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(now, now + if (action == MotionEvent.ACTION_UP) 32 else 0, action, x, y, 0)
            try { ui.runOnIdle { assertTrue(host.get().window.decorView.dispatchTouchEvent(event)) } }
            finally { event.recycle() }
        }
        ui.waitForIdle()
    }

    @Test fun actualStageTapsKeepHomeMediaAndNativeRightControlsIndependent() {
        install(night = false)
        stageTap(143f, 50f)
        assertEquals(1, homeActions); assertEquals(0, audioActions)
        stageTap(500f, 50f)
        assertEquals(1, homeActions); assertEquals(1, audioActions)
        stageTap(1686f, 50f)
        assertEquals(1, infoActions)
        stageTap(1907f, 50f)
        stageTap(1796f, 50f)
        assertEquals(listOf(true, false), themeActions)
        assertEquals(1, homeActions); assertEquals(1, audioActions); assertEquals(1, infoActions)
    }
}
