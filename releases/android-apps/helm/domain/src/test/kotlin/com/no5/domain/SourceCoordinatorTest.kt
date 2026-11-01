package com.no5.domain

import org.junit.Test
import kotlin.test.*

class SourceCoordinatorTest {
    private class Store(var config: SourceConfig = SourceConfig()) : SourceSettingsStore {
        var writes = 0
        var fail = false
        override fun load() = config
        override fun save(config: SourceConfig) { if (fail) error("Disk write failed"); writes++; this.config = config }
    }
    private fun coordinator(store: Store): SourceCoordinator {
        var id = 0
        return SourceCoordinator(store, DefaultSourceSessionFactory { 1_000_000L }) { "test-${++id}" }
    }
    @Test fun sameConfigAndNormalizedRepeatedSaveDoNotReconnect() {
        val store = Store(); val c = coordinator(store); val first = c.session
        repeat(5) { assertTrue(c.apply(c.config).applied); assertSame(first, c.session) }
        assertEquals(0, store.writes)
        assertTrue(c.apply(SourceConfig(SourceMode.PI, "https://pi.local:8443/")).changed)
        val pi = c.session
        assertTrue(c.apply(c.config).applied); assertSame(pi, c.session)
        assertEquals(1, store.writes); c.close()
    }
    @Test fun invalidEndpointDoesNotChangeConfigOrLiveSession() {
        val store=Store(); val c=coordinator(store); val first=c.session
        assertFalse(c.apply(SourceConfig(SourceMode.PI,"http://admin:secret@pi.local:8080")).applied)
        assertSame(first,c.session); assertEquals(SourceMode.DEMO,c.config.mode); assertEquals(0,store.writes)
        c.close()
    }
    @Test fun saveFailurePreservesPreviousSessionAndData() {
        val store=Store(); val c=coordinator(store); val first=c.session
        first.helm.demo!!.update(HelmInput.Rpm(EngineSide.PORT,1234.0))
        store.fail=true
        assertFalse(c.apply(SourceConfig(SourceMode.PI)).applied)
        assertSame(first,c.session); assertEquals(1234.0,c.session.helm.snapshots.value.portRpm)
        c.close()
    }
    @Test fun switchingClosesSubscriptionsInvalidatesCommandsAndNeverReplays() {
        val store=Store(); val c=coordinator(store); val old=c.session
        var helmEvents=0
        old.helm.snapshots.subscribe { helmEvents++ }
        old.audio.demoControls!!.start()
        val pending=old.audio.submit(AudioCommand.Fader(AudioChannel.MASTER,-18.0))
        assertEquals(AudioCommandStatus.PENDING,pending.status)
        assertTrue(c.apply(SourceConfig(SourceMode.PI)).changed)
        val pi=c.session
        assertNotEquals(old.identity.sessionId,pi.identity.sessionId)
        assertNull(pi.helm.demo); assertEquals(TelemetryStatus.UNAVAILABLE,pi.helm.snapshots.value.quality.status)
        assertFalse(pi.helm.snapshots.value.quality.demo)
        assertNull(pi.audio.demoControls); assertNull(pi.audio.state.value.pending)
        assertEquals(AudioCommandStatus.REJECTED,pi.audio.submit(AudioCommand.Fader(AudioChannel.MASTER,-18.0)).status)
        assertNull(pi.sonar.frames.value); assertFalse(pi.sonar.status.value.connected)
        val before=helmEvents
        old.helm.demo!!.update(HelmInput.Rpm(EngineSide.PORT,4000.0)); old.helm.advance(1000)
        assertEquals(before,helmEvents)
        assertFalse((old.audio as MockAudioSource).acknowledge(old.identity.sessionId,pending.requestId!!,true))
        assertTrue(c.apply(SourceConfig()).changed)
        assertNull(c.session.audio.state.value.pending)
        assertNotEquals(old.identity.sessionId,c.session.identity.sessionId)
        assertEquals(3400.0,c.session.helm.snapshots.value.portRpm)
        assertNull(c.session.sonar.frames.value)
        c.close()
    }
    @Test fun repeatedSwitchesHaveIsolatedSessionsAndClosedCoordinatorRefusesWrites() {
        val store=Store();val c=coordinator(store);val ids=mutableSetOf(c.session.identity.sessionId)
        repeat(8) { n ->
            val mode=if(n%2==0)SourceMode.PI else SourceMode.DEMO
            assertTrue(c.apply(SourceConfig(mode)).changed)
            assertTrue(ids.add(c.session.identity.sessionId))
        }
        assertEquals(8,store.writes);c.close();c.close()
        assertFalse(c.apply(SourceConfig(SourceMode.PI)).applied)
    }
    @Test fun retiringAdapterFailureStillPublishesThePersistedNewSession() {
        val store=Store();var count=0
        val factory=SourceSessionFactory { config, identity ->
            val delegate=DefaultSourceSessionFactory { 1000L }.create(config, identity)
            val failOnClose=count++==0
            object:No5SourceSession by delegate {
                override fun close() { delegate.close(); if(failOnClose) error("broken external close") }
            }
        }
        val c=SourceCoordinator(store,factory) { "session-${count}" }
        val old=c.session
        val result=c.apply(SourceConfig(SourceMode.PI))
        assertTrue(result.applied);assertTrue(result.changed);assertNotNull(result.warning)
        assertNotSame(old,c.session);assertEquals(store.config,c.config)
        assertEquals(SourceMode.PI,c.session.identity.mode)
        c.close()
    }
    @Test fun unitOnlySaveKeepsSessionFeedsPendingCommandsAndSonarHistory() {
        val store = Store()
        var created = 0
        val c = SourceCoordinator(store, SourceSessionFactory { config, identity ->
            created++
            DefaultSourceSessionFactory { 1_000_000L }.create(config, identity)
        }) { "unit-session-$created" }
        val session = c.session
        session.audio.demoControls!!.start()
        val pending = session.audio.submit(AudioCommand.Fader(AudioChannel.MASTER, -18.0))
        session.sonar.start(SonarAcquisitionRequest(session.identity.sessionId, 1))
        val frame = session.sonar.frames.value
        val requested = c.config.copy(displayUnits = DisplayUnits(SpeedUnit.KMH, ConsumptionUnit.KM_PER_L, DepthUnit.FEET))
        val result = c.apply(requested)
        assertTrue(result.applied); assertTrue(result.changed)
        assertSame(session, c.session); assertEquals(1, created)
        assertSame(frame, c.session.sonar.frames.value)
        assertEquals(pending.requestId, c.session.audio.state.value.pending?.requestId)
        assertEquals(requested, c.config); assertEquals(requested, store.config)
        repeat(5) { assertTrue(c.apply(requested).applied); assertFalse(c.apply(requested).changed) }
        assertEquals(1, store.writes); assertEquals(1, created)
        c.close()
    }

