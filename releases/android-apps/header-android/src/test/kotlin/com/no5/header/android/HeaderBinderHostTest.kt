package com.no5.header.android

import android.os.*
import com.no5.control.*
import com.no5.header.*
import org.junit.Assert.*
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration

internal class HeaderFixtureClock(var elapsed: Long = 100, var epoch: Long = 10000) : HeaderClock {
    override fun elapsedRealtimeMs() = elapsed
    override fun epochMs() = epoch
}
internal class HeaderFixtureCallback : Binder() {
    private var recipient: IBinder.DeathRecipient? = null
    override fun linkToDeath(recipient: IBinder.DeathRecipient, flags: Int) { this.recipient = recipient }
    override fun unlinkToDeath(recipient: IBinder.DeathRecipient, flags: Int): Boolean = true
    fun die() { recipient?.binderDied() }
    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        data.enforceInterface(HeaderAndroidContract.CALLBACK_DESCRIPTOR)
        HeaderParcel.readAdmission(data); HeaderParcel.end(data)
        requireNotNull(reply).writeNoException(); HeaderParcel.boolean(reply, true); return true
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class HeaderBinderHostTest {
    private val ipc = BoundedControlIpc()
    private val hosts = mutableListOf<HeaderBinderHost>()
    private val clock = HeaderFixtureClock()
    private var pid = 111
    private var uid = 10001
    private var packageName = HeaderApps.HELM
    private val states = mutableListOf<HeaderConsumerState>()
    private fun host(verifier: ((Int) -> CallerIdentity)? = null) = HeaderBinderHost("controller", 0,
        verifier ?: { CallerAdmissionPolicy.verify(it, 0, listOf(packageName), true, true) }, { uid }, { pid },
        clock, Handler(Looper.getMainLooper()), ipc, { states.add(it) }).also { hosts.add(it) }
    @After fun cleanup() { hosts.forEach { it.close() }; shadowOf(Looper.getMainLooper()).idle(); ipc.close() }
    private fun page(id: HeaderPage = HeaderPage.AUDIO, gen: Long = 1, source: String? = "source-a") =
        HeaderPageState(id, gen, source, true, true)
    private fun frame(a: HeaderAdmission, sequence: Long = 0, title: String = "current") = HeaderSnapshotV1(
        HeaderPublication(packageName, a.producerSession, a.foregroundEpoch, a.pageGeneration, a.sourceSessionId,
            sequence, clock.elapsed, clock.elapsed + 1000), a.pageId, HeaderTheme.DAY,
        HeaderApps.profile(packageName, a.pageId)!!, HeaderContext(title))

    @Test fun kernelIdentityIsVerifiedBeforeAnyParcelReadEvenForMalformedCaller() {
        var verified = false
        val h = host { verified = true; throw SecurityException("Denied caller") }
        val data = Parcel.obtain(); val reply = Parcel.obtain()
        try {
            // Native Binder fixtures marshal application exceptions into the reply. The caller
            // observes the identity denial through readException, as HeaderRemote already does.
            assertTrue(h.binder.transact(HeaderAndroidContract.PUBLISH, data, reply, 0))
            val denial = assertThrows(SecurityException::class.java) { reply.readException() }
            assertEquals("Denied caller", denial.message)
            assertTrue(verified); assertEquals(0, data.dataPosition()); assertNull(h.current().snapshot)
        } finally { data.recycle(); reply.recycle() }
    }
    @Test fun appClaimedFocusCannotSetOSFocusAndWrongUserOrDisplayNeverAdmits() {
        val h = host(); val remote = HeaderRemote(h.binder); val cb = HeaderFixtureCallback()
        var a = remote.register(packageName, cb, page())
        assertFalse(a.ready); assertFalse(remote.publish(a, frame(a)).accepted)
        for (scope in listOf(ObservedForeground("foreign", 1, true, 0), ObservedForeground(packageName, 2, false, 0),
            ObservedForeground(packageName, 3, true, 1), ObservedForeground(packageName, 4, true, 0, 1))) {
            h.observeForeground(scope); a = remote.admission(a, cb); assertFalse(a.ready)
        }
        h.observeForeground(ObservedForeground(packageName, 5, true, 0))
        a = remote.admission(a, cb); assertTrue(a.ready); assertTrue(remote.publish(a, frame(a)).accepted)
    }
    @Test fun stalePageSourceScopeAndOldProcessCannotEraseTheNewOwner() {
        val h = host(); h.observeForeground(ObservedForeground(packageName, 1, true, 0))
        val remote = HeaderRemote(h.binder); val firstCb = HeaderFixtureCallback()
        val first = remote.register(packageName, firstCb, page())
        assertTrue(remote.publish(first, frame(first)).accepted)
        val next = remote.state(first, firstCb, page(HeaderPage.SONAR, 2, "source-b"))
        assertTrue(remote.publish(next, frame(next, 1, "new page")).accepted)
        assertFalse(remote.publish(first, frame(first, 2, "old page")).accepted)
        assertEquals("new page", h.current().snapshot!!.context.title)
        assertThrows(IllegalArgumentException::class.java) { remote.state(first, firstCb, page()) }
        assertEquals("new page", h.current().snapshot!!.context.title)
        pid = 222
        val freshCb = HeaderFixtureCallback(); val fresh = remote.register(packageName, freshCb, page())
        assertTrue(remote.publish(fresh, frame(fresh, 3, "new process")).accepted)
        pid = 111
        assertFalse(remote.deregister(first, firstCb)); firstCb.die()
        assertFalse(remote.publish(first, frame(first, 4)).accepted)
        assertEquals("new process", h.current().snapshot!!.context.title)
    }
    @Test fun wrongPublicationTokensNeverClearNewerValidFrame() {
        val h = host(); h.observeForeground(ObservedForeground(packageName, 1, true, 0))
        val remote = HeaderRemote(h.binder); val a = remote.register(packageName, HeaderFixtureCallback(), page())
        val v = frame(a); assertTrue(remote.publish(a, v).accepted)
        assertFalse(remote.publish(a, v.copy(publication = v.publication.copy(sourceSessionId = "late", sequence = 1))).accepted)
        assertEquals("current", h.current().snapshot!!.context.title)
    }
    @Test fun pauseDeathAndTimerExpirationClearExtrasButKeepSystemNavigation() {
        val h = host(); h.observeForeground(ObservedForeground(packageName, 1, true, 0))
        val remote = HeaderRemote(h.binder); val cb = HeaderFixtureCallback()
        val a = remote.register(packageName, cb, page()); assertTrue(remote.publish(a, frame(a)).accepted)
        shadowOf(Looper.getMainLooper()).idle(); assertNotNull(states.last().snapshot)
        clock.elapsed = 1200
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250))
        assertNull(states.last().snapshot); assertEquals(HeaderClearReason.EXPIRED, states.last().reason)
        assertEquals(3, states.last().navigation(SystemNavigationAction.LOGO_HOME))
        cb.die(); assertNull(h.current().snapshot)
    }
    @Test fun sameRegistrationStaleLifecycleCannotClearAfterTrustedForegroundEpochChange() {
        val h = host(); h.observeForeground(ObservedForeground(packageName, 1, true, 0))
        val remote = HeaderRemote(h.binder); val cb = HeaderFixtureCallback()
        val old = remote.register(packageName, cb, page())
        h.observeForeground(ObservedForeground(packageName, 2, true, 0))
        val next = remote.admission(old, cb); assertTrue(remote.publish(next, frame(next)).accepted)
        assertThrows(IllegalArgumentException::class.java) { remote.state(old, cb, page().copy(resumed = false)) }
        assertNotNull(h.current().snapshot)
    }
    @Test fun lateAndDuplicateSequenceCannotEraseNewerFrameInTheSameScope() {
        val h = host(); h.observeForeground(ObservedForeground(packageName, 1, true, 0))
        val remote = HeaderRemote(h.binder); val a = remote.register(packageName, HeaderFixtureCallback(), page())
        assertTrue(remote.publish(a, frame(a, 5, "newer")).accepted)
        assertEquals(HeaderClearReason.OUT_OF_ORDER, remote.publish(a, frame(a, 4, "late")).reason)
        assertEquals(HeaderClearReason.OUT_OF_ORDER, remote.publish(a, frame(a, 5, "duplicate")).reason)
        assertEquals("newer", h.current().snapshot!!.context.title)
    }
}
