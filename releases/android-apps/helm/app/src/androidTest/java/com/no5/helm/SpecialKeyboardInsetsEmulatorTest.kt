package com.no5.helm

import android.graphics.Rect
import android.graphics.Bitmap
import android.view.*
import android.widget.FrameLayout
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.platform.app.InstrumentationRegistry
import com.no5.systemwindowtest.NarrowKeyboardView
import com.no5.keyboard.SpecialKeyboardGeometry
import com.no5.domain.SourceConfig
import org.junit.*
import org.junit.Assert.*
import java.io.File

/** Native keyboard view + explicit inset/geometry fixture; never changes the selected IME. */
class SpecialKeyboardInsetsEmulatorTest {
    @get:Rule(order=0) val offlineSource=object:org.junit.rules.ExternalResource(){override fun before(){AndroidSourceSettingsStore(InstrumentationRegistry.getInstrumentation().targetContext).save(SourceConfig(connectionEnabled=false))}}
    @get:Rule(order=1) val ui=createAndroidComposeRule<MainActivity>()
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
    private fun show(){instrumentation.runOnMainSync{
        val parent=ui.activity.findViewById<FrameLayout>(android.R.id.content)
        keyboard=NarrowKeyboardView(ui.activity){}.also{parent.addView(it,FrameLayout.LayoutParams(-1,NarrowKeyboardView.HEIGHT,Gravity.BOTTOM))}
    };ui.waitForIdle();inset(NarrowKeyboardView.HEIGHT)}
    private fun hide(){instrumentation.runOnMainSync{keyboard?.let{(it.parent as android.view.ViewGroup).removeView(it)};keyboard=null};inset(0)}
    private fun shot(name:String,appWindow:Boolean=false){
        val dir=File(ui.activity.getExternalFilesDir(null),"keyboard-build38").apply{mkdirs()}
        val b=if(appWindow){
            // Actual hardware-rendered app pixels, excluding Android's fullscreen tutorial.
            val bitmap=Bitmap.createBitmap(2000,1200,Bitmap.Config.ARGB_8888)
            val done=java.util.concurrent.CountDownLatch(1);var result=-1
            instrumentation.runOnMainSync {PixelCopy.request(ui.activity.window,bitmap,{result=it;done.countDown()},android.os.Handler(android.os.Looper.getMainLooper()))}
            assertTrue(done.await(5,java.util.concurrent.TimeUnit.SECONDS));assertEquals(PixelCopy.SUCCESS,result);bitmap
        }else instrumentation.uiAutomation.takeScreenshot()
        assertEquals(2000,b.width);assertEquals(1200,b.height)
        File(dir,"${if(baseline)"before"else"after"}-$name.png").outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)};b.recycle()
    }
    @Test fun repeatedNativeKeyboardFixtureFocusNavigationAndPhysicalBounds(){
        assertTrue("Only an isolated emulator",android.os.Build.FINGERPRINT.contains("generic")||android.os.Build.HARDWARE.contains("ranchu"))
        assertEquals(320,ui.activity.resources.displayMetrics.densityDpi)
        val nav=ui.onNodeWithContentDescription("HELM").fetchSemanticsNode().boundsInRoot
        val owner=ui.activity.sourceSessions;val session=owner.session
        ui.onNodeWithContentDescription("SETTINGS").performClick();ui.onNodeWithTag("open-gateway-connection").performScrollTo().performClick();shot("hidden-settings")
        repeat(3){ui.onNodeWithTag("gateway-endpoint").performScrollTo().performClick();show()
            val now=ui.onNodeWithContentDescription("HELM").fetchSemanticsNode().boundsInRoot
            if(!baseline){assertEquals(nav,now);val v=ui.onNodeWithTag("helm-form-viewport").fetchSemanticsNode().boundsInRoot;assertEquals(720f,v.bottom,1f)
                val f=ui.onNodeWithTag("gateway-endpoint").performScrollTo().fetchSemanticsNode().boundsInRoot;assertTrue(f.top>=v.top-1&&f.bottom<=v.bottom+1)}
            else assertTrue("Before inset shrinks complete stage",now.width<nav.width)
            if(it==0)shot("shown-settings")
            hide();assertEquals(nav,ui.onNodeWithContentDescription("HELM").fetchSemanticsNode().boundsInRoot)
        }
        ui.onNodeWithContentDescription("SONAR").performClick();ui.onNodeWithContentDescription("AUDIO").performClick();ui.onNodeWithContentDescription("HELM").performClick()
        assertSame(owner,ui.activity.sourceSessions);assertSame(session,owner.session);shot("hidden-helm")
    }
    @Test fun saveAtScrollEndRegression(){
        assertTrue(android.os.Build.FINGERPRINT.contains("generic")||android.os.Build.HARDWARE.contains("ranchu"))
        assertEquals(320,ui.activity.resources.displayMetrics.densityDpi)
        val before=InstrumentationRegistry.getArguments().getString("saveBaseline")=="true"
        val nav=ui.onNodeWithContentDescription("HELM").fetchSemanticsNode().boundsInRoot
        ui.onNodeWithContentDescription("SETTINGS").performClick();ui.onNodeWithTag("open-gateway-connection").performScrollTo().performClick()
        fun end(label:String){
            ui.onAllNodes(hasScrollAction()).onFirst().performSemanticsAction(SemanticsActions.ScrollBy){it(0f,100000f)}
            ui.waitForIdle()
            val v=ui.onNodeWithTag("helm-form-viewport").fetchSemanticsNode().boundsInRoot
            val s=ui.onNodeWithTag("gateway-save-address").fetchSemanticsNode().boundsInRoot
            shot("save-${if(before)"build38"else"build39"}-$label",appWindow=true)
            if(before)assertTrue("Build38 reproduces clipped Save: $s",s.height<95f)
            else {assertTrue("Complete Save touch target: $s",s.height>=95f);assertTrue(s.top>=v.top&&s.bottom<=v.bottom)}
            assertEquals(nav,ui.onNodeWithContentDescription("HELM").fetchSemanticsNode().boundsInRoot)
        }
        end("hidden-first")
        val initial=ui.onNodeWithTag("helm-form-viewport").fetchSemanticsNode().boundsInRoot
        repeat(3){
            ui.onNodeWithTag("gateway-endpoint").performScrollTo().performClick();show();end("shown-$it")
            ui.onNodeWithTag("gateway-endpoint").performScrollTo().performClick();hide()
            assertEquals(initial,ui.onNodeWithTag("helm-form-viewport").fetchSemanticsNode().boundsInRoot)
            end("hidden-$it")
        }
    }
}
