package com.no5.helm.control

import android.os.Looper
import com.no5.domain.*
import com.no5.helm.AndroidSourceSettingsStore
import com.no5.helm.DeviceGatewaySettingsStore
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** The service owns the real coordinator/factory. Consumers only borrow its current ports. */
class HelmSourceOwner(
    private val store: SourceSettingsStore,
    private val factory: SourceSessionFactory,
    sessionId: () -> String = { UUID.randomUUID().toString() },
) : AutoCloseable {
    private val coordinator = run { mainThread(); SourceCoordinator(store, factory, sessionId) }
    private val disposed = AtomicBoolean(false)
    private val leases = mutableSetOf<SessionLease>()
    private val sonarDiagnosticsConsumers = java.util.IdentityHashMap<SonarSourcePort, MutableSet<Any>>()
    private var warning = store.warningOrNull()
    private var acceptedHomeIdentity = (store as? DeviceGatewaySettingsStore)?.homeIdentity
    @Volatile private var sessionRecoveryPending = false
    private var recoveryCloseFailed = false
    private var recoveryReason: String? = null
    @Volatile var sessionGeneration: Long = generations.incrementAndGet(); private set
    private val revisions = MutableSourceFeed(sessionGeneration)
    /** Main-thread notifications; reload failure and disposal also retire the previous generation. */
    val changes: SourceFeed<Long> get() { mainThread(); return revisions }
    val isDisposed: Boolean get() = disposed.get()
    val homeIdentity: String? get() { mainThread(); return (store as? DeviceGatewaySettingsStore)?.homeIdentity }
    val luminaGateway get() = (store as? com.no5.helm.LuminaSourceSettingsStore)?.gateway
    val config: SourceConfig get() { mainThread(); return coordinator.config }
    val settingsWarning: String? get() { mainThread(); return if (isDisposed) "No5 Quellen-Dienst beendet" else warning }
    val endpointHistory: List<String> get() { mainThread(); return store.endpointHistory() }
    private val reconnect = (store as? DeviceGatewaySettingsStore)?.let {
        HelmSourceReconnectCoordinator({ synchronizeHome() }, { synchronizeHome(requestRefresh = false) })
    }
    /** Read-only diagnosis. Retry/timing changes must never emit the audio generation feed. */
    val recoveryStatus: HelmSourceRecoveryStatus get() {
        mainThread()
        val home = store as? DeviceGatewaySettingsStore
        val read = home?.readDiagnostic
        return HelmSourceRecoveryStatus(
            if (isDisposed) "CLOSED" else read?.phase?.name ?: "IDLE",
            if (isDisposed) "CLOSED" else recoveryReason ?: read?.reason?.name ?: "NOT_STARTED",
            read?.attempt ?: 0, read?.startedUptimeMs ?: 0, read?.finishedUptimeMs ?: 0,
            read?.deadlineUptimeMs ?: 0, reconnect?.nextRetryUptimeMs,
            !isDisposed && !sessionRecoveryPending && coordinator.config.connectionEnabled && home?.localSettingsBlocked != true,
            !isDisposed && home?.refreshInFlight == true)
    }
    private val homeObservation = (store as? DeviceGatewaySettingsStore)?.observeHome {
        // Reader completion is posted; do not re-enter an audio feed or source lock.
        if (!isDisposed) reconnect?.observed()
    }

    fun borrowSession(): No5SourceSession {
        mainThread()
        val raw = coordinator.session
        lateinit var lease: SessionLease
        lease = SessionLease(raw, sessionGeneration,
            { !disposed.get() && !sessionRecoveryPending && it == sessionGeneration && coordinator.session === raw },
            { leases.remove(lease) },
            { source, consumer, visible ->
                mainThread()
                val consumers = sonarDiagnosticsConsumers[source]
                    ?: if (visible) mutableSetOf<Any>().also { sonarDiagnosticsConsumers[source] = it } else null
                if (consumers != null) {
                    val wasVisible = consumers.isNotEmpty()
                    if (visible) consumers.add(consumer) else consumers.remove(consumer)
                    val isVisible = consumers.isNotEmpty()
                    if (!isVisible) sonarDiagnosticsConsumers.remove(source)
                    // Retired consumers cannot switch the replacement source on or off.
                    if (wasVisible != isVisible && !disposed.get() && !sessionRecoveryPending && coordinator.session === raw)
                        source.setDiagnosticsVisible(isVisible)
                }
            })
        return lease.also { leases.add(it) }
    }

    /** A separate consumer lease over the SAME underlying audio adapter, never a new transport. */
    fun borrowAudio(): AudioSourcePort {
        mainThread()
        val lease = borrowSession() as SessionLease
        val audio = lease.audio
        return object : AudioSourcePort by audio {
            // Explicit extension forwarding also invalidates stale incremental delegation bytecode.
            override val sound get() = audio.sound
            override fun close() = lease.close()
        }
    }

    fun apply(requested: SourceConfig): SourceApplyResult {
        mainThread()
        if (isDisposed) return SourceApplyResult(false, error = "HELM source service is unavailable")
        if (!retryRetiredClose()) return SourceApplyResult(false, error = "Source retirement could not be confirmed")
        val previous = coordinator.session
        val result = coordinator.apply(requested)
        if (!result.applied) return result
        if (coordinator.session !== previous) retireLeases()
        // Preserve the explicit repair path for a damaged persisted source record.
        if (!result.changed && warning != null) {
            try { store.save(coordinator.config) }
            catch (failure: RuntimeException) {
                return SourceApplyResult(false, error = failure.message ?: "Cannot repair source settings")
            }
        }
        warning = result.warning ?: store.warningOrNull()
        if (coordinator.session !== previous) { sessionRecoveryPending = false; recoveryCloseFailed = false }
        if (!sessionRecoveryPending) acceptedHomeIdentity = (store as? DeviceGatewaySettingsStore)?.homeIdentity
        revisions.emit(sessionGeneration)
        return result
    }

    fun reload(): SourceApplyResult {
        mainThread()
        if (isDisposed) return SourceApplyResult(false, error = "HELM source service is unavailable")
        if (!retryRetiredClose()) {
            reconnect?.completed(false, false)
            return SourceApplyResult(false, error = "Source retirement could not be confirmed")
        }
        // Reload deliberately closes the old transport even when replacement construction fails.
        val wasPending = sessionRecoveryPending
        if (!wasPending) retireLeases()
        val result = coordinator.reload()
        sessionRecoveryPending = !result.applied
        if (result.applied) {
            if (wasPending) retireLeases()
            acceptedHomeIdentity = (store as? DeviceGatewaySettingsStore)?.homeIdentity
        }
        warning = result.warning ?: store.warningOrNull()
        recoveryReason = if (result.applied) null else "SESSION_RELOAD_FAILED"
        if (!wasPending || result.applied) revisions.emit(sessionGeneration)
        reconnect?.completed(result.applied && coordinator.config.connectionEnabled,
            (store as? DeviceGatewaySettingsStore)?.refreshInFlight == true,
            blocked = (store as? DeviceGatewaySettingsStore)?.localSettingsBlocked == true)
        return result
    }

    fun synchronizeHome(requestRefresh: Boolean = true): Boolean {
        mainThread()
        if (isDisposed || store !is DeviceGatewaySettingsStore) return false
        if (requestRefresh) {
            reconnect?.requested()
            // Observe, manual refresh and timer share the existing single request.
            if (!store.refreshInFlight) store.requestHomeRefresh()
        }
        if (store.refreshInFlight) {
            reconnect?.completed(false, true)
            return !sessionRecoveryPending
        }
        val desired = store.load()
        if (store.localSettingsBlocked) {
            // Automatic Home discovery is never permission to repair local persisted settings.
            if (coordinator.config.connectionEnabled && !sessionRecoveryPending) {
                retireCurrentForRecovery()
            }
            recoveryReason = if (recoveryCloseFailed) "SESSION_CLOSE_FAILED" else "LOCAL_SETTINGS_BLOCKED"
            warning = store.warning
            reconnect?.completed(false, false, blocked = true)
            return false
        }
        if (!retryRetiredClose()) {
            warning = "Lumina-Gateway: SESSION_CLOSE_FAILED."
            reconnect?.completed(false, false)
            return false
        }
        val previous = coordinator.session
        val applying = desired != coordinator.config
        val identityChanged = store.homeIdentity != acceptedHomeIdentity
        val credentialsRenewed = !applying && !sessionRecoveryPending && identityChanged &&
            runCatching { (previous as? com.no5.helm.network.GatewayCredentialRenewal)?.renewGatewayCredentials() == true }.getOrDefault(false)
        var reloaded = !applying && (sessionRecoveryPending || identityChanged && !credentialsRenewed)
        var result = when {
            applying -> coordinator.apply(desired)
            reloaded -> reload()
            // Service and Activity may observe the same notification: never reconnect twice.
            else -> SourceApplyResult(true)
        }
        if (result.applied && sessionRecoveryPending && coordinator.session === previous) {
            // Display-only saves do not revive the raw session closed by a failed reload.
            reloaded = true
            result = reload()
        }
        // Coordinator.apply persists the existing preference selection. The local-corruption
        // gate above prevents this automatic path from repairing a damaged local record.
        if (applying && !reloaded && coordinator.session !== previous) {
            retireLeases(); revisions.emit(sessionGeneration)
        }
        if (applying && !result.applied && !sessionRecoveryPending) {
            // Changed Home authority may not leave an older privileged transport alive.
            retireCurrentForRecovery()
        }
        if (result.applied) {
            sessionRecoveryPending = false; recoveryCloseFailed = false; acceptedHomeIdentity = store.homeIdentity
            recoveryReason = null
        } else recoveryReason = if (recoveryCloseFailed) "SESSION_CLOSE_FAILED" else
            if (reloaded) "SESSION_RELOAD_FAILED" else "SESSION_APPLY_FAILED"
        warning = store.warning ?: result.warning ?: if (!result.applied) "Lumina-Gateway: ${recoveryReason}." else null
        reconnect?.completed(result.applied && desired.connectionEnabled && !sessionRecoveryPending, false)
        return result.applied
    }

    private fun retireCurrentForRecovery() {
        // Publish invalidity before touching a potentially throwing transport implementation.
        sessionRecoveryPending = true
        retireLeases()
        try { coordinator.session.close(); recoveryCloseFailed = false }
        catch (_: RuntimeException) { recoveryCloseFailed = true }
        revisions.emit(sessionGeneration)
    }

    private fun retryRetiredClose(): Boolean {
        if (!recoveryCloseFailed) return true
        return try { coordinator.session.close(); recoveryCloseFailed = false; true }
        catch (_: RuntimeException) { recoveryReason = "SESSION_CLOSE_FAILED"; false }
    }

    private fun retireLeases() {
        val retired = leases.toList()
        leases.clear()
        sessionGeneration = generations.incrementAndGet()
        retired.forEach { lease ->
            try { lease.close() } catch (_: RuntimeException) {
                warning = "A source consumer reported a disposal error; its generation is retired."
            }
        }
    }

    fun disposeService() = close()
    override fun close() {
        mainThread()
        if (!disposed.compareAndSet(false, true)) return
        reconnect?.close()
        homeObservation?.close()
        (store as? DeviceGatewaySettingsStore)?.close()
        retireLeases()
        try { coordinator.close() }
        finally {
            try { (factory as? AutoCloseable)?.close() }
            finally { revisions.emit(sessionGeneration); revisions.close() }
        }
    }

    companion object {
        private val generations = AtomicLong()
        internal fun mainThread() {
            check(Looper.myLooper() === Looper.getMainLooper()) { "HELM source ownership requires the main thread" }
        }
    }
}

