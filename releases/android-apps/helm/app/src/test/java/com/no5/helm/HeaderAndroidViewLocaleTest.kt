package com.no5.helm

import android.graphics.Rect
import android.view.View
import android.widget.TextView
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.size
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.viewinterop.AndroidView
import com.no5.header.artwork.No5HeaderControlsView
import com.no5.ui.No5LocaleScope
import com.no5.ui.UiLanguage
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Actual Compose AndroidView update, never a direct-render-only locale test. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 35], qualifiers = "w1000dp-h600dp-land-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HeaderAndroidViewLocaleTest {
    @get:Rule val ui = createComposeRule()
    @Test fun dayHeaderUpdatesConfirmedLanguageWithoutRecreatingItsNativeView() = assertLanguageUpdates(false)
    @Test fun nightHeaderUpdatesConfirmedLanguageWithoutRecreatingItsNativeView() = assertLanguageUpdates(true)

    private fun assertLanguageUpdates(night: Boolean) {
        val context = RuntimeEnvironment.getApplication()
        val previous = UiLanguage.current(context)
        // Deliberately keep the process cache EN: the confirmed render input must own all header text.
        UiLanguage.acceptConfirmed(context, "en")
        val confirmed = mutableStateOf("en")
        val updates = mutableListOf<String>()
        val created = mutableListOf<No5HeaderControlsView>()
        val build = "FIXTURE · B117"
        ui.setContent {
            val confirmedLanguage = confirmed.value
            No5LocaleScope(confirmedLanguage) {
                AndroidView(
                    factory = { No5HeaderControlsView(it).also { view -> created += view } },
                    modifier = Modifier.size(1000.dp, 65.dp),
                    update = { view ->
                        view.render(build, night, true, {}, language = confirmedLanguage) {}
                        updates += confirmedLanguage
                    },
                )
            }
        }
        data class Labels(val day: String, val dark: String, val dayA11y: String, val darkA11y: String,
            val info: String, val localWlan: String)
        val labels = linkedMapOf(
            "en" to Labels("Day", "Night", "Day mode", "Night mode", "Version and Wi-Fi info",
                "Local radio reception · does not indicate Internet or gateway availability"),
            "af" to Labels("Dag", "Nag", "Dagmodus", "Nagmodus", "Weergawe en Wi-Fi-inligting",
                "Plaaslike radio-ontvangs · dui nie internet- of gatewaybeskikbaarheid aan nie"),
            "de" to Labels("Tag", "Nacht", "Tagmodus", "Nachtmodus", "Version und WLAN-Info",
                "Lokaler Funkempfang · keine Aussage über Internet oder Gateway"),
        )
        var initialBounds: List<Rect>? = null
        try {
            for ((language, expected) in labels) {
                ui.runOnIdle { confirmed.value = language }
                ui.waitForIdle()
                ui.runOnIdle {
                    assertEquals(1, created.size)
                    assertEquals(language, updates.last())
                    assertEquals("en", UiLanguage.current(context))
                    val header = created.single()
                    val day = header.findViewWithTag<TextView>("header-day")
                    val dark = header.findViewWithTag<TextView>("header-night")
                    val info = header.findViewWithTag<View>("header-network-info")
                    val version = header.findViewWithTag<TextView>("installed-app-version")
                    assertEquals(expected.day, day.text.toString())
                    assertEquals(expected.dark, dark.text.toString())
                    assertEquals(expected.dayA11y, day.contentDescription.toString())
                    assertEquals(expected.darkA11y, dark.contentDescription.toString())
                    assertTrue(info.contentDescription.toString().startsWith(expected.info + ". "))
                    assertTrue(header.wifiDetails().endsWith(expected.localWlan))
                    assertEquals(build, version.text.toString())
                    assertEquals(!night, day.isSelected); assertEquals(night, dark.isSelected)
                    val bounds = listOf(day, dark, info, version).map { Rect(it.left, it.top, it.right, it.bottom) }
                    if (initialBounds == null) initialBounds = bounds else assertEquals(initialBounds, bounds)
                }
            }
        } finally { ui.runOnIdle { UiLanguage.acceptConfirmed(context, previous) } }
    }
}
