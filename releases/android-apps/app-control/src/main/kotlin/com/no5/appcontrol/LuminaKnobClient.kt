package com.no5.appcontrol

import android.content.*
import android.os.*
import com.no5.control.ClientDisposition
import com.no5.control.NormalizedKnobInput
import com.no5.gateway.LuminaControllerIdentity
import com.no5.gateway.LuminaGatewayClient
import com.no5.lumina.control.KnobLeaseProtocol as Wire
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** The app checks this ticket immediately before a mutation; a timed-out ticket cannot be replayed. */
class KnobActionTicket internal constructor(val deadlineUptimeMs: Long, private val current: () -> Boolean,
    private val deliver: (ClientDisposition) -> Unit) {
    val active: Boolean get() = current() && SystemClock.uptimeMillis() < deadlineUptimeMs
    fun complete(result: ClientDisposition) { deliver(if (active) result else ClientDisposition.AUDIO_DEFAULT) }
}

internal fun interface BufferedKnobSubmit { fun submit(input: NormalizedKnobInput, sequence: Long, physicalUptimeMs: Long): Long }

/** App-owned callback and short lease only. Physical focus and audio defaults belong to Lumina. */
internal class LuminaKnobClient(context: Context,
    private val buffered: BufferedKnobSubmit? = null,
    private val query: ((Long, Parcel) -> Boolean)? = null,
    private val dispatch: (NormalizedKnobInput, KnobActionTicket) -> Unit) : AutoCloseable {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadScheduledExecutor { Thread(it, "no5-knob-lease").apply { isDaemon = true } }
    @Volatile private var closed = false
    @Volatile private var resumed = false
    @Volatile private var focused = false
    @Volatile private var epoch = 0L
    @Volatile private var session = UUID.randomUUID().toString()
    @Volatile private var leaseId: String? = null
    @Volatile private var expiresAt = 0L
    @Volatile private var mask = 0
    @Volatile private var binder: IBinder? = null
    private val leaseLock = Any()
    @Volatile private var controllerUid = -1
    private var bound = false
    private val sequence = AtomicLong(-1)
    var reason: String? = "TRUSTED_FOCUS_UNAVAILABLE"; private set
    private val tick = Runnable { renew() }
    init { worker.scheduleWithFixedDelay({ refreshLease() }, 100, 100, TimeUnit.MILLISECONDS) }
    private val death = IBinder.DeathRecipient { main.post { disconnect() } }
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            if (!wanted() || !LuminaControllerIdentity.trusted(app)) { disconnect(); return }
            controllerUid = app.packageManager.getApplicationInfo(LuminaGatewayClient.CONTROLLER, 0).uid
            if (!LuminaControllerIdentity.isCallingController(app, controllerUid)) { disconnect(); return }
            binder = service
            try { service.linkToDeath(death, 0); worker.execute { refreshLease() }; renew() } catch (_: RemoteException) { disconnect() }
        }
        override fun onServiceDisconnected(name: ComponentName) = disconnect()
        override fun onBindingDied(name: ComponentName) = disconnect()
        override fun onNullBinding(name: ComponentName) = disconnect()
    }
    private val callback = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == INTERFACE_TRANSACTION) { reply?.writeString(Wire.INPUT_CALLBACK_DESCRIPTOR); return true }
            if (code != Wire.DELIVER_INPUT && code != Wire.READ_COMMAND_RESULT && code != Wire.DELIVER_BUFFERED_INPUT || reply == null) return false
            // Package/certificate/UID admission precedes every request field.
            if (getCallingUid() != controllerUid || controllerUid < 0) {
                if(code==Wire.DELIVER_BUFFERED_INPUT) primitiveAnswer(reply,Wire.RESULT_REJECTED,0,Wire.ADMISSION_REJECTED) else answer(reply, Wire.RESULT_REJECTED, "CALLER_NOT_AUTHORIZED"); return true
            }
            if (code == Wire.READ_COMMAND_RESULT) {
                try { require(data.dataSize() <= 2048); data.enforceInterface(Wire.INPUT_CALLBACK_DESCRIPTOR); val id = data.readLong(); require(data.dataAvail() == 0); reply.writeNoException(); if (query?.invoke(id, reply) != true) reply.writeInt(0) }
                catch (_: Exception) { reply.setDataSize(0); reply.setDataPosition(0); reply.writeNoException(); reply.writeInt(0) }
                return true
            }
            var result = ClientDisposition.AUDIO_DEFAULT
            var outcome = "STALE_INPUT"
            try {
                require(data.dataSize() <= 2048)
                data.enforceInterface(Wire.INPUT_CALLBACK_DESCRIPTOR)
                val requestedSession = data.readString(); val requestedLease = data.readString()
                val number = data.readLong(); val time = data.readLong(); val input = input(data.readInt())
                require(data.dataAvail() == 0)
                val admittedEpoch = epoch
                val now = SystemClock.uptimeMillis()
                val admissionDeadline=minOf(time+250,now+60,expiresAt)
                require(wanted() && requestedSession == session && requestedLease == leaseId && now < expiresAt &&
                    time >= 0 && time <= now && now - time <= 250 && input != null && mask and bit(input) != 0)
                var previous = sequence.get()
                while (true) { require(number > previous); if (sequence.compareAndSet(previous, number)) break; previous = sequence.get() }
                if (buffered != null) {
                    val commandId = synchronized(leaseLock) {
                        if (!wanted() || epoch != admittedEpoch || requestedLease != leaseId || SystemClock.uptimeMillis() >= admissionDeadline) -2L
                        else buffered.submit(input!!, number, time)
                    }
                    if (commandId > 0) {
                        if(SystemClock.uptimeMillis()>=admissionDeadline) {
                            if(code==Wire.DELIVER_BUFFERED_INPUT)primitiveAnswer(reply,Wire.RESULT_UNAVAILABLE,commandId,Wire.ADMISSION_COMPLETION_UNKNOWN)
                            else answer(reply,Wire.RESULT_UNAVAILABLE,"COMPLETION_UNKNOWN")
                            return true
                        }
                        if(code==Wire.DELIVER_BUFFERED_INPUT)primitiveAnswer(reply,Wire.RESULT_ACCEPTED,commandId,Wire.ADMISSION_QUEUED) else answer(reply, Wire.RESULT_ACCEPTED, "QUEUED:$commandId")
                        return true
                    }
                    // Zero means the actor refused admission; -1 selects the legacy handler.
                    if (commandId == -2L || commandId == 0L && SystemClock.uptimeMillis() >= admissionDeadline) {
                        // No actor took this ticket. A deadline/lease race is not a request for audio.
                        synchronized(leaseLock) { if(epoch==admittedEpoch&&session==requestedSession&&leaseId==requestedLease)sequence.compareAndSet(number,previous) }
                        if(code==Wire.DELIVER_BUFFERED_INPUT)primitiveAnswer(reply,Wire.RESULT_UNAVAILABLE,0,Wire.ADMISSION_STALE) else answer(reply,Wire.RESULT_UNAVAILABLE,"STALE_INPUT")
                        return true
                    }
                    if (commandId == 0L) { if(code==Wire.DELIVER_BUFFERED_INPUT)primitiveAnswer(reply,Wire.RESULT_UNAVAILABLE,0,Wire.ADMISSION_REJECTED) else answer(reply, Wire.RESULT_UNAVAILABLE, "ADMISSION_REJECTED"); return true }
                }
                if(code==Wire.DELIVER_BUFFERED_INPUT){sequence.compareAndSet(number,previous);primitiveAnswer(reply,Wire.RESULT_UNAVAILABLE,0,Wire.ADMISSION_UNSUPPORTED);return true}
                val answer = AtomicReference<ClientDisposition?>()
                val done = CountDownLatch(1)
                val deadline = minOf(time + 250, now + 60, expiresAt)
                val ticket = KnobActionTicket(deadline,
                    { wanted() && epoch == admittedEpoch && requestedLease == leaseId && answer.get() == null },
                    { disposition -> if (answer.compareAndSet(null, disposition)) done.countDown() })
                main.post {
                    if (ticket.active) try { dispatch(input!!, ticket) }
                    catch (_: RuntimeException) { ticket.complete(ClientDisposition.REFUSED) }
                    else ticket.complete(ClientDisposition.AUDIO_DEFAULT)
                }
                done.await((deadline - SystemClock.uptimeMillis()).coerceAtLeast(0), TimeUnit.MILLISECONDS)
                result = answer.get() ?: ClientDisposition.AUDIO_DEFAULT
                answer.compareAndSet(null, ClientDisposition.AUDIO_DEFAULT) // Revoke an action still queued on Main/renderer.
                outcome = if (result == ClientDisposition.HANDLED) "HANDLED" else "ACTION_UNAVAILABLE"
            } catch (_: Exception) { }
            if(code==Wire.DELIVER_BUFFERED_INPUT){primitiveAnswer(reply,Wire.RESULT_UNAVAILABLE,0,Wire.ADMISSION_STALE);return true}
            answer(reply, when(result) { ClientDisposition.HANDLED -> Wire.RESULT_ACCEPTED; ClientDisposition.REFUSED -> Wire.RESULT_REJECTED; else -> Wire.RESULT_UNAVAILABLE }, outcome)
            return true
        }
    }
    fun update(inputs: Set<NormalizedKnobInput>) {
        checkMain()
        val next = inputs.fold(0) { value, input -> value or bit(input) }
        if (next != mask) { release(); mask = next; epoch++; session = UUID.randomUUID().toString(); sequence.set(-1) }
        renew()
    }
    fun onResume() { checkMain(); resumed = true; renew() }
    fun onPause() { checkMain(); resumed = false; release(); unbind() }
    fun onWindowFocusChanged(value: Boolean) {
        checkMain(); focused = value
        if (!value) { release(); unbind() } else renew()
    }
    private fun wanted() = !closed && resumed && focused && mask != 0
    private fun renew() {
        checkMain(); main.removeCallbacks(tick)
        if (!wanted()) return
        if (binder == null && !bound && LuminaControllerIdentity.trusted(app)) {
            bound = try { app.bindService(Intent().setComponent(LuminaGatewayClient.SERVICE), connection, Context.BIND_AUTO_CREATE) }
                catch (_: RuntimeException) { false }
        }
        // WMS may legitimately revoke a lease between two OS observations.
        // Reacquire promptly; token/session renewal is a separate contract.
        main.postDelayed(tick, 100)
    }
    /** Binder renewal remains live when UI rendering or composition is busy. */
    private fun refreshLease() {
        val service = binder
        if (service != null && wanted()) {
            val expectedEpoch = epoch; val visibleSession = session; val requestedMask = mask
                var failure = "TRUSTED_FOCUS_UNAVAILABLE"
                val data = Parcel.obtain(); val reply = Parcel.obtain()
                try {
                    data.writeInterfaceToken(Wire.DESCRIPTOR); data.writeString(visibleSession); data.writeInt(requestedMask); data.writeStrongBinder(callback)
                    check(service.transact(Wire.REQUEST_LEASE, data, reply, 0)); require(reply.dataSize() <= 2048); reply.readException()
                    if (reply.readInt() == Wire.RESULT_ACCEPTED) {
                        val id = reply.readString()!!; val expiry = reply.readLong()
                        UUID.fromString(id); val now = SystemClock.uptimeMillis()
                        require(expiry > now && expiry - now <= Wire.MAX_LEASE_MS && reply.dataAvail() == 0)
                        var installed = false
                        synchronized(leaseLock) {
                            if (epoch == expectedEpoch && session == visibleSession && mask == requestedMask && wanted() && binder === service) {
                                leaseId = id; expiresAt = expiry; failure = "READY"; installed = true
                            }
                        }
                        if (!installed) remoteRelease(service, visibleSession, id)
                    } else failure = reply.readString()?.takeIf { it.matches(Regex("[A-Z_]{1,128}")) } ?: "INVALID_LEASE_RESPONSE"
                } catch (_: Exception) { failure = "LEASE_UNAVAILABLE" }
                finally { data.recycle(); reply.recycle() }
                synchronized(leaseLock) {
                    if (!closed && epoch == expectedEpoch && session == visibleSession && mask == requestedMask && binder === service) { reason = if (failure == "READY") null else failure; if (failure != "READY" && SystemClock.uptimeMillis() >= expiresAt) { leaseId = null; expiresAt = 0 } }
                }
        }
    }
    private fun release() {
        main.removeCallbacks(tick)
        val service: IBinder?; val id: String?; val oldSession: String
        synchronized(leaseLock) {
            epoch++; service=binder; id=leaseId; oldSession=session
            leaseId=null; expiresAt=0
        }
        if (service != null && id != null) worker.execute { remoteRelease(service, oldSession, id) }
    }
    private fun remoteRelease(service: IBinder, session: String, id: String) {
        val data = Parcel.obtain(); val reply = Parcel.obtain()
        try { data.writeInterfaceToken(Wire.DESCRIPTOR); data.writeString(session); data.writeString(id)
            service.transact(Wire.RELEASE_LEASE, data, reply, 0); reply.readException() }
        catch (_: Exception) { } finally { data.recycle(); reply.recycle() }
    }
    private fun unbind() {
        binder?.let { runCatching { it.unlinkToDeath(death, 0) } }; binder = null
        if (bound) { runCatching { app.unbindService(connection) }; bound = false }
    }
    private fun disconnect() { checkMain(); if (!closed) { release(); unbind(); synchronized(leaseLock) { session=UUID.randomUUID().toString();sequence.set(-1);controllerUid = -1 }; reason = "TRUSTED_FOCUS_UNAVAILABLE"; main.postDelayed(tick, 100) } }
    override fun close() { checkMain(); if (!closed) { release(); closed = true; unbind(); controllerUid=-1; worker.shutdownNow() } }
    private fun checkMain() = check(Looper.myLooper() == Looper.getMainLooper())
    private fun primitiveAnswer(reply:Parcel,result:Int,id:Long,reason:Int){reply.writeNoException();reply.writeInt(result);reply.writeLong(id);reply.writeInt(reason)}
    private fun answer(reply: Parcel, result: Int, reason: String) { reply.writeNoException(); reply.writeInt(result); reply.writeString(reason) }
    private fun bit(input: NormalizedKnobInput) = 1 shl (code(input) - 1)
    private fun code(input: NormalizedKnobInput): Int = when(input) {
        NormalizedKnobInput.LEFT_CW -> Wire.INPUT_LEFT_CW; NormalizedKnobInput.LEFT_CCW -> Wire.INPUT_LEFT_CCW
        NormalizedKnobInput.LEFT_PUSH -> Wire.INPUT_LEFT_PUSH; NormalizedKnobInput.LEFT_LONG_PUSH -> Wire.INPUT_LEFT_LONG
        NormalizedKnobInput.RIGHT_CW -> Wire.INPUT_RIGHT_CW; NormalizedKnobInput.RIGHT_CCW -> Wire.INPUT_RIGHT_CCW
        NormalizedKnobInput.RIGHT_PUSH -> Wire.INPUT_RIGHT_PUSH; NormalizedKnobInput.RIGHT_LONG_PUSH -> Wire.INPUT_RIGHT_LONG
    }
    private fun input(code: Int): NormalizedKnobInput? = when(code) {
        Wire.INPUT_LEFT_CW -> NormalizedKnobInput.LEFT_CW; Wire.INPUT_LEFT_CCW -> NormalizedKnobInput.LEFT_CCW
        Wire.INPUT_LEFT_PUSH -> NormalizedKnobInput.LEFT_PUSH; Wire.INPUT_LEFT_LONG -> NormalizedKnobInput.LEFT_LONG_PUSH
        Wire.INPUT_RIGHT_CW -> NormalizedKnobInput.RIGHT_CW; Wire.INPUT_RIGHT_CCW -> NormalizedKnobInput.RIGHT_CCW
        Wire.INPUT_RIGHT_PUSH -> NormalizedKnobInput.RIGHT_PUSH; Wire.INPUT_RIGHT_LONG -> NormalizedKnobInput.RIGHT_LONG_PUSH
        else -> null
    }
}
