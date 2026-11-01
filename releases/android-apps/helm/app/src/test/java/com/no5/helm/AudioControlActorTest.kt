package com.no5.helm

import android.os.*
import com.no5.control.NormalizedKnobInput.*
import com.no5.domain.*
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.lumina.control.commands.v1.CommandRing
import java.time.Duration

@RunWith(RobolectricTestRunner::class) @Config(sdk=[29]) @LooperMode(LooperMode.Mode.PAUSED)
class AudioControlActorTest {
    private fun fixture():Pair<MockAudioSource,AudioNativeController> {
        val source=MockAudioSource(now={1_000_000L}).also{it.loadFixture(AudioFixture.DEMO)}
        return source to AudioNativeController(Dispatchers.Unconfined,{1_000_000L},automaticTicker=false,automaticVerification=false,source=source)
    }
    @Test fun burstIsAcceptedBeforeMainRunsAndMuteRemainsAnOrderedBarrier() {
        val (source,controller)=fixture();val actor=AudioControlActor(controller);actor.observe()
        try {
            val action=actor.action(LEFT_CW)
            val first=action.submit(1,SystemClock.uptimeMillis())
            repeat(9){action.submit(it+2L,SystemClock.uptimeMillis())}
            actor.action(LEFT_PUSH).submit(11,SystemClock.uptimeMillis())
            assertNull(source.state.value.pending)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(AudioCommand.Fader(AudioChannel.MASTER,-14.0),source.state.value.pending!!.command)
            source.completePending(source.state.value.pending!!.requestId,true)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20))
            assertEquals(AudioCommand.Mute(AudioChannel.MASTER,true),source.state.value.pending!!.command)
            val parcel=Parcel.obtain()
            try{assertTrue(action.writeResult(first,parcel));parcel.setDataPosition(0);assertEquals(1,parcel.readInt());assertEquals(CommandRing.State.SUPERSEDED.ordinal,parcel.readInt())}finally{parcel.recycle()}
        }finally{actor.close();controller.dispose()}
    }
    @Test fun saturationAccumulatesToLimitAndFocusLossDropsUnstartedGoals() {
        val (source,controller)=fixture();val actor=AudioControlActor(controller);actor.observe()
        try {
            repeat(100){actor.action(LEFT_CW).submit(it+1L,SystemClock.uptimeMillis())}
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(AudioCommand.Fader(AudioChannel.MASTER,-6.0),source.state.value.pending!!.command)
            source.completePending(source.state.value.pending!!.requestId,true)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20));actor.observe()
            val atLimit=actor.action(LEFT_CW);val id=atLimit.submit(101,SystemClock.uptimeMillis())
            shadowOf(Looper.getMainLooper()).idle();assertNull(source.state.value.pending)
            val parcel=Parcel.obtain()
            try{assertTrue(atLimit.writeResult(id,parcel));parcel.setDataPosition(0);parcel.readInt();assertEquals(CommandRing.State.APPLIED.ordinal,parcel.readInt());assertEquals("BOUND_REACHED",parcel.readString())}finally{parcel.recycle()}
            actor.action(LEFT_CCW).submit(102,SystemClock.uptimeMillis());actor.cancel()
            shadowOf(Looper.getMainLooper()).idle();assertNull(source.state.value.pending)
        }finally{actor.close();controller.dispose()}
    }
    @Test fun selectedPhysicalFaderIsIndependentOfMasterAndLocalAliases() {
        val (source,controller)=fixture();controller.openPanel("mixer")
        controller.selectKnobFader(controller.faderControl(AudioChannel.SUB)!!)
        val actor=AudioControlActor(controller);actor.observe()
        try{
            actor.action(LEFT_CCW).submit(1,SystemClock.uptimeMillis());shadowOf(Looper.getMainLooper()).idle()
            assertEquals(AudioCommand.Fader(AudioChannel.SUB,-7.0),source.state.value.pending!!.command)
            assertEquals(-24.0,source.state.value.dsp.faders[AudioChannel.MASTER]!!.db!!,0.0)
        }finally{actor.close();controller.dispose()}
    }
    @Test fun guardedNavigationNeverClaimsAppliedOrHoldsTheAudioRing() {
        val (source,controller)=fixture();controller.openPanel("sound-preset")
        var decision:(()->Unit)?=null
        controller.panelNavigationGuard={decision=it}
        val actor=AudioControlActor(controller);actor.observe()
        try {
            val menu=actor.action(LEFT_LONG_PUSH)
            val id=menu.submit(1,SystemClock.uptimeMillis());shadowOf(Looper.getMainLooper()).idle()
            assertNotNull(decision);assertEquals("sound-preset",controller.panel)
            fun state():Int {val p=Parcel.obtain();try{assertTrue(menu.writeResult(id,p));p.setDataPosition(0);assertEquals(1,p.readInt());return p.readInt()}finally{p.recycle()}}
            assertEquals(CommandRing.State.REJECTED.ordinal,state())
            assertEquals(com.no5.header.artwork.No5MomentFeedback.Phase.FAILED,com.no5.header.artwork.No5MomentFeedback.current()?.phase)
            actor.action(LEFT_CCW).submit(2,SystemClock.uptimeMillis());shadowOf(Looper.getMainLooper()).idle()
            assertEquals(AudioCommand.Fader(AudioChannel.MASTER,-25.0),source.state.value.pending!!.command)
            // Explicit user confirmation may navigate, but cannot retroactively apply the knob action.
            decision!!.invoke();assertEquals("mixer",controller.panel)
            assertEquals(CommandRing.State.REJECTED.ordinal,state())
        }finally{actor.close();controller.dispose()}
    }
    @Test fun retiredGuardContinuationCannotOpenAPanelInANewControlSession() {
        val (_,controller)=fixture();controller.openPanel("sound-preset")
        var decision:(()->Unit)?=null;controller.panelNavigationGuard={decision=it}
        val actor=AudioControlActor(controller);actor.observe()
        try {
            actor.action(LEFT_LONG_PUSH).submit(1,SystemClock.uptimeMillis());shadowOf(Looper.getMainLooper()).idle()
            actor.cancel();decision!!.invoke();assertEquals("sound-preset",controller.panel)
        }finally{actor.close();controller.dispose()}
    }

}
