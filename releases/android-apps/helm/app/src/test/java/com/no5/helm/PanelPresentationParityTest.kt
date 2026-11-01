package com.no5.helm

import com.no5.domain.*
import com.no5.header.*
import com.no5.ui.No5Text
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.*
import org.junit.Test

/** Pure shared presentation inputs/metadata. Native glyph/pixel tests remain in their existing suite. */
class PanelPresentationParityTest {
    private val now=1_000_000L
    private fun quality(stamp:Long=now,simulated:Boolean=false)=TelemetryQuality(TelemetryStatus.LIVE,stamp,"actual-observation",simulated=simulated)
    private fun raw(speed:Double?=24.0,depth:Double?=7.25)=HelmState.unavailable().copy(
        quality=quality(),speed=speed?:0.0,depth=depth,portRpm=2345.6,starboardRpm=0.0,courseOverGroundDegrees=359.4,
        portCoolantC=82.6,portOilBar=3.46,
        telemetryFields=mapOf("speed" to TelemetryValue(speed,quality()),"depth" to TelemetryValue(depth,quality()),
            "portRpm" to TelemetryValue(2345.6,quality()),"starboardRpm" to TelemetryValue(null,quality()),
            "courseOverGroundDegrees" to TelemetryValue(359.4,quality()),"portCoolantC" to TelemetryValue(82.6,quality()),
            "portOilBar" to TelemetryValue(3.46,quality())))
    private fun project(raw:HelmState,frame:HelmState=raw,at:Long=now,units:DisplayUnits=DisplayUnits()):HelmPanelPresentation {
        val slow=slowPresentation(raw,at,QualityEvaluator.SHARED,WeatherSnapshot.unavailable(),QualityEvaluator.SHARED,units)
        val motion=motionPresentation(frame,at,QualityEvaluator.SHARED,units)
        val reading=SpeedDepthInstrumentGeometry.reading(raw,units,slow.values.quality,at)
        return helmPanelPresentation(raw,at,QualityEvaluator.SHARED,slow,motion,reading,WeatherPresentation.from(WeatherSnapshot.unavailable(),at))
    }
    @Test fun dashboardUsesTheExistingMotionReadingAndSlowTextObjectsWithoutAnotherProjectionClock() {
        val raw=raw()
        val frame=raw.copy(speed=12.345,portRpm=1820.4,courseOverGroundDegrees=4.6)
        val panel=project(raw,frame,units=DisplayUnits(speed=SpeedUnit.KMH,depth=DepthUnit.FEET))
        assertSame(raw,panel.raw)
        assertEquals(frame.portRpm,panel.motion.values.portRpm.toDouble(),.001)
        for(key in listOf("rpm-port","rpm-stbd","deck-cog"))assertEquals(panel.motion.values.text[key],panel.text(key))
        for(key in listOf("secondary-port-coolant","secondary-port-oil"))assertEquals(panel.slow.values.text[key],panel.text(key))
        // RotorNumericField and the dashboard both consume this one pure text contract.
        assertEquals(instrumentNumber(panel.motion.speed),panel.speedText)
        assertEquals(instrumentNumber(panel.instrument.depth),panel.depthText)
        assertEquals("22.9",panel.speedText)
        assertEquals("23.8",panel.depthText)
        assertEquals(panel.text("rpm-port"),panel.headerValues.text["rpm-port"])
        assertEquals(panel.speedText,panel.headerValues.text["hud-sog"])
        assertEquals("—",panel.text("rpm-stbd"))
        val nextMotion=motionPresentation(frame.copy(speed=14.0,portRpm=1700.0),now,QualityEvaluator.SHARED,
            DisplayUnits(speed=SpeedUnit.KMH,depth=DepthUnit.FEET))
        val next=helmPanelPresentation(raw,now,QualityEvaluator.SHARED,panel.slow,nextMotion,panel.instrument,panel.weather)
        assertSame(panel.raw,next.raw);assertSame(panel.slow,next.slow);assertSame(panel.instrument,next.instrument)
        assertSame(panel.weather,next.weather)
        assertSame(nextMotion,next.motion)
        assertEquals("25.9",next.speedText);assertEquals("1700",next.text("rpm-port"))
    }

