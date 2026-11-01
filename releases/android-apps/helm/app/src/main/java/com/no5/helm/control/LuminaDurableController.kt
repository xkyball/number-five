package com.no5.helm.control

import android.content.Context
import android.content.SharedPreferences
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import com.no5.control.ClientDisposition
import com.no5.control.ControlBinderHost
import com.no5.control.KnobEnvelope
import com.no5.control.NormalizedKnobInput
import com.no5.control.ObservedForeground
import com.no5.domain.AudioCommand
import com.no5.domain.TransportAction
import com.no5.helm.AudioNativeController
import com.no5.helm.input.KnobEvent
import com.no5.helm.input.QfKnobInput
import org.lumina.control.integration.v2.KnobRouter

/**
 * Android host for the durable V7 path.  It has one QF listener and one overlay only.
 *
 * The QF API is observational.  The host therefore never claims Android key consumption;
 * it only admits the reviewed Lumina codes to the signed No5 control contract.  Failure to
 * register, add the overlay, or create an audio session tears down every owned resource.
 */
internal class LuminaDurableController(
    private val context: Context,
    private val owner: HelmSourceOwner,
    private val header: View,
    private val preference: SharedPreferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE),
    private val clock: () -> Long = SystemClock::uptimeMillis,
    private val qf: QfKnobInput = QfKnobInput(context.applicationContext),
    private val windowManager: WindowManager = context.getSystemService(WindowManager::class.java),
    /** Mirrors the accepted durable focus into the already authenticated header channel. */
    private val onForeground: (ObservedForeground) -> Unit = {},
    private val inputOwnedByLumina: Boolean = false,
) : AutoCloseable {
    private val main = Handler(Looper.getMainLooper())
    private val control = ControlBinderHost(context.applicationContext, onClientStateChanged = {
        // App Binder callbacks may arrive on an arbitrary Binder thread.  Coalesce the
        // resulting focus/header refresh onto the one controller loop before any QF event.
        main.post { if (enabled && !closed) synchronizeScope() }
    })
    private val rows = ArrayDeque<String>()
    private var overlayOpen = false
    private var qfOpen = false
    private var closed = false
    private var enabled = false
    private var headerRequested = preference.getBoolean(KEY_HEADER_ENABLED, true)
    private var focusEpoch = 0L
    private var foregroundPackage: String? = null
    private var sequence = 0L
    private var bindingEpoch = 0L
    private var router = KnobRouter(KnobRouter.Scope("", 0, 0, false, false))
    private var audio: AudioNativeController? = null
    private var audioGeneration = -1L
    private var burstDirection = 0
    private var burstCount = 0
    private var burstTask: Runnable? = null

    val binder get() = control.binder
    val isEnabled get() = enabled

    fun requested(): Boolean = preference.getBoolean(KEY_ENABLED, false)

    /** Called only after exact cohort verification by the command bridge. */
    fun enable(): Boolean {
        HelmSourceOwner.mainThread()
        if (closed) return false
        if (enabled) return true
        if (headerRequested && !Settings.canDrawOverlays(context)) {
            record("enable_refused", "overlay_permission_missing")
            preference.edit().putBoolean(KEY_ENABLED, false).apply()
            return false
        }
        try {
            // The durable route owns no system configuration.  Persist the original
            // no-overlay state before changing anything, for an auditable disable path.
            preference.edit().putBoolean(KEY_ORIGINAL_CAPTURED, true).apply()
            if (headerRequested) openOverlay()
            if (!inputOwnedByLumina) {
                qf.start(::onQfEvent)
                check(qf.isRegistered) { "qf_registration_refused" }
                qfOpen = true
            }
            enabled = true
            preference.edit().putBoolean(KEY_ENABLED, true).apply()
            record("enabled", "header=${if (overlayOpen) "visible" else "hidden"},qf=${if (inputOwnedByLumina) "lumina_owned" else "registered"}")
            return true
        } catch (failure: Throwable) {
            record("enable_failed", failure.javaClass.simpleName + ":" + (failure.message ?: "unknown"))
            disable("enable_failed")
            return false
        }
    }

    fun disable(reason: String) {
        HelmSourceOwner.mainThread()
        cancelBurst()
        enabled = false
        preference.edit().putBoolean(KEY_ENABLED, false).apply()
        try { qf.stop() } catch (_: RuntimeException) { }
        qfOpen = false
        try { if (overlayOpen) windowManager.removeViewImmediate(header) } catch (_: RuntimeException) { }
        overlayOpen = false
        retireAudio()
        val cleared = ObservedForeground(null, nextEpoch(), false, 0)
        control.observeForeground(cleared)
        onForeground(cleared)
        record("disabled", reason + ";restored=overlay_absent,qf_unregistered")
    }

    /** Hiding the visual surface never revokes the one QF registration or audio routing. */
    fun setHeaderVisible(visible: Boolean): Boolean {
        HelmSourceOwner.mainThread()
        if (closed) return false
        if (visible && !Settings.canDrawOverlays(context)) {
            record("header_refused", "overlay_permission_missing")
            return false
        }
        headerRequested = visible
        preference.edit().putBoolean(KEY_HEADER_ENABLED, visible).apply()
        try {
            if (visible && enabled) openOverlay()
            if (!visible && overlayOpen) windowManager.removeViewImmediate(header)
            if (!visible) overlayOpen = false
            record("header", "${if (visible) "visible" else "hidden"};qf=$qfOpen")
            return true
        } catch (failure: RuntimeException) {
            record("header_failed", failure.javaClass.simpleName)
            return false
        }
    }

    /** QF/SystemUI replacement must replace, never multiply, the listener. */
    fun reconnect(reason: String): Boolean {
        HelmSourceOwner.mainThread()
        if (!enabled || closed) return false
        try {
            qf.stop(); qfOpen = false
            if (!inputOwnedByLumina) {
                qf.start(::onQfEvent)
                check(qf.isRegistered) { "qf_registration_refused" }
                qfOpen = true
            }
            if (headerRequested && !overlayOpen) openOverlay()
            record("reconnected", reason + ";single_qf_registration")
            return true
        } catch (failure: Throwable) {
            record("reconnect_failed", failure.javaClass.simpleName)
            disable("reconnect_failed")
            return false
        }
    }

    fun status(): String = "enabled=$enabled;requested=${requested()};headerRequested=$headerRequested;overlay=$overlayOpen;qf=$qfOpen;" +
        "lease=${foregroundPackage ?: "none"};last=${rows.lastOrNull() ?: "none"}"
    fun matrix(): List<String> = rows.toList()

    private fun openOverlay() {
        if (overlayOpen) return
        val params = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; title = "No5 Lumina Header" }
        windowManager.addView(header, params)
        overlayOpen = true
    }

    private fun onQfEvent(event: KnobEvent) {
        HelmSourceOwner.mainThread()
        if (!enabled || !qfOpen) { record("refused", "service_not_ready") ; return }
        val code = event.key.keyCode
        val phase = when (event.action) { 0 -> KnobRouter.Phase.DOWN; 1 -> KnobRouter.Phase.UP; else -> KnobRouter.Phase.CANCEL }
        val scope = synchronizeScope()
        if (event.action == 0 && event.repeat == 0 && !event.longPress && event.downTime == event.eventTime)
            router.admitFreshShortDown(scope, code)
        val decision = router.accept(scope, code, phase, event.repeat, event.longPress) ?: return
        if (!router.isCurrent(decision)) { record("refused", "stale_scope") ; return }
        val input = NormalizedKnobInput.valueOf(decision.input.name)
        when (decision.scope.owner) {
            KnobRouter.Owner.APP -> deliverToApp(input, decision)
            KnobRouter.Owner.HELM_AUDIO -> deliverToHelm(input)
            else -> record("refused", "no_owner")
        }
    }

    /**
     * The binder host accepts only No5-signature clients.  A current, focused client is the
     * visible app lease.  Ambiguity resolves to HELM, never to a remembered app.
     */
    private fun synchronizeScope(): KnobRouter.Scope {
        val next = control.visibleSignedPackage()
        if (next != foregroundPackage) {
            foregroundPackage = next
            val observed = ObservedForeground(next, nextEpoch(), next != null, 0)
            control.observeForeground(observed)
            onForeground(observed)
        }
        val ready = next?.let { control.findReadyClient(it, focusEpoch) }
        val fingerprint = listOf(next, ready?.clientEpoch, ready?.bindingsEpoch, owner.sessionGeneration, owner.isDisposed)
        bindingEpoch = (fingerprint.hashCode().toLong() and Long.MAX_VALUE).coerceAtLeast(1L)
        // KnobRouter's empty package denotes no owner for the bounded V6 path.  V7 has an
        // explicit durable HELM default, so retain the real null foreground for Binder/header
        // scope while presenting HELM as the local router owner when no app has a live lease.
        val routerPackage = next ?: "durable.helm.default"
        val scope = KnobRouter.Scope(routerPackage, focusEpoch, bindingEpoch, ready != null, !owner.isDisposed)
        router.updateScope(scope)
        return scope
    }

    private fun deliverToApp(input: NormalizedKnobInput, decision: KnobRouter.Decision) {
        val packageName = decision.scope.foregroundPackage
        val ready = control.findReadyClient(packageName, decision.scope.foregroundEpoch)
        if (ready == null || !control.claimsInput(packageName, decision.scope.foregroundEpoch, input)) {
            record("helm", "$input:unclaimed_by_visible_app")
            deliverToHelm(input)
            return
        }
        val issued = clock(); val expires = issued + 250
        val envelope = KnobEnvelope(controllerSession = control.controllerSession, sequence = nextSequence(),
            foregroundEpoch = ready.foregroundEpoch, clientEpoch = ready.clientEpoch,
            bindingsEpoch = ready.bindingsEpoch, input = input, issuedAtUptimeMs = issued, expiresAtUptimeMs = expires)
        control.dispatchAsync(envelope) { result ->
            HelmSourceOwner.mainThread()
            if (enabled && router.isCurrent(decision) && result == ClientDisposition.AUDIO_DEFAULT) deliverToHelm(input)
            else record("app", "$packageName:$input:$result")
        }
    }

    private fun deliverToHelm(input: NormalizedKnobInput) {
        if (input == NormalizedKnobInput.LEFT_LONG_PUSH || input == NormalizedKnobInput.RIGHT_LONG_PUSH) {
            record("helm", "$input:long_press_confirmed_no_action")
            return
        }
        if (input == NormalizedKnobInput.RIGHT_CW || input == NormalizedKnobInput.RIGHT_CCW) {
            queueRightRotation(if (input == NormalizedKnobInput.RIGHT_CW) 1 else -1)
            return
        }
        val handled = actionController()?.let { AudioKnobActions(it).perform(input) } ?: false
        record("helm", "$input:${if (handled) "handled" else "rejected"}")
    }

    /** One small detent changes track; a short same-direction burst seeks within the track. */
    private fun queueRightRotation(direction: Int) {
        val now = clock()
        if (burstDirection != direction) { cancelBurst(); burstDirection = direction; burstCount = 0 }
        burstCount++
        val task = Runnable {
            val count = burstCount; val sign = burstDirection
            burstTask = null; burstCount = 0; burstDirection = 0
            val controller = actionController() ?: run { record("helm", "right_rotation:audio_unavailable"); return@Runnable }
            val handled = if (count >= 3) {
                val position = controller.position() ?: 0L
                val duration = controller.state.media.durationMs ?: 0L
                val target = (position + sign * count * SEEK_STEP_MS).coerceIn(0L, duration)
                controller.write(AudioCommand.Transport(TransportAction.SEEK, target))
            } else AudioKnobActions(controller).perform(if (sign > 0) NormalizedKnobInput.RIGHT_CW else NormalizedKnobInput.RIGHT_CCW)
            record("helm", "right_rotation:${if (count >= 3) "seek" else "track"}:$count:${if (handled) "handled" else "rejected"}")
        }
        burstTask = task
        main.postDelayed(task, ROTARY_BURST_MS)
    }

    private fun actionController(): AudioNativeController? {
        if (owner.isDisposed) return null
        if (audioGeneration != owner.sessionGeneration) {
            retireAudio()
            audio = try { AudioNativeController(source = owner.borrowAudio()) } catch (_: RuntimeException) { null }
            audioGeneration = if (audio == null) -1 else owner.sessionGeneration
        }
        return audio
    }
    private fun retireAudio() { audio?.dispose(); audio = null; audioGeneration = -1 }
    private fun cancelBurst() { burstTask?.let(main::removeCallbacks); burstTask = null; burstCount = 0; burstDirection = 0 }
    private fun nextEpoch(): Long { check(focusEpoch < Long.MAX_VALUE); return ++focusEpoch }
    private fun nextSequence(): Long { check(sequence < Long.MAX_VALUE); return ++sequence }
    private fun record(kind: String, text: String) {
        val row = "${clock()}|$kind|focus=${foregroundPackage ?: "none"}|$text"
        rows.addLast(row); while (rows.size > 512) rows.removeFirst()
        preference.edit().putString(KEY_LAST, row).apply()
    }
    override fun close() { if (!closed) { closed = true; disable("service_destroyed"); control.close() } }

    companion object {
        const val PREFERENCES = "lumina_durable_v7"
        const val KEY_ENABLED = "enabled"
        const val KEY_HEADER_ENABLED = "header_enabled"
        const val KEY_ORIGINAL_CAPTURED = "original_captured"
        const val KEY_LAST = "last"
        private const val ROTARY_BURST_MS = 180L
        private const val SEEK_STEP_MS = 5_000L
    }
}
