package com.no5.helm

import android.os.Looper
import com.no5.helm.control.HelmSourceOwner
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 35])
@LooperMode(LooperMode.Mode.PAUSED)
class MainSourceDeliveryTest {
    @Test fun aBlockedMainKeepsOnlyTheLatestObservationPerFeed() {
        val helm = MainSourceDelivery(); val weather = MainSourceDelivery()
        var calls = 0; var value = -1; var weatherValue = -1
        val worker = Thread {
            repeat(100_000) { i ->
                helm.deliver { calls++; value = i }
                weather.deliver { calls++; weatherValue = i }
            }
        }
        worker.start(); worker.join(5_000); assertFalse(worker.isAlive)
        assertEquals(0, calls)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(2, calls); assertEquals(99_999, value); assertEquals(99_999, weatherValue)
        helm.close(); weather.close()
    }

    @Test fun disposingAFeedDropsItsQueuedClosureAndRejectsLaterValues() {
        val delivery = MainSourceDelivery(); var calls = 0
        val worker = Thread { delivery.deliver { calls++ } }
        worker.start(); worker.join(2_000); delivery.close()
        delivery.deliver { calls++ }
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, calls)
    }

    @Test fun anImmediateMainValueCannotBeOverwrittenByAnOlderQueuedValue() {
        val delivery = MainSourceDelivery(); var value = 0
        val worker = Thread { delivery.deliver { value = 1 } }
        worker.start(); worker.join(2_000)
        delivery.deliver { value = 2 }
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(2, value)
        delivery.close()
    }

    @Test fun workerObservationCanSafelyUseAMainOwnedLeaseAndRetainsItsSourceTime() {
        val delivery = MainSourceDelivery()
        val value = AtomicReference<Long?>()
        val failure = AtomicReference<Throwable?>()
        val observed = 1_700_000_000_123L
        val worker = Thread {
            try {
                delivery.deliver { HelmSourceOwner.mainThread(); value.set(observed) }
            } catch (error: Throwable) { failure.set(error) }
        }
        worker.start(); worker.join(2_000)
        assertFalse(worker.isAlive); assertNull(failure.get()); assertNull(value.get())
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(observed, value.get())
    }

    @Test fun aRetiredSubscriptionDoesNotPublishAQueuedWorkerObservation() {
        val delivery = MainSourceDelivery(); val active = AtomicBoolean(true); val published = AtomicBoolean(false)
        val worker = Thread { delivery.deliver { if (active.get()) { HelmSourceOwner.mainThread(); published.set(true) } } }
        worker.start(); worker.join(2_000); active.set(false)
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(published.get())
    }

    @Test fun mainThreadDeliveryKeepsTheImmediateInitialFeedRead() {
        val value = AtomicBoolean(false)
        MainSourceDelivery().deliver { HelmSourceOwner.mainThread(); value.set(true) }
        assertTrue(value.get())
    }
}
