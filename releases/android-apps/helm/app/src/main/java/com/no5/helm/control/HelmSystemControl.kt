package com.no5.helm.control

import android.content.Context
import android.os.SystemClock
import com.no5.control.*
import com.no5.control.wire.BridgeWire
import com.no5.domain.SourceSubscription
import com.no5.domain.AudioSourcePort
import com.no5.domain.AudioCommand
import com.no5.domain.AudioCommandOutcome
import com.no5.domain.AudioCommandStatus
import com.no5.helm.AudioNativeController
import org.lumina.control.integration.v2.KnobRouter

/**
 * Main-loop native integration. Binder clients cannot send raw events or claim OS focus.
 * Root-channel entry is deliberately not exposed by the service's Binder.
 * A kernel-authenticated socket and a real OS-window observer must precede enabling it.
 */
internal class HelmSystemControl(context: Context, private val owner: HelmSourceOwner,
    private val uptime: () -> Long = SystemClock::uptimeMillis,
    private val audioControllerFactory: (AudioSourcePort) -> AudioNativeController = { AudioNativeController(source = it) },
    private val onForeground: (ObservedForeground) -> Unit = {},
    private val hostFactory: (Context) -> ControlBinderHost = { ControlBinderHost(it) },
    private val onRootHeaderScope: (HelmHeaderFocusMapping) -> Unit = {},
    private val onRootHeaderFailure: () -> Unit = {}) : AutoCloseable {
    val host = hostFactory(context.applicationContext)
    val binder get() = host.binder
    private var guard = BridgeWire.Guard()
    private val router = KnobRouter(KnobRouter.Scope("", 0, 0, false, false))
    private var externalEpoch = 0L
    private var foregroundEpoch = 0L
    private var bindingsEpoch = 0L
    private var bindingAdmissionUptime = 0L
    private var deliverySequence = 0L
    private var fingerprint: List<Any?>? = null
    private var foregroundPackage = ""
    private var audioGeneration = -1L
    private var audioController: AudioNativeController? = null
    private var activeAudioAction: ActionWindow? = null
    @Volatile private var closed = false
    private val audioScopeGeneration = java.util.concurrent.atomic.AtomicLong()
    internal var isRootChannelValid = false
        private set
    internal var rootHeaderScope = HelmHeaderFocusMapping(null, null, 0)
        private set
    private var authenticatedRootControlSession: String? = null
    internal var isRootHeaderExportUsable = true
        private set
    private var sourceChanges: SourceSubscription? = null

    init {
        try {
            HelmSourceOwner.mainThread()
            sourceChanges = owner.changes.subscribe { if (!closed) { retireAudio(); synchronizeScope() } }
        } catch (failure: Throwable) {
            closed = true
            cleanupOwnedControl(failure)
            throw failure
        }
    }

    /** New transport connection; does not automatically connect or enable any socket. */
    internal fun beginAuthenticatedRootConnection() {
        HelmSourceOwner.mainThread(); check(!closed)
        revokeRoot(); guard = BridgeWire.Guard(); externalEpoch = 0
    }

    internal fun acceptFromAuthenticatedRoot(packet: BridgeWire.Packet): Boolean {
        HelmSourceOwner.mainThread()
        val acceptedAt = uptime()
        if (closed || !guard.accept(packet, acceptedAt)) { revokeRoot(); return false }
        when (packet.kind) {
            BridgeWire.HELLO -> {
                isRootChannelValid = true
                authenticatedRootControlSession = packet.controllerSession
                updateRootHeaderScope(null)
                return true
            }
            // A valid scope cancellation does not end the authenticated stream.
            BridgeWire.CANCEL -> { clearForeground(); return true }
            BridgeWire.FOCUS -> {
                val focus = packet.focus
                val changed = externalEpoch != focus.epoch
                if (changed) {
                    audioScopeGeneration.incrementAndGet() // Retire worker leases before fallible callbacks.
                    externalEpoch = focus.epoch
                    foregroundEpoch = advance(foregroundEpoch)
                    foregroundPackage = focus.packageName
                }
                // Capture EVERY accepted refresh before the matching registry foreground hook.
                updateRootHeaderScope(org.lumina.header.consumer.v1.HeaderConsumerContract.RootFocus(
                    checkNotNull(authenticatedRootControlSession), focus.epoch, focus.windowIdentity,
                    focus.packageName, focus.user, focus.display, focus.observedUptime))
                if (changed) {
                    val observed = ObservedForeground(foregroundPackage, foregroundEpoch, true, focus.user, focus.display)
                    host.observeForeground(observed)
                    onForeground(observed)
                }
                synchronizeScope()
                return true
            }
            BridgeWire.KEY -> return acceptKey(packet.key, acceptedAt)
        }
        revokeRoot(); return false
    }

    private fun acceptKey(key: BridgeWire.Key, acceptedAt: Long): Boolean {
        // Guard rejects replayed stream sequences. Identical KeyEvent fields alone cannot
        // distinguish two real detents, so do not use the metadata fingerprint as a debounce.
        val captured = synchronizeScope()
        val focus = guard.focus() ?: return false
        val expiry = minOf(deadline(focus.observedUptime), deadline(key.eventTime), deadline(key.copiedUptime))
        if (acceptedAt >= expiry) {
            if (key.action == 0) router.accept(captured, key.androidKeyCode, KnobRouter.Phase.CANCEL, key.repeat, key.longPress())
            return false
        }
        if (key.eventTime < bindingAdmissionUptime || key.copiedUptime < bindingAdmissionUptime) {
            // The Root wire owns OS scope; this additional cutoff owns the app/source binding.
            // Mark stale press prefixes cancelled, so a subsequent LONG repeat cannot migrate.
            if (key.action == 0) router.accept(captured, key.androidKeyCode, KnobRouter.Phase.CANCEL, key.repeat, key.longPress())
            return false
        }
        if (key.action == 0 && key.repeat == 0 && !key.longPress() && !key.cancelled() &&
            key.downTime == key.eventTime && key.downTime >= bindingAdmissionUptime)
            router.admitFreshShortDown(captured, key.androidKeyCode)
        val decision = router.accept(captured, key.androidKeyCode,
            if (key.cancelled()) KnobRouter.Phase.CANCEL else if (key.action == 0) KnobRouter.Phase.DOWN else KnobRouter.Phase.UP,
            key.repeat, key.longPress()) ?: return false
        synchronizeScope()
        if (!router.isCurrent(decision)) return false
        val window = ActionWindow(decision, acceptedAt, expiry)
        if (window.admitNow() == null) return false
        val input = NormalizedKnobInput.valueOf(decision.input.name)
        return when (decision.scope.owner) {
            KnobRouter.Owner.APP -> {
                val client = host.findReadyClient(foregroundPackage, foregroundEpoch) ?: return false
                // The Binder lease is an explicit input subset.  Inputs not leased by the
                // currently visible signed app go straight to HELM; they never cross an app IPC
                // boundary merely to be refused later.
                if (!host.claimsInput(foregroundPackage, foregroundEpoch, input)) return performAudio(input, window)
                val issuedAt = window.admitNow() ?: return false
                val envelope = KnobEnvelope(controllerSession = host.controllerSession,
                    sequence = advance(deliverySequence).also { deliverySequence = it },
                    foregroundEpoch = client.foregroundEpoch, clientEpoch = client.clientEpoch,
                    bindingsEpoch = client.bindingsEpoch, input = input,
                    issuedAtUptimeMs = issuedAt, expiresAtUptimeMs = window.expiresAt)
                // Exactly one client. A refusal/death never falls through to an Audio owner.
                // Async leaves HELM's own Main-looper client free to consume the callback.
                host.dispatchAsync(envelope) { disposition ->
                    if (!closed && disposition == ClientDisposition.AUDIO_DEFAULT) {
                        synchronizeScope()
                        if (router.isCurrent(decision) && host.findReadyClient(foregroundPackage, foregroundEpoch) == client &&
                            window.admitNow() != null) performAudio(input, window)
                    }
                }
                true // Admitted for one bounded callback; does not claim a completed action.
            }
            KnobRouter.Owner.HELM_AUDIO -> performAudio(input, window)
            else -> false
        }
    }

    private fun synchronizeScope(): KnobRouter.Scope {
        val client = host.findReadyClient(foregroundPackage, foregroundEpoch)
        val next = listOf(foregroundPackage, foregroundEpoch, client?.clientEpoch, client?.bindingsEpoch,
            owner.sessionGeneration, owner.isDisposed)
        if (fingerprint != next) {
            audioScopeGeneration.incrementAndGet() // Worker retirement is independent of Main/router access.
            bindingsEpoch = advance(bindingsEpoch); fingerprint = next
            bindingAdmissionUptime = uptime()
        }
        return KnobRouter.Scope(foregroundPackage, foregroundEpoch, bindingsEpoch, client != null, !owner.isDisposed)
            .also(router::updateScope)
    }
    private fun performAudio(input: NormalizedKnobInput, window: ActionWindow): Boolean {
        if (!validAudioAction(window)) return false
        if (audioGeneration != owner.sessionGeneration) {
            retireAudio()
            if (!validAudioAction(window)) return false
            val generation = owner.sessionGeneration
            val port = owner.borrowAudio()
            val bounded = object : AudioSourcePort by port {
                override val sound get() = port.sound
                override fun available(command: AudioCommand): Boolean {
                    val active = activeAudioAction ?: return false
                    return validAudioAction(active) && port.available(command) && validAudioAction(active)
                }
                override fun submit(command: AudioCommand): AudioCommandOutcome {
                    val active = activeAudioAction
                    if (active != null && validAudioAction(active)) {
                        val capturedScope = audioScopeGeneration.get()
                        val capturedPackage = active.decision.scope.foregroundPackage
                        val capturedForeground = active.decision.scope.foregroundEpoch
                        val capturedClient = host.findReadyClient(capturedPackage, capturedForeground)
                        val effect = HelmAudioEffectLease(active.expiresAt, generation, port.identity.sessionId,
                            active.admitNow() ?: return AudioCommandOutcome(identity.sessionId, null, command,
                                AudioCommandStatus.REJECTED, 0, "Knob action expired; no command sent"), uptime) {
                            // Pure atomic/volatile reads and the original synchronized host read only.
                            !closed && !owner.isDisposed && owner.sessionGeneration == generation &&
                                audioScopeGeneration.get() == capturedScope &&
                                host.findReadyClient(capturedPackage, capturedForeground) == capturedClient
                        }
                        return HelmAudioEffectContext.withLease(effect) { port.submit(command) }
                    }
                    return AudioCommandOutcome(identity.sessionId, null, command, AudioCommandStatus.REJECTED,
                        0, "Knob action expired or source scope changed; no command sent")
                }
            }
            val candidate = try { audioControllerFactory(bounded) }
                catch (failure: RuntimeException) { port.close(); throw failure }
            if (!validAudioAction(window) || generation != owner.sessionGeneration) {
                candidate.dispose(); return false
            }
            audioController = candidate; audioGeneration = generation
        }
        if (!validAudioAction(window)) return false
        activeAudioAction = window
        return try { AudioKnobActions(requireNotNull(audioController)).perform(input) }
            finally { activeAudioAction = null }
    }
    private fun validAudioAction(window: ActionWindow): Boolean {
        if (closed || owner.isDisposed || window.admitNow() == null) return false
        synchronizeScope()
        return router.isCurrent(window.decision) && window.admitNow() != null
    }
    /** This original event/focus lease never gets renewed by envelope/controller construction. */
    private inner class ActionWindow(val decision: KnobRouter.Decision, acceptedAt: Long, val expiresAt: Long) {
        private var lastChecked = acceptedAt
        fun admitNow(): Long? {
            val now = uptime()
            if (now < lastChecked || now >= expiresAt) return null
            lastChecked = now
            return now
        }
    }
    private fun deadline(original: Long): Long = if (original > Long.MAX_VALUE - 250) Long.MAX_VALUE else original + 250
    private fun retireAudio() {
        val retired = audioController
        audioController = null; audioGeneration = -1
        retired?.dispose()
    }
    internal fun revokeRoot() {
        HelmSourceOwner.mainThread()
        guard.close(); isRootChannelValid = false
        authenticatedRootControlSession = null
        clearForeground()
    }
    private fun clearForeground() {
        audioScopeGeneration.incrementAndGet() // Revoke before Header/Binder callbacks can fail or block.
        foregroundPackage = ""; externalEpoch = 0
        foregroundEpoch = advance(foregroundEpoch)
        updateRootHeaderScope(null)
        val observed = ObservedForeground(null, foregroundEpoch, false, 0)
        host.observeForeground(observed)
        onForeground(observed)
        router.cancelHolds(); synchronizeScope()
    }
    private fun advance(value: Long): Long { check(value < Long.MAX_VALUE); return value + 1 }
    private fun updateRootHeaderScope(focus: org.lumina.header.consumer.v1.HeaderConsumerContract.RootFocus?) {
        rootHeaderScope = HelmHeaderFocusMapping(authenticatedRootControlSession, focus, foregroundEpoch)
        if (isRootHeaderExportUsable) {
            try { onRootHeaderScope(rootHeaderScope) }
            catch (_: RuntimeException) {
                isRootHeaderExportUsable = false
                try { onRootHeaderFailure() } catch (_: RuntimeException) { /* No change to control admission. */ }
            }
        }
    }
    override fun close() {
        HelmSourceOwner.mainThread()
        if (closed) return
        closed = true // Fence callbacks BEFORE every fallible cleanup; borrowed owner remains alive.
        cleanupOwnedControl(null)?.let { throw it }
    }
    private fun cleanupOwnedControl(primary: Throwable?): Throwable? {
        val subscription = sourceChanges; sourceChanges = null
        var failure = primary
        listOf<() -> Unit>(
            { audioScopeGeneration.incrementAndGet(); revokeRoot() },
            { guard.close(); isRootChannelValid = false; authenticatedRootControlSession = null; updateRootHeaderScope(null) },
            { subscription?.close() },
            { retireAudio() },
            { host.close() }
        ).forEach { step ->
            try { step() } catch (next: Throwable) {
                if (failure == null) failure = next else if (failure !== next) failure!!.addSuppressed(next)
            }
        }
        return failure
    }
}
