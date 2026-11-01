package com.no5.helm

import android.os.Looper
import android.os.Parcel
import android.os.SystemClock
import com.no5.control.NormalizedKnobInput.*
import com.no5.domain.*
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.lumina.control.commands.v1.CommandRing

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
@LooperMode(LooperMode.Mode.PAUSED)
class SharedControlOwnershipTest {
    private fun audio(): Pair<MockAudioSource, AudioNativeController> {
        val source = MockAudioSource(now = { 1_000_000L }).also { it.loadFixture(AudioFixture.DEMO) }
        return source to AudioNativeController(Dispatchers.Unconfined, { 1_000_000L }, false, false, source)
    }
    private fun result(action: com.no5.appcontrol.BufferedKnobAction, id: Long): Pair<Int, String?> {
        val parcel = Parcel.obtain()
        try {
            assertTrue(action.writeResult(id, parcel)); parcel.setDataPosition(0)
            assertEquals(1, parcel.readInt())
            return parcel.readInt() to parcel.readString()
        } finally { parcel.recycle() }
    }

    @Test fun sonarPageUsesSharedSonarControlsAndAudioModeUsesTheExistingSelectedRearFader() {
        val (source,audio)=audio()
        val sonarSource=DeterministicMockSonarSource(now={1_000_000L})
        val sonar=SonarNativeController(sonarSource){1_000_000L};sonar.start()
        val a=AudioControlActor(audio);val s=SonarControlActor(sonar);val routing=HelmKnobRouting(a,s,sonar)
        try {
            assertEquals(SonarKnobMode.SONAR,sonar.knobMode)
            val raw=sonar.model.latest!!.samples.copyOf();val before=sonar.snapshot.display
            val bindings=routing.bind("SONAR")
            bindings.getValue(LEFT_CW).submit(1,SystemClock.uptimeMillis())
            bindings.getValue(RIGHT_CW).submit(2,SystemClock.uptimeMillis())
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(before.cropEnd*.8,sonar.snapshot.display.cropEnd,.0001)
            assertEquals(before.gainDb+1,sonar.snapshot.display.gainDb,0.0)
            assertNull(source.state.value.pending);assertArrayEquals(raw,sonar.model.latest!!.samples)
            val physical=sonar.snapshot.display
            sonar.display{before};sonar.adjustRange(1);sonar.adjustGain(1)
            assertEquals("Touch and physical events use the same display owner",physical,sonar.snapshot.display)
            audio.selectKnobFader(audio.faderControl(AudioChannel.REAR)!!)
            val master=audio.masterControl()!!.db!!;val rear=audio.faderControl(AudioChannel.REAR)!!.db!!
            sonar.toggleKnobMode();assertEquals(SonarKnobMode.AUDIO,sonar.knobMode)
            val audioBindings=routing.bind("SONAR")
            audioBindings.getValue(LEFT_CCW).submit(3,SystemClock.uptimeMillis())
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(AudioCommand.Fader(AudioChannel.REAR,rear-1),source.state.value.pending!!.command)
            source.completePending(source.state.value.pending!!.requestId,true)
            assertEquals(master,audio.masterControl()!!.db!!,0.0)
            assertEquals(physical,sonar.snapshot.display)
        }finally{routing.close();a.close();s.close();audio.dispose();sonar.dispose()}
    }

