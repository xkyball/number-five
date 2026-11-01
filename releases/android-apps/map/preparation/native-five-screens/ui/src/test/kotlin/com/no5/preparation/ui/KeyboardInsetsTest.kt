package com.no5.preparation.ui

import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29,35],qualifiers="w1000dp-h600dp-land-xhdpi")
class KeyboardInsetsTest {
    @get:Rule(order=0) val hostManifest=org.junit.rules.TestRule { base,_ -> object:org.junit.runners.model.Statement() {
        override fun evaluate(){val app=org.robolectric.RuntimeEnvironment.getApplication()
            org.robolectric.Shadows.shadowOf(app.packageManager).addOrUpdateActivity(android.content.pm.ActivityInfo().apply{
                packageName=app.packageName;name=ComponentActivity::class.java.name;enabled=true;exported=true
                applicationInfo=app.applicationInfo;theme=android.R.style.Theme_Material_NoActionBar})
            base.evaluate()}
    } }
    @get:Rule(order=1) val ui=createAndroidComposeRule<ComponentActivity>()
    private fun ime(px:Int){ui.runOnIdle{
        val builder=WindowInsetsCompat.Builder()
        if(android.os.Build.VERSION.SDK_INT<30)builder.setSystemWindowInsets(Insets.of(0,0,0,px)).setStableInsets(Insets.NONE)
        else builder.setInsets(WindowInsetsCompat.Type.ime(),Insets.of(0,0,0,px)).setVisible(WindowInsetsCompat.Type.ime(),px>0)
        ViewCompat.dispatchApplyWindowInsets(ui.activity.window.decorView,builder.build())};ui.waitForIdle()}
    private fun specialKeyboard(px:Int){ui.runOnIdle{com.no5.keyboard.SpecialKeyboardGeometry.publish(ui.activity,ui.activity.packageName,
        if(px==0)null else android.graphics.Rect(353,1200-px,1656,1200))};ui.waitForIdle()}
    @Test fun keyboardOnlyShortensEditorPanelAndKeepsRendererMountDockAndDraft(){
        ui.activity.window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        WindowCompat.setDecorFitsSystemWindows(ui.activity.window,false)
        var state by mutableStateOf(ShellState(destination=Destination.SETTINGS,chart=ChartUiState(status=DataStatus(Availability.READY,"TEST"))))
        var mounts=0;var releases=0
        var draft by mutableStateOf("saved draft")
        ui.setContent{No5Shell(state,{if(it is ShellEvent.Navigate)state=state.copy(destination=it.destination)},
            rendererSlot={m->DisposableEffect(Unit){mounts++;onDispose{releases++}};Box(m)},panelContent={_,m->
                Column(m.verticalScroll(rememberScrollState())){Spacer(Modifier.height(340.dp));OutlinedTextField(draft,{draft=it},Modifier.testTag("editor"))}
            })}
        val stage=ui.onNodeWithTag("no5-shell").fetchSemanticsNode().boundsInRoot
        val renderer=ui.onNodeWithTag("renderer-slot").fetchSemanticsNode().boundsInRoot
        val panel=ui.onNodeWithTag("screen-panel").fetchSemanticsNode().boundsInRoot
        assertEquals(2000f,stage.width,.5f);assertEquals(1200f,stage.height,.5f)
        repeat(3){ui.onNodeWithTag("editor").performScrollTo().performClick();ime(480);specialKeyboard(480)
            val viewport=ui.onNodeWithTag("screen-panel").fetchSemanticsNode().boundsInRoot
            assertEquals(720f,viewport.bottom,.5f);assertTrue(viewport.height<panel.height)
            val field=ui.onNodeWithTag("editor").performScrollTo().fetchSemanticsNode().boundsInRoot
            assertTrue(field.top>=viewport.top-.5f&&field.bottom<=viewport.bottom+.5f)
            assertEquals(stage,ui.onNodeWithTag("no5-shell").fetchSemanticsNode().boundsInRoot)
            assertEquals(renderer,ui.onNodeWithTag("renderer-slot").fetchSemanticsNode().boundsInRoot)
            ime(0);specialKeyboard(0);assertEquals(panel,ui.onNodeWithTag("screen-panel").fetchSemanticsNode().boundsInRoot)}
        ui.onNodeWithTag("editor").performScrollTo().performTextReplacement("retained draft")
        ui.runOnIdle{state=state.copy(chart=state.chart.copy(touchActive=true))}
        ui.onNodeWithTag("screen-panel").assertIsDisplayed();ui.onNodeWithTag("editor").assertIsFocused().assertTextEquals("retained draft")
        ui.runOnIdle{state=state.copy(chart=state.chart.copy(touchActive=false))}
        ui.onNodeWithTag("editor").assertIsFocused().assertTextEquals("retained draft")
        ui.onNodeWithTag("no5-shell").performTouchInput { down(androidx.compose.ui.geometry.Offset(center.x,height-2f));moveBy(androidx.compose.ui.geometry.Offset(0f,-160f));up() };ui.onNodeWithTag("nav-CHART").performClick();ui.onNodeWithTag("nav-SETTINGS").performClick()
        ui.onNodeWithTag("editor").assertTextEquals("retained draft")
        assertEquals(renderer,ui.onNodeWithTag("renderer-slot").fetchSemanticsNode().boundsInRoot)
        ui.runOnIdle{assertEquals(1,mounts);assertEquals(0,releases)}
    }
}
