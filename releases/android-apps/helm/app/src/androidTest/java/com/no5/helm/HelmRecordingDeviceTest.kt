package com.no5.helm

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createComposeRule
import com.no5.domain.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry

@RunWith(AndroidJUnit4::class)
class HelmRecordingDeviceTest {
    @get:Rule val ui=createComposeRule()
    @Test fun motionFramesDoNotRecordStaticSlowOrUnchangedSpeedBand() {
        val quality=TelemetryQuality(TelemetryStatus.LIVE,100000,"fixture",demo=true)
        val raw=HelmState(portRpm=1000.0,speed=10.0,heading=10.0,quality=quality)
        val units=DisplayUnits()
        val motion=mutableStateOf(motionPresentation(raw,100000,QualityEvaluator.SHARED,units))
        val slow=slowPresentation(raw,100000,QualityEvaluator.SHARED,WeatherSnapshot.unavailable(),QualityEvaluator.SHARED,units)
        val scene=HelmScene(InstrumentationRegistry.getInstrumentation().targetContext,false)
        ui.mainClock.autoAdvance=false
        var view:android.view.View?=null
        val target=android.graphics.RenderNode("helm-isolation-test").apply{setPosition(0,0,2000,1200)}
        fun record() { ui.runOnIdle { val c=target.beginRecording();view!!.draw(c);target.endRecording() } }
        ui.setContent {
            view=LocalView.current
            Box(Modifier.size(2000.dp,1200.dp)) {
                HelmStaticLayer(scene,units);HelmSlowLayer(scene,slow)
                HelmSpeedBandLayer(scene,motion);HelmMotionLayer(scene,motion);HelmStaticOverlay(scene,units)
            }
        }
        ui.mainClock.advanceTimeBy(64);ui.waitForIdle();record()
        val before=HelmRecordCounters.snapshot();val band=HelmRecordCounters.band
        repeat(12) { index ->
            ui.runOnIdle { motion.value=motionPresentation(raw.copy(portRpm=1100.0+index*50,heading=20.0+index),100000,QualityEvaluator.SHARED,units) }
            ui.mainClock.advanceTimeBy(32);ui.waitForIdle();record()
        }
        val after=HelmRecordCounters.snapshot()
        assertTrue("Motion must really record; no vacuous static test",after[2]>before[2])
        assertEquals("static re-records",before[0],after[0]);assertEquals("slow re-records",before[1],after[1])
        assertEquals("unchanged speed bin",band,HelmRecordCounters.band)

    }
}