    @Test fun speedDepthBoundsQualityAndUnitChangesRemainTheInstrumentAuthority() {
        for(units in listOf(DisplayUnits(),DisplayUnits(SpeedUnit.KMH,depth=DepthUnit.FEET),DisplayUnits(SpeedUnit.MPH))) {
            for(speed in listOf<Double?>(0.0,199.9,200.0,200.1,-1.0,Double.NaN,null))for(depth in listOf<Double?>(0.0,12_000.0,12_000.1,-1.0,null)) {
                val panel=project(raw(speed,depth),units=units)
                assertEquals(instrumentNumber(panel.motion.speed),panel.speedText)
                assertEquals(instrumentNumber(panel.instrument.depth),panel.depthText)
                assertEquals(panel.motion.reading().speed,panel.motion.speed)
                assertEquals(SpeedDepthInstrumentGeometry.reading(panel.raw,units,panel.slow.values.quality,now).depth,
                    panel.instrument.depth)
                if(speed==null||speed<0||!speed.isFinite()||speed>200)assertEquals("—",panel.speedText)
                if(depth==null||depth<0||depth>12_000)assertEquals("—",panel.depthText)
                if(panel.motion.aboveScale)assertEquals(No5Text.ui("ABOVE SCALE"),instrumentSpeedFlag(panel.motion.reading()))
            }
        }
        val stale=raw().let{it.copy(telemetryFields=it.telemetryFields+mapOf(
            "speed" to TelemetryValue(4.0,quality(now-6000)),"depth" to TelemetryValue(2.0,quality(now-6000))))}
        val held=project(stale,stale.copy(speed=100.0,depth=100.0))
        assertEquals("4.0",held.speedText);assertEquals("2.0",held.depthText)
        assertEquals(TelemetryStatus.STALE,held.motion.speedStatus)
        assertEquals(TelemetryStatus.STALE,held.instrument.depthStatus)
        assertEquals(No5Text.ui("STALE"),instrumentSpeedFlag(held.motion.reading()))
        assertEquals("4.0*",held.headerValues.text["hud-sog"])
        assertEquals("2.0*",held.headerValues.text["hud-depth"])
    }

    @Test fun weatherIsTheSamePresentationAsTheHelmDeckIncludingZeroStaleAndMissingPosition() {
        val weather=WeatherSnapshot(status=TelemetryStatus.LIVE,
            provider=WeatherProvider("fixture","Explicit weather fixture",WeatherProviderKind.ONBOARD),
            delivery=WeatherDelivery("fixture-gateway","fixture-transport"),
            capabilities=WeatherCapabilities(airTemperature=true,wind=true),
            values=WeatherValues(airTemperatureC=0.0,windSpeedKn=0.0),
            timestampEpochMs=now-6000,observedAtEpochMs=now-7000,issue=WeatherIssue.BOAT_POSITION_UNAVAILABLE)
        val raw=raw()
        val detail=slowPresentation(raw,now,QualityEvaluator.SHARED,weather,QualityEvaluator.SHARED,DisplayUnits())
        val view=WeatherPresentation.from(weather,now,QualityEvaluator.SHARED)
        val panel=helmPanelPresentation(raw,now,QualityEvaluator.SHARED,detail,
            motionPresentation(raw,now,QualityEvaluator.SHARED,DisplayUnits()),
            SpeedDepthInstrumentGeometry.reading(raw,DisplayUnits(),detail.values.quality,now),view)
        assertSame(view,panel.weather)
        assertEquals(panel.text("deck-airTemp"),panel.weather.airTemperature)
        assertEquals(panel.text("deck-wind"),panel.weather.windSpeed)
        assertEquals(panel.text("deck-evidence-environment"),panel.weather.evidenceLabel)
        assertEquals("0.0",panel.weather.airTemperature);assertEquals("0.0",panel.weather.windSpeed)
        assertEquals(TelemetryStatus.STALE,panel.weather.status)
        assertEquals("NO POSITION · STALE CACHE",panel.weather.evidenceLabel)
        assertEquals("—",panel.weather.observedLocation)
        assertEquals(java.time.Instant.ofEpochMilli(now-7000).atOffset(java.time.ZoneOffset.UTC).toString(),
            panel.weather.observationTime)
    }

