package com.no5.helm.control

import com.no5.control.wire.BridgeWire
import org.junit.Assert.*
import org.junit.Test

class HelmRootConnectionOwnerTest {
    private class Fixture : HelmRootConnectionOwner.Main {
        var now = 100L
        val scheduled = mutableMapOf<Runnable, Long>()
        val callbacks = mutableListOf<HelmRootConnectionOwner.Callbacks>()
        var starts = 0; var closes = 0; var connected = 0; var packets = 0; var losses = 0
        override fun check() = Unit
        override fun postDelayed(task: Runnable, delayMs: Long): Boolean { scheduled[task] = now + delayMs; return true }
        override fun remove(task: Runnable) { scheduled.remove(task) }
        fun advance(to: Long) {
            now = to
            scheduled.filterValues { it <= to }.keys.toList().forEach { scheduled.remove(it); it.run() }
        }
        fun owner(startedAt: Long? = null) = HelmRootConnectionOwner(this, { now }, factory = { cb ->
            callbacks.add(cb)
            object : HelmRootConnectionOwner.Channel {
                override fun start() { starts++ }
                override fun close() { closes++; cb.disconnected("old_close_callback") }
            }
        }, onConnected = { connected++ }, onPacket = { packets++; true },
            onDisconnected = { losses++ }, budgetStartedElapsed = startedAt)
    }
    @Test fun failedFirstConnectionRetriesFreshAndOldCallbacksCannotRevokeOrDeliver() {
        val f = Fixture(); val owner = f.owner(); owner.start()
        val old = f.callbacks.single(); old.disconnected("connect_failed")
        assertEquals(1, f.closes); assertEquals(1, f.losses)
        f.advance(200); assertEquals(2, f.starts)
        val current = f.callbacks.last(); current.connected()
        old.connected(); old.packet(BridgeWire.Packet.hello("0123456789abcdef0123456789abcdef")); old.disconnected("late")
        assertEquals(1, f.connected); assertEquals(0, f.packets); assertEquals(1, f.losses)
        current.packet(BridgeWire.Packet.hello("0123456789abcdef0123456789abcdef"))
        assertEquals(1, f.packets); owner.close(); assertEquals(2, f.closes)
    }
    @Test fun retryAndRepeatedStartNeverRenewOriginalNinetySecondDeadline() {
        val f = Fixture(); val owner = f.owner(0); owner.start()
        f.callbacks.single().disconnected("failed"); f.advance(200)
        owner.start(); f.advance(89_999); assertEquals(2, f.starts)
        f.advance(90_000); assertEquals(2, f.closes); assertTrue(f.scheduled.isEmpty())
        f.callbacks.last().connected(); f.advance(200_000); assertEquals(2, f.starts)
    }
    @Test fun delayedSetupConsumesOriginalBudgetAndNoChannelCanStartAfterIt() {
        val f = Fixture(); f.now = 90_000
        f.owner(0).start(); assertEquals(0, f.starts); assertTrue(f.scheduled.isEmpty())
    }
    @Test fun backwardsClockFailsClosedWithoutSpawningAnotherReader() {
        val f = Fixture(); val owner = f.owner(); owner.start()
        f.now = 99; f.callbacks.single().disconnected("lost")
        assertEquals(1, f.starts); assertEquals(1, f.closes); assertTrue(f.scheduled.isEmpty())
    }
}
