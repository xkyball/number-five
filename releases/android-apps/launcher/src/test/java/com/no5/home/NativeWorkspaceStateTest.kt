package com.no5.home

import android.app.Activity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import com.no5.header.artwork.No5WorkspaceView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29,35],qualifiers="w1000dp-h600dp-land-xhdpi")
class NativeWorkspaceStateTest {
    private fun all(view:View):List<View> = listOf(view)+if(view is ViewGroup)(0 until view.childCount).flatMap{all(view.getChildAt(it))}else emptyList()
    private fun layout(view:View){view.measure(View.MeasureSpec.makeMeasureSpec(2000,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(928,View.MeasureSpec.EXACTLY));view.layout(0,0,2000,928)}
    private fun pointer(view:View,action:Int){val e=MotionEvent.obtain(0,10,action,40f,40f,0);try{view.dispatchTouchEvent(e)}finally{e.recycle()}}
    @Test fun catalogueUpdateRetainsEditableFocusCursorAndDraftWithoutEchoingModelWrites(){
        Robolectric.buildActivity(Activity::class.java).setup().visible().windowFocusChanged(true).use{host->
            val edits=mutableListOf<String>()
            fun field(value:String)=No5WorkspaceView.Row("Bereiche oder Zellen suchen",value=value,rawValue=true,key="search",control="field",onValueChange={edits+=it})
            val view=No5WorkspaceView(host.get(),false,"Kartenbereiche auswählen","",listOf(field("B"),No5WorkspaceView.Row("A",key="A")),{})
            host.get().setContentView(view);layout(view)
            val input=all(view).filterIsInstance<EditText>().single();input.requestFocus();input.setSelection(1)
            view.renderRows(listOf(field("B"),No5WorkspaceView.Row("B",key="B")));layout(view)
            assertSame(input,all(view).filterIsInstance<EditText>().single());assertTrue(input.hasFocus());assertEquals(1,input.selectionStart);assertTrue(edits.isEmpty())
            input.append("O");assertEquals(listOf("BO"),edits)
            view.renderRows(listOf(field("BOS")));layout(view)
            assertEquals("BOS",input.text.toString());assertEquals(listOf("BO"),edits)
        }
    }
    @Test fun momentPressAndSelectionHaveIndependentLifetimesAcrossModelUpdates(){
        Robolectric.buildActivity(Activity::class.java).setup().visible().windowFocusChanged(true).use{host->
            var calls=0
            fun row(enabled:Boolean=true,selected:Boolean=false,action:(()->Unit)?={calls++})=No5WorkspaceView.Row("Folgen",key="follow",enabled=enabled,selected=selected,control="toggle",action=action)
            val view=No5WorkspaceView(host.get(),false,"Karte einrichten","",listOf(row(action=null)),{})
            host.get().setContentView(view);layout(view)
            val button=all(view).single{it.tag=="no5-row-follow"};assertFalse(button.isClickable)
            view.renderRows(listOf(row(selected=true)));layout(view);assertTrue(button.isClickable);assertTrue(button.isSelected)
            pointer(button,MotionEvent.ACTION_DOWN);pointer(button,MotionEvent.ACTION_CANCEL)
            assertFalse(button.isPressed);assertEquals(0,calls);assertTrue(button.isSelected)
            pointer(button,MotionEvent.ACTION_DOWN);view.renderRows(listOf(row(enabled=false,selected=false)));pointer(button,MotionEvent.ACTION_UP)
            assertFalse(button.isPressed);assertFalse(button.isSelected);assertEquals(0,calls)
            view.renderRows(listOf(row()));layout(view);assertTrue(button.performClick());assertEquals(1,calls)
        }
    }
    @Test fun liveCaptionDoesNotReplaceDraftFieldsOrRestoreAnOldScrollAndObserverEndsOnDetach(){
        Robolectric.buildActivity(Activity::class.java).setup().visible().windowFocusChanged(true).use{host->
            val rows=listOf(No5WorkspaceView.Row("Name",value="RAW_DRAFT",rawValue=true,key="draft",control="field",onValueChange={}))+
                (0 until 30).map{No5WorkspaceView.Row("FIXTURE $it",rawLabel=true,key="fixture-$it")}
            val view=No5WorkspaceView(host.get(),false,"Musik","1,0 Hz",rows,{})
            host.get().setContentView(view);layout(view)
            val input=all(view).filterIsInstance<EditText>().single();input.requestFocus();input.setSelection(3)
            view.renderSubtitle("4,8 Hz",raw=true);view.renderRows(rows);layout(view)
            assertSame(input,all(view).filterIsInstance<EditText>().single());assertTrue(input.hasFocus());assertEquals(3,input.selectionStart)
            assertEquals("RAW_DRAFT",input.text.toString());assertTrue(view.contentDescription.toString().contains("4,8 Hz"))
            all(view).single{it.tag=="no5-workspace-back"}.requestFocus()
            val samples=mutableListOf<Pair<Int,Boolean>>();view.observeScroll{y,moving->samples+=y to moving}
            view.restoreScroll(208);layout(view);assertEquals(208,view.scrollOffset)
            repeat(10){view.renderSubtitle("$it Hz",raw=true);view.renderRows(rows);layout(view);assertEquals(208,view.scrollOffset)}
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(200))
            assertEquals(208 to false,samples.last())
            host.get().setContentView(View(host.get()));val count=samples.size
            assertFalse(samples.last().second)
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(500))
            assertEquals(count,samples.size)
            host.get().setContentView(view);layout(view);view.restoreScroll(312);layout(view)
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(200))
            assertEquals(312 to false,samples.last())
        }
    }

}
