package com.no5.helm.input

import android.view.KeyEvent
import com.no5.domain.*

internal enum class KnobAction { MASTER_UP, MASTER_DOWN, MASTER_MUTE, PREVIOUS, NEXT, PLAY_PAUSE }
internal data class QfKey(val type: Int, val rawCode: Int, val keyCode: Int, val scanCode: Int, val deviceId: Int)
internal data class KnobEvent(val key: QfKey, val action: Int, val repeat: Int, val downTime: Long, val eventTime: Long,
                             val flags: Int = 0) {
    val longPress: Boolean get() = flags and KeyEvent.FLAG_LONG_PRESS != 0
}

/** Device/firmware-specific evidence is required; key codes alone do not identify a physical knob.
 * QF is observational (void callback): Android key consumption cannot suppress its OEM side effects.
 */
internal class KnobProfile(
    bindings: Map<QfKey, KnobAction> = emptyMap(),
    val mappingEvidence: String? = null,
    val exclusiveDeliveryEvidence: String? = null,
) {
    val bindings = bindings.toMap()
    val enabled get() = bindings.isNotEmpty() && !mappingEvidence.isNullOrBlank() && !exclusiveDeliveryEvidence.isNullOrBlank()
    companion object { val UNVERIFIED = KnobProfile() }
}

/** Only the existing source submits commands. No Android volume, source gain, speculative state or replay. */
internal class KnobAudioCommands(private val source: AudioSourcePort) {
    fun perform(action: KnobAction): Boolean {
        val state = source.state.value
        if (state.sessionId != source.identity.sessionId || state.pending != null) return false
        val master = state.dsp.faders[AudioChannel.MASTER]
        val command = when (action) {
            KnobAction.MASTER_UP, KnobAction.MASTER_DOWN -> {
                if (state.authority != VolumeAuthority.CAMILLA_DSP_MASTER) return false
                val current = master?.db?.takeIf { it.isFinite() } ?: return false
                val limits = state.limits?.get(AudioChannel.MASTER) ?: return false
                val next = limits.clamp(current + if (action == KnobAction.MASTER_UP) limits.stepDb else -limits.stepDb)
                if (next == current) return false
                AudioCommand.Fader(AudioChannel.MASTER, next)
            }
            KnobAction.MASTER_MUTE -> {
                if (state.authority != VolumeAuthority.CAMILLA_DSP_MASTER || master == null) return false
                AudioCommand.Mute(AudioChannel.MASTER, !master.muted)
            }
            KnobAction.PREVIOUS -> AudioCommand.Transport(TransportAction.PREVIOUS)
            KnobAction.NEXT -> AudioCommand.Transport(TransportAction.NEXT)
            KnobAction.PLAY_PAUSE -> AudioCommand.Transport(if (state.media.state == MediaState.PLAYING) TransportAction.PAUSE else TransportAction.PLAY)
        }
        return source.available(command) && source.submit(command).accepted
    }
}

internal interface KnobEventSource {
    fun start(receive: (KnobEvent) -> Unit)
    fun stop()
}

/** Main-thread owned, process-wide input lease, NOT a global audio/session singleton.
 * New focused owner revokes the old lease. Closing an old Activity cannot detach the new one.
 */
internal class ForegroundKnobInput(
    private val input: KnobEventSource,
    private val profile: KnobProfile,
    private val uptime: () -> Long,
) {
    private var generation = 0L
    private var active = false
    private val seen = LinkedHashSet<KnobEvent>()
    fun acquire(source: AudioSourcePort): AutoCloseable {
        val token = ++generation
        active = false
        input.stop()
        val since = uptime()
        val commands = KnobAudioCommands(source)
        if (profile.enabled) {
            active = true
            input.start { event ->
                if (active && generation == token && event.action == 0 && event.repeat == 0 &&
                    event.eventTime >= since && event.eventTime <= uptime() && event.downTime <= event.eventTime &&
                    event.downTime >= since && !seen.contains(event)) {
                    val action = profile.bindings[event.key]
                    if (action != null) {
                        seen.add(event)
                        if (seen.size > 128) seen.remove(seen.first())
                        commands.perform(action)
                    }
                }
            }
        }
        return AutoCloseable {
            if (generation == token) {
                ++generation
                active = false
                input.stop()
            }
        }
    }
}

/** Activity lifecycle adapter. Focus loss revokes synchronously, without waiting for recomposition. */
internal class KnobForegroundOwner(private val acquire: (AudioSourcePort) -> AutoCloseable) : AutoCloseable {
    private var source: AudioSourcePort? = null
    private var lease: AutoCloseable? = null
    private var binding = 0L
    private var resumed = false
    private var focused = false
    private var closed = false
    fun bind(source: AudioSourcePort): AutoCloseable {
        check(!closed)
        val token = ++binding
        lease?.close(); lease = null
        this.source = source
        update()
        return AutoCloseable {
            if (binding == token) { this.source = null; update() }
        }
    }
    fun resume(hasFocus: Boolean) { if (!closed) { resumed = true; focused = hasFocus; update() } }
    fun focus(hasFocus: Boolean) { focused = hasFocus; update() }
    fun stop() { resumed = false; update() }
    private fun update() {
        val current = source
        if (!closed && resumed && focused && current != null) {
            if (lease == null) lease = acquire(current)
        } else { lease?.close(); lease = null }
    }
    override fun close() { closed = true; source = null; stop() }
}
