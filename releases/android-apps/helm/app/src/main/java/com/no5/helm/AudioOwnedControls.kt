package com.no5.helm

import android.os.Handler
import android.os.Looper
import android.os.Parcel
import com.no5.domain.*
import com.no5.helm.control.HelmAudioEffectContext
import com.no5.helm.control.HelmAudioEffectLease
import org.lumina.control.commands.v1.CommandRing
import com.no5.header.artwork.No5MomentFeedback

/**
 * The AUDIO control model. Touch and input bindings share desired values, admission, effects and
 * receipts. Only the existing source owns transport/ACKs; outcomes wake this owner without polling.
 * The Binder admission section touches primitive slots and a published immutable source snapshot.
 */
internal class AudioOwnedControls(private val owner: AudioNativeController,
                                  private val uptime: () -> Long = { System.nanoTime() / 1_000_000 }) : AutoCloseable {
    private val lock = Any()
    private val ring = CommandRing()
    private val work = CommandRing.Entry()
    private val result = CommandRing.Entry()
    private val admission = CommandRing.Entry()
    private val diagnostics = LongArray(64)
    private var diagnosticCount = 0
    private var diagnosticHead = 0
    private val goals = DoubleArray(AudioChannel.entries.size) { Double.NaN }
    private val muteGoals = BooleanArray(AudioChannel.entries.size)
    private val muteSeeded = BooleanArray(AudioChannel.entries.size)
    private var playbackGoal: MediaState? = null
    private var equalizerGoal: AudioCommand.Equalizer? = null
    private var equalizerGoalId = 0L
    // UI-only stable payloads for the existing 64 waiting slots plus one running effect.
    // Physical knob admission never inserts into this map.
    private val presetGoals = HashMap<Long, String>(128)
    private var snapshot = owner.state
    private var selected = owner.selectedOutput
    private var generation = 0L
    private var sourceGeneration = 1L
    private var closed = false
    private var queued = false
    private var draining = false
    private var rerun = false
    private var running = false
    private var awaiting: String? = null
    private var activeEffect: HelmAudioEffectLease? = null
    private var feedbackTicket = 0L
    private var handler: Handler? = null
    private val drainTask = Runnable { drain() }
    private val retireFeedback = Runnable {
        val epoch=synchronized(lock){generation}
        No5MomentFeedback.invalidate(this,epoch)
    }

    /** Called on the UI thread while binding, never for the first physical impulse. */
    fun prepareBindings() { if (handler == null) handler = Handler(Looper.getMainLooper()) }

    fun observe(ownerGeneration: Long = sourceGeneration, useSelectedFader: Boolean = true,
                state: AudioState = owner.state, output: Int = owner.selectedOutput) = synchronized(lock) {
        if (sourceGeneration != ownerGeneration) {
            sourceGeneration = ownerGeneration
            cancelLocked(CommandRing.Reason.SOURCE_CHANGED)
        }
        snapshot = state
        val next = if (useSelectedFader) output else 0
        if (selected != next) { selected = next; cancelLocked(CommandRing.Reason.FOCUS_CHANGED) }
        resetGoalsIfIdle()
    }

    private fun physical(index: Int): AudioFaderControl? {
        var match: AudioFaderControl? = null
        for (control in snapshot.dsp.faderControls) if (control.physicalIndex == index) {
            if (match != null) return null
            match = control
        }
        return match
    }
    private fun control(channel: AudioChannel): AudioFaderControl? {
        var match: AudioFaderControl? = null
        for (control in snapshot.dsp.faderControls) if (control.gatewayId == channel) {
            if (match != null) return null
            match = control
        }
        return match
    }
    fun selectedControl(): AudioFaderControl? = synchronized(lock) { physical(selected) }

    fun adjustSelected(steps: Int, sequence: Long = 0, deferred: Boolean = false): Long = synchronized(lock) {
        adjustLocked(physical(selected), steps.toDouble(), true, sequence, deferred)
    }
    fun adjust(channel: AudioChannel, deltaDb: Double, sequence: Long = 0, deferred: Boolean = false): Long = synchronized(lock) {
        adjustLocked(control(channel), deltaDb, false, sequence, deferred)
    }
    private fun adjustLocked(control: AudioFaderControl?, value: Double, detents: Boolean, sequence: Long, deferred: Boolean): Long {
        val limit = control?.limit ?: return 0
        val db = control.db?.takeIf(Double::isFinite) ?: return 0
        if (!value.isFinite() || snapshot.authority != VolumeAuthority.CAMILLA_DSP_MASTER || limit.maxDb <= limit.minDb) return 0
        val channel = control.gatewayId.ordinal
        val uninitialized = goals[channel].isNaN()
        val baseline = if (uninitialized) db else goals[channel]
        val next = limit.clamp(baseline + value * if (detents) limit.stepDb else 1.0)
        val id = enqueue(channel, FADER, channel, next, sequence, deferred)
        if (retainedAsPending(id)) {
            goals[channel] = next
            if (!muteSeeded[channel]) { muteGoals[channel] = control.muted; muteSeeded[channel] = true }
        }
        return id
    }
    fun absolute(channel: AudioChannel, valueDb: Double, sequence: Long = 0, deferred: Boolean = false): Long = synchronized(lock) {
        val control = control(channel) ?: return 0
        val limit = control.limit ?: return 0
        if (!valueDb.isFinite()) return 0
        val target = limit.clamp(valueDb)
        val id = enqueue(channel.ordinal, FADER, channel.ordinal, target, sequence, deferred)
        if (retainedAsPending(id)) goals[channel.ordinal] = target
        id
    }
    fun toggleSelected(sequence: Long = 0, deferred: Boolean = false): Long = synchronized(lock) {
        toggleLocked(physical(selected), sequence, deferred)
    }
    fun toggle(channel: AudioChannel, sequence: Long = 0, deferred: Boolean = false): Long = synchronized(lock) {
        toggleLocked(control(channel), sequence, deferred)
    }
    private fun toggleLocked(control: AudioFaderControl?, sequence: Long, deferred: Boolean): Long {
        control ?: return 0
        if (snapshot.authority != VolumeAuthority.CAMILLA_DSP_MASTER) return 0
        val channel = control.gatewayId.ordinal
        val next = !(if (!muteSeeded[channel]) control.muted else muteGoals[channel])
        val id = enqueue(-1, MUTE, channel, if (next) 1.0 else 0.0, sequence, deferred)
        if (retainedAsPending(id)) { muteGoals[channel] = next; muteSeeded[channel] = true }
        return id
    }
    fun transport(action: TransportAction, sequence: Long = 0, deferred: Boolean = false, positionMs: Long? = null): Long = synchronized(lock) {
        val id = enqueue(-1, TRANSPORT, action.ordinal, positionMs?.toDouble() ?: Double.NaN, sequence, deferred)
        if (retainedAsPending(id)) when (action) {
            TransportAction.PLAY -> playbackGoal = MediaState.PLAYING
            TransportAction.PAUSE -> playbackGoal = MediaState.PAUSED
            else -> Unit
        }
        id
    }
    fun togglePlayback(sequence: Long = 0, deferred: Boolean = false): Long = synchronized(lock) {
        val next = if ((playbackGoal ?: snapshot.media.state) == MediaState.PLAYING) MediaState.PAUSED else MediaState.PLAYING
        val id = transport(if (next == MediaState.PLAYING) TransportAction.PLAY else TransportAction.PAUSE, sequence, deferred)
        if (retainedAsPending(id)) playbackGoal = next
        id
    }
    fun openPanel(panel: String, sequence: Long = 0, deferred: Boolean = false): Long = synchronized(lock) {
        val index = when (panel) { "mixer" -> 0; "media" -> 1; else -> return 0 }
        enqueue(-1, PANEL, index, 0.0, sequence, deferred)
    }
    fun preset(presetId: String): Long = synchronized(lock) {
        if (presetGoals.size >= 65 || snapshot.dsp.presets.none { it.id == presetId }) return 0
        val id = enqueue(-1, PRESET, 0, 0.0, 0, false)
        if (retainedAsPending(id)) presetGoals[id - AudioControlActor.PREFIX] = presetId
        id
    }
    /** One replaceable full-bank absolute goal; payload belongs to this existing command ring. */
    fun equalizer(command: AudioCommand.Equalizer): Long = synchronized(lock) {
        if (snapshot.dsp.equalizer?.admits(command) != true || !snapshot.capabilities.equalizer) return 0
        val id = enqueue(AudioChannel.entries.size, EQUALIZER, 0, 0.0, 0, false)
        if (retainedAsPending(id)) { equalizerGoal = command; equalizerGoalId = id - AudioControlActor.PREFIX }
        id
    }
    fun mute(channel: AudioChannel, muted: Boolean): Long = synchronized(lock) {
        val id = enqueue(-1, MUTE, channel.ordinal, if (muted) 1.0 else 0.0, 0, false)
        if (retainedAsPending(id)) { muteGoals[channel.ordinal] = muted; muteSeeded[channel.ordinal] = true }
        id
    }

    private fun enqueue(key: Int, kind: Int, index: Int, value: Double, sequence: Long, deferred: Boolean): Long {
        if (closed) return 0
        val now = uptime()
        val physicalBinding = if (deferred && kind in FADER..MUTE) control(AudioChannel.entries[index])?.physicalIndex?.plus(1)?.toDouble() ?: return 0
            else if (deferred) 1.0 else 0.0
        val id = ring.submit(key, kind, sequence, generation, sourceGeneration, index.toDouble(), value,
            physicalBinding, now + EFFECT_LIFETIME_MS.toDouble(), now)
        diagnostics[diagnosticHead] = id; diagnosticHead = (diagnosticHead + 1) % diagnostics.size
        if (diagnosticCount < diagnostics.size) diagnosticCount++
        if (deferred) {
            if (!queued) { queued = true; handler?.post(drainTask) ?: run { queued = false; ring.cancelPending(CommandRing.Reason.CLOSED, now) } }
        }
        return AudioControlActor.PREFIX + id
    }
    private fun retainedAsPending(id: Long): Boolean {
        if (id < AudioControlActor.PREFIX) return false
        return ring.read(id - AudioControlActor.PREFIX, admission) &&
            (admission.state == CommandRing.State.ACCEPTED || admission.state == CommandRing.State.RUNNING)
    }
    fun accepted(id: Long): Boolean {
        // UI calls execute outside admission's lock. An adapter's source lock can never block Binder admission.
        drain()
        return synchronized(result) {
            id >= AudioControlActor.PREFIX && ring.read(id - AudioControlActor.PREFIX, result) &&
                result.state != CommandRing.State.REJECTED && result.state != CommandRing.State.CANCELLED && result.state != CommandRing.State.UNKNOWN
        }
    }

    /** Source emissions are the sole completion signal; no main-loop ACK polling. */
    fun changed() {
        synchronized(lock) { snapshot = owner.state; resetGoalsIfIdle() }
        wake()
    }
    private fun wake() {
        synchronized(lock) {
            if (closed) return
            if (draining) { rerun = true; return }
        }
        drain()
    }
    private fun drain() {
        synchronized(lock) {
            if (closed) return
            if (draining) { rerun = true; return }
            draining = true; queued = false
        }
        try {
            do {
                synchronized(lock) { rerun = false }
                val wait = synchronized(lock) { awaiting }
                if (wait != null) {
                    val outcome = owner.lastOutcome
                    if (outcome?.requestId != wait || outcome.status == AudioCommandStatus.PENDING) return
                    complete(outcome)
                }
                if (owner.state.pending != null || owner.preview != null) return
                while (ring.take(work)) {
                    try {
                        val allowed = synchronized(lock) {
                            running = true
                            !closed && work.generation == generation && work.surface == sourceGeneration &&
                                (work.c == 0.0 || uptime() < work.d)
                        }
                        if (!allowed) { finish(CommandRing.State.CANCELLED, CommandRing.Reason.FOCUS_CHANGED); continue }
                        feedbackTicket = if(work.c != 0.0 && work.kind != FADER)
                            No5MomentFeedback.begin(this, when(work.kind) {
                                PANEL -> if(work.a.toInt()==0) "Fader zuordnen" else "Medien"
                                MUTE -> if(work.b!=0.0) "Stumm" else "Ton an"
                                TRANSPORT -> when(TransportAction.entries[work.a.toInt()]) {
                                    TransportAction.PLAY -> "Wiedergabe"; TransportAction.PAUSE -> "Pause"
                                    TransportAction.NEXT -> "Nächster Titel"; TransportAction.PREVIOUS -> "Vorheriger Titel"
                                    else -> "Medien"
                                }
                                else -> "Klangpreset"
                            }, work.generation) else 0L
                        if (work.kind == PANEL) {
                            val epoch=work.generation; val source=work.surface
                            var opened=false
                            owner.openPanel(if (work.a.toInt() == 0) "mixer" else "media",
                                onOpened = { opened=true }, stillCurrent = {
                                    synchronized(lock) { !closed && generation==epoch && sourceGeneration==source }
                                })
                            // A dirty decision or a busy editor has not opened the requested panel.
                            // Reject this physical action now: waiting for user input must not hold
                            // the audio ring. A later explicit Save/Discard is a new user decision;
                            // its continuation never rewrites this terminal receipt or feedback.
                            finish(if(opened)CommandRing.State.APPLIED else CommandRing.State.REJECTED,
                                if(opened)CommandRing.Reason.NONE else CommandRing.Reason.BUSY,-1)
                            continue
                        }
                        val command = when (work.kind) {
                            FADER -> AudioCommand.Fader(AudioChannel.entries[work.a.toInt()], work.b)
                            MUTE -> AudioCommand.Mute(AudioChannel.entries[work.a.toInt()], work.b != 0.0)
                            TRANSPORT -> AudioCommand.Transport(TransportAction.entries[work.a.toInt()], work.b.takeIf(Double::isFinite)?.toLong())
                            EQUALIZER -> synchronized(lock) { equalizerGoal.takeIf { equalizerGoalId == work.id } }
                            PRESET -> synchronized(lock) { presetGoals[work.id] }
                                ?.takeIf { id -> owner.presets.any { it.id == id } }
                                ?.let { AudioCommand.Preset(it) }
                            else -> null
                        }
                        val boundChannel = when (command) { is AudioCommand.Fader -> command.channel; is AudioCommand.Mute -> command.channel; else -> null }
                        if (work.c != 0.0 && boundChannel != null && owner.faderControl(boundChannel)?.physicalIndex != work.c.toInt() - 1) {
                            finish(CommandRing.State.CANCELLED, CommandRing.Reason.SOURCE_CHANGED); continue
                        }
                        if (command == null || !owner.can(command)) { finish(CommandRing.State.REJECTED, CommandRing.Reason.UNAVAILABLE); continue }
                        if (command is AudioCommand.Fader && owner.faderControl(command.channel)?.db == command.valueDb) {
                            finish(CommandRing.State.APPLIED, CommandRing.Reason.BOUND_REACHED); continue
                        }
                        ring.beginEffect(work.id)
                        val accepted = if (work.c != 0.0) {
                            val epoch = work.generation; val source = work.surface
                            val stamp = uptime()
                            val effect = HelmAudioEffectLease(work.d.toLong(), source, owner.state.sessionId, stamp, uptime) {
                                synchronized(lock) { !closed && generation == epoch && sourceGeneration == source }
                            }
                            activeEffect = effect
                            HelmAudioEffectContext.withLease(effect) { owner.submitSource(command) }
                        } else owner.submitSource(command)
                        val outcome = owner.lastOutcome
                        if (!accepted) { finish(CommandRing.State.REJECTED, CommandRing.Reason.UNAVAILABLE); continue }
                        if (outcome?.status == AudioCommandStatus.PENDING && outcome.requestId != null) {
                            synchronized(lock) { awaiting = outcome.requestId }
                            return
                        }
                        complete(outcome)
                    } catch (_: Exception) {
                        finish(CommandRing.State.UNKNOWN, CommandRing.Reason.COMPLETION_UNKNOWN)
                    }
                }
                synchronized(lock) { resetGoalsIfIdle() }
            } while (synchronized(lock) { rerun })
        } finally {
            val again = synchronized(lock) { draining = false; rerun }
            if (again) wake()
        }
    }
    private fun complete(outcome: AudioCommandOutcome?) {
        val state = when (outcome?.status) {
            AudioCommandStatus.VERIFIED -> CommandRing.State.APPLIED
            AudioCommandStatus.REJECTED -> CommandRing.State.REJECTED
            AudioCommandStatus.DROPPED -> if (activeEffect?.state == HelmAudioEffectLease.State.LOCAL_DENIED)
                CommandRing.State.CANCELLED else CommandRing.State.UNKNOWN
            else -> CommandRing.State.UNKNOWN
        }
        finish(state, when (state) {
            CommandRing.State.APPLIED -> CommandRing.Reason.NONE
            CommandRing.State.REJECTED -> CommandRing.Reason.UNAVAILABLE
            CommandRing.State.CANCELLED -> CommandRing.Reason.SOURCE_CHANGED
            else -> CommandRing.Reason.COMPLETION_UNKNOWN
        })
    }
    private fun finish(state: CommandRing.State, reason: CommandRing.Reason, revision: Long = owner.state.revision) {
        val completed = uptime()
        ring.finish(work.id, state, reason, revision, completed)
        if (handler != null) android.util.Log.i("No5AudioKnob",
            "seq=${work.sequence} id=${AudioControlActor.PREFIX + work.id} state=$state reason=$reason channel=${if (work.kind in FADER..MUTE) AudioChannel.entries[work.a.toInt()].name else "NONE"} waitMs=${completed - work.acceptedMs} revision=$revision gatewayRevision=${owner.lastOutcome?.acknowledgedGatewayRevision} gc=${android.os.Debug.getRuntimeStat("art.gc.gc-count")}")
        val stillOwned = synchronized(lock) { !closed && work.generation==generation && work.surface==sourceGeneration }
        if (stillOwned && feedbackTicket!=0L) No5MomentFeedback.complete(feedbackTicket,
            when(state){CommandRing.State.APPLIED->No5MomentFeedback.Phase.EXECUTED
                CommandRing.State.UNKNOWN->No5MomentFeedback.Phase.UNKNOWN
                else->No5MomentFeedback.Phase.FAILED})
        feedbackTicket=0L
        synchronized(lock) {
            if (work.kind == PRESET) presetGoals.remove(work.id)
            running = false; awaiting = null; activeEffect = null; snapshot = owner.state; resetGoalsIfIdle()
        }
    }
    private fun resetGoalsIfIdle() {
        if (!running && ring.pending() == 0) { goals.fill(Double.NaN); muteSeeded.fill(false); playbackGoal = null; equalizerGoal = null; equalizerGoalId = 0 }
    }
    private fun cancelLocked(reason: CommandRing.Reason) {
        generation++
        handler?.post(retireFeedback)
        ring.cancelPending(reason, uptime())
        presetGoals.clear()
        goals.fill(Double.NaN); muteSeeded.fill(false); playbackGoal = null; equalizerGoal = null; equalizerGoalId = 0
    }
    fun cancel() = synchronized(lock) { cancelLocked(CommandRing.Reason.FOCUS_CHANGED) }
    override fun close() {
        synchronized(lock) { if (closed) return; closed = true; cancelLocked(CommandRing.Reason.CLOSED) }
        handler?.removeCallbacks(drainTask)
    }
    fun writeResult(commandId: Long, reply: Parcel): Boolean = synchronized(result) {
        if (commandId < AudioControlActor.PREFIX || !ring.read(commandId - AudioControlActor.PREFIX, result)) return false
        reply.writeInt(1); reply.writeInt(result.state.ordinal); reply.writeString(result.reason.name); reply.writeLong(result.sequence)
        reply.writeLong(result.revision); reply.writeLong(result.revision)
        reply.writeLong(if (result.revision >= 0 && owner.presentedRevision >= result.revision) result.revision else -1)
        reply.writeDouble(Double.NaN); reply.writeDouble(Double.NaN)
        true
    }
    fun receiptState(commandId: Long): String? = synchronized(result) {
        if (commandId < AudioControlActor.PREFIX || !ring.read(commandId - AudioControlActor.PREFIX, result)) null else result.state.name
    }
    /** Last 64 admitted operations; result storage remains independently bounded at 256. */
    fun diagnostic(age: Int, destination: CommandRing.Entry): Boolean = synchronized(lock) {
        age in 0 until diagnosticCount && ring.read(diagnostics[(diagnosticHead - 1 - age + diagnostics.size) % diagnostics.size], destination)
    }
    companion object {
        private const val FADER = 0; private const val MUTE = 1; private const val TRANSPORT = 2
        private const val PANEL = 3; private const val PRESET = 4; private const val EQUALIZER = 5
        private const val EFFECT_LIFETIME_MS = 2_000L
    }
}
