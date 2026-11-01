package com.no5.helm.network

import com.no5.domain.*
import org.json.JSONObject
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.*

private class ManualScheduler : PiPollScheduler {
    lateinit var poll: () -> Unit
    var closed = false
    var intervalMs = 0L
    override fun schedule(intervalMs: Long, poll: () -> Unit): SourceSubscription {
        this.poll = poll
        this.intervalMs = intervalMs
        return SourceSubscription { closed = true }
    }
}

private class FakeTransport(var body: String) : PiHttpTransport {
    var fail = false
    var closed = false
    var reads = 0
    var onRead: (() -> Unit)? = null
    override fun get(path: String): String {
        reads++
        onRead?.invoke()
        if (fail) throw IOException("Disconnected")
        return body
    }
    override fun close() { closed = true }
}

class PiNetworkSourcesTest {
    private val identity = SourceIdentity("network-session", SourceMode.PI, "PI TEST")
    private val stamp = JSONObject(wireFixture("helm")).getLong("timestampEpochMs")

    @Test fun backgroundUiDemandLimitsHelmReadsAndInstrumentDemandRestoresFullCadence() {
        var now = stamp
        val timer = ManualScheduler()
        val transport = FakeTransport(wireFixture("helm"))
        val source = NetworkPiHelmSource(identity, transport, { now }, timer)
        source.setUiDemand(false)
        timer.poll()
        repeat(10) { timer.poll() }
        assertEquals(1, transport.reads)
        now += 999
        timer.poll()
        assertEquals(1, transport.reads)
        now += 1
        timer.poll()
        assertEquals(2, transport.reads)
        source.setUiDemand(true)
        timer.poll()
        assertEquals(3, transport.reads)
        source.close()
    }

    @Test fun identicalPollsAndFailuresDoNotNotifyButAgeTransitionsDo() {
        var now = stamp
        val timer = ManualScheduler()
        val transport = FakeTransport(wireFixture("helm"))
        val source = NetworkPiHelmSource(identity, transport, { now }, timer)
        val statuses = mutableListOf<TelemetryStatus>()
        source.snapshots.subscribe { statuses += it.quality.status }
        timer.poll()
        repeat(5) { timer.poll() }
        assertEquals(listOf(TelemetryStatus.UNAVAILABLE, TelemetryStatus.LIVE), statuses)
        now = stamp + 6_000
        timer.poll()
        repeat(5) { timer.poll() }
        assertEquals(TelemetryStatus.STALE, statuses.last())
        assertEquals(3, statuses.size)
        now = stamp + 31_000
        timer.poll()
        repeat(5) { timer.poll() }
        assertEquals(TelemetryStatus.LOST, statuses.last())
        assertEquals(4, statuses.size)
        transport.fail = true
        repeat(5) { timer.poll() }
        assertEquals(4, statuses.size)
        transport.fail = false
        transport.body = "{broken"
        repeat(5) { timer.poll() }
        assertEquals(TelemetryStatus.INVALID, statuses.last())
        assertEquals(5, statuses.size)
        source.close()
    }

    @Test fun equalNumbersWithNewObservationOrPublisherStillNotifyAndReachHistory() {
        val timer = ManualScheduler()
        val root = JSONObject(wireFixture("helm"))
        val transport = FakeTransport(root.toString())
        val source = NetworkPiHelmSource(identity, transport, { stamp + 100 }, timer)
        val snapshots = mutableListOf<HelmState>()
        val history = DepthHistory()
        source.snapshots.subscribe {
            snapshots += it
            history.accept(it, stamp + 100, identity.sessionId)
        }
        timer.poll()
        root.put("publishedAtEpochMs", stamp + 1)
        transport.body = root.toString()
        timer.poll()
        assertEquals(3, snapshots.size)
        assertEquals(stamp + 1, snapshots.last().quality.timestampEpochMs)
        root.getJSONObject("fieldQuality").getJSONObject("depth").put("timestampEpochMs", stamp + 1)
        transport.body = root.toString()
        timer.poll()
        assertEquals(4, snapshots.size)
        assertEquals(listOf(stamp, stamp + 1), history.snapshot(stamp + 100).points.map { it.timestampEpochMs })
        root.put("serverSessionId", "replacement-publisher")
        transport.body = root.toString()
        timer.poll()
        assertEquals(5, snapshots.size)
        assertEquals("replacement-publisher", snapshots.last().sourceSessionId)
        assertEquals(1, history.snapshot(stamp + 100).points.size)
        source.close()
    }

