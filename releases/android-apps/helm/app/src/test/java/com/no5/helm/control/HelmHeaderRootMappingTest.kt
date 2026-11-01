package com.no5.helm.control

import com.no5.control.wire.BridgeWire
import com.no5.control.ObservedForeground
import com.no5.domain.AudioFixture
import com.no5.helm.AudioNativeController
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class HelmHeaderRootMappingTest {
    private val session = "0123456789abcdef0123456789abcdef"
    @Test fun realAcceptedPacketHookCapturesSessionAndRefreshWithoutEquatingEpochs() {
        val factory = OwnerFixtureFactory(); val owner = HelmSourceOwner(OwnerFixtureStore(), factory)
        var now = 100L
        val roots = mutableListOf<HelmHeaderFocusMapping>(); val foreground = mutableListOf<ObservedForeground>()
        HelmSystemControl(RuntimeEnvironment.getApplication(), owner, uptime = { now },
            onForeground = foreground::add, onRootHeaderScope = roots::add).use { c ->
            c.beginAuthenticatedRootConnection()
            assertTrue(c.acceptFromAuthenticatedRoot(BridgeWire.Packet.hello(session)))
            assertEquals(session, roots.last().rootControlSession); assertNull(roots.last().rootFocus)
            assertTrue(c.acceptFromAuthenticatedRoot(BridgeWire.Packet.focus(session, 2,
                BridgeWire.Focus("com.no5.helm", "window-a", 71, now, 0, 0))))
            val first = roots.last(); assertEquals(71L, first.rootFocus!!.rootEpoch)
            assertNotEquals(71L, first.hostForegroundEpoch)
            val count = foreground.size; now = 150
            assertTrue(c.acceptFromAuthenticatedRoot(BridgeWire.Packet.focus(session, 3,
                BridgeWire.Focus("com.no5.helm", "window-a", 71, now, 0, 0))))
            assertTrue(first.sameScope(roots.last()))
            assertEquals(150L, roots.last().rootFocus!!.observedUptimeMs)
            assertEquals(count, foreground.size)
            assertTrue(c.acceptFromAuthenticatedRoot(BridgeWire.Packet.cancel(session, 4)))
            assertEquals(session, roots.last().rootControlSession); assertNull(roots.last().rootFocus)
            assertTrue(c.isRootChannelValid)
            c.revokeRoot(); assertNull(roots.last().rootControlSession)
        }; owner.close()
    }
    @Test fun failingReadOnlyHookRetiresExportWithoutChangingControlOrAudioAdmission() {
        val factory = OwnerFixtureFactory(); val owner = HelmSourceOwner(OwnerFixtureStore(), factory)
        factory.lastAudio!!.demoControls!!.loadFixture(AudioFixture.DEMO)
        var now = 100L; var failures = 0; val observed = mutableListOf<ObservedForeground>()
        HelmSystemControl(RuntimeEnvironment.getApplication(), owner, uptime = { now },
            audioControllerFactory = { AudioNativeController(Dispatchers.Unconfined, now = { 10_000L },
                automaticTicker = false, automaticVerification = false, source = it) },
            onForeground = observed::add,
            onRootHeaderScope = { if (it.rootFocus != null) throw IllegalStateException("export failure fixture") },
            onRootHeaderFailure = { failures++; throw IllegalStateException("broken failure sink fixture") }).use { c ->
            c.beginAuthenticatedRootConnection(); assertTrue(c.acceptFromAuthenticatedRoot(BridgeWire.Packet.hello(session)))
            assertTrue(c.acceptFromAuthenticatedRoot(BridgeWire.Packet.focus(session, 2,
                BridgeWire.Focus("com.android.settings", "window-a", 71, now, 0, 0))))
            assertFalse(c.isRootHeaderExportUsable); assertEquals(1, failures)
            assertEquals("com.android.settings", observed.last().packageName)
            now++
            assertTrue(c.acceptFromAuthenticatedRoot(BridgeWire.Packet.key(session, 3,
                BridgeWire.Key(71, 7, 364, 364, 0, -1, 257, 0, 0, 0, now, now, now))))
            assertEquals(1, factory.audioCommands); assertEquals(1, factory.creates)
            assertTrue(c.acceptFromAuthenticatedRoot(BridgeWire.Packet.cancel(session, 4)))
            assertTrue(c.isRootChannelValid); assertNull(observed.last().packageName)
            assertEquals(1, failures)
        }; owner.close()
    }
}
