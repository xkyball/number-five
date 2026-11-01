package com.no5.helm.control

import android.os.Looper
import android.os.SystemClock
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 35])
@LooperMode(LooperMode.Mode.PAUSED)
class HelmSourceReconnectCoordinatorTest {
    private fun advance(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    @Test fun oneTimerUsesBoundedBackoffAndSuccessResetsIt() {
        var retries = 0
        val coordinator = HelmSourceReconnectCoordinator({ retries++ }, {})
        coordinator.use {
            for ((index, delay) in listOf(2_000L, 5_000L, 10_000L, 30_000L, 30_000L).withIndex()) {
                val now = SystemClock.uptimeMillis()
                repeat(30) { coordinator.completed(false, false) }
                assertEquals(now + delay, coordinator.nextRetryUptimeMs)
                advance(delay - 1); assertEquals(index, retries)
                advance(1); assertEquals(index + 1, retries); assertNull(coordinator.nextRetryUptimeMs)
            }
            coordinator.completed(true, false); coordinator.completed(false, false)
            assertEquals(SystemClock.uptimeMillis() + 2_000, coordinator.nextRetryUptimeMs)
        }
        advance(31_000); assertEquals(5, retries)
    }
    @Test fun observationsArePostedCoalescedAndNeverReenterCaller() {
        var calls = 0; var inside = true
        val coordinator = HelmSourceReconnectCoordinator({}, { assertFalse(inside); calls++ })
        coordinator.use {
            repeat(50) { coordinator.observed() }; assertEquals(0, calls)
            inside = false; advance(0); assertEquals(1, calls)
            coordinator.observed(); coordinator.close(); advance(0); assertEquals(1, calls)
        }
    }
    @Test fun manualRequestBusyReadAndLocalCorruptionCancelRetryWithoutExtraCallbacks() {
        var calls = 0; val coordinator = HelmSourceReconnectCoordinator({ calls++ }, {})
        coordinator.use {
            coordinator.completed(false, false); coordinator.requested()
            assertNull(coordinator.nextRetryUptimeMs); advance(2_000); assertEquals(0, calls)
            coordinator.completed(false, false); coordinator.completed(false, true)
            assertNull(coordinator.nextRetryUptimeMs); advance(10_000); assertEquals(0, calls)
            coordinator.completed(false, false); coordinator.completed(false, false, blocked = true)
            assertNull(coordinator.nextRetryUptimeMs); advance(30_000); assertEquals(0, calls)
        }
    }
}
