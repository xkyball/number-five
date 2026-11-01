package com.no5.helm

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import com.no5.helm.diagnostics.GatewayDiagnosticExportFormat
import com.no5.domain.SourceConfig
import com.no5.domain.SourceMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-rUS-w2000dp-h1200dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SourceSettingsScreenTest {
    @get:Rule val ui = createComposeRule()
    @Before fun englishEditorFixture() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        com.no5.ui.UiLanguage.acceptConfirmed(context, "en")
    }

    private fun native(tag:String):android.view.View? {
        fun all(v:android.view.View):List<android.view.View> = listOf(v)+if(v is android.view.ViewGroup)(0 until v.childCount).flatMap{all(v.getChildAt(it))}else emptyList()
        return android.view.inspector.WindowInspector.getGlobalWindowViews().flatMap{all(it)}.singleOrNull{it.tag==tag}
    }
    private fun tapNative(tag:String){ui.runOnIdle{val view=requireNotNull(native(tag));org.junit.Assert.assertTrue(view.isEnabled);view.performClick()};ui.waitForIdle()}
    @Test fun disabledConnectionStartsCleanAndBackWithoutEditsNeedsNoDecisionOrWrite() {
        val config=SourceConfig(mode=SourceMode.PI,connectionEnabled=false)
        val writes=mutableListOf<SourceConfig>()
        var guard: ((() -> Unit) -> Unit)? = null
        var returned=0
        ui.setContent {
            SourceSettingsScreenWithHistory(1f,false,config,{writes+=it},
                onOpenGatewayConnection={},registerNavigationGuard={guard=it})
        }
        ui.onNodeWithTag("source-settings-apply").assertDoesNotExist()
        ui.runOnIdle { requireNotNull(guard).invoke { returned++ } }
        ui.onNodeWithTag("unsaved-changes-dialog").assertDoesNotExist()
        ui.runOnIdle { assertEquals(1,returned);assertEquals(emptyList<SourceConfig>(),writes) }
        tapNative("no5-row-open-display-settings")
        tapNative("no5-action-settings-detail-back")
        ui.runOnIdle{assertNotNull(native("no5-row-open-display-settings"))}
        ui.onNodeWithTag("unsaved-changes-dialog").assertDoesNotExist()
    }

    @Test fun savingLocalUnitsPreservesTheAuthoritativeModeAndDisabledAdmission() {
        val config=SourceConfig(mode=SourceMode.DEMO,connectionEnabled=false)
        val writes=mutableListOf<SourceConfig>()
        ui.setContent {
            SourceSettingsScreenWithHistory(1f,false,config,{writes+=it},onOpenGatewayConnection={})
        }
        tapNative("no5-row-open-display-settings")
        tapNative("no5-row-settings-depth-dropdown")
        focusHelmTestForegroundWindow(ui)
        ui.onNodeWithTag("settings-depth-FEET").assertIsEnabled().performClick()
        ui.onNodeWithTag("settings-choice-close").performClick()
        focusHelmTestForegroundWindow(ui)
        tapNative("no5-action-source-settings-apply")
        ui.runOnIdle {
            assertEquals(listOf(config.copy(displayUnits=config.displayUnits.copy(depth=com.no5.domain.DepthUnit.FEET))),writes)
        }
    }

    @Test fun navigationWaitsForActualBackgroundSaveAndFailedSaveKeepsDraft() {
        val background = com.no5.helm.ocean.BackgroundSelection(com.no5.helm.ocean.BackgroundMode.IMAGE)
        var guard: ((() -> Unit) -> Unit)? = null
        var request: com.no5.helm.ocean.BackgroundSelection.Save? = null
        var navigations = 0
        var saves = 0
        ui.setContent {
            SourceSettingsScreenWithHistory(1f, false,
                SourceConfig(mode = SourceMode.PI, endpoint = "https://lysmarine.local:9443"), {},
                onOpenGatewayConnection = {}, background = background,
                onSaveBackground = { saves++; request = background.beginSave() },
                registerNavigationGuard = { guard = it })
        }
        ui.runOnIdle {
            background.select(com.no5.helm.ocean.BackgroundMode.NONE)
            requireNotNull(guard).invoke { navigations++ }
        }
        focusHelmTestForegroundWindow(ui)
        ui.onNodeWithTag("unsaved-changes-save").assertIsEnabled().performClick()
        focusHelmTestForegroundWindow(ui)
        ui.runOnIdle { assertNotNull(request); assertEquals(1, saves); assertEquals(0, navigations) }
        ui.onNodeWithTag("unsaved-changes-save").assertIsNotEnabled()
        ui.runOnIdle { background.finishSave(requireNotNull(request), false) }
        ui.onNodeWithTag("unsaved-changes-error").assertExists()
        ui.runOnIdle { assertEquals(com.no5.helm.ocean.BackgroundMode.NONE, background.draft); assertEquals(0, navigations) }
        focusHelmTestForegroundWindow(ui)
        ui.onNodeWithTag("unsaved-changes-save").assertIsEnabled().performClick()
        focusHelmTestForegroundWindow(ui)
        ui.runOnIdle { assertEquals(2, saves); background.finishSave(requireNotNull(request), true) }
        ui.waitForIdle()
        ui.runOnIdle { assertEquals(1, navigations); assertEquals(com.no5.helm.ocean.BackgroundMode.NONE, background.active) }
    }

    @Test fun selectingRecentEndpointLoadsItIntoTheExistingConnectionDraftAndSavesNormally() {
        val config = SourceConfig(mode = SourceMode.PI, endpoint = "http://active.local:8080")
        var applied: SourceConfig? = null
        ui.setContent {
            SourceSettingsScreenWithHistory(
                scale = 1f,
                night = false,
                config = config,
                onApply = { applied = it },
                endpointHistory = listOf(config.endpoint, "https://recent.local:9443"),
            )
        }
        ui.onNodeWithTag("source-history-1").performClick()
        ui.waitForIdle()
        ui.onNodeWithTag("source-endpoint").assertTextContains("https://recent.local:9443")
        ui.onNodeWithTag("source-settings-apply").performScrollTo().performClick()
        ui.runOnIdle { assertEquals("https://recent.local:9443", applied?.endpoint) }
    }

    @Test fun hostCanMovePiAddressAndSecurityMaterialToTheDedicatedConnectionSubpage() {
        var opened = 0
        ui.setContent {
            SourceSettingsScreenWithHistory(
                scale = 1f,
                night = false,
                config = SourceConfig(mode = SourceMode.PI, endpoint = "https://lysmarine.local:9443"),
                onApply = {},
                onOpenGatewayConnection = { opened++ },
            )
        }

        tapNative("no5-row-open-gateway-connection")
        ui.runOnIdle { assertEquals(1, opened) }
    }

    @Test fun productSettingsUseCompactOverviewAndFocusedDisplayDetail() {
        ui.setContent {
            SourceSettingsScreenWithHistory(
                scale = 1f,
                night = false,
                config = SourceConfig(mode = SourceMode.PI, endpoint = "https://lysmarine.local:9443"),
                onApply = {},
                onOpenGatewayConnection = {},
            )
        }

        tapNative("no5-row-open-display-settings")
        ui.runOnIdle{assertNotNull(native("no5-row-settings-speed-dropdown"))}
        ui.runOnIdle{assertNotNull(native("no5-row-settings-consumption-dropdown"))}
        ui.runOnIdle{assertNotNull(native("no5-row-settings-depth-dropdown"))}
        ui.runOnIdle{assertNotNull(native("no5-action-source-settings-apply"))}
        ui.onNodeWithTag("open-camilla-configuration").assertDoesNotExist()
        tapNative("no5-action-settings-detail-back")
        ui.runOnIdle{assertNotNull(native("no5-row-open-weather-settings"))}
    }

    @Test fun dedicatedConnectionPageKeepsHttpMaterialReloadAndProbeControlsFailClosed() {
        ui.setContent {
            val context = LocalContext.current
            val diagnostics = remember { GatewayConnectionDiagnosticsModel(context) }
            GatewayConnectionScreen(
                scale = 1f,
                night = false,
                config = SourceConfig(mode = SourceMode.PI, endpoint = "http://lysmarine.local:8789"),
                endpointHistory = emptyList(),
                diagnostics = diagnostics,
                gatewayKeyStatus = null,
                gatewayTrustStatus = null,
                activeConnectionStatus = "HELM · SOURCE · UNAVAILABLE | WEATHER · SOURCE · UNAVAILABLE",
                scope = rememberCoroutineScope(),
                diagnosticTransferStatus = null,
                onBack = {}, onSaveEndpoint = {}, onImportGatewayKey = {}, onImportGatewayTrust = {}, onReload = {},
                onCopyDiagnostics = {}, onExportDiagnostics = { _: GatewayDiagnosticExportFormat, _: ByteArray -> },
            )
        }

        ui.onNodeWithTag("gateway-key-unavailable").assertTextContains("saved HTTPS gateway origin", substring = true)
        ui.onNodeWithTag("gateway-key-import").performScrollTo().assertIsNotEnabled()
        ui.onNodeWithTag("gateway-ca-import").performScrollTo().assertIsNotEnabled()
        ui.onNodeWithTag("gateway-reload").performScrollTo().assertIsNotEnabled()
        ui.onNodeWithTag("gateway-test").performScrollTo().assertIsNotEnabled()
        ui.onNodeWithTag("gateway-log-empty").performScrollTo().assertTextContains("No diagnostic attempts", substring = true)
    }

    @Test fun savedHttpsConnectionOffersExplicitKeyFileImportAndDeleteConfirmationWithoutClaimingConnection() {
        var deleteSource: Boolean? = null
        ui.setContent {
            SourceSettingsScreenWithHistory(
                scale = 1f,
                night = false,
                config = SourceConfig(mode = SourceMode.PI, endpoint = "https://lysmarine.local:9443"),
                onApply = {},
                gatewayKeyStatus = "Key saved, connection not checked.",
                onImportGatewayKey = { deleteSource = it },
            )
        }
        ui.onNodeWithTag("gateway-key-status").assertTextEquals("Key saved, connection not checked.")
        ui.onNodeWithText("Import a user-provisioned key file for this exact saved HTTPS origin (scheme, host and port). The key is stored separately and does not prove a connection.")
        ui.onNodeWithTag("gateway-key-import").performScrollTo().performClick()
        ui.runOnIdle { assertEquals(false, deleteSource) }
        ui.onNodeWithTag("gateway-key-delete-source").performScrollTo().performClick()
        ui.onNodeWithTag("gateway-key-import").performScrollTo().performClick()
        ui.runOnIdle { assertEquals(true, deleteSource) }
    }

    @Test fun savedHttpsConnectionRequiresExplicitExactOriginConfirmationBeforeCaSelection() {
        var selections = 0
        val endpoint = "https://lysmarine.local:9443"
        ui.setContent {
            SourceSettingsScreenWithHistory(
                scale = 1f,
                night = false,
                config = SourceConfig(mode = SourceMode.PI, endpoint = endpoint),
                onApply = {},
                gatewayTrustStatus = "Gateway CA saved, connection not checked.",
                onImportGatewayTrust = { selections++ },
            )
        }

        ui.onNodeWithTag("gateway-ca-status").performScrollTo().assertTextEquals("Gateway CA saved, connection not checked.")
        ui.onNodeWithText("I confirm: this is a public CA certificate for the exact saved HTTPS gateway origin $endpoint.")
            .performScrollTo()
        ui.onNodeWithTag("gateway-ca-import").performScrollTo().assertIsNotEnabled()
        ui.onNodeWithTag("gateway-ca-confirm").performScrollTo().performClick()
        ui.onNodeWithTag("gateway-ca-import").performScrollTo().performClick()
        ui.runOnIdle { assertEquals(1, selections) }
    }

    @Test fun savedHttpConnectionExplainsWhyGatewayKeyImportAndDeletionConfirmationAreDisabled() {
        ui.setContent {
            SourceSettingsScreenWithHistory(
                scale = 1f,
                night = false,
                config = SourceConfig(mode = SourceMode.PI, endpoint = "http://lysmarine.local:8789"),
                onApply = {},
                onImportGatewayKey = {},
                onImportGatewayTrust = {},
            )
        }

        ui.onNodeWithTag("gateway-key-unavailable").assertTextContains("current address uses HTTP", substring = true)
        ui.onNodeWithTag("gateway-key-import").performScrollTo().assertIsNotEnabled()
        ui.onNodeWithTag("gateway-key-delete-source").performScrollTo().assertIsNotEnabled()
        ui.onNodeWithTag("gateway-ca-unavailable").performScrollTo().assertTextContains("current address uses HTTP", substring = true)
        ui.onNodeWithTag("gateway-ca-confirm").performScrollTo().assertIsNotEnabled()
        ui.onNodeWithTag("gateway-ca-import").performScrollTo().assertIsNotEnabled()
    }

    @Test fun changingAnHttpsDraftDisablesCaSelectionUntilThatExactOriginIsSaved() {
        ui.setContent {
            SourceSettingsScreenWithHistory(
                scale = 1f,
                night = false,
                config = SourceConfig(mode = SourceMode.PI, endpoint = "https://saved.local:9443"),
                onApply = {},
                onImportGatewayTrust = {},
            )
        }

        ui.onNodeWithTag("source-endpoint").performTextReplacement("https://different.local:9443")
        ui.onNodeWithTag("gateway-ca-unavailable").performScrollTo()
            .assertTextContains("save the changed HTTPS gateway address first", substring = true)
        ui.onNodeWithTag("gateway-ca-confirm").performScrollTo().assertIsNotEnabled()
        ui.onNodeWithTag("gateway-ca-import").performScrollTo().assertIsNotEnabled()
    }
}
