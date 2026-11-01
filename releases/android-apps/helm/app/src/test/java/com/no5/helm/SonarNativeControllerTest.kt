package com.no5.helm

import com.no5.domain.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.roundToInt

class SonarNativeControllerTest {
    @Test fun reliefCameraAndDisplayDoNotChangeAcquisitionOrFrozenSourceData() {
        var now=1_000_000L;val fixture=SonarFixtureHarness{now};val c=fixture.controller
        assertFalse(c.reliefEnabled);assertTrue(c.reliefAligned);c.start();val raw=c.model.latest!!.samples
        c.relief(true);c.reliefCamera(100f,100f,100f)
        assertTrue(c.reliefEnabled);assertFalse(c.reliefAligned);assertTrue(c.running)
        assertTrue(c.reliefYaw in -kotlin.math.PI.toFloat()..kotlin.math.PI.toFloat())
        assertEquals(1.35f,c.reliefPitch,0f);assertEquals(1.35f,c.reliefZoom,0f)
        assertArrayEquals(raw,c.model.latest!!.samples)
        c.freeze();val held=c.model.viewRing
        now+=100;c.tick();assertSame(held,c.model.viewRing)
        c.reliefCamera(Float.NaN,0f);assertTrue(c.reliefYaw.isFinite())
        c.alignReliefRange();assertTrue(c.reliefAligned);assertSame(held,c.model.viewRing)
        assertTrue(c.running);assertArrayEquals(raw,held.atAge(0)!!.samples)
        c.resetReliefCamera();assertFalse(c.reliefAligned);assertEquals(-.25f,c.reliefYaw,0f)
        c.relief(false);assertTrue(c.running);assertTrue(c.snapshot.display.freeze)
        c.dispose()
    }

    @Test fun sonarKnobModeIsRestoredAndItsTouchToggleUsesTheOwnedModeControlOnly() {
        val fixture=SonarFixtureHarness{1_000_000L};val c=fixture.controller;c.start()
        assertEquals(SonarKnobMode.SONAR,c.knobMode);val raw=c.model.latest!!.samples.copyOf();val display=c.snapshot.display
        c.toggleKnobMode();assertEquals(SonarKnobMode.AUDIO,c.knobMode);assertEquals(display,c.snapshot.display)
        val saved=c.exportPresentationState();c.toggleKnobMode();assertEquals(SonarKnobMode.SONAR,c.knobMode)
        c.restorePresentationState(saved);assertEquals(SonarKnobMode.AUDIO,c.knobMode);assertArrayEquals(raw,c.model.latest!!.samples)
        c.toggleKnobMode();assertEquals(SonarKnobMode.SONAR,c.knobMode);assertTrue(c.running);c.dispose()
    }

    @Test fun m0AmplitudeTransferHasEquivalentDbAndReciprocalGammaWithoutNormalization() {
        val model=SonarModel{1_000_000L}
        model.setDisplay(model.display.copy(gainDb=20*kotlin.math.log10(1.4),gamma=1/.72,threshold=0.0))
        for((raw,expected) in listOf(0 to 0.0,32768 to .7735267912,46810 to .9999890135,46811 to 1.0))
            assertEquals("M0 reference transfer for $raw",expected,model.displayAmplitude(raw),1e-9)
        assertEquals(15.0146627566,512.0*30/1023,1e-9)
        assertEquals(258,SonarFrame.fromLittleEndian(ByteArray(2048).apply{this[0]=2;this[1]=1},1,1000).sample(0))
    }

