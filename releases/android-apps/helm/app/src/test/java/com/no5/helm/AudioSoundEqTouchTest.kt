package com.no5.helm

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.no5.domain.*
import com.no5.helm.network.soundFixture
import kotlinx.coroutines.Dispatchers
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.*

private class EqTouchPort(points:List<SoundEqPoint>,projected:Boolean=false):AudioSoundPort {
    override val state=MutableSourceFeed(soundFixture().let{it.copy(preset=it.preset!!.copy(eqPoints=points),capabilities=it.capabilities.copy(eqProjection=projected))})
    override val spectrum=MutableSourceFeed<AudioSpectrumFrame?>(null)
    override val results=MutableSourceFeed<AudioSoundResult?>(null)
    val commands=mutableListOf<AudioSoundCommand>()
    override fun submit(command:AudioSoundCommand):Long {commands+=command;return commands.size.toLong()}
    override fun setEditorVisible(visible:Boolean,eqVisible:Boolean)=Unit
    override fun refresh()=Unit
    override fun close()=Unit
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="de-w1000dp-h600dp-land-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AudioSoundEqTouchTest {
    @get:Rule val ui=createComposeRule()
    private fun mount(points:List<SoundEqPoint>,projected:Boolean=false):Pair<AudioNativeController,EqTouchPort> {
        val port=EqTouchPort(points,projected)
        val source=object:AudioSourcePort by MockAudioSource(now={10000}){override val sound=port}
        val audio=AudioNativeController(Dispatchers.Unconfined,now={10000},automaticTicker=false,source=source,eqValidator=FixtureEqValidator,eqValidationDispatcher=Dispatchers.Unconfined)
        ui.runOnIdle{audio.openSoundEditor(false);audio.soundEditor.choosePoint(points.first().id)}
        ui.setContent{MaterialTheme{Box(Modifier.size(1000.dp,600.dp)){AudioSoundEditor(.5f,false,audio,{},registerClose={},registerGuard={})}}}
        return audio to port
    }
    private fun high(p:SoundEqPoint)=Offset(SoundEqTouch.x(p.highHz,1718f),SoundEqTouch.y(p.gainDb,410f,12.0))