    @Test fun duplicatePublicationStillRunsAcceptanceChecksAndNewSubscribersGetCurrentValue() {
        val timer = ManualScheduler()
        val transport = FakeTransport("unused")
        var accepted = 0
        val polling = PiPollingFeed(
            initial = 0L, path = "/test", transport = transport, scheduler = timer,
            policy = PiNetworkPolicy.HELM, now = { stamp }, decode = { stamp },
            timestamp = { it }, age = { it }, onAccepted = { accepted++ },
            failure = { _, _ -> -1L },
        )
        val values = mutableListOf<Long>()
        polling.snapshots.subscribe { values += it }
        repeat(5) { timer.poll() }
        assertEquals(5, accepted)
        assertEquals(listOf(0L, stamp), values)
        val lateValues = mutableListOf<Long>()
        polling.snapshots.subscribe { lateValues += it }
        assertEquals(listOf(stamp), lateValues)
        polling.close()
    }

    @Test fun disconnectReconnectAndDuplicateSampleNeverResetSourceFreshness() {
        var now = stamp
        val timer = ManualScheduler()
        val transport = FakeTransport(wireFixture("helm"))
        val source = NetworkPiHelmSource(identity, transport, { now }, timer)
        assertEquals(TelemetryStatus.UNAVAILABLE, source.snapshots.value.quality.status)
        assertNull(source.demo)
        timer.poll()
        assertEquals(TelemetryStatus.LIVE, source.snapshots.value.quality.status)
        transport.fail = true
        timer.poll()
        assertEquals(TelemetryStatus.LOST, source.snapshots.value.quality.status)
        assertNull(source.snapshots.value.latitudeDegrees)
        transport.fail = false
        now += 6_000
        timer.poll()
        assertEquals(TelemetryStatus.STALE, source.snapshots.value.quality.status)
        assertEquals(stamp, source.snapshots.value.quality.timestampEpochMs)
        now = stamp + 31_000
        timer.poll()
        assertEquals(TelemetryStatus.LOST, source.snapshots.value.quality.status)
        assertNull(source.snapshots.value.latitudeDegrees)
        transport.body = JSONObject(wireFixture("helm")).put("publishedAtEpochMs", now).toString()
        timer.poll()
        assertEquals(TelemetryStatus.LIVE, source.snapshots.value.quality.status)
        transport.fail = true
        timer.poll()
        assertEquals(TelemetryStatus.LOST, source.snapshots.value.quality.status)
        transport.fail = false
        transport.body = wireFixture("helm")
        timer.poll()
        assertEquals(TelemetryStatus.INVALID, source.snapshots.value.quality.status)
        source.close()
        assertTrue(timer.closed && transport.closed)
    }

    @Test fun badPayloadClearsPreviouslyAcceptedNumbersAndThenRecovers() {
        val timer = ManualScheduler()
        val transport = FakeTransport(wireFixture("helm"))
        val source = NetworkPiHelmSource(identity, transport, { stamp }, timer)
        timer.poll()
        assertNotNull(source.snapshots.value.fuelRemainingLiters)
        transport.body = "{broken"
        timer.poll()
        assertEquals(TelemetryStatus.INVALID, source.snapshots.value.quality.status)
        assertNull(source.snapshots.value.fuelRemainingLiters)
        transport.body = wireFixture("helm")
        timer.poll()
        assertEquals(TelemetryStatus.LIVE, source.snapshots.value.quality.status)
        source.close()
    }

    @Test fun futurePacketCannotPoisonRecoveryOrOrderingWatermark() {
        val timer = ManualScheduler()
        val transport = FakeTransport(JSONObject(wireFixture("helm")).put("publishedAtEpochMs", stamp + 86_400_000).toString())
        val source = NetworkPiHelmSource(identity, transport, { stamp }, timer)
        timer.poll()
        assertEquals(TelemetryStatus.INVALID, source.snapshots.value.quality.status)
        transport.body = JSONObject(wireFixture("helm")).put("publishedAtEpochMs", stamp + 1_000).toString()
        timer.poll()
        assertEquals(TelemetryStatus.LIVE, source.snapshots.value.quality.status)
        transport.body = wireFixture("helm")
        timer.poll()
        assertEquals(TelemetryStatus.LIVE, source.snapshots.value.quality.status)
        assertEquals(stamp, source.snapshots.value.quality.timestampEpochMs)
        source.close()
    }

    @Test fun schedulerFailureClosesTransportBeforeFactoryLosesCandidate() {
        val transport = FakeTransport(wireFixture("helm"))
        assertFailsWith<IllegalStateException> {
            NetworkPiHelmSource(identity, transport, { stamp }, PiPollScheduler { _, _ -> error("Cannot schedule") })
        }
        assertTrue(transport.closed)
    }