    @Test fun sourceStartsUnavailableWithoutInventedDepth() {
        val fixture=SonarFixtureHarness{1_000_000L};val c=fixture.controller
        assertFalse(c.snapshot.connected);assertNull(c.snapshot.depthM);assertEquals(0,c.snapshot.ringCount)
        assertEquals("SONAR SOURCE UNAVAILABLE",c.statusText)
    }
    @Test fun startStopResumeAndResetAreSeparateFromDisplayFreeze() {
        var now=1_000_000L;val fixture=SonarFixtureHarness{now};val c=fixture.controller
        c.start();assertTrue(c.running);assertTrue(c.snapshot.demo);assertEquals(800,c.snapshot.ringCount)
        c.freeze();val held=c.model.viewRing.atAge(0)!!.pingId
        repeat(20){now+=100;c.tick()}
        assertEquals(820,c.snapshot.ringCount);assertEquals(800,c.snapshot.frozenCount)
        assertEquals(held,c.model.viewRing.atAge(0)!!.pingId);assertEquals(held+20,c.model.latest!!.pingId)
        c.stop();now+=100;c.tick();assertFalse(c.running);assertEquals(820,c.snapshot.ringCount)
        c.start();assertTrue(c.running);assertTrue(c.snapshot.display.freeze)
        c.freeze();assertSame(c.model.ring,c.model.viewRing)
        fixture.loadFixture(SonarFixture.CLEAR);assertEquals(0,c.snapshot.ringCount);assertFalse(c.running);assertNull(c.snapshot.depthM);assertFalse(c.snapshot.display.freeze)
    }
    @Test fun calibrationAndAutoAreGatedAndDropoutRetainsHistory() {
        val fixture=SonarFixtureHarness{1_000_000L};val c=fixture.controller
        c.start();c.autoRange(true)
        assertEquals(SonarRangeMode.MANUAL,c.snapshot.display.rangeMode);assertNotNull(c.notice);assertNull(c.snapshot.depthM)
        fixture.loadFixture(SonarFixture.CALIBRATED);assertNotNull(c.snapshot.depthM)
        c.autoRange(true);assertEquals(SonarRangeMode.AUTO,c.snapshot.display.rangeMode)
        assertEquals("Auto holds the current viewport until its source-time confirmation gates pass",1023.0,c.snapshot.display.cropEnd,.0001)
        fixture.loadFixture(SonarFixture.DROPOUT);assertFalse(c.running);assertNull(c.snapshot.depthM);assertEquals(800,c.snapshot.ringCount)
        c.start();assertTrue(c.running);assertFalse(c.model.calibrated);assertNull(c.snapshot.depthM)
    }
    @Test fun displayTransformsNeverRewriteRawBins() {
        val fixture=SonarFixtureHarness{1_000_000L};val c=fixture.controller;c.start();val samples=c.model.latest!!.samples
        c.gain(36.0);c.display{it.copy(gamma=.2,threshold=.75)};c.cyclePalette();c.interpolation(true)
        assertArrayEquals(samples,c.model.latest!!.samples);assertEquals(800,c.snapshot.ringCount)
        assertEquals(1.0,c.model.displayAmplitude(65535),.00001)
    }
    @Test fun ringStaysBoundedAcrossNavigationIndependentTicks() {
        var now=1_000_000L;val fixture=SonarFixtureHarness{now};val c=fixture.controller;c.start()
        // There is deliberately no SonarScreen involved in acquisition.
        repeat(1500){now+=100;c.tick()}
        assertEquals(2048,c.snapshot.ringCount);assertEquals(2299L,c.snapshot.pingId)
    }
    @Test fun cropMeterRangeAndCursorInspectionUseCurrentView() {
        val fixture=SonarFixtureHarness{1_000_000L};val c=fixture.controller;c.start();c.crop(256.0,767.0);c.inspect(1f,0f)
        assertEquals(256,c.cursor!!.bin);assertNull(c.cursor!!.depthM)
        assertEquals(c.model.latest!!.sample(256),c.cursor!!.amplitude)
        c.display{it.copy(zoom=true)};val b=c.bounds();assertEquals(255.5,b.end-b.start,.001)
        fixture.loadFixture(SonarFixture.CALIBRATED);c.range(5.0)
        assertEquals(5.0,c.snapshot.display.rangeM!!,.0001);assertEquals(5.0/.015,c.snapshot.display.cropEnd,.0001)
        c.inspect(1f,1f);assertEquals(c.cursor!!.bin*.015,c.cursor!!.depthM!!,.0001)
        c.autoRange(true);assertNull(c.snapshot.display.rangeM)
    }
    @Test fun historicalCursorUsesItsCaptureGeometryAndRefusesDepthBeyondItsCoverage() {
        val now=1_000_000L
        val c=SonarNativeController(DeterministicMockSonarSource(now={now})){now}
        try {
            c.model.connect("SYNTHETIC CURSOR TEST");c.model.acquisition(true)
            c.model.setDisplay(c.model.display.copy(historySeconds=10.0))
            fun append(id:Long,time:Long,maximum:Double) {
                c.model.setAnalysis(SonarGeometry(maximum,SonarTxReference.HOST_VERIFIED),SonarBottom(),null,false,
                    amplitude=SonarAmplitude(4095,12,true))
                c.model.frame(SonarFrame(ShortArray(1024){it.toShort()},id,time));c.refresh()
            }
            append(1,now-1000,3.072);append(2,now,24.576)
            c.inspect(.9f,1f/16f)
            assertEquals(512,c.cursor!!.bin);assertEquals(512,c.cursor!!.amplitude);assertEquals(1L,c.cursor!!.pingId)
            assertEquals(512*3.072/1023,c.cursor!!.depthM!!,1e-12)
            c.inspect(.9f,.5f);assertNull("Short capture has no sample at this depth",c.cursor)
            c.inspect(1f,.5f);assertEquals(512,c.cursor!!.bin);assertEquals(2L,c.cursor!!.pingId)
            c.model.setFreeze(true);c.refresh();c.inspect(.9f,1f/16f);val held=c.cursor
            append(3,now+100,3.072);c.inspect(.9f,1f/16f)
            assertEquals(held,c.cursor)
        }finally{c.dispose()}
    }

