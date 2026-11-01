package com.no5.helm

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.no5.gateway.*
import com.no5.helm.diagnostics.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Production panel, fixture metadata only; host Skia is not device touch acceptance. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29,35], qualifiers="en-rUS-w1312dp-h946dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeGatewayConnectionRenderTest {
    @get:Rule val ui = createComposeRule()
    @Test fun dayNightHaveIdenticalGeometryAndCompleteActionableServiceResults() {
        val night = mutableStateOf(false)
        val home = mutableStateOf<HomeGatewayStatus?>(HomeGatewayStatus(true, GatewayConfiguration(
            GatewayOrigin("no5-marineos-lab.local",9443),"192.168.68.89",GatewayAddressSource.MANUAL, checkedAt = 1000),
            keyPresent = true, caPresent = true))
        val entries = gatewayTestServices.mapIndexed { index, service -> GatewayDiagnosticEntry(1000,1,service,
            GatewayDiagnosticPhase.TEST_RESULT, if(index == 0) GatewayDiagnosticOutcome.FAILURE else GatewayDiagnosticOutcome.SUCCESS,
            40L + index, if(index == 0) GatewayDiagnosticError.HTTP_OTHER else GatewayDiagnosticError.NONE,
            if(index == 0) 503 else if(service == GatewayDiagnosticService.SONAR_WS) 101 else 200) }
        var view: android.view.View? = null
        var tests = 0
        var homeOpens = 0
        var backs = 0
        ui.setContent {
            view = LocalView.current
            Box(Modifier.size(1312.dp,946.dp)) {
                HomeGatewayConnectionContent(1f, night.value, home.value, "HELM · UNAVAILABLE | WEATHER · LIVE", false,
                    GatewayDiagnosticSnapshot(entries,0,entries.last(),entries.first()),
                    { homeOpens++ }, { backs++ }, { tests++ }, {}, {}, {})
            }
        }
        val output = File(System.getProperty("no5.renderOutputDir"),"gateway-design").also { it.mkdirs() }
        val dayBounds = ui.onNodeWithTag("gateway-test").fetchSemanticsNode().boundsInRoot
        for(isNight in listOf(false,true)) {
            ui.runOnIdle { night.value = isNight }
            ui.waitForIdle()
            ui.onNodeWithText("Gateway antwortet · Dienst derzeit nicht verfügbar").assertIsDisplayed()
            ui.onNodeWithText("HTTP 503 · 40 ms · HTTP_OTHER").assertIsDisplayed()
            ui.onNodeWithTag("gateway-test").assertIsEnabled().performClick()
            assertEquals(dayBounds,ui.onNodeWithTag("gateway-test").fetchSemanticsNode().boundsInRoot)
            ui.runOnIdle {
                val root = checkNotNull(view)
                val bitmap = Bitmap.createBitmap(root.width,root.height,Bitmap.Config.ARGB_8888)
                root.draw(Canvas(bitmap))
                File(output,"gateway-${if(isNight) "night" else "day"}-api${android.os.Build.VERSION.SDK_INT}.png").outputStream().use {
                    bitmap.compress(Bitmap.CompressFormat.PNG,100,it)
                }
                // The outer panel inherits SOUND SETUP's background, never the old beige.
                assertEquals(if(isNight) 0xff000000.toInt() else 0xfff6f9fa.toInt(),bitmap.getPixel(25,110))
                bitmap.recycle()
            }
            ui.onNodeWithText("OK · Handshake bestätigt").assertIsDisplayed()
        }
        ui.onNodeWithTag("gateway-log-toggle").performScrollTo().performClick()
        ui.onNodeWithTag("gateway-log-export-csv").performScrollTo().assertIsEnabled()
        ui.onNodeWithTag("gateway-home").performScrollTo().performClick()
        ui.onNodeWithTag("gateway-back").performClick()
        ui.runOnIdle { home.value = home.value!!.copy(caPresent = false) }
        ui.onNodeWithTag("gateway-test").assertIsNotEnabled()
        ui.onNodeWithTag("gateway-test-blocker").assertExists()
        assertEquals(2,tests)
        assertEquals(1,homeOpens)
        assertEquals(1,backs)
    }
}
