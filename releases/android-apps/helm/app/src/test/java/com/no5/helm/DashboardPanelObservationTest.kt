package com.no5.helm

import androidx.compose.runtime.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.no5.domain.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29])
class DashboardPanelObservationTest {
    @get:Rule val ui=createComposeRule()
    @Test fun rawAndClockChangesRetainObserverAndPublishTheirActualEvidence() {
        val raw=mutableStateOf(HelmState.unavailable())
        val now=mutableStateOf(1_000_000L)
        val units=DisplayUnits()
        val motion=mutableStateOf(motionPresentation(raw.value,now.value,QualityEvaluator.SHARED,units))
        var current:State<HelmPanelPresentation>?=null
        ui.setContent {
            val state=raw.value
            val slow=slowPresentation(state,now.value,QualityEvaluator.SHARED,WeatherSnapshot.unavailable(),QualityEvaluator.SHARED,units)
            val reading=SpeedDepthInstrumentGeometry.reading(state,units,slow.values.quality,now.value)
            val panel=rememberHelmPanelPresentation(state,now.value,QualityEvaluator.SHARED,slow,motion,reading,
                WeatherPresentation.from(WeatherSnapshot.unavailable(),now.value))
            SideEffect { current=panel }
        }
        ui.waitForIdle()
        val first=requireNotNull(current)
        ui.runOnIdle {
            val quality=TelemetryQuality(TelemetryStatus.LIVE,now.value,"actual-vessel")
            raw.value=raw.value.copy(speed=7.5,quality=quality,
                telemetryFields=mapOf("speed" to TelemetryValue(7.5,quality)))
            motion.value=motionPresentation(raw.value,now.value,QualityEvaluator.SHARED,units)
        }
        ui.waitForIdle()
        ui.runOnIdle {
            assertSame(first,current)
            assertSame(raw.value,first.value.raw)
            assertEquals(now.value,first.value.raw.telemetryFields.getValue("speed").quality.timestampEpochMs)
            assertEquals(7.5,first.value.motion.speed!!,0.0)
            now.value+=60_000
        }
        ui.waitForIdle()
        ui.runOnIdle {
            assertSame(first,current)
            assertEquals(now.value,first.value.nowEpochMs)
            assertEquals(1_000_000L,first.value.raw.telemetryFields.getValue("speed").quality.timestampEpochMs)
        }
    }
}
