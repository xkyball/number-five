package com.no5.helm.control

import android.os.Looper
import com.no5.control.wire.BridgeWire
import com.no5.domain.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class HelmAudioEffectOwnerTest {
    @Test fun actualBorrowedLeaseBindsExactOwnerAndReloadRetiresIt() {
        val factory = OwnerFixtureFactory(); val owner = HelmSourceOwner(OwnerFixtureStore(), factory)
        factory.lastAudio!!.demoControls!!.loadFixture(AudioFixture.DEMO)
        val port = owner.borrowAudio(); val generation = owner.sessionGeneration
        val effect = HelmAudioEffectLease(350, generation, port.identity.sessionId, 100, { 100 }, { true })
        val result = HelmAudioEffectContext.withLease(effect) { port.submit(AudioCommand.Transport(TransportAction.NEXT)) }
        assertTrue(result.accepted); assertTrue(effect.claimQueue(port.identity.sessionId))
        assertTrue(owner.reload().applied)
        assertTrue(effect.dropQueuedNow(port.identity.sessionId)); assertFalse(effect.beginSend(port.identity.sessionId))
        assertEquals(2, factory.creates); assertEquals(1, factory.sessionCloses)
        port.close(); owner.disposeService(); assertEquals(2, factory.sessionCloses); assertEquals(1, factory.factoryCloses)
    }
    @Test fun wrongOwnerGenerationNeverReachesOriginalSubmitAndContextUnwindsAfterError() {
        val factory = OwnerFixtureFactory(); val owner = HelmSourceOwner(OwnerFixtureStore(), factory)
        factory.lastAudio!!.demoControls!!.loadFixture(AudioFixture.DEMO)
        val port = owner.borrowAudio()
        val wrong = HelmAudioEffectLease(350, owner.sessionGeneration + 1, port.identity.sessionId, 100, { 100 }, { true })
        val outcome = HelmAudioEffectContext.withLease(wrong) { port.submit(AudioCommand.Transport(TransportAction.NEXT)) }
        assertEquals(AudioCommandStatus.REJECTED, outcome.status); assertEquals(0, factory.audioCommands)
        try { HelmAudioEffectContext.withLease(wrong) { throw AssertionError("Fixture failure") } }
        catch (_: AssertionError) { }
        assertNull(HelmAudioEffectContext.current()); port.close(); owner.disposeService()
    }
    @Test fun closeCallbackErrorStillFencesRootAndDoesNotDisposeBorrowedSourceOwner() {
        assertSame(Looper.getMainLooper(), Looper.myLooper())
        val factory = OwnerFixtureFactory(); val owner = HelmSourceOwner(OwnerFixtureStore(), factory)
        var throwOnForeground = false
        val control = HelmSystemControl(RuntimeEnvironment.getApplication(), owner, uptime = { 100 },
            onForeground = { if (throwOnForeground) throw AssertionError("Fixture close callback") })
        control.beginAuthenticatedRootConnection()
        assertTrue(control.acceptFromAuthenticatedRoot(BridgeWire.Packet.hello("0123456789abcdef0123456789abcdef")))
        throwOnForeground = true
        try { control.close(); fail("Expected original failure") } catch (expected: AssertionError) {
            assertEquals("Fixture close callback", expected.message)
        }
        assertFalse(control.isRootChannelValid); assertFalse(owner.isDisposed)
        control.close() // Reentrancy/idempotency cannot attempt the failed cleanup a second time.
        assertEquals(0, factory.sessionCloses); owner.disposeService(); assertEquals(1, factory.sessionCloses)
    }
}
