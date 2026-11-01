package com.no5.helm

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import com.no5.keyboard.SpecialKeyboardGeometry
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** Only the editor viewport changes. Typography and the surrounding physical stage do not. */
@Composable
internal fun ImeFormViewport(scale: Float, left: Float, top: Float, width: Float, height: Float, viewportTag:String="helm-form-viewport",
    content: @Composable () -> Unit) {
    val context=LocalContext.current
    val view=LocalView.current
    val density=LocalDensity.current
    var keyboard by remember{mutableStateOf<android.graphics.Rect?>(null)}
    DisposableEffect(context){val subscription=SpecialKeyboardGeometry.Subscription(context){keyboard=it};onDispose{subscription.close()}}
    BoxWithConstraints(Modifier.fillMaxSize().imePadding()) {
        val origin=IntArray(2);view.getLocationOnScreen(origin)
        val keyboardTop=keyboard?.let{with(density){(it.top-origin[1]).coerceAtLeast(0).toDp()}}?:maxHeight
        val visibleHeight = minOf((height * scale).dp, (minOf(maxHeight,keyboardTop) - (top * scale).dp).coerceAtLeast(0.dp))
        Box(Modifier.offset((left * scale).dp, (top * scale).dp)
            .size((width * scale).dp, visibleHeight).drawWithContent{
                // Preserve the canonical 1.5px frame stroke; the content and hit
                // budget remain the original IME-safe rectangle.
                val bleed=scale*density.density;val contentDraw=this
                clipRect(-bleed,-bleed,size.width+bleed,size.height+bleed){contentDraw.drawContent()}
            }.testTag(viewportTag)) {
            content()
        }
    }
}
