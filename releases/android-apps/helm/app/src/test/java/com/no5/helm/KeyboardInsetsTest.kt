package com.no5.helm

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.no5.domain.SourceConfig
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import android.os.PowerManager
import org.robolectric.annotation.Implements
import org.robolectric.annotation.Implementation
import org.robolectric.shadows.ShadowPowerManager
import java.util.concurrent.Executor

/** The host has no thermal-service Binder. Supply that boundary only in this test. */
@Implements(PowerManager::class)
class KeyboardTestPowerManager:ShadowPowerManager(){
    @Implementation fun addThermalStatusListener(executor:Executor,listener:PowerManager.OnThermalStatusChangedListener){executor.execute{listener.onThermalStatusChanged(PowerManager.THERMAL_STATUS_NONE)}}
    @Implementation fun removeThermalStatusListener(listener:PowerManager.OnThermalStatusChangedListener){}
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29,35],qualifiers="w1000dp-h600dp-land-xhdpi",shadows=[KeyboardTestPowerManager::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class KeyboardInsetsTest {
    @get:Rule(order=0) val offlineSource=object:org.junit.rules.ExternalResource(){
        private var service: org.robolectric.android.controller.ServiceController<com.no5.helm.control.HelmSourceService>? = null
        private var fixtureLanguage: String? = null
        override fun before(){
            resetAndroidViewModelApplicationFixture()
            val app=org.robolectric.RuntimeEnvironment.getApplication()
            fixtureLanguage=com.no5.ui.UiLanguage.current(app)
            com.no5.ui.UiLanguage.acceptConfirmed(app,"en")
            val store=AndroidSourceSettingsStore(app).also { it.save(SourceConfig(connectionEnabled=true,weatherProvider=com.no5.domain.WeatherProviderMode.DISABLED)) }
            // Robolectric records startForegroundService but does not execute that lifecycle.
            // Supply the real foreground owner/service with an explicit no-network source fixture.
            com.no5.helm.control.HelmSourceRegistry.installForTest(app,
                com.no5.helm.control.HelmSourceOwner(store,com.no5.helm.control.OwnerFixtureFactory()))
            service=org.robolectric.Robolectric.buildService(com.no5.helm.control.HelmSourceService::class.java).create()
        }
        override fun after(){
            service?.destroy();com.no5.helm.control.HelmSourceRegistry.resetForTest()
            fixtureLanguage?.let { com.no5.ui.UiLanguage.acceptConfirmed(org.robolectric.RuntimeEnvironment.getApplication(),it) }
        }
    }
    @get:Rule(order=1) val ui=createAndroidComposeRule<MainActivity>()
    private fun ime(px:Int){ui.runOnIdle{
        val builder=WindowInsetsCompat.Builder()
        // API29 has no native typed IME inset. Model the platform's system-window
        // bottom inset, from which WindowInsetsCompat derives IME occlusion.
        if(android.os.Build.VERSION.SDK_INT<30)builder.setSystemWindowInsets(Insets.of(0,0,0,px)).setStableInsets(Insets.NONE)
        else builder.setInsets(WindowInsetsCompat.Type.ime(),Insets.of(0,0,0,px)).setVisible(WindowInsetsCompat.Type.ime(),px>0)
        ViewCompat.dispatchApplyWindowInsets(ui.activity.window.decorView,builder.build())};ui.waitForIdle()}
    private fun specialKeyboard(px:Int){ui.runOnIdle{com.no5.keyboard.SpecialKeyboardGeometry.publish(ui.activity,ui.activity.packageName,
        if(px==0)null else android.graphics.Rect(353,1200-px,1656,1200))};ui.waitForIdle()}
    private fun saveAtScrollEnd(label:String) {
        ui.onAllNodes(hasScrollAction()).onFirst().performSemanticsAction(SemanticsActions.ScrollBy){it(0f,100000f)}
        ui.waitForIdle()
        val viewport=ui.onNodeWithTag("helm-form-viewport").fetchSemanticsNode().boundsInRoot
        val save=ui.onNodeWithTag("source-settings-apply").fetchSemanticsNode().boundsInRoot
        val out=File(System.getProperty("no5.renderOutputDir"),"settings-save-regression").apply{mkdirs()}
        ui.runOnIdle{val b=Bitmap.createBitmap(2000,1200,Bitmap.Config.ARGB_8888);ui.activity.window.decorView.draw(Canvas(b));File(out,"$label-api${android.os.Build.VERSION.SDK_INT}.png").outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)};b.recycle()}
        assertTrue("Save must remain a complete 48dp/96px touch target: $save inside $viewport",save.height>=95f)
        assertTrue(save.top>=viewport.top&&save.bottom<=viewport.bottom)
        assertTrue(KnobGeometry.safe(save.left,save.top,save.right,save.bottom))
    }
    @Test fun saveIsCompleteAtScrollEndWithKeyboardHiddenShownAndHiddenAgain(){
        ui.onNodeWithContentDescription("SETTINGS").performClick()
        ui.onNodeWithTag("open-display-settings").performScrollTo().performClick()
        saveAtScrollEnd("hidden-first")
        ui.onNodeWithTag("settings-detail-back").performClick()
        ui.onNodeWithTag("open-gateway-connection").performScrollTo().performClick()
        val initial=ui.onNodeWithTag("helm-form-viewport").fetchSemanticsNode().boundsInRoot
        repeat(3){
            ui.onNodeWithTag("gateway-setup").assertIsDisplayed()
            ime(480);specialKeyboard(480)
            val field=ui.onNodeWithTag("gateway-setup").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            val visible=ui.onNodeWithTag("helm-form-viewport").fetchSemanticsNode().boundsInRoot
            assertTrue(field.top>=visible.top&&field.bottom<=visible.bottom)
            ui.onNodeWithTag("gateway-setup").assertIsDisplayed()
            ime(0);specialKeyboard(0)
            assertEquals("No retained bottom inset after hide",initial,ui.onNodeWithTag("helm-form-viewport").fetchSemanticsNode().boundsInRoot)
        }
        ui.onNodeWithTag("gateway-back").performClick()
        ui.onNodeWithTag("open-display-settings").performScrollTo().performClick()
        saveAtScrollEnd("hidden-final")
    }
    @Test fun repeatedImeOcclusionAndNavigationKeepPhysicalStageAndSessionWhileSummaryScrolls(){
        val stage=ui.onNodeWithTag("helm-physical-stage").fetchSemanticsNode().boundsInRoot
        val nav=ui.onNodeWithContentDescription("HELM").fetchSemanticsNode().boundsInRoot
        val owner=ui.activity.sourceSessions;val session=owner.session
        assertEquals(2000f,stage.width,.5f);assertEquals(1200f,stage.height,.5f)
        ui.onNodeWithContentDescription("SETTINGS").performClick()
        ui.onNodeWithTag("open-gateway-connection").performScrollTo().performClick()
        val initial=ui.onNodeWithTag("helm-form-viewport").fetchSemanticsNode().boundsInRoot
        repeat(3){
            ui.onNodeWithTag("gateway-setup").assertIsDisplayed()
            ime(480)
            specialKeyboard(480)
            val viewport=ui.onNodeWithTag("helm-form-viewport").fetchSemanticsNode().boundsInRoot
            assertEquals(720f,viewport.bottom,.5f);assertTrue(viewport.height<initial.height)
            val field=ui.onNodeWithTag("gateway-setup").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertTrue(field.top>=viewport.top-.5f&&field.bottom<=viewport.bottom+.5f)
            assertEquals(stage,ui.onNodeWithTag("helm-physical-stage").fetchSemanticsNode().boundsInRoot)
            assertEquals(nav,ui.onNodeWithContentDescription("HELM").fetchSemanticsNode().boundsInRoot)
            ime(0);specialKeyboard(0);assertEquals(initial,ui.onNodeWithTag("helm-form-viewport").fetchSemanticsNode().boundsInRoot)
        }
        ui.onNodeWithContentDescription("SONAR").performClick();ui.onNodeWithContentDescription("AUDIO").performClick();ui.onNodeWithContentDescription("HELM").performClick()
        ui.runOnIdle{assertSame(owner,ui.activity.sourceSessions);assertSame(session,owner.session)}
        val out=File(System.getProperty("no5.renderOutputDir"),"keyboard-insets").apply{mkdirs()}
        ui.runOnIdle{val b=Bitmap.createBitmap(2000,1200,Bitmap.Config.ARGB_8888);ui.activity.window.decorView.draw(Canvas(b));File(out,"helm-hidden-api${android.os.Build.VERSION.SDK_INT}.png").outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)};b.recycle()}
    }
}
