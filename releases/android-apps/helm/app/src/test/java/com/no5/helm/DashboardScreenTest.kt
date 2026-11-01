package com.no5.helm

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.no5.domain.*
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29],qualifiers="en-rUS-w2000dp-h1200dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DashboardScreenTest {
    @get:Rule val ui=createComposeRule()
    private val now=1_000_000L
    private fun capture(name:String) {
        ui.runOnUiThread {
            val view=(ui as androidx.compose.ui.test.junit4.AndroidComposeTestRule<*, *>).activity.window.decorView
            val bitmap=android.graphics.Bitmap.createBitmap(view.width,view.height,android.graphics.Bitmap.Config.ARGB_8888)
            view.draw(android.graphics.Canvas(bitmap))
            val folder=java.io.File(System.getProperty("no5.renderOutputDir"),"dashboard").apply{mkdirs()}
            java.io.File(folder,name).outputStream().use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
            bitmap.recycle()
        }
    }
    @Test fun dashboardReusesSharedAudioAndSonarOwnersAndKeepsTouchOutsideKnobs() {
        val delegate=MockAudioSource(now={now}).also{it.loadFixture(AudioFixture.DEMO)}
        val writes=java.util.concurrent.CopyOnWriteArrayList<AudioCommand>()
        val source=object:AudioSourcePort by delegate {
            override fun submit(command:AudioCommand):AudioCommandOutcome {writes+=command;return delegate.submit(command)}
        }
        val audio=AudioNativeController(Dispatchers.Unconfined,now={now},automaticTicker=false,automaticVerification=false,source=source)
        val sonarSource=DeterministicMockSonarSource(now={now})
        val sonar=SonarNativeController(sonarSource,now={now})
        sonarSource.loadFixture(SonarFixture.CALIBRATED,SonarAcquisitionRequest(sonarSource.identity.sessionId,100))
        var opened=""
        val night=mutableStateOf(false)
        ui.setContent {MaterialTheme {Box(Modifier.size(2000.dp,1200.dp)) {
            DashboardScreen(1f,night.value,HelmState.unavailable(),WeatherSnapshot.unavailable(),DepthHistorySnapshot.empty(),audio,sonar,now,
                reducedMotion=true,externalSonarScene=true,onOpenPage={opened=it})
        }}}
        ui.onNodeWithTag("dashboard-title").assertTextContains(delegate.state.value.media.title!!)
        ui.onNodeWithTag("dashboard-speed").assertTextEquals("—")
        ui.onNodeWithTag("dashboard-depth").assertTextEquals("—")
        for(theme in listOf(false,true)) {
            ui.runOnIdle{night.value=theme}
            for(tag in listOf("dashboard-previous","dashboard-play-pause","dashboard-next","dashboard-fader","dashboard-fader-down","dashboard-mute","dashboard-fader-up",
                "dashboard-range-down","dashboard-range-up","dashboard-gain-down","dashboard-gain-up","dashboard-freeze")) {
                val bounds=ui.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
                assertTrue("$tag overlaps a physical knob: $bounds",KnobGeometry.safe(bounds.left,bounds.top,bounds.right,bounds.bottom))
                assertTrue("$tag below dashboard panel: $bounds",bounds.bottom<=DashboardGeometry.audio.bottom+.5f)
                assertTrue("$tag touch height: $bounds",bounds.height>=95f)
                val authored=DashboardGeometry.actions.getValue(tag)
                assertEquals("$tag draw/touch x",authored.x,bounds.left,.5f)
                assertEquals("$tag draw/touch y",authored.y,bounds.top,.5f)
                assertEquals("$tag draw/touch width",authored.width,bounds.width,.5f)
                assertEquals("$tag draw/touch height",authored.height,bounds.height,.5f)
            }
            capture(if(theme)"dashboard-night.png"else"dashboard-day.png")
        }
        ui.onNodeWithTag("dashboard-open-audio").performClick()
        ui.runOnIdle{assertEquals("AUDIO",opened)}
        val ping=sonar.snapshot.pingId
        val count=sonar.snapshot.ringCount
        ui.onNodeWithTag("dashboard-freeze").performClick()
        ui.runOnIdle{assertTrue(sonar.snapshot.display.freeze);assertEquals(ping,sonar.snapshot.pingId);assertEquals(count,sonar.snapshot.ringCount)}
        ui.onNodeWithTag("dashboard-play-pause").performClick()
        ui.waitUntil(5000){writes.isNotEmpty()}
        ui.runOnIdle {
            val command=writes.single() as AudioCommand.Transport
            assertEquals(TransportAction.PLAY,command.action)
            audio.dispose();sonar.dispose()
        }
    }

    @Test fun unavailableSourcesHaveNoInventedMeasurementsAndDisableTheirControls() {
        val source=object:AudioSourcePort {
            override val identity=SourceIdentity("missing",SourceMode.PI,"Actual source")
            override val state=MutableSourceFeed(AudioState(sessionId="missing"))
            override val outcomes=MutableSourceFeed<AudioCommandOutcome?>(null)
            override fun available(command:AudioCommand)=false
            override fun submit(command:AudioCommand):AudioCommandOutcome=error("Must not send")
            override fun tick()=Unit
            override fun close()=Unit
        }
        val audio=AudioNativeController(Dispatchers.Unconfined,now={now},automaticTicker=false,source=source)
        val sonar=SonarNativeController(DeterministicMockSonarSource(now={now}),now={now})
        ui.setContent {MaterialTheme {Box(Modifier.size(2000.dp,1200.dp)) {
            DashboardScreen(1f,false,HelmState.unavailable(),WeatherSnapshot.unavailable(),DepthHistorySnapshot.empty(),audio,sonar,now,
                reducedMotion=true,externalSonarScene=true,onOpenPage={})
        }}}
        for(tag in listOf("dashboard-speed","dashboard-depth"))ui.onNodeWithTag(tag).assertTextEquals("—")
        for(tag in listOf("dashboard-play-pause","dashboard-mute","dashboard-fader","dashboard-range-down","dashboard-gain-up","dashboard-freeze"))
            ui.onNodeWithTag(tag).assertIsNotEnabled()
        ui.runOnIdle{audio.dispose();sonar.dispose()}
    }

    @Test fun fullFaderHitSurfaceAcceptsBothVerticalEdgesThroughTheSameAudioController() {
        val delegate=MockAudioSource(now={now}).also{it.loadFixture(AudioFixture.DEMO)}
        val writes=java.util.concurrent.CopyOnWriteArrayList<AudioCommand>()
        val source=object:AudioSourcePort by delegate {
            override fun submit(command:AudioCommand):AudioCommandOutcome {writes+=command;return delegate.submit(command)}
        }
        val audio=AudioNativeController(Dispatchers.Unconfined,now={now},automaticTicker=false,automaticVerification=false,source=source)
        val sonar=SonarNativeController(DeterministicMockSonarSource(now={now}),now={now})
        ui.setContent {MaterialTheme {Box(Modifier.size(2000.dp,1200.dp)) {
            DashboardScreen(1f,false,HelmState.unavailable(),WeatherSnapshot.unavailable(),DepthHistorySnapshot.empty(),audio,sonar,now,
                reducedMotion=true,externalSonarScene=true,onOpenPage={})
        }}}
        ui.onNodeWithTag("dashboard-fader").performTouchInput {click(androidx.compose.ui.geometry.Offset(width*.75f,height-1f))}
        ui.waitUntil(5000){writes.size==1}
        ui.runOnIdle {
            assertTrue(writes.single() is AudioCommand.Fader)
            val pending=delegate.state.value.pending!!
            assertTrue(delegate.acknowledge(delegate.identity.sessionId,pending.requestId,true))
        }
        ui.onNodeWithTag("dashboard-fader").performTouchInput {click(androidx.compose.ui.geometry.Offset(width*.25f,1f))}
        ui.waitUntil(5000){writes.size==2}
        ui.runOnIdle {
            val high=writes[0] as AudioCommand.Fader
            val low=writes[1] as AudioCommand.Fader
            assertEquals(high.channel,low.channel)
            assertTrue(high.valueDb>low.valueDb)
            audio.dispose();sonar.dispose()
        }
    }

    @Test fun fasciaConsumesTheInjectedDetailPresentationAndKeepsItsDetailRoutes() {
        val quality=TelemetryQuality(TelemetryStatus.LIVE,now,"explicit-fixture")
        val raw=HelmState.unavailable().copy(quality=quality,speed=20.0,depth=12.0,portRpm=2345.0,
            telemetryFields=mapOf("speed" to TelemetryValue(20.0,quality),"depth" to TelemetryValue(12.0,quality),
                "portRpm" to TelemetryValue(2345.0,quality)))
        val slow=slowPresentation(raw,now,QualityEvaluator.SHARED,WeatherSnapshot.unavailable(),QualityEvaluator.SHARED,DisplayUnits())
        val reading=SpeedDepthInstrumentGeometry.reading(raw,DisplayUnits(),slow.values.quality,now)
        fun presented(speed:Double,rpm:Double)=helmPanelPresentation(raw,now,QualityEvaluator.SHARED,slow,
            motionPresentation(raw.copy(speed=speed,portRpm=rpm),now,QualityEvaluator.SHARED,DisplayUnits()),reading,
            WeatherPresentation.from(WeatherSnapshot.unavailable(),now))
        val shared=mutableStateOf(presented(6.5,1012.3))
        val clock=mutableStateOf(now)
        val audio=AudioNativeController(Dispatchers.Unconfined,now={now},automaticTicker=false,
            source=MockAudioSource(now={now}))
        val sonar=SonarNativeController(DeterministicMockSonarSource(now={now}),now={now})
        var opened=""
        ui.setContent {MaterialTheme {Box(Modifier.size(2000.dp,1200.dp)) {
            DashboardScreen(1f,false,raw,WeatherSnapshot.unavailable(),DepthHistorySnapshot.empty(),audio,sonar,now,
                active=false,reducedMotion=true,externalSonarScene=true,onOpenPage={opened=it},helmPresentation=shared,historyClock=clock)
        }}}
        try {
            ui.onNodeWithTag("dashboard-speed").assertTextEquals(shared.value.speedText)
            ui.onNodeWithTag("dashboard-depth").assertTextEquals(shared.value.depthText)
            ui.onNodeWithTag("dashboard-port-rpm").assertTextEquals(shared.value.text("rpm-port"))
            ui.onNodeWithTag("dashboard-starboard-rpm").assertTextEquals("—")
            ui.runOnIdle {shared.value=presented(7.5,1110.0)}
            ui.onNodeWithTag("dashboard-speed").assertTextEquals("7.5")
            ui.onNodeWithTag("dashboard-port-rpm").assertTextEquals("1110")
            ui.onNodeWithTag("dashboard-open-helm").performClick()
            ui.runOnIdle {assertEquals("HELM",opened)}
            ui.onNodeWithTag("dashboard-open-sonar").performClick()
            ui.runOnIdle {assertEquals("SONAR",opened)}
        } finally {ui.runOnIdle {audio.dispose();sonar.dispose()}}
    }
    @Test fun sonarDepthValueAndStatusHaveSeparateFixedPlacesWhileSignalKDepthStaysRaw() {
        val h=SonarDepthReadoutRenderTest.Input();val sonar=h.controller
        val audio=AudioNativeController(Dispatchers.Unconfined,now={now},automaticTicker=false,
            source=MockAudioSource(now={now}))
        val raw=HelmState.unavailable().copy(depth=9.0,quality=TelemetryQuality(TelemetryStatus.LIVE,now,"explicit-fixture"),
            telemetryFields=mapOf("depth" to TelemetryValue(9.0,TelemetryQuality(TelemetryStatus.LIVE,now,"explicit-fixture"))))
        h.send(true)
        ui.setContent {MaterialTheme {Box(Modifier.size(2000.dp,1200.dp)) {
            DashboardScreen(1f,false,raw,WeatherSnapshot.unavailable(),DepthHistorySnapshot.empty(),audio,sonar,now,
                active=false,reducedMotion=true,externalSonarScene=true,onOpenPage={})
        }}}
        try {
            val value=sonarPanelPresentation(sonar,DepthUnit.METERS).depthLabel
            val bounds=ui.onNodeWithTag("dashboard-sonar-depth").assertTextEquals(value).fetchSemanticsNode().boundsInRoot
            val status=ui.onNodeWithTag("dashboard-sonar-depth-status").fetchSemanticsNode().boundsInRoot
            assertTrue(bounds.right<=status.left)
            val signalK=ui.onNodeWithTag("dashboard-depth").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Text]
            ui.runOnIdle{h.send(false)}
            ui.onNodeWithTag("dashboard-sonar-depth").assertTextEquals(value)
            assertEquals(bounds,ui.onNodeWithTag("dashboard-sonar-depth").fetchSemanticsNode().boundsInRoot)
            assertEquals(status,ui.onNodeWithTag("dashboard-sonar-depth-status").fetchSemanticsNode().boundsInRoot)
            assertEquals(signalK,ui.onNodeWithTag("dashboard-depth").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Text])
            capture("quiet-depth-held.png")
            ui.runOnIdle{sonar.model.updateContext(SonarEpoch("new-context",2));sonar.refresh()}
            ui.onNodeWithTag("dashboard-sonar-depth").assertTextEquals(com.no5.ui.No5Text.ui("DEPTH —"))
            assertEquals(bounds,ui.onNodeWithTag("dashboard-sonar-depth").fetchSemanticsNode().boundsInRoot)
            assertEquals(status,ui.onNodeWithTag("dashboard-sonar-depth-status").fetchSemanticsNode().boundsInRoot)
        } finally {ui.runOnIdle{audio.dispose();sonar.dispose()}}
    }

}
