package com.no5.helm

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.no5.domain.SourceConfig
import com.no5.helm.ocean.BackgroundSettingsViewModel
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29,35],qualifiers="w1000dp-h600dp-land-xhdpi",shadows=[NavigationComposeRenderTest.HostPowerManager::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FooterSelectionRegressionTest {
    @get:Rule val ui=createComposeRule()
    private var fixtureLanguage: String? = null
    @Before fun englishFixture() {
        val app = RuntimeEnvironment.getApplication()
        fixtureLanguage = com.no5.ui.UiLanguage.current(app)
        com.no5.ui.UiLanguage.acceptConfirmed(app, "en")
    }
    @After fun restoreFixtureLanguage() {
        fixtureLanguage?.let { com.no5.ui.UiLanguage.acceptConfirmed(RuntimeEnvironment.getApplication(), it) }
    }

    private fun displayOfflineApp(): android.view.View {
        val app=RuntimeEnvironment.getApplication()
        AndroidSourceSettingsStore(app).save(SourceConfig(connectionEnabled=false))
        var view:android.view.View?=null
        ui.setContent { view=LocalView.current
            Box(Modifier.fillMaxSize()){No5App(remember{BackgroundSettingsViewModel(app)})}}
        ui.waitForIdle()
        return checkNotNull(view)
    }

    private fun assertAndroidAccessibilityExports(view: android.view.View, description: String) {
        val id=ui.onNodeWithContentDescription(description).fetchSemanticsNode().id
        ui.runOnIdle {
            // Compose test matchers see the unpruned tree. Ask the Android provider too,
            // so a later full-screen semantics sibling cannot hide this virtual node.
            val provider=checkNotNull(view.accessibilityNodeProvider)
            val node=provider.createAccessibilityNodeInfo(id)
            try {
                assertNotNull("Android accessibility must export $description",node)
                assertEquals(description,node?.contentDescription?.toString())
                assertTrue("Android accessibility must retain the action for $description",node?.isClickable==true)
            } finally { node?.recycle() }
        }
    }

    @Test fun footerBoundsPreserveTabRegionsAndExposeBodyThroughAndroidAccessibility() {
        val view=displayOfflineApp()
        val group=ui.onNode(SemanticsMatcher.expectValue(SemanticsProperties.SelectableGroup,Unit),useUnmergedTree=true)
            .fetchSemanticsNode().boundsInRoot
        assertEquals(BottomNavigation.left,group.left,.51f)
        assertEquals(BottomNavigation.top,group.top,.51f)
        assertEquals(BottomNavigation.cellWidth*BottomNavigation.pages.size,group.width,.51f)
        assertEquals(BottomNavigation.height,group.height,.51f)
        val app=RuntimeEnvironment.getApplication()
        BottomNavigation.pages.forEachIndexed { index,page ->
            val bounds=ui.onNodeWithContentDescription(com.no5.ui.No5Text.text(app,page))
                .fetchSemanticsNode().boundsInRoot
            assertEquals(BottomNavigation.cellLeft(index),bounds.left,.51f)
            assertEquals(BottomNavigation.top,bounds.top,.51f)
            assertEquals(BottomNavigation.hitWidth,bounds.width,.51f)
            assertEquals(BottomNavigation.height,bounds.height,.51f)
        }
        ui.onNodeWithContentDescription("AUDIO").performTouchInput { click(center) }
        ui.waitForIdle()
        ui.runOnIdle {
            assertNativeHeaderAccessibility(view, "installed-app-version",
                com.no5.releaseinfo.ReleaseInfo.shortBuildLabel(app, "HELM"), clickable = false)
            assertNativeHeaderAccessibility(view, "header-day", com.no5.ui.No5Text.text("en", "Tagmodus"), clickable = true)
            assertNativeHeaderAccessibility(view, "header-night", com.no5.ui.No5Text.text("en", "Nachtmodus"), clickable = true)
            val info = nativeHeaderTarget(view, "header-network-info")
            assertTrue(info.contentDescription.toString().startsWith(com.no5.ui.No5Text.text("en", "Version und WLAN-Info")))
            assertNativeHeaderAccessibility(view, "header-network-info", info.contentDescription.toString(), clickable = true)
        }
        assertAndroidAccessibilityExports(view, "Audio setup")
    }

    @Test fun audioSoundSetupOpensFromRawStageTouchWithTheHostFooterPresent() {
        displayOfflineApp()
        ui.onNodeWithContentDescription("AUDIO").performTouchInput { click(center) }
        ui.onNodeWithContentDescription("Audio setup").assertIsEnabled()
        // Physical design-stage coordinates from the failed target-device tap. This
        // must exercise pointer hit testing, rather than invoke the semantics action.
        ui.onNodeWithTag("helm-physical-stage").performTouchInput { click(Offset(960f,980f)) }
        ui.onNodeWithTag("audio-settings-menu").assertExists()
    }

    @Test fun inactiveTabDoesNotKeepAnIndependentFocusHighlightAfterRouteChange(){
        val app=RuntimeEnvironment.getApplication()
        AndroidSourceSettingsStore(app).save(SourceConfig(connectionEnabled=false))
        var view:android.view.View?=null
        var input:androidx.compose.ui.input.InputModeManager?=null
        ui.setContent {view=LocalView.current;input=LocalInputModeManager.current
            Box(Modifier.fillMaxSize()){No5App(remember{BackgroundSettingsViewModel(app)})}}
        ui.waitForIdle()
        ui.runOnIdle{input!!.requestInputMode(InputMode.Keyboard)}
        ui.onNodeWithContentDescription("HELM").performSemanticsAction(SemanticsActions.RequestFocus){it()}
        ui.onNodeWithContentDescription("HELM").assertIsFocused()
        ui.onNodeWithContentDescription("AUDIO").performClick()
        ui.mainClock.advanceTimeBy(500);ui.waitForIdle()
        ui.onNodeWithContentDescription("HELM").assertIsNotSelected()
        ui.onNodeWithContentDescription("AUDIO").assertIsSelected()
        val bitmap=Bitmap.createBitmap(2000,1200,Bitmap.Config.ARGB_8888)
        ui.runOnIdle{view!!.draw(Canvas(bitmap))}
        val dir=File(System.getProperty("no5.renderOutputDir"),"footer-selection").apply{mkdirs()}
        File(dir,"focus-after-api${android.os.Build.VERSION.SDK_INT}.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
        val inactive=bitmap.getPixel((BottomNavigation.cellLeft(1)+100).toInt(),1160)
        bitmap.recycle()
        assertEquals("Inactive HELM must not keep a second visual selection from focus",Color.parseColor("#f6f8f7"),inactive)
    }
}