    @Test fun nightPaletteNeverContainsBrightDayFields() {
        for(p in SonarPalette.entries)for(i in 0..100){
            val color=SonarNativeMath.palette(i/100.0,p,true)
            assertTrue((color ushr 16 and 255)<=140)
            assertTrue((color ushr 8 and 255)<=48)
            assertTrue((color and 255)<=12)
            assertEquals(color,SonarNativeMath.palette(i/100.0,SonarPalette.NIGHT_RED,false))
        }
    }
    @Test fun zoomClampsToCropAndDrawerClosesWithoutStoppingAcquisition() {
        val d=SonarDisplay(cropStart=256.0,cropEnd=767.0,zoom=true)
        assertEquals(SonarNativeBounds(256.0,511.5),SonarNativeMath.bounds(d,0.0))
        assertEquals(SonarNativeBounds(511.5,767.0),SonarNativeMath.bounds(d,1023.0))
        val fixture=SonarFixtureHarness{1_000_000L};val c=fixture.controller;c.start();c.open(SonarDrawerTab.DISPLAY);c.close()
        assertNull(c.drawer);assertTrue(c.running)
    }
    @Test fun axisAndCursorStayTransducerRelativeWhileDepthAppliesReferenceOffset() {
        val fixture=SonarFixtureHarness{1_000_000L};val c=fixture.controller;fixture.loadFixture(SonarFixture.CALIBRATED)
        c.model.setCalibration(Calibration(true,20.0,1500.0,2.0,1.0,DepthReference.SURFACE));c.refresh()
        assertEquals(c.model.bottom.bin!!*.015+2.0,c.snapshot.depthM!!,.0001)
        c.inspect(1f,.5f)
        assertEquals(c.cursor!!.bin*.015,c.cursor!!.depthM!!,.0001)
        c.model.setCalibration(Calibration(true,20.0,1500.0,2.0,1.0,DepthReference.KEEL));c.refresh()
        assertEquals(c.model.bottom.bin!!*.015-1.0,c.snapshot.depthM!!,.0001)
        assertEquals(c.cursor!!.bin*.015,c.cursor!!.depthM!!,.0001)
    }

    @Test fun inspectingZoomedDataDoesNotMoveTheViewportUnderTheCrosshair() {
        val fixture=SonarFixtureHarness{1_000_000L};val c=fixture.controller;c.start();c.display{it.copy(zoom=true)}
        val bounds=c.bounds();assertEquals(SonarNativeBounds(255.75,767.25),bounds)
        c.inspect(.5f,.25f)
        assertEquals(384,c.cursor!!.bin);assertEquals(bounds,c.bounds())
        c.inspect(.5f,.75f)
        assertEquals(639,c.cursor!!.bin);assertEquals(bounds,c.bounds())
        c.display{it.copy(zoom=false)};c.inspect(.5f,.25f)
        val anchor=c.cursor!!.bin
        c.display{it.copy(zoom=true)};val anchored=c.bounds()
        assertEquals(anchor.toDouble(),(anchored.start+anchored.end)/2,.001)
        c.inspect(.2f,.1f);assertEquals(anchored,c.bounds())
    }
    @Test fun cursorSamplesTheMovingColumnAtFixedCoordinatesAndHoldsWhenFrozen() {
        var now=1_000_000L;val fixture=SonarFixtureHarness{now};val c=fixture.controller;c.start();c.inspect(.5f,.33f)
        now+=100;c.tick()
        val age=(0 until c.model.visibleCount()).minBy { kotlin.math.abs(c.model.historyFractionAt(it)-(1-c.cursor!!.x)) }
        assertEquals(c.model.viewRing.sampleAt(age,c.cursor!!.bin),c.cursor!!.amplitude)
        assertEquals((c.model.viewRing.timestampAt(0)-c.model.viewRing.timestampAt(age))/1000.0,c.cursor!!.secondsAgo,.001)
        c.freeze();val frozen=c.cursor
        repeat(5){now+=100;c.tick()}
        assertEquals(frozen,c.cursor)
        c.freeze()
        val liveAge=(0 until c.model.visibleCount()).minBy { kotlin.math.abs(c.model.historyFractionAt(it)-(1-c.cursor!!.x)) }
        assertEquals(c.model.viewRing.sampleAt(liveAge,c.cursor!!.bin),c.cursor!!.amplitude)
    }

