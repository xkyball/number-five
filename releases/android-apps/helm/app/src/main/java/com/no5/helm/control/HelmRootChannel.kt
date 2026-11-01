package com.no5.helm.control

import android.content.Context
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.system.Os
import com.no5.control.wire.BridgeWire
import java.io.FileDescriptor
import java.io.IOException
import java.io.InputStream
import java.util.ArrayDeque
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One explicitly started Root -> HELM connection. There is no reconnect or gesture replay.
 * UID 0 is the declared root trust boundary, not proof of a particular root executable.
 * The wire session is freshness/ordering state and is never used as peer authentication.
 */
internal class HelmRootChannel internal constructor(
    private val onConnected: () -> Unit,
    private val onPacket: (BridgeWire.Packet) -> Unit,
    private val onDisconnected: (String) -> Unit,
    private val dependencies: Dependencies
) : AutoCloseable {
    @Suppress("UNUSED_PARAMETER")
    constructor(context: Context, onConnected: () -> Unit, onPacket: (BridgeWire.Packet) -> Unit,
        onDisconnected: (String) -> Unit) : this(onConnected, onPacket, onDisconnected, production())

    internal interface Connection : AutoCloseable {
        fun connect()
        fun peerUid(): Int
        fun input(): InputStream
        fun readTimeout(milliseconds: Int)
        fun ancillaryDescriptors(): Array<FileDescriptor>?
    }
    internal fun interface Cancellation { fun cancel() }
    internal interface MainQueue {
        fun isMainThread(): Boolean
        fun post(task: Runnable): Boolean
    }
    internal interface Runtime {
        /** False while an earlier connector/reader is still alive, including after close. */
        fun tryExecute(task: Runnable): Boolean
        fun watch(task: Runnable): Cancellation
    }
    internal data class Dependencies(
        val connection: () -> Connection,
        val main: MainQueue,
        val runtime: Runtime,
        val uptime: () -> Long,
        val closeDescriptor: (FileDescriptor) -> Unit
    )

    private data class Pending(val packet: BridgeWire.Packet, val expires: Long)
    private val lock = Any()
    private val pending = ArrayDeque<Pending>()
    private val dead = AtomicBoolean(false)
    private val guard = BridgeWire.Guard() // Main thread only: freshness is checked again at delivery.
    @Volatile private var connection: Connection? = null
    @Volatile private var watch: Cancellation? = null
    @Volatile private var ioDeadline = Long.MAX_VALUE
    private var started = false // Main thread only.
    private var connectedDelivered = false // Main thread only.
    private var disconnectedDelivered = false // Main thread only.
    private var drainPosted = false // lock
    private var reason = "closed" // lock
    private val drain = Runnable { drainMain() }

    internal val isActive: Boolean get() = started && !dead.get()

    fun start() {
        checkMain()
        if (started || dead.get()) return
        started = true
        ioDeadline = newDeadline() ?: return
        val scheduled = try { dependencies.runtime.watch(Runnable { checkExpiry() }) }
            catch (_: RuntimeException) { terminate("watchdog_unavailable"); return }
        watch = scheduled
        if (dead.get()) { scheduled.cancel(); return }
        if (!dependencies.runtime.tryExecute(Runnable { readConnection() })) terminate("reader_busy")
    }

    override fun close() {
        checkMain()
        terminate("owner_closed")
        // Service/bind owner teardown revokes synchronously on Main, even if native I/O is stuck.
        notifyDisconnected()
    }

    private fun readConnection() {
        try {
            if (dead.get()) return
            val socket = dependencies.connection()
            connection = socket
            if (dead.get()) { closeQuietly(socket); return }
            socket.connect() // Android 29 supports the one-argument API, not connect(address, timeout).
            requireLiveIo()
            if (socket.peerUid() != 0) throw ChannelFailure("peer_uid")
            requireLiveIo()
            // No input acquisition/decoding before the native kernel peer UID has been checked.
            val raw = socket.input()
            val bounded = object : InputStream() {
                override fun read(): Int = guardedRead(socket) { raw.read() }
                override fun read(bytes: ByteArray, offset: Int, length: Int): Int =
                    guardedRead(socket) { raw.read(bytes, offset, length) }
            }
            while (!dead.get()) {
                val expires = newDeadline() ?: break
                ioDeadline = expires
                val packet = BridgeWire.read(bounded) // MAX_FRAME 4096, strict UTF-8 and exact framing.
                requireLiveIo()
                if (packet == null) { terminate("eof"); break }
                enqueue(Pending(packet, expires))
            }
        } catch (failure: ChannelFailure) {
            terminate(failure.code)
        } catch (_: IOException) {
            terminate("io_or_frame")
        } catch (_: RuntimeException) {
            terminate("transport_failure")
        } finally {
            closeQuietly(connection)
            terminate("reader_ended")
        }
    }

    private fun guardedRead(socket: Connection, read: () -> Int): Int {
        val remaining = requireLiveIo()
        // SO_TIMEOUT bounds an individual syscall only; requireLiveIo and the independent watchdog
        // retain the same absolute deadline across every partial header/body read.
        socket.readTimeout(remaining.toInt())
        val result = try { read() } finally {
            val descriptors = socket.ancillaryDescriptors()
            if (!descriptors.isNullOrEmpty()) {
                for (descriptor in descriptors) {
                    try { dependencies.closeDescriptor(descriptor) } catch (_: Exception) { /* reject below */ }
                }
                throw ChannelFailure("unexpected_descriptors")
            }
        }
        requireLiveIo()
        return result
    }

    private fun requireLiveIo(): Long {
        if (dead.get()) throw ChannelFailure("closed")
        val now = dependencies.uptime()
        if (now < 0 || now >= ioDeadline) throw ChannelFailure("io_deadline")
        return ioDeadline - now
    }

    private fun newDeadline(): Long? {
        val now = dependencies.uptime()
        if (now < 0 || now > Long.MAX_VALUE - TTL_MS) { terminate("invalid_clock"); return null }
        return now + TTL_MS
    }

    private fun checkExpiry() {
        if (dead.get()) return
        val now = dependencies.uptime()
        if (now < 0 || now >= ioDeadline) { terminate("io_deadline"); return }
        val expiry = synchronized(lock) { pending.peekFirst()?.expires }
        if (expiry != null && now >= expiry) terminate("queue_expired")
    }

    private fun enqueue(value: Pending) {
        var failure: String? = null
        synchronized(lock) {
            if (dead.get()) return
            if (dependencies.uptime() >= value.expires) failure = "queue_expired"
            else if (pending.size >= MAX_PENDING) failure = "queue_overflow"
            else pending.addLast(value)
        }
        if (failure != null) terminate(failure!!) else postDrain()
    }

    private fun postDrain() {
        synchronized(lock) {
            if (drainPosted) return
            drainPosted = true
        }
        if (!dependencies.main.post(drain)) {
            synchronized(lock) { drainPosted = false }
            // A dead Main looper cannot receive a callback; admission is nevertheless revoked.
            terminate("main_unavailable", notify = false)
        }
    }

    private fun drainMain() {
        checkMain()
        // One posted runnable drains a fixed-size queue; producer frames never create Handler backlog.
        var delivered = 0
        while (delivered < MAX_PENDING) {
            if (dead.get()) {
                synchronized(lock) { pending.clear(); drainPosted = false }
                notifyDisconnected()
                return
            }
            val next = synchronized(lock) { pending.pollFirst() }
            if (next == null) {
                val finished = synchronized(lock) {
                    // Do not lose a producer enqueue between pollFirst and resetting drainPosted.
                    if (pending.isEmpty()) { drainPosted = false; true } else false
                }
                if (finished) { if (dead.get()) notifyDisconnected(); return }
                continue
            }
            val now = dependencies.uptime()
            if (now < 0 || now >= next.expires || !guard.accept(next.packet, now)) {
                terminate("delivery_rejected")
                continue
            }
            if (dead.get()) continue
            try {
                if (!connectedDelivered) {
                    connectedDelivered = true // Guard admits only HELLO as the first packet.
                    onConnected()
                }
                if (!dead.get()) onPacket(next.packet)
            } catch (_: RuntimeException) {
                terminate("callback_failure")
            }
            delivered++
        }
        synchronized(lock) { drainPosted = false }
        if (dead.get()) notifyDisconnected() else {
            if (synchronized(lock) { pending.isNotEmpty() }) postDrain()
        }
    }

    private fun terminate(code: String, notify: Boolean = true) {
        if (!dead.compareAndSet(false, true)) return
        synchronized(lock) { reason = code; pending.clear() }
        watch?.cancel()
        // Notify admission loss before close: native close is not assumed to be interruptible.
        if (notify) {
            if (dependencies.main.isMainThread()) notifyDisconnected() else postDrain()
        }
        closeQuietly(connection)
    }

    private fun notifyDisconnected() {
        checkMain()
        if (!dead.get() || disconnectedDelivered) return
        disconnectedDelivered = true
        guard.close()
        try { onDisconnected(synchronized(lock) { reason }) } catch (_: RuntimeException) {
            // Admission is already revoked. A consumer failure must not prevent socket cleanup.
        }
    }

    private fun checkMain() = check(dependencies.main.isMainThread()) { "Root channel owner/callback must be Main" }
    private fun closeQuietly(socket: Connection?) { try { socket?.close() } catch (_: Exception) { } }
    private class ChannelFailure(val code: String) : IOException(code)

    companion object {
        internal const val ADDRESS = "org.lumina.control.v2"
        internal const val TTL_MS = 250L
        internal const val MAX_PENDING = 32

        private fun production(): Dependencies {
            val handler = Handler(Looper.getMainLooper())
            return Dependencies({ AndroidConnection() }, object : MainQueue {
                override fun isMainThread() = Looper.myLooper() == Looper.getMainLooper()
                override fun post(task: Runnable) = handler.post(task)
            }, ProcessRuntime, SystemClock::uptimeMillis, { descriptor -> Os.close(descriptor) })
        }
    }

    private class AndroidConnection : Connection {
        private val socket = LocalSocket()
        override fun connect() = socket.connect(LocalSocketAddress(ADDRESS, LocalSocketAddress.Namespace.ABSTRACT))
        override fun peerUid() = socket.peerCredentials.uid
        override fun input(): InputStream = socket.inputStream
        override fun readTimeout(milliseconds: Int) { socket.soTimeout = milliseconds }
        override fun ancillaryDescriptors(): Array<FileDescriptor>? = socket.ancillaryFileDescriptors
        override fun close() = socket.close()
    }

    private object ProcessRuntime : Runtime {
        private val busy = AtomicBoolean(false)
        private val reader = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, SynchronousQueue<Runnable>(),
            { task -> Thread(task, "no5-root-reader").apply { isDaemon = true } })
        private val timer = ScheduledThreadPoolExecutor(1,
            { task -> Thread(task, "no5-root-watchdog").apply { isDaemon = true } }).apply {
            removeOnCancelPolicy = true
        }
        override fun tryExecute(task: Runnable): Boolean {
            if (!busy.compareAndSet(false, true)) return false
            return try {
                reader.execute { try { task.run() } finally { busy.set(false) } }
                true
            } catch (_: RuntimeException) { busy.set(false); false }
        }
        override fun watch(task: Runnable): Cancellation {
            val scheduled = timer.scheduleAtFixedRate(task, 10L, 10L, TimeUnit.MILLISECONDS)
            return Cancellation { scheduled.cancel(false) }
        }
    }
}
