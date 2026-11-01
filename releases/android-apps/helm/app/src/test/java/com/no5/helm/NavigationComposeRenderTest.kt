package com.no5.helm

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.no5.domain.SourceConfig
import com.no5.helm.ocean.BackgroundSettingsViewModel
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Real production Compose composition on host Skia. No device GPU/touch claim. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29,33,35], qualifiers="en-rUS-w2000dp-h1200dp-land-mdpi", shadows=[NavigationComposeRenderTest.HostPowerManager::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NavigationComposeRenderTest {
    /** Host has no thermal Binder service; only listener registration is stubbed.
     * This test never certifies heat handling or starts a hardware GL surface. */
    @org.robolectric.annotation.Implements(android.os.PowerManager::class)
    class HostPowerManager : org.robolectric.shadows.ShadowPowerManager() {
        @org.robolectric.annotation.Implementation
        fun addThermalStatusListener(executor: java.util.concurrent.Executor,
            listener: android.os.PowerManager.OnThermalStatusChangedListener) {}
        @org.robolectric.annotation.Implementation
        fun removeThermalStatusListener(listener: android.os.PowerManager.OnThermalStatusChangedListener) {}
    }
    @get:Rule val ui = createComposeRule()
    private var fixtureLanguage: String? = null
    @org.junit.Before fun englishFixture() {
        val app=RuntimeEnvironment.getApplication()
        fixtureLanguage=com.no5.ui.UiLanguage.current(app)
        com.no5.ui.UiLanguage.acceptConfirmed(app, "en")
    }
    @org.junit.After fun restoreFixtureLanguage() {
        fixtureLanguage?.let { com.no5.ui.UiLanguage.acceptConfirmed(RuntimeEnvironment.getApplication(), it) }
    }
    @Test fun allFivePagesHaveOneSelectedTabAndPersistThemeWithoutChangingSource() {
        val app = RuntimeEnvironment.getApplication()
        org.robolectric.Shadows.shadowOf(app.packageManager).addOrUpdateActivity(android.content.pm.ActivityInfo().apply {
            packageName = "com.no5.home"; name = "com.no5.home.HomeActivity"; enabled = true; exported = true
            applicationInfo = android.content.pm.ApplicationInfo().apply { packageName = "com.no5.home"; enabled = true }
        })
        val store = AndroidSourceSettingsStore(app)
        val config = SourceConfig(mode=com.no5.domain.SourceMode.PI,connectionEnabled=false)
        store.save(config)
        var view: android.view.View? = null
        ui.mainClock.autoAdvance = false
        ui.setContent {
            view = LocalView.current
            Box(Modifier.size(2000.dp,1200.dp)) { No5App(remember { BackgroundSettingsViewModel(app) }) }
        }
        fun settle() { ui.waitForIdle();ui.mainClock.advanceTimeBy(64);ui.waitForIdle() }
        settle()
        val output=File(System.getProperty("no5.renderOutputDir"),"navigation-full").also{it.mkdirs()}
        for (night in listOf(false,true)) {
            val header = checkNotNull(view)
            ui.runOnIdle {
                assertNativeHeaderAccessibility(header, "installed-app-version",
                    com.no5.releaseinfo.ReleaseInfo.shortBuildLabel(app, "HELM"), clickable = false)
                assertNativeHeaderAccessibility(header, if(night) "header-night" else "header-day",
                    com.no5.ui.No5Text.text("en", if(night) "Nachtmodus" else "Tagmodus"), clickable = true)
            }
            tapNativeHeaderTarget(ui, header, if(night) "header-night" else "header-day")
            settle()
            ui.waitForIdle()
            ui.onNodeWithContentDescription(com.no5.ui.No5Text.text("en", "No5 HOME öffnen")).performClick()
            settle()
            val home = requireNotNull(org.robolectric.Shadows.shadowOf(app).nextStartedActivity)
            assertEquals(android.content.ComponentName("com.no5.home", "com.no5.home.HomeActivity"), home.component)
            assertEquals(android.content.Intent.ACTION_MAIN, home.action)
            assertEquals(setOf(android.content.Intent.CATEGORY_LAUNCHER), home.categories)
            assertEquals(night, home.getBooleanExtra(com.no5.navigation.AppNavigation.NIGHT, !night))
            assertEquals(0, home.flags and (android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP))
            ui.onNodeWithText("NO 5 · Anwendungen").assertDoesNotExist()
            for(page in BottomNavigation.pages) {
                val description=com.no5.ui.No5Text.ui(page)
                val tab=hasContentDescription(description) and SemanticsMatcher.expectValue(SemanticsProperties.Role,Role.Tab)
                ui.onNode(tab).performClick()
                settle()
                ui.onNode(tab).assertIsSelected()
                    .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role,Role.Tab))
                ui.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role,Role.Tab)
                    and isSelected()).assertCountEquals(1)
                val bounds=ui.onNode(tab).fetchSemanticsNode().boundsInRoot
                assertEquals(BottomNavigation.hitWidth,bounds.width,.1f)
                assertEquals(BottomNavigation.height,bounds.height,.1f)
                assertEquals(BottomNavigation.cellLeft(BottomNavigation.pages.indexOf(page)),bounds.left,.51f)
                ui.runOnIdle {
                    assertEquals(config,store.load())
                    assertEquals(night,app.getSharedPreferences("no5_demo",0).getBoolean("night",false))
                    val root=checkNotNull(view)
                    val bitmap=Bitmap.createBitmap(root.width,root.height,Bitmap.Config.ARGB_8888)
                    root.draw(Canvas(bitmap))
                    File(output,"$page-${if(night)"night" else "day"}.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
                    bitmap.recycle()
                }
            }
            ui.onNodeWithTag("open-gateway-connection").performScrollTo().performClick()
            settle()
            ui.onNodeWithTag("gateway-endpoint").assertDoesNotExist()
            ui.onNodeWithTag("gateway-setup").assertExists()
            ui.onNodeWithTag("gateway-test").assertIsEnabled()
            ui.runOnIdle {
                val root=checkNotNull(view)
                val bitmap=Bitmap.createBitmap(root.width,root.height,Bitmap.Config.ARGB_8888)
                root.draw(Canvas(bitmap))
                File(output,"GATEWAY-${if(night)"night" else "day"}.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
                bitmap.recycle()
            }
            ui.runOnIdle {
                var context=checkNotNull(view).context
                while(context is android.content.ContextWrapper && context !is androidx.activity.ComponentActivity)
                    context=context.baseContext
                (context as androidx.activity.ComponentActivity).onBackPressedDispatcher.onBackPressed()
            }
            settle()
            ui.onNodeWithTag("open-gateway-connection").performScrollTo().performClick()
            settle()
            ui.onNodeWithTag("gateway-setup").assertExists()
            ui.onNodeWithTag("gateway-endpoint").assertDoesNotExist()
            ui.onNodeWithTag("gateway-back").performClick()
            settle()
            ui.runOnIdle { assertEquals(config,store.load()) }
        }
    }
}
