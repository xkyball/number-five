package com.no5.helm

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
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
class HelmThemeInvalidationTest {
    @get:Rule val ui=createComposeRule()
    @Test fun upstreamPacketsDoNotReplacePaletteOrRecomposeUnchangedTextButThemeStillChanges() {
        val packet=mutableIntStateOf(0)
        val night=mutableStateOf(false)
        val renders=AtomicInteger()
        val observed=AtomicReference<Color>()
        val consumedPacket=AtomicInteger()
        ui.setContent {
            val latest=packet.intValue
            SideEffect { consumedPacket.set(latest) }
            val body:@Composable ()->Unit=remember { {
                val primary=MaterialTheme.colorScheme.primary
                SideEffect { renders.incrementAndGet(); observed.set(primary) }
            } }
            MaterialTheme(colorScheme=rememberHelmColorScheme(night.value),content=body)
        }
        ui.waitForIdle();val before=renders.get()
        ui.runOnIdle { packet.intValue=1 };ui.waitForIdle()
        ui.runOnIdle {
            assertEquals(1,consumedPacket.get())
            assertEquals("The real window palette must not invalidate every text on a source update",before,renders.get())
            assertEquals(Color(0xFF005DAF),observed.get())
            night.value=true
        }
        ui.waitForIdle();val dark=renders.get()
        ui.runOnIdle {
            assertTrue(dark>before);assertEquals(Color(0xFFDC160C),observed.get())
            packet.intValue=2
        }
        ui.waitForIdle()
        ui.runOnIdle { assertEquals(dark,renders.get());night.value=false };ui.waitForIdle()
        ui.runOnIdle { assertEquals(Color(0xFF005DAF),observed.get());assertTrue(renders.get()>dark) }
    }
}
