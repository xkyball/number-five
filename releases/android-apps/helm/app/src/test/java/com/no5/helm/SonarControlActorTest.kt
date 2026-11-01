package com.no5.helm
import android.os.*
import com.no5.control.NormalizedKnobInput.*
import com.no5.domain.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.lumina.control.commands.v1.CommandRing

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29])
@LooperMode(LooperMode.Mode.PAUSED)
class SonarControlActorTest {
    @Test fun admissionWhileMainIsBlockedAccumulatesDetentsWithoutTouchingRawHistory() {
        val mock=DeterministicMockSonarSource(now={1_000_000L});val controller=SonarNativeController(mock){1_000_000L};controller.start()
        val actor=SonarControlActor(controller);actor.observe();val raw=controller.model.latest!!.samples.copyOf()
        val before=controller.snapshot.display;val gain=before.gainDb;val action=actor.action(RIGHT_CW)
        try {
            val first=action.submit(1,SystemClock.uptimeMillis());val last=action.submit(2,SystemClock.uptimeMillis())
            assertTrue(first>0&&last>first);assertEquals(gain,controller.snapshot.display.gainDb,0.0)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(gain+2,controller.snapshot.display.gainDb,0.0);assertArrayEquals(raw,controller.model.latest!!.samples)
            assertEquals(before.rangeMode,controller.snapshot.display.rangeMode);assertEquals(before.rangeM,controller.snapshot.display.rangeM)
            assertEquals(before.cropStart,controller.snapshot.display.cropStart,0.0);assertEquals(before.cropEnd,controller.snapshot.display.cropEnd,0.0)
            val reply=Parcel.obtain()
            try {assertTrue(action.writeResult(first,reply));reply.setDataPosition(0);assertEquals(1,reply.readInt());assertEquals(CommandRing.State.SUPERSEDED.ordinal,reply.readInt())}finally{reply.recycle()}
        }finally{actor.close();controller.dispose()}
    }
    @Test fun focusLossCancelsUnappliedTargetsAndVolumeEquivalentGainBoundaryIsNormal() {
        val mock=DeterministicMockSonarSource(now={1_000_000L});val controller=SonarNativeController(mock){1_000_000L};controller.start()
        controller.display {it.copy(gainDb=36.0)};val actor=SonarControlActor(controller);actor.observe()
        try {
            val action=actor.action(RIGHT_CW);val id=action.submit(1,SystemClock.uptimeMillis())
            assertTrue(id>0);shadowOf(Looper.getMainLooper()).idle();assertEquals(36.0,controller.snapshot.display.gainDb,0.0)
            actor.action(LEFT_CW).submit(2,SystemClock.uptimeMillis());actor.cancel();val before=controller.snapshot.display
            shadowOf(Looper.getMainLooper()).idle();assertEquals(before,controller.snapshot.display)
        }finally{actor.close();controller.dispose()}
    }
}
