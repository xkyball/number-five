package com.no5.gateway

import android.content.*
import android.content.pm.PackageManager
import android.os.*
import java.util.concurrent.Executors

enum class GatewaySessionPhase { WAITING, READY, UNAVAILABLE, CLOSED }
data class GatewaySessionStatus(val phase: GatewaySessionPhase, val origin: String? = null,
    val identity: String? = null, val reason: String? = null, val retryRemainingSeconds: Int = 0) {
    val usable: Boolean get() = phase == GatewaySessionPhase.READY || phase == GatewaySessionPhase.WAITING && identity != null
    val label: String get() = when (phase) {
        GatewaySessionPhase.READY -> "Gateway · Zugang bereit"
        GatewaySessionPhase.WAITING -> "Gateway · Verbindung wird erneuert · $retryRemainingSeconds s"
        GatewaySessionPhase.UNAVAILABLE -> "Gateway · nicht verfügbar · ${reason ?: "Zugang prüfen"}"
        GatewaySessionPhase.CLOSED -> "Gateway · Sitzung beendet"
    }
}

/** One bounded worker, one Binder connection and one renewal timer per owning app process. */
class LuminaGatewayClient(context: Context, private val handler: Handler = Handler(Looper.getMainLooper()),
    private val wallClock: () -> Long = System::currentTimeMillis,
    private val elapsed: () -> Long = SystemClock::elapsedRealtime) : AutoCloseable {
    private val app = context.applicationContext
    val role = runCatching { GatewayAppRole.forPackage(app.packageName) }.getOrNull()
    private val worker = Executors.newSingleThreadExecutor { task -> Thread(task, "no5-gateway-session").apply { isDaemon = true } }
    private val observers = linkedSetOf<(GatewaySessionStatus) -> Unit>()
    private var binder: IBinder? = null
    private var bound = false
    private var closed = false
    private var inFlight = false
    private var generation = 0L
    private var session: LuminaGatewaySession? = null
    private var failureAt: Long? = elapsed()
    private var retryAt = 0L
    private var bindStarted = 0L
    private var requestDeadline: Long? = null
    private var forceRefresh = false
    private var failures = 0
    private var failureReason = "GATEWAY_NOT_PROVISIONED"
    var status = GatewaySessionStatus(GatewaySessionPhase.WAITING, reason = failureReason, retryRemainingSeconds = 30)
        private set
    private val death = IBinder.DeathRecipient { handler.post { disconnected("BINDER_LOST") } }
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            if (closed) return
            if (!controllerTrusted()) { disconnected("CALLER_NOT_AUTHORIZED"); return }
            binder = service
            try { service.linkToDeath(death, 0) } catch (_: RemoteException) { disconnected("BINDER_LOST"); return }
            retryAt = 0; tick()
        }
        override fun onServiceDisconnected(name: ComponentName) = disconnected("BINDER_LOST")
        override fun onBindingDied(name: ComponentName) = disconnected("BINDER_LOST")
        override fun onNullBinding(name: ComponentName) = disconnected("GATEWAY_NOT_PROVISIONED")
    }
    private val timer = Runnable { tick() }
    private val setupChanged = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!closed && intent.action == SETUP_CHANGED) {
                generation++; forceRefresh = true; retryAt = 0; tick()
            }
        }
    }
    init {
        main()
        val filter = IntentFilter(SETUP_CHANGED)
        // The flags overload exists since API26 (all apps require API29).
        // This compile-time flag is inlined; the signature permission remains
        // mandatory on older framework versions as well.
        app.registerReceiver(setupChanged, filter, LEASE_PERMISSION, handler, Context.RECEIVER_EXPORTED)
        handler.post(timer)
    }
    fun observe(observer: (GatewaySessionStatus) -> Unit): AutoCloseable {
        main(); check(!closed); observers.add(observer); observer(status)
        return AutoCloseable { main(); observers.remove(observer) }
    }
    fun currentSession(): LuminaGatewaySession? { main(); return session?.takeIf { status.usable && it.valid } }
    fun refresh() { main(); if (!closed) { forceRefresh = true; retryAt = 0; tick() } }
    fun openSetup(): Boolean = try {
        if (!controllerTrusted()) false else {
            app.startActivity(Intent().setComponent(SETUP).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
        }
    } catch (_: RuntimeException) { false }
    private fun controllerTrusted(): Boolean = LuminaControllerIdentity.trusted(app)
    private fun connect() {
        if (role == null) { failed("CALLER_NOT_AUTHORIZED"); return }
        if (!controllerTrusted()) { failed("CONTROLLER_UNAVAILABLE"); return }
        if (bound) return
        try {
            bindStarted = elapsed()
            bound = app.bindService(Intent().setComponent(SERVICE), connection, Context.BIND_AUTO_CREATE)
            if (!bound) failed("CONTROLLER_UNAVAILABLE")
        } catch (_: RuntimeException) { failed("CONTROLLER_UNAVAILABLE") }
    }
    private fun request(service: IBinder) {
        if (inFlight) return
        forceRefresh = false
        inFlight = true; requestDeadline = elapsed() + 3500; val epoch = generation
        worker.execute {
            var fresh: LuminaGatewaySession? = null
            var reason: String? = null
            val request = Parcel.obtain(); val reply = Parcel.obtain()
            try {
                request.writeInterfaceToken(DESCRIPTOR); request.writeString(requireNotNull(role).name)
                val withRoute = service.transact(REQUEST_GATEWAY_SESSION_WITH_ROUTE, request, reply, 0)
                if (!withRoute) {
                    request.setDataPosition(0)
                    reply.setDataSize(0); reply.setDataPosition(0)
                    check(service.transact(REQUEST_GATEWAY_SESSION, request, reply, 0))
                }
                require(reply.dataSize() <= 262_144)
                reply.readException()
                if (reply.readInt() != ACCEPTED) reason = reply.readString()?.takeIf { it.matches(Regex("[A-Z_]{1,80}")) } ?: "INVALID_GATEWAY_RESPONSE"
                else {
                    val origin = GatewayOrigin.parse(reply.readString().orEmpty()) ?: error("INVALID_GATEWAY_RESPONSE")
                    val ca = reply.readString().orEmpty(); val token = reply.readString().orEmpty(); val id = reply.readString().orEmpty()
                    val issued = reply.readLong(); val expiry = reply.readLong()
                    val fallback = if (withRoute) reply.readString() else null
                    fresh = LuminaGatewaySession(origin, ca, token, id, issued, expiry, requireNotNull(role), wallClock, fallback)
                    require(reply.dataAvail() == 0)
                }
            } catch (_: Exception) { fresh?.close(); fresh = null; reason = "GATEWAY_NOT_PROVISIONED" }
            finally { request.recycle(); reply.recycle() }
            val result = fresh; val failure = reason
            handler.post {
                inFlight = false; requestDeadline = null
                if (closed || epoch != generation || binder !== service) { result?.close(); if (!closed) tick() }
                else if (result == null) { failed(failure ?: "GATEWAY_NOT_PROVISIONED"); tick() }
                else {
                    val previous = session; session = result; previous?.close()
                    failureAt = null; failures = 0; retryAt = 0; publish(); tick()
                }
            }
        }
    }
    private fun failed(reason: String) {
        failureReason = reason
        if (failureAt == null) failureAt = elapsed()
        retryAt = elapsed() + RETRY_MS[failures.coerceAtMost(RETRY_MS.lastIndex)]
        failures = (failures + 1).coerceAtMost(RETRY_MS.size)
        publish()
    }
    private fun disconnected(reason: String) {
        main(); if (closed) return
        generation++; binder?.let { runCatching { it.unlinkToDeath(death, 0) } }; binder = null
        if (bound) { runCatching { app.unbindService(connection) }; bound = false }
        failed(reason)
    }
    private fun publish() {
        if (session?.valid == false) {
            failureReason = "GATEWAY_SESSION_EXPIRED"
            failureAt = elapsed() - 30_000
        }
        val spent = failureAt?.let { (elapsed() - it).coerceAtLeast(0) }
        if (session?.valid == false || spent != null && spent >= 30_000) { session?.close(); session = null }
        val next = if (failureAt == null && session != null)
            GatewaySessionStatus(GatewaySessionPhase.READY, session!!.origin.url, session!!.identity)
        else GatewaySessionStatus(if (spent != null && spent < 30_000) GatewaySessionPhase.WAITING else GatewaySessionPhase.UNAVAILABLE,
            session?.origin?.url, session?.identity, failureReason, ((30_000 - (spent ?: 30_000)).coerceAtLeast(0) / 1000).toInt())
        if (status != next) { status = next; observers.toList().forEach { it(next) } }
    }
    private fun tick() {
        main(); handler.removeCallbacks(timer); if (closed) return
        if (bound && binder == null && elapsed() - bindStarted >= 3500) disconnected("BINDER_TIMEOUT")
        requestDeadline?.takeIf { elapsed() >= it }?.let { requestDeadline = null; failed("BINDER_TIMEOUT") }
        publish()
        if (elapsed() >= retryAt && !inFlight && (forceRefresh || session == null || session!!.expiresAtMs - wallClock() <= 60_000)) {
            val service = binder
            if (service == null) connect() else request(service)
        }
        handler.postDelayed(timer, 1000)
    }
    override fun close() {
        main(); if (closed) return
        closed = true; generation++; handler.removeCallbacks(timer)
        runCatching { app.unregisterReceiver(setupChanged) }
        binder?.let { runCatching { it.unlinkToDeath(death, 0) } }; binder = null
        if (bound) { runCatching { app.unbindService(connection) }; bound = false }
        session?.close(); session = null; worker.shutdownNow()
        status = GatewaySessionStatus(GatewaySessionPhase.CLOSED); observers.toList().forEach { it(status) }; observers.clear()
    }
    private fun main() = check(Looper.myLooper() === handler.looper)
    companion object {
        const val CONTROLLER = "com.no5.lumina.control"
        const val SETUP_CHANGED = "com.no5.lumina.control.GATEWAY_CONFIGURATION_CHANGED"
        const val LEASE_PERMISSION = "com.no5.lumina.control.permission.KNOB_LEASE"
        const val DESCRIPTOR = com.no5.lumina.control.KnobLeaseProtocol.DESCRIPTOR
        const val CONTRACT_VERSION = com.no5.lumina.control.KnobLeaseProtocol.VERSION
        const val REQUEST_GATEWAY_SESSION = com.no5.lumina.control.KnobLeaseProtocol.REQUEST_GATEWAY_SESSION
        const val REQUEST_GATEWAY_SESSION_WITH_ROUTE = com.no5.lumina.control.KnobLeaseProtocol.REQUEST_GATEWAY_SESSION_WITH_ROUTE
        const val ACCEPTED = com.no5.lumina.control.KnobLeaseProtocol.RESULT_ACCEPTED
        val SERVICE = ComponentName(CONTROLLER, "$CONTROLLER.LuminaControlService")
        val SETUP = ComponentName(CONTROLLER, "$CONTROLLER.GatewaySetupActivity")
        private val RETRY_MS = longArrayOf(1000, 2000, 5000, 10_000, 15_000)
    }
}