private fun SourceSettingsStore.warningOrNull(): String? = when (this) {
    is AndroidSourceSettingsStore -> warning
    is DeviceGatewaySettingsStore -> warning
    else -> null
}

private class PortLease(private val sessionAlive: () -> Boolean) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val subscriptions = mutableSetOf<SourceSubscription>()
    val alive: Boolean get() = !closed.get() && sessionAlive()
    val isClosed: Boolean get() = closed.get()
    fun usable(): Boolean { HelmSourceOwner.mainThread(); return alive }
    fun <T> feed(raw: SourceFeed<T>): SourceFeed<T> = object : SourceFeed<T> {
        @Volatile private var last = raw.value
        override val value: T get() = if (alive) raw.value.also { last = it } else last
        override fun subscribe(listener: (T) -> Unit): SourceSubscription {
            if (!alive) return SourceSubscription { }
            val rawSubscription = raw.subscribe { if (alive) { last = it; listener(it) } }
            val once = AtomicBoolean(false)
            lateinit var subscription: SourceSubscription
            subscription = SourceSubscription {
                if (once.compareAndSet(false, true)) {
                    synchronized(subscriptions) { subscriptions.remove(subscription) }
                    rawSubscription.close()
                }
            }
            synchronized(subscriptions) {
                if (alive) subscriptions.add(subscription) else subscription.close()
            }
            return subscription
        }
    }
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val retired = synchronized(subscriptions) { subscriptions.toList().also { subscriptions.clear() } }
        var failure: RuntimeException? = null
        retired.forEach { try { it.close() } catch (error: RuntimeException) { if (failure == null) failure = error } }
        failure?.let { throw it }
    }
}