    @Test fun customAcquisitionSourceSuppliesCalibratedBinsWithoutAnyFixtureApi() {
        val source = CustomSonarSource()
        val c = SonarNativeController(source) { 100_000L }
        source.state.emit(SonarSourceStatus(source.identity, 1, connected = true, running = true))
        source.packets.emit(source.packet(0, 1200))
        assertEquals(1, c.snapshot.ringCount); assertEquals(1200, c.model.latest!!.sample(0))
        assertEquals(1.5, c.snapshot.depthM!!, .00001); assertFalse(c.snapshot.demo)
        c.freeze(); source.packets.emit(source.packet(1, 2400))
        assertEquals(2, c.snapshot.ringCount); assertEquals(1, c.snapshot.frozenCount)
        assertEquals(1200, c.model.viewRing.sampleAt(0, 0)); assertEquals(2400, c.model.latest!!.sample(0))
    }

    @Test fun sourceSwitchDiscardsFrozenHistoryAndPiCannotActivateDemo() {
        val fixture = SonarFixtureHarness { 100_000L }; val demo = fixture.controller
        fixture.loadFixture(SonarFixture.CALIBRATED); demo.freeze(); demo.inspect(.5f,.5f)
        demo.dispose()
        val pi = SonarNativeController(UnavailablePiSonarSource(SourceIdentity("second", SourceMode.PI, "PI"))) { 100_000L }
        pi.start(); pi.tick()
        assertFalse(pi.snapshot.demo); assertFalse(pi.running); assertFalse(pi.canStart)
        assertEquals(0, pi.snapshot.ringCount); assertEquals(0, pi.snapshot.frozenCount)
        assertNull(pi.snapshot.depthM); assertNull(pi.snapshot.pingId); assertNull(pi.cursor)
        assertFalse(pi.snapshot.display.freeze); assertFalse(pi.model.calibrated)
        assertEquals(SonarRejection.UNAVAILABLE, (pi.lastCommandOutcome as SonarAcquisitionOutcome.Rejected).reason)
        demo.start(); demo.tick(); assertEquals(0, demo.snapshot.ringCount)
        val next = SonarNativeController(DeterministicMockSonarSource(SourceIdentity("third", SourceMode.DEMO, "DEMO")) { 100_000L }) { 100_000L }
        assertEquals(0, next.snapshot.ringCount); assertFalse(next.snapshot.display.freeze); assertNull(next.snapshot.depthM)
    }

    @Test fun closedControllerIgnoresLeakedCallbacksAndWrongSessionOrGenerationFrames() {
        val source = CustomSonarSource()
        val c = SonarNativeController(source) { 100_000L }
        source.state.emit(SonarSourceStatus(source.identity, 1, connected = true, running = true))
        source.packets.emit(source.packet(0, 1234))
        source.packets.emit(source.packet(1, 2222).copy(identity = source.identity.copy(sessionId = "old")))
        source.packets.emit(source.packet(2, 3333).copy(generation = 0))
        assertEquals(1, c.snapshot.ringCount); assertEquals(1234, c.model.latest!!.sample(0))
        source.state.emit(SonarSourceStatus(source.identity, 2, connected = true, running = true))
        assertEquals(0, c.snapshot.ringCount); assertFalse(c.model.calibrated)
        source.state.emit(SonarSourceStatus(source.identity, 1, connected = true, running = true))
        source.packets.emit(source.packet(3, 4444))
        assertEquals(0, c.snapshot.ringCount)
        c.dispose(); assertTrue(source.closed)
        source.state.emit(SonarSourceStatus(source.identity, 3, connected = true, running = true))
        source.packets.emit(source.packet(4, 5555).copy(generation = 3))
        assertEquals(0, c.snapshot.ringCount); assertFalse(c.running); assertNull(c.snapshot.depthM)
        assertFalse(c.canStart)
    }

