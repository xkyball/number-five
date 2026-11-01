package com.no5.helm.control

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.no5.gateway.HomeGatewayContract
import com.no5.header.HeaderConsumerState
import com.no5.header.android.HeaderAndroidContract
import com.no5.header.android.HeaderBinderHost
import com.no5.header.nativeui.No5SystemHeaderView
import com.no5.helm.MainActivity
import com.no5.helm.network.AndroidGatewayEndpointResolver
import com.no5.helm.LuminaSourceSettingsStore
import com.no5.helm.network.NetworkSourceSessionFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Shared HELM foreground service.  The V6 root bridge remains separately bounded; V7 durable
 * control is an Android-overlay path and is enabled only through the shell/root operator bridge.
 */
class HelmSourceService : Service() {
    /** Integration seam for the separate control Binder host; access only on the main thread. */
    internal val sourceOwner: HelmSourceOwner get() = checkNotNull(adoptedOwner) { "HELM source service is unavailable" }
    private var adoptedOwner: HelmSourceOwner? = null
    private var ownerReadiness: com.no5.domain.SourceSubscription? = null
    internal var startupFailure: RuntimeException? = null
        private set
    private var observer: ContentObserver? = null
    private var systemControl: HelmSystemControl? = null
    private var headerHost: HeaderBinderHost? = null
    private var rootConnections: HelmRootConnectionOwner? = null
    private var durable: LuminaDurableController? = null
    @Volatile private var headerExporter: HelmHeaderConsumerExporter? = null
    private var lastHeaderAttemptElapsed = -1L
    internal var sharedHeaderView: No5SystemHeaderView? = null
        private set
    internal fun headerState(): HeaderConsumerState? = headerHost?.current()

