package com.no5.helm

import android.app.Activity
import android.os.Looper
import android.view.View
import android.widget.TextView
import com.no5.header.artwork.No5HeaderControlsView
import com.no5.header.artwork.No5MomentFeedback
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29],qualifiers="w1000dp-h600dp-land-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class HeaderMomentFeedbackTest {
    @get:Rule val ui=createComposeRule()
    @After fun clear() { No5MomentFeedback.clear() }

    @Test fun oldResultsCannotReplaceANewerMessageOrANewSource() {
        val owner=Any()
        val old=No5MomentFeedback.begin(owner,"Medien",1)
        val fresh=No5MomentFeedback.begin(owner,"Fader zuordnen",2)
        No5MomentFeedback.complete(old,No5MomentFeedback.Phase.FAILED)
        No5MomentFeedback.invalidate(owner,2)
        assertEquals(fresh,No5MomentFeedback.current()!!.ticket)
        assertEquals(No5MomentFeedback.Phase.ACCEPTED,No5MomentFeedback.current()!!.phase)
        No5MomentFeedback.invalidate(owner,3)
        No5MomentFeedback.complete(fresh,No5MomentFeedback.Phase.EXECUTED)
        assertNull(No5MomentFeedback.current())
    }

    @Test fun nativeVersionSlotShowsOutcomeThenRestoresVersionWithoutChangingItsGeometry() {
        lateinit var view:No5HeaderControlsView
        ui.setContent {
            AndroidView(factory={No5HeaderControlsView(it).also{v->view=v}},
                modifier=Modifier.size(1000.dp,65.dp),
                update={it.render("HELM · B123",false,true,{},"de",{})})
        }
        ui.waitForIdle()
        focusHelmTestForegroundWindow(ui)
        ui.runOnIdle {
            // Robolectric's Compose host keeps AttachInfo at GONE even after resume.
            // Supply the explicit native WindowManager visibility fixture; product
            // observation still requires attached + shown + VISIBLE, as on Lumina.
            val attach=org.robolectric.util.ReflectionHelpers.getField<Any>(view,"mAttachInfo")
            org.robolectric.util.ReflectionHelpers.setField(attach,"mWindowVisibility",View.VISIBLE)
            view.dispatchWindowVisibilityChanged(View.VISIBLE)
        }
        val version=view.findViewWithTag<TextView>("installed-app-version")
        val bounds=listOf(version.left,version.top,version.width,version.height)
        assertTrue("attached=${view.isAttachedToWindow} shown=${view.isShown} window=${view.windowVisibility}", view.isAttachedToWindow && view.isShown && view.windowVisibility==View.VISIBLE)
        val ticket=No5MomentFeedback.begin(this,"Fader zuordnen")
        assertEquals("Fader zuordnen…",version.text.toString())
        assertTrue(version.contentDescription.contains("angenommen"))
        No5MomentFeedback.complete(ticket,No5MomentFeedback.Phase.EXECUTED)
        assertEquals("Fader zuordnen ✓",version.text.toString())
        assertEquals(bounds,listOf(version.left,version.top,version.width,version.height))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(2501))
        assertEquals("HELM · B123",version.text.toString())
        val uncertain=No5MomentFeedback.begin(this,"Medien")
        No5MomentFeedback.complete(uncertain,No5MomentFeedback.Phase.UNKNOWN)
        assertEquals("Medien ?",version.text.toString())
        assertTrue(version.contentDescription.contains("Abschluss unklar"))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(3000))
        assertEquals("Medien ?",version.text.toString())
        val failed=No5MomentFeedback.begin(this,"Medien")
        No5MomentFeedback.complete(failed,No5MomentFeedback.Phase.FAILED)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(3000))
        assertEquals("Medien ×",version.text.toString())
        view.setForeground(false)
        assertEquals("HELM · B123",version.text.toString())
        // The command owner retires its source session; a presentation consumer
        // must not clear a different owner's current message merely by detaching.
        No5MomentFeedback.invalidate(this)
        No5MomentFeedback.complete(failed,No5MomentFeedback.Phase.EXECUTED)
        view.setForeground(true)
        assertEquals("HELM · B123",version.text.toString())
        ui.runOnIdle { view.setForeground(false) }
    }
    @Test fun retiringAHeaderConsumerCannotEraseTheCurrentCommandOwnersMessage(){
        lateinit var first:No5HeaderControlsView
        lateinit var second:No5HeaderControlsView
        val oldVisible=androidx.compose.runtime.mutableStateOf(true)
        ui.setContent{
            androidx.compose.foundation.layout.Box(Modifier.size(1000.dp,65.dp)){
                if(oldVisible.value)AndroidView(factory={No5HeaderControlsView(it).also{first=it}},update={it.render("HELM · B123",false,true,{},"de",{})})
                AndroidView(factory={No5HeaderControlsView(it).also{second=it}},update={it.render("HELM · B123",false,true,{},"de",{})})
            }
        }
        ui.waitForIdle();focusHelmTestForegroundWindow(ui)
        ui.runOnIdle{
            for(v in listOf(first,second)){
                val attach=org.robolectric.util.ReflectionHelpers.getField<Any>(v,"mAttachInfo")
                org.robolectric.util.ReflectionHelpers.setField(attach,"mWindowVisibility",View.VISIBLE)
                v.dispatchWindowVisibilityChanged(View.VISIBLE)
            }
        }
        val owner=Any();val ticket=No5MomentFeedback.begin(owner,"Medien",4)
        No5MomentFeedback.complete(ticket,No5MomentFeedback.Phase.EXECUTED)
        val expiry=No5MomentFeedback.current()!!.expiresAt
        ui.runOnIdle{oldVisible.value=false};ui.waitForIdle()
        assertEquals(ticket,No5MomentFeedback.current()!!.ticket)
        assertEquals(expiry,No5MomentFeedback.current()!!.expiresAt)
        assertEquals("Medien ✓",second.findViewWithTag<TextView>("installed-app-version").text.toString())
        ui.runOnIdle{second.setForeground(false);second.setForeground(true)}
        assertEquals(ticket,No5MomentFeedback.current()!!.ticket)
        assertEquals(expiry,No5MomentFeedback.current()!!.expiresAt)
        No5MomentFeedback.invalidate(owner,5);assertNull(No5MomentFeedback.current())
    }

}