    @Test fun staleAcknowledgmentsNeverStartAcquisitionOrOverrideCurrentOutcome() {
        val source = CustomSonarSource()
        val c = SonarNativeController(source) { 100_000L }
        source.ackSession = "old-session"; c.start()
        assertNull(c.lastCommandOutcome); assertFalse(c.running)
        source.ackSession = source.identity.sessionId; source.ackOffset = -1; c.start()
        assertNull(c.lastCommandOutcome); assertFalse(c.running)
        source.ackOffset = 0; c.start()
        assertTrue(c.lastCommandOutcome is SonarAcquisitionOutcome.Acknowledged)
        assertFalse(c.running); assertEquals(0, c.snapshot.ringCount)
    }

    @Test fun failingSourceCloseStillClearsControllerHistoryAndRejectsLateFrames() {
        val source = CustomSonarSource()
        val c = SonarNativeController(source) { 100_000L }
        source.state.emit(SonarSourceStatus(source.identity, 1, connected = true, running = true))
        source.packets.emit(source.packet(0, 1234)); c.freeze()
        source.throwOnClose = true
        assertThrows(IllegalStateException::class.java) { c.dispose() }
        assertTrue(source.closed); assertEquals(0, c.snapshot.ringCount); assertEquals(0, c.snapshot.frozenCount)
        assertFalse(c.running); assertFalse(c.snapshot.display.freeze); assertNull(c.snapshot.depthM)
        source.packets.emit(source.packet(1, 5678)); assertEquals(0, c.snapshot.ringCount)
        c.dispose()
    }


    @Test fun asynchronousCommandsStayPendingAndRapidRepeatCannotHideTheirAck() {
        val source = CustomSonarSource().apply { pendingMode = true }
        val c = SonarNativeController(source) { 100_000L }
        c.start()
        assertTrue(c.commandPending); assertFalse(c.canStart); assertFalse(c.running)
        c.start(); c.stop(); assertEquals(1, source.commands)
        source.results.emit(SonarAcquisitionOutcome.Acknowledged(source.identity.sessionId, source.latestRequest + 1))
        assertTrue(c.commandPending)
        source.results.emit(SonarAcquisitionOutcome.Acknowledged(source.identity.sessionId, source.latestRequest))
        assertFalse(c.commandPending); assertFalse(c.running)
        assertTrue(c.lastCommandOutcome is SonarAcquisitionOutcome.Acknowledged)
        c.dispose()
    }

    @Test fun absentOptionalSelectionMetadataDoesNotInventANewEpochOrDeleteHistory() {
        val source=CustomSonarSource();val c=SonarNativeController(source){100_000L}
        val selection=SonarDisplaySelection(SonarPingSource.OWN,7)
        source.state.emit(SonarSourceStatus(source.identity,1,true,true,serverSessionId="confirmed",serverGeneration=1,displaySelection=selection))
        source.packets.emit(source.packet(0,1200).copy(displaySelection=selection,pingSource=SonarPingSource.OWN))
        source.state.emit(SonarSourceStatus(source.identity,1,true,true,serverSessionId="confirmed",serverGeneration=1))
        source.packets.emit(source.packet(1,2400).copy(pingSource=SonarPingSource.OWN))
        assertEquals(2,c.snapshot.ringCount);assertEquals(7L,c.model.epoch?.selectionGeneration)
        assertEquals(SonarPingSource.OWN,c.model.selectedPingSource)
        c.dispose()
    }

