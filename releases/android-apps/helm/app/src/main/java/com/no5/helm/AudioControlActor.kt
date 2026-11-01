package com.no5.helm

import android.os.Parcel
import android.os.SystemClock
import com.no5.appcontrol.BufferedKnobAction
import com.no5.control.NormalizedKnobInput

/** Input binding only. The controller owns targets, limits, queue and source outcomes. */
internal class AudioControlActor(private val controller: AudioNativeController) : AutoCloseable {
    init { controller.controls.prepareBindings() }
    fun observe(ownerGeneration: Long = 1L, useSelectedFader: Boolean = true,
                state: com.no5.domain.AudioState = controller.state, output: Int = controller.selectedOutput) {
        controller.controls.observe(ownerGeneration, useSelectedFader, state, output)
    }
    fun action(input: NormalizedKnobInput) = object : BufferedKnobAction {
        override fun submit(sequence: Long, physicalUptimeMs: Long): Long {
            val now = SystemClock.uptimeMillis()
            if (sequence < 0 || physicalUptimeMs < 0 || physicalUptimeMs > now || now - physicalUptimeMs > 250) return 0
            return when (input) {
                NormalizedKnobInput.LEFT_CW -> controller.controls.adjustSelected(1, sequence, true)
                NormalizedKnobInput.LEFT_CCW -> controller.controls.adjustSelected(-1, sequence, true)
                NormalizedKnobInput.LEFT_PUSH -> controller.controls.toggleSelected(sequence, true)
                NormalizedKnobInput.RIGHT_CW -> controller.controls.transport(com.no5.domain.TransportAction.NEXT, sequence, true)
                NormalizedKnobInput.RIGHT_CCW -> controller.controls.transport(com.no5.domain.TransportAction.PREVIOUS, sequence, true)
                NormalizedKnobInput.RIGHT_PUSH -> controller.controls.togglePlayback(sequence, true)
                NormalizedKnobInput.LEFT_LONG_PUSH -> controller.controls.openPanel("mixer", sequence, true)
                NormalizedKnobInput.RIGHT_LONG_PUSH -> controller.controls.openPanel("media", sequence, true)
            }
        }
        override fun writeResult(commandId: Long, reply: Parcel) = controller.controls.writeResult(commandId, reply)
        override fun cancel() = controller.controls.cancel()
    }
    fun cancel() = controller.controls.cancel()
    override fun close() = cancel()
    companion object { const val PREFIX = 1L shl 48 }
}

/** Selection changes update the controller, not a second knob-specific fader model. */
@androidx.compose.runtime.Composable
internal fun ObserveAudioKnobActor(actor: AudioControlActor, controller: AudioNativeController, page: String, ownerGeneration: Long) {
    val state = controller.state
    val output = controller.selectedOutput
    androidx.compose.runtime.SideEffect { actor.observe(ownerGeneration, true, state, output) }
}
