package com.no5.helm

import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.no5.domain.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Native semantic and real-pointer acceptance. This file is not evidence of execution. */
class NativeSonarTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private lateinit var controller:SonarNativeController
    private lateinit var fixtureSource:DeterministicMockSonarSource
    private var fixtureRequest = 0L
    private val depthUnit = mutableStateOf(DepthUnit.METERS)
    private fun loadFixture(fixture:SonarFixture) {
        fixtureSource.loadFixture(fixture, SonarAcquisitionRequest(fixtureSource.identity.sessionId, fixtureRequest++))
    }
    private fun acquisition(action:String) {
        ui.onNodeWithContentDescription("Sonar advanced").performClick()
        ui.onNodeWithContentDescription("Sonar acquisition tab").performClick()
        ui.onNodeWithContentDescription(action).performScrollTo().performClick()
        ui.onNodeWithContentDescription("Close advanced sonar").performClick()
    }
    @Before fun render(){
        fixtureSource=DeterministicMockSonarSource(now={1_000_000L})
        controller=SonarNativeController(fixtureSource){1_000_000L}
        ui.runOnUiThread{ui.activity.setContent{MaterialTheme{BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)){
            val scale=minOf(maxWidth.value/2000f,maxHeight.value/1200f)
            Box(Modifier.size((2000*scale).dp,(1200*scale).dp).align(Alignment.Center)){SonarScreen(scale,false,controller,depthUnit.value)}
        }}}}
    }
    @Test fun acquisitionCapabilityGateHasNoFixtureControls(){
        ui.onNodeWithContentDescription("Sonar freeze").assertIsNotEnabled()
        acquisition("Start sonar acquisition")
        ui.runOnIdle{assertEquals(800,controller.snapshot.ringCount);assertTrue(controller.snapshot.demo)}
        acquisition("Stop sonar acquisition")
        ui.runOnIdle{assertFalse(controller.running)}
        ui.onNodeWithContentDescription("Reset sonar DEMO").assertDoesNotExist()
        ui.onNodeWithContentDescription("Sonar advanced").performClick()
        ui.onNodeWithContentDescription("Sonar acquisition tab").performClick()
        ui.onNodeWithContentDescription("Load calibrated sonar fixture").assertDoesNotExist()
    }
    @Test fun freezeKeepsAcquiringAndResumesLatest(){
        acquisition("Start sonar acquisition")
        ui.onNodeWithContentDescription("Sonar freeze").performClick().assertIsSelected()
        ui.runOnIdle{repeat(4){controller.tick()};assertEquals(804,controller.snapshot.ringCount);assertEquals(800,controller.snapshot.frozenCount)}
        ui.onNodeWithContentDescription("Sonar freeze").performClick().assertIsNotSelected()
        ui.runOnIdle{assertEquals(804,controller.model.viewRing.count)}
    }
    @Test fun drawerTabsAndCapabilityGatesAreReachable(){
        ui.onNodeWithContentDescription("Sonar advanced").performClick()
        ui.onNodeWithContentDescription("Sonar acquisition tab").performClick()
        ui.runOnIdle{loadFixture(SonarFixture.CALIBRATED)}
        ui.onNodeWithContentDescription("Sonar analysis tab").performClick()
        ui.onNodeWithContentDescription("Sonar current bottom overlay").assertIsEnabled().performClick().assertIsSelected()
        ui.onNodeWithContentDescription("Sonar target overlays unavailable").assertIsNotEnabled()
        ui.onNodeWithContentDescription("Sonar recording tab").performClick()
        ui.onNodeWithContentDescription("Sonar recording unavailable").assertIsNotEnabled()
        ui.onNodeWithContentDescription("Close advanced sonar").performClick()
        ui.onNodeWithContentDescription("Sonar advanced").performClick()
        ui.onNodeWithContentDescription("Sonar display tab").assertIsSelected()
    }
    @Test fun sliderUsesNativeProgressAndRealPointerAtScaledViewport(){
        ui.onNodeWithContentDescription("Sonar advanced").performClick()
        val gain=ui.onNodeWithContentDescription("Sonar DISPLAY GAIN").performScrollTo()
        gain.performSemanticsAction(SemanticsActions.SetProgress){it(12f)}
        ui.runOnIdle{assertEquals(12.0,controller.snapshot.display.gainDb,.1)}
        gain.performTouchInput{swipeLeft()}
        ui.runOnIdle{assertTrue(controller.snapshot.display.gainDb<0)}
    }
    @Test fun plotPointerAndZoomInspectActualUnsignedSamples(){
        acquisition("Start sonar acquisition")
        ui.onNodeWithContentDescription("Sonar echogram inspection").performTouchInput{click(center)}
        ui.runOnIdle{assertNotNull(controller.cursor);assertTrue(controller.cursor!!.amplitude in 0..65535)}
        ui.onNodeWithContentDescription("Sonar zoom").performClick().assertIsSelected()
        ui.onNodeWithContentDescription("Sonar A-Scope").performClick().assertIsNotSelected()
    }
    @Test fun stoppedDropoutAndCalibrationFactsDoNotInventDepth(){
        ui.onNodeWithContentDescription("Sonar advanced").performClick()
        ui.onNodeWithContentDescription("Sonar acquisition tab").performClick()
        ui.runOnIdle{loadFixture(SonarFixture.CALIBRATED)}
        ui.runOnIdle{assertNotNull(controller.snapshot.depthM)}
        ui.runOnIdle{loadFixture(SonarFixture.DROPOUT)}
        ui.runOnIdle{assertNull(controller.snapshot.depthM);assertEquals(800,controller.snapshot.ringCount)}
        ui.onNodeWithContentDescription("Sonar diagnostics tab").performClick()
        ui.onNodeWithText("OFFLINE").assertIsDisplayed()
    }
    @Test fun openDrawerRemovesCoveredSonarActionsFromAccessibility() {
        acquisition("Start sonar acquisition")
        ui.onNodeWithContentDescription("Sonar advanced").performClick()
        ui.onNodeWithContentDescription("Sonar echogram inspection").assertDoesNotExist()
        ui.onNodeWithContentDescription("Sonar freeze").assertDoesNotExist()
        ui.onNodeWithContentDescription("Start sonar acquisition").assertDoesNotExist()
        ui.onNodeWithContentDescription("Close advanced sonar").performClick()
        ui.onNodeWithContentDescription("Sonar echogram inspection").assertExists()
        ui.onNodeWithContentDescription("Sonar freeze").assertIsEnabled()
        ui.runOnIdle{assertTrue(controller.running);assertFalse(controller.snapshot.display.freeze)}
    }

    @Test fun depthPreferenceChangesDisplayedRangeWithoutResettingSamples() {
        ui.runOnIdle { loadFixture(SonarFixture.CALIBRATED) }
        ui.onNodeWithContentDescription("Sonar advanced").performClick()
        ui.onNodeWithContentDescription("Sonar manual range 5.0 m").performScrollTo().assertExists()
        ui.runOnIdle { depthUnit.value = DepthUnit.FEET }
        ui.onNodeWithContentDescription("Sonar manual range 16.4 ft").performScrollTo().assertExists()
        ui.onNodeWithContentDescription("Sonar manual range 5.0 m").assertDoesNotExist()
        ui.runOnIdle {
            assertEquals(800, controller.snapshot.ringCount)
            assertEquals(.015, controller.model.metersPerBin!!, .000001)
            assertTrue(controller.running)
        }
    }

    @Test fun quickSwitchRetainsFrozenEchogramAndAcquisition() {
        ui.runOnIdle{loadFixture(SonarFixture.CALIBRATED);controller.freeze()}
        val held=controller.model.viewRing.atAge(0)!!.pingId
        val display=controller.snapshot.display
        ui.onNodeWithContentDescription("Sonar Audio quick switch").performTouchInput{click(center)}
        ui.runOnIdle{repeat(4){controller.tick()};assertTrue(controller.running);assertEquals(SonarKnobMode.AUDIO,controller.knobMode);assertEquals(held,controller.model.viewRing.atAge(0)!!.pingId);assertEquals(display,controller.snapshot.display)}
        ui.onNodeWithContentDescription("Sonar Audio quick switch").performTouchInput{click(center)}
        ui.runOnIdle{assertEquals(SonarKnobMode.SONAR,controller.knobMode);assertEquals(display,controller.snapshot.display)}
    }

}
