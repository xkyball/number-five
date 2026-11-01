package com.no5.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** A locale change invalidates this subtree, without key/recreate or loss of remembered UI state. */
val LocalNo5Language = staticCompositionLocalOf { UiLanguage.DEFAULT }
val LocalNo5LanguageChange = staticCompositionLocalOf<((String) -> Unit)?> { null }

@Composable
fun No5LocaleScope(language: String, onLanguageChange: ((String) -> Unit)? = null, content: @Composable () -> Unit) {
    // Static locals invalidate their entire content. A recreated caller lambda
    // is not a language/availability change and must not invalidate every panel
    // for each incoming telemetry packet. Keep the action current behind one
    // stable forwarding callback; real availability changes still publish null.
    val latestChange=rememberUpdatedState(onLanguageChange)
    val available=onLanguageChange!=null
    val change:((String)->Unit)?=remember(available) {
        if(available)({value:String->latestChange.value?.invoke(value);Unit})else null
    }
    CompositionLocalProvider(LocalNo5Language provides UiLanguage.requireSupported(language),
        LocalNo5LanguageChange provides change, content = content)
}

/** Prefer this reader for new Compose-only labels. Native painters use No5Text directly. */
@Composable
fun no5Text(source: String, vararg arguments: Any?): String =
    No5Text.text(LocalNo5Language.current, source, *arguments)

/** Shared in-app choice. The central owner confirms the choice; this does not recreate the host. */
@Composable
fun No5LanguageSelector(modifier: Modifier = Modifier, language: String = LocalNo5Language.current,
    onLanguageChange: ((String) -> Unit)? = LocalNo5LanguageChange.current) {
    val change = onLanguageChange
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(no5Text("App-Sprache"))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            No5Text.languages().forEach { code ->
                No5ActionFeedback(enabled = change != null, shape = ButtonDefaults.shape) { feedback ->
                    OutlinedButton(onClick = { change?.invoke(code) }, enabled = feedback.enabled,
                        interactionSource = feedback.interactionSource,
                        modifier = Modifier.testTag("no5-language-$code").semantics { selected = language == code }.then(feedback.modifier)) {
                        Text(No5Text.languageName(code))
                    }
                }
            }
        }
        Text(no5Text("Für alle No5-Apps"))
        if (change == null) Text(no5Text("Lumina Control ist nicht verfügbar."))
    }
}