    @Test fun headerAndBodyKeepOriginalFieldQualityTimeAndRealZeroInsteadOfEnvelopeFreshness() {
        val raw=raw(0.0,7.25).let { it.copy(telemetryFields=it.telemetryFields+
            ("portRpm" to TelemetryValue(0.0,quality(now-6000,true)))) }
        val panel=project(raw)
        val header=HelmHeaderSnapshotBuilder.build(HeaderPublication(HeaderApps.HELM,"fixture",1,1,"fixture-source",1,1000,2500),
            HeaderPage.DASHBOARD,raw,now,HeaderTheme.DAY)
        assertEquals("0.0",panel.speedText)
        assertEquals("0*",panel.text("rpm-port"))
        assertEquals("—",panel.text("rpm-stbd"))
        assertEquals(HeaderQuality.STALE,header.extras.portRpm!!.quality)
        assertEquals(now-6000,header.extras.portRpm!!.sourceObservedAtEpochMs)
        assertEquals(HeaderProvenance.SIMULATED,header.extras.portRpm!!.provenance)
        assertEquals("SIMULATED · STALE",panel.fieldEvidence("portRpm"))
        assertEquals(raw.fieldObservation("portRpm",null).quality.timestampEpochMs,
            dashboardField(panel.raw,"portRpm",panel.raw.portRpm,panel.nowEpochMs,panel.policy).observedAtEpochMs)
        assertEquals(0.0,header.extras.sog!!.value!!,0.0)
        assertNull(header.extras.starboardRpm!!.value)
        val expired=project(raw,at=now+30_001)
        assertEquals("—",expired.speedText);assertEquals("—",expired.text("rpm-port"))
    }

    @Test fun historyMatchesTheSameSixtySecondCacheGeometryAndScrollAtDifferentCacheAnchors() {
        val points=listOf(DepthHistoryPoint(10_000,100.0,true),DepthHistoryPoint(60_000,2.0,true),
            DepthHistoryPoint(80_000,2.2,false),DepthHistoryPoint(90_000,2.1,true))
        val history=DepthHistorySnapshot(points,0,100_000,120_000)
        val detail=InstrumentHistoryTraceCache()
        instrumentHistoryFrame(detail,history,100_000)
        val a=instrumentHistoryFrame(detail,history,101_000)
        val b=instrumentHistoryFrame(InstrumentHistoryTraceCache(),history,101_000)
        assertEquals(3,a.trace.points.size) // The old120s-only point is not part of either panel.
        assertEquals(a.trace.minimumMeters,b.trace.minimumMeters,0.0)
        assertEquals(a.trace.maximumMeters,b.trace.maximumMeters,0.0)
        assertTrue(a.trace.maximumMeters-a.trace.minimumMeters>=2.0)
        a.trace.points.zip(b.trace.points).forEach{(left,right)->
            assertEquals(left.x-a.scrollFraction,right.x-b.scrollFraction,.000001f)
            assertEquals(left.y,right.y,0f);assertEquals(left.breakBefore,right.breakBefore)
        }
    }

