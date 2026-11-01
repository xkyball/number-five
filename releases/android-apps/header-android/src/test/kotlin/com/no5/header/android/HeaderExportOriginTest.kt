package com.no5.header.android

import android.os.*
import com.no5.control.*
import com.no5.header.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.util.Collections

/** Simulated Binder calls/death thread; actual kernel credentials remain a separate admission. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class HeaderExportOriginTest {
    @Test fun publishAgingStaleTokenAndConcurrentDeathKeepOriginalOriginAndAcl() {
        val clock = HeaderFixtureClock()
        val events = Collections.synchronizedList(mutableListOf<HeaderRegistryOrigin>())
        val ipc = BoundedControlIpc()
        val h = HeaderBinderHost("host-a", 0,
            { CallerAdmissionPolicy.verify(it, 0, listOf(HeaderApps.HELM), true, true) }, { 10001 }, { 111 },
            clock, Handler(Looper.getMainLooper()), ipc, {}, events::add)
        try {
            h.observeForeground(ObservedForeground(HeaderApps.HELM, 3, true, 0))
            val remote = HeaderRemote(h.binder); val cb = HeaderFixtureCallback()
            val grant = remote.register(HeaderApps.HELM, cb, HeaderPageState(HeaderPage.AUDIO, 2, "source-a", true, true))
            val raw = HeaderSnapshotV1(HeaderPublication(HeaderApps.HELM, grant.producerSession, 3, 2,
                "source-a", 0, 100, 1100), HeaderPage.AUDIO, HeaderTheme.DAY, HeaderProfile.MARINE_SAFETY,
                extras = HeaderExtras(portRpm = HeaderMeasurement(0.0, HeaderUnit.RPM, "engine", 10_000, HeaderQuality.LIVE)))
            assertTrue(remote.publish(grant, raw).accepted)
            val original = events.last().snapshot!! // Parcel reconstructed original, before any at(...).
            clock.epoch = 60_000
            val count = events.size
            assertEquals(HeaderQuality.LOST, h.current().snapshot!!.extras.portRpm!!.quality)
            assertEquals(count, events.size)
            assertEquals(HeaderQuality.LIVE, original.extras.portRpm!!.quality)
            assertEquals(10_000L, original.extras.portRpm!!.sourceObservedAtEpochMs)
            val nextPage = remote.state(grant, cb, HeaderPageState(HeaderPage.SONAR, 3, "source-b", true, true))
            assertNull(events.last().snapshot)
            val clearedCount = events.size
            assertFalse(remote.publish(grant, raw.copy(publication = raw.publication.copy(sequence = 1))).accepted)
            assertEquals(clearedCount, events.size) // Old admission never clears newer scope.
            assertTrue(remote.publish(nextPage, raw.copy(pageId = HeaderPage.SONAR,
                publication = raw.publication.copy(pageGeneration = 3, sourceSessionId = "source-b", sequence = 1))).accepted)
            val death = Thread { cb.die() }; death.start()
            h.observeForeground(ObservedForeground(HeaderApps.MAP, 4, true, 0))
            death.join(1000); assertFalse(death.isAlive)
            assertNull(h.current().snapshot)
            assertNull(events.last().snapshot)
            assertTrue(events.zipWithNext().all { (a, b) -> a.revision < b.revision })
        } finally { h.close(); shadowOf(Looper.getMainLooper()).idle(); ipc.close() }
    }
}
