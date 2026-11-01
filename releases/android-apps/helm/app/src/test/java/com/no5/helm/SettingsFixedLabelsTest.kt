package com.no5.helm

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.no5.domain.SourceConfig
import com.no5.gateway.GatewaySessionPhase
import com.no5.gateway.GatewaySessionStatus
import com.no5.helm.ocean.BackgroundMode
import com.no5.helm.ocean.BackgroundSelection
import com.no5.ui.UiLanguage
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Fixed UI copy is translated; protocol reasons, origins and source observations are not. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "w1000dp-h600dp-land-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsFixedLabelsTest {
    @get:Rule val ui = createComposeRule()

    private fun native(tag:String):android.view.View? {
        fun all(v:android.view.View):List<android.view.View> = listOf(v)+if(v is android.view.ViewGroup)(0 until v.childCount).flatMap{all(v.getChildAt(it))}else emptyList()
        return android.view.inspector.WindowInspector.getGlobalWindowViews().flatMap{all(it)}.singleOrNull{it.tag==tag}
    }
    private fun tapNative(tag:String){ui.runOnIdle{requireNotNull(native(tag)).performClick()};ui.waitForIdle()}
    @Test fun backgroundOverviewShowsTheLocalizedModeRatherThanItsEnumName() {
        val context = RuntimeEnvironment.getApplication()
        val original = UiLanguage.current(context)
        val epoch = mutableIntStateOf(0)
        var mode = BackgroundMode.IMAGE
        ui.setContent {
            key(epoch.intValue) {
                MaterialTheme {
                    SourceSettingsScreenWithHistory(.5f, false, SourceConfig(), {},
                        onOpenGatewayConnection = {}, background = BackgroundSelection(mode))
                }
            }
        }
        val expected = mapOf(
            "de" to listOf("Hintergrundbild", "Animation", "Kein Hintergrund", "360° Video"),
            "en" to listOf("Background image", "Animation", "No background", "360° video"),
            "af" to listOf("Agtergrondprent", "Animasie", "Geen agtergrond", "360°-video"),
        )
        try {
            for ((language, labels) in expected) for ((index, target) in BackgroundMode.entries.withIndex()) {
                ui.runOnIdle { UiLanguage.acceptConfirmed(context, language); mode = target; epoch.intValue++ }
                tapNative("no5-row-open-background-settings")
                ui.runOnIdle{org.junit.Assert.assertTrue(requireNotNull(native("no5-row-background-mode")).contentDescription.toString().contains(labels[index]))}
                ui.onNodeWithText(target.name, substring = false).assertDoesNotExist()
            }
        } finally { ui.runOnIdle { UiLanguage.acceptConfirmed(context, original) } }
    }

    @Test fun knownCorruptSettingsWarningIsTranslatedButDynamicErrorsStayExact() {
        val context = RuntimeEnvironment.getApplication()
        val original = UiLanguage.current(context)
        val warning = "Saved source settings could not be read safely. Connections are disabled. Review and explicitly apply the app connection settings."
        val expected = mapOf(
            "de" to "Gespeicherte Quelleneinstellungen konnten nicht sicher gelesen werden. Verbindungen sind deaktiviert. Prüfe die Verbindungseinstellungen der App und übernimm sie ausdrücklich.",
            "en" to warning,
            "af" to "Gestoorde broninstellings kon nie veilig gelees word nie. Verbindings is gedeaktiveer. Hersien die app se verbindinginstellings en pas dit uitdruklik toe.",
        )
        try {
            for ((language, label) in expected) {
                UiLanguage.acceptConfirmed(context, language)
                assertEquals(label, sourceSettingsWarningLabel(warning))
                assertEquals("REMOTE_ERROR · payload remains exact", sourceSettingsWarningLabel("REMOTE_ERROR · payload remains exact"))
                // Even a dynamic error equal to another catalog key is not translated.
                assertEquals("Unavailable", sourceSettingsWarningLabel("Unavailable"))
            }
        } finally { UiLanguage.acceptConfirmed(context, original) }
    }

    @Test fun gatewayStatusLocalizesFixedPartsAndPreservesTheActualReasonAndOrigin() {
        val context = RuntimeEnvironment.getApplication()
        val original = UiLanguage.current(context)
        val epoch = mutableIntStateOf(0)
        val origin = "https://fixture.invalid:9443"
        val observation = "HELM · SOURCE · GOOD | WEATHER · SOURCE · UNAVAILABLE"
        var status = GatewaySessionStatus(GatewaySessionPhase.READY, origin)
        ui.setContent {
            key(epoch.intValue) {
                MaterialTheme { LuminaGatewayConnectionScreen(.5f, false, status, observation, {}, {}, {}) }
            }
        }
        val expected = mapOf(
            "de" to listOf("Gateway · Zugang bereit", "Gateway · Verbindung wird erneuert · 17 s", "Gateway · nicht verfügbar · TLS_HOSTNAME", "Gateway · Sitzung beendet"),
            "en" to listOf("Gateway · Access ready", "Gateway · Renewing connection · 17 s", "Gateway · Unavailable · TLS_HOSTNAME", "Gateway · Session ended"),
            "af" to listOf("Poort · Toegang gereed", "Poort · Verbinding word hernu · 17 s", "Poort · Nie beskikbaar nie · TLS_HOSTNAME", "Poort · Sessie beëindig"),
        )
        try {
            val phases = listOf(GatewaySessionPhase.READY, GatewaySessionPhase.WAITING, GatewaySessionPhase.UNAVAILABLE, GatewaySessionPhase.CLOSED)
            for ((language, labels) in expected) for ((index, target) in phases.withIndex()) {
                ui.runOnIdle {
                    UiLanguage.acceptConfirmed(context, language)
                    status = GatewaySessionStatus(target, origin, reason = "TLS_HOSTNAME", retryRemainingSeconds = 17)
                    epoch.intValue++
                }
                ui.runOnIdle{org.junit.Assert.assertTrue(requireNotNull(native("no5-row-gateway-session-status")).contentDescription.toString().contains(labels[index]))}
                tapNative("no5-row-gateway-session-status");focusHelmTestForegroundWindow(ui)
                ui.onNodeWithContentDescription(origin,substring=true).assertExists()
                ui.onNodeWithContentDescription(observation,substring=true).assertExists()
                ui.onNodeWithTag("Schließen").performClick();focusHelmTestForegroundWindow(ui)
            }
        } finally { ui.runOnIdle { UiLanguage.acceptConfirmed(context, original) } }
    }
}
