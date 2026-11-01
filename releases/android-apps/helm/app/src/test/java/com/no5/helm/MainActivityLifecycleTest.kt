package com.no5.helm

import android.app.Application
import android.content.ContentProvider
import android.content.ComponentName
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.ProviderInfo
import android.content.pm.Signature
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.no5.domain.DefaultSourceSessionFactory
import com.no5.domain.No5SourceSession
import com.no5.domain.SourceConfig
import com.no5.domain.SourceSessionFactory
import com.no5.domain.SourceSettingsStore
import com.no5.gateway.*
import com.no5.helm.ocean.BackgroundSettingsViewModel
import com.no5.helm.security.GatewaySecurityProfile
import com.no5.helm.security.GatewaySecurityProfileProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowBuild
import org.robolectric.shadows.ShadowContentResolver
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-rUS-w2000dp-h1200dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MainActivityLifecycleTest {
    @get:Rule val ui = createComposeRule()
    private var fixtureLanguage: String? = null
    @org.junit.Before fun resetApplicationFactory() {
        resetAndroidViewModelApplicationFixture()
        val app=RuntimeEnvironment.getApplication()
        fixtureLanguage=com.no5.ui.UiLanguage.current(app)
        com.no5.ui.UiLanguage.acceptConfirmed(app, "en")
    }
    @org.junit.After fun restoreFixtureLanguage() {
        fixtureLanguage?.let { com.no5.ui.UiLanguage.acceptConfirmed(RuntimeEnvironment.getApplication(), it) }
    }
    @Test @Config(sdk=[29],qualifiers="w1000dp-h600dp-land-xhdpi")
    @Suppress("DEPRECATION")
    fun luminaDensityFullscreenAndHomeOwnedEndpointSurviveResumeWithoutSourceChange() {
        val config=SourceConfig(endpoint="https://saved.local:9443",connectionEnabled=false)
        Robolectric.buildActivity(MainActivity::class.java).use { host ->
            // Seed the attached Activity's actual Application before onCreate.
            val store=AndroidSourceSettingsStore(host.get().application)
            store.save(config)
            assertEquals(config.endpoint,store.load().endpoint)
            host.setup()
            host.windowFocusChanged(true)
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            val first=host.get().sourceSessions.session
            fun checkClaim() {
                val activity=host.get()
                assertTrue(activity.window.attributes.flags and android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN!=0)
                assertEquals(0,activity.window.attributes.flags and android.view.WindowManager.LayoutParams.FLAG_FORCE_NOT_FULLSCREEN)
                assertEquals(0x1706,activity.window.decorView.systemUiVisibility and 0x1706)
                assertEquals(config.endpoint,activity.sourceSessions.config.endpoint)
                // Home owns gateway selection; HELM must not revive app-local
                // endpoint suggestions when its Activity resumes.
                assertTrue(activity.sourceSessions.endpointHistory.isEmpty())
                assertSame(first,activity.sourceSessions.session)
                assertEquals(2f,activity.resources.displayMetrics.density,0f)
            }
            checkClaim()
            host.windowFocusChanged(false).pause().stop()
            host.get().window.addFlags(android.view.WindowManager.LayoutParams.FLAG_FORCE_NOT_FULLSCREEN)
            host.get().window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN)
            host.restart().start().resume().windowFocusChanged(true)
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            checkClaim()
        }
    }
    private class Store(var config: SourceConfig) : SourceSettingsStore {
        override fun load() = config
        override fun save(config: SourceConfig) { this.config = config }
    }

    private class CountingFactory : SourceSessionFactory {
        var creates = 0
        var closes = 0
        override fun create(config: SourceConfig, identity: com.no5.domain.SourceIdentity): No5SourceSession {
            creates++
            val delegate = DefaultSourceSessionFactory { 1_000_000L }.create(config, identity)
            return object : No5SourceSession by delegate {
                private var closed = false
                override fun close() {
                    if (!closed) {
                        closed = true
                        closes++
                        delegate.close()
                    }
                }
            }
        }
    }

    private class SourceModelFactory(private val create: () -> SourceSessionViewModel) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = create() as T
    }

    @Test fun sourceSessionViewModelCreatesOneSessionAndClosesItOnlyWhenCleared() {
        val app = RuntimeEnvironment.getApplication() as Application
        val sourceFactory = CountingFactory()
        val store = Store(SourceConfig(connectionEnabled = false))
        val models = ViewModelStore()
        val model = ViewModelProvider(models, SourceModelFactory {
            SourceSessionViewModel(app, store, sourceFactory) { "activity-session" }
        })[SourceSessionViewModel::class.java]
        val first = model.session

        repeat(5) {
            assertTrue(model.apply(model.config).applied)
            assertSame(first, model.session)
        }
        assertTrue(model.apply(model.config.copy(displayUnits = model.config.displayUnits.copy(
            depth = com.no5.domain.DepthUnit.FEET))).changed)
        assertSame(first, model.session)
        assertEquals(1, sourceFactory.creates)
        assertEquals(0, sourceFactory.closes)

        models.clear()
        assertEquals(1, sourceFactory.closes)
    }

    @Test fun repeatedLauncherIntentAndStopStartRetainTheOneActivitySession() {
        val app = RuntimeEnvironment.getApplication()
        AndroidSourceSettingsStore(app).save(SourceConfig(connectionEnabled = false))
        Robolectric.buildActivity(MainActivity::class.java, Intent(Intent.ACTION_MAIN)).setup().use { host ->
            val first = host.get().sourceSessions.session
            repeat(5) { index ->
                host.newIntent(Intent(Intent.ACTION_MAIN).putExtra("repeat", index))
                assertSame(first, host.get().sourceSessions.session)
                assertEquals(index, host.get().intent.getIntExtra("repeat", -1))
                host.pause().stop().restart().start().resume()
                assertSame(first, host.get().sourceSessions.session)
            }
            host.recreate()
            assertSame(first, host.get().sourceSessions.session)
        }
    }

    @Test fun manifestRoutesRepeatedLaunchesToTheExistingMainActivity() {
        val app = RuntimeEnvironment.getApplication()
        val info = app.packageManager.getActivityInfo(ComponentName(app, MainActivity::class.java), 0)
        assertEquals(ActivityInfo.LAUNCH_SINGLE_TASK, info.launchMode)
    }

    /** Real production No5App/Settings/connection-screen callback, with offline IPC fixtures. */
    @Test
    @Config(sdk = [29, 35], qualifiers = "en-rUS-w2000dp-h1200dp-land-mdpi",
        shadows = [NavigationComposeRenderTest.HostPowerManager::class])
    @LooperMode(LooperMode.Mode.PAUSED)
    @Suppress("DEPRECATION")
    fun connectionTestRetriesExpiredOwnerReadWithSameHomeIdentityAndRejectsOldResponses() {
        val app = RuntimeEnvironment.getApplication() as Application
        ShadowBuild.setFingerprint("generic/test")
        ShadowBuild.setHardware("ranchu")
        val record = GatewayConfiguration(GatewayOrigin.parse("https://no5-marineos-lab.local:9443")!!,
            "192.168.68.88", GatewayAddressSource.MANUAL, checkedAt = 1)
        val ready = HomeGatewayStatus(true, record, 1, true, true)
        installRetryHomeProvider(app, ready)
        assertEquals(ready.identity, HomeGatewayClient(app).status().identity)

        val worker = RetryReaderWorker()
        val profiles = mutableListOf<GatewaySecurityProfile>()
        var uptime = 100L
        val phases = java.util.Collections.synchronizedList(mutableListOf<String>())
        lateinit var reader: HomeGatewayStatusReader
        fun trace(phase: String, reason: String = "none") {
            val generation = runCatching {
                HomeGatewayStatusReader::class.java.getDeclaredField("generation").apply {
                    isAccessible = true
                }.getLong(reader)
            }.getOrDefault(-1L)
            val entry = "api=${android.os.Build.VERSION.SDK_INT} phase=$phase " +
                "systemUptime=${android.os.SystemClock.uptimeMillis()} fakeUptime=$uptime " +
                "generation=$generation tasks=${worker.tasks.size} profiles=${profiles.size} reason=$reason"
            synchronized(phases) { if (phases.size < 80) phases.add(entry) }
            println("HOME_RETRY_DIAG $entry")
        }
        reader = HomeGatewayStatusReader({ trace("reader-status"); ready }, { trace("reader-offer") }, GatewaySecurityProfileProvider {
            trace("profile-start")
            try {
                homeGatewayReaderFixtureProfile(record.origin.url).also {
                    profiles.add(it); trace("profile-created")
                }
            } catch (failure: RuntimeException) {
                trace("profile-failed", "profile-create-${failure.javaClass.simpleName}")
                throw failure
            }
        }, Handler(Looper.getMainLooper()), worker, uptime = { uptime }, deadlineMs = 250L)
        worker.onEvent = { phase -> trace(phase) }
        val store = HomeSourceSettingsStore(Store(SourceConfig(endpoint = record.origin.url,
            connectionEnabled = true, weatherProvider = com.no5.domain.WeatherProviderMode.DISABLED)),
            reader::cachedStatus).also { it.attachReader(reader) }
        val factory = CountingFactory() // No network/connected fixture; assert only session replacement.
        val models = ViewModelStore()
        val model = ViewModelProvider(models, SourceModelFactory {
            SourceSessionViewModel(app, store, factory)
        })[SourceSessionViewModel::class.java]
        val showApp = mutableStateOf(true)
        val backgroundModel = BackgroundSettingsViewModel(app)
        var primaryFailure: Throwable? = null
        try {
            val disabled = model.session
            trace("owner-initial-disabled")
            assertFalse(model.config.connectionEnabled)
            ui.mainClock.autoAdvance = false
            ui.setContent {
                if (showApp.value) Box(Modifier.size(2000.dp, 1200.dp)) { No5App(model, backgroundModel) }
            }
            fun settle() { ui.waitForIdle(); ui.mainClock.advanceTimeBy(64); ui.waitForIdle() }
            settle()
            ui.onNodeWithContentDescription("SETTINGS").performClick(); settle()
            ui.onNodeWithTag("open-gateway-connection").performScrollTo().performClick(); settle()
            ui.waitUntil(3_000) {
                ui.mainClock.advanceTimeByFrame()
                ui.onAllNodesWithTag("gateway-test").fetchSemanticsNodes().singleOrNull()?.let {
                    !it.config.contains(SemanticsProperties.Disabled)
                } == true
            }
            ui.onNodeWithTag("gateway-test").assertIsEnabled()
            trace("screen-test-enabled")
            ui.runOnIdle {
                trace("initial-timeout-before")
                uptime += 250L
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250L))
                trace("initial-timeout-after")
                assertFalse(model.config.connectionEnabled)
                assertSame(disabled, model.session)
                assertEquals(1, factory.creates)
            }
            val oldRequests = worker.tasks.size
            assertTrue(oldRequests > 0)
            // Before the automatic timer is due, the existing Test button retries immediately.
            val testButton = ui.onNodeWithTag("gateway-test").fetchSemanticsNode()
            assertFalse(testButton.config.contains(SemanticsProperties.Disabled))
            val clickTest = requireNotNull(testButton.config[SemanticsActions.OnClick].action)
            ui.runOnIdle {
                trace("retry-block-start")
                // Invoke this production button's exact semantic action once. No frame settling
                // may advance the Handler past its genuine 250-ms deadline before the reply.
                assertTrue(clickTest())
                trace("after-button-action")
                assertEquals(oldRequests + 1, worker.tasks.size)
                assertFalse(model.config.connectionEnabled) // enqueue is not a fresh-read success
                assertEquals(1, factory.creates)
                worker.run(oldRequests)
                trace("retry-worker-replied")
                shadowOf(Looper.getMainLooper()).idle()
                trace("retry-main-delivered")
                assertTrue("Refresh refused: ownerIdentity=${model.homeIdentity}, readerStatus=" +
                    runCatching { reader.cachedStatus().identity }.getOrDefault("unavailable") +
                    "; phases=" + synchronized(phases) { phases.joinToString("; ") },
                    model.config.connectionEnabled)
                assertEquals(ready.identity, model.homeIdentity)
                assertNotSame(disabled, model.session)
                assertEquals(2, factory.creates)
                assertEquals(1, factory.closes)
            }
            val active = model.session
            repeat(oldRequests) { index ->
                worker.run(index)
                ui.runOnIdle { shadowOf(Looper.getMainLooper()).idle() }
            }
            ui.runOnIdle {
                assertEquals(ready.identity, HomeGatewayClient(app).status().identity)
                assertTrue(model.config.connectionEnabled)
                assertSame(active, model.session)
                assertEquals(2, factory.creates)
                assertEquals(1, factory.closes)
                // First is the accepted retry; every later profile came from a retired request.
                profiles.drop(1).forEach { profile ->
                    try {
                        profile.configureHttps("${record.origin.url}/v1/helm", {}, { _, _ -> })
                        org.junit.Assert.fail("Retired Home response retained its profile")
                    } catch (_: IllegalStateException) { }
                }
            }
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            try {
                ui.runOnIdle { showApp.value = false }
                // With autoAdvance=false, idle alone does not dispose the composition's observers.
                ui.mainClock.advanceTimeBy(64)
                ui.waitForIdle()
                ui.runOnIdle { models.clear() }
            } catch (cleanupFailure: Throwable) {
                val original = primaryFailure
                if (original == null) throw cleanupFailure
                original.addSuppressed(cleanupFailure)
            }
        }
    }

    private class RetryReaderWorker : HomeGatewayStatusReader.Worker {
        val tasks = mutableListOf<Runnable>()
        var onEvent: (String) -> Unit = { }
        override fun execute(task: Runnable): HomeGatewayStatusReader.Cancellation {
            tasks.add(task)
            val index = tasks.lastIndex
            onEvent("worker-enqueue-$index")
            // Preserve the fixture's semantics: already-running IPC may ignore cancellation.
            return HomeGatewayStatusReader.Cancellation { onEvent("worker-cancel-$index") }
        }
        fun run(index: Int) {
            onEvent("worker-run-$index")
            val failure = AtomicReference<Throwable?>()
            val thread = Thread { try { tasks[index].run() } catch (t: Throwable) { failure.set(t) } }
            thread.start(); thread.join(3_000)
            check(!thread.isAlive) { "Retry fixture worker did not finish" }
            failure.get()?.let { throw AssertionError("Retry fixture worker failed", it) }
            onEvent("worker-return-$index")
        }
    }

    @Suppress("DEPRECATION")
    private fun installRetryHomeProvider(app: Application, status: HomeGatewayStatus) {
        val pm = shadowOf(app.packageManager)
        val signature = Signature(byteArrayOf(1, 2, 3, 4))
        pm.installPackage(app.packageManager.getPackageInfo(app.packageName, PackageManager.GET_SIGNATURES).apply {
            signatures = arrayOf(signature)
        })
        val provider = ProviderInfo().apply {
            packageName = "com.no5.home"; name = RetryHomeProvider::class.java.name
            authority = HomeGatewayContract.AUTHORITY; exported = true
            applicationInfo = ApplicationInfo().apply { packageName = "com.no5.home"; enabled = true }
        }
        pm.installPackage(PackageInfo().apply {
            packageName = "com.no5.home"; signatures = arrayOf(signature)
            applicationInfo = provider.applicationInfo; providers = arrayOf(provider)
        })
        val instance = RetryHomeProvider(status).also { it.attachInfo(app, provider) }
        ShadowContentResolver.registerProviderInternal(HomeGatewayContract.AUTHORITY, instance)
    }

    /** Status-ready provider, but diagnostics get no profile and therefore make no network calls. */
    private class RetryHomeProvider(private val status: HomeGatewayStatus) : ContentProvider() {
        override fun onCreate() = true
        override fun call(method: String, arg: String?, extras: Bundle?): Bundle = Bundle().apply {
            if (method == HomeGatewayContract.READ) {
                putString(HomeGatewayContract.CONFIGURATION, GatewayConfigurationCodec.encode(requireNotNull(status.configuration)))
                putLong(HomeGatewayContract.REVISION, status.securityRevision)
                putBoolean(HomeGatewayContract.KEY_PRESENT, status.keyPresent)
                putBoolean(HomeGatewayContract.CA_PRESENT, status.caPresent)
            }
        }
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
            selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
    }
}