private class SessionLease(
    private val raw: No5SourceSession,
    private val generation: Long,
    private val current: (Long) -> Boolean,
    private val released: () -> Unit,
    private val diagnosticsDemand: (SonarSourcePort, Any, Boolean) -> Unit,
) : No5SourceSession {
    private val closed = AtomicBoolean(false)
    private val ports = mutableListOf<PortLease>()
    private var helmPort: HelmSource? = null
    private var audioPort: AudioSourcePort? = null
    private var sonarPort: SonarSourcePort? = null
    private var weatherPort: WeatherSourcePort? = null
    private fun port(): PortLease = PortLease { !closed.get() && current(generation) }.also { ports.add(it) }
    override val identity: SourceIdentity get() = raw.identity
    // Port close releases only that consumer. Re-entering a composition obtains a fresh lease.
    override val helm: HelmSource get() { HelmSourceOwner.mainThread(); return helmPort ?: borrowHelm().also { helmPort = it } }
    override val audio: AudioSourcePort get() { HelmSourceOwner.mainThread(); return audioPort ?: borrowAudio().also { audioPort = it } }
    override val sonar: SonarSourcePort get() { HelmSourceOwner.mainThread(); return sonarPort ?: borrowSonar().also { sonarPort = it } }
    override val weather: WeatherSourcePort get() { HelmSourceOwner.mainThread(); return weatherPort ?: borrowWeather().also { weatherPort = it } }

    private fun borrowHelm(): HelmSource {
        val lease = port(); val delegate = raw.helm
        return object : HelmSource by delegate {
            override val snapshots = lease.feed(delegate.snapshots)
            override val demo = delegate.demo?.let { demo -> object : HelmDemoControls {
                override fun update(input: HelmInput) = if (lease.usable()) demo.update(input) else HelmUpdate(false, snapshots.value, "Source lease closed")
                override fun reset() = if (lease.usable()) demo.reset() else snapshots.value
            } }
            override fun advance(elapsedMillis: Long) { if (lease.usable()) delegate.advance(elapsedMillis) }
            override fun close() { HelmSourceOwner.mainThread(); if (!lease.isClosed) { lease.close(); ports.remove(lease); helmPort = null } }
        }
    }

    private fun borrowAudio(): AudioSourcePort {
        val lease = port(); val delegate = raw.audio
        return object : AudioSourcePort by delegate {
            override val sound get() = delegate.sound
            override val state = lease.feed(delegate.state)
            override val outcomes = lease.feed(delegate.outcomes)
            override val demoControls = delegate.demoControls?.let { demo -> object : AudioDemoControls {
                override val demoState = lease.feed(demo.demoState)
                override fun loadFixture(fixture: AudioFixture) { if (lease.usable()) demo.loadFixture(fixture) }
                override fun start() { if (lease.usable()) demo.start() }
                override fun stop() { if (lease.usable()) demo.stop() }
                override fun reset() { if (lease.usable()) demo.reset() }
                override fun failNextWrite() { if (lease.usable()) demo.failNextWrite() }
                override fun setOffline(target: AudioOfflineTarget, offline: Boolean) { if (lease.usable()) demo.setOffline(target, offline) }
                override fun completePending(requestId: String, verified: Boolean) { if (lease.usable()) demo.completePending(requestId, verified) }
            } }
            private var ownsFader = false
            override val faderStream = delegate.faderStream?.let { stream -> object : AudioFaderStreamPort {
                override val status get() = if (lease.alive) stream.status else "Source lease closed"
                override val active get() = lease.alive && stream.active
                override val channel get() = if (lease.alive) stream.channel else null
                override fun available(channel: AudioChannel, valueDb: Double) = lease.usable() && stream.available(channel, valueDb)
                override fun preview(channel: AudioChannel, valueDb: Double): Boolean =
                    (lease.usable() && stream.preview(channel, valueDb)).also { if (it) ownsFader = true }
                override fun finish(): AudioCommandOutcome? {
                    if (!lease.usable() || !ownsFader) return null
                    ownsFader = false
                    return stream.finish()
                }
                override fun cancel() {
                    if (lease.usable() && ownsFader) { ownsFader = false; stream.cancel() }
                }
            } }
            override fun available(command: AudioCommand) = lease.usable() && delegate.available(command)
            override fun submit(command: AudioCommand): AudioCommandOutcome {
                if (lease.usable()) {
                    val effect = HelmAudioEffectContext.current()
                    if (effect == null || effect.bindOwner(generation, identity.sessionId) { !closed.get() && current(generation) && lease.alive })
                        return delegate.submit(command)
                }
                return AudioCommandOutcome(identity.sessionId, null, command, AudioCommandStatus.REJECTED, 0, "Source/knob lease closed; no command sent")
            }
            override fun tick() { if (lease.usable()) delegate.tick() }
            override fun close() {
                HelmSourceOwner.mainThread()
                if (lease.isClosed) return
                if (ownsFader && current(generation)) { ownsFader = false; delegate.faderStream?.cancel() }
                lease.close(); ports.remove(lease); audioPort = null
            }
        }
    }

    private fun borrowSonar(): SonarSourcePort {
        val lease = port(); val delegate = raw.sonar
        return object : SonarSourcePort by delegate {
            override val status = lease.feed(delegate.status)
            override val capabilities = lease.feed(delegate.capabilities)
            override val frames = lease.feed(delegate.frames)
            override val frameBatches = delegate.frameBatches?.let { lease.feed(it) }
            override fun receiveDiagnostics() = if (lease.usable()) delegate.receiveDiagnostics() else null
            override val outcomes = lease.feed(delegate.outcomes)
            override val filterState = lease.feed(delegate.filterState)
            override val filterOutcomes = lease.feed(delegate.filterOutcomes)
            override val sourceState = lease.feed(delegate.sourceState)
            override val sourceOutcomes = lease.feed(delegate.sourceOutcomes)
            override val diagnostics = lease.feed(delegate.diagnostics)
            private var ownsDiagnostics = false
            override fun setDiagnosticsVisible(visible: Boolean) {
                HelmSourceOwner.mainThread()
                if (visible == ownsDiagnostics || visible && !lease.usable()) return
                diagnosticsDemand(delegate, lease, visible)
                ownsDiagnostics = visible
            }
            override fun selectSource(request: SonarSourceSelectionRequest) =
                if (lease.usable()) delegate.selectSource(request) else SonarSourceSelectionOutcome(
                    identity.sessionId, request.requestId, null, request.source,
                    SonarFilterCommandStatus.REJECTED, "CLOSED")
            override fun sourceResult(commandId: Long) = if (lease.usable()) delegate.sourceResult(commandId) else null
            override fun filter(request: SonarFilterRequest) = if(lease.usable())delegate.filter(request) else
                SonarFilterOutcome(identity.sessionId,request.requestId,null,request.mode,SonarFilterCommandStatus.REJECTED,"CLOSED")
            override fun filterResult(commandId: Long) = if(lease.usable())delegate.filterResult(commandId) else null
            private fun rejected(request: SonarAcquisitionRequest) = SonarAcquisitionOutcome.Rejected(
                identity.sessionId, request.requestId, SonarRejection.CLOSED, "Source lease closed; no command sent")
            override fun start(request: SonarAcquisitionRequest) = if (lease.usable()) delegate.start(request) else rejected(request)
            override fun stop(request: SonarAcquisitionRequest) = if (lease.usable()) delegate.stop(request) else rejected(request)
            override fun tick() { if (lease.usable()) delegate.tick() }
            override fun close() {
                HelmSourceOwner.mainThread()
                if (lease.isClosed) return
                try { if (ownsDiagnostics) setDiagnosticsVisible(false) }
                finally { lease.close(); ports.remove(lease); sonarPort = null }
            }
        }
    }

    private fun borrowWeather(): WeatherSourcePort {
        val lease = port(); val delegate = raw.weather
        return object : WeatherSourcePort by delegate {
            override val snapshots = lease.feed(delegate.snapshots)
            override fun advance(elapsedMillis: Long) { if (lease.usable()) delegate.advance(elapsedMillis) }
            override fun close() { HelmSourceOwner.mainThread(); if (!lease.isClosed) { lease.close(); ports.remove(lease); weatherPort = null } }
        }
    }

    override fun close() {
        HelmSourceOwner.mainThread()
        if (!closed.compareAndSet(false, true)) return
        // Closing borrowed ports can never invoke raw.close().
        var failure: RuntimeException? = null
        fun retire(action: () -> Unit) { try { action() } catch (error: RuntimeException) { if (failure == null) failure = error } }
        retire { audioPort?.close() }; retire { helmPort?.close() }
        retire { sonarPort?.close() }; retire { weatherPort?.close() }
        ports.toList().forEach { retire { it.close() } }
        ports.clear(); released()
        failure?.let { throw it }
    }
}