    override fun onCreate() {
        super.onCreate()
        HelmSourceOwner.mainThread()
        val candidateStartedElapsed = android.os.SystemClock.elapsedRealtime()
        // Binding alone must not bootstrap transports from a background or foreign client.
        val owner = HelmSourceRegistry.peek(applicationContext)
        if (owner == null || owner.isDisposed) {
            startupFailure = IllegalStateException("HELM source service has no prepared owner")
            claimStartup(this, owner)
            failStartup(this, owner)
            stopSelf(); return
        }
        adoptedOwner = owner
        retireReadyOwner(owner)
        claimStartup(this, owner)
        try {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "No5 HELM", NotificationManager.IMPORTANCE_LOW))
            // minSdk29: typed FGS overload exists; target35 declares its type permission.
            // No translation catalogue or product UI may delay this first promotion.
            startForeground(NOTIFICATION_ID, notification("HELM · AUDIO · SONAR"), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            ownerReadiness = owner.changes.subscribe {
                if (adoptedOwner === owner && owner.isDisposed) retireReadyOwner(owner)
            }
            owner.synchronizeHome(requestRefresh = false)
            val control = HelmSystemControl(applicationContext, owner,
                onForeground = { headerHost?.observeForeground(it) },
                onRootHeaderScope = { observeRootHeader(it) },
                onRootHeaderFailure = { headerExporter?.rootHookFailed() })
            systemControl = control
            val exporter = HelmHeaderConsumerExporter(control.host.controllerSession, headerExportTransportFactory)
            headerExporter = exporter
            val view = No5SystemHeaderView(applicationContext)
            sharedHeaderView = view
            val headers = HeaderBinderHost(applicationContext, control.host.controllerSession,
                onState = { state -> if (sharedHeaderView === view) view.render(state) },
                // Current generation only. The exporter captures actual origin tokens itself.
                onExportOrigin = { origin -> headerExporter?.captureOrigin(origin) })
            headerHost = headers
            view.render(headers.current())
            durable = LuminaDurableController(applicationContext, owner, view,
                onForeground = { observed -> headers.observeForeground(observed) }, inputOwnedByLumina = true).also { candidate ->
                activeDurable = candidate
                // Boot/restart only resumes a prior explicit enable; missing overlay permission
                // makes the controller fail closed without leaving an input listener behind.
                if (candidate.requested()) candidate.enable()
            }
            // The root bridge belongs only to the separately bounded DEBUG acceptance candidate.
            // A signed V7 release has no root channel, exporter, or finite candidate budget.
            if (headerExportTransportFactory != null) {
                exporter.start() // Waits for independently accepted V2 HELLO/FOCUS before connecting.
                val handler = Handler(Looper.getMainLooper())
                val connections = HelmRootConnectionOwner(object : HelmRootConnectionOwner.Main {
                    override fun check() = HelmSourceOwner.mainThread()
                    override fun postDelayed(task: Runnable, delayMs: Long) = handler.postDelayed(task, delayMs)
                    override fun remove(task: Runnable) = handler.removeCallbacks(task)
                }, android.os.SystemClock::elapsedRealtime, factory = { callbacks ->
                    val channel = rootChannelFactory(applicationContext, callbacks.connected, callbacks.packet, callbacks.disconnected)
                    object : HelmRootConnectionOwner.Channel {
                        override fun start() = channel.start()
                        override fun close() = channel.close()
                    }
                }, onConnected = {
                    if (systemControl === control) {
                        control.beginAuthenticatedRootConnection()
                        // Each fresh wire connection gets a fresh one-shot exporter. No cached replay.
                        headerExporter?.close()
                        lastHeaderAttemptElapsed = android.os.SystemClock.elapsedRealtime()
                        headerExporter = HelmHeaderConsumerExporter(control.host.controllerSession, headerExportTransportFactory)
                            .also { it.start() }
                    }
                }, onPacket = { packet ->
                    if (systemControl !== control) false
                    else { control.acceptFromAuthenticatedRoot(packet); control.isRootChannelValid }
                }, onDisconnected = {
                    if (systemControl === control) {
                        control.revokeRoot()
                        headerExporter?.close(); headerExporter = null
                    }
                }, budgetStartedElapsed = candidateStartedElapsed)
                rootConnections = connections
                connections.start()
            }
            check(!owner.isDisposed && HelmSourceRegistry.peek(applicationContext) === owner &&
                startupOwner === owner && startupService === this) {
                "HELM source owner changed during service initialization"
            }
            readyOwner = owner
            readyService = this
            mutableForegroundStartupFailure.value = false
            mutableForegroundReadiness.value = true
            refreshNotificationLanguage(applicationContext)
        } catch (failure: Throwable) {
            startupFailure = failure as? RuntimeException ?: IllegalStateException("HELM startup failed", failure)
            failStartup(this, owner)
            try { releaseOwner() } catch (cleanup: Throwable) { if (cleanup !== failure) failure.addSuppressed(cleanup) }
            finally { stopSelf() }
            if (failure !is RuntimeException) throw failure
        }
    }

    private fun notification(description: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play).setContentTitle("No5 HELM")
            .setContentText(description).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).build()
    }

    private fun observeRootHeader(mapping: HelmHeaderFocusMapping) {
        HelmSourceOwner.mainThread()
        val control = systemControl ?: return
        val current = headerExporter
        val now = android.os.SystemClock.elapsedRealtime()
        // A failed header socket never revives its old publisher. Retry only from a NEW actual
        // accepted Root FOCUS, within the same finite Rootconnection owner. No second WMS poller.
        if (mapping.rootFocus != null && control.isRootChannelValid &&
            (current == null || current.status == HelmHeaderConsumerExporter.Status.RETIRED) &&
            (lastHeaderAttemptElapsed < 0 || now >= lastHeaderAttemptElapsed && now - lastHeaderAttemptElapsed >= 250)) {
            headerExporter = null
            current?.close()
            lastHeaderAttemptElapsed = now
            headerExporter = HelmHeaderConsumerExporter(control.host.controllerSession, headerExportTransportFactory)
                .also { it.start() }
        }
        headerExporter?.observeRoot(mapping)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        HelmSourceOwner.mainThread()
        when (intent?.action) {
            ACTION_ENABLE -> durable?.enable()
            ACTION_DISABLE -> durable?.disable("operator_disable")
            ACTION_HIDE_HEADER -> durable?.setHeaderVisible(false)
            ACTION_SHOW_HEADER -> durable?.setHeaderVisible(true)
            ACTION_RECONNECT -> durable?.reconnect("boot_or_service_restart")
        }
        // The foreground source owner is intentionally durable.  A process restart rebuilds the
        // owner, verifies the saved enable state and either restores V7 or remains inactive.
        return START_STICKY
    }
    override fun onBind(intent: Intent?): IBinder? {
        if (adoptedOwner?.isDisposed != false) return null
        return if (intent?.action == HeaderAndroidContract.BIND_ACTION) headerHost?.binder
        else durable?.takeIf { it.isEnabled }?.binder ?: systemControl?.binder
    }
    override fun dump(fd: java.io.FileDescriptor, writer: java.io.PrintWriter, args: Array<out String>?) {
        // Explicit Android DUMP only; no runtime-stat collection in knob admission or painting.
        writer.println("runtimeGc count=${android.os.Debug.getRuntimeStat("art.gc.gc-count")}" +
            " timeMs=${android.os.Debug.getRuntimeStat("art.gc.gc-time")}" +
            " blockingCount=${android.os.Debug.getRuntimeStat("art.gc.blocking-gc-count")}" +
            " blockingMs=${android.os.Debug.getRuntimeStat("art.gc.blocking-gc-time")}")
        com.no5.helm.SonarPaintDiagnostics.dump(writer)
    }
    private fun releaseOwner() {
        // Relinquish identities before any callback/close can reenter the service.
        val observed = observer; observer = null
        val exporter = headerExporter; headerExporter = null
        val connections = rootConnections; rootConnections = null
        val control = systemControl; systemControl = null
        val durableControl = durable; durable = null
        val headers = headerHost; headerHost = null
        val view = sharedHeaderView; sharedHeaderView = null
        val owner = adoptedOwner; adoptedOwner = null
        val readiness = ownerReadiness; ownerReadiness = null
        if (readyService === this) owner?.let { retireReadyOwner(it) }
        if (startupService === this) startupService = null
        var failure: Throwable? = null
        listOf<() -> Unit>(
            { readiness?.close() },
            { observed?.let { contentResolver.unregisterContentObserver(it) } },
            { exporter?.close() }, { connections?.close() }, { durableControl?.close() }, { control?.close() },
            { headers?.close() }, { view?.close() }, { owner?.let { HelmSourceRegistry.dispose(it) } }
        ).forEach { step ->
            try { step() } catch (next: Throwable) {
                if (failure == null) failure = next else if (failure !== next) failure!!.addSuppressed(next)
            }
        }
        failure?.let { throw it }
    }
    override fun onDestroy() {
        try { releaseOwner() }
        finally { activeDurable = null; stopForeground(STOP_FOREGROUND_REMOVE); super.onDestroy() }
    }

    companion object {
        /** Only the explicitly reviewed <=90s DEBUG candidate uses the UID0/same-session boundary.
         * Future release builds remain default-deny; no production/RootFacts/boot admission. */
        internal var headerExportTransportFactory: HelmHeaderTransportFactory? =
            if (com.no5.helm.BuildConfig.DEBUG) HelmHeaderSocketTransport.nativeCandidateFactory() else null
        internal var rootChannelFactory: (Context, () -> Unit, (com.no5.control.wire.BridgeWire.Packet) -> Unit, (String) -> Unit) -> HelmRootChannel =
            { context, connected, packet, disconnected -> HelmRootChannel(context, connected, packet, disconnected) }
        internal fun resetRootChannelFactoryForTest() {
            headerExportTransportFactory = null
            rootChannelFactory = { context, connected, packet, disconnected -> HelmRootChannel(context, connected, packet, disconnected) }
        }
        internal const val CHANNEL_ID = "no5-system-control"
        internal const val NOTIFICATION_ID = 504
        const val ACTION_ENABLE = "com.no5.helm.action.LUMINA_ENABLE"
        const val ACTION_DISABLE = "com.no5.helm.action.LUMINA_DISABLE"
        const val ACTION_HIDE_HEADER = "com.no5.helm.action.LUMINA_HIDE_HEADER"
        const val ACTION_SHOW_HEADER = "com.no5.helm.action.LUMINA_SHOW_HEADER"
        const val ACTION_RECONNECT = "com.no5.helm.action.LUMINA_RECONNECT"
        @Volatile private var activeDurable: LuminaDurableController? = null
        private val mutableForegroundReadiness = MutableStateFlow(false)
        internal val foregroundReadiness: StateFlow<Boolean> = mutableForegroundReadiness.asStateFlow()
        private val mutableForegroundStartupFailure = MutableStateFlow(false)
        internal val foregroundStartupFailure: StateFlow<Boolean> = mutableForegroundStartupFailure.asStateFlow()
        private var readyOwner: HelmSourceOwner? = null
        private var readyService: HelmSourceService? = null
        private var startupOwner: HelmSourceOwner? = null
        private var startupService: HelmSourceService? = null

        internal fun foregroundOwnerPrepared(context: Context, expected: HelmSourceOwner) {
            HelmSourceOwner.mainThread()
            if (isForegroundReady(context)) return
            if (startupOwner !== expected || mutableForegroundStartupFailure.value) {
                startupOwner = expected; startupService = null
                mutableForegroundStartupFailure.value = false
            }
        }

        private fun claimStartup(service: HelmSourceService, owner: HelmSourceOwner?) {
            HelmSourceOwner.mainThread()
            if (HelmSourceRegistry.peek(service.applicationContext) !== owner) return
            startupOwner = owner; startupService = service
            mutableForegroundStartupFailure.value = false
        }

        private fun failStartup(service: HelmSourceService, owner: HelmSourceOwner?) {
            HelmSourceOwner.mainThread()
            if (startupService !== service || startupOwner !== owner ||
                HelmSourceRegistry.peek(service.applicationContext) !== owner) return
            mutableForegroundStartupFailure.value = true
        }

        private fun failForegroundStart(context: Context) {
            val owner = HelmSourceRegistry.peek(context)
            startupOwner = owner; startupService = null
            mutableForegroundStartupFailure.value = true
        }

        internal fun resetForegroundStateForTest() {
            HelmSourceOwner.mainThread()
            readyOwner = null; readyService = null; startupOwner = null; startupService = null
            mutableForegroundReadiness.value = false; mutableForegroundStartupFailure.value = false
        }

        /** Main-owned identity fence: a prepared owner alone is never foreground-ready. */
        internal fun isForegroundReady(context: Context): Boolean {
            HelmSourceOwner.mainThread()
            val owner = readyOwner ?: return false
            val service = readyService ?: return false
            return mutableForegroundReadiness.value && !owner.isDisposed && service.adoptedOwner === owner &&
                service.startupFailure == null && HelmSourceRegistry.peek(context.applicationContext) === owner
        }

        internal fun retireReadyOwner(expected: HelmSourceOwner) {
            HelmSourceOwner.mainThread()
            if (readyOwner !== expected) return
            readyOwner = null; readyService = null
            mutableForegroundReadiness.value = false
        }

        /** Confirmed language observer only; no UI client, transport or large catalogue is created. */
        internal fun refreshNotificationLanguage(context: Context) {
            HelmSourceOwner.mainThread()
            if (!isForegroundReady(context)) return
            val service = readyService ?: return
            val description = when (com.no5.ui.UiLanguage.current(context.applicationContext)) {
                "en" -> "Shared sources for HELM, AUDIO and SONAR"
                "af" -> "Gedeelde bronne vir HELM, AUDIO en SONAR"
                else -> "Gemeinsame Quellen für HELM, AUDIO und SONAR"
            }
            // Notification access is optional after the first successful FGS promotion.
            runCatching { service.getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, service.notification(description)) }
        }

        /** Starts through the app process only; no unprotected broadcast control surface exists. */
        fun startDurable(context: Context, action: String) {
            val app = context.applicationContext
            Handler(Looper.getMainLooper()).post {
                HelmSourceOwner.mainThread()
                try {
                    HelmSourceRegistry.prepare(app)
                    app.startForegroundService(Intent(app, HelmSourceService::class.java).setAction(action))
                } catch (_: RuntimeException) {
                    failForegroundStart(app)
                    HelmSourceRegistry.peek(app)?.let { HelmSourceRegistry.dispose(it) }
                }
            }
        }
        fun durableStatus(context: Context): String = activeDurable?.status()
            ?: context.applicationContext.getSharedPreferences(LuminaDurableController.PREFERENCES, Context.MODE_PRIVATE)
                .getString(LuminaDurableController.KEY_LAST, "inactive;no_durable_service")!!
        fun durableMatrix(context: Context): List<String> = activeDurable?.matrix().orEmpty()
        /** Call from an explicit foreground Activity before the first product ViewModel read. */
        fun startFromForeground(activity: Activity): Boolean {
            HelmSourceOwner.mainThread()
            return try {
                activity.startForegroundService(Intent(activity, HelmSourceService::class.java))
                // Both callbacks run on the same main Looper. Service.onCreate adopts this owner.
                HelmSourceRegistry.prepare(activity.applicationContext)
                true
            } catch (_: RuntimeException) {
                failForegroundStart(activity.applicationContext)
                HelmSourceRegistry.peek(activity.applicationContext)?.let { HelmSourceRegistry.dispose(it) }
                activity.stopService(Intent(activity, HelmSourceService::class.java))
                false
            }
        }
    }
}

