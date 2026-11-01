package com.no5.helm

import android.graphics.Rect
import android.graphics.Bitmap
import android.view.*
import android.widget.FrameLayout
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.platform.app.InstrumentationRegistry
import com.no5.systemwindowtest.NarrowKeyboardView
import com.no5.keyboard.SpecialKeyboardGeometry
import org.junit.*
import org.junit.Assert.*
import java.io.File

/** Actual app/renderer, plus an explicit native keyboard fixture; no selected-IME changes. */
class SpecialKeyboardInsetsEmulatorTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val baseline get()=InstrumentationRegistry.getArguments().getString("baseline")=="true"
    private var keyboard:NarrowKeyboardView?=null
    private fun inset(px:Int){instrumentation.runOnMainSync{
        val b=WindowInsetsCompat.Builder()
        if(android.os.Build.VERSION.SDK_INT<30)b.setSystemWindowInsets(Insets.of(0,0,0,px)).setStableInsets(Insets.NONE)
        else b.setInsets(WindowInsetsCompat.Type.ime(),Insets.of(0,0,0,px)).setVisible(WindowInsetsCompat.Type.ime(),px>0)
        ViewCompat.dispatchApplyWindowInsets(ui.activity.window.decorView,b.build())
        if(!baseline)SpecialKeyboardGeometry.publish(ui.activity,ui.activity.packageName,if(px==0)null else Rect(353,1200-px,1656,1200))
    };ui.waitForIdle()}
    private fun show(){instrumentation.runOnMainSync{keyboard=NarrowKeyboardView(ui.activity){}.also{ui.activity.findViewById<FrameLayout>(android.R.id.content).addView(it,FrameLayout.LayoutParams(-1,NarrowKeyboardView.HEIGHT,Gravity.BOTTOM))}};ui.waitForIdle();inset(NarrowKeyboardView.HEIGHT)}
    private fun hide(){instrumentation.runOnMainSync{keyboard?.let{(it.parent as android.view.ViewGroup).removeView(it)};keyboard=null};inset(0)}
    private fun shot(name:String){val dir=File(ui.activity.getExternalFilesDir(null),"keyboard-build38").apply{mkdirs()};val b=instrumentation.uiAutomation.takeScreenshot();assertEquals(2000,b.width);assertEquals(1200,b.height);File(dir,"${if(baseline)"before"else"after"}-$name.png").outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)};b.recycle()}
    @Test fun rendererDockAndRouteEditorRetainBoundsDuringRepeatedNativeKeyboardFixture(){
        assertTrue("Only an isolated emulator",android.os.Build.FINGERPRINT.contains("generic")||android.os.Build.HARDWARE.contains("ranchu"))
        assertEquals(320,ui.activity.resources.displayMetrics.densityDpi)
        ui.waitUntil(60_000){ui.onAllNodesWithTag("no5-shell").fetchSemanticsNodes().isNotEmpty()}
        val renderer=ui.onNodeWithTag("renderer-slot").fetchSemanticsNode().boundsInRoot
        shot("hidden-chart")
        val reserved=ViewCompat.getRootWindowInsets(ui.activity.window.decorView)?.getInsets(WindowInsetsCompat.Type.mandatorySystemGestures() or WindowInsetsCompat.Type.navigationBars() or WindowInsetsCompat.Type.displayCutout())?.bottom?:0
        ui.onNodeWithTag("no5-shell").performTouchInput { down(androidx.compose.ui.geometry.Offset(center.x,height-reserved-2f));moveBy(androidx.compose.ui.geometry.Offset(0f,-160f));up() }
        ui.onNodeWithTag("nav-ROUTES").performClick()
        ui.onNodeWithText("+ Neue Route").performScrollTo().performClick();ui.onNode(hasSetTextAction()).performScrollTo().performClick();shot("hidden-route")
        repeat(3){show();val now=ui.onNodeWithTag("renderer-slot").fetchSemanticsNode().boundsInRoot
            if(!baseline){assertEquals(renderer,now);assertEquals(720f,ui.onNodeWithTag("screen-panel").fetchSemanticsNode().boundsInRoot.bottom,1f)
                ui.onNode(hasSetTextAction()).performScrollTo().assertIsDisplayed()}
            else assertTrue("Before inset shrinks renderer",now.width<renderer.width)
            if(it==0)shot("shown-route");hide();assertEquals(renderer,ui.onNodeWithTag("renderer-slot").fetchSemanticsNode().boundsInRoot)}
        ui.onNode(hasSetTextAction()).performTextReplacement("IME draft")
        ui.onNodeWithTag("panel-toggle").performClick();ui.onNodeWithTag("panel-toggle").performClick()
        ui.onNode(hasSetTextAction()).assertTextEquals("IME draft");assertEquals(renderer,ui.onNodeWithTag("renderer-slot").fetchSemanticsNode().boundsInRoot);shot("restored-route")
    }
}