    @Test fun originalReferenceReasonNeverClaimsAnEventTimeOrSpecificBatteryCause() {
        var time=100_000L;val source=CustomSonarSource();val c=SonarNativeController(source){time}
        val reason=SonarObservationReason("ORIGINAL_REFERENCE_LOST","native_reference_lost",null,time,true,true)
        source.state.emit(SonarSourceStatus(source.identity,1,true,true,observationReason=reason))
        val current=c.observationReasonLabel()!!
        assertTrue(current.contains(com.no5.ui.No5Text.ui("Originalreferenz verloren – Venterior/Versorgung und Kontakt prüfen")))
        assertFalse(current.contains(com.no5.ui.No5Text.ui("Status veraltet")))
        time+=6000;c.tick()
        assertTrue(c.observationReasonLabel()!!.contains(com.no5.ui.No5Text.ui("Status veraltet")))
        source.state.emit(SonarSourceStatus(source.identity,1,true,true));assertNull(c.observationReasonLabel())
        source.state.emit(SonarSourceStatus(source.identity,1,true,true,observationReason=reason.copy(code="FUTURE_UNKNOWN")))
        assertNull(c.observationReasonLabel())
        c.dispose()
    }

    /** Fixture control belongs to the test harness, never the app controller or screen. */
    private class SonarFixtureHarness(now: () -> Long) {
        private val mock = DeterministicMockSonarSource(now = now)
        private var sequence = 0L
        private fun next() = SonarAcquisitionRequest(mock.identity.sessionId, ++sequence)
        private fun SonarAcquisitionOutcome.withRequest(id: Long): SonarAcquisitionOutcome = when(this) {
            is SonarAcquisitionOutcome.Pending -> copy(requestId = id)
            is SonarAcquisitionOutcome.Acknowledged -> copy(requestId = id)
            is SonarAcquisitionOutcome.Rejected -> copy(requestId = id)
        }
        private val source = object : SonarSourcePort by mock {
            override fun start(request: SonarAcquisitionRequest) = mock.start(next()).withRequest(request.requestId)
            override fun stop(request: SonarAcquisitionRequest) = mock.stop(next()).withRequest(request.requestId)
        }
        val controller = SonarNativeController(source, now)
        fun loadFixture(fixture: SonarFixture) { mock.loadFixture(fixture, next()) }
    }

    /** Deliberately keeps listeners after close, so controller lifetime guards are exercised. */
    private class LeakyFeed<T>(initial: T) : SourceFeed<T> {
        override var value: T = initial; private set
        private val listeners = mutableListOf<(T) -> Unit>()
        override fun subscribe(listener: (T) -> Unit): SourceSubscription {
            listeners += listener; listener(value); return SourceSubscription { }
        }
        fun emit(next: T) { value = next; listeners.toList().forEach { it(next) } }
    }
    private class CustomSonarSource : SonarSourcePort {
        override val identity = SourceIdentity("custom", SourceMode.PI, "CUSTOM SONAR")
        val state = LeakyFeed(SonarSourceStatus(identity))
        private val features = LeakyFeed(SonarSourceCapabilities(identity, canStart = true, canStop = true, bottomDetection = true))
        val packets = LeakyFeed<SonarSourceFrame?>(null)
        override val status: SourceFeed<SonarSourceStatus> get() = state
        override val capabilities: SourceFeed<SonarSourceCapabilities> get() = features
        override val frames: SourceFeed<SonarSourceFrame?> get() = packets
        var closed = false
        var throwOnClose = false
        var ackSession = identity.sessionId
        var ackOffset = 0L
        var pendingMode = false
        var commands = 0
        var latestRequest = 0L
        val results = LeakyFeed<SonarAcquisitionOutcome?>(null)
        override val outcomes: SourceFeed<SonarAcquisitionOutcome?> get() = results
        fun packet(id: Long, amplitude: Int) = SonarSourceFrame(identity, 1,
            SonarFrame(ShortArray(SONAR_BINS) { amplitude.toShort() }, id, 100_000L),
            Calibration(true, 20.0), SonarBottom(100.0, .99, .8))
        override fun start(request: SonarAcquisitionRequest): SonarAcquisitionOutcome {
            commands++; latestRequest = request.requestId
            return if (pendingMode) SonarAcquisitionOutcome.Pending(ackSession, request.requestId)
            else SonarAcquisitionOutcome.Acknowledged(ackSession, request.requestId + ackOffset)
        }
        override fun stop(request: SonarAcquisitionRequest): SonarAcquisitionOutcome = start(request)
        override fun tick() = Unit
        override fun close() { closed = true; if (throwOnClose) throw IllegalStateException("Source close failed") }
    }

}
