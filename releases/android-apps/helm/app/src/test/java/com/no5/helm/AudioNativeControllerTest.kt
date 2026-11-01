package com.no5.helm

import com.no5.domain.*
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.*
import org.junit.Test

class AudioNativeControllerTest {
    private fun controller() = AudioNativeController(Dispatchers.Unconfined, now={10_000L}, automaticTicker=false, automaticVerification=false, source=MockAudioSource(now={10_000L}))
    @Test fun canonicalStartsUnknownAndRefusesWrites() {
        val c=controller()
        assertFalse(c.isDemo)
        assertNull(c.state.dsp.faders[AudioChannel.MASTER]?.db)
        assertNull(c.state.media.title)
        assertFalse(c.changeLevel(AudioChannel.MASTER,-10.0))
        assertFalse(c.transport(TransportAction.PLAY))
        assertNull(c.state.pending)
        c.dispose()
    }
    @Test fun localWriteIsPendingThenVerifiedAndClamped() {
        val c=controller();c.loadFixture(AudioFixture.DEMO)
        assertTrue(c.changeLevel(AudioChannel.MASTER,20.0))
        assertEquals(-6.0,c.state.dsp.faders.getValue(AudioChannel.MASTER).db!!,0.0)
        val id=c.state.pending!!.requestId
        assertTrue(c.changeLevel(AudioChannel.SUB,-12.0))
        c.completePending(id,true)
        assertEquals(AudioCommand.Fader(AudioChannel.SUB,-12.0),c.state.pending!!.command)
        assertEquals(AudioWriteState.VERIFIED,c.state.dsp.faders.getValue(AudioChannel.MASTER).writeState)
        c.completePending(c.state.pending!!.requestId,true)
        assertNull(c.state.pending)
        assertEquals(-12.0,c.state.dsp.faders.getValue(AudioChannel.SUB).db!!,0.0)
        c.dispose()
    }
    @Test fun offlineDropsPendingAndLateAckNeverReplaysIt() {
        val c=controller();c.loadFixture(AudioFixture.DEMO)
        c.changeLevel(AudioChannel.MASTER,-10.0)
        val id=c.state.pending!!.requestId
        c.setOffline(AudioOfflineTarget.USB)
        assertNull(c.state.pending)
        assertEquals(-24.0,c.state.dsp.faders.getValue(AudioChannel.MASTER).db!!,0.0)
        assertFalse(c.changeLevel(AudioChannel.MASTER,-8.0))
        c.setOffline(AudioOfflineTarget.USB,false)
        c.completePending(id,true)
        assertEquals(-24.0,c.state.dsp.faders.getValue(AudioChannel.MASTER).db!!,0.0)
        assertTrue(c.can(AudioCommand.Fader(AudioChannel.MASTER,-8.0)))
        c.dispose()
    }
    @Test fun sourceLossPreservesIndependentDspControl() {
        val c=controller();c.loadFixture(AudioFixture.DEMO)
        c.setOffline(AudioOfflineTarget.SOURCE)
        assertFalse(c.can(AudioCommand.Transport(TransportAction.PLAY)))
        assertTrue(c.can(AudioCommand.Fader(AudioChannel.SUB,-8.0)))
        assertFalse(c.state.source.connected)
        c.dispose()
    }
    @Test fun localDisplayAliasNeverChangesTheCamillaCommandId() {
        val c=controller();c.loadFixture(AudioFixture.DEMO)
        val master=requireNotNull(c.masterControl())
        assertEquals(com.no5.ui.No5Text.ui("Haupt"),c.displayName(master))
        assertTrue(c.setLocalDisplayAlias(master,"Cabin volume"))
        assertEquals("Cabin volume",c.displayName(master))
        assertTrue(c.changeLevel(master,-12.0))
        assertEquals(AudioCommand.Fader(AudioChannel.MASTER,-12.0),c.state.pending!!.command)
        assertTrue(c.setLocalDisplayAlias(master,null))
        assertEquals(com.no5.ui.No5Text.ui("Haupt"),c.displayName(master))
        c.dispose()
    }
    @Test fun persistedPresentationHidesOnlyTheMatchingPhysicalControlAndKeepsDspAvailabilityIndependentOfMedia() {
        val source = MockAudioSource(now = { 10_000L }).also { it.loadFixture(AudioFixture.DEMO); it.setOffline(AudioOfflineTarget.SOURCE) }
        val presentation = AudioPresentationSettings.DEFAULT
            .update(AudioChannel.SUB, "Cabin", false)
        val c = AudioNativeController(Dispatchers.Unconfined, now = { 10_000L }, automaticTicker = false,
            automaticVerification = false, source = source, initialPresentation = presentation)
        val sub = requireNotNull(c.faderControl(AudioChannel.SUB))
        assertEquals("Cabin", c.displayName(sub))
        assertFalse(c.isVisible(sub))
        assertTrue(c.canAdjust(sub))
        assertTrue(c.can(AudioCommand.Fader(AudioChannel.SUB, -8.0)))
        c.dispose()
    }
    @Test fun displayOnlySourceDoesNotOfferTransport() {
        val c=controller();c.loadFixture(AudioFixture.DISPLAY_ONLY)
        assertFalse(c.transport(TransportAction.PLAY))
        assertFalse(c.can(AudioCommand.Transport(TransportAction.SEEK,100)))
        assertTrue(c.can(AudioCommand.Mute(AudioChannel.MASTER,true)))
        assertEquals("AUDIO_GATE_PLAY_PAUSE_UNAVAILABLE; no command sent", c.commandGateDiagnostic(AudioCommand.Transport(TransportAction.PLAY)))
        c.dispose()
    }
    @Test fun cancelledDragNeverCommitsAndStopRollsBackPending() {
        val c=controller();c.loadFixture(AudioFixture.DEMO)
        c.previewLevel(AudioChannel.MASTER,-12.0)
        assertEquals(-12.0,c.level(AudioChannel.MASTER)!!,0.0)
        assertEquals(-24.0,c.state.dsp.faders.getValue(AudioChannel.MASTER).db!!,0.0)
        c.cancelPreview();assertEquals(-24.0,c.level(AudioChannel.MASTER)!!,0.0)
        c.changeLevel(AudioChannel.MASTER,-8.0)
        val id=c.state.pending!!.requestId
        c.stopDemo();c.completePending(id,true)
        assertEquals(-24.0,c.level(AudioChannel.MASTER)!!,0.0)
        c.dispose()
    }
    @Test fun demoHasFixedExplicitUnverifiedMeterFixture() {
        val c=controller();c.startDemo()
        val frame=c.state.dsp.meters!!
        assertTrue(frame.quality.demo)
        assertFalse(frame.routingVerified)
        assertTrue(frame.channels.all { it.label=="OUTPUT ${it.index+1}" })
        repeat(10){c.tickOnce()}
        assertEquals(frame,c.state.dsp.meters)
        c.stopDemo();val position=c.state.media.positionMs;c.tickOnce()
        assertEquals(position,c.state.media.positionMs)
        c.resetDemo();assertNull(c.state.dsp.meters);assertFalse(c.isDemo)
        c.dispose()
    }
    @Test fun presetFailureRollsBackAndTransportChangesRealFixtureTrack() {
        val c=controller();c.loadFixture(AudioFixture.DEMO)
        c.applyPreset();assertEquals(DspState.APPLYING_PRESET,c.state.dsp.state)
        c.completePending(c.state.pending!!.requestId,false)
        assertEquals(DspState.RUNNING,c.state.dsp.state)
        assertTrue(c.state.feedback!!.contains("ROLLED BACK"))
        c.transport(TransportAction.NEXT);c.completePending(c.state.pending!!.requestId,true)
        assertEquals("Blue horizon",c.state.media.title);assertEquals(0L,c.state.media.positionMs)
        c.dispose()
    }
    @Test fun customLiveSourceWorksWithoutTheDemoReducerOrUiChanges() {
        val fake = CustomAudioSource()
        val c = AudioNativeController(Dispatchers.Unconfined, now = { 10_000L }, automaticTicker = false, automaticVerification = false, source = fake)
        assertFalse(c.isDemo)
        assertFalse(c.demoAvailable)
        assertTrue(c.can(AudioCommand.Fader(AudioChannel.MASTER, -12.0)))
        assertTrue(c.changeLevel(AudioChannel.MASTER, -12.0))
        assertEquals(listOf(AudioCommand.Fader(AudioChannel.MASTER, -12.0)), fake.commands)
        assertEquals(-12.0, c.state.dsp.faders.getValue(AudioChannel.MASTER).db!!, 0.0)
        assertEquals(AudioCommandStatus.VERIFIED, c.lastOutcome!!.status)
        c.startDemo(); c.loadFixture(AudioFixture.CLIPPING); c.resetDemo(); c.setOffline(true)
        assertEquals(OperatingMode.LIVE, c.state.mode)
        assertEquals("CUSTOM LIVE SOURCE", c.state.media.title)
        c.dispose(); assertTrue(fake.closed)
    }
    @Test fun strictAdapterUsesValidFaderProbeAndFixedLimitsDisableDragging() {
        val fake = CustomAudioSource()
        val c = AudioNativeController(Dispatchers.Unconfined, now = { 10_000L }, automaticTicker = false, automaticVerification = false, source = fake)
        assertFalse(c.can(AudioCommand.Fader(AudioChannel.MASTER, 0.0)))
        assertTrue(c.canAdjust(AudioChannel.MASTER))
        c.previewLevel(AudioChannel.MASTER, 10.0)
        assertEquals(-6.0, c.level(AudioChannel.MASTER)!!, 0.0)
        c.commitPreview()
        assertEquals(AudioCommand.Fader(AudioChannel.MASTER, -6.0), fake.commands.last())
        val fixed = FaderLimit(-12.0, -12.0)
        fake.feed.emit(fake.state.value.copy(limits = fake.state.value.limits!! + (AudioChannel.MASTER to fixed)))
        assertFalse(c.canAdjust(AudioChannel.MASTER))
        c.previewLevel(AudioChannel.MASTER, -12.0)
        assertNull(c.preview)
        assertEquals(0f, AudioNativeGeometry.levelFraction(-12.0, fixed), 0f)
        assertEquals(0f, AudioNativeGeometry.levelFraction(Double.NaN, fixed), 0f)
        assertEquals(1f, AudioNativeGeometry.levelFraction(0.0, FaderLimit(-60.0, -6.0)), 0f)
        c.dispose()
    }
    @Test fun piSelectionCannotStartDemoThroughAnyControllerEntryPoint() {
        val pi = UnavailableAudioSource(SourceIdentity("pi-session", SourceMode.PI, "PI"))
        val c = AudioNativeController(Dispatchers.Unconfined, automaticTicker = false, automaticVerification = false, source = pi)
        c.startDemo(); c.loadFixture(AudioFixture.DEMO); c.loadFixture(AudioFixture.DISPLAY_ONLY)
        c.resetDemo(); c.failNextDemoWrite(); c.setOffline(false)
        assertFalse(c.demoAvailable)
        assertFalse(c.isDemo)
        assertFalse(c.demoRunning)
        assertNull(c.state.media.title)
        assertNull(c.state.dsp.meters)
        assertFalse(c.changeLevel(AudioChannel.MASTER, -12.0))
        c.dispose()
    }
    @Test fun staleSessionEmissionsAndCallbacksAfterDisposeCannotReachUi() {
        val fake = CustomAudioSource()
        val c = AudioNativeController(Dispatchers.Unconfined, now = { 10_000L }, automaticTicker = false, automaticVerification = false, source = fake)
        fake.feed.emit(fake.feed.value.copy(sessionId = "old-session", media = AudioMedia(title = "STALE")))
        assertEquals("CUSTOM LIVE SOURCE", c.state.media.title)
        val before = c.state
        c.dispose()
        fake.feed.emit(before.copy(media = AudioMedia(title = "CLOSED")))
        assertEquals(before, c.state)
        assertFalse(c.changeLevel(AudioChannel.MASTER, -20.0))
        assertTrue(fake.commands.isEmpty())
    }
    @Test fun customSourceFreshnessIsProjectedUsingItsOwnPolicy() {
        var time = 10_000L
        val fake = CustomAudioSource()
        val c = AudioNativeController(Dispatchers.Unconfined, now = { time }, automaticTicker = false, automaticVerification = false, source = fake)
        time = 13_000L; c.tickOnce()
        assertEquals(TelemetryStatus.LIVE, c.state.source.quality.status)
        time = 16_000L; c.tickOnce()
        assertEquals(TelemetryStatus.STALE, c.state.source.quality.status)
        time = 21_000L; c.tickOnce()
        assertEquals(TelemetryStatus.LOST, c.state.source.quality.status)
        c.dispose()
    }
    @Test fun sourceReplacementCannotReceiveOldAdaptersLateCompletion() {
        val oldSource = MockAudioSource(SourceIdentity("old-audio", SourceMode.DEMO, "OLD"), now = { 10_000L })
        val old = AudioNativeController(Dispatchers.Unconfined, now = { 10_000L }, automaticTicker = false, automaticVerification = false, source = oldSource)
        old.startDemo(); old.changeLevel(AudioChannel.MASTER, -12.0)
        val id = old.state.pending!!.requestId
        old.dispose()
        val replacement = AudioNativeController(Dispatchers.Unconfined, now = { 10_000L }, automaticTicker = false, automaticVerification = false,
            source = MockAudioSource(SourceIdentity("new-audio", SourceMode.DEMO, "NEW"), now = { 10_000L }))
        replacement.startDemo()
        assertFalse(oldSource.acknowledge("old-audio", id, true))
        assertEquals(-24.0, replacement.level(AudioChannel.MASTER)!!, 0.0)
        assertNull(replacement.lastOutcome)
        replacement.dispose()
    }
    private class CustomAudioSource : AudioSourcePort {
        override val identity = SourceIdentity("custom-session", SourceMode.PI, "CUSTOM PI")
        override val freshnessPolicy = FreshnessPolicy(5000, 10000)
        private val quality = TelemetryQuality(TelemetryStatus.LIVE, 10_000, "CUSTOM PI")
        val feed = MutableSourceFeed(AudioModel.fixture(AudioFixture.DEMO).let { initial ->
            initial.copy(sessionId = identity.sessionId, mode = OperatingMode.LIVE,
                source = initial.source.copy(quality = quality), media = initial.media.copy(title = "CUSTOM LIVE SOURCE"),
                dsp = initial.dsp.copy(quality = quality))
        })
        private val outcomeFeed = MutableSourceFeed<AudioCommandOutcome?>(null)
        override val state: SourceFeed<AudioState> get() = feed
        override val outcomes: SourceFeed<AudioCommandOutcome?> get() = outcomeFeed
        val commands = mutableListOf<AudioCommand>()
        var closed = false
        override fun available(command: AudioCommand) = !closed && command is AudioCommand.Fader &&
            state.value.limits?.get(command.channel)?.let { command.valueDb in it.minDb..it.maxDb } == true
        override fun submit(command: AudioCommand): AudioCommandOutcome {
            if (!available(command)) return AudioCommandOutcome(identity.sessionId, null, command, AudioCommandStatus.REJECTED, 10_000, "Unsupported")
            commands += command
            val fader = command as AudioCommand.Fader
            val revision = state.value.revision + 1
            feed.emit(state.value.copy(revision = revision, dsp = state.value.dsp.copy(faders = state.value.dsp.faders +
                (fader.channel to AudioFader(fader.valueDb, writeState = AudioWriteState.VERIFIED)))))
            return AudioCommandOutcome(identity.sessionId, "${identity.sessionId}:$revision", command, AudioCommandStatus.VERIFIED, 10_000, "CUSTOM VERIFIED").also { outcomeFeed.emit(it) }
        }
        override fun tick() = Unit
        // Keep the feed open deliberately to test the controller's subscription cleanup independently.
        override fun close() { closed = true }
    }
    @Test fun geometryIs1200AndThumbCoordinatesMatchBothOrientations() {
        assertEquals(1200f,AudioNativeGeometry.HEIGHT)
        assertEquals(1058f,AudioNativeGeometry.BOTTOM)
        assertEquals(0f,AudioNativeGeometry.fraction(20f,510f),0f)
        assertEquals(1f,AudioNativeGeometry.fraction(490f,510f),0f)
        assertEquals(.5f,AudioNativeGeometry.fraction(255f,510f),.001f)
        assertEquals(1f,AudioNativeGeometry.fraction(20f,280f,true),0f)
        assertEquals(0f,AudioNativeGeometry.fraction(260f,280f,true),0f)
    }
    @Test fun finalAudioGeometryKeepsMasterOutsideSetupAndTouchTargetsSeparate() {
        val g=AudioNativeGeometry
        val stage=AudioNativeRect(0f,g.TOP,g.WIDTH,g.BOTTOM-g.TOP)
        val controls=g.transport+(0..2).map{g.zoneSlider(it)}+(0..2).map{g.zoneMute(it)}+
            listOf(g.seek,g.masterSlider,g.masterDown,g.masterUp,g.masterMute,g.settings)
        controls.forEach{assertTrue(stage.contains(it))}
        controls.forEachIndexed{i,a->controls.drop(i+1).forEach{b->assertFalse("$a overlaps $b",a.overlaps(b))}}
        listOf(g.masterSlider,g.masterDown,g.masterUp,g.masterMute).forEach{assertFalse(g.drawer.overlaps(it))}
        assertEquals(4,g.transport.size)
        assertEquals(listOf(1103f,1291f,1479f),g.faderSlotX)
    }
    @Test fun removedLocalSimulatorPanelCannotBeOpened() {
        val c=controller();c.openPanel("demo");assertNull(c.panel)
        c.startDemo();c.openPanel("demo");assertNull(c.panel)
        c.dispose()
    }
    @Test fun networkSimulationLabelsCannotClaimRealPlaybackOrLocalDemo() {
        val source=CustomAudioSource()
        val q=TelemetryQuality(TelemetryStatus.LIVE,10_000,"PI SIMULATOR",demo=false,simulated=true)
        source.feed.emit(source.feed.value.let { it.copy(source=it.source.copy(quality=q),dsp=it.dsp.copy(quality=q)) })
        val c=AudioNativeController(Dispatchers.Unconfined,now={10_000},automaticTicker=false,automaticVerification=false,source=source)
        assertTrue(c.networkSimulation);assertTrue(c.isSimulation);assertFalse(c.isDemo);assertFalse(c.demoAvailable)
        assertEquals("NETWORK SIMULATION",c.provenanceLabel)
        assertTrue(c.statusLabel.contains("NETWORK SIMULATION"))
        source.feed.emit(source.feed.value.let { it.copy(source=it.source.copy(quality=q.copy(status=TelemetryStatus.LOST))) })
        assertTrue(c.statusLabel.contains("LOST"));c.dispose()
    }
}
