package com.no5.helm

import android.os.Handler
import android.os.Looper
import com.no5.domain.*
import com.no5.gateway.*
import com.no5.helm.control.HelmSourceOwner
import com.no5.helm.control.OwnerFixtureFactory
import com.no5.helm.control.OwnerFixtureStore
import com.no5.helm.network.NetworkSourceSessionFactory
import com.no5.helm.security.GatewaySecurityProfile
import com.no5.helm.security.GatewaySecurityProfileProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertFailsWith

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
@LooperMode(LooperMode.Mode.PAUSED)
class HomeGatewayStatusReaderTest {
    private val record = GatewayConfiguration(GatewayOrigin.parse("https://no5-marineos-lab.local:9443")!!,
        "192.168.68.88", GatewayAddressSource.MANUAL, checkedAt = 1)
    private val ready = HomeGatewayStatus(true, record, 1, true, true)
    private class Worker : HomeGatewayStatusReader.Worker {
        val tasks = mutableListOf<Runnable>()
        var reject = false
        var cancels = 0
        override fun execute(task: Runnable): HomeGatewayStatusReader.Cancellation? {
            if (reject) return null
            tasks.add(task)
            // Keep captured tasks runnable: model a Binder that already started and ignores cancellation.
            return HomeGatewayStatusReader.Cancellation { cancels++ }
        }
        fun run(index: Int = tasks.lastIndex) {
            val failure = AtomicReference<Throwable?>()
            val thread = Thread { try { tasks[index].run() } catch (t: Throwable) { failure.set(t) } }
            thread.start(); thread.join(3000)
            check(!thread.isAlive) { "Reader fixture worker failed to finish" }
            failure.get()?.let { throw AssertionError("Reader fixture failed", it) }
        }
    }
    private fun profile() = homeGatewayReaderFixtureProfile(record.origin.url)
    private fun usable(profile: GatewaySecurityProfile) {
        profile.configureHttps("${record.origin.url}/v1/helm", {}, { _, _ -> })
    }
    private fun reader(worker: Worker, read: () -> HomeGatewayStatus = { ready },
        load: () -> GatewaySecurityProfile? = { profile() }, offer: (String) -> Unit = {}): HomeGatewayStatusReader =
        // Keep the original exact 250ms boundary explicitly; product cold reads now have 5s.
        HomeGatewayStatusReader(read, offer, GatewaySecurityProfileProvider { load() }, Handler(Looper.getMainLooper()), worker, deadlineMs = 250)
    private fun deliver() { shadowOf(Looper.getMainLooper()).idle() }

