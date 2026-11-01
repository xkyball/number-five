package com.no5.helm.ui

import android.content.Context
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import com.no5.helm.model.*
import com.no5.preparation.ui.*
import com.no5.ui.No5LocaleScope
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

/** Host-only 2000x1200 / 320dpi proof. No GPS, GLES or physical follow claim. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29],qualifiers="w1000dp-h600dp-land-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MapPositionUiTest {
    @get:Rule val compose=createEmptyComposeRule()
    private lateinit var activity:ActivityController<No5ImmersiveActivity>
    @Before fun before() {
        activity=Robolectric.buildActivity(No5ImmersiveActivity::class.java)
        activity.get().setTheme(android.R.style.Theme_Material_NoActionBar)
        activity.setup().visible().windowFocusChanged(true)
    }
    private fun all(v:android.view.View):List<android.view.View> = listOf(v)+if(v is android.view.ViewGroup)(0 until v.childCount).flatMap{all(v.getChildAt(it))}else emptyList()
    private fun native(key:String)=all(activity.get().window.decorView).single{it.tag==key}
    private fun tap(key:String){compose.runOnIdle{assertTrue(native(key).isEnabled);native(key).performClick()};compose.waitForIdle()}
    private fun focusDialogWindow(){focusHostInputWindow(activity.get(),compose)}
    @After fun after() { activity.pause().stop().destroy();com.no5.ui.UiLanguage.acceptConfirmed(org.robolectric.RuntimeEnvironment.getApplication(),"de") }
    @Test fun onePositionButtonCyclesOffCenteredFollowOffWithSafeTargetsInEveryLanguageAndTheme() {
        var state by mutableStateOf(ShellState(chart=ChartUiState(ownFixValid=true,positionDetail="synthetic · current")))
        val events=mutableListOf<ShellEvent>();var language by mutableStateOf("de");var night by mutableStateOf(false)
        activity.get().setContent { No5LocaleScope(language) { No5ControlTheme(night) {
            No5Shell(state,{ e -> events+=e; if(e==ShellEvent.PositionCycle) {
                val mode=when(state.chart.positionMode){PositionButtonMode.OFF->PositionButtonMode.CENTERED;PositionButtonMode.CENTERED->PositionButtonMode.FOLLOW;PositionButtonMode.FOLLOW->PositionButtonMode.OFF}
                state=state.copy(chart=state.chart.copy(positionMode=mode,followRequested=mode==PositionButtonMode.FOLLOW,following=mode==PositionButtonMode.FOLLOW))
            } },rendererSlot={Box(it)})
        } } }
        for(l in listOf("de","en","af")) for(n in listOf(false,true)) {
            compose.runOnIdle { com.no5.ui.UiLanguage.acceptConfirmed(org.robolectric.RuntimeEnvironment.getApplication(),l);language=l;night=n;state=state.copy(redNight=n) }
            compose.onNodeWithTag("center-boat").assertDoesNotExist();compose.onNodeWithTag("follow-boat").assertDoesNotExist()
            val targets=listOf("position-cycle").map { tag ->
                compose.onNodeWithTag(tag).assertIsDisplayed().assertIsEnabled().fetchSemanticsNode().boundsInRoot.also { r ->
                    assertTrue("48dp physical target $tag",r.height>=96f);assertTrue(r.width>=96f)
                    assertTrue("Above hardware knob zones",r.bottom<900f)
                }
            }
            assertTrue(targets.zipWithNext().all { (a,b)->a.bottom<=b.top })
            compose.onNodeWithTag("position-cycle").assertIsNotSelected().performClick().assertIsSelected()
            compose.runOnIdle { assertEquals(PositionButtonMode.CENTERED,state.chart.positionMode);assertEquals(ShellEvent.PositionCycle,events.last()) }
            compose.onNodeWithTag("position-cycle").performClick().assertIsSelected()
            compose.runOnIdle { assertEquals(PositionButtonMode.FOLLOW,state.chart.positionMode) }
            compose.onNodeWithTag("position-cycle").performClick().assertIsNotSelected()
            compose.runOnIdle { assertEquals(PositionButtonMode.OFF,state.chart.positionMode) }
            compose.onNodeWithTag("position-sources").assertDoesNotExist();compose.onNodeWithTag("position-status").performClick()
            compose.runOnIdle { assertEquals(ShellEvent.PositionSources,events.last()) }
            compose.onNodeWithTag("source-mode").performClick();compose.runOnIdle{assertEquals(ShellEvent.OpenControl,events.last())}
        }
    }
    @Test fun missingFixDisablesActivationButNeverDisablesTheFollowOffAction() {
        var state by mutableStateOf(ShellState(chart=ChartUiState()))
        activity.get().setContent { No5ControlTheme(false) {No5Shell(state,{},rendererSlot={Box(it)})} }
        compose.onNodeWithTag("position-cycle").assertIsNotEnabled()
        compose.runOnIdle {state=state.copy(chart=state.chart.copy(positionMode=PositionButtonMode.CENTERED))}
        compose.onNodeWithTag("position-cycle").assertIsNotEnabled()
        compose.runOnIdle {state=state.copy(chart=state.chart.copy(positionMode=PositionButtonMode.FOLLOW,followRequested=true))}
        compose.onNodeWithTag("position-cycle").assertIsEnabled().assertIsSelected()
    }
    @Test fun boundedSourcePickerRetainsFullIdsAndCombinedIsUnavailableInEveryLanguage() {
        val id="synthetic."+"source".repeat(30)
        val source=PositionCandidate(id,id,id,PositionStatus.LIVE,10000,GeoPoint(0.0,0.0))
        val selected=MapPositionState(sources=PositionSources(primarySourceId=id,total=1,sources=listOf(source)),selectedSourceId=id,selectedLabel=id)
        var language by mutableStateOf("de");var night by mutableStateOf(false);var choice:PositionChoice?=null;var chosen:String?=null
        activity.get().setContent { No5LocaleScope(language) { No5ControlTheme(night) {
            MapPositionDialog(selected,{}, { mode,ref -> choice=mode;chosen=ref })
        } } }
        focusDialogWindow()
        for(l in listOf("de","en","af")) for(n in listOf(false,true)) {
            compose.runOnIdle { com.no5.ui.UiLanguage.acceptConfirmed(org.robolectric.RuntimeEnvironment.getApplication(),l);language=l;night=n }
            focusDialogWindow()
            tap("no5-row-position-individual");tap("no5-row-position-source-$id");tap("no5-workspace-back")
            compose.runOnIdle { assertEquals(PositionChoice.MANUAL,choice);assertEquals(id,chosen) }
            compose.runOnIdle{assertFalse(native("no5-row-position-combined").isEnabled)}
            tap("no5-row-position-auto")
            compose.runOnIdle { assertEquals(PositionChoice.AUTOMATIC,choice);assertNull(chosen) }
        }
    }
    @Test fun configuredCombinedChoiceIsUsableAndConflictIsVisibleInEachLanguage() {
        var reason by mutableStateOf("OK"); var language by mutableStateOf("de"); var choice:PositionChoice?=null
        activity.get().setContent { No5LocaleScope(language) { No5ControlTheme(false) {
            MapPositionDialog(MapPositionState(sources=PositionSources(combinationAvailable=true,combinationReason=reason)),{}, { mode,_ -> choice=mode })
        } } }
        focusDialogWindow()
        for(l in listOf("de","en","af")) {
            compose.runOnIdle { com.no5.ui.UiLanguage.acceptConfirmed(org.robolectric.RuntimeEnvironment.getApplication(),l);language=l }
            focusDialogWindow()
            tap("no5-row-position-combined")
            compose.runOnIdle { assertEquals(PositionChoice.COMBINED,choice);reason="SOURCE_DISAGREEMENT" }
            val expected=com.no5.ui.No5Text.text(l,"GPS-Konflikt: Positionen liegen zu weit auseinander")
            compose.runOnIdle{assertTrue(native("no5-row-position-combined").contentDescription.toString().contains(expected))}
            compose.runOnIdle { reason="OK" }
        }
    }
    @Test fun persistenceContainsOnlyVersionedIntentAndOverlayUsesSelectedFreshOwnship() {
        val p=activity.get().getSharedPreferences("synthetic-position-test",Context.MODE_PRIVATE);p.edit().clear().commit()
        val store=MapPositionPreferences(p)
        val intent=PositionIntent(choice=PositionChoice.MANUAL,sourceId="synthetic.gps.2",follow=true)
        store.save(intent);assertEquals(intent,MapPositionPreferences(p).load())
        val json=p.getString("positionIntentV1","")!!
        assertFalse(json.contains("latitude"));assertFalse(json.contains("observed"));assertFalse(json.contains("command"))
        val ship=Ownship(GeoPoint(0.0,0.0),null,null,null,null)
        val other=Ownship(GeoPoint(5.0,5.0),1.0,2.0,3.0,4.0)
        val snapshot=Snapshot(serverId="synthetic",revision=1,generatedAt="synthetic",state=HelmProjection(other,emptyList(),emptyList(),emptyList()))
        assertEquals(0.0,overlaySnapshot(snapshot,null,emptyMap(),ownship=ship).ownship!!.latitude,0.0)
        assertNull(overlaySnapshot(snapshot,null,emptyMap(),ownship=null).ownship)
        p.edit().putString("positionIntentV1","{\"version\":2}").commit();assertEquals(PositionIntent(),store.load())
    }
}
