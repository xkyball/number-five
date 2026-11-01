package com.no5.helm

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput

/** A blank scrim must enforce slop itself; it has no child drag recognizer. */
internal fun Modifier.outsidePanelTap(bounds: Rect, onOutside: () -> Unit): Modifier = pointerInput(bounds) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        if (bounds.contains(down.position)) return@awaitEachGesture
        var eligible = !down.isConsumed && currentEvent.changes.count { it.pressed } == 1
        val origin = down.position
        down.consume()
        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.find { it.id == down.id } ?: break
            if (event.changes.any { it.id != down.id && (it.pressed || it.previousPressed) } ||
                change.isConsumed || bounds.contains(change.position) ||
                (change.position - origin).getDistance() > viewConfiguration.touchSlop)
                eligible = false
            event.changes.forEach { it.consume() }
            if (!change.pressed) {
                if (eligible && change.previousPressed) onOutside()
                break
            }
        }
    }
}