    @Test fun switchingTargetsCancelsBothPendingRingsAndRejectsOldCallbackObjectsBeforeRebinding() {
        val (source,audio)=audio();val sonar=SonarNativeController(DeterministicMockSonarSource(now={1_000_000L})){1_000_000L};sonar.start()
        val a=AudioControlActor(audio);val s=SonarControlActor(sonar);val routing=HelmKnobRouting(a,s,sonar)
        try {
            val gain=sonar.snapshot.display.gainDb
            val oldSonar=routing.bind("SONAR").getValue(RIGHT_CW)
            val sonarId=oldSonar.submit(1,SystemClock.uptimeMillis());assertTrue(sonarId>0)
            // The touch MODE operation must cancel accepted-but-unstarted gain before draining.
            sonar.toggleKnobMode();assertEquals(SonarKnobMode.AUDIO,sonar.knobMode)
            assertEquals(0L,oldSonar.submit(2,SystemClock.uptimeMillis()))
            assertEquals(CommandRing.State.CANCELLED.ordinal,result(oldSonar,sonarId).first)
            val oldAudio=routing.bind("SONAR").getValue(LEFT_CW)
            val audioId=oldAudio.submit(3,SystemClock.uptimeMillis());assertTrue(audioId>0)
            sonar.toggleKnobMode();assertEquals(SonarKnobMode.SONAR,sonar.knobMode)
            assertEquals(0L,oldAudio.submit(4,SystemClock.uptimeMillis()))
            assertEquals(CommandRing.State.CANCELLED.ordinal,result(oldAudio,audioId).first)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(gain,sonar.snapshot.display.gainDb,0.0);assertNull(source.state.value.pending)
            val fresh=routing.bind("SONAR").getValue(RIGHT_CW)
            assertTrue(fresh.submit(5,SystemClock.uptimeMillis())>0)
            // A late cancel from a retired ProductKnobClient map may not erase the new map.
            oldAudio.cancel();oldSonar.cancel();shadowOf(Looper.getMainLooper()).idle()
            assertEquals(gain+1,sonar.snapshot.display.gainDb,0.0);assertNull(source.state.value.pending)
        }finally{routing.close();a.close();s.close();audio.dispose();sonar.dispose()}
    }

    @Test fun focusCancellationDropsWorkButTheUnchangedMapCanServeAFreshTrustedLease() {
        val (source,audio)=audio();val sonar=SonarNativeController(DeterministicMockSonarSource(now={1_000_000L})){1_000_000L};sonar.start()
        val a=AudioControlActor(audio);val s=SonarControlActor(sonar);val routing=HelmKnobRouting(a,s,sonar)
        try {
            val gain=sonar.snapshot.display.gainDb
            val binding=routing.bind("SONAR").getValue(RIGHT_CW)
            val cancelled=binding.submit(1,SystemClock.uptimeMillis());binding.cancel()
            shadowOf(Looper.getMainLooper()).idle();assertEquals(gain,sonar.snapshot.display.gainDb,0.0)
            assertEquals(CommandRing.State.CANCELLED.ordinal,result(binding,cancelled).first)
            assertTrue(binding.submit(2,SystemClock.uptimeMillis())>0)
            shadowOf(Looper.getMainLooper()).idle();assertEquals(gain+1,sonar.snapshot.display.gainDb,0.0)
            routing.bind("SETTINGS")
            assertEquals(0L,binding.submit(3,SystemClock.uptimeMillis()))
            assertNull(source.state.value.pending)
        }finally{routing.close();a.close();s.close();audio.dispose();sonar.dispose()}
    }

    @Test fun coalescedModeRoundTripChangesTheBindingKeyAndCancelsOldWorkWithoutBindingOnGainChanges() {
        val (source,audio)=audio();val sonar=SonarNativeController(DeterministicMockSonarSource(now={1_000_000L})){1_000_000L};sonar.start()
        val a=AudioControlActor(audio);val s=SonarControlActor(sonar);val routing=HelmKnobRouting(a,s,sonar)
        try {
            val initialMode=sonar.knobMode;val effectKey=sonar.knobMappingRevision
            val gain=sonar.snapshot.display.gainDb
            val original=routing.bind("SONAR").getValue(RIGHT_CW)
            val oldId=original.submit(1,SystemClock.uptimeMillis());assertTrue(oldId>0)
            // No rebind/recomposition between these touch operations: knobMode returns to A.
            sonar.toggleKnobMode();sonar.toggleKnobMode()
            assertEquals(initialMode,sonar.knobMode)
            assertNotEquals("The lease Effect key must still change for A→B→A",effectKey,sonar.knobMappingRevision)
            assertEquals(0L,original.submit(2,SystemClock.uptimeMillis()))
            assertEquals(CommandRing.State.CANCELLED.ordinal,result(original,oldId).first)
            val replacement=routing.bind("SONAR").getValue(RIGHT_CW)
            val newKey=sonar.knobMappingRevision
            assertTrue(replacement.submit(3,SystemClock.uptimeMillis())>0)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(gain+1,sonar.snapshot.display.gainDb,0.0)
            assertEquals("Gain must not churn the lease mapping",newKey,sonar.knobMappingRevision)
            sonar.adjustGain(-1);assertEquals(newKey,sonar.knobMappingRevision)
            assertNull(source.state.value.pending)
        }finally{routing.close();a.close();s.close();audio.dispose();sonar.dispose()}
    }