    @Test fun closePreventsInFlightReplyAndAnyLaterCallback() {
        val timer = ManualScheduler()
        val entered = CountDownLatch(1)
        val released = CountDownLatch(1)
        val transport = FakeTransport(wireFixture("helm")).apply {
            onRead = { entered.countDown(); check(released.await(2, TimeUnit.SECONDS)) }
        }
        val source = NetworkPiHelmSource(identity, transport, { stamp }, timer)
        val statuses = mutableListOf<TelemetryStatus>()
        source.snapshots.subscribe { statuses += it.quality.status }
        val worker = thread { timer.poll() }
        assertTrue(entered.await(1, TimeUnit.SECONDS))
        source.close()
        source.close()
        released.countDown()
        worker.join(2_000)
        assertFalse(worker.isAlive)
        timer.poll()
        assertEquals(listOf(TelemetryStatus.UNAVAILABLE), statuses)
        assertEquals(TelemetryStatus.UNAVAILABLE, source.snapshots.value.quality.status)
    }

    @Test fun failedWeatherDoesNotChangeHelmAndUsesItsOwnSourceClock() {
        val helmTimer = ManualScheduler()
        val weatherTimer = ManualScheduler()
        val weatherTransport = FakeTransport(wireFixture("weather"))
        val helm = NetworkPiHelmSource(identity, FakeTransport(wireFixture("helm")), { stamp }, helmTimer)
        val weather = NetworkPiWeatherSource(identity, weatherTransport, { stamp }, weatherTimer)
        assertEquals(50L, helmTimer.intervalMs)
        assertEquals(2_000L, weatherTimer.intervalMs)
        helmTimer.poll(); weatherTimer.poll()
        weatherTransport.fail = true
        weatherTimer.poll()
        assertEquals(TelemetryStatus.LOST, weather.snapshots.value.status)
        assertNull(weather.snapshots.value.values.airTemperatureC)
        assertEquals(TelemetryStatus.LIVE, helm.snapshots.value.quality.status)
        helm.close(); weather.close()
    }

    @Test fun oneFailingSubscriberCannotPermanentlyStopPolling() {
        val timer = ManualScheduler()
        val transport = FakeTransport(wireFixture("helm"))
        val source = NetworkPiHelmSource(identity, transport, { stamp }, timer)
        source.snapshots.subscribe { if (it.quality.status == TelemetryStatus.LIVE) error("Observer failure") }
        timer.poll()
        transport.fail = true
        timer.poll()
        assertEquals(TelemetryStatus.LOST, source.snapshots.value.quality.status)
        source.close()
    }

    @Test fun disabledRestoredConnectionCreatesNoNetworkWork() {
        var transports = 0
        var schedules = 0
        val factory = NetworkSourceSessionFactory({ stamp }, { _, _ -> transports++; FakeTransport(wireFixture("helm")) }, { schedules++; ManualScheduler() })
        val session = factory.create(SourceConfig(mode = SourceMode.PI, connectionEnabled = false, weatherProvider = WeatherProviderMode.OPEN_METEO, allowWeatherLocationSharing = true), identity)
        assertEquals(0, transports)
        assertEquals(0, schedules)
        assertEquals(TelemetryStatus.UNAVAILABLE, session.helm.snapshots.value.quality.status)
        assertEquals(TelemetryStatus.UNAVAILABLE, session.weather.snapshots.value.status)
        session.close()
    }

    @Test fun factoryOwnsIndependentPiPortsAndClosesMediaTransportsToo() {
        val transports = mutableListOf<FakeTransport>()
        val schedules = mutableListOf<ManualScheduler>()
        val factory = NetworkSourceSessionFactory({ stamp }, { _, _ -> FakeTransport(wireFixture("helm")).also { transports += it } }, { ManualScheduler().also { schedules += it } })
        val session = factory.create(SourceConfig(mode = SourceMode.PI), identity)
        assertEquals(8, transports.size)
        assertEquals(8, schedules.size)
        assertSame(identity, session.helm.identity)
        assertSame(identity, session.weather.identity)
        assertSame(identity, session.audio.identity)
        assertSame(identity, session.sonar.identity)
        assertNotNull(session.audio.mediaBrowser)
        assertNotNull(session.audio.sound)
        session.close(); session.close()
        assertTrue(transports.all { it.closed } && schedules.all { it.closed })
    }

    @Test fun openMeteoCannotStartWithoutLocationSharingPermission() {
        var transports = 0
        var schedules = 0
        val factory = NetworkSourceSessionFactory({ stamp }, { _, _ -> transports++; FakeTransport(wireFixture("helm")) }, { schedules++; ManualScheduler() })
        val session = factory.create(SourceConfig(mode = SourceMode.PI, weatherProvider = WeatherProviderMode.OPEN_METEO, allowWeatherLocationSharing = false), identity)
        assertEquals(7, transports)
        assertEquals(7, schedules)
        assertEquals(TelemetryStatus.UNAVAILABLE, session.weather.snapshots.value.status)
        session.close()
    }
}