    @Test fun initialUnavailableNeverWaitsForProviderAndMainRemainsRunnable() {
        val worker = Worker(); var calls = 0; var changes = 0
        reader(worker, read = { calls++; ready }, offer = {
            assertNotSame(Looper.getMainLooper().thread, Thread.currentThread()); calls++
        }).use { reader ->
            reader.observe { changes++ }
            assertFailsWith<HomeGatewayUnavailable> { reader.cachedStatus() }
            reader.refresh(record.origin.url)
            assertEquals(0, calls)
            var mainRan = false; Handler(Looper.getMainLooper()).post { mainRan = true }; deliver()
            assertTrue(mainRan); assertEquals(0, calls)
            worker.run(); deliver()
            assertEquals(3, calls); assertEquals(1, changes); assertEquals(ready, reader.cachedStatus())
        }
    }
    @Test fun snapshotAndEachSessionOwnIndependentZeroizableProfiles() {
        val worker = Worker(); lateinit var base: GatewaySecurityProfile
        val reader = reader(worker, load = { profile().also { base = it } })
        reader.refresh(record.origin.url); worker.run(); deliver()
        val first = requireNotNull(reader.cachedProfiles.load(record.origin.url))
        val second = requireNotNull(reader.cachedProfiles.load(record.origin.url))
        assertNotSame(base, first); assertNotSame(first, second)
        first.close(); assertFailsWith<IllegalStateException> { usable(first) }; usable(base); usable(second)
        reader.close(); assertFailsWith<IllegalStateException> { usable(base) }; usable(second)
        assertNull(reader.cachedProfiles.load(record.origin.url)); second.close()
    }
    @Test fun supersededQueuedResultCannotReplaceNewSnapshotAndOldProfileCloses() {
        val worker = Worker(); var status = ready; val bases = mutableListOf<GatewaySecurityProfile>()
        reader(worker, read = { status }, load = { profile().also(bases::add) }).use { reader ->
            reader.refresh(record.origin.url); worker.run(0) // old result posted, not yet delivered
            status = ready.copy(securityRevision = 2)
            reader.refresh(record.origin.url); worker.run(1); deliver()
            assertEquals(2L, reader.cachedStatus().securityRevision)
            assertFailsWith<IllegalStateException> { usable(bases[0]) }; usable(bases[1])
        }
    }
    @Test fun timeoutRetiresGenerationEvenWhenOriginalBinderReturnsLater() {
        val worker = Worker(); var changes = 0; lateinit var late: GatewaySecurityProfile
        reader(worker, load = { profile().also { late = it } }).use { reader ->
            reader.observe { changes++ }; reader.refresh(record.origin.url)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250))
            assertEquals(1, changes); assertFailsWith<HomeGatewayUnavailable> { reader.cachedStatus() }
            worker.run(0); deliver()
            assertEquals(1, changes); assertFailsWith<IllegalStateException> { usable(late) }
            reader.refresh(record.origin.url); worker.run(1); deliver()
            assertEquals(2, changes); assertEquals(ready, reader.cachedStatus())
        }
    }
    @Test fun closeRejectsLateReadAndNeverCallsRetiredListener() {
        val worker = Worker(); var changes = 0; lateinit var late: GatewaySecurityProfile
        val reader = reader(worker, load = { profile().also { late = it } })
        reader.observe { changes++ }; reader.refresh(record.origin.url); reader.close()
        worker.run(); deliver()
        assertEquals(0, changes); assertFailsWith<IllegalStateException> { usable(late) }
        assertFailsWith<HomeGatewayUnavailable> { reader.cachedStatus() }
    }
    @Test fun deliveryChecksMonotoneDeadlineBeforeMainTimeoutRunnableRuns() {
        val worker = Worker(); var uptime = 100L; var changes = 0; lateinit var late: GatewaySecurityProfile
        val reader = HomeGatewayStatusReader({ ready }, {}, GatewaySecurityProfileProvider {
            profile().also { late = it }
        }, Handler(Looper.getMainLooper()), worker, uptime = { uptime }, deadlineMs = 250)
        reader.use {
            reader.observe { changes++ }; reader.refresh(record.origin.url)
            worker.run() // ready result posted before the Handler's delayed timeout
            uptime += 250
            deliver() // Main delivers the ready result after its captured monotone deadline
            assertEquals(1, changes); assertFailsWith<HomeGatewayUnavailable> { reader.cachedStatus() }
            assertFailsWith<IllegalStateException> { usable(late) }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1)); assertEquals(1, changes)
        }
    }
    @Test fun inconsistentStatusAroundProfileFailsClosedAndDisposesCandidate() {
        val worker = Worker(); var reads = 0; lateinit var rejected: GatewaySecurityProfile
        reader(worker, read = { ready.copy(securityRevision = (++reads).toLong()) },
            load = { profile().also { rejected = it } }).use { reader ->
            reader.refresh(record.origin.url); worker.run(); deliver()
            assertFailsWith<HomeGatewayUnavailable> { reader.cachedStatus() }
            assertFailsWith<IllegalStateException> { usable(rejected) }
        }
    }
    @Test fun missingProfileAndRejectedWorkerBothRemainUnavailable() {
        val worker = Worker()
        reader(worker, load = { null }).use { reader ->
            reader.refresh(record.origin.url); worker.run(); deliver()
            assertFailsWith<HomeGatewayUnavailable> { reader.cachedStatus() }
        }
        worker.reject = true
        reader(worker).use { reader ->
            var changes = 0; reader.observe { changes++ }; reader.refresh(record.origin.url)
            assertEquals(1, changes); assertFailsWith<HomeGatewayUnavailable> { reader.cachedStatus() }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1)); assertEquals(1, changes)
        }
    }
    @Test fun providerExceptionCannotEscapeToMainOrPublishReadyStatus() {
        val worker = Worker()
        reader(worker, read = { throw HomeGatewayUnavailable() }).use { reader ->
            reader.refresh(record.origin.url); worker.run(); deliver()
            assertFailsWith<HomeGatewayUnavailable> { reader.cachedStatus() }
        }
    }
    @Test fun ownerStartsDisabledUsesOneFactoryAndClosedOwnerRejectsLateRevision() {
        val worker = Worker(); var status = ready
        val reader = reader(worker, read = { status })
        val store = HomeSourceSettingsStore(OwnerFixtureStore(), reader::cachedStatus).also { it.attachReader(reader) }
        val factory = OwnerFixtureFactory(); val owner = HelmSourceOwner(store, factory)
        assertFalse(owner.config.connectionEnabled); assertEquals(1, factory.creates)
        worker.run(0); deliver()
        assertTrue(owner.config.connectionEnabled); assertEquals(2, factory.creates)
        val first = owner.sessionGeneration
        status = ready.copy(securityRevision = 2)
        owner.synchronizeHome(); worker.run(1); deliver()
        assertTrue(owner.sessionGeneration > first); assertEquals(3, factory.creates)
        status = ready.copy(securityRevision = 3)
        owner.synchronizeHome(); owner.close(); worker.run(2); deliver()
        assertTrue(owner.isDisposed); assertEquals(3, factory.creates); assertEquals(1, factory.factoryCloses)
    }
    @Test fun stalledRefreshRetiresAlreadyActiveSourceWithoutWaitingForProviderSave() {
        val worker = Worker(); val reader = reader(worker)
        val local = OwnerFixtureStore()
        val store = HomeSourceSettingsStore(local, reader::cachedStatus).also { it.attachReader(reader) }
        val factory = OwnerFixtureFactory(); val owner = HelmSourceOwner(store, factory)
        try {
            worker.run(0); deliver(); assertTrue(owner.config.connectionEnabled)
            val activeGeneration = owner.sessionGeneration
            val old = owner.borrowSession()
            owner.synchronizeHome()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250))
            assertFalse(owner.config.connectionEnabled); assertTrue(owner.sessionGeneration > activeGeneration)
            assertFalse(old.audio.submit(AudioCommand.Transport(TransportAction.PLAY)).accepted)
            val creates = factory.creates
            worker.run(1); deliver()
            assertFalse(owner.config.connectionEnabled); assertEquals(creates, factory.creates)
            assertTrue(local.saved.connectionEnabled) // temporary Home failure is never persisted as a permanent disable
        } finally { owner.close() }
    }

    @Test fun deliberateOwnerRefreshCannotBypassMissingKeyCaOrInvalidProfile() {
        // Exercise the product factory: selecting PI/refreshing Home is not a connected source.
        for ((label, status) in listOf(
            "missing key" to ready.copy(keyPresent = false),
            "missing CA" to ready.copy(caPresent = false),
            "invalid profile" to ready,
        )) {
            val worker = Worker()
            var profileLoads = 0
            var transportStarts = 0
            var schedulerStarts = 0
            val reader = reader(worker, read = { status }, load = {
                profileLoads++
                // Actual profile validation, with deliberately invalid synthetic material.
                GatewaySecurityProfile.create(
                    com.no5.helm.security.GatewayOrigin.fromEndpoint(record.origin.url),
                    "synthetic-invalid-profile-key".toByteArray(), byteArrayOf(1, 2, 3))
            })
            val local = OwnerFixtureStore()
            val store = HomeSourceSettingsStore(local, reader::cachedStatus).also { it.attachReader(reader) }
            val factory = NetworkSourceSessionFactory(
                securityProfiles = reader.cachedProfiles,
                transport = { _, _ -> transportStarts++; error("Unexpected unprotected transport") },
                securedTransport = { _, _, _ -> transportStarts++; error("Unexpected protected transport") },
                scheduler = { _ -> schedulerStarts++; error("Unexpected polling worker") },
            )
            val owner = HelmSourceOwner(store, factory)
            try {
                assertFalse(label, owner.config.connectionEnabled)
                val identity = status.identity
                // The immediate return concerns cached reconciliation, not the async read result.
                assertTrue(owner.synchronizeHome())
                assertEquals(label, 1, worker.tasks.size) // manual and initial observation coalesce
                worker.run(0); deliver()
                assertEquals(label, identity, status.identity)
                assertFalse(label, owner.config.connectionEnabled)
                val session = owner.borrowSession()
                try {
                    assertEquals(label, TelemetryStatus.UNAVAILABLE, session.helm.snapshots.value.quality.status)
                    assertNull(label, session.audio.demoControls)
                    assertFalse(label, session.audio.available(AudioCommand.Transport(TransportAction.PLAY)))
                } finally { session.close() }
                assertEquals(label, if (label == "invalid profile") 1 else 0, profileLoads)
                assertEquals(label, 0, transportStarts)
                assertEquals(label, 0, schedulerStarts)
            } finally { owner.close() }
        }
    }

    @Test fun defaultColdReadAccepts900msButExact5000msDeadlineDisposesProfile() {
        for (elapsed in listOf(900L, 5_000L)) {
            val worker = Worker(); var now = 100L; lateinit var material: GatewaySecurityProfile
            val reader = HomeGatewayStatusReader({ ready }, {}, GatewaySecurityProfileProvider {
                profile().also { material = it; now += elapsed }
            }, Handler(Looper.getMainLooper()), worker, uptime = { now })
            reader.use {
                reader.refresh(record.origin.url); worker.run(); deliver()
                assertEquals(5_100L, reader.diagnostic.deadlineUptimeMs)
                assertEquals(now, reader.diagnostic.finishedUptimeMs)
                if (elapsed == 900L) {
                    assertEquals(ready, reader.cachedStatus()); usable(material)
                    assertEquals(HomeGatewayReadReason.READY, reader.diagnostic.reason)
                } else {
                    assertFailsWith<HomeGatewayUnavailable> { reader.cachedStatus() }
                    assertFailsWith<IllegalStateException> { usable(material) }
                    assertEquals(HomeGatewayReadReason.TIMEOUT, reader.diagnostic.reason)
                }
            }
        }
    }

    @Test fun clockRollbackBeforeDeliveryDisposesCandidateAndRecordsOnlyFixedReason() {
        val worker = Worker(); var now = 100L; lateinit var material: GatewaySecurityProfile
        val reader = HomeGatewayStatusReader({ ready }, {}, GatewaySecurityProfileProvider {
            profile().also { material = it }
        }, Handler(Looper.getMainLooper()), worker, uptime = { now })
        reader.use {
            reader.refresh(record.origin.url); worker.run(); now = 99; deliver()
            assertFailsWith<HomeGatewayUnavailable> { reader.cachedStatus() }
            assertFailsWith<IllegalStateException> { usable(material) }
            assertEquals(HomeGatewayReadReason.CLOCK_ROLLBACK, reader.diagnostic.reason)
        }
    }

    @Test fun autoRetryWithoutUiAcceptsOnceAndHealthyManualRefreshKeepsGenerationAndFeed() {
        val worker = Worker(); var unavailable = true
        val reader = reader(worker, read = { if (unavailable) throw HomeGatewayUnavailable() else ready })
        val store = HomeSourceSettingsStore(OwnerFixtureStore(), reader::cachedStatus).also { it.attachReader(reader) }
        val factory = OwnerFixtureFactory(); val owner = HelmSourceOwner(store, factory)
        var emissions = 0; val subscription = owner.changes.subscribe { emissions++ }
        try {
            worker.run(0); deliver()
            assertFalse(owner.config.connectionEnabled); assertEquals(1, factory.creates)
            assertNotNull(owner.recoveryStatus.nextRetryUptimeMs)
            val afterFailure = emissions
            unavailable = false
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(2_000))
            assertEquals(2, worker.tasks.size)
            worker.run(1); deliver()
            assertTrue(owner.config.connectionEnabled); assertTrue(owner.recoveryStatus.sourceAccepted)
            assertEquals(2, factory.creates); assertEquals(afterFailure + 1, emissions)
            assertNull(owner.recoveryStatus.nextRetryUptimeMs)
            val generation = owner.sessionGeneration; val before = emissions
            repeat(20) { owner.synchronizeHome() }
            assertEquals(3, worker.tasks.size) // all requests share one in-flight Binder job
            assertEquals(generation, owner.sessionGeneration); assertEquals(before, emissions)
            worker.run(2); deliver()
            assertEquals(2, factory.creates); assertEquals(generation, owner.sessionGeneration)
            assertEquals(before, emissions); assertNull(owner.recoveryStatus.nextRetryUptimeMs)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(31))
            assertEquals(3, worker.tasks.size)
        } finally { subscription.close(); owner.close() }
    }

    @Test fun timedOutOwnerRetriesThroughOneBoundedWorkerAndCloseCancelsTimer() {
        val worker = Worker(); val reader = reader(worker)
        val store = HomeSourceSettingsStore(OwnerFixtureStore(), reader::cachedStatus).also { it.attachReader(reader) }
        val factory = OwnerFixtureFactory(); val owner = HelmSourceOwner(store, factory)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250))
        assertEquals(HomeGatewayReadReason.TIMEOUT.name, owner.recoveryStatus.reason)
        worker.reject = true // process worker/one queue both still occupied; no replacement worker
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(2_000))
        assertEquals(1, worker.tasks.size)
        assertEquals(HomeGatewayReadReason.WORKER_BUSY.name, owner.recoveryStatus.reason)
        assertNotNull(owner.recoveryStatus.nextRetryUptimeMs)
        owner.close()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(1))
        assertEquals(1, worker.tasks.size); assertNull(owner.recoveryStatus.nextRetryUptimeMs)
        worker.run(0); deliver(); assertEquals(1, factory.creates)
    }
}