    @Test fun physicalLongPressUsesTheSameModeOperationAndOtherPagesRemainAudio() {
        val (source,audio)=audio();val sonar=SonarNativeController(DeterministicMockSonarSource(now={1_000_000L})){1_000_000L};sonar.start()
        val a=AudioControlActor(audio);val s=SonarControlActor(sonar);val routing=HelmKnobRouting(a,s,sonar)
        try {
            val display=sonar.snapshot.display
            val longPress=routing.bind("SONAR").getValue(LEFT_LONG_PUSH)
            val modeId=longPress.submit(1,SystemClock.uptimeMillis());assertTrue(modeId>0)
            shadowOf(Looper.getMainLooper()).idle();assertEquals(SonarKnobMode.AUDIO,sonar.knobMode)
            assertEquals(CommandRing.State.APPLIED.ordinal,result(longPress,modeId).first);assertEquals(display,sonar.snapshot.display)
            val replacement=routing.bind("SONAR").getValue(LEFT_CW)
            assertEquals("The replacement callback can still report the completed SONAR mode command",CommandRing.State.APPLIED.ordinal,result(replacement,modeId).first)
            val reverse=routing.bind("SONAR").getValue(LEFT_LONG_PUSH)
            val reverseId=reverse.submit(2,SystemClock.uptimeMillis());assertTrue(reverseId>0)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals("Long press switches back from AUDIO without opening its fader panel",SonarKnobMode.SONAR,sonar.knobMode)
            assertEquals(CommandRing.State.APPLIED.ordinal,result(reverse,reverseId).first)
            assertNull(audio.panel);assertNull(source.state.value.pending)
            for(page in listOf("HELM","AUDIO","DASHBOARD")){
                val binding=routing.bind(page).getValue(LEFT_CCW)
                assertTrue(binding.submit(2,SystemClock.uptimeMillis())>=AudioControlActor.PREFIX)
                shadowOf(Looper.getMainLooper()).idle()
                assertTrue(source.state.value.pending!!.command is AudioCommand.Fader)
                source.completePending(source.state.value.pending!!.requestId,true)
                assertEquals(display,sonar.snapshot.display)
            }
            val old=routing.bind("SONAR").getValue(RIGHT_CW)
            assertTrue(routing.bind("SONAR",active=false).isEmpty());assertEquals(0L,old.submit(4,SystemClock.uptimeMillis()))
        }finally{routing.close();a.close();s.close();audio.dispose();sonar.dispose()}
    }

    @Test fun touchAndKnobSharePendingFaderTargetAndAnOutcomeWakesTheNextEffectWithoutATimer() {
        val (source, controller) = audio()
        val binding = AudioControlActor(controller)
        try {
            assertTrue(controller.changeLevel(AudioChannel.MASTER, -20.0))
            val touch = source.state.value.pending!!.requestId
            binding.observe()
            val rotate = binding.action(LEFT_CW)
            val superseded = rotate.submit(1, SystemClock.uptimeMillis())
            val final = rotate.submit(2, SystemClock.uptimeMillis())
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(AudioCommand.Fader(AudioChannel.MASTER, -20.0), source.state.value.pending!!.command)
            source.completePending(touch, true)
            // No 20-ms main-loop poll: the owning source outcome drives the shared control immediately.
            assertEquals(AudioCommand.Fader(AudioChannel.MASTER, -18.0), source.state.value.pending!!.command)
            assertEquals(CommandRing.State.SUPERSEDED.ordinal, result(rotate, superseded).first)
            source.completePending(source.state.value.pending!!.requestId, true)
            assertEquals(CommandRing.State.APPLIED.ordinal, result(rotate, final).first)
        } finally { binding.close(); controller.dispose() }
    }

