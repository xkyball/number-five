package com.no5.helm.control

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.no5.domain.*
import com.no5.gateway.*
import com.no5.helm.AudioNativeController
import com.no5.helm.AndroidSourceSettingsStore
import com.no5.helm.SourcePreferencesBackend
import com.no5.helm.HomeSourceSettingsStore
import com.no5.helm.SonarNativeController
import com.no5.helm.SourceSessionViewModel
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.*
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import android.os.Looper
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HelmSourceOwnerTest {
    @After fun clearRegistry() = HelmSourceRegistry.resetForTest()
    private fun owner(store: SourceSettingsStore = OwnerFixtureStore(), factory: OwnerFixtureFactory = OwnerFixtureFactory()) =
        HelmSourceOwner(store, factory)

    @Test fun screensAndIndependentAudioLeaseShareOneTransportAndDoNotOwnItsClose() {
        val factory = OwnerFixtureFactory(); val owner = owner(factory = factory)
        val screen = owner.borrowSession(); val background = owner.borrowAudio()
        val audio = AudioNativeController(Dispatchers.Unconfined, { 10_000 }, false, false, screen.audio)
        val sonar = SonarNativeController(screen.sonar) { 10_000 }
        audio.dispose(); sonar.dispose(); screen.close(); screen.close()
        assertEquals(1, factory.creates); assertEquals(0, factory.sessionCloses)
        assertEquals(0, factory.factoryCloses)
        background.demoControls!!.loadFixture(AudioFixture.DEMO)
        assertTrue(background.available(AudioCommand.Transport(TransportAction.PLAY)))
        assertTrue(background.submit(AudioCommand.Transport(TransportAction.PLAY)).accepted)
        assertEquals(1, factory.audioCommands)
        background.close(); assertEquals(0, factory.sessionCloses)
        owner.disposeService(); owner.disposeService()
        assertEquals(1, factory.sessionCloses); assertEquals(1, factory.factoryCloses)
        assertFalse(background.available(AudioCommand.Transport(TransportAction.PLAY)))
    }

    @Test fun portDisposalAllowsNewCompositionLeaseAndCannotReactivateTheOldPort() {
        val factory = OwnerFixtureFactory(); val owner = owner(factory = factory)
        val session = owner.borrowSession(); val old = session.audio
        old.demoControls!!.loadFixture(AudioFixture.DEMO); old.close()
        val next = session.audio
        assertNotSame(old, next)
        old.close(); assertSame(next, session.audio)
        assertFalse(old.available(AudioCommand.Transport(TransportAction.PLAY)))
        assertFalse(old.submit(AudioCommand.Transport(TransportAction.PLAY)).accepted)
        assertTrue(next.available(AudioCommand.Transport(TransportAction.PLAY)))
        assertEquals(0, factory.audioCommands); assertEquals(0, factory.sessionCloses)
        owner.close()
    }

    @Test fun closingConsumerUnsubscribesItsFeedsWhileAnotherConsumerStillReceivesSameAdapter() {
        val factory = OwnerFixtureFactory(); val owner = owner(factory = factory)
        val first = owner.borrowAudio(); val second = owner.borrowAudio()
        var oldCallbacks = 0; var liveCallbacks = 0
        first.state.subscribe { oldCallbacks++ }; second.state.subscribe { liveCallbacks++ }
        first.close(); val oldCount = oldCallbacks; val liveCount = liveCallbacks
        second.demoControls!!.loadFixture(AudioFixture.DEMO)
        assertEquals(oldCount, oldCallbacks); assertTrue(liveCallbacks > liveCount)
        assertEquals(1, factory.creates); assertEquals(0, factory.sessionCloses)
        owner.close()
    }

    @Test fun unitsOnlyPreserveSessionButConnectionChangeRetiresLeasesAndOldSubscriptions() {
        val store = OwnerFixtureStore(); val factory = OwnerFixtureFactory(); val owner = owner(store, factory)
        val lease = owner.borrowSession(); val old = lease.audio
        var callbacks = 0
        old.state.subscribe { callbacks++ }
        val generation = owner.sessionGeneration
        assertTrue(owner.apply(owner.config.copy(displayUnits = DisplayUnits(depth = DepthUnit.FEET))).applied)
        assertEquals(generation, owner.sessionGeneration); assertEquals(1, factory.creates)
        assertTrue(owner.apply(owner.config.copy(endpoint = "https://next.local:9443")).applied)
        assertTrue(owner.sessionGeneration > generation)
        assertEquals(2, factory.creates); assertEquals(1, factory.sessionCloses)
        val before = callbacks
        factory.lastAudio!!.demoControls!!.loadFixture(AudioFixture.DEMO)
        assertEquals(before, callbacks)
        assertFalse(old.submit(AudioCommand.Transport(TransportAction.PLAY)).accepted)
        assertEquals(0, factory.audioCommands)
        owner.close(); assertEquals(2, factory.sessionCloses)
    }

    @Test fun failedSaveClosesOnlyCandidateAndRetainsActiveLeaseAndGeneration() {
        val store = OwnerFixtureStore(); val factory = OwnerFixtureFactory(); val owner = owner(store, factory)
        val active = owner.borrowAudio(); active.demoControls!!.loadFixture(AudioFixture.DEMO)
        val generation = owner.sessionGeneration; val original = owner.config
        store.failSave = true
        assertFalse(owner.apply(original.copy(endpoint = "https://next.local:9443")).applied)
        assertEquals(original, owner.config); assertEquals(generation, owner.sessionGeneration)
        assertEquals(2, factory.creates); assertEquals(1, factory.sessionCloses)
        assertTrue(active.available(AudioCommand.Transport(TransportAction.PLAY)))
        owner.close(); assertEquals(2, factory.sessionCloses); assertEquals(1, factory.factoryCloses)
    }

    @Test fun reloadFailureRetiresGenerationAndOldCommandsUntilSuccessfulRetry() {
        val factory = OwnerFixtureFactory(); val owner = owner(factory = factory)
        val active = owner.borrowAudio(); active.demoControls!!.loadFixture(AudioFixture.DEMO)
        val generation = owner.sessionGeneration
        factory.failCreate = true
        assertFalse(owner.reload().applied); assertTrue(owner.sessionGeneration > generation)
        assertFalse(active.available(AudioCommand.Transport(TransportAction.PLAY)))
        assertFalse(active.submit(AudioCommand.Transport(TransportAction.PLAY)).accepted)
        assertEquals(0, factory.audioCommands); assertEquals(1, factory.sessionCloses)
        factory.failCreate = false
        assertTrue(owner.reload().applied)
        owner.close(); assertEquals(2, factory.sessionCloses)
    }

    @Test fun duplicateHomeNotificationsDoNotReloadTwiceButSecurityMaterialDoesReload() {
        val record = GatewayConfiguration(GatewayOrigin.parse("https://no5-marineos-lab.local:9443")!!,
            "192.168.68.88", GatewayAddressSource.MANUAL, checkedAt = 1)
        var status = HomeGatewayStatus(true, record, 1, true, true)
        val factory = OwnerFixtureFactory()
        val owner = owner(HomeSourceSettingsStore(OwnerFixtureStore(), { status }), factory)
        assertTrue(owner.synchronizeHome()); assertEquals(1, factory.creates)
        status = status.copy(securityRevision = 2)
        assertTrue(owner.synchronizeHome()); assertEquals(2, factory.creates)
        assertTrue(owner.synchronizeHome()); assertEquals(2, factory.creates)
        status = status.copy(configuration = record.copy(checkedAt = 500))
        assertTrue(owner.synchronizeHome()); assertEquals(2, factory.creates)
        owner.close()
    }

    @Test @LooperMode(LooperMode.Mode.PAUSED)
    fun failedReloadWithSameHomeIdentityAutomaticallyRebuildsWithoutUiAndNeverReplaysOldCommand() {
        val record = GatewayConfiguration(GatewayOrigin.parse("https://no5-marineos-lab.local:9443")!!,
            "192.168.68.88", GatewayAddressSource.MANUAL, checkedAt = 1)
        val status = HomeGatewayStatus(true, record, 1, true, true)
        val factory = OwnerFixtureFactory()
        val owner = owner(HomeSourceSettingsStore(OwnerFixtureStore(), { status }), factory)
        val old = owner.borrowAudio(); old.demoControls!!.loadFixture(AudioFixture.DEMO)
        val identity = owner.homeIdentity; val initialGeneration = owner.sessionGeneration
        try {
            factory.failCreate = true
            assertFalse(owner.reload().applied)
            assertEquals(identity, owner.homeIdentity); assertTrue(owner.sessionGeneration > initialGeneration)
            assertFalse(owner.recoveryStatus.sourceAccepted); assertNotNull(owner.recoveryStatus.nextRetryUptimeMs)
            assertFalse(old.submit(AudioCommand.Transport(TransportAction.PLAY)).accepted)
            assertEquals(0, factory.audioCommands)
            factory.failCreate = false
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(2_000))
            assertEquals(identity, owner.homeIdentity); assertTrue(owner.recoveryStatus.sourceAccepted)
            assertEquals(2, factory.creates); assertNull(owner.recoveryStatus.nextRetryUptimeMs)
            assertFalse(old.submit(AudioCommand.Transport(TransportAction.PLAY)).accepted)
            assertEquals(0, factory.audioCommands)
            val generation = owner.sessionGeneration
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(31))
            assertEquals(generation, owner.sessionGeneration); assertEquals(2, factory.creates)
        } finally { old.close(); owner.close() }
    }

    @Test @LooperMode(LooperMode.Mode.PAUSED)
    fun corruptLocalRecordWithValidDifferentHomeOriginNeverAutosavesOrStartsNetwork() {
        var saves = 0; var enabledCreates = 0
        val backend = object : SourcePreferencesBackend {
            var record = "synthetic damaged local record"
            override fun read() = record
            override fun write(value: String): Boolean { saves++; record = value; return true }
        }
        val local = AndroidSourceSettingsStore(backend)
        val record = GatewayConfiguration(GatewayOrigin.parse("https://no5-marineos-lab.local:9443")!!,
            "192.168.68.88", GatewayAddressSource.MANUAL, checkedAt = 1)
        val home = HomeSourceSettingsStore(local, { HomeGatewayStatus(true, record, 1, true, true) },
            localWarning = { local.warning })
        val fixture = OwnerFixtureFactory()
        val factory = SourceSessionFactory { config, identity ->
            if (config.connectionEnabled) enabledCreates++
            fixture.create(config, identity)
        }
        val owner = HelmSourceOwner(home, factory)
        try {
            repeat(4) { assertFalse(owner.synchronizeHome()) }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(1))
            assertEquals(0, saves); assertEquals(0, enabledCreates)
            assertEquals("LOCAL_SETTINGS_BLOCKED", owner.recoveryStatus.reason)
            assertFalse(owner.config.connectionEnabled); assertEquals(local.warning, owner.settingsWarning)
            assertEquals("synthetic damaged local record", backend.record)
            assertNull(owner.recoveryStatus.nextRetryUptimeMs)
            assertTrue(owner.apply(owner.config).applied) // explicit user repair owns this save
            assertEquals(1, saves)
            assertTrue(owner.synchronizeHome()); assertTrue(owner.config.connectionEnabled)
            assertEquals(1, enabledCreates)
        } finally { owner.close() }
    }

    @Test @LooperMode(LooperMode.Mode.PAUSED)
    fun automaticRetirementCloseFailureIsContainedBeforeLocalBlockOrApplyRetry() {
        for (localBlock in listOf(true, false)) {
            val record = GatewayConfiguration(GatewayOrigin.parse("https://no5-marineos-lab.local:9443")!!,
                "192.168.68.88", GatewayAddressSource.MANUAL, checkedAt = 1)
            var status = HomeGatewayStatus(true, record, 1, true, true)
            var blocked = false; var closeFails = true; var closeCalls = 0
            val fixture = OwnerFixtureFactory()
            val factory = SourceSessionFactory { config, identity ->
                val raw = fixture.create(config, identity)
                object : No5SourceSession by raw {
                    override fun close() { closeCalls++; check(!closeFails) { "synthetic close failure" }; raw.close() }
                }
            }
            val owner = HelmSourceOwner(HomeSourceSettingsStore(OwnerFixtureStore(), { status },
                localWarning = { if (blocked) "synthetic local block" else null }), factory)
            val lease = owner.borrowAudio(); val generation = owner.sessionGeneration
            lease.demoControls!!.loadFixture(AudioFixture.DEMO)
            assertTrue(lease.available(AudioCommand.Transport(TransportAction.PLAY)))
            try {
                if (localBlock) blocked = true else {
                    status = status.copy(configuration = record.copy(origin = GatewayOrigin.parse("https://next.local:9443")!!))
                    fixture.failCreate = true
                }
                assertFalse(owner.synchronizeHome(requestRefresh = false))
                assertTrue(owner.sessionGeneration > generation); assertFalse(owner.recoveryStatus.sourceAccepted)
                assertEquals("SESSION_CLOSE_FAILED", owner.recoveryStatus.reason)
                assertEquals(1, closeCalls)
                assertFalse(lease.submit(AudioCommand.Transport(TransportAction.PLAY)).accepted)
                // changes can cause an immediate UI/knob reborrow while raw.close is unknown.
                val fresh = owner.borrowAudio()
                try {
                    assertFalse(fresh.available(AudioCommand.Transport(TransportAction.PLAY)))
                    assertFalse(fresh.submit(AudioCommand.Transport(TransportAction.PLAY)).accepted)
                    assertEquals(0, fixture.audioCommands)
                } finally { fresh.close() }
                if (localBlock) assertNull(owner.recoveryStatus.nextRetryUptimeMs) else {
                    assertNotNull(owner.recoveryStatus.nextRetryUptimeMs)
                    fixture.failCreate = false
                    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(2_000))
                    assertEquals(1, fixture.creates) // unknown old close forbids a second source
                    assertEquals("SESSION_CLOSE_FAILED", owner.recoveryStatus.reason)
                }
            } finally { closeFails = false; lease.close(); owner.close() }
        }
    }

    @Test @LooperMode(LooperMode.Mode.PAUSED)
    fun displayOnlyApplyAfterFailedReloadStillRequiresRawReplacementBeforeAccepting() {
        val record = GatewayConfiguration(GatewayOrigin.parse("https://no5-marineos-lab.local:9443")!!,
            "192.168.68.88", GatewayAddressSource.MANUAL, checkedAt = 1)
        val status = HomeGatewayStatus(true, record, 1, true, true)
        val local = OwnerFixtureStore(); val fixture = OwnerFixtureFactory()
        val owner = HelmSourceOwner(HomeSourceSettingsStore(local, { status }), fixture)
        try {
            val identity = owner.homeIdentity
            fixture.failCreate = true; assertFalse(owner.reload().applied)
            local.saved = local.saved.copy(displayUnits = DisplayUnits(depth = DepthUnit.FEET))
            assertFalse(owner.synchronizeHome(requestRefresh = false))
            assertEquals(DepthUnit.FEET, owner.config.displayUnits.depth)
            assertEquals(identity, owner.homeIdentity); assertFalse(owner.recoveryStatus.sourceAccepted)
            assertEquals(1, fixture.creates); assertNotNull(owner.recoveryStatus.nextRetryUptimeMs)
            fixture.failCreate = false
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(2_000))
            assertTrue(owner.recoveryStatus.sourceAccepted); assertEquals(2, fixture.creates)
            assertNull(owner.recoveryStatus.nextRetryUptimeMs)
        } finally { owner.close() }
    }

    @Test fun productionViewModelClearingReleasesOnlyItsLeaseWhileFixtureConstructorOwnsSession() {
        val app = RuntimeEnvironment.getApplication() as Application
        val factory = OwnerFixtureFactory(); val owner = owner(factory = factory)
        HelmSourceRegistry.installForTest(app, owner)
        val models = ViewModelStore()
        val model = ViewModelProvider(models, ModelFactory { SourceSessionViewModel(app) })[SourceSessionViewModel::class.java]
        val first = model.session; assertSame(first, model.session)
        models.clear(); assertEquals(0, factory.sessionCloses)
        assertEquals(0, factory.factoryCloses)
        owner.disposeService(); assertEquals(1, factory.sessionCloses)
        val isolatedFactory = OwnerFixtureFactory(); val fixtures = ViewModelStore()
        ViewModelProvider(fixtures, ModelFactory { SourceSessionViewModel(app, OwnerFixtureStore(), isolatedFactory) })[SourceSessionViewModel::class.java].session
        fixtures.clear(); assertEquals(1, isolatedFactory.sessionCloses); assertEquals(1, isolatedFactory.factoryCloses)
    }

    @Test fun ownerRejectsOffMainThreadMutationWithoutCreatingAnotherSession() {
        val factory = OwnerFixtureFactory(); val owner = owner(factory = factory)
        var error: Throwable? = null
        Thread { try { owner.borrowAudio() } catch (failure: Throwable) { error = failure } }.apply { start(); join() }
        assertTrue(error is IllegalStateException); assertEquals(1, factory.creates)
        owner.close()
    }

    @Test fun serviceDestroyKeepsThePreacquiredUnavailableUiLeaseWithoutThrowOrWrites() {
        val app = RuntimeEnvironment.getApplication() as Application
        val factory = OwnerFixtureFactory(); val owner = owner(factory = factory)
        HelmSourceRegistry.installForTest(app, owner)
        val models = ViewModelStore()
        val model = ViewModelProvider(models, ModelFactory { SourceSessionViewModel(app) })[SourceSessionViewModel::class.java]
        val session = model.session; val audio = session.audio
        audio.demoControls!!.loadFixture(AudioFixture.DEMO)
        val host = Robolectric.buildService(HelmSourceService::class.java).create()
        host.destroy()
        assertSame(session, model.session)
        assertFalse(audio.available(AudioCommand.Transport(TransportAction.PLAY)))
        assertFalse(audio.submit(AudioCommand.Transport(TransportAction.PLAY)).accepted)
        assertEquals(0, factory.audioCommands); assertEquals(1, factory.creates)
        models.clear(); assertEquals(1, factory.sessionCloses); assertEquals(1, factory.factoryCloses)
    }

    private class ModelFactory(private val create: () -> ViewModel) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>) = create() as T
    }
}