    @Test fun audioSourceSimulationDoesNotHideStaleOrLostAndUsesTheSelectedFader() {
        var stamp=now
        val initial=AudioModel.fixture(AudioFixture.DEMO).let { original ->original.copy(mode=OperatingMode.LIVE,sessionId="audio-fixture",
            source=original.source.copy(quality=quality(simulated=true)),dsp=original.dsp.copy(quality=quality(simulated=true))) }
        val source=object:AudioSourcePort {
            override val identity=SourceIdentity("audio-fixture",SourceMode.PI,"Actual adapter")
            override val state=MutableSourceFeed(initial)
            override val outcomes=MutableSourceFeed<AudioCommandOutcome?>(null)
            override fun available(command:AudioCommand)=AudioModel.available(state.value.withAudioFreshness(stamp),command)
            override fun submit(command:AudioCommand):AudioCommandOutcome=error("Read-only fixture")
            override fun tick()=Unit
            override fun close()=Unit
        }
        val controller=AudioNativeController(Dispatchers.Unconfined,now={stamp},automaticTicker=false,source=source)
        val selected=controller.faderControls.first{it.gatewayId!=AudioChannel.MASTER}
        controller.selectKnobFader(selected)
        assertEquals(selected.gatewayId,controller.selectedFader()!!.gatewayId)
        assertEquals(audioDb(controller.level(selected)),audioDb(controller.selectedFader()?.let(controller::level)))
        for((age,status)in listOf(0L to TelemetryStatus.LIVE,3000L to TelemetryStatus.STALE,31_000L to TelemetryStatus.LOST)) {
            stamp=now+age;controller.tickOnce()
            assertEquals(No5Text.ui("SIMULATED")+" · "+No5Text.ui(status.name),audioPanelSourceLabel(controller))
            if(status==TelemetryStatus.LOST)assertFalse(controller.canPresent(AudioCommand.Transport(TransportAction.PLAY)))
        }
        controller.dispose()
    }

    @Test fun sonarFrozenPreviewKeepsItsOwnPingTimeCalibrationAndAbsoluteRange() {
        var stamp=now
        val source=DeterministicMockSonarSource(now={stamp})
        val controller=SonarNativeController(source,now={stamp})
        source.loadFixture(SonarFixture.CALIBRATED,SonarAcquisitionRequest(source.identity.sessionId,100))
        controller.crop(256.0,767.0);controller.freeze()
        val held=sonarPanelPresentation(controller,DepthUnit.METERS)
        assertTrue(held.frozen)
        val bounds=controller.bounds();val meters=controller.model.viewMetersPerBin!!
        assertEquals("${sonarNumber(bounds.start*meters)}–${sonarNumber(bounds.end*meters)} m",held.rangeLabel)
        assertEquals(controller.model.viewRing.pingIdAt(0),held.viewPingId)
        assertEquals(controller.model.viewRing.timestampAt(0),held.viewTimestampEpochMs)
        repeat(5){stamp+=100;controller.tick()}
        assertTrue(controller.snapshot.pingId!!>held.viewPingId!!)
        val later=sonarPanelPresentation(controller,DepthUnit.METERS)
        assertEquals(held.viewPingId,later.viewPingId);assertEquals(held.viewTimestampEpochMs,later.viewTimestampEpochMs)
        assertEquals(held.rangeLabel,later.rangeLabel)
        assertEquals(held.depthLabel,later.depthLabel) // Frozen evidence cannot follow a newer live depth.
        controller.dispose()
    }

    @Test fun unverifiedSonarKeepsBinsAndDoesNotInventDepthOrAZeroBasedCrop() {
        val source=DeterministicMockSonarSource(now={now})
        val controller=SonarNativeController(source,now={now})
        controller.start();controller.model.setAnalysis(SonarGeometry(null,SonarTxReference.UNVERIFIED),SonarBottom(),null,false)
        controller.crop(256.0,767.0);controller.refresh()
        val labels=sonarPanelPresentation(controller,DepthUnit.FEET)
        assertEquals("256–767 BINS",labels.rangeLabel)
        assertEquals(No5Text.ui("DEPTH —"),labels.depthLabel)
        assertEquals(No5Text.ui("TX REFERENCE UNVERIFIED"),labels.interpretationLabel)
        assertEquals(No5Text.ui("SIMULATED"),labels.sourceLabel)
        controller.dispose()
    }
}
