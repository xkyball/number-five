package com.no5.helm

import android.os.Parcel
import android.os.SystemClock
import com.no5.appcontrol.BufferedKnobAction
import com.no5.control.NormalizedKnobInput

/** Physical input selects the same controller-owned controls as touch; no sonar maths here. */
internal class SonarControlActor(private val controller: SonarNativeController) : AutoCloseable {
    init { controller.controls.prepareBindings() }
    fun observe() = controller.controls.observe()
    fun action(input: NormalizedKnobInput) = object : BufferedKnobAction {
        override fun submit(sequence: Long, physicalUptimeMs: Long): Long {
            val now = SystemClock.uptimeMillis()
            if (sequence < 0 || physicalUptimeMs < 0 || physicalUptimeMs > now || now - physicalUptimeMs > 250) return 0
            return when (input) {
                NormalizedKnobInput.LEFT_CW -> controller.controls.adjustRange(1, sequence, true)
                NormalizedKnobInput.LEFT_CCW -> controller.controls.adjustRange(-1, sequence, true)
                NormalizedKnobInput.LEFT_PUSH -> controller.controls.freeze(sequence, true)
                NormalizedKnobInput.RIGHT_CW -> controller.controls.adjustGain(1, sequence, true)
                NormalizedKnobInput.RIGHT_CCW -> controller.controls.adjustGain(-1, sequence, true)
                NormalizedKnobInput.RIGHT_PUSH -> controller.controls.aScope(sequence, true)
                NormalizedKnobInput.LEFT_LONG_PUSH -> controller.controls.mode(sequence, true)
                NormalizedKnobInput.RIGHT_LONG_PUSH -> controller.controls.open(sequence, true)
            }
        }
        override fun writeResult(commandId: Long, reply: Parcel) = controller.controls.writeResult(commandId, reply)
        override fun cancel() = controller.controls.cancel()
    }
    fun cancel() = controller.controls.cancel()
    override fun close() = cancel()
}
