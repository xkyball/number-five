package com.no5.helm.control

import com.no5.control.wire.BridgeWire
import java.io.ByteArrayOutputStream
import java.io.FileDescriptor
import java.io.IOException
import java.io.InputStream
import java.util.ArrayDeque
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Explicit synthetic peer/streams/clock/scheduler, never a native socket or kernel credential proof. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class HelmRootChannelTest {
    private val session = "0123456789abcdef0123456789abcdef"
    private fun hello() = BridgeWire.Packet.hello(session)
    private fun focus(sequence: Long = 2, epoch: Long = 1, time: Long = 100) =
        BridgeWire.Packet.focus(session, sequence, BridgeWire.Focus("com.no5.home", "fixture-window", epoch, time, 0, 0))
    private fun key(sequence: Long = 3, epoch: Long = 1, down: Long = 100, time: Long = 100) =
        BridgeWire.Packet.key(session, sequence, BridgeWire.Key(epoch, 7, 364, 364, 0, -1, 257, 0, 0, 0, down, time, time))
    private fun encoded(vararg packets: BridgeWire.Packet): ByteArray = ByteArrayOutputStream().also { out ->
        packets.forEach { BridgeWire.write(out, it) }
    }.toByteArray()

    @Test fun rejectsNonrootKernelUidBeforeInputOrHelloAndNotifiesMainOnce() {
        val f = Fixture(encoded(hello()), uid = 10042)
        f.channel.start(); f.runtime.runReader(); f.main.drain(); f.channel.close()
        assertEquals(0, f.socket.inputCalls); assertEquals(0, f.input.position)
        assertTrue(f.packets.isEmpty()); assertEquals(0, f.connected)
        assertEquals(listOf("peer_uid"), f.disconnected); assertFalse(f.channel.isActive)
    }

    @Test fun validHelloFocusKeyAreOrderedOnMainAndEofRevokes() {
        val f = Fixture(encoded(hello(), focus(), key()))
        f.pumpBetweenFrames(hello(), focus(), key())
        f.channel.start(); f.runtime.runReader(); f.main.drain()
        assertEquals(listOf("connected", "packet:1", "packet:2", "packet:3", "disconnected:eof"), f.events)
        assertEquals(1, f.connected); assertFalse(f.channel.isActive)
        assertTrue(f.socket.timeouts.all { it in 1..250 })
        assertEquals(1, f.socket.uidCalls)
    }

    @Test fun firstFocusNeverAnnouncesConnectedAndCannotDeliverAnything() {
        val f = Fixture(encoded(focus())); f.pumpBetweenFrames(focus())
        f.channel.start(); f.runtime.runReader(); f.main.drain()
        assertEquals(0, f.connected); assertTrue(f.packets.isEmpty())
        assertEquals(listOf("delivery_rejected"), f.disconnected)
    }

    @Test fun sequenceGapClosesInsteadOfSilentlySkippingOrReplaying() {
        val skipped = focus(sequence = 3)
        val f = Fixture(encoded(hello(), skipped, key(sequence = 4)))
        f.pumpBetweenFrames(hello(), skipped, key(sequence = 4))
        f.channel.start(); f.runtime.runReader(); f.main.drain()
        assertEquals(listOf(BridgeWire.HELLO), f.packets.map { it.kind })
        assertEquals(listOf("delivery_rejected"), f.disconnected)
    }

    @Test fun reconnectAdmissionRejectsDownTimeFromBeforeFreshHello() {
        val staleFocus = focus(time = 900)
        val staleDown = key(down = 950, time = 1000)
        val f = Fixture(encoded(hello(), staleFocus, staleDown)); f.clock = 1000
        f.pumpBetweenFrames(hello(), staleFocus, staleDown)
        f.channel.start(); f.runtime.runReader(); f.main.drain()
        assertEquals(listOf(BridgeWire.HELLO, BridgeWire.FOCUS), f.packets.map { it.kind })
        assertEquals(listOf("delivery_rejected"), f.disconnected)
    }

    @Test fun cancelKeepsTransportButRequiresFreshScopeBeforeAnotherKey() {
        val cancel = BridgeWire.Packet.cancel(session, 3)
        val renewed = focus(sequence = 4, epoch = 2)
        val nextKey = key(sequence = 5, epoch = 2)
        val f = Fixture(encoded(hello(), focus(), cancel, renewed, nextKey))
        f.pumpBetweenFrames(hello(), focus(), cancel, renewed, nextKey)
        f.channel.start(); f.runtime.runReader(); f.main.drain()
        assertEquals(listOf(1, 2, 4, 2, 3), f.packets.map { it.kind })
        assertEquals(listOf("eof"), f.disconnected)
    }

    @Test fun cancelThenSameEpochCannotReactivateOldScope() {
        val cancel = BridgeWire.Packet.cancel(session, 3)
        val old = focus(sequence = 4)
        val f = Fixture(encoded(hello(), focus(), cancel, old)); f.pumpBetweenFrames(hello(), focus(), cancel, old)
        f.channel.start(); f.runtime.runReader(); f.main.drain()
        assertEquals(listOf(1, 2, 4), f.packets.map { it.kind })
        assertEquals(listOf("delivery_rejected"), f.disconnected)
    }

    @Test fun partialReadProgressDoesNotResetAbsoluteFrameDeadline() {
        val f = Fixture(encoded(hello())); f.input.chunk = 1
        f.input.afterRead = { f.clock += 20 }
        f.channel.start(); f.runtime.runReader(); f.main.drain()
        assertEquals(0, f.connected); assertTrue(f.packets.isEmpty())
        assertEquals(listOf("io_deadline"), f.disconnected)
        assertTrue(f.input.position < encoded(hello()).size)
        assertTrue(f.socket.timeouts.zipWithNext().all { (a, b) -> b <= a })
    }

    @Test fun watchdogRevokesStalledConnectBeforePeerOrInputAndNoSecondReaderStarts() {
        val f = Fixture(encoded(hello()))
        var second: HelmRootChannel? = null
        val secondReasons = mutableListOf<String>()
        f.socket.onConnect = {
            f.clock = 351; f.runtime.tick(); f.main.drain()
            f.main.asMain {
                second = HelmRootChannel({}, {}, { secondReasons += it }, f.dependencies())
                second!!.start()
            }
            assertTrue(f.runtime.busy) // Close is not treated as actual connector completion.
            assertEquals(1, f.runtime.acceptedReaders)
        }
        f.channel.start(); f.runtime.runReader(); f.main.drain()
        assertEquals(listOf("io_deadline"), f.disconnected)
        assertEquals(listOf("reader_busy"), secondReasons)
        assertEquals(0, f.socket.uidCalls); assertEquals(0, f.socket.inputCalls)
        assertFalse(f.runtime.busy); assertEquals(0, f.runtime.liveWatches)
        second!!.close()
    }

    @Test fun watchdogRevokesAStalledReadWithoutAReplacementThread() {
        val f = Fixture(encoded(hello()))
        f.input.beforeRead = {
            f.clock = 350; f.runtime.tick()
            assertFalse(f.channel.isActive)
        }
        f.channel.start(); f.runtime.runReader(); f.main.drain()
        assertEquals(listOf("io_deadline"), f.disconnected)
        assertEquals(0, f.connected); assertEquals(1, f.runtime.acceptedReaders)
    }

    @Test fun queueHasOneDrainAndOverflowDiscardsEveryPendingGesture() {
        val packets = arrayOf(hello(), *(2L..34L).map { BridgeWire.Packet.cancel(session, it) }.toTypedArray())
        val f = Fixture(encoded(*packets))
        f.channel.start(); f.runtime.runReader(); f.main.drain()
        assertEquals(1, f.main.maximumPending); assertEquals(1, f.main.posts)
        assertEquals(0, f.connected); assertTrue(f.packets.isEmpty())
        assertEquals(listOf("queue_overflow"), f.disconnected)
    }

    @Test fun queuedFrameExpiresAtOriginalFrameStartNotAfterParsingOrMainDelay() {
        val firstSize = encoded(hello()).size
        val f = Fixture(encoded(hello(), focus(time = 200)))
        f.input.afterRead = { if (f.input.position == firstSize) f.clock = 200 }
        f.input.beforeRead = {
            if (f.input.position == firstSize) { f.clock = 350; f.runtime.tick() }
        }
        f.channel.start(); f.runtime.runReader(); f.main.drain()
        assertEquals(listOf("queue_expired"), f.disconnected)
        assertEquals(0, f.connected); assertTrue(f.packets.isEmpty())
    }

    @Test fun eofBeforeMainDeliveryDiscardsOldQueueRatherThanReplaying() {
        val f = Fixture(encoded(hello(), focus(), key()))
        f.channel.start(); f.runtime.runReader(); f.main.drain()
        assertEquals(0, f.connected); assertTrue(f.packets.isEmpty())
        assertEquals(listOf("eof"), f.disconnected)
    }

    @Test fun ownerCloseIsSynchronousIdempotentAndSuppressesPostedCallbacks() {
        val f = Fixture(encoded(hello()))
        f.channel.start(); f.channel.close(); f.channel.close()
        assertEquals(listOf("owner_closed"), f.disconnected)
        f.runtime.runReader(); f.main.drain(); f.channel.start()
        assertEquals(0, f.socket.connectCalls); assertEquals(0, f.connected)
        assertEquals(1, f.runtime.acceptedReaders); assertEquals(0, f.runtime.liveWatches)
    }

    @Test fun closeInsideConnectedCallbackSuppressesHelloAndLaterPackets() {
        val f = Fixture(encoded(hello(), focus()))
        f.onConnectedHook = { f.channel.close() }; f.pumpBetweenFrames(hello(), focus())
        f.channel.start(); f.runtime.runReader(); f.main.drain()
        assertEquals(1, f.connected); assertTrue(f.packets.isEmpty())
        assertEquals(listOf("owner_closed"), f.disconnected)
    }

    @Test fun unexpectedAncillaryDescriptorsAreClosedAndNeverReachPacketCallback() {
        val f = Fixture(encoded(hello()))
        f.socket.descriptors = arrayOf(FileDescriptor(), FileDescriptor())
        f.channel.start(); f.runtime.runReader(); f.main.drain()
        assertEquals(2, f.closedDescriptors); assertEquals(0, f.connected)
        assertEquals(listOf("unexpected_descriptors"), f.disconnected)
    }

    @Test fun oversizedOrTruncatedFrameFailsClosed() {
        listOf(byteArrayOf(0, 0, 0x20, 0), encoded(hello()).copyOf(9)).forEach { bytes ->
            val f = Fixture(bytes); f.channel.start(); f.runtime.runReader(); f.main.drain()
            assertEquals(0, f.connected); assertTrue(f.packets.isEmpty())
            assertEquals(listOf("io_or_frame"), f.disconnected)
        }
    }

    @Test fun freshSessionMismatchAfterHelloNeverDeliversItsPacket() {
        val mismatch = BridgeWire.Packet.focus("abcdef0123456789abcdef0123456789", 2, focus().focus)
        val f = Fixture(encoded(hello(), mismatch)); f.pumpBetweenFrames(hello(), mismatch)
        f.channel.start(); f.runtime.runReader(); f.main.drain()
        assertEquals(listOf(BridgeWire.HELLO), f.packets.map { it.kind })
        assertEquals(listOf("delivery_rejected"), f.disconnected)
    }

    @Test fun wrongThreadStartOrCloseCannotAcquireAnotherWorker() {
        val f = Fixture(encoded(hello()))
        f.main.onMain = false
        assertThrows(IllegalStateException::class.java) { f.channel.start() }
        assertThrows(IllegalStateException::class.java) { f.channel.close() }
        assertEquals(0, f.runtime.acceptedReaders)
        f.main.onMain = true; f.channel.close()
    }

    private inner class Fixture(bytes: ByteArray, uid: Int = 0) {
        var clock = 100L
        val main = FakeMain()
        val runtime = FakeRuntime(main)
        val input = FixtureInput(bytes)
        val socket = FakeConnection(input, uid)
        var connected = 0
        var closedDescriptors = 0
        var onConnectedHook: (() -> Unit)? = null
        val packets = mutableListOf<BridgeWire.Packet>()
        val disconnected = mutableListOf<String>()
        val events = mutableListOf<String>()
        fun dependencies() = HelmRootChannel.Dependencies({ socket }, main, runtime, { clock }, { closedDescriptors++ })
        val channel = HelmRootChannel({
            assertTrue(main.onMain); connected++; events += "connected"; onConnectedHook?.invoke()
        }, { packet ->
            assertTrue(main.onMain); packets += packet; events += "packet:${packet.kind}"
        }, { reason ->
            assertTrue(main.onMain); disconnected += reason; events += "disconnected:$reason"
        }, dependencies())
        fun pumpBetweenFrames(vararg frames: BridgeWire.Packet) {
            val boundaries = frames.scan(0) { total, packet -> total + encoded(packet).size }.drop(1).toSet()
            input.beforeRead = { if (input.position in boundaries) main.drain() }
        }
    }

    private class FakeMain : HelmRootChannel.MainQueue {
        var onMain = true
        var posts = 0
        var maximumPending = 0
        private val tasks = ArrayDeque<Runnable>()
        override fun isMainThread() = onMain
        override fun post(task: Runnable): Boolean {
            posts++; tasks.addLast(task); maximumPending = maxOf(maximumPending, tasks.size); return true
        }
        fun asMain(action: () -> Unit) {
            val prior = onMain; onMain = true
            try { action() } finally { onMain = prior }
        }
        fun drain() = asMain {
            var count = 0
            while (tasks.isNotEmpty()) { check(++count <= 100); tasks.removeFirst().run() }
        }
    }

    private class FakeRuntime(private val main: FakeMain) : HelmRootChannel.Runtime {
        var busy = false
        var acceptedReaders = 0
        private var reader: Runnable? = null
        private class Watch(val task: Runnable, var cancelled: Boolean = false)
        private val watches = mutableListOf<Watch>()
        val liveWatches get() = watches.count { !it.cancelled }
        override fun tryExecute(task: Runnable): Boolean {
            if (busy) return false
            busy = true; acceptedReaders++; reader = task; return true
        }
        override fun watch(task: Runnable): HelmRootChannel.Cancellation {
            val value = Watch(task); watches += value
            return HelmRootChannel.Cancellation { value.cancelled = true }
        }
        fun tick() {
            val prior = main.onMain; main.onMain = false
            try { watches.toList().filter { !it.cancelled }.forEach { it.task.run() } }
            finally { main.onMain = prior }
        }
        fun runReader() {
            val task = reader ?: return; reader = null
            val prior = main.onMain; main.onMain = false
            try { task.run() } finally { busy = false; main.onMain = prior }
        }
    }

    private class FakeConnection(private val stream: InputStream, private val uid: Int) : HelmRootChannel.Connection {
        var connectCalls = 0
        var uidCalls = 0
        var inputCalls = 0
        var onConnect: (() -> Unit)? = null
        var descriptors: Array<FileDescriptor>? = null
        var closed = false
        val timeouts = mutableListOf<Int>()
        override fun connect() { connectCalls++; onConnect?.invoke() }
        override fun peerUid(): Int { uidCalls++; return uid }
        override fun input(): InputStream { inputCalls++; return stream }
        override fun readTimeout(milliseconds: Int) { timeouts += milliseconds }
        override fun ancillaryDescriptors(): Array<FileDescriptor>? = descriptors.also { descriptors = null }
        override fun close() { closed = true }
    }

    private class FixtureInput(private val bytes: ByteArray) : InputStream() {
        var position = 0
        var chunk = Int.MAX_VALUE
        var beforeRead: (() -> Unit)? = null
        var afterRead: (() -> Unit)? = null
        override fun read(): Int {
            beforeRead?.invoke()
            if (position == bytes.size) return -1
            val value = bytes[position++].toInt() and 255; afterRead?.invoke(); return value
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            beforeRead?.invoke()
            if (position == bytes.size) return -1
            val count = minOf(length, chunk, bytes.size - position)
            System.arraycopy(bytes, position, buffer, offset, count); position += count
            afterRead?.invoke(); return count
        }
    }
}