    @Test fun repeatedTouchAndPhysicalFaderIntentsShareOneAccumulatorWhileOwnAckIsPending() {
        val (source, controller) = audio()
        val binding = AudioControlActor(controller)
        try {
            assertTrue(controller.changeLevel(AudioChannel.MASTER, -20.0))
            binding.observe()
            assertTrue(controller.canAdjust(controller.masterControl()!!))
            assertTrue(controller.adjustSelectedFader(2))
            val physical = binding.action(LEFT_CW)
            val physicalId = physical.submit(1, SystemClock.uptimeMillis())
            assertTrue(controller.changeLevel(AudioChannel.MASTER, -10.0))
            assertTrue(controller.adjustSelectedFader(-1))
            assertTrue(controller.transport(TransportAction.NEXT))
            assertTrue(controller.toggleSelectedMute())
            assertEquals(AudioCommand.Fader(AudioChannel.MASTER, -20.0), source.state.value.pending!!.command)
            source.completePending(source.state.value.pending!!.requestId, true)
            assertEquals(AudioCommand.Fader(AudioChannel.MASTER, -11.0), source.state.value.pending!!.command)
            assertEquals(CommandRing.State.SUPERSEDED.ordinal, result(physical, physicalId).first)
            source.completePending(source.state.value.pending!!.requestId, true)
            assertEquals(AudioCommand.Transport(TransportAction.NEXT), source.state.value.pending!!.command)
            source.completePending(source.state.value.pending!!.requestId, true)
            assertEquals(AudioCommand.Mute(AudioChannel.MASTER, true), source.state.value.pending!!.command)
            source.completePending(source.state.value.pending!!.requestId, true)
            shadowOf(Looper.getMainLooper()).idle()
            assertNull(source.state.value.pending)
            assertEquals(-11.0, source.state.value.dsp.faders[AudioChannel.MASTER]!!.db!!, 0.0)
        } finally { binding.close(); controller.dispose() }
    }

    @Test fun aNewTouchAbsoluteGoalSupersedesQueuedPhysicalGoalsBeforeAnyEffect() {
        val (source, controller) = audio()
        val binding = AudioControlActor(controller)
        try {
            binding.observe()
            val action = binding.action(LEFT_CW)
            val first = action.submit(1, SystemClock.uptimeMillis())
            repeat(4) { action.submit(it + 2L, SystemClock.uptimeMillis()) }
            assertNull(source.state.value.pending)
            assertTrue(controller.changeLevel(AudioChannel.MASTER, -12.0))
            assertEquals(AudioCommand.Fader(AudioChannel.MASTER, -12.0), source.state.value.pending!!.command)
            assertEquals(CommandRing.State.SUPERSEDED.ordinal, result(action, first).first)
            source.completePending(source.state.value.pending!!.requestId, true)
            shadowOf(Looper.getMainLooper()).idle()
            assertNull(source.state.value.pending)
            assertEquals(-12.0, controller.masterControl()!!.db!!, 0.0)
        } finally { binding.close(); controller.dispose() }
    }

    @Test fun selectionChangeCancelsTheOldControlsAndRestoredSelectionNeverReplaysACommand() {
        val (source, controller) = audio()
        val binding = AudioControlActor(controller)
        try {
            binding.observe(useSelectedFader = true)
            val action = binding.action(LEFT_CW)
            val id = action.submit(1, SystemClock.uptimeMillis())
            controller.selectKnobFader(controller.faderControl(AudioChannel.SUB)!!)
            shadowOf(Looper.getMainLooper()).idle()
            assertNull(source.state.value.pending)
            assertEquals(CommandRing.State.CANCELLED.ordinal, result(action, id).first)
            val saved = controller.exportPresentationState()
            controller.selectKnobFader(controller.masterControl()!!)
            controller.restorePresentationState(saved)
            assertEquals(AudioChannel.SUB, controller.selectedFader()!!.gatewayId)
            assertNull(source.state.value.pending)
        } finally { binding.close(); controller.dispose() }
    }

