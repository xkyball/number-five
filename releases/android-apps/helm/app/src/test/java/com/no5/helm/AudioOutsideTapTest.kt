package com.no5.helm

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h300dp-land-mdpi")
class AudioOutsideTapTest {
    @get:Rule val ui = createComposeRule()

    @Test fun insideControlsWorkAndOnlySingleOutsideTapCloses() {
        var closes = 0
        var insideClicks = 0
        ui.setContent {
            Box(Modifier.size(400.dp, 300.dp).outsidePanelTap(Rect(50f, 50f, 350f, 250f)) { closes++ }
                .testTag("audio-outside-scrim")) {
                Box(Modifier.offset(50.dp, 50.dp).size(300.dp, 200.dp).clickable { insideClicks++ }
                    .testTag("audio-dialog-control"))
            }
        }
        ui.onNodeWithTag("audio-dialog-control").performTouchInput { click(center) }
        ui.runOnIdle { assertEquals(1, insideClicks); assertEquals(0, closes) }
        ui.onNodeWithTag("audio-outside-scrim").performTouchInput { swipe(Offset(10f, 10f), Offset(390f, 10f)) }
        ui.onNodeWithTag("audio-outside-scrim").performTouchInput {
            down(0, Offset(10f, 10f)); down(1, Offset(30f, 10f)); up(0); up(1)
        }
        ui.runOnIdle { assertEquals(0, closes) }
        ui.onNodeWithTag("audio-outside-scrim").performTouchInput { click(Offset(10f, 10f)) }
        ui.runOnIdle { assertEquals(1, closes); assertEquals(1, insideClicks) }
    }
}
