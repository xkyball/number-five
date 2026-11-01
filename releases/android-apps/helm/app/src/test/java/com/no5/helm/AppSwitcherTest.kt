package com.no5.helm

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import android.graphics.Bitmap
import android.graphics.Canvas
import java.io.File
import org.robolectric.shadows.ShadowDialog
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 35], qualifiers = "en-rUS-w1000dp-h464dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AppSwitcherTest {
    @get:Rule val ui = createComposeRule()
    @Test fun exportDayAndNightDialog() {
        val night = mutableStateOf(false)
        ui.setContent {
            MaterialTheme(colorScheme = if (night.value) darkColorScheme(surface=Color.Black, onSurface=Color(0xFFD40000)) else lightColorScheme(surface=Color(0xFFF6F9FA), onSurface=Color(0xFF071022))) {
                AppSwitcher(night.value) { }
            }
        }
        for (value in listOf(false, true)) {
            ui.runOnIdle { night.value = value }
            ui.waitForIdle()
            ui.runOnIdle {
                val view = ShadowDialog.getLatestDialog().window!!.decorView
                assertTrue(view.width > 0 && view.height > 0)
                val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                view.draw(Canvas(bitmap))
                val directory = File(System.getProperty("no5.renderOutputDir"), "launcher").apply { mkdirs() }
                File(directory, "switcher-${if(value) "night" else "day"}-${android.os.Build.VERSION.SDK_INT}.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
        }
    }
    @Test fun missingAppIsReportedAndMaintenanceCanReturnWithoutNavigation() {
        var closed = false
        ui.setContent { MaterialTheme(colorScheme = darkColorScheme()) { AppSwitcher(true) { closed = true } } }
        ui.onNodeWithText("No5 Home · nicht installiert").performClick()
        ui.onNodeWithText("App nicht installiert oder keine Startaktivität verfügbar.").performScrollTo().assertIsDisplayed()
        ui.runOnIdle { assertFalse(closed) }
        ui.onNodeWithText("Wartung").performScrollTo().performClick()
        ui.onNodeWithText("Android-Einstellungen").assertIsDisplayed()
        ui.onNodeWithText("Zurück zum App-Wechsler").performScrollTo().performClick()
        ui.onNodeWithText("HELM · Zurück").performScrollTo().performClick()
        ui.runOnIdle { assertTrue(closed) }
    }
}
