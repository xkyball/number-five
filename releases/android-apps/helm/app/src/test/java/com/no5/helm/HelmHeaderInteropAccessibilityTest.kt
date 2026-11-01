package com.no5.helm

import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.dp
import com.no5.domain.DefaultSourceSessionFactory
import com.no5.domain.SourceConfig
import com.no5.domain.SourceSettingsStore
import com.no5.header.artwork.No5HeaderMediaView
import com.no5.helm.ocean.BackgroundSettingsViewModel
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

/** Actual No5App entry point and original native views; only its source owner is an isolated fixture. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 35], qualifiers = "w1000dp-h600dp-land-xhdpi",
    shadows = [NavigationComposeRenderTest.HostPowerManager::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HelmHeaderInteropAccessibilityTest {
    @get:Rule val ui = createEmptyComposeRule()
    private lateinit var host: ActivityController<MainActivity>
    private val modelStore = androidx.lifecycle.ViewModelStore()
    private lateinit var originalLanguage: String

    @Before fun launchActualProductComposition() {
        resetAndroidViewModelApplicationFixture()
        val app = RuntimeEnvironment.getApplication()
        originalLanguage = UiLanguage.current(app)
        UiLanguage.acceptConfirmed(app, "en")
        app.getSharedPreferences("no5_demo", 0).edit().putBoolean("night", false).commit()
        val config = SourceConfig(connectionEnabled = false)
        AndroidSourceSettingsStore(app).save(config)
        val store = object : SourceSettingsStore {
            override fun load() = config
            override fun save(config: SourceConfig) = Unit
        }
        val factory = object : androidx.lifecycle.ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
                SourceSessionViewModel(app, store, DefaultSourceSessionFactory { 1_000_000L }) as T
        }
        val model = androidx.lifecycle.ViewModelProvider(modelStore, factory)[SourceSessionViewModel::class.java]
        host = Robolectric.buildActivity(MainActivity::class.java).setup().visible().windowFocusChanged(true)
        ui.mainClock.autoAdvance = false
        host.get().setContent {
            Box(Modifier.size(1000.dp, 600.dp)) { No5App(model, BackgroundSettingsViewModel(app)) }
        }
        settle()
    }

    @After fun close() {
        if (::host.isInitialized) host.pause().stop().destroy()
        modelStore.clear()
        if (::originalLanguage.isInitialized) UiLanguage.acceptConfirmed(RuntimeEnvironment.getApplication(), originalLanguage)
    }

    private fun settle() { ui.waitForIdle(); ui.mainClock.advanceTimeBy(64); ui.waitForIdle() }
    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }

    @Test fun originalMediaInteropIsExportedAndActualHeaderTouchOpensTheExistingAudioPanel() {
        val decor = host.get().window.decorView
        val media = descendants(decor).filterIsInstance<No5HeaderMediaView>().single()
        // Locate the existing interop node by its actual unchanged stage bounds; no test-only UI or tag.
        val semantics = ui.onAllNodes(SemanticsMatcher("existing native HELM media header bounds") {
            val b = it.boundsInRoot
            b.left == 282f && b.top == 4f && b.width == 1050f && b.height == 104f
        }, useUnmergedTree = true).fetchSemanticsNodes().single()
        ui.runOnIdle {
            val compose = descendants(decor).single { it.javaClass.name == "androidx.compose.ui.platform.AndroidComposeView" }
            val exported = requireNotNull(compose.accessibilityNodeProvider).createAccessibilityNodeInfo(semantics.id)
            try {
                assertNotNull("Original media AndroidView must be exported", exported)
                val bounds = android.graphics.Rect()
                exported!!.getBoundsInScreen(bounds)
                assertFalse("Later transparent controls cannot prune the media export", bounds.isEmpty)
                val native = media.createAccessibilityNodeInfo()
                try {
                    assertEquals("android.widget.Button", native.className)
                    assertFalse(native.contentDescription.isNullOrBlank())
                    assertTrue(native.isClickable)
                    val nativeBounds = android.graphics.Rect()
                    native.getBoundsInScreen(nativeBounds)
                    assertFalse("Original native media has actual screen bounds", nativeBounds.isEmpty)
                } finally { native.recycle() }
            } finally { exported?.recycle() }
        }
        val down = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(down, down + if (action == MotionEvent.ACTION_UP) 32 else 0,
                action, 500f, 50f, 0)
            try { ui.runOnIdle { assertTrue(decor.dispatchTouchEvent(event)) } } finally { event.recycle() }
        }
        settle()
        ui.onNode(hasContentDescription(No5Text.ui("AUDIO")) and
            SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)).assertIsSelected()
    }
}
