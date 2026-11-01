package com.no5.helm

import android.os.Build
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.no5.domain.*
import com.no5.helm.network.*
import kotlinx.coroutines.delay
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.After
import org.junit.Rule
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

/** Actual Android WS/decoder/SourceSession/controller/native UI, explicit synthetic host fixture. */
class Sonard035NativeTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private lateinit var controller: SonarNativeController
    private lateinit var endpoint: String
    private val night = mutableStateOf(false)

    private fun mode(value: String) {
        require(value in setOf("stable", "unstable", "unverified"))
        val request = URL("$endpoint/fixture/mode?value=$value").openConnection() as HttpURLConnection
        request.connectTimeout = 1500; request.readTimeout = 1500
        try { assertEquals(200, request.responseCode); request.inputStream.close() } finally { request.disconnect() }
    }

    @Before fun render() {
        assumeTrue("Explicit emulator-only contract fixture", Build.HARDWARE in setOf("ranchu", "goldfish"))
        val port = InstrumentationRegistry.getArguments().getString("sonard035FixturePort")?.toIntOrNull()
        require(port != null && port in 1024..65535) { "Start the loopback-only Sonard fixture server and pass sonard035FixturePort" }
        endpoint = "http://10.0.2.2:$port"; mode("stable")
        ui.runOnUiThread {
            val source = NetworkPiSonarSource(SourceIdentity("sonard035-emulator", SourceMode.PI, "EXPLICIT SYNTHETIC FIXTURE"),
                UrlConnectionPiTransport(endpoint, PiNetworkPolicy.SONAR), OkHttpSonarStreamTransport(endpoint))
            controller = SonarNativeController(source)
            ui.activity.setContent { MaterialTheme {
                LaunchedEffect(controller) { while (true) { controller.tick(); delay(50) } }
                BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
                    val scale = minOf(maxWidth.value / 2000f, maxHeight.value / 1200f)
                    Box(Modifier.size((2000*scale).dp, (1200*scale).dp).align(Alignment.Center)) {
                        SonarScreen(scale, night.value, controller)
                    }
                }
            } }
        }
        ui.waitUntil(8_000) { controller.model.bottomValid && controller.model.presentation?.quality == SonarAnalysisQuality.LOCKED }
    }

    @After fun retire() { if (::controller.isInitialized) ui.runOnUiThread { controller.dispose() } }

    @Test fun canonicalTrackDepthCursorAndDayNightUseTheActualDisplayBinGeometry() {
        ui.runOnIdle {
            assertEquals(12.0, controller.snapshot.depthM!!, 1e-6)
            assertEquals(.55, controller.snapshot.bottom.confidence!!, 1e-6)
            assertEquals(27 / 1023.0, controller.model.metersPerBin!!, 1e-10)
            assertEquals(4.0, controller.model.geometry!!.adcSamplePeriodUs!!, .0)
            assertEquals(SonarTxReference.EMPIRICAL_FALLBACK, controller.model.geometry!!.txReference)
            assertEquals("0.3.5", controller.model.hostPackageVersion)
        }
        ui.onNodeWithContentDescription("Sonar echogram inspection").performTouchInput { click(center) }
        ui.runOnIdle {
            val cursor = requireNotNull(controller.cursor)
            assertEquals(cursor.bin.toDouble(), cursor.amplitude.toDouble(), .0)
            assertEquals(cursor.bin * 27 / 1023.0, cursor.depthM!!, 1e-6)
            night.value = true
        }
        ui.onNodeWithContentDescription("Sonar advanced").performClick()
        ui.onNodeWithContentDescription("Sonar analysis tab").performClick()
        ui.onNodeWithText("0.55 · HEURISTIC").performScrollTo().assertIsDisplayed()
        ui.onNodeWithText("auto / auto_filter").performScrollTo().assertIsDisplayed()
    }

    @Test fun unstableRecommendationHoldsAutoRangeWhileRawSamplesContinue() {
        ui.runOnIdle { controller.autoRange(true) }
        var end = 0.0; var ping = 0L
        ui.runOnIdle { end = controller.snapshot.display.cropEnd; ping = controller.snapshot.pingId!! }
        mode("unstable")
        ui.waitUntil(8_000) { controller.model.presentation?.quality == SonarAnalysisQuality.UNSTABLE }
        ui.runOnIdle {
            assertTrue(controller.running); assertTrue(controller.snapshot.pingId!! > ping)
            assertNull(controller.snapshot.depthM); assertFalse(controller.snapshot.display.freeze)
            assertEquals(SonarRangeMode.AUTO, controller.snapshot.display.rangeMode)
            assertEquals(end, controller.snapshot.display.cropEnd, 1e-6)
            controller.display { it.copy(gainDb = 6.0) }
            assertEquals(end, controller.snapshot.display.cropEnd, 1e-6)
        }
    }

    @Test fun invalidTxSuppressesLiveMetersWhileManualFreezeKeepsItsCapturedGeometry() {
        ui.onNodeWithContentDescription("Sonar freeze").performClick().assertIsSelected()
        mode("unverified")
        ui.waitUntil(8_000) { controller.model.presentation?.quality == SonarAnalysisQuality.UNVERIFIED_TX }
        ui.runOnIdle {
            assertTrue(controller.running); assertNull(controller.model.metersPerBin)
            assertEquals(27 / 1023.0, controller.model.viewMetersPerBin!!, 1e-10)
            assertEquals(511, controller.model.latest!!.sample(511))
        }
        ui.onNodeWithContentDescription("Sonar freeze").performClick().assertIsNotSelected()
        ui.runOnIdle { assertNull(controller.model.viewMetersPerBin); assertNull(controller.snapshot.depthM) }
    }
}
