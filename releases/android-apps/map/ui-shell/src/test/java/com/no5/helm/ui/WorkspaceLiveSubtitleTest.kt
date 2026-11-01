package com.no5.helm.ui

import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import com.no5.header.artwork.No5WorkspaceView
import com.no5.ui.No5Workspace
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29,35],qualifiers="de-w1000dp-h600dp-land-xhdpi")
class WorkspaceLiveSubtitleTest {
    @get:Rule val ui=createEmptyComposeRule()
    private fun all(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap{all(v.getChildAt(it))}else emptyList()
    @Test fun liveDataUpdatesKeepTheSameNativePageAndEditableDraft(){
        var caption by mutableStateOf("1,0 Hz");var draft by mutableStateOf("RAW")
        Robolectric.buildActivity(ComponentActivity::class.java).apply{get().setTheme(android.R.style.Theme_Material_NoActionBar)}.setup().visible().windowFocusChanged(true).use{host->
            host.get().setContent{No5Workspace("Musik",caption,listOf(No5WorkspaceView.Row("Name",value=draft,rawValue=true,key="draft",control="field",onValueChange={draft=it})),false,{},Modifier.fillMaxSize(),liveSubtitle=true)}
            ui.waitForIdle()
            val page=all(host.get().window.decorView).filterIsInstance<No5WorkspaceView>().single()
            val field=all(page).filterIsInstance<EditText>().single()
            ui.runOnIdle{field.requestFocus();field.setSelection(2);caption="4,8 Hz"}
            ui.waitForIdle()
            ui.runOnIdle{
                assertSame(page,all(host.get().window.decorView).filterIsInstance<No5WorkspaceView>().single())
                assertSame(field,all(page).filterIsInstance<EditText>().single());assertTrue(field.hasFocus());assertEquals(2,field.selectionStart)
                assertEquals("RAW",field.text.toString());assertTrue(page.contentDescription.toString().contains("4,8 Hz"))
                field.append(" DATA")
            }
            ui.runOnIdle{caption="5,0 Hz"}
            ui.runOnIdle{assertEquals("RAW DATA",draft);assertSame(field,all(page).filterIsInstance<EditText>().single());assertEquals("RAW DATA",field.text.toString())}
        }
    }
}