/** Shared synthetic profile fixture for the real UI-to-owner retry regression. */
internal fun homeGatewayReaderFixtureProfile(endpoint: String) = GatewaySecurityProfile.create(
    com.no5.helm.security.GatewayOrigin.fromEndpoint(endpoint),
    "synthetic-home-reader-key-0001".toByteArray(), java.util.Base64.getDecoder().decode(TEST_CA_DER_B64))

private const val TEST_CA_DER_B64 = "MIIDMTCCAhmgAwIBAgIUag3f7ebUa/5EvO02d4UzypMGoxEwDQYJKoZIhvcNAQELBQAwIDEeMBwGA1UEAwwVTm81IFN5bnRoZXRpYyBUZXN0IENBMB4XDTI2MTAwNjE4NTQ0MVoXDTM2MTAwMzE4NTQ0MVowIDEeMBwGA1UEAwwVTm81IFN5bnRoZXRpYyBUZXN0IENBMIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEApg6XuRxGC8OFl0tS7Y+KuyFLCWogsJImp7oaCXJ7GPSgsAKxHwBKYwoti5SL/KwUCTcCyPhfxtYR/U//VWHwTqACA3hq+Inu7VpvtpD5wXIWCHae3lf8OOK06tnJbaBKGuFxqDMxiEc9YdU+U4L97OGDVAQrEdP7GNgtPFBFUtdnTTQNzKBmUzGWnfqchV49lsP7zlf/EDPey7lCuhQr0cP/6Z1B/HHNTZkNumZUTk+If10UNI6/VCYbpdmNfDd8Gi4okss4dFo5SImJOlg8c8iPDOAuQuRXAMVT6qNCVlmzluKz96elrhlIm8/o8t/oWMu2/NfJCecjVUV/lmG1HwIDAQABo2MwYTAdBgNVHQ4EFgQUj1AePpDCOjiC4tlfDi8Xp+lxha8wHwYDVR0jBBgwFoAUj1AePpDCOjiC4tlfDi8Xp+lxha8wDwYDVR0TAQH/BAUwAwEB/zAOBgNVHQ8BAf8EBAMCAQYwDQYJKoZIhvcNAQELBQADggEBAF25dzBoDFQ7cHmLUbYSKfMquPTKD9eQmTSH2J7nrlV7tgTQJp/eNuR7NYR/3mpA9GJZdEKXs0TtswcoQbsmQeZiIm4N+GCATZDa7I3doVp5JWqyfe/RpZY3x5fDk3Ozr8bAjMdzA5g1EP6eavPw2zDgKpmG3MeqmmVAXTGXpiP21hlu6ffdmTrBEzP9/p/zkse/HInA7NqddzOTpTTWVIpCjVRwdbLVn6lHuBETI0ZrfrIjXEQ1mMZD7MESp2odGF3eGukTuCFdAp4TvC4NWc7+DxQUR24Co26AV8ikWCKYA2NXrjwt++qmzrh+KetewQhykUvMx2TWnvZq3xQdwto="
