package com.no5.preparation.ui

import android.os.Build
import android.view.View
import android.view.WindowManager
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.NonSkippableComposable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.no5.keyboard.SpecialKeyboardGeometry

/** Window pixels, using the same centered 2000×1200 calibration as No5Shell.
 * Keep the whole panel in the central corridor: backgrounds, shadows, scroll
 * content and fixed actions all stay outside both physical knob exclusions.
 */
internal fun knobSafeDialogBounds(width: Float, height: Float, margin: Float, imeBottom: Float = 0f): Rect {
    require(width > 0f && height > 0f && margin >= 0f && imeBottom >= 0f)
    val scale = minOf(width / KnobGeometry.STAGE_WIDTH, height / 1200f)
    val stageLeft = (width - KnobGeometry.STAGE_WIDTH * scale) / 2f
    val leftKnob = KnobGeometry.knobs.first()
    val rightKnob = KnobGeometry.knobs.last()
    val left = maxOf(margin, stageLeft + (leftKnob.cx + leftKnob.rx + KnobGeometry.TOUCH_CLEARANCE) * scale + margin)
    val right = minOf(width - margin, stageLeft + (rightKnob.cx - rightKnob.rx - KnobGeometry.TOUCH_CLEARANCE) * scale - margin)
    // IME changes only the available bottom edge. Recomputing the calibrated
    // scale from the remaining height would move the physical knob exclusions.
    return Rect(left, margin, maxOf(left, right), maxOf(margin, height - imeBottom - margin))
}

/** Bounded dialog content with a fixed safe area; callers own their theme and
 * scrolling. Android density remains unchanged, including accessibility fonts.
 */
@Composable @NonSkippableComposable fun No5KnobSafeDialog(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    outsideTapBounds: Rect? = null,
    content: @Composable () -> Unit,
) {
    Dialog(onDismissRequest, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val view = LocalView.current
        val context = LocalContext.current
        var specialKeyboard by remember { mutableStateOf<android.graphics.Rect?>(null) }
        DisposableEffect(context) {
            val subscription = SpecialKeyboardGeometry.Subscription(context) { specialKeyboard = it }
            onDispose { subscription.close() }
        }
        val window = (view.parent as? DialogWindowProvider)?.window
        // A platform dialog has a separate window. Preserve the immersive stage
        // instead of letting its bars or IME change the physical knob calibration.
        DisposableEffect(window) {
            window?.apply {
                clearFlags(WindowManager.LayoutParams.FLAG_FORCE_NOT_FULLSCREEN)
                addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
                setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
                WindowCompat.setDecorFitsSystemWindows(this, false)
                if (Build.VERSION.SDK_INT < 30) {
                    @Suppress("DEPRECATION")
                    decorView.systemUiVisibility = decorView.systemUiVisibility or
                        View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                        View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_FULLSCREEN or
                        View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                }
                WindowCompat.getInsetsController(this, decorView).apply {
                    systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    hide(WindowInsetsCompat.Type.systemBars())
                }
            }
            onDispose { }
        }
        BoxWithConstraints(Modifier.fillMaxSize().testTag("knob-safe-dialog-window")) {
            val density = LocalDensity.current
            val origin = IntArray(2).also { view.getLocationOnScreen(it) }
            val heightPx = with(density) { maxHeight.toPx() }
            val widthPx = with(density) { maxWidth.toPx() }
            val specialBottom = specialKeyboard?.takeIf {
                it.bottom > origin[1] && it.right > origin[0] && it.left < origin[0] + widthPx
            }?.let { heightPx - (it.top - origin[1]).toFloat().coerceIn(0f, heightPx) } ?: 0f
            val imeBottom = maxOf(WindowInsets.ime.getBottom(density).toFloat(), specialBottom)
            val bounds = with(density) { knobSafeDialogBounds(maxWidth.toPx(), maxHeight.toPx(), 12.dp.toPx(), imeBottom) }
            val visiblePanel = outsideTapBounds ?: bounds
            // Dialog itself occupies the entire immersive window, so Android's
            // ordinary outside-window dismissal can never see these taps.
            // Hit-test against the real panel. Blank scrim has no child drag
            // recognizer, so explicitly reject motion beyond Android touch slop.
            // Function-reference equality does not describe its captured draft.
            val dismiss by remember { mutableStateOf(onDismissRequest, referentialEqualityPolicy()) }
                .apply { value = onDismissRequest }
            Box(Modifier.fillMaxSize().pointerInput(visiblePanel) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (visiblePanel.contains(down.position)) return@awaitEachGesture
                    var eligible = !down.isConsumed && currentEvent.changes.count { it.pressed } == 1
                    val origin = down.position
                    down.consume()
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.find { it.id == down.id } ?: break
                        if (event.changes.any { it.id != down.id && (it.pressed || it.previousPressed) } ||
                            change.isConsumed || visiblePanel.contains(change.position) ||
                            (change.position - origin).getDistance() > viewConfiguration.touchSlop)
                            eligible = false
                        event.changes.forEach { it.consume() }
                        if (!change.pressed) {
                            if (eligible && change.previousPressed) dismiss()
                            break
                        }
                    }
                }
            }) {
                Box(with(density) {
                    modifier.absoluteOffset(bounds.left.toDp(), bounds.top.toDp())
                        .size(bounds.width.toDp(), bounds.height.toDp())
                }.clipToBounds().testTag("knob-safe-dialog-content")) { content() }
            }
        }
    }
}