    @Test fun selectedFaderBindingDoesNotFallBackToMasterWhenSoundSetupCloses() {
        val (source, controller) = audio()
        val binding = AudioControlActor(controller)
        try {
            controller.openPanel("mixer")
            controller.selectKnobFader(controller.faderControl(AudioChannel.SUB)!!)
            controller.closePanel()
            binding.observe()
            binding.action(LEFT_CCW).submit(1, SystemClock.uptimeMillis())
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(AudioCommand.Fader(AudioChannel.SUB, -7.0), source.state.value.pending!!.command)
            assertEquals(-24.0, source.state.value.dsp.faders[AudioChannel.MASTER]!!.db!!, 0.0)
        } finally { binding.close(); controller.dispose() }
    }

    @Test fun bothPanelEntryPointsHonorTheSameDirtyGuardAndForcedCloseInvalidatesOldContinuations() {
        val (_, controller) = audio()
        val binding = AudioControlActor(controller)
        try {
            controller.openPanel("mixer")
            var confirmation: (() -> Unit)? = null
            var requests = 0
            controller.panelNavigationGuard = { proceed -> requests++; confirmation = proceed }
            // Touch and the physical binding both go through openPanel, with no input-side dialog policy.
            controller.openPanel("media")
            assertEquals("mixer", controller.panel); assertEquals(1, requests)
            confirmation!!.invoke()
            assertEquals("media", controller.panel)
            controller.closePanel(); controller.openPanel("mixer")
            binding.action(RIGHT_LONG_PUSH).submit(1, SystemClock.uptimeMillis())
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals("mixer", controller.panel); assertEquals(2, requests)
            controller.closePanel()
            confirmation!!.invoke()
            assertNull(controller.panel)
        } finally { binding.close(); controller.dispose() }
    }

    @Test fun expiredAcceptedAudioWorkDoesNotGainANewLifetimeWhenMainFinallyRuns() {
        val (source, controller) = audio()
        var uptime = 10L
        val controls = AudioOwnedControls(controller) { uptime }
        controls.prepareBindings()
        try {
            controls.observe()
            val id = controls.adjustSelected(1, 1, true)
            uptime += 2_000
            shadowOf(Looper.getMainLooper()).idle()
            assertNull(source.state.value.pending)
            val receipt = CommandRing.Entry()
            assertTrue(controls.diagnostic(0, receipt)); assertEquals(id - AudioControlActor.PREFIX, receipt.id)
            assertEquals(CommandRing.State.CANCELLED, receipt.state)
        } finally { controls.close(); controller.dispose() }
    }

