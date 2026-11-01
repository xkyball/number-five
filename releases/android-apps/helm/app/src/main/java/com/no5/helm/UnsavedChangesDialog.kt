package com.no5.helm

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.State
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.NonSkippableComposable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.no5.ui.No5Text
import com.no5.ui.No5ActionFeedback
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

/** Local function references can compare equal even when captured arguments changed. */
@Composable @NonSkippableComposable internal fun <T> rememberCurrentReference(value: T): State<T> =
    remember { mutableStateOf(value, referentialEqualityPolicy()) }.apply { this.value = value }

/** Hosts pass the actual persistence operation; dispatch alone is not success. */
@Composable internal fun rememberUnsavedChangesGuard(
    dirty: Boolean,
    busy: Boolean = false,
    error: String? = null,
    night: Boolean = false,
    onSave: suspend () -> Boolean,
    onDiscard: () -> Unit,
    isDirty: () -> Boolean = { dirty },
    isBusy: () -> Boolean = { busy },
): ((() -> Unit) -> Unit) {
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    var saving by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val currentDirty by rememberCurrentReference(isDirty)
    val currentBusy by rememberCurrentReference(isBusy)
    val save by rememberCurrentReference(onSave)
    val discard by rememberCurrentReference(onDiscard)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    if (pending != null) UnsavedChangesDialog(
        onSave = {
            if (!saving && !currentBusy()) {
                saving = true
                failed = false
                scope.launch {
                    try {
                        if (save()) { val continuation = pending; pending = null; continuation?.invoke() }
                        else failed = true
                    } catch (cancel: CancellationException) { throw cancel }
                    catch (_: RuntimeException) { failed = true }
                    finally { saving = false }
                }
            }
        },
        onDiscard = { if (!saving && !currentBusy()) { discard(); val continuation = pending; pending = null; continuation?.invoke() } },
        onContinue = { if (!saving) { pending = null; failed = false } },
        saving = saving || busy,
        night = night,
        error = error ?: if (failed) No5Text.text(context, "Speichern fehlgeschlagen. Die Änderungen bleiben erhalten.") else null,
    )
    return remember {
        { continuation ->
            if (!saving && !currentBusy()) {
                if (currentDirty()) { pending = continuation; failed = false }
                else continuation()
            }
        }
    }
}

/** Close permission is explicit; a second outside tap only resumes editing. */
@Composable internal fun UnsavedChangesDialog(
    onSave: () -> Unit,
    onDiscard: () -> Unit,
    onContinue: () -> Unit,
    saving: Boolean = false,
    saveEnabled: Boolean = true,
    error: String? = null,
    night: Boolean = false,
) {
    val font = remember { FontFamily(Font(R.font.barlow_condensed_regular)) }
    com.no5.ui.No5DecisionDialog(
        title="Änderungen behalten?",
        body="Ungespeicherte Änderungen gehen beim Verwerfen verloren.",
        night=night,font=font,onSave=onSave,onDiscard=onDiscard,onContinue=onContinue,
        busy=saving,canSave=saveEnabled,error=error,
    )
}