    @Test fun unitOnlySaveFailureKeepsOriginalConfigAndSession() {
        val store = Store(SourceConfig(mode = SourceMode.PI))
        val c = coordinator(store)
        val previousConfig = c.config; val previousSession = c.session
        store.fail = true
        val result = c.apply(c.config.copy(displayUnits = DisplayUnits(depth = DepthUnit.FEET)))
        assertFalse(result.applied); assertNotNull(result.error)
        assertEquals(previousConfig, c.config); assertSame(previousSession, c.session)
        assertEquals(0, store.writes)
        c.close()
    }

    @Test fun explicitReloadRetiresTheOldSessionBeforeItBuildsExactlyOneReplacementWithoutSaving() {
        val store = Store(SourceConfig(mode = SourceMode.PI))
        val events = mutableListOf<String>()
        val closed = mutableListOf<Boolean>()
        var created = 0
        val factory = SourceSessionFactory { config, identity ->
            if (created > 0) assertTrue(closed.first(), "replacement must not overlap old pollers or WebSockets")
            val ordinal = created++
            val delegate = DefaultSourceSessionFactory { 1_000L }.create(config, identity)
            object : No5SourceSession by delegate {
                override fun close() {
                    if (!closed[ordinal]) {
                        closed[ordinal] = true
                        events += "closed-$ordinal"
                    }
                    delegate.close()
                }
            }.also { closed += false; events += "created-$ordinal" }
        }
        val coordinator = SourceCoordinator(store, factory) { "reload-$created" }
        val initial = coordinator.session

        val result = coordinator.reload()

        assertTrue(result.applied)
        assertTrue(result.changed)
        assertNotSame(initial, coordinator.session)
        assertEquals(0, store.writes)
        assertEquals(listOf("created-0", "closed-0", "created-1"), events)
        coordinator.close()
    }

    @Test fun failedReloadLeavesNoOldLiveSessionAndCanBeRetriedWithoutPersisting() {
        val store = Store(SourceConfig(mode = SourceMode.PI))
        var created = 0
        var oldClosed = false
        val factory = SourceSessionFactory { config, identity ->
            created++
            if (created == 2) error("replacement unavailable")
            val delegate = DefaultSourceSessionFactory { 1_000L }.create(config, identity)
            object : No5SourceSession by delegate {
                override fun close() { oldClosed = true; delegate.close() }
            }
        }
        val coordinator = SourceCoordinator(store, factory) { "retry-$created" }

        val failed = coordinator.reload()
        assertFalse(failed.applied)
        assertTrue(oldClosed)
        assertEquals(0, store.writes)

        val retried = coordinator.reload()
        assertTrue(retried.applied)
        assertEquals(0, store.writes)
        coordinator.close()
    }

}
