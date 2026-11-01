package com.no5.helm.control

import com.no5.control.NormalizedKnobInput
import com.no5.domain.TransportAction
import com.no5.helm.AudioNativeController

/** Legacy binding seam: maps input to the same owned controls, without targets or wire commands. */
internal class AudioKnobActions(
    private val controller: AudioNativeController,
    private val specialActions: HelmAudioSpecialActions = HelmAudioSpecialActions.NONE,
) {
    fun perform(input: NormalizedKnobInput): Boolean = when (input) {
        NormalizedKnobInput.LEFT_CW -> controller.adjustSelectedFader(1)
        NormalizedKnobInput.LEFT_CCW -> controller.adjustSelectedFader(-1)
        NormalizedKnobInput.LEFT_PUSH -> controller.toggleSelectedMute()
        NormalizedKnobInput.RIGHT_CW -> controller.transport(TransportAction.NEXT)
        NormalizedKnobInput.RIGHT_CCW -> controller.transport(TransportAction.PREVIOUS)
        NormalizedKnobInput.RIGHT_PUSH -> controller.togglePlayback()
        NormalizedKnobInput.LEFT_LONG_PUSH, NormalizedKnobInput.RIGHT_LONG_PUSH -> specialActions.perform(input)
    }
}
