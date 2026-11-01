package com.no5.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

val LocalNo5ActionFeedbackColor = staticCompositionLocalOf { Color(0xffff5b00) }

/** Transient interaction only. Selection/toggle state belongs to the caller's model. */
class No5ActionFeedbackState internal constructor(
    val interactionSource: MutableInteractionSource,
    val enabled: Boolean,
    val modifier: Modifier,
    val pressed: Boolean,
    val focused: Boolean,
)

/** Survives enabled/window/lifecycle changes until that pointer sequence terminates. */
private class ActionGestureGuard {
    var active = false
        private set
    var cancelled = false
        private set
    private var pointerActive = false
    fun updateActive(value: Boolean) {
        active = value
        if (!value && pointerActive) cancelled = true
    }
    fun begin() { pointerActive = true; cancelled = !active }
    fun cancel() { cancelled = true }
    fun finish() { pointerActive = false }
}

/**
 * Standard clickable still owns press/release/cancel and activation. This observer only
 * disqualifies extra pointers and excursions out of the actual target before clickable sees
 * them. A retained guard consumes a disqualified sequence through its final UP. Disabling
 * clickable clears its visual press but does not, by itself, end its tap recognizer.
 */
@Composable
private fun rememberActionFeedback(enabled: Boolean, feedbackColor: Color, shape: Shape): No5ActionFeedbackState {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var resumed by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ -> resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val active = enabled && resumed && LocalWindowInfo.current.isWindowFocused
    val guard = remember { ActionGestureGuard() }
    guard.updateActive(active)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val focused by interaction.collectIsFocusedAsState()
    val feedback = Modifier.pointerInput(guard) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            guard.begin()
            if (guard.cancelled) currentEvent.changes.forEach { it.consume() }
            try {
                do {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val pointer = event.changes.firstOrNull { it.id == down.id }
                    if (!guard.active || pointer == null || event.changes.any { it.id != down.id && (it.pressed || it.previousPressed) } ||
                        pointer.position.x < 0f || pointer.position.x >= size.width ||
                        pointer.position.y < 0f || pointer.position.y >= size.height)
                        guard.cancel()
                    if (guard.cancelled) event.changes.forEach { it.consume() }
                } while (event.changes.any { it.pressed })
            } finally { guard.finish() }
        }
    }.drawWithContent {
        drawContent()
        if (active) {
            val outline = shape.createOutline(size, layoutDirection, this)
            if (pressed) drawOutline(outline, feedbackColor.copy(alpha = .14f))
            // Focus is a separate, unfilled cue; it can never look like a latched press.
            if (focused) {
                val width = 2.dp.toPx()
                inset(width / 2) {
                    drawOutline(shape.createOutline(size, layoutDirection, this), feedbackColor, style = Stroke(width))
                }
            }
        }
    }
    return No5ActionFeedbackState(interaction, active, feedback, active && pressed, active && focused)
}

/** A common action surface without Material's persistent focus state-layer. */
fun Modifier.no5Clickable(
    enabled: Boolean = true,
    role: Role? = null,
    onClickLabel: String? = null,
    feedbackColor: Color? = null,
    shape: Shape = RectangleShape,
    onClick: () -> Unit,
): Modifier = composed {
    val feedback = rememberActionFeedback(enabled, feedbackColor ?: LocalNo5ActionFeedbackColor.current, shape)
    this.then(feedback.modifier).clickable(
        enabled = feedback.enabled, role = role, onClickLabel = onClickLabel,
        interactionSource = feedback.interactionSource, indication = null, onClick = onClick,
    )
}

/** Stock Material buttons keep their geometry and gestures, with the same No5 feedback. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun No5ActionFeedback(
    enabled: Boolean = true,
    feedbackColor: Color? = null,
    shape: Shape = RectangleShape,
    content: @Composable (No5ActionFeedbackState) -> Unit,
) {
    val feedback = rememberActionFeedback(enabled, feedbackColor ?: LocalNo5ActionFeedbackColor.current, shape)
    CompositionLocalProvider(LocalRippleConfiguration provides null) { content(feedback) }
}

/** D03 Canvas actions own their face before drawing text, without a second state-layer. */
@Composable
fun No5ActionCanvas(modifier:Modifier=Modifier,enabled:Boolean=true,selected:Boolean=false,night:Boolean,
                    designWidth:Float,designOrigin:androidx.compose.ui.geometry.Offset=androidx.compose.ui.geometry.Offset.Zero,onClick:()->Unit,draw:androidx.compose.ui.graphics.drawscope.DrawScope.()->Unit) {
    val feedback=rememberActionFeedback(enabled,Color.Transparent,RectangleShape)
    androidx.compose.foundation.Canvas(modifier.then(feedback.modifier).clickable(
        enabled=feedback.enabled,interactionSource=feedback.interactionSource,indication=null,onClick=onClick)) {
        val canvas=drawContext.canvas.nativeCanvas
        val p=android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        val u=size.width/designWidth
        // Keep the reference's global logical rounded-rect coordinates. Drawing the
        // same curve in child-local coordinates changes Skia's AA flattening at corners.
        canvas.save();canvas.scale(u,u);canvas.translate(-designOrigin.x,-designOrigin.y)
        val bounds=android.graphics.RectF(designOrigin.x,designOrigin.y,designOrigin.x+designWidth,designOrigin.y+96f)
        No5ActionInk.face(canvas,p,bounds,night,selected,feedback.pressed,feedback.focused,1f)
        if(feedback.focused)No5ActionInk.focus(canvas,p,bounds,night,1f)
        canvas.restore()
        draw()
    }
}
