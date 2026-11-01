package com.no5.helm

import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.no5.domain.*
import com.no5.helm.ocean.*
import com.no5.ui.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29,35],qualifiers="de-w1000dp-h600dp-land-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NativeSettingsWorkspaceTest {
    @get:Rule val ui=createComposeRule()
    private fun all(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap{all(v.getChildAt(it))}else emptyList()
    private fun target(tag:String)=WindowInspector.getGlobalWindowViews().flatMap{all(it)}.single{it.tag==tag}
    private fun tap(tag:String){ui.runOnIdle{val v=target(tag);assertTrue("Disabled $tag",v.isEnabled);v.performClick()};ui.waitForIdle()}
    @After fun reset(){UiLanguage.acceptConfirmed(RuntimeEnvironment.getApplication(),"de")}
    @Test fun unitsRemainLocalAndGatewayNavigationCannotDropAnUnsavedDraft(){
        var config by mutableStateOf(SourceConfig(mode=SourceMode.PI,connectionEnabled=false))
        val writes=mutableListOf<SourceConfig>();var gateway=0
        var guard:((()->Unit)->Unit)?=null
        ui.setContent{SourceSettingsScreenWithHistory(.5f,false,config,{writes+=it;config=it},onOpenGatewayConnection={gateway++},
            bodyModifier=Modifier.fillMaxSize(),registerNavigationGuard={guard=it})}
        tap("no5-row-open-display-settings");tap("no5-row-settings-depth-dropdown");focusHelmTestForegroundWindow(ui)
        ui.onNodeWithTag("settings-depth-FEET").performClick()
        ui.onNodeWithTag("settings-choice-close").performClick();focusHelmTestForegroundWindow(ui)
        assertTrue(writes.isEmpty())
        tap("no5-action-settings-detail-back");focusHelmTestForegroundWindow(ui)
        ui.onNodeWithTag("unsaved-changes-continue").performClick();focusHelmTestForegroundWindow(ui)
        tap("no5-action-source-settings-apply")
        assertEquals(1,writes.size);assertFalse(config.connectionEnabled);assertEquals(SourceMode.PI,config.mode)
        assertEquals(DepthUnit.FEET,config.displayUnits.depth)
        tap("no5-row-open-gateway-connection");assertEquals(1,gateway)
        // An externally opened background draft must also block this otherwise clean gateway path.
        // Its real asynchronous store is exercised separately below.
        ui.runOnIdle{requireNotNull(guard).invoke{gateway++}}
        assertEquals(2,gateway)
    }
    @Test fun backgroundSaveFailureAndNavigationWaitForActualOwnerCompletion(){
        val background=BackgroundSelection(BackgroundMode.IMAGE)
        var request:BackgroundSelection.Save?=null;var gateway=0;var navigations=0
        var guard:((()->Unit)->Unit)?=null
        ui.setContent{SourceSettingsScreenWithHistory(.5f,false,SourceConfig(),{},onOpenGatewayConnection={gateway++},
            bodyModifier=Modifier.fillMaxSize(),background=background,onSaveBackground={request=background.beginSave()},registerNavigationGuard={guard=it})}
        ui.runOnIdle{background.select(BackgroundMode.NONE)}
        tap("no5-row-open-gateway-connection");focusHelmTestForegroundWindow(ui)
        assertEquals(0,gateway)
        ui.onNodeWithTag("unsaved-changes-save").performClick();ui.runOnIdle{assertNotNull(request);assertEquals(0,gateway);background.finishSave(request!!,false)}
        focusHelmTestForegroundWindow(ui)
        ui.onNodeWithTag("unsaved-changes-error").assertIsDisplayed();assertEquals(BackgroundMode.NONE,background.draft)
        ui.onNodeWithTag("unsaved-changes-save").performClick();ui.runOnIdle{assertEquals(0,gateway);background.finishSave(request!!,true)}
        ui.waitForIdle();assertEquals(1,gateway);assertEquals(BackgroundMode.NONE,background.active)
        ui.runOnIdle{requireNotNull(guard).invoke{navigations++}};assertEquals(1,navigations)
    }
    @Test fun failedLocalSaveRetainsUnitsAndDoesNotReturnToOverview(){
        ui.setContent{SourceSettingsScreenWithHistory(.5f,false,SourceConfig(),{error("EXPLICIT_STORE_FAILURE")},
            onOpenGatewayConnection={},bodyModifier=Modifier.fillMaxSize())}
        tap("no5-row-open-display-settings");tap("no5-row-settings-depth-dropdown");focusHelmTestForegroundWindow(ui)
        ui.onNodeWithTag("settings-depth-FEET").performClick();ui.onNodeWithTag("settings-choice-close").performClick();focusHelmTestForegroundWindow(ui)
        tap("no5-action-source-settings-apply")
        ui.runOnIdle{assertNotNull(target("no5-row-source-settings-warning"));assertTrue(target("no5-action-source-settings-apply").isEnabled)
            assertTrue(target("no5-row-settings-depth-dropdown").contentDescription.toString().contains("ft"))}
    }
}