    @Test fun blockedSourceGateCannotHoldTheBoundedAdmissionLock() {
        val original = MockAudioSource(now = { 1_000_000L }).also { it.loadFixture(AudioFixture.DEMO) }
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val block = java.util.concurrent.atomic.AtomicBoolean(true)
        val source = object : AudioSourcePort by original {
            override fun available(command: AudioCommand): Boolean {
                if (block.compareAndSet(true, false)) {
                    entered.countDown()
                    check(release.await(2, java.util.concurrent.TimeUnit.SECONDS))
                }
                return original.available(command)
            }
        }
        val controller = AudioNativeController(Dispatchers.Unconfined, { 1_000_000L }, false, false, source)
        val binding = AudioControlActor(controller)
        var worker: Thread? = null
        try {
            binding.observe()
            val action = binding.action(LEFT_CW)
            val first = action.submit(1, SystemClock.uptimeMillis())
            worker = kotlin.concurrent.thread { controller.controls.accepted(first) }
            assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS))
            val admitted = java.util.concurrent.CountDownLatch(1)
            var second = 0L
            val binder = kotlin.concurrent.thread {
                second = action.submit(2, SystemClock.uptimeMillis())
                admitted.countDown()
            }
            try {
                assertTrue("Admission must not wait for a source/transport lock", admitted.await(200, java.util.concurrent.TimeUnit.MILLISECONDS))
                assertTrue(second > first)
            } finally { release.countDown(); binder.join(2_000) }
        } finally {
            release.countDown(); worker?.join(2_000)
            binding.close(); controller.dispose()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    @Test fun touchGainSupersedesQueuedKnobGainAndNoDisplayActionChangesRawHistory() {
        val source = DeterministicMockSonarSource(now = { 1_000_000L })
        val controller = SonarNativeController(source) { 1_000_000L }
        controller.start()
        val binding = SonarControlActor(controller)
        val raw = controller.model.latest!!.samples
        try {
            val action = binding.action(RIGHT_CW)
            val id = action.submit(1, SystemClock.uptimeMillis())
            action.submit(2, SystemClock.uptimeMillis())
            controller.setGain(12.0)
            assertEquals(12.0, controller.snapshot.display.gainDb, 0.0)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(CommandRing.State.SUPERSEDED.ordinal, result(action, id).first)
            assertArrayEquals(raw, controller.model.latest!!.samples)
            assertTrue(controller.running)
        } finally { binding.close(); controller.dispose() }
    }

    @Test fun coldOfflineSaveRestartAndNewGeometryPreserveRangeAndOverlayIntentWithoutInventingDepth() {
        for (automatic in listOf(false, true)) {
            val wanted = SonarViewState(SonarDisplay(gainDb = 7.0, gamma = 1.7,
                rangeM = if (automatic) null else 5.0,
                rangeMode = if (automatic) SonarRangeMode.AUTO else SonarRangeMode.MANUAL,
                overlayBottom = true, overlayTargets = true, aScope = false))
            val cold = SonarNativeController(UnavailablePiSonarSource(SourceIdentity("cold-$automatic", SourceMode.PI, "TEST"))) { 1_000_000L }
            val saved = try {
                cold.restorePresentationState(wanted)
                cold.setGain(9.0)
                assertNull(cold.snapshot.depthM)
                assertNull(cold.snapshot.display.rangeM)
                assertEquals(SonarRangeMode.MANUAL, cold.snapshot.display.rangeMode)
                assertFalse(cold.snapshot.display.overlayBottom)
                assertFalse(cold.snapshot.display.overlayTargets)
                cold.exportPresentationState()
            } finally { cold.dispose() }
            assertEquals(wanted.display.rangeM, saved.display.rangeM)
            assertEquals(wanted.display.rangeMode, saved.display.rangeMode)
            assertTrue(saved.display.overlayBottom); assertTrue(saved.display.overlayTargets)
            assertEquals(9.0, saved.display.gainDb, 0.0)
            val firstSource = RestoreSource("geometry-first-$automatic")
            val restarted = SonarNativeController(firstSource) { 1_000_000L }
            val savedAgain = try {
                restarted.restorePresentationState(saved)
                // This is the immediate startup save that previously overwrote the pending preference.
                assertEquals(saved, restarted.exportPresentationState())
                assertNull(restarted.snapshot.depthM)
                firstSource.publish(30.0)
                assertEquals(wanted.display.rangeMode, restarted.snapshot.display.rangeMode)
                assertTrue(restarted.snapshot.display.overlayBottom)
                assertTrue(restarted.snapshot.display.overlayTargets)
                assertNotNull(restarted.snapshot.depthM)
                if (!automatic) assertEquals(5.0 / (30.0 / 1023), restarted.snapshot.display.cropEnd, 1e-9)
                firstSource.disconnect()
                assertNull(restarted.snapshot.depthM)
                assertFalse(restarted.snapshot.display.overlayBottom)
                val offlineSave = restarted.exportPresentationState()
                assertEquals(wanted.display.rangeM, offlineSave.display.rangeM)
                assertEquals(wanted.display.rangeMode, offlineSave.display.rangeMode)
                assertTrue(offlineSave.display.overlayBottom); assertTrue(offlineSave.display.overlayTargets)
                offlineSave
            } finally { restarted.dispose() }
            val nextSource = RestoreSource("geometry-next-$automatic")
            val next = SonarNativeController(nextSource) { 1_000_000L }
            try {
                next.restorePresentationState(savedAgain)
                assertEquals(savedAgain, next.exportPresentationState())
                assertNull(next.snapshot.depthM)
                nextSource.publish(60.0)
                assertEquals(wanted.display.rangeMode, next.snapshot.display.rangeMode)
                assertTrue(next.snapshot.display.overlayBottom); assertTrue(next.snapshot.display.overlayTargets)
                if (!automatic) assertEquals(5.0 / (60.0 / 1023), next.snapshot.display.cropEnd, 1e-9)
                assertEquals(0, firstSource.commands + nextSource.commands)
            } finally { next.dispose() }
        }
    }

    /** Explicit synthetic source. This proves preference/gating behavior, not hardware geometry. */
    private class RestoreSource(session: String) : SonarSourcePort {
        override val identity = SourceIdentity(session, SourceMode.PI, "TEST FIXTURE")
        override val status = MutableSourceFeed(SonarSourceStatus(identity))
        override val capabilities = MutableSourceFeed(SonarSourceCapabilities(identity))
        override val frames = MutableSourceFeed<SonarSourceFrame?>(null)
        var commands = 0
        fun publish(maxDepth: Double) {
            status.emit(SonarSourceStatus(identity, 1, connected = true, running = true))
            capabilities.emit(SonarSourceCapabilities(identity, bottomDetection = true, confirmedTracks = true))
            frames.emit(SonarSourceFrame(identity, 1, SonarFrame(ShortArray(1024) { 100 }, 1, 1_000_000L),
                bottom = SonarBottom(bin = 200.0, confidence = .9),
                geometry = SonarGeometry(maxDepth, SonarTxReference.HOST_VERIFIED)))
        }
        fun disconnect() { status.emit(SonarSourceStatus(identity, 2)) }
        override fun start(request: SonarAcquisitionRequest): SonarAcquisitionOutcome { commands++; return rejected(request) }
        override fun stop(request: SonarAcquisitionRequest): SonarAcquisitionOutcome { commands++; return rejected(request) }
        private fun rejected(request: SonarAcquisitionRequest) = SonarAcquisitionOutcome.Rejected(identity.sessionId,
            request.requestId, SonarRejection.UNSUPPORTED, "TEST FIXTURE")
        override fun tick() = Unit
        override fun close() { status.close(); capabilities.close(); frames.close() }
    }

    @Test fun sonarControlRingsAreBoundedAndPresentationRestoreDoesNotInventAcquisition() {
        val source = DeterministicMockSonarSource(now = { 1_000_000L })
        val controller = SonarNativeController(source) { 1_000_000L }
        controller.start()
        val binding = SonarControlActor(controller)
        var saved = SonarViewState()
        try {
            val action = binding.action(RIGHT_CW)
            val first = action.submit(1, SystemClock.uptimeMillis())
            repeat(1_000) { action.submit(it + 2L, SystemClock.uptimeMillis()) }
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(36.0, controller.snapshot.display.gainDb, 0.0)
            val old = Parcel.obtain()
            try { assertFalse(action.writeResult(first, old)) } finally { old.recycle() }
            val entry = CommandRing.Entry()
            assertTrue(controller.controls.diagnostic(63, entry))
            assertFalse(controller.controls.diagnostic(64, entry))
            controller.crop(256.0, 767.0); controller.toggleAScope(); controller.freeze()
            saved = controller.exportPresentationState()
        } finally { binding.close(); controller.dispose() }
        val replacement = SonarNativeController(UnavailablePiSonarSource(SourceIdentity("restored", SourceMode.PI, "PI"))) { 1_000_000L }
        try {
            replacement.restorePresentationState(saved)
            assertEquals(36.0, replacement.snapshot.display.gainDb, 0.0)
            assertEquals(256.0, replacement.snapshot.display.cropStart, 0.0)
            assertEquals(767.0, replacement.snapshot.display.cropEnd, 0.0)
            assertFalse(replacement.snapshot.display.freeze)
            assertEquals(0, replacement.snapshot.ringCount)
            assertNull(replacement.snapshot.depthM)
            assertNull(replacement.lastCommandOutcome)
            assertFalse(replacement.running)
        } finally { replacement.dispose() }
    }
}