internal class OwnerFixtureStore : SourceSettingsStore {
    var saved = SourceConfig(mode = SourceMode.PI, endpoint = "https://saved.local:9443", connectionEnabled = false,
        weatherProvider = WeatherProviderMode.DISABLED)
    var failSave = false
    override fun load() = saved
    override fun save(config: SourceConfig) { check(!failSave) { "Fixture save failure" }; saved = config }
}

/** Explicit test-fixture adapter only. No network transport, simulator process or real writes. */
internal class OwnerFixtureFactory : SourceSessionFactory, AutoCloseable {
    var creates = 0; var sessionCloses = 0; var factoryCloses = 0; var audioCommands = 0
    var failCreate = false
    var lastAudio: AudioSourcePort? = null
    override fun create(config: SourceConfig, identity: SourceIdentity): No5SourceSession {
        check(!failCreate) { "Fixture create failure" }; creates++
        val delegate = DefaultSourceSessionFactory { 10_000 }.create(config.copy(mode = SourceMode.DEMO), identity.copy(mode = SourceMode.DEMO))
        val audio = object : AudioSourcePort by delegate.audio {
            override fun submit(command: AudioCommand): AudioCommandOutcome { audioCommands++; return delegate.audio.submit(command) }
        }
        lastAudio = audio
        return object : No5SourceSession by delegate {
            override val audio = audio
            private var closed = false
            override fun close() { if (!closed) { closed = true; sessionCloses++; delegate.close() } }
        }
    }
    override fun close() { factoryCloses++ }
}