    @Test fun tenPixelWidthDragIsPreciseAndNeverChangesFrequencyOrGain(){
        val original=SoundEqPoint("a",1000.0,3.0,10.0)
        val (audio,_)=mount(listOf(original))
        try{
            val start=high(original)
            ui.onNodeWithTag("sound-eq-chart").performTouchInput{swipe(start,start+Offset(10f,0f),300)}
            ui.runOnIdle{
                val result=audio.soundEditor.preset!!.eqPoints.single()
                assertEquals(original.frequencyHz,result.frequencyHz);assertEquals(original.gainDb,result.gainDb)
                assertTrue(result.widthHz>original.widthHz)
                assertEquals(SoundEqTouch.moved(SoundEqTouchTarget(original,false),Offset(10f,0f),1718f,410f,12.0).q,result.q,1e-7)
            }
        }finally{ui.runOnIdle{audio.dispose()}}
    }
    @Test fun widthTargetStaysLatchedAcrossNearbyPointAndBothDirections(){
        val a=SoundEqPoint("a",1000.0,0.0,10.0);val neighbour=SoundEqPoint("b",1100.0,0.0,10.0)
        val (audio,_)=mount(listOf(a,neighbour))
        try{
            val start=high(a)
            ui.onNodeWithTag("sound-eq-chart").performTouchInput{swipe(start,start+Offset(85f,-12f),300)}
            ui.runOnIdle{
                val result=audio.soundEditor.preset!!.eqPoints
                assertEquals(a.frequencyHz,result.first().frequencyHz);assertEquals(a.gainDb,result.first().gainDb)
                assertEquals(neighbour,result.last());assertEquals("a",audio.soundEditor.selectedPoint)
            }
            val now=audio.soundEditor.preset!!.eqPoints.first();val end=high(now)
            ui.onNodeWithTag("sound-eq-chart").performTouchInput{swipe(end,end-Offset(20f,0f),300)}
            ui.runOnIdle{assertTrue(audio.soundEditor.preset!!.eqPoints.first().widthHz<now.widthHz);assertEquals(neighbour,audio.soundEditor.preset!!.eqPoints.last())}
        }finally{ui.runOnIdle{audio.dispose()}}
    }
    @Test fun lockedHandleAndSecondPointerNeverFallThroughToPointMovement(){
        val a=SoundEqPoint("a",1000.0,3.0,10.0,true)
        val (audio,port)=mount(listOf(a))
        try{
            val start=high(a)
            ui.onNodeWithTag("sound-eq-chart").performTouchInput{swipe(start,start+Offset(80f,30f),300)}
            ui.runOnIdle{assertEquals(a,audio.soundEditor.preset!!.eqPoints.single());assertTrue(port.commands.isEmpty());audio.soundEditor.updatePoint(a.copy(widthLocked=false))}
            val unlocked=audio.soundEditor.preset!!.eqPoints.single();val count=port.commands.size
            ui.onNodeWithTag("sound-eq-chart").performTouchInput{
                down(0,start);down(1,start+Offset(100f,30f));moveTo(0,start+Offset(80f,0f));up(1);moveTo(0,start+Offset(120f,0f));up(0)
            }
            ui.runOnIdle{assertEquals(unlocked,audio.soundEditor.preset!!.eqPoints.single());assertEquals(count,port.commands.size)}
        }finally{ui.runOnIdle{audio.dispose()}}
    }
    @Test fun nearestHandleWinsIndependentOfListOrderAndLockDoesNotStealItsTouch(){
        val a=SoundEqPoint("a",1000.0,3.0,10.0);val b=SoundEqPoint("b",1100.0,3.0,10.0)
        listOf(listOf(a,b),listOf(b,a)).forEach{points->
            val t=SoundEqTouch.target(points,"a",high(a)+Offset(1f,-12f),1718f,410f,12.0)!!
            assertEquals("a",t.point.id);assertEquals(false,t.low);assertFalse(t.lock)
        }
        val t=SoundEqTouch.target(listOf(a),"a",Offset(SoundEqTouch.x(a.frequencyHz,1718f),SoundEqTouch.y(a.gainDb,410f,12.0)-49),1718f,410f,12.0)!!
        assertTrue(t.lock)
    }
    @Test fun pendingRingDoesNotReplaceConfirmedPointOrItsVisibleHandles(){
        val confirmed=SoundEqPoint("a",1000.0,3.0,2.0)
        val pending=confirmed.copy(frequencyHz=6000.0,gainDb=-6.0)
        fun hit(at:Offset)=SoundEqTouch.target(listOf(confirmed),"a",at,1718f,410f,12.0,pending)
        val solid=hit(Offset(SoundEqTouch.x(confirmed.frequencyHz,1718f),SoundEqTouch.y(confirmed.gainDb,410f,12.0)))!!
        assertEquals(confirmed,solid.point);assertNull(solid.low);assertFalse(solid.lock)
        val ring=hit(Offset(SoundEqTouch.x(pending.frequencyHz,1718f),SoundEqTouch.y(pending.gainDb,410f,12.0)))!!
        assertEquals(pending,ring.point);assertNull(ring.low);assertFalse(ring.lock)
        val handle=hit(high(confirmed))!!
        assertEquals(confirmed,handle.point);assertEquals(false,handle.low)
        assertNull(hit(high(pending)),"No width handle is drawn on the pending ring")
        val lock=hit(Offset(SoundEqTouch.x(confirmed.frequencyHz,1718f),SoundEqTouch.y(confirmed.gainDb,410f,12.0)-49))!!
        assertEquals(confirmed,lock.point);assertTrue(lock.lock)
        assertEquals(pending,SoundEqTouch.moved(ring,Offset.Zero,1718f,410f,12.0))
    }
    @Test fun confirmedPointAndHandleCanBeGrabbedAgainBeforeAcknowledgement(){
        val original=SoundEqPoint("a",1000.0,3.0,2.0)
        val (audio,port)=mount(listOf(original),projected=true)
        try{
            ui.runOnIdle{audio.soundEditor.updatePoint(original.copy(frequencyHz=6000.0,gainDb=-6.0));assertTrue(audio.soundEditor.eqPending)}
            val solid=Offset(SoundEqTouch.x(original.frequencyHz,1718f),SoundEqTouch.y(original.gainDb,410f,12.0))
            ui.onNodeWithTag("sound-eq-chart").performTouchInput{swipe(solid,solid+Offset(0f,-25f),300)}
            ui.runOnIdle{
                assertEquals(original,audio.soundEditor.preset!!.eqPoints.single(),"Confirmation remains authoritative")
                assertEquals(original.frequencyHz,audio.soundEditor.eqTarget!!.frequencyHz)
                assertTrue(audio.soundEditor.eqTarget!!.gainDb>original.gainDb)
            }
            val start=high(original)
            ui.onNodeWithTag("sound-eq-chart").performTouchInput{swipe(start,start+Offset(10f,0f),300)}
            ui.runOnIdle{
                val requested=(port.commands.last()as AudioSoundCommand.Preview).preset!!.eqPoints.single()
                assertEquals(original.frequencyHz,requested.frequencyHz);assertEquals(original.gainDb,requested.gainDb)
                assertTrue(requested.widthHz>original.widthHz)
            }
        }finally{ui.runOnIdle{audio.dispose()}}
    }
    @Test fun staleRingCannotCreateTouchTargetForRemovedPoint(){
        val removed=SoundEqPoint("removed",6000.0,-6.0,2.0)
        assertNull(SoundEqTouch.target(emptyList(),null,Offset(SoundEqTouch.x(removed.frequencyHz,1718f),SoundEqTouch.y(removed.gainDb,410f,12.0)),1718f,410f,12.0,removed))
    }
    @Test fun cancelledTouchReleasesItsTargetAndRejectedEnableIsExplicit(){
        val a=SoundEqPoint("a",1000.0,3.0,10.0)
        val (audio,port)=mount(listOf(a))
        try{
            val start=high(a)
            ui.onNodeWithTag("sound-eq-chart").performTouchInput{down(start);cancel()}
            ui.runOnIdle{assertEquals(a,audio.soundEditor.preset!!.eqPoints.single());assertTrue(port.commands.isEmpty())}
            ui.onNodeWithTag("sound-eq-chart").performTouchInput{swipe(start,start+Offset(10f,0f),300)}
            ui.runOnIdle{assertTrue(audio.soundEditor.preset!!.eqPoints.single().widthHz>a.widthHz)}
            ui.onNodeWithTag("sound-eq-enabled").performClick()
            ui.runOnIdle{port.results.emit(AudioSoundResult(port.commands.size.toLong(),"REJECTED","EQ_COMBINED_GAIN_LIMIT"))}
            ui.onNodeWithTag("sound-eq-enabled").assertIsNotSelected()
            ui.onNodeWithContentDescription("EQ nicht aktiv").assertIsDisplayed()
        }finally{ui.runOnIdle{audio.dispose()}}
    }

}