/** Same-process handoff across asynchronous service creation, never a second product factory. */
internal object HelmSourceRegistry {
    private var app: Context? = null
    private var owner: HelmSourceOwner? = null
    fun peek(context: Context): HelmSourceOwner? {
        HelmSourceOwner.mainThread()
        return owner.takeIf { app === context.applicationContext }
    }
    fun requireOwner(context: Context): HelmSourceOwner = checkNotNull(peek(context)) {
        "Start HelmSourceService explicitly from the foreground Activity before acquiring a source lease"
    }
    fun prepare(context: Context): HelmSourceOwner {
        HelmSourceOwner.mainThread()
        peek(context)?.takeUnless { it.isDisposed }?.let { return it }
        owner?.let { HelmSourceService.retireReadyOwner(it); it.disposeService() }
        val applicationContext = context.applicationContext
        val store = LuminaSourceSettingsStore(applicationContext)
        val created = try {
            val factory = NetworkSourceSessionFactory(securityProfiles = requireNotNull(store.cachedSecurityProfiles),
                endpointResolver = store.endpointResolver)
            try { HelmSourceOwner(store, factory) }
            catch (failure: RuntimeException) { factory.close(); throw failure }
        } catch (failure: RuntimeException) { store.close(); throw failure }
        app = applicationContext; owner = created
        HelmSourceService.foregroundOwnerPrepared(applicationContext, created)
        return created
    }
    fun dispose(expected: HelmSourceOwner) {
        HelmSourceOwner.mainThread()
        HelmSourceService.retireReadyOwner(expected)
        expected.disposeService()
        // Retain a disposed record until the next explicit foreground start. Existing ViewModels
        // get unavailable leases instead of silently creating a standalone connection.
    }
    internal fun installForTest(context: Context, fixture: HelmSourceOwner) {
        HelmSourceOwner.mainThread()
        owner?.let { HelmSourceService.retireReadyOwner(it); it.disposeService() }
        app = context.applicationContext; owner = fixture
        HelmSourceService.foregroundOwnerPrepared(context, fixture)
    }
    internal fun resetForTest() {
        HelmSourceOwner.mainThread()
        owner?.let { HelmSourceService.retireReadyOwner(it); it.disposeService() }
        owner = null; app = null
        HelmSourceService.resetForegroundStateForTest()
    }
}
