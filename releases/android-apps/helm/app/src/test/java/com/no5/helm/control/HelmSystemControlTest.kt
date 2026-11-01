package com.no5.helm.control

import com.no5.control.wire.BridgeWire
import com.no5.control.*
import android.os.Binder
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import com.no5.domain.*
import com.no5.helm.AudioNativeController
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.CopyOnWriteArrayList

/** Explicit fixture entry, never a connected Root socket or real audio write. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class HelmSystemControlTest {
    private val session = "0123456789abcdef0123456789abcdef"
    private var uptime = 100L
    private var sequence = 2L
    private fun control(owner: HelmSourceOwner, beforeAudioCreate: () -> Unit = {}, host: ControlBinderHost? = null) =
        HelmSystemControl(RuntimeEnvironment.getApplication(), owner,
        uptime = { uptime }, audioControllerFactory = {
            beforeAudioCreate()
            AudioNativeController(Dispatchers.Unconfined,
                now = { 10_000L }, automaticTicker = false, automaticVerification = false, source = it)
        }, hostFactory = { host ?: ControlBinderHost(it) })
    private fun start(c: HelmSystemControl, pkg: String = "com.android.settings") {
        c.beginAuthenticatedRootConnection()
        assertTrue(c.acceptFromAuthenticatedRoot(BridgeWire.Packet.hello(session)))
        assertTrue(c.acceptFromAuthenticatedRoot(BridgeWire.Packet.focus(session,2,
            BridgeWire.Focus(pkg,"actual-window-fixture",1,uptime,0,0))))
    }
    private fun key(c: HelmSystemControl, code: Int, action: Int = 0, repeat: Int = 0, flags: Int = 0,
        down: Long = ++uptime, epoch: Long = 1): Boolean {
        return c.acceptFromAuthenticatedRoot(BridgeWire.Packet.key(session, ++sequence,
            BridgeWire.Key(epoch,7,code,code,0,-1,257,action,repeat,flags,down,uptime,uptime)))
    }
    @Test fun foreignPanelUsesOneExistingAudioSourceAndRetainsDistinctDetents() {
        val factory=OwnerFixtureFactory();val owner=HelmSourceOwner(OwnerFixtureStore(),factory)
        factory.lastAudio!!.demoControls!!.loadFixture(AudioFixture.DEMO)
        control(owner).use { c ->
            start(c);assertTrue(key(c,364));assertEquals(1,factory.audioCommands)
            factory.lastAudio!!.demoControls!!.completePending(factory.lastAudio!!.state.value.pending!!.requestId,true)
            // Even identical metadata are a separate authenticated stream event. No invented debounce.
            assertTrue(key(c,364,down=uptime));assertEquals(2,factory.audioCommands)
            assertEquals(1,factory.creates)
        }
        assertEquals(0,factory.sessionCloses);owner.close();assertEquals(1,factory.sessionCloses)
    }
    @Test fun ownAppWithoutReadyClientNeverFallsThroughToAudio() {
        val factory=OwnerFixtureFactory();val owner=HelmSourceOwner(OwnerFixtureStore(),factory)
        factory.lastAudio!!.demoControls!!.loadFixture(AudioFixture.DEMO)
        control(owner).use { c -> start(c,"com.no5.home");assertFalse(key(c,364));assertEquals(0,factory.audioCommands) }
        owner.close()
    }
    @Test fun shortWaitsForUpAndLongNeverAddsShortOrRepeats() {
        val factory=OwnerFixtureFactory();val owner=HelmSourceOwner(OwnerFixtureStore(),factory)
        factory.lastAudio!!.demoControls!!.loadFixture(AudioFixture.DEMO)
        control(owner).use { c ->
            start(c);val down=++uptime
            assertFalse(key(c,368,down=down));assertEquals(0,factory.audioCommands)
            uptime++;assertTrue(key(c,368,action=1,down=down));assertEquals(1,factory.audioCommands)
            factory.lastAudio!!.demoControls!!.completePending(factory.lastAudio!!.state.value.pending!!.requestId,true)
            val longDown=++uptime;assertFalse(key(c,368,down=longDown))
            uptime++;assertFalse(key(c,368,repeat=1,flags=128,down=longDown)) // no assigned audio-long action
            uptime++;assertFalse(key(c,368,repeat=2,flags=128,down=longDown))
            uptime++;assertFalse(key(c,368,action=1,down=longDown));assertEquals(1,factory.audioCommands)
        };owner.close()
    }
    @Test fun sourceReloadCancelsCapturedHoldAndRejectsReplayWithoutSecondSink() {
        val factory=OwnerFixtureFactory();val owner=HelmSourceOwner(OwnerFixtureStore(),factory)
        factory.lastAudio!!.demoControls!!.loadFixture(AudioFixture.DEMO)
        control(owner).use { c ->
            start(c);val down=++uptime;assertFalse(key(c,368,down=down))
            assertTrue(owner.reload().applied);factory.lastAudio!!.demoControls!!.loadFixture(AudioFixture.DEMO)
            uptime++;assertFalse(key(c,368,action=1,down=down));assertEquals(0,factory.audioCommands)
            val replay=BridgeWire.Packet.key(session,sequence,BridgeWire.Key(1,7,364,364,0,-1,257,0,0,0,uptime,uptime,uptime))
            assertFalse(c.acceptFromAuthenticatedRoot(replay));assertFalse(key(c,364));assertEquals(0,factory.audioCommands)
        };owner.close()
    }
    @Test fun scopeCancelRetainsChannelButRequiresFreshScopeAndFreshGesture() {
        val factory=OwnerFixtureFactory();val owner=HelmSourceOwner(OwnerFixtureStore(),factory)
        factory.lastAudio!!.demoControls!!.loadFixture(AudioFixture.DEMO)
        control(owner).use { c ->
            start(c);val down=++uptime;assertFalse(key(c,368,down=down))
            uptime++;assertTrue(c.acceptFromAuthenticatedRoot(BridgeWire.Packet.cancel(session,++sequence)))
            assertTrue(c.isRootChannelValid)
            uptime++;assertTrue(c.acceptFromAuthenticatedRoot(BridgeWire.Packet.focus(session,++sequence,
                BridgeWire.Focus("com.android.settings","new-window-fixture",2,uptime,0,0))))
            val freshDown=++uptime;assertFalse(key(c,368,down=freshDown,epoch=2))
            uptime++;assertTrue(key(c,368,action=1,down=freshDown,epoch=2));assertEquals(1,factory.audioCommands)
            c.revokeRoot();assertFalse(c.isRootChannelValid)
            assertFalse(key(c,364,epoch=2));assertEquals(1,factory.audioCommands)
        };owner.close()
    }
    @Test fun cancelledChannelCannotReuseOriginalHeldGestureAfterNewScope() {
        val factory=OwnerFixtureFactory();val owner=HelmSourceOwner(OwnerFixtureStore(),factory)
        factory.lastAudio!!.demoControls!!.loadFixture(AudioFixture.DEMO)
        control(owner).use { c ->
            start(c);val down=++uptime;assertFalse(key(c,368,down=down))
            uptime++;assertTrue(c.acceptFromAuthenticatedRoot(BridgeWire.Packet.cancel(session,++sequence)))
            uptime++;assertTrue(c.acceptFromAuthenticatedRoot(BridgeWire.Packet.focus(session,++sequence,
                BridgeWire.Focus("com.android.settings","new-window-fixture",2,uptime,0,0))))
            uptime++;assertFalse(key(c,368,action=1,down=down,epoch=2));assertEquals(0,factory.audioCommands)
            assertFalse(c.isRootChannelValid)
        };owner.close()
    }

    @Test fun originalFocusDeadlineAtExactly250RejectsBeforeAudioConstruction() {
        val factory=OwnerFixtureFactory();val owner=HelmSourceOwner(OwnerFixtureStore(),factory)
        factory.lastAudio!!.demoControls!!.loadFixture(AudioFixture.DEMO)
        var constructions=0
        control(owner, beforeAudioCreate={constructions++}).use { c ->
            start(c);uptime=350
            assertFalse(c.acceptFromAuthenticatedRoot(BridgeWire.Packet.key(session,++sequence,
                BridgeWire.Key(1,7,364,364,0,-1,257,0,0,0,350,350,350))))
            assertEquals(0,constructions);assertEquals(0,factory.audioCommands)
            assertTrue(c.isRootChannelValid) // Wire age equality is admitted; action equality is expired.
        };owner.close()
    }

    @Test fun oneMillisecondBeforeOriginalFocusDeadlineCanStillAct() {
        val factory=OwnerFixtureFactory();val owner=HelmSourceOwner(OwnerFixtureStore(),factory)
        factory.lastAudio!!.demoControls!!.loadFixture(AudioFixture.DEMO)
        control(owner).use { c ->
            start(c);uptime=349
            assertTrue(c.acceptFromAuthenticatedRoot(BridgeWire.Packet.key(session,++sequence,
                BridgeWire.Key(1,7,364,364,0,-1,257,0,0,0,349,349,349))))
            assertEquals(1,factory.audioCommands)
        };owner.close()
    }

    @Test fun olderEventDeadlineWinsEvenWhenCopiedRecentlyAndFocusRefreshed() {
        val factory=OwnerFixtureFactory();val owner=HelmSourceOwner(OwnerFixtureStore(),factory)
        factory.lastAudio!!.demoControls!!.loadFixture(AudioFixture.DEMO)
        control(owner).use { c ->
            start(c);uptime=300
            assertTrue(c.acceptFromAuthenticatedRoot(BridgeWire.Packet.focus(session,++sequence,
                BridgeWire.Focus("com.android.settings","actual-window-fixture",1,300,0,0))))
            uptime=450
            assertFalse(c.acceptFromAuthenticatedRoot(BridgeWire.Packet.key(session,++sequence,
                BridgeWire.Key(1,7,364,364,0,-1,257,0,0,0,200,200,440))))
            assertEquals(0,factory.audioCommands)
        };owner.close()
    }

    @Test fun controllerCreationCannotRenewOriginalDeadline() {
        val factory=OwnerFixtureFactory();val owner=HelmSourceOwner(OwnerFixtureStore(),factory)
        factory.lastAudio!!.demoControls!!.loadFixture(AudioFixture.DEMO)
        var constructions=0
        control(owner, beforeAudioCreate={constructions++;uptime=350}).use { c ->
            start(c);uptime=200
            assertFalse(c.acceptFromAuthenticatedRoot(BridgeWire.Packet.key(session,++sequence,
                BridgeWire.Key(1,7,364,364,0,-1,257,0,0,0,200,200,200))))
            assertEquals(1,constructions);assertEquals(0,factory.audioCommands)
        };owner.close()
    }

    @Test fun controllerInitializationUptimeRollbackCannotAdmitAudio() {
        val factory=OwnerFixtureFactory();val owner=HelmSourceOwner(OwnerFixtureStore(),factory)
        factory.lastAudio!!.demoControls!!.loadFixture(AudioFixture.DEMO)
        control(owner,beforeAudioCreate={uptime=199}).use { c ->
            start(c);uptime=200
            assertFalse(c.acceptFromAuthenticatedRoot(BridgeWire.Packet.key(session,++sequence,
                BridgeWire.Key(1,7,364,364,0,-1,257,0,0,0,200,200,200))))
            assertEquals(0,factory.audioCommands)
        };owner.close()
    }

    @Test fun capabilityCheckThatConsumesRemainingTimeCannotReachFinalSubmit() {
        val factory=OwnerFixtureFactory();var availabilityCalls=0
        val delayed=SourceSessionFactory { config,identity ->
            val source=factory.create(config,identity)
            object : No5SourceSession by source {
                override val audio=object : AudioSourcePort by source.audio {
                    override fun available(command: AudioCommand): Boolean {
                        availabilityCalls++;uptime=350
                        return source.audio.available(command)
                    }
                }
            }
        }
        val owner=HelmSourceOwner(OwnerFixtureStore(),delayed)
        factory.lastAudio!!.demoControls!!.loadFixture(AudioFixture.DEMO)
        control(owner).use { c ->
            start(c);uptime=200
            assertFalse(c.acceptFromAuthenticatedRoot(BridgeWire.Packet.key(session,++sequence,
                BridgeWire.Key(1,7,364,364,0,-1,257,0,0,0,200,200,200))))
            assertEquals(1,availabilityCalls);assertEquals(0,factory.audioCommands)
        };owner.close()
    }

    @Test fun sourceReplacementDuringControllerCreationCannotMigrateTheAction() {
        val factory=OwnerFixtureFactory();val owner=HelmSourceOwner(OwnerFixtureStore(),factory)
        factory.lastAudio!!.demoControls!!.loadFixture(AudioFixture.DEMO)
        control(owner,beforeAudioCreate={assertTrue(owner.reload().applied)}).use { c ->
            start(c);uptime=200
            assertFalse(c.acceptFromAuthenticatedRoot(BridgeWire.Packet.key(session,++sequence,
                BridgeWire.Key(1,7,364,364,0,-1,257,0,0,0,200,200,200))))
            assertEquals(2,factory.creates);assertEquals(0,factory.audioCommands)
        };owner.close()
    }

    @Test fun saturatedLongDeadlineDoesNotOverflowAndMaxItselfIsExpired() {
        val factory=OwnerFixtureFactory();val owner=HelmSourceOwner(OwnerFixtureStore(),factory)
        factory.lastAudio!!.demoControls!!.loadFixture(AudioFixture.DEMO)
        uptime=Long.MAX_VALUE-100
        control(owner).use { c ->
            start(c);uptime=Long.MAX_VALUE-1
            assertTrue(c.acceptFromAuthenticatedRoot(BridgeWire.Packet.key(session,++sequence,
                BridgeWire.Key(1,7,364,364,0,-1,257,0,0,0,uptime,uptime,uptime))))
            val count=factory.audioCommands
            uptime=Long.MAX_VALUE
            assertFalse(c.acceptFromAuthenticatedRoot(BridgeWire.Packet.key(session,++sequence,
                BridgeWire.Key(1,7,364,364,0,-1,257,0,0,0,uptime,uptime,uptime))))
            assertEquals(count,factory.audioCommands)
        };owner.close()
    }

    @Test fun appEnvelopeUsesOriginalFocusExpiryInsteadOfANew250msLease() {
        appFixture { c, callback, ipc, factory ->
            uptime=200
            assertTrue(key(c,364,down=uptime));flushWorker(ipc);shadowOf(Looper.getMainLooper()).idle()
            val sent=callback.envelopes.single()
            assertEquals(200L,sent.issuedAtUptimeMs);assertEquals(350L,sent.expiresAtUptimeMs)
            assertEquals(0,factory.audioCommands)
        }
    }

    @Test fun asyncAudioDefaultAtOriginalDeadlineCannotReachAudioFactory() {
        var creates=0
        appFixture(beforeAudioCreate={creates++}) { c, callback, ipc, factory ->
            callback.disposition=ClientDisposition.AUDIO_DEFAULT;uptime=200
            assertTrue(key(c,364,down=uptime));flushWorker(ipc)
            uptime=350;shadowOf(Looper.getMainLooper()).idle()
            assertEquals(1,callback.envelopes.size);assertEquals(0,creates);assertEquals(0,factory.audioCommands)
        }
    }

    @Test fun asyncAudioDefaultControllerCreationCannotSpendTheRemainingLease() {
        appFixture(beforeAudioCreate={uptime=350}) { c, callback, ipc, factory ->
            callback.disposition=ClientDisposition.AUDIO_DEFAULT;uptime=200
            assertTrue(key(c,364,down=uptime));flushWorker(ipc)
            uptime=349;shadowOf(Looper.getMainLooper()).idle()
            assertEquals(0,factory.audioCommands)
        }
    }

    @Test fun asyncAudioDefaultRollbackCannotBeYoungerThanItsIssuedEnvelope() {
        appFixture { c, callback, ipc, factory ->
            callback.disposition=ClientDisposition.AUDIO_DEFAULT;uptime=200
            assertTrue(key(c,364,down=uptime));flushWorker(ipc)
            uptime=199;shadowOf(Looper.getMainLooper()).idle()
            assertEquals(0,factory.audioCommands)
        }
    }

    private fun appFixture(beforeAudioCreate: () -> Unit = {},
        test: (HelmSystemControl, AppCallback, BoundedControlIpc, OwnerFixtureFactory) -> Unit) {
        val factory=OwnerFixtureFactory();val owner=HelmSourceOwner(OwnerFixtureStore(),factory)
        factory.lastAudio!!.demoControls!!.loadFixture(AudioFixture.DEMO)
        val ipc=BoundedControlIpc()
        val host=ControlBinderHost("fixture-host",0,
            { uid -> CallerAdmissionPolicy.verify(uid,0,listOf("com.no5.home"),true,true) },{10001},ipc,{uptime})
        try {
            control(owner,beforeAudioCreate,host).use { c ->
                start(c,"com.no5.home")
                val callback=AppCallback()
                ControlRemote(host.binder).register("com.no5.home",callback,
                    ClientState(1,true,true,setOf(NormalizedKnobInput.LEFT_CW)))
                flushWorker(ipc)
                // Establish the ready-client binding cutoff before the fixture event occurs.
                uptime=101
                assertTrue(c.acceptFromAuthenticatedRoot(BridgeWire.Packet.focus(session,++sequence,
                    BridgeWire.Focus("com.no5.home","actual-window-fixture",1,100,0,0))))
                test(c,callback,ipc,factory)
            }
        } finally { ipc.close();owner.close() }
    }
    private fun flushWorker(ipc: BoundedControlIpc) {
        val barrier=FutureTask { true }
        assertTrue(ipc.execute(barrier));assertTrue(barrier.get(1,TimeUnit.SECONDS))
    }
    private class AppCallback : Binder() {
        var disposition=ClientDisposition.HANDLED
        val envelopes=CopyOnWriteArrayList<KnobEnvelope>()
        override fun onTransact(code: Int,data: Parcel,reply: Parcel?,flags: Int): Boolean {
            data.enforceInterface(SystemControlContract.CALLBACK_DESCRIPTOR);requireNotNull(reply);reply.writeNoException()
            when(code) {
                SystemControlContract.CALLBACK_STATE -> {
                    ControlParcel.readAdmission(data);ControlParcel.end(data);reply.writeInt(1)
                }
                SystemControlContract.CALLBACK_INPUT -> {
                    val value=ControlParcel.readEnvelope(data);ControlParcel.end(data);envelopes+=value
                    reply.writeInt(disposition.wireCode)
                }
                else -> return false
            }
            return true
        }
    }
}
