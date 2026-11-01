package com.no5.helm

import androidx.compose.runtime.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.no5.ui.LocalNo5Language
import com.no5.ui.LocalNo5LanguageChange
import com.no5.ui.No5LocaleScope
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29])
class No5LocaleScopeInvalidationTest {
    @get:Rule val ui=createComposeRule()
    @Test fun unrelatedCallbackReplacementKeepsSubtreeButUsesLatestActionAndRealLanguageAvailabilityChanges() {
        val version=mutableIntStateOf(0)
        val language=mutableStateOf("de")
        val available=mutableStateOf(true)
        val renders=AtomicInteger()
        val action=AtomicReference<((String)->Unit)?>(null)
        val chosen=AtomicReference("")
        val shownLanguage=AtomicReference("")
        ui.setContent {
            val revision=version.intValue
            val content:@Composable ()->Unit=remember { {
                val callback=LocalNo5LanguageChange.current
                val code=LocalNo5Language.current
                SideEffect { renders.incrementAndGet();action.set(callback);shownLanguage.set(code) }
            } }
            No5LocaleScope(language.value,if(available.value)({value->chosen.set("$revision:$value")})else null,content)
        }
        ui.waitForIdle();val initial=renders.get();assertTrue(initial>0)
        ui.runOnIdle{version.intValue=1};ui.waitForIdle()
        ui.runOnIdle {
            assertEquals("A new callback object must not invalidate the entire static locale subtree",initial,renders.get())
            requireNotNull(action.get()).invoke("en")
            assertEquals("1:en",chosen.get())
            language.value="en"
        }
        ui.waitForIdle();val changed=renders.get()
        ui.runOnIdle {assertTrue(changed>initial);assertEquals("en",shownLanguage.get());available.value=false}
        ui.waitForIdle()
        ui.runOnIdle {assertTrue(renders.get()>changed);assertNull(action.get())}
    }
}
